package com.funkodex.ui.screens.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
                contributeEnabled = prefs[CONTRIBUTE_KEY]   ?: false,
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
    fun setHobbyDbEnabled(v: Boolean)= update { it[HOBBYDB_KEY] = v }

    fun refreshNow() {
        CatalogRefreshWorker.runNow(context)
    }

    private fun update(transform: (MutablePreferences) -> Unit) {
        viewModelScope.launch {
            context.catalogDataStore.edit(transform)
            // Re-apply WorkManager schedule after any settings change
            RefreshScheduler.applyConfig(context, config.value)
        }
    }
}
