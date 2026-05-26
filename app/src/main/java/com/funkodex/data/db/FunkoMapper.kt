package com.funkodex.data.db

import com.couchbase.lite.Blob
import com.couchbase.lite.Document
import com.couchbase.lite.MutableDocument
import com.funkodex.data.model.Condition
import com.funkodex.data.model.FunkoGenre
import com.funkodex.data.model.FunkoItem
import java.time.LocalDate

object FunkoMapper {

    fun toDocument(item: FunkoItem): MutableDocument {
        val doc = MutableDocument(item.id)
        doc.setString(FunkoDexDatabase.FIELD_TYPE,           FunkoDexDatabase.TYPE_FUNKO)
        doc.setString(FunkoDexDatabase.FIELD_UPC,            item.upc)
        doc.setString(FunkoDexDatabase.FIELD_CATALOG_REF,    item.catalogRef)
        doc.setString(FunkoDexDatabase.FIELD_FUNKO_ID,       item.funkoId)
        doc.setString(FunkoDexDatabase.FIELD_NAME,           item.name)
        doc.setString(FunkoDexDatabase.FIELD_FRANCHISE,      item.franchise)
        doc.setString(FunkoDexDatabase.FIELD_CATEGORY,       item.category)
        doc.setString(FunkoDexDatabase.FIELD_GENRE,          item.genre.name)
        doc.setString(FunkoDexDatabase.FIELD_SERIES_NUM,     item.seriesNumber)
        doc.setInt(FunkoDexDatabase.FIELD_SERIES_NUM_INT,    item.seriesNumberInt)
        doc.setString(FunkoDexDatabase.FIELD_IMAGE_URL,      item.imageUrl)
        // Thumbnail blob — stored as Couchbase Blob for offline display
        item.thumbnailBlob?.let {
            doc.setBlob(FunkoDexDatabase.FIELD_THUMBNAIL_BLOB, Blob("image/jpeg", it))
        }
        // Pricing
        doc.setDouble(FunkoDexDatabase.FIELD_PRICE_PAID,     item.pricePaid)
        doc.setDouble(FunkoDexDatabase.FIELD_RETAIL_PRICE,   item.retailPrice)
        doc.setDouble(FunkoDexDatabase.FIELD_MARKET_LOW,     item.marketLow)
        doc.setDouble(FunkoDexDatabase.FIELD_MARKET_HIGH,    item.marketHigh)
        doc.setDouble(FunkoDexDatabase.FIELD_MARKET_AVG,     item.marketAvg)
        item.priceLastUpdated?.let { doc.setString(FunkoDexDatabase.FIELD_PRICE_UPDATED, it.toString()) }
        // Flags
        doc.setBoolean(FunkoDexDatabase.FIELD_IS_OWNED,      item.isOwned)
        doc.setBoolean(FunkoDexDatabase.FIELD_IS_VAULTED,    item.isVaulted)
        doc.setBoolean(FunkoDexDatabase.FIELD_IS_CHASE,      item.isChase)
        doc.setBoolean(FunkoDexDatabase.FIELD_IS_EXCLUSIVE,  item.isExclusive)
        doc.setString(FunkoDexDatabase.FIELD_EXCL_RETAILER,  item.exclusiveRetailer)
        // Collection details
        doc.setString(FunkoDexDatabase.FIELD_CONDITION,      item.condition.name)
        doc.setString(FunkoDexDatabase.FIELD_NOTES,          item.notes)
        doc.setString(FunkoDexDatabase.FIELD_DATE_ADDED,     item.dateAdded.toString())
        item.dateAcquired?.let { doc.setString(FunkoDexDatabase.FIELD_DATE_ACQUIRED, it.toString()) }
        return doc
    }

    fun fromDocument(doc: Document): FunkoItem = FunkoItem(
        id               = doc.id,
        upc              = doc.getString(FunkoDexDatabase.FIELD_UPC)            ?: "",
        catalogRef       = doc.getString(FunkoDexDatabase.FIELD_CATALOG_REF)    ?: "",
        funkoId          = doc.getString(FunkoDexDatabase.FIELD_FUNKO_ID)       ?: "",
        name             = doc.getString(FunkoDexDatabase.FIELD_NAME)           ?: "",
        franchise        = doc.getString(FunkoDexDatabase.FIELD_FRANCHISE)      ?: "",
        category         = doc.getString(FunkoDexDatabase.FIELD_CATEGORY)       ?: "",
        genre            = runCatching {
            FunkoGenre.valueOf(doc.getString(FunkoDexDatabase.FIELD_GENRE) ?: "OTHER")
        }.getOrDefault(FunkoGenre.OTHER),
        seriesNumber     = doc.getString(FunkoDexDatabase.FIELD_SERIES_NUM)     ?: "",
        seriesNumberInt  = doc.getInt(FunkoDexDatabase.FIELD_SERIES_NUM_INT).let {
            if (it == 0 && doc.getString(FunkoDexDatabase.FIELD_SERIES_NUM).isNullOrEmpty()) -1 else it
        },
        imageUrl         = doc.getString(FunkoDexDatabase.FIELD_IMAGE_URL)      ?: "",
        thumbnailBlob    = doc.getBlob(FunkoDexDatabase.FIELD_THUMBNAIL_BLOB)?.content,
        // Pricing
        pricePaid        = doc.getDouble(FunkoDexDatabase.FIELD_PRICE_PAID),
        retailPrice      = doc.getDouble(FunkoDexDatabase.FIELD_RETAIL_PRICE),
        marketLow        = doc.getDouble(FunkoDexDatabase.FIELD_MARKET_LOW),
        marketHigh       = doc.getDouble(FunkoDexDatabase.FIELD_MARKET_HIGH),
        marketAvg        = doc.getDouble(FunkoDexDatabase.FIELD_MARKET_AVG),
        priceLastUpdated = runCatching {
            doc.getString(FunkoDexDatabase.FIELD_PRICE_UPDATED)?.let { LocalDate.parse(it) }
        }.getOrNull(),
        // Flags
        isOwned          = doc.getBoolean(FunkoDexDatabase.FIELD_IS_OWNED),
        isVaulted        = doc.getBoolean(FunkoDexDatabase.FIELD_IS_VAULTED),
        isChase          = doc.getBoolean(FunkoDexDatabase.FIELD_IS_CHASE),
        isExclusive      = doc.getBoolean(FunkoDexDatabase.FIELD_IS_EXCLUSIVE),
        exclusiveRetailer= doc.getString(FunkoDexDatabase.FIELD_EXCL_RETAILER)  ?: "",
        // Collection details
        condition        = runCatching {
            Condition.valueOf(doc.getString(FunkoDexDatabase.FIELD_CONDITION) ?: "MINT")
        }.getOrDefault(Condition.MINT),
        notes            = doc.getString(FunkoDexDatabase.FIELD_NOTES)          ?: "",
        dateAdded        = runCatching {
            LocalDate.parse(doc.getString(FunkoDexDatabase.FIELD_DATE_ADDED) ?: "")
        }.getOrDefault(LocalDate.now()),
        dateAcquired     = runCatching {
            doc.getString(FunkoDexDatabase.FIELD_DATE_ACQUIRED)?.let { LocalDate.parse(it) }
        }.getOrNull(),
    )
}
