package com.wesal.messenger.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.ChatRepository
import com.wesal.messenger.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ContactsUiState(
    val query: String = "",
    val results: List<WesalUser> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContactsUiState())
    val uiState: StateFlow<ContactsUiState> = _uiState.asStateFlow()

    val currentUser: StateFlow<WesalUser?> = userRepository.observeCurrentUser()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun search(query: String) {
        _uiState.update { it.copy(query = query, isSearching = query.isNotBlank(), error = null) }
        if (query.isBlank()) {
            _uiState.update { it.copy(results = emptyList(), isSearching = false) }
            return
        }
        viewModelScope.launch {
            runCatching { userRepository.searchUsers(query) }
                .onSuccess { list ->
                    _uiState.update { it.copy(results = list, isSearching = false) }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(isSearching = false, error = err.localizedMessage) }
                }
        }
    }

    fun startChatWith(peerUid: String, onChatReady: (String) -> Unit) {
        viewModelScope.launch {
            chatRepository.getOrCreateDirectChat(peerUid)
                .onSuccess { chatId -> onChatReady(chatId) }
                .onFailure { err -> _uiState.update { it.copy(error = err.localizedMessage) } }
        }
    }

    fun unblock(targetUid: String) {
        viewModelScope.launch { userRepository.unblockUser(targetUid) }
    }
}
