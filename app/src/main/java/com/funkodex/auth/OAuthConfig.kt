package com.funkodex.auth

/**
 * OAuthConfig — endpoint constants for HobbyDB and eBay OAuth 2.0 flows.
 *
 * Both use PKCE (Proof Key for Code Exchange) — the recommended flow for
 * mobile apps because it does not require a client secret embedded in the APK.
 *
 * Flow:
 *   1. Generate code_verifier (random 64-byte string, base64url-encoded)
 *   2. Compute code_challenge = SHA-256(code_verifier), base64url-encoded
 *   3. Launch Chrome Custom Tab to authorization URL with code_challenge
 *   4. Provider redirects to funkodex://oauth/{provider}?code=AUTH_CODE
 *   5. OAuthCallbackActivity receives the redirect, calls token exchange
 *   6. Token exchange sends code + code_verifier → receives access_token
 *   7. Store access_token in SecureKeyStore
 *
 * SECURITY: code_verifier is stored in memory only (SessionStore) for the
 *           lifetime of the OAuth session. Never written to disk or logs.
 */
object OAuthConfig {

    // ── HobbyDB ───────────────────────────────────────────────────────────────
    object HobbyDb {
        const val AUTH_URL      = "https://hobby-db.com/oauth/authorize"
        const val TOKEN_URL     = "https://hobby-db.com/oauth/token"
        const val CLIENT_ID     = "funkodex_android"   // public client — no secret in APK
        const val REDIRECT_URI  = "funkodex://oauth/hobbydb"
        const val SCOPE         = "read:items read:prices"

        // Vaulted status endpoint — available once authenticated
        const val VAULTED_URL   = "https://hobby-db.com/api/v1/items/vaulted?page="

        // Price history endpoint
        const val PRICE_URL     = "https://hobby-db.com/api/v1/items/{id}/prices"
    }

    // ── eBay ──────────────────────────────────────────────────────────────────
    object eBay {
        const val AUTH_URL      = "https://auth.ebay.com/oauth2/authorize"
        const val TOKEN_URL     = "https://api.ebay.com/identity/v1/oauth2/token"
        const val CLIENT_ID     = "FunkoDex-FunkoDex-PRD-xxxxxxxx-xxxxxxxx"  // set via BuildConfig after eBay app approval
        const val REDIRECT_URI  = "funkodex://oauth/ebay"
        const val SCOPE         = "https://api.ebay.com/oauth/api_scope/buy.browse"

        // Browse API — sold listings (higher quality than RSS)
        const val SOLD_URL      = "https://api.ebay.com/buy/browse/v1/item_summary/search"
    }
}
