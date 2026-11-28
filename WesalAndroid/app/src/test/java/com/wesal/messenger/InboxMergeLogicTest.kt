package com.wesal.messenger

import com.wesal.messenger.core.util.InboxMergeLogic
import com.wesal.messenger.domain.model.SupabaseInboxRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failover design §5.4: chats-list summary merge — the newer summary
 * by (lastMessageClientTs, lastMessageId) wins the preview slot, and
 * unread counts sum because the two stores' increments are disjoint
 * by construction.
 */
class InboxMergeLogicTest {

    private fun inbox(id: String?, clientTs: Long, unread: Int = 0) = SupabaseInboxRow(
        chatId = "chat1",
        lastMessageId = id,
        lastMessageClientTs = clientTs,
        unreadCount = unread
    )

    @Test
    fun newerClientTsWins() {
        assertTrue(InboxMergeLogic.isInboxNewer(100L, "m1", inbox("m2", 200L)))
        assertFalse(InboxMergeLogic.isInboxNewer(200L, "m2", inbox("m1", 100L)))
    }

    @Test
    fun equalClientTsBreaksTieByMessageId() {
        assertTrue(InboxMergeLogic.isInboxNewer(100L, "m1", inbox("m2", 100L)))
        assertFalse(InboxMergeLogic.isInboxNewer(100L, "m2", inbox("m1", 100L)))
        assertFalse(InboxMergeLogic.isInboxNewer(100L, "m1", inbox("m1", 100L)))
    }

    @Test
    fun blankInboxIdIsNeverNewer() {
        assertFalse(InboxMergeLogic.isInboxNewer(0L, null, inbox(null, 500L)))
        assertFalse(InboxMergeLogic.isInboxNewer(0L, null, inbox("", 500L)))
    }

    @Test
    fun firestoreWithoutSummaryYieldsToAnyInboxRow() {
        assertTrue(InboxMergeLogic.isInboxNewer(0L, null, inbox("m1", 1L)))
    }

    @Test
    fun unreadCountsSumAndClamp() {
        assertEquals(5, InboxMergeLogic.mergedUnreadCount(2, 3))
        assertEquals(0, InboxMergeLogic.mergedUnreadCount(0, 0))
        assertEquals(0, InboxMergeLogic.mergedUnreadCount(-1, 0))
    }
}
