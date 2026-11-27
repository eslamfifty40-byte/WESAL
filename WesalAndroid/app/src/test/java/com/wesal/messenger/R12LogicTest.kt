package com.wesal.messenger

import com.wesal.messenger.core.util.CallRingLogic
import com.wesal.messenger.core.util.CameraSwitchStateMachine
import com.wesal.messenger.core.util.SubscriptionRequestLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-12 pure-logic tests: the subscription-request payload choice
 * (storage vs inline vs none), the inline size budget / quality ladder
 * bounds, the admin display reference, and the user-facing message
 * mapping (no raw exception text, ever).
 */
class R12LogicTest {

    // ---------------------------------------------------------------
    // Payload choice
    // ---------------------------------------------------------------

    @Test
    fun payload_prefersStorageUrl_whenUploadSucceeded() {
        val payload = SubscriptionRequestLogic.resolvePayload(
            storageUrl = "https://storage.example/subscription_ids/u/r.jpg",
            inlineData = "data:image/jpeg;base64,AAAA"
        )
        assertEquals(SubscriptionRequestLogic.IdDocumentSource.STORAGE, payload.source)
        assertEquals("https://storage.example/subscription_ids/u/r.jpg", payload.url)
        // The inline copy is dropped when a URL exists — never store both.
        assertEquals("", payload.inlineData)
    }

    @Test
    fun payload_fallsBackToInline_whenStorageFailed() {
        // The proven on-device case: Storage throws OBJECT_NOT_FOUND, so
        // there is no URL — the request must still carry its ID image.
        val payload = SubscriptionRequestLogic.resolvePayload(
            storageUrl = null,
            inlineData = "data:image/jpeg;base64,AAAA"
        )
        assertEquals(SubscriptionRequestLogic.IdDocumentSource.INLINE, payload.source)
        assertEquals("", payload.url)
        assertEquals("data:image/jpeg;base64,AAAA", payload.inlineData)
    }

    @Test
    fun payload_blankStorageUrl_countsAsFailure() {
        val payload = SubscriptionRequestLogic.resolvePayload(
            storageUrl = "  ",
            inlineData = "data:image/jpeg;base64,BBBB"
        )
        assertEquals(SubscriptionRequestLogic.IdDocumentSource.INLINE, payload.source)
    }

    @Test
    fun payload_none_whenNeitherExists_requestStillWritten() {
        val payload = SubscriptionRequestLogic.resolvePayload(null, null)
        assertEquals(SubscriptionRequestLogic.IdDocumentSource.NONE, payload.source)
        assertEquals("", payload.url)
        assertEquals("", payload.inlineData)
    }

    // ---------------------------------------------------------------
    // Size budget + ladder bounds
    // ---------------------------------------------------------------

    @Test
    fun base64Length_matchesEncoderMath() {
        assertEquals(0, SubscriptionRequestLogic.base64EncodedLength(0))
        assertEquals(4, SubscriptionRequestLogic.base64EncodedLength(1))
        assertEquals(4, SubscriptionRequestLogic.base64EncodedLength(3))
        assertEquals(8, SubscriptionRequestLogic.base64EncodedLength(4))
        assertEquals(8, SubscriptionRequestLogic.base64EncodedLength(6))
    }

    @Test
    fun budget_boundary_isExact() {
        // Largest byte count whose base64 length is exactly the cap fits;
        // one byte more does not.
        val cap = SubscriptionRequestLogic.MAX_INLINE_BASE64_CHARS
        val maxBytes = (cap / 4) * 3
        assertEquals(cap, SubscriptionRequestLogic.base64EncodedLength(maxBytes))
        assertTrue(SubscriptionRequestLogic.fitsInlineBudget(maxBytes))
        assertFalse(SubscriptionRequestLogic.fitsInlineBudget(maxBytes + 1))
        assertFalse(SubscriptionRequestLogic.fitsInlineBudget(0))
    }

    @Test
    fun budget_keepsWholeDocSafelyUnderFirestoreLimit() {
        // 750K payload chars + the data: prefix + every other doc field
        // stays well under the 1,048,576-byte Firestore document limit.
        val worstCaseDocBytes = SubscriptionRequestLogic.MAX_INLINE_BASE64_CHARS + 23 + 2_000
        assertTrue(worstCaseDocBytes < 1_048_576)
    }

    @Test
    fun encodePlan_startsAtBestQualityAndStepsDownMonotonically() {
        val plan = SubscriptionRequestLogic.encodePlan()
        assertTrue(plan.isNotEmpty())
        assertEquals(
            SubscriptionRequestLogic.EncodeStep(
                SubscriptionRequestLogic.MAX_INLINE_SIDE,
                SubscriptionRequestLogic.QUALITY_LADDER.first()
            ),
            plan.first()
        )
        // Sides never grow as the plan advances; qualities stay in range.
        val sides = plan.map { it.maxSide }
        assertEquals(sides.sortedDescending(), sides)
        assertTrue(plan.all { it.quality in 1..100 })
        // The last resort is the smallest side at the lowest quality.
        assertEquals(
            SubscriptionRequestLogic.EncodeStep(
                SubscriptionRequestLogic.SIDE_LADDER.last(),
                SubscriptionRequestLogic.QUALITY_LADDER.last()
            ),
            plan.last()
        )
        // One step per (side, quality) pair — no duplicates.
        assertEquals(plan.distinct().size, plan.size)
        assertEquals(
            SubscriptionRequestLogic.SIDE_LADDER.size * SubscriptionRequestLogic.QUALITY_LADDER.size,
            plan.size
        )
    }

    // ---------------------------------------------------------------
    // Admin display reference
    // ---------------------------------------------------------------

    @Test
    fun displayRef_prefersUrl_thenInline_thenBlank() {
        assertEquals(
            "https://storage.example/x.jpg",
            SubscriptionRequestLogic.displayImageRef("https://storage.example/x.jpg", "data:image/jpeg;base64,AA")
        )
        assertEquals(
            "data:image/jpeg;base64,AA",
            SubscriptionRequestLogic.displayImageRef("", "data:image/jpeg;base64,AA")
        )
        assertEquals("", SubscriptionRequestLogic.displayImageRef("", ""))
        assertEquals("", SubscriptionRequestLogic.displayImageRef(null, null))
    }

    // ---------------------------------------------------------------
    // User-facing messages: human, fixed, no exception text
    // ---------------------------------------------------------------

    @Test
    fun failureMessage_isTheHumanArabicString() {
        assertEquals(
            "حصلت مشكلة وإحنا بنبعت الطلب، حاول تاني",
            SubscriptionRequestLogic.submitFailureMessage(isEnglish = false)
        )
        val en = SubscriptionRequestLogic.submitFailureMessage(isEnglish = true)
        assertTrue(en.isNotBlank())
        // No raw exception vocabulary may leak through either language.
        for (msg in listOf(SubscriptionRequestLogic.submitFailureMessage(false), en)) {
            assertFalse(msg.contains("Object does not exist"))
            assertFalse(msg.contains("Exception"))
            assertFalse(msg.contains("Storage"))
        }
    }

    @Test
    fun successMessage_statesRequestIsPendingReview() {
        val ar = SubscriptionRequestLogic.submitSuccessMessage(isEnglish = false)
        assertTrue(ar.contains("قيد المراجعة"))
        val en = SubscriptionRequestLogic.submitSuccessMessage(isEnglish = true)
        assertTrue(en.contains("pending review", ignoreCase = true))
    }

    // ---------------------------------------------------------------
    // Addendum A: caller ringback play/stop decision
    // ---------------------------------------------------------------

    @Test
    fun ringback_playsForOutgoingCall_untilConnect() {
        assertTrue(
            CallRingLogic.shouldPlayRingback(
                isIncoming = false, mediaGranted = true, hasConnected = false, peerConnected = false
            )
        )
    }

    @Test
    fun ringback_neverPlaysForIncomingCall() {
        assertFalse(
            CallRingLogic.shouldPlayRingback(
                isIncoming = true, mediaGranted = true, hasConnected = false, peerConnected = false
            )
        )
    }

    @Test
    fun ringback_waitsForMediaPermission() {
        assertFalse(
            CallRingLogic.shouldPlayRingback(
                isIncoming = false, mediaGranted = false, hasConnected = false, peerConnected = false
            )
        )
    }

    @Test
    fun ringback_stopsOnConnect_andNeverRestartsAfter() {
        assertFalse(
            CallRingLogic.shouldPlayRingback(
                isIncoming = false, mediaGranted = true, hasConnected = false, peerConnected = true
            )
        )
        // The hasConnected latch: a mid-call blip back to "not connected"
        // must not restart the ringback.
        assertFalse(
            CallRingLogic.shouldPlayRingback(
                isIncoming = false, mediaGranted = true, hasConnected = true, peerConnected = false
            )
        )
    }

    @Test
    fun ringback_cadence_isARepeatedBeep() {
        assertTrue(CallRingLogic.RINGBACK_TONE_DURATION_MS > 0)
        assertTrue(CallRingLogic.RINGBACK_CYCLE_DELAY_MS > CallRingLogic.RINGBACK_TONE_DURATION_MS)
    }

    // ---------------------------------------------------------------
    // Addendum B: camera switch serialization
    // ---------------------------------------------------------------

    @Test
    fun cameraSwitch_secondTapRefusedWhileInFlight() {
        val machine = CameraSwitchStateMachine()
        assertTrue(machine.tryBegin())
        assertFalse(machine.tryBegin())
        assertTrue(machine.switchInFlight)
    }

    @Test
    fun cameraSwitch_doneFlipsFacing_andReopensGate() {
        val machine = CameraSwitchStateMachine(initialFront = true)
        assertTrue(machine.tryBegin())
        assertTrue(machine.onDone(nowFront = false))
        assertFalse(machine.isFrontCamera)
        assertFalse(machine.switchInFlight)
        // The switch-BACK (the owner's freeze case) is a fresh, legal switch.
        assertTrue(machine.tryBegin())
        assertTrue(machine.onDone(nowFront = true))
        assertTrue(machine.isFrontCamera)
    }

    @Test
    fun cameraSwitch_errorKeepsCurrentCamera_andReopensGate() {
        val machine = CameraSwitchStateMachine(initialFront = true)
        assertTrue(machine.tryBegin())
        assertTrue(machine.onError())
        assertTrue(machine.isFrontCamera)
        assertFalse(machine.switchInFlight)
        assertTrue(machine.tryBegin())
    }

    @Test
    fun cameraSwitch_staleCallbacksAreRejected() {
        val machine = CameraSwitchStateMachine(initialFront = true)
        // No flight in progress: every resolution is stale.
        assertFalse(machine.onDone(nowFront = false))
        assertFalse(machine.onError())
        assertFalse(machine.onTimeout())
        assertTrue(machine.isFrontCamera)
        // After a timeout already resolved the flight, a late done from
        // the wedged capturer must not flip the facing.
        assertTrue(machine.tryBegin())
        assertTrue(machine.onTimeout())
        assertFalse(machine.onDone(nowFront = false))
        assertTrue(machine.isFrontCamera)
    }
}
