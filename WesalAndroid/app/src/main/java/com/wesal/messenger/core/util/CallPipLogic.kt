package com.wesal.messenger.core.util

/**
 * Round 22: picture-in-picture decisions for the call screen,
 * extracted pure so the rules are JVM-testable.
 *
 * The platform contract these rules serve (Android picture-in-picture,
 * API 26+):
 *  - the aspect ratio handed to PictureInPictureParams must lie
 *    within [100/239, 239/100] (≈ [0.418, 2.39]) — outside that range
 *    the builder throws IllegalArgumentException, which on the owner's
 *    device would read as "the mini window never appears";
 *  - PiP is a VIDEO-call affordance only: voice calls keep the
 *    round-20 behaviour (detach + ongoing-call notification), and a
 *    dead session must never try to enter PiP.
 */
object CallPipLogic {

    /** Default PiP aspect when no content size is known: portrait 9:16. */
    val DEFAULT_ASPECT: Pair<Int, Int> = 9 to 16

    /**
     * Reduces (width, height) to a fraction and clamps it into the
     * platform's legal PiP aspect range. Non-positive input (size not
     * known yet) yields [DEFAULT_ASPECT].
     */
    fun clampedAspect(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return DEFAULT_ASPECT
        var w = width.toLong()
        var h = height.toLong()
        // Too wide: w/h > 239/100 → pull w down to the boundary.
        if (w * 100 > h * 239) w = h * 239 / 100
        // Too tall: h/w > 239/100 → pull h down to the boundary.
        if (h * 100 > w * 239) h = w * 239 / 100
        if (w <= 0L) w = 1L
        if (h <= 0L) h = 1L
        val wi = w.toInt()
        val hi = h.toInt()
        val g = gcd(wi, hi)
        return (wi / g) to (hi / g)
    }

    /** PiP entry is attempted only for a live video call not already in PiP. */
    fun shouldEnterPip(isVideoCall: Boolean, sessionLive: Boolean, alreadyInPip: Boolean): Boolean =
        isVideoCall && sessionLive && !alreadyInPip

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val t = x % y
            x = y
            y = t
        }
        return if (x <= 0) 1 else x
    }
}
