package com.wesal.messenger.stories

import android.net.Uri
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.wesal.messenger.core.theme.*
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.domain.model.PrivacyVisibility
import com.wesal.messenger.domain.model.StoryItem
import com.wesal.messenger.domain.model.StoryType
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.StoryRepository
import com.wesal.messenger.media.VideoPlayerBox
import com.wesal.messenger.presentation.components.WesalAvatar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StoriesViewModel @Inject constructor(
    private val storyRepository: StoryRepository,
    authRepository: AuthRepository
) : ViewModel() {
    val myUid = authRepository.currentUserId.orEmpty()
    val stories: StateFlow<List<StoryItem>> = storyRepository.observeActiveStories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isPublishing = MutableStateFlow(false)

    fun publishTextStory(text: String, bgHex: Long, visibility: PrivacyVisibility) {
        if (text.isBlank()) return
        viewModelScope.launch {
            isPublishing.value = true
            storyRepository.publishTextStory(text, bgHex, visibility)
            isPublishing.value = false
        }
    }

    fun publishMediaStory(uri: Uri, isVideo: Boolean, caption: String, visibility: PrivacyVisibility) {
        viewModelScope.launch {
            isPublishing.value = true
            storyRepository.publishMediaStory(uri, isVideo, caption, visibility)
            isPublishing.value = false
        }
    }

    fun markViewed(story: StoryItem) {
        if (story.ownerUid != myUid && !story.viewerIds.contains(myUid)) {
            viewModelScope.launch { storyRepository.markStoryViewed(story.storyId) }
        }
    }

    fun deleteStory(storyId: String) {
        viewModelScope.launch { storyRepository.deleteStory(storyId) }
    }
}

@Composable
fun StoriesScreen(viewModel: StoriesViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val stories by viewModel.stories.collectAsStateWithLifecycle()
    val isPublishing by viewModel.isPublishing.collectAsStateWithLifecycle()

    var showTextDialog by remember { mutableStateOf(false) }
    var activeStory by remember { mutableStateOf<StoryItem?>(null) }
    var selectedVisibility by remember { mutableStateOf(PrivacyVisibility.EVERYONE) }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri).orEmpty()
            viewModel.publishMediaStory(uri, isVideo = mime.startsWith("video"), caption = "", visibility = selectedVisibility)
        }
    }

    activeStory?.let { story ->
        LaunchedEffect(story.storyId) { viewModel.markViewed(story) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (story.type == StoryType.TEXT) Color(story.backgroundColorHex) else Color.Black)
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
                        Text(DateTimeUtils.formatChatListTime(story.createdAt), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
                    }
                }
                Row {
                    if (story.ownerUid == viewModel.myUid) {
                        IconButton(onClick = {
                            viewModel.deleteStory(story.storyId)
                            activeStory = null
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "حذف الحالة", tint = WesalError)
                        }
                    }
                    IconButton(onClick = { activeStory = null }) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.White)
                    }
                }
            }

            if (story.ownerUid == viewModel.myUid) {
                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(24.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Visibility, contentDescription = null, tint = WesalTurquoise, modifier = Modifier.size(18.dp))
                        Text("${story.viewerIds.size} مشاهدة", color = Color.White)
                    }
                }
            }
        }
        return
    }

    val myStories = stories.filter { it.ownerUid == viewModel.myUid }
    val friendsStories = stories.filter { it.ownerUid != viewModel.myUid }

    Box(modifier = Modifier.fillMaxSize().background(WesalDeepBlack)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (isPublishing) {
                item {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = WesalTurquoise)
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

            if (myStories.isEmpty()) {
                item {
                    Surface(
                        color = WesalSurfaceDark,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
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
                                Text("أضف حالتك", color = WesalTextPrimary, fontWeight = FontWeight.Bold)
                                Text("شارك صورة أو فيديو أو نصاً مع أصدقائك", style = MaterialTheme.typography.labelMedium, color = WesalTextSecondary)
                            }
                        }
                    }
                }
            } else {
                items(myStories, key = { it.storyId }) { story ->
                    StoryRowCard(story = story, isMine = true, onClick = { activeStory = story })
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End
        ) {
            SmallFloatingActionButton(
                onClick = { showTextDialog = true },
                containerColor = WesalSurfaceElevated,
                contentColor = WesalTurquoise
            ) {
                Icon(Icons.Default.Edit, contentDescription = "حالة نصية")
            }
            FloatingActionButton(
                onClick = {
                    mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
                containerColor = WesalEmerald,
                contentColor = WesalDeepBlack
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = "نشر صورة أو فيديو")
            }
        }
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
}

@Composable
private fun StoryRowCard(story: StoryItem, isMine: Boolean, onClick: () -> Unit) {
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
            Text(
                text = "${DateTimeUtils.formatChatListTime(story.createdAt)} • ${story.viewerIds.size} مشاهدة",
                style = MaterialTheme.typography.labelSmall,
                color = WesalTextSecondary
            )
        }
    }
}
