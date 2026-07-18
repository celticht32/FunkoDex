package com.funkodex.data.preload

import android.content.Context
import com.funkodex.data.db.FunkoDexDatabase
import com.funkodex.util.FunkoDexLogger
import com.couchbase.lite.*
// Explicit import REQUIRED alongside the wildcard above: Kotlin's own
// kotlin.Function is in the default preamble and shadows the wildcard, so
// `Function.count(...)` fails to resolve without this. FunkoRepository.kt does
// the same for the same reason.
import com.couchbase.lite.Function
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
         * "2" = enriched base catalog (funkodex_base_catalog.json.gz), 20,580 records.
         *       NOTE: version 2 never actually loaded on any device — the asset was
         *       named .gz and AGP decompressed it out of existence (see ASSET_NAME).
         *       Any install claiming version 2 loaded nothing.
         * "3" = S23 catalog cleanup, 20,565 records, shipped as .gz_ so it actually
         *       reaches the device. Net -15 vs version 2: 12 Kenny/PriceCharting
         *       duplicate pairs merged (survivor keeps its name-slug _id and image,
         *       the pc-NNNN stub is dropped), 1 pure duplicate removed, and 1 non-Pop
         *       removed (a shirt carrying a real Pop's mis-stapled UPC/number). Also
         *       re-homes 3 records that were misfiled as damaged base figures: two are
         *       genuine Toyzilla signed editions, one was the shirt. See DEC-026 and
         *       CLAUDE_STATE_FunkoDex_S23.md.
         */
        private const val CATALOG_VER   = "4"

        /**
         * The gzipped catalog in assets/.
         *
         * NOTE THE TRAILING UNDERSCORE — it is deliberate and load-bearing. The
         * file IS a normal gzip stream (magic bytes 1f 8b); only the extension is
         * unusual, and GZIPInputStream below reads it exactly as before.
         *
         * AGP's asset merger DECOMPRESSES any `.gz` file under src/main/assets and
         * STRIPS the extension during mergeXxxAssets — before AAPT2 runs, and a
         * `gradlew clean` does not stop it. Shipping this as
         * `funkodex_base_catalog.json.gz` put an 18.1 MB *decompressed*
         * `funkodex_base_catalog.json` in the APK instead of the 2.0 MB gzip, so
         * assets.open("...json.gz") threw FileNotFoundException, preloadIfNeeded()
         * returned AssetMissing, and THE CATALOG NEVER LOADED ON ANY DEVICE.
         *
         * That failure was silent for two sessions: FunkoLookupService falls back to
         * a network search when the local Couchbase query returns nothing, so
         * searching the app still returned results — from the network, not the
         * catalog. S22's "fresh install, searched Stitch, got results" passed while
         * zero catalog records existed on the device.
         *
         * `.gz_` is not an extension AGP acts on, so the file survives intact.
         * build.gradle.kts also sets `androidResources { noCompress += "gz_" }` so
         * AAPT2 doesn't pointlessly deflate an already-gzipped file.
         *
         * If you change this name, change build_catalog_asset.py (DEF_OUT) and the
         * gradle noCompress entry to match, then VERIFY THE APK really contains it:
         *   [IO.Compression.ZipFile]::OpenRead("app-debug.apk").Entries |
         *     Where-Object { $_.FullName -like "assets/funkodex*" }
         * and confirm logcat shows "Catalog loaded: <n> items" on a fresh install —
         * an AssetMissing warning means it is broken again.
         */
        internal const val ASSET_NAME   = "funkodex_base_catalog.json.gz_"

        /**
         * Floor for "the catalog actually loaded". The shipped asset holds ~20,565
         * records; anything materially short means a writer raced the preload or the
         * stream died partway, and the marker must NOT be written or the shortfall
         * becomes permanent. Deliberately loose (not an equality check) — writeChunk
         * legitimately skips ids that already exist, and the catalog grows each ship.
         */
        private const val MIN_EXPECTED_ROWS = 20_000

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

        // Write the version marker ONLY if the load actually landed. `imported`
        // counts rows this pass WROTE — writeChunk skips ids that already exist, so
        // a record another writer (CatalogRefreshWorker's community-UPC merge) got
        // to first is not counted here even though it is present. What must never
        // happen is marking a SHORT load as done: the marker short-circuits every
        // future launch, so a partial catalog becomes permanent until CATALOG_VER
        // changes.
        //
        // S23 hit exactly this: the worker raced the preload, `imported` came back
        // 14856, the marker was written anyway, and 1,559 asset records were absent
        // forever while the app cheerfully reported "Catalog already present".
        // Scheduling the worker after the preload (see FunkoDexApp 4a/5c) removes
        // the race; this guard is the backstop for anything else that writes
        // catalog:: docs early.
        val catalogRows = countCatalogDocs(collection)
        if (catalogRows < MIN_EXPECTED_ROWS) {
            FunkoDexLogger.w(
                "CatalogPreloader",
                "Preload incomplete: only $catalogRows catalog rows present " +
                    "(imported $imported this pass, expected >= $MIN_EXPECTED_ROWS). " +
                    "NOT writing the version marker — the next launch will retry."
            )
            return@withContext PreloadResult.ParseError(
                "incomplete preload: $catalogRows rows"
            )
        }

        // Mutate the EXISTING marker if there is one, rather than constructing a
        // fresh MutableDocument(MARKER_DOC): a new document carries no revision
        // history, so saving it over a marker left by an earlier (e.g. incomplete)
        // run makes LiteCore throw `conflict [1, 8]`. Observed S23 — benign there
        // (the save still landed and the load reported correctly), but a genuine
        // failure here would leave no marker at all and re-import the whole catalog
        // on every launch. toMutable() keeps the revision, so the save is an update.
        val markerDoc = (collection.getDocument(MARKER_DOC)?.toMutable()
            ?: MutableDocument(MARKER_DOC)).apply {
            setString("type", "system")
            setString("version", CATALOG_VER)
            setInt("count", catalogRows)
            setString("loadedAt", java.time.LocalDate.now().toString())
        }
        collection.save(markerDoc)

        // Ensure catalog indexes
        ensureCatalogIndexes(collection)

        PreloadResult.Loaded(catalogRows)
    }

    /**
     * How many catalog:: documents actually exist right now.
     *
     * Counts the DB rather than trusting the preload's own `imported` tally:
     * writeChunk skips ids another writer already created, so `imported` can be far
     * below the true row count. Used to decide whether the version marker may be
     * written — see the guard in preloadIfNeeded().
     */
    private fun countCatalogDocs(collection: com.couchbase.lite.Collection): Int =
        try {
            QueryBuilder
                .select(SelectResult.expression(Function.count(Expression.string("*"))).`as`("n"))
                .from(DataSource.collection(collection))
                .where(
                    Expression.property(CatalogMapper.FIELD_TYPE)
                        .equalTo(Expression.string(TYPE_CATALOG))
                )
                .execute()
                .use { rs -> rs.allResults().firstOrNull()?.getInt("n") ?: 0 }
        } catch (e: Exception) {
            FunkoDexLogger.e("CatalogPreloader", "countCatalogDocs failed: ${e.message}", e)
            // On failure return 0 — the caller treats that as "incomplete" and
            // declines to write the marker, so the next launch retries. Refusing to
            // mark done is the safe direction.
            0
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
