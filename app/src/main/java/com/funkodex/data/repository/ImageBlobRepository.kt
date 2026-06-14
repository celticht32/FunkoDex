package com.funkodex.data.repository

import com.funkodex.util.FunkoDexLogger
import com.couchbase.lite.Blob
import com.funkodex.data.db.FunkoDexDatabase
import com.funkodex.data.model.FunkoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ImageBlobRepository — A7
 *
 * Downloads the HobbyDB image for an owned Funko item and stores it
 * as a Couchbase Blob on the funko:: document.
 *
 * Called silently from ScannerViewModel.confirmAdd() after the item
 * is saved — the user never sees this step.  Once stored, the
 * collection grid reads the blob directly so the image works fully
 * offline with no CDN dependency.
 *
 * Strategy:
 *   - All Kenny Chan URLs end in _large.jpg (300KB average)
 *   - We store _large directly — no thumbnail variant confirmed
 *   - Size for 200-item collection ≈ 30–60 MB
 *   - Blob is skipped if imageUrl is empty or already downloaded
 */
@Singleton
class ImageBlobRepository @Inject constructor(
    private val db: FunkoDexDatabase,
    private val client: OkHttpClient,
) {
    companion object {
        private const val MAX_BYTES  = 600_000   // 600KB safety cap — skip oversized images
        private const val TAG        = "ImageBlob"
    }

    /**
     * Download and store the image blob for the given item.
     * Silently no-ops if the item has no imageUrl or already has a blob.
     */
    suspend fun downloadAndStore(item: FunkoItem): Boolean =
        downloadAndStoreResult(item) is ImageFetchResult.Success

    /**
     * Like [downloadAndStore] but returns a specific outcome so callers (e.g. the
     * detail-screen "Fetch from catalog" action) can show an accurate error
     * instead of a generic "could not download" with a list of maybe-causes.
     */
    suspend fun downloadAndStoreResult(item: FunkoItem): ImageFetchResult = withContext(Dispatchers.IO) {
        if (item.imageUrl.isEmpty()) return@withContext ImageFetchResult.NoUrl
        if (item.id.isEmpty())       return@withContext ImageFetchResult.NoUrl

        val collection = db.getCollection()

        // Skip if blob already stored
        val existing = collection.getDocument(item.id)
        if (existing?.getBlob(FunkoDexDatabase.FIELD_THUMBNAIL_BLOB) != null) {
            return@withContext ImageFetchResult.Success
        }

        return@withContext runCatching {
            val request = Request.Builder()
                .url(item.imageUrl)
                .header("User-Agent", "FunkoDex/1.0 Android (image cache)")
                .build()

            val bytes = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    FunkoDexLogger.w(TAG, "Image fetch failed ${response.code} for ${item.name}")
                    return@runCatching ImageFetchResult.HttpError(response.code)
                }
                response.body?.bytes() ?: return@runCatching ImageFetchResult.EmptyBody
            }
            if (bytes.size > MAX_BYTES) {
                FunkoDexLogger.w(TAG, "Image too large (${bytes.size}B) for ${item.name} — skipping")
                return@runCatching ImageFetchResult.TooLarge(bytes.size)
            }

            // Detect MIME type from magic bytes
            val mimeType = when {
                bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte() -> "image/jpeg"
                bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte() -> "image/png"
                else -> "image/jpeg"   // HobbyDB CDN serves JPEG
            }

            // Upsert the blob onto the existing document
            val doc = collection.getDocument(item.id)?.toMutable()
                ?: return@runCatching ImageFetchResult.NoUrl
            doc.setBlob(FunkoDexDatabase.FIELD_THUMBNAIL_BLOB, Blob(mimeType, bytes))
            collection.save(doc)

            FunkoDexLogger.d(TAG, "Stored ${bytes.size}B image for ${item.name}")
            ImageFetchResult.Success
        }.getOrElse { e ->
            FunkoDexLogger.e(TAG, "Image download failed for ${item.name}: ${e.message}")
            ImageFetchResult.NetworkError(e.message ?: "unknown error")
        }
    }
}

/** Specific outcome of an image fetch, so the UI can show an accurate reason. */
sealed class ImageFetchResult {
    object Success : ImageFetchResult()
    object NoUrl : ImageFetchResult()
    object EmptyBody : ImageFetchResult()
    data class HttpError(val code: Int) : ImageFetchResult()
    data class TooLarge(val bytes: Int) : ImageFetchResult()
    data class NetworkError(val message: String) : ImageFetchResult()
}
