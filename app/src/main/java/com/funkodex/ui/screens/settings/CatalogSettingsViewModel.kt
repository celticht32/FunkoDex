package com.funkodex.ui.screens.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.funkodex.data.model.CatalogRefreshConfig
import com.funkodex.data.model.CatalogSource
import com.funkodex.data.preload.CatalogRefreshWorker
import com.funkodex.data.preload.RefreshScheduler
import com.funkodex.security.SecureKeyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

private val Context.catalogDataStore by preferencesDataStore(name = "catalog_prefs")

@HiltViewModel
class CatalogSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureKeyStore: SecureKeyStore,
) : ViewModel() {

    private val ENABLED_KEY       = booleanPreferencesKey("refresh_enabled")
    private val INTERVAL_KEY      = intPreferencesKey("refresh_interval_days")
    private val CONTRIBUTE_KEY    = booleanPreferencesKey("contribute_enabled")
    private val WIFI_KEY          = booleanPreferencesKey("refresh_wifi_only")
    private val HOBBYDB_KEY       = booleanPreferencesKey("hobbydb_enabled")
    private val LAST_REFRESH_KEY  = stringPreferencesKey("last_refreshed")

    val config: StateFlow<CatalogRefreshConfig> = context.catalogDataStore.data
        .map { prefs ->
            CatalogRefreshConfig(
                enabled          = prefs[ENABLED_KEY]       ?: true,
                contributeEnabled = prefs[CONTRIBUTE_KEY]   ?: true,
                intervalDays     = prefs[INTERVAL_KEY]      ?: 7,
                wifiOnly         = prefs[WIFI_KEY]          ?: true,
                channel3ApiKey   = secureKeyStore.getChannel3Key(),
                hobbyDbEnabled   = prefs[HOBBYDB_KEY]       ?: false,
                lastRefreshed    = prefs[LAST_REFRESH_KEY]?.let {
                    runCatching { LocalDate.parse(it) }.getOrNull()
                },
                sources          = buildSet {
                    add(CatalogSource.KENNY_CHAN)
                    if (secureKeyStore.hasChannel3Key()) add(CatalogSource.CHANNEL3)
                    if (prefs[HOBBYDB_KEY] == true) add(CatalogSource.HOBBYDB)
                }
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CatalogRefreshConfig())

    // Inline feedback for the "Refresh now" button
    private val _refreshState = MutableStateFlow<RefreshUiState>(RefreshUiState.Idle)
    val refreshState: StateFlow<RefreshUiState> = _refreshState.asStateFlow()

    fun setEnabled(enabled: Boolean) = update { it[ENABLED_KEY] = enabled }
    fun setIntervalDays(days: Int)   = update { it[INTERVAL_KEY] = days.coerceIn(1, 30) }

    fun setContributeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            context.catalogDataStore.edit { it[CONTRIBUTE_KEY] = enabled }
            if (enabled) {
                com.funkodex.data.backup.GitHubUploadWorker.schedule(context)
            } else {
                com.funkodex.data.backup.GitHubUploadWorker.cancel(context)
            }
        }
    }
    fun setWifiOnly(wifiOnly: Boolean) = update { it[WIFI_KEY] = wifiOnly }
    fun setChannel3Key(key: String) {
        secureKeyStore.setChannel3Key(key)
        // Trigger config flow reread so UI reflects updated state immediately
        viewModelScope.launch {
            context.catalogDataStore.edit { /* no-op edit to force flow re-emission */ }
        }
    }

    /**
     * Import API keys from a user-picked JSON file (e.g. funkodex_keys.json in
     * Downloads). Recognised fields: "channel3_api_key" (set now), plus
     * "ebay_client_id" / "hobbyDB" which are accepted but not yet wired (eBay
     * client ID is still a compile-time constant; HobbyDB's API is partner-gated).
     * Only non-blank fields are applied. Returns a short human-readable summary of
     * what was imported, or an error message, for the caller to surface.
     */
    fun importKeysFromFile(uri: android.net.Uri): String {
        return try {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }
                ?: return "Could not open file"

            val gson = com.google.gson.Gson()
            val keys = gson.fromJson(text, ImportedKeys::class.java)
                ?: return "File isn't valid JSON"

            val imported = mutableListOf<String>()
            keys.channel3_api_key?.trim()?.takeIf { it.isNotEmpty() }?.let {
                secureKeyStore.setChannel3Key(it)
                imported += "Channel3 key"
            }
            // ebay_client_id / hobbyDB are accepted for forward-compat but not yet
            // applied; note them so the user knows they were seen but skipped.
            val skipped = mutableListOf<String>()
            if (!keys.ebay_client_id.isNullOrBlank()) skipped += "eBay (not yet wired)"
            if (!keys.hobbyDB.isNullOrBlank())        skipped += "HobbyDB (not yet wired)"

            viewModelScope.launch {
                context.catalogDataStore.edit { /* force config reread */ }
            }

            when {
                imported.isEmpty() && skipped.isEmpty() -> "No keys found in file"
                imported.isEmpty() -> "No keys applied (${skipped.joinToString()})"
                skipped.isEmpty()  -> "Imported: ${imported.joinToString()}"
                else -> "Imported: ${imported.joinToString()} · skipped: ${skipped.joinToString()}"
            }
        } catch (e: Exception) {
            "Import failed: ${e.message ?: "unknown error"}"
        }
    }
    fun setHobbyDbEnabled(v: Boolean)= update { it[HOBBYDB_KEY] = v }

    fun refreshNow() {
        _refreshState.value = RefreshUiState.Running
        val requestId = CatalogRefreshWorker.runNow(context)
        viewModelScope.launch {
            WorkManager.getInstance(context)
                .getWorkInfoByIdFlow(requestId)
                .collect { info ->
                    if (info == null) return@collect
                    when (info.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            val newItems = info.outputData.getInt("new_items", 0)
                            val merged   = info.outputData.getInt("upcs_merged", 0)
                            // Persist the refresh date so the UI's "Last refreshed" line shows it
                            context.catalogDataStore.edit {
                                it[LAST_REFRESH_KEY] = LocalDate.now().toString()
                            }
                            _refreshState.value =
                                if (newItems == 0 && merged == 0) RefreshUiState.UpToDate
                                else RefreshUiState.Added(newItems, merged)
                            return@collect
                        }
                        WorkInfo.State.FAILED -> {
                            _refreshState.value = RefreshUiState.Failed
                            return@collect
                        }
                        else -> { /* ENQUEUED / RUNNING / BLOCKED — keep showing Running */ }
                    }
                }
        }
    }

    fun clearRefreshState() { _refreshState.value = RefreshUiState.Idle }

    // ─── OAuth helpers ───────────────────────────────────────────────────────────
    fun isHobbyDbConnected(): Boolean = secureKeyStore.hasHobbyDbToken() && secureKeyStore.isHobbyDbTokenValid()
    fun isEbayConnected(): Boolean    = secureKeyStore.hasEbayOAuthToken() && secureKeyStore.isEbayTokenValid()

    fun disconnectHobbyDb() {
        secureKeyStore.clearHobbyDbToken()
        // Rebuild config flow to reflect change
        viewModelScope.launch { context.catalogDataStore.edit { it[HOBBYDB_KEY] = false } }
    }

    fun disconnectEbay() {
        secureKeyStore.clearEbayOAuthToken()
    }

    // ─── Internal ─────────────────────────────────────────────────────────────
    private fun update(transform: (MutablePreferences) -> Unit) {
        viewModelScope.launch {
            context.catalogDataStore.edit(transform)
            // Re-apply WorkManager schedule after any settings change
            RefreshScheduler.applyConfig(context, config.value)
        }
    }
}

/** Inline UI state for the "Refresh now" button. */
sealed class RefreshUiState {
    data object Idle     : RefreshUiState()
    data object Running  : RefreshUiState()
    data object UpToDate : RefreshUiState()
    data object Failed   : RefreshUiState()
    data class  Added(val newItems: Int, val mergedUpcs: Int) : RefreshUiState()
}

/**
 * Shape of the importable keys JSON file. All fields optional; only non-blank
 * ones are applied. Field names match the funkodex_keys.json template.
 */
data class ImportedKeys(
    val channel3_api_key: String? = null,
    val ebay_client_id: String? = null,
    val hobbyDB: String? = null,
)
