package com.wesal.messenger.core.util

/**
 * Round 12 (addendum B): serialization state machine for WebRTC camera
 * switching, extracted pure so the guard rules are JVM-testable.
 *
 * DEVICE-PROVEN defect this serves: `WebRtcCallManager.switchCamera()`
 * was a bare `videoCapturer?.switchCamera(null)` — no switch handler, no
 * in-flight guard. A camera switch is an async stop→open→start sequence
 * on the capturer; issuing the switch-BACK while the first switch is
 * still reconfiguring the camera session (exactly the owner's
 * front→rear→front test) wedges the capturer: capture never resumes,
 * the preview freezes and the call appears hung («بيتهنج»), with the
 * failure completely silent because the handler was null.
 *
 * Rules enforced here:
 *  - at most ONE switch in flight; extra taps are refused, not queued;
 *  - callbacks that arrive when no switch is in flight are STALE (e.g. a
 *    late done after the watchdog already recovered the capturer) and
 *    are rejected, so they can never corrupt the facing state;
 *  - the facing flips ONLY on a confirmed done — an error or a timeout
 *    leaves the call on the camera it was already using.
 */
class CameraSwitchStateMachine(initialFront: Boolean = true) {

    /** The camera the call is actually on. Changes only on a confirmed switch. */
    var isFrontCamera: Boolean = initialFront
        private set

    /** True between tryBegin and its done/error/timeout resolution. */
    var switchInFlight: Boolean = false
        private set

    /** Begin a switch if none is running. Returns false when refused. */
    fun tryBegin(): Boolean {
        if (switchInFlight) return false
        switchInFlight = true
        return true
    }

    /**
     * The capturer confirmed the switch. Returns false (and changes
     * nothing) when the callback is stale.
     */
    fun onDone(nowFront: Boolean): Boolean {
        if (!switchInFlight) return false
        switchInFlight = false
        isFrontCamera = nowFront
        return true
    }

    /** The capturer reported an error: stay on the current camera. */
    fun onError(): Boolean {
        if (!switchInFlight) return false
        switchInFlight = false
        return true
    }

    /**
     * Neither callback arrived within the watchdog window: the capturer
     * is treated as wedged; the flight is closed so recovery can run and
     * future switches are possible again.
     */
    fun onTimeout(): Boolean {
        if (!switchInFlight) return false
        switchInFlight = false
        return true
    }
}
