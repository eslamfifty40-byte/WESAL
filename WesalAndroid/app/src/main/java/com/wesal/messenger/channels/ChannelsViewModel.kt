package com.wesal.messenger.channels

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wesal.messenger.domain.model.ChannelComment
import com.wesal.messenger.domain.model.ChannelJoinRequest
import com.wesal.messenger.domain.model.ChannelPost
import com.wesal.messenger.domain.model.ChannelPostType
import com.wesal.messenger.domain.model.WesalChannel
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.ChannelRepository
import com.wesal.messenger.domain.repository.UserRepository
import com.wesal.messenger.voice.VoicePlaybackState
import com.wesal.messenger.voice.VoicePlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import javax.inject.Inject

@HiltViewModel
class ChannelsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channelRepository: ChannelRepository,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val voicePlayer: VoicePlayerManager
) : ViewModel() {

    val myUid: String get() = authRepository.currentUserId.orEmpty()

    val isSuperAdmin: StateFlow<Boolean> = userRepository.observeIsSuperAdmin()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val publicChannels: StateFlow<List<WesalChannel>> = channelRepository.observePublicChannels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val myChannels: StateFlow<List<WesalChannel>> = channelRepository.observeMyChannels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeChannelId = MutableStateFlow<String?>(null)

    val activeChannel: StateFlow<WesalChannel?> = _activeChannelId
        .filterNotNull()
        .flatMapLatest { channelRepository.observeChannel(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activePosts: StateFlow<List<ChannelPost>> = _activeChannelId
        .filterNotNull()
        .flatMapLatest { channelRepository.observePosts(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeJoinRequests: StateFlow<List<ChannelJoinRequest>> = _activeChannelId
        .filterNotNull()
        .flatMapLatest { channelRepository.observeJoinRequests(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeCommentPostId = MutableStateFlow<String?>(null)
    val activeComments: StateFlow<List<ChannelComment>> = combine(
        _activeChannelId.filterNotNull(),
        _activeCommentPostId.filterNotNull()
    ) { chId, postId -> chId to postId }
        .flatMapLatest { (chId, postId) -> channelRepository.observeComments(chId, postId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playbackState: StateFlow<VoicePlaybackState> = voicePlayer.playbackState

    private val _uploadProgress = MutableStateFlow<Float?>(null)
    val uploadProgress: StateFlow<Float?> = _uploadProgress.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _moderators = MutableStateFlow<List<WesalUser>>(emptyList())
    val moderators: StateFlow<List<WesalUser>> = _moderators.asStateFlow()

    private val cachedPostAudio = mutableMapOf<String, File>()
    private var messageDismissJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { channelRepository.ensureOfficialTeamChannel() }
        }
    }

    private fun showTempMessage(msg: String) {
        messageDismissJob?.cancel()
        _message.value = msg
        messageDismissJob = viewModelScope.launch {
            delay(3500L)
            if (_message.value == msg) {
                _message.value = null
            }
        }
    }

    fun updateSearch(q: String) {
        _searchQuery.value = q
    }

    fun loadChannel(channelId: String) {
        _activeChannelId.value = channelId
    }

    fun loadModerators(moderatorIds: List<String>) {
        viewModelScope.launch {
            _moderators.value = moderatorIds.mapNotNull { uid -> userRepository.getUserOnce(uid) }
        }
    }

    fun selectPostForComments(postId: String?) {
        _activeCommentPostId.value = postId
    }

    fun createChannel(
        title: String,
        description: String,
        isPublic: Boolean,
        avatarUri: Uri?,
        onCreated: (String) -> Unit
    ) {
        viewModelScope.launch {
            channelRepository.createChannel(title, description, isPublic, avatarUri)
                .onSuccess { ch -> onCreated(ch.channelId) }
                .onFailure { showTempMessage(it.message ?: "تعذر إنشاء القناة") }
        }
    }

    fun updateChannel(
        channelId: String,
        title: String,
        description: String,
        isPublic: Boolean,
        avatarUri: Uri?,
        onDone: () -> Unit
    ) {
        viewModelScope.launch {
            channelRepository.updateChannel(channelId, title, description, isPublic, avatarUri)
                .onSuccess {
                    showTempMessage("تم تحديث القناة")
                    onDone()
                }
                .onFailure { showTempMessage(it.message ?: "تعذر تحديث القناة") }
        }
    }

    fun deleteChannel(channelId: String, onDeleted: () -> Unit) {
        viewModelScope.launch {
            channelRepository.deleteChannel(channelId)
                .onSuccess { onDeleted() }
                .onFailure { showTempMessage(it.message ?: "تعذر حذف القناة") }
        }
    }

    fun subscribe(channelId: String) {
        viewModelScope.launch {
            channelRepository.subscribePublicChannel(channelId)
                .onFailure { showTempMessage(it.message ?: "تعذر الاشتراك") }
        }
    }

    fun unsubscribe(channelId: String) {
        viewModelScope.launch {
            channelRepository.unsubscribeChannel(channelId)
                .onFailure { showTempMessage(it.message ?: "تعذر إلغاء الاشتراك") }
        }
    }

    fun requestJoin(channelId: String) {
        viewModelScope.launch {
            channelRepository.requestJoinPrivateChannel(channelId)
                .onSuccess { showTempMessage("تم إرسال طلب الانضمام إلى مشرفي القناة") }
                .onFailure { showTempMessage(it.message ?: "تعذر إرسال طلب الانضمام") }
        }
    }

    fun joinByInviteCode(code: String, onFound: (String) -> Unit) {
        val cleanInput = code.trim()
            .removePrefix("wesal://channel?code=")
            .substringAfter("code=", code.trim())
            .trim()
        viewModelScope.launch {
            val ch = runCatching { channelRepository.findChannelByInviteCode(cleanInput) }.getOrNull()
            if (ch == null) {
                showTempMessage("رمز الدعوة غير صالح")
            } else {
                if (ch.isPublic) {
                    channelRepository.subscribePublicChannel(ch.channelId)
                }
                onFound(ch.channelId)
            }
        }
    }

    fun publishTextPost(channelId: String, text: String) {
        publishMediaPost(channelId, ChannelPostType.TEXT, text, null, "", "", 0L, 0L)
    }

    fun publishMediaPost(
        channelId: String,
        type: ChannelPostType,
        text: String,
        mediaUri: Uri?,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        durationMs: Long
    ) {
        viewModelScope.launch {
            _uploadProgress.value = 0.05f
            channelRepository.publishPost(
                channelId = channelId,
                type = type,
                text = text,
                mediaUri = mediaUri,
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                durationMs = durationMs
            ).collect { res ->
                res.onSuccess { fraction ->
                    _uploadProgress.value = if (fraction >= 1.0f) null else fraction
                }.onFailure { err ->
                    _uploadProgress.value = null
                    showTempMessage(err.message ?: "تعذر نشر المنشور")
                }
            }
        }
    }

    fun editPost(channelId: String, postId: String, newText: String) {
        viewModelScope.launch {
            channelRepository.editPost(channelId, postId, newText)
                .onSuccess { showTempMessage("تم تعديل المنشور") }
                .onFailure { showTempMessage(it.message ?: "تعذر تعديل المنشور") }
        }
    }

    fun toggleChannelAudioPlayback(postId: String, mediaUrl: String) {
        val playbackKey = "ch_$postId"
        val existing = cachedPostAudio[postId]
        if (existing != null && existing.exists() && existing.length() > 0L) {
            voicePlayer.togglePlay(playbackKey, existing)
            return
        }
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val out = File(context.cacheDir, "channel_audio_$postId.m4a")
                    when {
                        mediaUrl.startsWith("data:", ignoreCase = true) -> {
                            val idx = mediaUrl.indexOf("base64,")
                            if (idx >= 0) {
                                val bytes = Base64.decode(mediaUrl.substring(idx + 7), Base64.DEFAULT)
                                out.writeBytes(bytes)
                            }
                        }
                        mediaUrl.startsWith("wesal-plain://", ignoreCase = true) -> {
                            val b64 = mediaUrl.removePrefix("wesal-plain://")
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            out.writeBytes(bytes)
                        }
                        mediaUrl.startsWith("http", ignoreCase = true) -> {
                            URL(mediaUrl).openStream().use { input ->
                                out.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                    }
                    if (out.exists() && out.length() > 0L) out else null
                }.getOrNull()
            }
            if (file != null) {
                cachedPostAudio[postId] = file
                voicePlayer.togglePlay(playbackKey, file)
            } else {
                showTempMessage("تعذر تشغيل الملف الصوتي")
            }
        }
    }

    fun stopAudioPlayback() {
        voicePlayer.stop()
    }

    fun toggleReaction(channelId: String, postId: String, emoji: String) {
        viewModelScope.launch {
            channelRepository.toggleReaction(channelId, postId, emoji)
        }
    }

    fun togglePin(channelId: String, postId: String, pinned: Boolean) {
        viewModelScope.launch {
            channelRepository.pinPost(channelId, postId, pinned)
        }
    }

    fun deletePost(channelId: String, postId: String) {
        viewModelScope.launch {
            channelRepository.deletePost(channelId, postId)
        }
    }

    fun addComment(channelId: String, postId: String, text: String) {
        viewModelScope.launch {
            channelRepository.addComment(channelId, postId, text)
                .onFailure { showTempMessage(it.message ?: "تعذر إضافة التعليق") }
        }
    }

    fun deleteComment(channelId: String, postId: String, commentId: String) {
        viewModelScope.launch {
            channelRepository.deleteComment(channelId, postId, commentId)
        }
    }

    fun approveRequest(channelId: String, req: ChannelJoinRequest) {
        viewModelScope.launch {
            channelRepository.approveJoinRequest(channelId, req.requestId, req.requesterUid)
        }
    }

    fun rejectRequest(channelId: String, req: ChannelJoinRequest) {
        viewModelScope.launch {
            channelRepository.rejectJoinRequest(channelId, req.requestId)
        }
    }

    fun regenerateInviteCode(channelId: String) {
        viewModelScope.launch {
            channelRepository.regenerateInviteCode(channelId)
                .onSuccess { code -> showTempMessage("تم تحديث رمز الدعوة: $code") }
                .onFailure { showTempMessage(it.message ?: "تعذر تحديث رمز الدعوة") }
        }
    }

    fun addModeratorByIdentifier(channelId: String, rawIdentifier: String) {
        val clean = rawIdentifier.trim().removePrefix("@")
        if (clean.isBlank()) return
        viewModelScope.launch {
            val target = userRepository.findUserByPublicIdOrUid(clean)
            if (target == null) {
                showTempMessage("لم يتم العثور على المستخدم @$clean")
                return@launch
            }
            channelRepository.setModerator(channelId, target.uid, true)
                .onSuccess { showTempMessage("تمت إضافة ${target.displayName.ifBlank { "@${target.publicId}" }} كمشرف") }
                .onFailure { showTempMessage(it.message ?: "تعذر إضافة المشرف") }
        }
    }

    fun toggleModerator(channelId: String, targetUid: String, makeMod: Boolean) {
        viewModelScope.launch {
            channelRepository.setModerator(channelId, targetUid, makeMod)
                .onFailure { showTempMessage(it.message ?: "تعذر تعديل المشرف") }
        }
    }

    fun clearMessage() {
        messageDismissJob?.cancel()
        _message.value = null
    }

    override fun onCleared() {
        super.onCleared()
        voicePlayer.stop()
    }
}
