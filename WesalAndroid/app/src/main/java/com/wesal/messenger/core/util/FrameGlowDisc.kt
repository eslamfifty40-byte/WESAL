package com.wesal.messenger.core.util

import kotlin.math.roundToInt

/**
 * The opaque glow disc drawn inside a frame's aperture, behind the photo (round-3 fix).
 *
 * The owner's reference composite has a LUMINOUS radial glow in the frame's own colour
 * behind the person (bright core, deepening to near-black at the rim) — the old disc was a
 * flat near-black tint. These stops are computed from the frame's resolved glow colour
 * only, so changing the frame recolours the disc automatically via
 * [WesalFrameLibrary] resolve().
 *
 * OPACITY INVARIANT (non-negotiable): every stop has alpha 0xFF. The disc is a fully
 * opaque backing — neither the profile cover nor any background may ever show through
 * the frame's opening. Only the brightness distribution changes.
 *
 * Pure Long-ARGB math, no Android/Compose classes — unit-tested on the JVM.
 */
object FrameGlowDisc {

    /** The three radial stops, centre -> rim. All ARGB longs with alpha 0xFF. */
    data class Stops(val coreArgb: Long, val midArgb: Long, val rimArgb: Long)

    /** Radius fraction at which the mid stop sits. */
    const val MID_STOP_FRACTION = 0.55f

    /** Disc gradient centre height fraction (slightly above centre, behind head/torso). */
    const val CENTER_Y_FRACTION = 0.46f

    private const val WHITE = 0xFFFFFFFFL
    private const val NEAR_BLACK = 0xFF080708L

    /**
     * @param glowArgb the frame's glow colour (alpha ignored, forced opaque).
     * @param intensity per-frame glow intensity 0..1 (admin §5.8 control): 0 reproduces
     *   the old flat dark disc exactly (core = glow x 0.30); 1 gives the full luminous
     *   core (glow lightened toward white).
     */
    fun discStops(glowArgb: Long, intensity: Float): Stops {
        val i = intensity.coerceIn(0f, 1f)
        val glow = glowArgb or 0xFF000000L
        // Core: today's dark tint at intensity 0, lightened luminous core at intensity 1.
        val darkCore = scaleRgb(glow, 0.30f)
        val lightCore = lerpRgb(glow, WHITE, 0.22f)
        val core = lerpRgb(darkCore, lightCore, i)
        // Mid: glow x 0.55 at full intensity, darker when the admin turns intensity down.
        val mid = scaleRgb(glow, 0.16f + 0.39f * i)
        // Rim: near-black with a breath of the frame colour, constant across intensities.
        val rim = lerpRgb(NEAR_BLACK, scaleRgb(glow, 0.10f), 0.35f)
        return Stops(coreArgb = core, midArgb = mid, rimArgb = rim)
    }

    /** Multiplies the RGB channels by [factor] (0..1 darkens); alpha is forced to 0xFF. */
    internal fun scaleRgb(argb: Long, factor: Float): Long {
        val f = factor.coerceIn(0f, 1f)
        val r = (((argb shr 16) and 0xFF) * f).roundToInt().coerceIn(0, 255)
        val g = (((argb shr 8) and 0xFF) * f).roundToInt().coerceIn(0, 255)
        val b = ((argb and 0xFF) * f).roundToInt().coerceIn(0, 255)
        return 0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    }

    /** Linear interpolation between two ARGB colours' RGB channels; alpha forced to 0xFF. */
    internal fun lerpRgb(fromArgb: Long, toArgb: Long, t: Float): Long {
        val f = t.coerceIn(0f, 1f)
        fun channel(shift: Int): Long {
            val a = (fromArgb shr shift) and 0xFF
            val b = (toArgb shr shift) and 0xFF
            return (a + (b - a) * f).roundToInt().toLong().coerceIn(0, 255)
        }
        return 0xFF000000L or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
