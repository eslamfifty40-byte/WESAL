package com.wesal.messenger.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.domain.model.ChatPreview
import com.wesal.messenger.domain.model.MessageType
import com.wesal.messenger.domain.model.WesalMessage
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.ChatRepository
import com.wesal.messenger.domain.repository.UserRepository
import com.wesal.messenger.security.E2EECryptoManager
import com.wesal.messenger.voice.VoicePlaybackState
import com.wesal.messenger.voice.VoicePlayerManager
import com.wesal.messenger.voice.VoiceRecorderManager
import com.wesal.messenger.voice.VoiceRecordingState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
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
    val progress: Float = 0f,
    val error: String? = null
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
    private val cryptoManager: E2EECryptoManager,
    private val prefs: UserPreferencesDataStore
) : ViewModel() {

    val chatId: String = checkNotNull(savedStateHandle["chatId"])
    val myUid: String = authRepository.currentUserId.orEmpty()

    val recentEmojis: StateFlow<List<String>> = prefs.recentEmojisFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    @OptIn(ExperimentalCoroutinesApi::class)
    val chatPreview: StateFlow<ChatPreview?> = chatRepository.observeChats()
        .map { list -> list.firstOrNull { it.chatId == chatId } }
        .flatMapLatest { preview ->
            if (preview != null && !preview.isGroup && preview.peerUid.isNotBlank()) {
                userRepository.observeUser(preview.peerUid).map { peer ->
                    if (peer != null) {
                        preview.copy(
                            title = peer.displayName.ifBlank {
                                peer.publicId.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty()
                            }.ifBlank { preview.title },
                            avatarUrl = peer.photoUrl,
                            peerPublicKey = peer.publicKey.ifBlank { preview.peerPublicKey },
                            peerOnline = peer.isOnline,
                            peerLastSeen = peer.lastSeen,
                            peerVerified = peer.isVerified
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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var typingDebounceJob: Job? = null

    init {
        viewModelScope.launch { chatRepository.markChatAsRead(chatId) }
    }

    fun recordRecentEmoji(emoji: String) {
        viewModelScope.launch { prefs.recordRecentEmoji(emoji) }
    }

    fun markRead() {
        viewModelScope.launch { chatRepository.markChatAsRead(chatId) }
    }

    fun onInputTextChanged(text: String) {
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

    fun forwardMessage(targetChatId: String, message: WesalMessage) {
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
                MessageType.IMAGE, MessageType.VIDEO, MessageType.VOICE, MessageType.DOCUMENT -> {
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

    fun sendPickedMedia(uri: Uri, isVideo: Boolean, caption: String = "") {
        val meta = queryUriMetadata(uri)
        val type = if (isVideo) MessageType.VIDEO else MessageType.IMAGE
        enqueueUpload(
            PendingMediaUpload(
                id = java.util.UUID.randomUUID().toString(),
                uri = uri,
                type = type,
                fileName = meta.first.ifBlank { if (isVideo) "video.mp4" else "photo.jpg" },
                mimeType = meta.second.ifBlank { if (isVideo) "video/mp4" else "image/jpeg" },
                sizeBytes = meta.third,
                durationMs = 0L,
                waveform = emptyList(),
                caption = caption
            )
        )
    }

    fun sendDocument(uri: Uri) {
        val meta = queryUriMetadata(uri)
        enqueueUpload(
            PendingMediaUpload(
                id = java.util.UUID.randomUUID().toString(),
                uri = uri,
                type = MessageType.DOCUMENT,
                fileName = meta.first.ifBlank { "document.pdf" },
                mimeType = meta.second.ifBlank { "application/octet-stream" },
                sizeBytes = meta.third,
                durationMs = 0L,
                waveform = emptyList(),
                caption = ""
            )
        )
    }

    fun startVoiceRecording() {
        voiceRecorder.startRecording()
    }

    fun cancelVoiceRecording() {
        voiceRecorder.cancelRecording()
    }

    fun stopAndSendVoiceRecording() {
        val rec = voiceRecorder.stopRecording() ?: return
        val file = rec.outputFile ?: return
        enqueueUpload(
            PendingMediaUpload(
                id = java.util.UUID.randomUUID().toString(),
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
                caption = upload.caption
            ).collect { res ->
                res.onSuccess { progress ->
                    if (progress >= 1.0f) {
                        _pendingUploads.update { list -> list.filterNot { it.id == upload.id } }
                    } else {
                        _pendingUploads.update { list ->
                            list.map { if (it.id == upload.id) it.copy(progress = progress) else it }
                        }
                    }
                }.onFailure { err ->
                    _pendingUploads.update { list ->
                        list.map {
                            if (it.id == upload.id) it.copy(error = err.localizedMessage ?: "فشل الرفع") else it
                        }
                    }
                }
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
                    _toastMessage.value = "تعذر تحميل أو فك تشفير الملف"
                }
        }
    }

    fun toggleVoicePlayback(message: WesalMessage) {
        resolveMediaFile(message) { file ->
            voicePlayer.togglePlay(message.messageId, file)
        }
    }

    fun sendLocation(lat: Double, lng: Double, address: String) {
        viewModelScope.launch {
            chatRepository.sendLocationMessage(chatId, lat, lng, address)
        }
    }

    fun getSafetyFingerprint(): String? {
        val peerKey = chatPreview.value?.peerPublicKey.orEmpty()
        if (peerKey.isBlank()) return null
        return cryptoManager.computeSafetyFingerprint(peerKey)
    }

    fun isEncryptionVerified(): Boolean {
        val peerKey = chatPreview.value?.peerPublicKey.orEmpty()
        if (peerKey.isBlank()) return false
        return cryptoManager.verifyChannelEncryption(peerKey, chatId)
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

    fun reportPeer(peerUid: String, reason: String) {
        viewModelScope.launch {
            userRepository.reportUser(peerUid, reason).onSuccess {
                _toastMessage.value = "تم إرسال البلاغ بنجاح"
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
