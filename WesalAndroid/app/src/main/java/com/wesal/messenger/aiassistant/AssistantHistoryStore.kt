package com.wesal.messenger.aiassistant

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wesal.messenger.core.util.AssistantLogic
import com.wesal.messenger.core.util.AssistantMediaKind
import com.wesal.messenger.core.util.AssistantMessage
import com.wesal.messenger.core.util.AssistantRole
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.assistantDataStore by preferencesDataStore(name = "wesal_assistant_prefs")

/**
 * Chats-list summary of the assistant conversation (round 10, item 6): the
 * last message's clean preview text + time, and whether the owner archived
 * the conversation row out of the chats list.
 */
data class AssistantConversationSummary(
    val previewText: String,
    val lastAtMs: Long,
    val archived: Boolean
)

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

    private fun archivedKey(uid: String) = booleanPreferencesKey("u_${uid}_assistant_archived")

    /**
     * Round 10 (item 6): live summary for the chats-list row. Emits null
     * while the conversation is empty (no row is shown before the first
     * exchange). The preview is the last message's first display line with
     * Markdown stripped via [AssistantLogic.parseDisplayLines]; media
     * messages preview as their bracketed placeholders.
     */
    fun observeSummary(uid: String): Flow<AssistantConversationSummary?> {
        if (uid.isBlank()) return flowOf(null)
        return context.assistantDataStore.data.map { prefs ->
            val raw = prefs[historyKey(uid)] ?: return@map null
            val stored = runCatching {
                json.decodeFromString<List<StoredMessage>>(raw)
            }.getOrDefault(emptyList())
            val last = stored.lastOrNull() ?: return@map null
            val preview = when {
                last.text.isNotBlank() -> AssistantLogic.parseDisplayLines(last.text)
                    .firstOrNull { line -> line.spans.any { it.text.isNotBlank() } }
                    ?.spans?.joinToString("") { it.text }
                    ?.trim()
                    .orEmpty()
                last.mediaKind == AssistantMediaKind.IMAGE.name -> AssistantLogic.IMAGE_PLACEHOLDER
                last.mediaKind == AssistantMediaKind.VOICE.name -> AssistantLogic.VOICE_PLACEHOLDER
                else -> ""
            }
            AssistantConversationSummary(
                previewText = preview,
                lastAtMs = last.createdAtMs,
                archived = prefs[archivedKey(uid)] ?: false
            )
        }
    }

    /** Round 10 (item 6): archive/un-archive the chats-list row. */
    suspend fun setArchived(uid: String, archived: Boolean) {
        if (uid.isBlank()) return
        runCatching {
            context.assistantDataStore.edit { prefs -> prefs[archivedKey(uid)] = archived }
        }
    }

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
            context.assistantDataStore.edit { prefs ->
                prefs.remove(historyKey(uid))
                prefs.remove(archivedKey(uid))
            }
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
