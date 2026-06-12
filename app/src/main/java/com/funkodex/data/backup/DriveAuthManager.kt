package com.funkodex.data.backup

import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DriveAuthManager
 *
 * Single owner of the AuthorizationClient interaction for Google Drive backup.
 * Replaces the deprecated GoogleSignIn / GoogleAccountCredential path.
 *
 * Authorization model (verified against Google identity docs, 2025-10 / 2026-03):
 *  - First authorize(): may return hasResolution()==true with a PendingIntent the
 *    CALLER must launch from an Activity (consent screen). Workers cannot do this.
 *  - Subsequent authorize(): returns a fresh ~1h access token with NO user
 *    interaction while the grant stands.
 *
 * MIT License — Copyright (c) 2026 Chris Ahrendt
 */
@Singleton
class DriveAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val client = Identity.getAuthorizationClient(context)

    private val request: AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DriveScopes.DRIVE_FILE)))
        .build()

    /** Result of an authorization attempt, normalized for callers. */
    sealed class DriveAuth {
        /** Token in hand — proceed. */
        data class Authorized(val accessToken: String) : DriveAuth()
        /** User consent required — only an Activity can launch this. */
        data class NeedsConsent(val pendingIntent: PendingIntent) : DriveAuth()
        /** Authorization failed (API error, no token, etc.). */
        data class Failed(val reason: String) : DriveAuth()
    }

    /**
     * Attempt authorization. Safe to call from a worker: never shows UI itself.
     * Null/blank token with no resolution is treated as Failed (see spec §6 item 3).
     */
    suspend fun authorize(): DriveAuth = try {
        val result: AuthorizationResult = client.authorize(request).await()
        when {
            result.hasResolution() -> {
                val pi = result.pendingIntent
                if (pi != null) DriveAuth.NeedsConsent(pi)
                else DriveAuth.Failed("Resolution required but no PendingIntent")
            }
            !result.accessToken.isNullOrBlank() ->
                DriveAuth.Authorized(result.accessToken!!)
            else -> DriveAuth.Failed("No access token and no resolution")
        }
    } catch (e: Exception) {
        DriveAuth.Failed(e.message ?: e.javaClass.simpleName)
    }

    /** Extract the result after the consent PendingIntent returns to the launcher. */
    fun resultFromConsentIntent(data: android.content.Intent?): DriveAuth = try {
        val result = client.getAuthorizationResultFromIntent(data)
        if (!result.accessToken.isNullOrBlank()) DriveAuth.Authorized(result.accessToken!!)
        else DriveAuth.Failed("Consent completed but no access token")
    } catch (e: Exception) {
        DriveAuth.Failed(e.message ?: e.javaClass.simpleName)
    }

    /** Drop a stale token from the local cache (call on Drive 401/403). Best-effort. */
    suspend fun clearToken(token: String) {
        try {
            client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
        } catch (_: Exception) { /* best-effort */ }
    }
}
