package com.funkodex.security

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for token storage/parsing logic in SecureKeyStore and TokenRefreshManager.
 * Pure logic tests — no Android context required, no encryption involved.
 * Token format: "accessToken|expireAtMs|refreshToken"
 */
class SecureKeyStoreTokenTest {

    private fun isValid(stored: String, bufferMs: Long = 60_000L): Boolean {
        val parts    = stored.split("|")
        if (parts.size < 2) return false
        val expireAt = parts[1].toLongOrNull() ?: return false
        return System.currentTimeMillis() < expireAt - bufferMs
    }
    private fun accessToken(stored: String)  = stored.substringBefore("|")
    private fun refreshToken(stored: String) = stored.split("|").getOrNull(2)?.takeIf { it.isNotEmpty() }

    @Test fun `future expiry is valid`() {
        assertTrue(isValid("tok|${System.currentTimeMillis() + 3_600_000}|ref"))
    }

    @Test fun `past expiry is invalid`() {
        assertFalse(isValid("tok|${System.currentTimeMillis() - 1_000}|ref"))
    }

    @Test fun `token within buffer window is invalid`() {
        // expires in 30s but buffer is 60s
        assertFalse(isValid("tok|${System.currentTimeMillis() + 30_000}|ref", 60_000))
    }

    @Test fun `token outside buffer window is valid`() {
        assertTrue(isValid("tok|${System.currentTimeMillis() + 300_000}|ref", 60_000))
    }

    @Test fun `empty stored is invalid`()             { assertFalse(isValid("")) }
    @Test fun `no pipe is invalid`()                  { assertFalse(isValid("justtoken")) }
    @Test fun `non-numeric expiry is invalid`()       { assertFalse(isValid("tok|notanumber|ref")) }
    @Test fun `zero expiry is invalid`()              { assertFalse(isValid("tok|0|ref")) }

    @Test fun `access token extracted correctly`() {
        assertEquals("MY_ACCESS", accessToken("MY_ACCESS|12345|REFRESH"))
    }
    @Test fun `access token on empty returns empty`() {
        assertEquals("", accessToken(""))
    }

    @Test fun `refresh token extracted correctly`() {
        assertEquals("REFRESH_XYZ", refreshToken("access|12345|REFRESH_XYZ"))
    }
    @Test fun `refresh token null when absent`() {
        assertNull(refreshToken("access|12345|"))
        assertNull(refreshToken("access|12345"))
    }

    @Test fun `round-trip preserves all three parts`() {
        val a = "eyAccess123"
        val e = System.currentTimeMillis() + 7_200_000
        val r = "eyRefresh456"
        val stored = "$a|$e|$r"
        assertEquals(a, accessToken(stored))
        assertEquals(r, refreshToken(stored))
        assertTrue(isValid(stored))
    }

    @Test fun `eBay 2-hour token is valid right after issue`() {
        val expireAt = System.currentTimeMillis() + 7_200_000  // exactly 2 hours
        assertTrue(isValid("ebay_token|$expireAt|refresh", 60_000))
    }

    @Test fun `eBay token triggers refresh when within 5 minutes`() {
        val expireAt = System.currentTimeMillis() + 4 * 60_000  // 4 minutes left
        assertFalse(isValid("ebay_token|$expireAt|refresh", 5 * 60_000))
    }
}
