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
import com.wesal.messenger.core.util.ReadReceiptBatcher
import com.wesal.messenger.core.util.TypingSignalGate
import com.wesal.messenger.core.util.WesalIdUtils
import com.wesal.messenger.data.local.AppUserSettings
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.domain.model.ChatListSyncState
import com.wesal.messenger.domain.model.ChatPreview
import com.wesal.messenger.domain.model.DisappearingTimer
import com.wesal.messenger.domain.model.MessageStatus
import com.wesal.messenger.domain.model.MessageType
import com.wesal.messenger.domain.model.WesalMessage
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.ChatRepository
import com.wesal.messenger.domain.repository.UserRepository
import com.wesal.messenger.presentation.components.WesalMediaKind
import com.wesal.messenger.presentation.components.saveMediaToGallery
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
import kotlinx.coroutines.sync.withLock
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

/**
 * Merges the live message window, fetched older pages and the local SENDING/FAILED
 * echoes into the single list the chat renders.
 *
 * R6 FIX (messages sent twice — one copy stuck in error state, one delivered): echoes
 * used to be deduped ONLY against the live window's ids. A delivered copy that was
 * present in an older page but (transiently) absent from the live window — e.g. while
 * the snapshot listener was re-subscribing after an error, or at the page seam — left
 * its FAILED echo visible next to it: two bubbles for one send. Echoes and older pages
 * are now deduped against the UNION of every server-side id known to this merge.
 * A genuinely failed send (its id exists nowhere server-side) still yields exactly
 * one FAILED echo, so retry/dismiss keep working.
 */
internal fun mergeChatMessages(
    remote: List<WesalMessage>,
    older: List<WesalMessage>,
    localEchoes: List<WesalMessage>
): List<WesalMessage> {
    val remoteIds = remote.mapTo(HashSet()) { it.messageId }
    val olderOnly = older.filterNot { it.messageId in remoteIds }
    val withOlder = if (olderOnly.isEmpty()) remote else (olderOnly + remote)
    val serverIds = withOlder.mapTo(HashSet()) { it.messageId }
    val localOnly = localEchoes.filterNot { it.messageId in serverIds }
    // Review MINOR-7: do not rely on the concatenation seam being ordered —
    // an older page fetched against a skewed client clock can straddle the
    // live window. A stable sort by timestamp guarantees the ascending
    // order the message list renders (ties keep their input order).
    return (if (localOnly.isEmpty()) withOlder else (withOlder + localOnly))
        .sortedBy { it.timestamp }
}

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

    // FIX 3 (round 5): the current user, for the screenshot-protection toggle
    // (privacySettings.screenshotProtectionEnabled). Null until the user doc
    // loads — the screen treats null as ON so there is no unprotected flash.
    val currentUser: StateFlow<WesalUser?> = userRepository.observeCurrentUser()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // FIX 3 (round 5): hidden chats stay FLAG_SECURE-protected even when the
    // user toggle is OFF (see ScreenshotProtectionPolicy).
    val isCurrentChatHidden: StateFlow<Boolean> = prefs.settingsFlow
        .map { settings -> chatId in settings.hiddenChatIds }
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

    private val _failedMessages = MutableStateFlow<List<WesalMessage>>(emptyList())

    private val _decryptedFiles = MutableStateFlow<Map<String, File>>(emptyMap())
    val decryptedFiles: StateFlow<Map<String, File>> = _decryptedFiles.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _peerUser = MutableStateFlow<WesalUser?>(null)
    val peerUser: StateFlow<WesalUser?> = _peerUser.asStateFlow()

    /**
     * ONE shared subscription to the chats list for this screen. Previously
     * chatPreview and allChatsForForward each called observeChats(), running two
     * Firestore snapshot listeners over the same collection for the life of the
     * screen (spec §13 duplication); both now derive from this single flow.
     * Review M-3: this uses the UNPAGED variant — the paged observeChats()
     * (100-chat pages for the main list) silently truncated the forward
     * picker and could lose this chat's own preview lookup past page 1.
     */
    private val sharedChats: StateFlow<List<ChatPreview>> = chatRepository.observeAllChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val chatPreview: StateFlow<ChatPreview?> = sharedChats
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
                            peerNeonEnabled = peer.neonEnabled,
                            peerNameplateColor = peer.nameplateColor,
                            peerPhotoUserFramed = peer.photoUserFramed
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

    // Round 12 (addendum 10b): the RAW request state of this chat doc —
    // chatPreview above is viewer-relative (the requester sees ACCEPTED so
    // his thread stays in the main list), so the sender's «طلب مراسلة —
    // بانتظار قبول الطرف الآخر» label reads this raw flow instead.
    val chatRequestState: StateFlow<com.wesal.messenger.domain.model.ChatRequestState> =
        chatRepository.observeChatRequestState(chatId)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                com.wesal.messenger.domain.model.ChatRequestState()
            )

    // Spec §§1.2/13.4: messages sync state (loading/error/cached) for the banner in
    // the chat screen; the retry key re-subscribes the listener after an error
    // (Firestore drops a listener once it errors).
    private val _messagesRetryKey = MutableStateFlow(0)

    val messageSyncState: StateFlow<ChatListSyncState> = chatRepository.observeMessageSyncState()
        .map { state ->
            // Review MINOR-6: the repository flow is a single shared state for
            // whichever chat's listener reported last. A state stamped for a
            // DIFFERENT chat is stale here (e.g. the previous chat's READY
            // replaying on open) — fall back to the neutral loading state
            // until this chat's own listener reports.
            if (state.chatId.isNotBlank() && state.chatId != chatId) ChatListSyncState() else state
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatListSyncState())

    fun retryMessageSync() {
        _messagesRetryKey.value += 1
    }

    // Spec §13.3: the live listener streams only the newest page; older pages are
    // fetched on demand and merged underneath it.
    private val _olderMessages = MutableStateFlow<List<WesalMessage>>(emptyList())
    private val _hasMoreOlderMessages = MutableStateFlow(true)
    val hasMoreOlderMessages: StateFlow<Boolean> = _hasMoreOlderMessages.asStateFlow()
    private var loadingOlderMessages = false

    fun loadOlderMessages() {
        if (loadingOlderMessages || !_hasMoreOlderMessages.value) return
        val oldest = _olderMessages.value.firstOrNull()?.timestamp
            ?: messages.value.firstOrNull()?.timestamp
            ?: return
        loadingOlderMessages = true
        viewModelScope.launch {
            val page = runCatching {
                chatRepository.fetchOlderMessages(chatId, beforeTimestamp = oldest)
            }.getOrDefault(emptyList())
            loadingOlderMessages = false
            if (page.size < 60) _hasMoreOlderMessages.value = false
            if (page.isNotEmpty()) {
                _olderMessages.update { current ->
                    val known = current.mapTo(HashSet()) { it.messageId }
                    (page.filterNot { it.messageId in known } + current).sortedBy { it.timestamp }
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val liveMessages: Flow<List<WesalMessage>> = _messagesRetryKey
        .flatMapLatest { chatRepository.observeMessages(chatId) }

    val messages: StateFlow<List<WesalMessage>> = combine(
        liveMessages,
        _olderMessages,
        _failedMessages,
        _searchQuery
    ) { remote, older, failed, query ->
        val merged = mergeChatMessages(remote, older, failed)
        val normQuery = WesalIdUtils.normalizeSearchText(query)
        if (normQuery.isBlank()) merged
        else merged.filter { WesalIdUtils.normalizeSearchText(it.text).contains(normQuery) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recordingState: StateFlow<VoiceRecordingState> = voiceRecorder.state
    val playbackState: StateFlow<VoicePlaybackState> = voicePlayer.playbackState

    val allChatsForForward: StateFlow<List<ChatPreview>> = sharedChats
        .map { list -> list.filterNot { it.isOfficialWesalChat } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var typingDebounceJob: Job? = null
    private var welcomeTriggeredInSession = false

    // Scaling pass: typing writes are transition-gated (TypingSignalGate)
    // and read receipts are batched (ReadReceiptBatcher) — see markRead()
    // and onInputTextChanged().
    private val typingGate = TypingSignalGate()
    private val readReceiptBatcher = ReadReceiptBatcher()
    private var readFlushJob: Job? = null

    init {
        markRead()
        observeAutoWelcome()
        restoreOutboxForThisChat()
    }

    /**
     * Reloads this chat's persisted pending messages (they survive process death now) and
     * retries each once with its original messageId — the send path is idempotent for a
     * caller-supplied id, so a message that actually landed before the app died is not duplicated.
     */
    private fun restoreOutboxForThisChat() {
        viewModelScope.launch {
            val pending = runCatching { prefs.pendingOutboxMessages() }.getOrDefault(emptyList())
                .filter { it.chatId == chatId }
            if (pending.isEmpty()) return@launch
            pending.forEach { entry ->
                _failedMessages.update { list ->
                    if (list.any { it.messageId == entry.messageId }) list else list + WesalMessage(
                        messageId = entry.messageId,
                        chatId = chatId,
                        senderId = myUid,
                        senderName = "",
                        type = MessageType.TEXT,
                        text = entry.text,
                        replyToMessageId = entry.replyToMessageId,
                        replyToPreview = entry.replyToPreview,
                        forwardedFromName = entry.forwardedFromName,
                        status = MessageStatus.FAILED,
                        timestamp = entry.timestamp
                    )
                }
            }
            pending.forEach { entry ->
                val echo = _failedMessages.value.firstOrNull { it.messageId == entry.messageId }
                if (echo != null) retryFailedMessage(echo)
            }
        }
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

    /**
     * Scaling pass: receipt requests are batched through
     * [ReadReceiptBatcher]. The first request (chat open) flushes at once;
     * requests during an active conversation coalesce into one trailing
     * sweep every [ReadReceiptBatcher.DEFAULT_INTERVAL_MS]; leaving the
     * chat flushes the remainder (see onCleared). Visible behaviour —
     * badge clears on open, blue ticks land promptly — is unchanged; the
     * full sweep no longer re-runs on every message-count change.
     */
    fun markRead() {
        when (val decision = readReceiptBatcher.onReadRequested(System.currentTimeMillis())) {
            ReadReceiptBatcher.Decision.FlushNow -> flushReadReceipts()
            is ReadReceiptBatcher.Decision.Schedule -> {
                readFlushJob?.cancel()
                readFlushJob = viewModelScope.launch {
                    delay(decision.delayMs)
                    flushReadReceipts()
                }
            }
            ReadReceiptBatcher.Decision.AlreadyScheduled -> Unit
        }
    }

    private fun flushReadReceipts() {
        readReceiptBatcher.markFlushed(System.currentTimeMillis())
        viewModelScope.launch { chatRepository.markChatAsRead(chatId) }
    }

    fun onInputTextChanged(text: String) {
        if (chatPreview.value?.isOfficialWesalChat == true) return
        // Scaling pass: the gate turns per-keystroke writes into
        // transition writes — true once when typing starts (plus a slow
        // refresh during long typing), false when the text empties or
        // the 2.5 s idle timer below fires.
        when (typingGate.onTextChanged(text.isBlank(), System.currentTimeMillis())) {
            TypingSignalGate.Action.SEND_TRUE ->
                viewModelScope.launch { chatRepository.setTyping(chatId, true) }
            TypingSignalGate.Action.SEND_FALSE ->
                viewModelScope.launch { chatRepository.setTyping(chatId, false) }
            TypingSignalGate.Action.NONE -> Unit
        }
        typingDebounceJob?.cancel()
        if (text.isNotBlank()) {
            typingDebounceJob = viewModelScope.launch {
                delay(2500L)
                if (typingGate.onStopTimer() == TypingSignalGate.Action.SEND_FALSE) {
                    chatRepository.setTyping(chatId, false)
                }
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
                    .onFailure { _toastMessage.value = "تعذر تعديل الرسالة" }
            }
            return
        }

        // Round 12 (addendum 10c): one composed message can only be
        // submitted once. A re-tap while the identical echo is still
        // SENDING is the same message, not a new one — every unguarded
        // submission minted a fresh id and eventually landed as a
        // separate copy (the reported 3–4× duplicates).
        if (com.wesal.messenger.core.util.SendInFlightGuard.isDuplicateInFlight(
                candidates = _failedMessages.value.map {
                    com.wesal.messenger.core.util.SendInFlightGuard.Candidate(
                        statusRaw = it.status.raw,
                        text = it.text,
                        replyToMessageId = it.replyToMessageId,
                        timestampMs = it.timestamp
                    )
                },
                text = trimmed,
                replyToMessageId = _replyingTo.value?.messageId,
                nowMs = System.currentTimeMillis()
            )
        ) {
            _replyingTo.value = null
            return
        }

        val reply = _replyingTo.value
        _replyingTo.value = null
        // Optimistic local echo with a REAL SENDING status and a stable messageId. The entry is
        // written to the persistent outbox BEFORE the network attempt, so killing the app
        // mid-send never loses the message; the same id makes every retry idempotent.
        val messageId = UUID.randomUUID().toString()
        val echo = WesalMessage(
            messageId = messageId,
            chatId = chatId,
            senderId = myUid,
            senderName = "",
            type = MessageType.TEXT,
            text = trimmed,
            replyToMessageId = reply?.messageId,
            replyToPreview = reply?.text?.take(80),
            status = MessageStatus.SENDING,
            timestamp = System.currentTimeMillis()
        )
        _failedMessages.update { it + echo }
        viewModelScope.launch {
            prefs.enqueueOutboxMessage(
                com.wesal.messenger.domain.model.PendingOutboxMessage(
                    messageId = messageId,
                    chatId = chatId,
                    text = trimmed,
                    replyToMessageId = reply?.messageId,
                    replyToPreview = reply?.text?.take(80),
                    timestamp = echo.timestamp
                )
            )
            if (typingGate.onMessageSent() == TypingSignalGate.Action.SEND_FALSE) {
                chatRepository.setTyping(chatId, false)
            }
            val sendResult = com.wesal.messenger.data.local.OutboxSendGuard.mutex.withLock {
                chatRepository.sendTextMessage(chatId, trimmed, replyTo = reply, clientMessageId = messageId)
            }
            sendResult
                .onSuccess {
                    prefs.removeOutboxMessage(messageId)
                    _failedMessages.update { list -> list.filterNot { it.messageId == messageId } }
                }
                .onFailure { err ->
                    // Round 12 (addendum 12): a QUEUED write (slow
                    // network, SDK mutation queue owns delivery) is not
                    // a failure — the echo stays SENDING and the outbox
                    // entry stays as the backstop. Only a permanent
                    // rejection marks FAILED. The toast is ALWAYS the
                    // mapped Arabic copy (SendFailureCopy) — raw
                    // exception text never reaches the user.
                    if (!com.wesal.messenger.core.util.SendFailureCopy.isQueued(err)) {
                        _failedMessages.update { list ->
                            list.map {
                                if (it.messageId == messageId) {
                                    it.copy(status = MessageStatus.FAILED, timestamp = System.currentTimeMillis())
                                } else it
                            }
                        }
                    }
                    _toastMessage.value =
                        com.wesal.messenger.core.util.SendFailureCopy.toastFor(err)
                }
        }
    }

    fun retryFailedMessage(failed: WesalMessage) {
        if (failed.status != MessageStatus.FAILED && failed.status != MessageStatus.SENDING) return
        // Entries created by older in-memory-only code used a "failed_..." id that is not a real
        // message id; give those a fresh stable id (and outbox entry) before retrying.
        val effectiveId = if (failed.messageId.startsWith("failed_")) {
            UUID.randomUUID().toString()
        } else {
            failed.messageId
        }
        val sending = failed.copy(messageId = effectiveId, status = MessageStatus.SENDING)
        _failedMessages.update { list ->
            list.map { if (it.messageId == failed.messageId) sending else it }
        }
        viewModelScope.launch {
            prefs.enqueueOutboxMessage(
                com.wesal.messenger.domain.model.PendingOutboxMessage(
                    messageId = effectiveId,
                    chatId = chatId,
                    text = failed.text,
                    replyToMessageId = failed.replyToMessageId,
                    replyToPreview = failed.replyToPreview,
                    forwardedFromName = failed.forwardedFromName,
                    timestamp = failed.timestamp,
                    attempts = 1
                )
            )
            val replyStub = if (!failed.replyToMessageId.isNullOrBlank()) {
                WesalMessage(
                    messageId = failed.replyToMessageId,
                    text = failed.replyToPreview.orEmpty()
                )
            } else null
            val retryResult = com.wesal.messenger.data.local.OutboxSendGuard.mutex.withLock {
                chatRepository.sendTextMessage(chatId, failed.text, replyTo = replyStub, clientMessageId = effectiveId)
            }
            retryResult
                .onSuccess {
                    prefs.removeOutboxMessage(effectiveId)
                    _failedMessages.update { list -> list.filterNot { it.messageId == effectiveId } }
                }
                .onFailure { err ->
                    // Round 12 (addendum 12): same queued/permanent
                    // split as the first send — a queued retry returns
                    // the bubble to SENDING, never to a raw-error toast.
                    if (!com.wesal.messenger.core.util.SendFailureCopy.isQueued(err)) {
                        _failedMessages.update { list ->
                            list.map {
                                if (it.messageId == effectiveId) {
                                    it.copy(status = MessageStatus.FAILED, timestamp = System.currentTimeMillis())
                                } else it
                            }
                        }
                    }
                    _toastMessage.value =
                        com.wesal.messenger.core.util.SendFailureCopy.toastFor(err)
                }
        }
    }

    fun dismissFailedMessage(messageId: String) {
        _failedMessages.update { list -> list.filterNot { it.messageId == messageId } }
        viewModelScope.launch { prefs.removeOutboxMessage(messageId) }
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
                isViewOnce = upload.isViewOnce,
                // Spec §1.4: the pending-upload id doubles as the message id, so a
                // retry reuses the same document/storage path and can resume the
                // persisted upload session instead of restarting it.
                clientMessageId = upload.id
            )
                .catch { err ->
                    // Round 12 (addendum 12): mapped Arabic only — the
                    // raw exception text (Storage/Firestore English) is
                    // never rendered.
                    val errText = com.wesal.messenger.core.util.SendFailureCopy.MEDIA_FAILED_AR
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
                        // Round 12 (addendum 12): mapped Arabic only.
                        val errText = com.wesal.messenger.core.util.SendFailureCopy.MEDIA_FAILED_AR
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
                            .onFailure { _toastMessage.value = "تعذر إعادة توجيه الموقع" }
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
                                        _toastMessage.value = "تعذر إعادة توجيه الوسائط"
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
        // AUDIT SEC-A fix: the Result was discarded — a failed delete-for-me was
        // indistinguishable from success (message stayed, no feedback).
        viewModelScope.launch {
            chatRepository.deleteMessageForMe(chatId, messageId)
                .onFailure { _toastMessage.value = "تعذر حذف الرسالة" }
        }
    }

    fun deleteForEveryone(messageId: String) {
        viewModelScope.launch {
            chatRepository.deleteMessageForEveryone(chatId, messageId)
                .onFailure { _toastMessage.value = "تعذر حذف الرسالة" }
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
                    _toastMessage.value = "تعذر حذف المحادثة"
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
                    _toastMessage.value = "تعذر تحديث الخلفية الرسمية"
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
                    _toastMessage.value = "تعذر تحميل أو فتح الملف"
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
                        // Round-4 nits: the fallback extension now follows the real
                        // MIME (voice notes are m4a/audio-mp4, not .mp3), and the
                        // MediaStore insert is IS_PENDING until the copy completes
                        // so music apps never index a half-written file.
                        val mime = message.mediaMimeType.ifBlank {
                            if (message.type == MessageType.VOICE) "audio/mp4" else "audio/mpeg"
                        }
                        val fallbackExt = when (mime) {
                            "audio/mp4" -> "m4a"
                            "audio/mpeg" -> "mp3"
                            "audio/aac" -> "aac"
                            "audio/ogg" -> "ogg"
                            "audio/x-wav", "audio/wav" -> "wav"
                            else -> "m4a"
                        }
                        val safeName = message.mediaFileName
                            .ifBlank { "wesal_audio_${System.currentTimeMillis()}.$fallbackExt" }
                            .replace(Regex("[^a-zA-Z0-9._\\-\\u0600-\\u06FF ]"), "_")

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val values = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/WESAL")
                                put(MediaStore.MediaColumns.IS_PENDING, 1)
                            }
                            val resolver = context.contentResolver
                            val targetUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                                ?: error("تعذر إنشاء الملف في مجلد الموسيقى")
                            try {
                                resolver.openOutputStream(targetUri)?.use { out ->
                                    sourceFile.inputStream().use { inp -> inp.copyTo(out) }
                                } ?: error("تعذر الكتابة للملف")
                                val done = ContentValues().apply {
                                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                                }
                                resolver.update(targetUri, done, null, null)
                            } catch (e: Exception) {
                                runCatching { resolver.delete(targetUri, null, null) }
                                throw e
                            }
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
                    _toastMessage.value = if (isEn) "Unable to save audio" else "تعذر حفظ الملف على الجهاز"
                }
            }
        }
    }

    /**
     * Round-4: documents previously could only be OPENED (the row's download
     * icon was decorative). This saves the decrypted file byte-for-byte into
     * Downloads/WESAL through the shared saver and reports the real outcome.
     */
    fun saveDocumentToDownloads(message: WesalMessage, isEn: Boolean) {
        resolveMediaFile(message) { sourceFile ->
            viewModelScope.launch {
                val safeName = message.mediaFileName
                    .ifBlank { "wesal_file_${System.currentTimeMillis()}" }
                    .replace(Regex("[^a-zA-Z0-9._\\-\\u0600-\\u06FF ]"), "_")
                saveMediaToGallery(
                    context = context,
                    source = sourceFile,
                    kind = WesalMediaKind.DOCUMENT,
                    fileName = safeName
                ).onSuccess {
                    _toastMessage.value = if (isEn) "Saved to Downloads/WESAL" else "تم حفظ الملف في التنزيلات/WESAL"
                }.onFailure { err ->
                    // Round 12 (addendum 12): no exception text on any
                    // chat surface, including file saving.
                    _toastMessage.value = if (isEn) "Unable to save file" else "تعذر حفظ الملف على الجهاز"
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
                .onFailure { err ->
                    // Round 12 (addendum 12): queued writes get the
                    // queued copy; permanent failures the plain Arabic
                    // one — never raw exception text.
                    _toastMessage.value =
                        if (com.wesal.messenger.core.util.SendFailureCopy.isQueued(err)) {
                            com.wesal.messenger.core.util.SendFailureCopy.QUEUED_AR
                        } else {
                            "تعذر إرسال الموقع"
                        }
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
                .onFailure { _toastMessage.value = "تعذر تحديث المؤقت" }
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
                .onFailure { _toastMessage.value = "تعذر تحديث الطلب" }
        }
    }

    suspend fun getPeerUser(): WesalUser? {
        val peerUid = chatPreview.value?.peerUid ?: return null
        if (peerUid.isBlank()) return null
        return userRepository.getUserOnce(peerUid)
    }

    fun blockPeer(peerUid: String) {
        viewModelScope.launch {
            userRepository.blockUser(peerUid)
                .onSuccess {
                    _toastMessage.value = "تم حظر المستخدم"
                }
                // AUDIT SEC-A fix: failures were swallowed — the menu closed and
                // the user believed the block had applied when it had not.
                .onFailure { _toastMessage.value = "تعذر حظر المستخدم" }
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
                _toastMessage.value = "تعذر إرسال البلاغ"
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
        // Scaling pass: leaving the chat flushes any batched read receipts
        // and clears a still-live typing signal. viewModelScope is being
        // torn down, so both go out on a detached IO scope, best-effort —
        // the same end state the un-batched code produced, minus its
        // per-message sweeps.
        val flushReceipts = readReceiptBatcher.hasPending()
        val clearTyping = typingGate.onStopTimer() == TypingSignalGate.Action.SEND_FALSE
        if (flushReceipts || clearTyping) {
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
            ).launch {
                if (clearTyping) runCatching { chatRepository.setTyping(chatId, false) }
                if (flushReceipts) runCatching { chatRepository.markChatAsRead(chatId) }
            }
        }
    }
}
