package com.funkodex.data.preload

import android.content.Context
import com.funkodex.data.db.FunkoDexDatabase
import com.couchbase.lite.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preloads the Kenny Chan Funko Pop catalog (funko_data.json from assets)
 * into Couchbase Lite as "catalog" type documents — read-only reference data.
 *
 * These are SEPARATE from the user's collection documents (type="funko").
 * Catalog docs are used for:
 *   - Instant offline lookup by name/series in the scanner
 *   - Powering the "series completion" report (what exists vs what you own)
 *   - Pre-populating the want list search
 *
 * Document key format:  "catalog::{handle}"
 * Document type field:  "catalog"
 *
 * Only runs once — checks a "catalog_loaded" marker document first.
 * Re-run is triggered if the marker is absent (e.g. fresh install or DB reset).
 */
@Singleton
class CatalogPreloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: FunkoDexDatabase,
) {
    companion object {
        internal const val MARKER_DOC    = "system::catalog_loaded"
        private const val CATALOG_VER   = "1"            // bump to force a reload on update
        const val TYPE_CATALOG          = "catalog"

        // Field names for catalog documents
        const val FIELD_HANDLE          = "handle"
        const val FIELD_TITLE           = "title"
        const val FIELD_IMAGE_URL       = "imageUrl"
        const val FIELD_SERIES_LIST     = "seriesList"   // full array from source
        const val FIELD_PRIMARY_SERIES  = "series"       // first Pop!/Funko series tag
        const val FIELD_CATEGORY        = "category"     // e.g. "Pop! Vinyl", "Pop! Movies"
        const val FIELD_IS_EXCLUSIVE    = "isExclusive"
        const val FIELD_EXCL_RETAILER   = "exclusiveRetailer"
        const val FIELD_IS_CHASE        = "isChase"
        const val FIELD_NUMBER          = "seriesNumber" // extracted from title if present
        const val FIELD_TYPE            = "type"
    }

    private val gson = Gson()

    /** Call from FunkoDexApp.onCreate() after CouchbaseLite.init() */
    suspend fun preloadIfNeeded(): PreloadResult = withContext(Dispatchers.IO) {
        val database = db.getDatabase()
        val collection = db.getCollection()

        // Check version marker
        val marker = collection.getDocument(MARKER_DOC)
        if (marker?.getString("version") == CATALOG_VER) {
            val count = marker.getInt("count")
            return@withContext PreloadResult.AlreadyLoaded(count)
        }


        // Load JSON from assets
        val json = try {
            context.assets.open("funko_data.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            return@withContext PreloadResult.AssetMissing
        }

        val type    = object : TypeToken<List<KennyRecord>>() {}.type
        val records: List<KennyRecord> = try {
            gson.fromJson(json, type)
        } catch (e: Exception) {
            return@withContext PreloadResult.ParseError(e.message ?: "Unknown parse error")
        }

        // Bulk insert in batches of 500 for performance
        var imported = 0
        val batch    = 500

        database.inBatch(UnitOfWork {
            records.chunked(batch).forEach { chunk ->
                chunk.forEach { record ->
                    val docId  = "catalog::${record.handle ?: return@forEach}"
                    // Skip if already exists — avoids conflict on partial re-run
                    if (collection.getDocument(docId) != null) return@forEach
                    val mapped = mapRecord(record) ?: return@forEach
                    val doc    = MutableDocument(docId, mapped)
                    collection.save(doc)
                    imported++
                }
            }
        })

        // Write version marker
        val markerDoc = MutableDocument(MARKER_DOC).apply {
            setString("type", "system")
            setString("version", CATALOG_VER)
            setInt("count", imported)
            setString("loadedAt", java.time.LocalDate.now().toString())
        }
        collection.save(markerDoc)

        // Ensure catalog indexes
        ensureCatalogIndexes(collection)

        PreloadResult.Loaded(imported)
    }

    /** Delegates to shared CatalogMapper — logic extracted for A1 fix. */
    private fun mapRecord(r: KennyRecord): Map<String, Any>? {
        val handle = r.handle?.trim() ?: return null
        val title  = r.title?.trim()  ?: return null
        return CatalogMapper.mapRecord(
            handle     = handle,
            title      = title,
            imageName  = r.imageName?.trim() ?: "",
            seriesList = r.series ?: emptyList(),
            price      = 0.0,
            vaulted    = false,
            source     = "KENNY_CHAN",
        )
    }

    private fun ensureCatalogIndexes(collection: com.couchbase.lite.Collection) {
        collection.createIndex("idx_catalog_title",
            IndexBuilder.valueIndex(ValueIndexItem.property(FIELD_TITLE)))
        collection.createIndex("idx_catalog_series",
            IndexBuilder.valueIndex(ValueIndexItem.property(FIELD_PRIMARY_SERIES)))
        collection.createIndex("idx_catalog_type",
            IndexBuilder.valueIndex(ValueIndexItem.property(FIELD_TYPE)))
    }

    /** Raw JSON record shape from Kenny Chan dataset */
    data class KennyRecord(
        val handle: String?    = null,
        val title: String?     = null,
        val imageName: String? = null,
        val series: List<String>? = null,
    )
}

sealed class PreloadResult {
    data class Loaded(val count: Int)            : PreloadResult()
    data class AlreadyLoaded(val count: Int)     : PreloadResult()
    object AssetMissing                          : PreloadResult()
    data class ParseError(val message: String)   : PreloadResult()
}
