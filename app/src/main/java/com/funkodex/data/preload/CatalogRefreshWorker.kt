package com.funkodex.data.preload

import android.content.Context
import com.funkodex.util.FunkoDexLogger
import androidx.work.*
import com.couchbase.lite.CouchbaseLite
import com.couchbase.lite.UnitOfWork
import com.couchbase.lite.MutableDocument
import com.funkodex.data.db.FunkoDexDatabase
import com.funkodex.security.SecureKeyStore
import com.funkodex.auth.TokenRefreshManager
import com.funkodex.auth.OAuthConfig
import com.funkodex.data.model.CatalogRefreshConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * CatalogRefreshWorker
 *
 * Background WorkManager job that periodically fetches the latest Kenny Chan
 * Funko Pop dataset from GitHub and merges new/updated records into the
 * local Couchbase catalog.
 *
 * Strategy:
 *   - Only creates documents for handles not already in the DB (new items)
 *   - Never overwrites existing docs (preserves any Channel3 enrichment)
 *   - Updates the refresh marker after each successful run
 */
class CatalogRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        // TAG is companion-level so it can be referenced from both companion and instance methods
        const val TAG = "CatalogRefreshWorker"
        const val WORK_NAME         = "catalog_refresh"
        const val KEY_WIFI_ONLY     = "wifi_only"
        const val KEY_INTERVAL_DAYS = "interval_days"

        private const val KENNY_CHAN_URL =
            "https://raw.githubusercontent.com/kennymkchan/funko-pop-data/master/funko_pop.json"

        private const val COMMUNITY_UPC_URL =
            "https://raw.githubusercontent.com/celticht32/funko-upc-community/main/funko_upc_community.json"

        /**
         * Shared OkHttpClient for this worker.
         * Lazy so it is only created when the worker actually runs.
         */
        val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        fun schedule(context: Context, config: CatalogRefreshConfig) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (config.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                )
                .build()

            val request = PeriodicWorkRequestBuilder<CatalogRefreshWorker>(
                config.intervalDays.toLong(), TimeUnit.DAYS
            )
                .setConstraints(constraints)
                .setInputData(workDataOf(
                    KEY_WIFI_ONLY     to config.wifiOnly,
                    KEY_INTERVAL_DAYS to config.intervalDays,
                ))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            FunkoDexLogger.i(TAG, "Scheduled: every ${config.intervalDays}d wifi=${config.wifiOnly}")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<CatalogRefreshWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // Workers can be started in a fresh process; init must be called first.
            CouchbaseLite.init(applicationContext)
            FunkoDexLogger.i(TAG, "Starting catalog refresh…")
            val newCount = refreshKennyChan()
            FunkoDexLogger.i(TAG, "Refresh complete: $newCount new catalog records added")

            val upcsMerged = refreshCommunityUpcFile()
            FunkoDexLogger.i(TAG, "Community UPC file: $upcsMerged UPCs merged into catalog")

            val localSecureKeyStore = SecureKeyStore(applicationContext)
            val localTokenRefresh   = TokenRefreshManager(localSecureKeyStore, sharedClient)
            val hobbyToken = localTokenRefresh.getValidHobbyDbToken()
            val vaultedCount = if (hobbyToken != null) {
                refreshVaultedStatus(hobbyToken)
            } else 0
            if (vaultedCount > 0) FunkoDexLogger.i(TAG, "Vaulted status updated: $vaultedCount items")

            Result.success(workDataOf("new_items" to newCount, "upcs_merged" to upcsMerged, "vaulted_updated" to vaultedCount))
        } catch (e: Exception) {
            FunkoDexLogger.e(TAG, "Refresh failed: ${e.message}", e)
            Result.retry()
        }
    }

    private suspend fun refreshKennyChan(): Int = withContext(Dispatchers.IO) {
        val response = sharedClient.newCall(
            Request.Builder()
                .url(KENNY_CHAN_URL)
                .header("User-Agent", "FunkoDex/1.0 Android")
                .build()
        ).execute()

        if (!response.isSuccessful) {
            FunkoDexLogger.w(TAG, "Kenny Chan fetch failed: ${response.code}")
            return@withContext 0
        }

        val json    = response.body?.string() ?: return@withContext 0
        val gson    = Gson()
        val type    = object : TypeToken<List<CatalogPreloader.KennyRecord>>() {}.type
        val records: List<CatalogPreloader.KennyRecord> = gson.fromJson(json, type)

        val db       = FunkoDexDatabase(applicationContext)
        db.ensureIndexes()
        val database = db.getDatabase()
        val collection = db.getCollection()

        var newCount = 0

        database.inBatch(UnitOfWork {
            records.forEach { record ->
                val handle = record.handle?.trim() ?: return@forEach
                val docId  = "catalog::$handle"

                if (collection.getDocument(docId) != null) return@forEach

                val mapped = CatalogMapper.mapRecord(
                    handle     = handle,
                    title      = record.title?.trim() ?: return@forEach,
                    imageName  = record.imageName?.trim() ?: "",
                    seriesList = record.series ?: emptyList(),
                    source     = "KENNY_CHAN",
                )

                collection.save(MutableDocument(docId, mapped))
                newCount++
            }
        })

        val marker = collection.getDocument(CatalogPreloader.MARKER_DOC)?.toMutable()
            ?: MutableDocument(CatalogPreloader.MARKER_DOC)
        marker.setString("lastRefreshed", LocalDate.now().toString())
        marker.setInt("totalRecords", records.size)
        collection.save(marker)

        newCount
    }

    /**
     * Download and merge the community UPC file.
     * "Better" data wins: CHANNEL3 source is never overwritten by USER_SCAN.
     */
    private suspend fun refreshCommunityUpcFile(): Int = withContext(Dispatchers.IO) {
        try {
            val response = sharedClient.newCall(
                Request.Builder()
                    .url(COMMUNITY_UPC_URL)
                    .header("User-Agent", "FunkoDex/1.0 Android")
                    .build()
            ).execute()

            if (!response.isSuccessful) {
                FunkoDexLogger.w(TAG, "Community UPC fetch failed: ${response.code}")
                return@withContext 0
            }

            val json    = response.body?.string() ?: return@withContext 0
            val type    = object : TypeToken<Array<Map<String, Any?>>>() {}.type
            val records = Gson().fromJson<Array<Map<String, Any?>>>(json, type)
                ?: return@withContext 0

            val db       = FunkoDexDatabase(applicationContext)
            val database = db.getDatabase()
            val collection = db.getCollection()
            var merged   = 0

            database.inBatch(UnitOfWork {
                for (record in records) {
                    val upc    = record["upc"] as? String ?: continue
                    val handle = record["handle"] as? String ?: continue
                    if (upc.isBlank() || handle.isBlank()) continue

                    val docId  = "catalog::$handle"
                    val source = record["source"] as? String ?: "USER_SCAN"

                    val existing = collection.getDocument(docId)
                    if (existing != null) {
                        val existingUpc    = existing.getString("upc") ?: ""
                        val existingSource = existing.getString(CatalogMapper.FIELD_SOURCE) ?: "KENNY_CHAN"
                        val betterSource   = sourceRank(source) > sourceRank(existingSource)

                        if (existingUpc.isBlank() || betterSource) {
                            val mut = existing.toMutable()
                            mut.setString("upc", upc)
                            if (betterSource) mut.setString(CatalogMapper.FIELD_SOURCE, source)
                            collection.save(mut)
                            merged++
                        }
                    } else {
                        val mapped = CatalogMapper.mapRecord(
                            handle     = handle,
                            title      = record["name"] as? String ?: handle,
                            imageName  = record["imageUrl"] as? String ?: "",
                            seriesList = listOfNotNull(record["franchise"] as? String,
                                                       record["category"] as? String),
                            upc        = upc,
                            price      = (record["retailPrice"] as? Number)?.toDouble() ?: 0.0,
                            vaulted    = record["isVaulted"] as? Boolean ?: false,
                            source     = source,
                        )
                        collection.save(MutableDocument(docId, mapped))
                        merged++
                    }
                }
            })
            merged
        } catch (e: Exception) {
            FunkoDexLogger.e(TAG, "Community UPC refresh failed: ${e.message}", e)
            0
        }
    }

    /**
     * Fetch vaulted status from HobbyDB and update catalog docs where isVaulted changed.
     * Only called when a valid HobbyDB OAuth token is available.
     */
    private suspend fun refreshVaultedStatus(hobbyToken: String): Int = withContext(Dispatchers.IO) {
        var updatedCount = 0
        try {
            if (hobbyToken.isEmpty()) return@withContext 0

            val db       = FunkoDexDatabase(applicationContext)
            val database = db.getDatabase()
            val collection = db.getCollection()
            var page     = 1
            var hasMore  = true

            while (hasMore) {
                val url = "${OAuthConfig.HobbyDb.VAULTED_URL}$page&per_page=100"
                val response = sharedClient.newCall(
                    Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $hobbyToken")
                        .header("User-Agent", "FunkoDex/1.0 Android")
                        .build()
                ).execute()

                if (!response.isSuccessful) {
                    FunkoDexLogger.w(TAG, "HobbyDB vaulted endpoint: HTTP ${response.code}")
                    break
                }

                val body  = response.body?.string() ?: break
                val json  = com.google.gson.JsonParser.parseString(body).asJsonObject
                val items = json.getAsJsonArray("items") ?: break

                if (items.size() == 0) { hasMore = false; break }

                database.inBatch(UnitOfWork {
                    items.forEach { el ->
                        val obj    = el.asJsonObject
                        val handle = obj.optString("handle") ?: return@forEach
                        val docId  = "${FunkoDexDatabase.TYPE_CATALOG}::$handle"
                        val doc    = collection.getDocument(docId) ?: return@forEach
                        if (!doc.getBoolean(FunkoDexDatabase.FIELD_IS_VAULTED)) {
                            collection.save(doc.toMutable().apply {
                                setBoolean(FunkoDexDatabase.FIELD_IS_VAULTED, true)
                            })
                            updatedCount++
                        }
                    }
                })

                hasMore = items.size() == 100
                page++
            }
        } catch (e: Exception) {
            FunkoDexLogger.e(TAG, "Failed to refresh vaulted status", e)
        }
        updatedCount
    }

    private fun sourceRank(source: String): Int = when (source) {
        "CHANNEL3"            -> 3
        "USER_SCAN_CHANNEL3"  -> 2
        "USER_SCAN"           -> 1
        else                  -> 0
    }

    // ─── helper ───────────────────────────────────────────────────────────────
    private fun com.google.gson.JsonObject?.optString(key: String): String? =
        try { this?.get(key)?.asString } catch (_: Exception) { null }
}

/**
 * Convenience helper — called by CatalogSettingsViewModel when settings change.
 */
object RefreshScheduler {
    fun applyConfig(context: Context, config: CatalogRefreshConfig) {
        if (config.enabled) {
            CatalogRefreshWorker.schedule(context, config)
        } else {
            CatalogRefreshWorker.cancel(context)
        }
    }
}
