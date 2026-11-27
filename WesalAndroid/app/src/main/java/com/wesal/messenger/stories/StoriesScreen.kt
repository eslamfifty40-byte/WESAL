package com.wesal.messenger.stories

import android.content.Context
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Base64
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.wesal.messenger.channels.ChannelsViewModel
import com.wesal.messenger.channels.CreateChannelDialog
import com.wesal.messenger.channels.JoinChannelCodeDialog
import com.wesal.messenger.channels.channelsDirectorySection
import com.wesal.messenger.core.theme.*
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.core.util.WesalShareUtils
import com.wesal.messenger.crop.CropActivity
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.domain.model.PrivacyVisibility
import com.wesal.messenger.domain.model.StoryAudienceMode
import com.wesal.messenger.domain.model.StoryItem
import com.wesal.messenger.domain.model.StoryType
import com.wesal.messenger.domain.model.StoryViewer
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.ChatRepository
import com.wesal.messenger.domain.repository.StoryPublishEvent
import com.wesal.messenger.domain.repository.StoryRepository
import com.wesal.messenger.domain.repository.UserRepository
import com.wesal.messenger.media.CameraCaptureScreen
import com.wesal.messenger.media.VideoPlayerBox
import com.wesal.messenger.presentation.components.WesalAvatar
import com.wesal.messenger.presentation.components.WesalMediaKind
import com.wesal.messenger.presentation.components.WesalNameWithBadge
import com.wesal.messenger.presentation.components.rememberSavePermissionGate
import com.wesal.messenger.presentation.components.saveMediaToGallery
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
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URL
import javax.inject.Inject

private val STORY_BG_COLORS = listOf(
    0xFF064E3BL,
    0xFF0E7490L,
    0xFF312E81L,
    0xFF831843L,
    0xFF1E293BL,
    0xFF7C2D12L,
    0xFF14532DL,
    0xFF4C1D95L
)

data class StarredMessageEntry(
    val rawKey: String,
    val chatId: String,
    val messageId: String,
    val summary: String
)

data class PendingStoryMedia(
    val uri: Uri,
    val isVideo: Boolean,
    val caption: String,
    val visibility: PrivacyVisibility,
    val audienceMode: StoryAudienceMode,
    val allowedViewerIds: List<String>,
    val excludedViewerIds: List<String>,
    val backgroundColorHex: Long = 0xFF064E3BL
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
    private val chatRepository: ChatRepository,
    private val voiceRecorder: VoiceRecorderManager,
    private val voicePlayer: VoicePlayerManager,
    private val prefs: UserPreferencesDataStore,
    authRepository: AuthRepository
) : ViewModel() {

    val myUidFlow: StateFlow<String> = authRepository.observeAuthState()
        .map { it.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, authRepository.currentUserId.orEmpty())

    val myUid: String
        get() = myUidFlow.value

    val stories: StateFlow<List<StoryItem>> = storyRepository.observeActiveStories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedAudienceMode: StateFlow<StoryAudienceMode> = prefs.settingsFlow
        .map { StoryAudienceMode.fromRaw(it.lastStoryAudienceMode) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, StoryAudienceMode.CONTACTS)

    val cameraEffectsEnabled: StateFlow<Boolean> = prefs.settingsFlow
        .map { it.cameraEffectsEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val archivedStoryIds: StateFlow<Set<String>> = prefs.settingsFlow
        .map { it.archivedStoryIds }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val archiveKeepMutedChats: StateFlow<Boolean> = prefs.settingsFlow
        .map { it.archiveKeepMutedChats }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val starredMessages: StateFlow<List<StarredMessageEntry>> = prefs.settingsFlow
        .map { settings ->
            val rawList = runCatching {
                Json.decodeFromString<List<String>>(settings.starredMessagesJson)
            }.getOrDefault(emptyList())
            rawList.map { raw ->
                val parts = raw.split("|", limit = 3)
                StarredMessageEntry(
                    rawKey = raw,
                    chatId = parts.getOrElse(0) { "" },
                    messageId = parts.getOrElse(1) { "" },
                    summary = parts.getOrElse(2) { raw }
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val acceptedContacts: StateFlow<List<WesalUser>> = combine(
        myUidFlow,
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

    fun toggleCameraEffects(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setCameraEffectsEnabled(enabled)
            _uploadState.update {
                it.copy(statusBanner = if (enabled) "تم تفعيل تأثيرات الكاميرا" else "تم إيقاف تأثيرات الكاميرا")
            }
        }
    }

    fun toggleArchiveStory(storyId: String, archived: Boolean) {
        viewModelScope.launch {
            prefs.toggleArchivedStory(storyId, archived)
            _uploadState.update {
                it.copy(statusBanner = if (archived) "تم نقل الحالة إلى الأرشيف" else "تمت استعادة الحالة من الأرشيف")
            }
        }
    }

    fun setArchiveKeepMuted(keep: Boolean) {
        viewModelScope.launch { prefs.setArchiveKeepMutedChats(keep) }
    }

    fun removeStarredEntry(rawKey: String) {
        viewModelScope.launch { prefs.toggleStarredMessage(rawKey) }
    }

    fun promoteStoryToContacts(story: StoryItem, recipientUids: List<String>, note: String) {
        if (recipientUids.isEmpty()) return
        viewModelScope.launch {
            val contentPreview = when (story.type) {
                StoryType.TEXT -> story.textContent
                StoryType.AUDIO -> story.caption.ifBlank { "حالة صوتية" }
                StoryType.VIDEO -> story.caption.ifBlank { "حالة فيديو" }
                StoryType.IMAGE -> story.caption.ifBlank { "حالة مصورة" }
            }
            val promoText = buildString {
                append("📣 ترويج حالة على WESAL")
                if (note.isNotBlank()) {
                    append("\n")
                    append(note.trim())
                }
                if (contentPreview.isNotBlank()) {
                    append("\n«")
                    append(contentPreview.take(280))
                    append("»")
                }
            }
            var sentCount = 0
            recipientUids.forEach { uid ->
                val chatId = chatRepository.getOrCreateDirectChat(uid).getOrNull()
                if (!chatId.isNullOrBlank()) {
                    chatRepository.sendTextMessage(chatId, promoText)
                        .onSuccess { sentCount++ }
                }
            }
            _uploadState.update {
                if (sentCount > 0) {
                    it.copy(statusBanner = "تم ترويج الحالة وإرسالها إلى $sentCount من جهات الاتصال")
                } else {
                    it.copy(errorMessage = "تعذر إرسال الترويج لجهات الاتصال المحددة")
                }
            }
        }
    }

    /**
     * Reply to someone else's story: lands as a real text message in the DM
     * with the story owner (created on demand), prefixed with a short quote of
     * the story so the owner knows what it answers. Mirrors the
     * promoteStoryToContacts send path (getOrCreateDirectChat + sendTextMessage).
     */
    fun replyToStory(story: StoryItem, text: String, onDone: (Boolean) -> Unit) {
        val clean = text.trim()
        if (clean.isBlank() || story.ownerUid.isBlank() || story.ownerUid == myUid) {
            onDone(false)
            return
        }
        viewModelScope.launch {
            val preview = when (story.type) {
                StoryType.TEXT -> story.textContent
                StoryType.AUDIO -> story.caption.ifBlank { "حالة صوتية" }
                StoryType.VIDEO -> story.caption.ifBlank { "حالة فيديو" }
                StoryType.IMAGE -> story.caption.ifBlank { "حالة مصورة" }
            }
            val message = buildString {
                append("💬 رد على حالتك")
                if (preview.isNotBlank()) {
                    append(" «")
                    append(preview.take(120))
                    append("»")
                }
                append("\n")
                append(clean)
            }
            val chatId = chatRepository.getOrCreateDirectChat(story.ownerUid).getOrNull()
            val sent = !chatId.isNullOrBlank() &&
                chatRepository.sendTextMessage(chatId, message).isSuccess
            onDone(sent)
        }
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
        excludedViewerIds: List<String>,
        backgroundColorHex: Long = 0xFF064E3BL
    ) {
        val visibility = if (audienceMode == StoryAudienceMode.ALL_CONTACTS) PrivacyVisibility.EVERYONE else PrivacyVisibility.CONTACTS
        val request = PendingStoryMedia(uri, isVideo, caption, visibility, audienceMode, allowedViewerIds, excludedViewerIds, backgroundColorHex)
        viewModelScope.launch {
            _uploadState.value = StoriesUploadUiState(isPublishing = true, uploadProgress = 0.02f)
            storyRepository.publishMediaStory(
                uri = uri,
                isVideo = isVideo,
                caption = caption,
                visibility = visibility,
                audienceMode = audienceMode,
                allowedViewerIds = allowedViewerIds,
                excludedViewerIds = excludedViewerIds,
                backgroundColorHex = backgroundColorHex
            ).catch { err ->
                _uploadState.value = StoriesUploadUiState(
                    errorMessage = err.localizedMessage ?: "تعذر نشر الحالة",
                    failedRequest = request
                )
            }.collect { event ->
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
        excludedViewerIds: List<String>,
        backgroundColorHex: Long = 0xFF064E3BL
    ) {
        val visibility = if (audienceMode == StoryAudienceMode.ALL_CONTACTS) PrivacyVisibility.EVERYONE else PrivacyVisibility.CONTACTS
        viewModelScope.launch {
            _uploadState.value = StoriesUploadUiState(isPublishing = true, uploadProgress = 0.08f)
            storyRepository.publishAudioStory(
                audioUri = audioUri,
                durationMs = durationMs,
                caption = caption,
                visibility = visibility,
                audienceMode = audienceMode,
                allowedViewerIds = allowedViewerIds,
                excludedViewerIds = excludedViewerIds,
                backgroundColorHex = backgroundColorHex
            ).catch { err ->
                _uploadState.value = StoriesUploadUiState(
                    errorMessage = err.localizedMessage ?: "تعذر نشر الحالة الصوتية"
                )
            }.collect { event ->
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
        // A fresh attempt clears the previous playback error so a stale message
        // never sits inside the viewer.
        _uploadState.update { it.copy(errorMessage = null) }
        val existing = cachedAudioFiles[storyId]
        if (existing != null && existing.exists() && existing.length() > 0L) {
            voicePlayer.togglePlay(storyId, existing)
            return
        }
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val out = File(context.cacheDir, "story_audio_$storyId.m4a")
                    if (mediaUrl.startsWith("data:", ignoreCase = true)) {
                        // Same decode the chat voice-note path uses
                        // (ChatRepositoryImpl.decryptMediaToCache): ANY data: URL,
                        // whatever MIME it declares. Stories published while
                        // Storage was unreachable carry the upload fallback's
                        // data:application/octet-stream payloads — the old
                        // data:audio-only check rejected those outright, no bytes
                        // were written, and the tap did nothing at all.
                        val raw = Base64.decode(mediaUrl.substringAfter("base64,"), Base64.NO_WRAP)
                        out.writeBytes(raw)
                    } else if (mediaUrl.startsWith("wesal-plain://", ignoreCase = true)) {
                        val bytes = Base64.decode(mediaUrl.removePrefix("wesal-plain://"), Base64.DEFAULT)
                        out.writeBytes(bytes)
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
        publishMediaStory(
            req.uri,
            req.isVideo,
            req.caption,
            req.audienceMode,
            req.allowedViewerIds,
            req.excludedViewerIds,
            req.backgroundColorHex
        )
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
        val uid = myUidFlow.value
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
fun StoriesScreen(
    onOpenChannel: (String) -> Unit = {},
    onOpenChat: (String) -> Unit = {},
    viewModel: StoriesViewModel = hiltViewModel(),
    channelsViewModel: ChannelsViewModel = hiltViewModel()
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val context = LocalContext.current
    val storySaveScope = rememberCoroutineScope()
    val savePermissionGate = rememberSavePermissionGate()
    val activity = context as? Activity
    val myUid by viewModel.myUidFlow.collectAsStateWithLifecycle()
    val stories by viewModel.stories.collectAsStateWithLifecycle()
    val uploadState by viewModel.uploadState.collectAsStateWithLifecycle()
    val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val savedAudience by viewModel.savedAudienceMode.collectAsStateWithLifecycle()
    val contacts by viewModel.acceptedContacts.collectAsStateWithLifecycle()
    val cameraEffectsEnabled by viewModel.cameraEffectsEnabled.collectAsStateWithLifecycle()
    val archivedStoryIds by viewModel.archivedStoryIds.collectAsStateWithLifecycle()
    val archiveKeepMuted by viewModel.archiveKeepMutedChats.collectAsStateWithLifecycle()
    val starredMessages by viewModel.starredMessages.collectAsStateWithLifecycle()

    val myChannels by channelsViewModel.myChannels.collectAsStateWithLifecycle()
    val publicChannels by channelsViewModel.publicChannels.collectAsStateWithLifecycle()
    val channelSearchQuery by channelsViewModel.searchQuery.collectAsStateWithLifecycle()
    val channelMessage by channelsViewModel.message.collectAsStateWithLifecycle()

    var showTextDialog by remember { mutableStateOf(false) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var showInlineCamera by remember { mutableStateOf(false) }
    var activeStory by remember { mutableStateOf<StoryItem?>(null) }
    var viewersSheetStory by remember { mutableStateOf<StoryItem?>(null) }
    var promoteTargetStory by remember { mutableStateOf<StoryItem?>(null) }
    var showUpdatesMenu by remember { mutableStateOf(false) }
    var showArchivedDialog by remember { mutableStateOf(false) }
    var showArchiveSettingsDialog by remember { mutableStateOf(false) }
    var showStarredDialog by remember { mutableStateOf(false) }
    var showStoryPrivacyDialog by remember { mutableStateOf(false) }
    var showCreateChannelDialog by remember { mutableStateOf(false) }
    var showJoinChannelDialog by remember { mutableStateOf(false) }

    var selectedAudience by remember(savedAudience) { mutableStateOf(savedAudience) }
    var selectedContactIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showContactsPickerForMode by remember { mutableStateOf<StoryAudienceMode?>(null) }

    var draftMedia by remember { mutableStateOf<Pair<Uri, Boolean>?>(null) }
    var draftCaption by remember { mutableStateOf("") }
    var draftBgHex by remember { mutableLongStateOf(0xFF064E3BL) }
    var recordedAudioDraft by remember { mutableStateOf<Pair<File, Long>?>(null) }

    val filteredMyChannels = remember(myChannels, channelSearchQuery) {
        if (channelSearchQuery.isBlank()) myChannels
        else myChannels.filter {
            it.title.contains(channelSearchQuery, true) || it.description.contains(channelSearchQuery, true)
        }
    }
    val myChannelIds = remember(myChannels) { myChannels.map { it.channelId }.toSet() }
    val filteredPublicChannels = remember(publicChannels, channelSearchQuery, myChannelIds) {
        publicChannels
            .filter { it.isPublic && !it.isSuspended }
            .filterNot { it.channelId in myChannelIds }
            .filter {
                channelSearchQuery.isBlank() ||
                    it.title.contains(channelSearchQuery, true) ||
                    it.description.contains(channelSearchQuery, true)
            }
    }

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

    // Story preview screen before uploading (supports crop, background gradient, caption & audience selection)
    draftMedia?.let { (uri, isVideo) ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(draftBgHex), Color.Black)
                    )
                )
                .systemBarsPadding()
        ) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = cameraEffectsEnabled,
                        onClick = { viewModel.toggleCameraEffects(!cameraEffectsEnabled) },
                        label = {
                            Text(
                                text = if (strings.isEnglish) "Effects" else "تأثيرات",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.AutoFixHigh,
                                contentDescription = null,
                                tint = if (cameraEffectsEnabled) WesalTurquoise else Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
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
                    if (!isVideo) {
                        StoryBackgroundColorRow(
                            selectedHex = draftBgHex,
                            onSelect = { draftBgHex = it }
                        )
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
                                viewModel.publishMediaStory(
                                    uri = uri,
                                    isVideo = isVideo,
                                    caption = draftCaption.trim(),
                                    audienceMode = selectedAudience,
                                    allowedViewerIds = allowed,
                                    excludedViewerIds = excluded,
                                    backgroundColorHex = draftBgHex
                                )
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
        val isArchived = story.storyId in archivedStoryIds
        var storyReplyText by remember(story.storyId) { mutableStateOf("") }
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
        val resolvedBgHex = if (story.backgroundColorHex == 0L) 0xFF064E3BL else story.backgroundColorHex

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    when (story.type) {
                        StoryType.TEXT -> Brush.verticalGradient(listOf(Color(resolvedBgHex), Color(resolvedBgHex).copy(alpha = 0.78f)))
                        StoryType.IMAGE -> Brush.verticalGradient(listOf(Color(resolvedBgHex), Color.Black))
                        StoryType.AUDIO -> Brush.verticalGradient(listOf(WesalDeepBlack, Color(resolvedBgHex).copy(alpha = 0.75f), WesalDeepBlack))
                        StoryType.VIDEO -> Brush.verticalGradient(listOf(Color.Black, Color.Black))
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
                                    listOf(WesalDeepBlack, Color(resolvedBgHex).copy(alpha = 0.55f), WesalDeepBlack)
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
                                // Playback failures surface HERE, inside the viewer: the
                                // decode error lives in uploadState.errorMessage, which
                                // otherwise renders only in the stories list BEHIND this
                                // fullscreen viewer, and player failures (prepare()
                                // throwing) live in playbackState.error — previously the
                                // tap simply did nothing and the user saw neither.
                                val playbackError = playbackState.error ?: uploadState.errorMessage
                                if (playbackError != null) {
                                    Text(
                                        text = playbackError,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = WesalError,
                                        textAlign = TextAlign.Center
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
                    WesalAvatar(
                        imageUrl = story.ownerAvatarUrl,
                        name = story.ownerName,
                        size = 40.dp,
                        iconUrl = com.wesal.messenger.core.util.WesalFrameLibrary.resolveIconUrl(story.ownerIconId, story.ownerIconUrl),
                        neonColor = if (story.ownerNeonEnabled) story.ownerNeonColor else "",
                        neonGlowIntensity = story.ownerNeonGlowIntensity,
                        fitInsideBounds = true
                    )
                    Column {
                        WesalNameWithBadge(
                            name = story.ownerName,
                            isVerified = story.ownerVerified,
                            verificationType = story.ownerVerificationType,
                            neonColor = story.ownerNeonColor,
                            neonGlowIntensity = story.ownerNeonGlowIntensity,
                            neonEnabled = story.ownerNeonEnabled,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            iconUrl = com.wesal.messenger.core.util.WesalFrameLibrary.resolveIconUrl(story.ownerIconId, story.ownerIconUrl)
                        )
                        Text(
                            DateTimeUtils.formatChatListTime(story.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
                Row {
                    // Only the owner can promote, archive, or delete; viewers NEVER see download/save/share/forward
                    if (isOwner) {
                        // Round-4: the owner can save their OWN story media to the
                        // gallery (viewers still never get a save action).
                        if (story.type != StoryType.TEXT && story.mediaUrl.isNotBlank()) {
                            IconButton(onClick = {
                                savePermissionGate {
                                    storySaveScope.launch {
                                        val kind = when (story.type) {
                                            StoryType.VIDEO -> WesalMediaKind.VIDEO
                                            StoryType.AUDIO -> WesalMediaKind.AUDIO
                                            else -> WesalMediaKind.IMAGE
                                        }
                                        saveMediaToGallery(context, story.mediaUrl, kind)
                                            .onSuccess {
                                                Toast.makeText(
                                                    context,
                                                    if (strings.isEnglish) "Saved to gallery ✓" else "تم الحفظ في المعرض ✓",
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
                            }) {
                                Icon(Icons.Default.Download, contentDescription = if (strings.isEnglish) "Save" else "حفظ", tint = Color.White)
                            }
                        }
                        IconButton(onClick = { promoteTargetStory = story }) {
                            Icon(Icons.Default.Campaign, contentDescription = if (strings.isEnglish) "Promote" else "ترويج", tint = WesalTurquoise)
                        }
                        IconButton(onClick = {
                            viewModel.toggleArchiveStory(story.storyId, !isArchived)
                        }) {
                            Icon(
                                imageVector = if (isArchived) Icons.Default.Unarchive else Icons.Default.Archive,
                                contentDescription = if (strings.isEnglish) "Archive" else "أرشفة",
                                tint = Color.White
                            )
                        }
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
                if (!isOwner) {
                    // Reply-to-story: sends a real DM to the story owner (see
                    // StoriesViewModel.replyToStory). Previously viewers had no
                    // reply affordance at all.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = storyReplyText,
                            onValueChange = { storyReplyText = it },
                            placeholder = {
                                Text(
                                    text = if (strings.isEnglish) "Reply to story..." else "الرد على الحالة...",
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = WesalTurquoise,
                                focusedBorderColor = WesalTurquoise,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                                focusedPlaceholderColor = Color.White.copy(alpha = 0.7f),
                                unfocusedPlaceholderColor = Color.White.copy(alpha = 0.7f),
                                focusedContainerColor = Color.Black.copy(alpha = 0.55f),
                                unfocusedContainerColor = Color.Black.copy(alpha = 0.55f)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                val replyText = storyReplyText
                                if (replyText.isNotBlank()) {
                                    viewModel.replyToStory(story, replyText) { sent ->
                                        Toast.makeText(
                                            context,
                                            if (sent) {
                                                if (strings.isEnglish) "Reply sent ✓" else "تم إرسال الرد ✓"
                                            } else {
                                                if (strings.isEnglish) "Could not send reply" else "تعذر إرسال الرد"
                                            },
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    storyReplyText = ""
                                }
                            },
                            enabled = storyReplyText.isNotBlank(),
                            modifier = Modifier
                                .background(WesalEmerald, CircleShape)
                        ) {
                            Icon(
                                Icons.Default.Send,
                                contentDescription = if (strings.isEnglish) "Send reply" else "إرسال الرد",
                                tint = WesalDeepBlack
                            )
                        }
                    }
                }
                if (isOwner) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        Surface(
                            color = WesalEmerald.copy(alpha = 0.88f),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.clickable { promoteTargetStory = story }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.Campaign, contentDescription = null, tint = WesalDeepBlack, modifier = Modifier.size(18.dp))
                                Text(if (strings.isEnglish) "Promote" else "ترويج", color = WesalDeepBlack, fontWeight = FontWeight.Bold)
                            }
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
            promoteTargetStory?.let { targetStory ->
                PromoteStoryDialog(
                    story = targetStory,
                    contacts = contacts,
                    onDismiss = { promoteTargetStory = null },
                    onPromote = { recipientIds, note ->
                        viewModel.promoteStoryToContacts(targetStory, recipientIds, note)
                        promoteTargetStory = null
                    }
                )
            }
        }
        return
    }

    val myStories = stories.filter { it.ownerUid == myUid && it.storyId !in archivedStoryIds }
    val archivedMyStories = stories.filter { it.ownerUid == myUid && it.storyId in archivedStoryIds }
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (strings.isEnglish) "Updates • Stories & Channels" else "التحديثات • الحالات والقنوات",
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.turquoise,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (strings.isEnglish) "Stories expire after 24h • Channels stay pinned below" else "تنتهي الحالات خلال 24 ساعة • القنوات بالأسفل",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textMuted
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconButton(onClick = { viewModel.toggleCameraEffects(!cameraEffectsEnabled) }) {
                            Icon(
                                imageVector = Icons.Default.AutoFixHigh,
                                contentDescription = if (strings.isEnglish) "Camera Effects" else "تأثيرات الكاميرا",
                                tint = if (cameraEffectsEnabled) palette.emerald else palette.textMuted
                            )
                        }
                        if (starredMessages.isNotEmpty()) {
                            BadgedBox(badge = { Badge { Text("${starredMessages.size}") } }) {
                                IconButton(onClick = { showStarredDialog = true }) {
                                    Icon(Icons.Default.Star, contentDescription = if (strings.isEnglish) "Starred" else "المميزة بنجمة", tint = palette.turquoise)
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { showUpdatesMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = strings.more, tint = palette.textPrimary)
                            }
                            DropdownMenu(
                                expanded = showUpdatesMenu,
                                onDismissRequest = { showUpdatesMenu = false },
                                containerColor = palette.surface
                            ) {
                                DropdownMenuItem(
                                    text = { Text(strings.createChannel, color = palette.textPrimary) },
                                    leadingIcon = { Icon(Icons.Default.Campaign, contentDescription = null, tint = palette.emerald) },
                                    onClick = { showUpdatesMenu = false; showCreateChannelDialog = true }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (strings.isEnglish) "Join Channel by Code" else "انضمام لقناة برمز", color = palette.textPrimary) },
                                    leadingIcon = { Icon(Icons.Default.QrCode2, contentDescription = null, tint = palette.turquoise) },
                                    onClick = { showUpdatesMenu = false; showJoinChannelDialog = true }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (strings.isEnglish) "Story Privacy" else "خصوصية الحالة", color = palette.textPrimary) },
                                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = palette.turquoise) },
                                    onClick = { showUpdatesMenu = false; showStoryPrivacyDialog = true }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (strings.isEnglish) "Archived Stories (${archivedMyStories.size})" else "الحالات المؤرشفة (${archivedMyStories.size})", color = palette.textPrimary) },
                                    leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null, tint = palette.emerald) },
                                    onClick = { showUpdatesMenu = false; showArchivedDialog = true }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (strings.isEnglish) "Archive Settings" else "إعدادات الأرشفة", color = palette.textPrimary) },
                                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, tint = palette.textSecondary) },
                                    onClick = { showUpdatesMenu = false; showArchiveSettingsDialog = true }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (strings.isEnglish) "Starred Messages (${starredMessages.size})" else "الرسائل المميزة بنجمة (${starredMessages.size})", color = palette.textPrimary) },
                                    leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, tint = palette.turquoise) },
                                    onClick = { showUpdatesMenu = false; showStarredDialog = true }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = if (cameraEffectsEnabled) {
                                                if (strings.isEnglish) "Disable Camera Effects" else "إيقاف تأثيرات الكاميرا"
                                            } else {
                                                if (strings.isEnglish) "Enable Camera Effects" else "تفعيل تأثيرات الكاميرا"
                                            },
                                            color = palette.textPrimary
                                        )
                                    },
                                    leadingIcon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = palette.emerald) },
                                    onClick = { showUpdatesMenu = false; viewModel.toggleCameraEffects(!cameraEffectsEnabled) }
                                )
                            }
                        }
                    }
                }
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
                items(myStories, key = { "my_story_${it.storyId}" }) { story ->
                    StoryRowCard(
                        story = story,
                        isMine = true,
                        onClick = { activeStory = story },
                        onShowViewers = { viewersSheetStory = story },
                        onPromote = { promoteTargetStory = story },
                        onArchive = { viewModel.toggleArchiveStory(story.storyId, true) }
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
                items(friendsStories, key = { "friend_story_${it.storyId}" }) { story ->
                    StoryRowCard(story = story, isMine = false, onClick = { activeStory = story })
                }
            }

            item(key = "updates_channels_divider") {
                HorizontalDivider(
                    color = palette.border,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            channelsDirectorySection(
                myUid = channelsViewModel.myUid,
                searchQuery = channelSearchQuery,
                message = channelMessage,
                filteredMy = filteredMyChannels,
                filteredPublic = filteredPublicChannels,
                onSearchChange = channelsViewModel::updateSearch,
                onClearMessage = channelsViewModel::clearMessage,
                onOpenCreate = { showCreateChannelDialog = true },
                onOpenJoinByCode = { showJoinChannelDialog = true },
                onOpenChannel = onOpenChannel,
                onSubscribe = channelsViewModel::subscribe,
                showSectionTitle = true
            )
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
        var audioBgHex by remember { mutableLongStateOf(0xFF064E3BL) }
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
                    StoryBackgroundColorRow(
                        selectedHex = audioBgHex,
                        onSelect = { audioBgHex = it }
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
                                excludedViewerIds = excluded,
                                backgroundColorHex = audioBgHex
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
        var bgHex by remember { mutableLongStateOf(0xFF064E3BL) }
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
                    StoryBackgroundColorRow(
                        selectedHex = bgHex,
                        onSelect = { bgHex = it }
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

    promoteTargetStory?.let { targetStory ->
        PromoteStoryDialog(
            story = targetStory,
            contacts = contacts,
            onDismiss = { promoteTargetStory = null },
            onPromote = { recipientIds, note ->
                viewModel.promoteStoryToContacts(targetStory, recipientIds, note)
                promoteTargetStory = null
            }
        )
    }

    if (showArchivedDialog) {
        ArchivedStoriesDialog(
            archivedStories = archivedMyStories,
            onDismiss = { showArchivedDialog = false },
            onOpenStory = { story ->
                showArchivedDialog = false
                activeStory = story
            },
            onUnarchive = { storyId -> viewModel.toggleArchiveStory(storyId, false) }
        )
    }

    if (showArchiveSettingsDialog) {
        ArchiveSettingsDialog(
            keepMutedChatsArchived = archiveKeepMuted,
            archivedStoriesCount = archivedMyStories.size,
            onToggleKeepMuted = { viewModel.setArchiveKeepMuted(it) },
            onDismiss = { showArchiveSettingsDialog = false }
        )
    }

    if (showStarredDialog) {
        StarredMessagesDialog(
            entries = starredMessages,
            onDismiss = { showStarredDialog = false },
            onOpenChat = { chatId ->
                showStarredDialog = false
                if (chatId.isNotBlank()) onOpenChat(chatId)
            },
            onRemoveStar = { rawKey -> viewModel.removeStarredEntry(rawKey) }
        )
    }

    if (showStoryPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showStoryPrivacyDialog = false },
            containerColor = palette.surface,
            title = {
                Text(
                    text = if (strings.isEnglish) "Story Privacy" else "خصوصية الحالة",
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
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
            },
            confirmButton = {
                TextButton(onClick = { showStoryPrivacyDialog = false }) { Text(strings.doneLabel(strings.isEnglish)) }
            }
        )
    }

    if (showCreateChannelDialog) {
        CreateChannelDialog(
            onDismiss = { showCreateChannelDialog = false },
            onCreate = { title, desc, isPublic, avatarUri ->
                channelsViewModel.createChannel(title, desc, isPublic, avatarUri) { newChannelId ->
                    showCreateChannelDialog = false
                    onOpenChannel(newChannelId)
                }
            }
        )
    }

    if (showJoinChannelDialog) {
        JoinChannelCodeDialog(
            onDismiss = { showJoinChannelDialog = false },
            onJoin = { code ->
                channelsViewModel.joinByInviteCode(code) { chId ->
                    showJoinChannelDialog = false
                    onOpenChannel(chId)
                }
            }
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
                                WesalAvatar(
                                    imageUrl = user.photoUrl,
                                    name = user.displayName,
                                    size = 38.dp,
                                    frameId = user.frameId,
                                    frameExpiresAt = user.frameExpiresAt,
                                    isCutout = user.photoRemovedBg,
                                    iconUrl = com.wesal.messenger.core.util.WesalFrameLibrary.resolveIconUrl(user.iconId, user.iconUrl),
                                    neonColor = if (user.neonEnabled) user.neonColor else "",
                                    neonGlowIntensity = user.neonGlowIntensity,
                                    fitInsideBounds = true
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    WesalNameWithBadge(
                                        name = user.displayName.ifBlank { "@${user.publicId}" },
                                        isVerified = user.isVerified,
                                        verificationType = user.verificationType,
                                        neonColor = user.neonColor,
                                        neonGlowIntensity = user.neonGlowIntensity,
                                        neonEnabled = user.neonEnabled,
                                        verificationGlowColor = user.verificationGlowColor,
                                        verificationGlowEnabled = user.verificationGlowEnabled,
                                        verificationGlowIntensity = user.verificationGlowIntensity,
                                        iconUrl = com.wesal.messenger.core.util.WesalFrameLibrary.resolveIconUrl(user.iconId, user.iconUrl)
                                    )
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
                            WesalAvatar(
                                imageUrl = viewer.photoUrl,
                                name = viewer.displayName,
                                size = 44.dp,
                                iconUrl = com.wesal.messenger.core.util.WesalFrameLibrary.resolveIconUrl(viewer.iconId, viewer.iconUrl),
                                neonColor = if (viewer.neonEnabled) viewer.neonColor else "",
                                neonGlowIntensity = viewer.neonGlowIntensity,
                                fitInsideBounds = true
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                WesalNameWithBadge(
                                    name = viewer.displayName,
                                    isVerified = viewer.isVerified,
                                    verificationType = viewer.verificationType,
                                    neonColor = viewer.neonColor,
                                    neonGlowIntensity = viewer.neonGlowIntensity,
                                    neonEnabled = viewer.neonEnabled,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = palette.textPrimary,
                                    iconUrl = com.wesal.messenger.core.util.WesalFrameLibrary.resolveIconUrl(viewer.iconId, viewer.iconUrl)
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
    onShowViewers: (() -> Unit)? = null,
    onPromote: (() -> Unit)? = null,
    onArchive: (() -> Unit)? = null
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
            WesalAvatar(
                imageUrl = story.ownerAvatarUrl,
                name = story.ownerName,
                size = 52.dp,
                iconUrl = com.wesal.messenger.core.util.WesalFrameLibrary.resolveIconUrl(story.ownerIconId, story.ownerIconUrl),
                neonColor = if (story.ownerNeonEnabled) story.ownerNeonColor else "",
                neonGlowIntensity = story.ownerNeonGlowIntensity,
                fitInsideBounds = true
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            WesalNameWithBadge(
                name = if (isMine) (if (strings.isEnglish) "My Story" else "حالتي") else story.ownerName,
                isVerified = !isMine && story.ownerVerified,
                verificationType = story.ownerVerificationType,
                neonColor = if (isMine) "" else story.ownerNeonColor,
                neonGlowIntensity = story.ownerNeonGlowIntensity,
                neonEnabled = !isMine && story.ownerNeonEnabled,
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
                iconUrl = if (isMine) "" else com.wesal.messenger.core.util.WesalFrameLibrary.resolveIconUrl(story.ownerIconId, story.ownerIconUrl)
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
        if (isMine) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onPromote != null) {
                    IconButton(onClick = onPromote, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Campaign,
                            contentDescription = if (strings.isEnglish) "Promote" else "ترويج",
                            tint = palette.emerald,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                if (onArchive != null) {
                    IconButton(onClick = onArchive, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Archive,
                            contentDescription = if (strings.isEnglish) "Archive" else "أرشفة",
                            tint = palette.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                if (onShowViewers != null) {
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
    }
}

private fun com.wesal.messenger.core.util.WesalStrings.doneLabel(isEn: Boolean): String =
    if (isEn) "Done" else "تم"

@Composable
private fun StoryBackgroundColorRow(
    selectedHex: Long,
    onSelect: (Long) -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = if (strings.isEnglish) "Story Background Color" else "لون خلفية الحالة",
            style = MaterialTheme.typography.labelMedium,
            color = palette.textSecondary
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            STORY_BG_COLORS.forEach { colorHex ->
                val isSelected = selectedHex == colorHex
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(colorHex))
                        .border(
                            width = if (isSelected) 2.5.dp else 1.dp,
                            color = if (isSelected) palette.turquoise else Color.White.copy(alpha = 0.35f),
                            shape = CircleShape
                        )
                        .clickable { onSelect(colorHex) }
                )
            }
        }
    }
}

@Composable
private fun PromoteStoryDialog(
    story: StoryItem,
    contacts: List<WesalUser>,
    onDismiss: () -> Unit,
    onPromote: (List<String>, String) -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val context = LocalContext.current
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = {
            Text(
                text = if (strings.isEnglish) "Promote Story" else "ترويج الحالة",
                color = palette.textPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(if (strings.isEnglish) "Promotion note (optional)" else "رسالة الترويج (اختياري)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { selectedIds = contacts.map { it.uid }.toSet() }) {
                        Text(if (strings.isEnglish) "Select All" else "تحديد الكل", color = palette.emerald)
                    }
                    TextButton(onClick = {
                        val shareTxt = buildString {
                            append("📣 ")
                            if (note.isNotBlank()) append(note.trim()).append("\n")
                            append(story.caption.ifBlank { story.textContent.ifBlank { "حالة على WESAL Messenger" } })
                            append("\nhttps://tinyurl.com/wesal-Messenger")
                        }
                        WesalShareUtils.shareViaWhatsApp(context, shareTxt)
                    }) {
                        Text(if (strings.isEnglish) "Share External" else "مشاركة خارجية", color = palette.turquoise)
                    }
                }
                if (contacts.isEmpty()) {
                    Text(
                        text = if (strings.isEnglish) "No approved contacts yet" else "لا توجد جهات اتصال معتمدة حالياً",
                        color = palette.textMuted
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        items(contacts, key = { it.uid }) { user ->
                            val checked = user.uid in selectedIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedIds = if (checked) selectedIds - user.uid else selectedIds + user.uid
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { isChecked ->
                                        selectedIds = if (isChecked) selectedIds + user.uid else selectedIds - user.uid
                                    }
                                )
                                WesalAvatar(
                                    imageUrl = user.photoUrl,
                                    name = user.displayName,
                                    size = 34.dp,
                                    frameId = user.frameId,
                                    frameExpiresAt = user.frameExpiresAt,
                                    isCutout = user.photoRemovedBg,
                                    iconUrl = com.wesal.messenger.core.util.WesalFrameLibrary.resolveIconUrl(user.iconId, user.iconUrl),
                                    neonColor = if (user.neonEnabled) user.neonColor else "",
                                    neonGlowIntensity = user.neonGlowIntensity,
                                    fitInsideBounds = true
                                )
                                WesalNameWithBadge(
                                    name = user.displayName.ifBlank { "@${user.publicId}" },
                                    isVerified = user.isVerified,
                                    verificationType = user.verificationType,
                                    neonColor = user.neonColor,
                                    neonEnabled = user.neonEnabled,
                                    neonGlowIntensity = user.neonGlowIntensity,
                                    verificationGlowEnabled = user.verificationGlowEnabled,
                                    verificationGlowIntensity = user.verificationGlowIntensity,
                                    modifier = Modifier.weight(1f),
                                    iconUrl = com.wesal.messenger.core.util.WesalFrameLibrary.resolveIconUrl(user.iconId, user.iconUrl)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onPromote(selectedIds.toList(), note) },
                enabled = selectedIds.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary)
            ) {
                Text(if (strings.isEnglish) "Send (${selectedIds.size})" else "ترويج (${selectedIds.size})")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        }
    )
}

@Composable
private fun ArchivedStoriesDialog(
    archivedStories: List<StoryItem>,
    onDismiss: () -> Unit,
    onOpenStory: (StoryItem) -> Unit,
    onUnarchive: (String) -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = {
            Text(
                text = if (strings.isEnglish) "Archived Stories" else "الحالات المؤرشفة",
                color = palette.textPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            if (archivedStories.isEmpty()) {
                Text(
                    text = if (strings.isEnglish) "No archived stories yet." else "لا توجد حالات في الأرشيف حالياً.",
                    color = palette.textSecondary
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(archivedStories, key = { it.storyId }) { story ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.surfaceElevated)
                                .clickable { onOpenStory(story) }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = story.textContent.ifBlank { story.caption.ifBlank { story.type.raw } },
                                    color = palette.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = DateTimeUtils.formatChatListTime(story.createdAt),
                                    color = palette.textMuted,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            TextButton(onClick = { onUnarchive(story.storyId) }) {
                                Text(if (strings.isEnglish) "Restore" else "استعادة", color = palette.emerald)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(strings.close) }
        }
    )
}

@Composable
private fun ArchiveSettingsDialog(
    keepMutedChatsArchived: Boolean,
    archivedStoriesCount: Int,
    onToggleKeepMuted: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = {
            Text(
                text = if (strings.isEnglish) "Archive Settings" else "إعدادات الأرشفة",
                color = palette.textPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (strings.isEnglish) "Keep muted chats archived" else "إبقاء المحادثات المكتومة مؤرشفة",
                            color = palette.textPrimary,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (strings.isEnglish) "Muted chats stay hidden when new messages arrive." else "تظل المحادثات المكتومة في الأرشيف عند وصول رسائل جديدة.",
                            color = palette.textSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Switch(checked = keepMutedChatsArchived, onCheckedChange = onToggleKeepMuted)
                }
                Text(
                    text = if (strings.isEnglish) "Archived stories: $archivedStoriesCount" else "عدد الحالات المؤرشفة: $archivedStoriesCount",
                    color = palette.turquoise,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(strings.close) }
        }
    )
}

@Composable
private fun StarredMessagesDialog(
    entries: List<StarredMessageEntry>,
    onDismiss: () -> Unit,
    onOpenChat: (String) -> Unit,
    onRemoveStar: (String) -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = {
            Text(
                text = if (strings.isEnglish) "Starred Messages" else "الرسائل المميزة بنجمة",
                color = palette.textPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            if (entries.isEmpty()) {
                Text(
                    text = if (strings.isEnglish) "No starred messages yet. Long-press any message to star it." else "لا توجد رسائل مميزة بنجمة حتى الآن.",
                    color = palette.textSecondary
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entries, key = { it.rawKey }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.surfaceElevated)
                                .clickable { onOpenChat(entry.chatId) }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Star, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(18.dp))
                            Text(
                                text = entry.summary,
                                color = palette.textPrimary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { onRemoveStar(entry.rawKey) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Close, contentDescription = strings.delete, tint = palette.textMuted, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(strings.close) }
        }
    )
}
