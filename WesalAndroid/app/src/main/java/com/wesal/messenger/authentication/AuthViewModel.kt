package com.wesal.messenger.authentication

import android.content.Context
import android.net.Uri
import android.util.Patterns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wesal.messenger.core.network.NetworkMonitor
import com.wesal.messenger.core.util.Constants
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

    private companion object {
        // 20s: the repository now returns success right after authentication + the
        // skeleton check (side effects are fire-and-forget), so the old 45s ceiling
        // only ever masked a stalled stream.
        const val AUTH_TIMEOUT_MS = 20_000L
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
        locationGraceStartedAt: Long
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
            val result = authCall {
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
            }
            result.onSuccess {
                _uiState.update { it.copy(isLoading = false, navigateToHome = true) }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = err.localizedMessage ?: "تعذر حفظ الملف الشخصي")
                }
            }
        }
    }

    fun consumeSupportChatNavigation() {
        _uiState.update { it.copy(navigateToSupportChatId = null) }
    }

    fun resetForNewLogin() {
        _uiState.value = AuthUiState()
    }
}
