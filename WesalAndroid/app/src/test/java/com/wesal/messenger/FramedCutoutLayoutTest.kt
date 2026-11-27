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
 * Round-6 geometry (item 10 — replaces the round-4 pop-out spec the owner rejected
 * on device): HEIGHT-FIRST, BOTTOM-ANCHORED, head INSIDE the ring.
 * - person height = aperture x (1 - 0.10 clearance + 0.25 overflow) = 1.15 x aperture;
 * - box bottom anchored at apertureBottom + 0.25 x aperture (torso continues behind
 *   the frame's bottom artwork — no void inside the ring);
 * - box top lands at apertureTop + 0.10 x aperture (head near the top INSIDE the
 *   ring, clear of the crown ornament zone);
 * - rendered width capped at 1.35 x aperture (bottom anchor kept when it binds);
 * - the over-frame strip is just the 2%-of-container crown-margin sliver.
 */
class FramedCutoutLayoutTest {

    private val s = 1000f
    private val cyF = 0.396f
    private val dF = 0.496f
    private val apertureD = s * dF // 496
    private val apertureTop = s * cyF - apertureD / 2f // 148
    private val apertureBottom = apertureTop + apertureD // 644
    private val crown = FramedCutoutLayout.CROWN_MARGIN_CONTAINER_FRACTION * s // 20
    private val clearance = FramedCutoutLayout.HEAD_CLEARANCE_APERTURE_FRACTION * apertureD // 19.84
    private val overflow = FramedCutoutLayout.BOTTOM_OVERFLOW_APERTURE_FRACTION * apertureD // 124
    private val targetPersonH = apertureD - clearance + overflow // 600.16

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

    private fun personBottom(t: FramedCutoutTransform, box: AlphaBounds): Float =
        t.offsetYPx + box.bottom * t.drawHeightPx

    private fun personHeight(t: FramedCutoutTransform, box: AlphaBounds): Float =
        box.height * t.drawHeightPx

    private fun personWidth(t: FramedCutoutTransform, box: AlphaBounds): Float =
        box.width * t.drawWidthPx

    /** The core round-6 invariants: fill-from-bottom, head inside, no void. */
    private fun assertFillFromBottomHeadInside(t: FramedCutoutTransform, box: AlphaBounds) {
        // Bottom anchored at the overflow point below the aperture's bottom edge:
        // the torso continues behind the bottom artwork, so the ring's bottom is
        // never a bare glow void.
        assertEquals(apertureBottom + overflow, personBottom(t, box), 1f)
        // Head INSIDE the ring: the box top never rises above the aperture's top
        // edge (no frame ornament can cover face/hair), and with the height-first
        // scale it sits at or below the clearance point.
        assertTrue(
            "person top must stay inside the aperture (top=${personTop(t, box)}, apertureTop=$apertureTop)",
            personTop(t, box) >= apertureTop + clearance - 1f
        )
        // No void: the person spans the aperture's full height.
        assertTrue(personTop(t, box) <= apertureTop + clearance + 1f || personHeight(t, box) < targetPersonH)
        assertTrue(personBottom(t, box) > apertureBottom)
        // The over-frame strip is only the crown-margin sliver now.
        assertEquals(crown, t.stripPx, 0.5f)
    }

    private fun assertCentredAndAspect(t: FramedCutoutTransform, box: AlphaBounds, imgW: Int, imgH: Int) {
        val personCentreX = t.offsetXPx + (box.left + box.width / 2f) * t.drawWidthPx
        assertEquals(s / 2f, personCentreX, 1f)
        // Draw size is the source scaled uniformly (aspect preserved).
        assertEquals(imgW * t.scale, t.drawWidthPx, 0.5f)
        assertEquals(imgH * t.scale, t.drawHeightPx, 0.5f)
    }

    @Test
    fun compositeLikePerson_fillsApertureFromBottom_headInsideRing() {
        // Person occupies 90% of the canvas width, like the owner's reference composite.
        val bounds = AlphaBounds(0.05f, 0.02f, 0.95f, 0.98f, 0.15f)
        val t = compute(bounds, 640, 640)
        // Height-first: person height is exactly the round-6 target (1.15 x aperture).
        assertEquals(targetPersonH, personHeight(t, bounds), 1f)
        assertEquals(targetPersonH / (bounds.height * 640f), t.scale, 0.001f)
        // Head top lands exactly at the clearance point inside the ring.
        assertEquals(apertureTop + clearance, personTop(t, bounds), 1f)
        // Person spans ~1.13 x aperture wide (the circle clips the sides, so the
        // shoulders meet the ring like the reference composite's ~0.92 visible span).
        assertTrue(personWidth(t, bounds) > 0.85f * apertureD)
        assertTrue(personWidth(t, bounds) <= FramedCutoutLayout.MAX_WIDTH_APERTURE_FRACTION * apertureD)
        assertFillFromBottomHeadInside(t, bounds)
        assertCentredAndAspect(t, bounds, 640, 640)
    }

    @Test
    fun ownerTightCutout_noBottomVoid_headClearOfCrownZone() {
        // Owner-like tight cutout (80% wide x 95% tall) — the shape that rendered
        // small/raised with a 0.45xD void under the old pop-out math.
        val bounds = AlphaBounds(0.10f, 0.025f, 0.90f, 0.975f, 0.24f)
        val t = compute(bounds, 640, 640)
        assertEquals(targetPersonH, personHeight(t, bounds), 1f)
        assertEquals(apertureTop + clearance, personTop(t, bounds), 1f)
        assertEquals(apertureBottom + overflow, personBottom(t, bounds), 1f)
        assertFillFromBottomHeadInside(t, bounds)
        assertCentredAndAspect(t, bounds, 640, 640)
    }

    @Test
    fun tallFullBody_heightFirstKeepsHeadInsideAndBottomFilled() {
        // Narrow full-body person: height-first fit makes him 1.15 x aperture tall
        // and narrower than the aperture (glow at the sides, like the reference's
        // side glow) — the old width-first/guard math exploded him to 2.1 x aperture.
        val bounds = AlphaBounds(0.30f, 0f, 0.70f, 1f, 0.6f)
        val t = compute(bounds, 640, 640)
        assertEquals(targetPersonH, personHeight(t, bounds), 1f)
        assertTrue(personWidth(t, bounds) < apertureD)
        assertFillFromBottomHeadInside(t, bounds)
        assertCentredAndAspect(t, bounds, 640, 640)
    }

    @Test
    fun wideShortCutout_widthCapBinds_bottomAnchorKept() {
        // Very wide/short box (1.6 x wider than tall): height-first scale would
        // render him 1.94 x aperture wide; the 1.35 cap binds instead. Height
        // shrinks, the bottom anchor is kept, and the top sits LOWER inside the
        // ring (never above the clearance point, never a bottom void).
        val bounds = AlphaBounds(0.02f, 0.30f, 0.98f, 0.90f, 0.35f)
        val t = compute(bounds, 640, 640)
        assertEquals(
            FramedCutoutLayout.MAX_WIDTH_APERTURE_FRACTION * apertureD,
            personWidth(t, bounds),
            1f
        )
        assertTrue(personHeight(t, bounds) < targetPersonH)
        assertEquals(apertureBottom + overflow, personBottom(t, bounds), 1f)
        assertTrue(personTop(t, bounds) > apertureTop + clearance)
        assertFillFromBottomHeadInside(t, bounds)
        assertCentredAndAspect(t, bounds, 640, 640)
    }

    @Test
    fun fullCanvasBounds_fillsFromBottom() {
        // Degenerate case: bounds == whole canvas.
        val bounds = AlphaBounds(0f, 0f, 1f, 1f, 0f)
        val t = compute(bounds, 640, 640)
        assertEquals(targetPersonH / 640f, t.scale, 0.001f)
        assertEquals(apertureBottom + overflow - targetPersonH, t.offsetYPx, 1f)
        assertEquals(apertureTop + clearance, personTop(t, bounds), 1f)
        assertEquals(s / 2f, t.offsetXPx + t.drawWidthPx / 2f, 1f)
        assertFillFromBottomHeadInside(t, bounds)
    }

    @Test
    fun smallPerson_scaledUpToFillFromBottom() {
        // Person is only 20% of the canvas wide / 40% tall (deep transparent
        // padding): the bounds fit scales the PERSON up to the full target height
        // instead of rendering the padded canvas small inside the ring.
        val bounds = AlphaBounds(0.40f, 0.30f, 0.60f, 0.70f, 0.92f)
        val t = compute(bounds, 640, 640)
        assertTrue(t.drawWidthPx.isFinite() && t.drawHeightPx.isFinite())
        assertEquals(targetPersonH, personHeight(t, bounds), 1f)
        assertFillFromBottomHeadInside(t, bounds)
        assertCentredAndAspect(t, bounds, 640, 640)
    }

    @Test
    fun defaultPadding_paddedBoxIsWhatLands() {
        val bounds = AlphaBounds(0.05f, 0.02f, 0.95f, 0.98f, 0.15f)
        val padded = bounds.padded(FramedCutoutLayout.DEFAULT_PAD_FRACTION)
        val t = compute(bounds, 640, 640, pad = FramedCutoutLayout.DEFAULT_PAD_FRACTION)
        assertEquals(targetPersonH, personHeight(t, padded), 1f)
        assertFillFromBottomHeadInside(t, padded)
        assertCentredAndAspect(t, padded, 640, 640)
    }

    @Test
    fun nonSquareImage_aspectPreservedAndPersonLands() {
        // Portrait source 480x640, person centred.
        val bounds = AlphaBounds(0.10f, 0.05f, 0.90f, 0.95f, 0.28f)
        val t = compute(bounds, 480, 640)
        assertEquals(targetPersonH, personHeight(t, bounds), 1f)
        assertFillFromBottomHeadInside(t, bounds)
        assertCentredAndAspect(t, bounds, 480, 640)
    }

    @Test
    fun stripIsOnlyTheCrownMarginSliver() {
        // Round 6: the head no longer pops out, so the over-frame strip window is
        // exactly the crown margin — independent of the person or the frame.
        val bounds = AlphaBounds(0.10f, 0.025f, 0.90f, 0.975f, 0.24f)
        val t = compute(bounds, 640, 640)
        assertEquals(crown, t.stripPx, 0.5f)
        assertTrue(t.stripPx < 0.05f * s)
    }
}
