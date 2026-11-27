package com.wesal.messenger

import com.wesal.messenger.core.util.AlphaBounds
import com.wesal.messenger.core.util.FramedCutoutLayout
import com.wesal.messenger.core.util.FramedCutoutTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the round-3 framed-cutout layout math, on the royal ADMIN frame
 * geometry (aperture centre Y 0.396, diameter 0.496 of the container, 10% pop-out strip).
 */
class FramedCutoutLayoutTest {

    private val s = 1000f
    private val cyF = 0.396f
    private val dF = 0.496f
    private val stripF = 0.10f
    private val apertureD = s * dF // 496
    private val apertureTop = s * cyF - apertureD / 2f // 148
    private val strip = s * stripF // 100
    private val maxPersonH = apertureD + strip + 0.35f * apertureD // 769.6

    private fun compute(bounds: AlphaBounds, imgW: Int, imgH: Int, pad: Float = 0f): FramedCutoutTransform =
        FramedCutoutLayout.compute(
            containerPx = s,
            apertureCenterYFraction = cyF,
            apertureDiameterFraction = dF,
            stripFraction = stripF,
            bounds = bounds,
            imageWidthPx = imgW,
            imageHeightPx = imgH,
            padFraction = pad
        )

    /** Asserts the (possibly padded) person box lands exactly as the composite requires. */
    private fun assertPersonLands(t: FramedCutoutTransform, box: AlphaBounds, imgW: Int, imgH: Int) {
        val personTop = t.offsetYPx + box.top * t.drawHeightPx
        val personWidth = box.width * t.drawWidthPx
        val personCentreX = t.offsetXPx + (box.left + box.width / 2f) * t.drawWidthPx
        assertEquals(apertureTop - strip, personTop, 1f)
        assertEquals(apertureD, personWidth, 1f)
        assertEquals(s / 2f, personCentreX, 1f)
        // Sanity: draw size is the source scaled uniformly (aspect preserved).
        assertEquals(imgW * t.scale, t.drawWidthPx, 0.5f)
        assertEquals(imgH * t.scale, t.drawHeightPx, 0.5f)
    }

    @Test
    fun compositeLikePerson_fillsApertureHeadAtStripTop() {
        // Person occupies 90% of the canvas width, like the owner's reference composite.
        val bounds = AlphaBounds(0.05f, 0.02f, 0.95f, 0.98f, 0.15f)
        val t = compute(bounds, 640, 640)
        assertPersonLands(t, bounds, 640, 640)
        // Width-first scale: aperture / person box width in px.
        assertEquals(apertureD / (0.90f * 640f), t.scale, 0.001f)
    }

    @Test
    fun tallFullBody_heightGuardClampsScale() {
        // Narrow, full-height person: width-first fit would make him 1240px tall.
        val bounds = AlphaBounds(0.30f, 0f, 0.70f, 1f, 0.6f)
        val t = compute(bounds, 640, 640)
        val personHeight = bounds.height * t.drawHeightPx
        assertEquals(maxPersonH, personHeight, 1f)
        assertEquals(maxPersonH / (bounds.height * 640f), t.scale, 0.001f)
        // Still centred horizontally and head at the strip top.
        val personCentreX = t.offsetXPx + (bounds.left + bounds.width / 2f) * t.drawWidthPx
        assertEquals(s / 2f, personCentreX, 1f)
        assertEquals(apertureTop - strip, t.offsetYPx + bounds.top * t.drawHeightPx, 1f)
    }

    @Test
    fun fullCanvasBounds_canvasTopLandsAtStripTopCentred() {
        // Degenerate case: bounds == whole canvas. The transform must place the canvas
        // top at the strip top and centre it (no wild offsets for near-normal photos).
        val bounds = AlphaBounds(0f, 0f, 1f, 1f, 0f)
        val t = compute(bounds, 640, 640)
        assertEquals(apertureTop - strip, t.offsetYPx, 1f)
        assertEquals(s / 2f, t.offsetXPx + t.drawWidthPx / 2f, 1f)
        assertEquals(apertureD / 640f, t.scale, 0.001f)
    }

    @Test
    fun smallPerson_scaleUpCappedByHeightGuardAndStaysCentred() {
        // Person is only 20% of the canvas wide / 40% tall (deep padding).
        val bounds = AlphaBounds(0.40f, 0.30f, 0.60f, 0.70f, 0.92f)
        val t = compute(bounds, 640, 640)
        assertTrue(t.drawWidthPx.isFinite() && t.drawHeightPx.isFinite())
        // Height guard engages (width-first height would be 992px > 769.6px cap).
        assertEquals(maxPersonH, bounds.height * t.drawHeightPx, 1f)
        val personCentreX = t.offsetXPx + (bounds.left + bounds.width / 2f) * t.drawWidthPx
        assertEquals(s / 2f, personCentreX, 1f)
        assertEquals(apertureTop - strip, t.offsetYPx + bounds.top * t.drawHeightPx, 1f)
    }

    @Test
    fun defaultPadding_paddedBoxIsWhatLands() {
        val bounds = AlphaBounds(0.05f, 0.02f, 0.95f, 0.98f, 0.15f)
        val t = compute(bounds, 640, 640, pad = FramedCutoutLayout.DEFAULT_PAD_FRACTION)
        assertPersonLands(t, bounds.padded(FramedCutoutLayout.DEFAULT_PAD_FRACTION), 640, 640)
    }

    @Test
    fun nonSquareImage_aspectPreservedAndPersonLands() {
        // Portrait source 480x640, person centred.
        val bounds = AlphaBounds(0.10f, 0.05f, 0.90f, 0.95f, 0.28f)
        val t = compute(bounds, 480, 640)
        assertPersonLands(t, bounds, 480, 640)
    }
}
