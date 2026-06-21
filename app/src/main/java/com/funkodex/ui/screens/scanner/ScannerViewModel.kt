package com.funkodex.ui.screens.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.funkodex.data.model.FunkoItem
import com.funkodex.data.model.Condition
import com.funkodex.data.model.PendingUpcScan
import com.funkodex.data.repository.FunkoRepository
import com.funkodex.data.repository.ImageBlobRepository
import com.funkodex.data.repository.ContributionRepository
import com.funkodex.data.model.CatalogContribution
import com.funkodex.network.FunkoLookupService
import com.funkodex.network.ConnectivityObserver
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
        val selected: Set<String> = emptySet(),  // item IDs
    ) : ScanState()

    /** Item saved to collection */
    data class Saved(val item: FunkoItem) : ScanState()

    /**
     * Manual entry of an item not found in the catalog. [upc] is carried from a
     * scan when reached from the NotFound sheet ([upcLocked] = true), or blank
     * and editable when reached from the toolbar manual-search sheet.
     */
    data class ManualAdd(
        val upc: String = "",
        val upcLocked: Boolean = false,
    ) : ScanState()

    /** A5: No network — UPC queued for later lookup */
    data class Pending(val upc: String) : ScanState()

    data class Error(val message: String) : ScanState()
}

/**
 * Form input for [ScannerViewModel.confirmManualAdd]. Only [name] is required;
 * all other fields are optional and default to empty/sensible values so a
 * record can be created from the minimum and fleshed out later.
 */
data class ManualAddInput(
    val upc: String = "",
    val name: String,
    val seriesNumber: String = "",     // Pop! box number, e.g. "1496"
    val franchise: String = "",
    val category: String = "",
    val isExclusive: Boolean = false,
    val exclusiveRetailer: String = "",
    val imageUrl: String = "",
    val pricePaid: Double = 0.0,
    val condition: Condition = Condition.MINT,
    val isOwned: Boolean = true,
    val shareToCommunity: Boolean = true,
)

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val repository: FunkoRepository,
    private val lookup: FunkoLookupService,
    private val imageBlobs: ImageBlobRepository,
    private val contribRepo: ContributionRepository,
    private val connectivity: ConnectivityObserver,
) : ViewModel() {

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    // Dynamic category list for the manual-add picker: curated + catalog-discovered,
    // so new Funko product lines are selectable without a code change.
    private val _categoryOptions =
        MutableStateFlow(com.funkodex.data.model.FunkoCategories.ALL)
    val categoryOptions: StateFlow<List<com.funkodex.data.model.FunkoCategories.CategoryDef>> =
        _categoryOptions.asStateFlow()

    init {
        viewModelScope.launch {
            val discovered = runCatching { repository.getDistinctCategories() }.getOrDefault(emptyList())
            _categoryOptions.value =
                com.funkodex.data.model.FunkoCategories.allWithDiscovered(discovered)
        }
    }

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

    /** User scanned an item they already own — add it as a variant on the existing record. */
    fun addAsVariant(existing: FunkoItem) {
        viewModelScope.launch {
            val newVariant = com.funkodex.data.model.FunkoVariant(
                note = "Variant copy"
            )
            repository.saveItem(existing.copy(variants = existing.variants + newVariant))
                .fold(
                    onSuccess = { saved ->
                        _state.value = ScanState.Saved(saved.copy(
                            name = "${saved.name} (variant added)"
                        ))
                    },
                    onFailure = { _state.value = ScanState.Error("Failed to add variant: ${it.message}") }
                )
        }
    }

    /** User scanned a variant but doesn't own the original — mark it. */
    fun addAsVariantMissingOriginal(existing: FunkoItem) {
        viewModelScope.launch {
            val newVariant = com.funkodex.data.model.FunkoVariant(
                note = "Variant copy — original not owned"
            )
            repository.saveItem(
                existing.copy(
                    variants          = existing.variants + newVariant,
                    isMissingOriginal = true,
                )
            ).fold(
                onSuccess = { saved -> _state.value = ScanState.Saved(saved) },
                onFailure = { _state.value = ScanState.Error("Failed: ${it.message}") }
            )
        }
    }

    fun markVariantMissingOriginal() {
        val saved = (_state.value as? ScanState.Saved)?.item ?: return
        viewModelScope.launch {
            repository.saveItem(saved.copy(isMissingOriginal = true))
            _state.value = ScanState.Idle
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

    fun toggleManualSelection(item: FunkoItem) {
        val current = _state.value as? ScanState.ManualSearch ?: return
        val newSelected = if (item.id in current.selected)
            current.selected - item.id
        else
            current.selected + item.id
        _state.value = current.copy(selected = newSelected)
    }

    fun confirmBulkAdd() {
        val current = _state.value as? ScanState.ManualSearch ?: return
        val toAdd = current.results.filter { it.id in current.selected }
        if (toAdd.isEmpty()) return
        viewModelScope.launch {
            var addedCount = 0
            var lastSaved: FunkoItem? = null
            for (item in toAdd) {
                // Check if an owned item with the same name+franchise already exists
                val existing = repository.findOwnedByNameAndFranchise(item.name, item.franchise)
                if (existing != null) {
                    _state.value = ScanState.AlreadyOwned(existing)
                    return@launch
                }
                val collectionItem = item.copy(
                    id      = "funko::${java.util.UUID.randomUUID()}",
                    isOwned = true,
                )
                repository.saveItem(collectionItem).fold(
                    onSuccess = { saved ->
                        addedCount++
                        lastSaved = saved
                        if (saved.isOwned) launch { imageBlobs.downloadAndStore(saved) }
                    },
                    onFailure = {}
                )
            }
            if (addedCount > 0 && lastSaved != null) {
                _state.value = ScanState.Saved(lastSaved!!.copy(
                    name = if (addedCount == 1) lastSaved!!.name
                           else "$addedCount items added to your collection"
                ))
            }
        }
    }

    // ─── Manual add (new item not in catalog) ─────────────────────────────────

    /** Open the manual-add form from the NotFound sheet, carrying the scanned UPC. */
    fun openManualAddFromScan(upc: String) {
        _state.value = ScanState.ManualAdd(upc = upc, upcLocked = true)
    }

    /** Open the manual-add form from the toolbar manual-search sheet (no UPC yet). */
    fun openManualAddBlank() {
        _state.value = ScanState.ManualAdd(upc = "", upcLocked = false)
    }

    /**
     * Save a manually-entered item to the collection. Only [ManualAddInput.name]
     * is required; everything else is optional and can be edited later from the
     * detail screen. When [ManualAddInput.shareToCommunity] is true and a UPC is
     * present, a community contribution is queued via the existing opt-in flow
     * (provenance: USER_MANUAL).
     */
    fun confirmManualAdd(input: ManualAddInput) {
        if (input.name.isBlank()) return
        viewModelScope.launch {
            val upc = input.upc.trim()
            val id = if (upc.isNotEmpty()) "funko::$upc"
                     else "funko::${java.util.UUID.randomUUID()}"
            val item = FunkoItem(
                id                = id,
                upc               = upc,
                name              = input.name.trim(),
                franchise         = input.franchise.trim(),
                category          = input.category.trim(),
                seriesNumber      = input.seriesNumber.trim(),
                imageUrl          = input.imageUrl.trim(),
                pricePaid         = input.pricePaid,
                isOwned           = input.isOwned,
                isExclusive       = input.isExclusive,
                exclusiveRetailer = input.exclusiveRetailer.trim(),
                condition         = input.condition,
            )
            repository.saveItem(item).fold(
                onSuccess = { saved ->
                    _state.value = ScanState.Saved(saved)
                    if (saved.isOwned) launch { imageBlobs.downloadAndStore(saved) }
                    // Queue community contribution if opted in and we have a UPC to key on.
                    if (input.shareToCommunity && upc.isNotEmpty()) {
                        launch {
                            contribRepo.saveContribution(
                                CatalogContribution(
                                    upc               = upc,
                                    handle            = FunkoLookupService
                                        .normalizeForSearch(input.name).replace(' ', '-'),
                                    name              = input.name.trim(),
                                    franchise         = input.franchise.trim(),
                                    category          = input.category.trim(),
                                    seriesNumber      = input.seriesNumber.trim(),
                                    isExclusive       = input.isExclusive,
                                    exclusiveRetailer = input.exclusiveRetailer.trim(),
                                    imageUrl          = input.imageUrl.trim(),
                                    source            = "USER_MANUAL",
                                )
                            )
                        }
                    }
                },
                onFailure = { _state.value = ScanState.Error("Save failed: ${it.message}") }
            )
        }
    }

    // ─── General ───────────────────────────────────────────────────────────────

    fun startScanning() { lastScannedUpc = ""; _state.value = ScanState.Scanning }
    fun reset()         { lastScannedUpc = ""; _state.value = ScanState.Idle }

    /**
     * "Scan again" from the NotFound sheet. A scan that missed (e.g. a
     * single-frame misread that resolved to a UPC not in the catalog) leaves
     * [lastScannedUpc] set, which would suppress an immediate re-read of the
     * same barcode. Clearing it and returning to [ScanState.Scanning] lets the
     * user re-aim and try again without backing all the way out of the screen.
     */
    fun retryScan() { lastScannedUpc = ""; _state.value = ScanState.Scanning }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    /** Uses ConnectivityObserver which correctly uses NetworkCapabilities API (API 29+ compliant). */
    private fun isNetworkAvailable(): Boolean = connectivity.isConnected()
}
