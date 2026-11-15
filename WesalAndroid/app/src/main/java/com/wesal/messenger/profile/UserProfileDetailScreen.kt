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
import com.wesal.messenger.core.theme.*
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.domain.model.PrivacyVisibility
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.ChatRepository
import com.wesal.messenger.domain.repository.UserRepository
import com.wesal.messenger.media.FullscreenImageViewer
import com.wesal.messenger.presentation.components.WesalAvatar
import com.wesal.messenger.security.E2EECryptoManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UserProfileDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val userRepository: UserRepository,
    private val chatRepository: ChatRepository,
    authRepository: AuthRepository,
    private val cryptoManager: E2EECryptoManager
) : ViewModel() {

    val targetUid: String = checkNotNull(savedStateHandle["uid"])
    val myUid: String = authRepository.currentUserId.orEmpty()

    val peerUser: StateFlow<WesalUser?> = userRepository.observeUser(targetUid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val currentUser: StateFlow<WesalUser?> = userRepository.observeCurrentUser()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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

    fun startDirectChat(onOpenChat: (String) -> Unit) {
        viewModelScope.launch {
            chatRepository.getOrCreateDirectChat(targetUid)
                .onSuccess(onOpenChat)
                .onFailure { _statusMessage.value = "تعذر فتح المحادثة" }
        }
    }

    fun toggleBlock(currentlyBlocked: Boolean) {
        viewModelScope.launch {
            val res = if (currentlyBlocked) {
                userRepository.unblockUser(targetUid)
            } else {
                userRepository.blockUser(targetUid)
            }
            res.onSuccess {
                _statusMessage.value = if (currentlyBlocked) "تم إلغاء حظر المستخدم" else "تم حظر المستخدم"
            }
        }
    }

    fun reportUser(reason: String) {
        viewModelScope.launch {
            userRepository.reportUser(targetUid, reason).onSuccess {
                _statusMessage.value = "تم إرسال البلاغ بنجاح"
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
    val context = LocalContext.current
    val peer by viewModel.peerUser.collectAsStateWithLifecycle()
    val me by viewModel.currentUser.collectAsStateWithLifecycle()
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
        FullscreenImageViewer(
            imageModel = target.photoUrl,
            onClose = { showFullscreenAvatar = false }
        )
        return
    }

    Scaffold(
        containerColor = WesalDeepBlack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("بيانات الحساب", color = WesalTextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = WesalTextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WesalSurfaceDark)
            )
        }
    ) { padding ->
        if (target == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = WesalTurquoise)
            }
        } else {
            val isBlocked = me?.blockedUsers?.contains(target.uid) == true
            val canSeeOnline = target.privacySettings.onlineVisibility != PrivacyVisibility.NOBODY
            val canSeeLastSeen = target.privacySettings.lastSeenVisibility != PrivacyVisibility.NOBODY
            val presenceLabel = when {
                canSeeOnline && target.isOnline -> "متصل الآن"
                canSeeLastSeen && target.lastSeen > 0L -> "آخر ظهور ${DateTimeUtils.formatChatListTime(target.lastSeen)}"
                else -> "الخصوصية مفعّلة"
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
                Box(
                    modifier = Modifier.clickable(enabled = showAvatar) { showFullscreenAvatar = true }
                ) {
                    WesalAvatar(
                        imageUrl = if (showAvatar) target.photoUrl else "",
                        name = target.displayName.ifBlank { target.publicId },
                        size = 118.dp,
                        isOnline = canSeeOnline && target.isOnline
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = target.displayName.ifBlank { "@${target.publicId}" },
                        style = MaterialTheme.typography.headlineSmall,
                        color = WesalTextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    if (target.publicId.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.clickable {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("wesal_id", "@${target.publicId}"))
                                Toast.makeText(context, "تم نسخ @${target.publicId}", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text(
                                text = "@${target.publicId}",
                                style = MaterialTheme.typography.titleMedium,
                                color = WesalTurquoise,
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "نسخ المعرف",
                                tint = WesalTurquoise,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Text(
                        text = presenceLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (canSeeOnline && target.isOnline) WesalEmerald else WesalTextSecondary
                    )
                    if (target.isPhoneVerified) {
                        Surface(
                            color = WesalEmerald.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, WesalEmerald.copy(alpha = 0.4f)),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.Verified, contentDescription = null, tint = WesalEmerald, modifier = Modifier.size(15.dp))
                                Text("حساب موثق على وصال", color = WesalEmerald, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                // Quick Communication Actions
                if (target.uid != viewModel.myUid) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { viewModel.startDirectChat(onOpenChat) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = WesalEmerald, contentColor = WesalDeepBlack)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("مراسلة", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = {
                                context.startActivity(CallActivity.createOutgoingIntent(context, target, isVideo = false))
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Call, contentDescription = null, tint = WesalEmerald, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("اتصال", color = WesalTextPrimary)
                        }
                        OutlinedButton(
                            onClick = {
                                context.startActivity(CallActivity.createOutgoingIntent(context, target, isVideo = true))
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Videocam, contentDescription = null, tint = WesalTurquoise, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("فيديو", color = WesalTextPrimary)
                        }
                    }
                }

                // Bio Card
                Surface(
                    color = WesalSurfaceDark,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, WesalSurfaceBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("نبذة عن الحساب", style = MaterialTheme.typography.labelMedium, color = WesalTurquoise)
                        Text(
                            text = target.bio.ifBlank { "أستخدم تطبيق وصال" },
                            style = MaterialTheme.typography.bodyLarge,
                            color = WesalTextPrimary
                        )
                    }
                }

                // Security & Moderation Actions
                if (target.uid != viewModel.myUid) {
                    Surface(
                        color = WesalSurfaceDark,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, WesalSurfaceBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            ListItem(
                                headlineContent = { Text("التشفير الطرفي وبصمة الأمان", color = WesalTextPrimary) },
                                supportingContent = { Text("اضغط لعرض والتحقق من بصمة ECDH P-256", color = WesalTextSecondary) },
                                leadingContent = { Icon(Icons.Default.Lock, contentDescription = null, tint = WesalTurquoise) },
                                modifier = Modifier.clickable { showFingerprintDialog = true },
                                colors = ListItemDefaults.colors(containerColor = WesalSurfaceDark)
                            )
                            HorizontalDivider(color = WesalSurfaceBorder, thickness = 0.5.dp)
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = if (isBlocked) "إلغاء حظر المستخدم" else "حظر المستخدم",
                                        color = if (isBlocked) WesalEmerald else WesalError
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.Block,
                                        contentDescription = null,
                                        tint = if (isBlocked) WesalEmerald else WesalError
                                    )
                                },
                                modifier = Modifier.clickable { viewModel.toggleBlock(isBlocked) },
                                colors = ListItemDefaults.colors(containerColor = WesalSurfaceDark)
                            )
                            HorizontalDivider(color = WesalSurfaceBorder, thickness = 0.5.dp)
                            ListItem(
                                headlineContent = { Text("الإبلاغ عن المستخدم", color = WesalWarning) },
                                leadingContent = { Icon(Icons.Default.Report, contentDescription = null, tint = WesalWarning) },
                                modifier = Modifier.clickable { showReportDialog = true },
                                colors = ListItemDefaults.colors(containerColor = WesalSurfaceDark)
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
            containerColor = WesalSurfaceDark,
            title = { Text("بصمة أمان التشفير الطرفي", color = WesalTextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (result == null) {
                        Text("لا يتوفر مفتاح تشفير لهذا الحساب حالياً.", color = WesalTextSecondary)
                    } else {
                        val (fp, verified) = result
                        Text("قارن بصمة المفتاح التالية مع ${target.displayName}:", color = WesalTextSecondary)
                        Text(fp, color = WesalTurquoise, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (verified) "تم التحقق من التشفير الثنائي ECDH + AES-GCM بنجاح ✓" else "تعذر التحقق من مفتاح الجلسة",
                            color = if (verified) WesalEmerald else WesalError,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFingerprintDialog = false }) { Text("حسناً") }
            }
        )
    }

    if (showReportDialog) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            containerColor = WesalSurfaceDark,
            title = { Text("إبلاغ عن إساءة", color = WesalTextPrimary) },
            text = {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("سبب البلاغ") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.reportUser(reason.ifBlank { "سلوك مخالف" })
                        showReportDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WesalWarning, contentColor = WesalDeepBlack)
                ) {
                    Text("إرسال البلاغ")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReportDialog = false }) { Text("إلغاء") }
            }
        )
    }
}
