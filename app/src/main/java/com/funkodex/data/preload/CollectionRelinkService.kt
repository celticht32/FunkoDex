package com.funkodex.data.preload

import com.couchbase.lite.DataSource
import com.couchbase.lite.Expression
import com.couchbase.lite.Meta
import com.couchbase.lite.QueryBuilder
import com.couchbase.lite.SelectResult
import com.couchbase.lite.UnitOfWork
import com.funkodex.data.db.FunkoDexDatabase
import com.funkodex.data.model.FunkoGenre
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CollectionRelinkService
 *
 * User-triggered, fill-only re-link of owned collection items (`funko::` docs)
 * to the catalog (`catalog::` docs). Run this AFTER importing an enriched
 * funko_data_enriched.json so that items you already own pick up the new
 * metadata (UPC, market value, PriceCharting URL, funko number, franchise/
 * category, retail price, catalog image) without re-scanning each one by hand.
 *
 * Sequencing (important): the catalog must already be enriched before this runs.
 * The intended flow is:
 *   1. Backup collection
 *   2. (optional) Force restore
 *   3. Import enriched catalog JSON   ← catalog is now enriched
 *   4. Re-link collection             ← THIS service
 *
 * Matching precedence per owned item:
 *   1. catalogRef → catalog::{handle} exact document
 *   2. item.upc → catalog doc via a one-shot UPC index (ambiguous UPCs dropped)
 * An item that resolves to no catalog doc is left untouched and counted as
 * "unmatched".
 *
 * Fill-only rule (mirrors CatalogImporter.mergeRecordInto):
 *   - A catalog value is written onto the owned item ONLY when the item's own
 *     field is empty / default / unset.
 *   - User-authored data is NEVER overwritten: pricePaid, condition, notes,
 *     userPhoto, dateAcquired, isOwned, and a manually-set market value
 *     (marketValueIsManual == true) are not touched.
 *   - imageUrl is filled from the catalog only when the item has no image URL
 *     of its own; a user-entered URL is preserved.
 *   - marketAvg is filled (complete → loose → new precedence, matching
 *     PriceService) only when the item has no market value and the value is not
 *     user-managed; priceLastUpdated is stamped when we set it.
 *
 * The pass runs inside database.inBatch() in chunks and is fully idempotent —
 * running it twice changes nothing the second time, because every target field
 * is already populated.
 *
 * MIT License — Copyright (c) 2026 Chris Ahrendt
 */
@Singleton
class CollectionRelinkService @Inject constructor(
    private val db: FunkoDexDatabase,
) {

    private companion object {
        const val FUNKO_TYPE = "funko"
        const val CHUNK = 500
    }

    /** Parse a "$NN.NN" (optionally with thousands separators) string to a positive Double, or null. */
    private fun parseMoney(raw: String?): Double? =
        raw?.replace(Regex("[^0-9.]"), "")
            ?.toDoubleOrNull()
            ?.takeIf { it > 0.0 }

    /**
     * Build UPC → catalog docId index for the UPC-fallback match. UPCs mapping to
     * more than one catalog doc are ambiguous (some variants share a UPC) and are
     * dropped, so a shared UPC can never drive a wrong re-link.
     */
    private fun buildCatalogUpcIndex(collection: com.couchbase.lite.Collection): Map<String, String> {
        val index = HashMap<String, String>(16_384)
        val ambiguous = HashSet<String>()
        val query = QueryBuilder
            .select(
                SelectResult.expression(Meta.id).`as`("id"),
                SelectResult.property(CatalogMapper.FIELD_UPC).`as`("upc"),
            )
            .from(DataSource.collection(collection))
            .where(
                Expression.property(CatalogMapper.FIELD_TYPE)
                    .equalTo(Expression.string(CatalogMapper.TYPE_CATALOG))
            )
        query.execute().use { rs ->
            rs.allResults().forEach { row ->
                val id  = row.getString("id") ?: return@forEach
                val upc = row.getString("upc")?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                if (index.put(upc, id) != null) ambiguous.add(upc)
            }
        }
        ambiguous.forEach { index.remove(it) }
        return index
    }

    /**
     * Re-link every owned funko:: doc to its catalog doc, filling missing fields.
     * Emits [RelinkProgress] after each batch; the final emission has
     * [RelinkProgress.done] == true and carries a [RelinkResult].
     */
    fun relink(): Flow<RelinkProgress> = flow {
        val startMs = System.currentTimeMillis()
        val database = db.getDatabase()
        val collection = db.getCollection()

        // Collect all funko:: doc IDs first (snapshot), so we don't mutate while iterating a live query.
        val funkoIds = ArrayList<String>()
        QueryBuilder
            .select(SelectResult.expression(Meta.id).`as`("id"))
            .from(DataSource.collection(collection))
            .where(
                Expression.property(FunkoDexDatabase.FIELD_TYPE)
                    .equalTo(Expression.string(FUNKO_TYPE))
            )
            .execute().use { rs ->
                rs.allResults().forEach { row -> row.getString("id")?.let { funkoIds.add(it) } }
            }

        val total = funkoIds.size
        var processed = 0
        var enriched = 0      // items that had at least one field filled
        var unmatched = 0     // items with no catalog doc found
        var unchanged = 0     // items matched but already complete
        var errors = 0

        val upcIndex: Map<String, String> = try {
            buildCatalogUpcIndex(collection)
        } catch (e: Exception) {
            emptyMap()
        }

        funkoIds.chunked(CHUNK).forEach { chunk ->
            database.inBatch(UnitOfWork {
                chunk.forEach { funkoId ->
                    try {
                        val item = collection.getDocument(funkoId) ?: run { processed++; return@forEach }

                        // ── Resolve catalog doc: catalogRef → UPC fallback ──
                        val catalogRef = item.getString(FunkoDexDatabase.FIELD_CATALOG_REF)
                            ?.trim().orEmpty()
                        val itemUpc = item.getString(FunkoDexDatabase.FIELD_UPC)
                            ?.trim()?.takeIf { it.isNotBlank() }

                        val catalog = catalogRef.takeIf { it.isNotBlank() }
                            ?.let { collection.getDocument(it) }
                            ?: itemUpc?.let { upcIndex[it] }?.let { collection.getDocument(it) }

                        if (catalog == null) {
                            unmatched++
                            processed++
                            return@forEach
                        }

                        val mutable = item.toMutable()
                        var changed = false

                        // catalogRef itself — backfill if the item matched only via UPC.
                        if (catalogRef.isBlank()) {
                            mutable.setString(FunkoDexDatabase.FIELD_CATALOG_REF, catalog.id)
                            changed = true
                        }

                        // UPC — fill only when the item has none.
                        if (itemUpc == null) {
                            catalog.getString(CatalogMapper.FIELD_UPC)
                                ?.trim()?.takeIf { it.isNotBlank() }
                                ?.let { mutable.setString(FunkoDexDatabase.FIELD_UPC, it); changed = true }
                        }

                        // Retail price — fill only when item has 0.
                        if (item.getDouble(FunkoDexDatabase.FIELD_RETAIL_PRICE) <= 0.0) {
                            val retail = catalog.getDouble(CatalogMapper.FIELD_RETAIL_PRICE)
                            if (retail > 0.0) { mutable.setDouble(FunkoDexDatabase.FIELD_RETAIL_PRICE, retail); changed = true }
                        }

                        // PriceCharting URL — fill only when blank.
                        if (item.getString(FunkoDexDatabase.FIELD_PRICECHARTING_URL).isNullOrBlank()) {
                            catalog.getString(CatalogMapper.FIELD_PC_URL)
                                ?.takeIf { it.isNotBlank() }
                                ?.let { mutable.setString(FunkoDexDatabase.FIELD_PRICECHARTING_URL, it); changed = true }
                        }

                        // Market value — fill only when the item has no value AND the
                        // user hasn't set one manually. complete → loose → new, matching
                        // PriceService's marketValueComplete-primary convention.
                        val isManual = item.getBoolean(FunkoDexDatabase.FIELD_MARKET_VALUE_IS_MANUAL)
                        val currentAvg = item.getDouble(FunkoDexDatabase.FIELD_MARKET_AVG)
                        if (!isManual && currentAvg <= 0.0) {
                            val complete = parseMoney(catalog.getString(CatalogMapper.FIELD_MKT_VALUE_COMPLETE))
                            val loose    = parseMoney(catalog.getString(CatalogMapper.FIELD_MKT_VALUE_LOOSE))
                            val mint     = parseMoney(catalog.getString(CatalogMapper.FIELD_MKT_VALUE_NEW))
                            val avg = complete ?: loose ?: mint
                            if (avg != null) {
                                mutable.setDouble(FunkoDexDatabase.FIELD_MARKET_AVG, avg)
                                loose?.let { mutable.setDouble(FunkoDexDatabase.FIELD_MARKET_LOW, it) }
                                mint?.let { mutable.setDouble(FunkoDexDatabase.FIELD_MARKET_HIGH, it) }
                                mutable.setString(FunkoDexDatabase.FIELD_PRICE_UPDATED, LocalDate.now().toString())
                                changed = true
                            }
                        }

                        // Franchise — fill only when blank. Catalog has no single
                        // "franchise" field; the closest is the primary series tag.
                        if (item.getString(FunkoDexDatabase.FIELD_FRANCHISE).isNullOrBlank()) {
                            catalog.getString(CatalogMapper.FIELD_PRIMARY_SERIES)
                                ?.takeIf { it.isNotBlank() }
                                ?.let { mutable.setString(FunkoDexDatabase.FIELD_FRANCHISE, it); changed = true }
                        }

                        // Category — fill only when blank; re-derive genre to match.
                        if (item.getString(FunkoDexDatabase.FIELD_CATEGORY).isNullOrBlank()) {
                            catalog.getString(CatalogMapper.FIELD_CATEGORY)
                                ?.takeIf { it.isNotBlank() }
                                ?.let { cat ->
                                    mutable.setString(FunkoDexDatabase.FIELD_CATEGORY, cat)
                                    mutable.setString(
                                        FunkoDexDatabase.FIELD_GENRE,
                                        FunkoGenre.fromCategory(cat).name
                                    )
                                    changed = true
                                }
                        }

                        // funkoId / Pop number — fill only when blank.
                        if (item.getString(FunkoDexDatabase.FIELD_FUNKO_ID).isNullOrBlank()) {
                            catalog.getString(CatalogMapper.FIELD_FUNKO_NUMBER)
                                ?.takeIf { it.isNotBlank() }
                                ?.let { mutable.setString(FunkoDexDatabase.FIELD_FUNKO_ID, it); changed = true }
                        }

                        // Image URL — fill only when the item has no image of its own.
                        // Try the HobbyDB CDN image first, then the funko.com image.
                        if (item.getString(FunkoDexDatabase.FIELD_IMAGE_URL).isNullOrBlank()) {
                            val img = catalog.getString(CatalogMapper.FIELD_IMAGE_URL)
                                ?.takeIf { it.isNotBlank() }
                                ?: catalog.getString(CatalogMapper.FIELD_FUNKO_IMAGE)
                                    ?.takeIf { it.isNotBlank() }
                            if (img != null) { mutable.setString(FunkoDexDatabase.FIELD_IMAGE_URL, img); changed = true }
                        }

                        // Vaulted flag — fill from catalog only when the item is not
                        // already marked vaulted (one-way: never un-vault an item).
                        if (!item.getBoolean(FunkoDexDatabase.FIELD_IS_VAULTED) &&
                            catalog.getBoolean(CatalogMapper.FIELD_IS_VAULTED)
                        ) {
                            mutable.setBoolean(FunkoDexDatabase.FIELD_IS_VAULTED, true)
                            changed = true
                        }

                        if (changed) {
                            collection.save(mutable)
                            enriched++
                        } else {
                            unchanged++
                        }
                    } catch (e: Exception) {
                        errors++
                    }
                    processed++
                }
            })

            emit(RelinkProgress(processed = processed, total = total, enriched = enriched, done = false))
        }

        emit(
            RelinkProgress(
                processed = processed,
                total = total,
                enriched = enriched,
                done = true,
                result = RelinkResult(
                    enriched = enriched,
                    unmatched = unmatched,
                    unchanged = unchanged,
                    errors = errors,
                    durationMs = System.currentTimeMillis() - startMs,
                ),
            )
        )
    }.flowOn(Dispatchers.IO)
}

// ── Progress / result data classes ────────────────────────────────────────────

data class RelinkProgress(
    val processed: Int = 0,
    val total: Int = 0,
    val enriched: Int = 0,
    val done: Boolean = false,
    val result: RelinkResult? = null,
    val error: String? = null,
)

data class RelinkResult(
    val enriched: Int,
    val unmatched: Int,
    val unchanged: Int,
    val errors: Int,
    val durationMs: Long,
)
