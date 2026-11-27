package com.wesal.messenger.presentation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wesal.messenger.core.network.NetworkMonitor
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.core.util.OwnProfileSnapshot
import com.wesal.messenger.core.util.PhoneOtpLogic
import com.wesal.messenger.data.local.AppUserSettings
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.domain.model.AppUpdateConfig
import com.wesal.messenger.domain.model.CallRecord
import com.wesal.messenger.domain.model.GlobalAppearanceConfig
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.CallRepository
import com.wesal.messenger.domain.repository.ChannelRepository
import com.wesal.messenger.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

sealed interface StartDestination {
    // No Loading state on purpose: the start destination is decided synchronously at
    // MainViewModel construction from LOCAL auth state (FirebaseAuth's disk-restored
    // user), so a signed-in user goes straight to Home and the UI is never gated on a
    // loading screen or on the network. The old Loading state was never assigned
    // anywhere — dead code that only invited a future gated/black startup screen.
    data object PhoneLogin : StartDestination
    data object ProfileSetup : StartDestination
    /** Mandatory phone-verification gate (phone phase): signed in + profile
     *  complete, but the number is not OTP-verified yet. */
    data object VerifyPhone : StartDestination
    data object Home : StartDestination
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val callRepository: CallRepository,
    private val channelRepository: ChannelRepository,
    private val prefs: UserPreferencesDataStore,
    networkMonitor: NetworkMonitor
) : ViewModel() {

    val appSettings: StateFlow<AppUserSettings> = prefs.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppUserSettings())

    val globalAppearance: StateFlow<GlobalAppearanceConfig> = userRepository.observeGlobalAppearance()
        .stateIn(viewModelScope, SharingStarted.Eagerly, GlobalAppearanceConfig())

    // Forced/soft update policy (system_config/app_update). Fail-open: the default
    // config is disabled, and the repository emits it on any error/missing doc, so
    // MainActivity shows an update dialog only on a positive, loaded policy.
    val appUpdateConfig: StateFlow<AppUpdateConfig> = userRepository.observeAppUpdateConfig()
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppUpdateConfig())

    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val authUid: StateFlow<String?> = authRepository.observeAuthState()
        .stateIn(viewModelScope, SharingStarted.Eagerly, authRepository.currentUserId)

    val incomingCall: StateFlow<CallRecord?> = callRepository.observeIncomingCalls()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val pendingInviteId: StateFlow<String?> = appSettings
        .map { it.pendingInviteId.trim().ifBlank { null } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Round 12 (addendum 11): one-shot event — a welcome gift was just
    // granted to the signed-in user; MainActivity announces it (dialog +
    // system notification). Fires at most once per account, ever.
    private val _welcomeGiftEvent =
        kotlinx.coroutines.flow.MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val welcomeGiftEvent: kotlinx.coroutines.flow.SharedFlow<Long> =
        _welcomeGiftEvent

    private val _resolvedChannelId = MutableStateFlow<String?>(null)
    val resolvedChannelId: StateFlow<String?> = _resolvedChannelId.asStateFlow()

    private val _startDestination = MutableStateFlow<StartDestination>(
        if (authRepository.currentUserId != null) StartDestination.Home else StartDestination.PhoneLogin
    )
    val startDestination: StateFlow<StartDestination> = _startDestination.asStateFlow()

    private var heartbeatJob: Job? = null

    /**
     * Uid whose Supabase-registry phone verification this process has already
     * proven. The verified_phones registry is the authority for the mandatory
     * gate; the user doc's isPhoneVerified is only its best-effort mirror, so
     * a positive registry result is cached for the session instead of being
     * re-queried on every start / doc emission.
     */
    @Volatile
    private var registryVerifiedUid: String? = null

    @Volatile
    private var registryRecheckUid: String? = null

    @Volatile
    private var registryRecheckAtMs: Long = 0L

    /**
     * True when the account's phone counts as verified for the mandatory
     * gate: the user doc's mirror is COMPLETE (flag + number + phoneHash —
     * see [PhoneOtpLogic.isCompleteVerificationMirror]; a flag-only mirror
     * is production reality and is NOT evidence), this session already
     * proved it against the registry, or the registry holds a row for [uid]
     * (checked now, bounded at 6s, fail-closed).
     */
    private suspend fun isGateVerified(uid: String, docVerified: Boolean): Boolean {
        if (docVerified || registryVerifiedUid == uid) {
            registryVerifiedUid = uid
            return true
        }
        val inRegistry = withTimeoutOrNull(6_000L) {
            runCatching { authRepository.isPhoneVerifiedInRegistry(uid) }.getOrDefault(false)
        } ?: false
        if (inRegistry) registryVerifiedUid = uid
        return inRegistry
    }

    /**
     * While an unverified account is held at the gate, re-check the registry
     * in the background (throttled to one check per uid per 20s — the doc
     * observer re-emits on presence heartbeats, so this ticks regularly):
     * a verification completed on another device, or one whose Firestore
     * mirror stamp failed, lifts the gate from here even when the user doc
     * never flips.
     */
    private fun recheckRegistryInBackground(uid: String) {
        val now = System.currentTimeMillis()
        if (registryRecheckUid == uid && now - registryRecheckAtMs < 20_000L) return
        registryRecheckUid = uid
        registryRecheckAtMs = now
        // Round 11 (item 1): this recheck ticks on app start and on every
        // own-doc emission (profile loads included) — the natural place to
        // also run the registry-keyed mirror heal, so a verified account
        // whose Firestore mirror was lost is repaired without waiting for
        // the next sign-in. The heal self-throttles per uid.
        runCatching { authRepository.healVerifiedPhoneMirror(uid) }
        viewModelScope.launch {
            // Round 12 (addendum 6): gift auto-revert write-back rides the
            // same throttled recheck — an expired timed badge/frame gift on
            // the user's own doc is restored to its pre-gift snapshot.
            runCatching { userRepository.revertExpiredGiftGrants(uid) }
            // Round 12 (addendum 11): the one-time welcome gift rides the
            // same tick (after the revert, so an expired gift frees the
            // slot first). The repository re-reads the doc fresh and the
            // once-ever stamp lives on it, so account switches and
            // restarts can never re-fire it; the event is emitted only
            // when a gift was actually granted in this call.
            val welcomeGranted = runCatching {
                userRepository.grantWelcomeGiftIfEligible(uid)
            }.getOrDefault(false)
            if (welcomeGranted && authRepository.currentUserId == uid) {
                _welcomeGiftEvent.tryEmit(System.currentTimeMillis())
            }
            val inRegistry = runCatching { authRepository.isPhoneVerifiedInRegistry(uid) }
                .getOrDefault(false)
            if (inRegistry && authRepository.currentUserId == uid) {
                registryVerifiedUid = uid
                if (_startDestination.value == StartDestination.VerifyPhone) {
                    _startDestination.value = StartDestination.Home
                }
            }
        }
    }

    init {
        resolveInitialRouteFast()
        observePendingChannelInvite()
        observeBanAndProfileStatus()
    }

    private fun resolveInitialRouteFast() {
        viewModelScope.launch {
            // The start destination's initial value already came from LOCAL auth state
            // at construction, so the first frame never waits on this coroutine. What
            // happens here is refinement only — and even the local reads get a hard 3s
            // cap (round 4): if the auth-state flow or the DataStore cache stalls, the
            // route settles on the construction-time fallback (Home when a Firebase
            // user is cached, otherwise PhoneLogin) instead of lingering unresolved.
            // The Firestore verification further below stays fully asynchronous and
            // never gates the first screen.
            val uid = withTimeoutOrNull(3_000L) {
                authRepository.currentUserId ?: authRepository.observeAuthState().first()
            } ?: authRepository.currentUserId
            if (uid != null) {
                val cached = withTimeoutOrNull(3_000L) { prefs.settingsFlow.first() }
                // Fast cache-first navigation directly to Chats (Home) without any splash delay (Sec 3 & 4)
                if (cached != null) {
                    _startDestination.value = if (cached.cachedUid == uid && !cached.cachedProfileComplete) {
                        StartDestination.ProfileSetup
                    } else {
                        StartDestination.Home
                    }
                }

                // Background verification without blocking cold launch
                launch { authRepository.syncFcmToken() }
                // Round 13 (Addendum J): re-apply a queued profile
                // completion from a save that hit an infrastructure
                // failure — background only, never gates the first frame.
                launch { runCatching { authRepository.retryPendingProfileCompletion() } }
                launch {
                    val userResult = runCatching { userRepository.getUserOnce(uid) }
                    val user = userResult.getOrNull()
                    if (user != null) {
                        val now = System.currentTimeMillis()
                        val banActive = user.isBanned && (user.banType == "PERMANENT" || user.banExpiresAt == 0L || now <= user.banExpiresAt)
                        if (user.deletedAt > 0L || user.deletedPermanent || banActive) {
                            authRepository.signOut()
                            _startDestination.value = StartDestination.PhoneLogin
                            return@launch
                        }
                        val complete = (user.profileCompleted ||
                            (user.displayName.isNotBlank() && user.phoneNumber.isNotBlank() && user.birthDate.isNotBlank()))
                        prefs.setCachedStartupProfileState(uid, complete)
                        _startDestination.value = when {
                            !complete -> StartDestination.ProfileSetup
                            // Mandatory gate: profile done but number unverified.
                            // The Supabase registry is the authority — the
                            // doc's isPhoneVerified is only its mirror, so a
                            // lost mirror must not gate a proven-verified user.
                            !isGateVerified(
                                uid,
                                PhoneOtpLogic.isCompleteVerificationMirror(
                                    user.isPhoneVerified, user.phoneNumber, user.phoneHash
                                )
                            ) -> StartDestination.VerifyPhone
                            else -> StartDestination.Home
                        }
                    } else if (userResult.isSuccess) {
                        // Failover design §3.2/§3.3: a null read is NOT
                        // proof of "no profile" — under a quota-dead
                        // Firestore the read fails soft to null, and
                        // routing a returning user to ProfileSetup from
                        // it would re-create the lockout inside the app.
                        // Decide from the on-device snapshot instead;
                        // its ban/deleted fields are enforced, and the
                        // gate verdict is snapshot mirror OR live
                        // registry (the registry recheck below also
                        // covers the §3.4 cold case: no snapshot, but a
                        // registry row proves this account verified
                        // before → treat the profile as complete for
                        // routing and refine when Firestore returns).
                        val snapshot = runCatching { prefs.ownProfileSnapshot(uid) }.getOrNull()
                        when (OwnProfileSnapshot.entryDecision(
                            snapshot,
                            registryVerified = false,
                            nowMs = System.currentTimeMillis()
                        )) {
                            OwnProfileSnapshot.EntryDecision.REFUSE_DELETED,
                            OwnProfileSnapshot.EntryDecision.REFUSE_BANNED -> {
                                authRepository.signOut()
                                _startDestination.value = StartDestination.PhoneLogin
                            }
                            OwnProfileSnapshot.EntryDecision.ENTER_PROFILE_SETUP -> {
                                prefs.setCachedStartupProfileState(uid, false)
                                _startDestination.value = StartDestination.ProfileSetup
                            }
                            OwnProfileSnapshot.EntryDecision.ENTER -> {
                                prefs.setCachedStartupProfileState(uid, true)
                                _startDestination.value = StartDestination.Home
                            }
                            OwnProfileSnapshot.EntryDecision.ENTER_VERIFY_PHONE -> {
                                // §3.3 entry recheck: the snapshot mirror
                                // is incomplete, but the live registry may
                                // still prove verification (verified on
                                // another device).
                                if (isGateVerified(uid, docVerified = false)) {
                                    _startDestination.value = StartDestination.Home
                                } else {
                                    _startDestination.value = StartDestination.VerifyPhone
                                }
                            }
                            OwnProfileSnapshot.EntryDecision.NO_SNAPSHOT -> {
                                if (isGateVerified(uid, docVerified = false)) {
                                    _startDestination.value = StartDestination.Home
                                } else {
                                    prefs.setCachedStartupProfileState(uid, false)
                                    _startDestination.value = StartDestination.ProfileSetup
                                }
                            }
                        }
                    }
                }
            } else {
                _startDestination.value = StartDestination.PhoneLogin
            }
        }
    }

    private fun observeBanAndProfileStatus() {
        viewModelScope.launch {
            userRepository.observeCurrentUser().collect { user ->
                if (user == null) return@collect
                val now = System.currentTimeMillis()
                val banActive = user.isBanned && (user.banType == "PERMANENT" || user.banExpiresAt == 0L || now <= user.banExpiresAt)
                if (user.deletedAt > 0L || user.deletedPermanent || banActive) {
                    authRepository.signOut()
                    return@collect
                }
                // Mandatory phone-verification gate (phone phase, owner rule):
                // keep the destination in sync with the live user doc — an
                // unverified complete account is held at the gate, and the
                // moment verification is proven (the doc mirror flips to
                // isPhoneVerified=true, or the Supabase registry check comes
                // back positive) the destination flips to Home and the gate
                // lifts itself. Profile setup still comes first for
                // incomplete accounts.
                val complete = (user.profileCompleted ||
                    (user.displayName.isNotBlank() && user.phoneNumber.isNotBlank() && user.birthDate.isNotBlank()))
                when {
                    !complete -> _startDestination.value = StartDestination.ProfileSetup
                    PhoneOtpLogic.isCompleteVerificationMirror(
                        user.isPhoneVerified, user.phoneNumber, user.phoneHash
                    ) || registryVerifiedUid == user.uid -> {
                        registryVerifiedUid = user.uid
                        _startDestination.value = StartDestination.Home
                    }
                    else -> {
                        _startDestination.value = StartDestination.VerifyPhone
                        recheckRegistryInBackground(user.uid)
                    }
                }
            }
        }
    }

    fun setAppForeground(foreground: Boolean) {
        heartbeatJob?.cancel()
        if (foreground && authRepository.currentUserId != null) {
            heartbeatJob = viewModelScope.launch {
                while (isActive) {
                    runCatching { userRepository.updatePresenceHeartbeat(isOnline = true) }
                    delay(Constants.PRESENCE_HEARTBEAT_INTERVAL_MS)
                }
            }
        } else if (!foreground) {
            prefs.setHiddenChatsUnlockedSession(false)
            if (authRepository.currentUserId != null) {
                viewModelScope.launch {
                    runCatching { userRepository.updatePresenceHeartbeat(isOnline = false) }
                }
            }
        }
    }

    private fun observePendingChannelInvite() {
        viewModelScope.launch {
            combine(
                authUid,
                appSettings.map { it.pendingChannelInvite.trim() }
            ) { uid, code -> uid to code }
                .distinctUntilChanged()
                .collect { (uid, code) ->
                    if (!uid.isNullOrBlank() && code.isNotBlank()) {
                        val channel = runCatching { channelRepository.findChannelByInviteCode(code) }.getOrNull()
                        if (channel != null) {
                            _resolvedChannelId.value = channel.channelId
                        } else {
                            prefs.clearPendingChannelInvite()
                        }
                    }
                }
        }
    }

    fun handleIncomingUri(uri: Uri?) {
        if (uri == null) return
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path.orEmpty()

        val isJoinLink = (scheme == "wesal" && host == "join") || path.startsWith("/join")
        if (isJoinLink) {
            val inviteId = uri.getQueryParameter("id")?.trim().orEmpty()
                .ifBlank {
                    uri.pathSegments.lastOrNull()?.takeIf { it != "join" }.orEmpty()
                }
                .removePrefix("@")
            if (inviteId.isNotBlank()) {
                viewModelScope.launch { prefs.setPendingInviteId(inviteId) }
            }
            return
        }

        val isChannelLink = (scheme == "wesal" && host == "channel") || path.startsWith("/channel")
        if (isChannelLink) {
            val code = uri.getQueryParameter("code")?.trim().orEmpty()
                .ifBlank {
                    uri.pathSegments.lastOrNull()?.takeIf { it != "channel" }.orEmpty()
                }
            if (code.isNotBlank()) {
                viewModelScope.launch { prefs.setPendingChannelInvite(code) }
            }
        }
    }

    fun consumePendingInvite() {
        viewModelScope.launch { prefs.clearPendingInviteId() }
    }

    fun consumePendingChannelInvite() {
        _resolvedChannelId.value = null
        viewModelScope.launch { prefs.clearPendingChannelInvite() }
    }
}
