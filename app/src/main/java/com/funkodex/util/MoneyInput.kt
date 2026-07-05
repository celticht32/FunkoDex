package com.funkodex.util

/**
 * Sanitizer for hand-entered dollar amounts (price paid, market value, target
 * price). Applied in an OutlinedTextField's onValueChange so the field can never
 * accumulate a malformed value like "9.959.95", "9.9.9", or stray letters — the
 * failure mode where toDoubleOrNull() later returns null and the amount silently
 * saves as 0.00.
 *
 * Clean-as-you-type (no hard block): each keystroke is coerced to a valid money
 * shape rather than rejected, so the user is never stuck. KeyboardType.Decimal
 * covers the on-screen keyboard, but paste and hardware keyboards can still emit
 * a second '.' or letters — this is the enforcement, the keyboard type is only a
 * hint.
 */
object MoneyInput {

    /**
     * Coerce arbitrary input to at most one decimal point and at most two
     * fractional digits. Non-digit, non-dot characters are dropped. A leading
     * dot is kept (".5") so mid-typing isn't fought; empty stays empty so the
     * field can be cleared.
     *
     * First dot is kept, later dots are dropped, fractional part capped at 2:
     *   "9.959.95" -> "9.95"   (dot at index 1; "95" fills the cap; rest dropped)
     *   "9.9.95"   -> "9.99"   (first dot kept, "9" then "9"; later dot dropped)
     *   "abc9.9x5" -> "9.95"   (letters dropped)
     *   "12."      -> "12."    (trailing dot allowed while typing)
     *   ""         -> ""       (clearable)
     */
    fun sanitize(raw: String): String {
        val sb = StringBuilder()
        var dotSeen = false
        var decimals = 0
        for (c in raw) {
            when {
                c.isDigit() -> {
                    if (dotSeen) {
                        if (decimals < 2) {
                            sb.append(c)
                            decimals++
                        }
                        // else: past 2 dp — drop extra fractional digits
                    } else {
                        sb.append(c)
                    }
                }
                c == '.' && !dotSeen -> {
                    dotSeen = true
                    sb.append(c)
                }
                // second '.' or any other char -> dropped
            }
        }
        return sb.toString()
    }
}
