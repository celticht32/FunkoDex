package com.funkodex.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import com.funkodex.util.FunkoDexLogger
import androidx.exifinterface.media.ExifInterface
import com.couchbase.lite.Blob
import com.funkodex.data.db.FunkoDexDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PhotoRepository — C2
 *
 * Handles user-taken box photos for Funko items.
 * Two capture paths:
 *   - Camera:  caller launches TakePicture contract → URI returned → call savePhoto(uri, itemId)
 *   - Gallery: caller launches GetContent contract  → URI returned → call savePhoto(uri, itemId)
 *
 * Storage: photos are stored as Couchbase Blobs on the funko:: document under
 * key "userPhoto". Separate from the auto-downloaded HobbyDB thumbnail blob.
 *
 * Processing:
 *   1. Decode the URI to a Bitmap
 *   2. Correct EXIF rotation so portrait photos display correctly
 *   3. Scale down to max 1024px on the long edge (preserves quality while capping size)
 *   4. Compress to JPEG at 85% quality — typically 80–250KB
 *   5. Store as Couchbase Blob
 *
 * The "userPhoto" field takes display priority over "thumbnailBlob" (HobbyDB auto-download).
 * Camera URI is written to a FileProvider-backed temp file in the app's cache directory.
 */
@Singleton
class PhotoRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: FunkoDexDatabase,
) {
    companion object {
        private const val TAG           = "PhotoRepository"
        private const val MAX_DIMENSION = 1024        // pixels — long edge cap
        private const val JPEG_QUALITY  = 85          // percent
        private const val MAX_BLOB_BYTES = 500_000    // 500KB safety cap
        const val FIELD_USER_PHOTO      = "userPhoto" // Couchbase Blob key
    }

    // ── Camera temp file (used with TakePicture contract) ─────────────────────

    /**
     * Creates a temp file in the app cache directory for TakePicture to write to.
     * Call this before launching the camera contract, then pass the URI to saveFromCamera().
     * The FileProvider authority must match AndroidManifest.xml.
     */
    fun createCameraTempUri(): android.net.Uri {
        val file = File(context.cacheDir, "funkodex_photo_${System.currentTimeMillis()}.jpg")
        return androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    // ── Save a photo from any URI ─────────────────────────────────────────────

    /**
     * Save a photo from [uri] (camera or gallery) onto [itemId].
     * Returns true on success, false on any failure.
     */
    suspend fun savePhoto(uri: Uri, itemId: String): Boolean = withContext(Dispatchers.IO) {
        if (itemId.isEmpty()) {
            FunkoDexLogger.w(TAG, "savePhoto called with empty itemId")
            return@withContext false
        }

        runCatching {
            // 1. Decode to Bitmap
            val original = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: run {
                FunkoDexLogger.w(TAG, "Could not open input stream for $uri")
                return@runCatching false
            }

            // 2. Correct EXIF rotation
            val rotated = correctRotation(uri, original)

            // 3. Scale down if needed
            val scaled = scaleBitmap(rotated)

            // 4. Compress to JPEG
            val bytes = ByteArrayOutputStream().also { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }.toByteArray()

            if (bytes.size > MAX_BLOB_BYTES) {
                FunkoDexLogger.w(TAG, "Photo too large after compression (${bytes.size}B) — re-compressing")
                val recompressed = recompressAggressively(scaled)
                if (recompressed.size > MAX_BLOB_BYTES) {
                    FunkoDexLogger.e(TAG, "Photo still too large after re-compress — skipping")
                    return@runCatching false
                }
                storeBlob(itemId, recompressed)
            } else {
                storeBlob(itemId, bytes)
            }

            FunkoDexLogger.d(TAG, "Photo saved for item $itemId (${bytes.size}B)")
            true
        }.getOrElse { e ->
            FunkoDexLogger.e(TAG, "Photo save failed: ${e.message}", e)
            false
        }
    }

    /**
     * Delete the user photo from [itemId].
     */
    suspend fun deletePhoto(itemId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val doc = db.getDatabase().getDocument(itemId)?.toMutable()
                ?: return@runCatching false
            doc.remove(FIELD_USER_PHOTO)
            db.getDatabase().save(doc)
            true
        }.getOrElse { false }
    }

    /**
     * Read the user photo blob bytes for [itemId].
     * Returns null if no user photo is stored.
     */
    suspend fun getPhotoBytes(itemId: String): ByteArray? = withContext(Dispatchers.IO) {
        db.getDatabase().getDocument(itemId)
            ?.getBlob(FIELD_USER_PHOTO)
            ?.content
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun correctRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        return runCatching {
            val exif = context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream)
            } ?: return@runCatching bitmap

            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90  ->  90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> return@runCatching bitmap
            }
            val matrix = Matrix().also { it.postRotate(degrees) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrElse { bitmap }
    }

    private fun scaleBitmap(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= MAX_DIMENSION && h <= MAX_DIMENSION) return bitmap
        val scale = MAX_DIMENSION.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(
            bitmap,
            (w * scale).toInt(),
            (h * scale).toInt(),
            true,
        )
    }

    private fun recompressAggressively(bitmap: Bitmap): ByteArray {
        // Try progressively lower quality until under the cap
        for (quality in listOf(60, 40, 25)) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= MAX_BLOB_BYTES) return out.toByteArray()
        }
        // Last resort: scale down to 512px
        val small = Bitmap.createScaledBitmap(bitmap, 512, 512, true)
        return ByteArrayOutputStream().also { out ->
            small.compress(Bitmap.CompressFormat.JPEG, 70, out)
        }.toByteArray()
    }

    private fun storeBlob(itemId: String, bytes: ByteArray) {
        val doc = db.getDatabase().getDocument(itemId)?.toMutable() ?: return
        doc.setBlob(FIELD_USER_PHOTO, Blob("image/jpeg", bytes))
        db.getDatabase().save(doc)
    }
}
