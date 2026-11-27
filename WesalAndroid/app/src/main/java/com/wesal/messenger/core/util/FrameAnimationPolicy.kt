package com.wesal.messenger.core.util

/**
 * Pure decision logic for frame animation (round 5, owner's animated-frames request).
 *
 * Kept Android/Compose-free so the on/off guarantee and the override precedence are
 * unit-tested on the JVM. WesalAvatar and the admin pickers must go through these
 * functions instead of re-deriving the rules inline.
 */

/** How much living treatment a framed avatar may render. */
enum class FrameMotionTier {
    /** Fully static: no infinite transitions are created at all. */
    NONE,

    /** Glow pulse only (halo + glow-disc breathing) — the small-avatar treatment. */
    REDUCED,

    /** Full treatment: glow pulse + shine sweep around the ring + sparkle accents. */
    FULL
}

object FrameAnimationPolicy {

    /** Avatars at or above this size (dp) get the reduced (glow-pulse) treatment. */
    const val REDUCED_MIN_SIZE_DP = 52f

    /** Avatars at or above this size (dp) get the full treatment. */
    const val FULL_MIN_SIZE_DP = 96f

    /**
     * THE on/off guarantee (owner's top emphasis): a frame animates only when BOTH
     * switches agree — its own resolved `animated` flag (catalogue default or the
     * admin's per-frame override in `frame_library`) AND the admin master switch
     * (`system_config/global_appearance.framesAnimationEnabled`). Either one off
     * means the frame renders still, everywhere, with no restart: both inputs are
     * Compose snapshot state in WesalFrameLibrary, so flipping either recomposes
     * every avatar immediately.
     */
    fun effectiveAnimated(frameAnimated: Boolean, masterEnabled: Boolean): Boolean =
        frameAnimated && masterEnabled

    /**
     * Motion tier for an avatar of [sizeDp] whose effective animation is
     * [effectiveAnimated]. Small list avatars stay static (perf: a scrolling list
     * must not pay for infinite transitions — the pre-round-5 rule), mid sizes get
     * the glow pulse only, and large avatars (profile/detail) get the full living
     * treatment.
     */
    fun motionTier(sizeDp: Float, effectiveAnimated: Boolean): FrameMotionTier = when {
        !effectiveAnimated -> FrameMotionTier.NONE
        sizeDp >= FULL_MIN_SIZE_DP -> FrameMotionTier.FULL
        sizeDp >= REDUCED_MIN_SIZE_DP -> FrameMotionTier.REDUCED
        else -> FrameMotionTier.NONE
    }
}

/**
 * Precedence for `frame_library` override documents over catalogue defaults.
 *
 * A built-in frame's override doc only wins for the fields it EXPLICITLY carries
 * (the `has*Override` flags are derived from field presence when the doc is read);
 * everything else falls back to the catalogue default. Custom (uploaded) frames
 * have no catalogue entry, so their doc IS the whole truth — callers pass their
 * doc values as the "defaults" in that case.
 */
object FrameOverrideResolver {

    fun animated(doc: FrameDoc?, builtinDefault: Boolean): Boolean =
        if (doc != null && doc.hasAnimatedOverride) doc.animated else builtinDefault

    fun style(doc: FrameDoc?, builtinDefault: String): String =
        if (doc != null && doc.hasAnimationStyleOverride) doc.animationStyle else builtinDefault

    fun glowIntensity(doc: FrameDoc?): Float =
        if (doc != null && doc.hasGlowIntensityOverride) doc.glowIntensity else 1.0f

    fun glowSpeed(doc: FrameDoc?): Float =
        if (doc != null && doc.hasGlowSpeedOverride) doc.glowSpeed else 1.0f
}
