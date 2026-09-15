package com.funkodex.util

/**
 * PriceParse
 *
 * Parses a money string supplied by the enricher or the catalog into a Double.
 *
 * This replaces the expression that was repeated at four call sites:
 *
 *     raw.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0
 *
 * which strips every non-digit and then hopes the remainder is a number. It
 * handles "$1,234.56" correctly but silently mangles anything carrying a second
 * separator: "$10.00 - $15.00" becomes "10.0015.00" and "1.234,56" becomes
 * "1.23456". Both fail toDoubleOrNull(), and the call sites then substituted 0.0
 * — writing a guess, and one indistinguishable from a genuinely free item.
 *
 * DEC-025 ("blank a wrong value; never guess a replacement") says the right
 * answer is to REFUSE. So:
 *
 *   - exactly one money token in the string -> parse it
 *   - zero tokens, or MORE than one -> null, and the caller leaves the field alone
 *
 * Refusing on multiple tokens is deliberate rather than lazy. A range like
 * "$10.00 - $15.00" has no single correct answer, and picking the first would be
 * exactly the guess DEC-025 forbids. A European "1.234,56" also yields two
 * tokens and is therefore refused rather than silently read as 1.234.
 *
 * MIT License — Copyright (c) 2026 Chris Ahrendt
 */
object PriceParse {

    /**
     * A money token: digits, optional thousands separators, optional decimal
     * part. Deliberately does NOT match a bare "." or a lone separator.
     */
    private val MONEY = Regex("""\d[\d,]*(?:\.\d+)?""")

    /**
     * The numeric value of [raw], or null when it cannot be read unambiguously.
     *
     * Never returns 0.0 as a failure signal — a returned 0.0 means the string
     * genuinely said zero. Callers distinguish "no value" (null) from "zero".
     */
    fun parse(raw: String?): Double? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        val tokens = MONEY.findAll(text).map { it.value }.toList()
        if (tokens.size != 1) return null          // absent, or ambiguous — refuse

        return tokens[0].replace(",", "").toDoubleOrNull()
    }

    /**
     * Like [parse] but also rejects non-positive values, for the callers that
     * treat 0 as "no price recorded" rather than as a real price.
     */
    fun parsePositive(raw: String?): Double? = parse(raw)?.takeIf { it > 0.0 }
}
