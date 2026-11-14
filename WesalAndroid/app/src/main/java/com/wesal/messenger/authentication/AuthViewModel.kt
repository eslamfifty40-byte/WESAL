package com.wesal.messenger.authentication

import android.app.Activity
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.PhoneAuthProvider
import com.wesal.messenger.core.network.NetworkMonitor
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.domain.model.CountryCode
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.PhoneAuthEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val selectedCountry: CountryCode = CountryCatalog.defaultEgypt,
    val phoneNumberInput: String = "",
    val fullPhoneE164: String = "",
    val verificationId: String = "",
    val otpCode: String = "",
    val countdownSeconds: Int = 0,
    val canResend: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val navigateToOtp: Boolean = false,
    val navigateToProfileSetup: Boolean = false,
    val navigateToHome: Boolean = false
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val prefs: UserPreferencesDataStore,
    private val networkMonitor: NetworkMonitor
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private var resendingToken: PhoneAuthProvider.ForceResendingToken? = null
    private var timerJob: Job? = null

    init {
        viewModelScope.launch {
            val saved = prefs.settingsFlow.first()
            if (saved.pendingVerificationId.isNotBlank() && saved.pendingPhoneNumber.isNotBlank()) {
                val elapsedSec = ((System.currentTimeMillis() - saved.pendingOtpTimestamp) / 1000L).toInt()
                val remaining = (Constants.OTP_TIMEOUT_SECONDS.toInt() - elapsedSec).coerceAtLeast(0)
                _uiState.update {
                    it.copy(
                        verificationId = saved.pendingVerificationId,
                        fullPhoneE164 = saved.pendingPhoneNumber
                    )
                }
                if (remaining > 0) startCountdown(remaining) else _uiState.update { it.copy(canResend = true) }
            }
        }
    }

    fun selectCountry(country: CountryCode) {
        _uiState.update { it.copy(selectedCountry = country, errorMessage = null) }
    }

    fun updatePhoneInput(input: String) {
        val digitsOnly = input.filter { it.isDigit() }
        _uiState.update { it.copy(phoneNumberInput = digitsOnly, errorMessage = null) }
    }

    fun updateOtpCode(code: String) {
        val digits = code.filter { it.isDigit() }.take(6)
        _uiState.update { it.copy(otpCode = digits, errorMessage = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun sendVerificationCode(activity: Activity, isResend: Boolean = false) {
        if (!networkMonitor.isCurrentlyConnected()) {
            _uiState.update { it.copy(errorMessage = "لا يوجد اتصال بالإنترنت، تحقق من الشبكة") }
            return
        }

        val state = _uiState.value
        val fullPhone = if (isResend && state.fullPhoneE164.isNotBlank()) {
            state.fullPhoneE164
        } else {
            val raw = state.phoneNumberInput.trimStart('0')
            if (raw.length < 7) {
                _uiState.update { it.copy(errorMessage = "يرجى إدخال رقم هاتف صحيح") }
                return
            }
            "${state.selectedCountry.dialCode}$raw"
        }

        _uiState.update { it.copy(isLoading = true, errorMessage = null, fullPhoneE164 = fullPhone) }

        viewModelScope.launch {
            authRepository.sendOtp(
                phoneNumber = fullPhone,
                activity = activity,
                resendingToken = if (isResend) resendingToken else null
            ).collect { event ->
                when (event) {
                    is PhoneAuthEvent.CodeSent -> {
                        resendingToken = event.resendingToken
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                verificationId = event.verificationId,
                                navigateToOtp = true,
                                canResend = false
                            )
                        }
                        startCountdown(Constants.OTP_TIMEOUT_SECONDS.toInt())
                    }
                    is PhoneAuthEvent.Verified -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                navigateToProfileSetup = event.isNewProfile,
                                navigateToHome = !event.isNewProfile
                            )
                        }
                    }
                    is PhoneAuthEvent.Error -> {
                        _uiState.update {
                            it.copy(isLoading = false, errorMessage = event.messageAr)
                        }
                    }
                }
            }
        }
    }

    fun verifyOtpCode() {
        val state = _uiState.value
        if (state.otpCode.length != 6) {
            _uiState.update { it.copy(errorMessage = "أدخل رمز التحقق المكون من 6 أرقام") }
            return
        }
        if (state.verificationId.isBlank()) {
            _uiState.update { it.copy(errorMessage = "انتهت صلاحية الجلسة، اطلب رمزاً جديداً") }
            return
        }
        if (!networkMonitor.isCurrentlyConnected()) {
            _uiState.update { it.copy(errorMessage = "لا يوجد اتصال بالإنترنت") }
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val result = authRepository.verifyOtpCode(state.verificationId, state.otpCode)
            result.onSuccess { isNewProfile ->
                timerJob?.cancel()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        navigateToProfileSetup = isNewProfile,
                        navigateToHome = !isNewProfile
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = err.message ?: "رمز التحقق غير صحيح")
                }
            }
        }
    }

    fun completeProfile(displayName: String, bio: String, photoUri: Uri?, phone: String = "") {
        if (displayName.isBlank()) {
            _uiState.update { it.copy(errorMessage = "الاسم مطلوب لإتمام الحساب") }
            return
        }
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            authRepository.completeUserProfile(displayName, bio, photoUri, phone)
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false, navigateToHome = true) }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = err.localizedMessage ?: "تعذر حفظ الملف الشخصي")
                    }
                }
        }
    }

    fun cancelPendingOtp() {
        viewModelScope.launch {
            prefs.clearPendingOtp()
            timerJob?.cancel()
            _uiState.update {
                it.copy(
                    verificationId = "",
                    otpCode = "",
                    navigateToOtp = false,
                    errorMessage = null
                )
            }
        }
    }

    private fun startCountdown(seconds: Int) {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            for (sec in seconds downTo 1) {
                _uiState.update { it.copy(countdownSeconds = sec, canResend = false) }
                delay(1000L)
            }
            _uiState.update { it.copy(countdownSeconds = 0, canResend = true) }
        }
    }
}
