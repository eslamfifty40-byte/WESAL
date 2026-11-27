package com.wesal.messenger.contacts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.domain.model.AppEventItem
import com.wesal.messenger.domain.model.ChatListSyncState
import com.wesal.messenger.domain.model.ContactLabelList
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
    val broadcastResults: Map<String, Boolean>? = null,
    val isSendingBroadcast: Boolean = false,
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

    /** Accounts that registered through my invite link (round 3) — shown as their own
     *  section in the contacts screen, independent of the device phone book. */
    val myReferrals: StateFlow<List<WesalUser>> = userRepository.observeMyReferrals()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Review MINOR-2: the contact-lists sync state existed repository-side
    // (P1-10) with no UI consumer, and the data flow could never recover from
    // a listener error. Both flows now hang off a retry key — bumping it
    // re-subscribes the dead listener — and the lists section renders the
    // ERROR state with a retry button.
    private val contactListsRetryKey = MutableStateFlow(0)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val contactLists: StateFlow<List<ContactLabelList>> = contactListsRetryKey
        .flatMapLatest { userRepository.observeContactLists() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val contactListsSyncState: StateFlow<ChatListSyncState> = userRepository.observeContactListsSyncState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatListSyncState())

    fun retryContactLists() {
        contactListsRetryKey.value += 1
    }

    val appEvents: StateFlow<List<AppEventItem>> = userRepository.observeAppEvents()
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
            val showLoader = _uiState.value.wesalContacts.isEmpty() && _uiState.value.nonWesalContacts.isEmpty()
            _uiState.update { it.copy(isLoadingContacts = showLoader, error = null) }
            // Device contacts load independently of the WESAL lookup: if the lookup fails, the
            // list must still render (with an error), never silently look empty.
            val deviceList = runCatching { PhoneContactsHelper.loadDeviceContacts(context) }
                .getOrElse { err ->
                    _uiState.update {
                        it.copy(
                            isLoadingContacts = false,
                            error = err.localizedMessage ?: "تعذر قراءة جهات الاتصال"
                        )
                    }
                    return@launch
                }
            val hashes = deviceList
                .flatMap { it.candidateHashes.ifEmpty { listOfNotNull(it.phoneHash) } }
                .distinct()
            val matchedByHash = runCatching { userRepository.findUsersByPhoneHashes(hashes) }
                .getOrElse { err ->
                    _uiState.update {
                        it.copy(
                            isLoadingContacts = false,
                            wesalContacts = emptyList(),
                            nonWesalContacts = deviceList.sortedBy { c -> c.displayName.trim().lowercase() },
                            error = err.localizedMessage
                                ?: "تعذر التحقق من جهات الاتصال المسجلة على WESAL"
                        )
                    }
                    return@launch
                }
            runCatching {

                val matched = mutableListOf<MatchedWesalContact>()
                val unmatched = mutableListOf<DeviceContact>()
                val seenUids = mutableSetOf<String>()

                deviceList.forEach { contact ->
                    val contactHashes = contact.candidateHashes.ifEmpty { listOfNotNull(contact.phoneHash) }
                    val user = contactHashes.firstNotNullOfOrNull { matchedByHash[it] }
                    val isSecondaryAdmin = user != null && (
                        user.numericId == Constants.SECONDARY_ADMIN_NUMERIC_ID ||
                            Constants.SECONDARY_ADMIN_EMAIL.equals(user.email, ignoreCase = true)
                        )
                    if (user != null && !isSecondaryAdmin) {
                        if (seenUids.add(user.uid)) {
                            matched.add(MatchedWesalContact(deviceName = contact.displayName, user = user))
                        }
                    } else if (user == null) {
                        unmatched.add(contact)
                    }
                }
                val sortedMatched = matched.sortedBy {
                    it.deviceName.ifBlank { it.user.displayName }.trim().lowercase()
                }
                val sortedUnmatched = unmatched.sortedBy {
                    it.displayName.trim().lowercase()
                }
                Pair(sortedMatched, sortedUnmatched)
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

    fun saveDeviceContact(name: String, phone: String, existingContactId: String? = null) {
        viewModelScope.launch {
            PhoneContactsHelper.upsertDeviceContact(context, name, phone, existingContactId)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            feedback = if (existingContactId.isNullOrBlank()) {
                                "تمت إضافة جهة الاتصال إلى الجهاز ✓"
                            } else {
                                "تم تعديل جهة الاتصال ✓"
                            }
                        )
                    }
                    syncDeviceContacts()
                }
                .onFailure { err ->
                    _uiState.update { it.copy(error = err.localizedMessage ?: "تعذر حفظ جهة الاتصال") }
                }
        }
    }

    fun deleteDeviceContact(contactId: String) {
        viewModelScope.launch {
            val phone = _uiState.value.nonWesalContacts.firstOrNull { it.contactId == contactId }?.rawPhone
            // Hide the row at once, then really delete (including duplicate copies of the same number).
            _uiState.update { st -> st.copy(nonWesalContacts = st.nonWesalContacts.filterNot { it.contactId == contactId }) }
            PhoneContactsHelper.deleteDeviceContact(context, contactId, phone)
                .onSuccess {
                    _uiState.update { it.copy(feedback = "تم حذف جهة الاتصال من الجهاز ✓") }
                    syncDeviceContacts()
                }
                .onFailure { err ->
                    _uiState.update { it.copy(error = err.localizedMessage ?: "تعذر حذف جهة الاتصال") }
                }
        }
    }

    fun saveContactList(listId: String?, name: String, memberUids: List<String>) {
        viewModelScope.launch {
            userRepository.upsertContactList(listId, name, memberUids)
                .onSuccess { _uiState.update { it.copy(feedback = "تم حفظ القائمة ✓") } }
                .onFailure { err -> _uiState.update { it.copy(error = err.localizedMessage ?: "تعذر حفظ القائمة") } }
        }
    }

    fun deleteContactList(listId: String) {
        viewModelScope.launch {
            userRepository.deleteContactList(listId)
                .onSuccess { _uiState.update { it.copy(feedback = "تم حذف القائمة") } }
                .onFailure { err -> _uiState.update { it.copy(error = err.localizedMessage) } }
        }
    }

    fun createAppEvent(title: String, description: String, locationName: String, eventTime: Long) {
        viewModelScope.launch {
            userRepository.createAppEvent(title, description, locationName, eventTime)
                .onSuccess { _uiState.update { it.copy(feedback = "تم نشر المناسبة ✓") } }
                .onFailure { err -> _uiState.update { it.copy(error = err.localizedMessage ?: "تعذر إنشاء المناسبة") } }
        }
    }

    fun deleteAppEvent(eventId: String) {
        viewModelScope.launch {
            userRepository.deleteAppEvent(eventId)
                .onSuccess { _uiState.update { it.copy(feedback = "تم حذف المناسبة") } }
                .onFailure { err -> _uiState.update { it.copy(error = err.localizedMessage) } }
        }
    }

    fun toggleEventRsvp(eventId: String, join: Boolean) {
        viewModelScope.launch {
            userRepository.rsvpAppEvent(eventId, join)
                .onFailure { err -> _uiState.update { it.copy(error = err.localizedMessage) } }
        }
    }

    fun sendBusinessBroadcast(recipientUids: List<String>, text: String) {
        if (recipientUids.isEmpty() || text.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSendingBroadcast = true, broadcastResults = null) }
            chatRepository.sendBusinessBroadcast(recipientUids, text)
                .onSuccess { map ->
                    val okCount = map.values.count { it }
                    _uiState.update {
                        it.copy(
                            isSendingBroadcast = false,
                            broadcastResults = map,
                            feedback = "تم الإرسال الجماعي لـ $okCount من أصل ${recipientUids.size} ✓"
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isSendingBroadcast = false,
                            error = err.localizedMessage ?: "تعذر إرسال البث الجماعي"
                        )
                    }
                }
        }
    }

    fun clearBroadcastResults() {
        _uiState.update { it.copy(broadcastResults = null) }
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

