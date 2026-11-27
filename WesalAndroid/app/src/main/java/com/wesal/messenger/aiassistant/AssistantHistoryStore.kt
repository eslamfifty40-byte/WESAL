package com.wesal.messenger.aiassistant

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wesal.messenger.core.util.AssistantLogic
import com.wesal.messenger.core.util.AssistantMediaKind
import com.wesal.messenger.core.util.AssistantMessage
import com.wesal.messenger.core.util.AssistantRole
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.assistantDataStore by preferencesDataStore(name = "wesal_assistant_prefs")

/**
 * Local-only persistence for the «مساعد وِصال» conversation (round 8).
 * The assistant is a virtual contact: its history NEVER touches Firestore.
 * Messages are stored as one JSON string per account uid in a dedicated
 * preferences DataStore, following the same per-user key shape
 * (`u_<uid>_…`) and kotlinx.serialization encoding the settings store
 * uses. Media messages persist only the app-private file path — the file
 * itself lives under filesDir/assistant_media. Error notices are
 * transient and are filtered out on save; the history is capped at
 * [AssistantLogic.MAX_HISTORY_MESSAGES].
 */
@Singleton
class AssistantHistoryStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    @Serializable
    private data class StoredMessage(
        val id: String,
        val role: String,
        val text: String,
        val mediaKind: String = "NONE",
        val mediaPath: String = "",
        val mediaMimeType: String = "",
        val durationMs: Long = 0L,
        val createdAtMs: Long = 0L
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun historyKey(uid: String) = stringPreferencesKey("u_${uid}_assistant_history_json")

    suspend fun load(uid: String): List<AssistantMessage> {
        if (uid.isBlank()) return emptyList()
        val raw = runCatching {
            context.assistantDataStore.data.first()[historyKey(uid)]
        }.getOrNull() ?: return emptyList()
        val stored = runCatching {
            json.decodeFromString<List<StoredMessage>>(raw)
        }.getOrDefault(emptyList())
        return stored.map { it.toMessage() }
    }

    suspend fun save(uid: String, messages: List<AssistantMessage>) {
        if (uid.isBlank()) return
        val capped = AssistantLogic.capHistory(messages.filterNot { it.isErrorNotice })
        val raw = json.encodeToString(capped.map { it.toStored() })
        runCatching {
            context.assistantDataStore.edit { prefs -> prefs[historyKey(uid)] = raw }
        }
    }

    suspend fun clear(uid: String) {
        if (uid.isBlank()) return
        runCatching {
            context.assistantDataStore.edit { prefs -> prefs.remove(historyKey(uid)) }
        }
    }

    private fun StoredMessage.toMessage(): AssistantMessage = AssistantMessage(
        id = id,
        role = runCatching { AssistantRole.valueOf(role) }.getOrDefault(AssistantRole.ASSISTANT),
        text = text,
        mediaKind = runCatching { AssistantMediaKind.valueOf(mediaKind) }
            .getOrDefault(AssistantMediaKind.NONE),
        mediaPath = mediaPath,
        mediaMimeType = mediaMimeType,
        durationMs = durationMs,
        createdAtMs = createdAtMs
    )

    private fun AssistantMessage.toStored(): StoredMessage = StoredMessage(
        id = id,
        role = role.name,
        text = text,
        mediaKind = mediaKind.name,
        mediaPath = mediaPath,
        mediaMimeType = mediaMimeType,
        durationMs = durationMs,
        createdAtMs = createdAtMs
    )
}
