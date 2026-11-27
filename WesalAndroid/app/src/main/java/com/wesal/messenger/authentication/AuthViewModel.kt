package com.wesal.messenger.authentication

import android.content.Context
import android.net.Uri
import android.util.Patterns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wesal.messenger.core.network.NetworkMonitor
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.core.util.PendingProfileLogic
import com.wesal.messenger.core.util.PhoneOtpLogic
import com.wesal.messenger.core.util.PhoneUtils
import com.wesal.messenger.core.util.WesalIdUtils
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.domain.model.CountryCode
import com.wesal.messenger.domain.model.SavedWesalAccount
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.ChatRepository
import com.wesal.messenger.settings.WesalCredentialVault
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.Period
import javax.inject.Inject

data class AuthUiState(
    val fullNameInput: String = "",
    val emailInput: String = "",
    val passwordInput: String = "",
    val confirmPasswordInput: String = "",
    val termsAccepted: Boolean = false,
    /** "حفظ الدخول" (remember me, §3.1): ON by default; when ON the email/password are
     *  kept in the encrypted vault so this account can be fast-switched later (§3.2). */
    val rememberMe: Boolean = true,
    val isSignUpMode: Boolean = false,
    val selectedCountry: CountryCode = CountryCatalog.defaultEgypt,
    val phoneNumberInput: String = "",
    /** Phone sign-in option (phone phase): 0 = enter number, 1 = enter the OTP code. */
    val phoneAuthStep: Int = 0,
    val phoneOtpInput: String = "",
    /** Canonical E.164 the current OTP was sent to (step 1). */
    val phoneOtpE164: String = "",
    /** Delivery channel reported by the server for the current OTP ("whatsapp"/"sms"). */
    val phoneOtpChannel: String = "",
    val isPhoneBusy: Boolean = false,
    val phoneResendSecondsLeft: Int = 0,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val pendingSupportOnAuth: Boolean = false,
    val supportAuthPromptMessage: String? = null,
    val navigateToProfileSetup: Boolean = false,
    val navigateToHome: Boolean = false,
    val navigateToSupportChatId: String? = null
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val chatRepository: ChatRepository,
    private val prefs: UserPreferencesDataStore,
    private val networkMonitor: NetworkMonitor
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    /**
     * Hard ceiling for every auth operation. The repository bounds its own Firestore calls,
     * but this guarantees the loading spinner ALWAYS resolves — success, failure, or a clear
     * timeout message — and can never spin forever (the owner's "kill and reopen" symptom).
     */
    private suspend fun <T> authCall(block: suspend () -> Result<T>): Result<T> =
        withTimeoutOrNull(AUTH_TIMEOUT_MS) { block() }
            ?: Result.failure(
                IllegalStateException("انتهت مهلة الاتصال بالخادم، تحقق من الإنترنت وحاول مرة أخرى")
            )

    /**
     * Ceiling for the phone-OTP calls only: delivery may try WhatsApp and then
     * fall back to SMS server-side, which legitimately takes longer than the
     * standard auth ceiling. Still bounded — the spinner always resolves.
     */
    private suspend fun <T> phoneAuthCall(block: suspend () -> Result<T>): Result<T> =
        withTimeoutOrNull(PHONE_AUTH_TIMEOUT_MS) { block() }
            ?: Result.failure(
                IllegalStateException("انتهت مهلة الاتصال بالخادم، تحقق من الإنترنت وحاول مرة أخرى")
            )

    private var phoneResendJob: Job? = null

    private companion object {
        // 20s: the repository now returns success right after authentication + the
        // skeleton check (side effects are fire-and-forget), so the old 45s ceiling
        // only ever masked a stalled stream.
        const val AUTH_TIMEOUT_MS = 20_000L
        const val PHONE_AUTH_TIMEOUT_MS = 60_000L

        // Round 13 (Addendum J): profile completion gets its own ceiling,
        // aligned with the repository's internally bounded chain (phone
        // reservation + snapshot + duplicate check + queued write +
        // read-back) instead of the shared 20s auth ceiling that used to
        // pre-empt it and trap new users on the form.
        const val PROFILE_SAVE_TIMEOUT_MS = 60_000L
    }

    val savedAccounts: StateFlow<List<SavedWesalAccount>> = authRepository.observeSavedAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun defaultDisplayName(): String = authRepository.currentUserDisplayName.orEmpty()

    fun defaultWesalIdSuggestion(): String {
        val uid = authRepository.currentUserId.orEmpty()
        return if (uid.isNotBlank()) WesalIdUtils.generateDefault(uid) else ""
    }

    fun updateFullNameInput(name: String) {
        _uiState.update { it.copy(fullNameInput = name, errorMessage = null, infoMessage = null) }
    }

    fun updateEmailInput(email: String) {
        _uiState.update { it.copy(emailInput = email, errorMessage = null, infoMessage = null) }
    }

    fun updatePasswordInput(password: String) {
        _uiState.update { it.copy(passwordInput = password, errorMessage = null, infoMessage = null) }
    }

    fun updateConfirmPasswordInput(confirm: String) {
        _uiState.update { it.copy(confirmPasswordInput = confirm, errorMessage = null, infoMessage = null) }
    }

    fun toggleTermsAccepted(accepted: Boolean) {
        _uiState.update { it.copy(termsAccepted = accepted, errorMessage = null) }
    }

    fun toggleRememberMe(remember: Boolean) {
        _uiState.update { it.copy(rememberMe = remember, errorMessage = null) }
    }

    fun toggleSignUpMode(signUp: Boolean) {
        _uiState.update {
            it.copy(
                isSignUpMode = signUp,
                errorMessage = null,
                infoMessage = null
            )
        }
    }

    fun setAuthError(message: String) {
        _uiState.update { it.copy(isLoading = false, errorMessage = message, infoMessage = null) }
    }

    fun clearError() {
        _uiState.update {
            it.copy(
                errorMessage = null,
                infoMessage = null,
                supportAuthPromptMessage = null
            )
        }
    }

    fun dismissSupportPrompt() {
        _uiState.update { it.copy(supportAuthPromptMessage = null, pendingSupportOnAuth = false) }
    }

    fun removeSavedAccount(uid: String) {
        // The repository also deletes the account's stored credential (§3.2).
        viewModelScope.launch { authRepository.removeSavedAccount(uid) }
    }

    /**
     * Fast account switch from the login screen (§3.2). If the account has a stored
     * credential (remember-me was ON), switch in place: bounded sign-out of the current
     * account (if any) then sign-in — the repository returns right after authentication
     * + skeleton check, so this resolves in seconds, not the old sign-out(15s) +
     * sign-in(45s) chain. Without a stored credential, fall back to prefilling the
     * email so the user only types the password.
     */
    fun switchToSavedAccount(account: SavedWesalAccount) {
        val password = WesalCredentialVault.passwordForEmail(context, account.email)
        if (password.isNullOrEmpty()) {
            _uiState.update {
                it.copy(
                    isSignUpMode = false,
                    emailInput = account.email,
                    passwordInput = "",
                    errorMessage = null,
                    infoMessage = "أدخل كلمة المرور لإتمام تسجيل الدخول بهذا الحساب"
                )
            }
            return
        }
        if (!networkMonitor.isCurrentlyConnected()) {
            _uiState.update { it.copy(errorMessage = "لا يوجد اتصال بالإنترنت، تحقق من الشبكة") }
            return
        }
        _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }
        viewModelScope.launch {
            val result = authCall {
                val currentUid = authRepository.currentUserId
                if (currentUid != null && currentUid != account.uid) {
                    // Bounded inside the repository; local sign-out always completes.
                    authRepository.signOut()
                }
                authRepository.signInWithEmail(account.email, password)
            }
            result.onSuccess { needsSetup ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        navigateToProfileSetup = needsSetup,
                        navigateToHome = !needsSetup
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.localizedMessage ?: "تعذر تبديل الحساب"
                    )
                }
            }
        }
    }

    fun signInWithGoogleIdToken(idToken: String) {
        if (!networkMonitor.isCurrentlyConnected()) {
            _uiState.update { it.copy(errorMessage = "لا يوجد اتصال بالإنترنت، تحقق من الشبكة") }
            return
        }
        val openSupport = _uiState.value.pendingSupportOnAuth
        _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null, supportAuthPromptMessage = null) }
        viewModelScope.launch {
            authCall { authRepository.signInWithGoogleIdToken(idToken) }
                .onSuccess { needsSetup ->
                    if (openSupport) {
                        val supportRes = chatRepository.openSupportChatByNumericId(Constants.OWNER_ADMIN_NUMERIC_ID)
                        val chatId = supportRes.getOrNull()
                        if (!chatId.isNullOrBlank()) {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    pendingSupportOnAuth = false,
                                    navigateToSupportChatId = chatId
                                )
                            }
                            return@launch
                        }
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            pendingSupportOnAuth = false,
                            navigateToProfileSetup = needsSetup,
                            navigateToHome = !needsSetup
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
        val fullName = state.fullNameInput.trim()
        val email = state.emailInput.trim()
        val password = state.passwordInput
        val confirm = state.confirmPasswordInput

        if (state.isSignUpMode) {
            if (fullName.length < 2) {
                _uiState.update { it.copy(errorMessage = "أدخل الاسم الكامل (حرفان على الأقل)") }
                return
            }
        }
        if (email.isBlank() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            _uiState.update { it.copy(errorMessage = "أدخل بريداً إلكترونياً صحيحاً") }
            return
        }
        if (password.length < 6) {
            _uiState.update { it.copy(errorMessage = "كلمة المرور يجب أن تكون 6 أحرف على الأقل") }
            return
        }
        if (state.isSignUpMode) {
            if (password != confirm) {
                _uiState.update { it.copy(errorMessage = "كلمة المرور وتأكيد كلمة المرور غير متطابقتين") }
                return
            }
            if (!state.termsAccepted) {
                _uiState.update { it.copy(errorMessage = "يجب الموافقة على الشروط وسياسة الخصوصية للمتابعة") }
                return
            }
        }

        val openSupport = state.pendingSupportOnAuth
        _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null, supportAuthPromptMessage = null) }
        viewModelScope.launch {
            val result = authCall {
                if (state.isSignUpMode) {
                    authRepository.signUpWithEmail(email, password, fullName)
                } else {
                    authRepository.signInWithEmail(email, password)
                }
            }
            result.onSuccess { needsSetup ->
                // "حفظ الدخول" (§3.1): honour the checkbox only AFTER a successful auth.
                // ON  -> keep the credential in the encrypted vault for fast switching.
                // OFF -> make sure no stored credential remains for this account.
                val signedInUid = authRepository.currentUserId.orEmpty()
                if (state.rememberMe) {
                    if (signedInUid.isNotBlank()) {
                        WesalCredentialVault.save(context, signedInUid, email, password)
                    }
                } else {
                    WesalCredentialVault.removeByEmail(context, email)
                }
                if (openSupport) {
                    val supportRes = chatRepository.openSupportChatByNumericId(Constants.OWNER_ADMIN_NUMERIC_ID)
                    val chatId = supportRes.getOrNull()
                    if (!chatId.isNullOrBlank()) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                pendingSupportOnAuth = false,
                                navigateToSupportChatId = chatId
                            )
                        }
                        return@launch
                    }
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        pendingSupportOnAuth = false,
                        navigateToProfileSetup = needsSetup,
                        navigateToHome = !needsSetup
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
            authCall { authRepository.sendPasswordReset(email) }
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

    fun openSupportManagement10001() {
        if (!networkMonitor.isCurrentlyConnected()) {
            _uiState.update { it.copy(errorMessage = "لا يوجد اتصال بالإنترنت، تحقق من الشبكة") }
            return
        }
        _uiState.update { it.copy(isLoading = true, errorMessage = null, supportAuthPromptMessage = null) }
        viewModelScope.launch {
            val guestRes = authCall { authRepository.signInGuestForSupport() }
            if (guestRes.isSuccess) {
                val chatRes = chatRepository.openSupportChatByNumericId(Constants.OWNER_ADMIN_NUMERIC_ID)
                chatRes.onSuccess { chatId ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            navigateToSupportChatId = chatId
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = err.localizedMessage ?: "تعذر فتح محادثة الإدارة 10001"
                        )
                    }
                }
            } else {
                // Anonymous Auth disabled in Firebase Spark project; queue support opening right after login
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        pendingSupportOnAuth = true,
                        supportAuthPromptMessage = guestRes.exceptionOrNull()?.message
                            ?: "سجّل الدخول بحسابك أو عبر Google وسيتم فتح محادثة حساب الإدارة 10001 تلقائياً"
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

    /** The signed-in account's OTP-verified phone (phone-registered accounts), if any. */
    fun verifiedPhoneE164(): String? =
        authRepository.currentUserPhoneNumber?.takeIf { it.isNotBlank() }

    fun updatePhoneOtpInput(input: String) {
        _uiState.update {
            it.copy(phoneOtpInput = PhoneOtpLogic.sanitizeOtpInput(input), errorMessage = null)
        }
    }

    /**
     * Phone sign-in option, step 1: validate the typed number (Egypt +20 by
     * default via the selected country) and ask the server to deliver a code.
     * The server enforces the real rate limits; the countdown here is only a
     * UI mirror of its 60s resend interval.
     */
    fun sendPhoneOtp() {
        if (!networkMonitor.isCurrentlyConnected()) {
            _uiState.update { it.copy(errorMessage = "لا يوجد اتصال بالإنترنت، تحقق من الشبكة") }
            return
        }
        val state = _uiState.value
        if (state.isPhoneBusy || state.isLoading) return
        val validation = PhoneUtils.validateForCountry(state.selectedCountry, state.phoneNumberInput)
        val e164 = validation.e164
        if (!validation.isValid || e164.isNullOrBlank()) {
            _uiState.update {
                it.copy(errorMessage = validation.errorAr.ifBlank { "يرجى إدخال رقم هاتف صحيح" })
            }
            return
        }
        _uiState.update { it.copy(isPhoneBusy = true, errorMessage = null, infoMessage = null) }
        viewModelScope.launch {
            phoneAuthCall { authRepository.sendPhoneOtp(e164) }
                .onSuccess { channel ->
                    _uiState.update {
                        it.copy(
                            isPhoneBusy = false,
                            phoneAuthStep = 1,
                            phoneOtpE164 = e164,
                            phoneOtpChannel = channel,
                            phoneOtpInput = "",
                            // Round 12 (addendum 9): honest copy, as in
                            // VerifyPhoneViewModel — no guaranteed delivery.
                            infoMessage = "تم إرسال كود التحقق على ${PhoneOtpLogic.channelDisplayNameAr(channel)} " +
                                "لرقم ${PhoneOtpLogic.maskPhoneForDisplay(e164)} — الكود صالح ١٠ دقايق" +
                                if (channel.equals("whatsapp", ignoreCase = true)) {
                                    " — لو الكود موصلش، شوف التعليمات اللي تحت"
                                } else {
                                    ""
                                }
                        )
                    }
                    startPhoneResendCountdown()
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isPhoneBusy = false,
                            errorMessage = err.localizedMessage ?: "تعذر إرسال كود التحقق"
                        )
                    }
                }
        }
    }

    fun resendPhoneOtp() {
        if (_uiState.value.phoneResendSecondsLeft > 0) return
        sendPhoneOtp()
    }

    /**
     * Add-account back-out: the phone flow replaced the active session with
     * its anonymous phone-login session when the code was requested, so
     * abandon that session here (the replaced account stays in the
     * saved-accounts list for one-tap re-entry). No-op when no phone login
     * is in flight.
     */
    fun abandonPhoneLoginSession() {
        viewModelScope.launch {
            runCatching { authRepository.abandonPhoneLoginSession() }
        }
    }

    fun cancelPhoneOtp() {
        phoneResendJob?.cancel()
        _uiState.update {
            it.copy(
                phoneAuthStep = 0,
                phoneOtpInput = "",
                phoneOtpE164 = "",
                phoneOtpChannel = "",
                phoneResendSecondsLeft = 0,
                errorMessage = null,
                infoMessage = null
            )
        }
    }

    /** Phone sign-in option, step 2: verify the code with the Supabase
     *  backend — the anonymous phone-login session created at send time is
     *  the account, and a successful verify binds the number to it in the
     *  registry; routing matches the Google flow. */
    fun verifyPhoneOtp() {
        if (!networkMonitor.isCurrentlyConnected()) {
            _uiState.update { it.copy(errorMessage = "لا يوجد اتصال بالإنترنت، تحقق من الشبكة") }
            return
        }
        val state = _uiState.value
        if (state.isPhoneBusy || state.isLoading) return
        val code = PhoneOtpLogic.sanitizeOtpInput(state.phoneOtpInput)
        if (!PhoneOtpLogic.isCompleteOtp(code)) {
            _uiState.update { it.copy(errorMessage = "اكتب كود التحقق كامل (٦ أرقام)") }
            return
        }
        val e164 = state.phoneOtpE164
        if (e164.isBlank()) {
            cancelPhoneOtp()
            return
        }
        val openSupport = state.pendingSupportOnAuth
        _uiState.update { it.copy(isPhoneBusy = true, errorMessage = null, infoMessage = null, supportAuthPromptMessage = null) }
        viewModelScope.launch {
            phoneAuthCall { authRepository.verifyPhoneOtpAndSignIn(e164, code) }
                .onSuccess { needsSetup ->
                    phoneResendJob?.cancel()
                    if (openSupport) {
                        val supportRes = chatRepository.openSupportChatByNumericId(Constants.OWNER_ADMIN_NUMERIC_ID)
                        val chatId = supportRes.getOrNull()
                        if (!chatId.isNullOrBlank()) {
                            _uiState.update {
                                it.copy(
                                    isPhoneBusy = false,
                                    pendingSupportOnAuth = false,
                                    navigateToSupportChatId = chatId
                                )
                            }
                            return@launch
                        }
                    }
                    _uiState.update {
                        it.copy(
                            isPhoneBusy = false,
                            pendingSupportOnAuth = false,
                            navigateToProfileSetup = needsSetup,
                            navigateToHome = !needsSetup
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isPhoneBusy = false,
                            errorMessage = err.localizedMessage ?: "تعذر التحقق من الكود"
                        )
                    }
                }
        }
    }

    private fun startPhoneResendCountdown() {
        phoneResendJob?.cancel()
        _uiState.update { it.copy(phoneResendSecondsLeft = PhoneOtpLogic.RESEND_COOLDOWN_SECONDS) }
        phoneResendJob = viewModelScope.launch {
            while (true) {
                delay(1_000L)
                val left = _uiState.value.phoneResendSecondsLeft
                if (left <= 1) {
                    _uiState.update { it.copy(phoneResendSecondsLeft = 0) }
                    break
                }
                _uiState.update { it.copy(phoneResendSecondsLeft = left - 1) }
            }
        }
    }

    fun completeProfile(
        displayName: String,
        bio: String,
        photoUri: Uri?,
        wesalId: String?,
        country: CountryCode,
        localPhone: String,
        birthDateIso: String,
        hideBirthDate: Boolean,
        locationGranted: Boolean,
        locationGraceStartedAt: Long,
        inviterId: String = ""
    ) {
        if (displayName.trim().length < 2) {
            _uiState.update { it.copy(errorMessage = "الاسم الظاهر مطلوب (حرفان على الأقل)") }
            return
        }
        val cleanWesalId = wesalId?.trim()?.takeIf { it.isNotBlank() }?.let { WesalIdUtils.normalize(it) }
        if (cleanWesalId.isNullOrBlank() || !WesalIdUtils.isValid(cleanWesalId)) {
            _uiState.update {
                it.copy(errorMessage = "معرف وصال (@Username) إلزامي: 4 إلى 30 حرفاً بالإنجليزية أو أرقام أو _")
            }
            return
        }
        val phoneResult = PhoneUtils.validateForCountry(country, localPhone)
        val e164Phone = phoneResult.e164
        if (!phoneResult.isValid || e164Phone.isNullOrBlank()) {
            _uiState.update {
                it.copy(errorMessage = phoneResult.errorAr ?: "يرجى إدخال رقم هاتف صحيح")
            }
            return
        }
        val parsedDob = runCatching { LocalDate.parse(birthDateIso.trim()) }.getOrNull()
        if (parsedDob == null) {
            _uiState.update { it.copy(errorMessage = "يرجى اختيار تاريخ الميلاد لإتمام الحساب") }
            return
        }
        val today = LocalDate.now()
        if (parsedDob.isAfter(today)) {
            _uiState.update { it.copy(errorMessage = "لا يمكن أن يكون تاريخ الميلاد في المستقبل") }
            return
        }
        if (Period.between(parsedDob, today).years < Constants.MIN_AGE_YEARS) {
            _uiState.update { it.copy(errorMessage = "يجب أن يكون العمر ${Constants.MIN_AGE_YEARS} سنة على الأقل") }
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            // Persist the optional hand-typed inviter id BEFORE the repository call:
            // AuthRepositoryImpl.completeUserProfile reads it from DataStore and stamps
            // the referral attribution once the profile writes succeed. Sequential in
            // this same coroutine, so the write is guaranteed to land first.
            val cleanInviterId = inviterId.trim().removePrefix("@")
            if (cleanInviterId.isNotBlank()) {
                runCatching { prefs.setManualInviteId(cleanInviterId) }
            }
            // Round 13 (Addendum J): the save gets a ceiling aligned with
            // the repository's own bounded chain (the old shared 20s auth
            // ceiling fired while the repository was still legitimately
            // working, and its failure trapped the user on this form).
            val result = withTimeoutOrNull(PROFILE_SAVE_TIMEOUT_MS) {
                authRepository.completeUserProfile(
                    displayName = displayName,
                    bio = bio,
                    photoUri = photoUri,
                    wesalId = cleanWesalId,
                    phoneE164 = e164Phone,
                    birthDate = birthDateIso.trim(),
                    hideBirthDate = hideBirthDate,
                    locationGranted = locationGranted,
                    locationGraceStartedAt = locationGraceStartedAt
                )
            } ?: Result.failure(
                IllegalStateException("انتهت مهلة الاتصال بالخادم، تحقق من الإنترنت وحاول مرة أخرى")
            )
            result.onSuccess {
                runCatching { prefs.setPendingProfileJson(null) }
                _uiState.update { it.copy(isLoading = false, navigateToHome = true) }
            }.onFailure { err ->
                if (PendingProfileLogic.isUserFixable(err)) {
                    // Duplicate @ID / a phone owned by another account / a
                    // banned number: the user fixes it here, with the
                    // exact message — queueing a claim that can never
                    // land would be a silent lie.
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = err.localizedMessage ?: "تعذر حفظ الملف الشخصي")
                    }
                } else {
                    // INFRASTRUCTURE failure (timeout / quota / network):
                    // a registration save must never dead-end. Persist
                    // the form locally, stamp the startup cache complete,
                    // enter the app NOW, and let the background retry
                    // (immediately, and again on every launch) commit it.
                    val uid = authRepository.currentUserId.orEmpty()
                    if (uid.isNotBlank()) {
                        runCatching {
                            prefs.setPendingProfileJson(
                                PendingProfileLogic.toJson(
                                    PendingProfileLogic.PendingProfile(
                                        uid = uid,
                                        displayName = displayName.trim(),
                                        bio = bio.trim(),
                                        wesalId = cleanWesalId,
                                        phoneE164 = e164Phone,
                                        birthDateIso = birthDateIso.trim(),
                                        hideBirthDate = hideBirthDate,
                                        locationGranted = locationGranted,
                                        locationGraceStartedAt = locationGraceStartedAt,
                                        photoUriString = photoUri?.toString().orEmpty(),
                                        savedAtMs = System.currentTimeMillis()
                                    )
                                )
                            )
                            prefs.setCachedStartupProfileState(uid, true)
                        }
                        viewModelScope.launch {
                            runCatching { authRepository.retryPendingProfileCompletion() }
                        }
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            infoMessage = "سيتم حفظ بياناتك تلقائياً — بنكمّل الحفظ في الخلفية",
                            navigateToHome = true
                        )
                    }
                }
            }
        }
    }

    fun consumeSupportChatNavigation() {
        _uiState.update { it.copy(navigateToSupportChatId = null) }
    }

    fun resetForNewLogin() {
        phoneResendJob?.cancel()
        _uiState.value = AuthUiState()
    }
}
