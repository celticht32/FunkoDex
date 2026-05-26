package com.funkodex.data.db

import android.content.Context
import com.couchbase.lite.*

class FunkoDexDatabase(private val context: Context) {

    companion object {
        private const val DB_NAME = "funkodex"

        // ── Document types ──────────────────────────────────────────────────
        const val TYPE_FUNKO           = "funko"      // user's owned/wanted items
        const val TYPE_CATALOG         = "catalog"    // reference catalog (Kenny Chan / Channel3)
        const val TYPE_CATEGORY_PREF   = "cat_pref"  // user category enable/disable prefs
        const val TYPE_PRICE_CACHE     = "price"      // cached market pricing per item
        const val TYPE_PRICE_ALERT     = "alert"      // price alert per wanted item
        const val TYPE_CONTRIBUTION    = "contrib"    // pending community UPC contribution

        // ── Common ──────────────────────────────────────────────────────────
        const val FIELD_TYPE           = "type"

        // ── FunkoItem fields ────────────────────────────────────────────────
        const val FIELD_UPC            = "upc"
        const val FIELD_CATALOG_REF    = "catalogRef"
        const val FIELD_FUNKO_ID       = "funkoId"
        const val FIELD_NAME           = "name"
        const val FIELD_FRANCHISE      = "franchise"   // IP/character universe
        const val FIELD_CATEGORY       = "category"    // Pop! product line
        const val FIELD_GENRE          = "genre"       // FunkoGenre enum name
        const val FIELD_SERIES_NUM     = "seriesNumber"
        const val FIELD_SERIES_NUM_INT = "seriesNumberInt"
        const val FIELD_IMAGE_URL      = "imageUrl"

        // Pricing
        const val FIELD_PRICE_PAID     = "pricePaid"
        const val FIELD_RETAIL_PRICE   = "retailPrice"
        const val FIELD_MARKET_LOW     = "marketLow"
        const val FIELD_MARKET_HIGH    = "marketHigh"
        const val FIELD_MARKET_AVG     = "marketAvg"
        const val FIELD_PRICE_UPDATED  = "priceLastUpdated"

        // Flags
        const val FIELD_IS_OWNED       = "isOwned"
        const val FIELD_IS_VAULTED     = "isVaulted"
        const val FIELD_IS_CHASE       = "isChase"
        const val FIELD_IS_EXCLUSIVE   = "isExclusive"
        const val FIELD_EXCL_RETAILER  = "exclusiveRetailer"

        // Collection details
        const val FIELD_CONDITION      = "condition"
        const val FIELD_NOTES          = "notes"
        const val FIELD_DATE_ADDED     = "dateAdded"
        const val FIELD_DATE_ACQUIRED  = "dateAcquired"
        const val FIELD_THUMBNAIL_BLOB = "thumbnailBlob"  // Couchbase Blob for offline display

        // ── Category preference fields ──────────────────────────────────────
        const val FIELD_CAT_NAME       = "categoryName"
        const val FIELD_CAT_ENABLED    = "enabled"
        const val FIELD_CAT_GENRE      = "genreName"

        // ── Price cache fields ──────────────────────────────────────────────
        const val FIELD_PRICE_ITEM_REF = "itemRef"       // funko doc ID
        const val FIELD_PRICE_SOURCE   = "priceSource"   // PriceSource enum name
        const val FIELD_PRICE_LOW      = "low"
        const val FIELD_PRICE_HIGH     = "high"
        const val FIELD_PRICE_AVG      = "avg"
        const val FIELD_PRICE_RETAIL   = "retail"
        const val FIELD_PRICE_FETCHED  = "fetchedAt"

        // ── Price alert fields (Phase D) ────────────────────────────────────
        const val FIELD_ALERT_ITEM_ID     = "alertItemId"
        const val FIELD_ALERT_TARGET      = "targetPrice"
        const val FIELD_ALERT_ENABLED     = "alertEnabled"
        const val FIELD_ALERT_TRIGGERED   = "lastTriggeredAt"
        const val FIELD_ALERT_ITEM_NAME   = "itemName"     // denormalised for notification
        const val FIELD_ALERT_UPC       = "alertUpc"     // denormalised for price lookup

        // ── Community contribution fields (Phase F) ─────────────────────────
        const val FIELD_CONTRIB_HANDLE    = "contribHandle"
        const val FIELD_CONTRIB_UPC       = "contribUpc"
        const val FIELD_CONTRIB_NAME      = "contribName"
        const val FIELD_CONTRIB_FRANCHISE = "contribFranchise"
        const val FIELD_CONTRIB_CATEGORY  = "contribCategory"
        const val FIELD_CONTRIB_NUMBER    = "contribSeriesNumber"
        const val FIELD_CONTRIB_RETAIL    = "contribRetailPrice"
        const val FIELD_CONTRIB_VAULTED   = "contribIsVaulted"
        const val FIELD_CONTRIB_CHASE     = "contribIsChase"
        const val FIELD_CONTRIB_EXCLUSIVE = "contribIsExclusive"
        const val FIELD_CONTRIB_RETAILER  = "contribExclusiveRetailer"
        const val FIELD_CONTRIB_IMAGE_URL = "contribImageUrl"
        const val FIELD_CONTRIB_SOURCE    = "contribSource"
        const val FIELD_CONTRIB_DATE      = "contribDate"
        const val FIELD_CONTRIB_UPLOADED  = "contribUploaded"
        const val FIELD_CONTRIB_SCHEMA_V  = "schemaVersion"
    }

    private val database: Database by lazy {
        val config = DatabaseConfigurationFactory.newConfig(context.filesDir.absolutePath)
        Database(DB_NAME, config)
    }

    fun getDatabase(): Database = database

    fun ensureIndexes() {
        val db = database
        // Collection indexes
        db.createIndex("idx_owned",
            IndexBuilder.valueIndex(ValueIndexItem.property(FIELD_IS_OWNED)))
        db.createIndex("idx_upc",
            IndexBuilder.valueIndex(ValueIndexItem.property(FIELD_UPC)))
        db.createIndex("idx_franchise",
            IndexBuilder.valueIndex(ValueIndexItem.property(FIELD_FRANCHISE)))
        db.createIndex("idx_category",
            IndexBuilder.valueIndex(ValueIndexItem.property(FIELD_CATEGORY)))
        db.createIndex("idx_genre",
            IndexBuilder.valueIndex(ValueIndexItem.property(FIELD_GENRE)))
        db.createIndex("idx_date_added",
            IndexBuilder.valueIndex(ValueIndexItem.property(FIELD_DATE_ADDED)))
        // Composite: franchise + owned (series completion queries)
        db.createIndex("idx_franchise_owned",
            IndexBuilder.valueIndex(
                ValueIndexItem.property(FIELD_FRANCHISE),
                ValueIndexItem.property(FIELD_IS_OWNED)))
        // Catalog indexes
        db.createIndex("idx_catalog_name",
            IndexBuilder.valueIndex(ValueIndexItem.property(FIELD_NAME)))
        db.createIndex("idx_catalog_franchise",
            IndexBuilder.valueIndex(ValueIndexItem.property(FIELD_FRANCHISE)))
        // Category prefs
        db.createIndex("idx_cat_pref",
            IndexBuilder.valueIndex(ValueIndexItem.property(FIELD_CAT_NAME)))

        // Type index — speeds up all doc-type filters (alerts, contrib, pending, price cache)
        db.createIndex("idx_type",
            IndexBuilder.valueIndex(ValueIndexItem.property(FIELD_TYPE)))

        // Price alert indexes
        db.createIndex("idx_alert_enabled",
            IndexBuilder.valueIndex(
                ValueIndexItem.property(FIELD_TYPE),
                ValueIndexItem.property(FIELD_ALERT_ENABLED)))
        db.createIndex("idx_alert_item",
            IndexBuilder.valueIndex(ValueIndexItem.property(FIELD_ALERT_ITEM_ID)))

        // Contribution indexes
        db.createIndex("idx_contrib_uploaded",
            IndexBuilder.valueIndex(
                ValueIndexItem.property(FIELD_TYPE),
                ValueIndexItem.property(FIELD_CONTRIB_UPLOADED)))
    }

    fun close() {
        if (database.isOpen) database.close()
    }
}
