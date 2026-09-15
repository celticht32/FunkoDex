package com.funkodex.data.preload

import org.junit.Assert.*
import org.junit.Test

/**
 * TitleDqTest — DEC-031.
 *
 * Locks the title data-quality contract: what counts as a usable title, how a
 * handle de-slugs into a readable name, and that a blank-title record still
 * gets a non-blank display name.
 */
class TitleDqTest {

    // ─── isUsableTitle truth table ─────────────────────────────────────────────

    @Test
    fun `isUsableTitle rejects null`() {
        assertFalse(TitleDq.isUsableTitle(null))
    }

    @Test
    fun `isUsableTitle rejects empty and whitespace`() {
        assertFalse(TitleDq.isUsableTitle(""))
        assertFalse(TitleDq.isUsableTitle("   "))
        assertFalse(TitleDq.isUsableTitle("\t\n"))
    }

    @Test
    fun `isUsableTitle rejects punctuation-only`() {
        assertFalse(TitleDq.isUsableTitle("#"))
        assertFalse(TitleDq.isUsableTitle("--"))
        assertFalse(TitleDq.isUsableTitle("!?"))
    }

    @Test
    fun `isUsableTitle rejects a single alphanumeric character`() {
        // The two real single-char titles in the shipped catalog.
        assertFalse(TitleDq.isUsableTitle("V"))
        assertFalse(TitleDq.isUsableTitle("L"))
        assertFalse(TitleDq.isUsableTitle(" V "))
    }

    @Test
    fun `isUsableTitle accepts two or more alphanumeric characters`() {
        assertTrue(TitleDq.isUsableTitle("VV"))
        assertTrue(TitleDq.isUsableTitle("R2"))
        assertTrue(TitleDq.isUsableTitle("Batman"))
        // Punctuation doesn't count, but the letters around it do.
        assertTrue(TitleDq.isUsableTitle("V (BTS)"))
    }

    // ─── deSlug ────────────────────────────────────────────────────────────────

    @Test
    fun `deSlug turns a hyphenated handle into a readable name`() {
        assertEquals("Holiday Piglet", TitleDq.deSlug("holiday-piglet"))
    }

    @Test
    fun `deSlug drops a trailing box-number segment`() {
        assertEquals("Maui", TitleDq.deSlug("maui-214"))
    }

    @Test
    fun `deSlug returns empty for a pure PriceCharting id handle`() {
        assertEquals("", TitleDq.deSlug("pc-7506256"))
    }

    @Test
    fun `deSlug strips the catalog document-id prefix`() {
        assertEquals("Snow White Maid", TitleDq.deSlug("catalog::snow-white-maid"))
    }

    @Test
    fun `deSlug returns empty for null blank and prefix-only input`() {
        assertEquals("", TitleDq.deSlug(null))
        assertEquals("", TitleDq.deSlug(""))
        assertEquals("", TitleDq.deSlug("   "))
        assertEquals("", TitleDq.deSlug("catalog::"))
    }

    // ─── firstNonBlank ─────────────────────────────────────────────────────────

    @Test
    fun `firstNonBlank returns the first non-blank trimmed value`() {
        assertEquals("Series", TitleDq.firstNonBlank(null, "  ", " Series ", "Handle"))
        assertEquals("", TitleDq.firstNonBlank(null, "", "   "))
    }

    // ─── displayName ───────────────────────────────────────────────────────────

    @Test
    fun `displayName prefers a usable title`() {
        assertEquals(
            "Holiday Piglet",
            TitleDq.displayName("Holiday Piglet", "Pop! Disney", "catalog::holiday-piglet"),
        )
    }

    @Test
    fun `displayName on a blank title falls back to series`() {
        val name = TitleDq.displayName("", "Pop! Disney", "catalog::holiday-piglet")
        assertTrue("expected a non-blank fallback, got '$name'", name.isNotBlank())
        assertEquals("Pop! Disney", name)
    }

    @Test
    fun `displayName on a blank title and blank series falls back to the handle`() {
        val name = TitleDq.displayName("  ", null, "catalog::holiday-piglet")
        assertTrue("expected a non-blank fallback, got '$name'", name.isNotBlank())
        assertEquals("Holiday Piglet", name)
    }

    @Test
    fun `displayName treats a degenerate title as unusable`() {
        assertEquals("Pop! Television", TitleDq.displayName("V", "Pop! Television", "catalog::v-1234"))
    }

    // ─── displayName with a property (the real-catalog cases) ──────────────────

    @Test
    fun `displayName qualifies a degenerate title with the property`() {
        // The 11 real degenerate rows in the shipped catalog, by pcSeries.
        assertEquals("V (BTS)", TitleDq.displayName("V", "Pop! Rocks", "catalog::pc-7514981", "BTS"))
        assertEquals("L (Death Note)", TitleDq.displayName("L", "Pop! Animation", "catalog::pc-13256519", "Death Note"))
        assertEquals("X (Mega Man X)", TitleDq.displayName("X", "Pop! WWE", "catalog::pc-13256481", "Mega Man X"))
        assertEquals("1 (Stranger Things)", TitleDq.displayName("1", "Pop! Television", "catalog::pc-7493393", "Stranger Things"))
    }

    @Test
    fun `displayName distinguishes rows that series alone collapsed`() {
        // Six catalog rows share series "Pop! Rocks"; the property separates them.
        val a = TitleDq.displayName("V", "Pop! Rocks", "catalog::pc-7515191", "BTS - Butter")
        val b = TitleDq.displayName("V", "Pop! Rocks", "catalog::pc-7515171", "BTS X Despicable Me 4")
        assertEquals("V (BTS - Butter)", a)
        assertEquals("V (BTS X Despicable Me 4)", b)
        assertTrue("property must disambiguate same-series rows", a != b)
    }

    @Test
    fun `displayName does not qualify a title with no alphanumerics`() {
        // "# (BTS)" would be worse than "BTS".
        assertEquals("BTS", TitleDq.displayName("#", "Pop! Rocks", "catalog::pc-7514981", "BTS"))
        assertEquals("BTS", TitleDq.displayName("", "Pop! Rocks", "catalog::pc-7514981", "BTS"))
    }

    @Test
    fun `displayName skips a property identical to the title`() {
        // "V (V)" is noise; with nothing better available the bare title stands.
        assertEquals("V", TitleDq.displayName("V", null, "catalog::pc-1", "V"))
    }

    @Test
    fun `displayName prefers property over series when the title is blank`() {
        assertEquals("BTS", TitleDq.displayName("", "Pop! Rocks", "catalog::pc-7514981", "BTS"))
    }

    @Test
    fun `displayName without a property keeps the original fallback order`() {
        assertEquals("Pop! Disney", TitleDq.displayName("", "Pop! Disney", "catalog::holiday-piglet"))
        assertEquals("Holiday Piglet", TitleDq.displayName("", null, "catalog::holiday-piglet"))
    }

    // ─── searchHaystack ────────────────────────────────────────────────────────

    @Test
    fun `searchHaystack combines title series and de-slugged handle`() {
        assertEquals(
            "Holiday Piglet Pop! Disney Holiday Piglet",
            TitleDq.searchHaystack("Holiday Piglet", "Pop! Disney", "catalog::holiday-piglet"),
        )
    }

    @Test
    fun `searchHaystack drops blanks and keeps a blank-title record findable`() {
        assertEquals("Holiday Piglet", TitleDq.searchHaystack("", null, "catalog::holiday-piglet"))
        assertEquals("", TitleDq.searchHaystack(null, null, "pc-7506256"))
    }

    @Test
    fun `searchHaystack includes the property so a degenerate row is findable by it`() {
        // Searching "BTS" must be able to reach catalog::pc-7514981, whose title
        // is "V", whose series is the line "Pop! Rocks", and whose handle is an
        // opaque PriceCharting id.
        val hay = TitleDq.searchHaystack("V", "Pop! Rocks", "catalog::pc-7514981", "BTS")
        assertEquals("V Pop! Rocks BTS", hay)
        assertTrue("must be reachable by property", hay.contains("BTS"))
    }
}
