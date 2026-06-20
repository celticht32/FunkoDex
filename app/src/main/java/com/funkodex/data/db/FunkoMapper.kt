package com.funkodex.data.db

import com.couchbase.lite.Blob
import com.couchbase.lite.Document
import com.couchbase.lite.MutableDocument
import com.funkodex.data.model.Condition
import com.funkodex.data.model.FunkoGenre
import com.funkodex.data.model.FunkoItem
import java.time.LocalDate

object FunkoMapper {

    fun toDocument(item: FunkoItem, existing: com.couchbase.lite.Document? = null): MutableDocument {
        // Use existing document to preserve blobs (userPhoto, thumbnailBlob) not in FunkoItem model
        val doc = existing?.toMutable() ?: MutableDocument(item.id)
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
        doc.setDouble(FunkoDexDatabase.FIELD_RESOLVED_RETAIL, item.resolvedRetail)
        doc.setDouble(FunkoDexDatabase.FIELD_MARKET_LOW,     item.marketLow)
        doc.setDouble(FunkoDexDatabase.FIELD_MARKET_HIGH,    item.marketHigh)
        doc.setDouble(FunkoDexDatabase.FIELD_MARKET_AVG,     item.marketAvg)
        doc.setBoolean(FunkoDexDatabase.FIELD_MARKET_VALUE_IS_MANUAL, item.marketValueIsManual)
        if (item.marketValueIsApproximate) doc.setBoolean(FunkoDexDatabase.FIELD_MARKET_VALUE_IS_APPROX, true)
        item.priceLastUpdated?.let { doc.setString(FunkoDexDatabase.FIELD_PRICE_UPDATED, it.toString()) }
        if (item.pricechartingUrl.isNotEmpty()) {
            doc.setString(FunkoDexDatabase.FIELD_PRICECHARTING_URL, item.pricechartingUrl)
        }
        // Flags
        doc.setBoolean(FunkoDexDatabase.FIELD_IS_OWNED,      item.isOwned)
        doc.setBoolean(FunkoDexDatabase.FIELD_IS_VAULTED,    item.isVaulted)
        doc.setBoolean(FunkoDexDatabase.FIELD_IS_CHASE,      item.isChase)
        doc.remove(FunkoDexDatabase.FIELD_IS_MISSING_ORIGINAL)
        if (item.isMissingOriginal) {
            doc.setBoolean(FunkoDexDatabase.FIELD_IS_MISSING_ORIGINAL, true)
        }
        // Serialize variants as JSON string — explicitly remove field when empty
        if (item.variants.isNotEmpty()) {
            val arr = org.json.JSONArray()
            item.variants.forEach { v ->
                val obj = org.json.JSONObject()
                obj.put("id", v.id)
                obj.put("note", v.note)
                obj.put("pricePaid", v.pricePaid)
                obj.put("condition", v.condition.name)
                obj.put("dateAdded", v.dateAdded.toString())
                v.photo?.let { bytes ->
                    obj.put("photo", android.util.Base64.encodeToString(
                        bytes, android.util.Base64.NO_WRAP))
                }
                arr.put(obj)
            }
            doc.setString(FunkoDexDatabase.FIELD_VARIANTS, arr.toString())
        } else {
            doc.remove(FunkoDexDatabase.FIELD_VARIANTS)
        }
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
        userPhoto        = doc.getBlob(com.funkodex.data.repository.PhotoRepository.FIELD_USER_PHOTO)?.content,
        // Pricing
        pricePaid        = doc.getDouble(FunkoDexDatabase.FIELD_PRICE_PAID),
        retailPrice      = doc.getDouble(FunkoDexDatabase.FIELD_RETAIL_PRICE),
        resolvedRetail   = doc.getDouble(FunkoDexDatabase.FIELD_RESOLVED_RETAIL),
        marketLow        = doc.getDouble(FunkoDexDatabase.FIELD_MARKET_LOW),
        marketHigh       = doc.getDouble(FunkoDexDatabase.FIELD_MARKET_HIGH),
        marketAvg        = doc.getDouble(FunkoDexDatabase.FIELD_MARKET_AVG),
        marketValueIsManual = doc.getBoolean(FunkoDexDatabase.FIELD_MARKET_VALUE_IS_MANUAL),
        marketValueIsApproximate = doc.getBoolean(FunkoDexDatabase.FIELD_MARKET_VALUE_IS_APPROX),
        priceLastUpdated = runCatching {
            doc.getString(FunkoDexDatabase.FIELD_PRICE_UPDATED)?.let { LocalDate.parse(it) }
        }.getOrNull(),
        pricechartingUrl = doc.getString(FunkoDexDatabase.FIELD_PRICECHARTING_URL) ?: "",
        // Flags
        isOwned          = doc.getBoolean(FunkoDexDatabase.FIELD_IS_OWNED),
        isVaulted        = doc.getBoolean(FunkoDexDatabase.FIELD_IS_VAULTED),
        isChase          = doc.getBoolean(FunkoDexDatabase.FIELD_IS_CHASE),
        isMissingOriginal = doc.getBoolean(FunkoDexDatabase.FIELD_IS_MISSING_ORIGINAL) == true,
        variants         = doc.getString(FunkoDexDatabase.FIELD_VARIANTS)?.let { json ->
            runCatching {
                val arr = org.json.JSONArray(json)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    com.funkodex.data.model.FunkoVariant(
                        id        = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        note      = obj.optString("note", ""),
                        pricePaid = obj.optDouble("pricePaid", 0.0),
                        condition = runCatching {
                            com.funkodex.data.model.Condition.valueOf(obj.optString("condition", "MINT"))
                        }.getOrElse { com.funkodex.data.model.Condition.MINT },
                        dateAdded = runCatching {
                            java.time.LocalDate.parse(obj.optString("dateAdded"))
                        }.getOrElse { java.time.LocalDate.now() },
                        photo     = obj.optString("photo", "").takeIf { it.isNotEmpty() }?.let {
                            android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
                        },
                    )
                }
            }.getOrElse { emptyList() }
        } ?: emptyList(),
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
