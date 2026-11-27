package com.wesal.messenger.aiassistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.wesal.messenger.core.util.AssistantLogic
import com.wesal.messenger.core.util.AssistantMediaKind
import com.wesal.messenger.core.util.AssistantMessage
import com.wesal.messenger.core.util.AssistantPayloadMedia
import com.wesal.messenger.core.util.AssistantPayloadMessage
import com.wesal.messenger.core.util.AssistantRole
import com.wesal.messenger.data.repository.SupabaseAssistantClient
import com.wesal.messenger.data.repository.SupabaseAssistantException
import com.wesal.messenger.domain.repository.UserRepository
import com.wesal.messenger.voice.VoicePlayerManager
import com.wesal.messenger.voice.VoicePlaybackState
import com.wesal.messenger.voice.VoiceRecorderManager
import com.wesal.messenger.voice.VoiceRecordingState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * «مساعد وِصال» conversation state (round 8). The assistant is a virtual
 * contact: no Firestore reads or writes — the conversation is the per-uid
 * local history ([AssistantHistoryStore]) plus one ask per user message to
 * the Supabase `ai-assistant` function ([SupabaseAssistantClient]), which
 * authenticates the caller by the Firebase ID token in the request body.
 * Voice capture/playback reuse the chat's [VoiceRecorderManager] /
 * [VoicePlayerManager] singletons, so the recording format (.m4a/AAC) and
 * playback behaviour match normal voice notes exactly.
 */
@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val assistantClient: SupabaseAssistantClient,
    private val historyStore: AssistantHistoryStore,
    private val userRepository: UserRepository,
    private val voiceRecorder: VoiceRecorderManager,
    private val voicePlayer: VoicePlayerManager,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _messages = MutableStateFlow<List<AssistantMessage>>(emptyList())
    val messages: StateFlow<List<AssistantMessage>> = _messages.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    /** Signed-in user's display name (drives the empty-state greeting). */
    private val _userDisplayName = MutableStateFlow("")
    val userDisplayName: StateFlow<String> = _userDisplayName.asStateFlow()

    val recordingState: StateFlow<VoiceRecordingState> = voiceRecorder.state
    val playbackState: StateFlow<VoicePlaybackState> = voicePlayer.playbackState

    private var uid: String = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
    private var historyLoadedForUid: String = ""

    init {
        if (uid.isNotBlank()) loadHistory(uid)
        viewModelScope.launch {
            userRepository.observeCurrentUser().collect { user ->
                if (user == null) return@collect
                _userDisplayName.value = user.displayName
                if (user.uid.isNotBlank() && user.uid != uid) {
                    uid = user.uid
                }
                if (uid.isNotBlank() && historyLoadedForUid != uid) {
                    loadHistory(uid)
                }
            }
        }
    }

    private fun loadHistory(forUid: String) {
        historyLoadedForUid = forUid
        viewModelScope.launch {
            val stored = historyStore.load(forUid)
            // Never clobber a conversation the user already started typing
            // into while the (fast, local) load was in flight.
            if (stored.isNotEmpty() && _messages.value.isEmpty()) {
                _messages.value = stored
            }
        }
    }

    // ------------------------------------------------------------- sending

    fun sendText(rawText: String) {
        val text = rawText.trim()
        if (text.isBlank() || _isSending.value) return
        dispatch(
            AssistantMessage(
                id = UUID.randomUUID().toString(),
                role = AssistantRole.USER,
                text = text,
                createdAtMs = System.currentTimeMillis()
            )
        )
    }

    /**
     * Prepares a picked photo (downscale + JPEG, mirroring the decode
     * approach of the chat's ImageEncode helpers) into the app-private
     * assistant media folder, then sends it with [caption] (blank = the
     * backend is asked to explain the photo, via AssistantLogic).
     */
    fun sendImage(uri: Uri, caption: String) {
        if (_isSending.value) return
        viewModelScope.launch {
            val file = runCatching { prepareImageFile(uri) }.getOrNull()
            if (file == null) {
                appendErrorNotice(AssistantLogic.ERROR_GENERIC)
                return@launch
            }
            dispatch(
                AssistantMessage(
                    id = UUID.randomUUID().toString(),
                    role = AssistantRole.USER,
                    text = caption.trim(),
                    mediaKind = AssistantMediaKind.IMAGE,
                    mediaPath = file.absolutePath,
                    mediaMimeType = "image/jpeg",
                    createdAtMs = System.currentTimeMillis()
                )
            )
        }
    }

    fun startVoiceRecording() {
        voiceRecorder.startRecording()
    }

    fun cancelVoiceRecording() {
        voiceRecorder.cancelRecording()
    }

    fun stopAndSendVoiceRecording() {
        if (_isSending.value) {
            voiceRecorder.cancelRecording()
            return
        }
        val rec = voiceRecorder.stopRecording() ?: return
        val recorded = rec.outputFile ?: return
        viewModelScope.launch {
            // Copy into the assistant's own media folder so the history
            // entry never depends on the shared voice-notes directory.
            val copy = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = assistantMediaDir()
                    val target = File(dir, "voice_${UUID.randomUUID()}.m4a")
                    recorded.copyTo(target, overwrite = true)
                    target
                }.getOrNull()
            } ?: recorded
            dispatch(
                AssistantMessage(
                    id = UUID.randomUUID().toString(),
                    role = AssistantRole.USER,
                    text = "",
                    mediaKind = AssistantMediaKind.VOICE,
                    mediaPath = copy.absolutePath,
                    mediaMimeType = "audio/mp4",
                    durationMs = rec.elapsedMs,
                    createdAtMs = System.currentTimeMillis()
                )
            )
        }
    }

    fun toggleVoicePlayback(message: AssistantMessage) {
        val file = File(message.mediaPath)
        if (file.exists()) {
            voicePlayer.togglePlay(message.id, file)
        }
    }

    /** Wipes the local conversation (history + media files) for this uid. */
    fun clearConversation() {
        voicePlayer.stop()
        val mediaFiles = _messages.value.map { it.mediaPath }.filter { it.isNotBlank() }
        _messages.value = emptyList()
        viewModelScope.launch {
            historyStore.clear(uid)
            withContext(Dispatchers.IO) {
                mediaFiles.forEach { path -> runCatching { File(path).delete() } }
            }
        }
    }

    // ------------------------------------------------------------ pipeline

    private fun dispatch(userMessage: AssistantMessage) {
        _messages.update { AssistantLogic.capHistory(it + userMessage) }
        persist()
        _isSending.value = true
        viewModelScope.launch {
            try {
                val attachment = buildAttachmentFor(userMessage)
                val payload = AssistantLogic.buildPayload(_messages.value, attachment)
                val reply = askWithTokenRetry(payload)
                _messages.update {
                    AssistantLogic.capHistory(
                        it + AssistantMessage(
                            id = UUID.randomUUID().toString(),
                            role = AssistantRole.ASSISTANT,
                            text = reply,
                            createdAtMs = System.currentTimeMillis()
                        )
                    )
                }
            } catch (e: SupabaseAssistantException) {
                appendErrorNotice(
                    if (e.status == 429) {
                        AssistantLogic.ERROR_DAILY_LIMIT
                    } else {
                        AssistantLogic.ERROR_GENERIC
                    }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                appendErrorNotice(AssistantLogic.ERROR_GENERIC)
            } finally {
                _isSending.value = false
                persist()
            }
        }
    }

    /**
     * Asks with the current ID token; on a 401 the token is force-refreshed
     * once and the ask retried once (backend contract).
     */
    private suspend fun askWithTokenRetry(
        payload: List<AssistantPayloadMessage>
    ): String {
        val user = FirebaseAuth.getInstance().currentUser
            ?: throw SupabaseAssistantException(401, "unauthenticated", "")
        val token = user.getIdToken(false).await().token
            ?: throw SupabaseAssistantException(401, "unauthenticated", "")
        return try {
            assistantClient.askAssistant(token, payload)
        } catch (e: SupabaseAssistantException) {
            if (e.status != 401) throw e
            val refreshed = user.getIdToken(true).await().token
                ?: throw e
            assistantClient.askAssistant(refreshed, payload)
        }
    }

    /**
     * Reads the just-sent message's media file into the base64 attachment
     * the contract allows on the newest message. Returns null for text
     * messages, missing files, or payloads over the contract caps
     * (image ≈1.5 MB base64 — enforced at prepare time; audio 4 MB).
     */
    private suspend fun buildAttachmentFor(message: AssistantMessage): AssistantPayloadMedia? {
        if (message.mediaKind == AssistantMediaKind.NONE || message.mediaPath.isBlank()) {
            return null
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = File(message.mediaPath)
                if (!file.exists()) return@runCatching null
                val maxRawBytes = if (message.mediaKind == AssistantMediaKind.IMAGE) {
                    1_125_000L
                } else {
                    4_000_000L
                }
                if (file.length() > maxRawBytes) return@runCatching null
                AssistantPayloadMedia(
                    mimeType = message.mediaMimeType.ifBlank {
                        if (message.mediaKind == AssistantMediaKind.IMAGE) {
                            "image/jpeg"
                        } else {
                            "audio/mp4"
                        }
                    },
                    dataBase64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                )
            }.getOrNull()
        }
    }

    private fun appendErrorNotice(text: String) {
        _messages.update {
            it + AssistantMessage(
                id = UUID.randomUUID().toString(),
                role = AssistantRole.ASSISTANT,
                text = text,
                createdAtMs = System.currentTimeMillis(),
                isErrorNotice = true
            )
        }
    }

    private fun persist() {
        val snapshot = _messages.value
        val forUid = uid
        if (forUid.isBlank()) return
        viewModelScope.launch { historyStore.save(forUid, snapshot) }
    }

    // --------------------------------------------------------- image prep

    private fun assistantMediaDir(): File =
        File(appContext.filesDir, "assistant_media").apply { mkdirs() }

    /**
     * Decodes [uri] (bounds-sampled + EXIF-rotated, the same approach as
     * ImageEncode.decodeArgbBitmap), scales it to at most 1280px on the
     * long side and writes JPEG q80 into the assistant media folder,
     * stepping quality/scale down until the base64 payload fits the
     * ≈1.5 MB contract cap.
     */
    private suspend fun prepareImageFile(uri: Uri): File? = withContext(Dispatchers.IO) {
        val bitmap = decodeScaledBitmap(uri, MAX_IMAGE_SIDE_PX) ?: return@withContext null
        var current = bitmap
        var quality = 80
        var bytes: ByteArray
        while (true) {
            val bos = ByteArrayOutputStream()
            current.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            bytes = bos.toByteArray()
            if (bytes.size <= MAX_IMAGE_RAW_BYTES) break
            if (quality > 55) {
                quality -= 15
            } else {
                val nextSide = (maxOf(current.width, current.height) * 0.75f).toInt()
                if (nextSide < 320) break
                val scale = nextSide.toFloat() / maxOf(current.width, current.height)
                current = Bitmap.createScaledBitmap(
                    current,
                    (current.width * scale).toInt().coerceAtLeast(1),
                    (current.height * scale).toInt().coerceAtLeast(1),
                    true
                )
                quality = 80
            }
        }
        val file = File(assistantMediaDir(), "img_${UUID.randomUUID()}.jpg")
        file.writeBytes(bytes)
        file
    }

    private fun decodeScaledBitmap(uri: Uri, maxSide: Int): Bitmap? {
        val resolver = appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        val rotation = try {
            resolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90f
                    3 -> 180f
                    8 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) {
            0f
        }
        if (rotation != 0f) {
            val matrix = Matrix().apply { postRotate(rotation) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        }
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height).coerceAtLeast(1)
        return if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * scale).toInt().coerceAtLeast(1),
                (bmp.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bmp
        }
    }

    private companion object {
        const val MAX_IMAGE_SIDE_PX = 1280

        /** Raw-byte ceiling whose base64 form stays under the ≈1.5 MB cap. */
        const val MAX_IMAGE_RAW_BYTES = 1_125_000
    }
}
