package com.funkodex.data.model

import java.time.LocalDate

/**
 * PendingUpcScan — A5
 *
 * Represents a UPC that was scanned when no network was available.
 * Stored as a "pending_upc::{upc}" document in Couchbase Lite.
 *
 * When network connectivity is restored, ConnectivityObserver processes
 * all pending scans via Channel3 and notifies the user of the results.
 *
 * Document key: "pending_upc::{upc}"
 *
 * Flow:
 *   1. User scans UPC — no network — ScanState.Pending
 *   2. PendingUpcScan saved to Couchbase
 *   3. Network restored → ConnectivityObserver fires
 *   4. FunkoDexApp processes queue via FunkoLookupService
 *   5. Matched items saved as funko:: docs
 *   6. Notification: "3 scans identified — tap to review"
 */
data class PendingUpcScan(
    val upc:        String,
    val scannedAt:  LocalDate = LocalDate.now(),
    val retryCount: Int       = 0,
) {
    companion object {
        const val DOC_PREFIX  = "pending_upc::"
        const val FIELD_UPC   = "upc"
        const val FIELD_DATE  = "scannedAt"
        const val FIELD_RETRY = "retryCount"
        const val FIELD_TYPE  = "type"
        const val TYPE_VAL    = "pending_upc"
    }

    val docId: String get() = "$DOC_PREFIX$upc"
}
