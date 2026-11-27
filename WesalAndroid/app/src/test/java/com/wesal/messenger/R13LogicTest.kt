package com.wesal.messenger

import com.wesal.messenger.core.util.AssistantLogic
import com.wesal.messenger.core.util.DictationLogic
import com.wesal.messenger.core.util.OfficialStoryLogic
import com.wesal.messenger.core.util.PendingProfileLogic
import com.wesal.messenger.core.util.SubscriptionGrantLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-13 pure-logic tests: the official-story admin gate (Item D),
 * the subscription grant plan (Addendum E), continuous dictation
 * decisions (Addenda I.3/I2), the assistant supersede guard (Item I.1),
 * and the queued profile-completion contract (Addendum J).
 */
class R13LogicTest {

    // ---------------------------------------------------------------
    // Item D: official stories are admins-only, at every layer
    // ---------------------------------------------------------------

    @Test
    fun officialGate_adminsMayPublish() {
        assertTrue(OfficialStoryLogic.canPublishOfficial("10001"))
        assertTrue(OfficialStoryLogic.canPublishOfficial("10002"))
        assertTrue(OfficialStoryLogic.canPublishOfficial(" 10002 "))
    }

    @Test
    fun officialGate_regularUsersMayNotPublish() {
        assertFalse(OfficialStoryLogic.canPublishOfficial("789671"))
        assertFalse(OfficialStoryLogic.canPublishOfficial("99999"))
        assertFalse(OfficialStoryLogic.canPublishOfficial(""))
        assertFalse(OfficialStoryLogic.canPublishOfficial("10003"))
    }

    @Test
    fun officialGate_effectiveStampRequiresRequestAndAdmin() {
        assertTrue(OfficialStoryLogic.effectiveIsOfficial(requested = true, authorNumericId = "10001"))
        assertTrue(OfficialStoryLogic.effectiveIsOfficial(requested = true, authorNumericId = "10002"))
        assertFalse(OfficialStoryLogic.effectiveIsOfficial(requested = false, authorNumericId = "10001"))
        // The defense-in-depth case: a non-admin somehow reaching the
        // publish path with the flag set is stamped non-official.
        assertFalse(OfficialStoryLogic.effectiveIsOfficial(requested = true, authorNumericId = "789671"))
        assertFalse(OfficialStoryLogic.effectiveIsOfficial(requested = true, authorNumericId = ""))
    }

    // ---------------------------------------------------------------
    // Addendum E: approved request -> grant plan for the user doc
    // ---------------------------------------------------------------

    @Test
    fun grantPlan_ownerDeviceCase_blackSeal_cust18Frame_redGlow() {
        // The exact request the owner approved on r12 (19:16): badge
        // الشارة السمراء (BLACK), frame cust_18, glow RED.
        val plan = SubscriptionGrantLogic.planFor(
            desiredSeal = "BLACK",
            desiredGlowColor = "RED",
            desiredFrameId = "cust_18"
        )
        assertEquals("BLACK", plan.sealKey)
        assertEquals("RED", plan.glowKey)
        assertEquals("cust_18", plan.frameId)
        assertTrue(plan.applyFrame)
        assertEquals(0L, plan.expiresAt)
    }

    @Test
    fun grantPlan_blankFrameMeansNoFrameGrant() {
        val plan = SubscriptionGrantLogic.planFor("BLUE", "GREEN", "")
        assertEquals("BLUE", plan.sealKey)
        assertEquals("GREEN", plan.glowKey)
        assertNull(plan.frameId)
        assertFalse(plan.applyFrame)
    }

    @Test
    fun grantPlan_unknownKeysFallBackLikeTheWriters() {
        val plan = SubscriptionGrantLogic.planFor("NOPE", "ALSO_NOPE", "  cust_01 ")
        assertEquals("BLUE", plan.sealKey) // VerificationSealType.fromKey default
        assertEquals("NONE", plan.glowKey) // NeonColorPreset.fromKey default
        assertEquals("cust_01", plan.frameId) // frame ids are trimmed, never remapped
        assertTrue(plan.applyFrame)
    }

    @Test
    fun grantPlan_nullsAreSafe() {
        val plan = SubscriptionGrantLogic.planFor(null, null, null)
        assertEquals("BLUE", plan.sealKey)
        assertEquals("NONE", plan.glowKey)
        assertNull(plan.frameId)
        assertFalse(plan.applyFrame)
    }

    // ---------------------------------------------------------------
    // Addenda I.3 + I2: continuous dictation decisions
    // ---------------------------------------------------------------

    @Test
    fun dictation_pauseErrorsAreTransient_neverEndTheSession() {
        assertTrue(DictationLogic.isPauseOrTransientError(DictationLogic.ERROR_SPEECH_TIMEOUT))
        assertTrue(DictationLogic.isPauseOrTransientError(DictationLogic.ERROR_NO_MATCH))
        assertTrue(DictationLogic.isPauseOrTransientError(DictationLogic.ERROR_NETWORK))
        assertTrue(DictationLogic.isPauseOrTransientError(DictationLogic.ERROR_RECOGNIZER_BUSY))
        assertFalse(DictationLogic.isPauseOrTransientError(DictationLogic.ERROR_INSUFFICIENT_PERMISSIONS))
    }

    @Test
    fun dictation_hardErrorsStopImmediately() {
        assertTrue(DictationLogic.isHardError(DictationLogic.ERROR_INSUFFICIENT_PERMISSIONS))
        assertTrue(DictationLogic.isHardError(DictationLogic.ERROR_LANGUAGE_NOT_SUPPORTED))
        assertTrue(DictationLogic.isHardError(DictationLogic.ERROR_LANGUAGE_UNAVAILABLE))
        assertFalse(DictationLogic.isHardError(DictationLogic.ERROR_SPEECH_TIMEOUT))
    }

    @Test
    fun dictation_restartsWhileActiveUntilFastFailureCap() {
        assertTrue(
            DictationLogic.shouldRestart(
                dictationActive = true,
                error = DictationLogic.ERROR_SPEECH_TIMEOUT,
                consecutiveFastFailures = 0
            )
        )
        assertTrue(
            DictationLogic.shouldRestart(
                dictationActive = true,
                error = DictationLogic.ERROR_NO_MATCH,
                consecutiveFastFailures = DictationLogic.MAX_FAST_FAILURES - 1
            )
        )
        // Service proven dead: repeated instant deaths stop the loop.
        assertFalse(
            DictationLogic.shouldRestart(
                dictationActive = true,
                error = DictationLogic.ERROR_CLIENT,
                consecutiveFastFailures = DictationLogic.MAX_FAST_FAILURES
            )
        )
        // The user's own stop always wins.
        assertFalse(
            DictationLogic.shouldRestart(
                dictationActive = false,
                error = DictationLogic.ERROR_SPEECH_TIMEOUT,
                consecutiveFastFailures = 0
            )
        )
        // Hard errors never restart.
        assertFalse(
            DictationLogic.shouldRestart(
                dictationActive = true,
                error = DictationLogic.ERROR_INSUFFICIENT_PERMISSIONS,
                consecutiveFastFailures = 0
            )
        )
    }

    @Test
    fun dictation_fastFailureStreakResetsAfterAHealthySession() {
        assertEquals(1, DictationLogic.nextFastFailureStreak(0, 100L))
        assertEquals(3, DictationLogic.nextFastFailureStreak(2, 699L))
        assertEquals(0, DictationLogic.nextFastFailureStreak(2, DictationLogic.FAST_FAILURE_WINDOW_MS))
        assertEquals(0, DictationLogic.nextFastFailureStreak(2, 5_000L))
    }

    @Test
    fun dictation_segmentsAppendWithSingleSpace() {
        assertEquals("أهلا", DictationLogic.appendSegment("", "أهلا"))
        assertEquals("أهلا بيك", DictationLogic.appendSegment("أهلا", "بيك"))
        assertEquals("أهلا بيك", DictationLogic.appendSegment("  أهلا  ", "  بيك "))
        assertEquals("أهلا", DictationLogic.appendSegment("أهلا", ""))
        assertEquals("", DictationLogic.appendSegment("", ""))
    }

    // ---------------------------------------------------------------
    // Item I.1: a superseded ask's late reply never lands
    // ---------------------------------------------------------------

    @Test
    fun assistantReplyAppliesOnlyForTheCurrentGeneration() {
        assertTrue(AssistantLogic.replyApplies(dispatchGeneration = 3, currentGeneration = 3))
        assertFalse(AssistantLogic.replyApplies(dispatchGeneration = 3, currentGeneration = 4))
        assertFalse(AssistantLogic.replyApplies(dispatchGeneration = 5, currentGeneration = 4))
    }

    // ---------------------------------------------------------------
    // Addendum J: queued profile completion
    // ---------------------------------------------------------------

    @Test
    fun pendingProfile_jsonRoundTrip() {
        val pending = PendingProfileLogic.PendingProfile(
            uid = "uid-1",
            displayName = "مستخدم جديد",
            bio = "أهلا",
            wesalId = "new_user",
            phoneE164 = "+201012345678",
            birthDateIso = "2000-01-15",
            hideBirthDate = true,
            locationGranted = false,
            locationGraceStartedAt = 123L,
            photoUriString = "content://media/1",
            savedAtMs = 999L
        )
        assertEquals(pending, PendingProfileLogic.fromJson(PendingProfileLogic.toJson(pending)))
    }

    @Test
    fun pendingProfile_fromJsonRejectsBlankCorruptAndOwnerless() {
        assertNull(PendingProfileLogic.fromJson(null))
        assertNull(PendingProfileLogic.fromJson(""))
        assertNull(PendingProfileLogic.fromJson("not json"))
        assertNull(PendingProfileLogic.fromJson("""{"displayName":"x"}"""))
    }

    @Test
    fun pendingProfile_userFixableFailuresBlock_infrastructureQueues() {
        // Duplicate @ID / duplicate phone arrive as IllegalArgumentException.
        assertTrue(PendingProfileLogic.isUserFixable(IllegalArgumentException("معرف وصال @x مستخدم بالفعل، اختر معرفاً آخر")))
        assertTrue(PendingProfileLogic.isUserFixable(IllegalArgumentException("رقم الهاتف هذا مستخدم بالفعل في حساب WESAL آخر")))
        // Banned number arrives as IllegalStateException with its marker.
        assertTrue(PendingProfileLogic.isUserFixable(IllegalStateException("هذا الرقم مرتبط بحساب محظور من استخدام WESAL")))
        // The trap from the owner's screenshot: a timeout is infrastructure.
        val timeout = IllegalStateException("انتهت مهلة الاتصال بالخادم، تحقق من الإنترنت وحاول مرة أخرى")
        assertFalse(PendingProfileLogic.isUserFixable(timeout))
        assertTrue(PendingProfileLogic.isInfrastructureFailure(timeout))
        assertTrue(PendingProfileLogic.isInfrastructureFailure(RuntimeException("RESOURCE_EXHAUSTED")))
        assertFalse(PendingProfileLogic.isInfrastructureFailure(null))
    }
}
