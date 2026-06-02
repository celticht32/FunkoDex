package com.funkodex.data.preload

import android.content.Context
import com.funkodex.util.FunkoDexLogger
import androidx.work.*
import com.couchbase.lite.UnitOfWork
import com.couchbase.lite.MutableDocument
import com.funkodex.data.db.FunkoDexDatabase
import com.funkodex.security.SecureKeyStore
import com.funkodex.auth.TokenRefreshManager
import com.funkodex.auth.OAuthConfig
import com.funkodex.data.preload.CatalogMapper
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
 * A1 fix: the worker now actually writes new catalog documents using
 * CatalogMapper.mapRecord() — previously it only counted records.
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
        private const val TAG = "CatalogRefreshWorker"
        const val WORK_NAME         = "catalog_refresh"
        const val KEY_WIFI_ONLY     = "wifi_only"
        const val KEY_INTERVAL_DAYS = "interval_days"

        private const val KENNY_CHAN_URL =
            "https://raw.githubusercontent.com/kennymkchan/funko-pop-data/master/funko_pop.json"

        private const val COMMUNITY_UPC_URL =
            "https://raw.githubusercontent.com/celticht32/funko-upc-community/main/funko_upc_community.json"

        /**
         * Shared OkHttpClient for this worker — avoids creating a new client on every
         * request within a single worker run (3 HTTP calls per run: Kenny Chan, community
         * UPC, HobbyDB vaulted). Connection pooling is handled by OkHttp internally.
         * Lazy so it is only created when the worker actually runs.
         */
        val sharedClient: okhttp3.OkHttpClient by lazy {
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
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
            FunkoDexLogger.i("CatalogRefresh", "Scheduled: every ${config.intervalDays}d wifi=${config.wifiOnly}")
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
            FunkoDexLogger.i("CatalogRefresh", "Starting catalog refresh…")
            val newCount = refreshKennyChan()
            FunkoDexLogger.i("CatalogRefresh", "Refresh complete: $newCount new catalog records added")

            // F3: Also refresh the community UPC file
            val upcsMerged = refreshCommunityUpcFile()
            FunkoDexLogger.i("CatalogRefresh", "Community UPC file: $upcsMerged UPCs merged into catalog")

            // Refresh vaulted status from HobbyDB if authenticated (silent token refresh)
            val localSecureKeyStore = SecureKeyStore(applicationContext)
            val localTokenRefresh   = TokenRefreshManager(localSecureKeyStore, sharedClient)
            val hobbyToken = localTokenRefresh.getValidHobbyDbToken()
            val vaultedCount = if (hobbyToken != null) {
                refreshVaultedStatus(hobbyToken)
            } else 0
            if (vaultedCount > 0) FunkoDexLogger.i(TAG, "Vaulted status updated: $vaultedCount items")

            Result.success(workDataOf("new_items" to newCount, "upcs_merged" to upcsMerged, "vaulted_updated" to vaultedCount))
        } catch (e: Exception) {
            FunkoDexLogger.e("CatalogRefresh", "Refresh failed: ${e.message}", e)
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
            FunkoDexLogger.w("CatalogRefresh", "Kenny Chan fetch failed: ${response.code}")
            return@withContext 0
        }

        val json    = response.body?.string() ?: return@withContext 0
        val gson    = Gson()
        val type    = object : TypeToken<List<CatalogPreloader.KennyRecord>>() {}.type
        val records: List<CatalogPreloader.KennyRecord> = gson.fromJson(json, type)

        val db = FunkoDexDatabase(applicationContext)
        db.ensureIndexes()
        val database = db.getDatabase()

        var newCount = 0

        database.inBatch(UnitOfWork {
            records.forEach { record ->
                val handle = record.handle?.trim() ?: return@forEach
                val docId  = "catalog::$handle"

                // Only create — never overwrite existing docs
                // (preserves Channel3-enriched entries with UPCs, prices, etc.)
                if (database.getDocument(docId) != null) return@forEach

                val mapped = CatalogMapper.mapRecord(
                    handle     = handle,
                    title      = record.title?.trim() ?: return@forEach,
                    imageName  = record.imageName?.trim() ?: "",
                    seriesList = record.series ?: emptyList(), // KennyRecord.series = raw JSON tag list
                    source     = "KENNY_CHAN",
                )

                database.save(MutableDocument(docId, mapped))
                newCount++
            }
        })

        // Update marker
        val marker = database.getDocument(CatalogPreloader.MARKER_DOC)?.toMutable()
            ?: MutableDocument(CatalogPreloader.MARKER_DOC)
        marker.setString("lastRefreshed", LocalDate.now().toString())
        marker.setInt("totalRecords", records.size)
        database.save(marker)

        newCount
    }


    /**
     * F3: Download and merge the community UPC file.
     *
     * The file at COMMUNITY_UPC_URL is a JSON array of global meta records,
     * each with a "upc" field. For each record we:
     *   1. Find the existing catalog::{handle} doc — add the UPC to it
     *   2. If no matching doc exists — create a new catalog entry
     *
     * "Better" data wins: CHANNEL3 source is never overwritten by USER_SCAN.
     * Owned/personal data is never touched.
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
                FunkoDexLogger.w("CatalogRefresh", "Community UPC fetch failed: ${response.code}")
                return@withContext 0
            }

            val json    = response.body?.string() ?: return@withContext 0
            val records = com.google.gson.Gson().fromJson(
                json,
                Array<Map<String, Any?>>::class.java,
            ) ?: return@withContext 0

            val db      = FunkoDexDatabase(applicationContext)
            val database = db.getDatabase()
            var merged  = 0

            database.inBatch(UnitOfWork {
                for (record in records) {
                    val upc    = record["upc"] as? String ?: continue
                    val handle = record["handle"] as? String ?: continue
                    if (upc.isBlank() || handle.isBlank()) continue

                    val docId  = "catalog::$handle"
                    val source = record["source"] as? String ?: "USER_SCAN"

                    val existing = database.getDocument(docId)
                    if (existing != null) {
                        // Only add UPC if not already set, or if new source is better
                        val existingUpc    = existing.getString("upc") ?: ""
                        val existingSource = existing.getString(CatalogMapper.FIELD_SOURCE) ?: "KENNY_CHAN"
                        val betterSource   = sourceRank(source) > sourceRank(existingSource)

                        if (existingUpc.isBlank() || betterSource) {
                            val mut = existing.toMutable()
                            mut.setString("upc",  upc)
                            if (betterSource) mut.setString(CatalogMapper.FIELD_SOURCE, source)
                            database.save(mut)
                            merged++
                        }
                    } else {
                        // New catalog entry from community file
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
                        database.save(MutableDocument(docId, mapped))
                        merged++
                    }
                }
            })
            merged
        } catch (e: Exception) {
            FunkoDexLogger.e("CatalogRefresh", "Community UPC refresh failed: ${e.message}", e)
            0
        }
    }

    private fun sourceRank(source: String): Int = when (source) {
        "CHANNEL3"            -> 3
        "USER_SCAN_CHANNEL3"  -> 2
        "USER_SCAN"           -> 1
        else                  -> 0
    }
}

    /**
     * Fetch vaulted status from HobbyDB and update any catalog docs where isVaulted changed.
     * Only called when a valid HobbyDB OAuth token is available.
     * Returns the count of catalog docs updated.
     */
    private suspend fun refreshVaultedStatus(hobbyToken: String): Int = withContext(Dispatchers.IO) {
        var updatedCount = 0
        try {
            val token    = hobbyToken
            if (token.isEmpty()) return@withContext 0

            val database = db.getDatabase()
            var page     = 1
            var hasMore  = true

            while (hasMore) {
                val url = "${com.funkodex.auth.OAuthConfig.HobbyDb.VAULTED_URL}$page&per_page=100"
                val response = okHttpClient.newCall(
                    okhttp3.Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $token")
                        .header("User-Agent", "FunkoDex/1.0 Android")
                        .build()
                ).execute()

                if (!response.isSuccessful) {
                    FunkoDexLogger.w(TAG, "HobbyDB vaulted endpoint: HTTP ${response.code}")
                    break
                }

                val body = response.body?.string() ?: break
                val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                val items = json.getAsJsonArray("items") ?: break

                if (items.size() == 0) { hasMore = false; break }

                database.inBatch(UnitOfWork {
                    items.forEach { el ->
                        val obj    = el.asJsonObject
                        val handle = obj.optString("handle") ?: return@forEach
                        val docId  = "${FunkoDexDatabase.TYPE_CATALOG}::$handle"
                        val doc    = database.getDocument(docId) ?: return@forEach
                        if (doc.getBoolean(FunkoDexDatabase.FIELD_IS_VAULTED) != true) {
                            database.save(doc.toMutable().apply {
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

    // ─── helper ───────────────────────────────────────────────────────────────
    private fun com.google.gson.JsonObject?.optString(key: String): String? =
        try { this?.get(key)?.asString } catch (_: Exception) { null }

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
