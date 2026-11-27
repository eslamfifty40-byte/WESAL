package com.wesal.messenger.data.repository

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

    private fun open(url: String, token: String, method: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 12000
        c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer $token")
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
                    val c = open("$BASE/users/$uid?updateMask.fieldPaths=lastHeartbeatAt", idToken(auth), "PATCH")
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
        is Float -> JSONObject().put("doubleValue", v.toDouble())
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
     * True = document exists on the server, false = it does not (404), null = could not tell
     * (network/auth error). Used by the send path to stay idempotent: if a timed-out SDK write
     * actually landed, its message document exists and the send must not be repeated (a repeat
     * would duplicate the message and double-increment the unread counter).
     */
    suspend fun documentExists(auth: FirebaseAuth, docPath: String): Boolean? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(10_000L) {
                runCatching {
                    val c = open("$BASE/$docPath", idToken(auth), "GET")
                    c.doOutput = false
                    val code = c.responseCode
                    runCatching {
                        (if (code >= 400) c.errorStream else c.inputStream)?.close()
                    }
                    c.disconnect()
                    when (code) {
                        200 -> true
                        404 -> false
                        else -> null
                    }
                }.getOrNull()
            }
        }

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
        val c = open("$BASE:commit", idToken(auth), "POST")
        post(c, body)
    }

    suspend fun setDocument(
        auth: FirebaseAuth,
        docPath: String,
        data: Map<String, Any?>,
        merge: Boolean = false
    ): Int = withContext(Dispatchers.IO) {
        val url = if (merge && data.isNotEmpty()) {
            val maskParams = data.keys.joinToString("&") { "updateMask.fieldPaths=${java.net.URLEncoder.encode(it, "UTF-8")}" }
            "$BASE/$docPath?$maskParams"
        } else {
            "$BASE/$docPath"
        }
        val body = JSONObject().put("fields", docFields(data))
        val c = open(url, idToken(auth), "PATCH")
        post(c, body)
    }

    suspend fun deleteDocument(auth: FirebaseAuth, docPath: String): Int = withContext(Dispatchers.IO) {
        val c = open("$BASE/$docPath", idToken(auth), "DELETE")
        c.doOutput = false
        val code = c.responseCode
        runCatching { (if (code >= 400) c.errorStream else c.inputStream)?.close() }
        c.disconnect()
        code
    }

    suspend fun listDocumentIds(
        auth: FirebaseAuth,
        collectionPath: String,
        pageSize: Int = 300
    ): List<String> = withContext(Dispatchers.IO) {
        val c = open("$BASE/$collectionPath?pageSize=${pageSize.coerceIn(1, 300)}", idToken(auth), "GET")
        c.doOutput = false
        val code = c.responseCode
        val bodyText = runCatching {
            val stream = if (code >= 400) c.errorStream else c.inputStream
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        }.getOrDefault("")
        c.disconnect()
        if (code !in 200..299 || bodyText.isBlank()) return@withContext emptyList()
        val json = runCatching { JSONObject(bodyText) }.getOrNull() ?: return@withContext emptyList()
        val docs = json.optJSONArray("documents") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until docs.length()) {
                val fullName = docs.optJSONObject(i)?.optString("name").orEmpty()
                val id = fullName.substringAfterLast('/')
                if (id.isNotBlank()) add(id)
            }
        }
    }

    suspend fun commitBatchDeletes(
        auth: FirebaseAuth,
        docPaths: List<String>
    ): Int = withContext(Dispatchers.IO) {
        if (docPaths.isEmpty()) return@withContext 200
        val writes = JSONArray()
        docPaths.forEach { path ->
            writes.put(JSONObject().put("delete", docName(path)))
        }
        val body = JSONObject().put("writes", writes)
        val c = open("$BASE:commit", idToken(auth), "POST")
        post(c, body)
    }
}
