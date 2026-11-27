package com.wesal.messenger.core.util

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
 * Pure math, no Android classes — unit-tested on the JVM.
 */
data class FramedCutoutTransform(
    /** Bitmap px -> container px scale factor (uniform, aspect-preserving). */
    val scale: Float,
    val drawWidthPx: Float,
    val drawHeightPx: Float,
    val offsetXPx: Float,
    val offsetYPx: Float
)

object FramedCutoutLayout {

    /** Safety pad added around the measured person box before fitting (fraction of canvas). */
    const val DEFAULT_PAD_FRACTION = 0.02f

    /** Extra person height allowed below the aperture before the height guard engages. */
    const val HEIGHT_GUARD_EXTRA_APERTURE_FRACTION = 0.35f

    fun compute(
        containerPx: Float,
        apertureCenterYFraction: Float,
        apertureDiameterFraction: Float,
        stripFraction: Float,
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
        val strip = containerPx * stripFraction

        val boxW = (b.width * imgW).coerceAtLeast(1f)
        val boxH = (b.height * imgH).coerceAtLeast(1f)

        // Width-first fit: person width == aperture diameter.
        var scale = apertureD / boxW

        // Height guard: full-body cutouts must not explode past aperture + strip + slack.
        val maxPersonH = apertureD + strip + HEIGHT_GUARD_EXTRA_APERTURE_FRACTION * apertureD
        if (boxH * scale > maxPersonH) {
            scale = maxPersonH / boxH
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
            offsetYPx = offsetY
        )
    }
}
