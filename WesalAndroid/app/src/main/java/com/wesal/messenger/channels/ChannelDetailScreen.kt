package com.wesal.messenger.channels

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.core.util.WesalShareUtils
import com.wesal.messenger.domain.model.ChannelPost
import com.wesal.messenger.domain.model.ChannelPostType
import com.wesal.messenger.domain.model.ChannelRole
import com.wesal.messenger.presentation.components.WesalAvatar
import com.wesal.messenger.presentation.components.WesalNameWithBadge
import com.wesal.messenger.presentation.components.wesalImageModel
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
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current

    val channel by viewModel.activeChannel.collectAsStateWithLifecycle()
    val posts by viewModel.activePosts.collectAsStateWithLifecycle()
    val joinRequests by viewModel.activeJoinRequests.collectAsStateWithLifecycle()
    val comments by viewModel.activeComments.collectAsStateWithLifecycle()
    val uploadProgress by viewModel.uploadProgress.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var postText by remember { mutableStateOf("") }
    var showAttachMenu by remember { mutableStateOf(false) }
    var showRequestsDialog by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var commentSheetPostId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(channelId) {
        viewModel.loadChannel(channelId)
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
    val canManage = role == ChannelRole.OWNER || role == ChannelRole.MODERATOR
    val canReadPosts = ch != null && (ch.isPublic || role != ChannelRole.NONE)
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
                        WesalAvatar(imageUrl = ch?.avatarUrl.orEmpty(), name = ch?.title ?: "C", size = 38.dp)
                        Column {
                            WesalNameWithBadge(
                                name = ch?.title ?: strings.loading,
                                isVerified = ch?.isVerified == true,
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
                        IconButton(onClick = {
                            val msg = WesalShareUtils.buildChannelInviteMessage(ch.title, ch.inviteCode, strings.isEnglish)
                            WesalShareUtils.shareGeneral(context, msg, ch.title)
                        }) {
                            Icon(Icons.Default.Share, contentDescription = strings.share, tint = palette.textPrimary)
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
                                DropdownMenu(expanded = showAttachMenu, onDismissRequest = { showAttachMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(if (strings.isEnglish) "Photo" else "صورة") },
                                        leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                                        onClick = {
                                            showAttachMenu = false
                                            imagePicker.launch("image/*")
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(if (strings.isEnglish) "Video" else "فيديو") },
                                        leadingIcon = { Icon(Icons.Default.VideoFile, contentDescription = null) },
                                        onClick = {
                                            showAttachMenu = false
                                            videoPicker.launch("video/*")
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(if (strings.isEnglish) "Audio" else "صوتية") },
                                        leadingIcon = { Icon(Icons.Default.AudioFile, contentDescription = null) },
                                        onClick = {
                                            showAttachMenu = false
                                            audioPicker.launch("audio/*")
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(if (strings.isEnglish) "File" else "ملف") },
                                        leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
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
                                placeholder = { Text(strings.publishPost) },
                                maxLines = 4,
                                shape = RoundedCornerShape(22.dp),
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

            if (ch != null && role == ChannelRole.NONE) {
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
                            colors = ButtonDefaults.buttonColors(containerColor = palette.emerald)
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
                            onReact = { emoji -> viewModel.toggleReaction(channelId, post.postId, emoji) },
                            onOpenComments = {
                                viewModel.selectPostForComments(post.postId)
                                commentSheetPostId = post.postId
                            },
                            onTogglePin = { viewModel.togglePin(channelId, post.postId, !post.isPinned) },
                            onDelete = { viewModel.deletePost(channelId, post.postId) }
                        )
                    }
                }
            }
        }
    }

    if (showInfoDialog && ch != null) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            containerColor = palette.surface,
            title = { WesalNameWithBadge(name = ch.title, isVerified = ch.isVerified, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ch.description.isNotBlank()) {
                        Text(ch.description, color = palette.textSecondary)
                    }
                    Text(
                        text = "${if (strings.isEnglish) "Invite Code" else "رمز الدعوة"}: ${ch.inviteCode}",
                        color = palette.emerald,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (canManage) {
                        TextButton(onClick = { viewModel.regenerateInviteCode(ch.channelId) }) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (strings.isEnglish) "Regenerate Invite Code" else "تجديد رمز الدعوة")
                        }
                    }
                    if (role == ChannelRole.SUBSCRIBER || role == ChannelRole.MODERATOR) {
                        TextButton(onClick = {
                            viewModel.unsubscribe(ch.channelId)
                            showInfoDialog = false
                        }) {
                            Text(strings.unsubscribeChannel, color = palette.error)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) { Text(strings.close) }
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
                TextButton(onClick = { showRequestsDialog = false }) { Text(strings.close) }
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
                        placeholder = { Text(if (strings.isEnglish) "Add a comment..." else "أضف تعليقاً...") },
                        singleLine = true,
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
}

@Composable
private fun ChannelPostCard(
    post: ChannelPost,
    myUid: String,
    canManage: Boolean,
    onReact: (String) -> Unit,
    onOpenComments: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val timeLabel = remember(post.createdAt) {
        SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(post.createdAt))
    }
    val myReaction = post.userReactions[myUid]

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
                    IconButton(onClick = onTogglePin, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.PushPin, contentDescription = null, tint = if (post.isPinned) palette.emerald else palette.textMuted, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = strings.delete, tint = palette.error, modifier = Modifier.size(18.dp))
                    }
                }
            }

            if (post.type == ChannelPostType.IMAGE && post.mediaUrl.isNotBlank()) {
                AsyncImage(
                    model = wesalImageModel(post.mediaUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
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
                            ChannelPostType.AUDIO -> Icons.Default.AudioFile
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
