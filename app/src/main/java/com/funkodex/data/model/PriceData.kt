package com.funkodex.data.model

import java.time.LocalDate

/**
 * Multi-tier price guide system.
 *
 * Tier 1 — Instant / always available (no network, no auth):
 *   • Retail price from Kenny Chan dataset or Channel3
 *   • User's own paid price
 *
 * Tier 2 — Network, no auth required (free, best-effort):
 *   • UPCitemdb — generic product pricing
 *   • eBay completed listings via RSS (public, no key)
 *   • Channel3 API (free tier, 100 req/day, UPC + retail price)
 *
 * Tier 3 — Network, API key required:
 *   • Channel3 premium (higher limits, richer data)
 *   • StockX public price API (read-only, no auth for basic data)
 *
 * Tier 4 — Authenticated / premium (login required):
 *   • HobbyDB / Pop Price Guide (full market history, high/low bands)
 *   • eBay OAuth (completed sales, volume data)
 *
 * The app tries each tier in order and stops at the first successful result.
 * Each tier's result is cached in Couchbase as a PriceSnapshot document.
 * Stale cache (older than staleDays) triggers a background refresh.
 */

enum class PriceSource(
    val displayName: String,
    val tier: Int,
    val requiresKey: Boolean,
    val requiresLogin: Boolean,
    val staleDays: Int,         // how many days before this source's data is considered stale
) {
    // Tier 0 — user-entered, authoritative, effectively never stale (100y),
    // never overwritten except by a real market feed. NOTE: staleDays feeds
    // LocalDate.plusDays(); Int.MAX_VALUE overflows that and throws, so use a
    // large finite value instead.
    MANUAL(           "Manually set",             0, false, false, 36_500),

    // Tier 1 — always available
    USER_PAID(        "Your paid price",          1, false, false, 36_500),
    RETAIL_CATALOG(   "Funko retail (catalog)",   1, false, false, 30),

    // Tier 2 — free network
    UPCITEMDB(        "UPCitemdb",                2, false, false, 7),
    CHANNEL3_FREE(    "Channel3 (free)",          2, false, false, 3),
    EBAY_RSS(         "eBay sold listings",       2, false, false, 1),
    PRICECHARTING(    "PriceCharting",            2, false, false, 3),

    // Tier 3 — API key
    CHANNEL3_PREMIUM( "Channel3 (premium)",       3, true,  false, 1),
    STOCKX(           "StockX",                   3, true,  false, 1),

    // Tier 4 — authenticated
    HOBBYDB(          "HobbyDB / Pop Price Guide",4, false, true,  1),
    EBAY_OAUTH(       "eBay (authenticated)",     4, false, true,  1),
}

/**
 * A single price snapshot fetched from one source at one point in time.
 * Stored as a "price" document in Couchbase: key = "price::{itemId}::{source}"
 */
data class PriceSnapshot(
    val itemId: String,             // funko doc ID this price belongs to
    val source: PriceSource,
    val retail: Double = 0.0,       // official retail price
    val low: Double = 0.0,          // market low (sold listings)
    val high: Double = 0.0,         // market high
    val avg: Double = 0.0,          // market average / estimated value
    val lastSalePrice: Double = 0.0,// most recent single sale
    val saleCount: Int = 0,         // number of sales this data is based on
    val fetchedAt: LocalDate = LocalDate.now(),
    val currency: String = "USD",
) {
    val isStale: Boolean get() {
        // Guard against overflow: very large staleDays (e.g. "never stale" sources)
        // would overflow LocalDate.plusDays() and throw. Cap the horizon at ~100y.
        val days = source.staleDays.toLong().coerceAtMost(36_500L)
        return fetchedAt.plusDays(days).isBefore(LocalDate.now())
    }

    /** Best single "estimated value" number to show the user */
    val estimatedValue: Double get() = when {
        avg > 0     -> avg
        lastSalePrice > 0 -> lastSalePrice
        retail > 0  -> retail
        else        -> 0.0
    }
}

/**
 * Resolved price for display — the best available data across all cached sources,
 * with provenance so the UI can show which tier it came from.
 */
data class ResolvedPrice(
    val retail: Double,
    val marketLow: Double,
    val marketHigh: Double,
    val marketAvg: Double,
    val estimatedValue: Double,
    val bestSource: PriceSource,
    val sourceTier: Int,
    val fetchedAt: LocalDate?,
    val isStale: Boolean,
    val staleDays: Int,
) {
    companion object {
        val UNKNOWN = ResolvedPrice(0.0, 0.0, 0.0, 0.0, 0.0,
            PriceSource.RETAIL_CATALOG, 0, null, true, 0)
    }
}

/**
 * Price config — which sources the user has enabled and their credentials.
 * Stored in DataStore.
 */
data class PriceConfig(
    val enabledSources: Set<PriceSource> = setOf(
        PriceSource.USER_PAID,
        PriceSource.RETAIL_CATALOG,
        PriceSource.UPCITEMDB,
        PriceSource.CHANNEL3_FREE,
        PriceSource.EBAY_RSS,
    ),
    val channel3ApiKey: String = "",
    val hobbyDbEmail: String = "",
    val hobbyDbToken: String = "",   // OAuth token, not password
    val ebayOAuthToken: String = "", // OAuth token
    val autoRefreshPrices: Boolean = true,
    val priceRefreshIntervalDays: Int = 3,
    val wifiOnlyPrices: Boolean = true,
)
