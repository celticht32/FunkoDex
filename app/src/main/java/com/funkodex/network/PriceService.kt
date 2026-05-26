package com.funkodex.network

import android.util.Log
import com.funkodex.util.FunkoDexLogger
import android.util.Xml
import com.funkodex.data.model.FunkoItem
import com.funkodex.data.model.PriceSnapshot
import com.funkodex.data.model.PriceSource
import com.funkodex.security.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
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
 *   2a. eBay completed-listings RSS (real sold prices, XmlPullParser, no key)
 *   2b. UPCitemdb  (generic pricing, 100 req/day free)
 *   2c. Channel3 free (structured Funko data, 100 req/day free)
 *
 * Tier 3 — API key required:
 *   3a. Channel3 premium (user's key from SecureKeyStore, higher limits)
 *
 * Tier 4 — Stub (auth not yet built):
 *   4a. HobbyDB — returns null; implement in Phase D when OAuth is wired.
 *
 * SECURITY: Channel3 key read from SecureKeyStore (EncryptedSharedPreferences).
 *           Never from BuildConfig or local.properties.
 */
@Singleton
class PriceService @Inject constructor(
    private val client: OkHttpClient,
    private val secureKeyStore: SecureKeyStore,
) {
    companion object {
        private const val TAG = "PriceService"
        private const val USER_AGENT = "FunkoDex/1.0 Android (price lookup)"

        // eBay completed-listings RSS
        private const val EBAY_RSS_BASE =
            "https://www.ebay.com/sch/i.html?LH_Complete=1&LH_Sold=1&_rss=1&_ipg=20&_nkw="

        // UPCitemdb
        private const val UPCITEMDB =
            "https://api.upcitemdb.com/prod/trial/lookup?upc="

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

        // Tier 2a: eBay RSS — most valuable (real sold prices)
        fetchEbayRss(item)?.let {
            FunkoDexLogger.d(TAG, "Tier 2a (eBay RSS) hit for ${item.name}")
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

        // Tier 4: HobbyDB — stub until OAuth is built in Phase D
        FunkoDexLogger.d(TAG, "All tiers exhausted for ${item.name}")
        null
    }

    // ─── Tier 2a: eBay completed-listings RSS ─────────────────────────────────

    private fun fetchEbayRss(item: FunkoItem): PriceSnapshot? {
        return runCatching {
            // Build search query: "funko pop {name} {number}" — specific enough to avoid noise
            val query = buildEbayQuery(item)
            val url   = EBAY_RSS_BASE + URLEncoder.encode(query, "UTF-8")

            val response = client.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .build()
            ).execute()

            if (!response.isSuccessful) return@runCatching null
            val xml = response.body?.string() ?: return@runCatching null

            parseEbayRss(item.id, xml)
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
     * Parse eBay RSS 2.0 XML using Android's XmlPullParser.
     * Extracts sold prices from <tobin:startprice> or $XX.XX in titles.
     * Returns a PriceSnapshot with low/high/avg computed from all results.
     */
    private fun parseEbayRss(itemId: String, xml: String): PriceSnapshot? {
        val prices = mutableListOf<Double>()
        val priceRegex = Regex("""\$(\d+(?:\.\d{2})?)""")

        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(xml.reader())

            var inItem         = false
            var currentTitle   = ""
            var currentPrice   = 0.0
            var eventType      = parser.eventType

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "item"       -> { inItem = true; currentTitle = ""; currentPrice = 0.0 }
                            "title"      -> if (inItem) currentTitle = parser.nextText()
                            "startprice" -> {
                                // <tobin:startprice> — most reliable price field
                                val text = parser.nextText()
                                currentPrice = text.toDoubleOrNull() ?: 0.0
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "item" && inItem) {
                            val price = when {
                                currentPrice > 0 -> currentPrice
                                else -> priceRegex.find(currentTitle)
                                    ?.groupValues?.getOrNull(1)
                                    ?.toDoubleOrNull() ?: 0.0
                            }
                            // Filter out obviously wrong prices (< $1 or > $500)
                            if (price in 1.0..500.0) prices.add(price)
                            inItem = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            FunkoDexLogger.w(TAG, "eBay RSS parse error: ${e.message}")
        }

        if (prices.isEmpty()) return null

        val sorted = prices.sorted()
        return PriceSnapshot(
            itemId        = itemId,
            source        = PriceSource.EBAY_RSS,
            low           = sorted.first(),
            high          = sorted.last(),
            avg           = sorted.average(),
            lastSalePrice = sorted.last(),   // most recent = last in RSS feed
            saleCount     = prices.size,
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
