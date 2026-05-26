package com.funkodex.network

import org.junit.Assert.*
import org.junit.Test

class FunkoLookupServiceTest {

    // ─── LocalFunkoRecord.toFunkoItem() tests ─────────────────────────────────

    @Test
    fun `LocalFunkoRecord toFunkoItem maps all fields`() {
        val record = FunkoLookupService.LocalFunkoRecord(
            name      = "Batman (1989)",
            series    = "DC Comics",
            number    = "#01",
            upc       = "012345678901",
            image     = "https://example.com/batman.png",
            category  = "Pop! Movies",
            exclusive = "Target",
            vaulted   = false,
            price     = 11.99,
        )
        val item = record.toFunkoItem()

        assertEquals("Batman (1989)",                item.name)
        assertEquals("DC Comics",                    item.series)
        assertEquals("#01",                          item.seriesNumber)
        assertEquals("012345678901",                 item.upc)
        assertEquals("https://example.com/batman.png", item.imageUrl)
        assertEquals("Pop! Movies",                  item.category)
        assertTrue(item.isExclusive)
        assertEquals("Target",                       item.exclusiveRetailer)
        assertFalse(item.isVaulted)
        assertEquals(11.99,                          item.retailPrice, 0.001)
    }

    @Test
    fun `LocalFunkoRecord toFunkoItem handles null exclusive`() {
        val record = FunkoLookupService.LocalFunkoRecord(
            name = "Test Pop", series = "Test", exclusive = null
        )
        val item = record.toFunkoItem()
        assertFalse(item.isExclusive)
        assertEquals("", item.exclusiveRetailer)
    }

    @Test
    fun `LocalFunkoRecord toFunkoItem uses upcOverride when provided`() {
        val record = FunkoLookupService.LocalFunkoRecord(name = "Test", upc = "111")
        val item   = record.toFunkoItem(upcOverride = "999")
        assertEquals("999", item.upc)
        assertTrue(item.id.contains("999"))
    }

    @Test
    fun `LocalFunkoRecord toFunkoItem generates id with upc`() {
        val record = FunkoLookupService.LocalFunkoRecord(name = "Test", upc = "012345678901")
        val item   = record.toFunkoItem()
        assertTrue(item.id.startsWith("funko::"))
        assertTrue(item.id.contains("012345678901"))
    }

    @Test
    fun `LocalFunkoRecord toFunkoItem handles null fields gracefully`() {
        val record = FunkoLookupService.LocalFunkoRecord()  // all nulls
        val item   = record.toFunkoItem()
        assertEquals("Unknown", item.name)
        assertEquals("",        item.series)
        assertEquals("",        item.upc)
        assertEquals(0.0,       item.retailPrice, 0.001)
    }

    // ─── Channel3Product.toFunkoItem() tests ──────────────────────────────────

    @Test
    fun `Channel3Product toFunkoItem maps attributes`() {
        val product = FunkoLookupService.Channel3Product(
            id         = "ch3-001",
            name       = "The Flash #196",
            upc        = "889698123456",
            imageUrl   = "https://img.example.com/flash.png",
            price      = 11.99,
            category   = "Pop! Heroes",
            attributes = mapOf(
                "series"    to "DC Comics",
                "number"    to "#196",
                "exclusive" to "Target",
            )
        )
        val item = product.toFunkoItem()

        assertEquals("The Flash #196",                 item.name)
        assertEquals("889698123456",                   item.upc)
        assertEquals("DC Comics",                      item.series)
        assertEquals("#196",                           item.seriesNumber)
        assertEquals("Target",                         item.exclusiveRetailer)
        assertTrue(item.isExclusive)
        assertEquals(11.99,                            item.retailPrice, 0.001)
    }

    @Test
    fun `Channel3Product toFunkoItem handles missing attributes`() {
        val product = FunkoLookupService.Channel3Product(name = "Test", brand = "Funko")
        val item    = product.toFunkoItem()
        assertEquals("Funko", item.series)   // falls back to brand when no series attribute
        assertEquals("",      item.seriesNumber)
        assertFalse(item.isExclusive)
    }
}
