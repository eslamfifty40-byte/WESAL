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
import androidx.core.content.ContextCompat
import com.wesal.messenger.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.*
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
    @ApplicationContext private val context: Context
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
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    private fun buildIceServers(): List<PeerConnection.IceServer> {
        val servers = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val turnUrls = parseTurnUrls(BuildConfig.TURN_URL)
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
        val factory = peerConnectionFactory ?: return
        isVideoSession = isVideo
        remoteDescriptionSet = false
        pendingRemoteCandidates.clear()

        _connectionState.value = PeerConnection.PeerConnectionState.NEW
        val rtcConfig = PeerConnection.RTCConfiguration(buildIceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let(onIceCandidateGenerated)
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
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
                        peerConnection?.restartIce()
                    }
                }
            }
        })

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
                if (desc == null) return
                peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                onSdpCreated(desc.description)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    fun setRemoteOfferAndCreateAnswer(offerSdp: String, onAnswerCreated: (String) -> Unit) {
        val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                flushPendingRemoteCandidates()
                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        if (desc == null) return
                        peerConnection?.setLocalDescription(SimpleSdpObserver(), desc)
                        onAnswerCreated(desc.description)
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String?) {}
                    override fun onSetFailure(error: String?) {}
                }, MediaConstraints())
            }
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {}
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

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
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

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val frontName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
        val backName = enumerator.deviceNames.firstOrNull { !enumerator.isFrontFacing(it) }
        val target = frontName ?: backName ?: return null
        return enumerator.createCapturer(target, null)
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    companion object {
        fun parseTurnUrls(raw: String): List<String> = raw
            .split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }
}
