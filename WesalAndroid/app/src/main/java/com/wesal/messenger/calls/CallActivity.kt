package com.wesal.messenger.calls

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.wesal.messenger.core.theme.*
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.domain.model.CallStatus
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.CallRepository
import com.wesal.messenger.presentation.components.WesalAvatar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SurfaceViewRenderer
import javax.inject.Inject

@AndroidEntryPoint
class CallActivity : AppCompatActivity() {

    @Inject lateinit var webRtcManager: WebRtcCallManager
    @Inject lateinit var callRepository: CallRepository

    private var activeCallId: String = ""
    private var startedSeconds: Long = 0L
    private val pendingLocalCandidates = mutableListOf<IceCandidate>()

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, false)
        val initialCallId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
        val peerUid = intent.getStringExtra(EXTRA_PEER_UID).orEmpty()
        val peerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: "مستخدم وصال"
        val peerAvatar = intent.getStringExtra(EXTRA_PEER_AVATAR).orEmpty()
        val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
        activeCallId = initialCallId

        setContent {
            WesalTheme(darkTheme = true) {
                val permissions = rememberMultiplePermissionsState(
                    if (isVideo) {
                        listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                    } else {
                        listOf(Manifest.permission.RECORD_AUDIO)
                    }
                )

                var micMuted by remember { mutableStateOf(false) }
                var cameraOff by remember { mutableStateOf(!isVideo) }
                var speakerOn by remember { mutableStateOf(isVideo) }
                var durationSeconds by remember { mutableLongStateOf(0L) }
                var callStatusLabel by remember { mutableStateOf(if (isIncoming) "جاري الاتصال..." else "جاري الرنين...") }

                val connState by webRtcManager.connectionState.collectAsStateWithLifecycle()
                val remoteVideo by webRtcManager.remoteVideoTrack.collectAsStateWithLifecycle()
                val localVideo by webRtcManager.localVideoTrackFlow.collectAsStateWithLifecycle()

                LaunchedEffect(Unit) {
                    if (!permissions.allPermissionsGranted) {
                        permissions.launchMultiplePermissionRequest()
                    }
                }

                LaunchedEffect(permissions.allPermissionsGranted) {
                    if (!permissions.allPermissionsGranted) return@LaunchedEffect

                    ContextCompat.startForegroundService(
                        this@CallActivity,
                        Intent(this@CallActivity, CallForegroundService::class.java).apply {
                            putExtra("peerName", peerName)
                            putExtra("isVideo", isVideo)
                        }
                    )

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
                                val targetUser = WesalUser(uid = peerUid, displayName = peerName, photoUrl = peerAvatar)
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
                                        listenToCallAndCandidates(newCallId, isCaller = true) { status ->
                                            callStatusLabel = status
                                        }
                                    }
                                    .onFailure {
                                        callStatusLabel = "تعذر بدء المكالمة"
                                    }
                            }
                        }
                    } else {
                        val callDoc = callRepository.observeCall(initialCallId)
                            .filter { it != null && it.offerSdp.isNotBlank() }
                            .firstOrNull()
                        if (callDoc != null) {
                            webRtcManager.setRemoteOfferAndCreateAnswer(callDoc.offerSdp) { answerSdp ->
                                lifecycleScope.launch {
                                    callRepository.submitAnswer(initialCallId, answerSdp)
                                    listenToCallAndCandidates(initialCallId, isCaller = false) { status ->
                                        callStatusLabel = status
                                    }
                                }
                            }
                        } else {
                            callStatusLabel = "تعذر الاتصال بالمكالمة"
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
                        callStatusLabel = "انقطع الاتصال — جاري إعادة المحاولة..."
                    }
                }

                // End unanswered outgoing call cleanly after 45 seconds
                LaunchedEffect(isIncoming, connState) {
                    if (!isIncoming && connState != PeerConnection.PeerConnectionState.CONNECTED) {
                        delay(45_000L)
                        if (webRtcManager.connectionState.value != PeerConnection.PeerConnectionState.CONNECTED) {
                            if (activeCallId.isNotBlank()) {
                                callRepository.updateCallStatus(activeCallId, CallStatus.MISSED, 0L)
                            }
                            finishCallLocally()
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize().background(WesalDeepBlack)) {
                    if (!permissions.allPermissionsGranted) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Icon(Icons.Default.MicOff, contentDescription = null, tint = WesalError, modifier = Modifier.size(48.dp))
                            Text(
                                text = if (isVideo) "تحتاج مكالمة الفيديو لصلاحية الميكروفون والكاميرا" else "تحتاج المكالمة لصلاحية الميكروفون",
                                style = MaterialTheme.typography.titleMedium,
                                color = WesalTextPrimary
                            )
                            Button(
                                onClick = { permissions.launchMultiplePermissionRequest() },
                                colors = ButtonDefaults.buttonColors(containerColor = WesalEmerald, contentColor = WesalDeepBlack)
                            ) {
                                Text("منح الصلاحيات")
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
                                Text("فتح إعدادات التطبيق", color = WesalTurquoise)
                            }
                        }
                    } else if (isVideo && remoteVideo != null) {
                        val remoteRenderer = remember {
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
                                Text(peerName, style = MaterialTheme.typography.titleMedium, color = WesalTextPrimary)
                                Text(callStatusLabel, style = MaterialTheme.typography.labelMedium, color = WesalTurquoise)
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            WesalAvatar(imageUrl = peerAvatar, name = peerName, size = 112.dp)
                            Text(peerName, style = MaterialTheme.typography.headlineSmall, color = WesalTextPrimary)
                            Text(callStatusLabel, style = MaterialTheme.typography.titleMedium, color = WesalTurquoise)
                        }
                    }

                    if (isVideo && localVideo != null && !cameraOff) {
                        val localRenderer = remember {
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
                            onClick = {
                                micMuted = !micMuted
                                webRtcManager.setMicEnabled(!micMuted)
                            }
                        )
                        if (isVideo) {
                            CallControlBtn(
                                icon = if (cameraOff) Icons.Default.VideocamOff else Icons.Default.Videocam,
                                active = cameraOff,
                                onClick = {
                                    cameraOff = !cameraOff
                                    webRtcManager.setCameraEnabled(!cameraOff)
                                }
                            )
                            CallControlBtn(
                                icon = Icons.Default.Cameraswitch,
                                active = false,
                                onClick = { webRtcManager.switchCamera() }
                            )
                        }
                        CallControlBtn(
                            icon = if (speakerOn) Icons.Default.VolumeUp else Icons.Default.Hearing,
                            active = speakerOn,
                            onClick = {
                                speakerOn = !speakerOn
                                webRtcManager.setSpeakerphone(speakerOn)
                            }
                        )
                        FloatingActionButton(
                            onClick = { hangupCall() },
                            containerColor = WesalError,
                            contentColor = Color.White,
                            shape = CircleShape,
                            modifier = Modifier.size(62.dp)
                        ) {
                            Icon(Icons.Default.CallEnd, contentDescription = "إنهاء")
                        }
                    }
                }
            }
        }
    }

    private fun listenToCallAndCandidates(callId: String, isCaller: Boolean, onStatus: (String) -> Unit) {
        lifecycleScope.launch {
            callRepository.observeRemoteIceCandidates(callId, listenForCaller = !isCaller)
                .collect { (mid, idx, cand) ->
                    webRtcManager.addRemoteIceCandidate(mid, idx, cand)
                }
        }
        lifecycleScope.launch {
            var answerApplied = false
            callRepository.observeCall(callId).collect { call ->
                if (call == null) return@collect
                if (isCaller && !answerApplied && call.answerSdp.isNotBlank()) {
                    answerApplied = true
                    webRtcManager.setRemoteAnswer(call.answerSdp)
                    onStatus("متصل")
                }
                if (call.status == CallStatus.ENDED || call.status == CallStatus.REJECTED) {
                    finishCallLocally()
                }
            }
        }
    }

    private fun hangupCall() {
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
        webRtcManager.endSession()
        stopService(Intent(this, CallForegroundService::class.java))
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_IS_INCOMING = "is_incoming"
        private const val EXTRA_CALL_ID = "call_id"
        private const val EXTRA_PEER_UID = "peer_uid"
        private const val EXTRA_PEER_NAME = "peer_name"
        private const val EXTRA_PEER_AVATAR = "peer_avatar"
        private const val EXTRA_IS_VIDEO = "is_video"

        fun createOutgoingIntent(context: Context, peer: WesalUser, isVideo: Boolean): Intent =
            Intent(context, CallActivity::class.java).apply {
                putExtra(EXTRA_IS_INCOMING, false)
                putExtra(EXTRA_PEER_UID, peer.uid)
                putExtra(EXTRA_PEER_NAME, peer.displayName.ifBlank { peer.phoneNumber })
                putExtra(EXTRA_PEER_AVATAR, peer.photoUrl)
                putExtra(EXTRA_IS_VIDEO, isVideo)
            }

        fun createIncomingAnswerIntent(
            context: Context,
            callId: String,
            peerName: String,
            peerAvatar: String,
            isVideo: Boolean
        ): Intent = Intent(context, CallActivity::class.java).apply {
            putExtra(EXTRA_IS_INCOMING, true)
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_PEER_NAME, peerName)
            putExtra(EXTRA_PEER_AVATAR, peerAvatar)
            putExtra(EXTRA_IS_VIDEO, isVideo)
        }
    }
}

@Composable
private fun CallControlBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit
) {
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (active) WesalTurquoise else WesalSurfaceElevated,
            contentColor = if (active) WesalDeepBlack else WesalTextPrimary
        ),
        modifier = Modifier.size(52.dp)
    ) {
        Icon(icon, contentDescription = null)
    }
}
