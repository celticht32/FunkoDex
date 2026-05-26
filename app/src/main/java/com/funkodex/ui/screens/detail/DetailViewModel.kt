package com.funkodex.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.funkodex.data.model.Condition
import com.funkodex.data.model.FunkoItem
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
    data class Editing(val draft: FunkoItem, val isSaving: Boolean = false) : DetailUiState()
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
) : ViewModel() {

    private val itemId: String = checkNotNull(savedStateHandle["itemId"])

    private val _state      = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private val _priceState = MutableStateFlow<PriceUiState>(PriceUiState.Idle)
    val priceState: StateFlow<PriceUiState> = _priceState.asStateFlow()

    // Photo state — null = no user photo, non-null = ByteArray for display
    private val _photoBytes = MutableStateFlow<ByteArray?>(null)
    val photoBytes: StateFlow<ByteArray?> = _photoBytes.asStateFlow()

    // Photo operation feedback
    private val _photoError = MutableStateFlow<String?>(null)
    val photoError: StateFlow<String?> = _photoError.asStateFlow()

    // D4: Price alert state — null means no alert set for this item
    private val _alertState = MutableStateFlow<PriceAlert?>(null)
    val alertState: StateFlow<PriceAlert?> = _alertState.asStateFlow()

    init {
        loadItem()
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
        if (showLoading) _priceState.value = PriceUiState.Loading
        val snapshot = priceService.fetchPrice(item)
        if (snapshot != null) {
            repository.savePriceSnapshot(snapshot)
            val resolved = repository.getResolvedPrice(itemId)
            _priceState.value = PriceUiState.Loaded(resolved)
        } else if (_priceState.value is PriceUiState.Loading) {
            _priceState.value = PriceUiState.Error("No price data available")
        }
        // If already showing cached data, don't replace with an error
    }

    // ─── View actions ─────────────────────────────────────────────────────────

    fun startEditing() {
        val item = (state.value as? DetailUiState.Viewing)?.item ?: return
        _state.value = DetailUiState.Editing(item)
    }

    fun deleteItem() {
        viewModelScope.launch {
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
    fun updateFranchise(value: String)       = updateDraft { it.copy(franchise = value) }
    fun updateNumber(value: String)          = updateDraft { it.copy(seriesNumber = value) }
    fun updatePricePaid(value: String)       = updateDraft { it.copy(pricePaid = value.toDoubleOrNull() ?: it.pricePaid) }
    fun updateCondition(value: Condition)    = updateDraft { it.copy(condition = value) }
    fun updateNotes(value: String)           = updateDraft { it.copy(notes = value) }
    fun updateDateAcquired(date: LocalDate?) = updateDraft { it.copy(dateAcquired = date) }

    fun saveEdit() {
        val editing = state.value as? DetailUiState.Editing ?: return
        _state.value = editing.copy(isSaving = true)
        viewModelScope.launch {
            repository.saveItem(editing.draft).fold(
                onSuccess = { saved -> _state.value = DetailUiState.Viewing(saved) },
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

    fun clearPhotoError() { _photoError.value = null }

    // ─── Price alert actions (D4) ────────────────────────────────────────────

    fun setAlert(targetPrice: Double) {
        val item = (state.value as? DetailUiState.Viewing)?.item ?: return
        viewModelScope.launch {
            val alert = PriceAlert(
                itemId      = itemId,
                itemName    = item.name,
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
}
