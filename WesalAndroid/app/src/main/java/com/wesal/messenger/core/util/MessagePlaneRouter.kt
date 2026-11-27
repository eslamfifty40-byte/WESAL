package com.wesal.messenger.core.util

/**
 * Failover design §5.1/§5.2: pure routing decisions for the dual-backend
 * message plane, over [BackendHealthStore] state + the plane config.
 *
 * Stage 1 posture — the router OBSERVES. The decisions are computed,
 * unit-tested and consulted by outbox replay (§5.2 rule 5), but no send
 * is rerouted yet: Firestore is the only store with a client until
 * stage 2, so consumers act on the FIRESTORE arm and degrade to today's
 * behavior on any other result. The plane config defaults to Firestore
 * primary; stage 2 feeds it from `system_config/message_plane` (the
 * rollout gate / kill switch, design §9.3).
 */
object MessagePlaneRouter {

    enum class Store { FIRESTORE, SUPABASE }

    /** The plane doc's value; Firestore primary until stage 2 creates the doc. */
    data class PlaneConfig(val primary: Store = Store.FIRESTORE)

    fun other(store: Store): Store = when (store) {
        Store.FIRESTORE -> Store.SUPABASE
        Store.SUPABASE -> Store.FIRESTORE
    }

    private fun backendOf(store: Store): BackendHealthStore.Backend = when (store) {
        Store.FIRESTORE -> BackendHealthStore.Backend.FIRESTORE
        Store.SUPABASE -> BackendHealthStore.Backend.SUPABASE
    }

    /**
     * The effective primary for new writes: the configured primary
     * while it is routable; else the other store while IT is routable;
     * else the configured primary (attempts then discover and report,
     * exactly as they do today — design §5.1).
     */
    fun effectivePrimary(config: PlaneConfig, health: BackendHealthStore.State): Store {
        if (BackendHealthStore.isRoutable(health, backendOf(config.primary))) {
            return config.primary
        }
        val fallback = other(config.primary)
        if (BackendHealthStore.isRoutable(health, backendOf(fallback))) {
            return fallback
        }
        return config.primary
    }

    /**
     * §5.2 rule 5: outbox replay re-runs the router instead of
     * remembering the store originally chosen. The store a previous
     * attempt targeted (the [PendingOutboxMessage.attemptedStore] hint)
     * is where the existence check runs while that store is routable —
     * a message that landed there must not be re-sent anywhere.
     * Otherwise the current effective primary decides.
     */
    fun replayTarget(
        attemptedStore: Store?,
        config: PlaneConfig,
        health: BackendHealthStore.State
    ): Store {
        if (attemptedStore != null &&
            BackendHealthStore.isRoutable(health, backendOf(attemptedStore))
        ) {
            return attemptedStore
        }
        return effectivePrimary(config, health)
    }

    /** Wire name persisted on [com.wesal.messenger.domain.model.PendingOutboxMessage.attemptedStore]. */
    fun wireName(store: Store): String = when (store) {
        Store.FIRESTORE -> "firestore"
        Store.SUPABASE -> "supabase"
    }

    fun storeFromWire(name: String?): Store? = when (name?.trim()?.lowercase()) {
        "firestore" -> Store.FIRESTORE
        "supabase" -> Store.SUPABASE
        else -> null
    }
}
