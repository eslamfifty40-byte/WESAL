package com.wesal.messenger.core.util

import kotlin.math.max
import kotlin.math.min

/**
 * Alpha analysis of an avatar bitmap (round-3 framed-avatar fix).
 *
 * A background-removed (cutout) avatar is a transparent canvas with the person somewhere
 * inside it. Nothing in the old pipeline measured the transparent padding, so the whole
 * padded canvas was fitted into a frame's aperture and the person rendered small. These
 * pure, Android-free helpers measure the opaque bounding box so the renderer can trim to
 * the PERSON and scale the person — not the canvas — into the frame.
 *
 * All coordinates are fractions of the canvas (0..1), normalised per axis.
 */
data class AlphaBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** Fraction of sampled pixels whose alpha was below the threshold. -1 = sentinel. */
    val transparentFraction: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width * height

    /** Expands the box by [padFraction] of the canvas on every side, clamped to the canvas. */
    fun padded(padFraction: Float): AlphaBounds {
        if (padFraction <= 0f) return this
        return copy(
            left = (left - padFraction).coerceIn(0f, 1f),
            top = (top - padFraction).coerceIn(0f, 1f),
            right = (right + padFraction).coerceIn(0f, 1f),
            bottom = (bottom + padFraction).coerceIn(0f, 1f)
        )
    }

    companion object {
        /**
         * Cache sentinel: "this image was analysed and is NOT a cutout" (fully opaque, or
         * transparency too small / bounding box too large to be a person cutout). Stored in
         * the bounds cache so each URL is analysed at most once per process. Its
         * transparentFraction is negative so [isCutoutBounds] is false for it by construction.
         */
        val NOT_A_CUTOUT = AlphaBounds(0f, 0f, 1f, 1f, -1f)

        /** Smallest width/height a measured box is allowed to keep (fraction of canvas). */
        const val MIN_BOX_FRACTION = 0.02f
    }
}

/**
 * Single pass over [pixels] (ARGB ints, row-major, [width] x [height]).
 *
 * A pixel counts as opaque when its alpha is >= [alphaThreshold]. The default 32 (not 248)
 * keeps the box on the solid body: ML Kit segmentation feathers edges with alpha 1..254,
 * and the layout step adds a safety pad back.
 *
 * Returns:
 * - `null` when there are no opaque pixels at all (fully transparent) or the input is invalid;
 * - a full-canvas box with transparentFraction 0 for a fully opaque image;
 * - otherwise the tight opaque bounding box. Degenerate boxes are expanded symmetrically
 *   to at least [AlphaBounds.MIN_BOX_FRACTION] per axis so downstream scale math stays finite.
 */
fun analyzeAlphaBounds(
    pixels: IntArray,
    width: Int,
    height: Int,
    alphaThreshold: Int = 32
): AlphaBounds? {
    if (width <= 0 || height <= 0) return null
    if (pixels.size < width * height) return null

    var minX = width
    var minY = height
    var maxX = -1
    var maxY = -1
    var transparent = 0

    for (y in 0 until height) {
        val rowOffset = y * width
        for (x in 0 until width) {
            val alpha = (pixels[rowOffset + x] ushr 24) and 0xFF
            if (alpha < alphaThreshold) {
                transparent++
            } else {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
    }

    if (maxX < 0) return null // fully transparent: no person to measure

    val total = (width * height).toFloat()
    val transparentFraction = transparent / total
    if (transparent == 0) {
        return AlphaBounds(0f, 0f, 1f, 1f, 0f)
    }

    var left = minX.toFloat() / width
    var right = (maxX + 1).toFloat() / width
    var top = minY.toFloat() / height
    var bottom = (maxY + 1).toFloat() / height

    // Clamp degenerate boxes (e.g. a single opaque pixel) to a minimum size, centred on
    // the measured box and kept inside the canvas.
    if (right - left < AlphaBounds.MIN_BOX_FRACTION) {
        val centre = (left + right) / 2f
        left = (centre - AlphaBounds.MIN_BOX_FRACTION / 2f).coerceIn(0f, 1f - AlphaBounds.MIN_BOX_FRACTION)
        right = left + AlphaBounds.MIN_BOX_FRACTION
    }
    if (bottom - top < AlphaBounds.MIN_BOX_FRACTION) {
        val centre = (top + bottom) / 2f
        top = (centre - AlphaBounds.MIN_BOX_FRACTION / 2f).coerceIn(0f, 1f - AlphaBounds.MIN_BOX_FRACTION)
        bottom = top + AlphaBounds.MIN_BOX_FRACTION
    }

    return AlphaBounds(
        left = min(1f, max(0f, left)),
        top = min(1f, max(0f, top)),
        right = min(1f, max(0f, right)),
        bottom = min(1f, max(0f, bottom)),
        transparentFraction = transparentFraction
    )
}

/**
 * Cutout verdict. The thresholds reject images that merely HAVE an alpha channel (PNG
 * screenshots, logos pasted on opaque photos): a real cutout has a meaningful amount of
 * transparency (>= 2% of the canvas) AND a person box that leaves a margin (<= 92% of the
 * canvas area). Only a verified verdict may drive the over-frame head strip — a false
 * positive there would paint a photo rectangle over the frame's crest.
 */
fun isCutoutBounds(bounds: AlphaBounds?): Boolean =
    bounds != null && bounds.transparentFraction >= 0.02f && bounds.area <= 0.92f
