package com.wesal.messenger

import com.wesal.messenger.data.repository.SupabaseMessageMapper
import com.wesal.messenger.domain.model.MessageStatus
import com.wesal.messenger.domain.model.MessageType
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failover design §5.3/§7 stage 2: the mapper round-trip — a message
 * expressed as the Firestore field map (what sendTextMessage builds)
 * and as the Supabase row (snake_case columns + the function's
 * epoch-ms companions) must produce the same WesalMessage, and the
 * Firestore mapper's filtering rules (deleted-for-me, view-once
 * media) must hold identically on the plane.
 */
class SupabaseMessageMapperTest {

    private val plainResolver = SupabaseMessageMapper.PlaneTextResolver {
            _, _, _, plainFallback, isDeletedForEveryone, _ ->
        if (isDeletedForEveryone) "تم حذف هذه الرسالة" else plainFallback
    }

    /** The row exactly as msg-list returns it for the message below. */
    private fun planeRow(): JSONObject = JSONObject()
        .put("message_id", "msg-1")
        .put("chat_id", "chat-1")
        .put("seq", 42)
        .put("sender_id", "peer-uid")
        .put("sender_name", "Peer")
        .put("sender_icon_id", "icon_1")
        .put("sender_icon_url", "")
        .put("sender_verified", true)
        .put("sender_verification_type", "GOLD")
        .put("sender_neon_color", "#FF0000")
        .put("sender_neon_enabled", true)
        .put("sender_neon_glow_intensity", 0.5)
        .put("type", "TEXT")
        .put("ciphertext", "")
        .put("body_text", "أهلاً بالعالم")
        .put("is_encrypted", false)
        .put("is_view_once", false)
        .put("viewed_by", JSONArray())
        .put("media_url", "")
        .put("media_mime_type", "")
        .put("media_file_name", "")
        .put("media_size_bytes", 0)
        .put("media_duration_ms", 0)
        .put("waveform_amplitudes", JSONArray())
        .put("wrapped_keys", JSONObject())
        .put("latitude", JSONObject.NULL)
        .put("longitude", JSONObject.NULL)
        .put("location_address", "")
        .put("live_location_expires_at", 0)
        .put("disappearing_expires_at", 0)
        .put("reply_to_message_id", "msg-0")
        .put("reply_to_preview", "earlier")
        .put("forwarded_from_name", JSONObject.NULL)
        .put("is_edited", false)
        .put("is_deleted_for_everyone", false)
        .put("deleted_for_users", JSONArray())
        .put("status", "DELIVERED")
        .put("client_ts", 1_800_000_000_000L)
        .put("serverTsMs", 1_800_000_000_500L)
        .put("updatedAtMs", 1_800_000_001_000L)

    @Test
    fun rowMapsToTheSameMessageTheFirestoreMapWould() = runTest {
        val mapped = SupabaseMessageMapper.rowToMessage(
            planeRow(), "chat-1", "my-uid", plainResolver
        )!!
        val m = mapped.message
        assertEquals("msg-1", m.messageId)
        assertEquals("chat-1", m.chatId)
        assertEquals("peer-uid", m.senderId)
        assertEquals("Peer", m.senderName)
        assertEquals("icon_1", m.senderIconId)
        assertTrue(m.senderVerified)
        assertEquals("GOLD", m.senderVerificationType)
        assertEquals("#FF0000", m.senderNeonColor)
        assertTrue(m.senderNeonEnabled)
        assertEquals(0.5f, m.senderNeonGlowIntensity)
        assertEquals(MessageType.TEXT, m.type)
        assertEquals("أهلاً بالعالم", m.text)
        assertEquals(MessageStatus.DELIVERED, m.status)
        // The display stamp is the store's server time, not the client's.
        assertEquals(1_800_000_000_500L, m.timestamp)
        assertEquals("msg-0", m.replyToMessageId)
        assertEquals("earlier", m.replyToPreview)
        assertNull(m.forwardedFromName)
        assertNull(m.latitude)
        assertNull(m.longitude)
        assertEquals(42L, mapped.seq)
        assertEquals(1_800_000_001_000L, mapped.updatedAtMs)
    }

    @Test
    fun deletedForMeRowsAreFiltered() = runTest {
        val row = planeRow().put("deleted_for_users", JSONArray(listOf("my-uid")))
        assertNull(SupabaseMessageMapper.rowToMessage(row, "chat-1", "my-uid", plainResolver))
    }

    @Test
    fun expiredDisappearingRowsAreFiltered() = runTest {
        val row = planeRow().put("disappearing_expires_at", 1L)
        assertNull(SupabaseMessageMapper.rowToMessage(row, "chat-1", "my-uid", plainResolver))
    }

    @Test
    fun viewOnceMediaIsHiddenFromTheReceiverAfterViewing() = runTest {
        val row = planeRow()
            .put("type", "IMAGE")
            .put("is_view_once", true)
            .put("viewed_by", JSONArray(listOf("my-uid")))
            .put("media_url", "https://example.invalid/img.jpg")
        val mapped = SupabaseMessageMapper.rowToMessage(row, "chat-1", "my-uid", plainResolver)!!
        assertEquals("", mapped.message.mediaUrl)
        // The sender keeps his own media URL.
        val senderRow = planeRow()
            .put("type", "IMAGE")
            .put("is_view_once", true)
            .put("sender_id", "my-uid")
            .put("viewed_by", JSONArray(listOf("peer-uid")))
            .put("media_url", "https://example.invalid/img.jpg")
        val senderMapped = SupabaseMessageMapper.rowToMessage(
            senderRow, "chat-1", "my-uid", plainResolver
        )!!
        assertEquals("https://example.invalid/img.jpg", senderMapped.message.mediaUrl)
    }

    @Test
    fun missingServerStampFallsBackToClientTs() = runTest {
        val row = planeRow().put("serverTsMs", 0)
        val mapped = SupabaseMessageMapper.rowToMessage(row, "chat-1", "my-uid", plainResolver)!!
        assertEquals(1_800_000_000_000L, mapped.message.timestamp)
    }
}
