package com.funkodex.network

import android.content.Context
import com.funkodex.data.model.FunkoItem
import com.funkodex.security.SecureKeyStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
        val localResults = searchLocalByName(query)
        if (localResults.isNotEmpty()) return@withContext localResults
        searchChannel3ByName(query)
    }

    // ─── Layer 1: Kenny Chan local JSON ───────────────────────────────────────

    private fun loadLocalDb(): List<LocalFunkoRecord> {
        localDb?.let { return it }
        return try {
            val json = context.assets.open("funko_data.json")
                .bufferedReader().use { it.readText() }
            val type = object : TypeToken<List<LocalFunkoRecord>>() {}.type
            val records: List<LocalFunkoRecord> = gson.fromJson(json, type)
            records.also { localDb = it }
        } catch (e: Exception) {
            emptyList<LocalFunkoRecord>().also { localDb = it }
        }
    }

    private fun lookupLocalByUpc(upc: String): FunkoItem? {
        return loadLocalDb()
            .firstOrNull { it.upc == upc || it.upc?.trimStart('0') == upc.trimStart('0') }
            ?.toFunkoItem(upc)
    }

    private fun searchLocalByName(query: String): List<FunkoItem> {
        val q = query.lowercase()
        return loadLocalDb()
            .filter { record ->
                record.name?.lowercase()?.contains(q) == true ||
                record.series?.lowercase()?.contains(q) == true
            }
            .take(20)
            .map { it.toFunkoItem() }
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
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@runCatching null
            val body = response.body?.string() ?: return@runCatching null
            val parsed = gson.fromJson(body, Channel3Response::class.java)
            parsed.products?.firstOrNull()?.toFunkoItem()
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
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@runCatching emptyList()
            val body = response.body?.string() ?: return@runCatching emptyList()
            val parsed = gson.fromJson(body, Channel3Response::class.java)
            parsed.products?.map { it.toFunkoItem() } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    // ─── Layer 3: UPCitemdb ───────────────────────────────────────────────────

    private suspend fun lookupUpcItemDb(upc: String): FunkoItem? {
        return runCatching {
            val request = Request.Builder()
                .url("$UPCITEMDB_LOOKUP$upc")
                .header("User-Agent", USER_AGENT)
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@runCatching null
            val body = response.body?.string() ?: return@runCatching null
            val parsed = gson.fromJson(body, UpcItemDbResponse::class.java)
            parsed.items?.firstOrNull()?.toFunkoItem(upc)
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
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@runCatching null
            val html = response.body?.string() ?: return@runCatching null

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
        val name: String? = null,
        val imageName: String? = null,
        val series: List<String>? = null,
        val upc: String? = null,
        val number: String? = null,
        val category: String? = null,
        val exclusive: String? = null,
        val vaulted: Boolean? = null,
        val price: Double? = null,
    ) {
        fun toFunkoItem(upcOverride: String? = null): FunkoItem {
            val id = "funko::${upcOverride ?: upc ?: UUID.randomUUID()}"
            return FunkoItem(
                id                = id,
                upc               = upcOverride ?: upc ?: "",
                name              = name ?: "Unknown",
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
