package com.wesal.messenger

import com.wesal.messenger.core.util.BackendHealthStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Failover design §5.1: the backend health state machine — quota-death
 * detection keyed on the Firestore code name (the same signal
 * ErrorCopy maps), stickiness while dead, the probe backoff ladders,
 * and the 10:00 Africa/Cairo reset-boundary math that caps Firestore
 * probing. Pure JVM, following the r14 gates' test pattern.
 */
class BackendHealthStoreTest {

    private fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private val healthy = BackendHealthStore.State()

    // ---------------------------------------------------------------
    // Detection
    // ---------------------------------------------------------------

    @Test
    fun firestore_resourceExhaustedFlipsToQuotaDead() {
        val t0 = ms("2026-11-28T06:00:00Z") // 08:00 Cairo
        val s = BackendHealthStore.onFirestoreCode(healthy, "RESOURCE_EXHAUSTED", t0)
        assertEquals(
            BackendHealthStore.Health.QUOTA_DEAD,
            s.firestore.health
        )
        assertEquals(t0, s.firestore.sinceMs)
        assertEquals(0, s.firestore.probeStep)
        // First probe after the 15-minute ladder rung (reset is 2h away).
        assertEquals(t0 + 900_000L, s.firestore.nextProbeMs)
        // The other backend is untouched.
        assertEquals(BackendHealthStore.Health.HEALTHY, s.supabase.health)
    }

    @Test
    fun firestore_otherCodesNeverFlipHealth() {
        val t0 = ms("2026-11-28T06:00:00Z")
        for (code in listOf("UNAVAILABLE", "PERMISSION_DENIED", "DEADLINE_EXCEEDED", "NOT_FOUND", null)) {
            assertEquals(
                "code $code must not change state",
                healthy,
                BackendHealthStore.onFirestoreCode(healthy, code, t0)
            )
        }
    }

    @Test
    fun firestore_probeIsCappedAtTheEstimatedReset() {
        // 09:50 Cairo: the 15-minute rung would land at 10:05, past the
        // 10:00 reset estimate — the probe is armed AT the reset.
        val t0 = ms("2026-11-28T07:50:00Z")
        val s = BackendHealthStore.onFirestoreCode(healthy, "RESOURCE_EXHAUSTED", t0)
        assertEquals(ms("2026-11-28T08:00:00Z"), s.firestore.nextProbeMs)
    }

    @Test
    fun firestore_repeatedQuotaReportsAreSticky() {
        val t0 = ms("2026-11-28T06:00:00Z")
        val dead = BackendHealthStore.onFirestoreCode(healthy, "RESOURCE_EXHAUSTED", t0)
        // A storm of further quota failures must not move the timeline:
        // the first failure owns it, probes own recovery.
        val again = BackendHealthStore.onFirestoreCode(dead, "RESOURCE_EXHAUSTED", t0 + 60_000L)
        assertEquals(dead, again)
    }

    @Test
    fun supabase_failureFlipsToDownWithShortLadder() {
        val t0 = ms("2026-11-28T06:00:00Z")
        val s = BackendHealthStore.onSupabaseFailure(healthy, t0)
        assertEquals(BackendHealthStore.Health.DOWN, s.supabase.health)
        assertEquals(t0, s.supabase.sinceMs)
        assertEquals(t0 + 60_000L, s.supabase.nextProbeMs)
        // Sticky while down.
        assertEquals(s, BackendHealthStore.onSupabaseFailure(s, t0 + 30_000L))
        // Firestore untouched.
        assertEquals(BackendHealthStore.Health.HEALTHY, s.firestore.health)
    }

    // ---------------------------------------------------------------
    // Probe ladder + recovery
    // ---------------------------------------------------------------

    @Test
    fun probeLaddersMatchTheDesignAndCap() {
        assertEquals(900_000L, BackendHealthStore.probeDelayMs(BackendHealthStore.Backend.FIRESTORE, 0))
        assertEquals(1_800_000L, BackendHealthStore.probeDelayMs(BackendHealthStore.Backend.FIRESTORE, 1))
        assertEquals(3_600_000L, BackendHealthStore.probeDelayMs(BackendHealthStore.Backend.FIRESTORE, 2))
        assertEquals(3_600_000L, BackendHealthStore.probeDelayMs(BackendHealthStore.Backend.FIRESTORE, 9))
        assertEquals(60_000L, BackendHealthStore.probeDelayMs(BackendHealthStore.Backend.SUPABASE, 0))
        assertEquals(300_000L, BackendHealthStore.probeDelayMs(BackendHealthStore.Backend.SUPABASE, 1))
        assertEquals(900_000L, BackendHealthStore.probeDelayMs(BackendHealthStore.Backend.SUPABASE, 2))
        assertEquals(900_000L, BackendHealthStore.probeDelayMs(BackendHealthStore.Backend.SUPABASE, 9))
    }

    @Test
    fun probeSuccessRestoresHealthyAndResetsTheLadder() {
        val t0 = ms("2026-11-28T06:00:00Z")
        var s = BackendHealthStore.onFirestoreCode(healthy, "RESOURCE_EXHAUSTED", t0)
        s = BackendHealthStore.onProbeResult(s, BackendHealthStore.Backend.FIRESTORE, false, t0 + 900_000L)
        assertEquals(1, s.firestore.probeStep)
        val healed = BackendHealthStore.onProbeResult(
            s, BackendHealthStore.Backend.FIRESTORE, true, t0 + 1_000_000L
        )
        assertEquals(BackendHealthStore.BackendHealth(), healed.firestore)
    }

    @Test
    fun probeFailureAdvancesTheLadder() {
        val t0 = ms("2026-11-28T06:00:00Z")
        var s = BackendHealthStore.onFirestoreCode(healthy, "RESOURCE_EXHAUSTED", t0)
        // Probe at +15m fails → next rung is 30m.
        s = BackendHealthStore.onProbeResult(s, BackendHealthStore.Backend.FIRESTORE, false, t0 + 900_000L)
        assertEquals(BackendHealthStore.Health.QUOTA_DEAD, s.firestore.health)
        assertEquals(1, s.firestore.probeStep)
        assertEquals(t0 + 900_000L + 1_800_000L, s.firestore.nextProbeMs)
        // Probe fails again → 60m rung, then capped.
        s = BackendHealthStore.onProbeResult(
            s, BackendHealthStore.Backend.FIRESTORE, false, t0 + 900_000L + 1_800_000L
        )
        assertEquals(2, s.firestore.probeStep)
        assertEquals(t0 + 900_000L + 1_800_000L + 3_600_000L, s.firestore.nextProbeMs)
    }

    @Test
    fun probeFailureNearTheResetIsStillCappedAtTheReset() {
        // Quota dies at 09:30 Cairo; the failed probe at 09:45 would
        // schedule +30m (10:15) but the reset estimate (10:00) wins.
        val t0 = ms("2026-11-28T07:30:00Z")
        var s = BackendHealthStore.onFirestoreCode(healthy, "RESOURCE_EXHAUSTED", t0)
        assertEquals(ms("2026-11-28T07:45:00Z"), s.firestore.nextProbeMs)
        s = BackendHealthStore.onProbeResult(
            s, BackendHealthStore.Backend.FIRESTORE, false, ms("2026-11-28T07:45:00Z")
        )
        assertEquals(ms("2026-11-28T08:00:00Z"), s.firestore.nextProbeMs)
    }

    @Test
    fun probeResultAgainstAHealthyBackendChangesNothing() {
        val t0 = ms("2026-11-28T06:00:00Z")
        assertEquals(
            healthy,
            BackendHealthStore.onProbeResult(healthy, BackendHealthStore.Backend.SUPABASE, false, t0)
        )
        assertEquals(
            healthy,
            BackendHealthStore.onProbeResult(healthy, BackendHealthStore.Backend.FIRESTORE, true, t0)
        )
    }

    @Test
    fun routableAndProbeDueFollowTheState() {
        val t0 = ms("2026-11-28T06:00:00Z")
        assertTrue(BackendHealthStore.isRoutable(healthy, BackendHealthStore.Backend.FIRESTORE))
        assertFalse(BackendHealthStore.probeDue(healthy, BackendHealthStore.Backend.FIRESTORE, t0))

        val dead = BackendHealthStore.onFirestoreCode(healthy, "RESOURCE_EXHAUSTED", t0)
        assertFalse(BackendHealthStore.isRoutable(dead, BackendHealthStore.Backend.FIRESTORE))
        assertFalse(BackendHealthStore.probeDue(dead, BackendHealthStore.Backend.FIRESTORE, t0 + 899_999L))
        assertTrue(BackendHealthStore.probeDue(dead, BackendHealthStore.Backend.FIRESTORE, t0 + 900_000L))
        // The other backend stays routable throughout.
        assertTrue(BackendHealthStore.isRoutable(dead, BackendHealthStore.Backend.SUPABASE))
    }

    // ---------------------------------------------------------------
    // Reset-boundary math (next 10:00 Africa/Cairo, strictly after now)
    // ---------------------------------------------------------------

    @Test
    fun quotaReset_eveningPointsAtNextMorning() {
        // 21:00 Cairo on Nov 27 → Nov 28, 10:00 Cairo = 08:00Z.
        assertEquals(
            ms("2026-11-28T08:00:00Z"),
            BackendHealthStore.nextQuotaResetMs(ms("2026-11-27T19:00:00Z"))
        )
    }

    @Test
    fun quotaReset_morningBeforeTenPointsAtSameMorning() {
        // 09:00 Cairo → today 10:00 Cairo.
        assertEquals(
            ms("2026-11-28T08:00:00Z"),
            BackendHealthStore.nextQuotaResetMs(ms("2026-11-28T07:00:00Z"))
        )
    }

    @Test
    fun quotaReset_exactlyAtTenRollsToNextDay() {
        assertEquals(
            ms("2026-11-29T08:00:00Z"),
            BackendHealthStore.nextQuotaResetMs(ms("2026-11-28T08:00:00Z"))
        )
        assertEquals(
            ms("2026-11-29T08:00:00Z"),
            BackendHealthStore.nextQuotaResetMs(ms("2026-11-28T08:00:01Z"))
        )
    }

    // ---------------------------------------------------------------
    // Infra-code classification (the §3.2 sign-in fall-through pair)
    // ---------------------------------------------------------------

    @Test
    fun infraCodesAreExactlyTheDesignPair() {
        assertTrue(BackendHealthStore.isFirestoreInfraCode("RESOURCE_EXHAUSTED"))
        assertTrue(BackendHealthStore.isFirestoreInfraCode("UNAVAILABLE"))
        assertFalse(BackendHealthStore.isFirestoreInfraCode("PERMISSION_DENIED"))
        assertFalse(BackendHealthStore.isFirestoreInfraCode("DEADLINE_EXCEEDED"))
        assertFalse(BackendHealthStore.isFirestoreInfraCode(null))
        assertFalse(BackendHealthStore.isFirestoreInfraCode(""))
    }
}
