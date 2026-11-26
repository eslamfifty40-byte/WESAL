package com.wesal.messenger.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wesal.messenger.core.util.WesalIdUtils
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.domain.model.ChatListSyncState
import com.wesal.messenger.domain.model.ChatPreview
import com.wesal.messenger.domain.model.ContactRequest
import com.wesal.messenger.domain.model.ContactRequestStatus
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.ChatRepository
import com.wesal.messenger.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatsListViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository,
    private val prefs: UserPreferencesDataStore
) : ViewModel() {

    val currentUid: String
        get() = authRepository.currentUserId.orEmpty()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _showArchivedOnly = MutableStateFlow(false)
    val showArchivedOnly: StateFlow<Boolean> = _showArchivedOnly.asStateFlow()

    val showHiddenChats: StateFlow<Boolean> = prefs.hiddenChatsUnlockedSession
    val openHiddenChatsTrigger: StateFlow<Boolean> = prefs.openHiddenChatsTrigger

    private var unlockedViaSearchBar: Boolean = false

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    val hiddenChatIds: StateFlow<Set<String>> = prefs.settingsFlow
        .map { it.hiddenChatIds }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val lockedChatIds: StateFlow<Set<String>> = prefs.settingsFlow
        .map { it.lockedChatIds }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    // One shared subscription to the chats listener for the whole screen (previously every
    // derived flow opened its own). The retry key lets the error state re-subscribe after
    // Firestore dropped the listener on error (spec §13.3: loading stops, error + retry).
    private val _chatsRetryKey = MutableStateFlow(0)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val allChatsSource: StateFlow<List<ChatPreview>> = _chatsRetryKey
        .flatMapLatest { chatRepository.observeChats() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    fun consumeOpenHiddenChatsTrigger() {
        prefs.consumeOpenHiddenChats()
    }

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
                .onFailure { _toastMessage.value = it.localizedMessage ?: "تعذر تحديث الطلب" }
        }
    }

    fun acceptContactRequest(requestId: String) {
        viewModelScope.launch {
            userRepository.acceptContactRequest(requestId)
                .onSuccess { _toastMessage.value = "تم قبول طلب التعارف" }
                .onFailure { _toastMessage.value = it.localizedMessage }
        }
    }

    fun holdContactRequest(requestId: String) {
        viewModelScope.launch {
            userRepository.holdContactRequest(requestId)
                .onSuccess { _toastMessage.value = "تم تعليق الطلب في قائمة الانتظار" }
                .onFailure { _toastMessage.value = it.localizedMessage }
        }
    }

    fun rejectContactRequest(requestId: String) {
        viewModelScope.launch {
            userRepository.rejectContactRequest(requestId)
                .onSuccess { _toastMessage.value = "تم رفض الطلب" }
                .onFailure { _toastMessage.value = it.localizedMessage }
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
                _toastMessage.value = it.localizedMessage ?: "تعذر إرسال البلاغ"
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
        viewModelScope.launch { chatRepository.togglePinChat(chat.chatId, !chat.isPinned) }
    }

    fun toggleMute(chat: ChatPreview) {
        viewModelScope.launch { chatRepository.toggleMuteChat(chat.chatId, !chat.isMuted) }
    }

    fun toggleArchive(chat: ChatPreview) {
        val next = !chat.isArchived
        viewModelScope.launch {
            chatRepository.toggleArchiveChat(chat.chatId, next)
                .onSuccess {
                    _toastMessage.value = if (next) "تمت أرشفة المحادثة" else "تمت إعادة المحادثة من الأرشيف"
                }
                .onFailure {
                    _toastMessage.value = it.localizedMessage ?: "تعذر تحديث الأرشفة"
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
                    _toastMessage.value = err.localizedMessage ?: "تعذر حذف المحادثة"
                }
        }
    }
}
