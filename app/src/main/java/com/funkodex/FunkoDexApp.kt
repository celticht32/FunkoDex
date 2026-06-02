package com.funkodex

import android.app.Application
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.couchbase.lite.CouchbaseLite
import com.funkodex.data.preload.CatalogPreloader
import com.funkodex.data.preload.PreloadResult
import com.funkodex.network.ConnectivityObserver
import androidx.hilt.work.HiltWorkerFactory
import com.funkodex.data.backup.DriveBackupWorker
import com.funkodex.util.CrashHandler
import com.funkodex.util.FunkoDexLogger
import com.funkodex.util.LogLevel
import com.funkodex.data.preload.CatalogRefreshWorker
import com.funkodex.auth.TokenKeeperWorker
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
    @Inject lateinit var userPrefs:             com.funkodex.ui.screens.settings.UserPreferencesRepository
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

        // 0. Install crash handler — must be absolute first so pre-init crashes are captured
        CrashHandler.install(this)

        // 0b. Init logger with default level — updated from DataStore once prefs are ready
        FunkoDexLogger.init(this, LogLevel.DEFAULT)
        FunkoDexLogger.i("FunkoDexApp", "App starting")

        // 1. Initialise Couchbase Lite — must be first
        CouchbaseLite.init(this)

        // 1b. Configure Coil image loader — F-PERF-1
        //     30% memory, full disk cache, crossfade globally for smooth loads
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .memoryCache {
                    MemoryCache.Builder(this)
                        .maxSizePercent(0.30)   // 30% of available heap (default is 25%)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(cacheDir.resolve("image_cache"))
                        .maxSizePercent(0.02)   // ~2% of disk space
                        .build()
                }
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(true)                // animate all image loads globally
                .crossfade(150)                 // 150ms — snappy but smooth
                .build()
        )

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

        // 4c. Schedule weekly OAuth token refresh (keeps HobbyDB + eBay sessions alive)
        // Proactively refreshes refresh tokens before they expire (~18 months for eBay).
        // Uses KEEP policy — does not reset the weekly interval on every launch.
        TokenKeeperWorker.schedule(this)

        // 5a. Sync persisted log level from DataStore (updates FunkoDexLogger at runtime)
        appScope.launch {
            userPrefs.logLevel.collect { level ->
                FunkoDexLogger.setLevel(level)
            }
        }

        // 5b. Preload the Funko catalog in the background.
        //    Runs during the splash screen — typically completes within 1.8s on
        //    first install; subsequent launches skip immediately (marker doc check).
        appScope.launch {
            when (val result = catalogPreloader.preloadIfNeeded()) {
                is PreloadResult.Loaded        ->
                    FunkoDexLogger.i("FunkoDexApp", "Catalog loaded: ${result.count} items")
                is PreloadResult.AlreadyLoaded ->
                    FunkoDexLogger.d("FunkoDexApp", "Catalog already present: ${result.count} items")
                is PreloadResult.AssetMissing  ->
                    FunkoDexLogger.w("FunkoDexApp", "funko_data.json not found — catalog lookup will use network only")
                is PreloadResult.ParseError    ->
                    FunkoDexLogger.e("FunkoDexApp", "Catalog parse error: ${result.message}")
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

        FunkoDexLogger.d("FunkoDexApp", "Notification channels created")

        // F-PLAT-4: Register quick-scan home screen shortcut (long-press app icon)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N_MR1) {
            val sm = getSystemService(ShortcutManager::class.java)
            if (sm != null && sm.dynamicShortcuts.none { it.id == "quick_scan" }) {
                val scanIntent = Intent(this, com.funkodex.MainActivity::class.java).apply {
                    action = "com.funkodex.ACTION_QUICK_SCAN"
                    flags  = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val shortcut = ShortcutInfo.Builder(this, "quick_scan")
                    .setShortLabel("Scan")
                    .setLongLabel("Scan a Funko barcode")
                    .setIcon(Icon.createWithResource(this, android.R.drawable.ic_menu_camera))
                    .setIntent(scanIntent)
                    .build()
                runCatching { sm.setDynamicShortcuts(listOf(shortcut)) }
            }
        }
    }
}
