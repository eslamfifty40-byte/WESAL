package com.wesal.messenger.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.domain.model.ChatPreview
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatsListViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val authRepository: AuthRepository,
    private val prefs: UserPreferencesDataStore
) : ViewModel() {

    val currentUid: String
        get() = authRepository.currentUserId.orEmpty()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _showHiddenChats = MutableStateFlow(false)
    val showHiddenChats: StateFlow<Boolean> = _showHiddenChats.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    val hiddenChatIds: StateFlow<Set<String>> = prefs.settingsFlow
        .map { it.hiddenChatIds }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val chats: StateFlow<List<ChatPreview>> = combine(
        chatRepository.observeChats(),
        _searchQuery,
        hiddenChatIds,
        _showHiddenChats
    ) { allChats, query, hiddenIds, showingHidden ->
        allChats.filter { chat ->
            val isHidden = chat.chatId in hiddenIds
            val visibilityMatch = if (showingHidden) isHidden else !isHidden
            val queryMatch = query.isBlank() ||
                chat.title.contains(query, ignoreCase = true) ||
                chat.lastMessagePreview.contains(query, ignoreCase = true)
            visibilityMatch && queryMatch
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hiddenCount: StateFlow<Int> = combine(
        chatRepository.observeChats(),
        hiddenChatIds
    ) { allChats, hiddenIds ->
        allChats.count { it.chatId in hiddenIds }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun hasHiddenPassword(): Boolean = prefs.hasHiddenChatsPassword(currentUid)

    fun getHiddenRecoveryQuestion(): String = prefs.getHiddenChatsRecoveryQuestion(currentUid)

    fun verifyHiddenPassword(candidate: String): Boolean =
        prefs.verifyHiddenChatsPassword(currentUid, candidate)

    fun verifyHiddenRecoveryAnswer(answer: String): Boolean =
        prefs.verifyHiddenChatsRecoveryAnswer(currentUid, answer)

    fun setupOrResetHiddenPassword(
        password: String,
        question: String,
        answer: String,
        pendingHideChatId: String? = null
    ) {
        viewModelScope.launch {
            prefs.setHiddenChatsPassword(currentUid, password, question, answer)
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
            _showHiddenChats.value = false
            _toastMessage.value = "تمت إعادة تعيين حماية المحادثات المخفية وإظهارها"
        }
    }

    fun setShowingHiddenChats(showing: Boolean) {
        _showHiddenChats.value = showing
    }

    fun hideOrUnhideChat(chat: ChatPreview, hide: Boolean) {
        viewModelScope.launch {
            prefs.toggleHiddenChat(chat.chatId, hide)
            _toastMessage.value = if (hide) "تم نقل المحادثة إلى المحادثات المخفية" else "تمت إعادة المحادثة إلى القائمة الرئيسية"
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
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

    fun deleteChat(chat: ChatPreview, forEveryone: Boolean = false) {
        viewModelScope.launch {
            chatRepository.deleteChat(chat.chatId, forEveryone)
                .onSuccess {
                    prefs.toggleHiddenChat(chat.chatId, false)
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
