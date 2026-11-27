package com.wesal.messenger.core.util

/**
 * Placement of a background-removed (cutout) photo inside a frame's aperture, computed
 * from the person's measured alpha bounds instead of the padded canvas (round-3 fix).
 *
 * The transform maps the SOURCE bitmap into the avatar container: draw the bitmap at
 * [FramedCutoutTransform.drawWidthPx] x [FramedCutoutTransform.drawHeightPx], translated
 * by ([FramedCutoutTransform.offsetXPx], [FramedCutoutTransform.offsetYPx]) relative to
 * the container's top-left. Both clip windows (aperture circle under the frame, safety
 * strip over it) render with this same transform, so the figure stays coherent across
 * the frame's ring.
 *
 * ROUND-6 GEOMETRY (item 10 — supersedes the round-3/4 "head pop-out" spec, which the
 * owner rejected on device: the pop-out lifted the whole figure, shrank the body and
 * left a dark void inside the ring below the body). The new fit mirrors the owner's
 * reference composite, measured on the 1536x1536 composite itself:
 * - the person's hair top sits essentially AT the aperture's top inner edge
 *   (clearance ~0.00-0.03 of the aperture diameter), INSIDE the ring — the frame's
 *   crown ornament lives entirely above that edge and never crosses the face/hair;
 * - the torso fills the aperture from the bottom up and continues BELOW the
 *   aperture's bottom edge, behind the frame's bottom artwork (shield/plaque), so
 *   there is no void anywhere inside the ring;
 * - at the shoulders the torso spans ~0.92 of the aperture diameter.
 *
 * Fit rule derived from those proportions:
 * - HEIGHT-FIRST, BOTTOM-ANCHORED: the rendered person height is
 *   aperture x (1 - [HEAD_CLEARANCE_APERTURE_FRACTION] + [BOTTOM_OVERFLOW_APERTURE_FRACTION])
 *   = 1.21 x aperture; the bounding-box BOTTOM is anchored at
 *   apertureBottom + [BOTTOM_OVERFLOW_APERTURE_FRACTION] x aperture and the box top
 *   therefore lands exactly [HEAD_CLEARANCE_APERTURE_FRACTION] x aperture below the
 *   aperture's top edge — head inside the ring, clear of the crown zone.
 * - WIDTH CAP: a very wide/short cutout is not zoomed past
 *   [MAX_WIDTH_APERTURE_FRACTION] x aperture wide; when the cap binds the height
 *   shrinks instead and the bottom anchor is kept (top sits lower, still inside —
 *   never a bottom void, never a head in the ornament zone).
 * - The over-frame strip window shrinks to a crown-margin sliver
 *   ([CROWN_MARGIN_CONTAINER_FRACTION]): with the head inside the ring it normally
 *   renders nothing; it only keeps a feather of hair from being shaved if the pad
 *   ever pushes the box top a hair above the aperture edge.
 *
 * Measured before/after on the owner's own screenshot (931x2048, royal frame,
 * container ~285px, aperture D ~141px, aperture top ~702px, bottom ~844px):
 * BEFORE (pop-out math): person top ~663px (0.28xD ABOVE the aperture top, in the
 * crown zone), person bottom ~780px -> void of ~64px = 0.45xD of bare glow disc
 * inside the ring below the body; rendered person height ~0.83xD.
 * AFTER (this math): person top = apertureTop + 0.04xD, person bottom =
 * apertureBottom + 0.25xD (behind the plaque), rendered height 1.21xD, void = 0.
 *
 * Pure math, no Android classes — unit-tested on the JVM.
 */
data class FramedCutoutTransform(
    /** Bitmap px -> container px scale factor (uniform, aspect-preserving). */
    val scale: Float,
    val drawWidthPx: Float,
    val drawHeightPx: Float,
    val offsetXPx: Float,
    val offsetYPx: Float,
    /**
     * Height (container px) of the over-frame safety-strip clip window. Round 6:
     * just the crown margin — the head renders INSIDE the aperture, so the strip
     * is a sliver that normally shows nothing.
     */
    val stripPx: Float
)

object FramedCutoutLayout {

    /** Safety pad added around the measured person box before fitting (fraction of canvas). */
    const val DEFAULT_PAD_FRACTION = 0.02f

    /**
     * Round 6: the person's bounding-box top lands this far (fraction of the
     * aperture diameter) BELOW the aperture's top inner edge — head near the top
     * INSIDE the ring, clear of the crown/ornament zone above the ring.
     * Reference composite: ~0.00-0.03; 0.04 keeps a hair of safety margin.
     */
    const val HEAD_CLEARANCE_APERTURE_FRACTION = 0.04f

    /**
     * Round 6: the person's bounding-box bottom lands this far (fraction of the
     * aperture diameter) BELOW the aperture's bottom edge, so the torso continues
     * behind the frame's bottom artwork (shield/plaque) exactly like the
     * reference composite and the aperture's bottom is never a void.
     */
    const val BOTTOM_OVERFLOW_APERTURE_FRACTION = 0.25f

    /**
     * Round 6: cap on the rendered person width (fraction of the aperture
     * diameter). Height-first fitting of a very wide/short cutout would
     * otherwise zoom it past the ring's sides.
     */
    const val MAX_WIDTH_APERTURE_FRACTION = 1.35f

    /**
     * Extra window height above the aperture's top edge (fraction of container):
     * the round-6 safety strip. The head is placed inside the ring, so this
     * sliver normally renders nothing.
     */
    const val CROWN_MARGIN_CONTAINER_FRACTION = 0.02f

    fun compute(
        containerPx: Float,
        apertureCenterYFraction: Float,
        apertureDiameterFraction: Float,
        bounds: AlphaBounds,
        imageWidthPx: Int,
        imageHeightPx: Int,
        padFraction: Float = DEFAULT_PAD_FRACTION
    ): FramedCutoutTransform {
        val imgW = imageWidthPx.coerceAtLeast(1).toFloat()
        val imgH = imageHeightPx.coerceAtLeast(1).toFloat()
        val b = bounds.padded(padFraction)

        val apertureD = containerPx * apertureDiameterFraction
        val apertureTop = containerPx * apertureCenterYFraction - apertureD / 2f
        val apertureBottom = apertureTop + apertureD

        val boxW = (b.width * imgW).coerceAtLeast(1f)
        val boxH = (b.height * imgH).coerceAtLeast(1f)

        // Height-first fit: the person spans from the head-clearance point just
        // inside the aperture's top edge to the overflow point below its bottom
        // edge — 1.21 aperture diameters for the round-6 fractions.
        val targetPersonH = apertureD *
            (1f - HEAD_CLEARANCE_APERTURE_FRACTION + BOTTOM_OVERFLOW_APERTURE_FRACTION)
        var scale = targetPersonH / boxH

        // Width cap: a wide/short cutout must not be zoomed past the ring's
        // sides. When the cap binds, the height shrinks and the bottom anchor
        // (below) is kept, so the top sits lower inside the ring — still no
        // bottom void and the head never enters the ornament zone.
        val maxPersonW = MAX_WIDTH_APERTURE_FRACTION * apertureD
        if (boxW * scale > maxPersonW) {
            scale = maxPersonW / boxW
        }

        val drawW = imgW * scale
        val drawH = imgH * scale

        // Bounding box horizontally centred on the aperture centre (container centre).
        val offsetX = containerPx / 2f - (b.left * drawW + (b.width * drawW) / 2f)
        // BOTTOM-ANCHORED: the bounding-box bottom lands at the overflow point
        // below the aperture's bottom edge. With the height-first scale the box
        // top then lands exactly at apertureTop + HEAD_CLEARANCE x aperture.
        val offsetY = (apertureBottom + BOTTOM_OVERFLOW_APERTURE_FRACTION * apertureD) - b.bottom * drawH

        return FramedCutoutTransform(
            scale = scale,
            drawWidthPx = drawW,
            drawHeightPx = drawH,
            offsetXPx = offsetX,
            offsetYPx = offsetY,
            // Safety sliver only (round 6): the head is inside the ring now.
            stripPx = CROWN_MARGIN_CONTAINER_FRACTION * containerPx
        )
    }
}
