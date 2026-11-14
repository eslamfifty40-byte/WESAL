package com.wesal.messenger.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    authRepository: AuthRepository
) : ViewModel() {

    val currentUid: String = authRepository.currentUserId.orEmpty()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _showArchived = MutableStateFlow(false)
    val showArchived: StateFlow<Boolean> = _showArchived.asStateFlow()

    val chats: StateFlow<List<ChatPreview>> = combine(
        chatRepository.observeChats(),
        _searchQuery,
        _showArchived
    ) { allChats, query, archivedOnly ->
        allChats.filter { chat ->
            val archiveMatch = if (archivedOnly) chat.isArchived else !chat.isArchived
            val queryMatch = query.isBlank() ||
                chat.title.contains(query, ignoreCase = true) ||
                chat.lastMessagePreview.contains(query, ignoreCase = true)
            archiveMatch && queryMatch
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val archivedCount: StateFlow<Int> = chatRepository.observeChats()
        .map { list -> list.count { it.isArchived } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleShowArchived() {
        _showArchived.value = !_showArchived.value
    }

    fun togglePin(chat: ChatPreview) {
        viewModelScope.launch { chatRepository.togglePinChat(chat.chatId, !chat.isPinned) }
    }

    fun toggleArchive(chat: ChatPreview) {
        viewModelScope.launch { chatRepository.toggleArchiveChat(chat.chatId, !chat.isArchived) }
    }

    fun toggleMute(chat: ChatPreview) {
        viewModelScope.launch { chatRepository.toggleMuteChat(chat.chatId, !chat.isMuted) }
    }

    fun deleteChat(chat: ChatPreview) {
        viewModelScope.launch { chatRepository.deleteChat(chat.chatId) }
    }
}
