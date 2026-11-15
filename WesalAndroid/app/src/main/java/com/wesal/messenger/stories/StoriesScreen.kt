package com.wesal.messenger.stories

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.wesal.messenger.core.theme.*
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.crop.CropActivity
import com.wesal.messenger.domain.model.PrivacyVisibility
import com.wesal.messenger.domain.model.StoryItem
import com.wesal.messenger.domain.model.StoryType
import com.wesal.messenger.domain.model.StoryViewer
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.StoryPublishEvent
import com.wesal.messenger.domain.repository.StoryRepository
import com.wesal.messenger.media.CameraCaptureScreen
import com.wesal.messenger.media.VideoPlayerBox
import com.wesal.messenger.presentation.components.WesalAvatar
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
    val visibility: PrivacyVisibility
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
    private val voiceRecorder: VoiceRecorderManager,
    private val voicePlayer: VoicePlayerManager,
    authRepository: AuthRepository
) : ViewModel() {

    val myUid: StateFlow<String> = authRepository.observeAuthState()
        .map { it.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, authRepository.currentUserId.orEmpty())

    val stories: StateFlow<List<StoryItem>> = storyRepository.observeActiveStories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recordingState: StateFlow<VoiceRecordingState> = voiceRecorder.state
    val playbackState: StateFlow<VoicePlaybackState> = voicePlayer.playbackState

    private val _uploadState = MutableStateFlow(StoriesUploadUiState())
    val uploadState: StateFlow<StoriesUploadUiState> = _uploadState.asStateFlow()

    private val cachedAudioFiles = mutableMapOf<String, File>()

    fun publishTextStory(text: String, bgHex: Long, visibility: PrivacyVisibility) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _uploadState.value = StoriesUploadUiState(isPublishing = true, uploadProgress = 0.5f)
            storyRepository.publishTextStory(text, bgHex, visibility)
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

    fun publishMediaStory(uri: Uri, isVideo: Boolean, caption: String, visibility: PrivacyVisibility) {
        val request = PendingStoryMedia(uri, isVideo, caption, visibility)
        viewModelScope.launch {
            _uploadState.value = StoriesUploadUiState(isPublishing = true, uploadProgress = 0.02f)
            storyRepository.publishMediaStory(uri, isVideo, caption, visibility).collect { event ->
                when (event) {
                    is StoryPublishEvent.Progress -> {
                        _uploadState.value = StoriesUploadUiState(
                            isPublishing = true,
                            uploadProgress = event.fraction
                        )
                    }
                    is StoryPublishEvent.Success -> {
                        _uploadState.value = StoriesUploadUiState(
                            statusBanner = "تم نشر الحالة بنجاح"
                        )
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

    fun publishAudioStory(audioUri: Uri, durationMs: Long, caption: String, visibility: PrivacyVisibility) {
        viewModelScope.launch {
            _uploadState.value = StoriesUploadUiState(isPublishing = true, uploadProgress = 0.08f)
            storyRepository.publishAudioStory(audioUri, durationMs, caption, visibility).collect { event ->
                when (event) {
                    is StoryPublishEvent.Progress -> {
                        _uploadState.value = StoriesUploadUiState(
                            isPublishing = true,
                            uploadProgress = event.fraction
                        )
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
        publishMediaStory(req.uri, req.isVideo, req.caption, req.visibility)
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
    val context = LocalContext.current
    val myUid by viewModel.myUid.collectAsStateWithLifecycle()
    val stories by viewModel.stories.collectAsStateWithLifecycle()
    val uploadState by viewModel.uploadState.collectAsStateWithLifecycle()
    val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()

    var showTextDialog by remember { mutableStateOf(false) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var showInlineCamera by remember { mutableStateOf(false) }
    var activeStory by remember { mutableStateOf<StoryItem?>(null) }
    var viewersSheetStory by remember { mutableStateOf<StoryItem?>(null) }
    var selectedVisibility by remember { mutableStateOf(PrivacyVisibility.EVERYONE) }
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

    // Story preview screen before uploading (supports crop for photos)
    draftMedia?.let { (uri, isVideo) ->
        var previewVisibility by remember { mutableStateOf(selectedVisibility) }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!isVideo) {
                        OutlinedButton(
                            onClick = {
                                val cropIntent = Intent(context, CropActivity::class.java).setData(uri)
                                cropLauncher.launch(cropIntent)
                            },
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.Crop, contentDescription = null, tint = WesalTurquoise, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("قص الصورة", color = WesalTurquoise)
                        }
                    }
                    TextButton(onClick = {
                        previewVisibility = when (previewVisibility) {
                            PrivacyVisibility.EVERYONE -> PrivacyVisibility.CONTACTS
                            PrivacyVisibility.CONTACTS -> PrivacyVisibility.NOBODY
                            PrivacyVisibility.NOBODY -> PrivacyVisibility.EVERYONE
                        }
                    }) {
                        val visLabel = when (previewVisibility) {
                            PrivacyVisibility.EVERYONE -> "الجميع"
                            PrivacyVisibility.CONTACTS -> "جهات الاتصال"
                            PrivacyVisibility.NOBODY -> "أنا فقط"
                        }
                        Text("الخصوصية: $visLabel", color = WesalTurquoise, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Surface(
                color = WesalSurfaceDark.copy(alpha = 0.92f),
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
                        value = draftCaption,
                        onValueChange = { draftCaption = it },
                        placeholder = { Text("أضف نصاً أو وصفاً للحالة...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(24.dp)
                    )
                    Button(
                        onClick = {
                            selectedVisibility = previewVisibility
                            viewModel.publishMediaStory(uri, isVideo, draftCaption.trim(), previewVisibility)
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
        return
    }

    // Active story fullscreen viewer (reflect live updates to viewerIds)
    val currentActiveStory = activeStory?.let { cur -> stories.firstOrNull { it.storyId == cur.storyId } ?: cur }
    currentActiveStory?.let { story ->
        LaunchedEffect(story.storyId) { viewModel.markViewed(story) }
        DisposableEffect(story.storyId) {
            onDispose { viewModel.stopAudioPlayback() }
        }
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
                        model = com.wesal.messenger.presentation.components.wesalImageModel(story.mediaUrl),
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
                                if (story.textContent.isNotBlank()) {
                                    Text(
                                        text = story.textContent,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = WesalTurquoise,
                                        textAlign = TextAlign.Center
                                    )
                                }
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
                        Text(story.ownerName, color = Color.White, fontWeight = FontWeight.Bold)
                        Text(
                            DateTimeUtils.formatChatListTime(story.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
                Row {
                    if (story.ownerUid == myUid) {
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
                if (story.ownerUid == myUid) {
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
        viewersSheetStory?.let { targetStory ->
            StoryViewersBottomSheet(
                story = targetStory,
                viewersFlow = viewModel.observeViewers(targetStory.storyId),
                onDismiss = { viewersSheetStory = null }
            )
        }
        return
    }

    val myStories = stories.filter { it.ownerUid == myUid }
    val friendsStories = stories.filter { it.ownerUid != myUid }

    Box(modifier = Modifier.fillMaxSize().background(WesalDeepBlack)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (uploadState.isPublishing) {
                item {
                    Surface(color = WesalSurfaceDark, shape = RoundedCornerShape(12.dp)) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "جاري رفع ونشر الحالة (${(uploadState.uploadProgress * 100).toInt()}%)",
                                color = WesalTurquoise,
                                fontWeight = FontWeight.Medium
                            )
                            LinearProgressIndicator(
                                progress = { uploadState.uploadProgress },
                                modifier = Modifier.fillMaxWidth(),
                                color = WesalTurquoise
                            )
                        }
                    }
                }
            }

            uploadState.errorMessage?.let { errMsg ->
                item {
                    Surface(
                        color = WesalError.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(errMsg, color = WesalError, style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (uploadState.failedRequest != null) {
                                    Button(
                                        onClick = { viewModel.retryFailedUpload() },
                                        colors = ButtonDefaults.buttonColors(containerColor = WesalEmerald, contentColor = WesalDeepBlack)
                                    ) {
                                        Text("إعادة المحاولة", fontWeight = FontWeight.Bold)
                                    }
                                }
                                TextButton(onClick = { viewModel.clearError() }) {
                                    Text("إغلاق", color = WesalTextSecondary)
                                }
                            }
                        }
                    }
                }
            }

            uploadState.statusBanner?.let { msg ->
                item {
                    Surface(
                        color = WesalEmerald.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(msg, color = WesalEmerald)
                            IconButton(onClick = { viewModel.clearBanner() }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = WesalEmerald, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("حالتي (تنتهي بعد 24 ساعة)", style = MaterialTheme.typography.titleMedium, color = WesalTurquoise)
                    TextButton(onClick = {
                        selectedVisibility = when (selectedVisibility) {
                            PrivacyVisibility.EVERYONE -> PrivacyVisibility.CONTACTS
                            PrivacyVisibility.CONTACTS -> PrivacyVisibility.NOBODY
                            PrivacyVisibility.NOBODY -> PrivacyVisibility.EVERYONE
                        }
                    }) {
                        val visLabel = when (selectedVisibility) {
                            PrivacyVisibility.EVERYONE -> "الجميع"
                            PrivacyVisibility.CONTACTS -> "جهات الاتصال"
                            PrivacyVisibility.NOBODY -> "أنا فقط"
                        }
                        Text("الخصوصية: $visLabel", color = WesalEmerald)
                    }
                }
            }

            item {
                Surface(
                    color = WesalSurfaceDark,
                    shape = RoundedCornerShape(14.dp),
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
                                    .background(WesalEmerald.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = WesalEmerald)
                            }
                            Column {
                                Text("إضافة حالة جديدة", color = WesalTextPrimary, fontWeight = FontWeight.Bold)
                                Text("صورة، فيديو، كاميرا، حالة صوتية، أو نصية", style = MaterialTheme.typography.labelMedium, color = WesalTextSecondary)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showInlineCamera = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, tint = WesalEmerald, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("كاميرا", color = WesalEmerald)
                            }
                            OutlinedButton(
                                onClick = { showAudioDialog = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = null, tint = WesalTurquoise, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("صوتية", color = WesalTurquoise)
                            }
                            OutlinedButton(
                                onClick = { showTextDialog = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, tint = WesalTextPrimary, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("نصية", color = WesalTextPrimary)
                            }
                        }
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
                Text("تحديثات الأصدقاء", style = MaterialTheme.typography.titleMedium, color = WesalTextSecondary)
            }

            if (friendsStories.isEmpty()) {
                item {
                    Text("لا توجد حالات نشطة من الأصدقاء حالياً", color = WesalTextMuted)
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
                containerColor = WesalSurfaceElevated,
                contentColor = WesalEmerald
            ) {
                Icon(Icons.Default.Mic, contentDescription = "حالة صوتية")
            }
            SmallFloatingActionButton(
                onClick = { showTextDialog = true },
                containerColor = WesalSurfaceElevated,
                contentColor = WesalTurquoise
            ) {
                Icon(Icons.Default.Edit, contentDescription = "حالة نصية")
            }
            FloatingActionButton(
                onClick = { showInlineCamera = true },
                containerColor = WesalEmerald,
                contentColor = WesalDeepBlack
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = "التقاط حالة بالكاميرا")
            }
        }
    }

    if (showAudioDialog) {
        var audioCaption by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {
                viewModel.cancelAudioRecording()
                recordedAudioDraft = null
                showAudioDialog = false
            },
            containerColor = WesalSurfaceDark,
            title = { Text("تسجيل حالة صوتية", color = WesalTextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val recFile = recordedAudioDraft
                    Text(
                        text = when {
                            recordingState.isRecording -> "جاري التسجيل: ${DateTimeUtils.formatDurationMillis(recordingState.elapsedMs)}"
                            recFile != null -> "تم تسجيل المقطع (${DateTimeUtils.formatDurationMillis(recFile.second)})"
                            else -> "اضغط بدء لتسجيل رسالة صوتية لحالتك"
                        },
                        color = if (recordingState.isRecording) WesalError else WesalTurquoise,
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!recordingState.isRecording) {
                            Button(
                                onClick = {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) {
                                        recordedAudioDraft = null
                                        viewModel.startAudioRecording()
                                    } else {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = WesalEmerald,
                                    contentColor = WesalDeepBlack
                                )
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (recFile == null) "بدء التسجيل" else "إعادة التسجيل", fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = {
                                    recordedAudioDraft = viewModel.stopAudioRecording()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = WesalError)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("إيقاف")
                            }
                        }
                    }
                    OutlinedTextField(
                        value = audioCaption,
                        onValueChange = { audioCaption = it },
                        label = { Text("وصف الحالة الصوتية (اختياري)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !recordingState.isRecording && recordedAudioDraft != null,
                    onClick = {
                        recordedAudioDraft?.let { (file, dur) ->
                            viewModel.publishAudioStory(
                                audioUri = Uri.fromFile(file),
                                durationMs = dur,
                                caption = audioCaption.trim(),
                                visibility = selectedVisibility
                            )
                        }
                        recordedAudioDraft = null
                        showAudioDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WesalEmerald, contentColor = WesalDeepBlack)
                ) {
                    Text("نشر", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.cancelAudioRecording()
                    recordedAudioDraft = null
                    showAudioDialog = false
                }) {
                    Text("إلغاء", color = WesalTextSecondary)
                }
            }
        )
    }

    if (showTextDialog) {
        var storyText by remember { mutableStateOf("") }
        var bgHex by remember { mutableLongStateOf(0xFF064E3B) }
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            containerColor = WesalSurfaceDark,
            title = { Text("حالة نصية جديدة", color = WesalTextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = storyText,
                        onValueChange = { storyText = it },
                        label = { Text("اكتب حالتك...") },
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
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.publishTextStory(storyText, bgHex, selectedVisibility)
                        showTextDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WesalEmerald, contentColor = WesalDeepBlack)
                ) {
                    Text("نشر")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTextDialog = false }) { Text("إلغاء") }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoryViewersBottomSheet(
    story: StoryItem,
    viewersFlow: Flow<List<StoryViewer>>,
    onDismiss: () -> Unit
) {
    val viewers by viewersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = WesalSurfaceDark
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
                    Icon(Icons.Default.Visibility, contentDescription = null, tint = WesalTurquoise)
                    Text(
                        text = "المشاهدات (${maxOf(viewers.size, story.viewerIds.size)})",
                        style = MaterialTheme.typography.titleLarge,
                        color = WesalTextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = WesalTextSecondary)
                }
            }
            HorizontalDivider(color = WesalSurfaceBorder)
            if (viewers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "لا توجد مشاهدات حتى الآن",
                        style = MaterialTheme.typography.bodyLarge,
                        color = WesalTextSecondary
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
                                    color = WesalTextPrimary
                                )
                                Text(
                                    text = if (viewer.viewedAt > 0L) {
                                        "شاهدها ${DateTimeUtils.formatChatListTime(viewer.viewedAt)}"
                                    } else {
                                        "شاهد الحالة"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = WesalTextSecondary
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
                .border(2.5.dp, WesalEmerald, CircleShape)
                .padding(3.dp)
        ) {
            WesalAvatar(imageUrl = story.ownerAvatarUrl, name = story.ownerName, size = 52.dp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isMine) "حالتي" else story.ownerName,
                style = MaterialTheme.typography.titleMedium,
                color = WesalTextPrimary
            )
            val typeTag = when (story.type) {
                StoryType.AUDIO -> "صوتية • "
                StoryType.VIDEO -> "فيديو • "
                StoryType.IMAGE -> "صورة • "
                StoryType.TEXT -> "نصية • "
            }
            Text(
                text = "$typeTag${DateTimeUtils.formatChatListTime(story.createdAt)} • ${story.viewerIds.size} مشاهدة",
                style = MaterialTheme.typography.labelSmall,
                color = WesalTextSecondary
            )
        }
        if (isMine && onShowViewers != null) {
            Surface(
                color = WesalSurfaceElevated,
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
                        contentDescription = "عرض المشاهدين",
                        tint = WesalTurquoise,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "${story.viewerIds.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = WesalTextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
