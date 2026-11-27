package com.wesal.messenger.presentation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wesal.messenger.core.network.NetworkMonitor
import com.wesal.messenger.core.util.Constants
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

    private val _resolvedChannelId = MutableStateFlow<String?>(null)
    val resolvedChannelId: StateFlow<String?> = _resolvedChannelId.asStateFlow()

    private val _startDestination = MutableStateFlow<StartDestination>(
        if (authRepository.currentUserId != null) StartDestination.Home else StartDestination.PhoneLogin
    )
    val startDestination: StateFlow<StartDestination> = _startDestination.asStateFlow()

    private var heartbeatJob: Job? = null

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
                            !user.isPhoneVerified -> StartDestination.VerifyPhone
                            else -> StartDestination.Home
                        }
                    } else if (userResult.isSuccess) {
                        prefs.setCachedStartupProfileState(uid, false)
                        _startDestination.value = StartDestination.ProfileSetup
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
                // moment the server stamp lands (isPhoneVerified=true) the
                // destination flips to Home and the gate lifts itself. Profile
                // setup still comes first for incomplete accounts.
                val complete = (user.profileCompleted ||
                    (user.displayName.isNotBlank() && user.phoneNumber.isNotBlank() && user.birthDate.isNotBlank()))
                _startDestination.value = when {
                    !complete -> StartDestination.ProfileSetup
                    !user.isPhoneVerified -> StartDestination.VerifyPhone
                    else -> StartDestination.Home
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
