package com.wesal.messenger.presentation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wesal.messenger.core.network.NetworkMonitor
import com.wesal.messenger.data.local.AppUserSettings
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.domain.model.CallRecord
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.CallRepository
import com.wesal.messenger.domain.repository.ChannelRepository
import com.wesal.messenger.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface StartDestination {
    data object Loading : StartDestination
    data object PhoneLogin : StartDestination
    data object OtpPending : StartDestination
    data object ProfileSetup : StartDestination
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

    private val _startDestination = MutableStateFlow<StartDestination>(StartDestination.Loading)
    val startDestination: StateFlow<StartDestination> = _startDestination.asStateFlow()

    init {
        resolveInitialRoute()
        observePendingChannelInvite()
    }

    private fun resolveInitialRoute() {
        viewModelScope.launch {
            val uid = authRepository.observeAuthState().first()
            if (uid != null) {
                launch { authRepository.syncFcmToken() }
                val userResult = runCatching { userRepository.getUserOnce(uid) }
                val user = userResult.getOrNull()
                _startDestination.value = when {
                    user != null && user.displayName.isBlank() -> StartDestination.ProfileSetup
                    userResult.isSuccess && user == null -> StartDestination.ProfileSetup
                    else -> StartDestination.Home
                }
            } else {
                _startDestination.value = StartDestination.PhoneLogin
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
