package com.wesal.messenger.aiassistant

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.wesal.messenger.R
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.AssistantLogic
import com.wesal.messenger.core.util.AssistantMediaKind
import com.wesal.messenger.core.util.AssistantMessage
import com.wesal.messenger.core.util.AssistantRole
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.presentation.components.WesalNameWithBadge
import com.wesal.messenger.voice.VoicePlaybackState
import java.io.File
import java.util.Calendar

/**
 * «مساعد وِصال» chat screen (round 8), modelled on the owner's Meta-AI
 * reference: header with the app icon + verified name, a greeting empty
 * state with suggestion chips, and a normal chat surface (text, photo and
 * voice questions; text answers). All state lives in [AssistantViewModel];
 * the conversation is local-only and never touches Firestore.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun AssistantChatScreen(
    onBack: () -> Unit,
    viewModel: AssistantViewModel = hiltViewModel()
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val isSending by viewModel.isSending.collectAsStateWithLifecycle()
    val userDisplayName by viewModel.userDisplayName.collectAsStateWithLifecycle()
    val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()

    var inputText by remember { mutableStateOf("") }
    var pendingImageUri by remember { mutableStateOf<Uri?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) pendingImageUri = uri
    }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, isSending) {
        val itemCount = messages.size + if (isSending) 1 else 0
        if (itemCount > 0) {
            listState.animateScrollToItem(itemCount - 1)
        }
    }

    Scaffold(
        containerColor = palette.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.surface),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.back,
                            tint = palette.textPrimary
                        )
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.mipmap.ic_launcher),
                            contentDescription = null,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Column {
                            WesalNameWithBadge(
                                name = if (strings.isEnglish) "Wesal Assistant" else "مساعد وِصال",
                                isVerified = true,
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.textPrimary
                            )
                            Text(
                                text = if (strings.isEnglish) {
                                    "Online — replies instantly"
                                } else {
                                    "متصل — بيرد فوراً"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.emerald
                            )
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = strings.more,
                                tint = palette.textPrimary
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (strings.isEnglish) "Clear conversation" else "مسح المحادثة"
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    viewModel.clearConversation()
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            Column(modifier = Modifier.background(palette.surface)) {
                if (recordingState.isRecording) {
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
                        Button(
                            onClick = viewModel::stopAndSendVoiceRecording,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = palette.emerald,
                                contentColor = palette.onPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(strings.send, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        }
                    }
                } else {
                    pendingImageUri?.let { uri ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AsyncImage(
                                model = uri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            Text(
                                text = if (strings.isEnglish) {
                                    "Photo ready — type your question or just send"
                                } else {
                                    "الصورة جاهزة — اكتب سؤالك أو ابعت على طول"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.textSecondary,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { pendingImageUri = null }) {
                                Icon(Icons.Default.Close, contentDescription = strings.cancel, tint = palette.textSecondary)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = {
                                photoPicker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                            enabled = !isSending
                        ) {
                            Icon(
                                imageVector = Icons.Default.Photo,
                                contentDescription = if (strings.isEnglish) "Send a photo" else "إرسال صورة",
                                tint = palette.turquoise
                            )
                        }

                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = {
                                Text(
                                    if (strings.isEnglish) {
                                        "Ask Wesal Assistant anything..."
                                    } else {
                                        "اسأل مساعد وِصال أي حاجة..."
                                    }
                                )
                            },
                            modifier = Modifier.weight(1f),
                            minLines = 1,
                            maxLines = 6,
                            shape = RoundedCornerShape(24.dp),
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

                        if (inputText.isNotBlank() || pendingImageUri != null) {
                            FloatingActionButton(
                                onClick = {
                                    // While an ask is in flight the ViewModel
                                    // ignores new sends — keep the draft in
                                    // that case instead of clearing it into
                                    // a silent drop.
                                    if (!isSending) {
                                        val image = pendingImageUri
                                        if (image != null) {
                                            viewModel.sendImage(image, inputText)
                                        } else {
                                            viewModel.sendText(inputText)
                                        }
                                        inputText = ""
                                        pendingImageUri = null
                                    }
                                },
                                modifier = Modifier.size(48.dp),
                                containerColor = palette.emerald,
                                contentColor = palette.onPrimary
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = strings.send)
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    if (!micPermission.status.isGranted) {
                                        micPermission.launchPermissionRequest()
                                    } else {
                                        viewModel.startVoiceRecording()
                                    }
                                },
                                enabled = !isSending,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(palette.emerald)
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = null, tint = palette.onPrimary)
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (messages.isEmpty() && !isSending) {
                AssistantEmptyState(
                    userDisplayName = userDisplayName,
                    onChipClick = { chip -> viewModel.sendText(chip) }
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        AssistantBubble(
                            message = message,
                            playbackState = playbackState,
                            onToggleVoice = { viewModel.toggleVoicePlayback(message) }
                        )
                    }
                    if (isSending) {
                        item(key = "assistant_typing") {
                            AssistantTypingBubble()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantEmptyState(
    userDisplayName: String,
    onChipClick: (String) -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val greeting = remember(userDisplayName) {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        AssistantLogic.greetingFor(hour, AssistantLogic.firstNameOf(userDisplayName))
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item(key = "assistant_greeting") {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 10.dp)
            ) {
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher),
                    contentDescription = null,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(24.dp))
                )
                Text(
                    text = greeting,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = if (strings.isEnglish) {
                        "Ask me anything — write, send a photo, or record your voice"
                    } else {
                        "اسألني أي حاجة — اكتب، ابعت صورة، أو سجّل بصوتك"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
        items(AssistantLogic.SUGGESTIONS, key = { it }) { chip ->
            Surface(
                color = palette.surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, palette.turquoise.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onChipClick(chip) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = palette.turquoise,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = chip,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantBubble(
    message: AssistantMessage,
    playbackState: VoicePlaybackState,
    onToggleVoice: () -> Unit
) {
    val palette = WesalColors.current
    val isMine = message.role == AssistantRole.USER
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
            modifier = Modifier.widthIn(max = 310.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (message.isErrorNotice) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = palette.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = palette.error
                        )
                    }
                } else {
                    if (message.mediaKind == AssistantMediaKind.IMAGE && message.mediaPath.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(190.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.surface)
                        ) {
                            AsyncImage(
                                model = File(message.mediaPath),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                    if (message.mediaKind == AssistantMediaKind.VOICE) {
                        AssistantVoiceChip(
                            message = message,
                            playbackState = playbackState,
                            onToggleVoice = onToggleVoice
                        )
                    }
                    if (message.text.isNotBlank()) {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = palette.textPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantVoiceChip(
    message: AssistantMessage,
    playbackState: VoicePlaybackState,
    onToggleVoice: () -> Unit
) {
    val palette = WesalColors.current
    val isActive = playbackState.activeMessageId == message.id
    val isPlaying = isActive && playbackState.isPlaying
    val progress = if (isActive && playbackState.durationMs > 0) {
        playbackState.positionMs.toFloat() / playbackState.durationMs.toFloat()
    } else {
        0f
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.widthIn(min = 180.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(palette.emerald)
                .clickable { onToggleVoice() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = palette.onPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            LinearProgressIndicator(
                progress = { progress },
                color = palette.turquoise,
                trackColor = palette.border,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
            )
            Text(
                text = DateTimeUtils.formatDurationMillis(
                    if (isActive && playbackState.durationMs > 0) {
                        playbackState.durationMs.toLong()
                    } else {
                        message.durationMs
                    }
                ),
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary
            )
        }
        Icon(
            Icons.Default.Mic,
            contentDescription = null,
            tint = palette.textSecondary,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun AssistantTypingBubble() {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val transition = rememberInfiniteTransition(label = "assistant_typing_dots")
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(
            color = palette.bubbleIncoming,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = 4.dp,
                bottomEnd = 16.dp
            ),
            modifier = Modifier.widthIn(max = 310.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(3) { index ->
                        val alpha by transition.animateFloat(
                            initialValue = 0.25f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 500, delayMillis = index * 160),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "assistant_typing_dot_$index"
                        )
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(palette.textSecondary.copy(alpha = alpha))
                        )
                    }
                }
                Text(
                    text = if (strings.isEnglish) "Wesal Assistant is typing…" else "مساعد وِصال بيكتب…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )
            }
        }
    }
}
