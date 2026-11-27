package com.wesal.messenger.authentication

import com.wesal.messenger.core.util.ErrorCopy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wesal.messenger.core.network.NetworkMonitor
import com.wesal.messenger.core.util.PhoneOtpLogic
import com.wesal.messenger.core.util.PhoneUtils
import com.wesal.messenger.domain.model.CountryCode
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class VerifyPhoneUiState(
    /** 0 = enter number, 1 = enter the OTP code. */
    val step: Int = 0,
    val selectedCountry: CountryCode = CountryCatalog.defaultEgypt,
    val phoneNumberInput: String = "",
    /** True when the number was pre-filled from the account's stored phone. */
    val prefilledFromAccount: Boolean = false,
    val otpInput: String = "",
    /** Canonical E.164 the current code was sent to (step 1). */
    val sentPhoneE164: String = "",
    val sentChannel: String = "",
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val resendSecondsLeft: Int = 0,
    val verified: Boolean = false
)

/**
 * Mandatory phone-verification gate (phone phase, owner rule 2026-11-27):
 * EVERY signed-in account whose number is not verified lands here before
 * the app opens — Gmail/email accounts and existing unverified accounts pass
 * it once; admins are not exempt. The flow never deletes or blocks the
 * account: it only completes verification (send code → verify code). The
 * Supabase verification backend binds the number to the account in its
 * registry (the authority the gate consults), and the client mirrors phone
 * + isPhoneVerified + phoneHash + the phone_uniqueness lock into Firestore.
 * Signing out is the only way off this screen.
 */
@HiltViewModel
class VerifyPhoneViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val networkMonitor: NetworkMonitor
) : ViewModel() {

    private val _uiState = MutableStateFlow(VerifyPhoneUiState())
    val uiState: StateFlow<VerifyPhoneUiState> = _uiState.asStateFlow()

    private var resendJob: Job? = null
    private var prefilled = false

    init {
        viewModelScope.launch {
            userRepository.observeCurrentUser().collect { user ->
                if (user == null) return@collect
                if (PhoneOtpLogic.isCompleteVerificationMirror(
                        user.isPhoneVerified, user.phoneNumber, user.phoneHash
                    )
                ) {
                    // The Firestore mirror stamp landed complete (or the
                    // account was already verified): MainViewModel's gate
                    // observer routes home (it also accepts the registry
                    // directly). A flag-only mirror (verified=true with a
                    // blank number/hash — the production owner doc) must NOT
                    // mark this screen verified: the gate would hold the
                    // account here while this VM claimed success.
                    _uiState.update { it.copy(verified = true) }
                } else if (!prefilled) {
                    // Pre-fill the number already on the account so the user
                    // just confirms it with the code; they may change it.
                    // Falls back to the legacy phoneDigits field when
                    // phoneNumber was left blank by older writers
                    // ("201122072195" → +201122072195).
                    val prefillE164 = PhoneOtpLogic.verificationPrefillE164(
                        user.phoneNumber, user.phoneDigits
                    )
                    if (prefillE164 != null) {
                        prefilled = true
                        val (country, local) = CountryCatalog.detectFromE164(prefillE164)
                        _uiState.update {
                            it.copy(
                                selectedCountry = country,
                                phoneNumberInput = local.filter { c -> c.isDigit() },
                                prefilledFromAccount = true
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun <T> gateCall(block: suspend () -> Result<T>): Result<T> =
        withTimeoutOrNull(60_000L) { block() }
            ?: Result.failure(
                IllegalStateException("انتهت مهلة الاتصال بالخادم، تحقق من الإنترنت وحاول مرة أخرى")
            )

    fun selectCountry(country: CountryCode) {
        _uiState.update { it.copy(selectedCountry = country, errorMessage = null) }
    }

    fun updatePhoneInput(input: String) {
        _uiState.update {
            it.copy(
                phoneNumberInput = input.filter { c -> c.isDigit() },
                prefilledFromAccount = false,
                errorMessage = null
            )
        }
    }

    fun updateOtpInput(input: String) {
        _uiState.update {
            it.copy(otpInput = PhoneOtpLogic.sanitizeOtpInput(input), errorMessage = null)
        }
    }

    fun sendCode() {
        if (!networkMonitor.isCurrentlyConnected()) {
            _uiState.update { it.copy(errorMessage = "لا يوجد اتصال بالإنترنت، تحقق من الشبكة") }
            return
        }
        val state = _uiState.value
        if (state.isBusy) return
        val validation = PhoneUtils.validateForCountry(state.selectedCountry, state.phoneNumberInput)
        val e164 = validation.e164
        if (!validation.isValid || e164.isNullOrBlank()) {
            _uiState.update {
                it.copy(errorMessage = validation.errorAr.ifBlank { "يرجى إدخال رقم هاتف صحيح" })
            }
            return
        }
        _uiState.update { it.copy(isBusy = true, errorMessage = null, infoMessage = null) }
        viewModelScope.launch {
            gateCall { authRepository.sendPhoneOtpForCurrentUser(e164) }
                .onSuccess { channel ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            step = 1,
                            sentPhoneE164 = e164,
                            sentChannel = channel,
                            otpInput = "",
                            // Round 12 (addendum 9): never claim guaranteed
                            // delivery — the send API accepts even when Meta
                            // later refuses a closed-window WhatsApp message.
                            infoMessage = "تم إرسال كود التحقق على ${PhoneOtpLogic.channelDisplayNameAr(channel)} " +
                                "لرقم ${PhoneOtpLogic.maskPhoneForDisplay(e164)} — الكود صالح ١٠ دقايق" +
                                if (channel.equals("whatsapp", ignoreCase = true)) {
                                    " — لو الكود موصلش، شوف التعليمات اللي تحت"
                                } else {
                                    ""
                                }
                        )
                    }
                    startResendCountdown()
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            errorMessage = ErrorCopy.friendly(err, "تعذر إرسال كود التحقق")
                        )
                    }
                }
        }
    }

    fun resendCode() {
        if (_uiState.value.resendSecondsLeft > 0) return
        sendCode()
    }

    fun backToNumberStep() {
        resendJob?.cancel()
        _uiState.update {
            it.copy(
                step = 0,
                otpInput = "",
                sentPhoneE164 = "",
                sentChannel = "",
                resendSecondsLeft = 0,
                errorMessage = null,
                infoMessage = null
            )
        }
    }

    fun verify() {
        if (!networkMonitor.isCurrentlyConnected()) {
            _uiState.update { it.copy(errorMessage = "لا يوجد اتصال بالإنترنت، تحقق من الشبكة") }
            return
        }
        val state = _uiState.value
        if (state.isBusy) return
        val code = PhoneOtpLogic.sanitizeOtpInput(state.otpInput)
        if (!PhoneOtpLogic.isCompleteOtp(code)) {
            _uiState.update { it.copy(errorMessage = "اكتب كود التحقق كامل (٦ أرقام)") }
            return
        }
        val e164 = state.sentPhoneE164
        if (e164.isBlank()) {
            backToNumberStep()
            return
        }
        _uiState.update { it.copy(isBusy = true, errorMessage = null, infoMessage = null) }
        viewModelScope.launch {
            gateCall { authRepository.verifyPhoneOtpForCurrentUser(e164, code) }
                .onSuccess {
                    resendJob?.cancel()
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            verified = true,
                            infoMessage = "تمام! رقمك اتوثّق بنجاح"
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            errorMessage = ErrorCopy.friendly(err, "تعذر التحقق من الكود")
                        )
                    }
                }
        }
    }

    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }

    private fun startResendCountdown() {
        resendJob?.cancel()
        _uiState.update { it.copy(resendSecondsLeft = PhoneOtpLogic.RESEND_COOLDOWN_SECONDS) }
        resendJob = viewModelScope.launch {
            while (true) {
                delay(1_000L)
                val left = _uiState.value.resendSecondsLeft
                if (left <= 1) {
                    _uiState.update { it.copy(resendSecondsLeft = 0) }
                    break
                }
                _uiState.update { it.copy(resendSecondsLeft = left - 1) }
            }
        }
    }
}
