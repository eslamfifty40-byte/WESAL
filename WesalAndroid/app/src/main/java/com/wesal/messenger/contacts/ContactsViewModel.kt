package com.wesal.messenger.contacts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.ChatRepository
import com.wesal.messenger.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MatchedWesalContact(
    val deviceName: String,
    val user: WesalUser
)

data class ContactsUiState(
    val query: String = "",
    val isLoadingContacts: Boolean = false,
    val wesalContacts: List<MatchedWesalContact> = emptyList(),
    val nonWesalContacts: List<DeviceContact> = emptyList(),
    val discoverableUsers: List<WesalUser> = emptyList(),
    val isLoadingDiscoverable: Boolean = false,
    val searchResults: List<WesalUser> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ContactsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userRepository: UserRepository,
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContactsUiState())
    val uiState: StateFlow<ContactsUiState> = _uiState.asStateFlow()

    val currentUser: StateFlow<WesalUser?> = userRepository.observeCurrentUser()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        refreshDiscoverableUsers()
    }

    fun refreshDiscoverableUsers() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingDiscoverable = true) }
            runCatching { userRepository.listDiscoverableUsers(limit = 30) }
                .onSuccess { list ->
                    _uiState.update { it.copy(discoverableUsers = list, isLoadingDiscoverable = false) }
                }
                .onFailure {
                    _uiState.update { it.copy(isLoadingDiscoverable = false) }
                }
        }
    }

    fun syncDeviceContacts() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingContacts = true, error = null) }
            runCatching {
                val deviceList = PhoneContactsHelper.loadDeviceContacts(context)
                val hashes = deviceList.mapNotNull { it.phoneHash }.distinct()
                val matchedByHash = userRepository.findUsersByPhoneHashes(hashes)

                val matched = mutableListOf<MatchedWesalContact>()
                val unmatched = mutableListOf<DeviceContact>()
                val seenUids = mutableSetOf<String>()

                deviceList.forEach { contact ->
                    val user = contact.phoneHash?.let { matchedByHash[it] }
                    if (user != null) {
                        if (seenUids.add(user.uid)) {
                            matched.add(MatchedWesalContact(deviceName = contact.displayName, user = user))
                        }
                    } else {
                        unmatched.add(contact)
                    }
                }
                Pair(matched, unmatched)
            }.onSuccess { (matched, unmatched) ->
                _uiState.update {
                    it.copy(
                        isLoadingContacts = false,
                        wesalContacts = matched,
                        nonWesalContacts = unmatched
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoadingContacts = false,
                        error = err.localizedMessage ?: "تعذر قراءة جهات الاتصال"
                    )
                }
            }
        }
    }

    fun search(query: String) {
        _uiState.update { it.copy(query = query, isSearching = query.isNotBlank(), error = null) }
        if (query.isBlank()) {
            _uiState.update { it.copy(searchResults = emptyList(), isSearching = false) }
            return
        }
        viewModelScope.launch {
            runCatching { userRepository.searchUsers(query) }
                .onSuccess { list ->
                    _uiState.update { it.copy(searchResults = list, isSearching = false) }
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
