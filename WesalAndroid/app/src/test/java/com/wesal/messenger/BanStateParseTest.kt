package com.wesal.messenger

import com.google.firebase.Timestamp
import com.wesal.messenger.data.repository.coerceBanEpochMillis
import com.wesal.messenger.data.repository.isBanClearlyActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * Round-4 regression tests for the tolerant ban parse used by
 * DocumentSnapshot.toWesalUser (contacts M2). The previous parse read
 * banExpiresAt with getLong() only, so a legacy doc whose expiry was missing or
 * stored as a Timestamp/String degraded to 0L = "no expiry = permanent ban" —
 * and considerUserDoc then silently dropped the user from contacts. A ban now
 * counts only when it is positively established: flag set AND (PERMANENT type
 * OR a readable expiry still in the future), mirroring firestore.rules
 * isCurrentlyBanned() and ChatRepositoryImpl.isBanClearlyActive.
 */
class BanStateParseTest {

    private val now = 1_700_000_000_000L
    private val future = now + 60_000L
    private val past = now - 60_000L

    @Test
    fun notFlaggedIsNeverBanned() {
        assertFalse(isBanClearlyActive(false, "PERMANENT", null, now))
        assertFalse(isBanClearlyActive(null, "PERMANENT", null, now))
        assertFalse(isBanClearlyActive("true", "PERMANENT", null, now))
    }

    @Test
    fun permanentBanCountsRegardlessOfExpiry() {
        assertTrue(isBanClearlyActive(true, "PERMANENT", null, now))
        assertTrue(isBanClearlyActive(true, "PERMANENT", past, now))
        assertTrue(isBanClearlyActive(true, "permanent", future, now))
    }

    @Test
    fun temporaryBanWithNumericExpiry() {
        assertTrue(isBanClearlyActive(true, "TEMPORARY", future, now))
        assertFalse(isBanClearlyActive(true, "TEMPORARY", past, now))
    }

    @Test
    fun temporaryBanWithMissingExpiryIsNotBanned() {
        // The M2 regression case: old parse treated a missing expiry as permanent.
        assertFalse(isBanClearlyActive(true, "TEMPORARY", null, now))
        assertFalse(isBanClearlyActive(true, "", null, now))
        assertFalse(isBanClearlyActive(true, null, null, now))
    }

    @Test
    fun temporaryBanWithTimestampExpiryIsTypeTolerant() {
        assertTrue(isBanClearlyActive(true, "TEMPORARY", Timestamp(Date(future)), now))
        // Old parse: getLong() on a Timestamp yields null -> 0L -> permanent ban.
        assertFalse(isBanClearlyActive(true, "TEMPORARY", Timestamp(Date(past)), now))
    }

    @Test
    fun temporaryBanWithStringExpiryIsTypeTolerant() {
        assertTrue(isBanClearlyActive(true, "TEMPORARY", future.toString(), now))
        assertFalse(isBanClearlyActive(true, "TEMPORARY", past.toString(), now))
        assertFalse(isBanClearlyActive(true, "TEMPORARY", "not-a-number", now))
        assertFalse(isBanClearlyActive(true, "TEMPORARY", "", now))
    }

    @Test
    fun temporaryBanWithDateExpiry() {
        assertTrue(isBanClearlyActive(true, "TEMPORARY", Date(future), now))
        assertFalse(isBanClearlyActive(true, "TEMPORARY", Date(past), now))
    }

    @Test
    fun coerceEpochMillisReadsEveryStoredType() {
        assertEquals(future, coerceBanEpochMillis(future))
        assertEquals(42L, coerceBanEpochMillis(42))
        assertEquals(future, coerceBanEpochMillis(Timestamp(Date(future))))
        assertEquals(future, coerceBanEpochMillis(Date(future)))
        assertEquals(future, coerceBanEpochMillis("  $future "))
        assertNull(coerceBanEpochMillis(null))
        assertNull(coerceBanEpochMillis(""))
        assertNull(coerceBanEpochMillis("soon"))
        assertNull(coerceBanEpochMillis(true))
        assertNull(coerceBanEpochMillis(emptyMap<String, Any>()))
    }
}
