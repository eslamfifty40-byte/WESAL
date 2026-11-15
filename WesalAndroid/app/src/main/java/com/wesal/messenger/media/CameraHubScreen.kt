package com.wesal.messenger.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.wesal.messenger.core.theme.*
import com.wesal.messenger.domain.model.ChatPreview
import com.wesal.messenger.domain.model.MessageType
import com.wesal.messenger.domain.model.PrivacyVisibility
import com.wesal.messenger.domain.repository.ChatRepository
import com.wesal.messenger.domain.repository.StoryPublishEvent
import com.wesal.messenger.domain.repository.StoryRepository
import com.wesal.messenger.presentation.components.WesalAvatar
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class CameraHubUiState(
    val isSending: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null
)

@HiltViewModel
class CameraHubViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatRepository: ChatRepository,
    private val storyRepository: StoryRepository
) : ViewModel() {

    val chats: StateFlow<List<ChatPreview>> = chatRepository.observeChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(CameraHubUiState())
    val uiState: StateFlow<CameraHubUiState> = _uiState.asStateFlow()

    fun publishAsStory(
        uri: Uri,
        isVideo: Boolean,
        caption: String,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _uiState.value = CameraHubUiState(isSending = true, progress = 0.05f)
            storyRepository.publishMediaStory(
                uri = uri,
                isVideo = isVideo,
                caption = caption,
                visibility = PrivacyVisibility.EVERYONE
            ).collect { event ->
                when (event) {
                    is StoryPublishEvent.Progress -> {
                        _uiState.value = CameraHubUiState(isSending = true, progress = event.fraction)
                    }
                    is StoryPublishEvent.Success -> {
                        _uiState.value = CameraHubUiState()
                        onSuccess()
                    }
                    is StoryPublishEvent.Failure -> {
                        _uiState.value = CameraHubUiState(error = event.errorMessage)
                    }
                }
            }
        }
    }

    fun sendToChat(
        chatId: String,
        uri: Uri,
        isVideo: Boolean,
        caption: String,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _uiState.value = CameraHubUiState(isSending = true, progress = 0.05f)
            val meta = queryMeta(uri, isVideo)
            val msgType = if (isVideo) MessageType.VIDEO else MessageType.IMAGE
            chatRepository.sendMediaMessage(
                chatId = chatId,
                uri = uri,
                type = msgType,
                fileName = meta.first,
                mimeType = meta.second,
                sizeBytes = meta.third,
                caption = caption
            ).collect { res ->
                res.onSuccess { p ->
                    if (p >= 1f) {
                        _uiState.value = CameraHubUiState()
                        onSuccess()
                    } else {
                        _uiState.value = CameraHubUiState(isSending = true, progress = p)
                    }
                }.onFailure { err ->
                    _uiState.value = CameraHubUiState(
                        error = err.localizedMessage ?: "تعذر إرسال الوسائط"
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun queryMeta(uri: Uri, isVideo: Boolean): Triple<String, String, Long> {
        val defaultName = if (isVideo) "camera_video.mp4" else "camera_photo.jpg"
        val defaultMime = if (isVideo) "video/mp4" else "image/jpeg"
        var name = defaultName
        var size = 0L
        val mime = context.contentResolver.getType(uri) ?: defaultMime
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (nIdx >= 0) name = c.getString(nIdx).orEmpty().ifBlank { defaultName }
                if (sIdx >= 0) size = c.getLong(sIdx)
            }
        }
        if (size == 0L && uri.scheme == "file") {
            size = uri.path?.let { File(it).length() } ?: 0L
        }
        return Triple(name, mime, size)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraHubScreen(
    onClose: () -> Unit,
    onStoryPublished: () -> Unit,
    onOpenChat: (String) -> Unit,
    viewModel: CameraHubViewModel = hiltViewModel()
) {
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var capturedMedia by remember { mutableStateOf<Triple<Uri, Boolean, String>?>(null) }

    val activeCapture = capturedMedia
    if (activeCapture == null) {
        CameraCaptureScreen(
            onMediaConfirmed = { uri, isVideo, caption ->
                capturedMedia = Triple(uri, isVideo, caption)
            },
            onClose = onClose
        )
        return
    }

    val (mediaUri, isVideo, caption) = activeCapture

    Scaffold(
        containerColor = WesalDeepBlack,
        topBar = {
            TopAppBar(
                title = { Text("اختر وجهة الإرسال", color = WesalTextPrimary) },
                navigationIcon = {
                    IconButton(onClick = { if (!uiState.isSending) capturedMedia = null }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "إعادة الالتقاط",
                            tint = WesalTextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WesalSurfaceDark)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Compact preview card
            Surface(
                color = WesalSurfaceDark,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, WesalSurfaceBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (isVideo) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(WesalDeepBlack, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            VideoPlayerBox(uri = mediaUri, modifier = Modifier.fillMaxSize())
                        }
                    } else {
                        AsyncImage(
                            model = mediaUri,
                            contentDescription = "معاينة",
                            modifier = Modifier
                                .size(64.dp)
                                .background(WesalDeepBlack, RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isVideo) "فيديو ملتقط بالكاميرا" else "صورة ملتقطة بالكاميرا",
                            color = WesalTextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        if (caption.isNotBlank()) {
                            Text(
                                text = caption,
                                color = WesalTextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            if (uiState.isSending) {
                LinearProgressIndicator(
                    progress = { uiState.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = WesalEmerald
                )
            }

            uiState.error?.let { err ->
                Surface(
                    color = WesalError.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(err, color = WesalError, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("إغلاق", color = WesalTextSecondary)
                        }
                    }
                }
            }

            // Destination 1: Publish to My Story (24h)
            Button(
                onClick = {
                    viewModel.publishAsStory(
                        uri = mediaUri,
                        isVideo = isVideo,
                        caption = caption,
                        onSuccess = onStoryPublished
                    )
                },
                enabled = !uiState.isSending,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = WesalEmerald,
                    contentColor = WesalDeepBlack
                )
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("نشر في حالتي (24 ساعة)", fontWeight = FontWeight.Bold)
            }

            HorizontalDivider(color = WesalSurfaceBorder)
            Text(
                text = "أو إرسال إلى محادثة مباشرة (${chats.size})",
                style = MaterialTheme.typography.titleSmall,
                color = WesalTurquoise,
                fontWeight = FontWeight.Bold
            )

            if (chats.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "لا توجد محادثات نشطة حالياً — يمكنك النشر في حالتك أعلاه أو بدء محادثة من الأصدقاء",
                        color = WesalTextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(chats, key = { it.chatId }) { chat ->
                        Surface(
                            color = WesalSurfaceDark,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !uiState.isSending) {
                                    viewModel.sendToChat(
                                        chatId = chat.chatId,
                                        uri = mediaUri,
                                        isVideo = isVideo,
                                        caption = caption,
                                        onSuccess = { onOpenChat(chat.chatId) }
                                    )
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                WesalAvatar(
                                    imageUrl = chat.avatarUrl,
                                    name = chat.title.ifBlank { "محادثة" },
                                    isOnline = chat.peerOnline,
                                    size = 44.dp
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = chat.title.ifBlank { "محادثة وصال" },
                                        color = WesalTextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (chat.lastMessagePreview.isNotBlank()) {
                                        Text(
                                            text = chat.lastMessagePreview,
                                            color = WesalTextSecondary,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Icon(
                                    Icons.Default.Send,
                                    contentDescription = "إرسال",
                                    tint = WesalEmerald
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
