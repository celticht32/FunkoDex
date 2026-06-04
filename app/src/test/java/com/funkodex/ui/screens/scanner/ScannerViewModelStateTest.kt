package com.funkodex.ui.screens.scanner

import com.funkodex.data.model.FunkoItem
import com.funkodex.data.model.PendingUpcScan
import com.funkodex.data.repository.ContributionRepository
import com.funkodex.data.repository.FunkoRepository
import com.funkodex.data.repository.ImageBlobRepository
import com.funkodex.network.ConnectivityObserver
import com.funkodex.network.FunkoLookupService
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * ScannerViewModelStateTest — F-QA-1
 *
 * Tests all state machine transitions in ScannerViewModel using Mockk fakes.
 * No Couchbase Lite or Android instrumentation required — pure JVM test.
 *
 * State machine under test:
 *   Idle → Scanning → LookingUp → Preview / AlreadyOwned / NotFound / Pending / Error
 *   Preview → Saved (via confirmAdd)
 *   NotFound → Preview (via selectNotFoundMatch)
 *   ManualSearch → Preview (via selectManualResult)
 *   Any → Scanning (via dismissPreview)
 *   Any → Idle (via reset)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScannerViewModelStateTest {

    private val testDispatcher = StandardTestDispatcher()

    @MockK lateinit var repository:   FunkoRepository
    @MockK lateinit var lookup:       FunkoLookupService
    @MockK lateinit var imageBlobs:   ImageBlobRepository
    @MockK lateinit var contribRepo:  ContributionRepository
    @MockK lateinit var connectivity: ConnectivityObserver

    private lateinit var vm: ScannerViewModel

    private val sampleItem = FunkoItem(
        id        = "funko::012345678901",
        upc       = "012345678901",
        name      = "Batman (1989)",
        franchise = "DC Comics",
    )

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        // Safe defaults — no-ops unless overridden in a test
        coEvery { repository.getItemByUpc(any()) } returns null
        coEvery { repository.saveItem(any()) }      returns Result.success(sampleItem)
        coEvery { repository.savePendingUpc(any()) } just Awaits
        coEvery { lookup.lookupByUpc(any()) }        returns null
        coEvery { lookup.searchByName(any()) }       returns emptyList()
        coEvery { imageBlobs.downloadAndStore(any()) } just Awaits
        coEvery { contribRepo.saveContribution(any()) } just Awaits
        every  { connectivity.isConnected() }        returns true

        vm = ScannerViewModel(repository, lookup, imageBlobs, contribRepo, connectivity)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ─── Initial state ────────────────────────────────────────────────────────

    @Test
    fun `initial state is Idle`() {
        assertEquals(ScanState.Idle, vm.state.value)
    }

    // ─── startScanning / reset ────────────────────────────────────────────────

    @Test
    fun `startScanning transitions from Idle to Scanning`() {
        vm.startScanning()
        assertEquals(ScanState.Scanning, vm.state.value)
    }

    @Test
    fun `reset from Scanning returns to Idle`() {
        vm.startScanning()
        vm.reset()
        assertEquals(ScanState.Idle, vm.state.value)
    }

    @Test
    fun `reset clears lastScannedUpc so same UPC can be scanned again`() = runTest {
        coEvery { lookup.lookupByUpc("012345678901") } returns sampleItem
        vm.startScanning()
        vm.onBarcodeDetected("012345678901")
        advanceUntilIdle()
        assertEquals(ScanState.Preview::class, vm.state.value::class)

        vm.reset()
        // After reset, same UPC should trigger a new lookup
        vm.startScanning()
        vm.onBarcodeDetected("012345678901")
        advanceUntilIdle()
        assertEquals(ScanState.Preview::class, vm.state.value::class)
        coVerify(exactly = 2) { lookup.lookupByUpc("012345678901") }
    }

    // ─── onBarcodeDetected → Preview ─────────────────────────────────────────

    @Test
    fun `barcode detected with network hit transitions to Preview`() = runTest {
        coEvery { lookup.lookupByUpc("012345678901") } returns sampleItem
        vm.startScanning()
        vm.onBarcodeDetected("012345678901")
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue("Expected Preview, got $state", state is ScanState.Preview)
        assertEquals(sampleItem, (state as ScanState.Preview).item)
    }

    @Test
    fun `barcode detected goes through LookingUp before Preview`() = runTest {
        // Set up a slow lookup to capture intermediate state
        var capturedIntermediate: ScanState? = null
        coEvery { lookup.lookupByUpc(any()) } coAnswers {
            capturedIntermediate = vm.state.value
            sampleItem
        }
        vm.startScanning()
        vm.onBarcodeDetected("012345678901")
        advanceUntilIdle()

        assertEquals(ScanState.LookingUp, capturedIntermediate)
    }

    // ─── onBarcodeDetected → AlreadyOwned ────────────────────────────────────

    @Test
    fun `barcode for already-owned item shows AlreadyOwned`() = runTest {
        val owned = sampleItem.copy(isOwned = true)
        coEvery { repository.getItemByUpc("012345678901") } returns owned

        vm.startScanning()
        vm.onBarcodeDetected("012345678901")
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue("Expected AlreadyOwned, got $state", state is ScanState.AlreadyOwned)
        assertEquals(owned, (state as ScanState.AlreadyOwned).item)
    }

    @Test
    fun `barcode for wanted item shows Preview (not AlreadyOwned)`() = runTest {
        val wanted = sampleItem.copy(isOwned = false)
        coEvery { repository.getItemByUpc("012345678901") } returns wanted

        vm.startScanning()
        vm.onBarcodeDetected("012345678901")
        advanceUntilIdle()

        assertTrue(vm.state.value is ScanState.Preview)
    }

    // ─── onBarcodeDetected → NotFound / Pending ───────────────────────────────

    @Test
    fun `unknown UPC with network shows NotFound`() = runTest {
        coEvery { lookup.lookupByUpc(any()) } returns null
        every { connectivity.isConnected() }  returns true

        vm.startScanning()
        vm.onBarcodeDetected("012345678901")
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue("Expected NotFound, got $state", state is ScanState.NotFound)
        assertEquals("012345678901", (state as ScanState.NotFound).upc)
    }

    @Test
    fun `unknown UPC without network queues Pending and saves to repo`() = runTest {
        coEvery { lookup.lookupByUpc(any()) } returns null
        every { connectivity.isConnected() }  returns false

        vm.startScanning()
        vm.onBarcodeDetected("012345678901")
        advanceUntilIdle()

        assertTrue(vm.state.value is ScanState.Pending)
        coVerify { repository.savePendingUpc(PendingUpcScan(upc = "012345678901")) }
    }

    // ─── Duplicate suppression ─────────────────────────────────────────────────

    @Test
    fun `duplicate UPC while LookingUp is ignored`() = runTest {
        coEvery { lookup.lookupByUpc(any()) } returns sampleItem

        vm.startScanning()
        vm.onBarcodeDetected("012345678901")
        vm.onBarcodeDetected("012345678901") // duplicate — should be ignored
        advanceUntilIdle()

        coVerify(exactly = 1) { lookup.lookupByUpc("012345678901") }
    }

    // ─── dismissPreview ────────────────────────────────────────────────────────

    @Test
    fun `dismissPreview resets lastScannedUpc and returns to Scanning`() = runTest {
        coEvery { lookup.lookupByUpc(any()) } returns sampleItem
        vm.startScanning()
        vm.onBarcodeDetected("012345678901")
        advanceUntilIdle()
        assertTrue(vm.state.value is ScanState.Preview)

        vm.dismissPreview()
        assertEquals(ScanState.Scanning, vm.state.value)

        // Same UPC should now trigger a new lookup (lastScannedUpc was cleared)
        vm.onBarcodeDetected("012345678901")
        advanceUntilIdle()
        coVerify(exactly = 2) { lookup.lookupByUpc("012345678901") }
    }

    // ─── confirmAdd ────────────────────────────────────────────────────────────

    @Test
    fun `confirmAdd transitions to Saved`() = runTest {
        coEvery { lookup.lookupByUpc(any()) } returns sampleItem
        vm.startScanning()
        vm.onBarcodeDetected("012345678901")
        advanceUntilIdle()

        vm.confirmAdd(sampleItem, pricePaid = 14.99)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue("Expected Saved, got $state", state is ScanState.Saved)
    }

    @Test
    fun `confirmAdd with want list flag saves isOwned=false`() = runTest {
        vm.startScanning()
        vm.confirmAdd(sampleItem, pricePaid = 0.0, addToWantList = true)
        advanceUntilIdle()

        coVerify {
            repository.saveItem(match { !it.isOwned })
        }
    }

    @Test
    fun `save failure transitions to Error`() = runTest {
        coEvery { repository.saveItem(any()) } returns Result.failure(RuntimeException("DB write failed"))
        vm.startScanning()
        vm.confirmAdd(sampleItem, pricePaid = 14.99)
        advanceUntilIdle()

        assertTrue(vm.state.value is ScanState.Error)
    }

    // ─── NotFound → Preview via selectNotFoundMatch ───────────────────────────

    @Test
    fun `selectNotFoundMatch transitions NotFound to Preview with enriched item`() = runTest {
        coEvery { lookup.lookupByUpc(any()) } returns null
        every { connectivity.isConnected() }  returns true

        vm.startScanning()
        vm.onBarcodeDetected("012345678901")
        advanceUntilIdle()
        assertTrue(vm.state.value is ScanState.NotFound)

        vm.selectNotFoundMatch(sampleItem, "012345678901")

        val state = vm.state.value
        assertTrue(state is ScanState.Preview)
        assertEquals("012345678901", (state as ScanState.Preview).item.upc)
    }

    @Test
    fun `selectNotFoundMatch queues community contribution`() = runTest {
        coEvery { lookup.lookupByUpc(any()) } returns null
        every { connectivity.isConnected() }  returns true

        vm.startScanning()
        vm.onBarcodeDetected("012345678901")
        advanceUntilIdle()
        vm.selectNotFoundMatch(sampleItem, "012345678901")
        advanceUntilIdle()

        coVerify { contribRepo.saveContribution(any()) }
    }

    // ─── Manual search ─────────────────────────────────────────────────────────

    @Test
    fun `openManualSearch transitions to ManualSearch`() {
        vm.openManualSearch()
        assertTrue(vm.state.value is ScanState.ManualSearch)
    }

    @Test
    fun `submitManualSearch populates results`() = runTest {
        coEvery { lookup.searchByName("Batman") } returns listOf(sampleItem)
        vm.openManualSearch()
        vm.submitManualSearch("Batman")
        advanceUntilIdle()

        val state = vm.state.value as? ScanState.ManualSearch
        assertNotNull(state)
        assertEquals(1, state!!.results.size)
        assertEquals("Batman (1989)", state.results[0].name)
    }

    @Test
    fun `selectManualResult transitions to Preview`() = runTest {
        vm.openManualSearch()
        vm.selectManualResult(sampleItem)
        assertTrue(vm.state.value is ScanState.Preview)
    }
}
