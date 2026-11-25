package com.wesal.messenger.chat

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wesal.messenger.data.local.AppUserSettings
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.domain.model.ChatPreview
import com.wesal.messenger.domain.model.DisappearingTimer
import com.wesal.messenger.domain.model.MessageType
import com.wesal.messenger.domain.model.WesalMessage
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.ChatRepository
import com.wesal.messenger.domain.repository.UserRepository
import com.wesal.messenger.voice.VoicePlaybackState
import com.wesal.messenger.voice.VoicePlayerManager
import com.wesal.messenger.voice.VoiceRecorderManager
import com.wesal.messenger.voice.VoiceRecordingState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

data class PendingMediaUpload(
    val id: String,
    val uri: Uri,
    val type: MessageType,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val waveform: List<Int>,
    val caption: String,
    val isViewOnce: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null
)

data class MediaDraftPreview(
    val uri: Uri,
    val type: MessageType,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val waveform: List<Int> = emptyList(),
    val caption: String = "",
    val isViewOnce: Boolean = false
)

@HiltViewModel
class ChatDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val chatRepository: ChatRepository,
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository,
    private val voiceRecorder: VoiceRecorderManager,
    private val voicePlayer: VoicePlayerManager,
    private val prefs: UserPreferencesDataStore
) : ViewModel() {

    val chatId: String = checkNotNull(savedStateHandle["chatId"])
    val myUid: String get() = authRepository.currentUserId.orEmpty()

    val isSuperAdmin: StateFlow<Boolean> = userRepository.observeIsSuperAdmin()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val personalWallpaper: StateFlow<String> = prefs.settingsFlow
        .map { settings ->
            val uid = myUid
            if (uid.isBlank()) "" else settings.personalWallpapers["${uid}_$chatId"].orEmpty()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val recentEmojis: StateFlow<List<String>> = prefs.recentEmojisFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val quickReplies: StateFlow<List<String>> = prefs.settingsFlow
        .map { it.quickReplies }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppUserSettings.DEFAULT_QUICK_REPLIES)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _replyingTo = MutableStateFlow<WesalMessage?>(null)
    val replyingTo: StateFlow<WesalMessage?> = _replyingTo.asStateFlow()

    private val _editingMessage = MutableStateFlow<WesalMessage?>(null)
    val editingMessage: StateFlow<WesalMessage?> = _editingMessage.asStateFlow()

    private val _pendingUploads = MutableStateFlow<List<PendingMediaUpload>>(emptyList())
    val pendingUploads: StateFlow<List<PendingMediaUpload>> = _pendingUploads.asStateFlow()

    private val _decryptedFiles = MutableStateFlow<Map<String, File>>(emptyMap())
    val decryptedFiles: StateFlow<Map<String, File>> = _decryptedFiles.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _peerUser = MutableStateFlow<WesalUser?>(null)
    val peerUser: StateFlow<WesalUser?> = _peerUser.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val chatPreview: StateFlow<ChatPreview?> = chatRepository.observeChats()
        .map { list -> list.firstOrNull { it.chatId == chatId } }
        .flatMapLatest { preview ->
            if (preview != null && !preview.isGroup && !preview.isOfficialWesalChat && preview.peerUid.isNotBlank()) {
                userRepository.observeUser(preview.peerUid).map { peer ->
                    _peerUser.value = peer
                    if (peer != null) {
                        preview.copy(
                            title = peer.displayName.ifBlank {
                                peer.publicId.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty()
                            }.ifBlank { preview.title },
                            avatarUrl = peer.photoUrl,
                            peerPublicKey = peer.publicKey.ifBlank { preview.peerPublicKey },
                            peerOnline = peer.isOnline,
                            peerLastSeen = peer.lastSeen,
                            peerVerified = peer.isVerified,
                            peerVerificationType = peer.verificationType,
                            peerVerificationGlowColor = peer.verificationGlowColor,
                            peerVerificationGlowEnabled = peer.verificationGlowEnabled,
                            peerVerificationGlowIntensity = peer.verificationGlowIntensity,
                            peerFrameId = peer.frameId,
                            peerFrameExpiresAt = peer.frameExpiresAt,
                            peerIconUrl = com.wesal.messenger.core.util.WesalFrameLibrary.resolveIconUrl(peer.iconId, peer.iconUrl),
                            peerPhotoRemovedBg = peer.photoRemovedBg,
                            peerNeonColor = peer.neonColor,
                            peerNeonGlowIntensity = peer.neonGlowIntensity,
                            peerNeonEnabled = peer.neonEnabled
                        )
                    } else {
                        preview
                    }
                }
            } else {
                flowOf(preview)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val messages: StateFlow<List<WesalMessage>> = combine(
        chatRepository.observeMessages(chatId),
        _searchQuery
    ) { all, query ->
        if (query.isBlank()) all else all.filter { it.text.contains(query, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recordingState: StateFlow<VoiceRecordingState> = voiceRecorder.state
    val playbackState: StateFlow<VoicePlaybackState> = voicePlayer.playbackState

    val allChatsForForward: StateFlow<List<ChatPreview>> = chatRepository.observeChats()
        .map { list -> list.filterNot { it.isOfficialWesalChat } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var typingDebounceJob: Job? = null
    private var welcomeTriggeredInSession = false

    init {
        viewModelScope.launch { chatRepository.markChatAsRead(chatId) }
        observeAutoWelcome()
    }

    private fun observeAutoWelcome() {
        viewModelScope.launch {
            combine(
                chatPreview,
                messages,
                prefs.settingsFlow
            ) { preview, msgs, settings ->
                Triple(preview, msgs, settings)
            }.collect { (preview, msgs, settings) ->
                if (welcomeTriggeredInSession) return@collect
                val uid = myUid
                if (uid.isBlank() || preview == null || preview.isGroup || preview.isOfficialWesalChat) return@collect
                if (!settings.welcomeMessageEnabled || settings.welcomeMessageText.isBlank()) return@collect
                val key = "${uid}_$chatId"
                if (key in settings.welcomedChatKeys) {
                    welcomeTriggeredInSession = true
                    return@collect
                }
                val hasIncomingMessage = msgs.any { it.senderId.isNotBlank() && it.senderId != uid }
                val hasMyReply = msgs.any { it.senderId == uid }
                if (hasIncomingMessage && !hasMyReply) {
                    welcomeTriggeredInSession = true
                    prefs.markChatWelcomed(uid, chatId)
                    chatRepository.sendTextMessage(chatId, settings.welcomeMessageText)
                }
            }
        }
    }

    fun updatePresenceActivity(active: Boolean) {
        val preview = chatPreview.value
        val type = when {
            !active -> "NONE"
            preview?.isGroup == true -> "GROUP"
            else -> "CHAT"
        }
        val count = preview?.participantIds?.size ?: 0
        viewModelScope.launch {
            userRepository.updatePresenceHeartbeat(
                isOnline = true,
                activityType = type,
                activityChatId = if (active) chatId else "",
                participantsCount = if (active) count else 0
            )
        }
    }

    fun recordRecentEmoji(emoji: String) {
        viewModelScope.launch { prefs.recordRecentEmoji(emoji) }
    }

    fun markRead() {
        viewModelScope.launch { chatRepository.markChatAsRead(chatId) }
    }

    fun onInputTextChanged(text: String) {
        if (chatPreview.value?.isOfficialWesalChat == true) return
        typingDebounceJob?.cancel()
        typingDebounceJob = viewModelScope.launch {
            chatRepository.setTyping(chatId, text.isNotBlank())
            if (text.isNotBlank()) {
                delay(2500L)
                chatRepository.setTyping(chatId, false)
            }
        }
    }

    fun setSearchQuery(q: String) {
        _searchQuery.value = q
    }

    fun setReplyingTo(msg: WesalMessage?) {
        _editingMessage.value = null
        _replyingTo.value = msg
    }

    fun setEditingMessage(msg: WesalMessage?) {
        _replyingTo.value = null
        _editingMessage.value = msg
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    fun sendOrEditText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val editing = _editingMessage.value
        if (editing != null) {
            viewModelScope.launch {
                chatRepository.editMessage(chatId, editing.messageId, trimmed)
                    .onSuccess { _editingMessage.value = null }
                    .onFailure { _toastMessage.value = it.message ?: "تعذر تعديل الرسالة" }
            }
            return
        }

        val reply = _replyingTo.value
        _replyingTo.value = null
        viewModelScope.launch {
            chatRepository.setTyping(chatId, false)
            chatRepository.sendTextMessage(chatId, trimmed, replyTo = reply)
                .onFailure { _toastMessage.value = it.localizedMessage ?: "فشل إرسال الرسالة" }
        }
    }

    fun buildMediaDraft(uri: Uri, type: MessageType, caption: String = "", isViewOnce: Boolean = false): MediaDraftPreview {
        val meta = queryUriMetadata(uri)
        val durationMs = if (type == MessageType.AUDIO || type == MessageType.VOICE || type == MessageType.VIDEO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                retriever.release()
                durStr?.toLongOrNull() ?: 0L
            }.getOrDefault(0L)
        } else {
            0L
        }
        val fallbackName = when (type) {
            MessageType.IMAGE -> "photo_${System.currentTimeMillis()}.jpg"
            MessageType.VIDEO -> "video_${System.currentTimeMillis()}.mp4"
            MessageType.AUDIO -> "audio_${System.currentTimeMillis()}.mp3"
            MessageType.VOICE -> "voice_${System.currentTimeMillis()}.m4a"
            MessageType.DOCUMENT -> "document_${System.currentTimeMillis()}.pdf"
            else -> "file_${System.currentTimeMillis()}"
        }
        val fallbackMime = when (type) {
            MessageType.IMAGE -> "image/jpeg"
            MessageType.VIDEO -> "video/mp4"
            MessageType.AUDIO -> "audio/mpeg"
            MessageType.VOICE -> "audio/mp4"
            else -> "application/octet-stream"
        }
        return MediaDraftPreview(
            uri = uri,
            type = type,
            fileName = meta.first.ifBlank { fallbackName },
            mimeType = meta.second.ifBlank { fallbackMime },
            sizeBytes = meta.third,
            durationMs = durationMs,
            caption = caption,
            isViewOnce = isViewOnce
        )
    }

    fun sendMediaDraft(draft: MediaDraftPreview) {
        enqueueUpload(
            PendingMediaUpload(
                id = UUID.randomUUID().toString(),
                uri = draft.uri,
                type = draft.type,
                fileName = draft.fileName,
                mimeType = draft.mimeType,
                sizeBytes = draft.sizeBytes,
                durationMs = draft.durationMs,
                waveform = draft.waveform,
                caption = draft.caption,
                isViewOnce = draft.isViewOnce
            )
        )
    }

    fun sendPickedMedia(uri: Uri, isVideo: Boolean, caption: String = "", isViewOnce: Boolean = false) {
        val type = if (isVideo) MessageType.VIDEO else MessageType.IMAGE
        sendMediaDraft(buildMediaDraft(uri, type, caption, isViewOnce))
    }

    fun startVoiceRecording() {
        voiceRecorder.startRecording()
    }

    fun cancelVoiceRecording() {
        voiceRecorder.cancelRecording()
    }

    fun stopVoiceForPreview(): MediaDraftPreview? {
        val rec = voiceRecorder.stopRecording() ?: return null
        val file = rec.outputFile ?: return null
        return MediaDraftPreview(
            uri = Uri.fromFile(file),
            type = MessageType.VOICE,
            fileName = file.name,
            mimeType = "audio/mp4",
            sizeBytes = file.length(),
            durationMs = rec.elapsedMs,
            waveform = rec.amplitudes,
            caption = "",
            isViewOnce = false
        )
    }

    fun toggleDraftVoicePlayback(draft: MediaDraftPreview) {
        val path = draft.uri.path ?: return
        val file = File(path)
        if (file.exists()) {
            voicePlayer.togglePlay("draft_voice_preview", file)
        }
    }

    fun stopVoicePreviewPlayback() {
        if (playbackState.value.activeMessageId == "draft_voice_preview") {
            voicePlayer.stop()
        }
    }

    fun stopAndSendVoiceRecording() {
        val rec = voiceRecorder.stopRecording() ?: return
        val file = rec.outputFile ?: return
        enqueueUpload(
            PendingMediaUpload(
                id = UUID.randomUUID().toString(),
                uri = Uri.fromFile(file),
                type = MessageType.VOICE,
                fileName = file.name,
                mimeType = "audio/mp4",
                sizeBytes = file.length(),
                durationMs = rec.elapsedMs,
                waveform = rec.amplitudes,
                caption = ""
            )
        )
    }

    fun retryUpload(upload: PendingMediaUpload) {
        _pendingUploads.update { list -> list.filterNot { it.id == upload.id } }
        enqueueUpload(upload.copy(progress = 0f, error = null))
    }

    fun dismissUpload(uploadId: String) {
        _pendingUploads.update { list -> list.filterNot { it.id == uploadId } }
    }

    private fun enqueueUpload(upload: PendingMediaUpload) {
        _pendingUploads.update { it + upload }
        viewModelScope.launch {
            chatRepository.sendMediaMessage(
                chatId = chatId,
                uri = upload.uri,
                type = upload.type,
                fileName = upload.fileName,
                mimeType = upload.mimeType,
                sizeBytes = upload.sizeBytes,
                durationMs = upload.durationMs,
                waveform = upload.waveform,
                caption = upload.caption,
                isViewOnce = upload.isViewOnce
            )
                .catch { err ->
                    val errText = err.localizedMessage ?: "فشل الإرسال"
                    _toastMessage.value = errText
                    _pendingUploads.update { list -> list.filterNot { it.id == upload.id } }
                }
                .onCompletion {
                    _pendingUploads.update { list -> list.filterNot { it.id == upload.id && it.error == null } }
                }
                .collect { res ->
                    res.onSuccess { progress ->
                        if (progress >= 1.0f) {
                            _pendingUploads.update { list -> list.filterNot { it.id == upload.id } }
                        } else {
                            _pendingUploads.update { list ->
                                list.map { if (it.id == upload.id) it.copy(progress = progress) else it }
                            }
                        }
                    }.onFailure { err ->
                        val errText = err.localizedMessage ?: "فشل الإرسال"
                        _toastMessage.value = errText
                        _pendingUploads.update { list ->
                            list.map {
                                if (it.id == upload.id) it.copy(error = errText) else it
                            }
                        }
                    }
                }
        }
    }

    fun markViewOnceOpened(messageId: String) {
        viewModelScope.launch {
            chatRepository.markViewOnceOpened(chatId, messageId)
            _decryptedFiles.update { it - messageId }
        }
    }

    fun forwardMessage(targetChatId: String, message: WesalMessage) {
        if (message.isViewOnce) {
            _toastMessage.value = "لا يمكن إعادة توجيه رسائل العرض لمرة واحدة"
            return
        }
        viewModelScope.launch {
            when (message.type) {
                MessageType.LOCATION -> {
                    val lat = message.latitude
                    val lng = message.longitude
                    if (lat != null && lng != null) {
                        chatRepository.sendLocationMessage(targetChatId, lat, lng, message.locationAddress)
                            .onSuccess { _toastMessage.value = "تمت إعادة توجيه الموقع" }
                            .onFailure { _toastMessage.value = it.localizedMessage ?: "تعذر إعادة توجيه الموقع" }
                    }
                }
                MessageType.IMAGE, MessageType.VIDEO, MessageType.VOICE, MessageType.AUDIO, MessageType.DOCUMENT -> {
                    if (message.mediaUrl.isNotBlank()) {
                        chatRepository.decryptMediaToCache(message)
                            .onSuccess { file ->
                                chatRepository.sendMediaMessage(
                                    chatId = targetChatId,
                                    uri = Uri.fromFile(file),
                                    type = message.type,
                                    fileName = message.mediaFileName.ifBlank { file.name },
                                    mimeType = message.mediaMimeType.ifBlank { "application/octet-stream" },
                                    sizeBytes = file.length(),
                                    durationMs = message.mediaDurationMs,
                                    waveform = message.waveformAmplitudes,
                                    caption = message.text
                                ).collect { res ->
                                    res.onSuccess { p ->
                                        if (p >= 1f) _toastMessage.value = "تمت إعادة توجيه الوسائط"
                                    }.onFailure { err ->
                                        _toastMessage.value = err.localizedMessage ?: "تعذر إعادة توجيه الوسائط"
                                    }
                                }
                            }
                            .onFailure {
                                if (message.text.isNotBlank()) {
                                    chatRepository.sendTextMessage(targetChatId, message.text, forwardedFromName = message.senderName)
                                        .onSuccess { _toastMessage.value = "تمت إعادة توجيه النص" }
                                } else {
                                    _toastMessage.value = "تعذر إعادة توجيه الوسائط"
                                }
                            }
                    } else if (message.text.isNotBlank()) {
                        chatRepository.sendTextMessage(targetChatId, message.text, forwardedFromName = message.senderName)
                            .onSuccess { _toastMessage.value = "تمت إعادة توجيه الرسالة" }
                    }
                }
                else -> {
                    if (message.text.isNotBlank()) {
                        chatRepository.sendTextMessage(
                            chatId = targetChatId,
                            text = message.text,
                            forwardedFromName = message.senderName
                        ).onSuccess {
                            _toastMessage.value = "تمت إعادة توجيه الرسالة"
                        }
                    }
                }
            }
        }
    }

    fun deleteForMe(messageId: String) {
        viewModelScope.launch { chatRepository.deleteMessageForMe(chatId, messageId) }
    }

    fun deleteForEveryone(messageId: String) {
        viewModelScope.launch {
            chatRepository.deleteMessageForEveryone(chatId, messageId)
                .onFailure { _toastMessage.value = it.localizedMessage }
        }
    }

    fun deleteChat(forEveryone: Boolean, onSuccess: () -> Unit) {
        viewModelScope.launch {
            chatRepository.deleteChat(chatId, forEveryone)
                .onSuccess {
                    prefs.toggleHiddenChat(chatId, false)
                    onSuccess()
                }
                .onFailure { err ->
                    _toastMessage.value = err.localizedMessage ?: "تعذر حذف المحادثة"
                }
        }
    }

    fun setPersonalWallpaper(uri: Uri?) {
        val uid = myUid
        if (uid.isBlank()) return
        viewModelScope.launch {
            if (uri == null) {
                prefs.setPersonalChatWallpaper("${uid}_$chatId", null)
                _toastMessage.value = "تمت استعادة الخلفية الافتراضية"
                return@launch
            }
            val savedPath = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.filesDir, "wallpapers").apply { mkdirs() }
                    val outFile = File(dir, "wp_${uid}_${chatId}.jpg")
                    context.contentResolver.openInputStream(uri)?.use { inp ->
                        outFile.outputStream().use { out -> inp.copyTo(out) }
                    }
                    outFile.absolutePath
                }.getOrNull()
            }
            if (!savedPath.isNullOrBlank()) {
                prefs.setPersonalChatWallpaper("${uid}_$chatId", savedPath)
                _toastMessage.value = "تم تعيين الخلفية الشخصية بنجاح"
            } else {
                _toastMessage.value = "تعذر حفظ الخلفية"
            }
        }
    }

    fun setOfficialSharedWallpaper(uri: Uri?) {
        viewModelScope.launch {
            chatRepository.setChatOfficialWallpaper(chatId, uri?.toString())
                .onSuccess {
                    _toastMessage.value = if (uri == null) {
                        "تمت إزالة الخلفية الرسمية المشتركة"
                    } else {
                        "تم تعيين الخلفية الرسمية المشتركة للطرفين"
                    }
                }
                .onFailure { err ->
                    _toastMessage.value = err.localizedMessage ?: "تعذر تحديث الخلفية الرسمية"
                }
        }
    }

    fun resolveMediaFile(message: WesalMessage, onReady: (File) -> Unit = {}) {
        val cached = _decryptedFiles.value[message.messageId]
        if (cached != null && cached.exists()) {
            onReady(cached)
            return
        }
        viewModelScope.launch {
            chatRepository.decryptMediaToCache(message)
                .onSuccess { file ->
                    _decryptedFiles.update { it + (message.messageId to file) }
                    onReady(file)
                }
                .onFailure {
                    _toastMessage.value = it.localizedMessage ?: "تعذر تحميل أو فتح الملف"
                }
        }
    }

    fun toggleVoicePlayback(message: WesalMessage) {
        resolveMediaFile(message) { file ->
            voicePlayer.togglePlay(message.messageId, file)
        }
    }

    fun seekAudio(positionMs: Int) {
        voicePlayer.seekTo(positionMs)
    }

    fun setAudioVolume(volume: Float) {
        voicePlayer.setVolume(volume)
    }

    fun saveAudioToDownloads(message: WesalMessage, isEn: Boolean) {
        resolveMediaFile(message) { sourceFile ->
            viewModelScope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val safeName = message.mediaFileName
                            .ifBlank { "wesal_audio_${System.currentTimeMillis()}.mp3" }
                            .replace(Regex("[^a-zA-Z0-9._\\-\\u0600-\\u06FF ]"), "_")
                        val mime = message.mediaMimeType.ifBlank { "audio/mpeg" }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val values = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/WESAL")
                            }
                            val resolver = context.contentResolver
                            val targetUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                                ?: error("تعذر إنشاء الملف في مجلد الموسيقى")
                            resolver.openOutputStream(targetUri)?.use { out ->
                                sourceFile.inputStream().use { inp -> inp.copyTo(out) }
                            } ?: error("تعذر الكتابة للملف")
                        } else {
                            @Suppress("DEPRECATION")
                            val musicDir = File(
                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                                "WESAL"
                            ).apply { mkdirs() }
                            val dest = File(musicDir, safeName)
                            sourceFile.copyTo(dest, overwrite = true)
                        }
                    }
                }
                saved.onSuccess {
                    _toastMessage.value = if (isEn) "Saved to Music/WESAL" else "تم حفظ الملف الصوتي في مجلد الموسيقى/WESAL"
                }.onFailure { err ->
                    _toastMessage.value = err.localizedMessage ?: if (isEn) "Unable to save audio" else "تعذر حفظ الملف على الجهاز"
                }
            }
        }
    }

    fun sendLocation(lat: Double, lng: Double, address: String, liveDurationMinutes: Int = 0) {
        viewModelScope.launch {
            chatRepository.sendLocationMessage(chatId, lat, lng, address, liveDurationMinutes)
                .onSuccess {
                    if (liveDurationMinutes > 0) {
                        _toastMessage.value = "تمت مشاركة الموقع المباشر لمدة $liveDurationMinutes دقيقة"
                    }
                }
                .onFailure {
                    _toastMessage.value = it.localizedMessage ?: "تعذر إرسال الموقع"
                }
        }
    }

    fun setDisappearingTimer(timer: DisappearingTimer) {
        viewModelScope.launch {
            chatRepository.setChatDisappearingTimer(chatId, timer.seconds)
                .onSuccess {
                    _toastMessage.value = if (timer == DisappearingTimer.OFF) {
                        "تم إيقاف مؤقت اختفاء الرسائل"
                    } else {
                        "الرسائل الجديدة ستختفي بعد ${timer.labelAr}"
                    }
                }
                .onFailure { _toastMessage.value = it.localizedMessage ?: "تعذر تحديث المؤقت" }
        }
    }

    fun toggleStarMessage(msg: WesalMessage) {
        val summary = msg.text.ifBlank { "[${msg.type.raw}] ${msg.mediaFileName}" }.take(80)
        viewModelScope.launch {
            prefs.toggleStarredMessage("${chatId}|${msg.messageId}|$summary")
            _toastMessage.value = "تم تحديث الرسائل المميزة بنجمة ✓"
        }
    }

    fun decideMessageRequest(decision: String, onRejectedDone: () -> Unit = {}) {
        val peerUid = chatPreview.value?.peerUid.orEmpty()
        viewModelScope.launch {
            chatRepository.setMessageRequestDecision(chatId, peerUid, decision)
                .onSuccess {
                    when (decision.uppercase()) {
                        "ACCEPTED" -> _toastMessage.value = "تم قبول المحادثة ✓"
                        "HELD" -> _toastMessage.value = "تم تعليق الطلب"
                        else -> onRejectedDone()
                    }
                }
                .onFailure { _toastMessage.value = it.localizedMessage ?: "تعذر تحديث الطلب" }
        }
    }

    suspend fun getPeerUser(): WesalUser? {
        val peerUid = chatPreview.value?.peerUid ?: return null
        if (peerUid.isBlank()) return null
        return userRepository.getUserOnce(peerUid)
    }

    fun blockPeer(peerUid: String) {
        viewModelScope.launch {
            userRepository.blockUser(peerUid).onSuccess {
                _toastMessage.value = "تم حظر المستخدم"
            }
        }
    }

    fun reportChatOrMessage(peerUid: String, reason: String, targetMessage: WesalMessage? = null) {
        val snapshot = if (targetMessage != null) {
            "${targetMessage.senderName}: ${targetMessage.text.ifBlank { "[${targetMessage.type.raw}] ${targetMessage.mediaFileName}" }}"
        } else {
            messages.value.takeLast(8).joinToString("\n") {
                "${it.senderName}: ${it.text.ifBlank { "[${it.type.raw}]" }}"
            }
        }
        viewModelScope.launch {
            userRepository.reportUser(
                targetUid = peerUid,
                reason = reason,
                targetType = if (targetMessage != null) "MESSAGE" else "CHAT",
                targetChatId = chatId,
                targetMessageId = targetMessage?.messageId.orEmpty(),
                targetContentSnapshot = snapshot
            ).onSuccess {
                _toastMessage.value = "تم إرسال البلاغ مع حفظ الأدلة بنجاح"
            }.onFailure { err ->
                _toastMessage.value = err.localizedMessage ?: "تعذر إرسال البلاغ"
            }
        }
    }

    private fun queryUriMetadata(uri: Uri): Triple<String, String, Long> {
        val mime = context.contentResolver.getType(uri).orEmpty()
        var name = ""
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = cursor.getString(nameIdx).orEmpty()
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
        if (size == 0L && uri.scheme == "file") {
            size = uri.path?.let { File(it).length() } ?: 0L
        }
        return Triple(name, mime, size)
    }

    override fun onCleared() {
        super.onCleared()
        voicePlayer.stop()
    }
}
