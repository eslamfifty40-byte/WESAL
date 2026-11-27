package com.wesal.messenger.calls

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.wesal.messenger.BuildConfig
import com.wesal.messenger.core.network.NetworkMonitor
import com.wesal.messenger.core.util.CameraSwitchStateMachine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import javax.inject.Inject
import javax.inject.Singleton

enum class CallAudioRoute {
    EARPIECE,
    SPEAKER,
    BLUETOOTH,
    WIRED_HEADSET
}

@Singleton
class WebRtcCallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkMonitor: NetworkMonitor
) {
    val eglBase: EglBase = EglBase.create()

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localVideoSource: VideoSource? = null
    private var localAudioSource: AudioSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteDescriptionSet = false
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var scoReceiver: BroadcastReceiver? = null
    private var isVideoSession = false

    private val _connectionState = MutableStateFlow(PeerConnection.PeerConnectionState.NEW)
    val connectionState: StateFlow<PeerConnection.PeerConnectionState> = _connectionState.asStateFlow()

    private val _iceConnectionState = MutableStateFlow(PeerConnection.IceConnectionState.NEW)
    val iceConnectionState: StateFlow<PeerConnection.IceConnectionState> = _iceConnectionState.asStateFlow()

    /** True when at least one TURN server was configured (from BuildConfig, i.e. CI secrets / gradle properties). */
    private val _turnConfigured = MutableStateFlow(false)
    val turnConfigured: StateFlow<Boolean> = _turnConfigured.asStateFlow()

    /**
     * Fatal session-setup error (factory unavailable, createPeerConnection returned null,
     * SDP creation/apply failed). CallActivity collects this and ends the call with the
     * message visible, instead of the old behaviour: a silent stall on "connecting…"
     * until the no-answer timer fired.
     */
    private val _sessionError = MutableStateFlow<String?>(null)
    val sessionError: StateFlow<String?> = _sessionError.asStateFlow()

    /**
     * Set by CallActivity. Invoked when ICE needs a real restart: the caller side must create a
     * restart offer and signal it; the callee side must ask the caller (via the call doc) to do so.
     * Invoked at most once per disconnect episode (see [triggerIceRestart]).
     */
    @Volatile
    var onIceRestartNeeded: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingIceRestartRunnable: Runnable? = null
    @Volatile private var iceRestartSignalledForEpisode = false
    @Volatile private var iceRestartAwaitingRenegotiation = false
    private var networkMonitorJob: Job? = null
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val _localVideoTrackFlow = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrackFlow: StateFlow<VideoTrack?> = _localVideoTrackFlow.asStateFlow()

    private val _audioRoute = MutableStateFlow(CallAudioRoute.EARPIECE)
    val audioRoute: StateFlow<CallAudioRoute> = _audioRoute.asStateFlow()

    private val _bluetoothAvailable = MutableStateFlow(false)
    val bluetoothAvailable: StateFlow<Boolean> = _bluetoothAvailable.asStateFlow()

    private val _wiredHeadsetAvailable = MutableStateFlow(false)
    val wiredHeadsetAvailable: StateFlow<Boolean> = _wiredHeadsetAvailable.asStateFlow()

    private fun ensureFactory() {
        if (peerConnectionFactory != null) return
        // The WHOLE construction — including the explicit JavaAudioDeviceModule — is
        // guarded (round-3): on devices where any step throws, the exception used to
        // escape into the call screen's LaunchedEffect and the call died silently on
        // "connecting…". Now the failure lands in _sessionError, which CallActivity
        // already collects and surfaces as a visible end-of-call message; the null
        // factory check in startSession then ends the call instead of hanging.
        val created = runCatching {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
            // Explicit audio device module (previously the library default was used implicitly,
            // with no control over echo cancellation / noise suppression). Hardware AEC/NS are
            // requested where the device supports them; the module falls back to software
            // processing otherwise.
            val audioDeviceModule = JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()
            PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioDeviceModule)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()
        }.getOrElse { error ->
            Log.e(TAG, "WebRTC factory initialisation failed; calls cannot start on this device.", error)
            _sessionError.value = SESSION_ERROR_GENERIC
            return
        }
        peerConnectionFactory = created
    }

    private fun buildIceServers(): List<PeerConnection.IceServer> {
        val servers = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val parsedUrls = parseTurnUrls(BuildConfig.TURN_URL)
        // Only entries that survive normalisation as real TURN/TURNS URLs count
        // (round-3). The flag used to be set from the raw parsed list, so a
        // present-but-unusable secret (bare host, space-separated garbage) reported
        // "TURN configured" — suppressing the in-call no-TURN warning — while
        // libwebrtc silently discarded every entry and media never connected.
        val turnUrls = parsedUrls.filter { TurnUrlParser.isTurnUrl(it) }
        // TURN credentials come only from BuildConfig (gradle properties / CI secrets) — never
        // hard-code them here. When TURN is absent the call still tries STUN-only, but degrade
        // loudly: media will fail behind symmetric NAT / many mobile carriers, and that must be
        // visible in logs and UI state instead of looking like a silent "no audio" bug.
        _turnConfigured.value = turnUrls.isNotEmpty()
        if (turnUrls.isEmpty()) {
            Log.w(
                TAG,
                if (parsedUrls.isEmpty()) {
                    "TURN is NOT configured for this build (WESAL_TURN_URL is empty): calls rely on " +
                        "STUN only and audio/video may not connect on restrictive networks. " +
                        "Supply TURN via CI secrets / gradle properties — do not commit credentials."
                } else {
                    "TURN is NOT usable for this build: ${parsedUrls.size} entr(ies) present in " +
                        "WESAL_TURN_URL but none carried a turn:/turns: scheme after " +
                        "normalisation, so calls rely on STUN only. Check the secret's format " +
                        "(values are never logged)."
                }
            )
        } else {
            Log.i(TAG, "TURN configured: ${turnUrls.size} server(s) in addition to STUN.")
        }
        turnUrls.forEach { url ->
            val builder = PeerConnection.IceServer.builder(url)
            if (BuildConfig.TURN_USERNAME.isNotBlank()) {
                builder.setUsername(BuildConfig.TURN_USERNAME)
            }
            if (BuildConfig.TURN_CREDENTIAL.isNotBlank()) {
                builder.setPassword(BuildConfig.TURN_CREDENTIAL)
            }
            servers.add(builder.createIceServer())
        }
        return servers
    }

    fun startSession(
        isVideo: Boolean,
        onIceCandidateGenerated: (IceCandidate) -> Unit
    ) {
        ensureFactory()
        _sessionError.value = null
        val factory = peerConnectionFactory
        if (factory == null) {
            // Never hang silently: surface a visible session error that ends the call.
            Log.e(TAG, "PeerConnectionFactory unavailable — cannot start the call session.")
            _sessionError.value = SESSION_ERROR_GENERIC
            return
        }
        isVideoSession = isVideo
        remoteDescriptionSet = false
        pendingRemoteCandidates.clear()

        _connectionState.value = PeerConnection.PeerConnectionState.NEW
        _iceConnectionState.value = PeerConnection.IceConnectionState.NEW
        val rtcConfig = PeerConnection.RTCConfiguration(buildIceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val peerConnectionObserver = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                if (state == null) return
                _iceConnectionState.value = state
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        // Healthy again: cancel any pending restart and re-arm the episode flag.
                        cancelPendingIceRestart()
                        iceRestartSignalledForEpisode = false
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        // Brief drops often heal on their own; give ICE a grace window first,
                        // then do a real restart (re-offer) instead of dying silently.
                        scheduleIceRestart(ICE_DISCONNECT_GRACE_MS)
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        triggerIceRestart()
                    }
                    else -> Unit
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let(onIceCandidateGenerated)
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {
                // Fires after restartIce(): if a restart was requested, the signaling layer
                // (CallActivity) must now produce/send the restart offer — previously this
                // callback was empty, so restartIce() could never take effect.
                if (iceRestartAwaitingRenegotiation) {
                    iceRestartAwaitingRenegotiation = false
                    val listener = onIceRestartNeeded
                    if (listener != null) {
                        listener.invoke()
                    } else {
                        Log.w(TAG, "ICE restart renegotiation needed but no signaling listener is set.")
                    }
                }
            }
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack) {
                    _remoteVideoTrack.value = track
                }
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                if (newState != null) {
                    _connectionState.value = newState
                    if (newState == PeerConnection.PeerConnectionState.DISCONNECTED) {
                        scheduleIceRestart(ICE_DISCONNECT_GRACE_MS)
                    } else if (newState == PeerConnection.PeerConnectionState.FAILED) {
                        triggerIceRestart()
                    } else if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                        cancelPendingIceRestart()
                        iceRestartSignalledForEpisode = false
                    }
                }
            }
        }
        val createdPeerConnection = factory.createPeerConnection(rtcConfig, peerConnectionObserver)
        if (createdPeerConnection == null) {
            // createPeerConnection can return null; without this check the offer callback
            // never fires and the caller UI hangs on "connecting…" until the 45 s timer.
            Log.e(TAG, "createPeerConnection returned null — aborting the session visibly.")
            _sessionError.value = SESSION_ERROR_GENERIC
            return
        }
        peerConnection = createdPeerConnection

        // Audio track
        val audioConstraints = MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            optional.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        localAudioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack("WESAL_AUDIO", localAudioSource)?.apply {
            setEnabled(true)
        }
        localAudioTrack?.let { peerConnection?.addTrack(it, listOf("WESAL_STREAM")) }

        // Video track
        if (isVideo) {
            val capturer = createCameraCapturer()
            if (capturer != null) {
                videoCapturer = capturer
                surfaceTextureHelper = SurfaceTextureHelper.create("WesalCaptureThread", eglBase.eglBaseContext)
                localVideoSource = factory.createVideoSource(capturer.isScreencast)
                capturer.initialize(surfaceTextureHelper, context, localVideoSource!!.capturerObserver)
                capturer.startCapture(720, 1280, 30)
                localVideoTrack = factory.createVideoTrack("WESAL_VIDEO", localVideoSource)
                localVideoTrack?.let {
                    peerConnection?.addTrack(it, listOf("WESAL_STREAM"))
                    _localVideoTrackFlow.value = it
                }
            }
        }

        registerAudioMonitoring()
        refreshAudioDevices()
        val initialRoute = when {
            _bluetoothAvailable.value -> CallAudioRoute.BLUETOOTH
            _wiredHeadsetAvailable.value -> CallAudioRoute.WIRED_HEADSET
            isVideo -> CallAudioRoute.SPEAKER
            else -> CallAudioRoute.EARPIECE
        }
        setAudioRoute(initialRoute)
        startNetworkMonitoring()
    }

    // ---------------------------------------------------------------------------------------------
    // ICE restart / reconnect. restartIce() alone is a no-op unless a NEW offer with the
    // iceRestart flag is created and signalled to the peer — that is what this machinery does:
    // it flags the PeerConnection, and onRenegotiationNeeded() hands over to the signaling
    // listener (CallActivity), which sends the restart offer (caller) or asks the caller to.
    // ---------------------------------------------------------------------------------------------

    private fun scheduleIceRestart(delayMs: Long) {
        if (iceRestartSignalledForEpisode || pendingIceRestartRunnable != null) return
        val runnable = Runnable {
            pendingIceRestartRunnable = null
            triggerIceRestart()
        }
        pendingIceRestartRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelPendingIceRestart() {
        pendingIceRestartRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingIceRestartRunnable = null
    }

    private fun triggerIceRestart() {
        cancelPendingIceRestart()
        val pc = peerConnection ?: return
        if (iceRestartSignalledForEpisode) return
        iceRestartSignalledForEpisode = true
        iceRestartAwaitingRenegotiation = true
        Log.i(TAG, "ICE restart triggered (iceState=${_iceConnectionState.value}, turnConfigured=${_turnConfigured.value})")
        runCatching { pc.restartIce() }
        // Some stacks never fire onRenegotiationNeeded after restartIce(); if it has not
        // consumed the flag shortly, hand over to the signaling listener directly so the
        // restart offer is still produced instead of stalling forever.
        mainHandler.postDelayed({
            if (iceRestartAwaitingRenegotiation) {
                iceRestartAwaitingRenegotiation = false
                val listener = onIceRestartNeeded
                if (listener != null) {
                    listener.invoke()
                } else {
                    Log.w(TAG, "ICE restart needed but no signaling listener is set.")
                }
            }
        }, ICE_RENEGOTIATION_FALLBACK_MS)
    }

    /**
     * Watches connectivity while a session is live: when the device switches network
     * (Wi-Fi ↔ mobile) or regains connectivity and ICE is not healthy, force a real restart.
     */
    private fun startNetworkMonitoring() {
        networkMonitorJob?.cancel()
        networkMonitorJob = managerScope.launch {
            var wasOnline = networkMonitor.isCurrentlyConnected()
            networkMonitor.isOnline.collect { online ->
                val regained = online && !wasOnline
                wasOnline = online
                if (!regained) return@collect
                val ice = _iceConnectionState.value
                if (peerConnection != null &&
                    ice != PeerConnection.IceConnectionState.CONNECTED &&
                    ice != PeerConnection.IceConnectionState.COMPLETED &&
                    ice != PeerConnection.IceConnectionState.CHECKING
                ) {
                    Log.i(TAG, "Connectivity regained during call with ICE=$ice — restarting ICE.")
                    iceRestartSignalledForEpisode = false
                    triggerIceRestart()
                }
            }
        }
    }

    private fun stopNetworkMonitoring() {
        networkMonitorJob?.cancel()
        networkMonitorJob = null
    }

    /** Creates an offer with the mandatory IceRestart flag set, for renegotiation after a drop. */
    fun createRestartOffer(onSdpCreated: (String) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                onSdpCreated(desc.description)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.w(TAG, "ICE restart offer creation failed: $error")
            }
            override fun onSetFailure(error: String?) {
                Log.w(TAG, "ICE restart offer setLocalDescription failed: $error")
            }
        }, constraints)
    }

    private fun registerAudioMonitoring() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audioDeviceCallback == null) {
            val cb = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    refreshAudioDevices()
                    if (_bluetoothAvailable.value && _audioRoute.value != CallAudioRoute.BLUETOOTH) {
                        setAudioRoute(CallAudioRoute.BLUETOOTH)
                    } else if (_wiredHeadsetAvailable.value && !_bluetoothAvailable.value &&
                        _audioRoute.value == CallAudioRoute.EARPIECE
                    ) {
                        setAudioRoute(CallAudioRoute.WIRED_HEADSET)
                    }
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    refreshAudioDevices()
                    val current = _audioRoute.value
                    if (current == CallAudioRoute.BLUETOOTH && !_bluetoothAvailable.value) {
                        setAudioRoute(
                            when {
                                _wiredHeadsetAvailable.value -> CallAudioRoute.WIRED_HEADSET
                                isVideoSession -> CallAudioRoute.SPEAKER
                                else -> CallAudioRoute.EARPIECE
                            }
                        )
                    } else if (current == CallAudioRoute.WIRED_HEADSET && !_wiredHeadsetAvailable.value) {
                        setAudioRoute(
                            when {
                                _bluetoothAvailable.value -> CallAudioRoute.BLUETOOTH
                                isVideoSession -> CallAudioRoute.SPEAKER
                                else -> CallAudioRoute.EARPIECE
                            }
                        )
                    }
                }
            }
            audioDeviceCallback = cb
            runCatching {
                audioManager.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
            }
        }
        if (scoReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val state = intent?.getIntExtra(
                        AudioManager.EXTRA_SCO_AUDIO_STATE,
                        AudioManager.SCO_AUDIO_STATE_DISCONNECTED
                    ) ?: AudioManager.SCO_AUDIO_STATE_DISCONNECTED
                    if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED &&
                        _audioRoute.value == CallAudioRoute.BLUETOOTH
                    ) {
                        @Suppress("DEPRECATION")
                        runCatching { audioManager.isBluetoothScoOn = true }
                    }
                }
            }
            scoReceiver = receiver
            runCatching {
                @Suppress("DEPRECATION")
                val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(receiver, filter)
                }
            }
        }
    }

    private fun unregisterAudioMonitoring() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioDeviceCallback?.let { cb ->
            runCatching { audioManager.unregisterAudioDeviceCallback(cb) }
            audioDeviceCallback = null
        }
        scoReceiver?.let { rcv ->
            runCatching { context.unregisterReceiver(rcv) }
            scoReceiver = null
        }
    }

    fun createOffer(onSdpCreated: (String) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) {
                    Log.e(TAG, "createOffer succeeded with a null description.")
                    _sessionError.value = SESSION_ERROR_SDP
                    return
                }
                peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                onSdpCreated(desc.description)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                // Was an empty callback: an SDP failure looked exactly like a peer who
                // never answers. Fail visibly and let CallActivity end the call.
                Log.e(TAG, "createOffer failed: $error")
                _sessionError.value = SESSION_ERROR_SDP
            }
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "createOffer setLocalDescription failed: $error")
                _sessionError.value = SESSION_ERROR_SDP
            }
        }, constraints)
    }

    fun setRemoteOfferAndCreateAnswer(offerSdp: String, onAnswerCreated: (String) -> Unit) {
        val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                flushPendingRemoteCandidates()
                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        if (desc == null) {
                            Log.e(TAG, "createAnswer succeeded with a null description.")
                            _sessionError.value = SESSION_ERROR_SDP
                            return
                        }
                        peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                        onAnswerCreated(desc.description)
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String?) {
                        Log.e(TAG, "createAnswer failed: $error")
                        _sessionError.value = SESSION_ERROR_SDP
                    }
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "createAnswer setLocalDescription failed: $error")
                        _sessionError.value = SESSION_ERROR_SDP
                    }
                }, MediaConstraints())
            }
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "setRemoteDescription(offer) failed: $error")
                _sessionError.value = SESSION_ERROR_SDP
            }
        }, remoteDesc)
    }

    fun setRemoteAnswer(answerSdp: String) {
        val remoteDesc = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                flushPendingRemoteCandidates()
            }
        }, remoteDesc)
    }

    @Synchronized
    private fun flushPendingRemoteCandidates() {
        remoteDescriptionSet = true
        val copy = pendingRemoteCandidates.toList()
        pendingRemoteCandidates.clear()
        copy.forEach { peerConnection?.addIceCandidate(it) }
    }

    @Synchronized
    fun addRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        val ice = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        if (peerConnection != null && remoteDescriptionSet) {
            peerConnection?.addIceCandidate(ice)
        } else {
            pendingRemoteCandidates.add(ice)
        }
    }

    fun setMicEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching { audioManager.isMicrophoneMute = !enabled }
    }

    fun setCameraEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    // ---------------------------------------------------------------------------------------------
    // Camera switching (round 12, addendum B). The old implementation was a
    // bare `videoCapturer?.switchCamera(null)`: no CameraSwitchHandler (so
    // success/failure was invisible) and no in-flight guard (so the
    // switch-BACK tap landed while the first switch was still
    // reconfiguring the camera session — the capturer wedged, capture
    // never resumed, and the call froze: «بيتهنج»). Switches are now
    // serialized through CameraSwitchStateMachine, confirmed via the
    // handler, watchdogged, and recovered on error/timeout by rebuilding
    // the capturer on the camera the call is already using. The call
    // itself (peer connection, audio, video track) is never touched by a
    // switch failure — worst case the user stays on the current camera
    // and sees a short Arabic message.
    // ---------------------------------------------------------------------------------------------

    private val cameraSwitchMachine = CameraSwitchStateMachine()
    private var cameraSwitchTimeoutRunnable: Runnable? = null

    /** The camera the call is actually on (CallActivity mirrors the preview from it). */
    private val _isFrontCamera = MutableStateFlow(true)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    /** Bumped after every completed switch/recovery so the UI re-attaches the local renderer. */
    private val _cameraSwitchCount = MutableStateFlow(0)
    val cameraSwitchCount: StateFlow<Int> = _cameraSwitchCount.asStateFlow()

    /** One-shot human message for the call screen (e.g. a failed camera switch). */
    private val _cameraMessage = MutableStateFlow<String?>(null)
    val cameraMessage: StateFlow<String?> = _cameraMessage.asStateFlow()

    fun clearCameraMessage() {
        _cameraMessage.value = null
    }

    fun switchCamera() {
        val capturer = videoCapturer ?: return
        if (!cameraSwitchMachine.tryBegin()) {
            Log.d(TAG, "Camera switch ignored — another switch is already in flight")
            return
        }
        scheduleCameraSwitchTimeout()
        runCatching {
            capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                    cancelCameraSwitchTimeout()
                    // A false return means this callback is stale (the
                    // watchdog already resolved the flight) — ignore it.
                    if (cameraSwitchMachine.onDone(isFrontCamera)) {
                        _isFrontCamera.value = isFrontCamera
                        _cameraSwitchCount.value += 1
                    }
                }

                override fun onCameraSwitchError(errorDescription: String?) {
                    cancelCameraSwitchTimeout()
                    if (cameraSwitchMachine.onError()) {
                        Log.e(TAG, "Camera switch failed: $errorDescription — recovering the capturer")
                        recoverCameraCapturer()
                    }
                }
            })
        }.onFailure {
            cancelCameraSwitchTimeout()
            if (cameraSwitchMachine.onError()) {
                Log.e(TAG, "Camera switch threw: ${it.message} — recovering the capturer")
                recoverCameraCapturer()
            }
        }
    }

    private fun scheduleCameraSwitchTimeout() {
        cancelCameraSwitchTimeout()
        val runnable = Runnable {
            if (cameraSwitchMachine.onTimeout()) {
                Log.e(TAG, "Camera switch produced no callback within ${CAMERA_SWITCH_TIMEOUT_MS}ms — recovering the capturer")
                recoverCameraCapturer()
            }
        }
        cameraSwitchTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, CAMERA_SWITCH_TIMEOUT_MS)
    }

    private fun cancelCameraSwitchTimeout() {
        cameraSwitchTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        cameraSwitchTimeoutRunnable = null
    }

    /**
     * Rebuilds the capturer on the camera the call is ALREADY using (a
     * failed/timed-out switch never changed it) and restarts capture into
     * the same video source, so the existing local video track — and the
     * call — carry on. Only when even that fails is the user told.
     */
    private fun recoverCameraCapturer() {
        val oldCapturer = videoCapturer
        val source = localVideoSource
        val helper = surfaceTextureHelper
        runCatching { oldCapturer?.stopCapture() }
        runCatching { oldCapturer?.dispose() }
        videoCapturer = null
        if (source == null || helper == null) {
            _cameraMessage.value = CAMERA_SWITCH_FAILED_MESSAGE
            return
        }
        val fresh = createCameraCapturer(preferFront = cameraSwitchMachine.isFrontCamera)
        if (fresh == null) {
            _cameraMessage.value = CAMERA_SWITCH_FAILED_MESSAGE
            return
        }
        videoCapturer = fresh
        runCatching {
            fresh.initialize(helper, context, source.capturerObserver)
            fresh.startCapture(720, 1280, 30)
            _cameraSwitchCount.value += 1
        }.onFailure {
            Log.e(TAG, "Camera capturer recovery failed: ${it.message}")
            _cameraMessage.value = CAMERA_SWITCH_FAILED_MESSAGE
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    fun refreshAudioDevices() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val hasBt = if (!hasBluetoothConnectPermission()) {
            false
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                audioManager.availableCommunicationDevices.any { dev ->
                    dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        dev.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                }
            }.getOrDefault(false)
        } else {
            runCatching {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { dev ->
                    dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                }
            }.getOrDefault(false)
        }

        val hasWired = runCatching {
            val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.availableCommunicationDevices
            } else {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            }
            devices.any { dev ->
                dev.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    dev.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    dev.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && dev.type == AudioDeviceInfo.TYPE_USB_DEVICE)
            }
        }.getOrDefault(false)

        _bluetoothAvailable.value = hasBt
        _wiredHeadsetAvailable.value = hasWired
    }

    fun cycleAudioRoute() {
        refreshAudioDevices()
        val availableRoutes = buildList {
            if (_wiredHeadsetAvailable.value) {
                add(CallAudioRoute.WIRED_HEADSET)
            } else {
                add(CallAudioRoute.EARPIECE)
            }
            add(CallAudioRoute.SPEAKER)
            if (_bluetoothAvailable.value) {
                add(CallAudioRoute.BLUETOOTH)
            }
        }
        val idx = availableRoutes.indexOf(_audioRoute.value)
        val next = availableRoutes[(idx + 1) % availableRoutes.size]
        setAudioRoute(next)
    }

    fun setSpeakerphone(enabled: Boolean) {
        setAudioRoute(if (enabled) CallAudioRoute.SPEAKER else CallAudioRoute.EARPIECE)
    }

    fun setAudioRoute(route: CallAudioRoute) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        requestCallAudioFocus(audioManager)
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        refreshAudioDevices()

        val effectiveRoute = when {
            route == CallAudioRoute.BLUETOOTH && !_bluetoothAvailable.value -> {
                if (_wiredHeadsetAvailable.value) CallAudioRoute.WIRED_HEADSET else CallAudioRoute.EARPIECE
            }
            route == CallAudioRoute.WIRED_HEADSET && !_wiredHeadsetAvailable.value -> {
                CallAudioRoute.EARPIECE
            }
            else -> route
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = runCatching { audioManager.availableCommunicationDevices }.getOrDefault(emptyList())
            val targetDevice = when (effectiveRoute) {
                CallAudioRoute.BLUETOOTH -> devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                }
                CallAudioRoute.WIRED_HEADSET -> devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_USB_DEVICE
                }
                CallAudioRoute.SPEAKER -> devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                CallAudioRoute.EARPIECE -> devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                } ?: devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
            }
            val applied = targetDevice != null && runCatching {
                audioManager.setCommunicationDevice(targetDevice)
            }.getOrDefault(false)
            if (!applied) {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = (effectiveRoute == CallAudioRoute.SPEAKER)
            }
        } else {
            @Suppress("DEPRECATION")
            when (effectiveRoute) {
                CallAudioRoute.BLUETOOTH -> {
                    audioManager.isSpeakerphoneOn = false
                    runCatching {
                        audioManager.startBluetoothSco()
                        audioManager.isBluetoothScoOn = true
                    }
                }
                CallAudioRoute.SPEAKER -> {
                    runCatching {
                        audioManager.isBluetoothScoOn = false
                        audioManager.stopBluetoothSco()
                    }
                    audioManager.isSpeakerphoneOn = true
                }
                CallAudioRoute.EARPIECE, CallAudioRoute.WIRED_HEADSET -> {
                    runCatching {
                        audioManager.isBluetoothScoOn = false
                        audioManager.stopBluetoothSco()
                    }
                    audioManager.isSpeakerphoneOn = false
                }
            }
        }
        _audioRoute.value = effectiveRoute
    }

    private fun requestCallAudioFocus(audioManager: AudioManager) {
        if (audioFocusRequest != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { }
                .setAcceptsDelayedFocusGain(false)
                .build()
            audioFocusRequest = req
            runCatching { audioManager.requestAudioFocus(req) }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
        }
    }

    fun endSession() {
        stopNetworkMonitoring()
        _sessionError.value = null
        cancelCameraSwitchTimeout()
        cancelPendingIceRestart()
        iceRestartSignalledForEpisode = false
        iceRestartAwaitingRenegotiation = false
        onIceRestartNeeded = null
        _iceConnectionState.value = PeerConnection.IceConnectionState.CLOSED
        unregisterAudioMonitoring()
        runCatching { videoCapturer?.stopCapture() }
        videoCapturer?.dispose()
        videoCapturer = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        localVideoTrack?.dispose()
        localVideoTrack = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        localVideoSource?.dispose()
        localVideoSource = null
        localAudioSource?.dispose()
        localAudioSource = null
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        remoteDescriptionSet = false
        pendingRemoteCandidates.clear()
        _remoteVideoTrack.value = null
        _localVideoTrackFlow.value = null
        _connectionState.value = PeerConnection.PeerConnectionState.CLOSED
        _audioRoute.value = CallAudioRoute.EARPIECE
        _bluetoothAvailable.value = false
        _wiredHeadsetAvailable.value = false
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching { audioManager.isMicrophoneMute = false }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
                audioManager.isSpeakerphoneOn = false
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            runCatching { audioManager.abandonAudioFocus(null) }
        }
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun createCameraCapturer(preferFront: Boolean = true): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val frontName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
        val backName = enumerator.deviceNames.firstOrNull { !enumerator.isFrontFacing(it) }
        val target = if (preferFront) {
            frontName ?: backName
        } else {
            backName ?: frontName
        } ?: return null
        return enumerator.createCapturer(target, null)
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            Log.e(TAG, "SDP create failure: $error")
        }
        override fun onSetFailure(error: String?) {
            Log.e(TAG, "SDP set failure: $error")
        }
    }

    companion object {
        private const val TAG = "WesalCalls"
        private const val SESSION_ERROR_GENERIC = "تعذر بدء المكالمة على هذا الجهاز"
        private const val SESSION_ERROR_SDP = "حدثت مشكلة أثناء إعداد المكالمة"
        private const val ICE_DISCONNECT_GRACE_MS = 3_000L
        private const val ICE_RENEGOTIATION_FALLBACK_MS = 1_200L
        /** Round 12 (addendum B): a camera switch that produces neither callback within this window is treated as wedged and recovered. */
        private const val CAMERA_SWITCH_TIMEOUT_MS = 5_000L
        private const val CAMERA_SWITCH_FAILED_MESSAGE = "تعذر تبديل الكاميرا"

        fun parseTurnUrls(raw: String): List<String> = TurnUrlParser.parse(raw)
    }
}

/**
 * Pure normalisation of the raw TURN_URL build-config value into ICE-server URLs.
 *
 * Extracted from [WebRtcCallManager] as a standalone object so the parsing rules are
 * unit-testable on the JVM (no Android/WebRTC types involved). The old inline parser
 * split only on commas/semicolons/newlines and never validated the scheme, so a
 * secret stored as a bare host or space-separated list silently produced zero
 * usable TURN servers while every indicator claimed TURN was configured.
 */
object TurnUrlParser {

    /**
     * Splits [raw] on commas, semicolons and any whitespace (spaces, tabs,
     * newlines), trims, drops empties, normalises each entry and dedupes — after
     * normalisation, so "host:3478" and "turn:host:3478" collapse into one entry.
     */
    fun parse(raw: String): List<String> = raw
        .split(Regex("[,;\\s]+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { normalize(it) }
        .distinct()

    /**
     * Entries that already carry a scheme (turn:/turns:/stun:/stuns:, or anything
     * containing "://") are kept verbatim. A bare host[:port] entry has no scheme —
     * libwebrtc requires one and silently discards schemeless servers — so "turn:"
     * is prepended.
     */
    fun normalize(entry: String): String = when {
        entry.contains("://") -> entry
        entry.startsWith("turn:", ignoreCase = true) -> entry
        entry.startsWith("turns:", ignoreCase = true) -> entry
        entry.startsWith("stun:", ignoreCase = true) -> entry
        entry.startsWith("stuns:", ignoreCase = true) -> entry
        else -> "turn:$entry"
    }

    /** True when [url] (post-normalisation) is a TURN/TURNS server URL. */
    fun isTurnUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("turn:") || lower.startsWith("turns:")
    }
}
