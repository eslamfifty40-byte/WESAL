package com.wesal.messenger.core.util

import kotlin.math.min

/**
 * Placement of a background-removed (cutout) photo inside a frame's aperture, computed
 * from the person's measured alpha bounds instead of the padded canvas (round-3 fix).
 *
 * The transform maps the SOURCE bitmap into the avatar container: draw the bitmap at
 * [FramedCutoutTransform.drawWidthPx] x [FramedCutoutTransform.drawHeightPx], translated
 * by ([FramedCutoutTransform.offsetXPx], [FramedCutoutTransform.offsetYPx]) relative to
 * the container's top-left. Both pop-out clip windows (aperture circle under the frame,
 * head strip over it) render with this same transform, so the figure stays coherent
 * across the frame's ring.
 *
 * Fit rule, mirroring the owner's reference composite:
 * - width-first: the person's bounding-box width equals the aperture diameter;
 * - the bounding-box top sits at the top of the pop-out strip (head above the ring);
 * - a height guard caps very tall cutouts (full body) so they cannot explode:
 *   total person height <= aperture + strip + 0.35 x aperture. The frame's own
 *   shield/ribbon artwork (drawn OVER the photo at the bottom) hides the lower overflow.
 *
 * Round-4 strip sizing: round 3 used a FIXED strip of 10% of the container, which is
 * only 56-71% of a human head — the frame's ring still crossed the forehead/eyes even
 * when the bounds path engaged. The strip is now DERIVED FROM THE BOUNDS: after the
 * width-first scale, the strip is [HEAD_FRACTION] (0.30) of the rendered person height
 * (head + neck), so the aperture's top edge — where the ring sits — lands behind the
 * neck and the whole head renders over the frame. The strip is capped at
 * [MAX_STRIP_CONTAINER_FRACTION] of the container; if the cap would bind, chin
 * alignment ([CHIN_FRACTION], 0.22) feeds the height guard instead of exploding the
 * figure. The height guard is re-derived with the dynamic strip, and the final strip
 * is recomputed from the final (possibly guard-clamped) person height so the ring
 * stays at the neck. [FramedCutoutTransform.stripPx] additionally includes a small
 * crown margin above the bounding-box top so feathered hair is never shaved by the
 * strip window's edge.
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
     * Height (container px) of the over-frame head-strip clip window: the person-derived
     * strip plus the crown margin. The renderer must clip the strip window with exactly
     * this height so the window matches the placement computed here.
     */
    val stripPx: Float
)

object FramedCutoutLayout {

    /** Safety pad added around the measured person box before fitting (fraction of canvas). */
    const val DEFAULT_PAD_FRACTION = 0.02f

    /** Extra person height allowed below the aperture before the height guard engages. */
    const val HEIGHT_GUARD_EXTRA_APERTURE_FRACTION = 0.35f

    /**
     * Fraction of the rendered person height placed above the aperture's top edge:
     * head (~0.22) + neck (~0.08). Puts the frame's ring behind the neck.
     */
    const val HEAD_FRACTION = 0.30f

    /** Fallback fraction (head only, ring at the chin) when the strip cap binds. */
    const val CHIN_FRACTION = 0.22f

    /** Hard cap on the person-derived strip, as a fraction of the container. */
    const val MAX_STRIP_CONTAINER_FRACTION = 0.40f

    /**
     * Extra window height above the bounding-box top (fraction of container) so the
     * strip window's edge never clips feathered hair at the crown.
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
        val maxStrip = MAX_STRIP_CONTAINER_FRACTION * containerPx

        val boxW = (b.width * imgW).coerceAtLeast(1f)
        val boxH = (b.height * imgH).coerceAtLeast(1f)

        // Width-first fit: person width == aperture diameter.
        var scale = apertureD / boxW
        val widthFirstPersonH = boxH * scale

        // Dynamic strip from the rendered person height (head + neck). If the safety
        // cap would bind (extreme full-body), chin alignment feeds the height guard
        // instead — the guard bounds the total height either way.
        var strip = HEAD_FRACTION * widthFirstPersonH
        if (strip > maxStrip) {
            strip = min(CHIN_FRACTION * widthFirstPersonH, maxStrip)
        }

        // Height guard, re-derived with the dynamic strip: full-body cutouts must not
        // explode past aperture + strip + slack.
        val maxPersonH = apertureD + strip + HEIGHT_GUARD_EXTRA_APERTURE_FRACTION * apertureD
        if (widthFirstPersonH > maxPersonH) {
            scale = maxPersonH / boxH
            // The strip follows the FINAL rendered person height (still capped), so the
            // ring stays behind the neck after clamping.
            strip = min(HEAD_FRACTION * boxH * scale, maxStrip)
        }

        val drawW = imgW * scale
        val drawH = imgH * scale

        // Bounding box horizontally centred on the aperture centre (container centre),
        // bounding-box top exactly at the strip's top edge.
        val offsetX = containerPx / 2f - (b.left * drawW + (b.width * drawW) / 2f)
        val offsetY = (apertureTop - strip) - b.top * drawH

        return FramedCutoutTransform(
            scale = scale,
            drawWidthPx = drawW,
            drawHeightPx = drawH,
            offsetXPx = offsetX,
            offsetYPx = offsetY,
            // The clip window extends a crown margin above the bounding-box top so
            // feathered hair at the crown is never shaved by the window's edge.
            stripPx = strip + CROWN_MARGIN_CONTAINER_FRACTION * containerPx
        )
    }
}
