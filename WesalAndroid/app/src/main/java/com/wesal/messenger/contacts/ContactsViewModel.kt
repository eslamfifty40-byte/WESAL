package com.wesal.messenger.contacts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.domain.model.ContactRequest
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.ChatRepository
import com.wesal.messenger.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val searchResults: List<WesalUser> = emptyList(),
    val isSearching: Boolean = false,
    val invitedPhonesCount: Int = 0,
    val error: String? = null,
    val feedback: String? = null
)

@HiltViewModel
class ContactsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userRepository: UserRepository,
    private val chatRepository: ChatRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    val myUid: String get() = authRepository.currentUserId.orEmpty()

    private val _uiState = MutableStateFlow(ContactsUiState())
    val uiState: StateFlow<ContactsUiState> = _uiState.asStateFlow()

    val currentUser: StateFlow<WesalUser?> = userRepository.observeCurrentUser()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val contactRequests: StateFlow<List<ContactRequest>> = userRepository.observeContactRequests()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isSuperAdmin: StateFlow<Boolean> = userRepository.observeIsSuperAdmin()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            authRepository.observeAuthState()
                .distinctUntilChanged()
                .collect {
                    searchJob?.cancel()
                    _uiState.value = ContactsUiState()
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
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(searchResults = emptyList(), isSearching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(250L)
            runCatching { userRepository.searchUsers(query) }
                .onSuccess { list ->
                    _uiState.update { it.copy(searchResults = list, isSearching = false) }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(isSearching = false, error = err.localizedMessage) }
                }
        }
    }

    fun sendContactRequest(targetUid: String) {
        viewModelScope.launch {
            userRepository.sendContactRequest(targetUid)
                .onSuccess {
                    _uiState.update { it.copy(feedback = "تم إرسال طلب التعارف ✓") }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(error = err.message ?: "تعذر إرسال الطلب") }
                }
        }
    }

    fun acceptContactRequest(requestId: String) {
        viewModelScope.launch {
            userRepository.acceptContactRequest(requestId)
                .onFailure { err -> _uiState.update { it.copy(error = err.message) } }
        }
    }

    fun rejectContactRequest(requestId: String) {
        viewModelScope.launch {
            userRepository.rejectContactRequest(requestId)
                .onFailure { err -> _uiState.update { it.copy(error = err.message) } }
        }
    }

    fun cancelContactRequest(requestId: String) {
        viewModelScope.launch {
            userRepository.cancelOrRemoveContactRequest(requestId)
                .onFailure { err -> _uiState.update { it.copy(error = err.message) } }
        }
    }

    fun startChatWith(peerUid: String, onChatReady: (String) -> Unit) {
        viewModelScope.launch {
            chatRepository.getOrCreateDirectChat(peerUid)
                .onSuccess { chatId -> onChatReady(chatId) }
                .onFailure { err -> _uiState.update { it.copy(error = err.localizedMessage ?: "تعذر فتح المحادثة") } }
        }
    }

    fun openSupportChat10001(onChatReady: (String) -> Unit) {
        viewModelScope.launch {
            chatRepository.openSupportChatByNumericId(Constants.OWNER_ADMIN_NUMERIC_ID)
                .onSuccess { chatId -> onChatReady(chatId) }
                .onFailure { err -> _uiState.update { it.copy(error = err.localizedMessage ?: "تعذر فتح محادثة الإدارة") } }
        }
    }

    fun recordInviteDispatched() {
        _uiState.update { it.copy(invitedPhonesCount = it.invitedPhonesCount + 1) }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(feedback = null, error = null) }
    }

    fun unblock(targetUid: String) {
        viewModelScope.launch { userRepository.unblockUser(targetUid) }
    }
}
