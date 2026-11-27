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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.google.accompanist.permissions.shouldShowRationale
import com.wesal.messenger.R
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.AssistantLogic
import com.wesal.messenger.core.util.AssistantMediaKind
import com.wesal.messenger.core.util.AssistantMessage
import com.wesal.messenger.core.util.AssistantRole
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.core.util.DictationLogic
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.presentation.components.WesalNameWithBadge
import com.wesal.messenger.presentation.components.wesalFieldColors
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
    // Round 9 (item 10): clearing the conversation asks first — the wipe is
    // destructive and purely local (history lives only in the per-uid
    // DataStore; nothing exists server-side to restore from).
    var showClearConfirm by remember { mutableStateOf(false) }

    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) pendingImageUri = uri
    }

    // Round 10 (item 7): voice DICTATION — speech-to-text into the input
    // field (distinct from the voice-NOTE button, which records a message
    // for the assistant to listen to). Android SpeechRecognizer with ar_EG;
    // partial results stream into the field and the final text stays for
    // review/editing before sending. Assistant-screen only: the main chat
    // input is a separate component and does not share this field.
    // Round 13 (Addenda I.3 + I2): dictation is CONTINUOUS — while the
    // mode is ON, end-of-speech and pause/transient errors auto-restart
    // listening and segments keep appending; NO silence timeout ends the
    // session. It stops only when the owner taps the mic/stop control
    // himself, or when DictationLogic proves the recognition service
    // dead (hard error, or repeated fast failures) — then an honest
    // Arabic notice replaces the old silent failure.
    val dictationContext = androidx.compose.ui.platform.LocalContext.current
    var isDictating by remember { mutableStateOf(false) }
    var dictationFastFailures by remember { mutableStateOf(0) }
    var dictationSessionStartedAt by remember { mutableStateOf(0L) }
    val dictationHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val dictationActiveState = rememberUpdatedState(isDictating)
    // Permission hand-off for dictation: a tap while ungranted launches the
    // system request; on grant the dictation starts immediately, and a
    // permanently-denied state gets a clear Arabic explanation instead of a
    // dead button.
    var dictationPermissionPending by remember { mutableStateOf(false) }
    var dictationRequestedOnce by remember { mutableStateOf(false) }
    var dictationBase by remember { mutableStateOf("") }
    val inputTextState = rememberUpdatedState(inputText)
    val speechRecognizer = remember(dictationContext) {
        if (android.speech.SpeechRecognizer.isRecognitionAvailable(dictationContext)) {
            android.speech.SpeechRecognizer.createSpeechRecognizer(dictationContext)
        } else {
            null
        }
    }
    DisposableEffect(speechRecognizer) {
        onDispose {
            dictationHandler.removeCallbacksAndMessages(null)
            runCatching { speechRecognizer?.destroy() }
        }
    }
    fun showDictationToast(ar: String, en: String) {
        android.widget.Toast.makeText(
            dictationContext,
            if (strings.isEnglish) en else ar,
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
    fun buildDictationIntent(): android.content.Intent =
        android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ar-EG")
            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Longer pause tolerance where the engine honors these —
            // thinking pauses must not cut the session (Addendum I2).
            putExtra(
                android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                3000L
            )
            putExtra(
                android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                5000L
            )
            putExtra(
                android.speech.RecognizerIntent.EXTRA_PROMPT,
                if (strings.isEnglish) "Speak now…" else "اتكلم دلوقتي…"
            )
        }

    /** User-initiated (or final) stop: the ONLY normal end of a dictation session. */
    fun stopDictation() {
        isDictating = false
        dictationHandler.removeCallbacksAndMessages(null)
        runCatching { speechRecognizer?.stopListening() }
    }

    fun beginDictationSession() {
        val recognizer = speechRecognizer ?: return
        dictationSessionStartedAt = System.currentTimeMillis()
        runCatching { recognizer.startListening(buildDictationIntent()) }
            .onFailure {
                if (dictationActiveState.value) {
                    isDictating = false
                    showDictationToast(
                        "تعذر بدء الإملاء الصوتي",
                        "Could not start voice dictation"
                    )
                }
            }
    }

    fun scheduleDictationRestart() {
        dictationHandler.removeCallbacksAndMessages(null)
        dictationHandler.postDelayed({
            if (dictationActiveState.value) {
                beginDictationSession()
            }
        }, DictationLogic.RESTART_DELAY_MS)
    }

    fun startDictation() {
        val recognizer = speechRecognizer
        if (recognizer == null) {
            showDictationToast(
                "الإملاء الصوتي غير متاح على هذا الجهاز",
                "Voice dictation is not available on this device"
            )
            return
        }
        dictationBase = inputTextState.value.trim()
        dictationFastFailures = 0
        recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
            private fun spokenText(bundle: android.os.Bundle?): String =
                bundle
                    ?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()

            override fun onResults(results: android.os.Bundle?) {
                val spoken = spokenText(results)
                if (spoken.isNotBlank()) {
                    inputText = DictationLogic.appendSegment(dictationBase, spoken)
                    // Commit the segment: the next auto-restarted session
                    // appends AFTER it instead of replacing it.
                    dictationBase = inputTextState.value.trim()
                }
                // A session that produced results is healthy.
                dictationFastFailures = 0
                // Continuous (I2): keep listening through the pause that
                // ended this segment — only the user's tap stops us.
                if (dictationActiveState.value) scheduleDictationRestart()
            }

            override fun onPartialResults(partialResults: android.os.Bundle?) {
                val spoken = spokenText(partialResults)
                if (spoken.isNotBlank()) {
                    inputText = DictationLogic.appendSegment(dictationBase, spoken)
                }
            }

            override fun onError(error: Int) {
                if (!dictationActiveState.value) return
                if (DictationLogic.isHardError(error)) {
                    stopDictation()
                    if (error == DictationLogic.ERROR_INSUFFICIENT_PERMISSIONS) {
                        showDictationToast(
                            "لازم تسمح بصلاحية الميكروفون عشان الإملاء الصوتي يشتغل",
                            "Microphone permission is required for voice dictation"
                        )
                    } else {
                        showDictationToast(
                            "الإملاء الصوتي بالعربي غير متاح على هذا الجهاز",
                            "Arabic voice dictation is not available on this device"
                        )
                    }
                    return
                }
                val sessionMs = System.currentTimeMillis() - dictationSessionStartedAt
                dictationFastFailures = DictationLogic.nextFastFailureStreak(
                    dictationFastFailures, sessionMs
                )
                if (error == DictationLogic.ERROR_RECOGNIZER_BUSY) {
                    runCatching { speechRecognizer?.cancel() }
                }
                if (DictationLogic.shouldRestart(true, error, dictationFastFailures)) {
                    // Pause/transient end — restart QUIETLY (no toast:
                    // a thinking pause is not an error).
                    scheduleDictationRestart()
                } else {
                    // The service keeps dying instantly — it is
                    // unavailable on this device; say so honestly
                    // instead of looping or failing silently.
                    stopDictation()
                    showDictationToast(
                        "الإملاء الصوتي غير متاح على هذا الجهاز حالياً",
                        "Voice dictation is not available on this device right now"
                    )
                }
            }

            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
        isDictating = true
        beginDictationSession()
    }
    // Permission hand-off (declared after startDictation — a local function
    // must be declared before a LaunchedEffect lambda references it): when
    // the system permission request returns granted, start listening at once.
    LaunchedEffect(micPermission.status.isGranted) {
        if (dictationPermissionPending && micPermission.status.isGranted) {
            dictationPermissionPending = false
            startDictation()
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, isSending) {
        val itemCount = messages.size + if (isSending) 1 else 0
        if (itemCount > 0) {
            listState.animateScrollToItem(itemCount - 1)
        }
    }

    // Round 9 (item 10): clear-conversation confirmation. On confirm the
    // ViewModel wipes the per-uid DataStore history + local media files and
    // empties the message state, so the screen falls back to the greeting +
    // suggestion-chips empty state on its own.
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = palette.surface,
            title = {
                Text(
                    text = if (strings.isEnglish) "Clear conversation?" else "مسح المحادثة؟",
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (strings.isEnglish) {
                        "All your messages with the assistant will be deleted."
                    } else {
                        "سيتم حذف كل الرسائل مع المساعد"
                    },
                    color = palette.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirm = false
                        viewModel.clearConversation()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.error,
                        contentColor = palette.onAccent
                    )
                ) {
                    Text(
                        if (strings.isEnglish) "Clear" else "مسح",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(
                        if (strings.isEnglish) "Cancel" else "إلغاء",
                        color = palette.textSecondary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        )
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
                            // ic_launcher_foreground (raster PNG) — ic_launcher
                            // is an adaptive-icon XML and crashes painterResource.
                            painter = painterResource(id = R.mipmap.ic_launcher_foreground),
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
                                // Round 9 (item 10): nothing to clear when the
                                // conversation is already empty, and a wipe
                                // always goes through the confirmation dialog.
                                enabled = messages.isNotEmpty(),
                                onClick = {
                                    menuOpen = false
                                    showClearConfirm = true
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            // Round 10 (item 11): imePadding is what lifts this bar above the
            // keyboard. The app is edge-to-edge (MainActivity enableEdgeToEdge),
            // so the manifest's adjustResize no longer moves Compose content —
            // without consuming the IME inset here, the Scaffold pins the
            // bottomBar to the screen's bottom edge BEHIND the keyboard and
            // the owner typed blind (his screenshot: keyboard open, no input
            // bar visible). With imePadding the bar rides directly on top of
            // the keyboard, the Scaffold re-measures it, and the message list
            // shrinks above it instead of pushing it off-screen. Keyboard
            // closed → IME inset is 0 → layout identical to before.
            Column(
                modifier = Modifier
                    .background(palette.surface)
                    .imePadding()
            ) {
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
                            }
                            // Round 13 (Item I.1): the composer never
                            // disables while a reply is in flight — a new
                            // send supersedes the old ask.
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
                                    if (isDictating) {
                                        if (strings.isEnglish) "Listening… speak now" else "بيسمعك… اتكلم دلوقتي"
                                    } else if (strings.isEnglish) {
                                        "Ask Wesal Assistant anything..."
                                    } else {
                                        "اسأل مساعد وِصال أي حاجة..."
                                    }
                                )
                            },
                            // Round 10 (item 7): dictation mic INSIDE the
                            // field — speech becomes text for review before
                            // sending (the round button outside the field
                            // stays the voice-NOTE recorder).
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        if (isDictating) {
                                            // Round 13 (I2): the user's tap is
                                            // the ONLY normal end of dictation.
                                            stopDictation()
                                        } else if (!micPermission.status.isGranted) {
                                            if (dictationRequestedOnce &&
                                                !micPermission.status.shouldShowRationale
                                            ) {
                                                // Permanently denied: say so
                                                // plainly instead of a dead tap.
                                                showDictationToast(
                                                    "صلاحية الميكروفون مرفوضة — فعّلها من إعدادات التطبيق عشان الإملاء الصوتي يشتغل",
                                                    "Microphone permission is denied — enable it in app settings to use voice dictation"
                                                )
                                            } else {
                                                dictationRequestedOnce = true
                                                dictationPermissionPending = true
                                                micPermission.launchPermissionRequest()
                                            }
                                        } else {
                                            startDictation()
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardVoice,
                                        contentDescription = if (strings.isEnglish) {
                                            "Voice dictation"
                                        } else {
                                            "إملاء صوتي"
                                        },
                                        tint = if (isDictating) palette.error else palette.turquoise
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                            minLines = 1,
                            maxLines = 6,
                            shape = RoundedCornerShape(24.dp),
                            // Round 13 (Item H): the standard field pairing
                            // (this composer was the device-proven ghost-text
                            // site — typed text rendered near-white on the
                            // light field).
                            colors = wesalFieldColors()
                        )

                        if (isSending) {
                            // Round 13 (Item I.1): visible STOP while a
                            // reply is in flight («بيكتب...») — cancels the
                            // ask, clears the typing indicator at once and
                            // frees the composer.
                            IconButton(onClick = { viewModel.cancelInFlight() }) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = if (strings.isEnglish) "Stop reply" else "إيقاف الرد",
                                    tint = palette.error
                                )
                            }
                        }

                        if (inputText.isNotBlank() || pendingImageUri != null) {
                            FloatingActionButton(
                                onClick = {
                                    // Sending ends any live dictation first,
                                    // so the recognizer cannot keep writing
                                    // into the cleared field.
                                    if (isDictating) {
                                        dictationHandler.removeCallbacksAndMessages(null)
                                        runCatching { speechRecognizer?.cancel() }
                                        isDictating = false
                                    }
                                    // Round 13 (Item I.1): sending while a
                                    // reply is in flight SUPERSEDES it (the
                                    // ViewModel cancels the old ask) instead
                                    // of silently dropping the draft.
                                    val image = pendingImageUri
                                    if (image != null) {
                                        viewModel.sendImage(image, inputText)
                                    } else {
                                        viewModel.sendText(inputText)
                                    }
                                    inputText = ""
                                    pendingImageUri = null
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
                    // ic_launcher_foreground (raster PNG) — ic_launcher
                    // is an adaptive-icon XML and crashes painterResource.
                    painter = painterResource(id = R.mipmap.ic_launcher_foreground),
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
                        if (isMine) {
                            Text(
                                text = message.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = palette.textPrimary
                            )
                        } else {
                            // Round 10 (item 5): assistant replies are
                            // Markdown — render them parsed (bold spans,
                            // bullets, headings) instead of raw "**"/"*" text.
                            AssistantFormattedText(text = message.text)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Round 10 (item 5): renders one assistant reply from
 * [AssistantLogic.parseDisplayLines] — bold spans via AnnotatedString,
 * bullet lines with a "• " marker, heading lines bold/larger. Raw Markdown
 * syntax never reaches the screen.
 */
@Composable
private fun AssistantFormattedText(text: String) {
    val palette = WesalColors.current
    val lines = remember(text) { AssistantLogic.parseDisplayLines(text) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (line in lines) {
            val annotated = remember(line) {
                androidx.compose.ui.text.buildAnnotatedString {
                    if (line.isBullet) append("•  ")
                    for (span in line.spans) {
                        if (span.bold) {
                            pushStyle(
                                androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)
                            )
                            append(span.text)
                            pop()
                        } else {
                            append(span.text)
                        }
                    }
                }
            }
            Text(
                text = annotated,
                style = if (line.isHeading) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                fontWeight = if (line.isHeading) FontWeight.Bold else null,
                color = palette.textPrimary
            )
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
