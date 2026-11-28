package com.wesal.messenger.core.util

/**
 * Round 20: screen-share capture sizing, extracted pure so the rule
 * is JVM-testable.
 *
 * The screen capturer is started with the display's real size, capped
 * so the long edge never exceeds [MAX_LONG_EDGE_PX]: full-resolution
 * panels (1440p+) would otherwise push an oversized video track the
 * encoder and the free TURN path struggle with, for no visible gain —
 * 1080p keeps shared text crisp. Dimensions are rounded DOWN to even
 * numbers, which hardware encoders require.
 */
object ScreenShareLogic {

    const val MAX_LONG_EDGE_PX = 1920
    const val SHARE_FPS = 15

    /** Returns the (width, height) to start screen capture with. */
    fun targetSize(displayWidth: Int, displayHeight: Int): Pair<Int, Int> {
        if (displayWidth <= 0 || displayHeight <= 0) return 720 to 1280
        val longEdge = maxOf(displayWidth, displayHeight)
        val scale = if (longEdge > MAX_LONG_EDGE_PX) {
            MAX_LONG_EDGE_PX.toDouble() / longEdge.toDouble()
        } else {
            1.0
        }
        val w = evenFloor((displayWidth * scale).toInt())
        val h = evenFloor((displayHeight * scale).toInt())
        return w to h
    }

    private fun evenFloor(value: Int): Int {
        val even = value - (value % 2)
        return if (even < 2) 2 else even
    }
}
