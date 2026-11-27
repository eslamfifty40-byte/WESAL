package com.wesal.messenger.chat

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.wesal.messenger.crop.CropActivity
import com.wesal.messenger.domain.model.DisappearingTimer
import com.wesal.messenger.domain.model.MessageStatus
import com.wesal.messenger.domain.model.MessageType
import com.wesal.messenger.domain.model.WesalMessage
import com.wesal.messenger.media.CameraCaptureScreen
import com.wesal.messenger.media.FullscreenImageViewer
import com.wesal.messenger.media.VideoPlayerBox
import com.wesal.messenger.presentation.components.MessageStatusTick
import com.wesal.messenger.presentation.components.WesalAvatar
import com.wesal.messenger.presentation.components.WesalMediaKind
import com.wesal.messenger.presentation.components.rememberSavePermissionGate
import com.wesal.messenger.presentation.components.saveMediaToGallery
import com.wesal.messenger.presentation.components.WesalEmojiKeyboard
import com.wesal.messenger.presentation.components.WesalEmojiUtils
import com.wesal.messenger.presentation.components.WesalNameWithBadge
import com.wesal.messenger.presentation.components.wesalImageModel
import com.wesal.messenger.voice.VoicePlaybackState
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class, ExperimentalFoundationApi::class)
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
    val activity = context as? Activity
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val composerFocusRequester = remember { FocusRequester() }

    val chat by viewModel.chatPreview.collectAsStateWithLifecycle()
    val peerUser by viewModel.peerUser.collectAsStateWithLifecycle()
    val isSuperAdmin by viewModel.isSuperAdmin.collectAsStateWithLifecycle()
    val personalWallpaper by viewModel.personalWallpaper.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val messageSyncState by viewModel.messageSyncState.collectAsStateWithLifecycle()
    val hasMoreOlderMessages by viewModel.hasMoreOlderMessages.collectAsStateWithLifecycle()
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
    val quickReplies by viewModel.quickReplies.collectAsStateWithLifecycle()

    // FLAG_SECURE against screenshots inside chat & view-once media viewer (Sec 17)
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // Metadata-only live presence inside chat
    LaunchedEffect(chat?.chatId, chat?.participantIds?.size) {
        if (chat != null) {
            viewModel.updatePresenceActivity(true)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            viewModel.updatePresenceActivity(false)
        }
    }

    var inputValue by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var showSearchBar by remember { mutableStateOf(false) }
    var showQuickRepliesBar by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    var showAttachSheet by remember { mutableStateOf(false) }
    var showEmojiKeyboard by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showWallpaperDialog by remember { mutableStateOf(false) }
    var showDeleteChatDialog by remember { mutableStateOf(false) }
    var showDisappearingDialog by remember { mutableStateOf(false) }
    var showLiveLocationDialog by remember { mutableStateOf(false) }
    var reportTarget by remember { mutableStateOf<WesalMessage?>(null) }
    var showReportChatDialog by remember { mutableStateOf(false) }

    var pendingDraft by remember { mutableStateOf<MediaDraftPreview?>(null) }
    var pendingDraftQueue by remember { mutableStateOf<List<MediaDraftPreview>>(emptyList()) }
    var croppedWallpaperUri by remember { mutableStateOf<Uri?>(null) }

    var fullscreenImageState by remember { mutableStateOf<Pair<File, WesalMessage?>?>(null) }
    var playingVideoState by remember { mutableStateOf<Pair<File, WesalMessage?>?>(null) }
    var selectedMessageForActions by remember { mutableStateOf<WesalMessage?>(null) }
    var forwardingMessage by remember { mutableStateOf<WesalMessage?>(null) }

    val listState = rememberLazyListState()
    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    // API ≤ 28 gate for the media savers (no-op on API 29+ scoped storage).
    val savePermissionGate = rememberSavePermissionGate()
    val locationPermissions = rememberMultiplePermissionsState(
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    )

    val draftCropLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { cropped ->
                pendingDraft = pendingDraft?.copy(uri = cropped)
            }
        }
    }

    val wallpaperCropLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { cropped ->
                croppedWallpaperUri = cropped
                showWallpaperDialog = true
            }
        }
    }

    val wallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            wallpaperCropLauncher.launch(CropActivity.createIntent(context, uri, CropActivity.MODE_WALLPAPER))
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) {
            val drafts = uris.map { viewModel.buildMediaDraft(it, MessageType.IMAGE) }
            pendingDraft = drafts.first()
            pendingDraftQueue = drafts.drop(1)
        }
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        if (uris.isNotEmpty()) {
            val drafts = uris.map { viewModel.buildMediaDraft(it, MessageType.VIDEO) }
            pendingDraft = drafts.first()
            pendingDraftQueue = drafts.drop(1)
        }
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingDraft = viewModel.buildMediaDraft(uri, MessageType.AUDIO)
            pendingDraftQueue = emptyList()
        }
    }

    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val drafts = uris.map { viewModel.buildMediaDraft(it, MessageType.DOCUMENT) }
            pendingDraft = drafts.first()
            pendingDraftQueue = drafts.drop(1)
        }
    }

    BackHandler(enabled = showEmojiKeyboard || showCamera || fullscreenImageState != null || playingVideoState != null || pendingDraft != null) {
        when {
            fullscreenImageState != null -> {
                val openedViewOnce = fullscreenImageState?.second
                fullscreenImageState = null
                if (openedViewOnce != null && openedViewOnce.isViewOnce && openedViewOnce.senderId != viewModel.myUid) {
                    viewModel.markViewOnceOpened(openedViewOnce.messageId)
                }
            }
            playingVideoState != null -> {
                val openedViewOnce = playingVideoState?.second
                playingVideoState = null
                if (openedViewOnce != null && openedViewOnce.isViewOnce && openedViewOnce.senderId != viewModel.myUid) {
                    viewModel.markViewOnceOpened(openedViewOnce.messageId)
                }
            }
            pendingDraft != null -> {
                viewModel.stopVoicePreviewPlayback()
                pendingDraft = null
                pendingDraftQueue = emptyList()
            }
            showCamera -> showCamera = false
            showEmojiKeyboard -> showEmojiKeyboard = false
        }
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
                pendingDraft = viewModel.buildMediaDraft(
                    uri = uri,
                    type = if (isVideo) MessageType.VIDEO else MessageType.IMAGE,
                    caption = caption
                )
                showCamera = false
            },
            onClose = { showCamera = false }
        )
        return
    }

    fullscreenImageState?.let { (file, sourceMsg) ->
        FullscreenImageViewer(
            imageModel = file,
            onClose = {
                fullscreenImageState = null
                if (sourceMsg != null && sourceMsg.isViewOnce && sourceMsg.senderId != viewModel.myUid) {
                    viewModel.markViewOnceOpened(sourceMsg.messageId)
                }
            },
            // View-once media from other people stays non-saveable; everything
            // else (including my own view-once) can be saved.
            canSave = !(sourceMsg != null && sourceMsg.isViewOnce && sourceMsg.senderId != viewModel.myUid)
        )
        return
    }

    playingVideoState?.let { (file, sourceMsg) ->
        val canSaveVideo = !(sourceMsg != null && sourceMsg.isViewOnce && sourceMsg.senderId != viewModel.myUid)
        var isSavingVideo by remember(sourceMsg?.messageId) { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            VideoPlayerBox(uri = Uri.fromFile(file), modifier = Modifier.fillMaxSize())
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (canSaveVideo) {
                    IconButton(
                        enabled = !isSavingVideo,
                        onClick = {
                            savePermissionGate {
                                isSavingVideo = true
                                scope.launch {
                                    saveMediaToGallery(context, file, WesalMediaKind.VIDEO)
                                        .onSuccess {
                                            Toast.makeText(
                                                context,
                                                if (strings.isEnglish) "Saved to gallery ✓" else "تم حفظ الفيديو في المعرض ✓",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        .onFailure { err ->
                                            Toast.makeText(
                                                context,
                                                err.localizedMessage ?: if (strings.isEnglish) "Save failed" else "تعذر حفظ الفيديو",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    isSavingVideo = false
                                }
                            }
                        },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    ) {
                        if (isSavingVideo) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Download, contentDescription = if (strings.isEnglish) "Save" else "حفظ الفيديو", tint = Color.White)
                        }
                    }
                }
                IconButton(
                    onClick = {
                        playingVideoState = null
                        if (sourceMsg != null && sourceMsg.isViewOnce && sourceMsg.senderId != viewModel.myUid) {
                            viewModel.markViewOnceOpened(sourceMsg.messageId)
                        }
                    }
                ) {
                    Icon(Icons.Default.Close, contentDescription = strings.close, tint = Color.White)
                }
            }
        }
        return
    }

    // Pre-send Media / Voice Preview Overlay (Crop, Caption, View-Once toggle, Play Audio Preview)
    pendingDraft?.let { draft ->
        MediaDraftPreviewScreen(
            draft = draft,
            remainingQueueCount = pendingDraftQueue.size,
            playbackState = playbackState,
            onUpdateDraft = { pendingDraft = it },
            onCropPhoto = {
                draftCropLauncher.launch(CropActivity.createIntent(context, draft.uri, CropActivity.MODE_SQUARE))
            },
            onTogglePlayVoice = { viewModel.toggleDraftVoicePlayback(draft) },
            onCancel = {
                viewModel.stopVoicePreviewPlayback()
                pendingDraft = null
                pendingDraftQueue = emptyList()
            },
            onSend = {
                viewModel.stopVoicePreviewPlayback()
                viewModel.sendMediaDraft(draft)
                if (pendingDraftQueue.isNotEmpty()) {
                    pendingDraft = pendingDraftQueue.first()
                    pendingDraftQueue = pendingDraftQueue.drop(1)
                } else {
                    pendingDraft = null
                }
            },
            onSendAll = {
                viewModel.stopVoicePreviewPlayback()
                viewModel.sendMediaDraft(draft)
                pendingDraftQueue.forEach { queued ->
                    viewModel.sendMediaDraft(queued)
                }
                pendingDraft = null
                pendingDraftQueue = emptyList()
            }
        )
        return
    }

    val isOfficialReadOnly = chat?.isOfficialWesalChat == true
    val effectiveWallpaper = personalWallpaper.ifBlank { chat?.officialWallpaperUrl.orEmpty() }

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
                            } else if (curChat != null && !curChat.isOfficialWesalChat && curChat.peerUid.isNotBlank()) {
                                onOpenUserProfile(curChat.peerUid)
                            }
                        }
                    ) {
                        WesalAvatar(
                            imageUrl = chat?.avatarUrl.orEmpty(),
                            name = chat?.title ?: strings.appShortName,
                            size = 38.dp,
                            isOnline = chat?.isGroup == false && !isOfficialReadOnly && chat?.peerOnline == true,
                            frameId = if (!isOfficialReadOnly && chat?.isGroup == false) chat?.peerFrameId.orEmpty() else "",
                            frameExpiresAt = chat?.peerFrameExpiresAt ?: 0L,
                            isCutout = !isOfficialReadOnly && chat?.isGroup == false && (chat?.peerPhotoRemovedBg == true),
                            iconUrl = if (!isOfficialReadOnly && chat?.isGroup == false) chat?.peerIconUrl.orEmpty() else "",
                            neonColor = if (!isOfficialReadOnly && chat?.isGroup == false && (chat?.peerNeonEnabled == true)) chat?.peerNeonColor.orEmpty() else "",
                            neonGlowIntensity = chat?.peerNeonGlowIntensity ?: 0.7f,
                            fallbackResId = if (isOfficialReadOnly || chat?.isSupportChat == true) com.wesal.messenger.R.drawable.wesal_brand_logo else null
                        )
                        Column {
                            WesalNameWithBadge(
                                name = chat?.title ?: if (strings.isEnglish) "Chat" else "محادثة",
                                isVerified = (chat?.isGroup == false && chat?.peerVerified == true) || isOfficialReadOnly,
                                verificationType = if (isOfficialReadOnly) "BLUE" else (chat?.peerVerificationType ?: "BLUE"),
                                neonColor = peerUser?.neonColor ?: chat?.peerNeonColor.orEmpty(),
                                neonGlowIntensity = peerUser?.neonGlowIntensity ?: (chat?.peerNeonGlowIntensity ?: 0.7f),
                                neonEnabled = if (isOfficialReadOnly || chat?.isGroup == true) false else (peerUser?.neonEnabled ?: (chat?.peerNeonEnabled == true)),
                                verificationGlowColor = peerUser?.verificationGlowColor ?: chat?.peerVerificationGlowColor.orEmpty(),
                                verificationGlowEnabled = peerUser?.verificationGlowEnabled ?: (chat?.peerVerificationGlowEnabled ?: true),
                                verificationGlowIntensity = peerUser?.verificationGlowIntensity ?: (chat?.peerVerificationGlowIntensity ?: 0.7f),
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.textPrimary
                            )
                            val activeTimer = DisappearingTimer.fromSeconds(chat?.disappearingDurationSec ?: 0L)
                            val timerSuffix = if (activeTimer != DisappearingTimer.OFF) {
                                " • ⏱ ${if (strings.isEnglish) activeTimer.labelEn else activeTimer.labelAr}"
                            } else ""
                            val subtitle = when {
                                isOfficialReadOnly -> if (strings.isEnglish) "Official WESAL Channel • Read Only" else "إشعارات وصال الرسمية • للقراءة فقط"
                                chat?.typingUsers?.isNotEmpty() == true -> strings.typingNow
                                chat?.isGroup == true -> if (strings.isEnglish) "${chat?.participantIds?.size ?: 0} members" else "${chat?.participantIds?.size ?: 0} عضو"
                                chat?.peerOnline == true -> strings.onlineNow
                                (chat?.peerLastSeen ?: 0L) > 0L ->
                                    if (strings.isEnglish) "Last seen ${DateTimeUtils.formatChatListTime(chat!!.peerLastSeen)}"
                                    else "آخر ظهور ${DateTimeUtils.formatChatListTime(chat!!.peerLastSeen)}"
                                else -> "WESAL Messenger"
                            } + timerSuffix
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
                    if (chat?.isGroup == false && !isOfficialReadOnly) {
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
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            containerColor = palette.surface
                        ) {
                            if (chat?.isGroup == false && !isOfficialReadOnly && !chat?.peerUid.isNullOrBlank()) {
                                DropdownMenuItem(
                                    text = { Text(if (strings.isEnglish) "View Profile" else "عرض الملف الشخصي", color = palette.textPrimary) },
                                    onClick = {
                                        onOpenUserProfile(chat!!.peerUid)
                                        showMenu = false
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if (strings.isEnglish) "Search in Chat" else "بحث في المحادثة", color = palette.textPrimary) },
                                onClick = { showSearchBar = !showSearchBar; showMenu = false }
                            )
                            if (!isOfficialReadOnly) {
                                DropdownMenuItem(
                                    text = { Text(if (strings.isEnglish) "Quick Replies" else "الردود السريعة", color = palette.textPrimary) },
                                    onClick = { showQuickRepliesBar = !showQuickRepliesBar; showMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (strings.isEnglish) "Disappearing Messages" else "الرسائل ذاتية الاختفاء", color = palette.textPrimary) },
                                    onClick = { showDisappearingDialog = true; showMenu = false }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if (strings.isEnglish) "Chat Wallpaper" else "خلفية المحادثة", color = palette.textPrimary) },
                                onClick = {
                                    croppedWallpaperUri = null
                                    showWallpaperDialog = true
                                    showMenu = false
                                }
                            )
                            if (chat?.isGroup == false && !isOfficialReadOnly && !chat?.peerUid.isNullOrBlank()) {
                                DropdownMenuItem(
                                    text = { Text(if (strings.isEnglish) "Block User" else "حظر المستخدم", color = palette.error) },
                                    onClick = {
                                        viewModel.blockPeer(chat!!.peerUid)
                                        showMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (strings.isEnglish) "Report Abuse" else "إبلاغ عن إساءة", color = palette.warning) },
                                    onClick = {
                                        showReportChatDialog = true
                                        showMenu = false
                                    }
                                )
                            }
                            if (!isOfficialReadOnly) {
                                DropdownMenuItem(
                                    text = { Text(if (strings.isEnglish) "Delete Chat" else "حذف المحادثة", color = palette.error) },
                                    onClick = {
                                        showDeleteChatDialog = true
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            if (effectiveWallpaper.isNotBlank()) {
                val model = remember(effectiveWallpaper) {
                    if (effectiveWallpaper.startsWith("/")) File(effectiveWallpaper)
                    else wesalImageModel(effectiveWallpaper)
                }
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(palette.background.copy(alpha = 0.52f))
                )
            }

            Column(modifier = Modifier.fillMaxSize()) {
                val diagError by WesalDiag.error.collectAsStateWithLifecycle()
                val diagStatus by WesalDiag.status.collectAsStateWithLifecycle()
                val diagProbe by WesalDiag.probe.collectAsStateWithLifecycle()
                val diagClr by WesalDiag.clearResult.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) {
                    if (WesalDiag.probe.value.isBlank()) {
                        kotlinx.coroutines.delay(6000L)
                        WesalDiag.probe.value = "فحص..."
                        WesalDiag.probe.value = runCatching {
                            com.wesal.messenger.data.repository.NetProbe.run(
                                com.google.firebase.firestore.FirebaseFirestore.getInstance(
                                    com.google.firebase.FirebaseApp.getInstance(),
                                    "ai-studio-livo-cd975dac-35e6-4dbb-864e-829e3b638424"
                                ),
                                com.google.firebase.auth.FirebaseAuth.getInstance()
                            )
                        }.getOrElse { "probe failed: ${it.javaClass.simpleName}" }
                    }
                }
                if (diagStatus.isNotBlank()) {
                    androidx.compose.material3.Text(
                        text = "v${com.wesal.messenger.BuildConfig.BUILD_STAMP} | $diagStatus | $diagProbe | $diagClr",
                        color = Color(0xFFBBBBBB),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF222222))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                diagError?.let { msg ->
                    val diagCtx = androidx.compose.ui.platform.LocalContext.current
                    androidx.compose.material3.Text(
                        text = msg + "  (اضغط للمشاركة · اضغط مطولاً للإخفاء)",
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFB00020))
                            .combinedClickable(
                                onClick = {
                                    val full = msg + "\n" + diagStatus + " | " + diagProbe + " | " + diagClr
                                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_TEXT, full)
                                    }
                                    runCatching {
                                        diagCtx.startActivity(
                                            android.content.Intent.createChooser(send, "مشاركة التشخيص")
                                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                },
                                onLongClick = { WesalDiag.error.value = null }
                            )
                            .padding(8.dp)
                    )
                }
                if (chat?.isMessageRequest == true || chat?.requestStatus == "PENDING" || chat?.requestStatus == "HELD") {
                    Surface(
                        color = palette.surfaceElevated,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = if (strings.isEnglish) "Message request" else "طلب مراسلة",
                                color = palette.turquoise,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = { viewModel.decideMessageRequest("ACCEPTED") },
                                colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) { Text(if (strings.isEnglish) "Accept" else "قبول") }
                            OutlinedButton(
                                onClick = { viewModel.decideMessageRequest("HELD") },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) { Text(if (strings.isEnglish) "Hold" else "تعليق", color = palette.warning) }
                            OutlinedButton(
                                onClick = { viewModel.decideMessageRequest("REJECTED", onRejectedDone = onBack) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) { Text(if (strings.isEnglish) "Reject" else "رفض", color = palette.error) }
                        }
                    }
                }

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

                // Spec §§1.2/13.3/13.4: messages sync banner — a listener error
                // surfaces with retry instead of a silently frozen chat, and
                // cache-served messages are labelled as such.
                if (messageSyncState.status == com.wesal.messenger.domain.model.ChatListSyncStatus.ERROR) {
                    Surface(
                        color = palette.error.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = messageSyncState.errorMessage
                                    ?: if (strings.isEnglish) "Couldn't sync messages" else "تعذر مزامنة الرسائل",
                                color = palette.error,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.retryMessageSync() }) {
                                Text(
                                    text = if (strings.isEnglish) "Retry" else "إعادة المحاولة",
                                    color = palette.error
                                )
                            }
                        }
                    }
                } else if (messageSyncState.fromCache) {
                    Text(
                        text = if (strings.isEnglish) "Offline — showing saved messages" else "غير متصل — يتم عرض الرسائل المحفوظة",
                        color = palette.textMuted,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
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
                    // Spec §13.3: the live listener streams only the newest page;
                    // older pages load on demand.
                    if (hasMoreOlderMessages && messages.isNotEmpty()) {
                        item(key = "load_older_messages") {
                            TextButton(
                                onClick = { viewModel.loadOlderMessages() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (strings.isEnglish) "Load older messages" else "تحميل رسائل أقدم",
                                    color = palette.turquoise,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }

                    items(messages, key = { it.messageId }) { msg ->
                        val isMine = msg.senderId == viewModel.myUid
                        MessageBubble(
                            message = msg,
                            isMine = isMine,
                            myUid = viewModel.myUid,
                            isGroup = chat?.isGroup == true,
                            decryptedFile = decryptedFiles[msg.messageId],
                            playbackState = playbackState,
                            onRequestMedia = {
                                if (!msg.isViewOnce) viewModel.resolveMediaFile(msg)
                            },
                            onOpenImage = {
                                viewModel.resolveMediaFile(msg) { file ->
                                    fullscreenImageState = file to msg
                                }
                            },
                            onPlayVideo = {
                                viewModel.resolveMediaFile(msg) { file ->
                                    playingVideoState = file to msg
                                }
                            },
                            onToggleVoice = { viewModel.toggleVoicePlayback(msg) },
                            onSeekAudio = { posMs -> viewModel.seekAudio(posMs) },
                            onSetVolume = { vol -> viewModel.setAudioVolume(vol) },
                            onSaveAudio = { savePermissionGate { viewModel.saveAudioToDownloads(msg, strings.isEnglish) } },
                            onSaveDocument = { savePermissionGate { viewModel.saveDocumentToDownloads(msg, strings.isEnglish) } },
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
                            onRetryFailed = { viewModel.retryFailedMessage(msg) },
                            onDismissFailed = { viewModel.dismissFailedMessage(msg.messageId) },
                            onLongClick = { selectedMessageForActions = msg }
                        )
                    }

                    items(pendingUploads, key = { it.id }) { upload ->
                        Surface(
                            color = palette.bubbleOutgoing.copy(alpha = 0.8f),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(0.78f)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (strings.isEnglish) "Sending: ${upload.fileName}" else "جاري إرسال: ${upload.fileName}",
                                        color = palette.textPrimary,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (upload.error != null) {
                                        IconButton(onClick = { viewModel.dismissUpload(upload.id) }, modifier = Modifier.size(24.dp)) {
                                            Icon(Icons.Default.Close, contentDescription = strings.close, tint = palette.textSecondary, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
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

                if (isOfficialReadOnly && !isSuperAdmin) {
                    Surface(
                        color = palette.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Verified, contentDescription = null, tint = palette.verifiedBlue, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (strings.isEnglish) {
                                    "Official announcements from WESAL Messenger — Read only"
                                } else {
                                    "قناة الإشعارات الرسمية من WESAL Messenger — للقراءة فقط"
                                },
                                color = palette.textSecondary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                } else {
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

                    if (showQuickRepliesBar && quickReplies.isNotEmpty() && !recordingState.isRecording) {
                        Surface(color = palette.surfaceElevated, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                quickReplies.forEach { preset ->
                                    Surface(
                                        color = palette.surface,
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, palette.turquoise.copy(alpha = 0.45f)),
                                        modifier = Modifier.clickable {
                                            viewModel.sendOrEditText(preset)
                                            showQuickRepliesBar = false
                                        }
                                    ) {
                                        Text(
                                            text = preset,
                                            color = palette.textPrimary,
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (recordingState.isRecording) {
                        Surface(color = palette.surface, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = null, tint = palette.error)
                                Text(
                                    text = DateTimeUtils.formatDurationMillis(recordingState.elapsedMs),
                                    color = palette.textPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                val liveBars = if (recordingState.amplitudes.isEmpty()) {
                                    List(14) { 20 }
                                } else {
                                    recordingState.amplitudes.takeLast(14)
                                }
                                Row(
                                    modifier = Modifier.weight(1f).height(26.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
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
                                IconButton(onClick = viewModel::cancelVoiceRecording) {
                                    Icon(Icons.Default.Delete, contentDescription = strings.cancel, tint = palette.error)
                                }
                                OutlinedButton(
                                    onClick = { pendingDraft = viewModel.stopVoiceForPreview() },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(if (strings.isEnglish) "Preview" else "معاينة", maxLines = 1, softWrap = false)
                                }
                                Button(
                                    onClick = viewModel::stopAndSendVoiceRecording,
                                    colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(strings.send, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(palette.surface)
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
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

                            IconButton(onClick = { showAttachSheet = true }) {
                                Icon(
                                    imageVector = Icons.Default.AddCircleOutline,
                                    contentDescription = if (strings.isEnglish) "Attachments" else "المرفقات والموسيقى",
                                    tint = palette.turquoise
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
                                minLines = 1,
                                maxLines = 6,
                                shape = RoundedCornerShape(24.dp),
                                trailingIcon = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (quickReplies.isNotEmpty()) {
                                            IconButton(
                                                onClick = { showQuickRepliesBar = !showQuickRepliesBar },
                                                modifier = Modifier.size(34.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Bolt,
                                                    contentDescription = null,
                                                    tint = if (showQuickRepliesBar) palette.emerald else palette.textSecondary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = { showCamera = true },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(Icons.Default.CameraAlt, contentDescription = strings.camera, tint = palette.textSecondary, modifier = Modifier.size(20.dp))
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
        }
    }

    if (showAttachSheet) {
        ModalBottomSheet(onDismissRequest = { showAttachSheet = false }, containerColor = palette.surface) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = if (strings.isEnglish) "Send Attachment & Media" else "إرسال مرفق أو ملف",
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                val sheetItemColors = ListItemDefaults.colors(containerColor = Color.Transparent)
                ListItem(
                    headlineContent = { Text(if (strings.isEnglish) "Photos" else "صور", color = palette.textPrimary) },
                    leadingContent = { Icon(Icons.Default.Photo, contentDescription = null, tint = palette.turquoise) },
                    colors = sheetItemColors,
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                )
                ListItem(
                    headlineContent = { Text(if (strings.isEnglish) "Video" else "فيديو", color = palette.textPrimary) },
                    leadingContent = { Icon(Icons.Default.Videocam, contentDescription = null, tint = palette.turquoise) },
                    colors = sheetItemColors,
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                    }
                )
                ListItem(
                    headlineContent = { Text(if (strings.isEnglish) "Camera" else "كاميرا", color = palette.textPrimary) },
                    leadingContent = { Icon(Icons.Default.CameraAlt, contentDescription = null, tint = palette.emerald) },
                    colors = sheetItemColors,
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        showCamera = true
                    }
                )
                ListItem(
                    headlineContent = { Text(strings.sendVoiceRecording, color = palette.textPrimary) },
                    leadingContent = { Icon(Icons.Default.Mic, contentDescription = null, tint = palette.emerald) },
                    colors = sheetItemColors,
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        if (micPermission.status.isGranted) {
                            viewModel.startVoiceRecording()
                        } else {
                            micPermission.launchPermissionRequest()
                        }
                    }
                )
                ListItem(
                    headlineContent = { Text(strings.sendAudioFile, color = palette.textPrimary) },
                    leadingContent = { Icon(Icons.Default.AudioFile, contentDescription = null, tint = palette.turquoise) },
                    colors = sheetItemColors,
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        audioPicker.launch(arrayOf("audio/*", "application/ogg"))
                    }
                )
                ListItem(
                    headlineContent = { Text(strings.sendMusic, color = palette.textPrimary) },
                    leadingContent = { Icon(Icons.Default.MusicNote, contentDescription = null, tint = palette.emerald) },
                    colors = sheetItemColors,
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        audioPicker.launch(arrayOf("audio/*"))
                    }
                )
                ListItem(
                    headlineContent = { Text(if (strings.isEnglish) "MP3 Audio" else "ملفات MP3", color = palette.textPrimary) },
                    leadingContent = { Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = palette.turquoise) },
                    colors = sheetItemColors,
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        audioPicker.launch(arrayOf("audio/mpeg", "audio/mp3", "audio/*"))
                    }
                )
                ListItem(
                    headlineContent = { Text(if (strings.isEnglish) "Documents" else "مستندات", color = palette.textPrimary) },
                    leadingContent = { Icon(Icons.Default.Description, contentDescription = null, tint = palette.emerald) },
                    colors = sheetItemColors,
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        documentPicker.launch(arrayOf("application/pdf", "application/msword", "text/*", "application/*"))
                    }
                )
                ListItem(
                    headlineContent = { Text(strings.chooseDeviceFile, color = palette.textPrimary) },
                    leadingContent = { Icon(Icons.Default.AttachFile, contentDescription = null, tint = palette.turquoise) },
                    colors = sheetItemColors,
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        documentPicker.launch(arrayOf("*/*"))
                    }
                )
                ListItem(
                    headlineContent = { Text(strings.sendLocation, color = palette.textPrimary) },
                    leadingContent = { Icon(Icons.Default.LocationOn, contentDescription = null, tint = palette.warning) },
                    colors = sheetItemColors,
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
                ListItem(
                    headlineContent = {
                        Text(
                            if (strings.isEnglish) "Share Live Location" else "مشاركة الموقع المباشر (مؤقت)",
                            color = palette.textPrimary
                        )
                    },
                    leadingContent = { Icon(Icons.Default.ShareLocation, contentDescription = null, tint = palette.emerald) },
                    colors = sheetItemColors,
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        if (locationPermissions.allPermissionsGranted) {
                            showLiveLocationDialog = true
                        } else {
                            locationPermissions.launchMultiplePermissionRequest()
                        }
                    }
                )
                Spacer(Modifier.height(14.dp))
            }
        }
    }

    if (showWallpaperDialog) {
        AlertDialog(
            onDismissRequest = { showWallpaperDialog = false },
            containerColor = palette.surface,
            title = {
                Text(
                    text = if (strings.isEnglish) "Chat Wallpaper" else "خلفية المحادثة",
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val pendingWp = croppedWallpaperUri
                    if (pendingWp != null) {
                        AsyncImage(
                            model = pendingWp,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Button(
                            onClick = {
                                viewModel.setPersonalWallpaper(pendingWp)
                                croppedWallpaperUri = null
                                showWallpaperDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary)
                        ) {
                            Text(if (strings.isEnglish) "Apply Personal Wallpaper (For Me Only)" else "تطبيق كخلفية شخصية لي فقط")
                        }
                        if (isSuperAdmin) {
                            Button(
                                onClick = {
                                    viewModel.setOfficialSharedWallpaper(pendingWp)
                                    croppedWallpaperUri = null
                                    showWallpaperDialog = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = palette.turquoise, contentColor = palette.onPrimary)
                            ) {
                                Text(if (strings.isEnglish) "Set Official Shared Wallpaper (10001)" else "تعيين كخلفية رسمية مشتركة للطرفين (10001)")
                            }
                        }
                    } else {
                        Button(
                            onClick = {
                                wallpaperPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary)
                        ) {
                            Icon(Icons.Default.Wallpaper, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (strings.isEnglish) "Pick & Crop Wallpaper" else "اختيار صورة وقصها كخلفية")
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.setPersonalWallpaper(null)
                                showWallpaperDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (strings.isEnglish) "Reset Personal Wallpaper" else "إعادة الخلفية الشخصية للافتراضي")
                        }
                        if (isSuperAdmin && !chat?.officialWallpaperUrl.isNullOrBlank()) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.setOfficialSharedWallpaper(null)
                                    showWallpaperDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (strings.isEnglish) "Clear Shared Official Wallpaper" else "إزالة الخلفية الرسمية المشتركة", color = palette.error)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showWallpaperDialog = false }) { Text(strings.close) }
            }
        )
    }

    if (showDeleteChatDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteChatDialog = false },
            containerColor = palette.surface,
            title = { Text(if (strings.isEnglish) "Delete Chat" else "حذف المحادثة", color = palette.error, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = if (strings.isEnglish) {
                        "Deleting for both sides clears the messages from WESAL servers for both participants (saved copies on peer's device remain unaffected)."
                    } else {
                        "الحذف لدى الطرفين يمسح الرسائل من الخادم لكلا الطرفين، مع التنويه أن أي نسخ تم حفظها مسبقاً على جهاز الطرف الآخر لا يمكن حذفها عن بُعد."
                    },
                    color = palette.textSecondary
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (chat?.isGroup == false) {
                        Button(
                            onClick = {
                                showDeleteChatDialog = false
                                viewModel.deleteChat(forEveryone = true, onSuccess = onBack)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = palette.error)
                        ) {
                            Text(if (strings.isEnglish) "Delete for Both" else "حذف لدى الطرفين")
                        }
                    }
                    TextButton(onClick = {
                        showDeleteChatDialog = false
                        viewModel.deleteChat(forEveryone = false, onSuccess = onBack)
                    }) {
                        Text(if (strings.isEnglish) "Delete for Me Only" else "حذف لدي فقط")
                    }
                    TextButton(onClick = { showDeleteChatDialog = false }) { Text(strings.cancel) }
                }
            }
        )
    }

    if (showReportChatDialog || reportTarget != null) {
        var reportReason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showReportChatDialog = false; reportTarget = null },
            containerColor = palette.surface,
            title = {
                Text(
                    if (strings.isEnglish) "Report Content" else "إرسال بلاغ للإدارة",
                    color = palette.warning,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (strings.isEnglish) {
                            "A snapshot of the reported message(s) will be preserved for review by WESAL Administration."
                        } else {
                            "سيتم حفظ نسخة من المحتوى المُبلّغ عنه للمراجعة من قِبل إدارة WESAL حتى لو تم حذفه لاحقاً."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary
                    )
                    OutlinedTextField(
                        value = reportReason,
                        onValueChange = { reportReason = it },
                        label = { Text(if (strings.isEnglish) "Reason" else "سبب البلاغ") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val peerUid = reportTarget?.senderId?.ifBlank { chat?.peerUid.orEmpty() } ?: chat?.peerUid.orEmpty()
                        viewModel.reportChatOrMessage(
                            peerUid = peerUid,
                            reason = reportReason.ifBlank { "محتوى مسيء" },
                            targetMessage = reportTarget
                        )
                        showReportChatDialog = false
                        reportTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.warning, contentColor = palette.onPrimary)
                ) {
                    Text(if (strings.isEnglish) "Submit Report" else "إرسال البلاغ")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReportChatDialog = false; reportTarget = null }) { Text(strings.cancel) }
            }
        )
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
                    if (!isOfficialReadOnly) {
                        ListItem(
                            headlineContent = { Text(if (strings.isEnglish) "Reply" else "رد") },
                            leadingContent = { Icon(Icons.Default.Reply, contentDescription = null) },
                            colors = actionItemColors,
                            modifier = Modifier.clickable { viewModel.setReplyingTo(msg); selectedMessageForActions = null }
                        )
                    }
                    if (!msg.isViewOnce) {
                        ListItem(
                            headlineContent = { Text(if (strings.isEnglish) "Forward" else "إعادة توجيه") },
                            leadingContent = { Icon(Icons.Default.Forward, contentDescription = null) },
                            colors = actionItemColors,
                            modifier = Modifier.clickable { forwardingMessage = msg; selectedMessageForActions = null }
                        )
                    }
                    if ((msg.type == MessageType.AUDIO || msg.type == MessageType.VOICE) && !msg.isViewOnce) {
                        ListItem(
                            headlineContent = { Text(strings.saveAudioToDevice) },
                            leadingContent = { Icon(Icons.Default.Download, contentDescription = null, tint = palette.emerald) },
                            colors = actionItemColors,
                            modifier = Modifier.clickable {
                                savePermissionGate { viewModel.saveAudioToDownloads(msg, strings.isEnglish) }
                                selectedMessageForActions = null
                            }
                        )
                    }
                    if (msg.type == MessageType.DOCUMENT && !msg.isViewOnce) {
                        ListItem(
                            headlineContent = { Text(strings.saveAudioToDevice) },
                            leadingContent = { Icon(Icons.Default.Download, contentDescription = null, tint = palette.emerald) },
                            colors = actionItemColors,
                            modifier = Modifier.clickable {
                                savePermissionGate { viewModel.saveDocumentToDownloads(msg, strings.isEnglish) }
                                selectedMessageForActions = null
                            }
                        )
                    }
                    if (msg.text.isNotBlank() || msg.mediaUrl.isNotBlank()) {
                        ListItem(
                            headlineContent = { Text(if (strings.isEnglish) "Star Message" else "تمييز الرسالة بنجمة") },
                            leadingContent = { Icon(Icons.Default.Star, contentDescription = null, tint = palette.warning) },
                            colors = actionItemColors,
                            modifier = Modifier.clickable {
                                viewModel.toggleStarMessage(msg)
                                selectedMessageForActions = null
                            }
                        )
                    }
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
                    if (msg.senderId == viewModel.myUid && msg.type == MessageType.TEXT && !msg.isDeletedForEveryone && !isOfficialReadOnly) {
                        ListItem(
                            headlineContent = { Text(strings.edit) },
                            leadingContent = { Icon(Icons.Default.Edit, contentDescription = null) },
                            colors = actionItemColors,
                            modifier = Modifier.clickable { viewModel.setEditingMessage(msg); selectedMessageForActions = null }
                        )
                    }
                    if (msg.senderId != viewModel.myUid && !isOfficialReadOnly) {
                        ListItem(
                            headlineContent = { Text(if (strings.isEnglish) "Report Message" else "إبلاغ عن الرسالة", color = palette.warning) },
                            leadingContent = { Icon(Icons.Default.Flag, contentDescription = null, tint = palette.warning) },
                            colors = actionItemColors,
                            modifier = Modifier.clickable { reportTarget = msg; selectedMessageForActions = null }
                        )
                    }
                    ListItem(
                        headlineContent = { Text(if (strings.isEnglish) "Delete for me" else "حذف لدي") },
                        leadingContent = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                        colors = actionItemColors,
                        modifier = Modifier.clickable { viewModel.deleteForMe(msg.messageId); selectedMessageForActions = null }
                    )
                    if ((msg.senderId == viewModel.myUid || isSuperAdmin) && !msg.isDeletedForEveryone && !isOfficialReadOnly) {
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

    if (showDisappearingDialog) {
        val currentSec = chat?.disappearingDurationSec ?: 0L
        AlertDialog(
            onDismissRequest = { showDisappearingDialog = false },
            containerColor = palette.surface,
            title = {
                Text(
                    if (strings.isEnglish) "Disappearing Messages" else "مؤقت اختفاء الرسائل",
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DisappearingTimer.entries.forEach { timer ->
                        val selected = currentSec == timer.seconds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    viewModel.setDisappearingTimer(timer)
                                    showDisappearingDialog = false
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            RadioButton(selected = selected, onClick = {
                                viewModel.setDisappearingTimer(timer)
                                showDisappearingDialog = false
                            })
                            Text(
                                text = if (strings.isEnglish) timer.labelEn else timer.labelAr,
                                color = palette.textPrimary,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDisappearingDialog = false }) { Text(strings.close) }
            }
        )
    }

    if (showLiveLocationDialog) {
        AlertDialog(
            onDismissRequest = { showLiveLocationDialog = false },
            containerColor = palette.surface,
            title = {
                Text(
                    if (strings.isEnglish) "Share Live Location" else "مشاركة الموقع المباشر",
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        15 to (if (strings.isEnglish) "15 minutes" else "15 دقيقة"),
                        60 to (if (strings.isEnglish) "1 hour" else "ساعة واحدة"),
                        480 to (if (strings.isEnglish) "8 hours" else "8 ساعات")
                    ).forEach { (mins, label) ->
                        OutlinedButton(
                            onClick = {
                                showLiveLocationDialog = false
                                val client = LocationServices.getFusedLocationProviderClient(context)
                                client.lastLocation.addOnSuccessListener { loc ->
                                    if (loc != null) {
                                        viewModel.sendLocation(
                                            loc.latitude,
                                            loc.longitude,
                                            if (strings.isEnglish) {
                                                "Live Location (${String.format("%.4f", loc.latitude)}, ${String.format("%.4f", loc.longitude)})"
                                            } else {
                                                "موقع مباشر (${String.format("%.4f", loc.latitude)}, ${String.format("%.4f", loc.longitude)})"
                                            },
                                            liveDurationMinutes = mins
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.ShareLocation, contentDescription = null, tint = palette.emerald)
                            Spacer(Modifier.width(8.dp))
                            Text(label, color = palette.textPrimary)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLiveLocationDialog = false }) { Text(strings.cancel) }
            }
        )
    }
}

@Composable
private fun MediaDraftPreviewScreen(
    draft: MediaDraftPreview,
    remainingQueueCount: Int = 0,
    playbackState: VoicePlaybackState,
    onUpdateDraft: (MediaDraftPreview) -> Unit,
    onCropPhoto: () -> Unit,
    onTogglePlayVoice: () -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    onSendAll: () -> Unit = onSend
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current

    Box(modifier = Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        when (draft.type) {
            MessageType.IMAGE -> {
                AsyncImage(
                    model = draft.uri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
            MessageType.VIDEO -> {
                VideoPlayerBox(uri = draft.uri, modifier = Modifier.fillMaxSize())
            }
            MessageType.AUDIO, MessageType.VOICE -> {
                val isPlaying = playbackState.activeMessageId == "draft_voice_preview" && playbackState.isPlaying
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Surface(
                        color = palette.surface,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth().padding(28.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Default.AudioFile, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(48.dp))
                            Text(draft.fileName, color = palette.textPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = DateTimeUtils.formatDurationMillis(draft.durationMs),
                                color = palette.textSecondary
                            )
                            Button(
                                onClick = onTogglePlayVoice,
                                colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary)
                            ) {
                                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (isPlaying) "إيقاف المعاينة" else "تشغيل للمعاينة")
                            }
                        }
                    }
                }
            }
            else -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Surface(
                        color = palette.surface,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth().padding(28.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(48.dp))
                            Text(draft.fileName, color = palette.textPrimary, fontWeight = FontWeight.Bold)
                            Text("${(draft.sizeBytes / 1024L).coerceAtLeast(1L)} KB", color = palette.textSecondary)
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onCancel,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = strings.cancel, tint = Color.White)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (draft.type == MessageType.IMAGE) {
                    OutlinedButton(
                        onClick = onCropPhoto,
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Black.copy(alpha = 0.55f))
                    ) {
                        Icon(Icons.Default.Crop, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (strings.isEnglish) "Crop" else "قص الصورة", color = Color.White)
                    }
                }
                if (draft.type == MessageType.IMAGE || draft.type == MessageType.VIDEO) {
                    FilterChip(
                        selected = draft.isViewOnce,
                        onClick = { onUpdateDraft(draft.copy(isViewOnce = !draft.isViewOnce)) },
                        label = { Text(if (strings.isEnglish) "View Once (1x)" else "عرض لمرة واحدة 1x", maxLines = 1, softWrap = false) },
                        leadingIcon = { Icon(Icons.Default.LooksOne, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = palette.emerald,
                            selectedLabelColor = palette.onPrimary,
                            containerColor = Color.Black.copy(alpha = 0.6f),
                            labelColor = Color.White
                        )
                    )
                }
            }
        }

        Surface(
            color = palette.surface.copy(alpha = 0.94f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = draft.caption,
                    onValueChange = { onUpdateDraft(draft.copy(caption = it)) },
                    placeholder = { Text(if (strings.isEnglish) "Add caption (optional)..." else "إضافة وصف أو نص (اختياري)...") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp)
                )
                FloatingActionButton(
                    onClick = onSend,
                    containerColor = palette.emerald,
                    contentColor = palette.onPrimary
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = strings.send)
                }
                if (remainingQueueCount > 0) {
                    FilledTonalButton(
                        onClick = onSendAll,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = palette.turquoise.copy(alpha = 0.22f),
                            contentColor = palette.turquoise
                        )
                    ) {
                        Text(
                            if (strings.isEnglish) "Send All (${remainingQueueCount + 1})" else "إرسال الكل (${remainingQueueCount + 1})",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: WesalMessage,
    isMine: Boolean,
    myUid: String,
    isGroup: Boolean,
    decryptedFile: File?,
    playbackState: VoicePlaybackState,
    onRequestMedia: () -> Unit,
    onOpenImage: () -> Unit,
    onPlayVideo: () -> Unit,
    onToggleVoice: () -> Unit,
    onSeekAudio: (Int) -> Unit,
    onSetVolume: (Float) -> Unit,
    onSaveAudio: () -> Unit,
    onOpenDocument: () -> Unit,
    onSaveDocument: () -> Unit = {},
    onOpenLocation: () -> Unit,
    onRetryFailed: () -> Unit = {},
    onDismissFailed: () -> Unit = {},
    onLongClick: () -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current

    val isThisAudioActive = playbackState.activeMessageId == message.messageId
    val isVoicePlaying = isThisAudioActive && playbackState.isPlaying
    val voiceProgress = if (isThisAudioActive && playbackState.durationMs > 0) {
        playbackState.positionMs.toFloat() / playbackState.durationMs.toFloat()
    } else {
        0f
    }

    LaunchedEffect(message.messageId, message.type, message.isViewOnce) {
        if (message.type == MessageType.IMAGE && !message.isViewOnce && decryptedFile == null && message.mediaUrl.isNotBlank()) {
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
                .widthIn(max = 310.dp)
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
                    Surface(color = palette.surface.copy(alpha = 0.65f), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            text = message.replyToPreview,
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSecondary,
                            maxLines = 2,
                            modifier = Modifier.padding(6.dp)
                        )
                    }
                }

                if (message.isViewOnce && (message.type == MessageType.IMAGE || message.type == MessageType.VIDEO)) {
                    val alreadyViewedByMe = myUid in message.viewedBy
                    val viewedByAny = message.viewedBy.isNotEmpty()
                    val canOpen = !isMine && !alreadyViewedByMe && message.mediaUrl.isNotBlank()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.surface.copy(alpha = 0.7f))
                            .clickable(enabled = canOpen) {
                                if (message.type == MessageType.VIDEO) onPlayVideo() else onOpenImage()
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (alreadyViewedByMe || (isMine && viewedByAny)) Icons.Default.DoneAll else Icons.Default.LooksOne,
                            contentDescription = null,
                            tint = if (canOpen) palette.emerald else palette.textMuted
                        )
                        Text(
                            text = when {
                                isMine && viewedByAny -> if (strings.isEnglish) "Opened" else "تمت المشاهدة"
                                isMine -> if (strings.isEnglish) "View-once media sent" else "صورة/فيديو للعرض مرة واحدة"
                                alreadyViewedByMe -> if (strings.isEnglish) "Opened & burned" else "تم فتحها وحذفها بعد المشاهدة"
                                message.type == MessageType.VIDEO -> if (strings.isEnglish) "Tap to view video once" else "فيديو للعرض مرة واحدة (اضغط للفتح)"
                                else -> if (strings.isEnglish) "Tap to view photo once" else "صورة للعرض مرة واحدة (اضغط للفتح)"
                            },
                            color = if (canOpen) palette.textPrimary else palette.textMuted,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
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

                        MessageType.AUDIO -> {
                            val totalMs = if (isThisAudioActive && playbackState.durationMs > 1) {
                                playbackState.durationMs.toLong()
                            } else {
                                message.mediaDurationMs.coerceAtLeast(0L)
                            }
                            val posMs = if (isThisAudioActive) playbackState.positionMs.toLong() else 0L
                            val sizeKb = (message.mediaSizeBytes / 1024L).coerceAtLeast(1L)

                            Surface(
                                color = palette.surface.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, palette.border),
                                modifier = Modifier.width(265.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Surface(
                                            color = palette.emerald.copy(alpha = 0.16f),
                                            shape = CircleShape,
                                            modifier = Modifier.size(38.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                IconButton(onClick = onToggleVoice, modifier = Modifier.size(38.dp)) {
                                                    Icon(
                                                        imageVector = if (isVoicePlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                        contentDescription = null,
                                                        tint = palette.emerald
                                                    )
                                                }
                                            }
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = message.mediaFileName.ifBlank { if (strings.isEnglish) "Audio Track" else "ملف صوتي" },
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = palette.textPrimary,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${DateTimeUtils.formatDurationMillis(posMs)} / ${DateTimeUtils.formatDurationMillis(totalMs)} • ${sizeKb} KB",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = palette.textSecondary
                                            )
                                        }
                                        IconButton(onClick = onSaveAudio, modifier = Modifier.size(32.dp)) {
                                            Icon(
                                                Icons.Default.Download,
                                                contentDescription = strings.saveAudioToDevice,
                                                tint = palette.turquoise,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }

                                    val seekDuration = totalMs.coerceAtLeast(1L).toFloat()
                                    Slider(
                                        value = if (isThisAudioActive) posMs.toFloat().coerceIn(0f, seekDuration) else 0f,
                                        onValueChange = { newPos ->
                                            if (isThisAudioActive) onSeekAudio(newPos.toInt())
                                        },
                                        valueRange = 0f..seekDuration,
                                        colors = SliderDefaults.colors(
                                            thumbColor = palette.turquoise,
                                            activeTrackColor = palette.turquoise,
                                            inactiveTrackColor = palette.border
                                        ),
                                        modifier = Modifier.fillMaxWidth().height(24.dp)
                                    )

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        IconButton(
                                            onClick = { onSetVolume(if (playbackState.volume > 0f) 0f else 1f) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (playbackState.volume <= 0.01f) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                                contentDescription = null,
                                                tint = palette.textSecondary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        Slider(
                                            value = playbackState.volume,
                                            onValueChange = onSetVolume,
                                            valueRange = 0f..1f,
                                            colors = SliderDefaults.colors(
                                                thumbColor = palette.emerald,
                                                activeTrackColor = palette.emerald,
                                                inactiveTrackColor = palette.border
                                            ),
                                            modifier = Modifier.weight(1f).height(20.dp)
                                        )
                                    }
                                    if (message.text.isNotBlank()) {
                                        Text(message.text, style = MaterialTheme.typography.bodyMedium, color = palette.textPrimary)
                                    }
                                }
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
                                modifier = Modifier.width(245.dp)
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
                                        text = DateTimeUtils.formatDurationMillis(
                                            if (isThisAudioActive && playbackState.isPlaying) playbackState.positionMs.toLong()
                                            else message.mediaDurationMs
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.textSecondary
                                    )
                                }
                                IconButton(onClick = onSaveAudio, modifier = Modifier.size(28.dp)) {
                                    Icon(
                                        Icons.Default.Download,
                                        contentDescription = strings.saveAudioToDevice,
                                        tint = palette.emerald,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        MessageType.DOCUMENT -> {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(palette.surface.copy(alpha = 0.65f))
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
                                    // Round-4: this download icon used to be decorative —
                                    // it is now a real save-to-Downloads action (the row
                                    // itself still opens the document).
                                    IconButton(onClick = onSaveDocument, modifier = Modifier.size(28.dp)) {
                                        Icon(
                                            Icons.Default.Download,
                                            contentDescription = strings.saveAudioToDevice,
                                            tint = palette.emerald,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                if (message.text.isNotBlank()) {
                                    Text(message.text, style = MaterialTheme.typography.bodyMedium, color = palette.textPrimary)
                                }
                            }
                        }

                        MessageType.LOCATION, MessageType.LIVE_LOCATION -> {
                            val isLive = message.type == MessageType.LIVE_LOCATION
                            val isLiveActive = isLive && message.liveLocationExpiresAt > System.currentTimeMillis()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(palette.surface.copy(alpha = 0.65f))
                                    .clickable { onOpenLocation() }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = if (isLive) Icons.Default.ShareLocation else Icons.Default.LocationOn,
                                    contentDescription = null,
                                    tint = if (isLiveActive || !isLive) palette.emerald else palette.textMuted
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = when {
                                            isLive && isLiveActive -> if (strings.isEnglish) "Live Location • Active" else "موقع مباشر • نشط الآن"
                                            isLive -> if (strings.isEnglish) "Live Location • Ended" else "موقع مباشر • انتهت المشاركة"
                                            else -> if (strings.isEnglish) "Location" else "موقع جغرافي"
                                        },
                                        color = palette.textPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(message.locationAddress, style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
                                }
                            }
                        }

                        MessageType.CALL_LOG -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(palette.surface.copy(alpha = 0.65f))
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Default.Call, contentDescription = null, tint = palette.turquoise)
                                Text(
                                    text = message.text.ifBlank { if (strings.isEnglish) "Call log" else "سجل مكالمة" },
                                    color = palette.textPrimary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
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
                if (isMine && message.status == MessageStatus.FAILED) {
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (strings.isEnglish) "Failed • Retry" else "فشل الإرسال • إعادة المحاولة",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.error,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { onRetryFailed() }
                        )
                        Text(
                            text = if (strings.isEnglish) "Remove" else "حذف",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textMuted,
                            modifier = Modifier.clickable { onDismissFailed() }
                        )
                    }
                }
            }
        }
    }
}


