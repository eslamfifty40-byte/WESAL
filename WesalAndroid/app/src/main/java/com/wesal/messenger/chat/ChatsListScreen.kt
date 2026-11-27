package com.wesal.messenger.chat

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.core.util.WesalFrameLibrary
import com.wesal.messenger.domain.model.ChatPreview
import com.wesal.messenger.presentation.components.MessageStatusTick
import com.wesal.messenger.presentation.components.WesalAvatar
import com.wesal.messenger.presentation.components.WesalNameWithBadge

@Composable
fun ChatsListScreen(
    onOpenChat: (String) -> Unit,
    onNewChat: () -> Unit,
    viewModel: ChatsListViewModel = hiltViewModel()
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val chatListState by viewModel.chatListState.collectAsStateWithLifecycle()
    val messageRequestChats by viewModel.messageRequestChats.collectAsStateWithLifecycle()
    val incomingContactRequests by viewModel.incomingContactRequests.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val showHiddenChats by viewModel.showHiddenChats.collectAsStateWithLifecycle()
    val showArchivedOnly by viewModel.showArchivedOnly.collectAsStateWithLifecycle()
    val archivedCount by viewModel.archivedCount.collectAsStateWithLifecycle()
    val hiddenCount by viewModel.hiddenCount.collectAsStateWithLifecycle()
    val hiddenIds by viewModel.hiddenChatIds.collectAsStateWithLifecycle()
    val lockedIds by viewModel.lockedChatIds.collectAsStateWithLifecycle()
    val toastMsg by viewModel.toastMessage.collectAsStateWithLifecycle()

    // Spec §8.3: while the hidden-chats list is unlocked and visible, FLAG_SECURE keeps it
    // out of Android Recents thumbnails and screenshots. Cleared as soon as the hidden
    // view closes or this screen leaves composition (normal list stays unprotected).
    val secureContext = LocalContext.current
    val secureActivity = remember(secureContext) { secureContext as? Activity }
    DisposableEffect(showHiddenChats, secureActivity) {
        if (showHiddenChats) {
            secureActivity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (showHiddenChats) {
                secureActivity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    var selectedChatForMenu by remember { mutableStateOf<ChatPreview?>(null) }
    var deleteConfirmChat by remember { mutableStateOf<ChatPreview?>(null) }
    var showSetupPasswordForChatId by remember { mutableStateOf<String?>(null) }
    var showRequestsDialog by remember { mutableStateOf(false) }
    var pendingLockedChat by remember { mutableStateOf<ChatPreview?>(null) }
    var reportRequestTarget by remember { mutableStateOf<Pair<String, String?>?>(null) }

    LaunchedEffect(archivedCount, showArchivedOnly) {
        if (showArchivedOnly && archivedCount == 0) {
            viewModel.setShowArchivedOnly(false)
        }
    }

    val extraContactRequests = remember(incomingContactRequests, messageRequestChats) {
        val chatPeers = messageRequestChats.map { it.peerUid }.toSet()
        incomingContactRequests.filterNot { it.senderUid in chatPeers }
    }
    val totalRequestsCount = messageRequestChats.size + extraContactRequests.size

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearToast()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = viewModel::updateSearchQuery,
                    placeholder = {
                        Text(if (strings.isEnglish) "Search chats..." else "ابحث في المحادثات...")
                    },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = palette.textSecondary) },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                Icon(Icons.Default.Close, contentDescription = strings.clear)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = palette.textPrimary,
                        unfocusedTextColor = palette.textPrimary,
                        focusedPlaceholderColor = palette.textMuted,
                        unfocusedPlaceholderColor = palette.textMuted,
                        focusedBorderColor = palette.turquoise,
                        unfocusedBorderColor = palette.border,
                        focusedContainerColor = palette.surface,
                        unfocusedContainerColor = palette.surface,
                        cursorColor = palette.turquoise
                    )
                )
            }

            if (showHiddenChats) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setShowingHiddenChats(false) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LockOpen,
                            contentDescription = null,
                            tint = palette.emerald
                        )
                        Text(
                            text = if (strings.isEnglish) "Back to Main Chats (Lock Hidden)" else "العودة للمحادثات الرئيسية (إعادة القفل)",
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.textPrimary
                        )
                    }
                    if (hiddenCount > 0) {
                        Text(text = hiddenCount.toString(), color = palette.emerald, fontWeight = FontWeight.Bold)
                    }
                }
                HorizontalDivider(color = palette.border, thickness = 0.5.dp)
            } else if (archivedCount > 0 || showArchivedOnly) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setShowArchivedOnly(!showArchivedOnly) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = if (showArchivedOnly) Icons.Default.Unarchive else Icons.Default.Archive,
                            contentDescription = null,
                            tint = palette.turquoise
                        )
                        Text(
                            text = if (showArchivedOnly) {
                                if (strings.isEnglish) "Back to Main Chats" else "العودة للمحادثات الرئيسية"
                            } else {
                                if (strings.isEnglish) "Archived Chats" else "المؤرشفة"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.textPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (!showArchivedOnly && archivedCount > 0) {
                        Surface(
                            color = palette.turquoise.copy(alpha = 0.18f),
                            shape = CircleShape
                        ) {
                            Text(
                                text = archivedCount.toString(),
                                color = palette.turquoise,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                HorizontalDivider(color = palette.border, thickness = 0.5.dp)
            }

            if (totalRequestsCount > 0 && !showHiddenChats) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showRequestsDialog = true }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MarkChatUnread,
                            contentDescription = null,
                            tint = palette.turquoise
                        )
                        Text(
                            text = if (strings.isEnglish) "Message Requests" else "طلبات المراسلة",
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.textPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Surface(
                        color = palette.turquoise.copy(alpha = 0.18f),
                        shape = CircleShape
                    ) {
                        Text(
                            text = totalRequestsCount.toString(),
                            color = palette.turquoise,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                        )
                    }
                }
                HorizontalDivider(color = palette.border, thickness = 0.5.dp)
            }

            if (chats.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // Spec §13.3: while the first snapshot is in flight show a loading
                    // indicator (it always stops: the repository publishes READY/ERROR from
                    // the snapshot listener); on listener error show the reason + retry
                    // instead of a misleading "no chats" empty state.
                    val syncStatus = chatListState.status
                    if (!showHiddenChats &&
                        syncStatus == com.wesal.messenger.domain.model.ChatListSyncStatus.LOADING
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(24.dp)
                        ) {
                            CircularProgressIndicator(color = palette.turquoise)
                            Text(
                                text = if (strings.isEnglish) "Loading chats…" else "جاري تحميل المحادثات…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.textSecondary
                            )
                        }
                    } else if (!showHiddenChats &&
                        syncStatus == com.wesal.messenger.domain.model.ChatListSyncStatus.ERROR
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = palette.turquoise.copy(alpha = 0.6f),
                                modifier = Modifier.size(56.dp)
                            )
                            Text(
                                text = if (strings.isEnglish) {
                                    "Couldn't load chats"
                                } else {
                                    "تعذّر تحميل المحادثات"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.textPrimary
                            )
                            chatListState.errorMessage?.let { reason ->
                                Text(
                                    text = reason,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.textSecondary
                                )
                            }
                            Button(
                                onClick = { viewModel.retryChats() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = palette.turquoise,
                                    contentColor = Color.White
                                )
                            ) {
                                Text(if (strings.isEnglish) "Retry" else "إعادة المحاولة")
                            }
                        }
                    } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = if (showHiddenChats) Icons.Default.Lock else Icons.Default.ChatBubbleOutline,
                            contentDescription = null,
                            tint = palette.turquoise.copy(alpha = 0.6f),
                            modifier = Modifier.size(56.dp)
                        )
                        Text(
                            text = if (showHiddenChats) {
                                if (strings.isEnglish) "No hidden chats" else "لا توجد محادثات مخفية"
                            } else {
                                if (strings.isEnglish) "No chats yet" else "لا توجد محادثات حتى الآن"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.textPrimary
                        )
                        Text(
                            text = if (showHiddenChats) {
                                if (strings.isEnglish) {
                                    "Long press any chat to hide it with your password"
                                } else {
                                    "اضغط مطولاً على أي محادثة لنقلها إلى المحادثات المخفية"
                                }
                            } else if (strings.isEnglish) {
                                "Tap + to start a chat via @Wesal ID or WESAL ID"
                            } else {
                                "اضغط على زر + لبدء محادثة جديدة عبر معرف وصال الدقيق"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textSecondary
                        )
                    }
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(chats, key = { it.chatId }) { chat ->
                        val isLocked = chat.chatId in lockedIds
                        ChatRowItem(
                            chat = chat,
                            myUid = viewModel.currentUid,
                            isLocked = isLocked,
                            onClick = {
                                if (isLocked) {
                                    pendingLockedChat = chat
                                } else {
                                    onOpenChat(chat.chatId)
                                }
                            },
                            onLongClick = { selectedChatForMenu = chat }
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onNewChat,
            containerColor = palette.emerald,
            contentColor = palette.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.AddComment, contentDescription = strings.newMessage)
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 84.dp)
        )
    }

    selectedChatForMenu?.let { chat ->
        val isCurrentlyHidden = chat.chatId in hiddenIds
        AlertDialog(
            onDismissRequest = { selectedChatForMenu = null },
            containerColor = palette.surface,
            title = {
                WesalNameWithBadge(
                    name = chat.title,
                    isVerified = (!chat.isGroup && chat.peerVerified) || chat.isOfficialWesalChat,
                    verificationType = if (chat.isOfficialWesalChat) "BLUE" else chat.peerVerificationType,
                    neonColor = if (!chat.isGroup && !chat.isOfficialWesalChat) chat.peerNeonColor else "",
                    neonGlowIntensity = chat.peerNeonGlowIntensity,
                    neonEnabled = !chat.isGroup && !chat.isOfficialWesalChat && chat.peerNeonEnabled,
                    verificationGlowColor = if (chat.isOfficialWesalChat) "" else chat.peerVerificationGlowColor,
                    verificationGlowEnabled = chat.peerVerificationGlowEnabled,
                    verificationGlowIntensity = chat.peerVerificationGlowIntensity
                )
            },
            text = {
                Column {
                    val menuItemColors = ListItemDefaults.colors(
                        containerColor = palette.surface,
                        headlineColor = palette.textPrimary,
                        supportingColor = palette.textSecondary
                    )
                    ListItem(
                        headlineContent = {
                            Text(
                                if (chat.isPinned) {
                                    if (strings.isEnglish) "Unpin" else "إلغاء التثبيت"
                                } else {
                                    if (strings.isEnglish) "Pin Chat" else "تثبيت المحادثة"
                                }
                            )
                        },
                        leadingContent = { Icon(Icons.Default.PushPin, contentDescription = null, tint = palette.turquoise) },
                        colors = menuItemColors,
                        modifier = Modifier.clickable {
                            viewModel.togglePin(chat)
                            selectedChatForMenu = null
                        }
                    )
                    ListItem(
                        headlineContent = {
                            Text(
                                if (chat.isArchived) {
                                    if (strings.isEnglish) "Unarchive Chat" else "إلغاء الأرشفة"
                                } else {
                                    if (strings.isEnglish) "Archive Chat" else "أرشفة المحادثة"
                                }
                            )
                        },
                        leadingContent = {
                            Icon(
                                if (chat.isArchived) Icons.Default.Unarchive else Icons.Default.Archive,
                                contentDescription = null,
                                tint = palette.turquoise
                            )
                        },
                        colors = menuItemColors,
                        modifier = Modifier.clickable {
                            viewModel.toggleArchive(chat)
                            selectedChatForMenu = null
                        }
                    )
                    if (!chat.isOfficialWesalChat) {
                        ListItem(
                            headlineContent = {
                                Text(
                                    if (isCurrentlyHidden) {
                                        if (strings.isEnglish) "Unhide Chat" else "إلغاء الإخفاء وإظهار المحادثة"
                                    } else {
                                        if (strings.isEnglish) "Hide Chat" else "إخفاء المحادثة برمز حماية"
                                    }
                                )
                            },
                            leadingContent = {
                                Icon(
                                    if (isCurrentlyHidden) Icons.Default.LockOpen else Icons.Default.VisibilityOff,
                                    contentDescription = null,
                                    tint = palette.emerald
                                )
                            },
                            colors = menuItemColors,
                            modifier = Modifier.clickable {
                                selectedChatForMenu = null
                                if (isCurrentlyHidden) {
                                    viewModel.hideOrUnhideChat(chat, hide = false)
                                } else if (viewModel.hasHiddenPassword()) {
                                    viewModel.hideOrUnhideChat(chat, hide = true)
                                } else {
                                    showSetupPasswordForChatId = chat.chatId
                                }
                            }
                        )
                        val isCurrentlyLocked = chat.chatId in lockedIds
                        ListItem(
                            headlineContent = {
                                Text(
                                    if (isCurrentlyLocked) {
                                        if (strings.isEnglish) "Unlock Chat" else "إلغاء قفل المحادثة"
                                    } else {
                                        if (strings.isEnglish) "Lock Chat" else "قفل المحادثة برمز حماية"
                                    }
                                )
                            },
                            leadingContent = {
                                Icon(
                                    if (isCurrentlyLocked) Icons.Default.LockOpen else Icons.Default.EnhancedEncryption,
                                    contentDescription = null,
                                    tint = palette.turquoise
                                )
                            },
                            colors = menuItemColors,
                            modifier = Modifier.clickable {
                                viewModel.toggleLockChat(chat, !isCurrentlyLocked)
                                selectedChatForMenu = null
                            }
                        )
                    }
                    ListItem(
                        headlineContent = {
                            Text(
                                if (chat.isMuted) {
                                    if (strings.isEnglish) "Unmute" else "إلغاء الكتم"
                                } else {
                                    if (strings.isEnglish) "Mute Notifications" else "كتم الإشعارات"
                                }
                            )
                        },
                        leadingContent = { Icon(Icons.Default.NotificationsOff, contentDescription = null, tint = palette.textSecondary) },
                        colors = menuItemColors,
                        modifier = Modifier.clickable {
                            viewModel.toggleMute(chat)
                            selectedChatForMenu = null
                        }
                    )
                    ListItem(
                        headlineContent = {
                            Text(if (strings.isEnglish) "Delete Chat" else "حذف المحادثة", color = palette.error)
                        },
                        leadingContent = { Icon(Icons.Default.Delete, contentDescription = null, tint = palette.error) },
                        colors = menuItemColors,
                        modifier = Modifier.clickable {
                            deleteConfirmChat = chat
                            selectedChatForMenu = null
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedChatForMenu = null }) { Text(strings.close) }
            }
        )
    }

    deleteConfirmChat?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteConfirmChat = null },
            containerColor = palette.surface,
            title = {
                Text(
                    text = if (strings.isEnglish) "Delete Chat" else "حذف المحادثة",
                    color = palette.error,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (strings.isEnglish) {
                            "Choose how to delete this conversation:"
                        } else {
                            "اختر طريقة الحذف المناسبة:"
                        },
                        color = palette.textPrimary
                    )
                    if (!target.isGroup && !target.isOfficialWesalChat) {
                        Text(
                            text = if (strings.isEnglish) {
                                "Note: Deleting for both sides erases messages from WESAL servers for both participants, though pre-downloaded copies on the other device cannot be pulled back."
                            } else {
                                "تنبيه: الحذف لدى الطرفين يمسح الرسائل من الخادم لكلاكما، لكن أي نسخ قام الطرف الآخر بحفظها مسبقاً أو تصويرها لا يمكن سحبها من جهازه."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary
                        )
                    }
                }
            },
            confirmButton = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    if (!target.isGroup && !target.isOfficialWesalChat) {
                        Button(
                            onClick = {
                                viewModel.deleteChat(target, forEveryone = true)
                                deleteConfirmChat = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = palette.error)
                        ) {
                            Text(if (strings.isEnglish) "Delete for Both" else "حذف المحادثة لدى الطرفين")
                        }
                    }
                    TextButton(
                        onClick = {
                            viewModel.deleteChat(target, forEveryone = false)
                            deleteConfirmChat = null
                        }
                    ) {
                        Text(
                            if (strings.isEnglish) "Delete for Me Only" else "حذف لدي فقط",
                            color = palette.textPrimary
                        )
                    }
                    TextButton(onClick = { deleteConfirmChat = null }) {
                        Text(strings.cancel, color = palette.textSecondary)
                    }
                }
            }
        )
    }

    // Spec §8.2: the unlock AlertDialog and its recovery dialog were removed from
    // this screen. Hidden chats now unlock ONLY when the password is typed into
    // the search box above (ChatsListViewModel.updateSearchQuery verifies it);
    // password recovery/reset lives in Settings. The dialog below is unrelated:
    // it SETS a first password when the user hides a chat without having one.

    showSetupPasswordForChatId?.let { pendingChatId ->
        var pass by remember { mutableStateOf("") }
        var confirmPass by remember { mutableStateOf("") }
        var passVisible by remember { mutableStateOf(false) }
        var question by remember { mutableStateOf("") }
        var answer by remember { mutableStateOf("") }
        var errMsg by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showSetupPasswordForChatId = null },
            containerColor = palette.surface,
            title = {
                Text(
                    if (strings.isEnglish) "Set Hidden Chats Password" else "تعيين كلمة مرور للمحادثات المخفية",
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it; errMsg = null },
                        label = { Text(if (strings.isEnglish) "Password (4+ chars)" else "كلمة المرور (4 أحرف فأكثر)") },
                        visualTransformation = if (passVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { passVisible = !passVisible }) {
                                Icon(
                                    if (passVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = palette.textSecondary
                                )
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmPass,
                        onValueChange = { confirmPass = it; errMsg = null },
                        label = { Text(if (strings.isEnglish) "Confirm password" else "تأكيد كلمة المرور") },
                        visualTransformation = if (passVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = question,
                        onValueChange = { question = it },
                        label = { Text(if (strings.isEnglish) "Recovery question (optional)" else "سؤال استعادة (اختياري)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = answer,
                        onValueChange = { answer = it },
                        label = { Text(if (strings.isEnglish) "Recovery answer" else "إجابة الاستعادة") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    errMsg?.let { Text(it, color = palette.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        when {
                            pass.trim().length < 4 -> errMsg = "كلمة المرور يجب أن لا تقل عن 4 أحرف"
                            pass.trim() != confirmPass.trim() -> errMsg = "كلمتا المرور غير متطابقتين"
                            else -> {
                                viewModel.setupOrResetHiddenPassword(pass, question, answer, pendingChatId)
                                showSetupPasswordForChatId = null
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary)
                ) {
                    Text(if (strings.isEnglish) "Save & Hide" else "حفظ وإخفاء")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSetupPasswordForChatId = null }) { Text(strings.cancel) }
            }
        )
    }

    if (showRequestsDialog) {
        var filterMode by remember { mutableStateOf("ALL") } // ALL, PENDING, HELD
        val filteredReqChats = remember(messageRequestChats, filterMode) {
            when (filterMode) {
                "PENDING" -> messageRequestChats.filter { it.requestStatus != "HELD" }
                "HELD" -> messageRequestChats.filter { it.requestStatus == "HELD" }
                else -> messageRequestChats
            }
        }
        val filteredContactReqs = remember(extraContactRequests, filterMode) {
            when (filterMode) {
                "PENDING" -> extraContactRequests.filter { it.status.raw == "PENDING" }
                "HELD" -> extraContactRequests.filter { it.status.raw == "HELD" }
                else -> extraContactRequests
            }
        }
        AlertDialog(
            onDismissRequest = { showRequestsDialog = false },
            containerColor = palette.surface,
            title = {
                Text(
                    if (strings.isEnglish) "Message Requests ($totalRequestsCount)" else "طلبات المراسلة ($totalRequestsCount)",
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "ALL" to (if (strings.isEnglish) "All" else "الكل"),
                            "PENDING" to (if (strings.isEnglish) "New" else "جديد"),
                            "HELD" to (if (strings.isEnglish) "Held" else "معلّق")
                        ).forEach { (key, label) ->
                            FilterChip(
                                selected = filterMode == key,
                                onClick = { filterMode = key },
                                label = { Text(label, maxLines = 1) }
                            )
                        }
                    }
                    if (filteredReqChats.isEmpty() && filteredContactReqs.isEmpty()) {
                        Text(
                            if (strings.isEnglish) "No requests in this filter." else "لا توجد طلبات في هذا التصنيف.",
                            color = palette.textSecondary,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(filteredReqChats, key = { "req_chat_${it.chatId}" }) { reqChat ->
                                Surface(
                                    color = palette.surfaceElevated,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.clickable {
                                                showRequestsDialog = false
                                                onOpenChat(reqChat.chatId)
                                            }
                                        ) {
                                            WesalAvatar(imageUrl = reqChat.avatarUrl, name = reqChat.title, size = 40.dp)
                                            Column(modifier = Modifier.weight(1f)) {
                                                WesalNameWithBadge(
                                                    name = reqChat.title,
                                                    isVerified = reqChat.peerVerified,
                                                    verificationType = reqChat.peerVerificationType,
                                                    verificationGlowColor = reqChat.peerVerificationGlowColor,
                                                    verificationGlowEnabled = reqChat.peerVerificationGlowEnabled,
                                                    verificationGlowIntensity = reqChat.peerVerificationGlowIntensity,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = reqChat.lastMessagePreview.ifBlank { if (strings.isEnglish) "Message request" else "طلب مراسلة" },
                                                    color = palette.textSecondary,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            if (reqChat.requestStatus == "HELD") {
                                                Text(
                                                    if (strings.isEnglish) "Held" else "معلّق",
                                                    color = palette.warning,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Button(
                                                onClick = { viewModel.decideMessageRequest(reqChat.chatId, reqChat.peerUid, "ACCEPTED") },
                                                colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                modifier = Modifier.weight(1f)
                                            ) { Text(if (strings.isEnglish) "Accept" else "قبول", maxLines = 1) }
                                            OutlinedButton(
                                                onClick = { viewModel.decideMessageRequest(reqChat.chatId, reqChat.peerUid, "HELD") },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                modifier = Modifier.weight(1f)
                                            ) { Text(if (strings.isEnglish) "Hold" else "تعليق", color = palette.warning, maxLines = 1) }
                                            OutlinedButton(
                                                onClick = { viewModel.decideMessageRequest(reqChat.chatId, reqChat.peerUid, "REJECTED") },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                modifier = Modifier.weight(1f)
                                            ) { Text(if (strings.isEnglish) "Reject" else "رفض", color = palette.error, maxLines = 1) }
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            TextButton(
                                                onClick = { viewModel.blockAndRejectRequest(reqChat.chatId, reqChat.peerUid, null) }
                                            ) { Text(if (strings.isEnglish) "Block" else "حظر", color = palette.error) }
                                            TextButton(
                                                onClick = { reportRequestTarget = reqChat.peerUid to reqChat.chatId }
                                            ) { Text(if (strings.isEnglish) "Report" else "إبلاغ", color = palette.warning) }
                                        }
                                    }
                                }
                            }
                            items(filteredContactReqs, key = { "req_c_${it.requestId}" }) { cReq ->
                                Surface(
                                    color = palette.surfaceElevated,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            WesalAvatar(imageUrl = cReq.senderAvatarUrl, name = cReq.senderName, size = 40.dp)
                                            Column(modifier = Modifier.weight(1f)) {
                                                WesalNameWithBadge(
                                                    name = cReq.senderName.ifBlank { "@${cReq.senderPublicId}" },
                                                    isVerified = cReq.senderVerified,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                if (cReq.senderPublicId.isNotBlank()) {
                                                    Text("@${cReq.senderPublicId}", color = palette.textSecondary, style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Button(
                                                onClick = { viewModel.acceptContactRequest(cReq.requestId) },
                                                colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                modifier = Modifier.weight(1f)
                                            ) { Text(if (strings.isEnglish) "Accept" else "قبول", maxLines = 1) }
                                            OutlinedButton(
                                                onClick = { viewModel.holdContactRequest(cReq.requestId) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                modifier = Modifier.weight(1f)
                                            ) { Text(if (strings.isEnglish) "Hold" else "تعليق", color = palette.warning, maxLines = 1) }
                                            OutlinedButton(
                                                onClick = { viewModel.rejectContactRequest(cReq.requestId) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                modifier = Modifier.weight(1f)
                                            ) { Text(if (strings.isEnglish) "Reject" else "رفض", color = palette.error, maxLines = 1) }
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            TextButton(
                                                onClick = { viewModel.blockAndRejectRequest(null, cReq.senderUid, cReq.requestId) }
                                            ) { Text(if (strings.isEnglish) "Block" else "حظر", color = palette.error) }
                                            TextButton(
                                                onClick = { reportRequestTarget = cReq.senderUid to null }
                                            ) { Text(if (strings.isEnglish) "Report" else "إبلاغ", color = palette.warning) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRequestsDialog = false }) { Text(strings.close) }
            }
        )
    }

    reportRequestTarget?.let { (peerUid, reqChatId) ->
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { reportRequestTarget = null },
            containerColor = palette.surface,
            title = { Text(if (strings.isEnglish) "Report Request" else "إبلاغ عن طلب مراسلة", color = palette.warning) },
            text = {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(if (strings.isEnglish) "Reason" else "سبب البلاغ") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.reportRequestSender(peerUid, reqChatId, reason)
                        reportRequestTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.warning, contentColor = palette.onPrimary)
                ) { Text(strings.send) }
            },
            dismissButton = {
                TextButton(onClick = { reportRequestTarget = null }) { Text(strings.cancel) }
            }
        )
    }

    pendingLockedChat?.let { target ->
        var pinInput by remember { mutableStateOf("") }
        var pinErr by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { pendingLockedChat = null },
            containerColor = palette.surface,
            title = {
                Text(
                    if (strings.isEnglish) "Locked Chat" else "محادثة مقفلة",
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (viewModel.hasAnyPinConfigured()) {
                        OutlinedTextField(
                            value = pinInput,
                            onValueChange = { pinInput = it; pinErr = null },
                            label = { Text(if (strings.isEnglish) "Enter PIN / Password" else "أدخل رمز الحماية") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        pinErr?.let { Text(it, color = palette.error, style = MaterialTheme.typography.bodySmall) }
                    } else {
                        Text(
                            if (strings.isEnglish) "Open this locked chat?" else "تأكيد فتح هذه المحادثة المقفلة؟",
                            color = palette.textSecondary
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (!viewModel.hasAnyPinConfigured() || viewModel.verifyTwoFactorOrHiddenPin(pinInput)) {
                            val id = target.chatId
                            pendingLockedChat = null
                            onOpenChat(id)
                        } else {
                            pinErr = if (strings.isEnglish) "Incorrect PIN" else "رمز الحماية غير صحيح"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary)
                ) { Text(if (strings.isEnglish) "Open" else "فتح") }
            },
            dismissButton = {
                TextButton(onClick = { pendingLockedChat = null }) { Text(strings.cancel) }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRowItem(
    chat: ChatPreview,
    myUid: String,
    isLocked: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        WesalAvatar(
            imageUrl = chat.avatarUrl,
            name = chat.title,
            size = 54.dp,
            isOnline = !chat.isGroup && !chat.isOfficialWesalChat && chat.peerOnline,
            // §5.7: the official WESAL chat renders WITH the official account's frame
            // (no longer forced frameless). ChatRepositoryImpl does not populate
            // peerFrameId for official chats, so fall back to the official (owner admin)
            // account's frame — the royal ADMIN crest it wears everywhere else.
            frameId = when {
                chat.isGroup -> ""
                chat.isOfficialWesalChat -> chat.peerFrameId.ifBlank { WesalFrameLibrary.ADMIN_FRAME_ID }
                else -> chat.peerFrameId
            },
            frameExpiresAt = chat.peerFrameExpiresAt,
            isCutout = !chat.isGroup && !chat.isOfficialWesalChat && chat.peerPhotoRemovedBg,
            iconUrl = if (!chat.isGroup && !chat.isOfficialWesalChat) chat.peerIconUrl else "",
            neonColor = if (!chat.isGroup && !chat.isOfficialWesalChat && chat.peerNeonEnabled) chat.peerNeonColor else "",
            neonGlowIntensity = chat.peerNeonGlowIntensity,
            fallbackResId = if (chat.isOfficialWesalChat || chat.isSupportChat) com.wesal.messenger.R.drawable.wesal_brand_logo else null
        )

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    WesalNameWithBadge(
                        name = chat.title,
                        isVerified = (!chat.isGroup && chat.peerVerified) || chat.isOfficialWesalChat,
                        verificationType = if (chat.isOfficialWesalChat) "BLUE" else chat.peerVerificationType,
                        neonColor = if (!chat.isGroup && !chat.isOfficialWesalChat) chat.peerNeonColor else "",
                        neonGlowIntensity = chat.peerNeonGlowIntensity,
                        neonEnabled = !chat.isGroup && !chat.isOfficialWesalChat && chat.peerNeonEnabled,
                        verificationGlowColor = if (chat.isOfficialWesalChat) "" else chat.peerVerificationGlowColor,
                        verificationGlowEnabled = chat.peerVerificationGlowEnabled,
                        verificationGlowIntensity = chat.peerVerificationGlowIntensity,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isLocked) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = palette.warning, modifier = Modifier.size(14.dp))
                    }
                    if (chat.isPinned || chat.isOfficialWesalChat) {
                        Icon(Icons.Default.PushPin, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(14.dp))
                    }
                    if (chat.isMuted) {
                        Icon(Icons.Default.VolumeOff, contentDescription = null, tint = palette.textMuted, modifier = Modifier.size(14.dp))
                    }
                }
                Text(
                    text = DateTimeUtils.formatChatListTime(chat.lastMessageTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (chat.unreadCount > 0) palette.emerald else palette.textSecondary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (chat.typingUsers.isNotEmpty()) {
                        Text(
                            text = strings.typingNow,
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.emerald,
                            fontWeight = FontWeight.Medium
                        )
                    } else {
                        if (chat.lastMessageSenderId == myUid && chat.lastMessagePreview.isNotBlank()) {
                            MessageStatusTick(status = chat.lastMessageStatus)
                        }
                        Text(
                            text = chat.lastMessagePreview.ifBlank {
                                if (strings.isEnglish) "Tap to start chatting" else "اضغط لبدء المحادثة"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (chat.unreadCount > 0) {
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
                            .clip(CircleShape)
                            .background(palette.emerald)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = chat.unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.onPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
