package com.funkodex.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SecureKeyStore
 *
 * Stores user-entered API keys using a thin AES-256-GCM wrapper backed
 * directly by the hardware-backed Android Keystore (`AndroidKeyStore`
 * provider). Ciphertext (IV + encrypted bytes, both base64) is stored in
 * a plain `SharedPreferences` file — the AES key itself never leaves the
 * Keystore and cannot be extracted, even on rooted devices.
 *
 * SESSION E (P2): Replaces the prior `androidx.security:EncryptedSharedPreferences`
 * implementation, which was pinned at the deprecated `1.1.0-alpha06` with no
 * stable 1.1.0 release available (see CHANGELOG Session 8 for verification
 * details). This implementation has no dependency on `security-crypto` at all.
 *
 * NOTE: No migration from the old `funkodex_secure_prefs` (EncryptedSharedPreferences)
 * file is performed — that file is simply abandoned on disk (still encrypted,
 * inert). On upgrade, users will need to re-enter their Channel3 API key and
 * re-link HobbyDB/eBay accounts once. This was a deliberate tradeoff to drop
 * the `security-crypto` dependency entirely rather than carry a one-time
 * migration shim that still required it.
 *
 * Never store secrets in:
 *   - BuildConfig fields  (extractable from any APK)
 *   - local.properties injected via buildConfigField  (same as above)
 *   - Plain SharedPreferences without this wrapper  (plaintext XML on device filesystem)
 */
@Singleton
class SecureKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        // Plain-prefs file — values are AES/GCM ciphertext, base64-encoded.
        private const val PREFS_FILE        = "funkodex_secure_prefs_v2"

        private const val KEY_CHANNEL3      = "channel3_api_key"
        private const val KEY_HOBBYDB       = "hobbydb_api_token"
        private const val KEY_EBAY_OAUTH    = "ebay_oauth_token"
        private const val KEY_INSTALL_ID    = "community_install_id"
        private const val KEY_LAST_BACKUP   = "drive_last_backup"
        private const val KEY_DRIVE_CONNECTED = "drive_connected"

        // Android Keystore
        private const val ANDROID_KEYSTORE  = "AndroidKeyStore"
        private const val KEY_ALIAS         = "funkodex_secure_key"
        private const val TRANSFORMATION    = "AES/GCM/NoPadding"
        private const val GCM_TAG_LEN_BITS  = 128
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    }

    // ─── AES/GCM key management ──────────────────────────────────────────────

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
        ks.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return keyGen.generateKey()
    }

    /** Encrypts [plaintext] and returns "base64(iv):base64(ciphertext)". */
    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv         = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
               Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    /** Decrypts a "base64(iv):base64(ciphertext)" string. Returns null on any failure
     *  (corrupt/missing entry, key invalidated, etc.) so callers fall back to "". */
    private fun decrypt(stored: String): String? {
        return try {
            val sep = stored.indexOf(':')
            if (sep < 0) return null
            val iv         = Base64.decode(stored.substring(0, sep), Base64.NO_WRAP)
            val ciphertext = Base64.decode(stored.substring(sep + 1), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LEN_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    // ─── Encrypted-string helpers ────────────────────────────────────────────

    private fun getEncryptedString(key: String): String {
        val stored = prefs.getString(key, null) ?: return ""
        return decrypt(stored) ?: ""
    }

    private fun setEncryptedString(key: String, value: String) {
        prefs.edit().putString(key, encrypt(value)).apply()
    }

    // ─── Channel3 API key ─────────────────────────────────────────────────────
    fun getChannel3Key(): String    = getEncryptedString(KEY_CHANNEL3)
    fun setChannel3Key(key: String) { setEncryptedString(KEY_CHANNEL3, key.trim()) }
    fun hasChannel3Key(): Boolean   = getChannel3Key().isNotEmpty()
    fun clearChannel3Key()          { prefs.edit().remove(KEY_CHANNEL3).apply() }

    // ─── HobbyDB token ───────────────────────────────────────────────────────────
    // Stored as "accessToken|expireAtMs|refreshToken"
    fun getHobbyDbToken(): String   = getEncryptedString(KEY_HOBBYDB)
    fun setHobbyDbToken(t: String)  { setEncryptedString(KEY_HOBBYDB, t.trim()) }
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
    fun getEbayOAuthToken(): String  = getEncryptedString(KEY_EBAY_OAUTH)
    fun setEbayOAuthToken(t: String) { setEncryptedString(KEY_EBAY_OAUTH, t.trim()) }
    fun clearEbayOAuthToken()        { prefs.edit().remove(KEY_EBAY_OAUTH).apply() }
    fun hasEbayOAuthToken(): Boolean = getEbayOAuthToken().isNotEmpty()
    fun getEbayAccessToken(): String = getEbayOAuthToken().substringBefore("|")
    // ─── Community install ID (anon UUID for rate-limiting) ─────────────────────
    fun getInstallId(): String {
        val stored = getEncryptedString(KEY_INSTALL_ID)
        if (stored.isNotEmpty()) return stored
        val new = java.util.UUID.randomUUID().toString()
        setEncryptedString(KEY_INSTALL_ID, new)
        return new
    }

    // ─── Drive backup timestamp ──────────────────────────────────────────────
    fun getLastBackup(): String  = getEncryptedString(KEY_LAST_BACKUP)
    fun setLastBackup(ts: String) { setEncryptedString(KEY_LAST_BACKUP, ts.trim()) }

    // ─── Drive connection flag (AuthorizationClient — no token stored, §5.5) ────
    fun isDriveConnected(): Boolean   = prefs.getBoolean(KEY_DRIVE_CONNECTED, false)
    fun setDriveConnected(connected: Boolean) { prefs.edit().putBoolean(KEY_DRIVE_CONNECTED, connected).apply() }
    fun clearDriveConnected()         { prefs.edit().remove(KEY_DRIVE_CONNECTED).apply() }

    fun isEbayTokenValid(): Boolean {
        val parts = getEbayOAuthToken().split("|")
        if (parts.size < 2) return false
        val expireAt = parts[1].toLongOrNull() ?: return false
        return System.currentTimeMillis() < expireAt - 60_000L
    }
}
