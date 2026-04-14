// Copyright (C) 2026 openlawsvpn contributors. All rights reserved.
package com.openlawsvpn.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SamlCallbackServer pure-Kotlin helpers.
 * These tests run on the JVM (no Android runtime needed).
 */
class SamlCallbackServerTest {

    // ── normalizeBase64 ───────────────────────────────────────────────────────

    @Test
    fun normalizeBase64_spaceConvertedToPlus() {
        // URLDecoder converts '+' → ' ' in form data; normalize must convert ' ' back to '+'
        assertEquals("AB+CD", SamlCallbackServer.normalizeBase64("AB CD"))
    }

    @Test
    fun normalizeBase64_urlSafeAlphabetConverted() {
        // URL-safe base64: '-' → '+', '_' → '/'
        assertEquals("AB+/", SamlCallbackServer.normalizeBase64("AB-_"))
    }

    @Test
    fun normalizeBase64_stripsNewlines() {
        assertEquals("ABCD", SamlCallbackServer.normalizeBase64("AB\nCD\r"))
    }

    @Test
    fun normalizeBase64_stripsPaddingThenReAdds() {
        // Existing padding is stripped (not base64), then re-added
        val result = SamlCallbackServer.normalizeBase64("ABCDE=")
        assertEquals(0, result.length % 4)
    }

    @Test
    fun normalizeBase64_lengthMultipleOf4() {
        for (len in 1..7) {
            val input = "A".repeat(len)
            val result = SamlCallbackServer.normalizeBase64(input)
            assertTrue("length=${result.length} not multiple of 4 for input len=$len",
                result.length % 4 == 0)
        }
    }

    @Test
    fun normalizeBase64_validBase64Preserved() {
        val valid = "ABCD1234+/"
        // Standard base64 chars pass through unchanged (minus existing padding)
        val result = SamlCallbackServer.normalizeBase64(valid)
        assertTrue(result.startsWith("ABCD1234+/"))
        assertEquals(0, result.length % 4)
    }

    // ── samlResponseAgeMinutes ────────────────────────────────────────────────
    // NOTE: samlResponseAgeMinutes uses android.util.Base64.
    // Tests for that method require a Robolectric runner; they live in
    // SamlCallbackServerRobolectricTest (not yet added — see prerelease checklist).

    @Test
    fun normalizeBase64_emptyInputReturnsPaddedEmpty() {
        // Empty input should round-trip to empty (length already 0, which is % 4 == 0)
        val result = SamlCallbackServer.normalizeBase64("")
        assertEquals(0, result.length % 4)
    }
}
