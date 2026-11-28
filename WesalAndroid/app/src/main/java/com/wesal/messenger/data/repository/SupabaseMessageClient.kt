package com.wesal.messenger.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.wesal.messenger.core.util.BackendHealthStore
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.domain.model.SupabaseInboxRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Failure reported by the Supabase message plane (FAILOVER_DESIGN.md
 * §4.3). [errorCode] is the function's stable machine code
 * (`unauthenticated`, `not_a_participant`, `chat_not_registered`,
 * `invalid_message`, `store_unavailable`); callers branch on the code,
 * never on the message text (ErrorCopy owns user-facing copy).
 */
class SupabaseMessageException(
    val errorCode: String,
    val httpStatus: Int,
    message: String = ""
) : Exception(message)

/** `msg-send` outcome: whether the row landed (false = idempotent retry of an already-landed id). */
data class PlaneSendResult(
    val landed: Boolean,
    val serverTsMs: Long,
    val seq: Long
)

/** One `msg-list` page: mapped rows stay JSON here; [SupabaseMessageMapper] owns row → model. */
data class PlaneListPage(
    val rows: List<JSONObject>,
    val typingUserIds: List<String>,
    val hasMore: Boolean
)

/**
 * HTTPS client for the failover message plane (FAILOVER_DESIGN.md §4),
 * a sibling of [SupabasePhoneClient]: same HttpURLConnection + org.json
 * shape, same envelope. Every function takes the caller's Firebase ID
 * token in the BODY and verifies it against Google's JWKS server-side;
 * the Authorization header carries the public anon key.
 *
 * Health semantics (§5.1) live here so every caller inherits them:
 * - transport failure / HTTP 5xx / `store_unavailable` reports to
 *   [BackendHealthStore] (Supabase DOWN, short probe ladder);
 * - while DOWN and the next probe is not yet due, calls fail fast
 *   with `store_unavailable` WITHOUT touching the network (stickiness
 *   — a dead backend is never hammered); a call made when a probe IS
 *   due acts as the probe and records its outcome;
 * - a 401 is NOT a health event: the Firebase token is force-refreshed
 *   once and the call retried once before surfacing `unauthenticated`.
 */
@Singleton
class SupabaseMessageClient @Inject constructor(
    private val auth: FirebaseAuth
) {

    private val functionsBase = Constants.SUPABASE_BASE_URL + "/functions/v1"

    private data class HttpResult(val status: Int, val body: String)

    private fun request(
        function: String,
        jsonBody: JSONObject,
        token: String,
        readTimeoutMs: Int
    ): HttpResult {
        val c = URL("$functionsBase/$function").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = readTimeoutMs
            c.requestMethod = "POST"
            c.setRequestProperty("Authorization", "Bearer ${Constants.SUPABASE_ANON_KEY}")
            c.setRequestProperty("apikey", Constants.SUPABASE_ANON_KEY)
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val payload = JSONObject(jsonBody.toString()).put("idToken", token)
            c.doOutput = true
            c.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = runCatching {
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }.getOrNull().orEmpty()
            return HttpResult(code, body)
        } finally {
            c.disconnect()
        }
    }

    private fun parseObject(body: String): JSONObject =
        runCatching { JSONObject(body) }.getOrDefault(JSONObject())

    private suspend fun freshToken(forceRefresh: Boolean): String {
        val user = auth.currentUser ?: throw SupabaseMessageException(
            "unauthenticated", 401, "Not signed in"
        )
        return user.getIdToken(forceRefresh).await().token
            ?: throw SupabaseMessageException("unauthenticated", 401, "Empty ID token")
    }

    /**
     * One plane call with the §5.1 health contract applied. Returns the
     * function's `{ok:true, …}` body on success.
     * @throws SupabaseMessageException on every failure shape.
     */
    private suspend fun callFunction(
        function: String,
        body: JSONObject,
        readTimeoutMs: Int = 20_000
    ): JSONObject = withContext(Dispatchers.IO) {
        val health = BackendHealthStore.currentState()
        val supabaseDown = !BackendHealthStore.isRoutable(
            health, BackendHealthStore.Backend.SUPABASE
        )
        val probing = supabaseDown && BackendHealthStore.probeDue(
            health, BackendHealthStore.Backend.SUPABASE, System.currentTimeMillis()
        )
        if (supabaseDown && !probing) {
            throw SupabaseMessageException("store_unavailable", 0, "Supabase down; probe not due")
        }

        fun onStoreFailure() {
            if (probing) {
                BackendHealthStore.recordProbeResult(
                    BackendHealthStore.Backend.SUPABASE, false
                )
            } else {
                BackendHealthStore.reportSupabaseFailure()
            }
        }

        var token = freshToken(forceRefresh = false)
        var retriedAuth = false
        while (true) {
            val res = try {
                request(function, body, token, readTimeoutMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onStoreFailure()
                throw SupabaseMessageException("store_unavailable", 0, e.message.orEmpty())
            }
            val json = parseObject(res.body)
            val code = json.optString("error")
            when {
                res.status == 200 && json.optBoolean("ok") -> {
                    if (probing) {
                        BackendHealthStore.recordProbeResult(
                            BackendHealthStore.Backend.SUPABASE, true
                        )
                    }
                    return@withContext json
                }
                res.status == 401 || code == "unauthenticated" -> {
                    if (retriedAuth) {
                        throw SupabaseMessageException("unauthenticated", 401, json.optString("message"))
                    }
                    retriedAuth = true
                    token = freshToken(forceRefresh = true)
                }
                res.status >= 500 || code == "store_unavailable" -> {
                    onStoreFailure()
                    throw SupabaseMessageException(
                        code.ifBlank { "store_unavailable" }, res.status, json.optString("message")
                    )
                }
                else -> throw SupabaseMessageException(
                    code.ifBlank { "server_error" }, res.status, json.optString("message")
                )
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw SupabaseMessageException("server_error", 0)
    }

    /** The router's probe target (§4.3): cheapest authenticated round-trip. */
    suspend fun health(): Boolean = try {
        callFunction("msg-health", JSONObject(), readTimeoutMs = 10_000)
        true
    } catch (e: SupabaseMessageException) {
        false
    }

    /**
     * Registers a chat's membership mirror (first-write-wins).
     * @return true when a mirror already existed.
     * @throws SupabaseMessageException `not_a_participant` when the stored
     * mirror (registered by someone else first) excludes the caller.
     */
    suspend fun registerChat(
        chatId: String,
        kind: String,
        participantIds: List<String>
    ): Boolean {
        val body = JSONObject()
            .put("chatId", chatId)
            .put("kind", kind)
            .put("participantIds", JSONArray(participantIds))
        val json = callFunction("msg-register-chat", body)
        return json.optBoolean("existed")
    }

    /**
     * Sends one message whose field map is the exact map the Firestore
     * path builds (§4.3 transliteration happens server-side).
     */
    suspend fun sendMessage(messageFields: Map<String, Any?>): PlaneSendResult {
        val json = callFunction("msg-send", JSONObject().put("message", messageFields.toJsonObject()))
        return PlaneSendResult(
            landed = json.optBoolean("landed"),
            serverTsMs = json.optLong("serverTsMs"),
            seq = json.optLong("seq")
        )
    }

    /** Delta / page / initial read of one chat (§4.3 three modes). */
    suspend fun listMessages(
        chatId: String,
        afterSeq: Long? = null,
        beforeSeq: Long? = null,
        watermarkMs: Long = 0L,
        limit: Int? = null
    ): PlaneListPage {
        val body = JSONObject().put("chatId", chatId)
        if (afterSeq != null) body.put("afterSeq", afterSeq)
        if (beforeSeq != null) body.put("beforeSeq", beforeSeq)
        if (watermarkMs > 0L) body.put("watermarkMs", watermarkMs)
        if (limit != null) body.put("limit", limit)
        val json = callFunction("msg-list", body, readTimeoutMs = 15_000)
        return parseListPage(json)
    }

    /** Existence/read of specific ids in one chat (§5.2 rule 5 replay check). */
    suspend fun listMessagesByIds(chatId: String, ids: List<String>): PlaneListPage {
        val body = JSONObject()
            .put("chatId", chatId)
            .put("ids", JSONArray(ids))
        val json = callFunction("msg-list", body, readTimeoutMs = 15_000)
        return parseListPage(json)
    }

    private fun parseListPage(json: JSONObject): PlaneListPage {
        val rows = ArrayList<JSONObject>()
        val messages = json.optJSONArray("messages")
        if (messages != null) {
            for (i in 0 until messages.length()) {
                messages.optJSONObject(i)?.let { rows += it }
            }
        }
        val typing = ArrayList<String>()
        val typingArr = json.optJSONArray("typing")
        if (typingArr != null) {
            for (i in 0 until typingArr.length()) {
                typingArr.optJSONObject(i)?.optString("userId")
                    ?.takeIf { it.isNotBlank() }?.let { typing += it }
            }
        }
        return PlaneListPage(rows, typing, json.optBoolean("hasMore"))
    }

    /** Batched receipt ack at the r14 cadences (§5.4); status is DELIVERED or READ. */
    suspend fun ackMessages(chatId: String, messageIds: List<String>, statusRaw: String) {
        if (messageIds.isEmpty()) return
        val body = JSONObject()
            .put("chatId", chatId)
            .put("messageIds", JSONArray(messageIds))
            .put("status", statusRaw)
        callFunction("msg-ack", body, readTimeoutMs = 15_000)
    }

    /** All of the caller's inbox rows (chats-list merge + notification poll source). */
    suspend fun inbox(): List<SupabaseInboxRow> {
        val json = callFunction("msg-inbox", JSONObject(), readTimeoutMs = 15_000)
        val rows = ArrayList<SupabaseInboxRow>()
        val arr = json.optJSONArray("rows") ?: return rows
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            rows += SupabaseInboxRow(
                chatId = row.optString("chatId"),
                lastMessageId = row.optString("lastMessageId").takeIf { it.isNotBlank() },
                lastMessagePreview = row.optString("lastMessagePreview"),
                lastMessageSenderId = row.optString("lastMessageSenderId"),
                lastMessageSenderName = row.optString("lastMessageSenderName"),
                lastMessageStatus = row.optString("lastMessageStatus").ifBlank { "SENT" },
                lastMessageServerTsMs = row.optLong("lastMessageServerTsMs"),
                lastMessageClientTs = row.optLong("lastMessageClientTs"),
                unreadCount = row.optInt("unreadCount"),
                updatedAtMs = row.optLong("updatedAtMs")
            )
        }
        return rows
    }

    /** Typing transition for a plane chat (§5.5); the gate decides when this is called. */
    suspend fun setTyping(chatId: String, on: Boolean) {
        val body = JSONObject()
            .put("chatId", chatId)
            .put("on", on)
        callFunction("chat-typing", body, readTimeoutMs = 10_000)
    }
}

/** Firestore field map → JSON (nulls skipped: the functions treat missing as null). */
private fun Map<String, Any?>.toJsonObject(): JSONObject {
    val out = JSONObject()
    for ((key, value) in this) {
        if (value == null) continue
        out.put(key, value.toJsonValue())
    }
    return out
}

private fun Any.toJsonValue(): Any = when (this) {
    is Map<*, *> -> {
        val out = JSONObject()
        for ((k, v) in this) {
            if (k is String && v != null) out.put(k, v.toJsonValue())
        }
        out
    }
    is List<*> -> {
        val arr = JSONArray()
        for (item in this) {
            if (item != null) arr.put(item.toJsonValue())
        }
        arr
    }
    else -> this
}
