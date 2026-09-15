package com.funkodex.data.preload

/**
 * TitleDq
 *
 * Title data-quality helpers for catalog records (DEC-031).
 *
 * A catalog doc whose `title` is blank or degenerate ("", " ", "#", a single
 * letter) is invisible to name search and produces a nameless item on a UPC
 * scan.  The shipped catalog is clean today, so this is a latent hazard rather
 * than a live bug — it fires once externally-authored records arrive via the
 * Community Catalog Distribution path.  These helpers are the single place that
 * decides whether a title is usable and what to show when it isn't.
 *
 * Read-side (displayName/searchHaystack) degrades gracefully: the record's
 * PROPERTY (pcSeries/franchiseSuggestion), then series, then the de-slugged
 * handle, stand in for a missing title so the record stays nameable and
 * findable.  Write-side (isUsableTitle) is the importer's insert guard.
 *
 * Property before series is deliberate and data-driven: on this catalog the 11
 * degenerate-title rows are PriceCharting-sourced, so their handles are opaque
 * ids ("pc-7514981") and their `series` holds the LINE ("Pop! Rocks"), not the
 * property.  Falling back to series rendered six different figures as the
 * identical string "Pop! Rocks"; `pcSeries` holds the real answer (BTS, Death
 * Note, Mega Man X, Stranger Things).
 *
 * MIT License — Copyright (c) 2026 Chris Ahrendt
 */
object TitleDq {

    /** A title needs at least this many alphanumeric characters to be usable. */
    private const val MIN_ALNUM = 2

    /** Document-id prefix on catalog docs; stripped before de-slugging. */
    private const val CATALOG_PREFIX = "catalog::"

    /**
     * A handle that is nothing but a PriceCharting id ("pc-7506256").  De-slugging
     * one yields "Pc", which is worse than no name at all, so it returns "".
     */
    private val PC_ID_HANDLE = Regex("^pc-\\d+$", RegexOption.IGNORE_CASE)

    /**
     * Trailing box-number segment on a handle ("maui-214" -> "maui").  Note this
     * also trims a legitimate trailing number in a name (a hypothetical
     * "blink-182"); Pop handles carry the box number here often enough that
     * dropping it is the right default.
     */
    private val TRAILING_BOX_NUMBER = Regex("-\\d+$")

    /**
     * True when [title] carries real content: at least [MIN_ALNUM] alphanumeric
     * characters after trimming.  Rejects "", "   ", "#", and single characters
     * such as "V" or "L"; accepts "VV", "R2", "Batman".
     */
    fun isUsableTitle(title: String?): Boolean {
        val trimmed = title?.trim() ?: return false
        return trimmed.count { it.isLetterOrDigit() } >= MIN_ALNUM
    }

    /**
     * Turn a catalog handle into a human-readable name:
     * "holiday-piglet" -> "Holiday Piglet", "maui-214" -> "Maui",
     * "catalog::snow-white-maid" -> "Snow White Maid".
     *
     * A leading "catalog::" document-id prefix is stripped first, so a raw doc id
     * can be passed straight in.  A pure PriceCharting id handle yields "".
     */
    fun deSlug(handle: String?): String {
        val raw = handle?.trim().orEmpty().removePrefix(CATALOG_PREFIX).trim()
        if (raw.isEmpty()) return ""
        if (PC_ID_HANDLE.matches(raw)) return ""

        val stripped = raw.replace(TRAILING_BOX_NUMBER, "")
        if (stripped.isBlank()) return ""

        return stripped
            .split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { it.uppercaseChar() }
            }
    }

    /** First non-blank value, trimmed; "" when every candidate is blank. */
    fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }?.trim() ?: ""

    /** Count of alphanumeric characters, the measure [isUsableTitle] applies. */
    private fun alnumCount(s: String): Int = s.count { it.isLetterOrDigit() }

    /**
     * The name to show for a catalog record.
     *
     *  1. A usable title wins outright.
     *  2. A DEGENERATE BUT PRESENT title ("V", "L", "X", "1") is real data — it
     *     is the figure's actual name, just ambiguous on its own — so it is
     *     QUALIFIED with [property] rather than discarded: "V (BTS)".  This is
     *     the same convention used when repairing these titles at source.
     *  3. Otherwise fall back to property, then series, then the de-slugged
     *     handle.
     *
     * [property] is the record's pcSeries (or franchiseSuggestion) — the real
     * property, as opposed to `series`, which holds the Pop! line.
     *
     * Returns "" only when a record has no usable signal at all, which the
     * importer's insert guard prevents for net-new records.
     */
    fun displayName(
        title: String?,
        series: String?,
        handle: String?,
        property: String? = null,
    ): String {
        val t = title?.trim().orEmpty()
        if (isUsableTitle(t)) return t

        val prop = property?.trim().orEmpty()
        // Qualify only a title that carries at least one alphanumeric — "#" is
        // noise, not a name, and "# (BTS)" would be worse than plain "BTS".
        // A property identical to the title adds nothing, so skip that too.
        if (alnumCount(t) >= 1 && prop.isNotEmpty() && !prop.equals(t, ignoreCase = true)) {
            return "$t ($prop)"
        }
        return firstNonBlank(prop, series, deSlug(handle))
    }

    /**
     * The text a name search matches against: title, series, [property], and the
     * de-slugged handle combined, blanks dropped.  The handle keeps a record with
     * a blank title findable by the name its handle encodes; the property is what
     * makes the degenerate-title rows findable by what a user would actually type
     * ("BTS", "Death Note") rather than by a single letter.
     *
     * NOTE: the coarse Couchbase LIKE pre-filter in FunkoLookupService must test
     * the same columns this combines, or a record never reaches this refine.
     */
    fun searchHaystack(
        title: String?,
        series: String?,
        handle: String?,
        property: String? = null,
    ): String =
        listOf(
            title?.trim().orEmpty(),
            series?.trim().orEmpty(),
            property?.trim().orEmpty(),
            deSlug(handle),
        )
            .filter { it.isNotBlank() }
            .joinToString(" ")
}
