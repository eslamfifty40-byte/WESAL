package com.wesal.messenger.core.util

/**
 * Failover design §5.1/§5.2: pure routing decisions for the dual-backend
 * message plane, over [BackendHealthStore] state + the plane config.
 *
 * Stage 2 posture — the router is ACTIVE. Send paths, merged reads and
 * outbox replay all consult these decisions (the config now comes from
 * `system_config/message_plane`, hydrated into [currentConfig]). The
 * dark-launch property is preserved by the config, not by observation:
 * with the doc absent or `plane="firestore"` and Firestore healthy,
 * every decision resolves to Firestore and behavior is byte-identical
 * to the pre-plane app; Supabase carries traffic only when the doc
 * flips the primary or Firestore's health fails.
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

    /**
     * Parses the `system_config/message_plane` doc's `plane` field
     * (§9.3). Anything unrecognized — including a missing doc, the
     * dark-launch state — means Firestore primary: the plane lights
     * only on the exact "supabase" value.
     */
    fun configFromPlaneValue(raw: String?): PlaneConfig =
        PlaneConfig(storeFromWire(raw) ?: Store.FIRESTORE)

    // ---- process-wide live config (storage only; decisions stay pure) ----
    //
    // Stage 2: the config is fed from `system_config/message_plane`
    // (read at startup, listened to by UserRepositoryImpl, cached in
    // DataStore for cold starts) and hydrated here so every consumer —
    // repositories, the outbox replay sites, the listener service —
    // routes on the same value without re-reading the doc.

    @Volatile
    private var liveConfig: PlaneConfig = PlaneConfig()

    fun currentConfig(): PlaneConfig = liveConfig

    /** Restores/feeds the live config (startup cache + doc listener). */
    fun hydrateConfig(config: PlaneConfig) {
        liveConfig = config
    }
}
