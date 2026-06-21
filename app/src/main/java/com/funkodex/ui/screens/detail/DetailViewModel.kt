package com.funkodex.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.funkodex.data.model.Condition
import com.funkodex.data.model.FunkoItem
import com.funkodex.data.model.FunkoGenre
import com.funkodex.data.model.ResolvedPrice
import com.funkodex.data.repository.FunkoRepository
import com.funkodex.network.PriceService
import com.funkodex.data.repository.PhotoRepository
import com.funkodex.data.repository.AlertRepository
import com.funkodex.data.model.PriceAlert
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

sealed class DetailUiState {
    object Loading                                                     : DetailUiState()
    data class Viewing(val item: FunkoItem)                           : DetailUiState()
    data class Editing(val draft: FunkoItem, val originalUpc: String = "", val originalImageUrl: String = "", val isSaving: Boolean = false) : DetailUiState()
    data class Error(val message: String)                              : DetailUiState()
    object Deleted                                                     : DetailUiState()
}

/** Separate state for price data so it doesn't force a full UI recompose */
sealed class PriceUiState {
    object Idle                                  : PriceUiState()
    object Loading                               : PriceUiState()
    data class Loaded(val price: ResolvedPrice)  : PriceUiState()
    data class Error(val message: String)        : PriceUiState()
}

@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: FunkoRepository,
    private val priceService: PriceService,
    private val photoRepository: PhotoRepository,
    private val alertRepository: AlertRepository,
    private val imageBlobs: com.funkodex.data.repository.ImageBlobRepository,
    private val db: com.funkodex.data.db.FunkoDexDatabase,
    private val contribRepo: com.funkodex.data.repository.ContributionRepository,
    private val groupPrefs: com.funkodex.data.repository.GroupPrefRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {

    private val itemId: String = checkNotNull(savedStateHandle["itemId"])

    private val _state      = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private val _priceState = MutableStateFlow<PriceUiState>(PriceUiState.Idle)
    val priceState: StateFlow<PriceUiState> = _priceState.asStateFlow()

    // Transient: a refresh completed but found no new market data, while an
    // existing (cached/manual) price is still shown. Cleared when a refresh
    // starts. Lets the card show a brief "No new market data found" note
    // without replacing the visible price.
    private val _noNewPriceData = MutableStateFlow(false)
    val noNewPriceData: StateFlow<Boolean> = _noNewPriceData.asStateFlow()

    // Photo state — null = no user photo, non-null = ByteArray for display
    private val _photoBytes = MutableStateFlow<ByteArray?>(null)
    val photoBytes: StateFlow<ByteArray?> = _photoBytes.asStateFlow()

    // Photo operation feedback
    private val _photoError = MutableStateFlow<String?>(null)
    val photoError: StateFlow<String?> = _photoError.asStateFlow()

    // D4: Price alert state — null means no alert set for this item
    private val _alertState = MutableStateFlow<PriceAlert?>(null)
    val alertState: StateFlow<PriceAlert?> = _alertState.asStateFlow()

    // Completion intent for this item's franchise and named set (if any).
    // null entry = group not applicable (blank franchise / no set).
    private val _franchiseIntent = MutableStateFlow<com.funkodex.data.model.GroupIntent?>(null)
    val franchiseIntent: StateFlow<com.funkodex.data.model.GroupIntent?> = _franchiseIntent.asStateFlow()
    private val _setIntent = MutableStateFlow<com.funkodex.data.model.GroupIntent?>(null)
    val setIntent: StateFlow<com.funkodex.data.model.GroupIntent?> = _setIntent.asStateFlow()

    // Dynamic category list: curated FunkoCategories.ALL plus any distinct category
    // discovered in the imported catalog, so a new Funko product line is selectable
    // without a code change. Loaded once on init.
    private val _categoryOptions =
        MutableStateFlow(com.funkodex.data.model.FunkoCategories.ALL)
    val categoryOptions: StateFlow<List<com.funkodex.data.model.FunkoCategories.CategoryDef>> =
        _categoryOptions.asStateFlow()

    init {
        loadItem()
        loadCategoryOptions()
    }

    /** Merge curated categories with distinct catalog categories (one-shot). */
    private fun loadCategoryOptions() {
        viewModelScope.launch {
            val discovered = runCatching { repository.getDistinctCategories() }.getOrDefault(emptyList())
            _categoryOptions.value =
                com.funkodex.data.model.FunkoCategories.allWithDiscovered(discovered)
        }
    }

    // ─── Load ─────────────────────────────────────────────────────────────────

    private fun loadItem() {
        viewModelScope.launch {
            val item = repository.getItem(itemId)
            if (item != null) {
                _state.value = DetailUiState.Viewing(item)
                // Load user photo bytes for display
                _photoBytes.value = photoRepository.getPhotoBytes(itemId)
                // Load price alert state (D4)
                _alertState.value = alertRepository.getAlert(itemId)
                // Load completion intent for this item's franchise + named set
                _franchiseIntent.value = item.franchise.takeIf { it.isNotBlank() }?.let {
                    groupPrefs.getIntent(com.funkodex.data.model.GroupLevel.FRANCHISE, it)
                }
                _setIntent.value = item.setTag.takeIf { it.isNotBlank() }?.let {
                    groupPrefs.getIntent(com.funkodex.data.model.GroupLevel.SET, it)
                }
                // Auto-fetch prices if stale or not yet loaded
                loadCachedPriceThenRefreshIfStale(item)
            } else {
                _state.value = DetailUiState.Error("Item not found")
            }
        }
    }

    /**
     * B3: Two-phase price load.
     * 1. Show cached price immediately (fast, offline).
     * 2. If stale or absent, trigger a background network refresh.
     */
    private fun loadCachedPriceThenRefreshIfStale(item: FunkoItem) {
        viewModelScope.launch {
            // Phase 1: read cache
            val cached = repository.getResolvedPrice(itemId)
            if (cached != ResolvedPrice.UNKNOWN) {
                _priceState.value = PriceUiState.Loaded(cached)
            }

            // Phase 2: refresh if stale or missing
            if (cached == ResolvedPrice.UNKNOWN || cached.isStale) {
                refreshPrices(item, showLoading = cached == ResolvedPrice.UNKNOWN)
            }
        }
    }

    /** B3: Manual "Refresh prices" button action */
    fun refreshPrices() {
        val item = (state.value as? DetailUiState.Viewing)?.item ?: return
        viewModelScope.launch { refreshPrices(item, showLoading = true) }
    }

    private suspend fun refreshPrices(item: FunkoItem, showLoading: Boolean) {
        _noNewPriceData.value = false
        if (showLoading) _priceState.value = PriceUiState.Loading
        val snapshot = priceService.fetchPrice(item)
        if (snapshot != null) {
            repository.savePriceSnapshot(snapshot)

            // A real market feed is ground truth and supersedes a manually-entered
            // value: a manual market value is only a fallback for when no source has
            // data. snapshot.avg > 0 means an actual market source returned comps
            // (retail-only tier-1 hits carry avg = 0 and must NOT clear a manual value).
            var effectiveItem = item
            if (snapshot.avg > 0 && item.marketValueIsManual) {
                repository.deletePriceSnapshot(item.id, com.funkodex.data.model.PriceSource.MANUAL)
                effectiveItem = item.copy(marketValueIsManual = false)
            }

            val resolved = repository.getResolvedPrice(itemId)
            _priceState.value = PriceUiState.Loaded(resolved)

            // Persist resolved market average and retail onto the item itself —
            // CollectionStats.totalMarketValue / totalRetailValue (Reports) sum
            // item.marketAvg / item.effectiveRetail, which were previously never
            // written back after a refresh. resolvedRetail is the price-waterfall
            // fallback used when there's no catalog retailPrice (see FunkoItem.effectiveRetail).
            val needsUpdate = resolved != ResolvedPrice.UNKNOWN &&
                (resolved.marketAvg != effectiveItem.marketAvg ||
                 resolved.retail != effectiveItem.resolvedRetail ||
                 effectiveItem.marketValueIsManual != item.marketValueIsManual)
            if (needsUpdate) {
                val result = repository.saveItem(
                    effectiveItem.copy(
                        marketAvg = resolved.marketAvg,
                        resolvedRetail = resolved.retail,
                    )
                )
                result.getOrNull()?.let { saved ->
                    if (_state.value is DetailUiState.Viewing) {
                        _state.value = DetailUiState.Viewing(saved)
                    }
                }
            }
        } else {
            // Fetch found no new market data. Don't blow away an existing cached
            // or manually-set price — re-resolve and keep showing it. Only show the
            // "no data" error when there is genuinely nothing to display.
            val cached = repository.getResolvedPrice(itemId)
            _priceState.value = if (cached != ResolvedPrice.UNKNOWN) {
                // Existing price is still shown; flag that this refresh added nothing.
                _noNewPriceData.value = true
                PriceUiState.Loaded(cached)
            } else {
                PriceUiState.Error("No price data available")
            }
        }
    }

    // ─── View actions ─────────────────────────────────────────────────────────

    // Pending UPC contribution — non-null when user just added a new UPC and should be prompted
    private val _pendingUpcContribution = MutableStateFlow<com.funkodex.data.model.CatalogContribution?>(null)
    val pendingUpcContribution: StateFlow<com.funkodex.data.model.CatalogContribution?> = _pendingUpcContribution.asStateFlow()

    fun dismissUpcContribution() { _pendingUpcContribution.value = null }

    fun confirmUpcContribution() {
        val contrib = _pendingUpcContribution.value ?: return
        _pendingUpcContribution.value = null
        viewModelScope.launch { contribRepo.saveContribution(contrib) }
    }

    fun startEditing() {
        val item = (state.value as? DetailUiState.Viewing)?.item ?: return
        _state.value = DetailUiState.Editing(item, originalUpc = item.upc, originalImageUrl = item.imageUrl)
    }

    fun deleteItem() {
        viewModelScope.launch {
            // Cancel any pending UPC contribution for this item before deleting
            val item = (state.value as? DetailUiState.Viewing)?.item
            if (item != null && item.upc.isNotEmpty()) {
                contribRepo.deletePendingContribution(item.upc)
            }
            repository.deleteItem(itemId)
            _state.value = DetailUiState.Deleted
        }
    }

    fun toggleOwned() {
        val item = (state.value as? DetailUiState.Viewing)?.item ?: return
        viewModelScope.launch {
            val nowOwned = !item.isOwned
            repository.saveItem(item.copy(isOwned = nowOwned))
            // D4: disable alert when item moves from want list to owned
            if (nowOwned) alertRepository.disableAlert(itemId)
            loadItem()
        }
    }

    // ─── Edit actions ─────────────────────────────────────────────────────────

    fun updateName(value: String)            = updateDraft { it.copy(name = value) }
    fun updateFranchise(value: String) {
        updateDraft { it.copy(franchise = value) }
        markEdited(com.funkodex.data.db.FunkoDexDatabase.FIELD_FRANCHISE)
    }
    fun updateNumber(value: String)          = updateDraft { it.copy(seriesNumber = value) }
    fun updatePricePaid(value: String)       = updateDraft { it.copy(pricePaid = value.toDoubleOrNull() ?: it.pricePaid) }
    fun updateCondition(value: Condition)    = updateDraft { it.copy(condition = value) }
    fun updateNotes(value: String)           = updateDraft { it.copy(notes = value) }
    fun updateCategory(value: String) {
        updateDraft { it.copy(category = value, genre = FunkoGenre.fromCategory(value)) }
        markEdited(com.funkodex.data.db.FunkoDexDatabase.FIELD_CATEGORY)
    }
    fun updateUpc(value: String) {
        updateDraft { it.copy(upc = value) }
        markEdited(com.funkodex.data.db.FunkoDexDatabase.FIELD_UPC)
    }
    fun updateImageUrl(value: String) {
        updateDraft { it.copy(imageUrl = value) }
        markEdited(com.funkodex.data.db.FunkoDexDatabase.FIELD_IMAGE_URL)
    }

    /**
     * Manually set the market value. Marks it as user-set so price refresh won't
     * overwrite it (see refreshPrices). A blank/zero value clears the manual flag,
     * allowing automatic pricing to resume. The MANUAL price snapshot that drives
     * the price-card display is written on save (see saveEdit).
     */
    fun updateMarketValue(value: String) = updateDraft {
        val parsed = value.toDoubleOrNull() ?: 0.0
        it.copy(marketAvg = parsed, marketValueIsManual = parsed > 0)
    }

    fun clearMissingOriginal() {
        val item = (state.value as? DetailUiState.Viewing)?.item ?: return
        // Clear the flag in the draft and enter edit mode so user can fill in original details
        _state.value = DetailUiState.Editing(
            draft       = item.copy(isMissingOriginal = false),
            originalUpc = item.upc,
            originalImageUrl = item.imageUrl,
        )
    }

    fun markVariantOnly() {
        val item = (state.value as? DetailUiState.Viewing)?.item ?: return
        viewModelScope.launch {
            repository.saveItem(item.copy(isMissingOriginal = true))
            loadItem()
        }
    }

    fun updateVariantNote(index: Int, note: String) = updateDraft { item ->
        val updated = item.variants.toMutableList()
        if (index in updated.indices) updated[index] = updated[index].copy(note = note)
        item.copy(variants = updated)
    }

    fun updateVariantPrice(index: Int, price: String) = updateDraft { item ->
        val updated = item.variants.toMutableList()
        if (index in updated.indices) {
            updated[index] = updated[index].copy(pricePaid = price.toDoubleOrNull() ?: updated[index].pricePaid)
        }
        item.copy(variants = updated)
    }

    fun removeVariant(index: Int) = updateDraft { item ->
        val updated = item.variants.toMutableList()
        if (index in updated.indices) updated.removeAt(index)
        val newItem = item.copy(variants = updated)
        // If last variant removed and item was flagged as missing original,
        // clear the flag too — no variants means no variant/original distinction
        if (updated.isEmpty() && item.isMissingOriginal) {
            newItem.copy(isMissingOriginal = false)
        } else {
            newItem
        }
    }
    fun updateDateAcquired(date: LocalDate?) = updateDraft { it.copy(dateAcquired = date) }

    fun saveEdit() {
        val editing = state.value as? DetailUiState.Editing ?: return
        _state.value = editing.copy(isSaving = true)
        viewModelScope.launch {
            repository.saveItem(editing.draft).fold(
                onSuccess = { saved ->
                    _state.value = DetailUiState.Viewing(saved)
                    // If the image URL changed during this edit, the cached blob is
                    // stale — force a re-download so the picture reflects the new URL.
                    if (saved.imageUrl.isNotEmpty() && saved.imageUrl != editing.originalImageUrl) {
                        viewModelScope.launch { imageBlobs.downloadAndStore(saved, force = true) }
                    }
                    // Manual market value: write (or refresh) a top-priority MANUAL
                    // snapshot so the price card shows it; this never goes stale and
                    // outranks every fetched source.
                    if (saved.marketValueIsManual && saved.marketAvg > 0) {
                        viewModelScope.launch {
                            repository.savePriceSnapshot(
                                com.funkodex.data.model.PriceSnapshot(
                                    itemId        = saved.id,
                                    source        = com.funkodex.data.model.PriceSource.MANUAL,
                                    avg           = saved.marketAvg,
                                    low           = saved.marketAvg,
                                    high          = saved.marketAvg,
                                    lastSalePrice = saved.marketAvg,
                                    saleCount     = 0,
                                    fetchedAt     = java.time.LocalDate.now(),
                                )
                            )
                            _priceState.value = PriceUiState.Loaded(repository.getResolvedPrice(itemId))
                        }
                    }
                    // If the manual value was cleared this edit, remove the MANUAL
                    // snapshot so automatic pricing can take over again.
                    if (!saved.marketValueIsManual) {
                        viewModelScope.launch {
                            repository.deletePriceSnapshot(saved.id, com.funkodex.data.model.PriceSource.MANUAL)
                            _priceState.value = PriceUiState.Loaded(repository.getResolvedPrice(itemId))
                        }
                    }
                    val newUpc = editing.draft.upc.trim()
                    val oldUpc = editing.originalUpc.trim()
                    viewModelScope.launch {
                        when {
                            // UPC cleared — delete any pending contribution for the old UPC
                            newUpc.isEmpty() && oldUpc.isNotEmpty() -> {
                                contribRepo.deletePendingContribution(oldUpc)
                            }
                            // UPC changed to a different value
                            newUpc.isNotEmpty() && newUpc != oldUpc -> {
                                // Delete pending contribution for old UPC if it exists
                                if (oldUpc.isNotEmpty()) {
                                    contribRepo.deletePendingContribution(oldUpc)
                                }
                                // Prompt to contribute the new/corrected UPC
                                _pendingUpcContribution.value = com.funkodex.data.model.CatalogContribution(
                                    upc               = newUpc,
                                    handle            = editing.draft.catalogRef,
                                    name              = editing.draft.name,
                                    franchise         = editing.draft.franchise,
                                    category          = editing.draft.category,
                                    seriesNumber      = editing.draft.seriesNumber,
                                    retailPrice       = editing.draft.retailPrice,
                                    isVaulted         = editing.draft.isVaulted,
                                    isChase           = editing.draft.isChase,
                                    isExclusive       = editing.draft.isExclusive,
                                    exclusiveRetailer = editing.draft.exclusiveRetailer,
                                    imageUrl          = editing.draft.imageUrl,
                                    source            = "USER_EDIT",
                                )
                            }
                            // UPC unchanged — nothing to do
                        }
                    }
                },
                onFailure = { _state.value = DetailUiState.Error(it.message ?: "Save failed") }
            )
        }
    }

    // ─── Photo actions (C3) ──────────────────────────────────────────────────

    /** Creates a temp file URI for the TakePicture contract. */
    fun createCameraUri(): android.net.Uri = photoRepository.createCameraTempUri()


    /**
     * Called after the camera or gallery contract returns a URI.
     * Saves the photo as a Couchbase Blob and reloads the display bytes.
     */
    fun savePhoto(uri: android.net.Uri) {
        viewModelScope.launch {
            val success = photoRepository.savePhoto(uri, itemId)
            if (success) {
                _photoBytes.value = photoRepository.getPhotoBytes(itemId)
                _photoError.value = null
            } else {
                _photoError.value = "Could not save photo — please try again"
            }
        }
    }

    fun deletePhoto() {
        viewModelScope.launch {
            photoRepository.deletePhoto(itemId)
            _photoBytes.value = null
        }
    }

    // ─── Photo target selection ───────────────────────────────────────────────

    enum class PhotoTarget { MAIN, VARIATION, BOTH }

    private val _pendingPhotoUri = MutableStateFlow<android.net.Uri?>(null)
    val pendingPhotoUri: StateFlow<android.net.Uri?> = _pendingPhotoUri.asStateFlow()

    fun setPendingPhoto(uri: android.net.Uri) { _pendingPhotoUri.value = uri }
    fun clearPendingPhoto() { _pendingPhotoUri.value = null }

    fun savePhotoWithTarget(uri: android.net.Uri, target: PhotoTarget) {
        clearPendingPhoto()
        viewModelScope.launch {
            when (target) {
                PhotoTarget.MAIN -> savePhoto(uri)
                PhotoTarget.VARIATION -> addVariantPhoto(uri)
                PhotoTarget.BOTH -> {
                    savePhoto(uri)
                    addVariantPhoto(uri)
                }
            }
        }
    }

    private suspend fun addVariantPhoto(uri: android.net.Uri) {
        val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } ?: return

        when (val s = state.value) {
            is DetailUiState.Editing -> {
                // In edit mode — update the draft directly, saved when user taps Save
                val newVariant = com.funkodex.data.model.FunkoVariant(
                    note  = "Variant",
                    photo = bytes,
                )
                _state.value = s.copy(draft = s.draft.copy(
                    variants = s.draft.variants + newVariant
                ))
            }
            is DetailUiState.Viewing -> {
                // In view mode — save immediately and reload
                val item = s.item
                val newVariant = com.funkodex.data.model.FunkoVariant(
                    note  = "Variant",
                    photo = bytes,
                )
                repository.saveItem(item.copy(variants = item.variants + newVariant))
                loadItem()
            }
            else -> return
        }
    }

    sealed class FetchState {
        object Idle : FetchState()
        object Fetching : FetchState()
        object Success : FetchState()
        data class Failed(val reason: String) : FetchState()
    }

    private val _fetchState = MutableStateFlow<FetchState>(FetchState.Idle)
    val fetchState: StateFlow<FetchState> = _fetchState.asStateFlow()

    fun clearFetchState() { _fetchState.value = FetchState.Idle }

    fun fetchImageFromCatalog() {
        val item = when (val s = state.value) {
            is DetailUiState.Viewing -> s.item
            is DetailUiState.Editing -> s.draft
            else -> null
        } ?: return
        // Resolve an image URL. The user's own imageUrl is authoritative and is
        // NEVER overwritten by this action. Only when the item has no URL of its
        // own do we fall back to the linked catalog doc (catalogRef →
        // catalog::{handle}), trying its imageUrl then funkoImageUrl. This lets
        // items created without an image recover one, without ever clobbering a
        // URL the user entered by hand.
        val userUrl = item.imageUrl.trim()
        val hasUserUrl = userUrl.isNotEmpty()
        var resolvedUrl = userUrl
        if (!hasUserUrl && item.catalogRef.isNotEmpty()) {
            val catalogDoc = db.getCollection().getDocument(item.catalogRef)
            resolvedUrl = catalogDoc?.getString(com.funkodex.data.preload.CatalogMapper.FIELD_IMAGE_URL)
                ?.takeIf { it.isNotBlank() }
                ?: catalogDoc?.getString(com.funkodex.data.preload.CatalogMapper.FIELD_FUNKO_IMAGE)
                    ?.takeIf { it.isNotBlank() }
                ?: ""
        }
        if (resolvedUrl.isEmpty()) {
            _fetchState.value = FetchState.Failed("No image URL set for this item, and no catalog image is available. Add an image URL in edit, or take a photo.")
            return
        }
        val itemWithUrl = item.copy(id = itemId, imageUrl = resolvedUrl)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _fetchState.value = FetchState.Fetching
            _photoError.value = null
            // Remove existing thumbnailBlob so downloadAndStore doesn't skip
            val doc = db.getCollection().getDocument(itemId)?.toMutable()
            if (doc != null) {
                doc.remove(com.funkodex.data.db.FunkoDexDatabase.FIELD_THUMBNAIL_BLOB)
                // Persist the resolved URL ONLY when the item had no user URL of its
                // own (i.e. we recovered one from the catalog). Never overwrite a
                // user-entered imageUrl with a catalog URL.
                if (!hasUserUrl) {
                    doc.setString(com.funkodex.data.db.FunkoDexDatabase.FIELD_IMAGE_URL, resolvedUrl)
                }
                db.getCollection().save(doc)
            }
            val result = imageBlobs.downloadAndStoreResult(itemWithUrl)
            if (result is com.funkodex.data.repository.ImageFetchResult.Success) {
                val bytes = db.getCollection().getDocument(itemId)
                    ?.getBlob(com.funkodex.data.db.FunkoDexDatabase.FIELD_THUMBNAIL_BLOB)
                    ?.content
                _photoBytes.value = bytes
                _fetchState.value = FetchState.Success
            } else {
                val reason = when (result) {
                    is com.funkodex.data.repository.ImageFetchResult.HttpError ->
                        if (result.code == 404)
                            "The catalog image no longer exists (404). This record's image URL is dead — try a different copy or add your own photo."
                        else
                            "The image server returned an error (HTTP ${result.code})."
                    is com.funkodex.data.repository.ImageFetchResult.TooLarge ->
                        "Image is too large (${result.bytes / 1024} KB; limit is ${600_000 / 1024} KB)."
                    is com.funkodex.data.repository.ImageFetchResult.NetworkError ->
                        "Network error: ${result.message}. Check your connection and try again."
                    is com.funkodex.data.repository.ImageFetchResult.EmptyBody ->
                        "The image server returned an empty response."
                    is com.funkodex.data.repository.ImageFetchResult.NoUrl ->
                        "No catalog image URL available for this item."
                    else -> "Could not download the image."
                }
                _fetchState.value = FetchState.Failed("$reason\n\nURL: $resolvedUrl")
            }
        }
    }

    // ─── Price alert actions (D4) ────────────────────────────────────────────

    fun setAlert(targetPrice: Double) {
        val item = (state.value as? DetailUiState.Viewing)?.item ?: return
        viewModelScope.launch {
            val alert = PriceAlert(
                itemId      = itemId,
                itemName    = item.name,
                upc         = item.upc,
                targetPrice = targetPrice,
                isEnabled   = true,
            )
            alertRepository.saveAlert(alert)
            _alertState.value = alert
        }
    }

    fun toggleAlert(enabled: Boolean) {
        val current = _alertState.value ?: return
        viewModelScope.launch {
            val updated = current.copy(isEnabled = enabled)
            alertRepository.saveAlert(updated)
            _alertState.value = updated
        }
    }

    fun deleteAlert() {
        viewModelScope.launch {
            alertRepository.deleteAlert(itemId)
            _alertState.value = null
        }
    }

    fun cancelEdit() { loadItem() }

    private fun updateDraft(transform: (FunkoItem) -> FunkoItem) {
        val editing = state.value as? DetailUiState.Editing ?: return
        _state.value = editing.copy(draft = transform(editing.draft))
    }

    /**
     * Stamp a FIELD_ key into the draft's userEditedFields so the re-link pass
     * will not overwrite this field from the catalog. Initializes the set from
     * null → empty on first edit (which also flips the doc from "pre-marker" to
     * "marker present"). Deduped. Pass the FunkoDexDatabase.FIELD_ constant.
     */
    private fun markEdited(fieldKey: String) = updateDraft { item ->
        val current = item.userEditedFields ?: emptyList()
        if (fieldKey in current) item
        else item.copy(userEditedFields = current + fieldKey)
    }

    /** Set the completion intent for the current item's franchise group. */
    fun setFranchiseIntent(intent: com.funkodex.data.model.GroupIntent) {
        val franchise = currentItem()?.franchise?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            groupPrefs.setIntent(com.funkodex.data.model.GroupLevel.FRANCHISE, franchise, intent)
            _franchiseIntent.value = intent
        }
    }

    /** Set the completion intent for the current item's named-set group. */
    fun setSetIntent(intent: com.funkodex.data.model.GroupIntent) {
        val setTag = currentItem()?.setTag?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            groupPrefs.setIntent(com.funkodex.data.model.GroupLevel.SET, setTag, intent)
            _setIntent.value = intent
        }
    }

    /** The item as currently shown, whether viewing or editing. */
    private fun currentItem(): FunkoItem? = when (val s = _state.value) {
        is DetailUiState.Viewing -> s.item
        is DetailUiState.Editing -> s.draft
        else -> null
    }
}
