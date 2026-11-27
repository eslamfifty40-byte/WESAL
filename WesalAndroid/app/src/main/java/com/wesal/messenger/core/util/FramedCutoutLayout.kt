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
 *   = 1.15 x aperture; the bounding-box BOTTOM is anchored at
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
 * AFTER (this math): person top = apertureTop + 0.10xD, person bottom =
 * apertureBottom + 0.25xD (behind the plaque), rendered height 1.15xD, void = 0.
 * ROUND 9 (item 7): clearance re-measured on the owner's definitive reference
 * composite and set to 0.03xD (see HEAD_CLEARANCE_APERTURE_FRACTION): person
 * top = apertureTop + 0.03xD, bottom unchanged, rendered height 1.22xD.
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
     * Reference composite: ~0.00-0.03. Raised to 0.10 on the owner's explicit
     * demand (2026-11-27, zoomed screenshot): the frame must NEVER touch the
     * face or the hair — 0.10 keeps the hair fully clear of the ring's inner
     * ornament band (incl. the flame wisps that lick inside the aperture)
     * even under zoom, at the cost of a slightly smaller figure.
     *
     * Round 9 (item 7): the owner superseded the 0.10 with his DEFINITIVE
     * reference composite (2026-11-27 15:11, the royal crest with the suited
     * man): measured on that 1536x1536 image against the crest aperture
     * (centre 0.396, diameter 0.496 -> top 0.148, bottom 0.644 of the height),
     * the hair top sits ~0.16 of the height = ~0.02-0.04 of the aperture BELOW
     * its top inner edge, and the figure's bottom (behind the shield/plaque)
     * lands ~0.75-0.78 = ~0.24 of the aperture BELOW its bottom edge. So the
     * reference split is clearance ~0.03 / overflow ~0.25 / total height
     * ~1.22 x aperture. The 0.10 clearance pushed the whole figure 0.07 x
     * aperture too low (visible glow gap above the hair — the owner's
     * "أبعاد الصورة مش متظبطة" complaint), so clearance returns to the
     * reference value 0.03; overflow already matched at 0.25 and is unchanged.
     */
    const val HEAD_CLEARANCE_APERTURE_FRACTION = 0.03f

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

    /**
     * Round 10 (item 9): zoom applied to OPAQUE photos inside a frame's
     * aperture, on top of the plain Crop fill. An opaque photo (e.g. the
     * crop editor's glow-background output — alpha-bounds verdict
     * NOT_A_CUTOUT) used to render with plain Crop/TopCenter: the photo
     * canvas exactly filled the disc, so the person sat at whatever size
     * the source framing left — visibly smaller than the reference
     * composite, whose figure spans 1.22 aperture diameters (the same
     * total the cutout fit targets: 1 − 0.03 clearance + 0.25 overflow).
     * An opaque rectangle cannot overflow the ring like a cutout (its
     * corners would paint over the frame art), so the reference
     * proportions are reproduced INSIDE the disc instead: the photo is
     * scaled to 1.22× its Crop fill and anchored TopCenter — the top of
     * the photo (the head, in any normal portrait/editor output) sits at
     * the disc's top edge and the torso extends past the disc's bottom
     * edge, clipped by the circle exactly where the frame's plaque zone
     * begins. For a square editor output the rendered canvas height is
     * exactly 1.22× the aperture — the reference split.
     */
    const val OPAQUE_REFERENCE_ZOOM = 1.22f

    /**
     * Round 10 (item 9): the uniform scale factor for an opaque framed
     * photo — Crop fill of the destination box, multiplied by
     * [OPAQUE_REFERENCE_ZOOM]. Pure math for the JVM tests; the Compose
     * side wraps it in a ContentScale.
     */
    fun opaqueScaleFactor(srcW: Float, srcH: Float, dstW: Float, dstH: Float): Float {
        val safeSrcW = srcW.coerceAtLeast(1f)
        val safeSrcH = srcH.coerceAtLeast(1f)
        val cropFill = maxOf(dstW / safeSrcW, dstH / safeSrcH)
        return cropFill * OPAQUE_REFERENCE_ZOOM
    }

    // ------------------------------------------------------------------
    // Round 11 (item 7): SUBJECT-AWARE fit for opaque framed photos.
    //
    // Why the round-10 zoom was not enough (MEASURED on the owner's own
    // screenshots, 2026-11-27): in his r10 profile screenshot the framed
    // figure spans only ~0.84x the aperture height with ~0.11x aperture of
    // head clearance, vs the reference composite's 1.22x / 0.03x. The 1.22x
    // canvas zoom WAS applying — but zooming the whole canvas by 1.22 only
    // scales the FIGURE by 1.22 too, and in his editor-style avatar output
    // the figure occupies only ~0.70 of the canvas height (the rest is
    // glow backdrop above/below), so the figure landed at 0.70 x 1.22 =
    // 0.85x aperture. Closing the gap requires fitting the SUBJECT, not
    // the canvas:
    //
    // Subject model for opaque editor-style avatar output (measured from
    // the owner's saved avatar style — centred subject on a baked glow
    // backdrop): the figure spans [OPAQUE_SUBJECT_HEIGHT_FRACTION] of the
    // canvas height, its centre at [OPAQUE_SUBJECT_CENTER_Y_FRACTION] of
    // the canvas height. The fit then solves for the uniform scale that
    // renders the SUBJECT at the reference figure height
    // (1 - clearance + overflow = 1.22 aperture diameters, the same total
    // the cutout fit targets) and the vertical bias that places the
    // subject's centre at the reference figure centre (clearance + 1.22/2
    // = 0.64 of the aperture from its top). For the measured model case
    // this reproduces the reference split EXACTLY: figure top at
    // +0.03x aperture, figure bottom at aperture bottom + 0.25x aperture.
    // ------------------------------------------------------------------

    /** Subject height as a fraction of an opaque editor-output canvas height. */
    const val OPAQUE_SUBJECT_HEIGHT_FRACTION = 0.70f

    /** Subject centre height as a fraction of an opaque editor-output canvas height. */
    const val OPAQUE_SUBJECT_CENTER_Y_FRACTION = 0.44f

    /** Hard cap on the subject fit's zoom relative to the plain Crop fill. */
    const val OPAQUE_MAX_ZOOM_VS_CROP = 2.6f

    /** Reference figure height in aperture diameters (clearance/overflow split above). */
    val TARGET_FIGURE_HEIGHT_FRACTION: Float =
        1f - HEAD_CLEARANCE_APERTURE_FRACTION + BOTTOM_OVERFLOW_APERTURE_FRACTION

    /** Reference figure centre, as a fraction of the aperture from its top edge. */
    val TARGET_FIGURE_CENTER_Y_FRACTION: Float =
        HEAD_CLEARANCE_APERTURE_FRACTION + TARGET_FIGURE_HEIGHT_FRACTION / 2f

    /**
     * Round 12 (addendum 5): minimum rendered person WIDTH (fraction of
     * the aperture diameter) the fit targets when the figure is narrow.
     * The reference figure fills the ring in BOTH dimensions — height
     * 1.22x and shoulders ~0.92x with the torso clipped by the ring's
     * sides at the bottom — while the height-first fit alone left a
     * narrow bust (the owner's own avatar: box ~0.62 wide x 0.94 tall)
     * at only ~0.81x wide with glow gaps at the sides (measured on his
     * r11 screenshot: figure ~1.00x tall / 0.81x wide / clearance ~0.07,
     * vs the reference 1.22x / ~0.92+ / 0.03). When the width fill binds,
     * the scale grows and the anchor switches from the bottom edge to
     * the HEAD CLEARANCE (the invariant the owner actually sees): the
     * top stays at +0.03x and the extra height overflows further below
     * the aperture, behind the plaque — exactly like the reference.
     */
    const val TARGET_MIN_WIDTH_FRACTION = 1.02f

    /**
     * Round 12 (addendum 5): cap on the width-fill boost relative to the
     * height-fit scale, so a genuinely narrow subject (a head-only
     * cutout) is enlarged toward the reference width without blowing the
     * face up without bound.
     */
    const val MAX_WIDTH_FILL_BOOST = 1.45f

    /** Decode oversampling for the subject fit (the fit zooms past the r10 1.22x). */
    const val OPAQUE_SUBJECT_DECODE_OVERSAMPLE = 2f

    /** Result of [opaqueSubjectFit]: the uniform scale plus the vertical bias. */
    data class OpaqueSubjectFit(
        /** Bitmap px -> destination px uniform scale factor. */
        val scale: Float,
        /**
         * Vertical alignment bias in [-1, 1] (-1 = top, +1 = bottom) that
         * positions the scaled bitmap so the subject centre lands on the
         * reference figure centre. Horizontal placement stays centred.
         */
        val biasY: Float,
        /** [scale] relative to the plain Crop fill — drives decode sizing. */
        val zoomVsCrop: Float
    )

    /**
     * Subject-aware fit for an OPAQUE photo rendered into a frame's
     * aperture (the destination box IS the aperture box in the avatar
     * renderer). Never scales below the Crop fill (the disc must stay
     * covered) and never past [OPAQUE_MAX_ZOOM_VS_CROP] times it.
     */
    fun opaqueSubjectFit(srcW: Float, srcH: Float, dstW: Float, dstH: Float): OpaqueSubjectFit {
        val safeSrcW = srcW.coerceAtLeast(1f)
        val safeSrcH = srcH.coerceAtLeast(1f)
        val safeDstW = dstW.coerceAtLeast(1f)
        val safeDstH = dstH.coerceAtLeast(1f)
        val cropScale = maxOf(safeDstW / safeSrcW, safeDstH / safeSrcH)
        val subjectSrcH = OPAQUE_SUBJECT_HEIGHT_FRACTION * safeSrcH
        val targetScale = TARGET_FIGURE_HEIGHT_FRACTION * safeDstH / subjectSrcH
        val scale = targetScale.coerceIn(cropScale, cropScale * OPAQUE_MAX_ZOOM_VS_CROP)
        val renderedH = safeSrcH * scale
        // Offset (dst px, relative to the dst top) that puts the subject
        // centre on the reference figure centre.
        val offsetY = TARGET_FIGURE_CENTER_Y_FRACTION * safeDstH -
            OPAQUE_SUBJECT_CENTER_Y_FRACTION * renderedH
        // Convert the offset to an Alignment bias: offset = (dstH - renderedH) * (bias + 1) / 2.
        val slack = safeDstH - renderedH
        val biasY = if (slack == 0f) {
            0f
        } else {
            (2f * offsetY / slack - 1f).coerceIn(-1f, 1f)
        }
        return OpaqueSubjectFit(
            scale = scale,
            biasY = biasY,
            zoomVsCrop = if (cropScale > 0f) scale / cropScale else 1f
        )
    }

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
        // edge — 1.22 aperture diameters for the round-9 fractions
        // (1 - 0.03 clearance + 0.25 overflow, the owner's reference split).
        val targetPersonH = apertureD *
            (1f - HEAD_CLEARANCE_APERTURE_FRACTION + BOTTOM_OVERFLOW_APERTURE_FRACTION)
        var scale = targetPersonH / boxH

        // Round 12 (addendum 5): WIDTH FILL for narrow figures. The
        // height-first scale alone renders a narrow bust at its natural
        // aspect — the owner's own avatar measured ~0.81x aperture wide
        // with glow gaps at the sides, vs the reference whose torso fills
        // the ring's width (shoulders ~0.92x, sides clipped at the
        // bottom). Raise the scale toward TARGET_MIN_WIDTH_FRACTION,
        // capped at MAX_WIDTH_FILL_BOOST x the height-fit scale; when the
        // boost binds, the anchor below switches to the head clearance so
        // the top stays at +0.03x and the extra height overflows behind
        // the plaque instead of pushing the head into the crown zone.
        var widthBoosted = false
        val widthFillScale = TARGET_MIN_WIDTH_FRACTION * apertureD / boxW
        if (widthFillScale > scale) {
            val boosted = minOf(widthFillScale, scale * MAX_WIDTH_FILL_BOOST)
            if (boosted > scale) {
                scale = boosted
                widthBoosted = true
            }
        }

        // Width cap: a wide/short cutout must not be zoomed past the ring's
        // sides. When the cap binds, the height shrinks and the bottom anchor
        // (below) is kept, so the top sits lower inside the ring — still no
        // bottom void and the head never enters the ornament zone.
        val maxPersonW = MAX_WIDTH_APERTURE_FRACTION * apertureD
        if (boxW * scale > maxPersonW) {
            scale = maxPersonW / boxW
            widthBoosted = false
        }

        val drawW = imgW * scale
        val drawH = imgH * scale

        // Bounding box horizontally centred on the aperture centre (container centre).
        val offsetX = containerPx / 2f - (b.left * drawW + (b.width * drawW) / 2f)
        // Anchor: normally BOTTOM-ANCHORED — the bounding-box bottom lands at
        // the overflow point below the aperture's bottom edge, and with the
        // height-first scale the box top then lands exactly at apertureTop +
        // HEAD_CLEARANCE x aperture. When the round-12 width fill raised the
        // scale, the CLEARANCE becomes the anchor instead (box top pinned at
        // apertureTop + HEAD_CLEARANCE x aperture) and the bottom overflows
        // further behind the plaque — the reference composite's own split.
        val offsetY = if (widthBoosted) {
            (apertureTop + HEAD_CLEARANCE_APERTURE_FRACTION * apertureD) - b.top * drawH
        } else {
            (apertureBottom + BOTTOM_OVERFLOW_APERTURE_FRACTION * apertureD) - b.bottom * drawH
        }

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
