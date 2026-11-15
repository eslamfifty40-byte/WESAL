package com.wesal.messenger.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wesal.messenger.calls.CallActivity
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.domain.model.ContactRequest
import com.wesal.messenger.domain.model.ContactRequestStatus
import com.wesal.messenger.domain.model.PrivacyVisibility
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.ChatRepository
import com.wesal.messenger.domain.repository.UserRepository
import com.wesal.messenger.media.FullscreenImageViewer
import com.wesal.messenger.presentation.components.WesalAvatar
import com.wesal.messenger.presentation.components.WesalNameWithBadge
import com.wesal.messenger.security.E2EECryptoManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UserProfileDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val userRepository: UserRepository,
    private val chatRepository: ChatRepository,
    private val authRepository: AuthRepository,
    private val cryptoManager: E2EECryptoManager
) : ViewModel() {

    val targetUid: String = checkNotNull(savedStateHandle["uid"])
    val myUid: String
        get() = authRepository.currentUserId.orEmpty()

    val peerUser: StateFlow<WesalUser?> = userRepository.observeUser(targetUid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val currentUser: StateFlow<WesalUser?> = userRepository.observeCurrentUser()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val contactRequests: StateFlow<List<ContactRequest>> = userRepository.observeContactRequests()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    fun computeFingerprintAndVerify(peer: WesalUser): Pair<String, Boolean>? {
        if (peer.publicKey.isBlank() || myUid.isBlank()) return null
        val sorted = listOf(myUid, peer.uid).sorted()
        val dmId = "dm_${sorted[0]}_${sorted[1]}"
        val fp = runCatching { cryptoManager.computeSafetyFingerprint(peer.publicKey) }.getOrNull() ?: return null
        val ok = cryptoManager.verifyChannelEncryption(peer.publicKey, dmId)
        return fp to ok
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
        }
    }

    fun reportUser(reason: String, isEn: Boolean) {
        viewModelScope.launch {
            userRepository.reportUser(targetUid, reason).onSuccess {
                _statusMessage.value = if (isEn) "Report submitted" else "تم إرسال البلاغ بنجاح"
            }
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    val requests by viewModel.contactRequests.collectAsStateWithLifecycle()
    val statusMsg by viewModel.statusMessage.collectAsStateWithLifecycle()

    var showFullscreenAvatar by remember { mutableStateOf(false) }
    var showFingerprintDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(statusMsg) {
        statusMsg?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatusMessage()
        }
    }

    val target = peer
    val showAvatar = target != null &&
        target.photoUrl.isNotBlank() &&
        target.privacySettings.profilePhotoVisibility != PrivacyVisibility.NOBODY

    if (showFullscreenAvatar && showAvatar && target != null) {
        FullscreenImageViewer(imageModel = target.photoUrl, onClose = { showFullscreenAvatar = false })
        return
    }

    val req = remember(requests, target?.uid, viewModel.myUid) {
        if (target == null) null
        else requests.firstOrNull {
            (it.senderUid == target.uid && it.receiverUid == viewModel.myUid) ||
                (it.receiverUid == target.uid && it.senderUid == viewModel.myUid)
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.surface)
            )
        }
    ) { padding ->
        if (target == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = palette.turquoise)
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

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(modifier = Modifier.clickable(enabled = showAvatar) { showFullscreenAvatar = true }) {
                    WesalAvatar(
                        imageUrl = if (showAvatar) target.photoUrl else "",
                        name = target.displayName.ifBlank { target.publicId },
                        size = 118.dp,
                        isOnline = canSeeOnline && target.isOnline
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    WesalNameWithBadge(
                        name = target.displayName.ifBlank { "@${target.publicId}" },
                        isVerified = target.isVerified,
                        style = MaterialTheme.typography.headlineSmall,
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    if (target.numericId.isNotBlank()) {
                        Surface(
                            color = palette.surfaceElevated,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, palette.turquoise.copy(alpha = 0.4f)),
                            modifier = Modifier.clickable {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("wesal_numeric_id", target.numericId))
                                Toast.makeText(context, "${strings.wesalNumericIdCopied} ${target.numericId}", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "ID: ${target.numericId}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = palette.turquoise,
                                    fontWeight = FontWeight.Bold
                                )
                                Icon(Icons.Default.ContentCopy, contentDescription = strings.copy, tint = palette.turquoise, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
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
                            Text("@${target.publicId}", style = MaterialTheme.typography.titleMedium, color = palette.textSecondary, fontWeight = FontWeight.SemiBold)
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
                        req?.status == ContactRequestStatus.ACCEPTED -> {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { viewModel.startDirectChat(onOpenChat) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onAccent)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (strings.isEnglish) "Message" else "مراسلة", fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = { context.startActivity(CallActivity.createOutgoingIntent(context, target, isVideo = false)) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Icon(Icons.Default.Call, contentDescription = null, tint = palette.emerald, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (strings.isEnglish) "Call" else "اتصال", color = palette.textPrimary)
                                }
                                OutlinedButton(
                                    onClick = { context.startActivity(CallActivity.createOutgoingIntent(context, target, isVideo = true)) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Icon(Icons.Default.Videocam, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (strings.isEnglish) "Video" else "فيديو", color = palette.textPrimary)
                                }
                            }
                        }
                        req?.status == ContactRequestStatus.PENDING && req.receiverUid == viewModel.myUid -> {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { viewModel.acceptContactRequest(req.requestId) },
                                    colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onAccent),
                                    modifier = Modifier.weight(1f)
                                ) { Text(strings.acceptContactRequest, fontWeight = FontWeight.Bold) }
                                OutlinedButton(
                                    onClick = { viewModel.rejectContactRequest(req.requestId) },
                                    modifier = Modifier.weight(1f)
                                ) { Text(strings.rejectContactRequest, color = palette.error) }
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

                // Phone card strictly respects hidePhoneNumber privacy
                Surface(
                    color = palette.surface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, palette.border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(strings.phoneNumberLabel, style = MaterialTheme.typography.labelMedium, color = palette.turquoise)
                        Text(
                            text = if (target.privacySettings.hidePhoneNumber || target.phoneNumber.isBlank()) {
                                strings.phoneHiddenValue
                            } else {
                                target.phoneNumber
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textPrimary
                        )
                        HorizontalDivider(color = palette.border)
                        Text(if (strings.isEnglish) "Bio" else "نبذة عن الحساب", style = MaterialTheme.typography.labelMedium, color = palette.turquoise)
                        Text(target.bio.ifBlank { "WESAL Messenger" }, style = MaterialTheme.typography.bodyLarge, color = palette.textPrimary)
                    }
                }

                if (target.uid != viewModel.myUid) {
                    Surface(
                        color = palette.surface,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, palette.border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            ListItem(
                                headlineContent = { Text(if (strings.isEnglish) "E2EE Security Fingerprint" else "التشفير الطرفي وبصمة الأمان", color = palette.textPrimary) },
                                supportingContent = { Text("ECDH P-256 + AES-256-GCM", color = palette.textSecondary) },
                                leadingContent = { Icon(Icons.Default.Lock, contentDescription = null, tint = palette.turquoise) },
                                modifier = Modifier.clickable { showFingerprintDialog = true },
                                colors = ListItemDefaults.colors(containerColor = palette.surface)
                            )
                            HorizontalDivider(color = palette.border, thickness = 0.5.dp)
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

    if (showFingerprintDialog && target != null) {
        val result = remember(target.uid, target.publicKey) { viewModel.computeFingerprintAndVerify(target) }
        AlertDialog(
            onDismissRequest = { showFingerprintDialog = false },
            containerColor = palette.surface,
            title = { Text(if (strings.isEnglish) "E2EE Safety Fingerprint" else "بصمة أمان التشفير الطرفي", color = palette.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (result == null) {
                        Text(if (strings.isEnglish) "Key unavailable." else "لا يتوفر مفتاح تشفير لهذا الحساب حالياً.", color = palette.textSecondary)
                    } else {
                        val (fp, verified) = result
                        Text(fp, color = palette.turquoise, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (verified) "ECDH + AES-GCM Verified ✓" else "Error",
                            color = if (verified) palette.emerald else palette.error,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFingerprintDialog = false }) { Text(strings.close) } }
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
