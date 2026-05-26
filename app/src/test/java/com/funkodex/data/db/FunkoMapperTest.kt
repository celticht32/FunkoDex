package com.funkodex.data.db

import com.couchbase.lite.MutableDocument
import com.funkodex.data.model.Condition
import com.funkodex.data.model.FunkoItem
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FunkoMapperTest {

    private fun sampleItem(
        id: String = "funko::012345678901",
        isOwned: Boolean = true,
    ) = FunkoItem(
        id                = id,
        upc               = "012345678901",
        funkoId           = "batman-1989",
        name              = "Batman (1989)",
        franchise         = "DC Comics",
        seriesNumber      = "#01",
        category          = "Pop! Movies",
        imageUrl          = "https://example.com/batman.png",
        pricePaid         = 14.99,
        retailPrice       = 11.99,
        isExclusive       = true,
        exclusiveRetailer = "Target",
        isOwned           = isOwned,
        isVaulted         = false,
        condition         = Condition.NEAR_MINT,
        notes             = "Box has minor crease",
        dateAdded         = LocalDate.of(2025, 1, 15),
        dateAcquired      = LocalDate.of(2025, 1, 14),
    )

    @Test
    fun `toDocument sets all string fields correctly`() {
        val item = sampleItem()
        val doc  = FunkoMapper.toDocument(item)

        assertEquals("funko",            doc.getString(FunkoDexDatabase.FIELD_TYPE))
        assertEquals(item.upc,           doc.getString(FunkoDexDatabase.FIELD_UPC))
        assertEquals(item.funkoId,       doc.getString(FunkoDexDatabase.FIELD_FUNKO_ID))
        assertEquals(item.name,          doc.getString(FunkoDexDatabase.FIELD_NAME))
        assertEquals(item.franchise,     doc.getString(FunkoDexDatabase.FIELD_FRANCHISE))
        assertEquals(item.seriesNumber,  doc.getString(FunkoDexDatabase.FIELD_SERIES_NUM))
        assertEquals(item.category,      doc.getString(FunkoDexDatabase.FIELD_CATEGORY))
        assertEquals(item.imageUrl,      doc.getString(FunkoDexDatabase.FIELD_IMAGE_URL))
        assertEquals("NEAR_MINT",        doc.getString(FunkoDexDatabase.FIELD_CONDITION))
        assertEquals(item.notes,         doc.getString(FunkoDexDatabase.FIELD_NOTES))
    }

    @Test
    fun `toDocument sets numeric and boolean fields correctly`() {
        val item = sampleItem()
        val doc  = FunkoMapper.toDocument(item)

        assertEquals(14.99, doc.getDouble(FunkoDexDatabase.FIELD_PRICE_PAID),   0.001)
        assertEquals(11.99, doc.getDouble(FunkoDexDatabase.FIELD_RETAIL_PRICE), 0.001)
        assertTrue(doc.getBoolean(FunkoDexDatabase.FIELD_IS_EXCLUSIVE))
        assertTrue(doc.getBoolean(FunkoDexDatabase.FIELD_IS_OWNED))
        assertFalse(doc.getBoolean(FunkoDexDatabase.FIELD_IS_VAULTED))
    }

    @Test
    fun `toDocument sets date fields as ISO strings`() {
        val item = sampleItem()
        val doc  = FunkoMapper.toDocument(item)

        assertEquals("2025-01-15", doc.getString(FunkoDexDatabase.FIELD_DATE_ADDED))
        assertEquals("2025-01-14", doc.getString(FunkoDexDatabase.FIELD_DATE_ACQUIRED))
    }

    @Test
    fun `toDocument omits dateAcquired when null`() {
        val item = sampleItem().copy(dateAcquired = null)
        val doc  = FunkoMapper.toDocument(item)
        assertNull(doc.getString(FunkoDexDatabase.FIELD_DATE_ACQUIRED))
    }

    @Test
    fun `document id matches item id`() {
        val item = sampleItem(id = "funko::test123")
        val doc  = FunkoMapper.toDocument(item)
        assertEquals("funko::test123", doc.id)
    }

    @Test
    fun `fromDocument handles missing optional fields gracefully`() {
        val doc = MutableDocument("funko::minimal")
        doc.setString(FunkoDexDatabase.FIELD_TYPE, FunkoDexDatabase.TYPE_FUNKO)
        doc.setString(FunkoDexDatabase.FIELD_NAME, "Mystery Funko")
        doc.setBoolean(FunkoDexDatabase.FIELD_IS_OWNED, true)

        val item = FunkoMapper.fromDocument(doc)

        assertEquals("funko::minimal", item.id)
        assertEquals("Mystery Funko",  item.name)
        assertEquals("",               item.franchise)
        assertEquals("",               item.upc)
        assertEquals(0.0,              item.pricePaid, 0.001)
        assertEquals(Condition.MINT,   item.condition)
        assertFalse(item.isExclusive)
        assertNull(item.dateAcquired)
    }

    @Test
    fun `fromDocument handles unknown condition string gracefully`() {
        val doc = MutableDocument("funko::test")
        doc.setString(FunkoDexDatabase.FIELD_NAME, "Test")
        doc.setString(FunkoDexDatabase.FIELD_CONDITION, "DESTROYED")
        doc.setBoolean(FunkoDexDatabase.FIELD_IS_OWNED, true)

        val item = FunkoMapper.fromDocument(doc)
        assertEquals(Condition.MINT, item.condition)
    }

    @Test
    fun `want list item has isOwned false`() {
        val item = sampleItem(isOwned = false)
        val doc  = FunkoMapper.toDocument(item)
        assertFalse(doc.getBoolean(FunkoDexDatabase.FIELD_IS_OWNED))
    }

    @Test
    fun `roundtrip preserves all core fields`() {
        val original = sampleItem()
        val doc      = FunkoMapper.toDocument(original)
        val restored = FunkoMapper.fromDocument(doc)

        assertEquals(original.name,             restored.name)
        assertEquals(original.franchise,        restored.franchise)
        assertEquals(original.seriesNumber,     restored.seriesNumber)
        assertEquals(original.upc,              restored.upc)
        assertEquals(original.pricePaid,        restored.pricePaid,  0.001)
        assertEquals(original.retailPrice,      restored.retailPrice, 0.001)
        assertEquals(original.condition,        restored.condition)
        assertEquals(original.isOwned,          restored.isOwned)
        assertEquals(original.isVaulted,        restored.isVaulted)
        assertEquals(original.isExclusive,      restored.isExclusive)
        assertEquals(original.exclusiveRetailer,restored.exclusiveRetailer)
        assertEquals(original.dateAdded,        restored.dateAdded)
        assertEquals(original.dateAcquired,     restored.dateAcquired)
    }
}
