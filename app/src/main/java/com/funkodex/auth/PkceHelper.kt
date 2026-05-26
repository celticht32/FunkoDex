package com.funkodex.auth

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PkceHelper — RFC 7636 PKCE utilities.
 *
 * Generates code_verifier and code_challenge for OAuth PKCE flows.
 * The verifier is only held in memory for the duration of the OAuth handshake.
 * Never logged, never written to disk.
 */
object PkceHelper {

    /** Generate a cryptographically random code_verifier (base64url, 64 bytes → 86 chars). */
    fun generateVerifier(): String {
        val bytes = ByteArray(64).also { SecureRandom().nextBytes(it) }
        return base64Url(bytes)
    }

    /** Derive code_challenge = BASE64URL(SHA-256(verifier)). */
    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64Url(digest)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

/**
 * In-memory store for the PKCE code_verifier during a single OAuth session.
 * Cleared immediately after the token exchange succeeds or fails.
 * Object-scope (singleton) is safe — only one OAuth flow runs at a time.
 */
object OAuthSession {
    @Volatile var pendingVerifier: String? = null
    @Volatile var pendingProvider: OAuthProvider? = null

    fun clear() {
        pendingVerifier = null
        pendingProvider = null
    }
}

enum class OAuthProvider { HOBBYDB, EBAY }
