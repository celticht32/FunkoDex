package com.funkodex.data.preload

import android.content.Context
import android.net.Uri
import com.couchbase.lite.MutableDocument
import com.couchbase.lite.UnitOfWork
import com.funkodex.data.db.FunkoDexDatabase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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

    private val gson = Gson()

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
        val type = object : TypeToken<List<EnrichedRecord>>() {}.type
        val records: List<EnrichedRecord> = try {
            gson.fromJson(json, type)
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
                        val docId    = "catalog::$handle"
                        val existing = database.getDocument(docId)

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

                            mutable.setString(CatalogMapper.FIELD_LAST_UPDATED, LocalDate.now().toString())
                            database.save(mutable)
                            enriched++

                        } else {
                            // ── Insert: build full document via CatalogMapper ──────────
                            val title = record.title?.trim()
                            if (title.isNullOrBlank()) {
                                skipped++
                                processed++
                                return@forEach
                            }

                            val parsedPrice = record.price
                                ?.replace(Regex("[^0-9.]"), "")
                                ?.toDoubleOrNull() ?: 0.0

                            val mapped = CatalogMapper.mapRecord(
                                handle           = handle,
                                title            = title,
                                imageName        = record.imageName?.trim() ?: "",
                                seriesList       = record.series ?: emptyList(),
                                upc              = record.upc?.takeIf { it.isNotBlank() },
                                price            = parsedPrice,
                                source           = record.funkoSource ?: "ENRICHED",
                                available        = record.available,
                                productUrl       = record.productUrl,
                                funkoImageUrl    = record.funkoPrimaryImage,
                                funkoShopId      = record.pid,
                                marketValueLoose = record.marketValueLoose,
                                marketValueNew   = record.marketValueNew,
                                pricechartingId  = record.pricechartingId,
                                pricechartingUrl = record.pricechartingUrl,
                            )
                            database.save(MutableDocument(docId, mapped))
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
