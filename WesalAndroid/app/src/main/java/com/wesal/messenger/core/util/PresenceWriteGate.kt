package com.wesal.messenger.core.util

/**
 * Scaling pass: the presence write gate.
 *
 * Before this gate, the foreground heartbeat wrote TWO Firestore documents
 * (users/{uid} + the never-read presence/{uid}) every 25 seconds, and both
 * the Application lifecycle and the MainViewModel wrote again on every
 * foreground/background transition — ≈288 billed writes per foreground hour
 * before any other feature wrote anything.
 *
 * The gate is the single decision point every presence writer consults:
 * a write is allowed only when
 *  - the account changed (first write of a session / account switch), or
 *  - the online value actually flips (foreground ↔ background), or
 *  - the stamped activity (chat/group the user is inside) actually changes, or
 *  - the previous online write is at least [HEARTBEAT_REFRESH_MS] old
 *    (the steady-state heartbeat refresh).
 *
 * The read side must agree with the cadence: with refreshes up to 5 minutes
 * apart, the historical 75 s online TTL would mark a foregrounded user
 * offline between refreshes, so [isWithinFreshnessWindow] widens the window
 * to cover the refresh interval plus margin. A clean exit still stamps
 * isOnline=false immediately, so "last seen" behaviour on exit is unchanged;
 * only an unclean exit (crash/kill) can now linger "online" up to the window
 * instead of 75 s — the bounded cost of a 12× heartbeat reduction.
 *
 * Pure decision functions over an explicit [Snapshot] keep the rule
 * JVM-testable; the process-wide live snapshot is just storage.
 */
object PresenceWriteGate {

    /** Minimum spacing between steady-state (no-change) online heartbeats. */
    const val HEARTBEAT_REFRESH_MS: Long = 300_000L

    /** Read-side freshness window: refresh interval + one minute of slack. */
    const val FRESHNESS_WINDOW_MS: Long = HEARTBEAT_REFRESH_MS + 60_000L

    data class Snapshot(
        val uid: String? = null,
        val isOnline: Boolean? = null,
        val activityType: String = "",
        val activityChatId: String = "",
        val lastWriteAtMs: Long = 0L
    )

    fun shouldWrite(
        snapshot: Snapshot,
        uid: String,
        isOnline: Boolean,
        activityType: String,
        activityChatId: String,
        nowMs: Long
    ): Boolean {
        if (uid.isBlank()) return false
        if (snapshot.uid != uid) return true
        if (snapshot.isOnline != isOnline) return true
        if (activityType.isNotBlank() &&
            (activityType != snapshot.activityType || activityChatId != snapshot.activityChatId)
        ) return true
        if (isOnline && nowMs - snapshot.lastWriteAtMs >= HEARTBEAT_REFRESH_MS) return true
        return false
    }

    /**
     * The snapshot after a write landed. A null [activityType] means the
     * write did not stamp activity fields (the heartbeat payload leaves
     * them untouched when no activity is reported), so the tracked
     * activity survives; a non-null value replaces it.
     */
    fun recorded(
        snapshot: Snapshot,
        uid: String,
        isOnline: Boolean,
        activityType: String?,
        activityChatId: String?,
        nowMs: Long
    ): Snapshot = snapshot.copy(
        uid = uid,
        isOnline = isOnline,
        activityType = activityType ?: snapshot.activityType,
        activityChatId = activityChatId ?: snapshot.activityChatId,
        lastWriteAtMs = nowMs
    )

    /**
     * Read-side freshness: a heartbeat stamped at [lastHeartbeatAtMs] still
     * counts as "online evidence" at [nowMs]. A zero stamp means "no
     * heartbeat recorded" and keeps the historical meaning (fresh).
     */
    fun isWithinFreshnessWindow(lastHeartbeatAtMs: Long, nowMs: Long): Boolean =
        lastHeartbeatAtMs == 0L ||
            nowMs - lastHeartbeatAtMs <= maxOf(Constants.PRESENCE_ONLINE_TTL_MS, FRESHNESS_WINDOW_MS)

    // ---- process-wide live state (storage only; decisions stay pure) ----

    @Volatile
    private var live: Snapshot = Snapshot()

    fun shouldWriteNow(
        uid: String,
        isOnline: Boolean,
        activityType: String,
        activityChatId: String,
        nowMs: Long
    ): Boolean = shouldWrite(live, uid, isOnline, activityType, activityChatId, nowMs)

    @Synchronized
    fun recordWriteNow(
        uid: String,
        isOnline: Boolean,
        activityType: String?,
        activityChatId: String?,
        nowMs: Long
    ) {
        live = recorded(live, uid, isOnline, activityType, activityChatId, nowMs)
    }
}
