package com.wesal.messenger.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wesal.messenger.aiassistant.AssistantConversationSummary
import com.wesal.messenger.aiassistant.AssistantHistoryStore
import com.wesal.messenger.core.util.PerfTrace
import com.wesal.messenger.core.util.WesalIdUtils
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.domain.model.ChatListSyncState
import com.wesal.messenger.domain.model.ChatPreview
import com.wesal.messenger.domain.model.ContactRequest
import com.wesal.messenger.domain.model.ContactRequestStatus
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.ChatRepository
import com.wesal.messenger.domain.repository.UserRepository
import com.wesal.messenger.stories.StarredMessageEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class ChatsListViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository,
    private val prefs: UserPreferencesDataStore,
    private val assistantHistoryStore: AssistantHistoryStore
) : ViewModel() {

    val currentUid: String
        get() = authRepository.currentUserId.orEmpty()

    /**
     * Round 10 (item 6): the «مساعد وِصال» conversation as a chats-list row
     * summary — null until the first exchange exists. The store is local
     * (per-uid DataStore); the row is hidden while archived.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val assistantSummary: StateFlow<AssistantConversationSummary?> =
        authRepository.observeAuthState()
            .map { it.orEmpty() }
            .distinctUntilChanged()
            .flatMapLatest { uid -> assistantHistoryStore.observeSummary(uid) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Round 10 (item 6): hide the assistant row until the next exchange/open. */
    fun archiveAssistantConversation() {
        val uid = currentUid
        if (uid.isBlank()) return
        viewModelScope.launch { assistantHistoryStore.setArchived(uid, true) }
    }

    /**
     * Round 10 (item 6): the same wipe the assistant screen's clear performs
     * (history + local media files), triggered from the chats-list row menu.
     */
    fun clearAssistantConversation() {
        val uid = currentUid
        if (uid.isBlank()) return
        viewModelScope.launch {
            val mediaPaths = assistantHistoryStore.load(uid)
                .map { it.mediaPath }
                .filter { it.isNotBlank() }
            assistantHistoryStore.clear(uid)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                mediaPaths.forEach { path -> runCatching { java.io.File(path).delete() } }
            }
        }
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _showArchivedOnly = MutableStateFlow(false)
    val showArchivedOnly: StateFlow<Boolean> = _showArchivedOnly.asStateFlow()

    val showHiddenChats: StateFlow<Boolean> = prefs.hiddenChatsUnlockedSession
    // Spec §8.2: no external "open hidden chats" trigger exists anymore — the ONLY
    // unlock path is updateSearchQuery() verifying the typed password below.

    private var unlockedViaSearchBar: Boolean = false

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    val hiddenChatIds: StateFlow<Set<String>> = prefs.settingsFlow
        .map { it.hiddenChatIds }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val lockedChatIds: StateFlow<Set<String>> = prefs.settingsFlow
        .map { it.lockedChatIds }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    // Spec §9.6 (r17): starred messages on the chats bar — the same per-uid
    // store the Updates tab already reads (StoriesViewModel.starredMessages),
    // parsed identically ("chatId|messageId|summary").
    val starredMessages: StateFlow<List<StarredMessageEntry>> = prefs.settingsFlow
        .map { settings ->
            val rawList = runCatching {
                Json.decodeFromString<List<String>>(settings.starredMessagesJson)
            }.getOrDefault(emptyList())
            rawList.map { raw ->
                val parts = raw.split("|", limit = 3)
                StarredMessageEntry(
                    rawKey = raw,
                    chatId = parts.getOrElse(0) { "" },
                    messageId = parts.getOrElse(1) { "" },
                    summary = parts.getOrElse(2) { raw }
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun removeStarredEntry(rawKey: String) {
        viewModelScope.launch { prefs.toggleStarredMessage(rawKey) }
    }

    // One shared subscription to the chats listener for the whole screen (previously every
    // derived flow opened its own). The retry key lets the error state re-subscribe after
    // Firestore dropped the listener on error (spec §13.3: loading stops, error + retry).
    private val _chatsRetryKey = MutableStateFlow(0)

    private val firstChatsSnapshotMarked = java.util.concurrent.atomic.AtomicBoolean(false)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val allChatsSource: StateFlow<List<ChatPreview>> = _chatsRetryKey
        .flatMapLatest { chatRepository.observeChats() }
        .onEach { list ->
            // Spec §13.2: first-snapshot timing mark (DEBUG logcat only).
            if (list.isNotEmpty() && firstChatsSnapshotMarked.compareAndSet(false, true)) {
                PerfTrace.mark("chats_first_snapshot")
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Spec §13.3: grow the paged chats listener by one page. */
    fun loadMoreChats() {
        chatRepository.requestMoreChats()
    }

    /**
     * Review MINOR-4: whether the repository's paged listener has more chats
     * to load. The load-more footer keys off THIS — the unfiltered page
     * state — not the filtered list size, which changes with the search query.
     */
    val hasMoreChats: StateFlow<Boolean> = chatRepository.observeChatsHasMore()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val chatListState: StateFlow<ChatListSyncState> = chatRepository.observeChatListState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatListSyncState())

    fun retryChats() {
        _chatsRetryKey.value += 1
    }

    val messageRequestChats: StateFlow<List<ChatPreview>> = allChatsSource
        .map { allChats ->
            allChats.filter { chat ->
                !chat.isOfficialWesalChat && !chat.isSupportChat && !chat.isGroup &&
                    (chat.isMessageRequest || chat.requestStatus == "PENDING" || chat.requestStatus == "HELD") &&
                    chat.requestStatus != "REJECTED"
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val incomingContactRequests: StateFlow<List<ContactRequest>> = userRepository.observeContactRequests()
        .map { list ->
            val uid = currentUid
            list.filter { req ->
                req.receiverUid == uid &&
                    (req.status == ContactRequestStatus.PENDING || req.status == ContactRequestStatus.HELD)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val archivedCount: StateFlow<Int> = combine(
        allChatsSource,
        hiddenChatIds
    ) { allChats, hiddenIds ->
        allChats.count { chat ->
            val isRequest = !chat.isOfficialWesalChat && !chat.isSupportChat && !chat.isGroup &&
                (chat.isMessageRequest || chat.requestStatus == "PENDING" || chat.requestStatus == "HELD" || chat.requestStatus == "REJECTED")
            !isRequest && chat.chatId !in hiddenIds && chat.isArchived
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val chats: StateFlow<List<ChatPreview>> = combine(
        allChatsSource,
        _searchQuery,
        hiddenChatIds,
        showHiddenChats,
        _showArchivedOnly
    ) { allChats, query, hiddenIds, showingHidden, archivedOnly ->
        val normQuery = WesalIdUtils.normalizeSearchText(query)
        val queryIsHiddenPass = showingHidden && query.isNotBlank() && hasHiddenPassword() && verifyHiddenPassword(query)
        allChats.filter { chat ->
            val isRequest = !chat.isOfficialWesalChat && !chat.isSupportChat && !chat.isGroup &&
                (chat.isMessageRequest || chat.requestStatus == "PENDING" || chat.requestStatus == "HELD" || chat.requestStatus == "REJECTED")
            if (isRequest) return@filter false
            val isHidden = chat.chatId in hiddenIds
            val visibilityMatch = when {
                showingHidden -> isHidden
                archivedOnly -> !isHidden && chat.isArchived
                else -> !isHidden && !chat.isArchived
            }
            val queryMatch = normQuery.isBlank() ||
                queryIsHiddenPass ||
                WesalIdUtils.normalizeSearchText(chat.title).contains(normQuery) ||
                WesalIdUtils.normalizeSearchText(chat.lastMessagePreview).contains(normQuery)
            visibilityMatch && queryMatch
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hiddenCount: StateFlow<Int> = combine(
        allChatsSource,
        hiddenChatIds
    ) { allChats, hiddenIds ->
        allChats.count { it.chatId in hiddenIds }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun hasHiddenPassword(): Boolean = prefs.hasHiddenChatsPassword(currentUid)

    fun getHiddenRecoveryQuestion(): String = prefs.getHiddenChatsRecoveryQuestion(currentUid)

    fun verifyHiddenPassword(candidate: String): Boolean =
        prefs.verifyHiddenChatsPassword(currentUid, candidate)

    fun verifyTwoFactorOrHiddenPin(candidate: String): Boolean =
        prefs.verifyTwoFactorPin(currentUid, candidate) || prefs.verifyHiddenChatsPassword(currentUid, candidate)

    fun hasAnyPinConfigured(): Boolean =
        prefs.hasHiddenChatsPassword(currentUid)

    fun verifyHiddenRecoveryAnswer(answer: String): Boolean =
        prefs.verifyHiddenChatsRecoveryAnswer(currentUid, answer)

    fun setupOrResetHiddenPassword(
        password: String,
        question: String,
        answer: String,
        pendingHideChatId: String? = null
    ) {
        val clean = prefs.normalizeHiddenPinInput(password)
        prefs.setHiddenChatsPassword(currentUid, clean, question, answer)
        viewModelScope.launch {
            if (!pendingHideChatId.isNullOrBlank()) {
                prefs.toggleHiddenChat(pendingHideChatId, true)
                chatRepository.purgeHiddenChatCache(pendingHideChatId)
                _toastMessage.value = "تم تعيين كلمة المرور وإخفاء المحادثة"
            } else {
                _toastMessage.value = "تم حفظ كلمة مرور المحادثات المخفية"
            }
        }
    }

    fun resetHiddenLockAndUnhideAll() {
        viewModelScope.launch {
            prefs.clearHiddenChatsProtectionAndUnhideAll(currentUid)
            prefs.setHiddenChatsUnlockedSession(false)
            _toastMessage.value = "تمت إعادة تعيين حماية المحادثات المخفية وإظهارها"
        }
    }

    fun setShowArchivedOnly(show: Boolean) {
        _showArchivedOnly.value = show
    }

    fun setShowingHiddenChats(showing: Boolean) {
        if (showing) {
            unlockedViaSearchBar = false
            _showArchivedOnly.value = false
            _searchQuery.value = ""
        } else {
            unlockedViaSearchBar = false
            _searchQuery.value = ""
        }
        prefs.setHiddenChatsUnlockedSession(showing)
    }

    fun hideOrUnhideChat(chat: ChatPreview, hide: Boolean) {
        viewModelScope.launch {
            prefs.toggleHiddenChat(chat.chatId, hide)
            if (hide) {
                // Spec §8.3: a hidden chat keeps no cached rows on disk; unhiding
                // re-syncs from Firestore on the next snapshot/open.
                chatRepository.purgeHiddenChatCache(chat.chatId)
            }
            _toastMessage.value = if (hide) "تم نقل المحادثة إلى المحادثات المخفية" else "تمت إعادة المحادثة إلى القائمة الرئيسية"
        }
    }

    fun toggleLockChat(chat: ChatPreview, lock: Boolean) {
        viewModelScope.launch {
            prefs.toggleLockedChat(chat.chatId, lock)
            _toastMessage.value = if (lock) "تم قفل المحادثة بنجاح" else "تم إلغاء قفل المحادثة"
        }
    }

    fun decideMessageRequest(chatId: String, peerUid: String, decision: String) {
        viewModelScope.launch {
            chatRepository.setMessageRequestDecision(chatId, peerUid, decision)
                .onSuccess {
                    _toastMessage.value = when (decision.uppercase()) {
                        "ACCEPTED" -> "تم قبول طلب المراسلة ونقل المحادثة إلى الرئيسية"
                        "HELD" -> "تم تعليق الطلب مؤقتاً"
                        else -> "تم رفض الطلب وحذفه"
                    }
                }
                .onFailure { _toastMessage.value = "تعذر تحديث الطلب" }
        }
    }

    fun acceptContactRequest(requestId: String) {
        viewModelScope.launch {
            userRepository.acceptContactRequest(requestId)
                .onSuccess { _toastMessage.value = "تم قبول طلب التعارف" }
                .onFailure { _toastMessage.value = "تعذر قبول الطلب" }
        }
    }

    fun holdContactRequest(requestId: String, senderUid: String = "") {
        viewModelScope.launch {
            userRepository.holdContactRequest(requestId)
                .onSuccess {
                    // AUDIT SEC-A (A11): keep the chat layer in sync when a chat doc exists.
                    if (senderUid.isNotBlank()) {
                        chatRepository.setMessageRequestDecision(directChatIdFor(senderUid), senderUid, "HELD")
                    }
                    _toastMessage.value = "تم تعليق الطلب في قائمة الانتظار"
                }
                .onFailure { _toastMessage.value = "تعذر تعليق الطلب" }
        }
    }

    fun rejectContactRequest(requestId: String, senderUid: String = "") {
        viewModelScope.launch {
            userRepository.rejectContactRequest(requestId)
                .onSuccess {
                    // AUDIT SEC-A (A11): keep the chat layer in sync when a chat doc exists.
                    if (senderUid.isNotBlank()) {
                        chatRepository.setMessageRequestDecision(directChatIdFor(senderUid), senderUid, "REJECTED")
                    }
                    _toastMessage.value = "تم رفض الطلب"
                }
                .onFailure { _toastMessage.value = "تعذر رفض الطلب" }
        }
    }

    fun blockAndRejectRequest(chatId: String?, peerUid: String, requestId: String?) {
        viewModelScope.launch {
            if (peerUid.isNotBlank()) userRepository.blockUser(peerUid)
            if (!chatId.isNullOrBlank()) chatRepository.setMessageRequestDecision(chatId, peerUid, "REJECTED")
            if (!requestId.isNullOrBlank()) userRepository.rejectContactRequest(requestId)
            _toastMessage.value = "تم حظر المستخدم ورفض الطلب"
        }
    }

    fun reportRequestSender(peerUid: String, chatId: String?, reason: String) {
        viewModelScope.launch {
            userRepository.reportUser(
                targetUid = peerUid,
                reason = reason.ifBlank { "طلب مراسلة مزعج" },
                targetType = "CHAT",
                targetChatId = chatId.orEmpty()
            ).onSuccess {
                _toastMessage.value = "تم إرسال البلاغ للإدارة"
            }.onFailure {
                _toastMessage.value = "تعذر إرسال البلاغ"
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        if (!showHiddenChats.value && query.isNotBlank() && hasHiddenPassword() && verifyHiddenPassword(query)) {
            unlockedViaSearchBar = true
            _showArchivedOnly.value = false
            prefs.setHiddenChatsUnlockedSession(true)
        } else if (query.isBlank() && unlockedViaSearchBar) {
            unlockedViaSearchBar = false
            prefs.setHiddenChatsUnlockedSession(false)
        }
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    fun togglePin(chat: ChatPreview) {
        // AUDIT SEC-A fix: the Result was discarded, so a failed pin write looked
        // identical to success (the row just never changed). Surface failures.
        viewModelScope.launch {
            chatRepository.togglePinChat(chat.chatId, !chat.isPinned)
                .onFailure { _toastMessage.value = "تعذر تحديث التثبيت" }
        }
    }

    fun toggleMute(chat: ChatPreview) {
        // AUDIT SEC-A fix: same discarded-Result problem as togglePin.
        viewModelScope.launch {
            chatRepository.toggleMuteChat(chat.chatId, !chat.isMuted)
                .onFailure { _toastMessage.value = "تعذر تحديث الكتم" }
        }
    }

    /**
     * AUDIT SEC-A fix (A2): the chat-row long-press menu had no Block action at
     * all (block existed only inside a chat and in the requests dialog). Block
     * writes blockedUsers on my user doc AND deletes the chat for me, so the row
     * actually disappears — blockUser alone leaves the chat fully visible.
     */
    fun blockChatPeer(chat: ChatPreview) {
        val peerUid = chat.peerUid
        if (peerUid.isBlank() || chat.isGroup || chat.isOfficialWesalChat) return
        viewModelScope.launch {
            userRepository.blockUser(peerUid)
                .onSuccess {
                    chatRepository.deleteChat(chat.chatId, forEveryone = false)
                    prefs.toggleHiddenChat(chat.chatId, false)
                    prefs.toggleLockedChat(chat.chatId, false)
                    _toastMessage.value = "تم حظر المستخدم وحذف المحادثة"
                }
                .onFailure { _toastMessage.value = "تعذر حظر المستخدم" }
        }
    }

    /** Deterministic 1:1 chat id, same construction as ChatRepositoryImpl. */
    private fun directChatIdFor(otherUid: String): String {
        val sorted = listOf(currentUid, otherUid).sorted()
        return "dm_${sorted[0]}_${sorted[1]}"
    }

    /**
     * AUDIT SEC-A fix (A11): accepting a CONTACT request must also flip the
     * chat-layer requestStatus when a chat doc already exists, and must open
     * the chat (previously Accept only flipped the contact_requests doc and the
     * user had to go find the person again to start talking).
     */
    fun acceptContactRequestAndOpenChat(requestId: String, senderUid: String, onOpenChat: (String) -> Unit) {
        viewModelScope.launch {
            userRepository.acceptContactRequest(requestId)
                .onSuccess {
                    _toastMessage.value = "تم قبول طلب التعارف"
                    if (senderUid.isNotBlank()) {
                        chatRepository.setMessageRequestDecision(directChatIdFor(senderUid), senderUid, "ACCEPTED")
                        chatRepository.getOrCreateDirectChat(senderUid)
                            .onSuccess { chatId -> onOpenChat(chatId) }
                            .onFailure { _toastMessage.value = "تم القبول، تعذر فتح المحادثة" }
                    }
                }
                .onFailure { _toastMessage.value = "تعذر قبول الطلب" }
        }
    }

    fun toggleArchive(chat: ChatPreview) {
        val next = !chat.isArchived
        viewModelScope.launch {
            chatRepository.toggleArchiveChat(chat.chatId, next)
                .onSuccess {
                    _toastMessage.value = if (next) "تمت أرشفة المحادثة" else "تمت إعادة المحادثة من الأرشيف"
                }
                .onFailure {
                    _toastMessage.value = "تعذر تحديث الأرشفة"
                }
        }
    }

    fun deleteChat(chat: ChatPreview, forEveryone: Boolean = false) {
        viewModelScope.launch {
            chatRepository.deleteChat(chat.chatId, forEveryone)
                .onSuccess {
                    prefs.toggleHiddenChat(chat.chatId, false)
                    prefs.toggleLockedChat(chat.chatId, false)
                    _toastMessage.value = if (forEveryone) {
                        "تم حذف المحادثة لدى الطرفين"
                    } else {
                        "تم حذف المحادثة من جهازك"
                    }
                }
                .onFailure { err ->
                    _toastMessage.value = "تعذر حذف المحادثة"
                }
        }
    }
}
