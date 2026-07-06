package com.funkodex.ui.screens.prescan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.funkodex.data.model.FunkoItem
import com.funkodex.data.repository.FunkoRepository
import com.funkodex.network.FunkoLookupService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class PreScanState {
    object Idle : PreScanState()
    object Scanning : PreScanState()
    object LookingUp : PreScanState()
    data class AlreadyOwned(val item: FunkoItem) : PreScanState()
    data class NotOwned(val item: FunkoItem) : PreScanState()    // found but not owned
    object NotFound : PreScanState()                              // not in DB at all

    /**
     * Name-based check for loose figures with no scannable barcode. Unlike a UPC
     * scan (one code -> one figure), a name matches many figures, so we can't
     * answer owned/not-owned directly — we show the matching catalog figures,
     * each badged with the user's ownership status for that specific figure.
     */
    data class NameSearch(
        val query: String = "",
        val isSearching: Boolean = false,
        val results: List<PreScanMatch> = emptyList(),
    ) : PreScanState()
}

/** A catalog figure returned by name search, badged with ownership status. */
data class PreScanMatch(
    val item: FunkoItem,
    val status: OwnStatus,
)

enum class OwnStatus { OWNED, WANTED, NOT_IN_COLLECTION }

@HiltViewModel
class PreScanViewModel @Inject constructor(
    private val repository: FunkoRepository,
    private val lookup: FunkoLookupService,
) : ViewModel() {

    private val _state = MutableStateFlow<PreScanState>(PreScanState.Idle)
    val state: StateFlow<PreScanState> = _state.asStateFlow()

    private var lastScannedUpc: String = ""
    private var autoResetJob: Job? = null

    fun startScanning() {
        lastScannedUpc = ""
        _state.value = PreScanState.Scanning
    }

    fun onBarcodeDetected(upc: String) {
        if (upc == lastScannedUpc) return
        if (_state.value is PreScanState.LookingUp) return
        // Allow re-scan if showing a result
        if (_state.value is PreScanState.AlreadyOwned ||
            _state.value is PreScanState.NotOwned ||
            _state.value is PreScanState.NotFound) {
            autoResetJob?.cancel()
        }

        lastScannedUpc = upc
        viewModelScope.launch {
            _state.value = PreScanState.LookingUp

            // Step 1: check if already in collection (owned)
            val ownedItem = repository.getItemByUpc(upc)
            if (ownedItem != null && ownedItem.isOwned) {
                _state.value = PreScanState.AlreadyOwned(ownedItem)
                scheduleAutoReset()
                return@launch
            }

            // Step 2: check want list
            if (ownedItem != null && !ownedItem.isOwned) {
                _state.value = PreScanState.NotOwned(ownedItem)
                scheduleAutoReset()
                return@launch
            }

            // Step 3: not in our DB at all — do a quick local lookup for metadata display
            val lookedUp = lookup.lookupByUpc(upc)
            if (lookedUp != null) {
                // Found metadata but not in our collection
                _state.value = PreScanState.NotOwned(lookedUp)
            } else {
                _state.value = PreScanState.NotFound
            }
            scheduleAutoReset()
        }
    }

    /** Auto-reset back to scanning after 4 seconds so the user can scan the next item */
    private fun scheduleAutoReset() {
        autoResetJob?.cancel()
        autoResetJob = viewModelScope.launch {
            delay(4_000)
            lastScannedUpc = ""
            _state.value = PreScanState.Scanning
        }
    }

    // ─── Name-based check (loose figures, no scannable barcode) ────────────────

    /** Enter name-search mode from the scan screen. */
    fun openNameSearch() {
        autoResetJob?.cancel()
        _state.value = PreScanState.NameSearch()
    }

    /** Leave name search, return to scanning. */
    fun closeNameSearch() {
        lastScannedUpc = ""
        _state.value = PreScanState.Scanning
    }

    fun onNameQueryChanged(query: String) {
        val current = _state.value as? PreScanState.NameSearch ?: return
        _state.value = current.copy(query = query)
    }

    fun submitNameSearch(query: String) {
        if (query.isBlank()) return
        val current = _state.value as? PreScanState.NameSearch ?: return
        viewModelScope.launch {
            _state.value = current.copy(isSearching = true)

            // 1. The user's OWN figures first — the reliable "do I have one like
            //    this?" answer. These come straight from the collection and do
            //    not depend on a catalog row existing (many dump-imported figures
            //    never match the catalog).
            val ownedMatches = repository.searchOwnedByName(query).map { owned ->
                val status = if (owned.isOwned) OwnStatus.OWNED else OwnStatus.WANTED
                PreScanMatch(item = owned, status = status)
            }

            // 2. Catalog figures the user does NOT already have, shown below.
            //    De-duplicate against owned by catalogRef, UPC, and normalized
            //    name so a figure the user owns is not listed twice.
            fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
            val ownedRefs  = ownedMatches.mapNotNull { it.item.catalogRef.takeIf { r -> r.isNotBlank() } }.toHashSet()
            val ownedUpcs  = ownedMatches.mapNotNull { it.item.upc.takeIf { u -> u.isNotBlank() }?.trimStart('0') }.toHashSet()
            val ownedNames = ownedMatches.map { norm(it.item.name) }.toHashSet()

            val catalogHits = lookup.searchByName(query)
            val unownedCatalog = catalogHits
                .filter { cat ->
                    cat.id !in ownedRefs &&
                        (cat.upc.isBlank() || cat.upc.trimStart('0') !in ownedUpcs) &&
                        norm(cat.name) !in ownedNames
                }
                .map { PreScanMatch(item = it, status = OwnStatus.NOT_IN_COLLECTION) }

            val results = ownedMatches + unownedCatalog
            (_state.value as? PreScanState.NameSearch)?.let {
                _state.value = it.copy(query = query, isSearching = false, results = results)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        autoResetJob?.cancel()
    }
}
