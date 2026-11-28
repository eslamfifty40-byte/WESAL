package com.wesal.messenger.calls

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.theme.WesalTheme
import com.wesal.messenger.core.util.CallRingLogic
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.data.local.AppUserSettings
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.data.repository.CallSignalingErrors
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.presentation.components.WesalAvatar
import com.wesal.messenger.presentation.components.WesalNameWithBadge
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.webrtc.PeerConnection
import org.webrtc.SurfaceViewRenderer
import javax.inject.Inject

/**
 * Round 20: the call UI is now a DETACHABLE VIEW on the session owned
 * by [CallSessionController]. This activity starts a session when none
 * is live (fresh outgoing / incoming-answer intents) or attaches to
 * the live one (notification rejoin, or any call intent opened while a
 * call is already running). Leaving the screen — Back, or the activity
 * being destroyed for any reason — detaches the view ONLY: the call
 * keeps running in the controller + foreground service. The ONLY way
 * this screen ends a call is the explicit end-call button, which goes
 * through [CallSessionController.endCall].
 */
@AndroidEntryPoint
class CallActivity : AppCompatActivity() {

    @Inject lateinit var webRtcManager: WebRtcCallManager
    @Inject lateinit var callController: CallSessionController
    @Inject lateinit var prefs: UserPreferencesDataStore

    /**
     * Round 11 (addendum 1b): proximity wake lock for the in-call UI.
     * Acquired while this UI is attached; released in onDestroy (a UI
     * concern — releasing it never touches the call itself).
     */
    private var proximityWakeLock: android.os.PowerManager.WakeLock? = null

    private fun acquireProximityLock() {
        if (proximityWakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isWakeLockLevelSupported(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                return
            }
            val lock = pm.newWakeLock(
                android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "wesal:call-proximity"
            )
            // Bounded as a safety net; onDestroy releases it on every exit path.
            lock.acquire(4 * 60 * 60 * 1000L)
            proximityWakeLock = lock
        }
    }

    private fun releaseProximityLock() {
        runCatching {
            if (proximityWakeLock?.isHeld == true) proximityWakeLock?.release()
        }
        proximityWakeLock = null
    }

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Strict screenshot & screen-recording protection during active calls
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // Proximity governs the screen while the UI is attached; only
        // devices without proximity support keep the old keep-screen-on.
        acquireProximityLock()
        if (proximityWakeLock == null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val isRejoin = intent.getBooleanExtra(EXTRA_REJOIN, false)
        val isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, false)
        val initialCallId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
        val peerUid = intent.getStringExtra(EXTRA_PEER_UID).orEmpty()
        // Round 9 (item 5): a present-but-blank peer name must fall back.
        val intentPeerName = intent.getStringExtra(EXTRA_PEER_NAME)?.takeIf { it.isNotBlank() } ?: "WESAL User"
        val intentPeerAvatar = intent.getStringExtra(EXTRA_PEER_AVATAR).orEmpty()
        val intentPeerVerified = intent.getBooleanExtra(EXTRA_PEER_VERIFIED, false)
        val intentIsVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)

        // A rejoin tap with no live session behind it (the call ended
        // while the notification was still up): nothing to attach to.
        if (isRejoin && callController.session.value == null) {
            finish()
            return
        }

        setContent {
            val settings by prefs.settingsFlow.collectAsStateWithLifecycle(initialValue = AppUserSettings())
            WesalTheme(
                darkTheme = settings.darkTheme,
                languageTag = settings.languageTag,
                fontScale = settings.fontScale
            ) {
                val palette = WesalColors.current
                val strings = LocalWesalStrings.current

                val activeSession by callController.session.collectAsStateWithLifecycle()
                val isVideo = activeSession?.isVideo ?: intentIsVideo
                val sessionIsIncoming = activeSession?.isIncoming ?: isIncoming
                // Display identity: the live session wins; before a
                // session exists (permission gate), the intent extras.
                val peerName = activeSession?.peerName ?: intentPeerName
                val peerAvatar = activeSession?.peerAvatar ?: intentPeerAvatar
                val peerVerified = activeSession?.peerVerified ?: intentPeerVerified

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
                // A live session was already granted its permissions at
                // start; the gate below applies only before a start.
                val sessionLive = activeSession != null
                val mediaReady = coreMediaGranted || sessionLive

                var micMuted by remember { mutableStateOf(false) }
                var cameraOff by remember { mutableStateOf(!isVideo) }
                var durationSeconds by remember { mutableLongStateOf(0L) }
                // Start strictly with CONNECTING ("جاري الاتصال...") — switches to RINGING only on real callee ACK
                var callStatusLabel by remember(strings.isEnglish) {
                    mutableStateOf(strings.connectingCall)
                }
                var closingByTermination by remember { mutableStateOf(false) }

                val connState by webRtcManager.connectionState.collectAsStateWithLifecycle()
                val iceState by webRtcManager.iceConnectionState.collectAsStateWithLifecycle()
                val turnConfigured by webRtcManager.turnConfigured.collectAsStateWithLifecycle()
                val signalingError by CallSignalingErrors.error.collectAsStateWithLifecycle()
                val remoteVideo by webRtcManager.remoteVideoTrack.collectAsStateWithLifecycle()
                val localVideo by webRtcManager.localVideoTrackFlow.collectAsStateWithLifecycle()
                val audioRoute by webRtcManager.audioRoute.collectAsStateWithLifecycle()
                // Round 12 (addendum B): camera-switch state — the count
                // re-attaches the local renderer after each switch, the
                // facing drives preview mirroring, and a failed switch
                // surfaces one short human message.
                val cameraSwitchCount by webRtcManager.cameraSwitchCount.collectAsStateWithLifecycle()
                val isFrontCamera by webRtcManager.isFrontCamera.collectAsStateWithLifecycle()
                // Round 18: while a switch is in flight the flip button
                // spins and disables itself.
                val isCameraSwitching by webRtcManager.isCameraSwitching.collectAsStateWithLifecycle()
                val cameraMessage by webRtcManager.cameraMessage.collectAsStateWithLifecycle()
                // Round 20: screen-share state (manager-owned).
                val isScreenSharing by webRtcManager.isScreenSharing.collectAsStateWithLifecycle()

                val phase by callController.phase.collectAsStateWithLifecycle()
                val answeredAtMs by callController.answeredAtMs.collectAsStateWithLifecycle()
                val termination by callController.termination.collectAsStateWithLifecycle()
                val endMessage by callController.endMessage.collectAsStateWithLifecycle()

                LaunchedEffect(cameraMessage) {
                    val msg = cameraMessage
                    if (msg != null) {
                        android.widget.Toast.makeText(this@CallActivity, msg, android.widget.Toast.LENGTH_SHORT).show()
                        webRtcManager.clearCameraMessage()
                    }
                }

                // Round 20: Back DETACHES the call UI — the call keeps
                // running (owner's explicit r19 verdict). Only the
                // end-call button ends a call.
                BackHandler { finish() }

                LaunchedEffect(Unit) {
                    if (!sessionLive && !permissions.allPermissionsGranted) {
                        permissions.launchMultiplePermissionRequest()
                    }
                }

                // Start a session when this screen was opened for a
                // fresh call and none is live. If one IS live, this
                // screen simply attaches to it (no duplicate session).
                var startRequested by remember { mutableStateOf(false) }
                LaunchedEffect(coreMediaGranted, sessionLive) {
                    if (!coreMediaGranted || sessionLive || startRequested) return@LaunchedEffect
                    startRequested = true
                    if (isIncoming) {
                        callController.startIncomingAnswer(
                            callId = initialCallId,
                            peerUid = peerUid,
                            peerName = intentPeerName,
                            peerAvatar = intentPeerAvatar,
                            peerVerified = intentPeerVerified,
                            isVideo = intentIsVideo
                        )
                    } else {
                        callController.startOutgoing(
                            peer = WesalUser(
                                uid = peerUid,
                                displayName = intentPeerName,
                                photoUrl = intentPeerAvatar,
                                isVerified = intentPeerVerified
                            ),
                            isVideo = intentIsVideo
                        )
                    }
                }

                // The generation of the session this screen is showing,
                // captured when the session first appears — termination
                // events of OTHER (older) sessions must never close it.
                var attachedGeneration by remember { mutableStateOf(-1L) }
                LaunchedEffect(sessionLive) {
                    if (sessionLive && attachedGeneration == -1L) {
                        attachedGeneration = callController.currentGeneration
                    }
                }

                // The session ended (by the peer, a deadline, a failure,
                // or the end button): show the farewell label briefly —
                // with the same timings the pre-controller UI used —
                // then close. When no UI is attached this never runs and
                // the controller has already cleaned everything up.
                LaunchedEffect(termination, attachedGeneration) {
                    val t = termination ?: return@LaunchedEffect
                    if (attachedGeneration == -1L || t.generation != attachedGeneration) return@LaunchedEffect
                    if (closingByTermination) return@LaunchedEffect
                    closingByTermination = true
                    when (t.reason) {
                        CallEndReason.PEER_ENDED, CallEndReason.LOCAL_HANGUP -> finish()
                        CallEndReason.REJECTED -> {
                            callStatusLabel = strings.callRejected
                            delay(1200L)
                            finish()
                        }
                        CallEndReason.MISSED -> {
                            callStatusLabel = strings.callMissed
                            delay(1200L)
                            finish()
                        }
                        CallEndReason.SETUP_FAILED -> {
                            callStatusLabel = if (strings.isEnglish) "Could not connect — please try again" else "تعذر الاتصال — حاول مرة أخرى"
                            delay(1500L)
                            finish()
                        }
                        CallEndReason.OFFER_TIMEOUT -> {
                            callStatusLabel = CallSignalingErrors.SERVER_ERROR_MESSAGE
                            delay(1800L)
                            finish()
                        }
                        CallEndReason.MEDIA_FAILURE -> {
                            callStatusLabel = if (strings.isEnglish) {
                                "Could not complete the call — check your internet connection and try again"
                            } else {
                                "تعذر إتمام الاتصال — تحقق من الإنترنت وحاول مرة أخرى"
                            }
                            delay(2200L)
                            finish()
                        }
                        CallEndReason.SESSION_ERROR -> {
                            callStatusLabel = endMessage ?: if (strings.isEnglish) {
                                "Could not start the call on this device"
                            } else {
                                "تعذر بدء المكالمة على هذا الجهاز"
                            }
                            delay(1800L)
                            finish()
                        }
                    }
                }

                // Status line from the session phase (the controller's
                // listener owns the truth now): CONNECTING until the
                // callee ack, RINGING on the ack, ANSWERED once the
                // answer lands — the duration effect below takes over
                // the label once media is CONNECTED.
                LaunchedEffect(phase) {
                    when (phase) {
                        CallPhase.CONNECTING -> callStatusLabel = strings.connectingCall
                        CallPhase.RINGING -> callStatusLabel = strings.ringingCall
                        CallPhase.ANSWERED -> {
                            if (connState != PeerConnection.PeerConnectionState.CONNECTED) {
                                callStatusLabel = strings.connectedCall
                            }
                        }
                    }
                }

                // Round 11 (item 3e + addendum 2b): the ringback starts
                // the moment the outgoing call is placed and keeps
                // playing through BOTH honest states («جاري الاتصال» and
                // «يرن») until the call connects or this screen goes
                // away — WhatsApp-style. (Round 20: the call itself no
                // longer goes away with the screen; the ringback is a
                // UI sound and stops with the UI, while the callee's
                // phone keeps ringing via the listener service.)
                var hasConnected by remember { mutableStateOf(false) }
                LaunchedEffect(sessionIsIncoming, mediaReady, hasConnected) {
                    if (!CallRingLogic.shouldPlayRingback(
                            isIncoming = sessionIsIncoming,
                            mediaGranted = mediaReady,
                            hasConnected = hasConnected,
                            peerConnected = false
                        )
                    ) {
                        return@LaunchedEffect
                    }
                    var toneGen = runCatching {
                        ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
                    }.getOrNull()
                    try {
                        while (isActive) {
                            if (!CallRingLogic.shouldPlayRingback(
                                    isIncoming = sessionIsIncoming,
                                    mediaGranted = mediaReady,
                                    hasConnected = hasConnected,
                                    peerConnected = webRtcManager.connectionState.value ==
                                        PeerConnection.PeerConnectionState.CONNECTED
                                )
                            ) {
                                break
                            }
                            // Round 12 (addendum A): a failed startTone
                            // recreates the generator and retries within
                            // the same cycle (a video session start used
                            // to kill the first generator and ring once).
                            var played = toneGen?.let {
                                runCatching {
                                    it.startTone(ToneGenerator.TONE_SUP_RINGTONE, CallRingLogic.RINGBACK_TONE_DURATION_MS)
                                }.getOrDefault(false)
                            } ?: false
                            if (!played) {
                                runCatching { toneGen?.release() }
                                toneGen = runCatching {
                                    ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
                                }.getOrNull()
                                val fresh = toneGen
                                if (fresh != null) {
                                    played = runCatching {
                                        fresh.startTone(ToneGenerator.TONE_SUP_RINGTONE, CallRingLogic.RINGBACK_TONE_DURATION_MS)
                                    }.getOrDefault(false)
                                }
                                if (!played) {
                                    android.util.Log.w("WesalCalls", "Ringback tone could not be started on the current audio route")
                                }
                            }
                            delay(CallRingLogic.RINGBACK_CYCLE_DELAY_MS)
                        }
                    } finally {
                        runCatching {
                            toneGen?.stopTone()
                            toneGen?.release()
                        }
                    }
                }

                LaunchedEffect(connState) {
                    if (connState == PeerConnection.PeerConnectionState.CONNECTED) {
                        hasConnected = true
                    } else if (connState == PeerConnection.PeerConnectionState.FAILED) {
                        callStatusLabel = if (strings.isEnglish) "Connection lost — retrying..." else "انقطع الاتصال — جاري إعادة المحاولة..."
                    }
                }

                // R17 (G-01): the minutes count starts when the other
                // side ANSWERS, not when the media path happens to
                // connect, and the status line keeps the state word next
                // to the time («متصل • 01:23»). Round 20: the answer
                // timestamp is the controller's, so a re-attached UI
                // resumes the true elapsed count instead of restarting
                // at zero.
                LaunchedEffect(answeredAtMs, connState) {
                    val answeredAt = answeredAtMs
                    if (answeredAt == null && connState != PeerConnection.PeerConnectionState.CONNECTED) return@LaunchedEffect
                    if (answeredAt != null) {
                        durationSeconds = ((System.currentTimeMillis() - answeredAt) / 1000L).coerceAtLeast(0L)
                    }
                    if (connState == PeerConnection.PeerConnectionState.CONNECTED) {
                        callStatusLabel = DateTimeUtils.formatCallStatusWithDuration(strings.connectedCall, durationSeconds)
                    }
                    while (true) {
                        delay(1000L)
                        durationSeconds++
                        if (webRtcManager.connectionState.value == PeerConnection.PeerConnectionState.CONNECTED) {
                            callStatusLabel = DateTimeUtils.formatCallStatusWithDuration(strings.connectedCall, durationSeconds)
                        }
                    }
                }

                // ICE-level drops trigger a real restart in
                // WebRtcCallManager; surface that honestly instead of a
                // frozen "connected" timer.
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

                // Signaling (Firestore) failure surfaced by
                // CallRepositoryImpl: show it as the call status while
                // the call is not yet connected, instead of a hang.
                LaunchedEffect(signalingError) {
                    val error = signalingError
                    if (error != null &&
                        connState != PeerConnection.PeerConnectionState.CONNECTED &&
                        !closingByTermination
                    ) {
                        callStatusLabel = error
                    }
                }

                // Round 20: screen-share permission prompt. Denial is
                // a toast and the call simply continues on camera; the
                // foreground service is (re)started WITH the
                // mediaProjection type before the prompt, as Android 14
                // requires it running before a projection is created.
                val screenShareLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    val data = result.data
                    if (result.resultCode == Activity.RESULT_OK && data != null) {
                        val metrics = resources.displayMetrics
                        val started = webRtcManager.startScreenShare(
                            resultData = data,
                            displayWidth = metrics.widthPixels,
                            displayHeight = metrics.heightPixels
                        )
                        if (started) {
                            cameraOff = false
                        } else {
                            android.widget.Toast.makeText(
                                this@CallActivity,
                                if (strings.isEnglish) "Could not start screen sharing" else "تعذر بدء مشاركة الشاشة",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        android.widget.Toast.makeText(
                            this@CallActivity,
                            if (strings.isEnglish) "Screen sharing needs your permission" else "مشاركة الشاشة تحتاج إذنك أولاً",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                Box(modifier = Modifier.fillMaxSize().background(palette.background)) {
                    if (!mediaReady) {
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
                            onDispose { localRenderer.release() }
                        }
                        // Round 12 (addendum B): a completed camera switch
                        // restarts the capturer's frame flow — re-attach
                        // the sink on every switch so the preview can never
                        // sit frozen on the old camera's last frame.
                        // Round 20: the same re-attach covers screen-share
                        // start/stop (the manager bumps the same count).
                        DisposableEffect(localVideo, cameraSwitchCount) {
                            localVideo?.addSink(localRenderer)
                            onDispose {
                                localVideo?.removeSink(localRenderer)
                            }
                        }
                        AndroidView(
                            factory = { localRenderer },
                            update = { renderer -> renderer.setMirror(isFrontCamera && !isScreenSharing) },
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
                                enabled = !isScreenSharing,
                                onClick = {
                                    cameraOff = !cameraOff
                                    webRtcManager.setCameraEnabled(!cameraOff)
                                }
                            )
                            CallControlBtn(
                                icon = Icons.Default.Cameraswitch,
                                active = false,
                                label = if (isCameraSwitching) {
                                    if (strings.isEnglish) "Switching" else "جاري التبديل"
                                } else {
                                    if (strings.isEnglish) "Flip" else "تبديل"
                                },
                                enabled = !isCameraSwitching && !isScreenSharing,
                                showProgress = isCameraSwitching,
                                onClick = { webRtcManager.switchCamera() }
                            )
                            // Round 20: screen share (1:1 video calls).
                            CallControlBtn(
                                icon = if (isScreenSharing) Icons.Default.StopScreenShare else Icons.Default.ScreenShare,
                                active = isScreenSharing,
                                label = if (isScreenSharing) {
                                    if (strings.isEnglish) "Stop share" else "إيقاف المشاركة"
                                } else {
                                    if (strings.isEnglish) "Share screen" else "مشاركة الشاشة"
                                },
                                onClick = {
                                    if (isScreenSharing) {
                                        webRtcManager.stopScreenShare()
                                    } else {
                                        ContextCompat.startForegroundService(
                                            this@CallActivity,
                                            Intent(this@CallActivity, CallForegroundService::class.java).apply {
                                                putExtra("peerName", peerName)
                                                putExtra("isVideo", true)
                                                putExtra(CallForegroundService.EXTRA_MEDIA_PROJECTION, true)
                                            }
                                        )
                                        val projectionManager =
                                            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                        screenShareLauncher.launch(projectionManager.createScreenCaptureIntent())
                                    }
                                }
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
                                onClick = {
                                    // Round 20: the ONLY end path — the
                                    // controller stamps ENDED and tears
                                    // the session down; this screen just
                                    // closes (the termination event would
                                    // close it anyway).
                                    callController.endCall()
                                    finish()
                                },
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

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        releaseProximityLock()
        // Round 20: NOTHING here touches the call. The session belongs
        // to CallSessionController; destroying this UI (Back, system,
        // re-creation) only detaches it. Teardown happens exclusively
        // in the controller's terminate().
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_IS_INCOMING = "is_incoming"
        private const val EXTRA_CALL_ID = "call_id"
        private const val EXTRA_PEER_UID = "peer_uid"
        private const val EXTRA_PEER_NAME = "peer_name"
        private const val EXTRA_PEER_AVATAR = "peer_avatar"
        private const val EXTRA_PEER_VERIFIED = "peer_verified"
        private const val EXTRA_IS_VIDEO = "is_video"
        private const val EXTRA_REJOIN = "rejoin"

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

        /**
         * Round 20: the ongoing-call notification's tap target. No call
         * data rides along — the activity attaches to whatever session
         * the controller currently owns (and closes itself if none).
         */
        fun createRejoinIntent(context: Context): Intent =
            Intent(context, CallActivity::class.java).apply {
                putExtra(EXTRA_REJOIN, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
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
    // Round 18: optional affordances (only the camera-flip button uses
    // them): disable the button and show a spinner while its action is
    // in flight. Defaults keep every other call site unchanged.
    enabled: Boolean = true,
    showProgress: Boolean = false,
    onClick: () -> Unit
) {
    val palette = WesalColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilledIconButton(
            onClick = onClick,
            enabled = enabled,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (active) palette.turquoise else palette.surfaceElevated,
                contentColor = if (active) palette.onPrimary else palette.textPrimary
            ),
            modifier = Modifier.size(52.dp)
        ) {
            if (showProgress) {
                CircularProgressIndicator(
                    color = palette.textPrimary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp)
                )
            } else {
                Icon(icon, contentDescription = label)
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary
        )
    }
}
