package com.wesal.messenger

import com.wesal.messenger.core.util.BackendHealthStore
import com.wesal.messenger.core.util.MessagePlaneRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Failover design §5.1/§5.2: the message-plane router's pure decisions
 * — the effective primary under every health × config combination,
 * and the rule-5 replay routing (hint first while its store is
 * routable, otherwise the current effective primary). Stage 1 only
 * OBSERVES these decisions; pinning them here is what lets stage 2
 * light the plane without re-deciding them.
 */
class MessagePlaneRouterTest {

    private val t0 = 1_800_000_000_000L

    private val healthy = BackendHealthStore.State()

    private val firestoreDead = BackendHealthStore.onFirestoreCode(
        healthy, "RESOURCE_EXHAUSTED", t0
    )

    private val supabaseDown = BackendHealthStore.onSupabaseFailure(healthy, t0)

    private val bothDead = BackendHealthStore.onSupabaseFailure(firestoreDead, t0)

    private val firestorePrimary = MessagePlaneRouter.PlaneConfig(MessagePlaneRouter.Store.FIRESTORE)
    private val supabasePrimary = MessagePlaneRouter.PlaneConfig(MessagePlaneRouter.Store.SUPABASE)

    // ---------------------------------------------------------------
    // Effective primary
    // ---------------------------------------------------------------

    @Test
    fun effectivePrimary_healthyFollowsTheConfig() {
        assertEquals(
            MessagePlaneRouter.Store.FIRESTORE,
            MessagePlaneRouter.effectivePrimary(firestorePrimary, healthy)
        )
        assertEquals(
            MessagePlaneRouter.Store.SUPABASE,
            MessagePlaneRouter.effectivePrimary(supabasePrimary, healthy)
        )
        // The stage-1 default config is Firestore primary.
        assertEquals(
            MessagePlaneRouter.Store.FIRESTORE,
            MessagePlaneRouter.effectivePrimary(MessagePlaneRouter.PlaneConfig(), healthy)
        )
    }

    @Test
    fun effectivePrimary_deadPrimaryFallsToTheHealthyStore() {
        assertEquals(
            MessagePlaneRouter.Store.SUPABASE,
            MessagePlaneRouter.effectivePrimary(firestorePrimary, firestoreDead)
        )
        assertEquals(
            MessagePlaneRouter.Store.FIRESTORE,
            MessagePlaneRouter.effectivePrimary(supabasePrimary, supabaseDown)
        )
    }

    @Test
    fun effectivePrimary_bothDeadKeepsTheConfiguredPrimary() {
        // Attempts then discover/report exactly as today — the router
        // never invents a third answer.
        assertEquals(
            MessagePlaneRouter.Store.FIRESTORE,
            MessagePlaneRouter.effectivePrimary(firestorePrimary, bothDead)
        )
        assertEquals(
            MessagePlaneRouter.Store.SUPABASE,
            MessagePlaneRouter.effectivePrimary(supabasePrimary, bothDead)
        )
    }

    // ---------------------------------------------------------------
    // Replay routing (§5.2 rule 5)
    // ---------------------------------------------------------------

    @Test
    fun replay_hintedStoreWinsWhileRoutable() {
        assertEquals(
            MessagePlaneRouter.Store.FIRESTORE,
            MessagePlaneRouter.replayTarget(MessagePlaneRouter.Store.FIRESTORE, supabasePrimary, healthy)
        )
        assertEquals(
            MessagePlaneRouter.Store.SUPABASE,
            MessagePlaneRouter.replayTarget(MessagePlaneRouter.Store.SUPABASE, firestorePrimary, healthy)
        )
    }

    @Test
    fun replay_deadHintFallsBackToTheEffectivePrimary() {
        // The attempt targeted Firestore, Firestore is quota-dead now:
        // replay re-runs the router instead of remembering the choice.
        assertEquals(
            MessagePlaneRouter.Store.SUPABASE,
            MessagePlaneRouter.replayTarget(MessagePlaneRouter.Store.FIRESTORE, firestorePrimary, firestoreDead)
        )
        assertEquals(
            MessagePlaneRouter.Store.FIRESTORE,
            MessagePlaneRouter.replayTarget(MessagePlaneRouter.Store.SUPABASE, supabasePrimary, supabaseDown)
        )
    }

    @Test
    fun replay_noHintUsesTheEffectivePrimary() {
        assertEquals(
            MessagePlaneRouter.Store.FIRESTORE,
            MessagePlaneRouter.replayTarget(null, firestorePrimary, healthy)
        )
        assertEquals(
            MessagePlaneRouter.Store.SUPABASE,
            MessagePlaneRouter.replayTarget(null, firestorePrimary, firestoreDead)
        )
    }

    // ---------------------------------------------------------------
    // Wire names (the attemptedStore hint's persisted form)
    // ---------------------------------------------------------------

    @Test
    fun wireNamesRoundTrip() {
        assertEquals("firestore", MessagePlaneRouter.wireName(MessagePlaneRouter.Store.FIRESTORE))
        assertEquals("supabase", MessagePlaneRouter.wireName(MessagePlaneRouter.Store.SUPABASE))
        assertEquals(
            MessagePlaneRouter.Store.FIRESTORE,
            MessagePlaneRouter.storeFromWire("firestore")
        )
        assertEquals(
            MessagePlaneRouter.Store.SUPABASE,
            MessagePlaneRouter.storeFromWire(" Supabase ")
        )
        assertNull(MessagePlaneRouter.storeFromWire(null))
        assertNull(MessagePlaneRouter.storeFromWire(""))
        assertNull(MessagePlaneRouter.storeFromWire("cloudflare"))
    }

    @Test
    fun otherIsTheOppositeStore() {
        assertEquals(
            MessagePlaneRouter.Store.SUPABASE,
            MessagePlaneRouter.other(MessagePlaneRouter.Store.FIRESTORE)
        )
        assertEquals(
            MessagePlaneRouter.Store.FIRESTORE,
            MessagePlaneRouter.other(MessagePlaneRouter.Store.SUPABASE)
        )
    }
}
