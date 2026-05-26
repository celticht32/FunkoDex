package com.funkodex.data.repository

import com.funkodex.data.model.Condition
import com.funkodex.data.model.FunkoItem
import com.funkodex.data.model.SeriesSummary
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CollectionStatsTest {

    private fun item(
        id: String,
        name: String,
        series: String,
        pricePaid: Double = 0.0,
        retailPrice: Double = 11.99,
        isOwned: Boolean = true,
    ) = FunkoItem(
        id          = id,
        name        = name,
        series      = series,
        pricePaid   = pricePaid,
        retailPrice = retailPrice,
        isOwned     = isOwned,
    )

    @Test
    fun `SeriesSummary completionPct calculates correctly`() {
        val summary = SeriesSummary(
            seriesName    = "DC Comics",
            totalInSeries = 10,
            ownedCount    = 7,
            missingItems  = emptyList(),
            totalCostPaid = 70.0,
            imageUrls     = emptyList(),
        )
        assertEquals(70, summary.completionPct)
    }

    @Test
    fun `SeriesSummary completionPct is 100 when full series owned`() {
        val summary = SeriesSummary(
            seriesName    = "Star Wars",
            totalInSeries = 5,
            ownedCount    = 5,
            missingItems  = emptyList(),
            totalCostPaid = 59.95,
            imageUrls     = emptyList(),
        )
        assertEquals(100, summary.completionPct)
    }

    @Test
    fun `SeriesSummary completionPct is 0 when nothing owned`() {
        val summary = SeriesSummary(
            seriesName    = "Empty Series",
            totalInSeries = 0,
            ownedCount    = 0,
            missingItems  = emptyList(),
            totalCostPaid = 0.0,
            imageUrls     = emptyList(),
        )
        assertEquals(0, summary.completionPct)
    }

    @Test
    fun `FunkoItem pricePaid defaults to zero`() {
        val item = FunkoItem(id = "test", name = "Test", series = "Test")
        assertEquals(0.0, item.pricePaid, 0.001)
    }

    @Test
    fun `FunkoItem isOwned defaults to true`() {
        val item = FunkoItem(id = "test", name = "Test", series = "Test")
        assertTrue(item.isOwned)
    }

    @Test
    fun `FunkoItem condition defaults to MINT`() {
        val item = FunkoItem(id = "test", name = "Test", series = "Test")
        assertEquals(Condition.MINT, item.condition)
    }

    @Test
    fun `want list item has isOwned false`() {
        val wanted = item("w1", "Wanted Pop", "Marvel", isOwned = false)
        assertFalse(wanted.isOwned)
    }

    @Test
    fun `total cost calculation across collection`() {
        val items = listOf(
            item("1", "Batman",     "DC",     pricePaid = 14.99),
            item("2", "Superman",   "DC",     pricePaid = 11.99),
            item("3", "Mandalorian","SW",     pricePaid = 12.49),
        )
        val total = items.sumOf { it.pricePaid }
        assertEquals(39.47, total, 0.001)
    }
}
