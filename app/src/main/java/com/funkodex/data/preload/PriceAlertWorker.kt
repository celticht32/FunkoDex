package com.funkodex.data.preload

import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.funkodex.util.FunkoDexLogger
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.funkodex.MainActivity
import com.funkodex.data.repository.AlertRepository
import com.funkodex.network.PriceService
import androidx.hilt.work.HiltWorker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * PriceAlertWorker — D3
 *
 * Daily background WorkManager job.
 * For each active PriceAlert:
 *   1. Fetch current market price via PriceService (Tier 2a eBay RSS preferred)
 *   2. If marketLow ≤ targetPrice AND not already notified today: fire notification
 *   3. Mark alert as triggered so we don't spam the user
 *
 * Runs once per day on any network (WiFi or mobile data — price data is small).
 * Retries with exponential backoff if network is unavailable.
 *
 * Notification taps deep-link to MainActivity with the item ID as an extra,
 * which FunkoDexNavHost will route to the Detail screen.
 *
 * NOTE: Uses AssistedInject because WorkManager creates workers via its own
 * factory, not Hilt's component. The HiltWorkerFactory must be set in
 * WorkManager's Configuration (see FunkoDexApp).
 */
@HiltWorker
class PriceAlertWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val alertRepository: AlertRepository,
    private val priceService: PriceService,
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME          = "price_alert_check"
        const val CHANNEL_ID         = "price_alerts"
        private const val TAG        = "PriceAlertWorker"
        private const val NOTIF_BASE = 2000   // notification ID base (avoid collision with 1001)

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PriceAlertWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,   // don't reset interval if already scheduled
                request
            )
            FunkoDexLogger.i(TAG, "Price alert worker scheduled (daily)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        FunkoDexLogger.d(TAG, "Price alert check started")
        try {
            val alerts = alertRepository.getActiveAlerts()
            FunkoDexLogger.d(TAG, "Checking ${alerts.size} active alert(s)")

            var notifiedCount = 0
            for (alert in alerts) {
                if (alert.notifiedToday) {
                    FunkoDexLogger.d(TAG, "Already notified today for ${alert.itemName} — skipping")
                    continue
                }

                // Build a minimal FunkoItem for the price service
                val pseudoItem = com.funkodex.data.model.FunkoItem(
                    id   = alert.itemId,
                    name = alert.itemName,
                    upc  = alert.upc,         // enables UPC-based price tiers (2b UPCitemdb, 2c Channel3)
                )

                val snapshot = try {
                    priceService.fetchPrice(pseudoItem)
                } catch (e: Exception) {
                    FunkoDexLogger.w(TAG, "Price fetch failed for ${alert.itemName}: ${e.message}")
                    null
                }

                val marketLow = snapshot?.low ?: continue
                if (marketLow > 0 && marketLow <= alert.targetPrice) {
                    sendNotification(alert, marketLow, notifiedCount)
                    alertRepository.markTriggered(alert.itemId)
                    notifiedCount++
                    FunkoDexLogger.i(TAG, "Alert fired: ${alert.itemName} @ $${"%.2f".format(marketLow)}")
                } else {
                    FunkoDexLogger.d(TAG, "${alert.itemName}: low=$${"%.2f".format(marketLow)} vs target=$${"%.2f".format(alert.targetPrice)}")
                }
            }

            FunkoDexLogger.d(TAG, "Price alert check complete: $notifiedCount notification(s) sent")
            Result.success(workDataOf("notified" to notifiedCount))
        } catch (e: Exception) {
            FunkoDexLogger.e(TAG, "Price alert worker failed: ${e.message}", e)
            Result.retry()
        }
    }

    private fun sendNotification(
        alert:     com.funkodex.data.model.PriceAlert,
        currentLow: Double,
        index:     Int,
    ) {
        // Android 13+ requires POST_NOTIFICATIONS runtime permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                applicationContext, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                FunkoDexLogger.d(TAG, "POST_NOTIFICATIONS not granted — skipping alert notification")
                return
            }
        }

        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager

        // Deep-link intent: open the item's Detail screen
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("NAVIGATE_TO_ITEM", alert.itemId)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            NOTIF_BASE + index,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Price drop: ${alert.itemName}")
            .setContentText(
                "Market low is now $${"%.2f".format(currentLow)}" +
                " (your target: $${"%.2f".format(alert.targetPrice)})"
            )
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(
                    "${alert.itemName} is available for $${"%.2f".format(currentLow)}. " +
                    "Your target price was $${"%.2f".format(alert.targetPrice)}. " +
                    "Tap to view the item."
                )
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        nm.notify(NOTIF_BASE + index, notification)
    }
}
