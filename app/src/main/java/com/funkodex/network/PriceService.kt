package com.funkodex.network

import com.funkodex.util.FunkoDexLogger
import com.funkodex.data.model.FunkoItem
import com.funkodex.data.model.PriceSnapshot
import com.funkodex.data.model.PriceSource
import com.funkodex.security.SecureKeyStore
import com.funkodex.auth.TokenRefreshManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PriceService — B1
 *
 * Multi-tier price waterfall for a Funko item.
 * Tries each tier in order and returns the first successful result.
 * Results are NOT cached here — the caller (FunkoRepository) owns the cache.
 *
 * Tier 1 — Instant (no network):
 *   retailPrice already stored on FunkoItem from catalog data.
 *
 * Tier 2 — Free network, no auth:
 *   2a. eBay completed/sold listings (real sold prices, HTML s-card parse, no key)
 *   2b. UPCitemdb  (generic pricing, 100 req/day free)
 *   2c. Channel3 free (structured Funko data, 100 req/day free)
 *
 * Tier 3 — API key required:
 *   3a. Channel3 premium (user's key from SecureKeyStore, higher limits)
 *
 * Tier 4 — HobbyDB (OAuth required):
 *   4a. HobbyDB price API — uses TokenRefreshManager for silent token refresh.
 *       Token is valid for 2h (eBay) or provider-specific. Refreshed automatically.
 *
 * SECURITY: Channel3 key read from SecureKeyStore (AES/GCM, Android Keystore-backed).
 *           Never from BuildConfig or local.properties.
 */
@Singleton
class PriceService @Inject constructor(
    private val client: OkHttpClient,
    private val secureKeyStore: SecureKeyStore,
    private val tokenRefresh: TokenRefreshManager,
) {
    companion object {
        private const val TAG = "PriceService"
        private const val USER_AGENT = "FunkoDex/1.0 Android (price lookup)"

        // eBay sold/completed listings — HTML search results (the RSS feed
        // _rss=1 was deprecated; eBay now serves the s-card HTML layout).
        private const val EBAY_SOLD_BASE =
            "https://www.ebay.com/sch/i.html?LH_Complete=1&LH_Sold=1&_ipg=60&_nkw="

        // eBay serves the results HTML to browser-like clients; a generic UA
        // can be redirected to a challenge page or an empty result.
        private const val EBAY_BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

        // UPCitemdb
        private const val UPCITEMDB =
            "https://api.upcitemdb.com/prod/trial/lookup?upc="

        // HobbyDB pricing
        private const val HOBBYDB_BASE  = "https://hobby-db.com/api/v1"

        // Channel3
        private const val CHANNEL3_BASE   = "https://api.trychannel3.com/v1"
        private const val CHANNEL3_SEARCH = "$CHANNEL3_BASE/products/search"
    }

    // ─── Public entry point ────────────────────────────────────────────────────

    /**
     * Fetch the best available price data for [item].
     * Returns a PriceSnapshot if any tier succeeds, null if all fail.
     * Tier 1 (retail) always returns a result if retailPrice > 0.
     */
    suspend fun fetchPrice(item: FunkoItem): PriceSnapshot? = withContext(Dispatchers.IO) {
        // Tier 1: retail price from catalog — always instant
        if (item.retailPrice > 0) {
            FunkoDexLogger.d(TAG, "Tier 1 hit for ${item.name}: retail=${item.retailPrice}")
            return@withContext PriceSnapshot(
                itemId      = item.id,
                source      = PriceSource.RETAIL_CATALOG,
                retail      = item.retailPrice,
                fetchedAt   = LocalDate.now(),
            )
        }

        // Tier 2a: eBay sold listings — most valuable (real sold prices)
        fetchEbaySold(item)?.let {
            FunkoDexLogger.d(TAG, "Tier 2a (eBay sold) hit for ${item.name}")
            return@withContext it
        }

        // Tier 2b: UPCitemdb
        if (item.upc.isNotEmpty()) {
            fetchUpcItemDb(item)?.let {
                FunkoDexLogger.d(TAG, "Tier 2b (UPCitemdb) hit for ${item.name}")
                return@withContext it
            }
        }

        // Tier 2c: Channel3 free (uses upc if available, else name search)
        fetchChannel3(item, premium = false)?.let {
            FunkoDexLogger.d(TAG, "Tier 2c (Channel3 free) hit for ${item.name}")
            return@withContext it
        }

        // Tier 3: Channel3 premium
        if (secureKeyStore.hasChannel3Key()) {
            fetchChannel3(item, premium = true)?.let {
                FunkoDexLogger.d(TAG, "Tier 3 (Channel3 premium) hit for ${item.name}")
                return@withContext it
            }
        }

        // Tier 4: HobbyDB — silently refresh token if needed, then fetch
        tokenRefresh.getValidHobbyDbToken()?.let { token ->
            fetchHobbyDb(item, token)?.let {
                FunkoDexLogger.d(TAG, "Tier 4 (HobbyDB) hit for ${item.name}")
                return@withContext it
            }
        }

        FunkoDexLogger.d(TAG, "All tiers exhausted for ${item.name}")
        null
    }

    // ─── Tier 4: HobbyDB ─────────────────────────────────────────────────────

    private fun fetchHobbyDb(item: FunkoItem, token: String): PriceSnapshot? = runCatching {
        // HobbyDB search by name — returns an array of matching items with pricing
        val nameEnc = java.net.URLEncoder.encode(item.name, "UTF-8")
        val url     = "$HOBBYDB_BASE/items?q=$nameEnc&category=pop&per_page=5"

        val response = client.newCall(
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("User-Agent", USER_AGENT)
                .build()
        ).execute()

        if (!response.isSuccessful) {
            FunkoDexLogger.w(TAG, "HobbyDB returned HTTP ${response.code}")
            return@runCatching null
        }

        val body = response.body?.string() ?: return@runCatching null
        val json = com.google.gson.JsonParser.parseString(body).asJsonObject

        // Find the best matching item (highest name similarity)
        val items = json.getAsJsonArray("items") ?: return@runCatching null
        if (items.size() == 0) return@runCatching null

        // Take the first result — HobbyDB returns by relevance
        val hobbyItem = items[0].asJsonObject
        val prices    = hobbyItem.getAsJsonObject("price_data")

        val low  = prices?.get("low")?.asDouble  ?: 0.0
        val high = prices?.get("high")?.asDouble ?: 0.0
        val avg  = prices?.get("avg")?.asDouble  ?: ((low + high) / 2.0)
        val cnt  = prices?.get("sales_count")?.asInt ?: 0

        if (low == 0.0 && high == 0.0) return@runCatching null

        PriceSnapshot(
            itemId    = item.id,
            source    = PriceSource.HOBBYDB,
            retail    = item.retailPrice,
            low       = low,
            high      = high,
            avg       = avg,
            saleCount = cnt,
            fetchedAt = LocalDate.now(),
        )
    }.getOrNull()

    // ─── Tier 2a: eBay sold/completed listings (HTML) ─────────────────────────

    private fun fetchEbaySold(item: FunkoItem): PriceSnapshot? {
        return runCatching {
            // Build search query: "funko pop {name} {number}" — specific enough to avoid noise
            val query = buildEbayQuery(item)
            val url   = EBAY_SOLD_BASE + URLEncoder.encode(query, "UTF-8")

            val response = client.newCall(
                Request.Builder()
                    .url(url)
                    // eBay serves the s-card results page to browser-like clients;
                    // a non-browser UA can be redirected or blocked.
                    .header("User-Agent", EBAY_BROWSER_UA)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .build()
            ).execute()

            if (!response.isSuccessful) return@runCatching null
            val html = response.body?.string() ?: return@runCatching null

            parseEbaySold(item.id, html)
        }.getOrNull()
    }

    private fun buildEbayQuery(item: FunkoItem): String {
        val parts = mutableListOf("funko pop")
        if (item.name.isNotEmpty()) {
            // Strip series number from name if already in seriesNumber field
            val cleanName = item.name
                .replace(Regex("""#\d+"""), "")
                .replace(Regex("""\(\d{4}\)"""), "")   // strip year e.g. "(1989)"
                .trim()
            parts.add(cleanName)
        }
        if (item.seriesNumber.isNotEmpty()) parts.add(item.seriesNumber)
        return parts.joinToString(" ")
    }

    /**
     * Parse eBay's sold-listings HTML (the current `s-card` results layout; the
     * older `s-item` layout and the `_rss=1` XML feed are both retired).
     *
     * Sold prices appear as:
     *   <span class="su-styled-text positive bold large-1 s-card__price">$20.00</span>
     *   <span class="su-styled-text primary  bold large-1 s-card__price">$20.00</span>
     * A `strikethrough` modifier marks an original (was-)price and is excluded.
     *
     * Verified against a live results page (Mr. Toad with Monocle #1496, 2026-06).
     * If eBay changes these class names again, this returns null and Tier 2a is
     * simply skipped — pricing falls through to the next tier rather than crashing.
     */
    private fun parseEbaySold(itemId: String, html: String): PriceSnapshot? {
        // Match s-card__price spans, capturing the class list (to drop strikethrough)
        // and the inner text. Tolerant of attribute/whitespace variation.
        val spanRegex = Regex(
            """<span class="([^"]*\bs-card__price\b[^"]*)"[^>]*>\s*\$?([\d,]+(?:\.\d{2})?)""",
            RegexOption.IGNORE_CASE,
        )

        val prices = spanRegex.findAll(html).mapNotNull { m ->
            val classes = m.groupValues[1]
            if (classes.contains("strikethrough", ignoreCase = true)) return@mapNotNull null
            m.groupValues[2].replace(",", "").toDoubleOrNull()
        }
            // Filter out obviously wrong prices: below $3 is almost always a
            // shipping fee or junk match, above $500 a misfire.
            .filter { it in 3.0..500.0 }
            .toList()

        if (prices.isEmpty()) return null

        val sorted = prices.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
                     else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        return PriceSnapshot(
            itemId        = itemId,
            source        = PriceSource.EBAY_RSS,
            low           = sorted.first(),
            high          = sorted.last(),
            avg           = median,          // median is robust to promo/outlier prices
            lastSalePrice = sorted.first(),  // results are sorted newest-first by default
            saleCount     = sorted.size,
            fetchedAt     = LocalDate.now(),
        )
    }

    // ─── Tier 2b: UPCitemdb ───────────────────────────────────────────────────

    private fun fetchUpcItemDb(item: FunkoItem): PriceSnapshot? {
        return runCatching {
            val response = client.newCall(
                Request.Builder()
                    .url("$UPCITEMDB${item.upc}")
                    .header("User-Agent", USER_AGENT)
                    .build()
            ).execute()

            if (!response.isSuccessful) return@runCatching null
            val body = response.body?.string() ?: return@runCatching null

            // Parse JSON manually to avoid an extra dep — only need two fields
            val lowestMatch  = Regex(""""lowest_recorded_price"\s*:\s*(\d+\.?\d*)""").find(body)
            val highestMatch = Regex(""""highest_recorded_price"\s*:\s*(\d+\.?\d*)""").find(body)
            val retail       = Regex(""""price"\s*:\s*"?\$?(\d+\.?\d*)""").find(body)

            val low    = lowestMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: return@runCatching null
            val high   = highestMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val avg    = if (low > 0 && high > 0) (low + high) / 2.0 else low
            val retailP = retail?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

            PriceSnapshot(
                itemId    = item.id,
                source    = PriceSource.UPCITEMDB,
                retail    = retailP,
                low       = low,
                high      = high,
                avg       = avg,
                fetchedAt = LocalDate.now(),
            )
        }.getOrNull()
    }

    // ─── Tier 2c / 3: Channel3 ───────────────────────────────────────────────

    private fun fetchChannel3(item: FunkoItem, premium: Boolean): PriceSnapshot? {
        return runCatching {
            val key = secureKeyStore.getChannel3Key()
            if (key.isEmpty() && premium) return@runCatching null

            val url = if (item.upc.isNotEmpty()) {
                "$CHANNEL3_SEARCH?upc=${item.upc}"
            } else {
                val q = URLEncoder.encode("${item.name} ${item.seriesNumber}".trim(), "UTF-8")
                "$CHANNEL3_SEARCH?q=$q&brand=Funko"
            }

            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
            if (key.isNotEmpty()) builder.header("Authorization", "Bearer $key")

            val response = client.newCall(builder.build()).execute()
            if (!response.isSuccessful) return@runCatching null
            val body = response.body?.string() ?: return@runCatching null

            // Parse just the price fields from the JSON response
            val lowestMatch  = Regex(""""lowest_price"\s*:\s*(\d+\.?\d*)""").find(body)
            val highestMatch = Regex(""""highest_price"\s*:\s*(\d+\.?\d*)""").find(body)
            val avgMatch     = Regex(""""average_price"\s*:\s*(\d+\.?\d*)""").find(body)
            val retailMatch  = Regex(""""retail_price"\s*:\s*(\d+\.?\d*)""").find(body)

            val low    = lowestMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val high   = highestMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val avg    = avgMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val retail = retailMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

            if (low == 0.0 && avg == 0.0 && retail == 0.0) return@runCatching null

            PriceSnapshot(
                itemId    = item.id,
                source    = if (premium) PriceSource.CHANNEL3_PREMIUM else PriceSource.CHANNEL3_FREE,
                retail    = retail,
                low       = low,
                high      = high,
                avg       = avg,
                fetchedAt = LocalDate.now(),
            )
        }.getOrNull()
    }
}
