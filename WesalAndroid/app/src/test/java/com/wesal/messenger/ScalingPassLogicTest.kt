package com.wesal.messenger

import com.wesal.messenger.core.util.ContactsSyncGuard
import com.wesal.messenger.core.util.PresenceWriteGate
import com.wesal.messenger.core.util.ReadReceiptBatcher
import com.wesal.messenger.core.util.TypingSignalGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scaling-pass pure-logic tests: the presence write gate, the typing
 * transition gate, the read-receipt batcher, and the contacts sync
 * fingerprint guard. These four decisions are where the Firestore
 * write/read reductions come from, so their contracts are pinned here.
 */
class ScalingPassLogicTest {

    // ---------------------------------------------------------------
    // PresenceWriteGate
    // ---------------------------------------------------------------

    private val t0 = 1_000_000L

    @Test
    fun presence_firstWriteOfSessionAlwaysLands() {
        val s = PresenceWriteGate.Snapshot()
        assertTrue(PresenceWriteGate.shouldWrite(s, "uidA", true, "", "", t0))
        assertTrue(PresenceWriteGate.shouldWrite(s, "uidA", false, "", "", t0))
    }

    @Test
    fun presence_blankUidNeverWrites() {
        val s = PresenceWriteGate.Snapshot()
        assertFalse(PresenceWriteGate.shouldWrite(s, "", true, "", "", t0))
        assertFalse(PresenceWriteGate.shouldWrite(s, "  ", true, "", "", t0))
    }

    @Test
    fun presence_steadyHeartbeatIsSuppressedUntilRefreshInterval() {
        var s = PresenceWriteGate.Snapshot()
        s = PresenceWriteGate.recorded(s, "uidA", true, null, null, t0)
        // 25 s loop ticks: all suppressed.
        for (tick in 1..11) {
            assertFalse(
                "tick $tick must be gated",
                PresenceWriteGate.shouldWrite(s, "uidA", true, "", "", t0 + tick * 25_000L)
            )
        }
        // At the refresh interval the heartbeat lands again.
        assertTrue(
            PresenceWriteGate.shouldWrite(
                s, "uidA", true, "", "",
                t0 + PresenceWriteGate.HEARTBEAT_REFRESH_MS
            )
        )
    }

    @Test
    fun presence_transitionsAlwaysWriteImmediately() {
        var s = PresenceWriteGate.Snapshot()
        s = PresenceWriteGate.recorded(s, "uidA", true, null, null, t0)
        // Going offline 1 s after the online write still writes.
        assertTrue(PresenceWriteGate.shouldWrite(s, "uidA", false, "", "", t0 + 1_000L))
        s = PresenceWriteGate.recorded(s, "uidA", false, "NONE", "", t0 + 1_000L)
        // And coming back online writes at once too.
        assertTrue(PresenceWriteGate.shouldWrite(s, "uidA", true, "", "", t0 + 2_000L))
        // A repeated offline stamp with no change is suppressed (the
        // Application + ViewModel double-write of the old code).
        assertFalse(PresenceWriteGate.shouldWrite(s, "uidA", false, "", "", t0 + 3_000L))
    }

    @Test
    fun presence_activityChangeWritesButSameActivityDoesNot() {
        var s = PresenceWriteGate.Snapshot()
        s = PresenceWriteGate.recorded(s, "uidA", true, null, null, t0)
        // Entering a chat stamps activity.
        assertTrue(PresenceWriteGate.shouldWrite(s, "uidA", true, "CHAT", "chat1", t0 + 1_000L))
        s = PresenceWriteGate.recorded(s, "uidA", true, "CHAT", "chat1", t0 + 1_000L)
        // Re-firing the same activity (LaunchedEffect re-key) does not.
        assertFalse(PresenceWriteGate.shouldWrite(s, "uidA", true, "CHAT", "chat1", t0 + 2_000L))
        // Moving to another chat does.
        assertTrue(PresenceWriteGate.shouldWrite(s, "uidA", true, "CHAT", "chat2", t0 + 3_000L))
        // Leaving (activity NONE) does.
        assertTrue(PresenceWriteGate.shouldWrite(s, "uidA", true, "NONE", "", t0 + 4_000L))
    }

    @Test
    fun presence_blankActivityHeartbeatDoesNotEraseTrackedActivity() {
        var s = PresenceWriteGate.Snapshot()
        s = PresenceWriteGate.recorded(s, "uidA", true, "GROUP", "g1", t0)
        // A heartbeat refresh records with null activity: the tracked
        // activity must survive, or the next identical activity report
        // would look like a change and write again.
        s = PresenceWriteGate.recorded(
            s, "uidA", true, null, null,
            t0 + PresenceWriteGate.HEARTBEAT_REFRESH_MS
        )
        assertFalse(
            PresenceWriteGate.shouldWrite(
                s, "uidA", true, "GROUP", "g1",
                t0 + PresenceWriteGate.HEARTBEAT_REFRESH_MS + 1_000L
            )
        )
    }

    @Test
    fun presence_accountSwitchAlwaysWrites() {
        var s = PresenceWriteGate.Snapshot()
        s = PresenceWriteGate.recorded(s, "uidA", true, null, null, t0)
        assertTrue(PresenceWriteGate.shouldWrite(s, "uidB", true, "", "", t0 + 1_000L))
    }

    @Test
    fun presence_freshnessWindowCoversTheRefreshCadence() {
        // A heartbeat stamped just after the old 75 s TTL expired still
        // counts as fresh under the gated cadence.
        assertTrue(PresenceWriteGate.isWithinFreshnessWindow(t0, t0 + 200_000L))
        assertTrue(
            PresenceWriteGate.isWithinFreshnessWindow(
                t0, t0 + PresenceWriteGate.FRESHNESS_WINDOW_MS
            )
        )
        assertFalse(
            PresenceWriteGate.isWithinFreshnessWindow(
                t0, t0 + PresenceWriteGate.FRESHNESS_WINDOW_MS + 1L
            )
        )
        // Zero stamp keeps the historical "no heartbeat recorded" meaning.
        assertTrue(PresenceWriteGate.isWithinFreshnessWindow(0L, t0 + 999_999_999L))
    }

    // ---------------------------------------------------------------
    // TypingSignalGate
    // ---------------------------------------------------------------

    @Test
    fun typing_firstKeystrokeSendsTrueThenKeystrokesAreSilent() {
        val gate = TypingSignalGate()
        assertEquals(TypingSignalGate.Action.SEND_TRUE, gate.onTextChanged(false, 0L))
        assertEquals(TypingSignalGate.Action.NONE, gate.onTextChanged(false, 100L))
        assertEquals(TypingSignalGate.Action.NONE, gate.onTextChanged(false, 500L))
        assertEquals(TypingSignalGate.Action.NONE, gate.onTextChanged(false, 5_000L))
    }

    @Test
    fun typing_longTypingRefreshesTrueAtTheRefreshInterval() {
        val gate = TypingSignalGate()
        assertEquals(TypingSignalGate.Action.SEND_TRUE, gate.onTextChanged(false, 0L))
        assertEquals(TypingSignalGate.Action.NONE, gate.onTextChanged(false, 9_999L))
        assertEquals(TypingSignalGate.Action.SEND_TRUE, gate.onTextChanged(false, 10_000L))
        assertEquals(TypingSignalGate.Action.NONE, gate.onTextChanged(false, 10_100L))
    }

    @Test
    fun typing_clearingTheTextSendsFalseOnce() {
        val gate = TypingSignalGate()
        gate.onTextChanged(false, 0L)
        assertEquals(TypingSignalGate.Action.SEND_FALSE, gate.onTextChanged(true, 300L))
        assertEquals(TypingSignalGate.Action.NONE, gate.onTextChanged(true, 400L))
    }

    @Test
    fun typing_stopTimerSendsFalseOnceAndTypingCanRestart() {
        val gate = TypingSignalGate()
        gate.onTextChanged(false, 0L)
        assertEquals(TypingSignalGate.Action.SEND_FALSE, gate.onStopTimer())
        assertEquals(TypingSignalGate.Action.NONE, gate.onStopTimer())
        // Typing again after the stop re-sends true.
        assertEquals(TypingSignalGate.Action.SEND_TRUE, gate.onTextChanged(false, 3_000L))
    }

    @Test
    fun typing_sendClearsTheSignal() {
        val gate = TypingSignalGate()
        gate.onTextChanged(false, 0L)
        assertEquals(TypingSignalGate.Action.SEND_FALSE, gate.onMessageSent())
        assertEquals(TypingSignalGate.Action.NONE, gate.onMessageSent())
    }

    @Test
    fun typing_blankTextWithoutSignalIsSilent() {
        val gate = TypingSignalGate()
        assertEquals(TypingSignalGate.Action.NONE, gate.onTextChanged(true, 0L))
        assertEquals(TypingSignalGate.Action.NONE, gate.onStopTimer())
    }

    // ---------------------------------------------------------------
    // ReadReceiptBatcher
    // ---------------------------------------------------------------

    @Test
    fun receipts_firstRequestFlushesImmediately() {
        val batcher = ReadReceiptBatcher()
        assertEquals(
            ReadReceiptBatcher.Decision.FlushNow,
            batcher.onReadRequested(1_000L)
        )
    }

    @Test
    fun receipts_requestsInsideTheIntervalCoalesceIntoOneTrailingFlush() {
        val batcher = ReadReceiptBatcher()
        batcher.onReadRequested(0L)
        batcher.markFlushed(0L)
        val first = batcher.onReadRequested(3_000L)
        assertTrue(first is ReadReceiptBatcher.Decision.Schedule)
        assertEquals(7_000L, (first as ReadReceiptBatcher.Decision.Schedule).delayMs)
        // Further requests neither reschedule nor flush early.
        assertEquals(
            ReadReceiptBatcher.Decision.AlreadyScheduled,
            batcher.onReadRequested(4_000L)
        )
        assertEquals(
            ReadReceiptBatcher.Decision.AlreadyScheduled,
            batcher.onReadRequested(9_500L)
        )
        assertTrue(batcher.hasPending())
        // The trailing flush lands; the next request is a fresh cycle.
        batcher.markFlushed(10_000L)
        assertFalse(batcher.hasPending())
        val next = batcher.onReadRequested(11_000L)
        assertTrue(next is ReadReceiptBatcher.Decision.Schedule)
    }

    @Test
    fun receipts_requestAfterTheIntervalFlushesImmediatelyAgain() {
        val batcher = ReadReceiptBatcher()
        batcher.onReadRequested(0L)
        batcher.markFlushed(0L)
        assertEquals(
            ReadReceiptBatcher.Decision.FlushNow,
            batcher.onReadRequested(10_000L)
        )
    }

    @Test
    fun receipts_noRequestsMeansNothingPending() {
        val batcher = ReadReceiptBatcher()
        assertFalse(batcher.hasPending())
        batcher.onReadRequested(0L)
        batcher.markFlushed(0L)
        assertFalse(batcher.hasPending())
    }

    // ---------------------------------------------------------------
    // ContactsSyncGuard
    // ---------------------------------------------------------------

    @Test
    fun contacts_fingerprintIsOrderIndependentAndContentSensitive() {
        val a = ContactsSyncGuard.fingerprint(listOf("h1", "h2", "h3"))
        val b = ContactsSyncGuard.fingerprint(listOf("h3", "h1", "h2", "h1"))
        assertEquals(a, b)
        val c = ContactsSyncGuard.fingerprint(listOf("h1", "h2", "h4"))
        assertTrue(a != c)
    }

    @Test
    fun contacts_skipOnlyForUnchangedFreshSuccessfulSync() {
        val fp = ContactsSyncGuard.fingerprint(listOf("h1", "h2"))
        // Same book, synced 30 s ago, not forced → skip.
        assertTrue(
            ContactsSyncGuard.shouldSkipSync(
                force = false, currentFingerprint = fp,
                lastSuccessfulFingerprint = fp,
                lastSuccessfulAtMs = 100_000L, nowMs = 130_000L
            )
        )
        // Forced syncs never skip.
        assertFalse(
            ContactsSyncGuard.shouldSkipSync(
                force = true, currentFingerprint = fp,
                lastSuccessfulFingerprint = fp,
                lastSuccessfulAtMs = 100_000L, nowMs = 130_000L
            )
        )
        // A changed phone book never skips.
        assertFalse(
            ContactsSyncGuard.shouldSkipSync(
                force = false, currentFingerprint = fp + 1,
                lastSuccessfulFingerprint = fp,
                lastSuccessfulAtMs = 100_000L, nowMs = 130_000L
            )
        )
        // A stale success (past the fresh window) never skips.
        assertFalse(
            ContactsSyncGuard.shouldSkipSync(
                force = false, currentFingerprint = fp,
                lastSuccessfulFingerprint = fp,
                lastSuccessfulAtMs = 100_000L,
                nowMs = 100_000L + ContactsSyncGuard.FRESH_WINDOW_MS
            )
        )
        // No successful sync yet never skips.
        assertFalse(
            ContactsSyncGuard.shouldSkipSync(
                force = false, currentFingerprint = fp,
                lastSuccessfulFingerprint = null,
                lastSuccessfulAtMs = 0L, nowMs = 130_000L
            )
        )
    }
}
