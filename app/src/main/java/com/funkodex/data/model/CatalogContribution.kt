package com.funkodex.data.model

import java.time.LocalDate

/**
 * CatalogContribution — F1
 *
 * Represents a UPC→catalog mapping contributed by the user when they
 * manually match a not-found scan to a Kenny Chan catalog entry.
 *
 * PRIVACY: Contains ONLY global product metadata — no personal data.
 * "isOwned", "pricePaid", "notes", "condition", "dateAcquired" are
 * never included. Only factual product data true for all users.
 *
 * Stored as "contrib::{upc}" in Couchbase.
 * After the user opts in to sharing, GitHubUploadWorker batches all
 * unuploaded contributions and POSTs them to the Cloudflare Worker,
 * which validates, rate-limits, and writes them to the community repo.
 *
 * Upload is anonymous — no user ID, device ID, or account info included
 * in the payload. The Cloudflare Worker's X-Device-ID header is a random
 * install UUID only used for rate-limiting, never stored in the repo.
 *
 * Schema version: 1 (matches community repo SCHEMA.md)
 */
data class CatalogContribution(
    // ── Identity ─────────────────────────────────────────────────────────────
    val upc:               String,
    val handle:            String,     // Kenny Chan dataset handle

    // ── Global product metadata (safe to share) ───────────────────────────────
    val name:              String,
    val franchise:         String,
    val category:          String     = "",
    val seriesNumber:      String     = "",
    val retailPrice:       Double     = 0.0,
    val isVaulted:         Boolean    = false,
    val isChase:           Boolean    = false,
    val isExclusive:       Boolean    = false,
    val exclusiveRetailer: String     = "",
    val imageUrl:          String     = "",

    // ── Provenance ────────────────────────────────────────────────────────────
    val source:            String     = "USER_SCAN",   // USER_SCAN | USER_SCAN_CHANNEL3 | CHANNEL3
    val schemaVersion:     Int        = 1,
    val contributedAt:     LocalDate  = LocalDate.now(),

    // ── Upload tracking (local only, never sent) ──────────────────────────────
    val isUploaded:        Boolean    = false,
) {
    companion object {
        const val DOC_PREFIX     = "contrib::"
        const val SCHEMA_VERSION = 1
    }

    val docId: String get() = "$DOC_PREFIX$upc"

    /** Serialise to the community JSON schema format (global meta only). */
    fun toUploadMap(): Map<String, Any> = buildMap {
        put("upc",               upc)
        put("handle",            handle)
        put("name",              name)
        put("franchise",         franchise)
        put("category",          category)
        put("seriesNumber",      seriesNumber)
        put("retailPrice",       retailPrice)
        put("isVaulted",         isVaulted)
        put("isChase",           isChase)
        put("isExclusive",       isExclusive)
        put("exclusiveRetailer", exclusiveRetailer)
        put("imageUrl",          imageUrl)
        put("source",            source)
        put("schemaVersion",     schemaVersion)
        put("contributedAt",     contributedAt.toString())
        // NOTE: isUploaded and docId are NOT included — local tracking only
    }
}
