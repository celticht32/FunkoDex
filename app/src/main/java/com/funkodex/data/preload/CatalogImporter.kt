package com.funkodex.data.preload

import android.content.Context
import android.net.Uri
import com.couchbase.lite.DataSource
import com.couchbase.lite.Expression
import com.couchbase.lite.Meta
import com.couchbase.lite.MutableDocument
import com.couchbase.lite.QueryBuilder
import com.couchbase.lite.SelectResult
import com.couchbase.lite.UnitOfWork
import com.funkodex.data.db.FunkoDexDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CatalogImporter
 *
 * User-triggered import of funko_data_enriched.json into the live Couchbase catalog.
 *
 * Behaviour:
 *   - Existing catalog docs → merge: only writes non-null new fields, never overwrites
 *     imageUrl, title, handle, or seriesList.
 *   - Missing docs → insert: full record via CatalogMapper.mapRecord().
 *   - Runs in batches of 500 inside database.inBatch() for performance.
 *   - Emits ImportProgress updates so the UI can show a live counter.
 *   - Returns ImportResult as the final emission.
 *
 * MIT License — Copyright (c) 2026 Chris Ahrendt
 */
@Singleton
class CatalogImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: FunkoDexDatabase,
) {

    // ── Non-Pop filter (handoff spec) ─────────────────────────────────────
    // Net-new records matching these are merchandise, not standard Pops, and
    // are skipped on insert. Merges into existing catalog docs are NOT
    // filtered — if a doc is already in the catalog, enriching it is harmless.
    private val NON_POP_TITLE = Regex(
        "\\b(tee|shirt|backpack|bag|wallet|keychain|soda|mystery minis|wacky wobbler|" +
        "funkoverse|bitty pop|pocket pop|pin set|enamel pin|dorbz|hikari|rock candy|" +
        "fabrikations|paka paka|plush|mug|cup|cushion)\\b",
        RegexOption.IGNORE_CASE
    )

    private fun isStandardPop(record: EnrichedRecord): Boolean {
        if (NON_POP_TITLE.containsMatchIn(record.title ?: "")) return false
        val series = record.series.map { it.lowercase() }
        return listOf("pop! tees","loungefly","mystery minis","wacky wobblers",
            "vinyl soda","funkoverse","dorbz","rock candy","hikari","fabrikations")
            .none { tag -> series.any { it.contains(tag) } }
    }

    /** Lowercase, strip punctuation, collapse whitespace. Null if blank after normalization. */
    private fun normalizeTitle(title: String?): String? =
        title?.lowercase()
            ?.replace(Regex("[^a-z0-9]+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    // funko.com scrape emits page filenames (e.g. "91991.html") as handles for
    // records it couldn't match to a HobbyDB handle. These are not valid catalog
    // handles — inserting them would create garbage doc IDs like
    // "catalog::91991.html". Detected and replaced with a title-derived slug.
    private val FUNKO_PAGE_HANDLE = Regex("""^\d+\.html$""")

    /** Title → handle slug: lowercase, non-alphanumeric runs → single hyphen, trimmed. */
    private fun slugify(title: String): String =
        title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

    /**
     * Build a one-shot index of normalized catalog title → document ID for the
     * title-fallback match. Titles shared by more than one catalog doc are
     * ambiguous and removed — a fallback merge must be unambiguous.
     */
    private fun buildTitleIndex(collection: com.couchbase.lite.Collection): Map<String, String> {
        val index     = HashMap<String, String>(32_768)
        val ambiguous = HashSet<String>()
        val query = QueryBuilder
            .select(
                SelectResult.expression(Meta.id).`as`("id"),
                SelectResult.property(CatalogMapper.FIELD_TITLE).`as`("title"),
            )
            .from(DataSource.collection(collection))
            .where(
                Expression.property(CatalogMapper.FIELD_TYPE)
                    .equalTo(Expression.string(CatalogMapper.TYPE_CATALOG))
            )
        query.execute().use { rs ->
            rs.allResults().forEach { row ->
                val id   = row.getString("id") ?: return@forEach
                val norm = normalizeTitle(row.getString("title")) ?: return@forEach
                if (index.put(norm, id) != null) ambiguous.add(norm)
            }
        }
        ambiguous.forEach { index.remove(it) }
        return index
    }

    /**
     * Parse [uri] as a JSON array of [EnrichedRecord], merge into catalog, and
     * emit progress. The final emission is always an [ImportProgress] whose
     * [ImportProgress.done] flag is true; callers can collect the last value
     * for the summary dialog.
     */
    fun importFromUri(uri: Uri): Flow<ImportProgress> = flow {
        val startMs = System.currentTimeMillis()

        // ── 1. Read file ──────────────────────────────────────────────────
        val json = context.contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: run {
                emit(ImportProgress(error = "Could not open file"))
                return@flow
            }

        // ── 2. Parse ──────────────────────────────────────────────────────
        // NOTE: We intentionally do NOT use gson.fromJson(json, TypeToken<List<
        // EnrichedRecord>>) here. That reflective path throws
        // "java.util.ArrayList cannot be cast to java.lang.Void" on-device for
        // this Kotlin data class — Gson mis-resolves the generic `series:
        // List<String>` field type under Kotlin's emitted metadata. Parsing the
        // tree and mapping each object field explicitly sidesteps Gson's
        // reflective TypeAdapter entirely and is fully deterministic.
        val records: List<EnrichedRecord> = try {
            val root = com.google.gson.JsonParser.parseString(json)
            if (!root.isJsonArray) {
                emit(ImportProgress(error = "Expected a JSON array of records"))
                return@flow
            }
            root.asJsonArray.map { element -> element.asJsonObject.toEnrichedRecord() }
        } catch (e: Exception) {
            emit(ImportProgress(error = "JSON parse error: ${e.message}"))
            return@flow
        }

        val total   = records.size
        var enriched = 0
        var added    = 0
        var skipped  = 0
        var errors   = 0
        var processed = 0

        val database = db.getDatabase()
        val collection = db.getCollection()

        // Normalized title → docId, for the fallback match when handle misses.
        // Built once up front; ~23k entries. Wrapped so an index failure
        // degrades to handle-only matching instead of failing the import.
        val titleIndex: Map<String, String> = try {
            buildTitleIndex(collection)
        } catch (e: Exception) {
            emptyMap()
        }

        // ── 3. Upsert in batches of 500 ───────────────────────────────────
        records.chunked(500).forEach { chunk ->
            database.inBatch(UnitOfWork {
                chunk.forEach { record ->
                    val handle = record.handle?.trim()
                    if (handle.isNullOrBlank()) {
                        skipped++
                        processed++
                        return@forEach
                    }

                    try {
                        val docId = "catalog::$handle"
                        // Match by handle first; fall back to unambiguous
                        // normalized-title match (low hit rate expected —
                        // funko.com vs HobbyDB prefix formats differ).
                        val existing = collection.getDocument(docId)
                            ?: normalizeTitle(record.title)
                                ?.let { titleIndex[it] }
                                ?.let { collection.getDocument(it) }

                        if (existing != null) {
                            // ── Merge: only write new fields, never overwrite identity ──
                            val mutable = existing.toMutable()

                            record.available?.let {
                                mutable.setBoolean(CatalogMapper.FIELD_IS_AVAILABLE, it)
                            }
                            record.productUrl?.takeIf { it.isNotBlank() }?.let {
                                mutable.setString(CatalogMapper.FIELD_PRODUCT_URL, it)
                            }
                            record.funkoPrimaryImage?.takeIf { it.isNotBlank() }?.let {
                                mutable.setString(CatalogMapper.FIELD_FUNKO_IMAGE, it)
                            }
                            record.pid?.takeIf { it.isNotBlank() }?.let {
                                mutable.setString(CatalogMapper.FIELD_FUNKO_SHOP_ID, it)
                            }
                            record.funkoNumber?.takeIf { it.isNotBlank() }?.let {
                                mutable.setString(CatalogMapper.FIELD_FUNKO_NUMBER, it)
                            }
                            record.popType?.takeIf { it.isNotBlank() }?.let {
                                mutable.setString(CatalogMapper.FIELD_POP_TYPE, it)
                            }
                            record.upc?.takeIf { it.isNotBlank() }?.let {
                                // Only write UPC if doc doesn't already have one
                                if (existing.getString(CatalogMapper.FIELD_UPC).isNullOrBlank()) {
                                    mutable.setString(CatalogMapper.FIELD_UPC, it)
                                }
                            }
                            record.price?.let { raw ->
                                val parsed = raw.replace(Regex("[^0-9.]"), "").toDoubleOrNull()
                                if (parsed != null && parsed > 0.0) {
                                    mutable.setDouble(CatalogMapper.FIELD_RETAIL_PRICE, parsed)
                                }
                            }
                            record.marketValueLoose?.takeIf { it.isNotBlank() }?.let {
                                mutable.setString(CatalogMapper.FIELD_MKT_VALUE_LOOSE, it)
                            }
                            record.marketValueNew?.takeIf { it.isNotBlank() }?.let {
                                mutable.setString(CatalogMapper.FIELD_MKT_VALUE_NEW, it)
                            }
                            record.pricechartingId?.takeIf { it.isNotBlank() }?.let {
                                mutable.setString(CatalogMapper.FIELD_PC_ID, it)
                            }
                            record.pricechartingUrl?.takeIf { it.isNotBlank() }?.let {
                                mutable.setString(CatalogMapper.FIELD_PC_URL, it)
                            }

                            // Repair the legacy bad category value. Earlier catalog
                            // builds stored "Pop! Vinyl" (a format descriptor, not a
                            // collecting category) in the category field, which the
                            // category filter can't match and silently hides. Recompute
                            // from the record's series and overwrite only when the stored
                            // value is the bad one, so a re-import self-heals existing docs.
                            val storedCategory = existing.getString(CatalogMapper.FIELD_CATEGORY)
                            if (storedCategory.equals("Pop! Vinyl", ignoreCase = true)) {
                                val repaired = record.series.firstOrNull { s ->
                                    s.startsWith("Pop!", ignoreCase = true) &&
                                    !s.equals("Pop! Vinyl", ignoreCase = true) &&
                                    !s.equals("Pop!", ignoreCase = true)
                                } ?: ""
                                mutable.setString(CatalogMapper.FIELD_CATEGORY, repaired)
                            }

                            mutable.setString(CatalogMapper.FIELD_LAST_UPDATED, LocalDate.now().toString())
                            collection.save(mutable)
                            enriched++

                        } else {
                            // ── Insert: build full document via CatalogMapper ──────────
                            // Non-Pop merchandise (tees, soda, Loungefly…) is never
                            // inserted as a net-new catalog record.
                            if (!isStandardPop(record)) {
                                skipped++
                                processed++
                                return@forEach
                            }

                            val title = record.title?.trim()
                            if (title.isNullOrBlank()) {
                                skipped++
                                processed++
                                return@forEach
                            }

                            val parsedPrice = record.price
                                ?.replace(Regex("[^0-9.]"), "")
                                ?.toDoubleOrNull() ?: 0.0

                            // Repair funko.com page-name handles with a title slug.
                            val insertHandle =
                                if (FUNKO_PAGE_HANDLE.matches(handle)) slugify(title) else handle
                            if (insertHandle.isBlank()) {
                                skipped++
                                processed++
                                return@forEach
                            }
                            val insertDocId = "catalog::$insertHandle"

                            // Never clobber: if a doc already exists at the target ID
                            // (ambiguous-title doc, or a duplicate earlier in this
                            // import), skip rather than replace its content.
                            if (collection.getDocument(insertDocId) != null) {
                                skipped++
                                processed++
                                return@forEach
                            }

                            val mapped = CatalogMapper.mapRecord(
                                handle           = insertHandle,
                                title            = title,
                                imageName        = record.imageName?.trim() ?: "",
                                seriesList       = record.series,
                                upc              = record.upc?.takeIf { it.isNotBlank() },
                                price            = parsedPrice,
                                source           = record.funkoSource ?: "ENRICHED",
                                available        = record.available,
                                productUrl       = record.productUrl,
                                funkoImageUrl    = record.funkoPrimaryImage,
                                funkoShopId      = record.pid,
                                funkoNumber      = record.funkoNumber,
                                popType          = record.popType,
                                marketValueLoose = record.marketValueLoose,
                                marketValueNew   = record.marketValueNew,
                                pricechartingId  = record.pricechartingId,
                                pricechartingUrl = record.pricechartingUrl,
                            )
                            collection.save(MutableDocument(insertDocId, mapped))
                            added++
                        }
                    } catch (e: Exception) {
                        errors++
                    }

                    processed++
                }
            })

            // Emit progress after each batch
            emit(ImportProgress(
                processed = processed,
                total     = total,
                enriched  = enriched,
                added     = added,
                done      = false,
            ))
        }

        // ── 4. Final emission ─────────────────────────────────────────────
        emit(ImportProgress(
            processed  = processed,
            total      = total,
            enriched   = enriched,
            added      = added,
            done       = true,
            result     = ImportResult(
                enriched   = enriched,
                added      = added,
                skipped    = skipped,
                errors     = errors,
                durationMs = System.currentTimeMillis() - startMs,
            ),
        ))

    }.flowOn(Dispatchers.IO)
}

// ── Progress / result data classes ────────────────────────────────────────────

data class ImportProgress(
    val processed: Int         = 0,
    val total:     Int         = 0,
    val enriched:  Int         = 0,
    val added:     Int         = 0,
    val done:      Boolean     = false,
    val result:    ImportResult? = null,
    val error:     String?     = null,
)

data class ImportResult(
    val enriched:   Int,
    val added:      Int,
    val skipped:    Int,
    val errors:     Int,
    val durationMs: Long,
)

// ── Explicit JSON → EnrichedRecord mapping ────────────────────────────────────
// Hand-rolled to avoid Gson's reflective binding of List<EnrichedRecord>, which
// throws "ArrayList cannot be cast to java.lang.Void" on-device for this Kotlin
// data class. Every field is read defensively: missing/JsonNull → null (or
// emptyList() for series).

private fun com.google.gson.JsonObject.optString(key: String): String? {
    val el = get(key) ?: return null
    return if (el.isJsonNull) null else el.asString
}

private fun com.google.gson.JsonObject.optBoolean(key: String): Boolean? {
    val el = get(key) ?: return null
    return if (el.isJsonNull) null else el.asBoolean
}

private fun com.google.gson.JsonObject.optStringList(key: String): List<String> {
    val el = get(key) ?: return emptyList()
    if (!el.isJsonArray) return emptyList()
    return el.asJsonArray.mapNotNull { if (it.isJsonNull) null else it.asString }
}

private fun com.google.gson.JsonObject.toEnrichedRecord(): EnrichedRecord = EnrichedRecord(
    handle            = optString("handle"),
    title             = optString("title"),
    imageName         = optString("imageName"),
    series            = optStringList("series"),
    upc               = optString("upc"),
    pid               = optString("pid"),
    price             = optString("price"),
    available         = optBoolean("available"),
    productUrl        = optString("productUrl"),
    funkoPrimaryImage = optString("funkoPrimaryImage"),
    funkoSource       = optString("funkoSource"),
    funkoNumber       = optString("funkoNumber"),
    popType           = optString("popType"),
    marketValueLoose  = optString("marketValueLoose"),
    marketValueNew    = optString("marketValueNew"),
    pricechartingId   = optString("pricechartingId"),
    pricechartingUrl  = optString("pricechartingUrl"),
)
