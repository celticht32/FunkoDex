package com.funkodex.auth

import org.junit.Assert.*
import org.junit.Test

/**
 * PkceHelperTest — verifies RFC 7636 PKCE implementation.
 *
 * Security relevance: the PKCE code_verifier and code_challenge are the
 * core of OAuth security for mobile apps. These tests enforce the
 * cryptographic properties required by the spec so a future refactor
 * cannot accidentally break the security model.
 */
class PkceHelperTest {

    @Test
    fun `generateVerifier produces base64url-safe characters only`() {
        val verifier = PkceHelper.generateVerifier()
        assertTrue("Verifier must only contain base64url chars (no + / =)",
            verifier.all { it.isLetterOrDigit() || it in "-_." })
    }

    @Test
    fun `generateVerifier is 86 chars for 64 source bytes`() {
        // 64 bytes base64url without padding = ceil(64 * 4/3) = 86 chars
        val verifier = PkceHelper.generateVerifier()
        assertEquals(86, verifier.length)
    }

    @Test
    fun `generateVerifier produces unique values each call`() {
        val verifiers = (1..10).map { PkceHelper.generateVerifier() }.toSet()
        assertEquals("All 10 verifiers should be unique", 10, verifiers.size)
    }

    @Test
    fun `generateVerifier contains no padding or non-url-safe chars`() {
        repeat(20) {
            val v = PkceHelper.generateVerifier()
            assertFalse('=' in v)
            assertFalse('+' in v)
            assertFalse('/' in v)
        }
    }

    @Test
    fun `challenge is deterministic for same verifier`() {
        val verifier = PkceHelper.generateVerifier()
        assertEquals(PkceHelper.challenge(verifier), PkceHelper.challenge(verifier))
    }

    @Test
    fun `challenge is 43 chars for SHA-256 output`() {
        // SHA-256 → 32 bytes → base64url no-padding → 43 chars
        assertEquals(43, PkceHelper.challenge(PkceHelper.generateVerifier()).length)
    }

    @Test
    fun `challenge contains no padding or non-url-safe chars`() {
        val challenge = PkceHelper.challenge(PkceHelper.generateVerifier())
        assertFalse('=' in challenge)
        assertFalse('+' in challenge)
        assertFalse('/' in challenge)
    }

    @Test
    fun `challenge matches RFC 7636 Appendix B test vector`() {
        // Official test vector from RFC 7636 Appendix B
        // https://www.rfc-editor.org/rfc/rfc7636#appendix-B
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expected = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        assertEquals(
            "Must match RFC 7636 Appendix B — this is the canonical PKCE test vector",
            expected,
            PkceHelper.challenge(verifier)
        )
    }

    @Test
    fun `different verifiers produce different challenges`() {
        val challenges = (1..10).map { PkceHelper.challenge(PkceHelper.generateVerifier()) }.toSet()
        assertEquals("All challenges should be unique", 10, challenges.size)
    }
}
