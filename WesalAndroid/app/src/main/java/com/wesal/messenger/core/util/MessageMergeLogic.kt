package com.wesal.messenger.core.util

import com.wesal.messenger.domain.model.MessageStatus
import com.wesal.messenger.domain.model.WesalMessage

/**
 * Failover design §5.3: the pure merge rules for a chat whose history
 * spans two stores (Firestore + the Supabase message plane).
 *
 * - **Dedupe by `messageId`** — the client outbox UUID is the primary
 *   key in both stores and in Room, so a message that exists in both
 *   (the §5.2 rule-3 cross-store retry case) renders exactly once.
 * - **Winner rule**: the copy with the more advanced status wins
 *   (SENT < DELIVERED < READ); ties break to the later timestamp (the
 *   home store's server stamp), then to the later list position
 *   (callers pass sources in freshness order — local, Firestore,
 *   Supabase poll last, so the freshest copy wins a full tie).
 * - **Ordering**: `(timestamp, messageId)` ascending — both stores
 *   stamp with NTP-disciplined server clocks, so cross-store order is
 *   consistent to sub-second skew and always deterministic. Local
 *   echoes (SENDING/FAILED, not yet in either store) sort after all
 *   server-stamped rows by their client timestamp, as they do today.
 *
 * Pure functions only — unit-tested on the JVM like the r14 gates.
 */
object MessageMergeLogic {

    /** Store-carried statuses ranked; local echo states rank below all. */
    fun statusRank(status: MessageStatus): Int = when (status) {
        MessageStatus.SENT -> 0
        MessageStatus.DELIVERED -> 1
        MessageStatus.READ -> 2
        MessageStatus.SENDING, MessageStatus.FAILED -> -1
    }

    private fun isEcho(message: WesalMessage): Boolean =
        statusRank(message.status) < 0

    /** Picks the winning copy of one message id per the rules above. */
    fun winner(a: WesalMessage, b: WesalMessage): WesalMessage {
        val rankA = statusRank(a.status)
        val rankB = statusRank(b.status)
        if (rankA != rankB) return if (rankA > rankB) a else b
        if (a.timestamp != b.timestamp) return if (a.timestamp > b.timestamp) a else b
        return b
    }

    /**
     * Merges any number of per-source lists into one deduped,
     * deterministically ordered list. Sources should be passed in
     * freshness order (least fresh first) so full ties resolve to the
     * freshest copy.
     */
    fun merge(vararg sources: List<WesalMessage>): List<WesalMessage> {
        val byId = LinkedHashMap<String, WesalMessage>()
        for (source in sources) {
            for (message in source) {
                if (message.messageId.isBlank()) continue
                val existing = byId[message.messageId]
                byId[message.messageId] =
                    if (existing == null) message else winner(existing, message)
            }
        }
        val stamped = ArrayList<WesalMessage>()
        val echoes = ArrayList<WesalMessage>()
        for (message in byId.values) {
            if (isEcho(message)) echoes += message else stamped += message
        }
        stamped.sortWith(compareBy({ it.timestamp }, { it.messageId }))
        echoes.sortWith(compareBy({ it.timestamp }, { it.messageId }))
        return stamped + echoes
    }
}
