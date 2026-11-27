package com.wesal.messenger.profile

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.wesal.messenger.calls.CallActivity
import com.wesal.messenger.contacts.PhoneContactsHelper
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.core.util.PhoneUtils
import com.wesal.messenger.core.util.WesalShareUtils
import com.wesal.messenger.domain.model.BusinessHoursSchedule
import com.wesal.messenger.domain.model.ChatPreview
import com.wesal.messenger.domain.model.ContactLabelList
import com.wesal.messenger.domain.model.ContactRequest
import com.wesal.messenger.domain.model.ContactRequestStatus
import com.wesal.messenger.domain.model.DisappearingTimer
import com.wesal.messenger.domain.model.MessageType
import com.wesal.messenger.domain.model.PrivacyVisibility
import com.wesal.messenger.domain.model.WesalMessage
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.ChatRepository
import com.wesal.messenger.domain.repository.UserRepository
import com.wesal.messenger.media.FullscreenImageViewer
import com.wesal.messenger.presentation.components.WesalAvatar
import com.wesal.messenger.presentation.components.WesalNameWithBadge
import com.wesal.messenger.presentation.components.WesalQrCodeBox
import com.wesal.messenger.presentation.components.WesalRoyalNameplateBanner
import com.wesal.messenger.presentation.components.saveImageUrlToGallery
import com.wesal.messenger.presentation.components.wesalImageModel
import com.wesal.messenger.presentation.components.wesalNeonTextStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject

@HiltViewModel
class UserProfileDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val userRepository: UserRepository,
    private val chatRepository: ChatRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    val targetUid: String = checkNotNull(savedStateHandle["uid"])
    val myUid: String
        get() = authRepository.currentUserId.orEmpty()

    val peerUser: StateFlow<WesalUser?> = userRepository.observeUser(targetUid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val currentUser: StateFlow<WesalUser?> = userRepository.observeCurrentUser()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isSuperAdmin: StateFlow<Boolean> = userRepository.observeIsSuperAdmin()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val contactRequests: StateFlow<List<ContactRequest>> = userRepository.observeContactRequests()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val contactLists: StateFlow<List<ContactLabelList>> = userRepository.observeContactLists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Review M-3: both lookups use the UNPAGED variant — with the paged
    // observeChats() the direct chat / mutual groups silently missed whenever
    // the chat ranked past the newest 100.
    val directChat: StateFlow<ChatPreview?> = chatRepository.observeAllChats()
        .map { list -> list.firstOrNull { !it.isGroup && !it.isOfficialWesalChat && it.peerUid == targetUid } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val mutualGroups: StateFlow<List<ChatPreview>> = chatRepository.observeAllChats()
        .map { list -> list.filter { it.isGroup && targetUid in it.participantIds } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val sharedMessages: StateFlow<List<WesalMessage>> = directChat
        .flatMapLatest { chat ->
            if (chat == null) {
                flowOf(emptyList())
            } else {
                // Review M-3: observeMessages now streams only the newest 60,
                // which silently truncated this shared-media gallery to the
                // newest page. Merge the live page with a one-shot
                // full-history fetch (document-cursor paged in the
                // repository), deduped by id, oldest first.
                val history = flow {
                    emit(chatRepository.fetchChatMessagesForGallery(chat.chatId))
                }
                combine(chatRepository.observeMessages(chat.chatId), history) { live, past ->
                    (past + live)
                        .distinctBy { it.messageId }
                        .sortedBy { it.timestamp }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private var visitRecorded = false

    fun recordVisitOnce() {
        if (visitRecorded || targetUid == myUid) return
        visitRecorded = true
        viewModelScope.launch {
            userRepository.recordProfileVisit(targetUid)
        }
    }

    fun sendContactRequest() {
        viewModelScope.launch {
            userRepository.sendContactRequest(targetUid)
                .onFailure { _statusMessage.value = it.localizedMessage }
        }
    }

    fun acceptContactRequest(requestId: String) {
        viewModelScope.launch {
            userRepository.acceptContactRequest(requestId)
                .onFailure { _statusMessage.value = it.localizedMessage }
        }
    }

    fun rejectContactRequest(requestId: String) {
        viewModelScope.launch {
            userRepository.rejectContactRequest(requestId)
                .onFailure { _statusMessage.value = it.localizedMessage }
        }
    }

    fun startDirectChat(onOpenChat: (String) -> Unit) {
        viewModelScope.launch {
            chatRepository.getOrCreateDirectChat(targetUid)
                .onSuccess(onOpenChat)
                .onFailure { _statusMessage.value = it.localizedMessage }
        }
    }

    fun toggleMuteDirectChat() {
        val chat = directChat.value ?: return
        viewModelScope.launch {
            chatRepository.toggleMuteChat(chat.chatId, !chat.isMuted)
                .onSuccess {
                    _statusMessage.value = if (!chat.isMuted) "تم كتم إشعارات المحادثة" else "تم تفعيل إشعارات المحادثة"
                }
                // Audit B4: failure was silent — the row looked toggled but nothing was written.
                .onFailure { _statusMessage.value = it.localizedMessage ?: "تعذر تغيير حالة كتم الإشعارات" }
        }
    }

    fun setDisappearingTimer(timer: DisappearingTimer) {
        val chat = directChat.value ?: return
        viewModelScope.launch {
            chatRepository.setChatDisappearingTimer(chat.chatId, timer.seconds)
                .onSuccess {
                    _statusMessage.value = if (timer == DisappearingTimer.OFF) {
                        "تم إيقاف مؤقت اختفاء الرسائل"
                    } else {
                        "تم تفعيل مؤقت ${timer.labelAr}"
                    }
                }
                .onFailure { _statusMessage.value = it.localizedMessage ?: "تعذر تغيير مؤقت اختفاء الرسائل" }
        }
    }

    fun clearDirectChatMessages() {
        val chat = directChat.value ?: return
        viewModelScope.launch {
            chatRepository.clearChatMessagesForMe(chat.chatId)
                .onSuccess { _statusMessage.value = "تم مسح سجل الرسائل لديك ✓" }
                .onFailure { _statusMessage.value = it.localizedMessage ?: "تعذر مسح المحادثة" }
        }
    }

    fun toggleFavoriteInList(favoriteList: ContactLabelList?) {
        viewModelScope.launch {
            // Audit B4: every branch was onSuccess-only — a failed write flipped
            // nothing visibly and told the user nothing.
            if (favoriteList == null) {
                userRepository.upsertContactList(null, "المفضلة", listOf(targetUid))
                    .onSuccess { _statusMessage.value = "تمت الإضافة إلى المفضلة ✓" }
                    .onFailure { _statusMessage.value = it.localizedMessage ?: "تعذّر تحديث المفضلة" }
            } else if (targetUid in favoriteList.memberUids) {
                userRepository.removeUidFromContactList(favoriteList.listId, targetUid)
                    .onSuccess { _statusMessage.value = "تمت الإزالة من المفضلة" }
                    .onFailure { _statusMessage.value = it.localizedMessage ?: "تعذّر تحديث المفضلة" }
            } else {
                userRepository.addUidToContactList(favoriteList.listId, targetUid)
                    .onSuccess { _statusMessage.value = "تمت الإضافة إلى المفضلة ✓" }
                    .onFailure { _statusMessage.value = it.localizedMessage ?: "تعذّر تحديث المفضلة" }
            }
        }
    }

    fun addOrRemoveCustomList(list: ContactLabelList) {
        viewModelScope.launch {
            if (targetUid in list.memberUids) {
                userRepository.removeUidFromContactList(list.listId, targetUid)
                    .onSuccess { _statusMessage.value = "تمت الإزالة من قائمة ${list.name}" }
                    .onFailure { _statusMessage.value = it.localizedMessage ?: "تعذّر تحديث القائمة" }
            } else {
                userRepository.addUidToContactList(list.listId, targetUid)
                    .onSuccess { _statusMessage.value = "تمت الإضافة إلى قائمة ${list.name} ✓" }
                    .onFailure { _statusMessage.value = it.localizedMessage ?: "تعذّر تحديث القائمة" }
            }
        }
    }

    /**
     * Audit B4 (owner: «أقدر احذفهم أقدر أضيفهم — كل حاجة»): removing a contact
     * from the other-user profile. Deletes the contact_requests doc between the
     * two accounts (the repository requires the caller to be a participant), so
     * an ACCEPTED contact is removed and a PENDING outgoing request is cancelled
     * with the same real Firestore effect the contacts screen uses.
     */
    fun removeContactRequest(requestId: String, isEn: Boolean) {
        viewModelScope.launch {
            userRepository.cancelOrRemoveContactRequest(requestId)
                .onSuccess {
                    _statusMessage.value = if (isEn) "Contact removed" else "تمت إزالة جهة الاتصال"
                }
                .onFailure {
                    _statusMessage.value = it.localizedMessage
                        ?: if (isEn) "Could not remove the contact" else "تعذّرت إزالة جهة الاتصال"
                }
        }
    }

    fun createListAndAdd(name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            userRepository.upsertContactList(null, clean, listOf(targetUid))
                .onSuccess { _statusMessage.value = "تم إنشاء قائمة $clean وإضافة الحساب ✓" }
                .onFailure { _statusMessage.value = it.localizedMessage }
        }
    }

    fun editPhonebookContact(rawPhone: String, newName: String) {
        viewModelScope.launch {
            val contactId = when (val lookup = PhoneContactsHelper.findContactIdByPhone(context, rawPhone)) {
                is PhoneContactsHelper.PhoneContactLookup.Found -> lookup.contactId
                PhoneContactsHelper.PhoneContactLookup.PermissionDenied -> {
                    _statusMessage.value = "اسمح بالوصول إلى جهات الاتصال أولاً من إعدادات التطبيق"
                    return@launch
                }
                // NotFound / NoPhone → insert branch; upsert validates the (now editable) phone.
                else -> null
            }
            PhoneContactsHelper.upsertDeviceContact(context, newName, rawPhone, contactId)
                .onSuccess { _statusMessage.value = "تم تحديث الاسم في دفتر الهاتف ✓" }
                .onFailure { _statusMessage.value = it.localizedMessage ?: "تعذر تحديث دفتر الهاتف" }
        }
    }

    fun deletePhonebookContact(rawPhone: String) {
        viewModelScope.launch {
            when (val lookup = PhoneContactsHelper.findContactIdByPhone(context, rawPhone)) {
                is PhoneContactsHelper.PhoneContactLookup.Found ->
                    // rawPhone is passed through so duplicate phone-book copies of the same
                    // number (Google / SIM / WhatsApp) are swept too — mirroring the
                    // contacts-screen delete flow in ContactsViewModel.
                    PhoneContactsHelper.deleteDeviceContact(context, lookup.contactId, rawPhone)
                        .onSuccess { _statusMessage.value = "تم حذف جهة الاتصال من دفتر الهاتف ✓" }
                        .onFailure { _statusMessage.value = it.localizedMessage ?: "تعذر حذف جهة الاتصال" }
                PhoneContactsHelper.PhoneContactLookup.NoPhone ->
                    // Honest message: the account exposes no number, so there is nothing to
                    // look up — the old code falsely claimed the number "is not saved".
                    _statusMessage.value = "رقم هاتف هذا الحساب غير ظاهر، فلا يوجد رقم لحذفه من دفتر الهاتف"
                PhoneContactsHelper.PhoneContactLookup.PermissionDenied ->
                    _statusMessage.value = "اسمح بالوصول إلى جهات الاتصال أولاً من إعدادات التطبيق"
                PhoneContactsHelper.PhoneContactLookup.NotFound ->
                    _statusMessage.value = "هذا الرقم غير محفوظ في دفتر الهاتف"
            }
        }
    }

    fun toggleBlock(currentlyBlocked: Boolean, isEn: Boolean) {
        viewModelScope.launch {
            val res = if (currentlyBlocked) userRepository.unblockUser(targetUid) else userRepository.blockUser(targetUid)
            res.onSuccess {
                _statusMessage.value = if (currentlyBlocked) {
                    if (isEn) "User unblocked" else "تم إلغاء حظر المستخدم"
                } else {
                    if (isEn) "User blocked" else "تم حظر المستخدم"
                }
            }
            // Audit B4: a failed block/unblock was completely silent.
            res.onFailure {
                _statusMessage.value = it.localizedMessage
                    ?: if (isEn) "Could not update the block state" else "تعذّر تحديث حالة الحظر"
            }
        }
    }

    fun reportUser(reason: String, isEn: Boolean) {
        viewModelScope.launch {
            userRepository.reportUser(targetUid, reason, targetType = "USER")
                .onSuccess {
                    _statusMessage.value = if (isEn) "Report submitted" else "تم إرسال البلاغ بنجاح"
                }
                // Audit B4: the dialog closed either way, so a failed report
                // looked exactly like a submitted one.
                .onFailure {
                    _statusMessage.value = it.localizedMessage
                        ?: if (isEn) "Could not submit the report" else "تعذّر إرسال البلاغ"
                }
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun UserProfileDetailScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    viewModel: UserProfileDetailViewModel = hiltViewModel()
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val context = LocalContext.current
    val peer by viewModel.peerUser.collectAsStateWithLifecycle()
    val me by viewModel.currentUser.collectAsStateWithLifecycle()
    val isSuperAdmin by viewModel.isSuperAdmin.collectAsStateWithLifecycle()
    val requests by viewModel.contactRequests.collectAsStateWithLifecycle()
    val contactLists by viewModel.contactLists.collectAsStateWithLifecycle()
    val directChat by viewModel.directChat.collectAsStateWithLifecycle()
    val mutualGroups by viewModel.mutualGroups.collectAsStateWithLifecycle()
    val sharedMessages by viewModel.sharedMessages.collectAsStateWithLifecycle()
    val statusMsg by viewModel.statusMessage.collectAsStateWithLifecycle()

    var fullscreenMediaUrl by remember { mutableStateOf<String?>(null) }
    var showReportDialog by remember { mutableStateOf(false) }
    var showTopMenu by remember { mutableStateOf(false) }
    var showSecurityCodeDialog by remember { mutableStateOf(false) }
    var showListsDialog by remember { mutableStateOf(false) }
    var showEditContactDialog by remember { mutableStateOf(false) }
    var showDeleteContactDialog by remember { mutableStateOf(false) }
    var showDisappearingDialog by remember { mutableStateOf(false) }
    var showClearChatDialog by remember { mutableStateOf(false) }
    var mediaTabIndex by remember { mutableIntStateOf(0) } // 0 = Media, 1 = Links, 2 = Docs

    val contactsWritePermissions = rememberMultiplePermissionsState(
        listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
    )

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(statusMsg) {
        statusMsg?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatusMessage()
        }
    }

    LaunchedEffect(peer?.uid) {
        if (peer != null) {
            viewModel.recordVisitOnce()
        }
    }

    val target = peer
    val showAvatar = target != null &&
        target.photoUrl.isNotBlank() &&
        !target.privacySettings.hideAvatarPhoto &&
        target.privacySettings.profilePhotoVisibility != PrivacyVisibility.NOBODY
    val showCover = target != null &&
        target.coverUrl.isNotBlank() &&
        !target.privacySettings.hideCoverPhoto

    if (!fullscreenMediaUrl.isNullOrBlank()) {
        FullscreenImageViewer(imageModel = fullscreenMediaUrl!!, onClose = { fullscreenMediaUrl = null })
        return
    }

    val req = remember(requests, target?.uid, viewModel.myUid) {
        if (target == null) null
        else requests.firstOrNull {
            (it.senderUid == target.uid && it.receiverUid == viewModel.myUid) ||
                (it.receiverUid == target.uid && it.senderUid == viewModel.myUid)
        }
    }

    val sharedMedia = remember(sharedMessages) {
        sharedMessages.filter {
            !it.isDeletedForEveryone && !it.isViewOnce &&
                (it.type == MessageType.IMAGE || it.type == MessageType.VIDEO) &&
                it.mediaUrl.isNotBlank()
        }
    }
    val sharedLinks = remember(sharedMessages) {
        sharedMessages.filter {
            !it.isDeletedForEveryone &&
                (it.text.contains("http://", ignoreCase = true) ||
                    it.text.contains("https://", ignoreCase = true) ||
                    it.text.contains("www.", ignoreCase = true))
        }
    }
    val sharedDocs = remember(sharedMessages) {
        sharedMessages.filter {
            !it.isDeletedForEveryone &&
                (it.type == MessageType.DOCUMENT || it.type == MessageType.AUDIO) &&
                it.mediaUrl.isNotBlank()
        }
    }

    val favoriteList = remember(contactLists) {
        contactLists.firstOrNull { it.name == "المفضلة" || it.name.equals("Favorites", ignoreCase = true) }
    }
    val isFavorite = favoriteList?.memberUids?.contains(target?.uid) == true

    fun openExternalUrl(rawUrl: String) {
        val clean = rawUrl.trim().let {
            if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "https://$it"
        }
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(clean)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    Scaffold(
        containerColor = palette.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (strings.isEnglish) "Account Details" else "بيانات الحساب", color = palette.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back, tint = palette.textPrimary)
                    }
                },
                actions = {
                    if (target != null && target.uid != viewModel.myUid) {
                        IconButton(onClick = { viewModel.toggleFavoriteInList(favoriteList) }) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = null,
                                tint = if (isFavorite) palette.warning else palette.textSecondary
                            )
                        }
                        Box {
                            IconButton(onClick = { showTopMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = strings.more, tint = palette.textPrimary)
                            }
                            DropdownMenu(
                                expanded = showTopMenu,
                                onDismissRequest = { showTopMenu = false },
                                containerColor = palette.surface
                            ) {
                                DropdownMenuItem(
                                    text = { Text(if (strings.isEnglish) "Share Contact" else "مشاركة جهة الاتصال", color = palette.textPrimary) },
                                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = palette.turquoise) },
                                    onClick = {
                                        showTopMenu = false
                                        val shareText = buildString {
                                            append(target.displayName.ifBlank { "WESAL User" })
                                            if (target.numericId.isNotBlank()) append("\nID: ${target.numericId}")
                                            if (target.publicId.isNotBlank()) append("\n@${target.publicId}")
                                            append("\n${WesalShareUtils.buildSingleInviteLink(target.publicId)}")
                                        }
                                        WesalShareUtils.shareGeneral(context, shareText)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (strings.isEnglish) "Edit Name in Phonebook" else "تعديل الاسم في دفتر الهاتف", color = palette.textPrimary) },
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = palette.emerald) },
                                    onClick = {
                                        showTopMenu = false
                                        if (!contactsWritePermissions.allPermissionsGranted) {
                                            contactsWritePermissions.launchMultiplePermissionRequest()
                                        } else {
                                            showEditContactDialog = true
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (strings.isEnglish) "Delete from Address Book" else "حذف من دفتر العناوين", color = palette.error) },
                                    leadingIcon = { Icon(Icons.Default.PersonRemove, contentDescription = null, tint = palette.error) },
                                    onClick = {
                                        showTopMenu = false
                                        if (!contactsWritePermissions.allPermissionsGranted) {
                                            contactsWritePermissions.launchMultiplePermissionRequest()
                                        } else {
                                            showDeleteContactDialog = true
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (strings.isEnglish) "Verify Security Code" else "التحقق من رمز الأمان (E2EE)", color = palette.textPrimary) },
                                    leadingIcon = { Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = palette.turquoise) },
                                    onClick = {
                                        showTopMenu = false
                                        showSecurityCodeDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (strings.isEnglish) "Add to List" else "إضافة إلى القائمة", color = palette.textPrimary) },
                                    leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null, tint = palette.emerald) },
                                    onClick = {
                                        showTopMenu = false
                                        showListsDialog = true
                                    }
                                )
                                // Audit B4 (owner: from the ⋮ menu «أقدر احذفهم
                                // أقدر أضيفهم — كل حاجة»): remove-contact, block
                                // and report used to live only far down the page
                                // (remove-contact nowhere at all). Same handlers
                                // as the in-page rows — real Firestore effects.
                                if (req != null) {
                                    val currentReqId = req.requestId
                                    DropdownMenuItem(
                                        text = { Text(if (strings.isEnglish) "Remove Contact" else "إزالة جهة الاتصال", color = palette.error) },
                                        leadingIcon = { Icon(Icons.Default.PersonRemove, contentDescription = null, tint = palette.error) },
                                        onClick = {
                                            showTopMenu = false
                                            viewModel.removeContactRequest(currentReqId, strings.isEnglish)
                                        }
                                    )
                                }
                                val menuIsBlocked = me?.blockedUsers?.contains(target.uid) == true
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (menuIsBlocked) {
                                                if (strings.isEnglish) "Unblock User" else "إلغاء حظر المستخدم"
                                            } else {
                                                if (strings.isEnglish) "Block User" else "حظر المستخدم"
                                            },
                                            color = if (menuIsBlocked) palette.emerald else palette.error
                                        )
                                    },
                                    leadingIcon = { Icon(Icons.Default.Block, contentDescription = null, tint = if (menuIsBlocked) palette.emerald else palette.error) },
                                    onClick = {
                                        showTopMenu = false
                                        viewModel.toggleBlock(menuIsBlocked, strings.isEnglish)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (strings.isEnglish) "Report User" else "الإبلاغ عن المستخدم", color = palette.warning) },
                                    leadingIcon = { Icon(Icons.Default.Report, contentDescription = null, tint = palette.warning) },
                                    onClick = {
                                        showTopMenu = false
                                        showReportDialog = true
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.surface)
            )
        }
    ) { padding ->
        if (target == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = palette.turquoise)
            }
        } else if ((target.hiddenFromSearch && !isSuperAdmin && target.uid != viewModel.myUid) || target.deletedAt > 0L || target.deletedPermanent) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.PersonOff, contentDescription = null, tint = palette.textMuted, modifier = Modifier.size(56.dp))
                    Text(
                        text = if (strings.isEnglish) "This account is currently unavailable" else "هذا الحساب غير متاح حاليًا",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.textSecondary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        } else {
            val isBlocked = me?.blockedUsers?.contains(target.uid) == true
            val canSeeOnline = target.privacySettings.onlineVisibility != PrivacyVisibility.NOBODY
            val canSeeLastSeen = target.privacySettings.lastSeenVisibility != PrivacyVisibility.NOBODY
            val presenceLabel = when {
                canSeeOnline && target.isOnline -> strings.onlineNow
                canSeeLastSeen && target.lastSeen > 0L -> DateTimeUtils.formatChatListTime(target.lastSeen)
                else -> if (strings.isEnglish) "Privacy enabled" else "الخصوصية مفعّلة"
            }
            val neonColor = target.neonColor
            val neonGlow = target.neonGlowIntensity
            val canMessageDirectly = isSuperAdmin ||
                target.numericId == Constants.OWNER_ADMIN_NUMERIC_ID ||
                req?.status == ContactRequestStatus.ACCEPTED

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val detailScope = rememberCoroutineScope()
                // Cover header: round 7 — the cover is ONE clean image
                // filling its band edge-to-edge (Crop, top-biased; no
                // blurred ghost backdrop, no scrim), and the framed avatar
                // block sits fully BELOW it with a clear gap strip — no
                // overlap over the cover.
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            palette.surfaceElevated,
                                            palette.emerald.copy(alpha = 0.22f),
                                            palette.turquoise.copy(alpha = 0.20f)
                                        )
                                    )
                                )
                        ) {
                            if (showCover) {
                                // Round 9 (item 9 — SUPERSEDES the round-7
                                // Crop instruction): the COMPLETE cover photo
                                // shows via ContentScale.Fit, uncropped and
                                // undistorted; the band's palette gradient
                                // (on the Box above) fills any leftover space.
                                // No blurred ghost copy, no scrim (round-7
                                // rejection stands). The fullscreen tap rides
                                // this single image.
                                AsyncImage(
                                    model = wesalImageModel(target.coverUrl),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable { fullscreenMediaUrl = target.coverUrl },
                                    contentScale = ContentScale.Fit
                                )
                                Surface(
                                    color = palette.background.copy(alpha = 0.72f),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                        .clickable {
                                            detailScope.launch {
                                                saveImageUrlToGallery(context, target.coverUrl, "WESAL_COVER")
                                                    .onSuccess {
                                                        Toast.makeText(
                                                            context,
                                                            if (strings.isEnglish) "Cover saved ✓" else "تم حفظ صورة الغلاف ✓",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                            }
                                        }
                                ) {
                                    Icon(
                                        Icons.Default.Download,
                                        contentDescription = if (strings.isEnglish) "Save cover" else "حفظ الغلاف",
                                        tint = palette.turquoise,
                                        modifier = Modifier.padding(6.dp).size(18.dp)
                                    )
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 26.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = if (target.privacySettings.hideCoverPhoto) Icons.Default.Lock else Icons.Default.Wallpaper,
                                        contentDescription = null,
                                        tint = palette.turquoise.copy(alpha = 0.65f),
                                        modifier = Modifier.size(26.dp)
                                    )
                                    Text(
                                        text = if (target.privacySettings.hideCoverPhoto) {
                                            if (strings.isEnglish) "Cover photo hidden" else "صورة الغلاف مخفية"
                                        } else {
                                            "WESAL Messenger"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = palette.textSecondary.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                        // FIX 2 (b): clear gap strip — the framed avatar
                        // block below starts fully under the cover.
                        Spacer(Modifier.height(20.dp))

                    Box(modifier = Modifier.clickable(enabled = showAvatar) { fullscreenMediaUrl = target.photoUrl }) {
                        WesalAvatar(
                            imageUrl = if (showAvatar) target.photoUrl else "",
                            name = target.displayName.ifBlank { target.publicId },
                            size = 118.dp,
                            isOnline = canSeeOnline && target.isOnline,
                            frameId = target.frameId,
                            frameExpiresAt = target.frameExpiresAt,
                            isCutout = target.photoRemovedBg,
                            isUserFramed = target.photoUserFramed,
                            iconUrl = com.wesal.messenger.core.util.WesalFrameLibrary.resolveIconUrl(target.iconId, target.iconUrl),
                            neonColor = if (target.neonEnabled) target.neonColor else "",
                            neonGlowIntensity = neonGlow
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    WesalRoyalNameplateBanner(
                        displayName = target.displayName.ifBlank { "@${target.publicId}" },
                        numericId = target.numericId,
                        isVerified = target.isVerified,
                        verificationType = target.verificationType,
                        verificationGlowColor = target.verificationGlowColor,
                        verificationGlowEnabled = target.verificationGlowEnabled,
                        verificationGlowIntensity = target.verificationGlowIntensity,
                        neonColor = neonColor,
                        neonGlowIntensity = neonGlow,
                        neonEnabled = target.neonEnabled,
                        nameplateColorKey = target.nameplateColor,
                        iconUrl = com.wesal.messenger.core.util.WesalFrameLibrary.resolveIconUrl(target.iconId, target.iconUrl)
                    )
                    if (target.publicId.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.clickable {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("wesal_id", "@${target.publicId}"))
                                Toast.makeText(context, "@${target.publicId}", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(
                                    text = "@${target.publicId}",
                                    style = wesalNeonTextStyle(MaterialTheme.typography.titleMedium, neonColor, neonGlow, palette.textSecondary),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Icon(Icons.Default.ContentCopy, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(15.dp))
                        }
                    }
                    Text(
                        text = presenceLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (canSeeOnline && target.isOnline) palette.emerald else palette.textSecondary
                    )
                }

                if (target.uid != viewModel.myUid) {
                    when {
                        canMessageDirectly -> {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { viewModel.startDirectChat(onOpenChat) },
                                    modifier = Modifier.weight(1.1f).height(46.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onAccent)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (strings.isEnglish) "Message" else "مراسلة", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                }
                                OutlinedButton(
                                    onClick = { context.startActivity(CallActivity.createOutgoingIntent(context, target, isVideo = false)) },
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Call, contentDescription = null, tint = palette.emerald, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (strings.isEnglish) "Call" else "اتصال", color = palette.textPrimary, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                }
                                OutlinedButton(
                                    onClick = { context.startActivity(CallActivity.createOutgoingIntent(context, target, isVideo = true)) },
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Videocam, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (strings.isEnglish) "Video" else "فيديو", color = palette.textPrimary, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        req?.status == ContactRequestStatus.PENDING && req.receiverUid == viewModel.myUid -> {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { viewModel.acceptContactRequest(req.requestId) },
                                    colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onAccent),
                                    modifier = Modifier.weight(1f)
                                ) { Text(strings.acceptContactRequest, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
                                OutlinedButton(
                                    onClick = { viewModel.rejectContactRequest(req.requestId) },
                                    modifier = Modifier.weight(1f)
                                ) { Text(strings.rejectContactRequest, color = palette.error, maxLines = 1, softWrap = false) }
                            }
                        }
                        req?.status == ContactRequestStatus.PENDING && req.senderUid == viewModel.myUid -> {
                            Surface(color = palette.surfaceElevated, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.HourglassTop, contentDescription = null, tint = palette.turquoise)
                                    Spacer(Modifier.width(8.dp))
                                    Text(strings.pendingRequestSent, color = palette.turquoise, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        else -> {
                            Button(
                                onClick = { viewModel.sendContactRequest() },
                                colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onAccent),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Default.PersonAdd, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(strings.sendContactRequest, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Public Info Card
                Surface(
                    color = palette.surface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, palette.border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Round 9 (owner rule): a phone number is visible ONLY to
                        // its owner — another user's profile never renders it,
                        // not even masked. People reach each other via
                        // @publicId + numeric ID, so this card starts with the
                        // birth date / bio.
                        if (!target.privacySettings.hideBirthDate && target.birthDate.isNotBlank()) {
                            Text(if (strings.isEnglish) "Birth Date" else "تاريخ الميلاد", style = MaterialTheme.typography.labelMedium, color = palette.turquoise)
                            Text(target.birthDate, style = MaterialTheme.typography.bodyMedium, color = palette.textPrimary)
                            HorizontalDivider(color = palette.border)
                        }

                        Text(if (strings.isEnglish) "Bio" else "نبذة عن الحساب", style = MaterialTheme.typography.labelMedium, color = palette.turquoise)
                        Text(
                            text = target.bio.ifBlank { "WESAL Messenger" },
                            style = wesalNeonTextStyle(MaterialTheme.typography.bodyLarge, neonColor, neonGlow, palette.textPrimary)
                        )
                    }
                }

                // Strictly Filtered Shared Media / Links / Docs Section
                if (directChat != null) {
                    Surface(
                        color = palette.surface,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, palette.border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = if (strings.isEnglish) "Shared Content" else "الوسائط والروابط والمستندات",
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.turquoise,
                                fontWeight = FontWeight.Bold
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    0 to "${if (strings.isEnglish) "Media" else "الوسائط"} (${sharedMedia.size})",
                                    1 to "${if (strings.isEnglish) "Links" else "الروابط"} (${sharedLinks.size})",
                                    2 to "${if (strings.isEnglish) "Docs" else "المستندات"} (${sharedDocs.size})"
                                ).forEach { (idx, title) ->
                                    FilterChip(
                                        selected = mediaTabIndex == idx,
                                        onClick = { mediaTabIndex = idx },
                                        label = { Text(title, maxLines = 1) }
                                    )
                                }
                            }
                            when (mediaTabIndex) {
                                0 -> {
                                    if (sharedMedia.isEmpty()) {
                                        Text(
                                            if (strings.isEnglish) "No shared photos or videos yet." else "لا توجد صور أو فيديوهات مشتركة بعد.",
                                            color = palette.textMuted,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    } else {
                                        sharedMedia.takeLast(6).reversed().forEach { item ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable { onOpenChat(directChat!!.chatId) }
                                                    .padding(vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (item.type == MessageType.VIDEO) Icons.Default.Videocam else Icons.Default.Image,
                                                    contentDescription = null,
                                                    tint = palette.emerald
                                                )
                                                Text(
                                                    text = item.mediaFileName.ifBlank { item.text.ifBlank { item.type.raw } },
                                                    color = palette.textPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Text(
                                                    DateTimeUtils.formatChatListTime(item.timestamp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = palette.textSecondary
                                                )
                                            }
                                        }
                                    }
                                }
                                1 -> {
                                    if (sharedLinks.isEmpty()) {
                                        Text(
                                            if (strings.isEnglish) "No shared links yet." else "لا توجد روابط مشتركة بعد.",
                                            color = palette.textMuted,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    } else {
                                        sharedLinks.takeLast(6).reversed().forEach { item ->
                                            Text(
                                                text = item.text,
                                                color = palette.turquoise,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { openExternalUrl(item.text.trim()) }
                                                    .padding(vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                                else -> {
                                    if (sharedDocs.isEmpty()) {
                                        Text(
                                            if (strings.isEnglish) "No shared documents yet." else "لا توجد مستندات مشتركة بعد.",
                                            color = palette.textMuted,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    } else {
                                        sharedDocs.takeLast(6).reversed().forEach { item ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { onOpenChat(directChat!!.chatId) }
                                                    .padding(vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = palette.turquoise)
                                                Text(
                                                    text = item.mediaFileName.ifBlank { "Document" },
                                                    color = palette.textPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Social Links
                if (target.facebookUrl.isNotBlank() || target.instagramUrl.isNotBlank()) {
                    Surface(
                        color = palette.surface,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, palette.border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = if (strings.isEnglish) "Social Links" else "روابط التواصل الاجتماعي",
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.turquoise
                            )
                            if (target.facebookUrl.isNotBlank()) {
                                OutlinedButton(
                                    onClick = { openExternalUrl(target.facebookUrl) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Public, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Facebook", color = palette.textPrimary, modifier = Modifier.weight(1f))
                                    Icon(Icons.Default.OpenInNew, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(16.dp))
                                }
                            }
                            if (target.instagramUrl.isNotBlank()) {
                                OutlinedButton(
                                    onClick = { openExternalUrl(target.instagramUrl) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Instagram", color = palette.textPrimary, modifier = Modifier.weight(1f))
                                    Icon(Icons.Default.OpenInNew, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                // Business Hours Display
                if (target.businessHours.enabled) {
                    val isOpenNow = remember(target.businessHours) { computeIsOpenNow(target.businessHours) }
                    Surface(
                        color = palette.surface,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, palette.border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = target.businessHours.businessName.ifBlank {
                                        if (strings.isEnglish) "Business Hours" else "ساعات العمل"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = palette.textPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Surface(
                                    color = (if (isOpenNow) palette.emerald else palette.error).copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = if (isOpenNow) {
                                            if (strings.isEnglish) "Open Now" else "مفتوح الآن"
                                        } else {
                                            if (strings.isEnglish) "Closed Now" else "مغلق الآن"
                                        },
                                        color = if (isOpenNow) palette.emerald else palette.error,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            target.businessHours.days.forEach { d ->
                                val dayName = when (d.dayKey) {
                                    "SAT" -> if (strings.isEnglish) "Sat" else "السبت"
                                    "SUN" -> if (strings.isEnglish) "Sun" else "الأحد"
                                    "MON" -> if (strings.isEnglish) "Mon" else "الاثنين"
                                    "TUE" -> if (strings.isEnglish) "Tue" else "الثلاثاء"
                                    "WED" -> if (strings.isEnglish) "Wed" else "الأربعاء"
                                    "THU" -> if (strings.isEnglish) "Thu" else "الخميس"
                                    else -> if (strings.isEnglish) "Fri" else "الجمعة"
                                }
                                val hoursText = when {
                                    d.isClosed -> if (strings.isEnglish) "Closed" else "مغلق"
                                    d.isOpen24Hours -> if (strings.isEnglish) "24 Hours" else "مفتوح 24 ساعة"
                                    else -> d.slots.joinToString(" , ") { "${it.openTime} - ${it.closeTime}" }
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(dayName, style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                                    Text(hoursText, style = MaterialTheme.typography.bodySmall, color = palette.textPrimary)
                                }
                            }
                        }
                    }
                }

                // Mutual Groups
                if (mutualGroups.isNotEmpty()) {
                    Surface(
                        color = palette.surface,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, palette.border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = if (strings.isEnglish) "${mutualGroups.size} Mutual Groups" else "${mutualGroups.size} مجموعات مشتركة",
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.turquoise,
                                fontWeight = FontWeight.Bold
                            )
                            mutualGroups.forEach { grp ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onOpenChat(grp.chatId) }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    WesalAvatar(imageUrl = grp.avatarUrl, name = grp.title, size = 36.dp)
                                    Text(grp.title, color = palette.textPrimary, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = palette.textSecondary)
                                }
                            }
                        }
                    }
                }

                // Chat Controls & Safety Controls (Mute, Disappearing, Clear, Block, Report)
                if (target.uid != viewModel.myUid) {
                    Surface(
                        color = palette.surface,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, palette.border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            if (directChat != null) {
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            if (directChat!!.isMuted) {
                                                if (strings.isEnglish) "Unmute Notifications" else "إلغاء كتم الإشعارات"
                                            } else {
                                                if (strings.isEnglish) "Mute Notifications" else "كتم الإشعارات"
                                            },
                                            color = palette.textPrimary
                                        )
                                    },
                                    leadingContent = {
                                        Icon(
                                            if (directChat!!.isMuted) Icons.Default.NotificationsOff else Icons.Default.NotificationsActive,
                                            contentDescription = null,
                                            tint = palette.turquoise
                                        )
                                    },
                                    modifier = Modifier.clickable { viewModel.toggleMuteDirectChat() },
                                    colors = ListItemDefaults.colors(containerColor = palette.surface)
                                )
                                HorizontalDivider(color = palette.border, thickness = 0.5.dp)
                                val currentTimer = DisappearingTimer.fromSeconds(directChat!!.disappearingDurationSec)
                                ListItem(
                                    headlineContent = { Text(if (strings.isEnglish) "Disappearing Messages" else "الرسائل ذاتية الاختفاء", color = palette.textPrimary) },
                                    supportingContent = { Text(if (strings.isEnglish) currentTimer.labelEn else currentTimer.labelAr, color = palette.textSecondary) },
                                    leadingContent = { Icon(Icons.Default.Timer, contentDescription = null, tint = palette.emerald) },
                                    modifier = Modifier.clickable { showDisappearingDialog = true },
                                    colors = ListItemDefaults.colors(containerColor = palette.surface)
                                )
                                HorizontalDivider(color = palette.border, thickness = 0.5.dp)
                                ListItem(
                                    headlineContent = { Text(if (strings.isEnglish) "Clear Chat History" else "مسح سجل الدردشة لدي", color = palette.warning) },
                                    leadingContent = { Icon(Icons.Default.CleaningServices, contentDescription = null, tint = palette.warning) },
                                    modifier = Modifier.clickable { showClearChatDialog = true },
                                    colors = ListItemDefaults.colors(containerColor = palette.surface)
                                )
                                HorizontalDivider(color = palette.border, thickness = 0.5.dp)
                            }
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = if (isBlocked) (if (strings.isEnglish) "Unblock User" else "إلغاء حظر المستخدم") else (if (strings.isEnglish) "Block User" else "حظر المستخدم"),
                                        color = if (isBlocked) palette.emerald else palette.error
                                    )
                                },
                                leadingContent = { Icon(Icons.Default.Block, contentDescription = null, tint = if (isBlocked) palette.emerald else palette.error) },
                                modifier = Modifier.clickable { viewModel.toggleBlock(isBlocked, strings.isEnglish) },
                                colors = ListItemDefaults.colors(containerColor = palette.surface)
                            )
                            HorizontalDivider(color = palette.border, thickness = 0.5.dp)
                            ListItem(
                                headlineContent = { Text(if (strings.isEnglish) "Report User" else "الإبلاغ عن المستخدم", color = palette.warning) },
                                leadingContent = { Icon(Icons.Default.Report, contentDescription = null, tint = palette.warning) },
                                modifier = Modifier.clickable { showReportDialog = true },
                                colors = ListItemDefaults.colors(containerColor = palette.surface)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSecurityCodeDialog && target != null) {
        val rawDigest = remember(viewModel.myUid, target.uid, target.publicKey) {
            val pair = listOf(viewModel.myUid, target.uid).sorted().joinToString("|") + "|" + target.publicKey
            PhoneUtils.sha256Hash(pair)
        }
        val numericBlocks = remember(rawDigest) {
            val digits = rawDigest.map { (it.code % 10).digitToChar() }.joinToString("")
                .padEnd(60, '0')
                .take(60)
            digits.chunked(5).chunked(4).joinToString("\n") { it.joinToString("  ") }
        }
        AlertDialog(
            onDismissRequest = { showSecurityCodeDialog = false },
            containerColor = palette.surface,
            title = {
                Text(
                    if (strings.isEnglish) "Verify Security Code" else "التحقق من رمز الأمان",
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    WesalQrCodeBox(payload = "WESAL-E2EE:${rawDigest.take(36)}", size = 180.dp)
                    Text(
                        text = numericBlocks,
                        fontFamily = FontFamily.Monospace,
                        color = palette.turquoise,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = if (strings.isEnglish) {
                            "Compare this SHA-256 fingerprint or scan the QR with your contact to verify end-to-end encryption."
                        } else {
                            "قارن هذا الرمز أو امسح كود QR مع الطرف الآخر للتحقق من تشفير المحادثة من طرف إلى طرف."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSecurityCodeDialog = false }) { Text(strings.close) }
            }
        )
    }

    if (showListsDialog && target != null) {
        var newListName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showListsDialog = false },
            containerColor = palette.surface,
            title = {
                Text(
                    if (strings.isEnglish) "Add to Custom List" else "إضافة إلى القوائم المخصصة",
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newListName,
                            onValueChange = { newListName = it },
                            label = { Text(if (strings.isEnglish) "New list name" else "اسم قائمة جديدة") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                viewModel.createListAndAdd(newListName)
                                newListName = ""
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary)
                        ) { Text(if (strings.isEnglish) "Add" else "إضافة") }
                    }
                    contactLists.forEach { list ->
                        val isIn = target.uid in list.memberUids
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { viewModel.addOrRemoveCustomList(list) }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(list.name, color = palette.textPrimary, fontWeight = FontWeight.Medium)
                            Checkbox(checked = isIn, onCheckedChange = { viewModel.addOrRemoveCustomList(list) })
                        }
                    }
                    if (contactLists.isEmpty()) {
                        listOf("المفضلة", "العائلة", "الأصدقاء", "العمل").forEach { preset ->
                            OutlinedButton(
                                onClick = { viewModel.createListAndAdd(preset) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("+ $preset", color = palette.turquoise)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showListsDialog = false }) { Text(strings.close) }
            }
        )
    }

    if (showEditContactDialog && target != null) {
        var nameInput by remember { mutableStateOf(target.displayName) }
        // Round 3: the phone is a real, editable field — the old dialog had NO phone
        // field at all, so the insert path's «رقم الهاتف مطلوب» validation could never
        // be satisfied from this screen.
        // Round 9 (owner rule): the field is NOT prefilled from the account's doc
        // number anymore — another user's number is never shown to anyone but its
        // owner. The viewer types a number they already know; a blank phone fails
        // the upsert validation with an honest message instead of leaking the number.
        var phoneInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showEditContactDialog = false },
            containerColor = palette.surface,
            title = { Text(if (strings.isEnglish) "Edit Phonebook Name" else "تعديل الاسم في دفتر الهاتف", color = palette.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text(if (strings.isEnglish) "Contact Name" else "اسم جهة الاتصال") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = phoneInput,
                        onValueChange = { phoneInput = it },
                        label = { Text(if (strings.isEnglish) "Phone Number" else "رقم الهاتف") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.editPhonebookContact(phoneInput, nameInput)
                        showEditContactDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary)
                ) { Text(strings.save) }
            },
            dismissButton = { TextButton(onClick = { showEditContactDialog = false }) { Text(strings.cancel) } }
        )
    }

    if (showDeleteContactDialog && target != null) {
        AlertDialog(
            onDismissRequest = { showDeleteContactDialog = false },
            containerColor = palette.surface,
            title = { Text(if (strings.isEnglish) "Delete Contact" else "حذف من دفتر العناوين", color = palette.error) },
            text = {
                Text(
                    if (strings.isEnglish) "Remove ${target.displayName} from your phone address book?" else "هل تريد حذف ${target.displayName} من دفتر العناوين على جهازك؟",
                    color = palette.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deletePhonebookContact(target.phoneNumber)
                        showDeleteContactDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.error)
                ) { Text(strings.delete) }
            },
            dismissButton = { TextButton(onClick = { showDeleteContactDialog = false }) { Text(strings.cancel) } }
        )
    }

    if (showDisappearingDialog && directChat != null) {
        AlertDialog(
            onDismissRequest = { showDisappearingDialog = false },
            containerColor = palette.surface,
            title = { Text(if (strings.isEnglish) "Disappearing Messages" else "مؤقت اختفاء الرسائل", color = palette.textPrimary) },
            text = {
                Column {
                    DisappearingTimer.entries.forEach { timer ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setDisappearingTimer(timer)
                                    showDisappearingDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RadioButton(
                                selected = directChat!!.disappearingDurationSec == timer.seconds,
                                onClick = {
                                    viewModel.setDisappearingTimer(timer)
                                    showDisappearingDialog = false
                                }
                            )
                            Text(if (strings.isEnglish) timer.labelEn else timer.labelAr, color = palette.textPrimary)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDisappearingDialog = false }) { Text(strings.close) } }
        )
    }

    if (showClearChatDialog) {
        AlertDialog(
            onDismissRequest = { showClearChatDialog = false },
            containerColor = palette.surface,
            title = { Text(if (strings.isEnglish) "Clear Chat" else "مسح سجل الدردشة", color = palette.warning) },
            text = {
                Text(
                    if (strings.isEnglish) "Clear all messages in this chat from your view?" else "هل تريد مسح جميع الرسائل من هذه المحادثة لديك؟",
                    color = palette.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearDirectChatMessages()
                        showClearChatDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.warning, contentColor = palette.onPrimary)
                ) { Text(strings.clear) }
            },
            dismissButton = { TextButton(onClick = { showClearChatDialog = false }) { Text(strings.cancel) } }
        )
    }

    if (showReportDialog) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            containerColor = palette.surface,
            title = { Text(if (strings.isEnglish) "Report Abuse" else "إبلاغ عن إساءة", color = palette.textPrimary) },
            text = {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(if (strings.isEnglish) "Reason" else "سبب البلاغ") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = palette.textPrimary,
                        unfocusedTextColor = palette.textPrimary,
                        focusedLabelColor = palette.turquoise,
                        unfocusedLabelColor = palette.textSecondary,
                        focusedBorderColor = palette.turquoise,
                        unfocusedBorderColor = palette.border,
                        cursorColor = palette.turquoise
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.reportUser(reason.ifBlank { "Abuse" }, strings.isEnglish)
                        showReportDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.warning, contentColor = palette.onAccent)
                ) { Text(strings.send) }
            },
            dismissButton = { TextButton(onClick = { showReportDialog = false }) { Text(strings.cancel) } }
        )
    }
}

private fun computeIsOpenNow(schedule: BusinessHoursSchedule): Boolean {
    if (!schedule.enabled) return false
    return runCatching {
        val zone = ZoneId.of(schedule.timeZoneId.ifBlank { ZoneId.systemDefault().id })
        val now = ZonedDateTime.now(zone)
        val key = when (now.dayOfWeek) {
            DayOfWeek.SATURDAY -> "SAT"
            DayOfWeek.SUNDAY -> "SUN"
            DayOfWeek.MONDAY -> "MON"
            DayOfWeek.TUESDAY -> "TUE"
            DayOfWeek.WEDNESDAY -> "WED"
            DayOfWeek.THURSDAY -> "THU"
            DayOfWeek.FRIDAY -> "FRI"
        }
        val day = schedule.days.firstOrNull { it.dayKey == key } ?: return false
        if (day.isClosed) return false
        if (day.isOpen24Hours) return true
        val currentTime = now.toLocalTime()
        day.slots.any { slot ->
            val open = LocalTime.parse(slot.openTime.trim())
            val close = LocalTime.parse(slot.closeTime.trim())
            if (close.isAfter(open)) {
                !currentTime.isBefore(open) && currentTime.isBefore(close)
            } else {
                !currentTime.isBefore(open) || currentTime.isBefore(close)
            }
        }
    }.getOrDefault(false)
}

