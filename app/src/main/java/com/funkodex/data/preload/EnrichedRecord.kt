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
    val series:           List<String>? = null,
    val upc:              String?       = null,

    // ── funko.com enriched fields ──────────────────────────────────────────
    val pid:              String?       = null,    // Funko SFCC product ID → funkoShopId
    val price:            String?       = null,    // "$11.99" string — parsed on use
    val available:        Boolean?      = null,
    val productUrl:       String?       = null,
    val funkoPrimaryImage:String?       = null,    // funko.com CDN image → funkoImageUrl
    val funkoSource:      String?       = null,

    // ── PriceCharting enriched fields ──────────────────────────────────────
    val marketValueLoose: String?       = null,
    val marketValueNew:   String?       = null,
    val pricechartingId:  String?       = null,
    val pricechartingUrl: String?       = null,
)
