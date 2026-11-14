package com.wesal.messenger.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wesal.messenger.core.network.NetworkMonitor
import com.wesal.messenger.data.local.AppUserSettings
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.domain.model.CallRecord
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.CallRepository
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
    private val prefs: UserPreferencesDataStore,
    networkMonitor: NetworkMonitor
) : ViewModel() {

    val appSettings: StateFlow<AppUserSettings> = prefs.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppUserSettings())

    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val incomingCall: StateFlow<CallRecord?> = callRepository.observeIncomingCalls()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _startDestination = MutableStateFlow<StartDestination>(StartDestination.Loading)
    val startDestination: StateFlow<StartDestination> = _startDestination.asStateFlow()

    init {
        resolveInitialRoute()
    }

    private fun resolveInitialRoute() {
        viewModelScope.launch {
            val uid = authRepository.currentUserId
            if (uid != null) {
                authRepository.syncFcmToken()
                val user = userRepository.getUserOnce(uid)
                _startDestination.value = if (user == null || user.displayName.isBlank()) {
                    StartDestination.ProfileSetup
                } else {
                    StartDestination.Home
                }
            } else {
                val saved = prefs.settingsFlow.first()
                _startDestination.value = if (saved.pendingVerificationId.isNotBlank()) {
                    StartDestination.OtpPending
                } else {
                    StartDestination.PhoneLogin
                }
            }
        }
    }
}
