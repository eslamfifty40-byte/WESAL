package com.wesal.messenger.channels

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.core.util.WesalShareUtils
import com.wesal.messenger.domain.model.ChannelPost
import com.wesal.messenger.domain.model.ChannelPostType
import com.wesal.messenger.domain.model.ChannelRole
import com.wesal.messenger.media.FullscreenImageViewer
import com.wesal.messenger.presentation.components.WesalAvatar
import com.wesal.messenger.presentation.components.WesalNameWithBadge
import com.wesal.messenger.presentation.components.WesalQrCodeBox
import com.wesal.messenger.presentation.components.saveImageUrlToGallery
import com.wesal.messenger.presentation.components.wesalImageModel
import com.wesal.messenger.voice.VoicePlaybackState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val QUICK_CHANNEL_REACTIONS = listOf("👍", "❤️", "🔥", "👏", "😂")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelDetailScreen(
    channelId: String,
    onBack: () -> Unit,
    viewModel: ChannelsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current

    val channel by viewModel.activeChannel.collectAsStateWithLifecycle()
    val posts by viewModel.activePosts.collectAsStateWithLifecycle()
    val joinRequests by viewModel.activeJoinRequests.collectAsStateWithLifecycle()
    val comments by viewModel.activeComments.collectAsStateWithLifecycle()
    val uploadProgress by viewModel.uploadProgress.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val moderators by viewModel.moderators.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val isSuperAdmin by viewModel.isSuperAdmin.collectAsStateWithLifecycle()
    val currentUserPhotoUrl by viewModel.currentUserPhotoUrl.collectAsStateWithLifecycle()
    val uiScope = rememberCoroutineScope()

    var postText by remember { mutableStateOf("") }
    var showAttachMenu by remember { mutableStateOf(false) }
    var showRequestsDialog by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showModsDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var editingPost by remember { mutableStateOf<ChannelPost?>(null) }
    var commentSheetPostId by remember { mutableStateOf<String?>(null) }
    var fullscreenImageUrl by remember { mutableStateOf<String?>(null) }
    var copiedNotice by remember { mutableStateOf(false) }

    LaunchedEffect(channelId) {
        viewModel.loadChannel(channelId)
    }

    LaunchedEffect(channel?.moderatorIds) {
        channel?.moderatorIds?.let { viewModel.loadModerators(it) }
    }

    DisposableEffect(channelId) {
        onDispose { viewModel.stopAudioPlayback() }
    }

    fun resolveMeta(uri: Uri): Pair<String, Long> {
        var name = "wesal_media"
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
        }
        return name to size
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val (name, size) = resolveMeta(uri)
            viewModel.publishMediaPost(channelId, ChannelPostType.IMAGE, postText, uri, name, "image/*", size, 0L)
            postText = ""
        }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val (name, size) = resolveMeta(uri)
            viewModel.publishMediaPost(channelId, ChannelPostType.VIDEO, postText, uri, name, "video/*", size, 0L)
            postText = ""
        }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val (name, size) = resolveMeta(uri)
            viewModel.publishMediaPost(channelId, ChannelPostType.AUDIO, postText, uri, name, "audio/*", size, 0L)
            postText = ""
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val (name, size) = resolveMeta(uri)
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            viewModel.publishMediaPost(channelId, ChannelPostType.DOCUMENT, postText, uri, name, mime, size, 0L)
            postText = ""
        }
    }

    val ch = channel
    val myUid = viewModel.myUid
    val role = ch?.roleFor(myUid) ?: ChannelRole.NONE
    // Official Team Channel can ONLY be managed/published by Super Admin 10001
    val canManage = when {
        ch == null -> false
        ch.isSuspended -> false
        ch.isOfficialTeamChannel -> isSuperAdmin
        else -> role == ChannelRole.OWNER || role == ChannelRole.MODERATOR
    }
    val canReadPosts = ch != null && (ch.isPublic || role != ChannelRole.NONE || isSuperAdmin)
    val pinnedPost = posts.firstOrNull { it.isPinned }

    Scaffold(
        containerColor = palette.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.clickable { showInfoDialog = true }
                    ) {
                        Box(
                            modifier = Modifier.clickable(enabled = ch?.avatarUrl?.isNotBlank() == true) {
                                fullscreenImageUrl = ch?.avatarUrl
                            }
                        ) {
                            WesalAvatar(imageUrl = ch?.avatarUrl.orEmpty(), name = ch?.title ?: "C", size = 38.dp)
                        }
                        Column {
                            WesalNameWithBadge(
                                name = ch?.title ?: strings.loading,
                                isVerified = ch?.isVerified == true,
                                verificationType = ch?.verificationType ?: "BLUE",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (ch == null) "" else if (strings.isEnglish) {
                                    "${ch.subscribersCount} subscribers"
                                } else {
                                    "${ch.subscribersCount} مشترك"
                                },
                                color = palette.textSecondary,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back, tint = palette.textPrimary)
                    }
                },
                actions = {
                    if (canManage && ch != null && !ch.isPublic) {
                        BadgedBox(
                            badge = {
                                if (joinRequests.isNotEmpty()) {
                                    Badge { Text("${joinRequests.size}") }
                                }
                            }
                        ) {
                            IconButton(onClick = { showRequestsDialog = true }) {
                                Icon(Icons.Default.GroupAdd, contentDescription = strings.requestToJoin, tint = palette.textPrimary)
                            }
                        }
                    }
                    if (ch != null) {
                        IconButton(onClick = { showInfoDialog = true }) {
                            Icon(Icons.Default.QrCode2, contentDescription = strings.share, tint = palette.textPrimary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.surface)
            )
        },
        bottomBar = {
            if (canManage) {
                Surface(color = palette.surface, tonalElevation = 4.dp) {
                    Column(modifier = Modifier.navigationBarsPadding()) {
                        uploadProgress?.let { progress ->
                            LinearProgressIndicator(
                                progress = { progress },
                                color = palette.emerald,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box {
                                IconButton(onClick = { showAttachMenu = true }) {
                                    Icon(Icons.Default.AttachFile, contentDescription = null, tint = palette.textSecondary)
                                }
                                DropdownMenu(
                                    expanded = showAttachMenu,
                                    onDismissRequest = { showAttachMenu = false },
                                    containerColor = palette.surface
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(if (strings.isEnglish) "Photo" else "صورة", color = palette.textPrimary) },
                                        leadingIcon = { Icon(Icons.Default.Image, contentDescription = null, tint = palette.emerald) },
                                        onClick = {
                                            showAttachMenu = false
                                            imagePicker.launch("image/*")
                                        }
                                    )
                                    if (currentUserPhotoUrl.isNotBlank()) {
                                        DropdownMenuItem(
                                            text = { Text(if (strings.isEnglish) "Post My Profile Photo" else "نشر صورتي الشخصية", color = palette.textPrimary) },
                                            leadingIcon = { Icon(Icons.Default.AccountCircle, contentDescription = null, tint = palette.turquoise) },
                                            onClick = {
                                                showAttachMenu = false
                                                viewModel.publishPersonalPhotoToChannel(channelId, postText, currentUserPhotoUrl)
                                                postText = ""
                                            }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(if (strings.isEnglish) "Video" else "فيديو", color = palette.textPrimary) },
                                        leadingIcon = { Icon(Icons.Default.VideoFile, contentDescription = null, tint = palette.turquoise) },
                                        onClick = {
                                            showAttachMenu = false
                                            videoPicker.launch("video/*")
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(if (strings.isEnglish) "Audio" else "صوتية", color = palette.textPrimary) },
                                        leadingIcon = { Icon(Icons.Default.AudioFile, contentDescription = null, tint = palette.emerald) },
                                        onClick = {
                                            showAttachMenu = false
                                            audioPicker.launch("audio/*")
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(if (strings.isEnglish) "File" else "ملف", color = palette.textPrimary) },
                                        leadingIcon = { Icon(Icons.Default.Description, contentDescription = null, tint = palette.textSecondary) },
                                        onClick = {
                                            showAttachMenu = false
                                            filePicker.launch(arrayOf("*/*"))
                                        }
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = postText,
                                onValueChange = { postText = it },
                                placeholder = { Text(strings.publishPost, color = palette.textMuted) },
                                maxLines = 4,
                                shape = RoundedCornerShape(22.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = palette.textPrimary,
                                    unfocusedTextColor = palette.textPrimary,
                                    cursorColor = palette.emerald,
                                    focusedBorderColor = palette.emerald,
                                    unfocusedBorderColor = palette.border,
                                    focusedPlaceholderColor = palette.textMuted,
                                    unfocusedPlaceholderColor = palette.textMuted,
                                    focusedContainerColor = palette.surfaceElevated,
                                    unfocusedContainerColor = palette.surfaceElevated
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    if (postText.isNotBlank()) {
                                        viewModel.publishTextPost(channelId, postText)
                                        postText = ""
                                    }
                                },
                                enabled = postText.isNotBlank() && uploadProgress == null
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = strings.send, tint = palette.emerald)
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (ch?.isSuspended == true) {
                Surface(color = palette.error.copy(alpha = 0.14f), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (strings.isEnglish) "This channel has been suspended by WESAL administration." else "تم إيقاف هذه القناة من قِبل إدارة WESAL.",
                        color = palette.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (ch?.isOfficialTeamChannel == true) {
                Surface(color = palette.emerald.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (strings.isEnglish) {
                            "Official WESAL Team Channel — Posts published exclusively by WESAL administration (ID: 10001)"
                        } else {
                            "قناة فريق وصال الرسمية — ينشر فيها الحساب الإداري المعتمد فقط (ID: 10001)"
                        },
                        color = palette.emerald,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            message?.let { msg ->
                Surface(
                    color = palette.emerald.copy(alpha = 0.14f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.clearMessage() }
                ) {
                    Text(msg, color = palette.emerald, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                }
            }

            if (ch != null && role == ChannelRole.NONE && !ch.isSuspended) {
                Surface(color = palette.surfaceElevated, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = if (ch.isPublic) Icons.Default.Public else Icons.Default.Lock,
                            contentDescription = null,
                            tint = palette.emerald
                        )
                        Text(
                            text = if (ch.isPublic) {
                                if (strings.isEnglish) "Public Channel — Subscribe to stay updated" else "قناة عامة — اشترك لمتابعة المنشورات"
                            } else {
                                if (strings.isEnglish) "Private Channel — Request access to read posts" else "قناة خاصة — اطلب الانضمام لعرض المنشورات"
                            },
                            color = palette.textPrimary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (ch.isPublic) viewModel.subscribe(ch.channelId)
                                else viewModel.requestJoin(ch.channelId)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary)
                        ) {
                            Text(if (ch.isPublic) strings.subscribeChannel else strings.requestToJoin)
                        }
                    }
                }
            }

            if (pinnedPost != null && canReadPosts) {
                Surface(
                    color = palette.emerald.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.PushPin, contentDescription = null, tint = palette.emerald, modifier = Modifier.size(16.dp))
                        Text(
                            text = pinnedPost.text.ifBlank { pinnedPost.mediaFileName.ifBlank { "Pinned post" } },
                            color = palette.textPrimary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            if (!canReadPosts) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (strings.isEnglish) "Join this private channel to view posts." else "انضم إلى هذه القناة الخاصة لمشاهدة منشوراتها.",
                        color = palette.textSecondary
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(posts, key = { it.postId }) { post ->
                        ChannelPostCard(
                            post = post,
                            myUid = myUid,
                            canManage = canManage,
                            playbackState = playbackState,
                            onToggleAudio = {
                                viewModel.toggleChannelAudioPlayback(post.postId, post.mediaUrl)
                            },
                            onReact = { emoji -> viewModel.toggleReaction(channelId, post.postId, emoji) },
                            onOpenComments = {
                                viewModel.selectPostForComments(post.postId)
                                commentSheetPostId = post.postId
                            },
                            onOpenImage = { url -> fullscreenImageUrl = url },
                            onSaveImage = { url ->
                                uiScope.launch {
                                    saveImageUrlToGallery(context, url, "WESAL_CHANNEL")
                                        .onSuccess {
                                            Toast.makeText(
                                                context,
                                                if (strings.isEnglish) "Photo saved to gallery ✓" else "تم حفظ الصورة في المعرض ✓",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        .onFailure { err ->
                                            Toast.makeText(
                                                context,
                                                err.localizedMessage ?: if (strings.isEnglish) "Save failed" else "تعذر حفظ الصورة",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                }
                            },
                            onEdit = { editingPost = post },
                            onTogglePin = { viewModel.togglePin(channelId, post.postId, !post.isPinned) },
                            onDelete = { viewModel.deletePost(channelId, post.postId) }
                        )
                    }
                }
            }
        }
    }

    editingPost?.let { postToEdit ->
        var editText by remember(postToEdit.postId) { mutableStateOf(postToEdit.text) }
        AlertDialog(
            onDismissRequest = { editingPost = null },
            containerColor = palette.surface,
            title = { Text(if (strings.isEnglish) "Edit Post" else "تعديل المنشور", color = palette.textPrimary) },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.editPost(channelId, postToEdit.postId, editText)
                        editingPost = null
                    },
                    enabled = editText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary)
                ) {
                    Text(strings.save)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingPost = null }) { Text(strings.cancel) }
            }
        )
    }

    if (showInfoDialog && ch != null) {
        val qrPayload = "wesal://channel?code=${ch.inviteCode}"
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            containerColor = palette.surface,
            title = {
                WesalNameWithBadge(
                    name = ch.title,
                    isVerified = ch.isVerified,
                    verificationType = ch.verificationType,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (ch.avatarUrl.isNotBlank()) {
                        Box(modifier = Modifier.clickable { fullscreenImageUrl = ch.avatarUrl }) {
                            WesalAvatar(imageUrl = ch.avatarUrl, name = ch.title, size = 76.dp)
                        }
                        OutlinedButton(
                            onClick = {
                                uiScope.launch {
                                    saveImageUrlToGallery(context, ch.avatarUrl, "WESAL_CH_AVATAR")
                                        .onSuccess {
                                            Toast.makeText(
                                                context,
                                                if (strings.isEnglish) "Channel photo saved ✓" else "تم حفظ صورة القناة في المعرض ✓",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        .onFailure { err ->
                                            Toast.makeText(
                                                context,
                                                err.localizedMessage ?: if (strings.isEnglish) "Save failed" else "تعذر الحفظ",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (strings.isEnglish) "Save Channel Photo" else "حفظ صورة القناة", color = palette.turquoise)
                        }
                    }
                    if (ch.description.isNotBlank()) {
                        Text(ch.description, color = palette.textSecondary, modifier = Modifier.fillMaxWidth())
                    }
                    Surface(
                        color = palette.surfaceElevated,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (strings.isEnglish) {
                                "${ch.subscribersCount} subscribers • Subscriber identities stay private"
                            } else {
                                "${ch.subscribersCount} مشترك • قائمة المشتركين خاصة ومخفية لحماية الخصوصية"
                            },
                            color = palette.textSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    WesalQrCodeBox(payload = qrPayload, size = 164.dp)
                    Text(
                        text = "${if (strings.isEnglish) "Invite Code" else "رمز الدعوة"}: ${ch.inviteCode}",
                        color = palette.emerald,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (copiedNotice) {
                        Text(
                            text = if (strings.isEnglish) "Copied ✓" else "تم النسخ ✓",
                            color = palette.turquoise,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(ch.inviteCode))
                                copiedNotice = true
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp), tint = palette.turquoise)
                            Spacer(Modifier.width(4.dp))
                            Text(strings.copy, color = palette.turquoise, maxLines = 1)
                        }
                        Button(
                            onClick = {
                                val msg = WesalShareUtils.buildChannelInviteMessage(ch.title, ch.inviteCode, strings.isEnglish)
                                WesalShareUtils.shareViaWhatsApp(context, msg)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(strings.share, maxLines = 1)
                        }
                    }

                    if (canManage) {
                        HorizontalDivider(color = palette.border)
                        TextButton(
                            onClick = { viewModel.regenerateInviteCode(ch.channelId) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = palette.turquoise)
                            Spacer(Modifier.width(6.dp))
                            Text(if (strings.isEnglish) "Regenerate Invite Code" else "تجديد رمز الدعوة", color = palette.turquoise)
                        }
                        TextButton(
                            onClick = {
                                showInfoDialog = false
                                showEditDialog = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = palette.textPrimary)
                            Spacer(Modifier.width(6.dp))
                            Text(if (strings.isEnglish) "Edit Channel Info" else "تعديل بيانات القناة", color = palette.textPrimary)
                        }
                    }

                    if (!ch.isOfficialTeamChannel && role == ChannelRole.OWNER) {
                        TextButton(
                            onClick = {
                                showInfoDialog = false
                                showModsDialog = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.AdminPanelSettings, contentDescription = null, tint = palette.emerald)
                            Spacer(Modifier.width(6.dp))
                            Text(if (strings.isEnglish) "Manage Moderators" else "إدارة المشرفين", color = palette.emerald)
                        }
                        TextButton(
                            onClick = {
                                showInfoDialog = false
                                showDeleteConfirm = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = palette.error)
                            Spacer(Modifier.width(6.dp))
                            Text(if (strings.isEnglish) "Delete Channel" else "حذف القناة", color = palette.error)
                        }
                    }

                    if (role == ChannelRole.SUBSCRIBER || (role == ChannelRole.MODERATOR && ch.ownerUid != myUid)) {
                        TextButton(
                            onClick = {
                                viewModel.unsubscribe(ch.channelId)
                                showInfoDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(strings.unsubscribeChannel, color = palette.error)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) { Text(strings.close, color = palette.textSecondary) }
            }
        )
    }

    if (showEditDialog && ch != null) {
        EditChannelDialog(
            initialTitle = ch.title,
            initialDesc = ch.description,
            initialPublic = ch.isPublic,
            onDismiss = { showEditDialog = false },
            onSave = { t, d, pub, avatarUri ->
                viewModel.updateChannel(ch.channelId, t, d, pub, avatarUri) {
                    showEditDialog = false
                }
            }
        )
    }

    if (showModsDialog && ch != null) {
        var modQuery by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showModsDialog = false },
            containerColor = palette.surface,
            title = {
                Text(
                    text = if (strings.isEnglish) "Channel Moderators" else "مشرفو القناة",
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = modQuery,
                            onValueChange = { modQuery = it },
                            label = { Text(if (strings.isEnglish) "@username or 5-digit ID" else "@username أو المعرّف الرقمي") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = palette.textPrimary,
                                unfocusedTextColor = palette.textPrimary,
                                focusedBorderColor = palette.emerald,
                                unfocusedBorderColor = palette.border,
                                focusedLabelColor = palette.emerald,
                                unfocusedLabelColor = palette.textSecondary
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                viewModel.addModeratorByIdentifier(ch.channelId, modQuery)
                                modQuery = ""
                            },
                            enabled = modQuery.isNotBlank()
                        ) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null, tint = palette.emerald)
                        }
                    }
                    HorizontalDivider(color = palette.border)
                    moderators.forEach { mod ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            WesalAvatar(imageUrl = mod.photoUrl, name = mod.displayName, size = 32.dp)
                            Column(modifier = Modifier.weight(1f)) {
                                WesalNameWithBadge(
                                    name = mod.displayName.ifBlank { "@${mod.publicId}" },
                                    isVerified = mod.isVerified,
                                    verificationType = mod.verificationType,
                                    style = MaterialTheme.typography.bodySmall,
                                    badgeSize = 14.dp
                                )
                                if (mod.numericId.isNotBlank()) {
                                    Text("ID: ${mod.numericId}", color = palette.textMuted, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (mod.uid != ch.ownerUid) {
                                IconButton(onClick = { viewModel.toggleModerator(ch.channelId, mod.uid, false) }) {
                                    Icon(Icons.Default.RemoveCircleOutline, contentDescription = strings.delete, tint = palette.error)
                                }
                            } else {
                                Text(
                                    text = if (strings.isEnglish) "Owner" else "المالك",
                                    color = palette.emerald,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModsDialog = false }) { Text(strings.close, color = palette.textSecondary) }
            }
        )
    }

    if (showDeleteConfirm && ch != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = palette.surface,
            title = {
                Text(
                    text = if (strings.isEnglish) "Delete Channel" else "حذف القناة نهائياً",
                    color = palette.error,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (strings.isEnglish) {
                        "Are you sure you want to delete \"${ch.title}\"? This removes all posts and subscribers."
                    } else {
                        "هل أنت متأكد من حذف قناة «${ch.title}»؟ سيتم حذف جميع المنشورات نهائياً."
                    },
                    color = palette.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteChannel(ch.channelId) { onBack() }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.error, contentColor = palette.onPrimary)
                ) {
                    Text(strings.delete)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(strings.cancel, color = palette.textSecondary) }
            }
        )
    }

    if (showRequestsDialog) {
        AlertDialog(
            onDismissRequest = { showRequestsDialog = false },
            containerColor = palette.surface,
            title = { Text(if (strings.isEnglish) "Join Requests" else "طلبات الانضمام", color = palette.textPrimary) },
            text = {
                if (joinRequests.isEmpty()) {
                    Text(if (strings.isEnglish) "No pending requests" else "لا توجد طلبات معلقة", color = palette.textSecondary)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        joinRequests.forEach { req ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                WesalAvatar(imageUrl = req.requesterAvatarUrl, name = req.requesterName, size = 36.dp)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(req.requesterName, color = palette.textPrimary, fontWeight = FontWeight.Medium)
                                    if (req.requesterPublicId.isNotBlank()) {
                                        Text("@${req.requesterPublicId}", color = palette.textMuted, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                IconButton(onClick = { viewModel.approveRequest(channelId, req) }) {
                                    Icon(Icons.Default.Check, contentDescription = strings.acceptContactRequest, tint = palette.emerald)
                                }
                                IconButton(onClick = { viewModel.rejectRequest(channelId, req) }) {
                                    Icon(Icons.Default.Close, contentDescription = strings.rejectContactRequest, tint = palette.error)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRequestsDialog = false }) { Text(strings.close, color = palette.textSecondary) }
            }
        )
    }

    commentSheetPostId?.let { activePostId ->
        var commentInput by remember { mutableStateOf("") }
        ModalBottomSheet(
            onDismissRequest = {
                commentSheetPostId = null
                viewModel.selectPostForComments(null)
            },
            containerColor = palette.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(strings.commentsTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = palette.textPrimary)
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(comments, key = { it.commentId }) { c ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            WesalAvatar(imageUrl = c.authorAvatarUrl, name = c.authorName, size = 32.dp)
                            Column(modifier = Modifier.weight(1f)) {
                                WesalNameWithBadge(
                                    name = c.authorName,
                                    isVerified = c.authorVerified,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    badgeSize = 14.dp
                                )
                                Text(c.text, color = palette.textPrimary, style = MaterialTheme.typography.bodySmall)
                            }
                            if (canManage || c.authorUid == myUid) {
                                IconButton(
                                    onClick = { viewModel.deleteComment(channelId, activePostId, c.commentId) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = strings.delete, tint = palette.error, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = commentInput,
                        onValueChange = { commentInput = it },
                        placeholder = {
                            Text(
                                text = if (strings.isEnglish) "Add a comment..." else "أضف تعليقاً...",
                                color = palette.textMuted
                            )
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = palette.textPrimary,
                            unfocusedTextColor = palette.textPrimary,
                            cursorColor = palette.emerald,
                            focusedBorderColor = palette.emerald,
                            unfocusedBorderColor = palette.border,
                            focusedPlaceholderColor = palette.textMuted,
                            unfocusedPlaceholderColor = palette.textMuted,
                            focusedContainerColor = palette.surfaceElevated,
                            unfocusedContainerColor = palette.surfaceElevated
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            if (commentInput.isNotBlank()) {
                                viewModel.addComment(channelId, activePostId, commentInput)
                                commentInput = ""
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = strings.send, tint = palette.emerald)
                    }
                }
            }
        }
    }

    fullscreenImageUrl?.let { imgUrl ->
        Dialog(
            onDismissRequest = { fullscreenImageUrl = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            FullscreenImageViewer(
                imageModel = imgUrl,
                onClose = { fullscreenImageUrl = null }
            )
        }
    }
}

@Composable
private fun EditChannelDialog(
    initialTitle: String,
    initialDesc: String,
    initialPublic: Boolean,
    onDismiss: () -> Unit,
    onSave: (title: String, description: String, isPublic: Boolean, avatarUri: Uri?) -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    var title by remember { mutableStateOf(initialTitle) }
    var description by remember { mutableStateOf(initialDesc) }
    var isPublic by remember { mutableStateOf(initialPublic) }
    var avatarUri by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) avatarUri = uri
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = {
            Text(
                text = if (strings.isEnglish) "Edit Channel" else "تعديل القناة",
                color = palette.textPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val fieldColors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = palette.textPrimary,
                    unfocusedTextColor = palette.textPrimary,
                    cursorColor = palette.emerald,
                    focusedBorderColor = palette.emerald,
                    unfocusedBorderColor = palette.border,
                    focusedLabelColor = palette.emerald,
                    unfocusedLabelColor = palette.textSecondary,
                    focusedContainerColor = palette.surfaceElevated,
                    unfocusedContainerColor = palette.surfaceElevated
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(if (strings.isEnglish) "Channel Title" else "اسم القناة") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(if (strings.isEnglish) "Description" else "وصف القناة") },
                    maxLines = 3,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (strings.isEnglish) {
                            if (isPublic) "Public Channel" else "Private Channel"
                        } else {
                            if (isPublic) "قناة عامة" else "قناة خاصة"
                        },
                        color = palette.textPrimary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = isPublic, onCheckedChange = { isPublic = it })
                }
                TextButton(onClick = { picker.launch("image/*") }) {
                    Text(
                        text = if (avatarUri == null) {
                            if (strings.isEnglish) "Change Channel Photo" else "تغيير صورة القناة"
                        } else {
                            if (strings.isEnglish) "New photo selected ✓" else "تم اختيار صورة جديدة ✓"
                        },
                        color = palette.turquoise
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title, description, isPublic, avatarUri) },
                enabled = title.trim().isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary)
            ) {
                Text(strings.save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel, color = palette.textSecondary) }
        }
    )
}

@Composable
private fun ChannelPostCard(
    post: ChannelPost,
    myUid: String,
    canManage: Boolean,
    playbackState: VoicePlaybackState,
    onToggleAudio: () -> Unit,
    onReact: (String) -> Unit,
    onOpenComments: () -> Unit,
    onOpenImage: (String) -> Unit,
    onSaveImage: (String) -> Unit,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val timeLabel = remember(post.createdAt) {
        SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(post.createdAt))
    }
    val myReaction = post.userReactions[myUid]
    val playbackKey = "ch_${post.postId}"
    val isThisAudio = playbackState.activeMessageId == playbackKey

    Card(
        colors = CardDefaults.cardColors(containerColor = palette.surface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                WesalAvatar(imageUrl = post.authorAvatarUrl, name = post.authorName, size = 38.dp)
                Column(modifier = Modifier.weight(1f)) {
                    WesalNameWithBadge(
                        name = post.authorName.ifBlank { "WESAL Channel" },
                        isVerified = post.authorVerified,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(timeLabel, color = palette.textMuted, style = MaterialTheme.typography.labelSmall)
                }
                if (post.isPinned) {
                    Icon(Icons.Default.PushPin, contentDescription = null, tint = palette.emerald, modifier = Modifier.size(16.dp))
                }
                if (canManage) {
                    IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = strings.edit, tint = palette.turquoise, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onTogglePin, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.PushPin, contentDescription = null, tint = if (post.isPinned) palette.emerald else palette.textMuted, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = strings.delete, tint = palette.error, modifier = Modifier.size(18.dp))
                    }
                }
            }

            if (post.type == ChannelPostType.IMAGE && post.mediaUrl.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onOpenImage(post.mediaUrl) }
                ) {
                    AsyncImage(
                        model = wesalImageModel(post.mediaUrl),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Surface(
                        color = Color.Black.copy(alpha = 0.58f),
                        shape = CircleShape,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .clickable { onSaveImage(post.mediaUrl) }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = if (strings.isEnglish) "Save photo" else "حفظ الصورة",
                            tint = Color.White,
                            modifier = Modifier
                                .padding(7.dp)
                                .size(18.dp)
                        )
                    }
                }
            } else if (post.type == ChannelPostType.AUDIO && post.mediaUrl.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.surfaceElevated)
                        .clickable { onToggleAudio() }
                        .padding(10.dp)
                ) {
                    Icon(
                        imageVector = if (isThisAudio && playbackState.isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                        contentDescription = null,
                        tint = palette.emerald,
                        modifier = Modifier.size(34.dp)
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = post.mediaFileName.ifBlank { if (strings.isEnglish) "Audio post" else "منشور صوتي" },
                            color = palette.textPrimary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isThisAudio && playbackState.durationMs > 0) {
                            LinearProgressIndicator(
                                progress = { (playbackState.positionMs.toFloat() / playbackState.durationMs.toFloat()).coerceIn(0f, 1f) },
                                color = palette.emerald,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "${DateTimeUtils.formatDurationMillis(playbackState.positionMs.toLong())} / ${DateTimeUtils.formatDurationMillis(playbackState.durationMs.toLong())}",
                                color = palette.textMuted,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            } else if (post.type != ChannelPostType.TEXT && post.mediaUrl.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.surfaceElevated)
                        .padding(10.dp)
                ) {
                    Icon(
                        imageVector = when (post.type) {
                            ChannelPostType.VIDEO -> Icons.Default.VideoFile
                            else -> Icons.Default.Description
                        },
                        contentDescription = null,
                        tint = palette.turquoise
                    )
                    Text(
                        text = post.mediaFileName.ifBlank { post.type.raw },
                        color = palette.textPrimary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (post.text.isNotBlank()) {
                Text(post.text, color = palette.textPrimary, style = MaterialTheme.typography.bodyMedium)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                QUICK_CHANNEL_REACTIONS.forEach { emoji ->
                    val count = post.reactionCounts[emoji] ?: 0
                    val selected = myReaction == emoji
                    Surface(
                        color = if (selected) palette.emerald.copy(alpha = 0.22f) else palette.surfaceElevated,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.clickable { onReact(emoji) }
                    ) {
                        Text(
                            text = if (count > 0) "$emoji $count" else emoji,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) palette.emerald else palette.textSecondary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenComments) {
                    Icon(Icons.AutoMirrored.Filled.Comment, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${post.commentsCount}", maxLines = 1)
                }
            }
        }
    }
}
