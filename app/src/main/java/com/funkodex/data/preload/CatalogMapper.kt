package com.funkodex.data.preload

/**
 * CatalogMapper
 *
 * Shared utility for mapping a raw Kenny Chan JSON record into a
 * Couchbase catalog document map.  Extracted from CatalogPreloader so
 * that CatalogRefreshWorker can call the same logic without coupling to
 * the preloader class or duplicating code.
 *
 * This is the fix for the A1 stub in CatalogRefreshWorker — the worker
 * was incrementing a counter but never actually writing records because
 * mapRecord() was private inside CatalogPreloader.
 */
object CatalogMapper {

    // ── Field name constants (mirrors CatalogPreloader.companion) ─────────────
    const val TYPE_CATALOG          = "catalog"
    const val FIELD_TYPE            = "type"
    const val FIELD_HANDLE          = "handle"
    const val FIELD_TITLE           = "title"
    const val FIELD_IMAGE_URL       = "imageUrl"
    const val FIELD_SERIES_LIST     = "seriesList"
    const val FIELD_PRIMARY_SERIES  = "series"
    const val FIELD_CATEGORY        = "category"
    const val FIELD_IS_EXCLUSIVE    = "isExclusive"
    const val FIELD_EXCL_RETAILER   = "exclusiveRetailer"
    const val FIELD_IS_CHASE        = "isChase"
    const val FIELD_NUMBER          = "seriesNumber"
    // Phase A2 — global meta additions
    const val FIELD_UPC             = "upc"
    const val FIELD_SOURCE          = "source"
    const val FIELD_LAST_UPDATED    = "lastUpdated"
    const val FIELD_CONTRIBUTED_BY  = "contributedBy"
    const val FIELD_RETAIL_PRICE    = "retailPrice"
    const val FIELD_IS_VAULTED      = "isVaulted"

    private val EXCLUSIVE_KEYWORDS = listOf(
        "exclusive", "funko-shop", "sdcc", "nycc", "eccc", "c2e2",
        "target", "gamestop", "walmart", "amazon", "hot topic",
        "box lunch", "boxlunch", "entertainment earth", "walgreens",
        "fye", "best buy", "barnes", "bam", "primark", "fanatics"
    )

    private val RETAILER_MAP = mapOf(
        "target"              to "Target",
        "gamestop"            to "GameStop",
        "walmart"             to "Walmart",
        "amazon"              to "Amazon",
        "hot topic"           to "Hot Topic",
        "box lunch"           to "BoxLunch",
        "boxlunch"            to "BoxLunch",
        "entertainment earth" to "Entertainment Earth",
        "walgreens"           to "Walgreens",
        "fye"                 to "FYE",
        "best buy"            to "Best Buy",
        "barnes"              to "Barnes & Noble",
        "funko-shop"          to "Funko Shop",
        "sdcc"                to "SDCC",
        "nycc"                to "NYCC",
        "eccc"                to "ECCC",
        "c2e2"                to "C2E2",
        "bam"                 to "Books-A-Million",
        "primark"             to "Primark",
        "fanatics"            to "Fanatics",
    )

    private val NUMBER_REGEX = Regex("""#\d+""")

    /**
     * Map a raw Kenny Chan record to a Couchbase document property map.
     * Returns null if essential fields (handle, title) are missing.
     *
     * @param handle   The Kenny Chan slug (used as the Couchbase doc ID suffix)
     * @param title    Product title
     * @param imageName HobbyDB CDN image URL
     * @param seriesList Raw series/tag array from Kenny Chan
     * @param upc      UPC code if known (null for initial Kenny Chan load)
     * @param price    Retail price if known
     * @param vaulted  Whether this item is vaulted
     * @param source   Data source label (e.g. "KENNY_CHAN", "CHANNEL3")
     */
    fun mapRecord(
        handle:      String,
        title:       String,
        imageName:   String   = "",
        seriesList:  List<String> = emptyList(),
        upc:         String?  = null,
        price:       Double   = 0.0,
        vaulted:     Boolean  = false,
        source:      String   = "KENNY_CHAN",
    ): Map<String, Any> {

        // Primary series = first non-Pop!/non-exclusive/non-chase tag
        val primarySeries = seriesList.firstOrNull { s ->
            !s.startsWith("Pop!", ignoreCase = true) &&
            !s.equals("Pop! Vinyl", ignoreCase = true) &&
            !isExclusiveSeries(s) &&
            !s.equals("Chase Pieces", ignoreCase = true)
        } ?: seriesList.firstOrNull() ?: ""

        // Category = the Pop! product line tag
        val category = seriesList
            .firstOrNull { it.startsWith("Pop!", ignoreCase = true) } ?: ""

        // Exclusive detection
        val isExclusive       = seriesList.any { isExclusiveSeries(it) }
        val exclusiveRetailer = if (isExclusive) extractRetailer(seriesList) else ""

        // Chase detection
        val isChase = seriesList.any {
            it.equals("Chase Pieces", ignoreCase = true)
        }

        // Series number from title (e.g. "Batman #01" → "#01")
        val seriesNumber = NUMBER_REGEX.find(title)?.value ?: ""

        return buildMap {
            put(FIELD_TYPE,           TYPE_CATALOG)
            put(FIELD_HANDLE,         handle)
            put(FIELD_TITLE,          title)
            put(FIELD_IMAGE_URL,      imageName)
            put(FIELD_SERIES_LIST,    seriesList)
            put(FIELD_PRIMARY_SERIES, primarySeries)
            put(FIELD_CATEGORY,       category)
            put(FIELD_IS_EXCLUSIVE,   isExclusive)
            put(FIELD_EXCL_RETAILER,  exclusiveRetailer)
            put(FIELD_IS_CHASE,       isChase)
            put(FIELD_NUMBER,         seriesNumber)
            put(FIELD_RETAIL_PRICE,   price)
            put(FIELD_IS_VAULTED,     vaulted)
            put(FIELD_SOURCE,         source)
            put(FIELD_LAST_UPDATED,   java.time.LocalDate.now().toString())
            if (upc != null) put(FIELD_UPC, upc)
        }
    }

    fun isExclusiveSeries(s: String): Boolean =
        EXCLUSIVE_KEYWORDS.any { s.lowercase().contains(it) }

    fun extractRetailer(seriesList: List<String>): String {
        for (tag in seriesList) {
            val lower = tag.lowercase()
            for ((key, name) in RETAILER_MAP) {
                if (lower.contains(key)) return name
            }
        }
        return "Exclusive"
    }
}
