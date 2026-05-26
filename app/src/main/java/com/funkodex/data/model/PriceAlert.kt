package com.funkodex.data.model

import java.time.LocalDate

/**
 * PriceAlert — D2
 *
 * Represents a price drop alert for a wanted Funko item.
 * Stored as an "alert::{itemId}" document in Couchbase Lite.
 *
 * The worker (PriceAlertWorker) checks all active alerts daily,
 * fetches the current market price via PriceService, and fires a
 * notification when marketLow ≤ targetPrice.
 *
 * Dedup rule: once a notification is sent, lastTriggeredAt is set.
 * The worker won't re-notify within 24 hours for the same item.
 *
 * Only valid for wanted items (isOwned = false).
 * When the user marks an item as owned the alert is automatically disabled.
 *
 * Document key: "alert::{itemId}"
 */
data class PriceAlert(
    val itemId:          String,
    val itemName:        String,    // denormalised — needed for notification without a DB join
    val upc:             String     = "",  // denormalised — enables UPC-based price lookup in worker
    val targetPrice:     Double,    // notify when marketLow drops at or below this
    val isEnabled:       Boolean    = true,
    val lastTriggeredAt: LocalDate? = null,
) {
    companion object {
        const val DOC_PREFIX = "alert::"
    }

    val docId: String get() = "$DOC_PREFIX$itemId"

    /** True if we have already notified within the last 24 hours. */
    val notifiedToday: Boolean
        get() = lastTriggeredAt?.isEqual(LocalDate.now()) == true
}
