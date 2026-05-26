package com.funkodex.ui.screens.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.funkodex.data.model.FunkoItem
import com.funkodex.data.repository.FunkoRepository
import com.funkodex.data.repository.CategoryPreferenceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CollectionUiState(
    val items: List<FunkoItem> = emptyList(),
    val isLoading: Boolean = true,
    val searchQuery: String = "",
    val filterFranchise: String? = null,
    val sortBy: SortOption = SortOption.DATE_ADDED,
)

enum class SortOption(val label: String) {
    DATE_ADDED("Recently Added"),
    NAME("Name A–Z"),
    SERIES("Series"),
    PRICE_PAID("Price Paid"),
}

@HiltViewModel
class CollectionViewModel @Inject constructor(
    private val repository: FunkoRepository,
    private val categoryPrefs: CategoryPreferenceRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CollectionUiState())
    val uiState: StateFlow<CollectionUiState> = _uiState.asStateFlow()

    val allSeries: StateFlow<List<String>> = _uiState
        .map { state -> state.items.map { it.franchise }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val displayedItems: StateFlow<List<FunkoItem>> = _uiState
        .map { state ->
            state.items
                .filter { item ->
                    (state.searchQuery.isEmpty() ||
                        item.name.contains(state.searchQuery, ignoreCase = true) ||
                        item.franchise.contains(state.searchQuery, ignoreCase = true)) &&
                    (state.filterFranchise == null || item.franchise == state.filterFranchise)
                }
                .let { filtered ->
                    when (state.sortBy) {
                        SortOption.DATE_ADDED  -> filtered.sortedByDescending { it.dateAdded }
                        SortOption.NAME        -> filtered.sortedBy { it.name }
                        SortOption.SERIES      -> filtered.sortedWith(compareBy({ it.franchise }, { it.seriesNumber }))
                        SortOption.PRICE_PAID  -> filtered.sortedByDescending { it.pricePaid }
                    }
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Combine live collection with live category preferences
        // so the list reacts immediately when the user toggles categories
        viewModelScope.launch {
            repository.collectionFlow()
                .combine(categoryPrefs.enabledCategoryKeysFlow()) { items, enabledKeys ->
                    if (enabledKeys.isEmpty()) items  // no prefs yet → show all
                    else items.filter { item ->
                        item.category.isEmpty() ||
                        enabledKeys.any { key ->
                            item.category.contains(key, ignoreCase = true)
                        }
                    }
                }
                .collect { filtered ->
                    _uiState.update { it.copy(items = filtered, isLoading = false) }
                }
        }
    }

    fun setSearchQuery(query: String) = _uiState.update { it.copy(searchQuery = query) }
    fun setFilterSeries(series: String?) = _uiState.update { it.copy(filterFranchise = series) }
    fun setSortBy(sort: SortOption) = _uiState.update { it.copy(sortBy = sort) }

    fun deleteItem(item: FunkoItem) {
        viewModelScope.launch { repository.deleteItem(item.id) }
    }

    fun moveToWantList(item: FunkoItem) {
        viewModelScope.launch { repository.saveItem(item.copy(isOwned = false)) }
    }
}
