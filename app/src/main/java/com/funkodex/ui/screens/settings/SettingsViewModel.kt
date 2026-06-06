package com.funkodex.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.funkodex.data.preload.CatalogImporter
import com.funkodex.data.preload.ImportProgress
import com.funkodex.util.FunkoDexLogger
import com.funkodex.util.LogLevel
import com.funkodex.ui.theme.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// DataStore extension on Context — one instance per app
private val Context.dataStore by preferencesDataStore(name = "user_prefs")

@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val THEME_KEY     = stringPreferencesKey("app_theme")
    private val LOG_LEVEL_KEY = stringPreferencesKey("log_level")

    val appTheme: Flow<AppTheme> = context.dataStore.data
        .map { prefs ->
            val raw = prefs[THEME_KEY] ?: AppTheme.SYSTEM.name
            runCatching { AppTheme.valueOf(raw) }.getOrDefault(AppTheme.SYSTEM)
        }

    val logLevel: Flow<LogLevel> = context.dataStore.data
        .map { prefs -> LogLevel.fromName(prefs[LOG_LEVEL_KEY] ?: LogLevel.DEFAULT.name) }

    suspend fun setTheme(theme: AppTheme) {
        context.dataStore.edit { it[THEME_KEY] = theme.name }
    }

    suspend fun setLogLevel(level: LogLevel) {
        context.dataStore.edit { it[LOG_LEVEL_KEY] = level.name }
        FunkoDexLogger.setLevel(level)
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: UserPreferencesRepository,
    private val catalogImporter: CatalogImporter,
) : ViewModel() {

    val currentTheme: StateFlow<AppTheme> = prefs.appTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppTheme.SYSTEM)

    val logLevel: StateFlow<LogLevel> = prefs.logLevel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LogLevel.DEFAULT)

    fun setTheme(theme: AppTheme) {
        viewModelScope.launch { prefs.setTheme(theme) }
    }

    fun setLogLevel(level: LogLevel) {
        viewModelScope.launch { prefs.setLogLevel(level) }
    }

    // ── Enriched catalog import ────────────────────────────────────────────

    private val _importProgress = MutableStateFlow<ImportProgress?>(null)
    val importProgress: StateFlow<ImportProgress?> = _importProgress.asStateFlow()

    fun importEnrichedCatalog(uri: Uri) {
        viewModelScope.launch {
            _importProgress.value = ImportProgress(total = 0, done = false)
            catalogImporter.importFromUri(uri).collect { progress ->
                _importProgress.value = progress
            }
        }
    }

    fun clearImportProgress() {
        _importProgress.value = null
    }
}
