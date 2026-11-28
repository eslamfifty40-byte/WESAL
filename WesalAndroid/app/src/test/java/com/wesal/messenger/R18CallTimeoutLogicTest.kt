package com.wesal.messenger

import com.wesal.messenger.core.util.CallTimeoutLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-18 ring-sustain coverage. Owner complaint on r17: an outgoing
 * call sat at «جاري الاتصال» and disconnected "right away" instead of
 * sustaining the ring. The deadline decisions in CallTimeoutLogic must
 * guarantee the WhatsApp-like contract: a placed call stays up until
 * the callee answers, rejects, or the FULL no-answer window expires —
 * and any sign of progress cancels a teardown permanently.
 */
class R18CallTimeoutLogicTest {

    // ------------------------------------------------------------ no-answer deadline

    @Test
    fun noAnswer_firesOnlyWhenTrulyUnanswered() {
        assertTrue(
            CallTimeoutLogic.shouldEndForNoAnswer(answered = false, peerConnected = false)
        )
    }

    @Test
    fun noAnswer_neverFiresOnAnAnsweredCall() {
        // The r17 defect: the deadline keyed only on the WebRTC state, so
        // a callee who answered at second 40 — with ICE still CHECKING —
        // got stamped MISSED at 45s. An answer cancels it permanently.
        assertFalse(
            CallTimeoutLogic.shouldEndForNoAnswer(answered = true, peerConnected = false)
        )
    }

    @Test
    fun noAnswer_neverFiresOnceMediaConnected() {
        assertFalse(
            CallTimeoutLogic.shouldEndForNoAnswer(answered = false, peerConnected = true)
        )
        assertFalse(
            CallTimeoutLogic.shouldEndForNoAnswer(answered = true, peerConnected = true)
        )
    }

    // ------------------------------------------------------------ setup deadline

    @Test
    fun setup_firesOnlyWithZeroProgress() {
        assertTrue(
            CallTimeoutLogic.shouldEndSetup(
                callDocCreated = false, answered = false, peerConnected = false
            )
        )
    }

    @Test
    fun setup_neverFiresOnceTheCallDocExists() {
        assertFalse(
            CallTimeoutLogic.shouldEndSetup(
                callDocCreated = true, answered = false, peerConnected = false
            )
        )
    }

    @Test
    fun setup_neverFiresOnProgress() {
        assertFalse(
            CallTimeoutLogic.shouldEndSetup(
                callDocCreated = false, answered = true, peerConnected = false
            )
        )
        assertFalse(
            CallTimeoutLogic.shouldEndSetup(
                callDocCreated = false, answered = false, peerConnected = true
            )
        )
    }

    // ------------------------------------------------------------ media-failure watchdog

    @Test
    fun mediaWatchdog_disarmedDuringSetupAndRinging() {
        // Before any answer/connection, ICE trouble is a setup-phase
        // condition; the watchdog must not end a still-ringing call.
        assertFalse(
            CallTimeoutLogic.mediaFailureWatchdogArmed(hasConnected = false, answered = false)
        )
    }

    @Test
    fun mediaWatchdog_armedOnceEstablished() {
        assertTrue(
            CallTimeoutLogic.mediaFailureWatchdogArmed(hasConnected = true, answered = false)
        )
        assertTrue(
            CallTimeoutLogic.mediaFailureWatchdogArmed(hasConnected = false, answered = true)
        )
        assertTrue(
            CallTimeoutLogic.mediaFailureWatchdogArmed(hasConnected = true, answered = true)
        )
    }

    // ------------------------------------------------------------ window sizes

    @Test
    fun windows_areWhatsAppLike() {
        // The ring sustains a full minute before «لم يتم الرد».
        assertEquals(60_000L, CallTimeoutLogic.NO_ANSWER_TIMEOUT_MS)
        // The setup stall guard stays bounded at 20s.
        assertEquals(20_000L, CallTimeoutLogic.SETUP_TIMEOUT_MS)
        // The no-answer window must exceed the setup window so the
        // remainder delay in the deadline effect is always positive.
        assertTrue(CallTimeoutLogic.NO_ANSWER_TIMEOUT_MS > CallTimeoutLogic.SETUP_TIMEOUT_MS)
        assertEquals(25_000L, CallTimeoutLogic.MEDIA_FAILURE_GRACE_MS)
    }
}
