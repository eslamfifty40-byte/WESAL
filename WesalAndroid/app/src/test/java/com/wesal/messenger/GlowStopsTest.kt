package com.wesal.messenger

import com.wesal.messenger.core.util.FrameGlowDisc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the round-3 framed glow disc ([FrameGlowDisc]). The critical
 * regression guard: EVERY stop must stay fully opaque (alpha 0xFF) at every intensity —
 * a translucent stop would let the profile cover show through the frame's opening.
 */
class GlowStopsTest {

    private val adminRed = 0xFFFF3B1FL // royal ADMIN frame default glow
    private val custGold = 0xFFFFD54FL // cust_01 gold

    private fun alpha(argb: Long): Long = (argb shr 24) and 0xFF
    private fun channel(argb: Long, shift: Int): Int = ((argb shr shift) and 0xFF).toInt()
    private fun luminance(argb: Long): Float =
        0.299f * channel(argb, 16) + 0.587f * channel(argb, 8) + 0.114f * channel(argb, 0)

    @Test
    fun everyStopFullyOpaque_atAllIntensities() {
        val glows = listOf(adminRed, custGold, 0xFF00E5FFL, 0xFF123456L, 0xFF000000L)
        val intensities = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        for (glow in glows) {
            for (intensity in intensities) {
                val stops = FrameGlowDisc.discStops(glow, intensity)
                assertEquals("core alpha, glow=$glow i=$intensity", 0xFFL, alpha(stops.coreArgb))
                assertEquals("mid alpha, glow=$glow i=$intensity", 0xFFL, alpha(stops.midArgb))
                assertEquals("rim alpha, glow=$glow i=$intensity", 0xFFL, alpha(stops.rimArgb))
            }
        }
    }

    @Test
    fun stopsDarkenMonotonicallyCoreToRim() {
        for (glow in listOf(adminRed, custGold)) {
            for (intensity in listOf(0f, 0.5f, 1f)) {
                val stops = FrameGlowDisc.discStops(glow, intensity)
                assertTrue(
                    "core >= mid, glow=$glow i=$intensity",
                    luminance(stops.coreArgb) >= luminance(stops.midArgb)
                )
                assertTrue(
                    "mid >= rim, glow=$glow i=$intensity",
                    luminance(stops.midArgb) >= luminance(stops.rimArgb)
                )
            }
        }
    }

    @Test
    fun intensityZero_reproducesTheOldFlatDarkCore() {
        // The pre-round-3 disc core was glow x 0.30. At intensity 0 we must be no
        // brighter than that (per channel, +/- 1 for rounding).
        for (glow in listOf(adminRed, custGold)) {
            val core = FrameGlowDisc.discStops(glow, 0f).coreArgb
            for (shift in listOf(16, 8, 0)) {
                val expected = channel(glow, shift) * 0.30f
                assertEquals(expected, channel(core, shift).toFloat(), 1.5f)
            }
        }
    }

    @Test
    fun intensityOne_coreIsBrighterThanTheGlowItself() {
        // The lightened core: at full intensity the core luminance exceeds the raw glow.
        for (glow in listOf(adminRed, custGold)) {
            val core = FrameGlowDisc.discStops(glow, 1f).coreArgb
            assertTrue(
                "core brighter than glow, glow=$glow",
                luminance(core) > luminance(glow)
            )
        }
    }

    @Test
    fun huePreserved_redAndGoldKeepChannelOrdering() {
        // Both test colours are R > G > B; every stop must keep that ordering (hue kept).
        for (glow in listOf(adminRed, custGold)) {
            for (intensity in listOf(0f, 0.5f, 1f)) {
                val stops = FrameGlowDisc.discStops(glow, intensity)
                for (stop in listOf(stops.coreArgb, stops.midArgb)) {
                    assertTrue(channel(stop, 16) >= channel(stop, 8))
                    assertTrue(channel(stop, 8) >= channel(stop, 0))
                }
            }
        }
    }

    @Test
    fun intensityIsMonotonic_coreBrightensWithIntensity() {
        var previous = -1f
        for (intensity in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val core = FrameGlowDisc.discStops(adminRed, intensity).coreArgb
            val lum = luminance(core)
            assertTrue("core luminance must not decrease at i=$intensity", lum >= previous)
            previous = lum
        }
    }
}
