package com.wesal.messenger

import com.wesal.messenger.core.util.MessageMergeLogic
import com.wesal.messenger.domain.model.MessageStatus
import com.wesal.messenger.domain.model.WesalMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failover design §5.3: merged-read rules — dedupe by message id with
 * the status-rank winner rule, cross-store ordering by
 * (server timestamp, id), and local echoes trailing server-stamped
 * rows.
 */
class MessageMergeLogicTest {

    private fun msg(
        id: String,
        ts: Long,
        status: MessageStatus = MessageStatus.SENT,
        text: String = ""
    ) = WesalMessage(
        messageId = id,
        chatId = "chat1",
        senderId = "peer",
        type = com.wesal.messenger.domain.model.MessageType.TEXT,
        text = text,
        status = status,
        timestamp = ts
    )

    @Test
    fun merge_dedupesByIdAcrossStores() {
        val firestore = listOf(msg("a", 100), msg("b", 200))
        val supabase = listOf(msg("b", 200), msg("c", 300))
        val merged = MessageMergeLogic.merge(firestore, supabase)
        assertEquals(listOf("a", "b", "c"), merged.map { it.messageId })
    }

    @Test
    fun merge_moreAdvancedStatusWins() {
        val sentCopy = msg("m", 100, MessageStatus.SENT, text = "sent-copy")
        val readCopy = msg("m", 100, MessageStatus.READ, text = "read-copy")
        val merged = MessageMergeLogic.merge(listOf(sentCopy), listOf(readCopy))
        assertEquals(1, merged.size)
        assertEquals(MessageStatus.READ, merged[0].status)
        assertEquals("read-copy", merged[0].text)
        // Order of sources does not matter for the rank rule.
        val reversed = MessageMergeLogic.merge(listOf(readCopy), listOf(sentCopy))
        assertEquals(MessageStatus.READ, reversed[0].status)
    }

    @Test
    fun merge_statusTieBreaksToLaterTimestamp() {
        val older = msg("m", 100, MessageStatus.DELIVERED, text = "older")
        val newer = msg("m", 150, MessageStatus.DELIVERED, text = "newer")
        val merged = MessageMergeLogic.merge(listOf(older), listOf(newer))
        assertEquals("newer", merged[0].text)
    }

    @Test
    fun merge_ordersByTimestampThenId() {
        val merged = MessageMergeLogic.merge(
            listOf(msg("b", 200), msg("a", 200), msg("c", 100)),
            listOf(msg("d", 200))
        )
        // (100,c) < (200,a) < (200,b) < (200,d) — id breaks ts ties.
        assertEquals(listOf("c", "a", "b", "d"), merged.map { it.messageId })
    }

    @Test
    fun merge_echoesTrailServerStampedRows() {
        val echo = msg("echo", 50, MessageStatus.SENDING)
        val stamped = msg("s", 100, MessageStatus.SENT)
        val merged = MessageMergeLogic.merge(listOf(echo), listOf(stamped))
        assertEquals(listOf("s", "echo"), merged.map { it.messageId })
    }

    @Test
    fun merge_skipsBlankIdsAndHandlesEmptySources() {
        assertTrue(MessageMergeLogic.merge(emptyList(), emptyList()).isEmpty())
        val merged = MessageMergeLogic.merge(listOf(msg("", 100)), listOf(msg("x", 100)))
        assertEquals(listOf("x"), merged.map { it.messageId })
    }

    @Test
    fun statusRank_ordersStoreStatuses() {
        assertTrue(
            MessageMergeLogic.statusRank(MessageStatus.SENT) <
                MessageMergeLogic.statusRank(MessageStatus.DELIVERED)
        )
        assertTrue(
            MessageMergeLogic.statusRank(MessageStatus.DELIVERED) <
                MessageMergeLogic.statusRank(MessageStatus.READ)
        )
        assertTrue(MessageMergeLogic.statusRank(MessageStatus.SENDING) < 0)
        assertTrue(MessageMergeLogic.statusRank(MessageStatus.FAILED) < 0)
    }
}
