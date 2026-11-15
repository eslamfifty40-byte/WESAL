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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.wesal.messenger.core.theme.*
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.domain.model.MessageType
import com.wesal.messenger.domain.model.WesalMessage
import com.wesal.messenger.media.CameraCaptureScreen
import com.wesal.messenger.media.FullscreenImageViewer
import com.wesal.messenger.media.VideoPlayerBox
import com.wesal.messenger.presentation.components.MessageStatusTick
import com.wesal.messenger.presentation.components.WesalAvatar
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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

    var inputText by remember { mutableStateOf("") }
    var showSearchBar by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    var showAttachSheet by remember { mutableStateOf(false) }
    var showEmojiBar by remember { mutableStateOf(false) }
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
        editingMessage?.let { inputText = it.text }
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
                Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.White)
            }
        }
        return
    }

    Scaffold(
        containerColor = WesalDeepBlack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WesalSurfaceDark),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = WesalTextPrimary)
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
                            name = chat?.title ?: "وصال",
                            size = 38.dp,
                            isOnline = chat?.isGroup == false && chat?.peerOnline == true
                        )
                        Column {
                            Text(
                                text = chat?.title ?: "محادثة",
                                style = MaterialTheme.typography.titleMedium,
                                color = WesalTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val subtitle = when {
                                chat?.typingUsers?.isNotEmpty() == true -> "جاري الكتابة..."
                                chat?.isGroup == true -> "${chat?.participantIds?.size ?: 0} عضو"
                                chat?.peerOnline == true -> "متصل الآن"
                                (chat?.peerLastSeen ?: 0L) > 0L ->
                                    "آخر ظهور ${DateTimeUtils.formatChatListTime(chat!!.peerLastSeen)}"
                                else -> "مشفرة من طرف إلى طرف"
                            }
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (chat?.typingUsers?.isNotEmpty() == true || chat?.peerOnline == true) {
                                    WesalEmerald
                                } else {
                                    WesalTextSecondary
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
                            Icon(Icons.Default.Videocam, contentDescription = "مكالمة فيديو", tint = WesalTextPrimary)
                        }
                        IconButton(onClick = {
                            scope.launch {
                                val peer = viewModel.getPeerUser() ?: return@launch
                                context.startActivity(
                                    CallActivity.createOutgoingIntent(context, peer, isVideo = false)
                                )
                            }
                        }) {
                            Icon(Icons.Default.Call, contentDescription = "مكالمة صوتية", tint = WesalTextPrimary)
                        }
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "المزيد", tint = WesalTextPrimary)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            if (chat?.isGroup == false && !chat?.peerUid.isNullOrBlank()) {
                                DropdownMenuItem(
                                    text = { Text("عرض الملف الشخصي") },
                                    onClick = {
                                        onOpenUserProfile(chat!!.peerUid)
                                        showMenu = false
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("بحث في المحادثة") },
                                onClick = { showSearchBar = !showSearchBar; showMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("التحقق من التشفير الطرفي") },
                                onClick = { showFingerprintDialog = true; showMenu = false }
                            )
                            if (chat?.isGroup == false && !chat?.peerUid.isNullOrBlank()) {
                                DropdownMenuItem(
                                    text = { Text("حظر المستخدم", color = WesalError) },
                                    onClick = {
                                        viewModel.blockPeer(chat!!.peerUid)
                                        showMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("إبلاغ عن إساءة") },
                                    onClick = {
                                        viewModel.reportPeer(chat!!.peerUid, "إبلاغ من داخل المحادثة")
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
                    placeholder = { Text("ابحث داخل الرسائل...") },
                    trailingIcon = {
                        IconButton(onClick = { viewModel.setSearchQuery(""); showSearchBar = false }) {
                            Icon(Icons.Default.Close, contentDescription = "إغلاق البحث")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            // Messages List
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
                        color = WesalBubbleOutgoing.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(0.75f)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("جاري إرسال: ${upload.fileName}", color = WesalTextPrimary, style = MaterialTheme.typography.bodyMedium)
                            if (upload.error != null) {
                                Text(upload.error, color = WesalError, style = MaterialTheme.typography.labelSmall)
                                TextButton(onClick = { viewModel.retryUpload(upload) }) {
                                    Text("إعادة المحاولة", color = WesalTurquoise)
                                }
                            } else {
                                LinearProgressIndicator(progress = { upload.progress }, color = WesalTurquoise)
                            }
                        }
                    }
                }
            }

            // Reply / Edit Banner
            val bannerTitle = when {
                editingMessage != null -> "تعديل الرسالة"
                replyingTo != null -> "رد على ${replyingTo!!.senderName}"
                else -> null
            }
            if (bannerTitle != null) {
                Surface(color = WesalSurfaceElevated, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(bannerTitle, color = WesalTurquoise, style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = (editingMessage?.text ?: replyingTo?.text).orEmpty(),
                                color = WesalTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        IconButton(onClick = {
                            viewModel.setReplyingTo(null)
                            viewModel.setEditingMessage(null)
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "إلغاء", tint = WesalTextSecondary)
                        }
                    }
                }
            }

            if (showEmojiBar) {
                val emojis = listOf("👍", "❤️", "😂", "🙏", "🔥", "👏", "🎉", "😢", "🤝", "✅")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(WesalSurfaceDark)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    emojis.forEach { emoji ->
                        Text(
                            text = emoji,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.clickable {
                                inputText += emoji
                                viewModel.onInputTextChanged(inputText)
                            }
                        )
                    }
                }
            }

            // Recording indicator or Composer bar
            if (recordingState.isRecording) {
                Surface(color = WesalSurfaceDark, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, tint = WesalError)
                        Text(
                            text = DateTimeUtils.formatDurationMillis(recordingState.elapsedMs),
                            color = WesalTextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        // Live amplitude waveform preview
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
                                        .background(WesalTurquoise)
                                )
                            }
                        }
                        TextButton(onClick = viewModel::cancelVoiceRecording) {
                            Text("إلغاء", color = WesalError)
                        }
                        Button(
                            onClick = viewModel::stopAndSendVoiceRecording,
                            colors = ButtonDefaults.buttonColors(containerColor = WesalEmerald, contentColor = WesalDeepBlack)
                        ) {
                            Text("إرسال", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(WesalSurfaceDark)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(onClick = { showEmojiBar = !showEmojiBar }) {
                        Icon(Icons.Default.EmojiEmotions, contentDescription = "رموز تعبيرية", tint = WesalTextSecondary)
                    }
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = {
                            inputText = it
                            viewModel.onInputTextChanged(it)
                        },
                        placeholder = { Text("اكتب رسالة...") },
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp),
                        trailingIcon = {
                            Row {
                                IconButton(onClick = { showAttachSheet = true }) {
                                    Icon(Icons.Default.AttachFile, contentDescription = "إرفاق", tint = WesalTextSecondary)
                                }
                                IconButton(onClick = { showCamera = true }) {
                                    Icon(Icons.Default.CameraAlt, contentDescription = "كاميرا", tint = WesalTextSecondary)
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = WesalTurquoise,
                            unfocusedBorderColor = WesalSurfaceBorder,
                            focusedContainerColor = WesalSurfaceElevated,
                            unfocusedContainerColor = WesalSurfaceElevated
                        )
                    )

                    if (inputText.isNotBlank()) {
                        FloatingActionButton(
                            onClick = {
                                viewModel.sendOrEditText(inputText)
                                inputText = ""
                            },
                            modifier = Modifier.size(48.dp),
                            containerColor = WesalEmerald,
                            contentColor = WesalDeepBlack
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "إرسال")
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(WesalEmerald)
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
                                            // Hold & release (>= 700ms) sends immediately; short tap leaves bar open for manual Send/Cancel
                                            if (released && heldMs >= 700L) {
                                                viewModel.stopAndSendVoiceRecording()
                                            }
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = "اضغط مطولاً أو انقر للتسجيل الصوتي", tint = WesalDeepBlack)
                        }
                    }
                }
            }
        }
    }

    if (showAttachSheet) {
        ModalBottomSheet(onDismissRequest = { showAttachSheet = false }, containerColor = WesalSurfaceDark) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("إرسال مرفق", style = MaterialTheme.typography.titleLarge, color = WesalTextPrimary)
                ListItem(
                    headlineContent = { Text("صور أو فيديو من المعرض") },
                    leadingContent = { Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = WesalTurquoise) },
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        photoVideoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    }
                )
                ListItem(
                    headlineContent = { Text("ملف (PDF / Word / Excel / نصوص)") },
                    leadingContent = { Icon(Icons.Default.Description, contentDescription = null, tint = WesalEmerald) },
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
                    headlineContent = { Text("مشاركة الموقع الجغرافي الحالي") },
                    leadingContent = { Icon(Icons.Default.LocationOn, contentDescription = null, tint = WesalWarning) },
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        if (locationPermissions.allPermissionsGranted) {
                            val client = LocationServices.getFusedLocationProviderClient(context)
                            client.lastLocation.addOnSuccessListener { loc ->
                                if (loc != null) {
                                    viewModel.sendLocation(
                                        loc.latitude,
                                        loc.longitude,
                                        "الموقع الجغرافي (${String.format("%.4f", loc.latitude)}, ${String.format("%.4f", loc.longitude)})"
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
            containerColor = WesalSurfaceDark,
            title = { Text("خيارات الرسالة", color = WesalTextPrimary) },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text("رد") },
                        leadingContent = { Icon(Icons.Default.Reply, contentDescription = null) },
                        modifier = Modifier.clickable { viewModel.setReplyingTo(msg); selectedMessageForActions = null }
                    )
                    ListItem(
                        headlineContent = { Text("إعادة توجيه") },
                        leadingContent = { Icon(Icons.Default.Forward, contentDescription = null) },
                        modifier = Modifier.clickable { forwardingMessage = msg; selectedMessageForActions = null }
                    )
                    if (msg.text.isNotBlank()) {
                        ListItem(
                            headlineContent = { Text("نسخ النص") },
                            leadingContent = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                            modifier = Modifier.clickable {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("wesal_msg", msg.text))
                                selectedMessageForActions = null
                            }
                        )
                    }
                    if (msg.senderId == viewModel.myUid && msg.type == MessageType.TEXT && !msg.isDeletedForEveryone) {
                        ListItem(
                            headlineContent = { Text("تعديل الرسالة") },
                            leadingContent = { Icon(Icons.Default.Edit, contentDescription = null) },
                            modifier = Modifier.clickable { viewModel.setEditingMessage(msg); selectedMessageForActions = null }
                        )
                    }
                    ListItem(
                        headlineContent = { Text("حذف لدي") },
                        leadingContent = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                        modifier = Modifier.clickable { viewModel.deleteForMe(msg.messageId); selectedMessageForActions = null }
                    )
                    if (msg.senderId == viewModel.myUid && !msg.isDeletedForEveryone) {
                        ListItem(
                            headlineContent = { Text("حذف لدى الجميع", color = WesalError) },
                            leadingContent = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = WesalError) },
                            modifier = Modifier.clickable { viewModel.deleteForEveryone(msg.messageId); selectedMessageForActions = null }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedMessageForActions = null }) { Text("إغلاق") }
            }
        )
    }

    forwardingMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { forwardingMessage = null },
            containerColor = WesalSurfaceDark,
            title = { Text("إعادة توجيه إلى", color = WesalTextPrimary) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(allChats, key = { it.chatId }) { target ->
                        ListItem(
                            headlineContent = { Text(target.title, color = WesalTextPrimary) },
                            modifier = Modifier.clickable {
                                viewModel.forwardMessage(target.chatId, msg)
                                forwardingMessage = null
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { forwardingMessage = null }) { Text("إلغاء") }
            }
        )
    }

    if (showFingerprintDialog) {
        val fp = viewModel.getSafetyFingerprint()
        val verified = remember(fp) { fp != null && viewModel.isEncryptionVerified() }
        AlertDialog(
            onDismissRequest = { showFingerprintDialog = false },
            containerColor = WesalSurfaceDark,
            title = { Text("رمز أمان التشفير الطرفي", color = WesalTextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "قارن بصمة المفتاح التالية مع الطرف الآخر للتحقق من عدم وجود وسيط:",
                        color = WesalTextSecondary
                    )
                    Text(
                        text = fp ?: "التشفير الطرفي يستخدم مفاتيح الجلسة لكل عضو في المجموعة",
                        color = WesalTurquoise,
                        fontWeight = FontWeight.Bold
                    )
                    if (fp != null) {
                        Text(
                            text = if (verified) "تم التحقق من التشفير الثنائي ECDH + AES-GCM بنجاح ✓" else "تعذر التحقق من التشفير — أعد مزامنة مفاتيح الجلسة",
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
    LaunchedEffect(message.messageId, message.type) {
        if ((message.type == MessageType.IMAGE) && decryptedFile == null && message.mediaUrl.isNotBlank()) {
            onRequestMedia()
        }
    }

    val alignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (isMine) WesalBubbleOutgoing else WesalBubbleIncoming

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
                    Text(message.senderName, style = MaterialTheme.typography.labelMedium, color = WesalTurquoise)
                }

                if (!message.forwardedFromName.isNullOrBlank()) {
                    Text("محولة من ${message.forwardedFromName}", style = MaterialTheme.typography.labelSmall, color = WesalTextSecondary)
                }

                if (!message.replyToPreview.isNullOrBlank()) {
                    Surface(color = Color.Black.copy(alpha = 0.25f), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            text = message.replyToPreview,
                            style = MaterialTheme.typography.labelSmall,
                            color = WesalTextSecondary,
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
                                .background(WesalSurfaceDark)
                                .clickable { onOpenImage() },
                            contentAlignment = Alignment.Center
                        ) {
                            if (decryptedFile != null) {
                                AsyncImage(
                                    model = decryptedFile,
                                    contentDescription = "صورة",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                CircularProgressIndicator(color = WesalTurquoise, modifier = Modifier.size(26.dp))
                            }
                        }
                        if (message.text.isNotBlank()) {
                            Text(message.text, style = MaterialTheme.typography.bodyLarge, color = WesalTextPrimary)
                        }
                    }

                    MessageType.VIDEO -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(WesalSurfaceDark)
                                .clickable { onPlayVideo() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PlayCircleFilled, contentDescription = "تشغيل الفيديو", tint = WesalTurquoise, modifier = Modifier.size(52.dp))
                        }
                        if (message.text.isNotBlank()) {
                            Text(message.text, style = MaterialTheme.typography.bodyLarge, color = WesalTextPrimary)
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
                                    contentDescription = if (isVoicePlaying) "إيقاف مؤقت" else "تشغيل الصوت",
                                    tint = WesalTurquoise,
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
                                                .background(if (isPlayed) WesalTurquoise else WesalSurfaceBorder)
                                        )
                                    }
                                }
                                Text(
                                    text = DateTimeUtils.formatDurationMillis(message.mediaDurationMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = WesalTextSecondary
                                )
                            }
                        }
                    }

                    MessageType.DOCUMENT -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Black.copy(alpha = 0.22f))
                                .clickable { onOpenDocument() }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = WesalTurquoise)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(message.mediaFileName.ifBlank { "مستند" }, color = WesalTextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${(message.mediaSizeBytes / 1024L).coerceAtLeast(1)} KB", style = MaterialTheme.typography.labelSmall, color = WesalTextSecondary)
                            }
                            Icon(Icons.Default.Download, contentDescription = "فتح", tint = WesalEmerald)
                        }
                    }

                    MessageType.LOCATION -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Black.copy(alpha = 0.22f))
                                .clickable { onOpenLocation() }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = WesalEmerald)
                            Column(modifier = Modifier.weight(1f)) {
                                Text("موقع جغرافي", color = WesalTextPrimary, fontWeight = FontWeight.Bold)
                                Text(message.locationAddress, style = MaterialTheme.typography.labelSmall, color = WesalTextSecondary)
                            }
                        }
                    }

                    else -> {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (message.isDeletedForEveryone) WesalTextMuted else WesalTextPrimary
                        )
                    }
                }

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (message.isEdited) {
                        Text("معدلة", style = MaterialTheme.typography.labelSmall, color = WesalTextMuted)
                    }
                    Text(
                        text = DateTimeUtils.formatMessageTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = WesalTextSecondary
                    )
                    if (isMine) {
                        MessageStatusTick(status = message.status)
                    }
                }
            }
        }
    }
}
