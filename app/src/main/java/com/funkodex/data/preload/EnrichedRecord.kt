package com.funkodex.data.preload

/**
 * EnrichedRecord
 *
 * Deserialisation target for a record in funko_data_enriched.json — a superset
 * of the Kenny Chan format with additional fields from funko.com and PriceCharting.
 *
 * All fields are nullable with defaults so partial records (e.g. Kenny Chan–only
 * records missing funko.com data) deserialise cleanly without Gson errors.
 *
 * MIT License — Copyright (c) 2026 Chris Ahrendt
 */
data class EnrichedRecord(
    // ── Kenny Chan base fields ─────────────────────────────────────────────
    val handle:           String?       = null,
    val title:            String?       = null,
    val imageName:        String?       = null,
    val series:           List<String>  = emptyList(),
    val upc:              String?       = null,

    // ── funko.com enriched fields ──────────────────────────────────────────
    val pid:              String?       = null,    // Funko SFCC product ID → funkoShopId
    val price:            String?       = null,    // "$11.99" string — parsed on use
    val available:        Boolean?      = null,
    val productUrl:       String?       = null,
    val funkoPrimaryImage:String?       = null,    // funko.com CDN image → funkoImageUrl
    val funkoSource:      String?       = null,

    // ── HobbyDB enriched fields (enricher Pass 4) ──────────────────────────
    val funkoNumber:      String?       = null,    // Funko item number, e.g. "157" — display only, may be shared across variants
    val popType:          String?       = null,    // e.g. "Pop!", "Pop! Deluxe", "Pop! Rides"

    // ── PriceCharting enriched fields ──────────────────────────────────────
    val marketValueLoose:    String?    = null,    // out-of-box / loose price
    val marketValueComplete: String?    = null,    // in-box price — PRIMARY market value
    val marketValueNew:      String?    = null,    // mint / sealed price
    val marketValueIsApproximate: Boolean = false, // base price used for an unlisted variant
    val pricechartingId:     String?    = null,
    val pricechartingUrl:    String?    = null,
    // PriceCharting metadata (enricher Pass 3 harvest)
    val releaseDate:         String?    = null,    // ISO yyyy-MM-dd
    val ebayEpid:            String?    = null,    // eBay product id
    val amazonAsin:          String?    = null,
    val printRun:            String?    = null,
    val publisher:           String?    = null,
    val pcSeries:            String?    = null,    // PriceCharting's series label
    val pcDescription:       String?    = null,

    // ── Collection-grouping fields (enricher POST-PROCESS 5) ───────────────
    val setTag:              String?    = null,    // most-specific named set, or null
    val franchiseSuggestion: String?    = null,    // property-specific console franchise, or null
)
