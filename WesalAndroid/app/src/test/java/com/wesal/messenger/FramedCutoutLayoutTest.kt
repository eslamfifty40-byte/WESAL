package com.wesal.messenger

import com.wesal.messenger.core.util.AlphaBounds
import com.wesal.messenger.core.util.FramedCutoutLayout
import com.wesal.messenger.core.util.FramedCutoutTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the framed-cutout layout math, on the royal ADMIN frame geometry
 * (aperture centre Y 0.396, diameter 0.496 of the container).
 *
 * Round-4 geometry: the pop-out strip is DERIVED from the bounds — strip = 30% of the
 * rendered person height (head + neck), so the aperture's top edge (where the frame's
 * ring sits) lands behind the neck and the whole head renders over the frame. The
 * height guard is re-derived with the dynamic strip; the strip window additionally
 * carries a 2%-of-container crown margin.
 */
class FramedCutoutLayoutTest {

    private val s = 1000f
    private val cyF = 0.396f
    private val dF = 0.496f
    private val apertureD = s * dF // 496
    private val apertureTop = s * cyF - apertureD / 2f // 148
    private val crown = FramedCutoutLayout.CROWN_MARGIN_CONTAINER_FRACTION * s // 20
    private val headF = FramedCutoutLayout.HEAD_FRACTION // 0.30

    private fun compute(bounds: AlphaBounds, imgW: Int, imgH: Int, pad: Float = 0f): FramedCutoutTransform =
        FramedCutoutLayout.compute(
            containerPx = s,
            apertureCenterYFraction = cyF,
            apertureDiameterFraction = dF,
            bounds = bounds,
            imageWidthPx = imgW,
            imageHeightPx = imgH,
            padFraction = pad
        )

    private fun personTop(t: FramedCutoutTransform, box: AlphaBounds): Float =
        t.offsetYPx + box.top * t.drawHeightPx

    private fun personHeight(t: FramedCutoutTransform, box: AlphaBounds): Float =
        box.height * t.drawHeightPx

    /**
     * The core round-4 invariant: the strip places at least the top
     * [FramedCutoutLayout.HEAD_FRACTION] (30%) of the person above the aperture's top
     * edge — head + neck over the frame, ring behind the neck — and the strip window
     * matches that strip plus the crown margin.
     */
    private fun assertRingBehindNeck(t: FramedCutoutTransform, box: AlphaBounds) {
        val aboveAperture = apertureTop - personTop(t, box)
        val personH = personHeight(t, box)
        assertTrue(
            "top 30% of the person must be above the aperture top (above=$aboveAperture, personH=$personH)",
            aboveAperture >= headF * personH - 1f
        )
        assertEquals(headF * personH, aboveAperture, 1.5f)
        assertEquals(headF * personH + crown, t.stripPx, 1.5f)
    }

    private fun assertCentredAndAspect(t: FramedCutoutTransform, box: AlphaBounds, imgW: Int, imgH: Int) {
        val personCentreX = t.offsetXPx + (box.left + box.width / 2f) * t.drawWidthPx
        assertEquals(s / 2f, personCentreX, 1f)
        // Draw size is the source scaled uniformly (aspect preserved).
        assertEquals(imgW * t.scale, t.drawWidthPx, 0.5f)
        assertEquals(imgH * t.scale, t.drawHeightPx, 0.5f)
    }

    @Test
    fun compositeLikePerson_fillsApertureRingBehindNeck() {
        // Person occupies 90% of the canvas width, like the owner's reference composite.
        val bounds = AlphaBounds(0.05f, 0.02f, 0.95f, 0.98f, 0.15f)
        val t = compute(bounds, 640, 640)
        // Width-first fit: person width == aperture diameter.
        assertEquals(apertureD, bounds.width * t.drawWidthPx, 1f)
        assertEquals(apertureD / (0.90f * 640f), t.scale, 0.001f)
        assertRingBehindNeck(t, bounds)
        assertCentredAndAspect(t, bounds, 640, 640)
    }

    @Test
    fun ownerTightCutout_wholeHeadAboveAperture() {
        // Owner-like tight cutout (80% wide x 95% tall). The head alone is ~22% of the
        // person height; the 30% strip must clear it entirely.
        val bounds = AlphaBounds(0.10f, 0.025f, 0.90f, 0.975f, 0.24f)
        val t = compute(bounds, 640, 640)
        assertEquals(apertureD, bounds.width * t.drawWidthPx, 1f)
        val aboveAperture = apertureTop - personTop(t, bounds)
        assertTrue(aboveAperture >= 0.22f * personHeight(t, bounds))
        assertRingBehindNeck(t, bounds)
        assertCentredAndAspect(t, bounds, 640, 640)
    }

    @Test
    fun tallFullBody_heightGuardClampsScale() {
        // Narrow, full-height person: width-first fit would make him 1240px tall with
        // a 372px strip; the guard (aperture + strip + 0.35 aperture = 1041.6) clamps.
        val bounds = AlphaBounds(0.30f, 0f, 0.70f, 1f, 0.6f)
        val t = compute(bounds, 640, 640)
        val expectedMaxPersonH = apertureD + headF * 1240f +
            FramedCutoutLayout.HEIGHT_GUARD_EXTRA_APERTURE_FRACTION * apertureD // 1041.6
        assertEquals(expectedMaxPersonH, personHeight(t, bounds), 1f)
        assertEquals(expectedMaxPersonH / (bounds.height * 640f), t.scale, 0.001f)
        // After clamping, the strip follows the final person height: ring at the neck.
        assertRingBehindNeck(t, bounds)
        assertCentredAndAspect(t, bounds, 640, 640)
    }

    @Test
    fun extremeFullBody_stripCapFallsBackToChinForGuard() {
        // Very narrow full-body: width-first person height 1653px -> head strip would
        // be 496px, above the 40%-of-container cap, so the guard is derived with the
        // CHIN strip (0.22 x 1653.3 = 363.7): max person height = 496+363.7+173.6.
        val bounds = AlphaBounds(0.35f, 0f, 0.65f, 1f, 0.7f)
        val t = compute(bounds, 640, 640)
        val expectedMaxPersonH = apertureD + FramedCutoutLayout.CHIN_FRACTION * (640f * apertureD / (0.30f * 640f)) +
            FramedCutoutLayout.HEIGHT_GUARD_EXTRA_APERTURE_FRACTION * apertureD // 1033.3
        assertEquals(expectedMaxPersonH, personHeight(t, bounds), 1f)
        // The rendered strip window never exceeds the cap (+ crown margin).
        assertTrue(t.stripPx <= FramedCutoutLayout.MAX_STRIP_CONTAINER_FRACTION * s + crown + 0.5f)
        assertRingBehindNeck(t, bounds)
        assertCentredAndAspect(t, bounds, 640, 640)
    }

    @Test
    fun fullCanvasBounds_placedByDynamicStrip() {
        // Degenerate case: bounds == whole canvas. Width-first scale is unchanged; the
        // canvas top lands at the dynamic strip top (apertureTop - 0.30 x personH).
        val bounds = AlphaBounds(0f, 0f, 1f, 1f, 0f)
        val t = compute(bounds, 640, 640)
        assertEquals(apertureD / 640f, t.scale, 0.001f)
        assertEquals(apertureTop - headF * apertureD, t.offsetYPx, 1f)
        assertEquals(s / 2f, t.offsetXPx + t.drawWidthPx / 2f, 1f)
    }

    @Test
    fun smallPerson_scaleUpCappedByHeightGuardAndStaysCentred() {
        // Person is only 20% of the canvas wide / 40% tall (deep padding). Width-first
        // person height 992px, strip 297.6px; guard caps height at 496+297.6+173.6.
        val bounds = AlphaBounds(0.40f, 0.30f, 0.60f, 0.70f, 0.92f)
        val t = compute(bounds, 640, 640)
        assertTrue(t.drawWidthPx.isFinite() && t.drawHeightPx.isFinite())
        val expectedMaxPersonH = apertureD + headF * 992f +
            FramedCutoutLayout.HEIGHT_GUARD_EXTRA_APERTURE_FRACTION * apertureD // 967.2
        assertEquals(expectedMaxPersonH, personHeight(t, bounds), 1f)
        assertRingBehindNeck(t, bounds)
        assertCentredAndAspect(t, bounds, 640, 640)
    }

    @Test
    fun defaultPadding_paddedBoxIsWhatLands() {
        val bounds = AlphaBounds(0.05f, 0.02f, 0.95f, 0.98f, 0.15f)
        val padded = bounds.padded(FramedCutoutLayout.DEFAULT_PAD_FRACTION)
        val t = compute(bounds, 640, 640, pad = FramedCutoutLayout.DEFAULT_PAD_FRACTION)
        assertEquals(apertureD, padded.width * t.drawWidthPx, 1f)
        assertRingBehindNeck(t, padded)
        assertCentredAndAspect(t, padded, 640, 640)
    }

    @Test
    fun nonSquareImage_aspectPreservedAndPersonLands() {
        // Portrait source 480x640, person centred.
        val bounds = AlphaBounds(0.10f, 0.05f, 0.90f, 0.95f, 0.28f)
        val t = compute(bounds, 480, 640)
        assertEquals(apertureD, bounds.width * t.drawWidthPx, 1f)
        assertRingBehindNeck(t, bounds)
        assertCentredAndAspect(t, bounds, 480, 640)
    }
}
