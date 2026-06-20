package com.funkodex.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.funkodex.data.backup.DriveAuthManager
import com.funkodex.data.preload.CatalogImporter
import com.funkodex.data.preload.CollectionRelinkService
import com.funkodex.data.preload.ImportProgress
import com.funkodex.data.preload.RelinkProgress
import com.funkodex.security.SecureKeyStore
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
    private val collectionRelinkService: CollectionRelinkService,
    private val driveAuthManager: DriveAuthManager,
    private val secureKeyStore: SecureKeyStore,
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

    // ── Collection re-link (run AFTER enriched catalog import) ─────────────────
    // Fills missing UPC / price / market value / image / franchise / category on
    // owned items from the now-enriched catalog. Fill-only; never overwrites user
    // data. Must run after the enriched JSON is in the catalog.

    private val _relinkProgress = MutableStateFlow<RelinkProgress?>(null)
    val relinkProgress: StateFlow<RelinkProgress?> = _relinkProgress.asStateFlow()

    fun relinkCollection() {
        viewModelScope.launch {
            _relinkProgress.value = RelinkProgress(total = 0, done = false)
            collectionRelinkService.relink().collect { progress ->
                _relinkProgress.value = progress
            }
        }
    }

    fun clearRelinkProgress() {
        _relinkProgress.value = null
    }

    // ── Google Drive connection (AuthorizationClient-only, see spec §5) ────────

    private val _driveConnected = MutableStateFlow(secureKeyStore.isDriveConnected())
    val driveConnected: StateFlow<Boolean> = _driveConnected.asStateFlow()

    /** Emits a consent PendingIntent when authorize() needs UI; UI launches it then clears. */
    private val _driveConsentIntent = MutableStateFlow<android.app.PendingIntent?>(null)
    val driveConsentIntent: StateFlow<android.app.PendingIntent?> = _driveConsentIntent.asStateFlow()

    fun clearDriveConsentIntent() {
        _driveConsentIntent.value = null
    }

    /** Settings "Connect Google Drive" tap — may surface a consent PendingIntent. */
    fun connectDrive() {
        viewModelScope.launch {
            when (val auth = driveAuthManager.authorize()) {
                is DriveAuthManager.DriveAuth.Authorized -> {
                    secureKeyStore.setDriveConnected(true)
                    _driveConnected.value = true
                }
                is DriveAuthManager.DriveAuth.NeedsConsent -> {
                    _driveConsentIntent.value = auth.pendingIntent
                }
                is DriveAuthManager.DriveAuth.Failed -> {
                    FunkoDexLogger.w("SettingsViewModel", "Drive connect failed: ${auth.reason}")
                }
            }
        }
    }

    /** Result of launching the consent PendingIntent from connectDrive(). */
    fun onConsentResult(data: Intent?) {
        when (val auth = driveAuthManager.resultFromConsentIntent(data)) {
            is DriveAuthManager.DriveAuth.Authorized -> {
                secureKeyStore.setDriveConnected(true)
                _driveConnected.value = true
            }
            is DriveAuthManager.DriveAuth.NeedsConsent -> { /* shouldn't recurse; no-op */ }
            is DriveAuthManager.DriveAuth.Failed -> {
                FunkoDexLogger.w("SettingsViewModel", "Drive consent failed: ${auth.reason}")
            }
        }
    }

    /** Settings "Disconnect Google Drive" tap — clears the local connection flag.
     *  No access token is ever persisted (§5.5), so there is nothing to clearToken()
     *  here; full server-side revocation is via Google Account → Connections (UI subtitle). */
    fun disconnectDrive() {
        secureKeyStore.clearDriveConnected()
        _driveConnected.value = false
    }
}
