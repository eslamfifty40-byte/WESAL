package com.wesal.messenger.authentication

import android.app.Activity
import android.net.Uri
import android.util.Patterns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.PhoneAuthProvider
import com.wesal.messenger.core.network.NetworkMonitor
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.core.util.WesalIdUtils
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.domain.model.CountryCode
import com.wesal.messenger.domain.model.SavedWesalAccount
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.PhoneAuthEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val emailInput: String = "",
    val passwordInput: String = "",
    val isSignUpMode: Boolean = false,
    val selectedCountry: CountryCode = CountryCatalog.defaultEgypt,
    val phoneNumberInput: String = "",
    val fullPhoneE164: String = "",
    val verificationId: String = "",
    val otpCode: String = "",
    val countdownSeconds: Int = 0,
    val canResend: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
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

    val savedAccounts: StateFlow<List<SavedWesalAccount>> = authRepository.observeSavedAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    fun defaultDisplayName(): String = authRepository.currentUserDisplayName.orEmpty()

    fun updateEmailInput(email: String) {
        _uiState.update { it.copy(emailInput = email, errorMessage = null, infoMessage = null) }
    }

    fun updatePasswordInput(password: String) {
        _uiState.update { it.copy(passwordInput = password, errorMessage = null, infoMessage = null) }
    }

    fun toggleSignUpMode(signUp: Boolean) {
        _uiState.update { it.copy(isSignUpMode = signUp, errorMessage = null, infoMessage = null) }
    }

    fun setAuthError(message: String) {
        _uiState.update { it.copy(isLoading = false, errorMessage = message, infoMessage = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null, infoMessage = null) }
    }

    fun removeSavedAccount(uid: String) {
        viewModelScope.launch { authRepository.removeSavedAccount(uid) }
    }

    fun signInWithGoogleIdToken(idToken: String) {
        if (!networkMonitor.isCurrentlyConnected()) {
            _uiState.update { it.copy(errorMessage = "لا يوجد اتصال بالإنترنت، تحقق من الشبكة") }
            return
        }
        _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }
        viewModelScope.launch {
            authRepository.signInWithGoogleIdToken(idToken)
                .onSuccess { isNewProfile ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            navigateToProfileSetup = isNewProfile,
                            navigateToHome = !isNewProfile
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = err.localizedMessage ?: "تعذر تسجيل الدخول عبر Google"
                        )
                    }
                }
        }
    }

    fun submitEmailAuth() {
        if (!networkMonitor.isCurrentlyConnected()) {
            _uiState.update { it.copy(errorMessage = "لا يوجد اتصال بالإنترنت، تحقق من الشبكة") }
            return
        }
        val state = _uiState.value
        val email = state.emailInput.trim()
        val password = state.passwordInput
        if (email.isBlank() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            _uiState.update { it.copy(errorMessage = "أدخل بريداً إلكترونياً صحيحاً") }
            return
        }
        if (password.length < 6) {
            _uiState.update { it.copy(errorMessage = "كلمة المرور يجب أن تكون 6 أحرف على الأقل") }
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }
        viewModelScope.launch {
            val result = if (state.isSignUpMode) {
                authRepository.signUpWithEmail(email, password)
            } else {
                authRepository.signInWithEmail(email, password)
            }
            result.onSuccess { isNewProfile ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        navigateToProfileSetup = isNewProfile,
                        navigateToHome = !isNewProfile
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.localizedMessage ?: "تعذر إتمام المصادقة بالبريد الإلكتروني"
                    )
                }
            }
        }
    }

    fun sendPasswordReset(rawEmail: String = _uiState.value.emailInput) {
        if (!networkMonitor.isCurrentlyConnected()) {
            _uiState.update { it.copy(errorMessage = "لا يوجد اتصال بالإنترنت، تحقق من الشبكة") }
            return
        }
        val email = rawEmail.trim()
        if (email.isBlank() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            _uiState.update { it.copy(errorMessage = "أدخل بريدك الإلكتروني أولاً لاستعادة كلمة المرور") }
            return
        }
        _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }
        viewModelScope.launch {
            authRepository.sendPasswordReset(email)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            infoMessage = "تم إرسال رابط استعادة كلمة المرور إلى $email"
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = err.localizedMessage ?: "تعذر إرسال رابط استعادة كلمة المرور"
                        )
                    }
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

    fun completeProfile(
        displayName: String,
        bio: String,
        photoUri: Uri?,
        wesalId: String? = null
    ) {
        if (displayName.isBlank()) {
            _uiState.update { it.copy(errorMessage = "الاسم مطلوب لإتمام الحساب") }
            return
        }
        val cleanWesalId = wesalId?.takeIf { it.isNotBlank() }?.let { WesalIdUtils.normalize(it) }
        if (cleanWesalId != null && !WesalIdUtils.isValid(cleanWesalId)) {
            _uiState.update {
                it.copy(errorMessage = "معرف وصال (Wesal ID) يجب أن يتكون من 4 إلى 30 حرفاً بالإنجليزية أو أرقام أو _")
            }
            return
        }
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            authRepository.completeUserProfile(displayName, bio, photoUri, cleanWesalId)
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

    fun resetForNewLogin() {
        timerJob?.cancel()
        resendingToken = null
        _uiState.value = AuthUiState()
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
