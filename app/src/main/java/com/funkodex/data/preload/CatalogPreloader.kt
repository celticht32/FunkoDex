package com.funkodex.data.preload

import android.content.Context
import com.funkodex.data.db.FunkoDexDatabase
import com.couchbase.lite.*
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preloads the enriched FunkoDex catalog (funkodex_base_catalog.json.gz from
 * assets) into Couchbase Lite as "catalog" type documents — read-only reference
 * data.
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
 * ── Why this replaced the Kenny Chan seed ────────────────────────────────────
 * The old asset (funko_data.json) was the raw Kenny Chan dataset: per-character
 * records with a series LIST and no UPC, pricing, or Funko numbers. The enricher
 * (enrich.js) now produces a cleaned, deduplicated, enriched catalog with UPCs,
 * PriceCharting values, HobbyDB imagery and Funko numbers already resolved, and
 * with the series-derived fields (isExclusive / isChase / seriesNumber /
 * category) ALREADY COMPUTED. So this loader:
 *
 *   1. Reads the enriched shape (`_id`/`handle`, string `series`), not KennyRecord.
 *   2. Trusts the enricher's derived fields instead of recomputing them via
 *      CatalogMapper.deriveSeriesFields — recomputing from a single flattened
 *      series string would produce WORSE results than the enricher's, which had
 *      the full series list plus funko.com/PriceCharting context available.
 *   3. STREAMS the file. The catalog is ~20 MB uncompressed / ~4 MB gzipped;
 *      the old readText()+fromJson approach held the whole document tree in
 *      memory at once, which is an OOM risk on low-end devices. JsonReader
 *      parses one record at a time.
 *
 * Only runs once per CATALOG_VER — checks the marker document first.
 * Bump CATALOG_VER when shipping a new catalog to force a reload.
 */
@Singleton
class CatalogPreloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: FunkoDexDatabase,
) {
    companion object {
        internal const val MARKER_DOC    = "system::catalog_loaded"

        /**
         * Bump to force a reload on update.
         * "1" = Kenny Chan seed (funko_data.json)
         * "2" = enriched base catalog (funkodex_base_catalog.json.gz)
         */
        private const val CATALOG_VER   = "2"

        internal const val ASSET_NAME   = "funkodex_base_catalog.json.gz"

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

        /**
         * Sentinel the enricher writes when a Funko number could not be resolved.
         * Must never reach the UI as a literal.
         */
        private const val UNRESOLVED    = "__unresolved__"
    }

    private val gson = Gson()

    /** Call from FunkoDexApp.onCreate() after CouchbaseLite.init() */
    suspend fun preloadIfNeeded(): PreloadResult = withContext(Dispatchers.IO) {
        val database   = db.getDatabase()
        val collection = db.getCollection()

        // Check version marker
        val marker = collection.getDocument(MARKER_DOC)
        if (marker?.getString("version") == CATALOG_VER) {
            val count = marker.getInt("count")
            return@withContext PreloadResult.AlreadyLoaded(count)
        }

        var imported = 0

        try {
            context.assets.open(ASSET_NAME).use { raw ->
                GZIPInputStream(raw).use { gz ->
                    InputStreamReader(gz, Charsets.UTF_8).use { isr ->
                        JsonReader(isr).use { reader ->
                            // The catalog is a single top-level JSON array. Read it
                            // element-by-element so only one record is in memory at
                            // a time.
                            reader.beginArray()

                            // Couchbase batches are bounded to keep the transaction
                            // (and its memory) reasonable; we collect a chunk, write
                            // it, then continue streaming.
                            val chunk = ArrayList<BaseRecord>(BATCH)
                            while (reader.hasNext()) {
                                chunk.add(gson.fromJson(reader, BaseRecord::class.java))
                                if (chunk.size >= BATCH) {
                                    imported += writeChunk(database, collection, chunk)
                                    chunk.clear()
                                }
                            }
                            if (chunk.isNotEmpty()) {
                                imported += writeChunk(database, collection, chunk)
                            }

                            reader.endArray()
                        }
                    }
                }
            }
        } catch (e: java.io.FileNotFoundException) {
            return@withContext PreloadResult.AssetMissing
        } catch (e: Exception) {
            return@withContext PreloadResult.ParseError(e.message ?: "Unknown parse error")
        }

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

    /** Write one batch inside a single Couchbase transaction. Returns rows written. */
    private fun writeChunk(
        database: Database,
        collection: com.couchbase.lite.Collection,
        chunk: List<BaseRecord>,
    ): Int {
        var n = 0
        database.inBatch(UnitOfWork {
            chunk.forEach { record ->
                val docId = record.docId() ?: return@forEach
                // Skip if already exists — avoids conflict on partial re-run
                if (collection.getDocument(docId) != null) return@forEach
                val mapped = mapRecord(record) ?: return@forEach
                collection.save(MutableDocument(docId, mapped))
                n++
            }
        })
        return n
    }

    /**
     * Map an enriched base-catalog record to a Couchbase document map.
     *
     * Unlike the old Kenny path this does NOT call CatalogMapper.deriveSeriesFields:
     * the enricher already computed isExclusive / exclusiveRetailer / isChase /
     * seriesNumber / category from the full series list (which this shape has
     * flattened to a single string), so recomputing here would be strictly lossy.
     * Field names are still taken from CatalogMapper so the two stay in sync.
     */
    private fun mapRecord(r: BaseRecord): Map<String, Any>? {
        val handle = r.handleOrId() ?: return null
        val title  = r.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        return buildMap {
            put(CatalogMapper.FIELD_TYPE,           TYPE_CATALOG)
            put(CatalogMapper.FIELD_HANDLE,         handle)
            put(CatalogMapper.FIELD_TITLE,          title)
            put(CatalogMapper.FIELD_IMAGE_URL,      r.imageUrl.orEmpty())
            // The enriched shape carries a single series string; keep seriesList
            // populated (as a 1-element list) so anything reading it still works.
            put(CatalogMapper.FIELD_SERIES_LIST,    listOfNotNull(r.series?.takeIf { it.isNotBlank() }))
            put(CatalogMapper.FIELD_PRIMARY_SERIES, r.series.orEmpty())
            put(CatalogMapper.FIELD_CATEGORY,       r.category.orEmpty())
            put(CatalogMapper.FIELD_IS_EXCLUSIVE,   r.isExclusive ?: false)
            put(CatalogMapper.FIELD_EXCL_RETAILER,  r.exclusiveRetailer.orEmpty())
            put(CatalogMapper.FIELD_IS_CHASE,       r.isChase ?: false)
            put(CatalogMapper.FIELD_NUMBER,         r.seriesNumber.orEmpty())
            put(CatalogMapper.FIELD_RETAIL_PRICE,   r.retailPrice ?: 0.0)
            put(CatalogMapper.FIELD_IS_VAULTED,     r.isVaulted ?: false)
            put(CatalogMapper.FIELD_SOURCE,         r.source?.takeIf { it.isNotBlank() } ?: "ENRICHED")
            put(CatalogMapper.FIELD_LAST_UPDATED,   r.lastUpdated ?: java.time.LocalDate.now().toString())

            r.upc?.takeIf { it.isNotBlank() }?.let { put(CatalogMapper.FIELD_UPC, it) }

            // funkoNumber carries a sentinel when unresolved — never store it.
            r.funkoNumber?.takeIf { it.isNotBlank() && it != UNRESOLVED }
                ?.let { put(CatalogMapper.FIELD_FUNKO_NUMBER, it) }

            r.popType?.takeIf { it.isNotBlank() }?.let { put(CatalogMapper.FIELD_POP_TYPE, it) }

            // PriceCharting / enriched extras — omit when absent so the document
            // shape matches what CatalogMapper.mapRecord would have produced.
            r.marketValueLoose?.takeIf { it.isNotBlank() }
                ?.let { put(CatalogMapper.FIELD_MKT_VALUE_LOOSE, it) }
            r.marketValueComplete?.takeIf { it.isNotBlank() }
                ?.let { put(CatalogMapper.FIELD_MKT_VALUE_COMPLETE, it) }
            r.marketValueNew?.takeIf { it.isNotBlank() }
                ?.let { put(CatalogMapper.FIELD_MKT_VALUE_NEW, it) }
            if (r.marketValueIsApproximate == true) {
                put(CatalogMapper.FIELD_MKT_IS_APPROX, true)
            }
            r.pricechartingId?.takeIf { it.isNotBlank() }
                ?.let { put(CatalogMapper.FIELD_PC_ID, it) }
            r.pricechartingUrl?.takeIf { it.isNotBlank() }
                ?.let { put(CatalogMapper.FIELD_PC_URL, it) }
            r.releaseDate?.takeIf { it.isNotBlank() }
                ?.let { put(CatalogMapper.FIELD_RELEASE_DATE, it) }
            r.ebayEpid?.takeIf { it.isNotBlank() }
                ?.let { put(CatalogMapper.FIELD_EBAY_EPID, it) }
            r.publisher?.takeIf { it.isNotBlank() }
                ?.let { put(CatalogMapper.FIELD_PUBLISHER, it) }
            r.pcSeries?.takeIf { it.isNotBlank() }
                ?.let { put(CatalogMapper.FIELD_PC_SERIES, it) }
            r.franchiseSuggestion?.takeIf { it.isNotBlank() }
                ?.let { put(CatalogMapper.FIELD_FRANCHISE_SUGGESTION, it) }
        }
    }

    private fun ensureCatalogIndexes(collection: com.couchbase.lite.Collection) {
        // Unchanged from the previous implementation — see git history.
        db.ensureIndexes()
    }

    /**
     * The enriched base-catalog record shape, as written by enrich.js
     * (toBaseCatalogShape). Every field is nullable: the enricher omits what it
     * could not resolve, and a missing field must never crash the preload.
     *
     * NOTE the differences from the old KennyRecord:
     *   - `_id` ("catalog::handle") is authoritative; `handle` is also present
     *   - `series` is a STRING, not a List<String>
     *   - `imageUrl` replaces `imageName`
     *   - the derived fields (isExclusive/isChase/seriesNumber/category) are
     *     supplied by the enricher rather than computed here
     */
    data class BaseRecord(
        @com.google.gson.annotations.SerializedName("_id")
        val id: String?                    = null,
        val handle: String?                = null,
        val type: String?                  = null,
        val title: String?                 = null,
        val imageUrl: String?              = null,
        val series: String?                = null,
        val category: String?              = null,
        val isExclusive: Boolean?          = null,
        val exclusiveRetailer: String?     = null,
        val isChase: Boolean?              = null,
        val isVaulted: Boolean?            = null,
        val seriesNumber: String?          = null,
        val funkoNumber: String?           = null,
        val popType: String?               = null,
        val upc: String?                   = null,
        val retailPrice: Double?           = null,
        val source: String?                = null,
        val lastUpdated: String?           = null,
        val marketValueLoose: String?      = null,
        val marketValueComplete: String?   = null,
        val marketValueNew: String?        = null,
        val marketValueIsApproximate: Boolean? = null,
        val pricechartingId: String?       = null,
        val pricechartingUrl: String?      = null,
        val releaseDate: String?           = null,
        val ebayEpid: String?              = null,
        val publisher: String?             = null,
        val pcSeries: String?              = null,
        val franchiseSuggestion: String?   = null,
    ) {
        /** The Couchbase document id. Prefers `_id`, which is already prefixed. */
        fun docId(): String? {
            id?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            return handleOrId()?.let { "catalog::$it" }
        }

        /** The bare handle, derived from `handle` or stripped out of `_id`. */
        fun handleOrId(): String? {
            handle?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            return id?.trim()?.removePrefix("catalog::")?.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * The raw Kenny Chan record shape.
     *
     * RETAINED ONLY so the disabled Kenny re-fetch in CatalogRefreshWorker still
     * compiles — nothing on the live path uses it any more. The app now ships the
     * enriched catalog (see BaseRecord above). Delete this together with
     * CatalogRefreshWorker.refreshKennyChanDISABLED() when that code is removed
     * for good.
     */
    @Deprecated("Kenny Chan seed replaced by the enriched base catalog; see BaseRecord.")
    data class KennyRecord(
        val handle: String?    = null,
        val title: String?     = null,
        val imageName: String? = null,
        val series: List<String>? = null,
    )
}

private const val BATCH = 500

sealed class PreloadResult {
    data class Loaded(val count: Int)            : PreloadResult()
    data class AlreadyLoaded(val count: Int)     : PreloadResult()
    object AssetMissing                          : PreloadResult()
    data class ParseError(val message: String)   : PreloadResult()
}
