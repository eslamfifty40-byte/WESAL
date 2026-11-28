package com.wesal.messenger.core.util

import com.wesal.messenger.domain.model.SupabaseInboxRow

/**
 * Failover design §5.4: the pure merge rules between the Firestore
 * chats doc's summary fields and the Supabase plane's `chat_inbox`
 * row for the same chat.
 *
 * - **Newer summary wins** by `(lastMessageClientTs, lastMessageId)`
 *   compared lexicographically — client ts first (both summaries are
 *   stamped from the same sender-side value the message itself
 *   carries), message id as the deterministic tiebreak.
 * - **Unread counts SUM.** The two counters are disjoint by
 *   construction: a message lands on exactly one store (§5.2), and
 *   its unread increment lands only on that store's summary
 *   (Firestore batch *or* rpc_send_message). The single overlap case
 *   (landed-but-unacked send retried cross-store) is neutralized by
 *   the stores' id-keyed writes and the §5.2 rule-5 existence check.
 *
 * Pure functions only — unit-tested on the JVM like the r14 gates.
 */
object InboxMergeLogic {

    /**
     * True when the inbox row's last-message summary is strictly newer
     * than the Firestore doc's `(clientTs, messageId)` pair.
     */
    fun isInboxNewer(
        firestoreLastMessageClientTs: Long,
        firestoreLastMessageId: String?,
        inbox: SupabaseInboxRow
    ): Boolean {
        if (inbox.lastMessageId.isNullOrBlank()) return false
        if (inbox.lastMessageClientTs != firestoreLastMessageClientTs) {
            return inbox.lastMessageClientTs > firestoreLastMessageClientTs
        }
        return inbox.lastMessageId > (firestoreLastMessageId ?: "")
    }

    /** The chats-list unread badge for a chat with traffic on both stores. */
    fun mergedUnreadCount(firestoreUnread: Int, inboxUnread: Int): Int =
        (firestoreUnread + inboxUnread).coerceAtLeast(0)
}
