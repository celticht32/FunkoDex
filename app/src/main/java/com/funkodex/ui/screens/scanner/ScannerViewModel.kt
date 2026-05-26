package com.funkodex.ui.screens.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.funkodex.data.model.FunkoItem
import com.funkodex.data.model.PendingUpcScan
import com.funkodex.data.repository.FunkoRepository
import com.funkodex.data.repository.ImageBlobRepository
import com.funkodex.data.repository.ContributionRepository
import com.funkodex.data.model.CatalogContribution
import com.funkodex.network.FunkoLookupService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * States for the scanner screen:
 *
 *  Idle          — camera not yet started
 *  Scanning      — camera live, waiting for barcode
 *  LookingUp     — UPC detected, network lookup in progress
 *  AlreadyOwned  — UPC found in user's collection (A4 duplicate detection)
 *  Preview       — lookup succeeded, show item card for confirmation
 *  NotFound      — UPC not found in Channel3/UPCitemdb; show name-search sheet (A3)
 *  ManualSearch  — user has typed a name; showing Kenny Chan results
 *  Saved         — item confirmed and written to Couchbase
 *  Pending       — no network; UPC queued for later lookup (A5)
 *  Error         — unrecoverable error
 */
sealed class ScanState {
    object Idle        : ScanState()
    object Scanning    : ScanState()
    object LookingUp   : ScanState()

    /** A4: UPC is already in the user's collection */
    data class AlreadyOwned(
        val item: FunkoItem,
    ) : ScanState()

    /** Lookup succeeded — show item card for add/want-list confirmation */
    data class Preview(
        val item: FunkoItem,
        val alreadyOwned: Boolean = false,
    ) : ScanState()

    /**
     * A3: UPC not found anywhere — show name-search sheet backed by
     * the local Kenny Chan catalog so the user can match manually.
     */
    data class NotFound(
        val upc: String,
        val query: String = "",
        val results: List<FunkoItem> = emptyList(),
        val isSearching: Boolean = false,
    ) : ScanState()

    /** User chose to search by name instead of scanning */
    data class ManualSearch(
        val query: String = "",
        val results: List<FunkoItem> = emptyList(),
        val isSearching: Boolean = false,
    ) : ScanState()

    /** Item saved to collection */
    data class Saved(val item: FunkoItem) : ScanState()

    /** A5: No network — UPC queued for later lookup */
    data class Pending(val upc: String) : ScanState()

    data class Error(val message: String) : ScanState()
}

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val repository: FunkoRepository,
    private val lookup: FunkoLookupService,
    private val imageBlobs: ImageBlobRepository,
    private val contribRepo: ContributionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    // Suppress duplicate rapid-fire reads from the barcode analyzer
    private var lastScannedUpc: String = ""

    // ─── Barcode detected ─────────────────────────────────────────────────────

    fun onBarcodeDetected(upc: String) {
        if (upc == lastScannedUpc) return
        if (_state.value is ScanState.LookingUp) return
        if (_state.value is ScanState.Preview)   return
        if (_state.value is ScanState.NotFound)  return
        if (_state.value is ScanState.AlreadyOwned) return

        lastScannedUpc = upc
        viewModelScope.launch {
            _state.value = ScanState.LookingUp

            // ── A4: Duplicate detection ─────────────────────────────────────
            val existing = repository.getItemByUpc(upc)
            if (existing != null && existing.isOwned) {
                // Already in collection — warn the user rather than silently adding
                _state.value = ScanState.AlreadyOwned(existing)
                return@launch
            }
            if (existing != null && !existing.isOwned) {
                // On want list — show preview so user can confirm they got it
                _state.value = ScanState.Preview(existing, alreadyOwned = false)
                return@launch
            }

            // ── Network lookup ──────────────────────────────────────────────
            val fetched = lookup.lookupByUpc(upc)
            when {
                fetched != null -> _state.value = ScanState.Preview(fetched, alreadyOwned = false)

                // ── A5: No network / not found — queue or show NotFound sheet ─
                !isNetworkAvailable() -> {
                    repository.savePendingUpc(
                        PendingUpcScan(upc = upc)
                    )
                    _state.value = ScanState.Pending(upc)
                }

                // ── A3: Found network but item unknown — show lookup sheet ───
                else -> _state.value = ScanState.NotFound(upc = upc)
            }
        }
    }

    // ─── A3: NotFound name-search actions ─────────────────────────────────────

    fun onNotFoundQueryChanged(query: String) {
        val current = _state.value as? ScanState.NotFound ?: return
        _state.value = current.copy(query = query)
        if (query.length >= 2) searchKennyChan(query, current.upc)
    }

    private fun searchKennyChan(query: String, upc: String) {
        val current = _state.value as? ScanState.NotFound ?: return
        viewModelScope.launch {
            _state.value = current.copy(isSearching = true)
            // searchByName uses local Kenny Chan data — instant, offline
            val results = lookup.searchByName(query)
            (_state.value as? ScanState.NotFound)?.let {
                _state.value = it.copy(
                    results     = results,
                    isSearching = false,
                )
            }
        }
    }

    /**
     * User picked a catalog entry from the NotFound sheet.
     * Merges the UPC onto the matched item and transitions to Preview.
     */
    fun selectNotFoundMatch(item: FunkoItem, upc: String) {
        val enriched = item.copy(id = "funko::$upc", upc = upc)
        _state.value = ScanState.Preview(enriched, alreadyOwned = false)

        // F1: Queue this UPC→catalog mapping as a community contribution
        viewModelScope.launch {
            contribRepo.saveContribution(
                CatalogContribution(
                    upc               = upc,
                    handle            = item.catalogRef.removePrefix("catalog::").ifEmpty { item.id },
                    name              = item.name,
                    franchise         = item.franchise,
                    category          = item.category,
                    seriesNumber      = item.seriesNumber,
                    retailPrice       = item.retailPrice,
                    isVaulted         = item.isVaulted,
                    isChase           = item.isChase,
                    isExclusive       = item.isExclusive,
                    exclusiveRetailer = item.exclusiveRetailer,
                    imageUrl          = item.imageUrl,
                    source            = "USER_SCAN",
                )
            )
        }
    }

    // ─── Preview actions ───────────────────────────────────────────────────────

    fun confirmAdd(item: FunkoItem, pricePaid: Double, addToWantList: Boolean = false) {
        viewModelScope.launch {
            val toSave = item.copy(
                pricePaid = pricePaid,
                isOwned   = !addToWantList,
            )
            repository.saveItem(toSave).fold(
                onSuccess = { saved ->
                    _state.value = ScanState.Saved(saved)
                    // A7: download image blob silently for owned items
                    if (saved.isOwned) {
                        launch { imageBlobs.downloadAndStore(saved) }
                    }
                },
                onFailure = { _state.value = ScanState.Error("Save failed: ${it.message}") }
            )
        }
    }

    /** User wants to update an already-owned item (e.g. improve condition/notes). */
    fun confirmUpdate(item: FunkoItem) {
        viewModelScope.launch {
            repository.saveItem(item).fold(
                onSuccess = { saved -> _state.value = ScanState.Saved(saved) },
                onFailure = { _state.value = ScanState.Error("Update failed: ${it.message}") }
            )
        }
    }

    fun dismissPreview() {
        lastScannedUpc = ""
        _state.value = ScanState.Scanning
    }

    // ─── Manual name search (from toolbar button, not NotFound sheet) ──────────

    fun openManualSearch() { _state.value = ScanState.ManualSearch() }

    fun onManualQueryChanged(query: String) {
        val current = _state.value as? ScanState.ManualSearch ?: return
        _state.value = current.copy(query = query)
    }

    fun submitManualSearch(query: String) {
        if (query.isBlank()) return
        val current = _state.value as? ScanState.ManualSearch ?: return
        viewModelScope.launch {
            _state.value = current.copy(isSearching = true)
            val results = lookup.searchByName(query)
            (_state.value as? ScanState.ManualSearch)?.let {
                _state.value = it.copy(query = query, results = results, isSearching = false)
            }
        }
    }

    fun selectManualResult(item: FunkoItem) {
        _state.value = ScanState.Preview(item, alreadyOwned = false)
    }

    // ─── General ───────────────────────────────────────────────────────────────

    fun startScanning() { lastScannedUpc = ""; _state.value = ScanState.Scanning }
    fun reset()         { lastScannedUpc = ""; _state.value = ScanState.Idle }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private fun isNetworkAvailable(): Boolean =
        try {
            val cm = repository.getConnectivityManager()
            cm?.activeNetworkInfo?.isConnectedOrConnecting == true
        } catch (e: Exception) { false }
}
