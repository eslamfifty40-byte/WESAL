package com.wesal.messenger.core.util

/**
 * Scaling pass: read-receipt batching decisions (one instance per chat).
 *
 * Before this batcher, every change of the open chat's message count ran
 * the full mark-as-read sweep (chat GET + unread-count write + paged
 * unread query + per-message updates), so an active conversation re-ran
 * the sweep — and re-issued its writes — on nearly every message.
 *
 * The batcher keeps the visible contract: the FIRST request flushes
 * immediately (the unread badge and receipts react at once when a chat is
 * opened), further requests inside [intervalMs] coalesce into ONE trailing
 * flush, and leaving the chat flushes whatever is still pending.
 */
class ReadReceiptBatcher(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS
) {
    sealed interface Decision {
        /** Run the sweep now. */
        object FlushNow : Decision

        /** A trailing flush is due in [delayMs]; schedule it once. */
        data class Schedule(val delayMs: Long) : Decision

        /** A trailing flush is already scheduled; do nothing. */
        object AlreadyScheduled : Decision
    }

    private var lastFlushAtMs: Long = Long.MIN_VALUE
    private var scheduledFlushAtMs: Long? = null

    @Synchronized
    fun onReadRequested(nowMs: Long): Decision {
        if (scheduledFlushAtMs != null) return Decision.AlreadyScheduled
        if (lastFlushAtMs == Long.MIN_VALUE || nowMs - lastFlushAtMs >= intervalMs) {
            return Decision.FlushNow
        }
        val due = lastFlushAtMs + intervalMs
        scheduledFlushAtMs = due
        return Decision.Schedule(due - nowMs)
    }

    /** A sweep (immediate, trailing, or exit) has just been performed. */
    @Synchronized
    fun markFlushed(nowMs: Long) {
        lastFlushAtMs = nowMs
        scheduledFlushAtMs = null
    }

    /** True when requests arrived since the last flush and await one. */
    @Synchronized
    fun hasPending(): Boolean = scheduledFlushAtMs != null

    companion object {
        const val DEFAULT_INTERVAL_MS: Long = 10_000L
    }
}
