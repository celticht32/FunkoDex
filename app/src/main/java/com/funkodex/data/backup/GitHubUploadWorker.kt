package com.funkodex.data.backup

import android.content.Context
import android.util.Log
import com.funkodex.util.FunkoDexLogger
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.funkodex.BuildConfig
import com.funkodex.data.repository.ContributionRepository
import com.funkodex.security.HmacKeyStore
import com.google.gson.Gson
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * GitHubUploadWorker — F2
 *
 * Batches pending CatalogContribution documents and POSTs them to the
 * Cloudflare Worker proxy, which validates and writes them to the
 * community GitHub repository.
 *
 * SECURITY:
 *   - Never calls GitHub directly — all writes go through the Worker
 *   - Each request is signed with HMAC-SHA256 using a per-install key
 *     stored in the hardware-backed Android Keystore (HmacKeyStore)
 *   - X-Device-ID header is a random UUID for rate-limiting only
 *   - Payload contains ONLY global product metadata — no personal data
 *   - If WORKER_URL is empty (not configured) the worker exits cleanly
 *
 * Runs once per day on any connected network.
 * Only executes when the user has opted in via Settings.
 *
 * Request format (matches Cloudflare Worker validation schema):
 *   POST {WORKER_URL}
 *   Headers: X-Device-ID, X-Timestamp, X-Signature
 *   Body: { "schemaVersion": 1, "contributions": [ ...CatalogContribution.toUploadMap()... ] }
 */
@HiltWorker
class GitHubUploadWorker @AssistedInject constructor(
    @Assisted context:   Context,
    @Assisted params:    WorkerParameters,
    private val contribRepo: ContributionRepository,
    private val hmacKeyStore: HmacKeyStore,
    private val client:  OkHttpClient,
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME  = "github_upload"
        private const val TAG = "GitHubUploadWorker"
        private val JSON_MT   = "application/json; charset=utf-8".toMediaType()

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<GitHubUploadWorker>(1, TimeUnit.DAYS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                    .build()
            )
            FunkoDexLogger.i(TAG, "Scheduled (daily)")
        }

        fun cancel(context: Context) =
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val workerUrl = BuildConfig.WORKER_URL
        if (workerUrl.isBlank()) {
            FunkoDexLogger.i(TAG, "WORKER_URL not configured — skipping upload")
            return@withContext Result.success(workDataOf("skipped" to "no_url"))
        }

        try {
            val pending = contribRepo.getPendingContributions()
            if (pending.isEmpty()) {
                FunkoDexLogger.d(TAG, "No pending contributions")
                return@withContext Result.success(workDataOf("uploaded" to 0))
            }

            FunkoDexLogger.d(TAG, "Uploading ${pending.size} contribution(s)")

            // Build payload — global meta only, no personal data
            val payload = mapOf(
                "schemaVersion"   to 1,
                "contributions"   to pending.map { it.toUploadMap() },
            )
            val bodyJson  = Gson().toJson(payload)
            val timestamp = System.currentTimeMillis().toString()
            val signature = hmacKeyStore.sign(bodyJson + timestamp)
            val installId = hmacKeyStore.getInstallId()

            val request = Request.Builder()
                .url(workerUrl)
                .post(bodyJson.toRequestBody(JSON_MT))
                .header("X-Device-ID",  installId)
                .header("X-Timestamp",  timestamp)
                .header("X-Signature",  signature)
                .header("User-Agent",   "FunkoDex/1.0 Android (community contrib)")
                .build()

            val response = client.newCall(request).execute()

            return@withContext if (response.isSuccessful) {
                // Mark all as uploaded
                pending.forEach { contribRepo.markUploaded(it.upc) }
                FunkoDexLogger.i(TAG, "Upload success: ${pending.size} contributions accepted")
                Result.success(workDataOf("uploaded" to pending.size))
            } else {
                val body = response.body?.string() ?: ""
                FunkoDexLogger.w(TAG, "Upload failed ${response.code}: $body")
                when (response.code) {
                    429  -> Result.retry()  // rate limited — retry with backoff
                    400  -> {               // schema error — mark all uploaded to avoid loop
                        pending.forEach { contribRepo.markUploaded(it.upc) }
                        Result.failure(workDataOf("error" to "schema_rejected"))
                    }
                    else -> Result.retry()
                }
            }
        } catch (e: Exception) {
            FunkoDexLogger.e(TAG, "Upload exception: ${e.message}", e)
            Result.retry()
        }
    }
}
