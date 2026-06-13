package com.funkodex.ui.screens.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.funkodex.data.model.CollectionStats
import com.funkodex.data.repository.FunkoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReportsUiState(
    val stats: CollectionStats? = null,
    val isLoading: Boolean = true,
)

@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val repository: FunkoRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReportsUiState())
    val uiState: StateFlow<ReportsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val stats = repository.getCollectionStats()
            _uiState.value = ReportsUiState(stats = stats, isLoading = false)
        }
    }
}
