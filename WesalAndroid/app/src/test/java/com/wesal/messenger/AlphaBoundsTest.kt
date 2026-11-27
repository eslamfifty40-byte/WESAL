package com.wesal.messenger

import com.wesal.messenger.core.util.AlphaBounds
import com.wesal.messenger.core.util.analyzeAlphaBounds
import com.wesal.messenger.core.util.isCutoutBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the round-3 alpha analysis core ([analyzeAlphaBounds] /
 * [isCutoutBounds]) on synthetic pixel grids — no Android classes involved.
 */
class AlphaBoundsTest {

    private fun argb(alpha: Int, r: Int = 0x22, g: Int = 0x44, b: Int = 0x66): Int =
        (alpha shl 24) or (r shl 16) or (g shl 8) or b

    /** Builds a w x h grid, transparent everywhere except the given opaque rect (inclusive). */
    private fun grid(w: Int, h: Int, opaque: IntRange, opaqueRows: IntRange): IntArray {
        val px = IntArray(w * h) { argb(0) }
        for (y in opaqueRows) {
            for (x in opaque) {
                px[y * w + x] = argb(0xFF)
            }
        }
        return px
    }

    @Test
    fun fullOpaque_fullCanvasBoxZeroTransparencyNotCutout() {
        val px = IntArray(10 * 10) { argb(0xFF) }
        val b = analyzeAlphaBounds(px, 10, 10)
        assertNotNull(b)
        b!!
        assertEquals(0f, b.left, 0.001f)
        assertEquals(0f, b.top, 0.001f)
        assertEquals(1f, b.right, 0.001f)
        assertEquals(1f, b.bottom, 0.001f)
        assertEquals(0f, b.transparentFraction, 0.001f)
        assertFalse(isCutoutBounds(b))
    }

    @Test
    fun centredPerson_exactFractions() {
        // 100x100 canvas, person occupies x 25..74, y 10..89 (50% wide, 80% tall).
        val px = grid(100, 100, 25..74, 10..89)
        val b = analyzeAlphaBounds(px, 100, 100)!!
        assertEquals(0.25f, b.left, 0.001f)
        assertEquals(0.10f, b.top, 0.001f)
        assertEquals(0.75f, b.right, 0.001f)
        assertEquals(0.90f, b.bottom, 0.001f)
        // transparent = 1 - (50*80)/10000 = 0.6
        assertEquals(0.6f, b.transparentFraction, 0.001f)
        assertTrue(isCutoutBounds(b))
    }

    @Test
    fun oneSidedTransparency_oneSidedBox() {
        // Opaque right half only.
        val px = grid(100, 100, 50..99, 0..99)
        val b = analyzeAlphaBounds(px, 100, 100)!!
        assertEquals(0.5f, b.left, 0.001f)
        assertEquals(1f, b.right, 0.001f)
        assertEquals(0f, b.top, 0.001f)
        assertEquals(1f, b.bottom, 0.001f)
        assertEquals(0.5f, b.transparentFraction, 0.001f)
    }

    @Test
    fun allTransparent_returnsNull() {
        val px = IntArray(20 * 20) { argb(0) }
        assertNull(analyzeAlphaBounds(px, 20, 20))
        assertFalse(isCutoutBounds(null))
    }

    @Test
    fun singleOpaquePixel_degenerateBoxClampedToMinimumSize() {
        val px = IntArray(100 * 100) { argb(0) }
        px[50 * 100 + 50] = argb(0xFF)
        val b = analyzeAlphaBounds(px, 100, 100)!!
        assertEquals(AlphaBounds.MIN_BOX_FRACTION, b.width, 0.001f)
        assertEquals(AlphaBounds.MIN_BOX_FRACTION, b.height, 0.001f)
        // Centred on the pixel (0.505, 0.505).
        assertEquals(0.505f, (b.left + b.right) / 2f, 0.002f)
        assertEquals(0.505f, (b.top + b.bottom) / 2f, 0.002f)
    }

    @Test
    fun alphaThreshold31Transparent32Opaque() {
        // 10x1 row: alpha 31 counts transparent at threshold 32, alpha 32 counts opaque.
        val px = IntArray(10) { i -> if (i < 5) argb(31) else argb(32) }
        val b = analyzeAlphaBounds(px, 10, 1, alphaThreshold = 32)!!
        assertEquals(0.5f, b.left, 0.001f)
        assertEquals(1f, b.right, 0.001f)
        assertEquals(0.5f, b.transparentFraction, 0.001f)
    }

    @Test
    fun nonSquareCanvas_fractionsNormalisedPerAxis() {
        // 200x100 canvas, opaque = right half x 100..199, top half y 0..49.
        val px = grid(200, 100, 100..199, 0..49)
        val b = analyzeAlphaBounds(px, 200, 100)!!
        assertEquals(0.5f, b.left, 0.001f)
        assertEquals(1f, b.right, 0.001f)
        assertEquals(0f, b.top, 0.001f)
        assertEquals(0.5f, b.bottom, 0.001f)
    }

    @Test
    fun verdictThresholds_rejectMarginalTransparencyAndOversizedBoxes() {
        // Transparency below 2% -> not a cutout.
        assertFalse(isCutoutBounds(AlphaBounds(0f, 0f, 1f, 1f, 0.019f)))
        // Box area above 92% -> not a cutout even with plenty of transparency.
        assertFalse(isCutoutBounds(AlphaBounds(0.02f, 0.02f, 0.98f, 0.98f, 0.5f)))
        // The negative-result sentinel is never a cutout.
        assertFalse(isCutoutBounds(AlphaBounds.NOT_A_CUTOUT))
        // A genuine cutout passes.
        assertTrue(isCutoutBounds(AlphaBounds(0.25f, 0.10f, 0.75f, 0.90f, 0.6f)))
    }

    @Test
    fun padded_expandsAndClampsToCanvas() {
        val b = AlphaBounds(0.25f, 0.10f, 0.75f, 0.90f, 0.6f).padded(0.03f)
        assertEquals(0.22f, b.left, 0.001f)
        assertEquals(0.07f, b.top, 0.001f)
        assertEquals(0.78f, b.right, 0.001f)
        assertEquals(0.93f, b.bottom, 0.001f)
        val clamped = AlphaBounds(0.01f, 0.01f, 0.99f, 0.99f, 0.1f).padded(0.03f)
        assertEquals(0f, clamped.left, 0.001f)
        assertEquals(0f, clamped.top, 0.001f)
        assertEquals(1f, clamped.right, 0.001f)
        assertEquals(1f, clamped.bottom, 0.001f)
    }

    @Test
    fun invalidInput_returnsNull() {
        assertNull(analyzeAlphaBounds(IntArray(0), 0, 0))
        assertNull(analyzeAlphaBounds(IntArray(4), 10, 10)) // buffer smaller than w*h
    }
}
