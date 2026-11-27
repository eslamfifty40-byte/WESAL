package com.wesal.messenger.core.util

import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Failover design §5.1: the per-backend health state machine behind the
 * dual-backend message plane.
 *
 * Stage 1 posture — OBSERVING ONLY. Repositories report Firestore
 * failures here (at the same choke points that already route errors
 * through [ErrorCopy]; the Firestore code NAME is the signal, exactly as
 * [ErrorCopy.forCodeName] keys on it), the state is persisted so a
 * killed app does not resurrect into hammering a dead backend, and the
 * router ([MessagePlaneRouter]) reads it. Nothing reroutes message
 * traffic yet — sends stay Firestore-only until stage 2.
 *
 * Rules (design §5.1):
 *  - Firestore flips to [Health.QUOTA_DEAD] on RESOURCE_EXHAUSTED only,
 *    until the earlier of the estimated quota reset (next 10:00
 *    Africa/Cairo — the observed reset boundary, treated as an
 *    estimate, never a promise) or a successful probe.
 *  - Supabase flips to [Health.DOWN] on transport/5xx failure with a
 *    short probe ladder (its failures are not day-shaped).
 *  - Probe ladders: Firestore 15 min → 30 min → 60 min (capped);
 *    Supabase 1 min → 5 min → 15 min (capped).
 *  - Stickiness: while a backend is dead, repeated failure reports are
 *    no-ops — the first failure owns the timeline and only probe
 *    results advance or clear it. Routing therefore never flaps.
 *  - A 401 from a Supabase function is NOT a health event (it is a
 *    token problem) and is never reported here by callers.
 *
 * The state machine is pure functions over an explicit [State] (the
 * r14 gates' shape, JVM-testable); the process-wide live state below is
 * just storage plus the Throwable→code-name adapter.
 */
object BackendHealthStore {

    enum class Backend { FIRESTORE, SUPABASE }

    enum class Health { HEALTHY, QUOTA_DEAD, DOWN }

    @Serializable
    data class BackendHealth(
        val health: Health = Health.HEALTHY,
        val sinceMs: Long = 0L,
        val nextProbeMs: Long = 0L,
        val probeStep: Int = 0
    )

    @Serializable
    data class State(
        val firestore: BackendHealth = BackendHealth(),
        val supabase: BackendHealth = BackendHealth()
    )

    private val FIRESTORE_PROBE_LADDER_MS = listOf(900_000L, 1_800_000L, 3_600_000L)
    private val SUPABASE_PROBE_LADDER_MS = listOf(60_000L, 300_000L, 900_000L)

    /** Delay before probe attempt [step] (0-based), capped at the ladder's last rung. */
    fun probeDelayMs(backend: Backend, step: Int): Long {
        val ladder = when (backend) {
            Backend.FIRESTORE -> FIRESTORE_PROBE_LADDER_MS
            Backend.SUPABASE -> SUPABASE_PROBE_LADDER_MS
        }
        return ladder[step.coerceIn(0, ladder.lastIndex)]
    }

    private val QUOTA_ZONE: ZoneId = ZoneId.of("Africa/Cairo")

    /**
     * The next 10:00 Africa/Cairo strictly after [nowMs] — the observed
     * boundary at which the Firestore free daily quota resets.
     */
    fun nextQuotaResetMs(nowMs: Long): Long {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), QUOTA_ZONE)
        var candidate = now.toLocalDate().atTime(10, 0).atZone(QUOTA_ZONE)
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
        return candidate.toInstant().toEpochMilli()
    }

    fun healthOf(state: State, backend: Backend): BackendHealth = when (backend) {
        Backend.FIRESTORE -> state.firestore
        Backend.SUPABASE -> state.supabase
    }

    private fun withHealth(state: State, backend: Backend, health: BackendHealth): State =
        when (backend) {
            Backend.FIRESTORE -> state.copy(firestore = health)
            Backend.SUPABASE -> state.copy(supabase = health)
        }

    /**
     * The Firestore codes that mean "the infrastructure did not answer"
     * — the design §3.2 pair that lets a sign-in fall through to the
     * local profile snapshot. Keyed by code NAME, like
     * [ErrorCopy.forCodeName]; PERMISSION_DENIED and friends are
     * verdicts about the caller, never infrastructure failures.
     */
    fun isFirestoreInfraCode(codeName: String?): Boolean =
        codeName == "RESOURCE_EXHAUSTED" || codeName == "UNAVAILABLE"

    /**
     * A Firestore failure, keyed by code NAME. Only RESOURCE_EXHAUSTED
     * changes state (design §5.1); while already [Health.QUOTA_DEAD]
     * the report is a no-op (stickiness — probes own recovery).
     */
    fun onFirestoreCode(state: State, codeName: String?, nowMs: Long): State {
        if (codeName != "RESOURCE_EXHAUSTED") return state
        if (state.firestore.health == Health.QUOTA_DEAD) return state
        val nextProbe = minOf(
            nowMs + probeDelayMs(Backend.FIRESTORE, 0),
            nextQuotaResetMs(nowMs)
        )
        return state.copy(
            firestore = BackendHealth(
                health = Health.QUOTA_DEAD,
                sinceMs = nowMs,
                nextProbeMs = nextProbe,
                probeStep = 0
            )
        )
    }

    /** A Supabase transport/5xx failure. While already [Health.DOWN], a no-op (stickiness). */
    fun onSupabaseFailure(state: State, nowMs: Long): State {
        if (state.supabase.health == Health.DOWN) return state
        return state.copy(
            supabase = BackendHealth(
                health = Health.DOWN,
                sinceMs = nowMs,
                nextProbeMs = nowMs + probeDelayMs(Backend.SUPABASE, 0),
                probeStep = 0
            )
        )
    }

    /**
     * The outcome of a probe against a dead backend. Success restores
     * [Health.HEALTHY] and resets the ladder. Failure advances the
     * ladder one rung (for Firestore the next probe is still capped at
     * the estimated reset). Probes only run against dead backends; a
     * result reported against a healthy one changes nothing.
     */
    fun onProbeResult(
        state: State,
        backend: Backend,
        success: Boolean,
        nowMs: Long
    ): State {
        val current = healthOf(state, backend)
        if (current.health == Health.HEALTHY) return state
        if (success) return withHealth(state, backend, BackendHealth())
        val nextStep = current.probeStep + 1
        var nextProbe = nowMs + probeDelayMs(backend, nextStep)
        if (backend == Backend.FIRESTORE) {
            nextProbe = minOf(nextProbe, nextQuotaResetMs(nowMs))
        }
        return withHealth(
            state, backend,
            current.copy(probeStep = nextStep, nextProbeMs = nextProbe)
        )
    }

    /** A backend carries traffic only while [Health.HEALTHY] (design §5.1 stickiness). */
    fun isRoutable(state: State, backend: Backend): Boolean =
        healthOf(state, backend).health == Health.HEALTHY

    /** True when a dead backend's next probe may be attempted at [nowMs]. */
    fun probeDue(state: State, backend: Backend, nowMs: Long): Boolean {
        val h = healthOf(state, backend)
        return h.health != Health.HEALTHY && nowMs >= h.nextProbeMs
    }

    // ---- process-wide live state (storage only; decisions stay pure) ----

    @Volatile
    private var live: State = State()

    @Volatile
    private var persister: ((State) -> Unit)? = null

    fun currentState(): State = live

    /** Restores the persisted state at process start (WesalApplication). */
    @Synchronized
    fun hydrate(state: State) {
        live = state
    }

    /** Registers the persistence sink (WesalApplication → UserPreferencesDataStore). */
    fun attachPersister(sink: (State) -> Unit) {
        persister = sink
    }

    @Synchronized
    private fun update(next: State) {
        if (next != live) {
            live = next
            persister?.invoke(next)
        }
    }

    /** Repository choke-point adapter: extracts the Firestore code name, if any. */
    @Synchronized
    fun reportFirestoreError(error: Throwable?) {
        val codeName = (error as? FirebaseFirestoreException)?.code?.name ?: return
        update(onFirestoreCode(live, codeName, System.currentTimeMillis()))
    }

    @Synchronized
    fun reportSupabaseFailure() {
        update(onSupabaseFailure(live, System.currentTimeMillis()))
    }

    @Synchronized
    fun recordProbeResult(backend: Backend, success: Boolean) {
        update(onProbeResult(live, backend, success, System.currentTimeMillis()))
    }
}
