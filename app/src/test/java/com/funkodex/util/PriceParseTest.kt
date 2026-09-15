package com.funkodex.util

import org.junit.Assert.*
import org.junit.Test

/**
 * PriceParseTest — locks the DEC-025 contract: parse an unambiguous money
 * string, refuse anything ambiguous, and never signal failure with 0.0.
 */
class PriceParseTest {

    @Test
    fun `parses a plain decimal`() {
        assertEquals(12.50, PriceParse.parse("12.50")!!, 0.0001)
        assertEquals(7.0, PriceParse.parse("7")!!, 0.0001)
    }

    @Test
    fun `parses a currency-prefixed value with thousands separators`() {
        assertEquals(1234.56, PriceParse.parse("$1,234.56")!!, 0.0001)
        assertEquals(1234.56, PriceParse.parse("  USD 1,234.56 ")!!, 0.0001)
    }

    @Test
    fun `refuses a range rather than guessing an end`() {
        // The old expression produced "10.0015.00" -> null -> written as 0.0.
        assertNull(PriceParse.parse("$10.00 - $15.00"))
    }

    @Test
    fun `refuses an ambiguous european format`() {
        // "1.234,56" would have been read as 1.23456 by the old expression.
        assertNull(PriceParse.parse("1.234,56"))
    }

    @Test
    fun `returns null for absent or non-numeric input`() {
        assertNull(PriceParse.parse(null))
        assertNull(PriceParse.parse(""))
        assertNull(PriceParse.parse("   "))
        assertNull(PriceParse.parse("N/A"))
        assertNull(PriceParse.parse("$"))
    }

    @Test
    fun `a genuine zero parses as zero, not as failure`() {
        val v = PriceParse.parse("$0.00")
        assertNotNull("zero must be a value, not a null failure signal", v)
        assertEquals(0.0, v!!, 0.0001)
    }

    @Test
    fun `parsePositive rejects zero and negatives but keeps real prices`() {
        assertNull(PriceParse.parsePositive("$0.00"))
        assertNull(PriceParse.parsePositive("N/A"))
        assertEquals(3.99, PriceParse.parsePositive("$3.99")!!, 0.0001)
    }
}
