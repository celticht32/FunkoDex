package com.funkodex.network

import android.content.Context
import com.funkodex.data.model.FunkoItem
import com.funkodex.security.SecureKeyStore
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Layered Funko lookup service — waterfall strategy:
 *
 *  Layer 1 — Kenny Chan local JSON bundle (~23K items, shipped with app, instant, offline)
 *  Layer 2 — Channel3 Funko Product API (structured, UPC + name search, free tier)
 *  Layer 3 — UPCitemdb (generic UPC fallback, 100 req/day free, no key needed)
 *  Layer 4 — null (caller shows not-found lookup sheet)
 *
 * SECURITY: Channel3 API key is user-entered in Settings and stored in
 * SecureKeyStore (AES/GCM, Android Keystore-backed). It is never hardcoded,
 * never in BuildConfig, and never in local.properties. If the key is absent
 * the service silently skips Channel3 and falls through to UPCitemdb.
 *
 * SETUP:
 *  - Channel3: Settings > Data Sources > Channel3 API key
 *    Sign up free at https://trychannel3.com
 *  - funko_data.json: place in app/src/main/assets/funko_data.json
 */
@Singleton
class FunkoLookupService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val secureKeyStore: SecureKeyStore,
    private val db: com.funkodex.data.db.FunkoDexDatabase,
    private val categoryPrefs: com.funkodex.data.repository.CategoryPreferenceRepository,
) {
    companion object {
        private const val CHANNEL3_BASE    = "https://api.trychannel3.com/v1"
        private const val CHANNEL3_SEARCH  = "$CHANNEL3_BASE/products/search"
        private const val UPCITEMDB_LOOKUP = "https://api.upcitemdb.com/prod/trial/lookup?upc="
        private const val BARCODESPIDER     = "https://www.barcodelookup.com/"
        private const val USER_AGENT       = "FunkoDex/1.0 Android"

        /**
         * Normalize a string for punctuation-insensitive name matching.
         * Lowercases, replaces any run of non-alphanumeric characters with a
         * single space, and trims. So "Mr. Toad", "mr toad", and "MR.  TOAD"
         * all normalize to "mr toad".
         */
        internal fun normalizeForSearch(s: String): String =
            s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

        /**
         * True when every whitespace-delimited token of [query] appears as a
         * substring of the normalized [haystack]. Token order does not matter,
         * so "toad mr" matches "Mr. Toad" just as "mr toad" does. An all-blank
         * query matches nothing.
         */
        internal fun matchesAllTokens(query: String, haystack: String): Boolean {
            val tokens = normalizeForSearch(query).split(' ').filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return false
            val hay = normalizeForSearch(haystack)
            return tokens.all { hay.contains(it) }
        }
    }

    private val gson = Gson()

    // Lazy-loaded local database — loaded once on first lookup, kept in memory
    private var localDb: List<LocalFunkoRecord>? = null

    /** Call once at app startup to pre-parse funko_data.json in background. */
    fun warmup() {
        if (localDb != null) return
        Thread { loadLocalDb() }.start()
    }

    // ─── Public API ────────────────────────────────────────────────────────────

    /** Primary entry point: look up by UPC barcode. */
    suspend fun lookupByUpc(upc: String): FunkoItem? = withContext(Dispatchers.IO) {
        // Couchbase catalog is the source of truth (holds all imported/enriched
        // records). The bundled JSON is only a preload seed, so it's a fallback
        // for the rare case the catalog hasn't been populated yet. External APIs
        // are last resorts for UPCs absent from the catalog entirely.
        lookupCatalogByUpc(upc)
            ?: lookupLocalByUpc(upc)
            ?: lookupChannel3ByUpc(upc)
            ?: lookupUpcItemDb(upc)
            ?: lookupBarcodeSpider(upc)
    }

    /** Free-text search — used by the not-found lookup sheet. */
    suspend fun searchByName(query: String): List<FunkoItem> = withContext(Dispatchers.IO) {
        val enabled = categoryPrefs.getEnabledCategories()
        val localResults = searchLocalByName(query)
        val rawResults = if (localResults.isNotEmpty()) localResults else searchChannel3ByName(query)
        // Drop identify-only catalog rows: entries with no UPC, no Pop number, no
        // PriceCharting link, and no franchise. These ~6k rows (prototypes, box
        // sets, Pocket Pops) can be *seen* but not acted on — a user who picks one
        // gets no UPC to attach and no price to look up, so it's a dead end in the
        // add flow. Keeping any ONE actionable signal is enough to surface it.
        val results = rawResults.filter { item ->
            item.upc.isNotBlank() ||
                item.seriesNumber.isNotBlank() ||
                item.pricechartingUrl.isNotBlank() ||
                item.franchise.isNotBlank()
        }
        // Apply category filter. Hide an item ONLY when its category is a
        // RECOGNIZED Pop! line that the user has explicitly disabled. Items with
        // a blank category, or a category not in the canonical list (e.g.
        // enriched records whose series tags had no clean "Pop! X" line), were
        // never deliberately turned off — so they must pass through, not vanish.
        if (enabled.isEmpty()) results
        else {
            val knownKeys = com.funkodex.data.model.FunkoCategories.ALL
                .map { it.key }.toSet()
            results.filter { item ->
                if (item.category.isEmpty()) return@filter true
                val key = com.funkodex.data.model.FunkoCategories.toKey(item.category)
                key !in knownKeys || key in enabled
            }
        }
    }

    // ─── Layer 1: Kenny Chan local JSON ───────────────────────────────────────

    private fun loadLocalDb(): List<LocalFunkoRecord> {
        localDb?.let {
            return it
        }
        val t0 = System.currentTimeMillis()
        return try {
            val records = mutableListOf<LocalFunkoRecord>()
            context.assets.open("funko_data.json").bufferedReader().use { reader ->
                val jr = com.google.gson.stream.JsonReader(reader)
                jr.beginArray()
                while (jr.hasNext()) {
                    records.add(gson.fromJson(jr, LocalFunkoRecord::class.java))
                }
                jr.endArray()
            }
            records.also { localDb = it }
        } catch (e: Exception) {
            android.util.Log.e("FunkoLookup", "Failed to load funko_data.json: ${e.message}")
            emptyList<LocalFunkoRecord>().also { localDb = it }
        }
    }

    /**
     * Build a [FunkoItem] from a catalog document. Shared by the UPC lookup and
     * the name-search path so both produce identical items. Seeds marketAvg from
     * the catalog's PriceCharting in-box (Complete) price when present — a
     * non-manual baseline a live refresh or manual value can still override.
     */
    private fun catalogDocToFunkoItem(
        docId: String,
        doc: com.couchbase.lite.Document,
    ): com.funkodex.data.model.FunkoItem {
        val pcComplete = doc.getString(
            com.funkodex.data.preload.CatalogMapper.FIELD_MKT_VALUE_COMPLETE)
            ?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull() ?: 0.0
        val pcUrl = doc.getString(com.funkodex.data.preload.CatalogMapper.FIELD_PC_URL) ?: ""
        // Franchise is the user's property grouping. Seed it from the enricher's
        // property-specific franchiseSuggestion when present, else derive one from
        // the PriceCharting console (umbrella consoles yield none). Do NOT seed it
        // from the raw catalog "series" tag — that is a format/line, not a property,
        // and would mis-group the item. A blank here lets the first-scan prompt ask.
        val franchiseSeed =
            doc.getString(com.funkodex.data.preload.CatalogMapper.FIELD_FRANCHISE_SUGGESTION)
                ?.takeIf { it.isNotBlank() }
                ?: com.funkodex.data.util.ConsoleFranchise.resolve(
                    doc.getString(com.funkodex.data.preload.CatalogMapper.FIELD_PC_SERIES),
                    pcUrl,
                )
                ?: ""
        // Pop number: prefer the PriceCharting Box Number (funkoNumber, the
        // authoritative structured value) over the title-regex seriesNumber.
        // Verified: where both exist they agree 375/377; the box number wins the
        // rare conflict. Normalised to a leading "#".
        val rawNumber = doc.getString(com.funkodex.data.preload.CatalogMapper.FIELD_FUNKO_NUMBER)
            ?.takeIf { it.isNotBlank() }
            ?: doc.getString("seriesNumber")?.takeIf { it.isNotBlank() }
            ?: ""
        val displayNumber = when {
            rawNumber.isBlank() -> ""
            rawNumber.startsWith("#") -> rawNumber
            else -> "#$rawNumber"
        }
        return com.funkodex.data.model.FunkoItem(
            id           = docId,
            upc          = doc.getString("upc") ?: "",
            name         = doc.getString("title") ?: "",
            franchise    = franchiseSeed,
            seriesNumber = displayNumber,
            setTag       = doc.getString(com.funkodex.data.preload.CatalogMapper.FIELD_SET_TAG) ?: "",
            category     = doc.getString("category") ?: "",
            imageUrl     = doc.getString("imageUrl") ?: "",
            retailPrice  = doc.getDouble("retailPrice"),
            isExclusive  = doc.getBoolean("isExclusive"),
            exclusiveRetailer = doc.getString("exclusiveRetailer") ?: "",
            isVaulted    = doc.getBoolean("isVaulted"),
            marketAvg    = pcComplete,
            marketValueIsApproximate = doc.getBoolean(com.funkodex.data.preload.CatalogMapper.FIELD_MKT_IS_APPROX),
            pricechartingUrl = pcUrl,
        )
    }

    /**
     * Primary scan lookup: query the Couchbase catalog (the live data store) for
     * a doc whose UPC matches. The bundled funko_data.json is only a preload seed,
     * not the source of truth, so the catalog — which holds every imported/enriched
     * record — is searched first. Leading zeros are normalised both ways since UPC
     * encodings vary (UPC-A vs the value stored). Returns the matching item or null.
     */
    private fun lookupCatalogByUpc(upc: String): com.funkodex.data.model.FunkoItem? {
        val trimmed = upc.trimStart('0')
        return try {
            val results = com.couchbase.lite.QueryBuilder
                .select(
                    com.couchbase.lite.SelectResult.expression(com.couchbase.lite.Meta.id).`as`("id"),
                    com.couchbase.lite.SelectResult.property("upc").`as`("upc"),
                )
                .from(com.couchbase.lite.DataSource.collection(db.getCollection()))
                .where(
                    com.couchbase.lite.Expression.property("type")
                        .equalTo(com.couchbase.lite.Expression.string(
                            com.funkodex.data.preload.CatalogPreloader.TYPE_CATALOG))
                        .and(
                            com.couchbase.lite.Expression.property("upc")
                                .equalTo(com.couchbase.lite.Expression.string(upc))
                            .or(
                                com.couchbase.lite.Expression.property("upc")
                                    .equalTo(com.couchbase.lite.Expression.string(trimmed))
                            )
                        )
                )
                .limit(com.couchbase.lite.Expression.intValue(1))
                .execute()
                .allResults()
            val docId = results.firstOrNull()?.getString("id") ?: return null
            val doc = db.getCollection().getDocument(docId) ?: return null
            catalogDocToFunkoItem(docId, doc)
        } catch (e: Exception) {
            android.util.Log.e("FunkoLookup", "Catalog UPC lookup failed: ${e.message}")
            null
        }
    }

    private fun lookupLocalByUpc(upc: String): FunkoItem? {
        return loadLocalDb()
            .firstOrNull { it.upc == upc || it.upc?.trimStart('0') == upc.trimStart('0') }
            ?.toFunkoItem(upc)
    }

    private fun searchLocalByName(query: String): List<FunkoItem> {
        return try {
            val tokens = normalizeForSearch(query).split(' ').filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return emptyList()
            // Use the longest token as the coarse Couchbase pre-filter: it is the
            // most selective and keeps the candidate set small. Punctuation in the
            // stored value can't break this because we only require ONE normalized
            // token to appear via LIKE; exact token/punctuation matching is then
            // done in-memory by matchesAllTokens below. (A leading-wildcard LIKE
            // is a full scan regardless, so this is no slower than the old query.)
            val coarse = tokens.maxByOrNull { it.length } ?: tokens.first()

            val candidates = com.couchbase.lite.QueryBuilder
                .select(
                    com.couchbase.lite.SelectResult.expression(com.couchbase.lite.Meta.id).`as`("id"),
                    com.couchbase.lite.SelectResult.all()
                )
                .from(com.couchbase.lite.DataSource.collection(db.getCollection()))
                .where(
                    com.couchbase.lite.Expression.property("type")
                        .equalTo(com.couchbase.lite.Expression.string(
                            com.funkodex.data.preload.CatalogPreloader.TYPE_CATALOG))
                        .and(
                            com.couchbase.lite.Function.lower(
                                com.couchbase.lite.Expression.property("title"))
                                .like(com.couchbase.lite.Expression.string("%$coarse%"))
                            .or(
                                com.couchbase.lite.Function.lower(
                                    com.couchbase.lite.Expression.property("series"))
                                    .like(com.couchbase.lite.Expression.string("%$coarse%"))
                            )
                        )
                )
                .execute()
                .allResults()
                .mapNotNull { result ->
                    val docId = result.getString("id") ?: return@mapNotNull null
                    val doc = db.getCollection().getDocument(docId) ?: return@mapNotNull null
                    val title  = doc.getString("title") ?: ""
                    val series = doc.getString("series") ?: ""
                    // Require ALL query tokens to match against title + series combined.
                    if (!matchesAllTokens(query, "$title $series")) return@mapNotNull null
                    catalogDocToFunkoItem(docId, doc)
                }
                .take(20)

            // If Couchbase returned nothing, the catalog may still be loading —
            // fall back to the in-memory JSON bundle using the same token logic.
            if (candidates.isEmpty()) {
                loadLocalDb().asSequence()
                    .filter { record ->
                        val name   = record.resolvedName ?: ""
                        val series = record.series?.joinToString(" ") ?: ""
                        matchesAllTokens(query, "$name $series")
                    }
                    .take(20)
                    .map { it.toFunkoItem() }
                    .toList()
            } else candidates
        } catch (e: Exception) {
            android.util.Log.e("FunkoLookup", "Couchbase search failed: ${e.message}")
            emptyList()
        }
    }

    // ─── Layer 2: Channel3 API ────────────────────────────────────────────────

    private suspend fun lookupChannel3ByUpc(upc: String): FunkoItem? {
        val key = secureKeyStore.getChannel3Key()
        if (key.isEmpty()) return null
        return runCatching {
            val request = Request.Builder()
                .url("$CHANNEL3_SEARCH?upc=$upc")
                .header("Authorization", "Bearer $key")
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(request).execute().let { response ->
                val body = response.use { it.body?.string() }
                if (!response.isSuccessful || body == null) return@runCatching null
                val parsed = gson.fromJson(body, Channel3Response::class.java)
                parsed.products?.firstOrNull()?.toFunkoItem()
            }
        }.getOrNull()
    }

    private suspend fun searchChannel3ByName(query: String): List<FunkoItem> {
        val key = secureKeyStore.getChannel3Key()
        if (key.isEmpty()) return emptyList()
        return runCatching {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("$CHANNEL3_SEARCH?q=$encoded&brand=Funko")
                .header("Authorization", "Bearer $key")
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(request).execute().let { response ->
                val body = response.use { it.body?.string() }
                if (!response.isSuccessful || body == null) return@runCatching emptyList()
                val parsed = gson.fromJson(body, Channel3Response::class.java)
                parsed.products?.map { it.toFunkoItem() } ?: emptyList()
            }
        }.getOrElse { emptyList() }
    }

    // ─── Layer 3: UPCitemdb ───────────────────────────────────────────────────

    private suspend fun lookupUpcItemDb(upc: String): FunkoItem? {
        return runCatching {
            val request = Request.Builder()
                .url("$UPCITEMDB_LOOKUP$upc")
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(request).execute().let { response ->
                val body = response.use { it.body?.string() }
                if (!response.isSuccessful || body == null) return@runCatching null
                val parsed = gson.fromJson(body, UpcItemDbResponse::class.java)
                parsed.items?.firstOrNull()?.toFunkoItem(upc)
            }
        }.getOrNull()
    }

    // ─── Layer 3.5: Barcode Spider (B4 — fallback before not-found sheet) ────────

    /**
     * Public UPC lookup using barcodelookup.com.
     * Used as a last-resort before showing the manual not-found sheet.
     * Parses basic product info from the HTML response (no API key needed).
     * Only accepts results that clearly contain "Funko" or "Pop!" in the title.
     */
    private suspend fun lookupBarcodeSpider(upc: String): FunkoItem? {
        return runCatching {
            val request = Request.Builder()
                .url("$BARCODESPIDER$upc")
                .header("User-Agent", "Mozilla/5.0 (Android) FunkoDex/1.0")
                .header("Accept", "text/html")
                .build()
            val html = client.newCall(request).execute().let { response ->
                response.use { it.body?.string() }.also {
                    if (!response.isSuccessful) return@runCatching null
                }
            } ?: return@runCatching null

            // Extract product name from <h4 class="product-name">
            val nameRegex  = Regex("""<h4[^>]*class="product-name"[^>]*>(.*?)</h4>""", RegexOption.DOT_MATCHES_ALL)
            val nameMatch  = nameRegex.find(html) ?: return@runCatching null
            val title      = nameMatch.groupValues[1].replace(Regex("<[^>]*>"), "").trim()

            // Only return Funko items
            if (!title.contains("funko", ignoreCase = true) &&
                !title.contains("pop!", ignoreCase = true)) return@runCatching null

            FunkoItem(
                id       = "funko::$upc",
                upc      = upc,
                name     = title,
                franchise = "",   // not available from barcode lookup
                imageUrl  = "",
            )
        }.getOrNull()
    }

        // ─── Data models for JSON parsing ─────────────────────────────────────────

    /** Kenny Chan JSON record shape */
    data class LocalFunkoRecord(
        val handle: String? = null,
        val title: String? = null,       // Kenny Chan JSON uses "title" not "name"
        val name: String? = null,        // fallback alias
        val imageName: String? = null,
        val series: List<String>? = null,
        val upc: String? = null,
        val number: String? = null,
        val category: String? = null,
        val exclusive: String? = null,
        val vaulted: Boolean? = null,
        val price: Double? = null,
    ) {
        // Resolve name from either field
        val resolvedName get() = title ?: name
        fun toFunkoItem(upcOverride: String? = null): FunkoItem {
            val id = "funko::${upcOverride ?: upc ?: UUID.randomUUID()}"
            return FunkoItem(
                id                = id,
                upc               = upcOverride ?: upc ?: "",
                name              = resolvedName ?: "Unknown",
                franchise         = series?.firstOrNull() ?: "",
                seriesNumber      = number ?: "",
                category          = category ?: "",
                imageUrl          = imageName ?: "",
                retailPrice       = price ?: 0.0,
                isExclusive       = exclusive != null,
                exclusiveRetailer = exclusive ?: "",
                isVaulted         = vaulted ?: false,
            )
        }
    }

    data class Channel3Response(
        val products: List<Channel3Product>? = null,
        val total: Int? = null,
    )

    data class Channel3Product(
        val id: String? = null,
        val name: String? = null,
        val brand: String? = null,
        val upc: String? = null,
        val imageUrl: String? = null,
        val price: Double? = null,
        val category: String? = null,
        val attributes: Map<String, String>? = null,
    ) {
        fun toFunkoItem(): FunkoItem {
            val upcVal = upc ?: ""
            return FunkoItem(
                id                = "funko::${upcVal.ifEmpty { id ?: UUID.randomUUID().toString() }}",
                upc               = upcVal,
                funkoId           = id ?: "",
                name              = name ?: "Unknown",
                franchise         = attributes?.get("series") ?: "",
                seriesNumber      = attributes?.get("number") ?: "",
                category          = category ?: "",
                imageUrl          = imageUrl ?: "",
                retailPrice       = price ?: 0.0,
                isExclusive       = attributes?.containsKey("exclusive") == true,
                exclusiveRetailer = attributes?.get("exclusive") ?: "",
            )
        }
    }

    data class UpcItemDbResponse(
        val items: List<UpcItem>? = null,
        val code: String? = null,
    )
    data class UpcItem(
        val title: String? = null,
        val brand: String? = null,
        val images: List<String>? = null,
        val lowest_recorded_price: Double? = null,
    ) {
        fun toFunkoItem(upc: String): FunkoItem? {
            val titleLower = title?.lowercase() ?: return null
            if (!titleLower.contains("funko") && !titleLower.contains("pop!")) return null
            return FunkoItem(
                id       = "funko::$upc",
                upc      = upc,
                name     = title ?: return null,
                franchise = brand ?: "Unknown",
                imageUrl = images?.firstOrNull() ?: "",
            )
        }
    }
}
