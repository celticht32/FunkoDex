package com.funkodex.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SecureKeyStore
 *
 * Stores user-entered API keys using AndroidX EncryptedSharedPreferences,
 * which encrypts both keys and values using AES-256-GCM backed by the
 * Android Keystore hardware module.
 *
 * SECURITY: Keys stored here are encrypted at rest. The AES master key
 * lives in the hardware-backed Android Keystore and cannot be extracted
 * even on rooted devices. This is far more secure than BuildConfig fields
 * (plaintext string constants in classes.dex, readable by JADX in seconds).
 *
 * Never store secrets in:
 *   - BuildConfig fields  (extractable from any APK)
 *   - local.properties injected via buildConfigField  (same as above)
 *   - Plain SharedPreferences  (plaintext XML on device filesystem)
 */
@Singleton
class SecureKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val PREFS_FILE     = "funkodex_secure_prefs"
        private const val KEY_CHANNEL3   = "channel3_api_key"
        private const val KEY_HOBBYDB    = "hobbydb_api_token"
        private const val KEY_EBAY_OAUTH = "ebay_oauth_token"
    }

    private val prefs: SharedPreferences by lazy { buildPrefs() }

    private fun buildPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // ─── Channel3 API key ─────────────────────────────────────────────────────
    fun getChannel3Key(): String    = prefs.getString(KEY_CHANNEL3, "")  ?: ""
    fun setChannel3Key(key: String) { prefs.edit().putString(KEY_CHANNEL3, key.trim()).apply() }
    fun hasChannel3Key(): Boolean   = getChannel3Key().isNotEmpty()
    fun clearChannel3Key()          { prefs.edit().remove(KEY_CHANNEL3).apply() }

    // ─── HobbyDB token (Phase D placeholder) ──────────────────────────────────
    fun getHobbyDbToken(): String   = prefs.getString(KEY_HOBBYDB, "")   ?: ""
    fun setHobbyDbToken(t: String)  { prefs.edit().putString(KEY_HOBBYDB, t.trim()).apply() }
    fun clearHobbyDbToken()         { prefs.edit().remove(KEY_HOBBYDB).apply() }

    // ─── eBay OAuth token (Phase D placeholder) ────────────────────────────────
    fun getEbayOAuthToken(): String  = prefs.getString(KEY_EBAY_OAUTH, "") ?: ""
    fun setEbayOAuthToken(t: String) { prefs.edit().putString(KEY_EBAY_OAUTH, t.trim()).apply() }
    fun clearEbayOAuthToken()        { prefs.edit().remove(KEY_EBAY_OAUTH).apply() }
}
