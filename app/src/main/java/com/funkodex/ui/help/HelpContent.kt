package com.funkodex.ui.help

/**
 * HelpContent — central repository of all in-app help strings.
 *
 * Keeping text here makes it easy to update copy, translate to other
 * languages, or pass to a copywriter without touching screen logic.
 * Help is contextual — shown at the right moment in the right screen.
 */
object HelpContent {

    // ── Collection screen ─────────────────────────────────────────────────────
    const val COLLECTION_EMPTY_TITLE  = "Your collection is empty"
    const val COLLECTION_EMPTY_BODY   =
        "Tap the Scan tab to scan the barcode on any Funko Pop box, " +
        "or use the search icon to add one by name."
    const val COLLECTION_WANT_EMPTY   =
        "Nothing on your want list yet. When viewing any Funko, " +
        "tap \"Add to want list\" to track it here."

    // ── Scanner screen ────────────────────────────────────────────────────────
    const val SCANNER_IDLE            =
        "Point the camera at the barcode on the back of the box. " +
        "The barcode is the set of vertical black lines, usually near the bottom."
    const val SCANNER_NOT_FOUND_TITLE = "Barcode not in catalog"
    const val SCANNER_NOT_FOUND_BODY  =
        "This UPC wasn't found in Channel3 or UPCitemdb. " +
        "Search by name below to find the matching Funko and link it — " +
        "future scans of this barcode will be instant."
    const val SCANNER_ALREADY_OWNED   =
        "Already in your collection. You can update the condition or notes, " +
        "or record a second copy if you have one."
    const val SCANNER_PENDING         =
        "No network — this scan has been saved and will be looked up " +
        "automatically when you're back online."
    const val BATCH_SCAN_HINT         =
        "Keep pointing at barcodes. Each one is added to the list automatically. " +
        "Tap \"Save all\" when done, or remove individual items first."

    // ── Pre-scan (store check) ────────────────────────────────────────────────
    const val PRESCAN_HINT            =
        "Scan the barcode before buying to check whether this Funko is " +
        "already in your collection or on your want list."

    // ── Detail screen ─────────────────────────────────────────────────────────
    const val DETAIL_PRICE_STALE      =
        "These prices are from a previous refresh. Tap the refresh icon for " +
        "the latest eBay sold listings."
    const val DETAIL_PRICE_NONE       =
        "No price data yet. Tap the refresh icon to fetch current market prices. " +
        "Requires an internet connection."
    const val DETAIL_ALERT_HINT       =
        "Set a target price and FunkoDex will notify you when the market low " +
        "drops to or below that amount. Checks run once daily."
    const val DETAIL_PHOTO_HINT       =
        "Add a photo of your actual box. Useful for tracking condition or " +
        "spotting variants. Stored locally — never uploaded."

    // ── Reports screen ────────────────────────────────────────────────────────
    const val REPORTS_EMPTY           =
        "Nothing to report yet. Add Funkos via the Scan tab and your " +
        "stats will appear here."
    const val REPORTS_MARKET_NOTE     =
        "Estimated value uses the latest cached market average per item. " +
        "Tap any item to refresh its price."

    // ── Settings ──────────────────────────────────────────────────────────────
    const val SETTINGS_CHANNEL3       =
        "Free Funko data API. Sign up at trychannel3.com for an API key. " +
        "Without a key, FunkoDex falls back to UPCitemdb (100 lookups/day)."
    const val SETTINGS_CONTRIBUTE     =
        "Anonymously share UPC→product mappings you discover while scanning. " +
        "No personal data is ever sent — only product facts like name, " +
        "barcode, and category. Contributed data is shared with all users."
    const val SETTINGS_DRIVE          =
        "Backs up your database to a 'FunkoDex Backups' folder in your " +
        "Google Drive. Runs automatically on WiFi once per day. " +
        "Keeps the last 7 backups."
    const val SETTINGS_REFRESH        =
        "Periodically downloads the latest Funko catalog from GitHub and " +
        "the community UPC database. WiFi-only avoids mobile data charges."
    const val SETTINGS_DB_TRANSFER    =
        "Move your collection to a new phone or back up before a factory reset. " +
        "Includes all items, notes, price paid, and condition records."

    // ── Export ────────────────────────────────────────────────────────────────
    const val EXPORT_HINT             =
        "Exports to an Excel workbook (4 sheets: Collection, Want List, " +
        "Series Completion, Price History) or plain CSV for Google Sheets."

    // ── Category filter ───────────────────────────────────────────────────────
    const val CATEGORY_FILTER         =
        "Disabled categories are hidden from catalog search and want-list " +
        "suggestions. Items you already own are always shown regardless."
}
