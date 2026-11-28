package com.wesal.messenger.core.util

/**
 * Round 18 (ring-sustain fix): the outgoing-call deadline decisions,
 * extracted pure so they are JVM-testable.
 *
 * Owner complaint on r17: an outgoing call sat at «جاري الاتصال» and then
 * disconnected "right away" — it never got to ring properly. Two r13-era
 * mechanisms could kill a call that was still making progress:
 *
 *  1. The no-answer deadline (45s) was keyed ONLY on the WebRTC
 *     PeerConnection state. It fired even when the callee had already
 *     ANSWERED (call doc ACTIVE) but ICE had not reached CONNECTED yet —
 *     stamping MISSED over a live, answered call — and its 45s budget was
 *     measured from placement, so the callee's wake/ack latency ate the
 *     ring itself.
 *  2. The media-failure watchdog (25s of ICE DISCONNECTED/FAILED) had no
 *     progress gate: during the setup/ring phase ICE can report
 *     DISCONNECTED/FAILED before the answer has even landed, and a
 *     continuous 25s in that state ended a still-ringing call.
 *
 * The rules here are the WhatsApp-like contract the owner asked for: a
 * placed call stays up — ringback playing — until the callee answers,
 * rejects, or the FULL no-answer window expires. Any sign of progress
 * (call doc created, callee ack/answer, media connected) cancels the
 * relevant teardown permanently; the watchdogs may only fire when there
 * is genuinely no progress at all.
 */
object CallTimeoutLogic {

    /**
     * The call document must be created/acknowledged inside this window.
     * A genuine stall guard (device-proven under the exhausted quota,
     * where the write never ACKed and the dialer hung forever) — it may
     * only end the call when the doc never appeared AND nothing else
     * progressed either.
     */
    const val SETUP_TIMEOUT_MS: Long = 20_000L

    /**
     * Total no-answer budget from call placement (WhatsApp-style: the
     * ring sustains for a full minute). Only when this whole window
     * expires with no answer does the caller stamp MISSED and show
     * «لم يتم الرد».
     */
    const val NO_ANSWER_TIMEOUT_MS: Long = 60_000L

    /**
     * How long ICE may stay FAILED/DISCONNECTED on an ESTABLISHED call
     * before the media-failure watchdog ends it with the honest reason.
     * Never armed before the call is answered or has connected.
     */
    const val MEDIA_FAILURE_GRACE_MS: Long = 25_000L

    /** Setup teardown: only when there is genuinely no progress at all. */
    fun shouldEndSetup(
        callDocCreated: Boolean,
        answered: Boolean,
        peerConnected: Boolean
    ): Boolean = !callDocCreated && !answered && !peerConnected

    /**
     * No-answer teardown: NEVER once the callee has answered (doc ACTIVE)
     * or the media path has connected — an answered call belongs to the
     * in-call machinery (ICE restarts / media watchdog), not to the ring
     * deadline.
     */
    fun shouldEndForNoAnswer(
        answered: Boolean,
        peerConnected: Boolean
    ): Boolean = !answered && !peerConnected

    /**
     * The media-failure watchdog guards ESTABLISHED calls only. Before an
     * answer/connection, ICE trouble is a setup-phase condition owned by
     * the setup + no-answer deadlines above.
     */
    fun mediaFailureWatchdogArmed(
        hasConnected: Boolean,
        answered: Boolean
    ): Boolean = hasConnected || answered
}
