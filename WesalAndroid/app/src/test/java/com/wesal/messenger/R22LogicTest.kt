package com.wesal.messenger

import com.wesal.messenger.core.util.CallPipLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 22: picture-in-picture rules for the call screen.
 *
 * The owner's r21 ask: a WhatsApp-style floating mini window for a
 * background video call. Two platform rules decide whether that
 * window can exist at all, and both live in [CallPipLogic]:
 *
 *  - the aspect ratio handed to PictureInPictureParams must lie
 *    within [100/239, 239/100] — outside it the platform throws and
 *    the mini window silently never appears;
 *  - PiP is attempted only for a LIVE VIDEO call that is not already
 *    in PiP (voice calls keep the detach + notification behaviour).
 *
 * The screen-share rebuild of the same round is WebRTC plumbing (a
 * dedicated screencast source/track + RtpSender.setTrack swap) with
 * no pure decision to pin here; its evidence and the parts only the
 * owner's device can confirm are recorded in FIXLOG_R22.
 */
class R22LogicTest {

    // --- clampedAspect: reduction -------------------------------------

    @Test
    fun aspect_landscapeHd_reducesTo16by9() {
        assertEquals(16 to 9, CallPipLogic.clampedAspect(1920, 1080))
    }

    @Test
    fun aspect_portraitHd_reducesTo9by16() {
        assertEquals(9 to 16, CallPipLogic.clampedAspect(1080, 1920))
    }

    @Test
    fun aspect_square_stays1by1() {
        assertEquals(1 to 1, CallPipLogic.clampedAspect(800, 800))
    }

    @Test
    fun aspect_tallPhoneScreen_insideRangeUnchanged() {
        // 1080x2400 = 9:20, ratio 2.22 < 2.39 — legal as-is.
        assertEquals(9 to 20, CallPipLogic.clampedAspect(1080, 2400))
    }

    // --- clampedAspect: clamping to the platform range -----------------

    @Test
    fun aspect_ultraWide_clampedTo239by100() {
        val (w, h) = CallPipLogic.clampedAspect(4000, 100)
        assertEquals(239 to 100, w to h)
        assertTrue(w.toDouble() / h <= 2.39)
    }

    @Test
    fun aspect_ultraTall_clampedTo100by239() {
        val (w, h) = CallPipLogic.clampedAspect(100, 4000)
        assertEquals(100 to 239, w to h)
        assertTrue(h.toDouble() / w <= 2.39)
    }

    @Test
    fun aspect_justOutsideWide_clamped() {
        // 240:100 = 2.40 > 2.39 must come down to the boundary.
        val (w, h) = CallPipLogic.clampedAspect(240, 100)
        assertTrue(w.toDouble() / h <= 2.39)
    }

    // --- clampedAspect: unknown size ------------------------------------

    @Test
    fun aspect_zeroOrNegative_fallsBackToDefault() {
        assertEquals(CallPipLogic.DEFAULT_ASPECT, CallPipLogic.clampedAspect(0, 0))
        assertEquals(CallPipLogic.DEFAULT_ASPECT, CallPipLogic.clampedAspect(-5, 1080))
        assertEquals(CallPipLogic.DEFAULT_ASPECT, CallPipLogic.clampedAspect(1080, 0))
    }

    // --- shouldEnterPip ---------------------------------------------------

    @Test
    fun pip_liveVideoCall_enters() {
        assertTrue(
            CallPipLogic.shouldEnterPip(isVideoCall = true, sessionLive = true, alreadyInPip = false)
        )
    }

    @Test
    fun pip_voiceCall_neverEnters() {
        assertFalse(
            CallPipLogic.shouldEnterPip(isVideoCall = false, sessionLive = true, alreadyInPip = false)
        )
    }

    @Test
    fun pip_deadSession_neverEnters() {
        assertFalse(
            CallPipLogic.shouldEnterPip(isVideoCall = true, sessionLive = false, alreadyInPip = false)
        )
    }

    @Test
    fun pip_alreadyInPip_doesNotReenter() {
        assertFalse(
            CallPipLogic.shouldEnterPip(isVideoCall = true, sessionLive = true, alreadyInPip = true)
        )
    }
}
