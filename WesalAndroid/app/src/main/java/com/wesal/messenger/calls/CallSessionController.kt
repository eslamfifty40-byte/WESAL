package com.wesal.messenger.calls

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.wesal.messenger.core.util.CallRingLogic
import com.wesal.messenger.core.util.CallTimeoutLogic
import com.wesal.messenger.data.repository.CallSignalingErrors
import com.wesal.messenger.domain.model.CallStatus
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.CallRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import javax.inject.Inject
import javax.inject.Singleton

/** Static description of the live call, rendered by any attached UI. */
data class CallSessionInfo(
    val callId: String = "",
    val peerUid: String = "",
    val peerName: String = "WESAL User",
    val peerAvatar: String = "",
    val peerVerified: Boolean = false,
    val isVideo: Boolean = false,
    val isIncoming: Boolean = false,
    val startedAtMs: Long = 0L
)

/** Coarse phase for the status line (the UI maps it to its strings). */
enum class CallPhase { CONNECTING, RINGING, ANSWERED }

/** Why a session ended; an attached UI maps it to its farewell label. */
enum class CallEndReason {
    LOCAL_HANGUP,
    PEER_ENDED,
    REJECTED,
    MISSED,
    SETUP_FAILED,
    OFFER_TIMEOUT,
    MEDIA_FAILURE,
    SESSION_ERROR
}

/** A finished session, tagged so only the UI of THAT session reacts. */
data class CallTermination(
    val generation: Long,
    val reason: CallEndReason
)

/**
 * Round 20: application-scoped owner of the live 1:1 call session.
 *
 * Before this round the session lived inside CallActivity: the
 * signaling listeners, the deadlines and the WebRTC start were all
 * driven from the activity's lifecycleScope, and `onDestroy()` called
 * `endSession()` unconditionally — so pressing Back (or the activity
 * being destroyed for any reason) ENDED the call, exactly what the
 * owner rejected («لما اعمل رجوع المكالمة بتفصل — أنا مش عايزها
 * تفصل»). Now the session lives here, in a singleton whose scope is
 * the process, not any screen:
 *
 *  - CallActivity is a detachable VIEW: Back finishes the activity
 *    and nothing else; the call keeps running (media, signaling,
 *    deadlines) and the foreground-service notification offers the
 *    way back in.
 *  - Only [endCall] (the explicit end button), a terminal call-doc
 *    status, or a real failure ends a call — via [terminate], which
 *    is also the ONLY place that tears the WebRTC session down.
 *  - Starting a session while one is active is refused, so re-entry
 *    can never create a duplicate session on the singleton manager.
 *  - A lightweight marker in SharedPreferences records the live
 *    callId; if the process dies mid-call, the next process finds the
 *    marker with no live session behind it and reconciles (best-effort
 *    ENDED stamp + marker cleared) instead of resurrecting a zombie.
 *
 * All deadline/watchdog decisions reuse [CallTimeoutLogic] unchanged
 * (round 18 ring-sustain contract).
 */
@Singleton
class CallSessionController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val webRtcManager: WebRtcCallManager,
    private val callRepository: CallRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val markerPrefs by lazy {
        context.getSharedPreferences(MARKER_PREFS, Context.MODE_PRIVATE)
    }

    private val _session = MutableStateFlow<CallSessionInfo?>(null)
    val session: StateFlow<CallSessionInfo?> = _session.asStateFlow()

    private val _phase = MutableStateFlow(CallPhase.CONNECTING)
    val phase: StateFlow<CallPhase> = _phase.asStateFlow()

    /** Epoch ms when the other side answered (doc ACTIVE), else null. */
    private val _answeredAtMs = MutableStateFlow<Long?>(null)
    val answeredAtMs: StateFlow<Long?> = _answeredAtMs.asStateFlow()

    private val _termination = MutableStateFlow<CallTermination?>(null)
    val termination: StateFlow<CallTermination?> = _termination.asStateFlow()

    /**
     * Human message captured at termination time for the reasons that
     * carry one (today: SESSION_ERROR — the manager clears its own
     * error flow during teardown, so the text is copied here first).
     */
    private val _endMessage = MutableStateFlow<String?>(null)
    val endMessage: StateFlow<String?> = _endMessage.asStateFlow()

    /** Increments per session; a UI reacts only to its own generation. */
    @Volatile
    var currentGeneration: Long = 0L
        private set

    @Volatile
    private var finishing = false

    @Volatile
    private var hasConnected = false

    private var sessionJobs = mutableListOf<Job>()
    private val pendingLocalCandidates = mutableListOf<IceCandidate>()

    private fun isActive(): Boolean = _session.value != null && !finishing

    init {
        // Round 21: when the process comes back to the foreground
        // during a live call, re-assert the call's audio pipeline
        // (focus / communication mode / route) on the manager. Any
        // audio damage the background period did — above all an audio
        // focus loss that nothing recovered, the owner's «الصوت
        // بيقطع خالص» — heals here at the latest, instead of lasting
        // the whole call. Registered once: this controller is a
        // process-scoped singleton, like the observer's owner.
        scope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) {
                        if (isActive()) {
                            webRtcManager.ensureCallAudio()
                        }
                    }
                }
            )
        }
    }

    // ------------------------------------------------------------------
    // Session start
    // ------------------------------------------------------------------

    /**
     * Starts an outgoing call. Returns false (and changes nothing)
     * when a session is already active — the caller should simply
     * attach its UI to the live one.
     */
    fun startOutgoing(peer: WesalUser, isVideo: Boolean): Boolean {
        if (_session.value != null) return false
        val info = CallSessionInfo(
            callId = "",
            peerUid = peer.uid,
            peerName = peer.displayName.ifBlank {
                peer.publicId.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "WESAL User"
            },
            peerAvatar = peer.photoUrl,
            peerVerified = peer.isVerified,
            isVideo = isVideo,
            isIncoming = false,
            startedAtMs = System.currentTimeMillis()
        )
        beginSession(info)
        val gen = currentGeneration
        scope.launch {
            webRtcManager.createOffer { offerSdp ->
                scope.launch {
                    // The original peer object (initiateCall also
                    // reads publicId for the callee handle fallback).
                    val targetUser = peer
                    // Bounded setup (round 13/18): a call-doc write
                    // that never ACKs must not hang the dialer.
                    val initResult = withTimeoutOrNull(CallTimeoutLogic.SETUP_TIMEOUT_MS) {
                        callRepository.initiateCall(targetUser, isVideo, offerSdp)
                    }
                    (initResult ?: Result.failure(Exception("call setup timeout")))
                        .onSuccess { newCallId ->
                            // Generation guard: a completion from a
                            // dead session must never hijack a newer one.
                            if (!isActive() || currentGeneration != gen) return@onSuccess
                            _session.value = info.copy(callId = newCallId)
                            writeMarker(newCallId, info)
                            val queued = synchronized(pendingLocalCandidates) {
                                pendingLocalCandidates.toList().also { pendingLocalCandidates.clear() }
                            }
                            queued.forEach { c ->
                                scope.launch {
                                    callRepository.addIceCandidate(
                                        callId = newCallId,
                                        sdpMid = c.sdpMid,
                                        sdpMLineIndex = c.sdpMLineIndex,
                                        candidate = c.sdp,
                                        fromCaller = true
                                    )
                                }
                            }
                            listenToCallAndCandidates(newCallId, isCaller = true)
                        }
                        .onFailure {
                            // Honest failure exit (round 13): the UI
                            // shows why and closes; nothing was ever
                            // written, so nothing is stamped.
                            terminate(CallEndReason.SETUP_FAILED)
                        }
                }
            }
        }
        startOutgoingDeadlines()
        return true
    }

    /**
     * Starts the answering side of an incoming call. Same
     * already-active contract as [startOutgoing].
     */
    fun startIncomingAnswer(
        callId: String,
        peerUid: String,
        peerName: String,
        peerAvatar: String,
        peerVerified: Boolean,
        isVideo: Boolean
    ): Boolean {
        if (_session.value != null) return false
        if (callId.isBlank()) return false
        val info = CallSessionInfo(
            callId = callId,
            peerUid = peerUid,
            peerName = peerName.ifBlank { "WESAL User" },
            peerAvatar = peerAvatar,
            peerVerified = peerVerified,
            isVideo = isVideo,
            isIncoming = true,
            startedAtMs = System.currentTimeMillis()
        )
        beginSession(info)
        writeMarker(callId, info)
        val gen = currentGeneration
        scope.launch {
            // Bounded wait for the caller's offer (round 13): if the
            // call doc / offer never becomes readable, fail visibly and
            // end instead of waiting forever.
            val callDoc = withTimeoutOrNull(OFFER_WAIT_TIMEOUT_MS) {
                callRepository.observeCall(callId)
                    .filter { it != null && it.offerSdp.isNotBlank() }
                    .firstOrNull()
            }
            if (!isActive() || currentGeneration != gen) return@launch
            if (callDoc != null) {
                webRtcManager.setRemoteOfferAndCreateAnswer(callDoc.offerSdp) { answerSdp ->
                    scope.launch {
                        callRepository.submitAnswer(callId, answerSdp)
                        if (isActive() && currentGeneration == gen) {
                            listenToCallAndCandidates(callId, isCaller = false)
                        }
                    }
                }
            } else {
                // Offer never arrived: release the caller from waiting
                // on us, then end (the UI shows the server-error copy).
                runCatching {
                    callRepository.updateCallStatus(callId, CallStatus.ENDED, 0L)
                }
                terminate(CallEndReason.OFFER_TIMEOUT)
            }
        }
        return true
    }

    /** Shared session bootstrap: state, foreground service, WebRTC. */
    private fun beginSession(info: CallSessionInfo) {
        currentGeneration += 1
        finishing = false
        hasConnected = false
        _termination.value = null
        _endMessage.value = null
        _answeredAtMs.value = null
        _phase.value = CallPhase.CONNECTING
        _session.value = info
        synchronized(pendingLocalCandidates) { pendingLocalCandidates.clear() }
        writeMarker(info.callId, info)

        startCallForegroundService(info, mediaProjection = false, sharing = false)

        // Real ICE-restart signaling (unchanged from the activity
        // era): the caller produces a fresh restart offer into the
        // call doc; the callee asks the caller to do so.
        webRtcManager.onIceRestartNeeded = {
            if (isActive()) {
                val current = _session.value
                if (current != null) {
                    if (!current.isIncoming) {
                        produceRestartOffer()
                    } else if (current.callId.isNotBlank()) {
                        scope.launch {
                            callRepository.requestIceRestart(current.callId)
                        }
                    }
                }
            }
        }

        CallSignalingErrors.clear()

        webRtcManager.startSession(info.isVideo) { candidate ->
            val cid = _session.value?.callId.orEmpty()
            if (cid.isNotBlank()) {
                scope.launch {
                    callRepository.addIceCandidate(
                        callId = cid,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex,
                        candidate = candidate.sdp,
                        fromCaller = _session.value?.isIncoming != true
                    )
                }
            } else {
                synchronized(pendingLocalCandidates) {
                    pendingLocalCandidates.add(candidate)
                }
            }
        }

        trackJob(scope.launch {
            webRtcManager.connectionState.collect { state ->
                if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                    hasConnected = true
                }
            }
        })
        trackJob(scope.launch {
            webRtcManager.sessionError.collect { error ->
                if (error != null && isActive()) {
                    _endMessage.value = error
                    terminate(CallEndReason.SESSION_ERROR)
                }
            }
        })
        // Round 21: keep the ongoing-call notification in sync with
        // the screen-share state, wherever the share is started or
        // stopped from (the call UI, the notification's own
        // «إيقاف المشاركة» action, or the system's projection UI —
        // all of them land on the manager's isScreenSharing flow).
        // While sharing, the notification is re-posted WITH the
        // mediaProjection type and the stop-share action; when the
        // share ends it is re-posted without the action, so the owner
        // can always see — and stop — a live share. Only CHANGES
        // re-post; the initial value matches the beginSession post.
        trackJob(scope.launch {
            var lastSharing = webRtcManager.isScreenSharing.value
            webRtcManager.isScreenSharing.collect { sharing ->
                if (sharing == lastSharing) return@collect
                lastSharing = sharing
                val current = _session.value ?: return@collect
                if (!isActive()) return@collect
                startCallForegroundService(
                    current,
                    mediaProjection = sharing,
                    sharing = sharing
                )
            }
        })
        // Round 21: the outgoing RINGBACK is owned HERE, not by the
        // call UI. In r20 it was a ToneGenerator inside CallActivity's
        // composition; leaving the screen cancelled it — «صوت الرن حتى
        // بيقطع خالص» (owner, r20 verdict). Now the session drives
        // it: it sounds from placement through «جاري الاتصال» and
        // «يرن» until the answer lands or the media connects, whether
        // or not any call UI is attached — WhatsApp behaviour. The
        // play/stop decision is the same pure CallRingLogic the UI
        // used (media is granted by construction once a session
        // exists); collectLatest cancels the loop the moment the
        // decision flips, and terminate() cancels it with the other
        // session jobs.
        trackJob(scope.launch {
            combine(_phase, webRtcManager.connectionState, _session) { phase, conn, session ->
                session != null &&
                    phase != CallPhase.ANSWERED &&
                    CallRingLogic.shouldPlayRingback(
                        isIncoming = session.isIncoming,
                        mediaGranted = true,
                        hasConnected = hasConnected,
                        peerConnected = conn == PeerConnection.PeerConnectionState.CONNECTED
                    )
            }.collectLatest { shouldRing ->
                if (shouldRing) runRingbackLoop()
            }
        })
        startMediaFailureWatchdog()
    }

    /**
     * The ringback cadence («تيت … تيت») for one ringing episode.
     * Suspends until cancelled (answer / connect / terminate — via
     * the collectLatest above); the finally always silences and
     * releases the generator. A failed startTone recreates the
     * generator and retries within the same cycle (round 12 addendum
     * A, ported from the old UI effect verbatim in behaviour).
     */
    private suspend fun runRingbackLoop() {
        var toneGen = runCatching {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
        }.getOrNull()
        try {
            while (true) {
                var played = toneGen?.let {
                    runCatching {
                        it.startTone(
                            ToneGenerator.TONE_SUP_RINGTONE,
                            CallRingLogic.RINGBACK_TONE_DURATION_MS
                        )
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
                            fresh.startTone(
                                ToneGenerator.TONE_SUP_RINGTONE,
                                CallRingLogic.RINGBACK_TONE_DURATION_MS
                            )
                        }.getOrDefault(false)
                    }
                    if (!played) {
                        Log.w(TAG, "Ringback tone could not be started on the current audio route")
                    }
                }
                delay(CallRingLogic.RINGBACK_CYCLE_DELAY_MS)
            }
        } finally {
            runCatching { toneGen?.stopTone() }
            runCatching { toneGen?.release() }
        }
    }

    /** (Re)posts the ongoing-call foreground service / notification. */
    private fun startCallForegroundService(
        info: CallSessionInfo,
        mediaProjection: Boolean,
        sharing: Boolean
    ) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, CallForegroundService::class.java).apply {
                putExtra("peerName", info.peerName)
                putExtra("isVideo", info.isVideo)
                putExtra(CallForegroundService.EXTRA_MEDIA_PROJECTION, mediaProjection)
                putExtra(CallForegroundService.EXTRA_SHARING, sharing)
            }
        )
    }

    /**
     * Round 21: called by the call UI immediately BEFORE it launches
     * the system MediaProjection consent prompt. Android 14 requires
     * a foreground service of the mediaProjection type to be running
     * before a projection may be created; re-posting the call service
     * with that type here keeps the whole foreground-service lifecycle
     * in this controller instead of the (detachable) activity poking
     * the service itself. No-op when no session is live.
     */
    fun prepareScreenShare() {
        val current = _session.value ?: return
        if (!isActive()) return
        startCallForegroundService(
            current,
            mediaProjection = true,
            sharing = webRtcManager.isScreenSharing.value
        )
    }

    // ------------------------------------------------------------------
    // Signaling listeners + deadlines (ported from CallActivity)
    // ------------------------------------------------------------------

    private fun listenToCallAndCandidates(callId: String, isCaller: Boolean) {
        trackJob(scope.launch {
            callRepository.observeRemoteIceCandidates(callId, listenForCaller = !isCaller)
                .collect { (mid, idx, cand) ->
                    webRtcManager.addRemoteIceCandidate(mid, idx, cand)
                }
        })
        trackJob(scope.launch {
            var appliedAnswerGeneration = -1L
            var appliedOfferGeneration = 0L // generation 0 is the initial offer, applied before this listener starts (callee)
            var handledRestartRequestAt = 0L
            var activeReported = false
            callRepository.observeCall(callId).collect { call ->
                if (call == null || !isActive()) return@collect
                // The callee stamps ACTIVE in submitAnswer; the caller
                // observes the same doc. Either side starts its
                // minutes count the moment the answer lands (R17 G-01).
                if (call.status == CallStatus.ACTIVE && !activeReported) {
                    activeReported = true
                    _answeredAtMs.value = System.currentTimeMillis()
                    _phase.value = CallPhase.ANSWERED
                }
                if (isCaller && (call.status == CallStatus.RINGING || call.calleeRingingAck) &&
                    appliedAnswerGeneration < 0L &&
                    webRtcManager.connectionState.value != PeerConnection.PeerConnectionState.CONNECTED
                ) {
                    if (_phase.value == CallPhase.CONNECTING) {
                        _phase.value = CallPhase.RINGING
                    }
                }
                // Apply the answer for the CURRENT offer generation
                // only — after an ICE restart the callee writes a fresh
                // answer for the new generation, and it must be applied
                // too (the old one-shot flag dropped renegotiated ones).
                if (isCaller && call.answerSdp.isNotBlank() &&
                    appliedAnswerGeneration != call.iceRestartGeneration
                ) {
                    appliedAnswerGeneration = call.iceRestartGeneration
                    webRtcManager.setRemoteAnswer(call.answerSdp)
                    _phase.value = CallPhase.ANSWERED
                }
                // Callee asked (through the doc) for a restart: caller
                // produces the offer.
                if (isCaller && call.iceRestartRequestedAt > handledRestartRequestAt &&
                    call.iceRestartRequestedAt > 0L
                ) {
                    handledRestartRequestAt = call.iceRestartRequestedAt
                    produceRestartOffer()
                }
                // Caller signalled a restart offer: callee re-answers.
                if (!isCaller && call.iceRestartGeneration > appliedOfferGeneration &&
                    call.offerSdp.isNotBlank()
                ) {
                    appliedOfferGeneration = call.iceRestartGeneration
                    webRtcManager.setRemoteOfferAndCreateAnswer(call.offerSdp) { answerSdp ->
                        scope.launch {
                            callRepository.submitAnswer(callId, answerSdp)
                        }
                    }
                }
                when (call.status) {
                    CallStatus.REJECTED -> terminate(CallEndReason.REJECTED)
                    CallStatus.MISSED -> terminate(CallEndReason.MISSED)
                    CallStatus.ENDED -> terminate(CallEndReason.PEER_ENDED)
                    else -> Unit
                }
            }
        })
    }

    /** Caller side of an ICE restart: fresh offer written to the doc. */
    private fun produceRestartOffer() {
        val cid = _session.value?.callId.orEmpty()
        if (cid.isBlank() || !isActive()) return
        webRtcManager.createRestartOffer { restartSdp ->
            scope.launch {
                callRepository.submitRestartOffer(cid, restartSdp)
            }
        }
    }

    /**
     * Outgoing deadlines (round 13/18 contract, now session-owned so
     * they fire even while no call UI is attached): the setup deadline
     * may only fire on a total absence of progress; the no-answer
     * deadline is a full 60 s from placement and can never fire once
     * the callee answered or the media connected.
     */
    private fun startOutgoingDeadlines() {
        trackJob(scope.launch {
            delay(CallTimeoutLogic.SETUP_TIMEOUT_MS)
            if (isActive() &&
                CallTimeoutLogic.shouldEndSetup(
                    callDocCreated = _session.value?.callId?.isNotBlank() == true,
                    answered = _answeredAtMs.value != null,
                    peerConnected = webRtcManager.connectionState.value ==
                        PeerConnection.PeerConnectionState.CONNECTED
                )
            ) {
                terminate(CallEndReason.SETUP_FAILED)
                return@launch
            }
            delay(CallTimeoutLogic.NO_ANSWER_TIMEOUT_MS - CallTimeoutLogic.SETUP_TIMEOUT_MS)
            if (isActive() &&
                CallTimeoutLogic.shouldEndForNoAnswer(
                    answered = _answeredAtMs.value != null,
                    peerConnected = webRtcManager.connectionState.value ==
                        PeerConnection.PeerConnectionState.CONNECTED
                )
            ) {
                val cid = _session.value?.callId.orEmpty()
                if (cid.isNotBlank()) {
                    runCatching {
                        callRepository.updateCallStatus(cid, CallStatus.MISSED, 0L)
                    }
                }
                terminate(CallEndReason.MISSED)
            }
        })
    }

    /**
     * Terminal media-failure watchdog (rounds 10/18): armed ONLY for
     * an established call; any ICE recovery cancels the countdown
     * (collectLatest restarts the block on every state change).
     */
    private fun startMediaFailureWatchdog() {
        trackJob(scope.launch {
            combine(
                webRtcManager.iceConnectionState,
                webRtcManager.connectionState,
                _answeredAtMs
            ) { ice, conn, answeredAt -> Triple(ice, conn, answeredAt) }
                .collectLatest { (ice, _, answeredAt) ->
                    if ((ice == PeerConnection.IceConnectionState.FAILED ||
                            ice == PeerConnection.IceConnectionState.DISCONNECTED) &&
                        CallTimeoutLogic.mediaFailureWatchdogArmed(
                            hasConnected = hasConnected,
                            answered = answeredAt != null
                        )
                    ) {
                        delay(CallTimeoutLogic.MEDIA_FAILURE_GRACE_MS)
                        if (isActive()) {
                            Log.e(
                                TAG,
                                "MEDIA FAILURE terminal: ice=$ice — ending call with user-visible reason"
                            )
                            val cid = _session.value?.callId.orEmpty()
                            if (cid.isNotBlank()) {
                                runCatching {
                                    callRepository.updateCallStatus(cid, CallStatus.ENDED, 0L)
                                }
                            }
                            terminate(CallEndReason.MEDIA_FAILURE)
                        }
                    }
                }
        })
    }

    // ------------------------------------------------------------------
    // Ending
    // ------------------------------------------------------------------

    /**
     * The explicit end-call action — the ONLY user action that ends a
     * call. Stamps ENDED with the answered duration, then tears down.
     */
    fun endCall() {
        val info = _session.value ?: return
        if (finishing) return
        finishing = true
        val durationSeconds = _answeredAtMs.value?.let {
            (System.currentTimeMillis() - it) / 1000L
        } ?: 0L
        scope.launch {
            if (info.callId.isNotBlank()) {
                runCatching {
                    callRepository.updateCallStatus(info.callId, CallStatus.ENDED, durationSeconds)
                }
            }
            terminate(CallEndReason.LOCAL_HANGUP)
        }
    }

    /**
     * The single teardown path. Idempotent; safe to call from any
     * terminal trigger. Publishes the termination (tagged with the
     * session generation) BEFORE clearing the session, so an attached
     * UI can show its farewell label and close itself.
     */
    @Synchronized
    private fun terminate(reason: CallEndReason) {
        if (_session.value == null) return
        finishing = true
        _termination.value = CallTermination(currentGeneration, reason)
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        synchronized(pendingLocalCandidates) { pendingLocalCandidates.clear() }
        webRtcManager.endSession()
        runCatching {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
        clearMarker()
        _session.value = null
        _answeredAtMs.value = null
        _phase.value = CallPhase.CONNECTING
        Log.i(TAG, "Call session terminated: $reason")
    }

    private fun trackJob(job: Job) {
        sessionJobs.add(job)
    }

    // ------------------------------------------------------------------
    // Process-death reconciliation
    // ------------------------------------------------------------------

    private fun writeMarker(callId: String, info: CallSessionInfo) {
        runCatching {
            markerPrefs.edit()
                .putString(KEY_CALL_ID, callId)
                .putString(KEY_PEER_NAME, info.peerName)
                .putBoolean(KEY_IS_VIDEO, info.isVideo)
                .putLong(KEY_STARTED_AT, info.startedAtMs)
                .apply()
        }
    }

    private fun clearMarker() {
        runCatching { markerPrefs.edit().clear().apply() }
    }

    /**
     * Called once per process from WesalApplication. A marker found
     * here can only belong to a call whose process died (a live
     * process owns its marker through its in-memory session): stamp
     * the call ENDED best-effort so the other side is not left
     * ringing a ghost, then clear the marker. No session is ever
     * resurrected from the marker.
     */
    fun reconcileStaleMarker() {
        val staleCallId = runCatching {
            markerPrefs.getString(KEY_CALL_ID, null)
        }.getOrNull()
        clearMarker()
        if (staleCallId.isNullOrBlank()) return
        if (_session.value != null) return
        Log.i(TAG, "Reconciling stale call marker for $staleCallId (previous process died mid-call)")
        scope.launch {
            runCatching {
                callRepository.updateCallStatus(staleCallId, CallStatus.ENDED, 0L)
            }
        }
    }

    companion object {
        private const val TAG = "WesalCallSession"
        private const val OFFER_WAIT_TIMEOUT_MS = 30_000L
        private const val MARKER_PREFS = "wesal_active_call"
        private const val KEY_CALL_ID = "callId"
        private const val KEY_PEER_NAME = "peerName"
        private const val KEY_IS_VIDEO = "isVideo"
        private const val KEY_STARTED_AT = "startedAt"
    }
}
