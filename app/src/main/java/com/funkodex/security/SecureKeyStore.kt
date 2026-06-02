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
        private const val KEY_INSTALL_ID = "community_install_id"
        private const val KEY_LAST_BACKUP = "drive_last_backup"
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

    // ─── HobbyDB token ───────────────────────────────────────────────────────────
    // Stored as "accessToken|expireAtMs|refreshToken"
    fun getHobbyDbToken(): String   = prefs.getString(KEY_HOBBYDB, "")   ?: ""
    fun setHobbyDbToken(t: String)  { prefs.edit().putString(KEY_HOBBYDB, t.trim()).apply() }
    fun clearHobbyDbToken()         { prefs.edit().remove(KEY_HOBBYDB).apply() }
    fun hasHobbyDbToken(): Boolean  = getHobbyDbToken().isNotEmpty()
    /** Extracts just the access token portion (before the first '|'). */
    fun getHobbyDbAccessToken(): String = getHobbyDbToken().substringBefore("|")
    /** True if the stored token has not yet passed its expiry timestamp. */
    fun isHobbyDbTokenValid(): Boolean {
        val parts = getHobbyDbToken().split("|")
        if (parts.size < 2) return false
        val expireAt = parts[1].toLongOrNull() ?: return false
        return System.currentTimeMillis() < expireAt - 60_000L  // 60s grace
    }

    // ─── eBay OAuth token ─────────────────────────────────────────────────────
    // Stored as "accessToken|expireAtMs|refreshToken"
    fun getEbayOAuthToken(): String  = prefs.getString(KEY_EBAY_OAUTH, "") ?: ""
    fun setEbayOAuthToken(t: String) { prefs.edit().putString(KEY_EBAY_OAUTH, t.trim()).apply() }
    fun clearEbayOAuthToken()        { prefs.edit().remove(KEY_EBAY_OAUTH).apply() }
    fun hasEbayOAuthToken(): Boolean = getEbayOAuthToken().isNotEmpty()
    fun getEbayAccessToken(): String = getEbayOAuthToken().substringBefore("|")
    // ─── Community install ID (anon UUID for rate-limiting) ─────────────────────
    fun getInstallId(): String {
        val stored = prefs.getString(KEY_INSTALL_ID, null)
        if (!stored.isNullOrEmpty()) return stored
        val new = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_ID, new).apply()
        return new
    }

    // ─── Drive backup timestamp ──────────────────────────────────────────────
    fun getLastBackup(): String  = prefs.getString(KEY_LAST_BACKUP, "") ?: ""
    fun setLastBackup(ts: String) { prefs.edit().putString(KEY_LAST_BACKUP, ts.trim()).apply() }

    fun isEbayTokenValid(): Boolean {
        val parts = getEbayOAuthToken().split("|")
        if (parts.size < 2) return false
        val expireAt = parts[1].toLongOrNull() ?: return false
        return System.currentTimeMillis() < expireAt - 60_000L
    }
}
