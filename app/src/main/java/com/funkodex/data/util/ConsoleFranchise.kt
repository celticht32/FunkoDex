package com.funkodex.data.util

/**
 * ConsoleFranchise
 *
 * Maps a PriceCharting console slug (embedded in a catalog record's
 * pricechartingUrl, e.g. ".../game/funko-pop-harry-potter/...") to a
 * property-level franchise SUGGESTION used to pre-fill the first-scan prompt.
 *
 * Only property-specific consoles yield a suggestion. Umbrella / genre consoles
 * (disney, animation, movies, marvel, television, …) carry no property signal —
 * e.g. every Hocus Pocus figure sits under funko-pop-disney — so they return
 * null and the user assigns the franchise by hand. The suggestion is a hint
 * only; the user always confirms or overrides it, and the user-assigned
 * franchise is authoritative thereafter.
 *
 * The enricher emits the same value as `franchiseSuggestion` on each record;
 * this object is the on-device fallback for records that carry only the URL, and
 * the single source of truth for the umbrella blocklist when the app must judge
 * a console itself.
 *
 * MIT License — Copyright (c) 2026 Chris Ahrendt
 */
object ConsoleFranchise {

    /** Console slugs (the part after "funko-pop-") that are umbrella / genre
     *  lines, not properties. Seeded from a live run's console distribution. */
    private val UMBRELLA = setOf(
        "animation", "star-wars", "disney", "marvel", "television", "comics",
        "movies", "games", "heroes", "icons", "rocks", "ad-icons", "retro-toys",
        "asia", "art-series", "digital", "vinyl-soda", "soda", "rides",
    )

    /** Known acronyms/brands rendered upper-case instead of title-case. */
    private val ACRONYMS = mapOf(
        "wwe" to "WWE", "mlb" to "MLB", "nfl" to "NFL", "nba" to "NBA",
        "nhl" to "NHL", "mls" to "MLS", "bape" to "BAPE", "vhs" to "VHS",
        "se" to "SE", "dc" to "DC", "tv" to "TV", "ufc" to "UFC",
    )

    private val URL_CONSOLE = Regex("""/game/funko-pop-([a-z0-9-]+)/""")

    /** pcSeries values that are pure noise (retailer/format), not a property. */
    private val PCSERIES_HARD_NOISE = setOf(
        "walmart", "wal-mart", "only at walmart", "funko shop", "funko shop.",
        "funko pop figure", "vinyl figure", "collector's edition", "impressions",
        "icons", "slam", "target", "gamestop", "hot topic", "boxlunch", "box lunch",
        "fye", "f.y.e.", "amazon", "disney store", "px previews exclusive",
        "summer convention", "summer funko convention", "funko spring convention", "tpm25",
    )
    private val PCSERIES_EVENT_KW = listOf(
        "exclusive", "convention", "sdcc", "nycc", "eccc", "d23", "comic con",
        "celebration", "loot crate", "blizzard", "walgreens", "px previews", "ccxp",
        "lacc", "galactic convention", "limited edition", "blacklight", "funko fundays",
        "funko shop", "first to market",
    )

    /**
     * Clean the PriceCharting product-page "Series" value into a property, or null.
     * PriceCharting appends retailer/event qualifiers after a "." or "," (e.g.
     * "Dragon Ball Z. FYE", "My Hero Academia, Target"); take the leading segment
     * and drop it when that segment is itself pure noise. This is the strongest
     * property signal — "Hocus Pocus", "Garfield" — present where a figure was
     * PriceCharting-matched.
     */
    fun fromPcSeries(pcSeries: String?): String? {
        if (pcSeries.isNullOrBlank()) return null
        val seg = pcSeries.split('.', ',').firstOrNull()?.trim() ?: return null
        if (seg.isEmpty()) return null
        val low = seg.lowercase()
        if (low in PCSERIES_HARD_NOISE) return null
        if (seg.split(Regex("\\s+")).size <= 4 && PCSERIES_EVENT_KW.any { low.contains(it) }) return null
        return seg
    }

    /**
     * Resolve a franchise from catalog fields: prefer the cleaned pcSeries
     * property, then the property-specific console from the URL. Null when neither
     * yields a property (the user assigns it by hand).
     */
    fun resolve(pcSeries: String?, pricechartingUrl: String?): String? =
        fromPcSeries(pcSeries) ?: fromPricechartingUrl(pricechartingUrl)

    /**
     * Suggest a franchise from a full pricechartingUrl, or null when the URL is
     * blank, unparseable, or points to an umbrella console.
     */
    fun fromPricechartingUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val slug = URL_CONSOLE.find(url)?.groupValues?.get(1) ?: return null
        return fromConsoleSlug(slug)
    }

    /**
     * Suggest a franchise from a bare console slug (e.g. "harry-potter"), or
     * null for umbrella slugs.
     */
    fun fromConsoleSlug(slug: String?): String? {
        if (slug.isNullOrBlank()) return null
        val s = slug.removePrefix("funko-pop-").lowercase()
        if (s in UMBRELLA) return null
        return label(s)
    }

    private fun label(slug: String): String =
        slug.split("-").joinToString(" ") { w ->
            ACRONYMS[w] ?: w.replaceFirstChar { c -> c.uppercase() }
        }
}
