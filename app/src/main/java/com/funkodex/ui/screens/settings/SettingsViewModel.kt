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
    @ApplicationContext private val context: Context,
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

    // ── Save logs to Downloads ─────────────────────────────────────────────
    // Logs live in filesDir, which is app-private and not browsable by the user,
    // so sharing was previously the ONLY way to get at them. This writes a plain
    // .txt copy into the public Downloads folder, where the user can open it,
    // keep it, or attach it later — the same MediaStore route the backup export
    // uses (see DatabaseTransferViewModel.saveToDownloads).

    private val _logSaveMessage = MutableStateFlow<String?>(null)
    val logSaveMessage: StateFlow<String?> = _logSaveMessage.asStateFlow()

    fun clearLogSaveMessage() { _logSaveMessage.value = null }

    /**
     * Concatenate every retained log file (oldest first) into one timestamped
     * .txt in Downloads. A single text file rather than a zip: it opens in any
     * viewer on the phone with no unzip step, which is the point of having it
     * outside the share sheet.
     */
    fun saveLogsToDownloads() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val result = runCatching {
                // Writes are queued on a background thread, so flush first or the
                // most recent lines — usually the reason for saving — are missed.
                FunkoDexLogger.flushBlocking()

                val files = FunkoDexLogger.allLogFiles().sortedBy { it.lastModified() }
                if (files.isEmpty()) return@runCatching null

                val stamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                val fileName = "FunkoDex_log_$stamp.txt"
                val body = buildString {
                    append("FunkoDex log export — ")
                    append(java.time.LocalDateTime.now())
                    append("\nlevel=").append(FunkoDexLogger.currentLevel.name)
                    append("  files=").append(files.size).append("\n")
                    files.forEach { f ->
                        append("\n===== ").append(f.name)
                        append("  (").append(f.length() / 1024).append(" KB) =====\n")
                        append(runCatching { f.readText() }
                            .getOrElse { "<unreadable: ${'$'}{it.message}>\n" })
                    }
                }
                writeTextToDownloads(fileName, body)
                fileName
            }
            _logSaveMessage.value = when {
                result.isFailure -> "Could not save log: ${'$'}{result.exceptionOrNull()?.message}"
                result.getOrNull() == null -> "No log files to save yet"
                else -> "Saved to Downloads/${'$'}{result.getOrNull()}"
            }
        }
    }

    private fun writeTextToDownloads(fileName: String, text: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore refused the insert")
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                ?: error("Could not open the output stream")
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            java.io.File(dir, fileName).writeText(text)
        }
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
