package com.funkodex.data.model

/**
 * User category filter preferences.
 *
 * Stored as "cat_pref::{categoryKey}" documents in Couchbase.
 * Disabled categories are excluded from:
 *   - Catalog search results
 *   - Want list generation
 *   - Series completion reports
 *   - Scanner lookup (still scans, but won't suggest adding)
 *   - Collection grid display (unless the user actually owns the item)
 *
 * Note: items the user actually OWNS always show regardless of category filter,
 * so disabling "Pop! Sports" after you've scanned a few sports items won't
 * hide the ones you have — it only hides catalog suggestions and new lookups.
 *
 * categoryKey = the normalized category string, e.g. "pop_movies", "pop_animation"
 * Built from the Pop! category name: "Pop! Movies" → "pop_movies"
 */
data class CategoryPreference(
    val categoryKey: String,        // "pop_movies", "pop_sports", "pop_disney"
    val categoryName: String,       // "Pop! Movies"  — display name
    val genreName: String,          // "ENTERTAINMENT"  — FunkoGenre.name
    val isEnabled: Boolean = true,  // false = user has turned this category off
)

/**
 * Complete set of all known Funko Pop! categories with their default enabled state.
 * Based on the official taxonomy from funko.com / pop-figures.com.
 *
 * Users who collect only certain things can disable entire genres at once
 * (e.g. toggle off all SPORTS) or individual categories within a genre.
 */
object FunkoCategories {

    data class CategoryDef(
        val key: String,
        val displayName: String,
        val genre: FunkoGenre,
        val defaultEnabled: Boolean = true,
    )

    val ALL: List<CategoryDef> = listOf(
        // ── ENTERTAINMENT ─────────────────────────────────────────────────
        CategoryDef("pop_animation",    "Pop! Animation",       FunkoGenre.ENTERTAINMENT),
        CategoryDef("pop_anime",        "Pop! Anime",           FunkoGenre.ENTERTAINMENT),
        CategoryDef("pop_movies",       "Pop! Movies",          FunkoGenre.ENTERTAINMENT),
        CategoryDef("pop_television",   "Pop! Television",      FunkoGenre.ENTERTAINMENT),
        CategoryDef("pop_disney",       "Pop! Disney",          FunkoGenre.ENTERTAINMENT),
        CategoryDef("pop_marvel",       "Pop! Marvel",          FunkoGenre.ENTERTAINMENT),
        CategoryDef("pop_star_wars",    "Pop! Star Wars",       FunkoGenre.ENTERTAINMENT),
        CategoryDef("pop_heroes",       "Pop! Heroes",          FunkoGenre.ENTERTAINMENT),
        CategoryDef("pop_games",        "Pop! Games",           FunkoGenre.ENTERTAINMENT),
        CategoryDef("pop_harry_potter", "Pop! Harry Potter",    FunkoGenre.ENTERTAINMENT),
        CategoryDef("pop_halo",         "Pop! Halo",            FunkoGenre.ENTERTAINMENT),
        CategoryDef("pop_8bit",         "Pop! 8-Bit",           FunkoGenre.ENTERTAINMENT),
        CategoryDef("pop_game_of_thrones","Pop! Game of Thrones",FunkoGenre.ENTERTAINMENT),
        CategoryDef("pop_muppets",      "Pop! Muppets",         FunkoGenre.ENTERTAINMENT),

        // ── MUSIC ─────────────────────────────────────────────────────────
        CategoryDef("pop_music",        "Pop! Music",           FunkoGenre.MUSIC),
        CategoryDef("pop_albums",       "Pop! Albums",          FunkoGenre.MUSIC),
        CategoryDef("pop_artists",      "Pop! Artists",         FunkoGenre.MUSIC),
        CategoryDef("pop_broadway",     "Pop! Broadway",        FunkoGenre.MUSIC),
        CategoryDef("pop_rocks",        "Pop! Rocks",           FunkoGenre.MUSIC),

        // ── SPORTS ────────────────────────────────────────────────────────
        CategoryDef("pop_football",     "Pop! Football",        FunkoGenre.SPORTS, defaultEnabled = true),
        CategoryDef("pop_basketball",   "Pop! Basketball",      FunkoGenre.SPORTS, defaultEnabled = true),
        CategoryDef("pop_mlb",          "Pop! MLB",             FunkoGenre.SPORTS, defaultEnabled = true),
        CategoryDef("pop_hockey",       "Pop! Hockey",          FunkoGenre.SPORTS, defaultEnabled = true),
        CategoryDef("pop_nascar",       "Pop! NASCAR",          FunkoGenre.SPORTS, defaultEnabled = true),
        CategoryDef("pop_wwe",          "Pop! WWE",             FunkoGenre.SPORTS, defaultEnabled = true),
        CategoryDef("pop_golf",         "Pop! Golf",            FunkoGenre.SPORTS, defaultEnabled = true),
        CategoryDef("pop_boxing",       "Pop! Boxing",          FunkoGenre.SPORTS, defaultEnabled = true),
        CategoryDef("pop_cricket",      "Pop! Cricket",         FunkoGenre.SPORTS, defaultEnabled = true),
        CategoryDef("pop_mls",          "Pop! MLS",             FunkoGenre.SPORTS, defaultEnabled = true),
        CategoryDef("pop_nba_mascots",  "Pop! NBA Mascots",     FunkoGenre.SPORTS, defaultEnabled = true),
        CategoryDef("pop_college",      "Pop! College",         FunkoGenre.SPORTS, defaultEnabled = true),

        // ── ICONS & ADVERTISING ───────────────────────────────────────────
        CategoryDef("pop_ad_icons",     "Pop! Ad Icons",        FunkoGenre.ICONS, defaultEnabled = true),
        CategoryDef("pop_icons",        "Pop! Icons",           FunkoGenre.ICONS),
        CategoryDef("pop_comedians",    "Pop! Comedians",       FunkoGenre.ICONS),
        CategoryDef("pop_directors",    "Pop! Directors",       FunkoGenre.ICONS),
        CategoryDef("pop_gpk",          "Pop! GPK",             FunkoGenre.ICONS, defaultEnabled = true),

        // ── FANTASY & MYTH ────────────────────────────────────────────────
        CategoryDef("pop_myths",        "Pop! Myths",           FunkoGenre.FANTASY_MYTH),
        CategoryDef("pop_monsters",     "Pop! Monsters",        FunkoGenre.FANTASY_MYTH),
        CategoryDef("pop_magic",        "Pop! Magic",           FunkoGenre.FANTASY_MYTH),
        CategoryDef("pop_classics",     "Pop! Classics",        FunkoGenre.FANTASY_MYTH),

        // ── LIFESTYLE ─────────────────────────────────────────────────────
        CategoryDef("pop_books",        "Pop! Books",           FunkoGenre.LIFESTYLE),
        CategoryDef("pop_art_series",   "Pop! Art Series",      FunkoGenre.LIFESTYLE),
        CategoryDef("pop_asia",         "Pop! Asia",            FunkoGenre.LIFESTYLE),
        CategoryDef("pop_around_world", "Pop! Around the World",FunkoGenre.LIFESTYLE),
        CategoryDef("pop_board_games",  "Pop! Board Games",     FunkoGenre.LIFESTYLE),
        CategoryDef("pop_pets",         "Pop! Pets",            FunkoGenre.LIFESTYLE),
        CategoryDef("pop_retro_toys",   "Pop! Retro Toys",      FunkoGenre.LIFESTYLE),
        CategoryDef("pop_candy",        "Pop! Candy",           FunkoGenre.LIFESTYLE, defaultEnabled = true),
        CategoryDef("pop_aquasox",      "Pop! AquaSox",         FunkoGenre.LIFESTYLE, defaultEnabled = true),

        // ── MILITARY ──────────────────────────────────────────────────────
        CategoryDef("pop_air_force",    "Pop! Air Force",       FunkoGenre.MILITARY, defaultEnabled = true),
        CategoryDef("pop_army",         "Pop! Army",            FunkoGenre.MILITARY, defaultEnabled = true),
        CategoryDef("pop_marines",      "Pop! Marines",         FunkoGenre.MILITARY, defaultEnabled = true),
        CategoryDef("pop_navy",         "Pop! Navy",            FunkoGenre.MILITARY, defaultEnabled = true),

        // ── OTHER / PRODUCT LINES ─────────────────────────────────────────
        CategoryDef("pop_deluxe",       "Pop! Deluxe",          FunkoGenre.OTHER),
        CategoryDef("pop_town",         "Pop! Town",            FunkoGenre.OTHER),
        CategoryDef("pop_rides",        "Pop! Rides",           FunkoGenre.OTHER),
        CategoryDef("pop_moments",      "Pop! Moments",         FunkoGenre.OTHER),
        CategoryDef("pop_holidays",     "Pop! Holidays",        FunkoGenre.OTHER),
        CategoryDef("pop_christmas",    "Pop! Christmas",       FunkoGenre.OTHER),
        CategoryDef("pop_funko",        "Pop! Funko",           FunkoGenre.OTHER),
        CategoryDef("pop_bitty",        "Pop! Bitty Pop!",      FunkoGenre.OTHER),
        CategoryDef("pop_pins",         "Pop! Pins",            FunkoGenre.OTHER, defaultEnabled = true),
        CategoryDef("pop_pez",          "Pop! Pez",             FunkoGenre.OTHER, defaultEnabled = true),
        CategoryDef("pop_art_covers",   "Pop! Art Covers",      FunkoGenre.OTHER),
        CategoryDef("pop_comic_covers", "Pop! Comic Covers",    FunkoGenre.OTHER),
        CategoryDef("pop_die_cast",     "Pop! Die-Cast",        FunkoGenre.OTHER),
    )

    /** Get default enabled categories as a ready-to-store list */
    fun defaultPreferences(): List<CategoryPreference> = ALL.map { def ->
        CategoryPreference(
            categoryKey  = def.key,
            categoryName = def.displayName,
            genreName    = def.genre.name,
            isEnabled    = def.defaultEnabled,
        )
    }

    /** Normalise a category string from catalog data to a key */
    fun toKey(categoryName: String): String =
        categoryName.lowercase()
            .removePrefix("pop! ")
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .let { "pop_$it" }

    /**
     * The full category list for UI pickers: the curated [ALL] list UNION any
     * distinct category strings discovered in the imported catalog that aren't
     * already known. Discovered categories get their genre from
     * [FunkoGenre.fromCategory] (keyword-derived, falling back to OTHER), so a new
     * Funko product line shows up in the dropdown automatically without a code
     * change. Curated entries always win on key collision (they carry the
     * hand-checked genre). Result is de-duplicated by [toKey] and the discovered
     * extras are appended after the curated ones.
     */
    fun allWithDiscovered(catalogCategories: Collection<String>): List<CategoryDef> {
        val knownKeys = ALL.map { it.key }.toHashSet()
        val seen = HashSet<String>()
        val extras = ArrayList<CategoryDef>()
        for (raw in catalogCategories) {
            val name = raw.trim()
            if (name.isEmpty()) continue
            val key = toKey(name)
            if (key in knownKeys || !seen.add(key)) continue
            extras += CategoryDef(key, name, FunkoGenre.fromCategory(name))
        }
        // Curated first (stable, hand-ordered), then discovered extras sorted by name.
        return ALL + extras.sortedBy { it.displayName }
    }

    /** All genres that appear in the list — for the genre-level toggle */
    val GENRES: List<FunkoGenre> = FunkoGenre.values().toList()
}
