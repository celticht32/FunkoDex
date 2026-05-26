package com.funkodex.ui.screens.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.funkodex.data.model.FunkoItem
import com.funkodex.data.model.PendingUpcScan
import com.funkodex.data.repository.FunkoRepository
import com.funkodex.data.repository.ImageBlobRepository
import com.funkodex.network.FunkoLookupService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * BatchScanViewModel — E1
 *
 * Manages a queue of barcodes scanned in continuous batch mode.
 * The camera stays live after each scan, adding to the queue without
 * requiring the user to tap "Confirm" after every item.
 *
 * Queue entries are resolved in the background as they arrive —
 * each UPC is looked up via FunkoLookupService and the result stored.
 * The user reviews the final list and taps "Save all" to commit or
 * "Discard" to throw away the session.
 *
 * Duplicate UPCs within the same session are highlighted in amber but
 * still allowed (same UPC, different box condition is a valid use case).
 * UPCs already owned in Couchbase are flagged DUPLICATE.
 *
 * Status transitions per queue entry:
 *   SCANNING → LOOKING_UP → FOUND / NOT_FOUND / ALREADY_OWNED / DUPLICATE
 */
data class BatchEntry(
    val upc:          String,
    val status:       BatchStatus,
    val item:         FunkoItem? = null,
    val errorMessage: String     = "",
)

enum class BatchStatus {
    LOOKING_UP,    // network fetch in progress
    FOUND,         // resolved and ready to save
    NOT_FOUND,     // no match in any source
    ALREADY_OWNED, // already in user's collection
    DUPLICATE,     // same UPC scanned twice this session
}

@HiltViewModel
class BatchScanViewModel @Inject constructor(
    private val repository: FunkoRepository,
    private val lookup:     FunkoLookupService,
    private val imageBlobs: ImageBlobRepository,
) : ViewModel() {

    private val _entries    = MutableStateFlow<List<BatchEntry>>(emptyList())
    val entries: StateFlow<List<BatchEntry>> = _entries.asStateFlow()

    private val _isSaving   = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saveResult = MutableStateFlow<BatchSaveResult?>(null)
    val saveResult: StateFlow<BatchSaveResult?> = _saveResult.asStateFlow()

    // Track UPCs seen this session to detect in-session duplicates
    private val scannedThisSession = mutableSetOf<String>()

    // ─── Barcode detected ────────────────────────────────────────────────────

    fun onBarcodeDetected(upc: String) {
        viewModelScope.launch {
            val isDuplicate = upc in scannedThisSession
            scannedThisSession.add(upc)

            if (isDuplicate) {
                addEntry(BatchEntry(upc, BatchStatus.DUPLICATE))
                return@launch
            }

            // Add as LOOKING_UP placeholder first so UI shows it immediately
            addEntry(BatchEntry(upc, BatchStatus.LOOKING_UP))

            // Check if already owned in collection
            val existing = repository.getItemByUpc(upc)
            if (existing?.isOwned == true) {
                updateEntry(upc, BatchEntry(upc, BatchStatus.ALREADY_OWNED, existing))
                return@launch
            }

            // Network lookup
            val fetched = lookup.lookupByUpc(upc)
            if (fetched != null) {
                updateEntry(upc, BatchEntry(upc, BatchStatus.FOUND, fetched))
            } else {
                updateEntry(upc, BatchEntry(upc, BatchStatus.NOT_FOUND,
                    errorMessage = "Not found in catalog"))
            }
        }
    }

    // ─── Queue management ────────────────────────────────────────────────────

    fun removeEntry(upc: String) {
        _entries.value = _entries.value.filterNot { it.upc == upc }
        scannedThisSession.remove(upc)
    }

    fun clearQueue() {
        _entries.value = emptyList()
        scannedThisSession.clear()
    }

    // ─── Save all ────────────────────────────────────────────────────────────

    fun saveAll(pricePaid: Double = 0.0) {
        val toSave = _entries.value.filter { it.status == BatchStatus.FOUND && it.item != null }
        if (toSave.isEmpty()) return

        viewModelScope.launch {
            _isSaving.value = true
            var saved = 0
            var failed = 0

            toSave.forEach { entry ->
                val item = entry.item!!.copy(
                    isOwned   = true,
                    pricePaid = pricePaid,
                )
                repository.saveItem(item).fold(
                    onSuccess = { savedItem ->
                        // Trigger blob download for each saved item (A7)
                        launch { imageBlobs.downloadAndStore(savedItem) }
                        saved++
                    },
                    onFailure = { failed++ }
                )
            }

            _isSaving.value   = false
            _saveResult.value = BatchSaveResult(saved, failed, toSave.size)
        }
    }

    fun addToWantList() {
        val toSave = _entries.value.filter { it.status == BatchStatus.FOUND && it.item != null }
        if (toSave.isEmpty()) return

        viewModelScope.launch {
            _isSaving.value = true
            var saved = 0
            toSave.forEach { entry ->
                repository.saveItem(entry.item!!.copy(isOwned = false)).fold(
                    onSuccess = { saved++ },
                    onFailure = {}
                )
            }
            _isSaving.value   = false
            _saveResult.value = BatchSaveResult(saved, 0, toSave.size, addedToWantList = true)
        }
    }

    fun clearSaveResult() { _saveResult.value = null }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun addEntry(entry: BatchEntry) {
        _entries.value = _entries.value + entry
    }

    private fun updateEntry(upc: String, updated: BatchEntry) {
        _entries.value = _entries.value.map { if (it.upc == upc) updated else it }
    }

    val foundCount:   Int get() = _entries.value.count { it.status == BatchStatus.FOUND }
    val pendingCount: Int get() = _entries.value.count { it.status == BatchStatus.LOOKING_UP }
}

data class BatchSaveResult(
    val saved:           Int,
    val failed:          Int,
    val total:           Int,
    val addedToWantList: Boolean = false,
)
