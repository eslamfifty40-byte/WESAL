package com.wesal.messenger.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.core.util.LocalWesalStrings
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
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val showHiddenChats by viewModel.showHiddenChats.collectAsStateWithLifecycle()
    val hiddenCount by viewModel.hiddenCount.collectAsStateWithLifecycle()
    val hiddenIds by viewModel.hiddenChatIds.collectAsStateWithLifecycle()
    val toastMsg by viewModel.toastMessage.collectAsStateWithLifecycle()

    var selectedChatForMenu by remember { mutableStateOf<ChatPreview?>(null) }
    var deleteConfirmChat by remember { mutableStateOf<ChatPreview?>(null) }
    var showUnlockDialog by remember { mutableStateOf(false) }
    var showSetupPasswordForChatId by remember { mutableStateOf<String?>(null) }
    var showRecoveryDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearToast()
        }
    }

    // Re-lock hidden chats view when user leaves screen
    DisposableEffect(Unit) {
        onDispose {
            viewModel.setShowingHiddenChats(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
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

            if (hiddenCount > 0 || showHiddenChats) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (showHiddenChats) {
                                viewModel.setShowingHiddenChats(false)
                            } else if (viewModel.hasHiddenPassword()) {
                                showUnlockDialog = true
                            } else {
                                viewModel.setShowingHiddenChats(true)
                            }
                        }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = if (showHiddenChats) Icons.Default.LockOpen else Icons.Default.Lock,
                            contentDescription = null,
                            tint = palette.emerald
                        )
                        Text(
                            text = if (showHiddenChats) {
                                if (strings.isEnglish) "Back to Main Chats (Lock Hidden)" else "العودة للمحادثات الرئيسية (إعادة القفل)"
                            } else {
                                if (strings.isEnglish) "Hidden Chats" else "المحادثات المخفية"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.textPrimary
                        )
                    }
                    if (!showHiddenChats) {
                        Text(text = hiddenCount.toString(), color = palette.emerald, fontWeight = FontWeight.Bold)
                    }
                }
                HorizontalDivider(color = palette.border, thickness = 0.5.dp)
            }

            if (chats.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
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
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(chats, key = { it.chatId }) { chat ->
                        ChatRowItem(
                            chat = chat,
                            myUid = viewModel.currentUid,
                            onClick = { onOpenChat(chat.chatId) },
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
                    isVerified = chat.peerVerified || chat.isOfficialWesalChat,
                    verificationType = chat.peerVerificationType
                )
            },
            text = {
                Column {
                    val menuItemColors = ListItemDefaults.colors(
                        containerColor = palette.surface,
                        headlineColor = palette.textPrimary,
                        supportingColor = palette.textSecondary
                    )
                    if (!chat.isOfficialWesalChat) {
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
                    if (!chat.isOfficialWesalChat) {
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
                    if (!target.isGroup) {
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
                    if (!target.isGroup) {
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

    if (showUnlockDialog) {
        var passInput by remember { mutableStateOf("") }
        var passError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showUnlockDialog = false },
            containerColor = palette.surface,
            title = {
                Text(
                    if (strings.isEnglish) "Unlock Hidden Chats" else "فتح المحادثات المخفية",
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = passInput,
                        onValueChange = { passInput = it; passError = null },
                        label = { Text(if (strings.isEnglish) "Hidden Chats Password" else "كلمة مرور المحادثات المخفية") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    passError?.let {
                        Text(it, color = palette.error, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = {
                        showUnlockDialog = false
                        showRecoveryDialog = true
                    }) {
                        Text(
                            if (strings.isEnglish) "Forgot password?" else "نسيت كلمة المرور؟",
                            color = palette.turquoise
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (viewModel.verifyHiddenPassword(passInput)) {
                            showUnlockDialog = false
                            viewModel.setShowingHiddenChats(true)
                        } else {
                            passError = if (strings.isEnglish) "Incorrect password" else "كلمة المرور غير صحيحة"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary)
                ) {
                    Text(if (strings.isEnglish) "Unlock" else "فتح")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnlockDialog = false }) { Text(strings.cancel) }
            }
        )
    }

    if (showRecoveryDialog) {
        val question = remember { viewModel.getHiddenRecoveryQuestion() }
        var answerInput by remember { mutableStateOf("") }
        var newPass by remember { mutableStateOf("") }
        var recError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showRecoveryDialog = false },
            containerColor = palette.surface,
            title = {
                Text(
                    if (strings.isEnglish) "Recover Hidden Chats" else "استعادة المحادثات المخفية",
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (question.isNotBlank()) {
                        Text(
                            text = if (strings.isEnglish) "Question: $question" else "سؤال الأمان: $question",
                            color = palette.turquoise,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedTextField(
                            value = answerInput,
                            onValueChange = { answerInput = it; recError = null },
                            label = { Text(if (strings.isEnglish) "Recovery answer" else "إجابة الأمان") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newPass,
                            onValueChange = { newPass = it; recError = null },
                            label = { Text(if (strings.isEnglish) "New password (4+ chars)" else "كلمة المرور الجديدة (4 أحرف فأكثر)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            text = if (strings.isEnglish) {
                                "No recovery question was configured. Resetting will clear the lock and unhide your chats."
                            } else {
                                "لم يتم إعداد سؤال أمان سابقاً. يمكنك مسح القفل وإعادة إظهار جميع المحادثات."
                            },
                            color = palette.textSecondary
                        )
                    }
                    recError?.let {
                        Text(it, color = palette.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                if (question.isNotBlank()) {
                    Button(
                        onClick = {
                            if (!viewModel.verifyHiddenRecoveryAnswer(answerInput)) {
                                recError = if (strings.isEnglish) "Incorrect answer" else "الإجابة غير صحيحة"
                            } else if (newPass.length < 4) {
                                recError = if (strings.isEnglish) "Password must be at least 4 characters" else "كلمة المرور يجب أن تكون 4 أحرف على الأقل"
                            } else {
                                viewModel.setupOrResetHiddenPassword(newPass, question, answerInput)
                                showRecoveryDialog = false
                                viewModel.setShowingHiddenChats(true)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary)
                    ) {
                        Text(if (strings.isEnglish) "Reset & Unlock" else "تغيير وفتح")
                    }
                } else {
                    Button(
                        onClick = {
                            viewModel.resetHiddenLockAndUnhideAll()
                            showRecoveryDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = palette.error)
                    ) {
                        Text(if (strings.isEnglish) "Reset Lock" else "إلغاء القفل وإظهار المحادثات")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showRecoveryDialog = false }) { Text(strings.cancel) }
            }
        )
    }

    showSetupPasswordForChatId?.let { pendingChatId ->
        var pass by remember { mutableStateOf("") }
        var confirmPass by remember { mutableStateOf("") }
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
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmPass,
                        onValueChange = { confirmPass = it; errMsg = null },
                        label = { Text(if (strings.isEnglish) "Confirm password" else "تأكيد كلمة المرور") },
                        visualTransformation = PasswordVisualTransformation(),
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
                            pass.length < 4 -> errMsg = "كلمة المرور يجب أن لا تقل عن 4 أحرف"
                            pass != confirmPass -> errMsg = "كلمتا المرور غير متطابقتين"
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRowItem(
    chat: ChatPreview,
    myUid: String,
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
            frameId = if (!chat.isGroup && !chat.isOfficialWesalChat) chat.peerFrameId else "",
            frameExpiresAt = chat.peerFrameExpiresAt
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
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false)
                    )
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
