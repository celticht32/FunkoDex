package com.funkodex.util

/**
 * Format validation for retail barcodes entered by hand.
 *
 * Validates UPC-A (12 digits) and EAN-13 (13 digits) by their standard
 * modulo-10 check digit. This catches transposition and single-digit typos
 * locally, with no network call. It does NOT verify the code maps to a real
 * product — only that the digits form a structurally valid barcode.
 */
object UpcValidation {

    /** True for a structurally valid UPC-A or EAN-13 (check digit included). */
    fun isValid(raw: String): Boolean {
        val s = raw.trim()
        return when (s.length) {
            12 -> s.all(Char::isDigit) && upcACheckOk(s)
            13 -> s.all(Char::isDigit) && ean13CheckOk(s)
            else -> false
        }
    }

    /** UPC-A: 3 * sum(odd-index digits) + sum(even-index digits), all 12, mod 10 == 0. */
    private fun upcACheckOk(s: String): Boolean {
        var sum = 0
        for (i in 0 until 12) {
            val d = s[i] - '0'
            sum += if (i % 2 == 0) d * 3 else d
        }
        return sum % 10 == 0
    }

    /** EAN-13: positions weighted 1 and 3 over all 13 digits, mod 10 == 0. */
    private fun ean13CheckOk(s: String): Boolean {
        var sum = 0
        for (i in 0 until 13) {
            val d = s[i] - '0'
            sum += if (i % 2 == 0) d else d * 3
        }
        return sum % 10 == 0
    }
}
