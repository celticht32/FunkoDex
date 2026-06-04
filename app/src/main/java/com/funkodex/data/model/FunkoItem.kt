package com.funkodex.data.model

import java.time.LocalDate

/**
 * Core domain model for a single Funko in the user's COLLECTION.
 *
 * Primary key strategy (based on research):
 *   - Series number (#NNN) is unique WITHIN a Pop! category line, NOT globally.
 *     e.g. Star Wars #01 and DC Comics #01 are different items.
 *   - Therefore the true unique key is: category + seriesNumber, or the UPC barcode.
 *   - Document ID = "funko::{upc}" if scanned, else "funko::{uuid}"
 *   - catalogRef links back to the catalog document if the item was found in the dataset.
 *
 * Funko official category taxonomy (from funko.com / pop-figures.com research):
 *   Pop! Animation, Pop! Movies, Pop! Television, Pop! Games, Pop! Music,
 *   Pop! Heroes, Pop! Disney, Pop! Marvel, Pop! Star Wars, Pop! Sports,
 *   Pop! Football, Pop! Basketball, Pop! Baseball (MLB), Pop! Hockey,
 *   Pop! NASCAR, Pop! WWE, Pop! Books, Pop! Ad Icons, Pop! Icons,
 *   Pop! 8-Bit, Pop! Art Series, Pop! Deluxe, Pop! Rides, Pop! Moments,
 *   Pop! Funko, Pop! Asia, Pop! Myths, Pop! Holidays, Pop! Christmas, etc.
 *
 * Genre taxonomy (top-level grouping above category):
 *   ENTERTAINMENT  → Animation, Movies, Television, Disney, Marvel, Star Wars, Heroes, Games
 *   MUSIC          → Music, Albums, Artists, Broadway
 *   SPORTS         → Football, Basketball, Baseball, Hockey, NASCAR, WWE, Golf, Boxing, Cricket, MLS
 *   ICONS          → Ad Icons, Icons, Comedians, Directors
 *   FANTASY_MYTH   → Myths, Monsters, Magic
 *   LIFESTYLE      → Art Series, Asia, Around the World, Retro Toys, Board Games, Pets
 *   MILITARY       → Air Force, Army, Marines, Navy
 *   OTHER          → 8-Bit, Deluxe, Rides, Moments, Funko, Holidays, Christmas
 */
data class FunkoItem(
    // ── Identity ──────────────────────────────────────────────────────────────
    val id: String,                      // Couchbase doc key: "funko::{upc}" or "funko::{uuid}"
    val upc: String = "",                // Barcode UPC — primary lookup key when available
    val catalogRef: String = "",         // catalog doc ID if matched ("catalog::{handle}")
    val funkoId: String = "",            // Funko's own product ID / slug

    // ── Classification ────────────────────────────────────────────────────────
    val name: String,                    // e.g. "Batman (1989)"
    val franchise: String = "",          // The IP/franchise: "DC Comics", "Star Wars", "One Piece"
    val category: String = "",           // Funko product line: "Pop! Movies", "Pop! Animation"
    val genre: FunkoGenre = FunkoGenre.OTHER, // Top-level genre for filtering/reporting
    val seriesNumber: String = "",       // Number within the Pop! line: "#01", "#196"
    val seriesNumberInt: Int = -1,       // Parsed integer for sorting (-1 = no number)

    // ── Pricing ───────────────────────────────────────────────────────────────
    val pricePaid: Double = 0.0,         // What the user actually paid
    val retailPrice: Double = 0.0,       // Funko's listed retail (usually $11.99–$14.99)
    val marketLow: Double = 0.0,         // Current market low (from HobbyDB/Channel3)
    val marketHigh: Double = 0.0,        // Current market high
    val marketAvg: Double = 0.0,         // Current market average / estimated value
    val priceLastUpdated: LocalDate? = null, // When market prices were last fetched

    // ── Status flags ─────────────────────────────────────────────────────────
    val isOwned: Boolean = true,         // false = on want list
    val isVaulted: Boolean = false,      // Funko has retired/vaulted this item
    val isChase: Boolean = false,        // Chase variant (rare alternate version)
    val isMissingOriginal: Boolean = false, // Owns variant but not the standard version
    val isExclusive: Boolean = false,    // Retailer or convention exclusive
    val exclusiveRetailer: String = "",  // "Target", "GameStop", "SDCC", etc.

    // ── Collection details ────────────────────────────────────────────────────
    val condition: Condition = Condition.MINT,
    val notes: String = "",
    val imageUrl: String = "",
    val thumbnailBlob: ByteArray? = null,  // local blob downloaded on ownership confirmation
    val userPhoto: ByteArray? = null,      // user's own camera/gallery photo
    val dateAdded: LocalDate = LocalDate.now(),
    val dateAcquired: LocalDate? = null,

    // ── Variants ─────────────────────────────────────────────────────────────
    // Same Funko figure, different paint/packaging version.
    // Stored on the parent record — does NOT create a separate collection entry.
    val variants: List<FunkoVariant> = emptyList(),
)

enum class Condition {
    MINT,        // Box perfect, never opened
    NEAR_MINT,   // Minor shelf wear
    GOOD,        // Visible wear
    FAIR,        // Significant damage
    LOOSE        // No box
}

/**
 * A variant of a Funko item — same figure, different version (paint, packaging, etc.).
 * Stored as a list on the parent FunkoItem. Does NOT create a separate collection record,
 * so it doesn't affect series completion counts or duplicate the item in reports.
 */
data class FunkoVariant(
    val id: String = java.util.UUID.randomUUID().toString(),
    val note: String = "",           // What makes this different, e.g. "Metallic paint"
    val photo: ByteArray? = null,    // User photo of this specific variant
    val pricePaid: Double = 0.0,     // What was paid for this variant copy
    val condition: Condition = Condition.MINT,
    val dateAdded: LocalDate = LocalDate.now(),
)

/**
 * Top-level genre grouping — derived from the category field.
 * Used for the genre filter in the collection screen and reports.
 */
enum class FunkoGenre(val displayName: String) {
    ENTERTAINMENT("Entertainment"),  // Animation, Movies, TV, Disney, Marvel, Star Wars, Heroes, Games
    MUSIC("Music"),                  // Music, Albums, Artists, Broadway, Rocks
    SPORTS("Sports"),                // Football, Basketball, Baseball, Hockey, NASCAR, WWE, Golf
    ICONS("Icons & Advertising"),    // Ad Icons, Icons, Comedians, Directors
    FANTASY_MYTH("Fantasy & Myth"),  // Myths, Monsters, Magic, Horror
    LIFESTYLE("Lifestyle"),          // Art Series, Asia, Around the World, Board Games, Pets
    MILITARY("Military"),            // Air Force, Army, Marines, Navy
    OTHER("Other"),                  // Deluxe, Rides, Moments, Holidays, 8-Bit
    ;

    companion object {
        /** Derives genre from Funko category string */
        fun fromCategory(category: String): FunkoGenre {
            val c = category.lowercase()
            return when {
                c.contains("animation") || c.contains("movie") || c.contains("television") ||
                c.contains("disney") || c.contains("marvel") || c.contains("star wars") ||
                c.contains("heroes") || c.contains("games") || c.contains("anime") ||
                c.contains("harry potter") || c.contains("halo") || c.contains("8-bit") -> ENTERTAINMENT

                c.contains("music") || c.contains("album") || c.contains("artist") ||
                c.contains("broadway") || c.contains("rock") || c.contains("band") -> MUSIC

                c.contains("football") || c.contains("basketball") || c.contains("mlb") ||
                c.contains("baseball") || c.contains("hockey") || c.contains("nascar") ||
                c.contains("wwe") || c.contains("golf") || c.contains("boxing") ||
                c.contains("cricket") || c.contains("mls") || c.contains("nba") ||
                c.contains("sport") || c.contains("college") -> SPORTS

                c.contains("ad icon") || c.contains("icon") || c.contains("comedian") ||
                c.contains("director") || c.contains("candy") || c.contains("gp") -> ICONS

                c.contains("myth") || c.contains("monster") || c.contains("magic") ||
                c.contains("horror") || c.contains("classic") -> FANTASY_MYTH

                c.contains("air force") || c.contains("army") || c.contains("marine") ||
                c.contains("navy") -> MILITARY

                c.contains("art series") || c.contains("asia") || c.contains("around") ||
                c.contains("board") || c.contains("pet") || c.contains("retro") ||
                c.contains("book") -> LIFESTYLE

                else -> OTHER
            }
        }
    }
}

// ── Derived/aggregate models ──────────────────────────────────────────────────

data class SeriesSummary(
    val franchise: String,
    val category: String,
    val genre: FunkoGenre,
    val totalInCatalog: Int,       // total known items in this franchise/category
    val ownedCount: Int,
    val wantedCount: Int,
    val missingItems: List<FunkoItem>,
    val totalCostPaid: Double,
    val marketValue: Double,       // sum of marketAvg for owned items
    val imageUrls: List<String>,
) {
    val completionPct: Int get() = if (totalInCatalog == 0) 0 else (ownedCount * 100) / totalInCatalog
}

data class CollectionStats(
    val totalOwned: Int,
    val totalWanted: Int,
    val totalPaid: Double,
    val totalRetailValue: Double,
    val totalMarketValue: Double,
    val uniqueFranchises: Int,
    val mostExpensivePaid: FunkoItem?,
    val highestMarketValue: FunkoItem?,
    val recentlyAdded: List<FunkoItem>,
    val seriesSummaries: List<SeriesSummary>,
    val byGenre: Map<FunkoGenre, Int>,
)

/**
 * Catalog entry — reference data from Kenny Chan / Channel3 / Funko.com scrape.
 * NOT owned by the user. Used for lookup and series completion.
 */
data class CatalogEntry(
    val handle: String,
    val name: String,
    val franchise: String,
    val category: String,
    val genre: FunkoGenre,
    val seriesNumber: String,
    val seriesNumberInt: Int,
    val imageUrl: String,
    val isExclusive: Boolean,
    val exclusiveRetailer: String,
    val isChase: Boolean,
    val isVaulted: Boolean,
    val retailPrice: Double,
    val upc: String = "",
    val source: CatalogSource = CatalogSource.KENNY_CHAN,
    val lastUpdated: LocalDate = LocalDate.now(),
    val contributedBy: String = "anonymous",  // community upload label — never a real identity
)

enum class CatalogSource {
    KENNY_CHAN,     // Free open-source dataset (~23K items, 4 fields each)
    CHANNEL3,       // Channel3 API (structured, UPC-enabled)
    HOBBYDB,        // HobbyDB/Pop Price Guide (authenticated, has pricing)
    FUNKO_SITE,     // Scraped from funko.com directly
    USER_ADDED,     // User manually added an item not in any catalog
}

/**
 * Catalog refresh configuration — stored in user preferences, drives the
 * periodic background refresh scheduler.
 */
data class CatalogRefreshConfig(
    val enabled: Boolean = true,
    val intervalDays: Int = 7,          // How often to check for new data
    val wifiOnly: Boolean = true,       // Only refresh on WiFi
    val contributeEnabled: Boolean = false, // opt-in community UPC contribution (Phase F)
    val autoUpdateVaulted: Boolean = true,
    val sources: Set<CatalogSource> = setOf(CatalogSource.KENNY_CHAN, CatalogSource.CHANNEL3),
    val channel3ApiKey: String = "",    // Stored locally (not in repo)
    val hobbyDbEnabled: Boolean = false,
    val lastRefreshed: LocalDate? = null,
)
