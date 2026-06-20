package com.funkodex.data.preload

/**
 * CatalogMapper
 *
 * Shared utility for mapping a raw Kenny Chan JSON record into a
 * Couchbase catalog document map.  Extracted from CatalogPreloader so
 * that CatalogRefreshWorker can call the same logic without coupling to
 * the preloader class or duplicating code.
 *
 * MIT License — Copyright (c) 2026 Chris Ahrendt
 */
object CatalogMapper {

    // ── Field name constants ───────────────────────────────────────────────
    const val TYPE_CATALOG          = "catalog"
    const val FIELD_TYPE            = "type"
    const val FIELD_HANDLE          = "handle"
    const val FIELD_TITLE           = "title"
    const val FIELD_IMAGE_URL       = "imageUrl"        // HobbyDB CDN — never overwrite
    const val FIELD_SERIES_LIST     = "seriesList"
    const val FIELD_PRIMARY_SERIES  = "series"
    const val FIELD_CATEGORY        = "category"
    const val FIELD_IS_EXCLUSIVE    = "isExclusive"
    const val FIELD_EXCL_RETAILER   = "exclusiveRetailer"
    const val FIELD_IS_CHASE        = "isChase"
    const val FIELD_NUMBER          = "seriesNumber"
    const val FIELD_UPC             = "upc"
    const val FIELD_SOURCE          = "source"
    const val FIELD_LAST_UPDATED    = "lastUpdated"
    const val FIELD_CONTRIBUTED_BY  = "contributedBy"
    const val FIELD_RETAIL_PRICE    = "retailPrice"
    const val FIELD_IS_VAULTED      = "isVaulted"

    // ── Enriched catalog fields (funko.com + PriceCharting) ────────────────
    const val FIELD_IS_AVAILABLE    = "isAvailable"     // funko.com availability flag
    const val FIELD_PRODUCT_URL     = "productUrl"      // funko.com product page URL
    const val FIELD_FUNKO_IMAGE     = "funkoImageUrl"   // funko.com CDN image (separate from imageUrl)
    const val FIELD_FUNKO_SHOP_ID   = "funkoShopId"     // Funko's internal SFCC product ID (pid)
    const val FIELD_FUNKO_NUMBER    = "funkoNumber"      // HobbyDB Funko item number — display only, may be shared
    const val FIELD_POP_TYPE        = "popType"          // e.g. "Pop!", "Pop! Deluxe"
    const val FIELD_MKT_VALUE_LOOSE    = "marketValueLoose"    // PriceCharting OOB / loose price
    const val FIELD_MKT_VALUE_COMPLETE = "marketValueComplete" // PriceCharting in-box price — PRIMARY
    const val FIELD_MKT_VALUE_NEW      = "marketValueNew"      // PriceCharting sealed / new price
    const val FIELD_PC_ID              = "pricechartingId"     // PriceCharting product ID
    const val FIELD_PC_URL             = "pricechartingUrl"    // PriceCharting page URL
    const val FIELD_RELEASE_DATE       = "releaseDate"         // ISO yyyy-MM-dd
    const val FIELD_EBAY_EPID          = "ebayEpid"            // eBay product id
    const val FIELD_AMAZON_ASIN        = "amazonAsin"
    const val FIELD_PRINT_RUN          = "printRun"
    const val FIELD_PUBLISHER          = "publisher"
    const val FIELD_PC_SERIES          = "pcSeries"            // PriceCharting series label
    const val FIELD_PC_DESCRIPTION     = "pcDescription"

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
     * Map a raw Kenny Chan or enriched record to a Couchbase document property map.
     * Returns null if essential fields (handle, title) are missing.
     *
     * Enriched fields are all optional (null = not present in source, omitted from doc).
     * imageUrl (HobbyDB) is NEVER overwritten by funkoImageUrl — they are separate fields.
     */
    fun mapRecord(
        handle:           String,
        title:            String,
        imageName:        String        = "",
        seriesList:       List<String>  = emptyList(),
        upc:              String?       = null,
        price:            Double        = 0.0,
        vaulted:          Boolean       = false,
        source:           String        = "KENNY_CHAN",
        // Enriched fields — funko.com
        available:        Boolean?      = null,
        productUrl:       String?       = null,
        funkoImageUrl:    String?       = null,
        funkoShopId:      String?       = null,
        // Enriched fields — HobbyDB
        funkoNumber:      String?       = null,
        popType:          String?       = null,
        // Enriched fields — PriceCharting
        marketValueLoose:    String?    = null,
        marketValueComplete: String?    = null,
        marketValueNew:      String?    = null,
        pricechartingId:     String?    = null,
        pricechartingUrl:    String?    = null,
        releaseDate:         String?    = null,
        ebayEpid:            String?    = null,
        amazonAsin:          String?    = null,
        printRun:            String?    = null,
        publisher:           String?    = null,
        pcSeries:            String?    = null,
        pcDescription:       String?    = null,
    ): Map<String, Any> {

        val primarySeries = seriesList.firstOrNull { s ->
            !s.startsWith("Pop!", ignoreCase = true) &&
            !s.equals("Pop! Vinyl", ignoreCase = true) &&
            !isExclusiveSeries(s) &&
            !s.equals("Chase Pieces", ignoreCase = true)
        } ?: seriesList.firstOrNull() ?: ""

        // Category = the first series tag that is a real Pop! category, NOT a
        // generic format descriptor. "Pop! Vinyl" (and bare "Pop!") describe the
        // product format, not a collecting category, so they must not be stored
        // as the category — doing so produces an un-matchable value that the
        // category filter silently drops (e.g. funko.com-enriched records whose
        // series is ["Pop! Vinyl", "Music"]). Falls back to "" (uncategorized)
        // when no real category tag is present.
        val category = seriesList
            .firstOrNull { s ->
                s.startsWith("Pop!", ignoreCase = true) &&
                !s.equals("Pop! Vinyl", ignoreCase = true) &&
                !s.equals("Pop!", ignoreCase = true)
            } ?: ""

        val isExclusive       = seriesList.any { isExclusiveSeries(it) }
        val exclusiveRetailer = if (isExclusive) extractRetailer(seriesList) else ""
        val isChase           = seriesList.any { it.equals("Chase Pieces", ignoreCase = true) }
        val seriesNumber      = NUMBER_REGEX.find(title)?.value ?: ""

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
            if (upc != null)                          put(FIELD_UPC,             upc)
            // Enriched — funko.com
            if (available != null)                    put(FIELD_IS_AVAILABLE,    available)
            if (!productUrl.isNullOrBlank())          put(FIELD_PRODUCT_URL,     productUrl)
            if (!funkoImageUrl.isNullOrBlank())       put(FIELD_FUNKO_IMAGE,     funkoImageUrl)
            if (!funkoShopId.isNullOrBlank())         put(FIELD_FUNKO_SHOP_ID,   funkoShopId)
            // Enriched — HobbyDB
            if (!funkoNumber.isNullOrBlank())         put(FIELD_FUNKO_NUMBER,    funkoNumber)
            if (!popType.isNullOrBlank())             put(FIELD_POP_TYPE,        popType)
            // Enriched — PriceCharting
            if (!marketValueLoose.isNullOrBlank())    put(FIELD_MKT_VALUE_LOOSE,    marketValueLoose)
            if (!marketValueComplete.isNullOrBlank()) put(FIELD_MKT_VALUE_COMPLETE, marketValueComplete)
            if (!marketValueNew.isNullOrBlank())      put(FIELD_MKT_VALUE_NEW,      marketValueNew)
            if (!pricechartingId.isNullOrBlank())     put(FIELD_PC_ID,              pricechartingId)
            if (!pricechartingUrl.isNullOrBlank())    put(FIELD_PC_URL,             pricechartingUrl)
            if (!releaseDate.isNullOrBlank())         put(FIELD_RELEASE_DATE,       releaseDate)
            if (!ebayEpid.isNullOrBlank())            put(FIELD_EBAY_EPID,          ebayEpid)
            if (!amazonAsin.isNullOrBlank())          put(FIELD_AMAZON_ASIN,        amazonAsin)
            if (!printRun.isNullOrBlank())            put(FIELD_PRINT_RUN,          printRun)
            if (!publisher.isNullOrBlank())           put(FIELD_PUBLISHER,          publisher)
            if (!pcSeries.isNullOrBlank())            put(FIELD_PC_SERIES,          pcSeries)
            if (!pcDescription.isNullOrBlank())       put(FIELD_PC_DESCRIPTION,     pcDescription)
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
