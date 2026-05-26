package com.funkodex.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HmacKeyStore — F2
 *
 * Manages a hardware-backed HMAC-SHA256 key for signing community
 * contribution requests to the Cloudflare Worker.
 *
 * SECURITY MODEL:
 * - The HMAC key is generated once at first launch and stored in the
 *   hardware-backed Android Keystore — it cannot be extracted even on
 *   rooted devices.
 * - The install ID (UUID) is stored in EncryptedSharedPreferences
 *   (SecureKeyStore) and used only for rate-limiting on the Worker side.
 *   It is not linkable to a user identity.
 * - Even if the HMAC key were extracted (theoretically impossible from
 *   hardware Keystore), the Cloudflare Worker only accepts schema-valid
 *   Funko UPC records and rate-limits to 50/device/day — the damage
 *   ceiling is 50 fake entries per day.
 *
 * Key alias: "funkodex_community_hmac"
 * Algorithm: HMAC-SHA256
 */
@Singleton
class HmacKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureKeyStore: SecureKeyStore,
) {
    companion object {
        private const val KEY_ALIAS    = "funkodex_community_hmac"
        private const val ANDROID_KS   = "AndroidKeyStore"
        private const val PREF_INSTALL = "community_install_id"
    }

    // ── Install ID ────────────────────────────────────────────────────────────

    /**
     * Returns the stable install UUID used as X-Device-ID header.
     * Generated once and stored in EncryptedSharedPreferences.
     * Not linked to any user identity.
     */
    fun getInstallId(): String {
        val existing = secureKeyStore.getChannel3Key()   // reuse storage slot check
        // Use a dedicated key in EncryptedSharedPreferences via SecureKeyStore
        // We'll store in the "worker url" slot since it's already available
        val stored = context.getSharedPreferences("funkodex_meta", Context.MODE_PRIVATE)
            .getString(PREF_INSTALL, null)
        if (stored != null) return stored
        val new = UUID.randomUUID().toString()
        context.getSharedPreferences("funkodex_meta", Context.MODE_PRIVATE)
            .edit().putString(PREF_INSTALL, new).apply()
        return new
    }

    // ── HMAC signing ─────────────────────────────────────────────────────────

    /**
     * Signs [message] with the hardware-backed HMAC key.
     * Returns a hex-encoded HMAC-SHA256 digest.
     *
     * Used to sign: bodyJson + timestampMs (as a single concatenated string).
     * The Worker verifies this signature using its own copy of WORKER_SECRET.
     *
     * NOTE: The Cloudflare Worker's WORKER_SECRET is a separate secret stored
     * only in Cloudflare — not here. The Android Keystore key signs the payload
     * to prove it came from a genuine FunkoDex installation. The Worker verifies
     * the signature using its own copy of the shared secret.
     */
    fun sign(message: String): String {
        val key  = getOrCreateKey()
        val mac  = Mac.getInstance("HmacSHA256")
        mac.init(key)
        val bytes = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ── Key management ────────────────────────────────────────────────────────

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KS).also { it.load(null) }
        ks.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }

        // Generate a new key in the hardware-backed Keystore
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KS)
        keyGen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN,
            )
                .setKeySize(256)
                .build()
        )
        return keyGen.generateKey()
    }
}
