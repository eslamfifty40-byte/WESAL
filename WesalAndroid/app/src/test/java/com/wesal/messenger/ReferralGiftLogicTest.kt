package com.wesal.messenger

import com.wesal.messenger.core.util.ReferralGiftLogic
import com.wesal.messenger.core.util.WelcomeGiftLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Referral invitee gift (2026-11-27): the upgraded welcome package for
 * referred signups — frame + gift verification badge for 24 hours —
 * decided by [ReferralGiftLogic.plan]. The non-referred path must stay
 * byte-equivalent to the standard welcome gift (WelcomeGiftLogic).
 */
class ReferralGiftLogicTest {

    private fun plan(
        welcomeGiftGrantedAt: Long = 0L,
        profileComplete: Boolean = true,
        phoneVerified: Boolean = true,
        referred: Boolean,
        giftFrameActive: Boolean = false,
        giftBadgeActive: Boolean = false
    ) = ReferralGiftLogic.plan(
        welcomeGiftGrantedAt = welcomeGiftGrantedAt,
        profileComplete = profileComplete,
        phoneVerified = phoneVerified,
        referred = referred,
        giftFrameActive = giftFrameActive,
        giftBadgeActive = giftBadgeActive
    )

    // ---------------------------------------------------------------
    // Referred: the full package
    // ---------------------------------------------------------------

    @Test
    fun `referred - both slots free grants frame and badge for 24 hours`() {
        val p = plan(referred = true)
        assertTrue(p.grantFrame)
        assertTrue(p.grantBadge)
        assertEquals(ReferralGiftLogic.DURATION_MS, p.frameDurationMs)
        assertEquals(86_400_000L, ReferralGiftLogic.DURATION_MS)
        assertFalse(p.stampOnly)
        assertEquals(ReferralGiftLogic.Outcome.REFERRAL_PACKAGE, p.outcome)
    }

    @Test
    fun `referred - admin frame gift live grants the badge only`() {
        val p = plan(referred = true, giftFrameActive = true)
        assertFalse(p.grantFrame)
        assertTrue(p.grantBadge)
        assertFalse(p.stampOnly)
        assertEquals(ReferralGiftLogic.Outcome.REFERRAL_BADGE, p.outcome)
    }

    @Test
    fun `referred - admin badge gift live grants the 24h frame only`() {
        val p = plan(referred = true, giftBadgeActive = true)
        assertTrue(p.grantFrame)
        assertFalse(p.grantBadge)
        assertEquals(ReferralGiftLogic.DURATION_MS, p.frameDurationMs)
        assertFalse(p.stampOnly)
        assertEquals(ReferralGiftLogic.Outcome.REFERRAL_FRAME, p.outcome)
    }

    @Test
    fun `referred - both slots busy is mark-only, exactly once`() {
        val p = plan(referred = true, giftFrameActive = true, giftBadgeActive = true)
        assertFalse(p.grantFrame)
        assertFalse(p.grantBadge)
        assertTrue(p.stampOnly)
        assertEquals(ReferralGiftLogic.Outcome.NONE, p.outcome)
    }

    @Test
    fun `referred - never re-fires after the once-ever stamp`() {
        val p = plan(welcomeGiftGrantedAt = 123L, referred = true)
        assertEquals(ReferralGiftLogic.Plan.NONE, p)
    }

    @Test
    fun `referred - waits for full registration like the standard gift`() {
        assertEquals(
            ReferralGiftLogic.Plan.NONE,
            plan(profileComplete = false, referred = true)
        )
        assertEquals(
            ReferralGiftLogic.Plan.NONE,
            plan(phoneVerified = false, referred = true)
        )
    }

    // ---------------------------------------------------------------
    // Non-referred: unchanged standard welcome gift
    // ---------------------------------------------------------------

    @Test
    fun `non-referred - grants the standard 1h frame only`() {
        val p = plan(referred = false)
        assertTrue(p.grantFrame)
        assertFalse(p.grantBadge)
        assertEquals(WelcomeGiftLogic.DURATION_MS, p.frameDurationMs)
        assertEquals(3_600_000L, p.frameDurationMs)
        assertFalse(p.stampOnly)
        assertEquals(ReferralGiftLogic.Outcome.STANDARD_FRAME, p.outcome)
    }

    @Test
    fun `non-referred - an active badge gift does not change the standard path`() {
        // The standard gift has no badge component: a busy badge slot is
        // irrelevant, the 1h frame still grants.
        val p = plan(referred = false, giftBadgeActive = true)
        assertTrue(p.grantFrame)
        assertFalse(p.grantBadge)
        assertEquals(ReferralGiftLogic.Outcome.STANDARD_FRAME, p.outcome)
    }

    @Test
    fun `non-referred - admin frame gift live is mark-only`() {
        val p = plan(referred = false, giftFrameActive = true)
        assertFalse(p.grantFrame)
        assertFalse(p.grantBadge)
        assertTrue(p.stampOnly)
        assertEquals(ReferralGiftLogic.Outcome.NONE, p.outcome)
    }

    @Test
    fun `non-referred - already granted and not-yet-eligible stay silent`() {
        assertEquals(ReferralGiftLogic.Plan.NONE, plan(welcomeGiftGrantedAt = 9L, referred = false))
        assertEquals(ReferralGiftLogic.Plan.NONE, plan(profileComplete = false, referred = false))
        assertEquals(ReferralGiftLogic.Plan.NONE, plan(phoneVerified = false, referred = false))
    }

    // ---------------------------------------------------------------
    // Local invite evidence (pre-stamp sequencing safety net)
    // ---------------------------------------------------------------

    @Test
    fun `local evidence - blank or own ids are not evidence`() {
        assertFalse(ReferralGiftLogic.isLocalInviteEvidence(null, "uid1", "eslamahmed", "10002"))
        assertFalse(ReferralGiftLogic.isLocalInviteEvidence("", "uid1", "eslamahmed", "10002"))
        assertFalse(ReferralGiftLogic.isLocalInviteEvidence("  ", "uid1", "eslamahmed", "10002"))
        // Own uid (deep link to my own profile).
        assertFalse(ReferralGiftLogic.isLocalInviteEvidence("uid1", "uid1", "eslamahmed", "10002"))
        // Own numeric id, plain and #-prefixed forms.
        assertFalse(ReferralGiftLogic.isLocalInviteEvidence("10002", "uid1", "eslamahmed", "10002"))
        assertFalse(ReferralGiftLogic.isLocalInviteEvidence("#10002", "uid1", "eslamahmed", "10002"))
        // Own @handle, case- and @-insensitive.
        assertFalse(ReferralGiftLogic.isLocalInviteEvidence("eslamahmed", "uid1", "eslamahmed", "10002"))
        assertFalse(ReferralGiftLogic.isLocalInviteEvidence("@EslamAhmed", "uid1", "eslamahmed", "10002"))
    }

    @Test
    fun `local evidence - someone else's id is evidence in every form`() {
        assertTrue(ReferralGiftLogic.isLocalInviteEvidence("otheruid", "uid1", "eslamahmed", "10002"))
        assertTrue(ReferralGiftLogic.isLocalInviteEvidence("10001", "uid1", "eslamahmed", "10002"))
        assertTrue(ReferralGiftLogic.isLocalInviteEvidence("@wesaladmin", "uid1", "eslamahmed", "10002"))
    }

    // ---------------------------------------------------------------
    // Announcements
    // ---------------------------------------------------------------

    @Test
    fun `announcement - referred outcomes promise exactly 24 hours of what was granted`() {
        val pkgAr = ReferralGiftLogic.announcement(ReferralGiftLogic.Outcome.REFERRAL_PACKAGE, true)
        assertTrue(pkgAr!!.contains("٢٤ ساعة") && pkgAr.contains("إطار") && pkgAr.contains("توثيق"))
        val pkgEn = ReferralGiftLogic.announcement(ReferralGiftLogic.Outcome.REFERRAL_PACKAGE, false)
        assertTrue(pkgEn!!.contains("24 hours") && pkgEn.contains("frame") && pkgEn.contains("badge"))

        val frameAr = ReferralGiftLogic.announcement(ReferralGiftLogic.Outcome.REFERRAL_FRAME, true)
        assertTrue(frameAr!!.contains("٢٤ ساعة") && frameAr.contains("إطار"))
        val badgeEn = ReferralGiftLogic.announcement(ReferralGiftLogic.Outcome.REFERRAL_BADGE, false)
        assertTrue(badgeEn!!.contains("24 hours") && badgeEn.contains("badge"))
    }

    @Test
    fun `announcement - standard path keeps WelcomeGiftLogic copy (null here)`() {
        assertNull(ReferralGiftLogic.announcement(ReferralGiftLogic.Outcome.STANDARD_FRAME, true))
        assertNull(ReferralGiftLogic.announcement(ReferralGiftLogic.Outcome.NONE, false))
    }
}
