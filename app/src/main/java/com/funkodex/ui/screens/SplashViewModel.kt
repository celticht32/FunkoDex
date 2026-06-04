package com.funkodex.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.funkodex.data.preload.CatalogPreloader
import com.funkodex.data.repository.FunkoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val catalogPreloader: CatalogPreloader,
    private val repository: FunkoRepository,
) : ViewModel() {

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady

    init {
        viewModelScope.launch {
            // Run preload and minimum display time in parallel —
            // splash stays until BOTH are done
            kotlinx.coroutines.coroutineScope {
                val preload  = async { catalogPreloader.preloadIfNeeded() }
                val minTimer = async { kotlinx.coroutines.delay(4200L) }
                preload.await()
                minTimer.await()
            }
            _isReady.value = true
        }
    }
}
