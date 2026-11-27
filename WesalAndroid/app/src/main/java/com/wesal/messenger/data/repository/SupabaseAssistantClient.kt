package com.wesal.messenger.data.repository

import com.wesal.messenger.core.util.AssistantPayloadMessage
import com.wesal.messenger.core.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Failure reported by the Supabase `ai-assistant` backend. [status] is the
 * HTTP status (401 unauthenticated, 429 daily free limit, 5xx server, or
 * -1 when the request never reached the server); the caller maps it to
 * the Arabic bubble the assistant shows (see AssistantLogic).
 */
class SupabaseAssistantException(
    val status: Int,
    val errorCode: String,
    message: String
) : Exception(message)

/**
 * HTTPS client for the Supabase `ai-assistant` Edge Function (round 8), in
 * the same HttpURLConnection + org.json style as [SupabasePhoneClient] —
 * no new networking dependency. Contract:
 *
 * - `POST /functions/v1/ai-assistant` with the public anon key in the
 *   Authorization header (same as the phone backend) and the caller's
 *   Firebase ID token in the BODY (`idToken`), which the function
 *   verifies before answering.
 * - Body: `{"idToken": "...", "messages": [{"role": "user"|"assistant",
 *   "text": "...", "media"?: {"mimeType": "...", "dataBase64": "..."}}]}`
 *   — at most the last 10 messages, an attachment only on the newest one
 *   (shaping lives in AssistantLogic.buildPayload).
 * - 200: `{"reply": "..."}`. Errors: 401 unauthenticated, 429 daily free
 *   limit, 5xx generic.
 *
 * The read timeout is longer than the phone client's (60s vs 30s): an LLM
 * answer legitimately takes longer to generate than an OTP dispatch.
 */
@Singleton
class SupabaseAssistantClient @Inject constructor() {

    private val functionUrl = Constants.SUPABASE_BASE_URL + "/functions/v1/ai-assistant"

    private data class HttpResult(val status: Int, val body: String)

    private fun request(jsonBody: JSONObject): HttpResult {
        val c = URL(functionUrl).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 60_000
            c.requestMethod = "POST"
            c.setRequestProperty("Authorization", "Bearer ${Constants.SUPABASE_ANON_KEY}")
            c.setRequestProperty("apikey", Constants.SUPABASE_ANON_KEY)
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.doOutput = true
            c.outputStream.use { it.write(jsonBody.toString().toByteArray(Charsets.UTF_8)) }
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

    /**
     * Sends one ask to the assistant backend and returns its reply text.
     * @throws SupabaseAssistantException on any non-200 outcome or when the
     * reply is missing/blank.
     */
    suspend fun askAssistant(
        idToken: String,
        messages: List<AssistantPayloadMessage>
    ): String = withContext(Dispatchers.IO) {
        val messagesJson = JSONArray()
        messages.forEach { message ->
            val entry = JSONObject()
                .put("role", message.role)
                .put("text", message.text)
            message.media?.let { media ->
                entry.put(
                    "media",
                    JSONObject()
                        .put("mimeType", media.mimeType)
                        .put("dataBase64", media.dataBase64)
                )
            }
            messagesJson.put(entry)
        }
        val body = JSONObject()
            .put("idToken", idToken)
            .put("messages", messagesJson)

        val res = try {
            request(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SupabaseAssistantException(-1, "network_error", e.message.orEmpty())
        }
        val json = parseObject(res.body)
        if (res.status == 200) {
            json.optString("reply").takeIf { it.isNotBlank() }
                ?: throw SupabaseAssistantException(200, "empty_reply", "")
        } else {
            throw SupabaseAssistantException(
                res.status,
                json.optString("error").ifBlank { "server_error" },
                json.optString("message")
            )
        }
    }
}
