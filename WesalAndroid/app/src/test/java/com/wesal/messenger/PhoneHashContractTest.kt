package com.wesal.messenger

import com.wesal.messenger.core.util.PhoneOtpLogic
import com.wesal.messenger.core.util.PhoneUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 6 contract tests pinning the client to the Supabase backend
 * (supabase/functions/_shared/phone.ts):
 *
 * - Backend `sha256Hex` = SHA-256 over the UTF-8 bytes of the canonical
 *   E.164 string, lowercase hex (TextEncoder + toString(16).padStart).
 *   Client [PhoneUtils.sha256Hash] = MessageDigest SHA-256 over UTF-8,
 *   "%02x" — the pinned digests below were produced by an independent
 *   SHA-256 implementation (coreutils sha256sum) over the exact E.164
 *   strings, so a drift on EITHER side (charset, hex case, hashed string)
 *   fails here.
 * - Backend `normalizePhoneE164` maps an Egyptian local number
 *   (0-leading, 11 digits) to "+20" + national digits; the client's
 *   candidate generation must contain that same canonical form, or
 *   contacts discovery can never match a registry row.
 * - The gate's mirror-evidence rule and the verify prefill fallback.
 */
class PhoneHashContractTest {

    @Test
    fun sha256MatchesBackendAlgorithmForKnownE164() {
        // sha256("+201122072195") — independent reference digest.
        assertEquals(
            "0a3f20cf39b694c7a3da6757c88993657fe0edd698f3464ff370dc4adc7bcee7",
            PhoneUtils.sha256Hash("+201122072195")
        )
        // sha256("+201027503464") — the owner's second number, canonical form.
        assertEquals(
            "a320b51c13958ab8c87fb94194d12c035e5f1411e139d691aac015429bebdd0e",
            PhoneUtils.sha256Hash("+201027503464")
        )
    }

    @Test
    fun localEgyptianNumberCandidatesContainTheBackendCanonicalForm() {
        val candidates = PhoneUtils.normalizePhoneCandidates("01027503464")
        assertTrue(candidates.contains("+201027503464"))
        // And the hash the lookup sends for that contact is the registry hash.
        assertEquals(
            "a320b51c13958ab8c87fb94194d12c035e5f1411e139d691aac015429bebdd0e",
            PhoneUtils.sha256Hash(candidates.first { it == "+201027503464" })
        )
    }

    @Test
    fun digitsOnlyFormNormalizesToSameE164AsBackend() {
        // phoneDigits-style stored value "201122072195" (no '+', no trunk 0):
        // backend normalizePhoneE164 → "+201122072195"; client must agree.
        assertEquals("+201122072195", PhoneUtils.normalizeToE164("201122072195"))
        assertEquals("+201122072195", PhoneUtils.normalizeToE164("01122072195"))
        assertEquals("+201122072195", PhoneUtils.normalizeToE164("+201122072195"))
    }

    @Test
    fun completeMirrorRequiresFlagNumberAndHash() {
        assertTrue(
            PhoneOtpLogic.isCompleteVerificationMirror(
                true, "+201122072195",
                "0a3f20cf39b694c7a3da6757c88993657fe0edd698f3464ff370dc4adc7bcee7"
            )
        )
        // The production owner doc shape: flag set, number AND hash blank.
        assertFalse(PhoneOtpLogic.isCompleteVerificationMirror(true, "", ""))
        assertFalse(PhoneOtpLogic.isCompleteVerificationMirror(true, "+201122072195", ""))
        assertFalse(PhoneOtpLogic.isCompleteVerificationMirror(true, "", "abc123"))
        assertFalse(PhoneOtpLogic.isCompleteVerificationMirror(false, "+201122072195", "abc123"))
        assertFalse(PhoneOtpLogic.isCompleteVerificationMirror(true, null, null))
    }

    @Test
    fun prefillFallsBackToPhoneDigitsWhenNumberBlank() {
        assertEquals(
            "+201122072195",
            PhoneOtpLogic.verificationPrefillE164("", "201122072195")
        )
        assertEquals(
            "+201122072195",
            PhoneOtpLogic.verificationPrefillE164("01122072195", "")
        )
        assertEquals(
            "+201122072195",
            PhoneOtpLogic.verificationPrefillE164("+201122072195", "201027503464")
        )
        assertNull(PhoneOtpLogic.verificationPrefillE164("", ""))
        assertNull(PhoneOtpLogic.verificationPrefillE164(null, null))
    }
}
