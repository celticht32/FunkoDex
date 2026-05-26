package com.funkodex.auth

import android.content.Context
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.funkodex.util.FunkoDexLogger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * TokenKeeperWorker
 *
 * Weekly background job that proactively refreshes OAuth tokens for all
 * connected providers (HobbyDB, eBay) before they expire naturally.
 *
 * Motivation:
 *   eBay access tokens last 2 hours — handled by TokenRefreshManager on-demand.
 *   eBay refresh tokens last 18 months. If the user doesn't open the app for
 *   weeks, the refresh token itself can expire, forcing a full re-authentication.
 *   A weekly refresh keeps both provider sessions alive indefinitely while the
 *   app is installed.
 *
 * Behaviour:
 *   - Runs weekly on any network connection (Wi-Fi or mobile).
 *   - Skips any provider that has no stored token (user hasn't connected).
 *   - On 400/401 (refresh token revoked): clears the token and logs a warning —
 *     the user will need to re-authenticate from Settings > Data Sources.
 *   - On other errors (network outage): retries with exponential backoff.
 *     This is a best-effort job — missing a weekly run is not critical.
 *   - Uses KEEP policy so the interval is not reset on every app launch.
 *
 * NOTE: Uses @HiltWorker / @AssistedInject.
 *       HiltWorkerFactory must be set in WorkManager Configuration (FunkoDexApp).
 */
@HiltWorker
class TokenKeeperWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val tokenRefresh: TokenRefreshManager,
) : CoroutineWorker(context, params) {

    // ─── F-AUTH-2: Re-authentication notification ─────────────────────────────

    private fun sendReauthNotification(context: Context, provider: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        }

        // Deep-link to Settings > Data Sources
        val settingsIntent = Intent(context, com.funkodex.MainActivity::class.java).apply {
            action  = "com.funkodex.ACTION_OPEN_SETTINGS"
            flags   = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, provider.hashCode(), settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, "backup_status")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("$provider sign-in expired")
            .setContentText("Tap to re-connect $provider in Settings → Data Sources")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val nm = context.getSystemService(NotificationManager::class.java)
        nm?.notify(provider.hashCode(), notification)
        FunkoDexLogger.i(TAG, "Re-auth notification sent for $provider")
    }

    companion object {
        const val WORK_NAME = "token_keeper"
        private const val TAG = "TokenKeeperWorker"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TokenKeeperWorker>(7, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            FunkoDexLogger.i(TAG, "Token keeper worker scheduled (weekly)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            FunkoDexLogger.i(TAG, "Token keeper worker cancelled")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        FunkoDexLogger.i(TAG, "Token keeper starting proactive refresh check")

        var refreshed = 0
        var failed    = 0

        // ── HobbyDB ──────────────────────────────────────────────────────────
        val hobbyResult = tokenRefresh.getValidHobbyDbToken()
        when {
            hobbyResult != null -> {
                FunkoDexLogger.i(TAG, "HobbyDB token is valid / refreshed")
                refreshed++
            }
            tokenRefresh.hasHobbyDbRefreshToken() -> {
                // Had a token but refresh failed (cleared by TokenRefreshManager)
                FunkoDexLogger.w(TAG, "HobbyDB refresh failed — user needs to re-authenticate")
                sendReauthNotification(applicationContext, "HobbyDB")
                failed++
            }
            else -> FunkoDexLogger.d(TAG, "HobbyDB not connected — skipping")
        }

        // ── eBay ─────────────────────────────────────────────────────────────
        val ebayResult = tokenRefresh.getValidEbayToken()
        when {
            ebayResult != null -> {
                FunkoDexLogger.i(TAG, "eBay token is valid / refreshed")
                refreshed++
            }
            tokenRefresh.hasEbayRefreshToken() -> {
                FunkoDexLogger.w(TAG, "eBay refresh failed — user needs to re-authenticate")
                sendReauthNotification(applicationContext, "eBay")
                failed++
            }
            else -> FunkoDexLogger.d(TAG, "eBay not connected — skipping")
        }

        FunkoDexLogger.i(TAG, "Token keeper done: $refreshed refreshed, $failed failed")

        // If refresh failed for a connected provider, retry — the network might
        // have been briefly unavailable. Exponential backoff prevents hammering.
        if (failed > 0) Result.retry() else Result.success(
            workDataOf("refreshed" to refreshed, "failed" to failed)
        )
    }
}
