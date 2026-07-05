package com.funkodex.auth

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.funkodex.security.SecureKeyStore
import com.funkodex.util.FunkoDexLogger
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject

/**
 * OAuthCallbackActivity
 *
 * Receives the OAuth redirect URI after the user authenticates in the
 * Chrome Custom Tab. The Custom Tab closes and this Activity receives
 * the intent with the auth code in the query parameter.
 *
 * Redirect URIs handled:
 *   funkodex://oauth/hobbydb?code=AUTH_CODE
 *   funkodex://oauth/ebay?code=AUTH_CODE
 *
 * On success: stores the access token in SecureKeyStore, broadcasts
 *             OAuthResultReceiver.ACTION_SUCCESS so Settings UI updates.
 * On failure: broadcasts OAuthResultReceiver.ACTION_FAILURE with error.
 *
 * This Activity has no UI — it finishes immediately after the token
 * exchange completes.
 */
@AndroidEntryPoint
class OAuthCallbackActivity : ComponentActivity() {

    companion object {
        private const val TAG = "OAuthCallback"
        const val ACTION_SUCCESS = "com.funkodex.OAUTH_SUCCESS"
        const val ACTION_FAILURE = "com.funkodex.OAUTH_FAILURE"
        const val EXTRA_PROVIDER = "provider"
        const val EXTRA_ERROR    = "error"
    }

    @Inject lateinit var secureKeyStore: SecureKeyStore
    @Inject lateinit var httpClient: OkHttpClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleCallback(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCallback(intent)
    }

    private fun handleCallback(intent: Intent?) {
        val uri      = intent?.data ?: run { finish(); return }
        val code     = uri.getQueryParameter("code")
        val error    = uri.getQueryParameter("error")
        val provider = OAuthSession.pendingProvider

        FunkoDexLogger.d(TAG, "OAuth callback: uri=$uri provider=$provider code=${code?.take(8)}…")

        when {
            error != null -> {
                FunkoDexLogger.w(TAG, "OAuth error from provider: $error")
                broadcast(ACTION_FAILURE, provider, error)
                OAuthSession.clear()
                finish()
            }
            code == null || provider == null -> {
                FunkoDexLogger.w(TAG, "OAuth callback missing code or session")
                broadcast(ACTION_FAILURE, provider, "Missing code or session expired")
                OAuthSession.clear()
                finish()
            }
            else -> {
                exchangeToken(provider, code)
            }
        }
    }

    private fun exchangeToken(provider: OAuthProvider, code: String) {
        val verifier = OAuthSession.pendingVerifier ?: run {
            broadcast(ACTION_FAILURE, provider, "PKCE verifier expired")
            OAuthSession.clear()
            finish()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val (tokenUrl, clientId, redirectUri) = when (provider) {
                    OAuthProvider.HOBBYDB -> Triple(
                        OAuthConfig.HobbyDb.TOKEN_URL,
                        OAuthConfig.HobbyDb.CLIENT_ID,
                        OAuthConfig.HobbyDb.REDIRECT_URI,
                    )
                    OAuthProvider.EBAY    -> Triple(
                        OAuthConfig.eBay.TOKEN_URL,
                        OAuthConfig.eBay.CLIENT_ID,
                        OAuthConfig.eBay.REDIRECT_URI,
                    )
                }

                val body = FormBody.Builder()
                    .add("grant_type",    "authorization_code")
                    .add("code",          code)
                    .add("redirect_uri",  redirectUri)
                    .add("client_id",     clientId)
                    .add("code_verifier", verifier)
                    .build()

                val request = Request.Builder()
                    .url(tokenUrl)
                    .post(body)
                    .header("User-Agent", "FunkoDex/1.0 Android")
                    .build()

                val response = httpClient.newCall(request).execute()
                response.use { resp ->
                    val bodyStr = resp.body?.string()
                        ?: error("Empty token response")

                    if (!resp.isSuccessful) {
                        error("Token exchange failed: HTTP ${resp.code} — $bodyStr")
                    }

                    val json         = JSONObject(bodyStr)
                    val accessToken  = json.getString("access_token")
                    val refreshToken = json.optString("refresh_token", "")
                    val expiresIn    = json.optLong("expires_in", 3600L)

                    // Store token — include expiry prefix so we can check staleness later
                    val expireAt = System.currentTimeMillis() + (expiresIn * 1000)
                    val stored   = "$accessToken|$expireAt|$refreshToken"

                    when (provider) {
                        OAuthProvider.HOBBYDB -> secureKeyStore.setHobbyDbToken(stored)
                        OAuthProvider.EBAY    -> secureKeyStore.setEbayOAuthToken(stored)
                    }

                    FunkoDexLogger.i(TAG, "OAuth token stored for $provider (expires in ${expiresIn}s)")
                    stored
                }
            }.fold(
                onSuccess = {
                    broadcast(ACTION_SUCCESS, provider, null)
                    OAuthSession.clear()
                    withContext(Dispatchers.Main) { finish() }
                },
                onFailure = { t ->
                    FunkoDexLogger.e(TAG, "Token exchange failed", t)
                    broadcast(ACTION_FAILURE, provider, t.message ?: "Unknown error")
                    OAuthSession.clear()
                    withContext(Dispatchers.Main) { finish() }
                }
            )
        }
    }

    private fun broadcast(action: String, provider: OAuthProvider?, error: String?) {
        sendBroadcast(Intent(action).apply {
            provider?.let { putExtra(EXTRA_PROVIDER, it.name) }
            error?.let   { putExtra(EXTRA_ERROR, it) }
            setPackage(packageName)  // restrict to our app only
        })
    }
}
