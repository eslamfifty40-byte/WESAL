package com.wesal.messenger

import com.wesal.messenger.core.util.FrameAnimationPolicy
import com.wesal.messenger.core.util.FrameCategory
import com.wesal.messenger.core.util.FrameDoc
import com.wesal.messenger.core.util.FrameMotionTier
import com.wesal.messenger.core.util.FrameOverrideResolver
import com.wesal.messenger.core.util.WesalFrameCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the round-5 animated-frames work:
 *  - the on/off guarantee ([FrameAnimationPolicy.effectiveAnimated]) — the owner's
 *    top emphasis: per-frame flag AND master switch, either off = still;
 *  - the motion-tier boundaries (small avatars static, mid glow-only, large full);
 *  - per-frame override resolution ([FrameOverrideResolver]) over catalogue defaults;
 *  - catalogue integrity of the «إطارات متحركة» set (49 frames, tiered, measured).
 */
class FrameAnimationPolicyTest {

    // ------------------------------------------------------------------
    // effectiveAnimated: the on/off guarantee
    // ------------------------------------------------------------------

    @Test
    fun effectiveAnimated_requiresBothSwitchesOn() {
        assertTrue(FrameAnimationPolicy.effectiveAnimated(frameAnimated = true, masterEnabled = true))
        assertFalse(FrameAnimationPolicy.effectiveAnimated(frameAnimated = true, masterEnabled = false))
        assertFalse(FrameAnimationPolicy.effectiveAnimated(frameAnimated = false, masterEnabled = true))
        assertFalse(FrameAnimationPolicy.effectiveAnimated(frameAnimated = false, masterEnabled = false))
    }

    // ------------------------------------------------------------------
    // motionTier boundaries
    // ------------------------------------------------------------------

    @Test
    fun motionTier_notAnimated_isAlwaysNone() {
        assertEquals(FrameMotionTier.NONE, FrameAnimationPolicy.motionTier(200f, effectiveAnimated = false))
        assertEquals(FrameMotionTier.NONE, FrameAnimationPolicy.motionTier(56f, effectiveAnimated = false))
        assertEquals(FrameMotionTier.NONE, FrameAnimationPolicy.motionTier(38f, effectiveAnimated = false))
    }

    @Test
    fun motionTier_sizeBoundaries() {
        // Below the reduced threshold: static even when animation is on (perf rule).
        assertEquals(FrameMotionTier.NONE, FrameAnimationPolicy.motionTier(51.9f, effectiveAnimated = true))
        assertEquals(FrameMotionTier.NONE, FrameAnimationPolicy.motionTier(38f, effectiveAnimated = true))
        // Reduced band: glow pulse only.
        assertEquals(FrameMotionTier.REDUCED, FrameAnimationPolicy.motionTier(52f, effectiveAnimated = true))
        assertEquals(FrameMotionTier.REDUCED, FrameAnimationPolicy.motionTier(72f, effectiveAnimated = true))
        assertEquals(FrameMotionTier.REDUCED, FrameAnimationPolicy.motionTier(95.9f, effectiveAnimated = true))
        // Full band: glow + shine sweep + sparkles.
        assertEquals(FrameMotionTier.FULL, FrameAnimationPolicy.motionTier(96f, effectiveAnimated = true))
        assertEquals(FrameMotionTier.FULL, FrameAnimationPolicy.motionTier(120f, effectiveAnimated = true))
    }

    // ------------------------------------------------------------------
    // FrameOverrideResolver: doc wins only where it explicitly overrides
    // ------------------------------------------------------------------

    @Test
    fun resolver_nullDoc_fallsBackToBuiltinDefaults() {
        assertTrue(FrameOverrideResolver.animated(null, builtinDefault = true))
        assertFalse(FrameOverrideResolver.animated(null, builtinDefault = false))
        assertEquals("SHIMMER", FrameOverrideResolver.style(null, builtinDefault = "SHIMMER"))
        assertEquals(1.0f, FrameOverrideResolver.glowIntensity(null), 0.0001f)
        assertEquals(1.0f, FrameOverrideResolver.glowSpeed(null), 0.0001f)
    }

    @Test
    fun resolver_docWithoutOverrideFlags_keepsBuiltinDefaults() {
        // A doc that merely exists (e.g. a rename) must not change animation behaviour.
        val doc = FrameDoc(id = "anim_01", titleAr = "اسم جديد", animated = false, animationStyle = "ROTATE_AURA")
        assertTrue(FrameOverrideResolver.animated(doc, builtinDefault = true))
        assertEquals("PULSE_GLOW", FrameOverrideResolver.style(doc, builtinDefault = "PULSE_GLOW"))
        assertEquals(1.0f, FrameOverrideResolver.glowIntensity(doc), 0.0001f)
        assertEquals(1.0f, FrameOverrideResolver.glowSpeed(doc), 0.0001f)
    }

    @Test
    fun resolver_docWithOverrideFlags_wins() {
        val off = FrameDoc(id = "anim_01", animated = false, hasAnimatedOverride = true)
        assertFalse(FrameOverrideResolver.animated(off, builtinDefault = true))

        val on = FrameDoc(id = "cust_01", animated = true, hasAnimatedOverride = true)
        assertTrue(FrameOverrideResolver.animated(on, builtinDefault = false))

        val styled = FrameDoc(id = "anim_01", animationStyle = "SHIMMER", hasAnimationStyleOverride = true)
        assertEquals("SHIMMER", FrameOverrideResolver.style(styled, builtinDefault = "PULSE_GLOW"))

        val glow = FrameDoc(
            id = "anim_01",
            glowIntensity = 0.4f, hasGlowIntensityOverride = true,
            glowSpeed = 1.8f, hasGlowSpeedOverride = true
        )
        assertEquals(0.4f, FrameOverrideResolver.glowIntensity(glow), 0.0001f)
        assertEquals(1.8f, FrameOverrideResolver.glowSpeed(glow), 0.0001f)
    }

    // ------------------------------------------------------------------
    // «إطارات متحركة» catalogue integrity
    // ------------------------------------------------------------------

    @Test
    fun animatedCatalogue_has49TieredFrames() {
        val frames = WesalFrameCatalog.animatedFrames
        assertEquals(49, frames.size)
        assertTrue(frames.all { it.category == FrameCategory.ANIMATED })
        assertTrue(frames.all { it.defaultAnimated })
        assertTrue(frames.all { it.tier in 1..4 })
        // Tier distribution: simple → medium → luxury → royal.
        assertEquals(7, frames.count { it.tier == 1 })
        assertEquals(12, frames.count { it.tier == 2 })
        assertEquals(19, frames.count { it.tier == 3 })
        assertEquals(11, frames.count { it.tier == 4 })
        // The list is ordered by tier (sortOrder grouping for the pickers).
        assertEquals(frames.sortedBy { it.tier }.map { it.id }, frames.map { it.id })
    }

    @Test
    fun animatedCatalogue_aperturesAreMeasuredAndSane() {
        WesalFrameCatalog.animatedFrames.forEach { spec ->
            assertTrue("${spec.id} diameter", spec.apertureDiameterFraction in 0.30f..0.70f)
            assertTrue("${spec.id} centreY", spec.apertureCenterYFraction in 0.35f..0.60f)
        }
    }

    @Test
    fun catalogue_idsAreUniqueAcrossAllFrames() {
        val ids = WesalFrameCatalog.allFrames.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        // The animated set is part of the merged catalogue (glow map covers it too).
        assertTrue(WesalFrameCatalog.allFrames.containsAll(WesalFrameCatalog.animatedFrames))
        WesalFrameCatalog.animatedFrames.forEach { spec ->
            assertTrue("builtinGlow missing ${spec.id}", WesalFrameCatalog.builtinGlow.containsKey(spec.id))
        }
        assertNotNull(WesalFrameCatalog.findActiveFrame("anim_01", 0L))
        assertNotNull(WesalFrameCatalog.findActiveFrame("anim_49", 0L))
    }
}
