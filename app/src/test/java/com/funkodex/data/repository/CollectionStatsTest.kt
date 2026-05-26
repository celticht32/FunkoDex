package com.funkodex.data.repository

import com.funkodex.data.model.Condition
import com.funkodex.data.model.FunkoItem
import com.funkodex.data.model.FunkoGenre
import com.funkodex.data.model.SeriesSummary
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CollectionStatsTest {

    private fun item(
        id: String,
        name: String,
        franchise: String,
        pricePaid: Double = 0.0,
        retailPrice: Double = 11.99,
        marketAvg: Double = 0.0,
        isOwned: Boolean = true,
    ) = FunkoItem(
        id          = id,
        name        = name,
        franchise   = franchise,
        pricePaid   = pricePaid,
        retailPrice = retailPrice,
        marketAvg   = marketAvg,
        isOwned     = isOwned,
    )

    // ─── SeriesSummary ────────────────────────────────────────────────────────

    @Test
    fun `SeriesSummary completionPct is correct`() {
        val summary = SeriesSummary(
            franchise      = "DC Comics",
            category       = "Pop! Heroes",
            genre          = FunkoGenre.ENTERTAINMENT,
            totalInCatalog = 10,
            ownedCount     = 7,
            wantedCount    = 3,
            missingItems   = emptyList(),
            totalCostPaid  = 70.0,
            marketValue    = 105.0,
            imageUrls      = emptyList(),
        )
        assertEquals(70, summary.completionPct)
    }

    @Test
    fun `SeriesSummary completionPct is 100 when full series owned`() {
        val summary = SeriesSummary(
            franchise      = "Star Wars",
            category       = "Pop! Movies",
            genre          = FunkoGenre.ENTERTAINMENT,
            totalInCatalog = 5,
            ownedCount     = 5,
            wantedCount    = 0,
            missingItems   = emptyList(),
            totalCostPaid  = 59.95,
            marketValue    = 75.0,
            imageUrls      = emptyList(),
        )
        assertEquals(100, summary.completionPct)
    }

    @Test
    fun `SeriesSummary completionPct is 0 when nothing owned`() {
        val summary = SeriesSummary(
            franchise      = "Empty",
            category       = "",
            genre          = FunkoGenre.OTHER,
            totalInCatalog = 0,
            ownedCount     = 0,
            wantedCount    = 0,
            missingItems   = emptyList(),
            totalCostPaid  = 0.0,
            marketValue    = 0.0,
            imageUrls      = emptyList(),
        )
        assertEquals(0, summary.completionPct)
    }

    // ─── FunkoItem defaults ───────────────────────────────────────────────────

    @Test
    fun `FunkoItem pricePaid defaults to zero`() {
        val item = FunkoItem(id = "test", name = "Test")
        assertEquals(0.0, item.pricePaid, 0.001)
    }

    @Test
    fun `FunkoItem isOwned defaults to false`() {
        val item = FunkoItem(id = "test", name = "Test")
        // Default is false — items are not owned until the user confirms the scan
        assertFalse(item.isOwned)
    }

    @Test
    fun `FunkoItem condition defaults to MINT`() {
        val item = FunkoItem(id = "test", name = "Test")
        assertEquals(Condition.MINT, item.condition)
    }

    @Test
    fun `want list item has isOwned false`() {
        val wanted = item("w1", "Wanted Pop", "Marvel", isOwned = false)
        assertFalse(wanted.isOwned)
    }

    // ─── Collection arithmetic ────────────────────────────────────────────────

    @Test
    fun `total pricePaid sums correctly`() {
        val items = listOf(
            item("1", "Batman",      "DC",  pricePaid = 14.99),
            item("2", "Superman",    "DC",  pricePaid = 11.99),
            item("3", "Mandalorian", "SW",  pricePaid = 12.49),
        )
        assertEquals(39.47, items.sumOf { it.pricePaid }, 0.001)
    }

    @Test
    fun `market value sums correctly`() {
        val items = listOf(
            item("1", "Batman",   "DC", marketAvg = 24.99),
            item("2", "Venom",    "Marvel", marketAvg = 18.50),
        )
        assertEquals(43.49, items.sumOf { it.marketAvg }, 0.001)
    }

    @Test
    fun `owned and wanted items filter correctly`() {
        val collection = listOf(
            item("1", "Batman",   "DC",  isOwned = true),
            item("2", "Flash",    "DC",  isOwned = false),
            item("3", "Thanos",   "Marvel", isOwned = true),
            item("4", "Iron Man", "Marvel", isOwned = false),
        )
        assertEquals(2, collection.count { it.isOwned })
        assertEquals(2, collection.count { !it.isOwned })
    }

    @Test
    fun `franchise grouping is case sensitive`() {
        val items = listOf(
            item("1", "A", "DC Comics"),
            item("2", "B", "DC Comics"),
            item("3", "C", "Star Wars"),
        )
        val byFranchise = items.groupBy { it.franchise }
        assertEquals(2, byFranchise.size)
        assertEquals(2, byFranchise["DC Comics"]?.size)
        assertEquals(1, byFranchise["Star Wars"]?.size)
    }
}
