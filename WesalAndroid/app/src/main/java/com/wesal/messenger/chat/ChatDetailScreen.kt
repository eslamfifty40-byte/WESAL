package com.wesal.messenger.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState
import com.google.android.gms.location.LocationServices
import com.wesal.messenger.calls.CallActivity
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.domain.model.MessageType
import com.wesal.messenger.domain.model.WesalMessage
import com.wesal.messenger.media.CameraCaptureScreen
import com.wesal.messenger.media.FullscreenImageViewer
import com.wesal.messenger.media.VideoPlayerBox
import com.wesal.messenger.presentation.components.MessageStatusTick
import com.wesal.messenger.presentation.components.WesalAvatar
import com.wesal.messenger.presentation.components.WesalEmojiKeyboard
import com.wesal.messenger.presentation.components.WesalEmojiUtils
import com.wesal.messenger.presentation.components.WesalNameWithBadge
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun ChatDetailScreen(
    onBack: () -> Unit,
    onOpenGroupInfo: (String) -> Unit,
    onOpenUserProfile: (String) -> Unit = {},
    viewModel: ChatDetailViewModel = hiltViewModel()
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val composerFocusRequester = remember { FocusRequester() }

    val chat by viewModel.chatPreview.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val replyingTo by viewModel.replyingTo.collectAsStateWithLifecycle()
    val editingMessage by viewModel.editingMessage.collectAsStateWithLifecycle()
    val pendingUploads by viewModel.pendingUploads.collectAsStateWithLifecycle()
    val decryptedFiles by viewModel.decryptedFiles.collectAsStateWithLifecycle()
    val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val toastMsg by viewModel.toastMessage.collectAsStateWithLifecycle()
    val allChats by viewModel.allChatsForForward.collectAsStateWithLifecycle()
    val recentEmojis by viewModel.recentEmojis.collectAsStateWithLifecycle()

    var inputValue by remember { mutableStateOf(TextFieldValue("")) }
    var showSearchBar by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    var showAttachSheet by remember { mutableStateOf(false) }
    var showEmojiKeyboard by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showFingerprintDialog by remember { mutableStateOf(false) }
    var fullscreenImageFile by remember { mutableStateOf<File?>(null) }
    var playingVideoFile by remember { mutableStateOf<File?>(null) }
    var selectedMessageForActions by remember { mutableStateOf<WesalMessage?>(null) }
    var forwardingMessage by remember { mutableStateOf<WesalMessage?>(null) }

    val listState = rememberLazyListState()
    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    val locationPermissions = rememberMultiplePermissionsState(
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    )

    val photoVideoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri).orEmpty()
            viewModel.sendPickedMedia(uri, isVideo = mime.startsWith("video"))
        }
    }

    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.sendDocument(uri)
    }

    LaunchedEffect(editingMessage?.messageId) {
        editingMessage?.let {
            inputValue = TextFieldValue(it.text, TextRange(it.text.length))
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
            viewModel.markRead()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearToast()
        }
    }

    if (showCamera) {
        CameraCaptureScreen(
            onMediaConfirmed = { uri, isVideo, caption ->
                viewModel.sendPickedMedia(uri, isVideo, caption)
                showCamera = false
            },
            onClose = { showCamera = false }
        )
        return
    }

    fullscreenImageFile?.let { file ->
        FullscreenImageViewer(
            imageModel = file,
            onClose = { fullscreenImageFile = null }
        )
        return
    }

    playingVideoFile?.let { file ->
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            VideoPlayerBox(uri = Uri.fromFile(file), modifier = Modifier.fillMaxSize())
            IconButton(
                onClick = { playingVideoFile = null },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = strings.close, tint = Color.White)
            }
        }
        return
    }

    Scaffold(
        containerColor = palette.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.surface),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back, tint = palette.textPrimary)
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.clickable {
                            val curChat = chat
                            if (curChat?.isGroup == true && !curChat.groupId.isNullOrBlank()) {
                                onOpenGroupInfo(curChat.groupId)
                            } else if (curChat != null && curChat.peerUid.isNotBlank()) {
                                onOpenUserProfile(curChat.peerUid)
                            } else {
                                showFingerprintDialog = true
                            }
                        }
                    ) {
                        WesalAvatar(
                            imageUrl = chat?.avatarUrl.orEmpty(),
                            name = chat?.title ?: strings.appShortName,
                            size = 38.dp,
                            isOnline = chat?.isGroup == false && chat?.peerOnline == true
                        )
                        Column {
                            WesalNameWithBadge(
                                name = chat?.title ?: if (strings.isEnglish) "Chat" else "محادثة",
                                isVerified = chat?.isGroup == false && chat?.peerVerified == true,
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.textPrimary
                            )
                            val subtitle = when {
                                chat?.typingUsers?.isNotEmpty() == true -> strings.typingNow
                                chat?.isGroup == true -> if (strings.isEnglish) "${chat?.participantIds?.size ?: 0} members" else "${chat?.participantIds?.size ?: 0} عضو"
                                chat?.peerOnline == true -> strings.onlineNow
                                (chat?.peerLastSeen ?: 0L) > 0L ->
                                    if (strings.isEnglish) "Last seen ${DateTimeUtils.formatChatListTime(chat!!.peerLastSeen)}"
                                    else "آخر ظهور ${DateTimeUtils.formatChatListTime(chat!!.peerLastSeen)}"
                                else -> if (strings.isEnglish) "End-to-end encrypted" else "مشفرة من طرف إلى طرف"
                            }
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (chat?.typingUsers?.isNotEmpty() == true || chat?.peerOnline == true) {
                                    palette.emerald
                                } else {
                                    palette.textSecondary
                                }
                            )
                        }
                    }
                },
                actions = {
                    if (chat?.isGroup == false) {
                        IconButton(onClick = {
                            scope.launch {
                                val peer = viewModel.getPeerUser() ?: return@launch
                                context.startActivity(
                                    CallActivity.createOutgoingIntent(context, peer, isVideo = true)
                                )
                            }
                        }) {
                            Icon(Icons.Default.Videocam, contentDescription = null, tint = palette.textPrimary)
                        }
                        IconButton(onClick = {
                            scope.launch {
                                val peer = viewModel.getPeerUser() ?: return@launch
                                context.startActivity(
                                    CallActivity.createOutgoingIntent(context, peer, isVideo = false)
                                )
                            }
                        }) {
                            Icon(Icons.Default.Call, contentDescription = null, tint = palette.textPrimary)
                        }
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = strings.more, tint = palette.textPrimary)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            if (chat?.isGroup == false && !chat?.peerUid.isNullOrBlank()) {
                                DropdownMenuItem(
                                    text = { Text(if (strings.isEnglish) "View Profile" else "عرض الملف الشخصي") },
                                    onClick = {
                                        onOpenUserProfile(chat!!.peerUid)
                                        showMenu = false
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if (strings.isEnglish) "Search in Chat" else "بحث في المحادثة") },
                                onClick = { showSearchBar = !showSearchBar; showMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text(if (strings.isEnglish) "Verify E2EE Fingerprint" else "التحقق من التشفير الطرفي") },
                                onClick = { showFingerprintDialog = true; showMenu = false }
                            )
                            if (chat?.isGroup == false && !chat?.peerUid.isNullOrBlank()) {
                                DropdownMenuItem(
                                    text = { Text(if (strings.isEnglish) "Block User" else "حظر المستخدم", color = palette.error) },
                                    onClick = {
                                        viewModel.blockPeer(chat!!.peerUid)
                                        showMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (strings.isEnglish) "Report Abuse" else "إبلاغ عن إساءة") },
                                    onClick = {
                                        viewModel.reportPeer(chat!!.peerUid, "Reported from chat")
                                        showMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (showSearchBar) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = viewModel::setSearchQuery,
                    placeholder = {
                        Text(
                            text = if (strings.isEnglish) "Search messages..." else "ابحث داخل الرسائل...",
                            color = palette.textMuted
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { viewModel.setSearchQuery(""); showSearchBar = false }) {
                            Icon(Icons.Default.Close, contentDescription = strings.close, tint = palette.textSecondary)
                        }
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = palette.textPrimary,
                        unfocusedTextColor = palette.textPrimary,
                        cursorColor = palette.turquoise,
                        focusedBorderColor = palette.turquoise,
                        unfocusedBorderColor = palette.border,
                        focusedPlaceholderColor = palette.textMuted,
                        unfocusedPlaceholderColor = palette.textMuted,
                        focusedContainerColor = palette.surfaceElevated,
                        unfocusedContainerColor = palette.surfaceElevated
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(messages, key = { it.messageId }) { msg ->
                    val isMine = msg.senderId == viewModel.myUid
                    MessageBubble(
                        message = msg,
                        isMine = isMine,
                        isGroup = chat?.isGroup == true,
                        decryptedFile = decryptedFiles[msg.messageId],
                        isVoicePlaying = playbackState.activeMessageId == msg.messageId && playbackState.isPlaying,
                        voiceProgress = if (playbackState.activeMessageId == msg.messageId && playbackState.durationMs > 0) {
                            playbackState.positionMs.toFloat() / playbackState.durationMs.toFloat()
                        } else 0f,
                        onRequestMedia = { viewModel.resolveMediaFile(msg) },
                        onOpenImage = { viewModel.resolveMediaFile(msg) { fullscreenImageFile = it } },
                        onPlayVideo = { viewModel.resolveMediaFile(msg) { playingVideoFile = it } },
                        onToggleVoice = { viewModel.toggleVoicePlayback(msg) },
                        onOpenDocument = {
                            viewModel.resolveMediaFile(msg) { file ->
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, msg.mediaMimeType.ifBlank { "*/*" })
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching { context.startActivity(intent) }
                            }
                        },
                        onOpenLocation = {
                            val lat = msg.latitude ?: 0.0
                            val lng = msg.longitude ?: 0.0
                            val geo = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(msg.locationAddress)})")
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, geo)) }
                        },
                        onLongClick = { selectedMessageForActions = msg }
                    )
                }

                items(pendingUploads, key = { it.id }) { upload ->
                    Surface(
                        color = palette.bubbleOutgoing.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(0.75f)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = if (strings.isEnglish) "Sending: ${upload.fileName}" else "جاري إرسال: ${upload.fileName}",
                                color = palette.textPrimary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (upload.error != null) {
                                Text(upload.error, color = palette.error, style = MaterialTheme.typography.labelSmall)
                                TextButton(onClick = { viewModel.retryUpload(upload) }) {
                                    Text(strings.retry, color = palette.turquoise)
                                }
                            } else {
                                LinearProgressIndicator(progress = { upload.progress }, color = palette.turquoise)
                            }
                        }
                    }
                }
            }

            // Reply / Edit Banner
            val bannerTitle = when {
                editingMessage != null -> if (strings.isEnglish) "Editing message" else "تعديل الرسالة"
                replyingTo != null -> if (strings.isEnglish) "Reply to ${replyingTo!!.senderName}" else "رد على ${replyingTo!!.senderName}"
                else -> null
            }
            if (bannerTitle != null) {
                Surface(color = palette.surfaceElevated, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(bannerTitle, color = palette.turquoise, style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = (editingMessage?.text ?: replyingTo?.text).orEmpty(),
                                color = palette.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        IconButton(onClick = {
                            viewModel.setReplyingTo(null)
                            viewModel.setEditingMessage(null)
                        }) {
                            Icon(Icons.Default.Close, contentDescription = strings.cancel, tint = palette.textSecondary)
                        }
                    }
                }
            }

            // Composer / Recording Bar
            if (recordingState.isRecording) {
                Surface(color = palette.surface, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, tint = palette.error)
                        Text(
                            text = DateTimeUtils.formatDurationMillis(recordingState.elapsedMs),
                            color = palette.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        val liveBars = if (recordingState.amplitudes.isEmpty()) {
                            List(18) { 20 }
                        } else {
                            recordingState.amplitudes.takeLast(18)
                        }
                        Row(
                            modifier = Modifier.weight(1f).height(26.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            liveBars.forEach { amp ->
                                val barHeight = (6 + (amp / 100f) * 20f).dp
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(barHeight)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(palette.turquoise)
                                )
                            }
                        }
                        TextButton(onClick = viewModel::cancelVoiceRecording) {
                            Text(strings.cancel, color = palette.error)
                        }
                        Button(
                            onClick = viewModel::stopAndSendVoiceRecording,
                            colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary)
                        ) {
                            Text(strings.send, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.surface)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = {
                            if (showEmojiKeyboard) {
                                showEmojiKeyboard = false
                                composerFocusRequester.requestFocus()
                                keyboardController?.show()
                            } else {
                                keyboardController?.hide()
                                showEmojiKeyboard = true
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (showEmojiKeyboard) Icons.Default.Keyboard else Icons.Default.EmojiEmotions,
                            contentDescription = strings.switchToTextKeyboard,
                            tint = if (showEmojiKeyboard) palette.emerald else palette.textSecondary
                        )
                    }
                    OutlinedTextField(
                        value = inputValue,
                        onValueChange = {
                            inputValue = it
                            viewModel.onInputTextChanged(it.text)
                        },
                        placeholder = {
                            Text(if (strings.isEnglish) "Type a message..." else "اكتب رسالة...")
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(composerFocusRequester)
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused && showEmojiKeyboard) {
                                    showEmojiKeyboard = false
                                }
                            },
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp),
                        trailingIcon = {
                            Row {
                                IconButton(onClick = { showAttachSheet = true }) {
                                    Icon(Icons.Default.AttachFile, contentDescription = null, tint = palette.textSecondary)
                                }
                                IconButton(onClick = { showCamera = true }) {
                                    Icon(Icons.Default.CameraAlt, contentDescription = strings.camera, tint = palette.textSecondary)
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = palette.textPrimary,
                            unfocusedTextColor = palette.textPrimary,
                            cursorColor = palette.turquoise,
                            focusedBorderColor = palette.turquoise,
                            unfocusedBorderColor = palette.border,
                            focusedPlaceholderColor = palette.textMuted,
                            unfocusedPlaceholderColor = palette.textMuted,
                            focusedContainerColor = palette.surfaceElevated,
                            unfocusedContainerColor = palette.surfaceElevated
                        )
                    )

                    if (inputValue.text.isNotBlank()) {
                        FloatingActionButton(
                            onClick = {
                                viewModel.sendOrEditText(inputValue.text)
                                inputValue = TextFieldValue("")
                            },
                            modifier = Modifier.size(48.dp),
                            containerColor = palette.emerald,
                            contentColor = palette.onPrimary
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = strings.send)
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(palette.emerald)
                                .pointerInput(micPermission.status.isGranted) {
                                    detectTapGestures(
                                        onPress = {
                                            if (!micPermission.status.isGranted) {
                                                micPermission.launchPermissionRequest()
                                                return@detectTapGestures
                                            }
                                            val pressedAt = System.currentTimeMillis()
                                            viewModel.startVoiceRecording()
                                            val released = tryAwaitRelease()
                                            val heldMs = System.currentTimeMillis() - pressedAt
                                            if (released && heldMs >= 700L) {
                                                viewModel.stopAndSendVoiceRecording()
                                            }
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = null, tint = palette.onPrimary)
                        }
                    }
                }
            }

            if (showEmojiKeyboard) {
                WesalEmojiKeyboard(
                    recentEmojis = recentEmojis,
                    onEmojiSelected = { emoji ->
                        inputValue = WesalEmojiUtils.insertAtSelection(inputValue, emoji)
                        viewModel.recordRecentEmoji(emoji)
                        viewModel.onInputTextChanged(inputValue.text)
                    },
                    onBackspace = {
                        inputValue = WesalEmojiUtils.backspaceAtSelection(inputValue)
                        viewModel.onInputTextChanged(inputValue.text)
                    },
                    onSwitchToSystemKeyboard = {
                        showEmojiKeyboard = false
                        composerFocusRequester.requestFocus()
                        keyboardController?.show()
                    }
                )
            }
        }
    }

    if (showAttachSheet) {
        ModalBottomSheet(onDismissRequest = { showAttachSheet = false }, containerColor = palette.surface) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = if (strings.isEnglish) "Send Attachment" else "إرسال مرفق",
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.textPrimary
                )
                ListItem(
                    headlineContent = { Text(if (strings.isEnglish) "Photos or Videos" else "صور أو فيديو من المعرض", color = palette.textPrimary) },
                    leadingContent = { Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = palette.turquoise) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        photoVideoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    }
                )
                ListItem(
                    headlineContent = { Text(if (strings.isEnglish) "Document (PDF / Office / Text)" else "ملف (PDF / Word / Excel / نصوص)", color = palette.textPrimary) },
                    leadingContent = { Icon(Icons.Default.Description, contentDescription = null, tint = palette.emerald) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        documentPicker.launch(
                            arrayOf(
                                "application/pdf",
                                "application/msword",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "application/vnd.ms-excel",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "text/plain",
                                "*/*"
                            )
                        )
                    }
                )
                ListItem(
                    headlineContent = { Text(if (strings.isEnglish) "Share Current Location" else "مشاركة الموقع الجغرافي الحالي", color = palette.textPrimary) },
                    leadingContent = { Icon(Icons.Default.LocationOn, contentDescription = null, tint = palette.warning) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        if (locationPermissions.allPermissionsGranted) {
                            val client = LocationServices.getFusedLocationProviderClient(context)
                            client.lastLocation.addOnSuccessListener { loc ->
                                if (loc != null) {
                                    viewModel.sendLocation(
                                        loc.latitude,
                                        loc.longitude,
                                        "Location (${String.format("%.4f", loc.latitude)}, ${String.format("%.4f", loc.longitude)})"
                                    )
                                }
                            }
                        } else {
                            locationPermissions.launchMultiplePermissionRequest()
                        }
                    }
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    selectedMessageForActions?.let { msg ->
        AlertDialog(
            onDismissRequest = { selectedMessageForActions = null },
            containerColor = palette.surface,
            title = { Text(if (strings.isEnglish) "Message Actions" else "خيارات الرسالة", color = palette.textPrimary) },
            text = {
                Column {
                    val actionItemColors = ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                        headlineColor = palette.textPrimary,
                        leadingIconColor = palette.textSecondary
                    )
                    ListItem(
                        headlineContent = { Text(if (strings.isEnglish) "Reply" else "رد") },
                        leadingContent = { Icon(Icons.Default.Reply, contentDescription = null) },
                        colors = actionItemColors,
                        modifier = Modifier.clickable { viewModel.setReplyingTo(msg); selectedMessageForActions = null }
                    )
                    ListItem(
                        headlineContent = { Text(if (strings.isEnglish) "Forward" else "إعادة توجيه") },
                        leadingContent = { Icon(Icons.Default.Forward, contentDescription = null) },
                        colors = actionItemColors,
                        modifier = Modifier.clickable { forwardingMessage = msg; selectedMessageForActions = null }
                    )
                    if (msg.text.isNotBlank()) {
                        ListItem(
                            headlineContent = { Text(strings.copy) },
                            leadingContent = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                            colors = actionItemColors,
                            modifier = Modifier.clickable {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("wesal_msg", msg.text))
                                selectedMessageForActions = null
                            }
                        )
                    }
                    if (msg.senderId == viewModel.myUid && msg.type == MessageType.TEXT && !msg.isDeletedForEveryone) {
                        ListItem(
                            headlineContent = { Text(strings.edit) },
                            leadingContent = { Icon(Icons.Default.Edit, contentDescription = null) },
                            colors = actionItemColors,
                            modifier = Modifier.clickable { viewModel.setEditingMessage(msg); selectedMessageForActions = null }
                        )
                    }
                    ListItem(
                        headlineContent = { Text(if (strings.isEnglish) "Delete for me" else "حذف لدي") },
                        leadingContent = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                        colors = actionItemColors,
                        modifier = Modifier.clickable { viewModel.deleteForMe(msg.messageId); selectedMessageForActions = null }
                    )
                    if (msg.senderId == viewModel.myUid && !msg.isDeletedForEveryone) {
                        ListItem(
                            headlineContent = { Text(if (strings.isEnglish) "Delete for everyone" else "حذف لدى الجميع", color = palette.error) },
                            leadingContent = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = palette.error) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { viewModel.deleteForEveryone(msg.messageId); selectedMessageForActions = null }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedMessageForActions = null }) { Text(strings.close) }
            }
        )
    }

    forwardingMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { forwardingMessage = null },
            containerColor = palette.surface,
            title = { Text(if (strings.isEnglish) "Forward to" else "إعادة توجيه إلى", color = palette.textPrimary) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(allChats, key = { it.chatId }) { target ->
                        ListItem(
                            headlineContent = { Text(target.title, color = palette.textPrimary) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable {
                                viewModel.forwardMessage(target.chatId, msg)
                                forwardingMessage = null
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { forwardingMessage = null }) { Text(strings.cancel) }
            }
        )
    }

    if (showFingerprintDialog) {
        val fp = viewModel.getSafetyFingerprint()
        val verified = remember(fp) { fp != null && viewModel.isEncryptionVerified() }
        AlertDialog(
            onDismissRequest = { showFingerprintDialog = false },
            containerColor = palette.surface,
            title = { Text(if (strings.isEnglish) "E2EE Safety Number" else "رمز أمان التشفير الطرفي", color = palette.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (strings.isEnglish) "Compare this key fingerprint with your contact:" else "قارن بصمة المفتاح التالية مع الطرف الآخر:",
                        color = palette.textSecondary
                    )
                    Text(
                        text = fp ?: if (strings.isEnglish) "Group chats use per-member wrapped session keys" else "التشفير الطرفي يستخدم مفاتيح الجلسة لكل عضو في المجموعة",
                        color = palette.turquoise,
                        fontWeight = FontWeight.Bold
                    )
                    if (fp != null) {
                        Text(
                            text = if (verified) {
                                if (strings.isEnglish) "ECDH + AES-GCM verified ✓" else "تم التحقق من التشفير الثنائي ECDH + AES-GCM بنجاح ✓"
                            } else {
                                if (strings.isEnglish) "Re-sync keys to verify" else "تعذر التحقق من التشفير"
                            },
                            color = if (verified) palette.emerald else palette.error,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFingerprintDialog = false }) { Text(strings.confirm) }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: WesalMessage,
    isMine: Boolean,
    isGroup: Boolean,
    decryptedFile: File?,
    isVoicePlaying: Boolean,
    voiceProgress: Float,
    onRequestMedia: () -> Unit,
    onOpenImage: () -> Unit,
    onPlayVideo: () -> Unit,
    onToggleVoice: () -> Unit,
    onOpenDocument: () -> Unit,
    onOpenLocation: () -> Unit,
    onLongClick: () -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current

    LaunchedEffect(message.messageId, message.type) {
        if ((message.type == MessageType.IMAGE) && decryptedFile == null && message.mediaUrl.isNotBlank()) {
            onRequestMedia()
        }
    }

    val alignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (isMine) palette.bubbleOutgoing else palette.bubbleIncoming

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isMine) 16.dp else 4.dp,
                bottomEnd = if (isMine) 4.dp else 16.dp
            ),
            modifier = Modifier
                .widthIn(max = 300.dp)
                .combinedClickable(onClick = {}, onLongClick = onLongClick)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!isMine && isGroup && message.senderName.isNotBlank()) {
                    Text(message.senderName, style = MaterialTheme.typography.labelMedium, color = palette.turquoise)
                }

                if (!message.forwardedFromName.isNullOrBlank()) {
                    Text(
                        text = if (strings.isEnglish) "Forwarded from ${message.forwardedFromName}" else "محولة من ${message.forwardedFromName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary
                    )
                }

                if (!message.replyToPreview.isNullOrBlank()) {
                    Surface(color = Color.Black.copy(alpha = 0.14f), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            text = message.replyToPreview,
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSecondary,
                            maxLines = 2,
                            modifier = Modifier.padding(6.dp)
                        )
                    }
                }

                when (message.type) {
                    MessageType.IMAGE -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.surface)
                                .clickable { onOpenImage() },
                            contentAlignment = Alignment.Center
                        ) {
                            if (decryptedFile != null) {
                                AsyncImage(
                                    model = decryptedFile,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                CircularProgressIndicator(color = palette.turquoise, modifier = Modifier.size(26.dp))
                            }
                        }
                        if (message.text.isNotBlank()) {
                            Text(message.text, style = MaterialTheme.typography.bodyLarge, color = palette.textPrimary)
                        }
                    }

                    MessageType.VIDEO -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.surface)
                                .clickable { onPlayVideo() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PlayCircleFilled, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(52.dp))
                        }
                        if (message.text.isNotBlank()) {
                            Text(message.text, style = MaterialTheme.typography.bodyLarge, color = palette.textPrimary)
                        }
                    }

                    MessageType.VOICE -> {
                        val bars = if (message.waveformAmplitudes.isNotEmpty()) {
                            message.waveformAmplitudes.take(28)
                        } else {
                            listOf(18, 36, 54, 42, 70, 88, 60, 44, 72, 92, 50, 32, 66, 80, 48, 30, 58, 74, 40, 26)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.width(235.dp)
                        ) {
                            IconButton(onClick = onToggleVoice, modifier = Modifier.size(38.dp)) {
                                Icon(
                                    imageVector = if (isVoicePlaying) Icons.Default.PauseCircleFilled else Icons.Default.PlayCircleFilled,
                                    contentDescription = null,
                                    tint = palette.turquoise,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().height(26.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    bars.forEachIndexed { idx, amp ->
                                        val frac = (idx + 1).toFloat() / bars.size.toFloat()
                                        val isPlayed = voiceProgress > 0f && frac <= voiceProgress
                                        val barHeight = (5 + (amp.coerceIn(10, 100) / 100f) * 20f).dp
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(barHeight)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(if (isPlayed) palette.turquoise else palette.border)
                                        )
                                    }
                                }
                                Text(
                                    text = DateTimeUtils.formatDurationMillis(message.mediaDurationMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.textSecondary
                                )
                            }
                        }
                    }

                    MessageType.DOCUMENT -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Black.copy(alpha = 0.14f))
                                .clickable { onOpenDocument() }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = palette.turquoise)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(message.mediaFileName.ifBlank { "Document" }, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${(message.mediaSizeBytes / 1024L).coerceAtLeast(1)} KB", style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
                            }
                            Icon(Icons.Default.Download, contentDescription = null, tint = palette.emerald)
                        }
                    }

                    MessageType.LOCATION -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Black.copy(alpha = 0.14f))
                                .clickable { onOpenLocation() }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = palette.emerald)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(if (strings.isEnglish) "Location" else "موقع جغرافي", color = palette.textPrimary, fontWeight = FontWeight.Bold)
                                Text(message.locationAddress, style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
                            }
                        }
                    }

                    else -> {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (message.isDeletedForEveryone) palette.textMuted else palette.textPrimary
                        )
                    }
                }

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (message.isEdited) {
                        Text(
                            text = if (strings.isEnglish) "edited" else "معدلة",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textMuted
                        )
                    }
                    Text(
                        text = DateTimeUtils.formatMessageTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary
                    )
                    if (isMine) {
                        MessageStatusTick(status = message.status)
                    }
                }
            }
        }
    }
}
