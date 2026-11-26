package com.wesal.messenger.data.repository

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Direct HTTPS REST access to Firestore. The Android SDK has no long-polling mode (that only
 * exists on web), so when the gRPC write stream silently stalls (writes never ack while reads
 * work), writes are sent through the REST endpoint instead. Receiving is untouched: the Listen
 * stream keeps working, so messages written here arrive through the existing snapshot listeners.
 */
object FirestoreRest {
    private const val PROJECT_ID = "idyllic-axon-mw1rw"
    private const val DB_ID = "ai-studio-livo-cd975dac-35e6-4dbb-864e-829e3b638424"
    private const val BASE =
        "https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/$DB_ID/documents"

    private suspend fun idToken(auth: FirebaseAuth): String {
        val user = auth.currentUser ?: error("NOUSER")
        return user.getIdToken(false).await().token ?: error("NOTOKEN")
    }

    /** The SDK attaches an App Check token to every request automatically; raw REST must send it
     *  explicitly or an enforced App Check rejects the call with 401/403 and fakes a rules problem. */
    private suspend fun appCheckToken(): String? =
        runCatching { FirebaseAppCheck.getInstance().getToken(false).await().token }.getOrNull()

    private fun open(url: String, token: String, appCheck: String?, method: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 12000
        c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer $token")
        if (!appCheck.isNullOrBlank()) c.setRequestProperty("X-Firebase-AppCheck", appCheck)
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.doOutput = true
        return c
    }

    private fun post(c: HttpURLConnection, body: JSONObject): Int {
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        // Drain a little of the error body so the connection is reusable, then close.
        runCatching { (if (code >= 400) c.errorStream else c.inputStream)?.close() }
        c.disconnect()
        return code
    }

    /** "200" | "403" | "429" | "TIMEOUT" | "ERR:..." — REST twin of the SDK wr= probe. */
    suspend fun probeWrite(auth: FirebaseAuth): String {
        val uid = auth.currentUser?.uid ?: return "NOUSER"
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(15000L) {
                runCatching {
                    val fields = JSONObject().put(
                        "fields", JSONObject().put(
                            "lastHeartbeatAt",
                            JSONObject().put("integerValue", System.currentTimeMillis().toString())
                        )
                    )
                    val c = open("$BASE/users/$uid?updateMask.fieldPaths=lastHeartbeatAt", idToken(auth), appCheckToken(), "PATCH")
                    post(c, fields).toString()
                }.getOrElse { "ERR:${it.javaClass.simpleName}" }
            } ?: "TIMEOUT"
        }
    }

    private fun fieldValue(v: Any?): JSONObject = when (v) {
        null -> JSONObject().put("nullValue", JSONObject.NULL)
        is Boolean -> JSONObject().put("booleanValue", v)
        is Long -> JSONObject().put("integerValue", v.toString())
        is Int -> JSONObject().put("integerValue", v.toString())
        is Double -> JSONObject().put("doubleValue", v)
        is String -> JSONObject().put("stringValue", v)
        is Map<*, *> -> {
            val m = JSONObject()
            v.forEach { (k, value) -> if (k is String) m.put(k, fieldValue(value)) }
            JSONObject().put("mapValue", JSONObject().put("fields", m))
        }
        is List<*> -> {
            val arr = JSONArray()
            v.forEach { arr.put(fieldValue(it)) }
            JSONObject().put("arrayValue", JSONObject().put("values", arr))
        }
        else -> JSONObject().put("stringValue", v.toString())
    }

    private fun docFields(data: Map<String, Any?>): JSONObject {
        val fields = JSONObject()
        data.forEach { (k, v) -> fields.put(k, fieldValue(v)) }
        return fields
    }

    private fun docName(docPath: String) =
        "projects/$PROJECT_ID/databases/$DB_ID/documents/$docPath"

    /**
     * Commits a text message: sets the message document and updates the chat summary with
     * unread-count increments, in one commit. Returns the HTTP status code.
     */
    suspend fun commitTextMessage(
        auth: FirebaseAuth,
        chatDocPath: String,
        messageDocPath: String,
        messageData: Map<String, Any?>,
        chatScalarFields: Map<String, Any?>,
        unreadIncrementUids: List<String>
    ): Int = withContext(Dispatchers.IO) {
        val msgWrite = JSONObject().put(
            "update", JSONObject()
                .put("name", docName(messageDocPath))
                .put("fields", docFields(messageData))
        )
        val mask = JSONArray()
        chatScalarFields.keys.forEach { mask.put(it) }
        val transforms = JSONArray()
        unreadIncrementUids.forEach { uid ->
            transforms.put(
                JSONObject()
                    .put("fieldPath", "unreadCounts.$uid")
                    .put("increment", JSONObject().put("integerValue", "1"))
            )
        }
        val chatWrite = JSONObject()
            .put("currentDocument", JSONObject().put("exists", true))
            .put(
                "update", JSONObject()
                    .put("name", docName(chatDocPath))
                    .put("fields", docFields(chatScalarFields))
            )
            .put("updateMask", JSONObject().put("fieldPaths", mask))
            .put("updateTransforms", transforms)
        val body = JSONObject().put("writes", JSONArray().put(msgWrite).put(chatWrite))
        val c = open("$BASE:commit", idToken(auth), appCheckToken(), "POST")
        post(c, body)
    }
}
