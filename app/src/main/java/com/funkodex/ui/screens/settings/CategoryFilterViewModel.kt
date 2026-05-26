package com.funkodex.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.funkodex.data.model.CategoryPreference
import com.funkodex.data.model.FunkoGenre
import com.funkodex.data.repository.CategoryPreferenceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CategoryFilterViewModel @Inject constructor(
    private val repo: CategoryPreferenceRepository,
) : ViewModel() {

    /** All preferences grouped by genre, sorted genre alphabetically, categories by name */
    val grouped: StateFlow<Map<FunkoGenre, List<CategoryPreference>>> = repo.preferencesFlow()
        .map { prefs ->
            prefs
                .groupBy { pref ->
                    runCatching { FunkoGenre.valueOf(pref.genreName) }.getOrDefault(FunkoGenre.OTHER)
                }
                .entries
                .sortedBy { it.key.displayName }
                .associate { it.key to it.value.sortedBy { p -> p.categoryName } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Count of enabled categories per genre — for the header badge */
    val genreCounts: StateFlow<Map<FunkoGenre, Int>> = grouped
        .map { g -> g.mapValues { (_, cats) -> cats.count { it.isEnabled } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun setEnabled(categoryKey: String, enabled: Boolean) {
        viewModelScope.launch { repo.setEnabled(categoryKey, enabled) }
    }

    fun setGenreEnabled(genre: FunkoGenre, enabled: Boolean) {
        viewModelScope.launch { repo.setGenreEnabled(genre, enabled) }
    }

    fun resetToDefaults() {
        viewModelScope.launch { repo.resetToDefaults() }
    }
}
