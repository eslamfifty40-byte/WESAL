package com.wesal.messenger.media

import com.wesal.messenger.core.util.ErrorCopy
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
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.domain.model.ChatPreview
import com.wesal.messenger.domain.model.MessageType
import com.wesal.messenger.domain.model.PrivacyVisibility
import com.wesal.messenger.domain.model.StoryAudienceMode
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.ChatRepository
import com.wesal.messenger.domain.repository.StoryPublishEvent
import com.wesal.messenger.domain.repository.StoryRepository
import com.wesal.messenger.domain.repository.UserRepository
import com.wesal.messenger.presentation.components.WesalAvatar
import com.wesal.messenger.stories.StoryAudienceSummaryRow
import com.wesal.messenger.stories.StoryContactsPickerDialog
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
    private val storyRepository: StoryRepository,
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository,
    private val prefs: UserPreferencesDataStore
) : ViewModel() {

    val chats: StateFlow<List<ChatPreview>> = combine(
        // Review M-3: share-target picker needs the whole list — the paged
        // observeChats() truncated targets past the newest 100 chats.
        chatRepository.observeAllChats(),
        prefs.settingsFlow.map { it.hiddenChatIds }
    ) { list, hiddenIds ->
        list.filterNot { it.isOfficialWesalChat || it.chatId in hiddenIds }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedAudienceMode: StateFlow<StoryAudienceMode> = prefs.settingsFlow
        .map { StoryAudienceMode.fromRaw(it.lastStoryAudienceMode) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, StoryAudienceMode.CONTACTS)

    val acceptedContacts: StateFlow<List<WesalUser>> = combine(
        authRepository.observeAuthState().map { it.orEmpty() },
        userRepository.observeContactRequests()
    ) { uid, requests ->
        uid to requests.filter { it.status.raw == "ACCEPTED" }
    }.map { (uid, accepted) ->
        if (uid.isBlank()) emptyList()
        else accepted.map { if (it.senderUid == uid) it.receiverUid else it.senderUid }
            .distinct()
            .filter { it.isNotBlank() && it != uid }
            .mapNotNull { pid -> userRepository.getUserOnce(pid) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(CameraHubUiState())
    val uiState: StateFlow<CameraHubUiState> = _uiState.asStateFlow()

    fun persistAudienceMode(mode: StoryAudienceMode) {
        viewModelScope.launch { prefs.setLastStoryAudienceMode(mode.raw) }
    }

    fun publishAsStory(
        uri: Uri,
        isVideo: Boolean,
        caption: String,
        audienceMode: StoryAudienceMode,
        allowedViewerIds: List<String>,
        excludedViewerIds: List<String>,
        onSuccess: () -> Unit
    ) {
        val visibility = if (audienceMode == StoryAudienceMode.ALL_CONTACTS) PrivacyVisibility.EVERYONE else PrivacyVisibility.CONTACTS
        viewModelScope.launch {
            _uiState.value = CameraHubUiState(isSending = true, progress = 0.05f)
            storyRepository.publishMediaStory(
                uri = uri,
                isVideo = isVideo,
                caption = caption,
                visibility = visibility,
                audienceMode = audienceMode,
                allowedViewerIds = allowedViewerIds,
                excludedViewerIds = excludedViewerIds
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
                        error = ErrorCopy.friendly(err, "تعذر إرسال الوسائط")
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
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val savedAudience by viewModel.savedAudienceMode.collectAsStateWithLifecycle()
    val contacts by viewModel.acceptedContacts.collectAsStateWithLifecycle()

    var capturedMedia by remember { mutableStateOf<Triple<Uri, Boolean, String>?>(null) }
    var selectedAudience by remember(savedAudience) { mutableStateOf(savedAudience) }
    var selectedContactIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showContactsPickerForMode by remember { mutableStateOf<StoryAudienceMode?>(null) }

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
        containerColor = palette.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (strings.isEnglish) "Send To" else "اختر وجهة الإرسال",
                        color = palette.textPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (!uiState.isSending) capturedMedia = null }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.back,
                            tint = palette.textPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.surface)
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
            Surface(
                color = palette.surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, palette.border),
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
                                .background(palette.background, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            VideoPlayerBox(uri = mediaUri, modifier = Modifier.fillMaxSize())
                        }
                    } else {
                        AsyncImage(
                            model = mediaUri,
                            contentDescription = null,
                            modifier = Modifier
                                .size(64.dp)
                                .background(palette.background, RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                strings.isEnglish && isVideo -> "Captured Video"
                                strings.isEnglish -> "Captured Photo"
                                isVideo -> "فيديو ملتقط بالكاميرا"
                                else -> "صورة ملتقطة بالكاميرا"
                            },
                            color = palette.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        if (caption.isNotBlank()) {
                            Text(
                                text = caption,
                                color = palette.textSecondary,
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
                    color = palette.emerald
                )
            }

            uiState.error?.let { err ->
                Surface(
                    color = palette.error.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(err, color = palette.error, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text(strings.close, color = palette.textSecondary)
                        }
                    }
                }
            }

            StoryAudienceSummaryRow(
                audienceMode = selectedAudience,
                selectedCount = selectedContactIds.size,
                totalContactsCount = contacts.size,
                onSelectMode = { mode ->
                    selectedAudience = mode
                    viewModel.persistAudienceMode(mode)
                    if (mode == StoryAudienceMode.CONTACTS_EXCEPT || mode == StoryAudienceMode.ONLY_WITH) {
                        showContactsPickerForMode = mode
                    }
                }
            )

            Button(
                onClick = {
                    val allowed = if (selectedAudience == StoryAudienceMode.ONLY_WITH) selectedContactIds.toList() else emptyList()
                    val excluded = if (selectedAudience == StoryAudienceMode.CONTACTS_EXCEPT) selectedContactIds.toList() else emptyList()
                    viewModel.publishAsStory(
                        uri = mediaUri,
                        isVideo = isVideo,
                        caption = caption,
                        audienceMode = selectedAudience,
                        allowedViewerIds = allowed,
                        excludedViewerIds = excluded,
                        onSuccess = onStoryPublished
                    )
                },
                enabled = !uiState.isSending,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.emerald,
                    contentColor = palette.onAccent
                )
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (strings.isEnglish) "Publish to My Story (24h)" else "نشر في حالتي (24 ساعة)",
                    fontWeight = FontWeight.Bold
                )
            }

            HorizontalDivider(color = palette.border)
            Text(
                text = if (strings.isEnglish) {
                    "Or send to a direct chat (${chats.size})"
                } else {
                    "أو إرسال إلى محادثة مباشرة (${chats.size})"
                },
                style = MaterialTheme.typography.titleSmall,
                color = palette.turquoise,
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
                        text = if (strings.isEnglish) {
                            "No active chats yet — publish to your story above or start a chat from Contacts"
                        } else {
                            "لا توجد محادثات نشطة حالياً — يمكنك النشر في حالتك أعلاه أو بدء محادثة من الأصدقاء"
                        },
                        color = palette.textSecondary,
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
                            color = palette.surface,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, palette.border),
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
                                    name = chat.title.ifBlank { strings.appShortName },
                                    isOnline = chat.peerOnline,
                                    size = 44.dp
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = chat.title.ifBlank { strings.appDisplayName },
                                        color = palette.textPrimary,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (chat.lastMessagePreview.isNotBlank()) {
                                        Text(
                                            text = chat.lastMessagePreview,
                                            color = palette.textSecondary,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Icon(
                                    Icons.Default.Send,
                                    contentDescription = strings.send,
                                    tint = palette.emerald
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    showContactsPickerForMode?.let { mode ->
        StoryContactsPickerDialog(
            mode = mode,
            contacts = contacts,
            initialSelectedIds = selectedContactIds,
            onDismiss = { showContactsPickerForMode = null },
            onConfirm = { picked ->
                selectedContactIds = picked
                showContactsPickerForMode = null
            }
        )
    }
}
