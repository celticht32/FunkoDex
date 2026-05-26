package com.funkodex.auth

import com.funkodex.security.SecureKeyStore
import com.funkodex.util.FunkoDexLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TokenRefreshManager
 *
 * Handles silent (background) access token refresh using stored refresh tokens.
 * Called by PriceService and CatalogRefreshWorker before making API calls,
 * so tokens are always fresh without requiring the user to re-authenticate.
 *
 * Refresh flow:
 *   1. Caller checks isTokenValid() — if still live, use it directly
 *   2. If expired (or within 5 min of expiry), call ensureFreshToken(provider)
 *   3. ensureFreshToken() posts the refresh_token to the provider's token URL
 *   4. On success: new tokens stored in SecureKeyStore, caller gets fresh access token
 *   5. On failure: token is cleared, caller falls back gracefully (skips that tier)
 *
 * Thread safety: per-provider Mutex prevents concurrent refresh races.
 * If two coroutines both find the token expired simultaneously, only one
 * performs the refresh; the other waits and uses the fresh token.
 *
 * SECURITY: refresh_token is read from EncryptedSharedPreferences, never logged.
 * The refresh POST contains only the refresh_token, client_id, and grant_type —
 * no client_secret is sent (PKCE public client flow).
 */
@Singleton
class TokenRefreshManager @Inject constructor(
    private val secureKeyStore: SecureKeyStore,
    private val httpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "TokenRefreshManager"
        // Refresh if token expires within 5 minutes
        private const val REFRESH_BUFFER_MS = 5 * 60 * 1000L
    }

    // One mutex per provider — prevents concurrent refresh storms
    private val hobbyDbMutex = Mutex()
    private val ebayMutex    = Mutex()

    /**
     * Returns a valid HobbyDB access token, refreshing silently if needed.
     * Returns null if no token is stored or refresh fails (caller should skip Tier 4).
     */
    suspend fun getValidHobbyDbToken(): String? =
        getValidToken(OAuthProvider.HOBBYDB, hobbyDbMutex)

    /**
     * Returns a valid eBay access token, refreshing silently if needed.
     * Returns null if no token is stored or refresh fails.
     */
    suspend fun getValidEbayToken(): String? =
        getValidToken(OAuthProvider.EBAY, ebayMutex)

    // ─── Status helpers (for TokenKeeperWorker) ───────────────────────────────

    /** True if there is a stored HobbyDB refresh token (connection was made at some point). */
    fun hasHobbyDbRefreshToken(): Boolean {
        val parts = secureKeyStore.getHobbyDbToken().split("|")
        return parts.getOrNull(2)?.isNotEmpty() == true
    }

    /** True if there is a stored eBay refresh token. */
    fun hasEbayRefreshToken(): Boolean {
        val parts = secureKeyStore.getEbayOAuthToken().split("|")
        return parts.getOrNull(2)?.isNotEmpty() == true
    }

    // ─── Core ─────────────────────────────────────────────────────────────────

    private suspend fun getValidToken(
        provider: OAuthProvider,
        mutex: Mutex,
    ): String? = mutex.withLock {
        val stored = when (provider) {
            OAuthProvider.HOBBYDB -> secureKeyStore.getHobbyDbToken()
            OAuthProvider.EBAY    -> secureKeyStore.getEbayOAuthToken()
        }
        if (stored.isEmpty()) return null

        val parts      = stored.split("|")
        val accessToken = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return null
        val expireAtMs  = parts.getOrNull(1)?.toLongOrNull() ?: return null
        val refreshToken= parts.getOrNull(2)?.takeIf { it.isNotEmpty() }

        // Token still has enough life — use it directly
        if (System.currentTimeMillis() < expireAtMs - REFRESH_BUFFER_MS) {
            FunkoDexLogger.v(TAG, "$provider token valid, no refresh needed")
            return accessToken
        }

        // Token is expired or expiring soon — try to refresh
        if (refreshToken.isNullOrEmpty()) {
            FunkoDexLogger.w(TAG, "$provider token expired and no refresh token stored — clearing")
            clearToken(provider)
            return null
        }

        FunkoDexLogger.i(TAG, "$provider token expiring soon — attempting silent refresh")
        return refresh(provider, refreshToken)
    }

    private suspend fun refresh(provider: OAuthProvider, refreshToken: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val (tokenUrl, clientId) = when (provider) {
                    OAuthProvider.HOBBYDB -> Pair(OAuthConfig.HobbyDb.TOKEN_URL, OAuthConfig.HobbyDb.CLIENT_ID)
                    OAuthProvider.EBAY    -> Pair(OAuthConfig.eBay.TOKEN_URL,    OAuthConfig.eBay.CLIENT_ID)
                }

                val body = FormBody.Builder()
                    .add("grant_type",    "refresh_token")
                    .add("refresh_token", refreshToken)
                    .add("client_id",     clientId)
                    .build()

                val response = httpClient.newCall(
                    Request.Builder()
                        .url(tokenUrl)
                        .post(body)
                        .header("User-Agent", "FunkoDex/1.0 Android")
                        .build()
                ).execute()

                val bodyStr = response.body?.string() ?: error("Empty refresh response")
                if (!response.isSuccessful) {
                    // 400/401 means refresh token is invalid — clear and require re-auth
                    if (response.code in 400..401) {
                        FunkoDexLogger.w(TAG, "$provider refresh token rejected (${response.code}) — clearing tokens")
                        clearToken(provider)
                        return@runCatching null
                    }
                    error("Refresh failed HTTP ${response.code}")
                }

                val json         = JSONObject(bodyStr)
                val newAccess    = json.getString("access_token")
                val newRefresh   = json.optString("refresh_token", refreshToken) // some providers rotate refresh tokens
                val expiresIn    = json.optLong("expires_in", 3600L)
                val expireAt     = System.currentTimeMillis() + (expiresIn * 1000)
                val newStored    = "$newAccess|$expireAt|$newRefresh"

                when (provider) {
                    OAuthProvider.HOBBYDB -> secureKeyStore.setHobbyDbToken(newStored)
                    OAuthProvider.EBAY    -> secureKeyStore.setEbayOAuthToken(newStored)
                }

                FunkoDexLogger.i(TAG, "$provider token silently refreshed (expires in ${expiresIn}s)")
                newAccess
            }.getOrElse { t ->
                FunkoDexLogger.e(TAG, "$provider silent refresh failed — will skip tier", t)
                null  // graceful degradation — caller skips the tier, not a crash
            }
        }

    private fun clearToken(provider: OAuthProvider) {
        when (provider) {
            OAuthProvider.HOBBYDB -> secureKeyStore.clearHobbyDbToken()
            OAuthProvider.EBAY    -> secureKeyStore.clearEbayOAuthToken()
        }
    }
}
