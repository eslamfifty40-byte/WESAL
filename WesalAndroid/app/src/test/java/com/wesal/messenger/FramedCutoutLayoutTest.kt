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
 * Round 9 (item 7): the clearance fraction was re-measured on the owner's
 * definitive reference composite and set to 0.03 (overflow unchanged at 0.25),
 * so the expectations below are derived from the shipped constants:
 * - person height = aperture x (1 - clearance + 0.25 overflow) = 1.22 x aperture;
 * - box bottom anchored at apertureBottom + 0.25 x aperture (torso continues behind
 *   the frame's bottom artwork — no void inside the ring);
 * - box top lands at apertureTop + clearance x aperture (head near the top INSIDE
 *   the ring, clear of the crown ornament zone);
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
    private val clearance = FramedCutoutLayout.HEAD_CLEARANCE_APERTURE_FRACTION * apertureD // 14.88
    private val overflow = FramedCutoutLayout.BOTTOM_OVERFLOW_APERTURE_FRACTION * apertureD // 124
    private val targetPersonH = apertureD - clearance + overflow // 605.12

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
        // Bottom reaches AT LEAST the overflow point below the aperture's
        // bottom edge: the torso continues behind the bottom artwork, so the
        // ring's bottom is never a bare glow void. (Round-12 addendum 5: when
        // the width fill boosts the scale, the head-clearance anchor lets the
        // bottom overflow FURTHER behind the plaque — more is fine, less is
        // the void this invariant exists to forbid. Unboosted cases still
        // land exactly on the point.)
        assertTrue(
            "person bottom must reach the overflow point (bottom=${personBottom(t, box)}, point=${apertureBottom + overflow})",
            personBottom(t, box) >= apertureBottom + overflow - 1f
        )
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
        // Height-first: person height is exactly the target (1.22 x aperture).
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
        // Narrow full-body person. Round-12 addendum 5 supersedes the old
        // exact-height expectation for narrow figures: the width fill now
        // raises the scale (capped at 1.45x the height-fit scale) and pins
        // the head at the clearance point, so the figure is TALLER than
        // 1.22 x aperture with the extra height overflowing behind the
        // plaque. Even boosted, this very narrow figure stays narrower
        // than the aperture (side glow, like the reference) — the old
        // width-first/guard math that exploded him to 2.1 x aperture is
        // still impossible. Head-inside + bottom-filled invariants hold.
        val bounds = AlphaBounds(0.30f, 0f, 0.70f, 1f, 0.6f)
        val t = compute(bounds, 640, 640)
        assertTrue(personHeight(t, bounds) > targetPersonH)
        assertEquals(apertureTop + clearance, personTop(t, bounds), 1f)
        assertTrue(personBottom(t, bounds) > apertureBottom + overflow)
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
        // padding): the bounds fit scales the PERSON up instead of rendering
        // the padded canvas small inside the ring. Round-12 addendum 5: the
        // width fill boosts the scale further (capped), the head stays
        // pinned at the clearance point and the bottom overflows behind
        // the plaque — the person ends up at least the full target height.
        val bounds = AlphaBounds(0.40f, 0.30f, 0.60f, 0.70f, 0.92f)
        val t = compute(bounds, 640, 640)
        assertTrue(t.drawWidthPx.isFinite() && t.drawHeightPx.isFinite())
        assertTrue(personHeight(t, bounds) >= targetPersonH)
        assertEquals(apertureTop + clearance, personTop(t, bounds), 1f)
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
        // Portrait source 480x640, person centred. Round-12 addendum 5:
        // this figure renders 0.81 x aperture wide under the height fit —
        // the owner's exact rejected case — so the width fill raises the
        // scale until the person spans TARGET_MIN_WIDTH_FRACTION of the
        // aperture, head pinned at the clearance point, bottom
        // overflowing behind the plaque. Aspect + centring unchanged.
        val bounds = AlphaBounds(0.10f, 0.05f, 0.90f, 0.95f, 0.28f)
        val t = compute(bounds, 480, 640)
        assertEquals(
            FramedCutoutLayout.TARGET_MIN_WIDTH_FRACTION * apertureD,
            personWidth(t, bounds),
            1f
        )
        assertTrue(personHeight(t, bounds) > targetPersonH)
        assertEquals(apertureTop + clearance, personTop(t, bounds), 1f)
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

    // ---- Round 10 (item 9): opaque-photo reference zoom ----

    @Test
    fun opaqueZoom_squareEditorOutput_rendersAtReferenceProportions() {
        // Square source (the crop editor's output shape) into a square
        // aperture disc: Crop fill is 1:1, so the rendered canvas height is
        // exactly the reference figure total — 1.22 x aperture, the same
        // total the bounds-driven cutout fit targets.
        val factor = FramedCutoutLayout.opaqueScaleFactor(800f, 800f, 200f, 200f)
        assertEquals(200f * 1.22f, 800f * factor, 0.5f)
        assertEquals(1.22f, FramedCutoutLayout.OPAQUE_REFERENCE_ZOOM, 0.001f)
    }

    @Test
    fun opaqueZoom_isUniformCropFillTimesZoom() {
        // Portrait source 750x1000 into a 200x200 disc: Crop fill is
        // width-driven (200/750); the zoom multiplies it uniformly, so the
        // aspect is preserved and both dimensions exceed the disc (the
        // overflow is what the circle clips).
        val factor = FramedCutoutLayout.opaqueScaleFactor(750f, 1000f, 200f, 200f)
        assertEquals((200f / 750f) * 1.22f, factor, 0.001f)
        assertTrue(750f * factor >= 200f)
        assertTrue(1000f * factor >= 200f)
    }

    @Test
    fun opaqueZoom_degenerateSource_staysFinite() {
        val factor = FramedCutoutLayout.opaqueScaleFactor(0f, 0f, 200f, 200f)
        assertTrue(factor.isFinite() && factor > 0f)
    }

    // ------------------------------------------------------------------
    // Round 11 (item 7): subject-aware opaque fit. The r10 canvas zoom
    // rendered the owner's figure at ~0.84x aperture (measured); the
    // subject fit must land the FIGURE at the reference 1.22x aperture
    // with 0.03 head clearance for the measured subject model.
    // ------------------------------------------------------------------

    @Test
    fun opaqueSubjectFit_measuredModel_reproducesReferenceSplit() {
        // Square editor output (1000x1000) into a 200x200 aperture box.
        val fit = FramedCutoutLayout.opaqueSubjectFit(1000f, 1000f, 200f, 200f)
        val renderedH = 1000f * fit.scale
        // Subject (figure) height = 0.70 of canvas -> must render at 1.22x the aperture.
        val figureH = FramedCutoutLayout.OPAQUE_SUBJECT_HEIGHT_FRACTION * renderedH
        assertEquals(1.22f * 200f, figureH, 0.5f)
        // Reconstruct the drawn offset from the bias: offset = (dstH - renderedH) * (bias+1)/2.
        val offsetY = (200f - renderedH) * (fit.biasY + 1f) / 2f
        // Subject top in the source sits at (centre - height/2) = 0.44 - 0.35 = 0.09 of canvas.
        val subjectTopSrcFrac = FramedCutoutLayout.OPAQUE_SUBJECT_CENTER_Y_FRACTION -
            FramedCutoutLayout.OPAQUE_SUBJECT_HEIGHT_FRACTION / 2f
        val figureTop = offsetY + subjectTopSrcFrac * renderedH
        val figureBottom = figureTop + figureH
        // Head clearance = 0.03x aperture below the aperture top; bottom
        // overflows the aperture bottom by 0.25x aperture.
        assertEquals(0.03f * 200f, figureTop, 0.5f)
        assertEquals(200f + 0.25f * 200f, figureBottom, 0.5f)
        // Zoom vs plain Crop fill (0.2): 1.22 / 0.70 = ~1.743.
        assertEquals(1.22f / 0.70f, fit.zoomVsCrop, 0.01f)
    }

    @Test
    fun opaqueSubjectFit_neverBelowCropFill_andBiasClamped() {
        // A very tall thin source: Crop fill is height-driven; the subject
        // fit must still cover the disc and keep the bias inside [-1, 1].
        val fit = FramedCutoutLayout.opaqueSubjectFit(400f, 2000f, 200f, 200f)
        val cropFill = maxOf(200f / 400f, 200f / 2000f)
        assertTrue(fit.scale >= cropFill)
        assertTrue(400f * fit.scale >= 200f && 2000f * fit.scale >= 200f)
        assertTrue(fit.biasY in -1f..1f)
        assertTrue(fit.zoomVsCrop <= FramedCutoutLayout.OPAQUE_MAX_ZOOM_VS_CROP + 0.001f)
    }

    @Test
    fun opaqueSubjectFit_degenerateSource_staysFinite() {
        val fit = FramedCutoutLayout.opaqueSubjectFit(0f, 0f, 200f, 200f)
        assertTrue(fit.scale.isFinite() && fit.scale > 0f)
        assertTrue(fit.biasY in -1f..1f)
    }
}
