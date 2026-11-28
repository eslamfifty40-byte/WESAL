package com.wesal.messenger.data.repository

import com.wesal.messenger.domain.model.MessageStatus
import com.wesal.messenger.domain.model.MessageType
import com.wesal.messenger.domain.model.WesalMessage
import org.json.JSONArray
import org.json.JSONObject

/** A mapped plane row plus the delta-cursor facts the repository tracks per chat. */
data class MappedPlaneMessage(
    val message: WesalMessage,
    val seq: Long,
    val updatedAtMs: Long
)

/**
 * Failover design §5.3: `chat_messages` row → [WesalMessage], the
 * transliteration counterpart of `ChatRepositoryImpl.mapAndDecryptMessage`.
 * Field rules are mirrored one-for-one from the Firestore mapper
 * (deleted-for-me filtering, disappearing expiry, the view-once media
 * rule, status decoding); the only difference is the source shape —
 * snake_case columns plus the function's epoch-ms companions
 * (`serverTsMs` / `updatedAtMs`) — and text resolution, which is
 * delegated to the repository so both mappers share the exact same
 * decrypt path (never a second implementation).
 */
object SupabaseMessageMapper {

    /** The decrypt/plain-text decision both mappers share (repository-owned). */
    fun interface PlaneTextResolver {
        suspend fun resolve(
            senderId: String,
            ciphertext: String,
            isEncrypted: Boolean,
            plainFallback: String,
            isDeletedForEveryone: Boolean,
            wrappedKeys: Map<String, String>
        ): String
    }

    suspend fun rowToMessage(
        row: JSONObject,
        chatId: String,
        myUid: String,
        resolveText: PlaneTextResolver
    ): MappedPlaneMessage? {
        val deletedFor = row.optStringArray("deleted_for_users")
        if (deletedFor.contains(myUid)) return null
        val disappearingExpiresAt = row.optLong("disappearing_expires_at")
        if (disappearingExpiresAt > 0L && System.currentTimeMillis() > disappearingExpiresAt) {
            return null
        }

        val senderId = row.optString("sender_id")
        val isDeletedForEveryone = row.optBoolean("is_deleted_for_everyone")
        val ciphertext = row.optString("ciphertext")
        val isEncrypted = row.optBoolean("is_encrypted")
        val isViewOnce = row.optBoolean("is_view_once")
        val viewedBy = row.optStringArray("viewed_by")
        val wrappedKeys = row.optStringMap("wrapped_keys")
        val plainFallback = row.optString("body_text")

        val resolvedText = resolveText.resolve(
            senderId, ciphertext, isEncrypted, plainFallback, isDeletedForEveryone, wrappedKeys
        )

        val mediaUrl = if (isViewOnce && viewedBy.isNotEmpty() && senderId != myUid) {
            ""
        } else {
            row.optString("media_url")
        }

        val serverTsMs = row.optLong("serverTsMs")
        val clientTs = row.optLong("client_ts")

        return MappedPlaneMessage(
            message = WesalMessage(
                messageId = row.optString("message_id"),
                chatId = chatId,
                senderId = senderId,
                senderName = row.optString("sender_name"),
                senderIconId = row.optString("sender_icon_id"),
                senderIconUrl = row.optString("sender_icon_url"),
                senderVerified = row.optBoolean("sender_verified"),
                senderVerificationType = row.optString("sender_verification_type").ifBlank { "BLUE" },
                senderNeonColor = row.optString("sender_neon_color"),
                senderNeonEnabled = row.optBoolean("sender_neon_enabled"),
                senderNeonGlowIntensity = row.optDouble("sender_neon_glow_intensity", 0.7).toFloat(),
                type = MessageType.fromRaw(row.optString("type")),
                ciphertext = ciphertext,
                text = resolvedText,
                isEncrypted = isEncrypted,
                isViewOnce = isViewOnce,
                viewedBy = viewedBy,
                mediaUrl = mediaUrl,
                mediaMimeType = row.optString("media_mime_type"),
                mediaFileName = row.optString("media_file_name"),
                mediaSizeBytes = row.optLong("media_size_bytes"),
                mediaDurationMs = row.optLong("media_duration_ms"),
                waveformAmplitudes = row.optIntArray("waveform_amplitudes"),
                wrappedKeys = wrappedKeys,
                latitude = row.optNullableDouble("latitude"),
                longitude = row.optNullableDouble("longitude"),
                locationAddress = row.optString("location_address"),
                liveLocationExpiresAt = row.optLong("live_location_expires_at"),
                disappearingExpiresAt = disappearingExpiresAt,
                replyToMessageId = row.optNullableString("reply_to_message_id"),
                replyToPreview = row.optNullableString("reply_to_preview"),
                forwardedFromName = row.optNullableString("forwarded_from_name"),
                isEdited = row.optBoolean("is_edited"),
                isDeletedForEveryone = isDeletedForEveryone,
                deletedForUsers = deletedFor,
                status = MessageStatus.fromRaw(row.optString("status")),
                timestamp = if (serverTsMs > 0L) serverTsMs else clientTs
            ),
            seq = row.optLong("seq"),
            updatedAtMs = row.optLong("updatedAtMs")
        )
    }
}

private fun JSONObject.optStringArray(key: String): List<String> {
    val arr = optJSONArray(key) ?: return emptyList()
    val out = ArrayList<String>(arr.length())
    for (i in 0 until arr.length()) {
        arr.optString(i).takeIf { it.isNotBlank() }?.let { out += it }
    }
    return out
}

private fun JSONObject.optIntArray(key: String): List<Int> {
    val arr: JSONArray = optJSONArray(key) ?: return emptyList()
    val out = ArrayList<Int>(arr.length())
    for (i in 0 until arr.length()) out += arr.optInt(i)
    return out
}

private fun JSONObject.optStringMap(key: String): Map<String, String> {
    val obj = optJSONObject(key) ?: return emptyMap()
    val out = HashMap<String, String>()
    val keys = obj.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        out[k] = obj.optString(k)
    }
    return out
}

private fun JSONObject.optNullableString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun JSONObject.optNullableDouble(key: String): Double? =
    if (isNull(key)) null else optDouble(key, Double.NaN).takeIf { !it.isNaN() }
