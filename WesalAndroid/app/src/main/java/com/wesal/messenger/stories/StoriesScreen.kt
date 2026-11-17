package com.wesal.messenger.stories

import android.content.Context
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Base64
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.wesal.messenger.core.theme.*
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.crop.CropActivity
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.domain.model.PrivacyVisibility
import com.wesal.messenger.domain.model.StoryAudienceMode
import com.wesal.messenger.domain.model.StoryItem
import com.wesal.messenger.domain.model.StoryType
import com.wesal.messenger.domain.model.StoryViewer
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.StoryPublishEvent
import com.wesal.messenger.domain.repository.StoryRepository
import com.wesal.messenger.domain.repository.UserRepository
import com.wesal.messenger.media.CameraCaptureScreen
import com.wesal.messenger.media.VideoPlayerBox
import com.wesal.messenger.presentation.components.WesalAvatar
import com.wesal.messenger.presentation.components.WesalNameWithBadge
import com.wesal.messenger.presentation.components.wesalImageModel
import com.wesal.messenger.voice.VoicePlaybackState
import com.wesal.messenger.voice.VoicePlayerManager
import com.wesal.messenger.voice.VoiceRecorderManager
import com.wesal.messenger.voice.VoiceRecordingState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import javax.inject.Inject

data class PendingStoryMedia(
    val uri: Uri,
    val isVideo: Boolean,
    val caption: String,
    val visibility: PrivacyVisibility,
    val audienceMode: StoryAudienceMode,
    val allowedViewerIds: List<String>,
    val excludedViewerIds: List<String>
)

data class StoriesUploadUiState(
    val isPublishing: Boolean = false,
    val uploadProgress: Float = 0f,
    val errorMessage: String? = null,
    val failedRequest: PendingStoryMedia? = null,
    val statusBanner: String? = null
)

@HiltViewModel
class StoriesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storyRepository: StoryRepository,
    private val userRepository: UserRepository,
    private val voiceRecorder: VoiceRecorderManager,
    private val voicePlayer: VoicePlayerManager,
    private val prefs: UserPreferencesDataStore,
    authRepository: AuthRepository
) : ViewModel() {

    val myUid: StateFlow<String> = authRepository.observeAuthState()
        .map { it.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, authRepository.currentUserId.orEmpty())

    val stories: StateFlow<List<StoryItem>> = storyRepository.observeActiveStories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedAudienceMode: StateFlow<StoryAudienceMode> = prefs.settingsFlow
        .map { StoryAudienceMode.fromRaw(it.lastStoryAudienceMode) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, StoryAudienceMode.CONTACTS)

    val acceptedContacts: StateFlow<List<WesalUser>> = combine(
        myUid,
        userRepository.observeContactRequests()
    ) { uid, requests ->
        uid to requests.filter { it.status.raw == "ACCEPTED" }
    }.map { (uid, accepted) ->
        if (uid.isBlank()) {
            emptyList()
        } else {
            val peerIds = accepted.map { if (it.senderUid == uid) it.receiverUid else it.senderUid }
                .distinct()
                .filter { it.isNotBlank() && it != uid }
            peerIds.mapNotNull { pid -> userRepository.getUserOnce(pid) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recordingState: StateFlow<VoiceRecordingState> = voiceRecorder.state
    val playbackState: StateFlow<VoicePlaybackState> = voicePlayer.playbackState

    private val _uploadState = MutableStateFlow(StoriesUploadUiState())
    val uploadState: StateFlow<StoriesUploadUiState> = _uploadState.asStateFlow()

    private val cachedAudioFiles = mutableMapOf<String, File>()

    fun persistAudienceMode(mode: StoryAudienceMode) {
        viewModelScope.launch { prefs.setLastStoryAudienceMode(mode.raw) }
    }

    fun publishTextStory(
        text: String,
        bgHex: Long,
        audienceMode: StoryAudienceMode,
        allowedViewerIds: List<String>,
        excludedViewerIds: List<String>
    ) {
        if (text.isBlank()) return
        val visibility = if (audienceMode == StoryAudienceMode.ALL_CONTACTS) PrivacyVisibility.EVERYONE else PrivacyVisibility.CONTACTS
        viewModelScope.launch {
            _uploadState.value = StoriesUploadUiState(isPublishing = true, uploadProgress = 0.5f)
            storyRepository.publishTextStory(text, bgHex, visibility, audienceMode, allowedViewerIds, excludedViewerIds)
                .onSuccess {
                    _uploadState.value = StoriesUploadUiState(statusBanner = "تم نشر الحالة النصية بنجاح")
                }
                .onFailure { err ->
                    _uploadState.value = StoriesUploadUiState(
                        errorMessage = err.localizedMessage ?: "تعذر نشر الحالة النصية"
                    )
                }
        }
    }

    fun publishMediaStory(
        uri: Uri,
        isVideo: Boolean,
        caption: String,
        audienceMode: StoryAudienceMode,
        allowedViewerIds: List<String>,
        excludedViewerIds: List<String>
    ) {
        val visibility = if (audienceMode == StoryAudienceMode.ALL_CONTACTS) PrivacyVisibility.EVERYONE else PrivacyVisibility.CONTACTS
        val request = PendingStoryMedia(uri, isVideo, caption, visibility, audienceMode, allowedViewerIds, excludedViewerIds)
        viewModelScope.launch {
            _uploadState.value = StoriesUploadUiState(isPublishing = true, uploadProgress = 0.02f)
            storyRepository.publishMediaStory(uri, isVideo, caption, visibility, audienceMode, allowedViewerIds, excludedViewerIds).collect { event ->
                when (event) {
                    is StoryPublishEvent.Progress -> {
                        _uploadState.value = StoriesUploadUiState(isPublishing = true, uploadProgress = event.fraction)
                    }
                    is StoryPublishEvent.Success -> {
                        _uploadState.value = StoriesUploadUiState(statusBanner = "تم نشر الحالة بنجاح")
                    }
                    is StoryPublishEvent.Failure -> {
                        _uploadState.value = StoriesUploadUiState(
                            errorMessage = event.errorMessage,
                            failedRequest = request
                        )
                    }
                }
            }
        }
    }

    fun startAudioRecording(): Boolean = voiceRecorder.startRecording()

    fun stopAudioRecording(): Pair<File, Long>? {
        val rec = voiceRecorder.stopRecording() ?: return null
        val file = rec.outputFile ?: return null
        return file to rec.elapsedMs
    }

    fun cancelAudioRecording() {
        voiceRecorder.cancelRecording()
    }

    fun toggleDraftVoiceFile(file: File) {
        if (file.exists()) {
            voicePlayer.togglePlay("draft_audio_story", file)
        }
    }

    fun publishAudioStory(
        audioUri: Uri,
        durationMs: Long,
        caption: String,
        audienceMode: StoryAudienceMode,
        allowedViewerIds: List<String>,
        excludedViewerIds: List<String>
    ) {
        val visibility = if (audienceMode == StoryAudienceMode.ALL_CONTACTS) PrivacyVisibility.EVERYONE else PrivacyVisibility.CONTACTS
        viewModelScope.launch {
            _uploadState.value = StoriesUploadUiState(isPublishing = true, uploadProgress = 0.08f)
            storyRepository.publishAudioStory(audioUri, durationMs, caption, visibility, audienceMode, allowedViewerIds, excludedViewerIds).collect { event ->
                when (event) {
                    is StoryPublishEvent.Progress -> {
                        _uploadState.value = StoriesUploadUiState(isPublishing = true, uploadProgress = event.fraction)
                    }
                    is StoryPublishEvent.Success -> {
                        _uploadState.value = StoriesUploadUiState(statusBanner = "تم نشر الحالة الصوتية بنجاح")
                    }
                    is StoryPublishEvent.Failure -> {
                        _uploadState.value = StoriesUploadUiState(errorMessage = event.errorMessage)
                    }
                }
            }
        }
    }

    fun toggleAudioPlayback(storyId: String, mediaUrl: String) {
        val existing = cachedAudioFiles[storyId]
        if (existing != null && existing.exists()) {
            voicePlayer.togglePlay(storyId, existing)
            return
        }
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val out = File(context.cacheDir, "story_audio_$storyId.m4a")
                    if (mediaUrl.startsWith("data:audio", ignoreCase = true)) {
                        val idx = mediaUrl.indexOf("base64,")
                        if (idx >= 0) {
                            val bytes = Base64.decode(mediaUrl.substring(idx + 7), Base64.DEFAULT)
                            out.writeBytes(bytes)
                        }
                    } else if (mediaUrl.startsWith("http", ignoreCase = true)) {
                        URL(mediaUrl).openStream().use { input ->
                            out.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    if (out.exists() && out.length() > 0L) out else null
                }.getOrNull()
            }
            if (file != null) {
                cachedAudioFiles[storyId] = file
                voicePlayer.togglePlay(storyId, file)
            } else {
                _uploadState.update { it.copy(errorMessage = "تعذر تشغيل الحالة الصوتية") }
            }
        }
    }

    fun stopAudioPlayback() {
        voicePlayer.stop()
    }

    fun retryFailedUpload() {
        val req = _uploadState.value.failedRequest ?: return
        publishMediaStory(req.uri, req.isVideo, req.caption, req.audienceMode, req.allowedViewerIds, req.excludedViewerIds)
    }

    fun clearError() {
        _uploadState.update { it.copy(errorMessage = null, failedRequest = null) }
    }

    fun clearBanner() {
        _uploadState.update { it.copy(statusBanner = null) }
    }

    fun observeViewers(storyId: String): Flow<List<StoryViewer>> =
        storyRepository.observeStoryViewers(storyId)

    fun markViewed(story: StoryItem) {
        val uid = myUid.value
        if (uid.isNotBlank() && story.ownerUid != uid && !story.viewerIds.contains(uid)) {
            viewModelScope.launch { storyRepository.markStoryViewed(story.storyId) }
        }
    }

    fun deleteStory(storyId: String) {
        viewModelScope.launch {
            storyRepository.deleteStory(storyId)
                .onSuccess { _uploadState.update { it.copy(statusBanner = "تم حذف الحالة") } }
                .onFailure { err ->
                    _uploadState.update { it.copy(errorMessage = err.localizedMessage ?: "تعذر حذف الحالة") }
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceRecorder.cancelRecording()
        voicePlayer.stop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoriesScreen(viewModel: StoriesViewModel = hiltViewModel()) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val context = LocalContext.current
    val activity = context as? Activity
    val myUid by viewModel.myUid.collectAsStateWithLifecycle()
    val stories by viewModel.stories.collectAsStateWithLifecycle()
    val uploadState by viewModel.uploadState.collectAsStateWithLifecycle()
    val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val savedAudience by viewModel.savedAudienceMode.collectAsStateWithLifecycle()
    val contacts by viewModel.acceptedContacts.collectAsStateWithLifecycle()

    var showTextDialog by remember { mutableStateOf(false) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var showInlineCamera by remember { mutableStateOf(false) }
    var activeStory by remember { mutableStateOf<StoryItem?>(null) }
    var viewersSheetStory by remember { mutableStateOf<StoryItem?>(null) }

    var selectedAudience by remember(savedAudience) { mutableStateOf(savedAudience) }
    var selectedContactIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showContactsPickerForMode by remember { mutableStateOf<StoryAudienceMode?>(null) }

    var draftMedia by remember { mutableStateOf<Pair<Uri, Boolean>?>(null) }
    var draftCaption by remember { mutableStateOf("") }
    var recordedAudioDraft by remember { mutableStateOf<Pair<File, Long>?>(null) }

    val cropLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { croppedUri ->
                draftMedia = croppedUri to false
            }
        }
    }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri).orEmpty()
            draftCaption = ""
            draftMedia = uri to mime.startsWith("video")
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            recordedAudioDraft = null
            viewModel.startAudioRecording()
        }
    }

    if (showInlineCamera) {
        CameraCaptureScreen(
            onMediaConfirmed = { uri, isVideo, caption ->
                showInlineCamera = false
                draftCaption = caption
                draftMedia = uri to isVideo
            },
            onClose = { showInlineCamera = false }
        )
        return
    }

    // Story preview screen before uploading (supports crop, caption & audience selection)
    draftMedia?.let { (uri, isVideo) ->
        Box(modifier = Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            if (isVideo) {
                VideoPlayerBox(uri = uri, modifier = Modifier.fillMaxSize())
            } else {
                AsyncImage(
                    model = uri,
                    contentDescription = "معاينة الحالة",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { draftMedia = null }) {
                    Icon(Icons.Default.Close, contentDescription = "إلغاء", tint = Color.White)
                }
                if (!isVideo) {
                    OutlinedButton(
                        onClick = {
                            cropLauncher.launch(CropActivity.createIntent(context, uri, CropActivity.MODE_SQUARE))
                        },
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Crop, contentDescription = null, tint = WesalTurquoise, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("قص الصورة", color = WesalTurquoise)
                    }
                }
            }

            Surface(
                color = WesalSurfaceDark.copy(alpha = 0.94f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = draftCaption,
                            onValueChange = { draftCaption = it },
                            placeholder = { Text("أضف نصاً أو وصفاً للحالة...", color = WesalTextMuted) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = WesalTextPrimary,
                                unfocusedTextColor = WesalTextPrimary,
                                cursorColor = WesalTurquoise,
                                focusedBorderColor = WesalTurquoise,
                                unfocusedBorderColor = WesalSurfaceBorder,
                                focusedPlaceholderColor = WesalTextMuted,
                                unfocusedPlaceholderColor = WesalTextMuted,
                                focusedContainerColor = WesalSurfaceElevated,
                                unfocusedContainerColor = WesalSurfaceElevated
                            ),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(24.dp)
                        )
                        Button(
                            onClick = {
                                val allowed = if (selectedAudience == StoryAudienceMode.ONLY_WITH) selectedContactIds.toList() else emptyList()
                                val excluded = if (selectedAudience == StoryAudienceMode.CONTACTS_EXCEPT) selectedContactIds.toList() else emptyList()
                                viewModel.publishMediaStory(uri, isVideo, draftCaption.trim(), selectedAudience, allowed, excluded)
                                draftMedia = null
                                draftCaption = ""
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = WesalEmerald,
                                contentColor = WesalDeepBlack
                            ),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Text("نشر", fontWeight = FontWeight.Bold)
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
        return
    }

    // Active story fullscreen viewer (FLAG_SECURE strictly on for ALL non-owner viewers)
    val currentActiveStory = activeStory?.let { cur -> stories.firstOrNull { it.storyId == cur.storyId } ?: cur }
    currentActiveStory?.let { story ->
        val isOwner = story.ownerUid == myUid
        LaunchedEffect(story.storyId) { viewModel.markViewed(story) }
        DisposableEffect(story.storyId, isOwner) {
            if (!isOwner) {
                activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            onDispose {
                viewModel.stopAudioPlayback()
                if (!isOwner) {
                    activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }

        val currentIdx = stories.indexOfFirst { it.storyId == story.storyId }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    when (story.type) {
                        StoryType.TEXT -> Color(story.backgroundColorHex)
                        StoryType.AUDIO -> WesalDeepBlack
                        else -> Color.Black
                    }
                )
        ) {
            when (story.type) {
                StoryType.TEXT -> {
                    Text(
                        text = story.textContent,
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp)
                    )
                }
                StoryType.IMAGE -> {
                    AsyncImage(
                        model = wesalImageModel(story.mediaUrl),
                        contentDescription = "حالة",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
                StoryType.VIDEO -> {
                    VideoPlayerBox(uri = Uri.parse(story.mediaUrl), modifier = Modifier.fillMaxSize())
                }
                StoryType.AUDIO -> {
                    val isThisPlaying = playbackState.activeMessageId == story.storyId && playbackState.isPlaying
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(WesalDeepBlack, WesalEmerald.copy(alpha = 0.25f), WesalDeepBlack)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            color = WesalSurfaceDark.copy(alpha = 0.9f),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(CircleShape)
                                        .background(WesalEmerald.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.GraphicEq,
                                        contentDescription = null,
                                        tint = WesalTurquoise,
                                        modifier = Modifier.size(38.dp)
                                    )
                                }
                                Text(
                                    text = "حالة صوتية",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = WesalTextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                if (playbackState.activeMessageId == story.storyId && playbackState.durationMs > 0) {
                                    LinearProgressIndicator(
                                        progress = {
                                            (playbackState.positionMs.toFloat() / playbackState.durationMs.toFloat()).coerceIn(0f, 1f)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = WesalEmerald
                                    )
                                    Text(
                                        text = "${DateTimeUtils.formatDurationMillis(playbackState.positionMs.toLong())} / ${DateTimeUtils.formatDurationMillis(playbackState.durationMs.toLong())}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = WesalTextSecondary
                                    )
                                }
                                Button(
                                    onClick = { viewModel.toggleAudioPlayback(story.storyId, story.mediaUrl) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = WesalEmerald,
                                        contentColor = WesalDeepBlack
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isThisPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = null
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = if (isThisPlaying) "إيقاف مؤقت" else "تشغيل الحالة الصوتية",
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Navigation Arrows for Next / Previous Story
            if (currentIdx > 0) {
                IconButton(
                    onClick = {
                        viewModel.stopAudioPlayback()
                        activeStory = stories[currentIdx - 1]
                    },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 8.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                ) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = null, tint = Color.White)
                }
            }
            if (currentIdx >= 0 && currentIdx < stories.lastIndex) {
                IconButton(
                    onClick = {
                        viewModel.stopAudioPlayback()
                        activeStory = stories[currentIdx + 1]
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                ) {
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.White)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WesalAvatar(imageUrl = story.ownerAvatarUrl, name = story.ownerName, size = 40.dp)
                    Column {
                        WesalNameWithBadge(
                            name = story.ownerName,
                            isVerified = story.ownerVerified,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            DateTimeUtils.formatChatListTime(story.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
                Row {
                    // Only the owner can delete or manage; viewers NEVER see download/save/share/forward
                    if (isOwner) {
                        IconButton(onClick = {
                            viewModel.stopAudioPlayback()
                            viewModel.deleteStory(story.storyId)
                            activeStory = null
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "حذف الحالة", tint = WesalError)
                        }
                    }
                    IconButton(onClick = {
                        viewModel.stopAudioPlayback()
                        activeStory = null
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.White)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (story.caption.isNotBlank() && story.type != StoryType.TEXT) {
                    Surface(color = Color.Black.copy(alpha = 0.65f), shape = RoundedCornerShape(12.dp)) {
                        Text(
                            text = story.caption,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
                if (isOwner) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.clickable { viewersSheetStory = story }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Visibility, contentDescription = "عرض المشاهدين", tint = WesalTurquoise, modifier = Modifier.size(18.dp))
                            Text("${story.viewerIds.size} مشاهدة", color = Color.White, fontWeight = FontWeight.Bold)
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        if (isOwner) {
            viewersSheetStory?.let { targetStory ->
                StoryViewersBottomSheet(
                    story = targetStory,
                    viewersFlow = viewModel.observeViewers(targetStory.storyId),
                    onDismiss = { viewersSheetStory = null }
                )
            }
        }
        return
    }

    val myStories = stories.filter { it.ownerUid == myUid }
    val friendsStories = stories.filter { it.ownerUid != myUid }

    Box(modifier = Modifier.fillMaxSize().background(palette.background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (uploadState.isPublishing) {
                item {
                    Surface(color = palette.surface, shape = RoundedCornerShape(12.dp)) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = if (strings.isEnglish) {
                                    "Uploading & publishing story (${(uploadState.uploadProgress * 100).toInt()}%)"
                                } else {
                                    "جاري رفع ونشر الحالة (${(uploadState.uploadProgress * 100).toInt()}%)"
                                },
                                color = palette.turquoise,
                                fontWeight = FontWeight.Medium
                            )
                            LinearProgressIndicator(
                                progress = { uploadState.uploadProgress },
                                modifier = Modifier.fillMaxWidth(),
                                color = palette.turquoise
                            )
                        }
                    }
                }
            }

            uploadState.errorMessage?.let { errMsg ->
                item {
                    Surface(
                        color = palette.error.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(errMsg, color = palette.error, style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (uploadState.failedRequest != null) {
                                    Button(
                                        onClick = { viewModel.retryFailedUpload() },
                                        colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onAccent)
                                    ) {
                                        Text(strings.retry, fontWeight = FontWeight.Bold)
                                    }
                                }
                                TextButton(onClick = { viewModel.clearError() }) {
                                    Text(strings.close, color = palette.textSecondary)
                                }
                            }
                        }
                    }
                }
            }

            uploadState.statusBanner?.let { msg ->
                item {
                    Surface(
                        color = palette.emerald.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(msg, color = palette.emerald)
                            IconButton(onClick = { viewModel.clearBanner() }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = strings.close, tint = palette.emerald, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = if (strings.isEnglish) "My Story (expires after 24h)" else "حالتي (تنتهي بعد 24 ساعة)",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.turquoise
                )
            }

            item {
                Surface(
                    color = palette.surface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, palette.border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(palette.emerald.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = palette.emerald)
                            }
                            Column {
                                Text(
                                    text = if (strings.isEnglish) "Add a New Story" else "إضافة حالة جديدة",
                                    color = palette.textPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (strings.isEnglish) {
                                        "Photo, video, camera, voice note, or text"
                                    } else {
                                        "صورة، فيديو، كاميرا، حالة صوتية، أو نصية"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = palette.textSecondary
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showInlineCamera = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, tint = palette.emerald, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = strings.storyCamera,
                                    color = palette.emerald,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                            OutlinedButton(
                                onClick = { showAudioDialog = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = strings.storyVoice,
                                    color = palette.turquoise,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                            OutlinedButton(
                                onClick = { showTextDialog = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, tint = palette.textPrimary, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = strings.storyText,
                                    color = palette.textPrimary,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }

                        HorizontalDivider(color = palette.border)

                        // Sec 21: "من يستطيع مشاهدة حالتي؟" selector directly beneath Story Box
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
                    }
                }
            }

            if (myStories.isNotEmpty()) {
                items(myStories, key = { it.storyId }) { story ->
                    StoryRowCard(
                        story = story,
                        isMine = true,
                        onClick = { activeStory = story },
                        onShowViewers = { viewersSheetStory = story }
                    )
                }
            }

            item {
                Text(
                    text = if (strings.isEnglish) "Friends Updates" else "تحديثات الأصدقاء",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textSecondary
                )
            }

            if (friendsStories.isEmpty()) {
                item {
                    Text(
                        text = if (strings.isEnglish) "No active stories from friends right now" else "لا توجد حالات نشطة من الأصدقاء حالياً",
                        color = palette.textMuted
                    )
                }
            } else {
                items(friendsStories, key = { it.storyId }) { story ->
                    StoryRowCard(story = story, isMine = false, onClick = { activeStory = story })
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End
        ) {
            SmallFloatingActionButton(
                onClick = { showAudioDialog = true },
                containerColor = palette.surfaceElevated,
                contentColor = palette.emerald
            ) {
                Icon(Icons.Default.Mic, contentDescription = strings.storyVoice)
            }
            SmallFloatingActionButton(
                onClick = { showTextDialog = true },
                containerColor = palette.surfaceElevated,
                contentColor = palette.turquoise
            ) {
                Icon(Icons.Default.Edit, contentDescription = strings.storyText)
            }
            FloatingActionButton(
                onClick = { showInlineCamera = true },
                containerColor = palette.emerald,
                contentColor = palette.onAccent
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = strings.storyCamera)
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

    if (showAudioDialog) {
        var audioCaption by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {
                viewModel.stopAudioPlayback()
                viewModel.cancelAudioRecording()
                recordedAudioDraft = null
                showAudioDialog = false
            },
            containerColor = palette.surface,
            title = {
                Text(
                    text = if (strings.isEnglish) "Record Voice Story" else "تسجيل حالة صوتية",
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val recFile = recordedAudioDraft
                    Text(
                        text = when {
                            recordingState.isRecording -> (if (strings.isEnglish) "Recording: " else "جاري التسجيل: ") + DateTimeUtils.formatDurationMillis(recordingState.elapsedMs)
                            recFile != null -> (if (strings.isEnglish) "Clip recorded (" else "تم تسجيل المقطع (") + DateTimeUtils.formatDurationMillis(recFile.second) + ")"
                            else -> if (strings.isEnglish) "Tap Start to record a voice clip" else "اضغط بدء لتسجيل رسالة صوتية لحالتك"
                        },
                        color = if (recordingState.isRecording) palette.error else palette.turquoise,
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!recordingState.isRecording) {
                            Button(
                                onClick = {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) {
                                        viewModel.stopAudioPlayback()
                                        recordedAudioDraft = null
                                        viewModel.startAudioRecording()
                                    } else {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = palette.emerald,
                                    contentColor = palette.onAccent
                                )
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (recFile == null) {
                                        if (strings.isEnglish) "Start Recording" else "بدء التسجيل"
                                    } else {
                                        if (strings.isEnglish) "Re-record" else "إعادة التسجيل"
                                    },
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (recFile != null) {
                                val isDraftPlaying = playbackState.activeMessageId == "draft_audio_story" && playbackState.isPlaying
                                OutlinedButton(onClick = { viewModel.toggleDraftVoiceFile(recFile.first) }) {
                                    Icon(if (isDraftPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (isDraftPlaying) "إيقاف" else "سماع")
                                }
                            }
                        } else {
                            Button(
                                onClick = { recordedAudioDraft = viewModel.stopAudioRecording() },
                                colors = ButtonDefaults.buttonColors(containerColor = palette.error)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (strings.isEnglish) "Stop" else "إيقاف")
                            }
                        }
                    }
                    OutlinedTextField(
                        value = audioCaption,
                        onValueChange = { audioCaption = it },
                        label = { Text(if (strings.isEnglish) "Voice note caption (optional)" else "وصف الحالة الصوتية (اختياري)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
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
                }
            },
            confirmButton = {
                Button(
                    enabled = !recordingState.isRecording && recordedAudioDraft != null,
                    onClick = {
                        viewModel.stopAudioPlayback()
                        val allowed = if (selectedAudience == StoryAudienceMode.ONLY_WITH) selectedContactIds.toList() else emptyList()
                        val excluded = if (selectedAudience == StoryAudienceMode.CONTACTS_EXCEPT) selectedContactIds.toList() else emptyList()
                        recordedAudioDraft?.let { (file, dur) ->
                            viewModel.publishAudioStory(
                                audioUri = Uri.fromFile(file),
                                durationMs = dur,
                                caption = audioCaption.trim(),
                                audienceMode = selectedAudience,
                                allowedViewerIds = allowed,
                                excludedViewerIds = excluded
                            )
                        }
                        recordedAudioDraft = null
                        showAudioDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onAccent)
                ) {
                    Text(if (strings.isEnglish) "Publish" else "نشر", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.stopAudioPlayback()
                    viewModel.cancelAudioRecording()
                    recordedAudioDraft = null
                    showAudioDialog = false
                }) {
                    Text(strings.cancel, color = palette.textSecondary)
                }
            }
        )
    }

    if (showTextDialog) {
        var storyText by remember { mutableStateOf("") }
        var bgHex by remember { mutableLongStateOf(0xFF064E3B) }
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            containerColor = palette.surface,
            title = {
                Text(
                    if (strings.isEnglish) "New Text Story" else "حالة نصية جديدة",
                    color = palette.textPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = storyText,
                        onValueChange = { storyText = it },
                        label = { Text(if (strings.isEnglish) "Write your story..." else "اكتب حالتك...") },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(0xFF064E3B, 0xFF0E7490, 0xFF312E81, 0xFF831843).forEach { colorHex ->
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(colorHex))
                                    .border(
                                        width = if (bgHex == colorHex) 2.dp else 0.dp,
                                        color = Color.White,
                                        shape = CircleShape
                                    )
                                    .clickable { bgHex = colorHex }
                            )
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
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val allowed = if (selectedAudience == StoryAudienceMode.ONLY_WITH) selectedContactIds.toList() else emptyList()
                        val excluded = if (selectedAudience == StoryAudienceMode.CONTACTS_EXCEPT) selectedContactIds.toList() else emptyList()
                        viewModel.publishTextStory(storyText, bgHex, selectedAudience, allowed, excluded)
                        showTextDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onAccent)
                ) {
                    Text(if (strings.isEnglish) "Publish" else "نشر")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTextDialog = false }) {
                    Text(strings.cancel, color = palette.textSecondary)
                }
            }
        )
    }

    viewersSheetStory?.let { targetStory ->
        StoryViewersBottomSheet(
            story = targetStory,
            viewersFlow = viewModel.observeViewers(targetStory.storyId),
            onDismiss = { viewersSheetStory = null }
        )
    }
}

@Composable
fun StoryAudienceSummaryRow(
    audienceMode: StoryAudienceMode,
    selectedCount: Int,
    totalContactsCount: Int,
    onSelectMode: (StoryAudienceMode) -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    var expanded by remember { mutableStateOf(false) }

    val label = when (audienceMode) {
        StoryAudienceMode.CONTACTS -> if (strings.isEnglish) "My Contacts ($totalContactsCount)" else "جهات اتصالي ($totalContactsCount)"
        StoryAudienceMode.CONTACTS_EXCEPT -> if (strings.isEnglish) "Contacts except ($selectedCount)" else "جهات اتصالي باستثناء ($selectedCount)"
        StoryAudienceMode.ONLY_WITH -> if (strings.isEnglish) "Only share with ($selectedCount)" else "المشاركة فقط مع ($selectedCount)"
        StoryAudienceMode.ALL_CONTACTS -> if (strings.isEnglish) "All Contacts ($totalContactsCount)" else "الكل من جهات الاتصال ($totalContactsCount)"
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = if (strings.isEnglish) "Who can see my status?" else "من يستطيع مشاهدة حالتي؟",
            style = MaterialTheme.typography.labelMedium,
            color = palette.textSecondary
        )
        Box {
            Surface(
                color = palette.surfaceElevated,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, palette.turquoise.copy(alpha = 0.45f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Groups, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(18.dp))
                        Text(label, color = palette.textPrimary, fontWeight = FontWeight.Medium)
                    }
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = palette.turquoise)
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = palette.surface
            ) {
                DropdownMenuItem(
                    text = { Text("جهات اتصالي", color = palette.textPrimary) },
                    onClick = { expanded = false; onSelectMode(StoryAudienceMode.CONTACTS) }
                )
                DropdownMenuItem(
                    text = { Text("جهات اتصالي باستثناء...", color = palette.textPrimary) },
                    onClick = { expanded = false; onSelectMode(StoryAudienceMode.CONTACTS_EXCEPT) }
                )
                DropdownMenuItem(
                    text = { Text("المشاركة فقط مع...", color = palette.textPrimary) },
                    onClick = { expanded = false; onSelectMode(StoryAudienceMode.ONLY_WITH) }
                )
                DropdownMenuItem(
                    text = { Text("الكل من جهات الاتصال", color = palette.textPrimary) },
                    onClick = { expanded = false; onSelectMode(StoryAudienceMode.ALL_CONTACTS) }
                )
            }
        }
    }
}

@Composable
fun StoryContactsPickerDialog(
    mode: StoryAudienceMode,
    contacts: List<WesalUser>,
    initialSelectedIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    var selected by remember(initialSelectedIds) { mutableStateOf(initialSelectedIds) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = {
            Text(
                text = if (mode == StoryAudienceMode.CONTACTS_EXCEPT) {
                    if (strings.isEnglish) "Hide story from..." else "إخفاء الحالة عن..."
                } else {
                    if (strings.isEnglish) "Share story only with..." else "مشاركة الحالة فقط مع..."
                },
                color = palette.textPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { selected = contacts.map { it.uid }.toSet() }) {
                        Text(if (strings.isEnglish) "Select All" else "تحديد الكل", color = palette.emerald)
                    }
                    TextButton(onClick = { selected = emptySet() }) {
                        Text(if (strings.isEnglish) "Clear All" else "إلغاء الكل", color = palette.textSecondary)
                    }
                }
                if (contacts.isEmpty()) {
                    Text(
                        text = if (strings.isEnglish) "No accepted contacts yet" else "لا توجد جهات اتصال مقبولة حالياً",
                        color = palette.textMuted
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(contacts, key = { it.uid }) { user ->
                            val isChecked = user.uid in selected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = if (isChecked) selected - user.uid else selected + user.uid
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + user.uid else selected - user.uid
                                    }
                                )
                                WesalAvatar(imageUrl = user.photoUrl, name = user.displayName, size = 38.dp)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(user.displayName.ifBlank { "@${user.publicId}" }, color = palette.textPrimary)
                                    if (user.numericId.isNotBlank()) {
                                        Text("ID: ${user.numericId}", style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selected) },
                colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary)
            ) {
                Text(if (strings.isEnglish) "Confirm (${selected.size})" else "تأكيد (${selected.size})")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoryViewersBottomSheet(
    story: StoryItem,
    viewersFlow: Flow<List<StoryViewer>>,
    onDismiss: () -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val viewers by viewersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, tint = palette.turquoise)
                    Text(
                        text = if (strings.isEnglish) {
                            "Views (${maxOf(viewers.size, story.viewerIds.size)})"
                        } else {
                            "المشاهدات (${maxOf(viewers.size, story.viewerIds.size)})"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = strings.close, tint = palette.textSecondary)
                }
            }
            HorizontalDivider(color = palette.border)
            if (viewers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (strings.isEnglish) "No views yet" else "لا توجد مشاهدات حتى الآن",
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.textSecondary
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(viewers, key = { it.uid }) { viewer ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            WesalAvatar(imageUrl = viewer.photoUrl, name = viewer.displayName, size = 44.dp)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = viewer.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = palette.textPrimary
                                )
                                Text(
                                    text = if (viewer.viewedAt > 0L) {
                                        if (strings.isEnglish) {
                                            "Viewed ${DateTimeUtils.formatChatListTime(viewer.viewedAt)}"
                                        } else {
                                            "شاهدها ${DateTimeUtils.formatChatListTime(viewer.viewedAt)}"
                                        }
                                    } else {
                                        if (strings.isEnglish) "Viewed story" else "شاهد الحالة"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.textSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StoryRowCard(
    story: StoryItem,
    isMine: Boolean,
    onClick: () -> Unit,
    onShowViewers: (() -> Unit)? = null
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .border(2.5.dp, palette.emerald, CircleShape)
                .padding(3.dp)
        ) {
            WesalAvatar(imageUrl = story.ownerAvatarUrl, name = story.ownerName, size = 52.dp)
        }
        Column(modifier = Modifier.weight(1f)) {
            WesalNameWithBadge(
                name = if (isMine) (if (strings.isEnglish) "My Story" else "حالتي") else story.ownerName,
                isVerified = !isMine && story.ownerVerified,
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary
            )
            val typeTag = when (story.type) {
                StoryType.AUDIO -> if (strings.isEnglish) "Voice • " else "صوتية • "
                StoryType.VIDEO -> if (strings.isEnglish) "Video • " else "فيديو • "
                StoryType.IMAGE -> if (strings.isEnglish) "Photo • " else "صورة • "
                StoryType.TEXT -> if (strings.isEnglish) "Text • " else "نصية • "
            }
            val viewsSuffix = if (strings.isEnglish) "views" else "مشاهدة"
            val subtitle = if (isMine) {
                "$typeTag${DateTimeUtils.formatChatListTime(story.createdAt)} • ${story.viewerIds.size} $viewsSuffix"
            } else {
                "$typeTag${DateTimeUtils.formatChatListTime(story.createdAt)}"
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary
            )
        }
        if (isMine && onShowViewers != null) {
            Surface(
                color = palette.surfaceElevated,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.clickable(onClick = onShowViewers)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        Icons.Default.Visibility,
                        contentDescription = null,
                        tint = palette.turquoise,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "${story.viewerIds.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
