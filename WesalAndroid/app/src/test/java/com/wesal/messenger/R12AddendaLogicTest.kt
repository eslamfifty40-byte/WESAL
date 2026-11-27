package com.wesal.messenger

import com.wesal.messenger.core.util.AlphaBounds
import com.wesal.messenger.core.util.DirectChatRequestLogic
import com.wesal.messenger.core.util.FramedCutoutLayout
import com.wesal.messenger.core.util.GiftGrantLogic
import com.wesal.messenger.core.util.OfficialChatWelcome
import com.wesal.messenger.core.util.PhoneDiscoveryLogic
import com.wesal.messenger.core.util.SendInFlightGuard
import com.wesal.messenger.core.util.UserSearchLogic
import com.wesal.messenger.core.util.WelcomeGiftLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Round-12 ADDENDA pure-logic tests (addenda 3, 5, 6, 8, 4):
 * phone-hash discovery acceptance, framed-photo width fill, gift
 * snapshot/restore decisions + notification text, numeric-ID search
 * planning, and the official-chat welcome text.
 */
class R12AddendaLogicTest {

    // ---------------------------------------------------------------
    // Addendum 3: phone-hash discovery acceptance
    // ---------------------------------------------------------------

    @Test
    fun `discovery - admin viewer 10001 includes target 10002`() {
        // The owner's exact case: viewer 10001, target 10002 — different
        // uids, registry-proven (requireVerified = false).
        assertTrue(
            PhoneDiscoveryLogic.acceptsTarget(
                isSelf = false,
                requireVerified = false,
                isPhoneVerified = false,
                isBanned = false,
                deletedAt = 0L
            )
        )
        // And via the legacy Firestore-stamp source with a verified stamp.
        assertTrue(
            PhoneDiscoveryLogic.acceptsTarget(
                isSelf = false,
                requireVerified = true,
                isPhoneVerified = true,
                isBanned = false,
                deletedAt = 0L
            )
        )
    }

    @Test
    fun `discovery - viewer excludes only self`() {
        assertFalse(
            PhoneDiscoveryLogic.acceptsTarget(
                isSelf = true,
                requireVerified = false,
                isPhoneVerified = true,
                isBanned = false,
                deletedAt = 0L
            )
        )
    }

    @Test
    fun `discovery - banned and deleted targets excluded`() {
        assertFalse(
            PhoneDiscoveryLogic.acceptsTarget(
                isSelf = false, requireVerified = false, isPhoneVerified = true,
                isBanned = true, deletedAt = 0L
            )
        )
        assertFalse(
            PhoneDiscoveryLogic.acceptsTarget(
                isSelf = false, requireVerified = false, isPhoneVerified = true,
                isBanned = false, deletedAt = 123L
            )
        )
    }

    @Test
    fun `discovery - legacy source requires the verified stamp`() {
        assertFalse(
            PhoneDiscoveryLogic.acceptsTarget(
                isSelf = false, requireVerified = true, isPhoneVerified = false,
                isBanned = false, deletedAt = 0L
            )
        )
    }

    // ---------------------------------------------------------------
    // Addendum 8: numeric-ID search planning
    // ---------------------------------------------------------------

    @Test
    fun `search - full range IDs plan an exact lookup`() {
        // Saad's real ID — above the old spec-search cap of 100000.
        assertEquals("789671", UserSearchLogic.planNumericIdQuery("789671"))
        assertEquals("100000", UserSearchLogic.planNumericIdQuery("100000"))
        assertEquals("1000000", UserSearchLogic.planNumericIdQuery("1000000"))
        assertEquals("10002", UserSearchLogic.planNumericIdQuery("10002"))
        // A 5-digit input is a complete valid ID in its own right
        // (the range starts at 10000), not a "partial" of a longer one.
        assertEquals("78967", UserSearchLogic.planNumericIdQuery("78967"))
    }

    @Test
    fun `search - decorations and Arabic digits are normalized`() {
        assertEquals("789671", UserSearchLogic.planNumericIdQuery("#789671"))
        assertEquals("789671", UserSearchLogic.planNumericIdQuery("ID: 789671"))
        assertEquals("789671", UserSearchLogic.planNumericIdQuery("٧٨٩٦٧١"))
    }

    @Test
    fun `search - partial IDs and non-IDs plan nothing`() {
        assertNull(UserSearchLogic.planNumericIdQuery("7896"))
        assertNull(UserSearchLogic.planNumericIdQuery("7896710"))
        assertNull(UserSearchLogic.planNumericIdQuery("saad"))
        assertNull(UserSearchLogic.planNumericIdQuery(""))
        assertNull(UserSearchLogic.planNumericIdQuery("1001"))
    }

    // ---------------------------------------------------------------
    // Addendum 5: framed cutout width fill
    // ---------------------------------------------------------------

    private fun transformFor(bounds: AlphaBounds) = FramedCutoutLayout.compute(
        containerPx = 1000f,
        apertureCenterYFraction = 0.396f,
        apertureDiameterFraction = 0.496f,
        bounds = bounds,
        imageWidthPx = 1000,
        imageHeightPx = 1000
    )

    // Aperture: D = 496, top = 148, bottom = 644 (container 1000).

    @Test
    fun `frame - narrow bust fills the width and keeps the head clearance`() {
        // The owner's avatar shape: box 0.62 wide x 0.94 tall.
        val t = transformFor(AlphaBounds(0.19f, 0.03f, 0.81f, 0.97f, 0.4f))
        val padded = AlphaBounds(0.19f, 0.03f, 0.81f, 0.97f, 0.4f)
            .padded(FramedCutoutLayout.DEFAULT_PAD_FRACTION)
        val boxW = padded.width * 1000f * t.scale
        val boxTop = t.offsetYPx + padded.top * t.drawHeightPx
        val boxBottom = t.offsetYPx + padded.bottom * t.drawHeightPx
        // Width now fills the aperture (was ~0.81x with the height-only fit).
        assertTrue("box width $boxW must reach ~1.02x aperture", boxW >= 1.0f * 496f)
        // Head clearance stays at the reference 0.03x aperture.
        assertTrue(
            "clearance ${(boxTop - 148f) / 496f}",
            abs((boxTop - 148f) / 496f - 0.03f) < 0.005f
        )
        // Bottom overflows at least the reference 0.25x behind the plaque.
        assertTrue("overflow ${(boxBottom - 644f) / 496f}", (boxBottom - 644f) / 496f >= 0.25f)
    }

    @Test
    fun `frame - wide figure keeps the height-fit geometry unchanged`() {
        val t = transformFor(AlphaBounds(0.02f, 0.05f, 0.98f, 0.95f, 0.1f))
        val padded = AlphaBounds(0.02f, 0.05f, 0.98f, 0.95f, 0.1f)
            .padded(FramedCutoutLayout.DEFAULT_PAD_FRACTION)
        val boxTop = t.offsetYPx + padded.top * t.drawHeightPx
        val boxBottom = t.offsetYPx + padded.bottom * t.drawHeightPx
        // Height-fit identity: top at +0.03 clearance, bottom at +0.25 overflow.
        assertTrue(abs((boxTop - 148f) / 496f - 0.03f) < 0.005f)
        assertTrue(abs((boxBottom - 644f) / 496f - 0.25f) < 0.005f)
        // And never past the width cap.
        assertTrue(padded.width * 1000f * t.scale <= 1.35f * 496f + 0.5f)
    }

    @Test
    fun `frame - width boost is capped for a very narrow figure`() {
        val bounds = AlphaBounds(0.40f, 0.05f, 0.60f, 0.95f, 0.7f)
        val t = transformFor(bounds)
        val padded = bounds.padded(FramedCutoutLayout.DEFAULT_PAD_FRACTION)
        val heightScale = (1.22f * 496f) / (padded.height * 1000f)
        assertTrue(
            "scale ${t.scale} must not exceed height-fit x ${FramedCutoutLayout.MAX_WIDTH_FILL_BOOST}",
            t.scale <= heightScale * FramedCutoutLayout.MAX_WIDTH_FILL_BOOST + 0.001f
        )
        // Even capped, the head clearance anchor holds.
        val boxTop = t.offsetYPx + padded.top * t.drawHeightPx
        assertTrue(abs((boxTop - 148f) / 496f - 0.03f) < 0.005f)
    }

    // ---------------------------------------------------------------
    // Addendum 6: gift snapshot / restore decisions
    // ---------------------------------------------------------------

    @Test
    fun `gift - snapshot only on a new timed grant`() {
        assertTrue(GiftGrantLogic.shouldSnapshot(giftAlreadyActive = false, timed = true, isGrant = true))
        assertFalse(GiftGrantLogic.shouldSnapshot(giftAlreadyActive = true, timed = true, isGrant = true))
        assertFalse(GiftGrantLogic.shouldSnapshot(giftAlreadyActive = false, timed = false, isGrant = true))
        assertFalse(GiftGrantLogic.shouldSnapshot(giftAlreadyActive = false, timed = true, isGrant = false))
    }

    @Test
    fun `gift - permanent grant and revoke clear the snapshot`() {
        assertTrue(GiftGrantLogic.shouldClearSnapshot(timed = false, isGrant = true))
        assertTrue(GiftGrantLogic.shouldClearSnapshot(timed = true, isGrant = false))
        assertFalse(GiftGrantLogic.shouldClearSnapshot(timed = true, isGrant = true))
    }

    @Test
    fun `gift - expiry restores only an active expired gift`() {
        val now = 1_000_000L
        // Had something before -> restore (decision is RESTORE regardless
        // of the snapshot contents; contents decide what renders).
        assertEquals(
            GiftGrantLogic.ExpiryAction.RESTORE,
            GiftGrantLogic.expiryAction(giftActive = true, giftExpiresAt = now - 1, nowMs = now)
        )
        // Not yet expired -> untouched.
        assertEquals(
            GiftGrantLogic.ExpiryAction.NONE,
            GiftGrantLogic.expiryAction(giftActive = true, giftExpiresAt = now + 60_000, nowMs = now)
        )
        // No gift in force -> untouched even with a past timestamp.
        assertEquals(
            GiftGrantLogic.ExpiryAction.NONE,
            GiftGrantLogic.expiryAction(giftActive = false, giftExpiresAt = now - 1, nowMs = now)
        )
        // Permanent (no expiry) -> untouched.
        assertEquals(
            GiftGrantLogic.ExpiryAction.NONE,
            GiftGrantLogic.expiryAction(giftActive = true, giftExpiresAt = 0L, nowMs = now)
        )
        assertTrue(GiftGrantLogic.badgeGiftExpired(true, now - 5, now))
        assertFalse(GiftGrantLogic.frameGiftExpired(true, now + 5, now))
    }

    @Test
    fun `gift - message composes badge frame duration and note`() {
        val text = GiftGrantLogic.giftMessageText(
            badgeLabel = "توثيق (الشارة الزرقاء)",
            frameName = "الإطار الملكي",
            durationLabel = "ساعة",
            permanent = false,
            note = "هديه تسجيل"
        )
        assertTrue(text.contains("🎁"))
        assertTrue(text.contains("توثيق (الشارة الزرقاء)"))
        assertTrue(text.contains("إطار الإطار الملكي"))
        assertTrue(text.contains("لمدة ساعة"))
        assertTrue(text.contains("هديه تسجيل"))
    }

    @Test
    fun `gift - message for a permanent frame-only grant`() {
        val text = GiftGrantLogic.giftMessageText(
            badgeLabel = null,
            frameName = "هدية",
            durationLabel = "دائم",
            permanent = true,
            note = null
        )
        assertTrue(text.contains("إطار هدية"))
        assertTrue(text.contains("بشكل دائم"))
        assertFalse(text.contains("لمدة"))
    }

    // ---------------------------------------------------------------
    // Addendum 4: official-chat welcome
    // ---------------------------------------------------------------

    @Test
    fun `welcome - personalized with the user name`() {
        assertEquals(
            "أهلاً بيك يا سعد محمد 🌹 اكتب مشكلتك هنا وسيتم التواصل معك قريباً.",
            OfficialChatWelcome.textFor("سعد محمد")
        )
    }

    @Test
    fun `welcome - blank name falls back`() {
        assertTrue(OfficialChatWelcome.textFor("").contains("صديقنا"))
        assertTrue(OfficialChatWelcome.textFor(null).contains("صديقنا"))
        assertTrue(OfficialChatWelcome.textFor("   ").contains("صديقنا"))
    }

    // ---------------------------------------------------------------
    // Addenda 10/10b: first-contact chat birth state + sender label
    // ---------------------------------------------------------------

    @Test
    fun `direct chat - fresh pair with no request is born PENDING`() {
        assertEquals(
            DirectChatRequestLogic.InitialState.PENDING,
            DirectChatRequestLogic.initialState(
                isSupportTarget = false,
                callerIsSuperAdmin = false,
                contactRequestStatus = null
            )
        )
        assertEquals(
            DirectChatRequestLogic.InitialState.PENDING,
            DirectChatRequestLogic.initialState(false, false, "PENDING")
        )
        assertEquals(
            DirectChatRequestLogic.InitialState.PENDING,
            DirectChatRequestLogic.initialState(false, false, "HELD")
        )
    }

    @Test
    fun `direct chat - accepted request, support target and super admin open directly`() {
        assertEquals(
            DirectChatRequestLogic.InitialState.ACCEPTED,
            DirectChatRequestLogic.initialState(false, false, "ACCEPTED")
        )
        assertEquals(
            DirectChatRequestLogic.InitialState.ACCEPTED,
            DirectChatRequestLogic.initialState(true, false, null)
        )
        assertEquals(
            DirectChatRequestLogic.InitialState.ACCEPTED,
            DirectChatRequestLogic.initialState(false, true, "REJECTED")
        )
    }

    @Test
    fun `direct chat - a rejected request is respected`() {
        assertEquals(
            DirectChatRequestLogic.InitialState.REJECTED,
            DirectChatRequestLogic.initialState(false, false, "REJECTED")
        )
    }

    @Test
    fun `direct chat - sender pending label only for the requester in a pending chat`() {
        assertTrue(
            DirectChatRequestLogic.senderPendingLabelVisible("PENDING", "uid_me", "uid_me")
        )
        // The recipient never sees the sender label (he gets the
        // accept/hold/reject banner instead).
        assertFalse(
            DirectChatRequestLogic.senderPendingLabelVisible("PENDING", "uid_other", "uid_me")
        )
        assertFalse(
            DirectChatRequestLogic.senderPendingLabelVisible("ACCEPTED", "uid_me", "uid_me")
        )
        assertFalse(
            DirectChatRequestLogic.senderPendingLabelVisible("", "", "uid_me")
        )
        assertFalse(
            DirectChatRequestLogic.senderPendingLabelVisible("PENDING", null, "uid_me")
        )
    }

    // ---------------------------------------------------------------
    // Addendum 10c: in-flight duplicate guard
    // ---------------------------------------------------------------

    @Test
    fun `send guard - identical message still sending inside the window is a duplicate`() {
        val now = 100_000L
        val candidates = listOf(
            SendInFlightGuard.Candidate("SENDING", "اي", null, now - 3_000L)
        )
        assertTrue(SendInFlightGuard.isDuplicateInFlight(candidates, "اي", null, now))
    }

    @Test
    fun `send guard - different text, reply target, status or stale echo is not a duplicate`() {
        val now = 100_000L
        val sending = SendInFlightGuard.Candidate("SENDING", "اي", null, now - 3_000L)
        assertFalse(
            SendInFlightGuard.isDuplicateInFlight(listOf(sending), "اهلا", null, now)
        )
        assertFalse(
            SendInFlightGuard.isDuplicateInFlight(listOf(sending), "اي", "msg_1", now)
        )
        assertFalse(
            SendInFlightGuard.isDuplicateInFlight(
                listOf(sending.copy(statusRaw = "SENT")), "اي", null, now
            )
        )
        assertFalse(
            SendInFlightGuard.isDuplicateInFlight(
                listOf(sending.copy(statusRaw = "FAILED")), "اي", null, now
            )
        )
        // Long past the window: a deliberate repeat send is allowed.
        assertFalse(
            SendInFlightGuard.isDuplicateInFlight(
                listOf(sending.copy(timestampMs = now - SendInFlightGuard.WINDOW_MS - 1)),
                "اي", null, now
            )
        )
        assertFalse(SendInFlightGuard.isDuplicateInFlight(emptyList(), "اي", null, now))
    }

    // ---------------------------------------------------------------
    // Addendum 11: welcome gift grant-once decision + interplay
    // ---------------------------------------------------------------

    @Test
    fun `welcome gift - grants once to a fully registered user with no active gift`() {
        assertEquals(
            WelcomeGiftLogic.Decision.GRANT,
            WelcomeGiftLogic.decide(
                welcomeGiftGrantedAt = 0L,
                profileComplete = true,
                phoneVerified = true,
                giftFrameActive = false
            )
        )
    }

    @Test
    fun `welcome gift - never re-fires after the stamp, and waits for full registration`() {
        assertEquals(
            WelcomeGiftLogic.Decision.SKIP,
            WelcomeGiftLogic.decide(123L, profileComplete = true, phoneVerified = true, giftFrameActive = false)
        )
        assertEquals(
            WelcomeGiftLogic.Decision.SKIP,
            WelcomeGiftLogic.decide(0L, profileComplete = false, phoneVerified = true, giftFrameActive = false)
        )
        assertEquals(
            WelcomeGiftLogic.Decision.SKIP,
            WelcomeGiftLogic.decide(0L, profileComplete = true, phoneVerified = false, giftFrameActive = false)
        )
    }

    @Test
    fun `welcome gift - an active admin gift supersedes it without stomping the snapshot`() {
        assertEquals(
            WelcomeGiftLogic.Decision.MARK_ONLY,
            WelcomeGiftLogic.decide(
                welcomeGiftGrantedAt = 0L,
                profileComplete = true,
                phoneVerified = true,
                giftFrameActive = true
            )
        )
        // The shared-slot rule from the other direction (addendum 6): an
        // admin grant landing while the welcome gift is active must NOT
        // overwrite the welcome snapshot, so expiry restores the user's
        // true pre-gift frame.
        assertFalse(
            GiftGrantLogic.shouldSnapshot(
                giftAlreadyActive = true,
                timed = true,
                isGrant = true
            )
        )
        // And while the welcome gift itself is live and unexpired, the
        // expiry decision leaves everything untouched.
        assertEquals(
            GiftGrantLogic.ExpiryAction.NONE,
            GiftGrantLogic.expiryAction(
                giftActive = true,
                giftExpiresAt = 10_000L,
                nowMs = 5_000L
            )
        )
    }

    @Test
    fun `welcome gift - announcement carries the one hour promise in both languages`() {
        assertTrue(WelcomeGiftLogic.announcement(isArabic = true).contains("ساعة"))
        assertTrue(WelcomeGiftLogic.announcement(isArabic = false).contains("one hour"))
        assertEquals(3_600_000L, WelcomeGiftLogic.DURATION_MS)
        assertEquals("anim_04", WelcomeGiftLogic.WELCOME_FRAME_ID)
    }
}
