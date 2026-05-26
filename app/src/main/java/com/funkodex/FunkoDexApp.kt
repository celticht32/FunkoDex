package com.funkodex

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.couchbase.lite.CouchbaseLite
import com.funkodex.data.preload.CatalogPreloader
import com.funkodex.data.preload.PreloadResult
import com.funkodex.network.ConnectivityObserver
import androidx.hilt.work.HiltWorkerFactory
import com.funkodex.data.backup.DriveBackupWorker
import com.funkodex.data.preload.CatalogRefreshWorker
import com.funkodex.data.model.CatalogRefreshConfig
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class FunkoDexApp : Application(), Configuration.Provider {

    @Inject lateinit var catalogPreloader:      CatalogPreloader
    @Inject lateinit var connectivityObserver:  ConnectivityObserver
    @Inject lateinit var workerFactory:         HiltWorkerFactory

    // Required by Configuration.Provider — lets Hilt inject dependencies into Workers
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // 1. Initialise Couchbase Lite — must be first
        CouchbaseLite.init(this)

        // 2. Create notification channels (Android 8+ requirement)
        createNotificationChannels()

        // 3. Register connectivity observer for offline UPC queue (A5)
        connectivityObserver.register()

        // 4a. Schedule periodic catalog refresh (default: 7 days, WiFi only)
        // Uses KEEP policy so existing schedules are not reset on every launch
        CatalogRefreshWorker.schedule(this, CatalogRefreshConfig())

        // 4b. Schedule daily price alert check (D3)
        com.funkodex.data.preload.PriceAlertWorker.schedule(this)

        // 4b. Schedule daily Drive backup if user has connected Google Drive (E2)
        // Only schedules if a Google account is signed in — otherwise no-ops
        DriveBackupWorker.schedule(this)

        // 5. Preload the Funko catalog in the background.
        //    Runs during the splash screen — typically completes within 1.8s on
        //    first install; subsequent launches skip immediately (marker doc check).
        appScope.launch {
            when (val result = catalogPreloader.preloadIfNeeded()) {
                is PreloadResult.Loaded        ->
                    Log.i("FunkoDex", "Catalog loaded: ${result.count} items")
                is PreloadResult.AlreadyLoaded ->
                    Log.d("FunkoDex", "Catalog already present: ${result.count} items")
                is PreloadResult.AssetMissing  ->
                    Log.w("FunkoDex",
                        "funko_data.json not found — catalog lookup will use network only")
                is PreloadResult.ParseError    ->
                    Log.e("FunkoDex", "Catalog parse error: ${result.message}")
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        connectivityObserver.unregister()
    }

    // ── Notification channels ─────────────────────────────────────────────────

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val nm = getSystemService(NotificationManager::class.java)

        // Channel for offline UPC queue results (A5)
        nm.createNotificationChannel(
            NotificationChannel(
                "pending_scans",
                "Pending scan results",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Notifies when offline UPC scans have been identified after reconnecting"
            }
        )

        // Channel for price alerts (Phase D)
        nm.createNotificationChannel(
            NotificationChannel(
                "price_alerts",
                "Price alerts",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Notifies when a wanted Funko drops below your target price"
            }
        )

        // Channel for backup status (Phase E)
        nm.createNotificationChannel(
            NotificationChannel(
                "backup_status",
                "Backup status",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows progress and status of cloud database backups"
            }
        )

        Log.d("FunkoDex", "Notification channels created")
    }
}
