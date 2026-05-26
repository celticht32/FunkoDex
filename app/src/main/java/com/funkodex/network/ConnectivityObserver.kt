package com.funkodex.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.funkodex.util.FunkoDexLogger
import com.couchbase.lite.MutableDocument
import com.funkodex.data.db.FunkoDexDatabase
import com.funkodex.data.model.PendingUpcScan
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ConnectivityObserver — A5
 *
 * Watches for network connectivity restoration and processes any
 * pending UPC scans that were queued while offline.
 *
 * Registered in FunkoDexApp.onCreate(). When connectivity is regained,
 * it queries Couchbase for all pending_upc:: documents, looks each up
 * via FunkoLookupService, saves the resolved items, and posts a
 * notification summarising the results.
 *
 * The observer fires at most once per connectivity-restore event
 * (guarded by isProcessing flag).
 */
@Singleton
class ConnectivityObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: FunkoDexDatabase,
    private val lookup: FunkoLookupService,
) {
    private val scope         = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isProcessing  = false
    private var isRegistered  = false

    private val connectivityManager: ConnectivityManager
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            FunkoDexLogger.d("ConnectivityObserver", "Network available — processing pending UPC queue")
            processPendingQueue()
        }
    }

    fun register() {
        if (isRegistered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        isRegistered = true
        FunkoDexLogger.d("ConnectivityObserver", "Registered")
    }

    fun unregister() {
        if (!isRegistered) return
        connectivityManager.unregisterNetworkCallback(networkCallback)
        isRegistered = false
    }

    fun isConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps    = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // ── Process the pending queue ──────────────────────────────────────────────

    private fun processPendingQueue() {
        if (isProcessing) return
        isProcessing = true

        scope.launch {
            try {
                val pending = loadPendingScans()
                if (pending.isEmpty()) {
                    isProcessing = false
                    return@launch
                }

                FunkoDexLogger.i("ConnectivityObserver", "Processing ${pending.size} pending UPC scan(s)")
                var resolved = 0
                val resolvedItems = mutableListOf<String>()

                for (scan in pending) {
                    val item = try {
                        lookup.lookupByUpc(scan.upc)
                    } catch (e: Exception) {
                        FunkoDexLogger.w("ConnectivityObserver", "Lookup failed for ${scan.upc}: ${e.message}")
                        null
                    }

                    if (item != null) {
                        // Save as a want-list item — user can confirm ownership later
                        val database = db.getDatabase()
                        val docId    = "funko::${scan.upc}"
                        if (database.getDocument(docId) == null) {
                            val doc = MutableDocument(docId).apply {
                                setString("type",      FunkoDexDatabase.TYPE_FUNKO)
                                setString("upc",       scan.upc)
                                setString("name",      item.name)
                                setString("franchise", item.franchise)
                                setString("imageUrl",  item.imageUrl)
                                setBoolean("isOwned",  false) // want list — user confirms later
                                setString("dateAdded", java.time.LocalDate.now().toString())
                            }
                            database.save(doc)
                        }
                        // Remove the pending doc
                        database.getDocument(scan.docId)?.let { database.delete(it) }
                        resolvedItems.add(item.name)
                        resolved++
                    } else {
                        // Increment retry count — give up after 5 attempts
                        val database = db.getDatabase()
                        val pendingDoc = database.getDocument(scan.docId)?.toMutable()
                        if (pendingDoc != null) {
                            val retries = pendingDoc.getInt(PendingUpcScan.FIELD_RETRY)
                            if (retries >= 5) {
                                database.delete(pendingDoc) // give up
                            } else {
                                pendingDoc.setInt(PendingUpcScan.FIELD_RETRY, retries + 1)
                                database.save(pendingDoc)
                            }
                        }
                    }
                }

                if (resolved > 0) {
                    sendNotification(resolved, resolvedItems)
                }

                FunkoDexLogger.i("ConnectivityObserver", "Queue processed: $resolved/${pending.size} resolved")
            } finally {
                isProcessing = false
            }
        }
    }

    private fun loadPendingScans(): List<PendingUpcScan> {
        val database = db.getDatabase()
        val query = com.couchbase.lite.QueryBuilder
            .select(
                com.couchbase.lite.SelectResult.expression(com.couchbase.lite.Meta.id),
                com.couchbase.lite.SelectResult.property(PendingUpcScan.FIELD_UPC),
                com.couchbase.lite.SelectResult.property(PendingUpcScan.FIELD_DATE),
                com.couchbase.lite.SelectResult.property(PendingUpcScan.FIELD_RETRY),
            )
            .from(com.couchbase.lite.DataSource.database(database))
            .where(
                com.couchbase.lite.Expression.property(PendingUpcScan.FIELD_TYPE)
                    .equalTo(com.couchbase.lite.Expression.string(PendingUpcScan.TYPE_VAL))
            )

        return query.execute().use { rs ->
            rs.allResults().mapNotNull { row ->
                val upc = row.getString(PendingUpcScan.FIELD_UPC) ?: return@mapNotNull null
                PendingUpcScan(
                    upc        = upc,
                    retryCount = row.getInt(PendingUpcScan.FIELD_RETRY),
                )
            }
        }
    }

    private fun sendNotification(count: Int, names: List<String>) {
        try {
            // Android 13+ (API 33) requires POST_NOTIFICATIONS runtime permission.
            // Without it nm.notify() is a silent no-op; check first so we can log clearly.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    FunkoDexLogger.d("ConnectivityObserver", "POST_NOTIFICATIONS not granted — skipping notification")
                    return
                }
            }

            val nm    = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            val title = if (count == 1) "Funko scan identified" else "$count Funko scans identified"
            val body  = if (names.size == 1) names[0]
                        else "${names.take(2).joinToString(", ")} and ${count - 2} more added to want list"

            val notification = android.app.Notification.Builder(context, "pending_scans")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .build()

            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            FunkoDexLogger.w("ConnectivityObserver", "Could not send notification: ${e.message}")
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}
