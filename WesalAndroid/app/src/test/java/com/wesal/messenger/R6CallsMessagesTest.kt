package com.wesal.messenger

import com.wesal.messenger.chat.mergeChatMessages
import com.wesal.messenger.data.repository.INCOMING_CALL_WINDOW_MS
import com.wesal.messenger.data.repository.isActionableIncomingCall
import com.wesal.messenger.domain.model.CallStatus
import com.wesal.messenger.domain.model.MessageStatus
import com.wesal.messenger.domain.model.WesalMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R6 regression coverage for the two owner-reported defects:
 *  1. Calls: the incoming-call eligibility predicate that replaced the
 *     rules-unprovable `calleeId == uid` Firestore query (the denied listener was
 *     what killed incoming detection/ringing and poisoned CallSignalingErrors with
 *     the «حدثت مشكلة في الاتصال بالخادم…» message).
 *  2. Messages sent twice: the chat-list merge must render exactly one bubble per
 *     message id — a local FAILED/SENDING echo is dropped whenever its id exists
 *     in ANY server-side source (live window OR an older page), and a genuinely
 *     failed send keeps exactly one echo.
 */
class R6CallsMessagesTest {

    private val me = "uid_me"
    private val peer = "uid_peer"
    private val now = 1_700_000_000_000L

    // ------------------------------------------------------------ incoming calls

    @Test
    fun freshRingingCallAddressedToMeIsActionable() {
        assertTrue(
            isActionableIncomingCall(
                calleeId = me, callerId = peer, status = CallStatus.RINGING,
                startedAt = now - 5_000L, uid = me, nowMs = now
            )
        )
        assertTrue(
            isActionableIncomingCall(
                calleeId = me, callerId = peer, status = CallStatus.CONNECTING,
                startedAt = now - 5_000L, uid = me, nowMs = now
            )
        )
    }

    @Test
    fun outgoingCallIsNotActionableAsIncoming() {
        // I participate in the call (the query returns it), but I am the caller.
        assertFalse(
            isActionableIncomingCall(
                calleeId = peer, callerId = me, status = CallStatus.RINGING,
                startedAt = now - 5_000L, uid = me, nowMs = now
            )
        )
    }

    @Test
    fun selfCallAndBlankCallerAreRejected() {
        assertFalse(
            isActionableIncomingCall(
                calleeId = me, callerId = me, status = CallStatus.RINGING,
                startedAt = now, uid = me, nowMs = now
            )
        )
        assertFalse(
            isActionableIncomingCall(
                calleeId = me, callerId = "", status = CallStatus.RINGING,
                startedAt = now, uid = me, nowMs = now
            )
        )
    }

    @Test
    fun finishedOrStaleCallsAreNotActionable() {
        assertFalse(
            isActionableIncomingCall(
                calleeId = me, callerId = peer, status = CallStatus.ENDED,
                startedAt = now, uid = me, nowMs = now
            )
        )
        assertFalse(
            isActionableIncomingCall(
                calleeId = me, callerId = peer, status = CallStatus.ACTIVE,
                startedAt = now, uid = me, nowMs = now
            )
        )
        assertFalse(
            isActionableIncomingCall(
                calleeId = me, callerId = peer, status = CallStatus.RINGING,
                startedAt = now - INCOMING_CALL_WINDOW_MS - 1L, uid = me, nowMs = now
            )
        )
        // Boundary: exactly at the window edge still counts as ringing.
        assertTrue(
            isActionableIncomingCall(
                calleeId = me, callerId = peer, status = CallStatus.RINGING,
                startedAt = now - INCOMING_CALL_WINDOW_MS, uid = me, nowMs = now
            )
        )
    }

    // ------------------------------------------------------------ message merge

    private fun msg(id: String, ts: Long, status: MessageStatus = MessageStatus.SENT) =
        WesalMessage(messageId = id, chatId = "dm_a_b", senderId = me, text = "hi", status = status, timestamp = ts)

    @Test
    fun echoIsDroppedWhenDeliveredCopyIsInLiveWindow() {
        val merged = mergeChatMessages(
            remote = listOf(msg("m1", 100L)),
            older = emptyList(),
            localEchoes = listOf(msg("m1", 100L, MessageStatus.FAILED))
        )
        assertEquals(listOf("m1"), merged.map { it.messageId })
        assertEquals(MessageStatus.SENT, merged.single().status)
    }

    @Test
    fun echoIsDroppedWhenDeliveredCopyIsOnlyInOlderPage() {
        // The R6 defect: dedupe used to consult the live window only, so this
        // combination rendered the delivered copy AND its FAILED echo together.
        val merged = mergeChatMessages(
            remote = emptyList(),
            older = listOf(msg("m1", 100L)),
            localEchoes = listOf(msg("m1", 100L, MessageStatus.FAILED))
        )
        assertEquals(listOf("m1"), merged.map { it.messageId })
        assertEquals(MessageStatus.SENT, merged.single().status)
    }

    @Test
    fun genuinelyFailedSendKeepsExactlyOneEcho() {
        val merged = mergeChatMessages(
            remote = listOf(msg("m0", 50L)),
            older = emptyList(),
            localEchoes = listOf(msg("m1", 100L, MessageStatus.FAILED))
        )
        assertEquals(listOf("m0", "m1"), merged.map { it.messageId })
        assertEquals(MessageStatus.FAILED, merged.last().status)
    }

    @Test
    fun olderPageEntriesDuplicatedInLiveWindowRenderOnce() {
        val merged = mergeChatMessages(
            remote = listOf(msg("m1", 100L), msg("m2", 200L)),
            older = listOf(msg("m0", 10L), msg("m1", 100L)),
            localEchoes = emptyList()
        )
        assertEquals(listOf("m0", "m1", "m2"), merged.map { it.messageId })
    }

    @Test
    fun mergedListIsSortedByTimestamp() {
        val merged = mergeChatMessages(
            remote = listOf(msg("m2", 200L)),
            older = listOf(msg("m0", 10L)),
            localEchoes = listOf(msg("m1", 100L, MessageStatus.SENDING))
        )
        assertEquals(listOf("m0", "m1", "m2"), merged.map { it.messageId })
    }
}
