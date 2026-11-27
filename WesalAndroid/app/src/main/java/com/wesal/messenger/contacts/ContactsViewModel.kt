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

/**
 * Stable key for one unregistered device-contact row — the same (contactId, rawPhone)
 * pair the contacts list rows are keyed by. Selection and invited tracking are keyed by
 * this, never by name or by a bare number, so a selection entry always points at exactly
 * one contact record (and its invite always uses that record's own name AND number).
 */
fun deviceContactKey(contact: DeviceContact): String = "${contact.contactId}_${contact.rawPhone}"

data class ContactsUiState(
    val query: String = "",
    val isLoadingContacts: Boolean = false,
    val wesalContacts: List<MatchedWesalContact> = emptyList(),
    val nonWesalContacts: List<DeviceContact> = emptyList(),
    val searchResults: List<WesalUser> = emptyList(),
    val isSearching: Boolean = false,
    val invitedPhonesCount: Int = 0,
    /** Bulk-invite selection mode: keys ([deviceContactKey]) of the unregistered contacts the user ticked. */
    val selectedContactKeys: Set<String> = emptySet(),
    /** Keys of contacts already invited in this session — drives the «تمت دعوته ✓» row state and the bulk "next" advance. */
    val invitedContactKeys: Set<String> = emptySet(),
    val broadcastResults: Map<String, Boolean>? = null,
    val isSendingBroadcast: Boolean = false,
    val error: String? = null,
    val feedback: String? = null,
    // Round 6 (item 9): dedicated add/edit device-contact save outcome. The
    // dialog observes these to stay OPEN on failure (with the error shown
    // inside it) and close only on success — previously it closed
    // unconditionally the moment حفظ was tapped, so a failed insert was
    // indistinguishable from a saved contact that never appeared.
    val isSavingDeviceContact: Boolean = false,
    val deviceContactSaveError: String? = null,
    val deviceContactSaveSucceeded: Boolean = false,
    // Round 10 (item 4): timestamp of the last SUCCESSFUL phone-book sync —
    // the contacts screen renders it («على وصال: X • آخر مزامنة …») so an
    // empty «على وصال» section is diagnosable (never synced / synced just
    // now with zero matches / stale) instead of silently ambiguous.
    val lastSyncAtMs: Long = 0L
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
    private var syncJob: Job? = null
    private var liveUpdatesJob: Job? = null

    /** The device phone book as of the last sync — the live listeners re-derive matches against it. */
    @Volatile
    private var lastDeviceList: List<DeviceContact> = emptyList()

    /** Hash → user matches from the last full sync, unioned with live emissions. */
    @Volatile
    private var lastMatchedByHash: Map<String, WesalUser> = emptyMap()

    /** Uid the current uiState belongs to; the auth collector resets ONLY on a real change. */
    @Volatile
    private var lastAuthUid: String? = null

    private val cacheStore by lazy { ContactsCacheStore(context) }

    private fun hasContactsPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CONTACTS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    init {
        viewModelScope.launch {
            authRepository.observeAuthState()
                .distinctUntilChanged()
                .collect { uid ->
                    // Round 11 (addendum 3) — PROVEN trigger gap: this
                    // collector used to reset the state on EVERY emission,
                    // and observeAuthState re-emits the SAME uid whenever
                    // the stream restarts (ViewModel recreation on
                    // navigation, token refresh). The ViewModel is
                    // destination-scoped, so each entry created a fresh
                    // empty state AND the reset could land after the sync's
                    // results, wiping them — the screen then sat empty
                    // until a manual refresh re-ran the sync. Now: reset
                    // only on an actual account change; on first sight of a
                    // uid, render the persisted cache immediately and
                    // auto-sync (no manual step).
                    if (uid == lastAuthUid) return@collect
                    val previousUid = lastAuthUid
                    lastAuthUid = uid
                    searchJob?.cancel()
                    liveUpdatesJob?.cancel()
                    liveUpdatesJob = null
                    lastDeviceList = emptyList()
                    lastMatchedByHash = emptyMap()
                    _uiState.value = ContactsUiState()
                    if (uid.isNullOrBlank()) return@collect
                    if (previousUid != null) {
                        // Account switch: nothing from the old account may linger.
                        syncJob?.cancel()
                    }
                    val cached = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        cacheStore.load(uid)
                    }
                    if (cached != null && (cached.matched.isNotEmpty() || cached.unmatched.isNotEmpty())) {
                        // The unmatched rows are real device records (they
                        // carry the candidate hashes the live listeners and
                        // re-derivation need); matched rows render from the
                        // cached user subset directly.
                        lastDeviceList = cached.unmatched
                        _uiState.update {
                            it.copy(
                                wesalContacts = cached.matched,
                                nonWesalContacts = cached.unmatched,
                                // Honest staleness: the status line shows the
                                // CACHE's time until the fresh sync lands.
                                lastSyncAtMs = cached.savedAtMs
                            )
                        }
                    }
                    if (hasContactsPermission()) {
                        // Round 12 (addendum 3c): a cached ZERO-registered
                        // snapshot over a non-empty phone book must never
                        // mask reality — force a fresh live sync (cancelling
                        // any in-flight one) and update in place.
                        val cachedZeroRegistered = cached != null &&
                            cached.matched.isEmpty() && cached.unmatched.isNotEmpty()
                        syncDeviceContacts(force = cachedZeroRegistered)
                    }
                }
        }
    }

    fun syncDeviceContacts(force: Boolean = false) {
        if (syncJob?.isActive == true) {
            if (!force) return
            syncJob?.cancel()
        }
        syncJob = viewModelScope.launch {
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
            lastDeviceList = deviceList
            val matchedByHash = runCatching { userRepository.findUsersByPhoneHashes(hashes) }
                .getOrElse { err ->
                    _uiState.update {
                        // Round 11 (addendum 3): a failed lookup must not
                        // wipe lists the cache/state already rendered — keep
                        // what is on screen and surface the error instead.
                        if (it.wesalContacts.isEmpty() && it.nonWesalContacts.isEmpty()) {
                            it.copy(
                                isLoadingContacts = false,
                                nonWesalContacts = deviceList.sortedBy { c -> c.displayName.trim().lowercase() },
                                error = err.localizedMessage
                                    ?: "تعذر التحقق من جهات الاتصال المسجلة على WESAL"
                            )
                        } else {
                            it.copy(
                                isLoadingContacts = false,
                                error = err.localizedMessage
                                    ?: "تعذر التحقق من جهات الاتصال المسجلة على WESAL"
                            )
                        }
                    }
                    return@launch
                }
            try {
                val (matched, unmatched) = deriveMatches(deviceList, matchedByHash)
                if (com.wesal.messenger.BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "ContactsSync",
                        "device=${deviceList.size} hashes=${hashes.size} " +
                            "registryOrFirestoreMatched=${matchedByHash.size} " +
                            "onWesal=${matched.size} unmatched=${unmatched.size}"
                    )
                }
                lastMatchedByHash = matchedByHash
                val syncAt = System.currentTimeMillis()
                _uiState.update {
                    it.copy(
                        isLoadingContacts = false,
                        wesalContacts = matched,
                        nonWesalContacts = unmatched,
                        lastSyncAtMs = syncAt,
                        // Keep the bulk-invite selection honest across refreshes: drop
                        // keys whose contact no longer exists in the unregistered list.
                        selectedContactKeys = it.selectedContactKeys
                            .intersect(unmatched.map { c -> deviceContactKey(c) }.toSet())
                    )
                }
                // Round 11 (addendum 3): persist the result so the next
                // entry renders instantly instead of opening empty.
                val uid = lastAuthUid
                if (!uid.isNullOrBlank()) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        cacheStore.save(
                            uid,
                            ContactsCacheSnapshot(
                                matched = matched,
                                unmatched = unmatched,
                                savedAtMs = syncAt
                            )
                        )
                    }
                }
            } catch (err: Exception) {
                if (err is kotlinx.coroutines.CancellationException) throw err
                _uiState.update {
                    it.copy(
                        isLoadingContacts = false,
                        error = err.localizedMessage ?: "تعذر قراءة جهات الاتصال"
                    )
                }
            }
        }
    }

    /**
     * Shared derivation: phone book + hash→user matches → (on-WESAL,
     * unregistered), both sorted for display. Used by the one-shot sync
     * and by the live listeners so the two paths can never disagree.
     * Round 9 (item 8): no secondary-admin carve-out — the repository map
     * already excludes only the current user themself, and a verified
     * match belongs in the «على وصال» list, admins included.
     */
    private fun deriveMatches(
        deviceList: List<DeviceContact>,
        matchedByHash: Map<String, WesalUser>
    ): Pair<List<MatchedWesalContact>, List<DeviceContact>> {
        val matched = mutableListOf<MatchedWesalContact>()
        val unmatched = mutableListOf<DeviceContact>()
        val seenUids = mutableSetOf<String>()
        deviceList.forEach { contact ->
            val contactHashes = contact.candidateHashes.ifEmpty { listOfNotNull(contact.phoneHash) }
            val user = contactHashes.firstNotNullOfOrNull { matchedByHash[it] }
            if (user != null) {
                if (seenUids.add(user.uid)) {
                    matched.add(MatchedWesalContact(deviceName = contact.displayName, user = user))
                }
            } else {
                unmatched.add(contact)
            }
        }
        val sortedMatched = matched.sortedBy {
            it.deviceName.ifBlank { it.user.displayName }.trim().lowercase()
        }
        val sortedUnmatched = unmatched.sortedBy {
            it.displayName.trim().lowercase()
        }
        return Pair(sortedMatched, sortedUnmatched)
    }

    /**
     * Round 11 (item 5): LIVE contacts updates while the screen is
     * visible. Chunked snapshot listeners over the users phoneHash
     * queries (repository-side) re-derive the lists the moment a matching
     * user doc appears/changes — a contact who registers NOW appears in
     * «على وصال» without closing/reopening the screen. Bounded: the
     * screen starts this on ON_RESUME and stops it on ON_PAUSE, and the
     * listeners die with the flow collection.
     */
    fun startLiveContactUpdates() {
        if (liveUpdatesJob?.isActive == true) return
        if (!hasContactsPermission()) return
        liveUpdatesJob = viewModelScope.launch {
            // Fresh phone-book read: the candidate hashes must cover the
            // MATCHED contacts too (their hashes aren't in the cache), so
            // a matched contact whose doc changes re-derives correctly.
            val deviceList = runCatching { PhoneContactsHelper.loadDeviceContacts(context) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: lastDeviceList
            if (deviceList.isEmpty()) return@launch
            lastDeviceList = deviceList
            val hashes = deviceList
                .flatMap { it.candidateHashes.ifEmpty { listOfNotNull(it.phoneHash) } }
                .distinct()
            if (hashes.isEmpty()) return@launch
            userRepository.observeUsersByPhoneHashes(hashes).collect { matchedByHash ->
                // Union with the last full sync's matches (which include
                // the registry-primary source): live Firestore emissions
                // add to / refresh the picture, never shrink it below the
                // last sync. The next ON_RESUME sync re-derives from
                // scratch and corrects any drift.
                lastMatchedByHash = lastMatchedByHash + matchedByHash
                val (matched, unmatched) = deriveMatches(deviceList, lastMatchedByHash)
                val state = _uiState.value
                if (matched == state.wesalContacts && unmatched == state.nonWesalContacts) {
                    return@collect
                }
                _uiState.update {
                    it.copy(
                        wesalContacts = matched,
                        nonWesalContacts = unmatched,
                        selectedContactKeys = it.selectedContactKeys
                            .intersect(unmatched.map { c -> deviceContactKey(c) }.toSet())
                    )
                }
                // Persist the fresher picture (keeping the last real SYNC
                // timestamp — a live update is not a phone-book sync).
                val uid = lastAuthUid
                if (!uid.isNullOrBlank()) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        cacheStore.save(
                            uid,
                            ContactsCacheSnapshot(
                                matched = matched,
                                unmatched = unmatched,
                                savedAtMs = state.lastSyncAtMs
                            )
                        )
                    }
                }
            }
        }
    }

    fun stopLiveContactUpdates() {
        liveUpdatesJob?.cancel()
        liveUpdatesJob = null
    }

    fun saveDeviceContact(name: String, phone: String, existingContactId: String? = null) {
        if (_uiState.value.isSavingDeviceContact) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSavingDeviceContact = true,
                    deviceContactSaveError = null,
                    deviceContactSaveSucceeded = false
                )
            }
            PhoneContactsHelper.upsertDeviceContact(context, name, phone, existingContactId)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isSavingDeviceContact = false,
                            deviceContactSaveSucceeded = true,
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
                    _uiState.update {
                        it.copy(
                            isSavingDeviceContact = false,
                            deviceContactSaveError = err.localizedMessage ?: "تعذر حفظ جهة الاتصال",
                            error = err.localizedMessage ?: "تعذر حفظ جهة الاتصال"
                        )
                    }
                }
        }
    }

    /** The dialog calls this once it has consumed the save outcome (closed
     *  on success, or the user dismissed after an error). */
    fun clearDeviceContactSaveOutcome() {
        _uiState.update {
            it.copy(
                isSavingDeviceContact = false,
                deviceContactSaveError = null,
                deviceContactSaveSucceeded = false
            )
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
                    // Audit B5: the row was optimistically hidden above; without a
                    // resync a FAILED delete looked exactly like a successful one
                    // until the next manual refresh. Resync restores the true row.
                    syncDeviceContacts()
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

    // ---- Bulk WhatsApp invites: selection mode over the unregistered phone contacts ----

    fun toggleContactSelected(contact: DeviceContact) {
        val key = deviceContactKey(contact)
        _uiState.update { st ->
            st.copy(
                selectedContactKeys = if (key in st.selectedContactKeys) {
                    st.selectedContactKeys - key
                } else {
                    st.selectedContactKeys + key
                }
            )
        }
    }

    /**
     * «تحديد الكل» — selects exactly the contacts the user can SEE (the screen passes its
     * search-filtered list): with a search query active, contacts hidden by the filter must
     * never be silently selected — the selection also feeds the bulk device delete.
     */
    fun selectAllContacts(contacts: List<DeviceContact>) {
        _uiState.update { st ->
            st.copy(selectedContactKeys = contacts.map { deviceContactKey(it) }.toSet())
        }
    }

    fun clearContactSelection() {
        _uiState.update { it.copy(selectedContactKeys = emptySet()) }
    }

    /**
     * Marks a contact as invited in this session: the row switches to «تمت دعوته ✓» and
     * the header «دعوات مرسلة» counter moves — both fed from the same state so the bulk
     * "next" advance and the counter can never disagree. Idempotent per contact.
     */
    fun markContactInvited(contact: DeviceContact) {
        val key = deviceContactKey(contact)
        _uiState.update { st ->
            if (key in st.invitedContactKeys) st
            else st.copy(
                invitedContactKeys = st.invitedContactKeys + key,
                invitedPhonesCount = st.invitedPhonesCount + 1
            )
        }
    }

    /**
     * «حذف المحدد» — bulk delete with the EXACT semantics of the per-row 🗑 delete: every
     * selected contact goes through [PhoneContactsHelper.deleteDeviceContact] (same store,
     * same duplicate-number sweep). Rows are hidden optimistically, then ONE resync at the
     * end restores the truth — so a failed delete can never look like a successful one
     * (same contract as audit fix B5 for the single delete).
     */
    fun deleteSelectedContacts() {
        val selected = _uiState.value.nonWesalContacts
            .filter { deviceContactKey(it) in _uiState.value.selectedContactKeys }
        if (selected.isEmpty()) return
        viewModelScope.launch {
            val keys = selected.map { deviceContactKey(it) }.toSet()
            _uiState.update { st ->
                st.copy(
                    nonWesalContacts = st.nonWesalContacts.filterNot { deviceContactKey(it) in keys },
                    selectedContactKeys = emptySet()
                )
            }
            var failures = 0
            selected.forEach { contact ->
                PhoneContactsHelper.deleteDeviceContact(context, contact.contactId, contact.rawPhone)
                    .onFailure { failures++ }
            }
            _uiState.update { st ->
                if (failures == 0) {
                    st.copy(feedback = "تم حذف ${selected.size} جهة اتصال ✓")
                } else {
                    st.copy(
                        feedback = "تم حذف ${selected.size - failures} من أصل ${selected.size} جهة اتصال",
                        error = "تعذر حذف $failures جهة اتصال"
                    )
                }
            }
            syncDeviceContacts()
        }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(feedback = null, error = null) }
    }

    fun unblock(targetUid: String) {
        viewModelScope.launch { userRepository.unblockUser(targetUid) }
    }
}

