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
 * EncryptedSharedPreferences via SecureKeyStore. It is never hardcoded,
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
        lookupLocalByUpc(upc)
            ?: lookupChannel3ByUpc(upc)
            ?: lookupUpcItemDb(upc)
            ?: lookupBarcodeSpider(upc)
    }

    /** Free-text search — used by the not-found lookup sheet. */
    suspend fun searchByName(query: String): List<FunkoItem> = withContext(Dispatchers.IO) {
        val enabled = categoryPrefs.getEnabledCategories()
        val localResults = searchLocalByName(query)
        val results = if (localResults.isNotEmpty()) localResults else searchChannel3ByName(query)
        // Apply category filter — only show items whose category is enabled
        if (enabled.isEmpty()) results
        else results.filter { item ->
            item.category.isEmpty() ||
            enabled.any { key -> item.category.contains(key, ignoreCase = true) }
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

    private fun lookupLocalByUpc(upc: String): FunkoItem? {
        return loadLocalDb()
            .firstOrNull { it.upc == upc || it.upc?.trimStart('0') == upc.trimStart('0') }
            ?.toFunkoItem(upc)
    }

    private fun searchLocalByName(query: String): List<FunkoItem> {
        val t0 = System.currentTimeMillis()
        return try {
            val q = query.lowercase()
            // Diagnostic: count total catalog documents
            val totalCount = com.couchbase.lite.QueryBuilder
                .select(com.couchbase.lite.SelectResult.expression(com.couchbase.lite.Meta.id).`as`("id"))
                .from(com.couchbase.lite.DataSource.database(db.getDatabase()))
                .where(com.couchbase.lite.Expression.property("type")
                    .equalTo(com.couchbase.lite.Expression.string("catalog")))
                .execute().use { it.allResults().size }
            val results = com.couchbase.lite.QueryBuilder
                .select(
                    com.couchbase.lite.SelectResult.expression(com.couchbase.lite.Meta.id).`as`("id"),
                    com.couchbase.lite.SelectResult.all()
                )
                .from(com.couchbase.lite.DataSource.database(db.getDatabase()))
                .where(
                    com.couchbase.lite.Expression.property("type")
                        .equalTo(com.couchbase.lite.Expression.string(
                            com.funkodex.data.preload.CatalogPreloader.TYPE_CATALOG))
                        .and(
                            com.couchbase.lite.Function.lower(
                                com.couchbase.lite.Expression.property("title"))
                                .like(com.couchbase.lite.Expression.string("%$q%"))
                            .or(
                                com.couchbase.lite.Function.lower(
                                    com.couchbase.lite.Expression.property("series"))
                                    .like(com.couchbase.lite.Expression.string("%$q%"))
                            )
                        )
                )
                .limit(com.couchbase.lite.Expression.intValue(20))
                .execute()
                .allResults()
                .mapNotNull { result ->
                    val docId = result.getString("id") ?: return@mapNotNull null
                    val doc = db.getDatabase().getDocument(docId) ?: return@mapNotNull null
                    com.funkodex.data.model.FunkoItem(
                        id           = docId,
                        upc          = doc.getString("upc") ?: "",
                        name         = doc.getString("title") ?: "",
                        franchise    = doc.getString("series") ?: "",
                        seriesNumber = doc.getString("seriesNumber") ?: "",
                        category     = doc.getString("category") ?: "",
                        imageUrl     = doc.getString("imageUrl") ?: "",
                        retailPrice  = doc.getDouble("retailPrice"),
                        isExclusive  = doc.getBoolean("isExclusive"),
                        exclusiveRetailer = doc.getString("exclusiveRetailer") ?: "",
                        isVaulted    = doc.getBoolean("isVaulted"),
                    )
                }
            // If Couchbase returned nothing, catalog may still be loading — fall back to JSON
            if (results.isEmpty()) loadLocalDb().filter { record ->
                record.resolvedName?.lowercase()?.contains(q) == true ||
                record.series?.any { it.lowercase().contains(q) } == true
            }.take(20).map { it.toFunkoItem() }
            else results
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
                franchise         = attributes?.get("series") ?: brand ?: "",
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
