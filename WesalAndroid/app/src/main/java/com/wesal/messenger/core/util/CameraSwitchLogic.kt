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

    /** Round 18: what one watchdog tick decides about the open flight. */
    enum class WatchdogVerdict {
        /** Keep waiting — the flight stays open for one more window. */
        EXTEND_ONCE,

        /** The flight is wedged — close it (onTimeout) and recover. */
        EXPIRED
    }

    private var watchdogExtensionsUsed = 0

    /** Begin a switch if none is running. Returns false when refused. */
    fun tryBegin(): Boolean {
        if (switchInFlight) return false
        switchInFlight = true
        watchdogExtensionsUsed = 0
        return true
    }

    /**
     * Round 18: the watchdog consults the machine before killing a
     * flight. The FIRST tick of a flight returns
     * [WatchdogVerdict.EXTEND_ONCE] — the flight stays open and the
     * caller re-arms the watchdog once. Root cause this serves
     * (device-reported on r17, OPPO/ColorOS): a slow-but-healthy
     * camera2 session reconfiguration can outlast the old single 5 s
     * window; when the watchdog fired mid-switch it closed the flight
     * and the recovery DESTROYED the capturer that was mid-switch and
     * rebuilt it on the old camera — the real switch's done callback
     * then arrived and was discarded as stale, so the user saw a
     * freeze, stayed on the same camera, pressed again, and could hit
     * the same abort repeatedly. With one extension, a healthy switch
     * completes inside the doubled window and its callback is accepted
     * normally. The SECOND tick returns [WatchdogVerdict.EXPIRED]:
     * the capturer is treated as genuinely wedged and the caller
     * closes the flight via [onTimeout] and recovers. The allowance
     * resets on every begin/resolution. A tick with no flight open is
     * EXPIRED (nothing to extend).
     */
    fun onWatchdogTick(): WatchdogVerdict {
        if (!switchInFlight) return WatchdogVerdict.EXPIRED
        if (watchdogExtensionsUsed >= 1) return WatchdogVerdict.EXPIRED
        watchdogExtensionsUsed += 1
        return WatchdogVerdict.EXTEND_ONCE
    }

    /**
     * The capturer confirmed the switch. Returns false (and changes
     * nothing) when the callback is stale.
     */
    fun onDone(nowFront: Boolean): Boolean {
        if (!switchInFlight) return false
        switchInFlight = false
        watchdogExtensionsUsed = 0
        isFrontCamera = nowFront
        return true
    }

    /** The capturer reported an error: stay on the current camera. */
    fun onError(): Boolean {
        if (!switchInFlight) return false
        switchInFlight = false
        watchdogExtensionsUsed = 0
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
        watchdogExtensionsUsed = 0
        return true
    }
}

/**
 * Round 20: WHICH camera a switch goes to, extracted pure so the rule
 * is JVM-testable.
 *
 * DEVICE-PROVEN defect this serves (owner's r19 verdict: front→rear
 * works, rear→front needs ~4 presses): the app used to call the
 * capturer's UNTARGETED `switchCamera(handler)` overload. In the
 * shipped stream-webrtc-android 1.2.2 build, that overload does NOT
 * pick the opposite-facing camera — `CameraCapturer.switchCamera`
 * selects `deviceNames[(indexOf(current) + 1) % size]`, the NEXT
 * camera id in `CameraManager.getCameraIdList()` order. On
 * multi-camera devices (the owner's OPPO class exposes front + main
 * rear + wide rear + macro/tele rear ids) the cycle from the main
 * rear camera lands on ANOTHER rear camera, then another, and only
 * wraps to the front after several presses — each intermediate "switch"
 * faithfully reports `isFrontCamera=false`, so the app stays "on rear"
 * exactly as he described. Slow auxiliary cameras add the stall.
 *
 * The rule here: a switch always targets the FIRST camera of the
 * OPPOSITE facing (the same selection `createCameraCapturer` uses for
 * the initial camera), via the targeted
 * `switchCamera(handler, cameraName)` overload. Two-state toggle,
 * symmetric in both directions, auxiliary cameras never visited.
 */
object CameraSwitchTarget {

    /** One enumerated camera: its capturer name and whether it faces front. */
    data class CameraInfo(val name: String, val isFront: Boolean)

    /**
     * The camera to switch TO, or null when no opposite-facing camera
     * exists. [currentCameraName] is the name the live capturer was
     * created/switched with (null when unknown — then [currentFront],
     * the state machine's facing, decides the current facing).
     */
    fun pickSwitchTarget(
        cameras: List<CameraInfo>,
        currentCameraName: String?,
        currentFront: Boolean
    ): String? {
        if (cameras.size < 2) return null
        val currentFacing = cameras.firstOrNull { it.name == currentCameraName }?.isFront
            ?: currentFront
        return cameras.firstOrNull { it.isFront != currentFacing && it.name != currentCameraName }?.name
    }

    /** The camera a fresh capturer starts on (initial capture / recovery). */
    fun pickInitialCamera(cameras: List<CameraInfo>, preferFront: Boolean): String? =
        cameras.firstOrNull { it.isFront == preferFront }?.name ?: cameras.firstOrNull()?.name
}
