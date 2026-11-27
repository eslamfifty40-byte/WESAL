package com.wesal.messenger.data.repository

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
 * Failure reported by the Supabase phone-verification backend. [errorCode] is
 * the backend's stable machine code (`rate_limited_resend`, `code_expired`,
 * `invalid_code`, `phone_in_use`, `delivery_failed`, `unauthenticated`, …);
 * the exception message is the backend's own message (Arabic for user-facing
 * validation errors, English for 401/403). The repository layer maps the pair
 * to the Arabic text the phone screens show via
 * [com.wesal.messenger.core.util.PhoneOtpLogic.arabicMessageForErrorCode].
 */
class SupabasePhoneException(
    val errorCode: String,
    message: String
) : Exception(message)

/** One row of the Supabase `verified_phones` registry. */
data class SupabaseVerifiedPhone(
    val phoneHash: String,
    val firebaseUid: String,
    val phoneE164: String
)

/**
 * HTTPS client for the Supabase phone-verification backend (round 5), in the
 * same HttpURLConnection + org.json style as [FirestoreRest] — no new
 * networking dependency. The Firebase project is a Google-managed project
 * where Cloud Functions cannot be deployed, so the round-4 phone callables
 * were replaced by two Supabase Edge Functions plus PostgREST reads of the
 * `verified_phones` registry:
 *
 * - `POST /functions/v1/send-phone-code` `{phone, idToken}` — the caller is
 *   authenticated by the Firebase ID token in the BODY (the Authorization
 *   header carries the public anon key, per the deployed contract).
 * - `POST /functions/v1/verify-phone-code` `{phone, code, idToken}` — on
 *   success the backend binds the number to the token's uid in the registry
 *   and returns its canonical phoneHash.
 * - `GET /rest/v1/verified_phones` — anon-key reads (RLS allows SELECT for
 *   anon; writes exist only inside the Edge Functions via the service role).
 *
 * The registry is the SOLE authority on which number is verified for which
 * uid; Firestore keeps only a best-effort display mirror.
 */
@Singleton
class SupabasePhoneClient @Inject constructor() {

    private val functionsBase = Constants.SUPABASE_BASE_URL + "/functions/v1"
    private val restBase = Constants.SUPABASE_BASE_URL + "/rest/v1"

    private data class HttpResult(val status: Int, val body: String)

    private fun request(
        method: String,
        url: String,
        jsonBody: JSONObject? = null,
        readTimeoutMs: Int = 30_000
    ): HttpResult {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = readTimeoutMs
            c.requestMethod = method
            c.setRequestProperty("Authorization", "Bearer ${Constants.SUPABASE_ANON_KEY}")
            c.setRequestProperty("apikey", Constants.SUPABASE_ANON_KEY)
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (jsonBody != null) {
                c.doOutput = true
                c.outputStream.use { it.write(jsonBody.toString().toByteArray(Charsets.UTF_8)) }
            }
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

    private fun JSONObject.toVerifiedPhone(): SupabaseVerifiedPhone = SupabaseVerifiedPhone(
        phoneHash = optString("phone_hash"),
        firebaseUid = optString("firebase_uid"),
        phoneE164 = optString("phone_e164")
    )

    /**
     * Asks the backend to generate + deliver a 6-digit code for [phoneE164]
     * (E.164). Returns the delivery channel ("whatsapp" / "sms").
     * @throws SupabasePhoneException on any backend-reported failure.
     */
    suspend fun sendPhoneCode(phoneE164: String, idToken: String): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("phone", phoneE164)
                .put("idToken", idToken)
            val res = request("POST", "$functionsBase/send-phone-code", body)
            val json = parseObject(res.body)
            if (res.status == 200 && json.optBoolean("ok")) {
                json.optString("channel").takeIf { it.isNotBlank() } ?: "sms"
            } else {
                throw SupabasePhoneException(
                    json.optString("error").ifBlank { "server_error" },
                    json.optString("message")
                )
            }
        }

    /**
     * Verifies [code] for [phoneE164] against the challenge the backend bound
     * to [idToken]'s uid at send time. On success the number is bound to that
     * uid in the registry; returns the canonical phoneHash
     * (sha256 hex of the E.164 number — the client's PhoneUtils hash space).
     * @throws SupabasePhoneException on any backend-reported failure.
     */
    suspend fun verifyPhoneCode(phoneE164: String, code: String, idToken: String): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("phone", phoneE164)
                .put("code", code)
                .put("idToken", idToken)
            val res = request("POST", "$functionsBase/verify-phone-code", body)
            val json = parseObject(res.body)
            if (res.status == 200 && json.optBoolean("ok")) {
                json.optString("phoneHash").takeIf { it.isNotBlank() }
                    ?: throw SupabasePhoneException("server_error", "")
            } else {
                throw SupabasePhoneException(
                    json.optString("error").ifBlank { "server_error" },
                    json.optString("message")
                )
            }
        }

    /**
     * The registry row bound to [uid], or null when the uid has no verified
     * number. Read failures collapse to null as well: every caller treats
     * "no usable row" as "not (provably) verified", which is the fail-closed
     * answer the gate and the phoneHash heal both want.
     */
    suspend fun fetchVerifiedPhoneForUid(uid: String): SupabaseVerifiedPhone? =
        withContext(Dispatchers.IO) {
            try {
                val url = "$restBase/verified_phones" +
                    "?select=phone_hash,firebase_uid,phone_e164&firebase_uid=eq.$uid"
                val res = request("GET", url, null, readTimeoutMs = 10_000)
                if (res.status != 200) return@withContext null
                val rows = JSONArray(res.body)
                if (rows.length() == 0) {
                    null
                } else {
                    rows.getJSONObject(0).toVerifiedPhone()
                        .takeIf { it.phoneHash.isNotBlank() && it.firebaseUid.isNotBlank() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Batch registry lookup for contacts discovery: phoneHash → registry row.
     * Returns null ONLY when the lookup itself failed (HTTP/parse/network) —
     * an empty map is a successful "nobody matched" answer, and callers rely
     * on the distinction to tell a total outage from an empty result.
     * Hashes are lowercase hex, so the PostgREST `in.(…)` list needs no
     * escaping.
     */
    suspend fun findVerifiedPhonesByHashes(
        phoneHashes: List<String>
    ): Map<String, SupabaseVerifiedPhone>? = withContext(Dispatchers.IO) {
        try {
            if (phoneHashes.isEmpty()) return@withContext emptyMap()
            val inList = phoneHashes.joinToString(",")
            val url = "$restBase/verified_phones" +
                "?select=phone_hash,firebase_uid,phone_e164&phone_hash=in.($inList)"
            val res = request("GET", url, null, readTimeoutMs = 12_000)
            if (res.status != 200) return@withContext null
            val rows = JSONArray(res.body)
            val out = HashMap<String, SupabaseVerifiedPhone>()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i).toVerifiedPhone()
                if (row.phoneHash.isNotBlank() && row.firebaseUid.isNotBlank()) {
                    out[row.phoneHash] = row
                }
            }
            out
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
