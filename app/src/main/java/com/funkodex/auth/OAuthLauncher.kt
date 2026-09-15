package com.funkodex.auth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.funkodex.util.FunkoDexLogger

/**
 * OAuthLauncher — starts the OAuth flow in a Chrome Custom Tab.
 *
 * Builds the authorization URL with PKCE code_challenge, stores the
 * code_verifier in OAuthSession for later retrieval by OAuthCallbackActivity,
 * then opens the Custom Tab.
 *
 * Usage (from a Composable via rememberLauncherForActivityResult):
 *   OAuthLauncher.launch(context, OAuthProvider.HOBBYDB)
 */
object OAuthLauncher {

    private const val TAG = "OAuthLauncher"

    /**
     * @return null on success, or a human-readable reason the flow could not
     * start. eBay needs a client id imported via funkodex_keys.json; without one
     * we refuse rather than sending the placeholder and getting an opaque error.
     */
    fun launch(
        context: Context,
        provider: OAuthProvider,
        secureKeyStore: com.funkodex.security.SecureKeyStore,
    ): String? {
        if (provider == OAuthProvider.EBAY && !OAuthConfig.eBay.isConfigured(secureKeyStore)) {
            FunkoDexLogger.w(TAG, "eBay sign-in blocked: no client id configured")
            return "eBay isn't set up yet. Import a keys file containing " +
                "\"ebay_client_id\" from Settings > Data Sources first."
        }
        val verifier   = PkceHelper.generateVerifier()
        val challenge  = PkceHelper.challenge(verifier)

        // Store in session — OAuthCallbackActivity reads this after the redirect
        OAuthSession.pendingVerifier = verifier
        OAuthSession.pendingProvider = provider

        val authUri = buildAuthUri(provider, challenge, secureKeyStore)
        FunkoDexLogger.d(TAG, "Launching OAuth for $provider: ${authUri.toString().take(80)}…")

        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(context, authUri)
        return null
    }

    fun revoke(context: Context, provider: OAuthProvider) {
        // Clear local token — provider sessions are long-lived so we just drop the token
        FunkoDexLogger.i(TAG, "Revoking local OAuth token for $provider")
        OAuthSession.clear()
        // Caller is responsible for clearing SecureKeyStore (done in SettingsViewModel)
    }

    private fun buildAuthUri(
        provider: OAuthProvider,
        challenge: String,
        secureKeyStore: com.funkodex.security.SecureKeyStore,
    ): Uri {
        return when (provider) {
            OAuthProvider.HOBBYDB -> Uri.parse(OAuthConfig.HobbyDb.AUTH_URL).buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id",     OAuthConfig.HobbyDb.CLIENT_ID)
                .appendQueryParameter("redirect_uri",  OAuthConfig.HobbyDb.REDIRECT_URI)
                .appendQueryParameter("scope",         OAuthConfig.HobbyDb.SCOPE)
                .appendQueryParameter("code_challenge",        challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .build()

            OAuthProvider.EBAY -> Uri.parse(OAuthConfig.eBay.AUTH_URL).buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id",     OAuthConfig.eBay.clientId(secureKeyStore))
                .appendQueryParameter("redirect_uri",  OAuthConfig.eBay.REDIRECT_URI)
                .appendQueryParameter("scope",         OAuthConfig.eBay.SCOPE)
                .appendQueryParameter("code_challenge",        challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .build()
        }
    }
}
