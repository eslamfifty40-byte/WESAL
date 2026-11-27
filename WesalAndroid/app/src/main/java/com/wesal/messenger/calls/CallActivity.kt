package com.wesal.messenger.calls

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.theme.WesalTheme
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.data.local.AppUserSettings
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.data.repository.CallSignalingErrors
import com.wesal.messenger.domain.model.CallStatus
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.CallRepository
import com.wesal.messenger.presentation.components.WesalAvatar
import com.wesal.messenger.presentation.components.WesalNameWithBadge
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SurfaceViewRenderer
import javax.inject.Inject

@AndroidEntryPoint
class CallActivity : AppCompatActivity() {

    @Inject lateinit var webRtcManager: WebRtcCallManager
    @Inject lateinit var callRepository: CallRepository
    @Inject lateinit var prefs: UserPreferencesDataStore

    private var activeCallId: String = ""
    private var startedSeconds: Long = 0L
    private var isFinishingCall: Boolean = false
    private val pendingLocalCandidates = mutableListOf<IceCandidate>()

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Strict screenshot & screen-recording protection during active calls
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, false)
        val initialCallId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
        val peerUid = intent.getStringExtra(EXTRA_PEER_UID).orEmpty()
        val peerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: "WESAL User"
        val peerAvatar = intent.getStringExtra(EXTRA_PEER_AVATAR).orEmpty()
        val peerVerified = intent.getBooleanExtra(EXTRA_PEER_VERIFIED, false)
        val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
        activeCallId = initialCallId

        setContent {
            val settings by prefs.settingsFlow.collectAsStateWithLifecycle(initialValue = AppUserSettings())
            WesalTheme(
                darkTheme = settings.darkTheme,
                languageTag = settings.languageTag,
                fontScale = settings.fontScale
            ) {
                val palette = WesalColors.current
                val strings = LocalWesalStrings.current

                val requestedPermissions = remember(isVideo) {
                    buildList {
                        add(Manifest.permission.RECORD_AUDIO)
                        if (isVideo) add(Manifest.permission.CAMERA)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            add(Manifest.permission.BLUETOOTH_CONNECT)
                        }
                    }
                }
                val permissions = rememberMultiplePermissionsState(requestedPermissions)
                val coreMediaGranted = permissions.permissions
                    .filter {
                        it.permission == Manifest.permission.RECORD_AUDIO ||
                            (isVideo && it.permission == Manifest.permission.CAMERA)
                    }
                    .all { it.status.isGranted }

                var micMuted by remember { mutableStateOf(false) }
                var cameraOff by remember { mutableStateOf(!isVideo) }
                var durationSeconds by remember { mutableLongStateOf(0L) }
                // Start strictly with CONNECTING ("جاري الاتصال...") — switches to RINGING only on real callee ACK
                var callStatusLabel by remember(strings.isEnglish) {
                    mutableStateOf(strings.connectingCall)
                }

                val connState by webRtcManager.connectionState.collectAsStateWithLifecycle()
                val iceState by webRtcManager.iceConnectionState.collectAsStateWithLifecycle()
                val turnConfigured by webRtcManager.turnConfigured.collectAsStateWithLifecycle()
                val signalingError by CallSignalingErrors.error.collectAsStateWithLifecycle()
                val sessionError by webRtcManager.sessionError.collectAsStateWithLifecycle()
                val remoteVideo by webRtcManager.remoteVideoTrack.collectAsStateWithLifecycle()
                val localVideo by webRtcManager.localVideoTrackFlow.collectAsStateWithLifecycle()
                val audioRoute by webRtcManager.audioRoute.collectAsStateWithLifecycle()

                BackHandler { hangupCall() }

                LaunchedEffect(Unit) {
                    if (!permissions.allPermissionsGranted) {
                        permissions.launchMultiplePermissionRequest()
                    }
                }

                // Outgoing ringback tone ONLY after callee genuinely acknowledges ringing
                LaunchedEffect(isIncoming, callStatusLabel, connState, coreMediaGranted) {
                    if (
                        !isIncoming &&
                        coreMediaGranted &&
                        callStatusLabel == strings.ringingCall &&
                        connState != PeerConnection.PeerConnectionState.CONNECTED
                    ) {
                        val toneGen = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull()
                        try {
                            while (isActive) {
                                runCatching { toneGen?.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1000) }
                                delay(3500L)
                            }
                        } finally {
                            runCatching {
                                toneGen?.stopTone()
                                toneGen?.release()
                            }
                        }
                    }
                }

                LaunchedEffect(coreMediaGranted) {
                    if (!coreMediaGranted) return@LaunchedEffect

                    ContextCompat.startForegroundService(
                        this@CallActivity,
                        Intent(this@CallActivity, CallForegroundService::class.java).apply {
                            putExtra("peerName", peerName)
                            putExtra("isVideo", isVideo)
                        }
                    )

                    // Real ICE-restart signaling: when the WebRTC layer reports the media path
                    // died (network switch, ICE FAILED/DISCONNECTED), the caller produces a fresh
                    // restart offer into the call doc; the callee asks the caller to do so.
                    webRtcManager.onIceRestartNeeded = {
                        if (!isFinishingCall) {
                            if (!isIncoming) {
                                produceRestartOffer()
                            } else if (initialCallId.isNotBlank()) {
                                lifecycleScope.launch {
                                    callRepository.requestIceRestart(initialCallId)
                                }
                            }
                        }
                    }

                    CallSignalingErrors.clear()

                    webRtcManager.startSession(isVideo) { candidate ->
                        val cid = activeCallId
                        if (cid.isNotBlank()) {
                            lifecycleScope.launch {
                                callRepository.addIceCandidate(
                                    callId = cid,
                                    sdpMid = candidate.sdpMid,
                                    sdpMLineIndex = candidate.sdpMLineIndex,
                                    candidate = candidate.sdp,
                                    fromCaller = !isIncoming
                                )
                            }
                        } else {
                            synchronized(pendingLocalCandidates) {
                                pendingLocalCandidates.add(candidate)
                            }
                        }
                    }

                    if (!isIncoming) {
                        webRtcManager.createOffer { offerSdp ->
                            lifecycleScope.launch {
                                val targetUser = WesalUser(
                                    uid = peerUid,
                                    displayName = peerName,
                                    photoUrl = peerAvatar,
                                    isVerified = peerVerified
                                )
                                callRepository.initiateCall(targetUser, isVideo, offerSdp)
                                    .onSuccess { newCallId ->
                                        activeCallId = newCallId
                                        val queued = synchronized(pendingLocalCandidates) {
                                            pendingLocalCandidates.toList().also { pendingLocalCandidates.clear() }
                                        }
                                        queued.forEach { c ->
                                            lifecycleScope.launch {
                                                callRepository.addIceCandidate(
                                                    callId = newCallId,
                                                    sdpMid = c.sdpMid,
                                                    sdpMLineIndex = c.sdpMLineIndex,
                                                    candidate = c.sdp,
                                                    fromCaller = true
                                                )
                                            }
                                        }
                                        listenToCallAndCandidates(
                                            callId = newCallId,
                                            isCaller = true,
                                            ringingText = strings.ringingCall,
                                            connectedText = strings.connectedCall,
                                            rejectedText = strings.callRejected,
                                            missedText = strings.callMissed
                                        ) { status ->
                                            callStatusLabel = status
                                        }
                                    }
                                    .onFailure {
                                        callStatusLabel = if (strings.isEnglish) "Unable to start call" else "تعذر بدء المكالمة"
                                    }
                            }
                        }
                    } else {
                        // Bounded wait for the caller's offer: if the call doc / offer never
                        // becomes readable (rules denial, caller write failed), fail visibly
                        // and end instead of waiting forever on this screen.
                        val callDoc = withTimeoutOrNull(OFFER_WAIT_TIMEOUT_MS) {
                            callRepository.observeCall(initialCallId)
                                .filter { it != null && it.offerSdp.isNotBlank() }
                                .firstOrNull()
                        }
                        if (callDoc != null) {
                            webRtcManager.setRemoteOfferAndCreateAnswer(callDoc.offerSdp) { answerSdp ->
                                lifecycleScope.launch {
                                    callRepository.submitAnswer(initialCallId, answerSdp)
                                    listenToCallAndCandidates(
                                        callId = initialCallId,
                                        isCaller = false,
                                        ringingText = strings.ringingCall,
                                        connectedText = strings.connectedCall,
                                        rejectedText = strings.callRejected,
                                        missedText = strings.callMissed
                                    ) { status ->
                                        callStatusLabel = status
                                    }
                                }
                            }
                        } else {
                            // Offer never arrived within the timeout: tell the user why and
                            // end the call (also release the caller from waiting on us).
                            callStatusLabel = CallSignalingErrors.SERVER_ERROR_MESSAGE
                            if (initialCallId.isNotBlank()) {
                                callRepository.updateCallStatus(initialCallId, CallStatus.ENDED, 0L)
                            }
                            delay(1800L)
                            finishCallLocally()
                        }
                    }
                }

                LaunchedEffect(connState) {
                    if (connState == PeerConnection.PeerConnectionState.CONNECTED) {
                        while (true) {
                            delay(1000L)
                            durationSeconds++
                            startedSeconds = durationSeconds
                            callStatusLabel = DateTimeUtils.formatDurationSeconds(durationSeconds)
                        }
                    } else if (connState == PeerConnection.PeerConnectionState.FAILED) {
                        callStatusLabel = if (strings.isEnglish) "Connection lost — retrying..." else "انقطع الاتصال — جاري إعادة المحاولة..."
                    }
                }

                // ICE-level drops (e.g. Wi-Fi ↔ mobile switch) now trigger a real restart in
                // WebRtcCallManager; surface that honestly instead of a frozen "connected" timer.
                LaunchedEffect(iceState, connState, turnConfigured) {
                    if (connState != PeerConnection.PeerConnectionState.CONNECTED &&
                        (iceState == PeerConnection.IceConnectionState.DISCONNECTED ||
                            iceState == PeerConnection.IceConnectionState.FAILED)
                    ) {
                        callStatusLabel = when {
                            !turnConfigured && iceState == PeerConnection.IceConnectionState.FAILED ->
                                if (strings.isEnglish) {
                                    "Connection lost — retrying... (no TURN server in this build)"
                                } else {
                                    "انقطع الاتصال — جاري إعادة المحاولة... (لا يوجد خادم TURN في هذه النسخة)"
                                }
                            else ->
                                if (strings.isEnglish) "Connection lost — retrying..." else "انقطع الاتصال — جاري إعادة المحاولة..."
                        }
                    }
                }

                // Fatal WebRTC session-setup failure (factory/PeerConnection/SDP): show the
                // error and end the call instead of hanging wordlessly on "connecting…".
                LaunchedEffect(sessionError) {
                    val error = sessionError
                    if (error != null && !isFinishingCall) {
                        isFinishingCall = true
                        callStatusLabel = error
                        delay(1800L)
                        finishCallLocally()
                    }
                }

                // Signaling (Firestore) failure surfaced by CallRepositoryImpl: show it as
                // the call status while the call is not yet connected, instead of a hang.
                LaunchedEffect(signalingError) {
                    val error = signalingError
                    if (error != null &&
                        connState != PeerConnection.PeerConnectionState.CONNECTED &&
                        !isFinishingCall
                    ) {
                        callStatusLabel = error
                    }
                }

                LaunchedEffect(isIncoming, connState) {
                    if (!isIncoming && connState != PeerConnection.PeerConnectionState.CONNECTED) {
                        delay(45_000L)
                        if (webRtcManager.connectionState.value != PeerConnection.PeerConnectionState.CONNECTED && !isFinishingCall) {
                            callStatusLabel = strings.callMissed
                            if (activeCallId.isNotBlank()) {
                                callRepository.updateCallStatus(activeCallId, CallStatus.MISSED, 0L)
                            }
                            delay(1100L)
                            finishCallLocally()
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize().background(palette.background)) {
                    if (!coreMediaGranted) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Icon(Icons.Default.MicOff, contentDescription = null, tint = palette.error, modifier = Modifier.size(48.dp))
                            Text(
                                text = if (strings.isEnglish) {
                                    if (isVideo) "Video call requires microphone & camera permission" else "Call requires microphone permission"
                                } else {
                                    if (isVideo) "تحتاج مكالمة الفيديو لصلاحية الميكروفون والكاميرا" else "تحتاج المكالمة لصلاحية الميكروفون"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.textPrimary
                            )
                            Button(
                                onClick = { permissions.launchMultiplePermissionRequest() },
                                colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onPrimary)
                            ) {
                                Text(if (strings.isEnglish) "Grant Permissions" else "منح الصلاحيات")
                            }
                            OutlinedButton(
                                onClick = {
                                    val settingsIntent = Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", packageName, null)
                                    )
                                    runCatching { startActivity(settingsIntent) }
                                }
                            ) {
                                Text(if (strings.isEnglish) "App Settings" else "فتح إعدادات التطبيق", color = palette.turquoise)
                            }
                        }
                    } else if (isVideo && remoteVideo != null) {
                        val remoteRenderer = remember(remoteVideo) {
                            SurfaceViewRenderer(this@CallActivity).apply {
                                init(webRtcManager.eglBase.eglBaseContext, null)
                            }
                        }
                        DisposableEffect(remoteVideo) {
                            remoteVideo?.addSink(remoteRenderer)
                            onDispose {
                                remoteVideo?.removeSink(remoteRenderer)
                                remoteRenderer.release()
                            }
                        }
                        AndroidView(
                            factory = { remoteRenderer },
                            modifier = Modifier.fillMaxSize()
                        )
                        Surface(
                            color = Color.Black.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(top = 48.dp, start = 16.dp)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                WesalNameWithBadge(
                                    name = peerName,
                                    isVerified = peerVerified,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White
                                )
                                Text(callStatusLabel, style = MaterialTheme.typography.labelMedium, color = palette.turquoise)
                                if (!turnConfigured && connState != PeerConnection.PeerConnectionState.CONNECTED) {
                                    TurnNotConfiguredHint(isEnglish = strings.isEnglish)
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            WesalAvatar(imageUrl = peerAvatar, name = peerName, size = 112.dp)
                            WesalNameWithBadge(
                                name = peerName,
                                isVerified = peerVerified,
                                style = MaterialTheme.typography.headlineSmall,
                                color = palette.textPrimary
                            )
                            Text(callStatusLabel, style = MaterialTheme.typography.titleMedium, color = palette.turquoise)
                            if (!turnConfigured && connState != PeerConnection.PeerConnectionState.CONNECTED) {
                                TurnNotConfiguredHint(isEnglish = strings.isEnglish)
                            }
                        }
                    }

                    if (isVideo && localVideo != null && !cameraOff) {
                        val localRenderer = remember(localVideo) {
                            SurfaceViewRenderer(this@CallActivity).apply {
                                init(webRtcManager.eglBase.eglBaseContext, null)
                                setZOrderMediaOverlay(true)
                                setMirror(true)
                            }
                        }
                        DisposableEffect(localVideo) {
                            localVideo?.addSink(localRenderer)
                            onDispose {
                                localVideo?.removeSink(localRenderer)
                                localRenderer.release()
                            }
                        }
                        AndroidView(
                            factory = { localRenderer },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 48.dp, end = 16.dp)
                                .size(width = 110.dp, height = 160.dp)
                                .clip(RoundedCornerShape(14.dp))
                        )
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(bottom = 36.dp, start = 20.dp, end = 20.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CallControlBtn(
                            icon = if (micMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            active = micMuted,
                            label = if (micMuted) (if (strings.isEnglish) "Muted" else "مكتوم") else (if (strings.isEnglish) "Mic" else "الميكروفون"),
                            onClick = {
                                micMuted = !micMuted
                                webRtcManager.setMicEnabled(!micMuted)
                            }
                        )
                        if (isVideo) {
                            CallControlBtn(
                                icon = if (cameraOff) Icons.Default.VideocamOff else Icons.Default.Videocam,
                                active = cameraOff,
                                label = strings.camera,
                                onClick = {
                                    cameraOff = !cameraOff
                                    webRtcManager.setCameraEnabled(!cameraOff)
                                }
                            )
                            CallControlBtn(
                                icon = Icons.Default.Cameraswitch,
                                active = false,
                                label = if (strings.isEnglish) "Flip" else "تبديل",
                                onClick = { webRtcManager.switchCamera() }
                            )
                        }
                        val (audioIcon, audioLabel) = when (audioRoute) {
                            CallAudioRoute.BLUETOOTH -> Icons.Default.BluetoothAudio to strings.bluetoothAudio
                            CallAudioRoute.SPEAKER -> Icons.Default.VolumeUp to strings.speakerAudio
                            CallAudioRoute.WIRED_HEADSET -> Icons.Default.Headset to (if (strings.isEnglish) "Headset" else "سماعة سلكية")
                            CallAudioRoute.EARPIECE -> Icons.Default.Hearing to strings.earpieceAudio
                        }
                        CallControlBtn(
                            icon = audioIcon,
                            active = audioRoute != CallAudioRoute.EARPIECE,
                            label = audioLabel,
                            onClick = { webRtcManager.cycleAudioRoute() }
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FloatingActionButton(
                                onClick = { hangupCall() },
                                containerColor = palette.error,
                                contentColor = Color.White,
                                shape = CircleShape,
                                modifier = Modifier.size(62.dp)
                            ) {
                                Icon(Icons.Default.CallEnd, contentDescription = null)
                            }
                            Text(
                                text = if (strings.isEnglish) "End" else "إنهاء",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textSecondary
                            )
                        }
                    }
                }
            }
        }
    }

    private fun listenToCallAndCandidates(
        callId: String,
        isCaller: Boolean,
        ringingText: String,
        connectedText: String,
        rejectedText: String,
        missedText: String,
        onStatus: (String) -> Unit
    ) {
        lifecycleScope.launch {
            callRepository.observeRemoteIceCandidates(callId, listenForCaller = !isCaller)
                .collect { (mid, idx, cand) ->
                    webRtcManager.addRemoteIceCandidate(mid, idx, cand)
                }
        }
        lifecycleScope.launch {
            var appliedAnswerGeneration = -1L
            var appliedOfferGeneration = 0L // generation 0 is the initial offer, applied before this listener starts (callee)
            var handledRestartRequestAt = 0L
            callRepository.observeCall(callId).collect { call ->
                if (call == null || isFinishingCall) return@collect
                if (isCaller && (call.status == CallStatus.RINGING || call.calleeRingingAck) &&
                    appliedAnswerGeneration < 0L && webRtcManager.connectionState.value != PeerConnection.PeerConnectionState.CONNECTED
                ) {
                    onStatus(ringingText)
                }
                // Apply the answer for the CURRENT offer generation only — after an ICE restart
                // the callee writes a fresh answer for the new generation, and it must be applied
                // too (the old one-shot flag silently dropped every renegotiated answer).
                if (isCaller && call.answerSdp.isNotBlank() &&
                    appliedAnswerGeneration != call.iceRestartGeneration
                ) {
                    appliedAnswerGeneration = call.iceRestartGeneration
                    webRtcManager.setRemoteAnswer(call.answerSdp)
                    onStatus(connectedText)
                }
                // Callee asked (through the doc) for a restart: caller produces the offer.
                if (isCaller && call.iceRestartRequestedAt > handledRestartRequestAt &&
                    call.iceRestartRequestedAt > 0L
                ) {
                    handledRestartRequestAt = call.iceRestartRequestedAt
                    produceRestartOffer()
                }
                // Caller signalled a restart offer: callee re-answers it.
                if (!isCaller && call.iceRestartGeneration > appliedOfferGeneration &&
                    call.offerSdp.isNotBlank()
                ) {
                    appliedOfferGeneration = call.iceRestartGeneration
                    webRtcManager.setRemoteOfferAndCreateAnswer(call.offerSdp) { answerSdp ->
                        lifecycleScope.launch {
                            callRepository.submitAnswer(callId, answerSdp)
                        }
                    }
                }
                when (call.status) {
                    CallStatus.REJECTED -> {
                        isFinishingCall = true
                        onStatus(rejectedText)
                        delay(1200L)
                        finishCallLocally()
                    }
                    CallStatus.MISSED -> {
                        isFinishingCall = true
                        onStatus(missedText)
                        delay(1200L)
                        finishCallLocally()
                    }
                    CallStatus.ENDED -> {
                        isFinishingCall = true
                        finishCallLocally()
                    }
                    else -> Unit
                }
            }
        }
    }

    /** Caller side of an ICE restart: fresh offer (IceRestart=true) written to the call doc. */
    private fun produceRestartOffer() {
        val cid = activeCallId
        if (cid.isBlank() || isFinishingCall) return
        webRtcManager.createRestartOffer { restartSdp ->
            lifecycleScope.launch {
                callRepository.submitRestartOffer(cid, restartSdp)
            }
        }
    }

    private fun hangupCall() {
        if (isFinishingCall) return
        isFinishingCall = true
        lifecycleScope.launch {
            if (activeCallId.isNotBlank()) {
                callRepository.updateCallStatus(activeCallId, CallStatus.ENDED, startedSeconds)
            }
            finishCallLocally()
        }
    }

    private fun finishCallLocally() {
        webRtcManager.endSession()
        stopService(Intent(this, CallForegroundService::class.java))
        finish()
    }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        webRtcManager.endSession()
        stopService(Intent(this, CallForegroundService::class.java))
        super.onDestroy()
    }

    companion object {
        private const val OFFER_WAIT_TIMEOUT_MS = 30_000L
        private const val EXTRA_IS_INCOMING = "is_incoming"
        private const val EXTRA_CALL_ID = "call_id"
        private const val EXTRA_PEER_UID = "peer_uid"
        private const val EXTRA_PEER_NAME = "peer_name"
        private const val EXTRA_PEER_AVATAR = "peer_avatar"
        private const val EXTRA_PEER_VERIFIED = "peer_verified"
        private const val EXTRA_IS_VIDEO = "is_video"

        fun createOutgoingIntent(context: Context, peer: WesalUser, isVideo: Boolean): Intent =
            Intent(context, CallActivity::class.java).apply {
                putExtra(EXTRA_IS_INCOMING, false)
                putExtra(EXTRA_PEER_UID, peer.uid)
                putExtra(
                    EXTRA_PEER_NAME,
                    peer.displayName.ifBlank {
                        peer.publicId.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "WESAL User"
                    }
                )
                putExtra(EXTRA_PEER_AVATAR, peer.photoUrl)
                putExtra(EXTRA_PEER_VERIFIED, peer.isVerified)
                putExtra(EXTRA_IS_VIDEO, isVideo)
            }

        fun createIncomingAnswerIntent(
            context: Context,
            callId: String,
            peerName: String,
            peerAvatar: String,
            isVideo: Boolean,
            peerVerified: Boolean = false,
            peerUid: String = ""
        ): Intent = Intent(context, CallActivity::class.java).apply {
            putExtra(EXTRA_IS_INCOMING, true)
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_PEER_UID, peerUid)
            putExtra(EXTRA_PEER_NAME, peerName)
            putExtra(EXTRA_PEER_AVATAR, peerAvatar)
            putExtra(EXTRA_PEER_VERIFIED, peerVerified)
            putExtra(EXTRA_IS_VIDEO, isVideo)
        }
    }
}

/**
 * Persistent pre-call hint shown from the moment a call starts while no TURN server is
 * configured in this build (previously the missing TURN was only mentioned after ICE had
 * already FAILED). Disappears once the call actually connects.
 */
@Composable
private fun TurnNotConfiguredHint(isEnglish: Boolean) {
    val palette = WesalColors.current
    Text(
        text = if (isEnglish) {
            "TURN service is not configured — audio may not work over mobile data"
        } else {
            "خدمة TURN غير مضبوطة — قد لا يعمل الصوت عبر بيانات الهاتف"
        },
        style = MaterialTheme.typography.labelSmall,
        color = palette.warning
    )
}

@Composable
private fun CallControlBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    label: String,
    onClick: () -> Unit
) {
    val palette = WesalColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilledIconButton(
            onClick = onClick,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (active) palette.turquoise else palette.surfaceElevated,
                contentColor = if (active) palette.onPrimary else palette.textPrimary
            ),
            modifier = Modifier.size(52.dp)
        ) {
            Icon(icon, contentDescription = label)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary
        )
    }
}
