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
}

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

    override fun onCleared() {
        super.onCleared()
        autoResetJob?.cancel()
    }
}
