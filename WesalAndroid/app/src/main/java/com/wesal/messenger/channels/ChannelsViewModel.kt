package com.wesal.messenger.channels

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wesal.messenger.domain.model.ChannelComment
import com.wesal.messenger.domain.model.ChannelJoinRequest
import com.wesal.messenger.domain.model.ChannelPost
import com.wesal.messenger.domain.model.ChannelPostType
import com.wesal.messenger.domain.model.WesalChannel
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.ChannelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChannelsViewModel @Inject constructor(
    private val channelRepository: ChannelRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    val myUid: String get() = authRepository.currentUserId.orEmpty()

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

    private val _uploadProgress = MutableStateFlow<Float?>(null)
    val uploadProgress: StateFlow<Float?> = _uploadProgress.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun updateSearch(q: String) {
        _searchQuery.value = q
    }

    fun loadChannel(channelId: String) {
        _activeChannelId.value = channelId
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
                .onFailure { _message.value = it.message ?: "تعذر إنشاء القناة" }
        }
    }

    fun subscribe(channelId: String) {
        viewModelScope.launch {
            channelRepository.subscribePublicChannel(channelId)
                .onFailure { _message.value = it.message ?: "تعذر الاشتراك" }
        }
    }

    fun unsubscribe(channelId: String) {
        viewModelScope.launch {
            channelRepository.unsubscribeChannel(channelId)
                .onFailure { _message.value = it.message ?: "تعذر إلغاء الاشتراك" }
        }
    }

    fun requestJoin(channelId: String) {
        viewModelScope.launch {
            channelRepository.requestJoinPrivateChannel(channelId)
                .onSuccess { _message.value = "تم إرسال طلب الانضمام إلى مشرفي القناة" }
                .onFailure { _message.value = it.message ?: "تعذر إرسال طلب الانضمام" }
        }
    }

    fun joinByInviteCode(code: String, onFound: (String) -> Unit) {
        viewModelScope.launch {
            val ch = runCatching { channelRepository.findChannelByInviteCode(code) }.getOrNull()
            if (ch == null) {
                _message.value = "رمز الدعوة غير صالح"
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
                    _message.value = err.message ?: "تعذر نشر المنشور"
                }
            }
        }
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
                .onFailure { _message.value = it.message ?: "تعذر إضافة التعليق" }
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
                .onSuccess { code -> _message.value = "تم تحديث رمز الدعوة: $code" }
                .onFailure { _message.value = it.message ?: "تعذر تحديث رمز الدعوة" }
        }
    }

    fun toggleModerator(channelId: String, targetUid: String, makeMod: Boolean) {
        viewModelScope.launch {
            channelRepository.setModerator(channelId, targetUid, makeMod)
                .onFailure { _message.value = it.message ?: "تعذر تعديل المشرف" }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
