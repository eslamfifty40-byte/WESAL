package com.wesal.messenger.settings

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.core.util.LocaleManager
import com.wesal.messenger.core.util.VerificationSealType
import com.wesal.messenger.core.util.WesalFrameLibrary
import com.wesal.messenger.data.local.AppUserSettings
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.domain.model.AdminPermissions
import com.wesal.messenger.domain.model.DeviceSession
import com.wesal.messenger.domain.model.PrivacySettings
import com.wesal.messenger.domain.model.PrivacyVisibility
import com.wesal.messenger.domain.model.SavedWesalAccount
import com.wesal.messenger.domain.model.SubscriptionRequest
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.ChatRepository
import com.wesal.messenger.domain.repository.UserRepository
import com.wesal.messenger.presentation.components.WesalAvatar
import com.wesal.messenger.presentation.components.WesalNameWithBadge
import com.wesal.messenger.presentation.components.WesalVerifiedBadge
import com.wesal.messenger.presentation.components.wesalImageModel
import com.wesal.messenger.presentation.components.wesalNeonTextStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val chatRepository: ChatRepository,
    private val prefs: UserPreferencesDataStore
) : ViewModel() {

    val currentUser: StateFlow<WesalUser?> = userRepository.observeCurrentUser()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isSuperAdmin: StateFlow<Boolean> = userRepository.observeIsSuperAdmin()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val adminPermissions: StateFlow<AdminPermissions> = userRepository.observeMyAdminPermissions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AdminPermissions(active = false))

    val mySubscriptionRequests: StateFlow<List<SubscriptionRequest>> = userRepository.observeMySubscriptionRequests()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val appSettings: StateFlow<AppUserSettings> = prefs.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppUserSettings())

    val sessions: StateFlow<List<DeviceSession>> = userRepository.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedAccounts: StateFlow<List<SavedWesalAccount>> = authRepository.observeSavedAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isSwitchingAccount = MutableStateFlow(false)
    val isDeletingAccount = MutableStateFlow(false)
    val isSubmittingSubscription = MutableStateFlow(false)
    val statusMessage = MutableStateFlow<String?>(null)
    val sessionLinkToken = MutableStateFlow<String?>(null)

    fun submitSubscriptionRequest(
        requestType: String,
        desiredSeal: String,
        idUri: Uri,
        note: String,
        isEn: Boolean,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            isSubmittingSubscription.value = true
            userRepository.submitSubscriptionRequest(requestType, desiredSeal, idUri, note)
                .onSuccess {
                    isSubmittingSubscription.value = false
                    statusMessage.value = if (isEn) {
                        "Request submitted! WESAL Admin will review your ID."
                    } else {
                        "تم إرسال طلب الاشتراك والتوثيق بنجاح للمراجعة الإدارية"
                    }
                    onSuccess()
                }
                .onFailure { err ->
                    isSubmittingSubscription.value = false
                    statusMessage.value = err.localizedMessage
                        ?: if (isEn) "Failed to submit request" else "تعذر إرسال الطلب؛ حاول مجددًا"
                }
        }
    }

    fun hasHiddenChatsPassword(): Boolean {
        val uid = authRepository.currentUserId.orEmpty()
        return prefs.hasHiddenChatsPassword(uid)
    }

    fun getHiddenChatsRecoveryQuestion(): String {
        val uid = authRepository.currentUserId.orEmpty()
        return prefs.getHiddenChatsRecoveryQuestion(uid)
    }

    fun openHiddenChatsFromSettings(onOpenHiddenChatsTab: () -> Unit) {
        prefs.requestOpenHiddenChats()
        onOpenHiddenChatsTab()
    }

    fun setupHiddenChatsPassword(
        currentPassword: String,
        newPassword: String,
        recoveryQuestion: String,
        recoveryAnswer: String,
        isEn: Boolean
    ): Boolean {
        val uid = authRepository.currentUserId.orEmpty()
        if (uid.isBlank()) return false
        val cleanNew = prefs.normalizeHiddenPinInput(newPassword)
        if (prefs.hasHiddenChatsPassword(uid) && !prefs.verifyHiddenChatsPassword(uid, currentPassword)) {
            statusMessage.value = if (isEn) "Current password is incorrect" else "كلمة المرور الحالية غير صحيحة"
            return false
        }
        if (cleanNew.length < 4 || !cleanNew.any { it.isLetterOrDigit() }) {
            statusMessage.value = if (isEn) "Use at least 4 letters or numbers" else "استخدم 4 حروف أو أرقام على الأقل"
            return false
        }
        prefs.setHiddenChatsPassword(uid, cleanNew, recoveryQuestion, recoveryAnswer)
        statusMessage.value = if (isEn) "Hidden chats password saved" else "تم حفظ كلمة مرور المحادثات المخفية"
        return true
    }

    fun resetHiddenChatsWithRecovery(answer: String, isEn: Boolean): Boolean {
        val uid = authRepository.currentUserId.orEmpty()
        if (!prefs.verifyHiddenChatsRecoveryAnswer(uid, answer)) {
            statusMessage.value = if (isEn) "Recovery answer does not match" else "إجابة سؤال الاستعادة غير صحيحة"
            return false
        }
        viewModelScope.launch {
            prefs.clearHiddenChatsProtectionAndUnhideAll(uid)
            statusMessage.value = if (isEn) "Hidden chats unlocked and reset" else "تمت إعادة تعيين القفل وإظهار المحادثات المخفية"
        }
        return true
    }

    fun forceResetHiddenChats(isEn: Boolean) {
        val uid = authRepository.currentUserId.orEmpty()
        viewModelScope.launch {
            prefs.clearHiddenChatsProtectionAndUnhideAll(uid)
            statusMessage.value = if (isEn) "Lock cleared and hidden chats restored" else "تم حذف كلمة المرور وإظهار جميع المحادثات"
        }
    }

    fun removeSavedAccount(uid: String) {
        viewModelScope.launch { authRepository.removeSavedAccount(uid) }
    }

    fun switchToGoogleIdToken(idToken: String, isEn: Boolean, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            isSwitchingAccount.value = true
            authRepository.signInWithGoogleIdToken(idToken)
                .onSuccess { isNewProfile ->
                    isSwitchingAccount.value = false
                    statusMessage.value = if (isEn) "Account switched successfully" else "تم تبديل الحساب بنجاح"
                    onComplete(isNewProfile)
                }
                .onFailure { err ->
                    isSwitchingAccount.value = false
                    statusMessage.value = err.localizedMessage ?: if (isEn) "Failed to switch Google account" else "تعذر تبديل حساب Google"
                }
        }
    }

    fun openSupportChatWith10001(isEn: Boolean, onOpenChat: (String) -> Unit) {
        viewModelScope.launch {
            chatRepository.openSupportChatByNumericId(Constants.OWNER_ADMIN_NUMERIC_ID)
                .onSuccess(onOpenChat)
                .onFailure { err ->
                    statusMessage.value = err.localizedMessage ?: if (isEn) "Unable to reach WESAL Support" else "تعذر فتح محادثة إدارة WESAL"
                }
        }
    }

    fun updatePrivacy(settings: PrivacySettings) {
        viewModelScope.launch { userRepository.updatePrivacySettings(settings) }
    }

    fun setDarkTheme(enabled: Boolean) {
        viewModelScope.launch { prefs.setDarkTheme(enabled) }
    }

    fun setFontScale(scale: Float) {
        viewModelScope.launch { prefs.setFontScale(scale) }
    }

    fun setLanguage(tag: String) {
        viewModelScope.launch {
            prefs.setLanguage(tag)
            LocaleManager.setAppLanguage(tag)
        }
    }

    fun updateNotificationPrefs(messages: Boolean? = null, groups: Boolean? = null, calls: Boolean? = null, stories: Boolean? = null) {
        viewModelScope.launch { prefs.setNotificationPreference(messages, groups, calls, stories) }
    }

    fun revokeSession(sessionId: String) {
        viewModelScope.launch { userRepository.revokeSession(sessionId) }
    }

    fun revokeAllOtherSessions(isEn: Boolean) {
        viewModelScope.launch {
            userRepository.revokeAllOtherSessions()
                .onSuccess {
                    statusMessage.value = if (isEn) "All other sessions ended" else "تم إنهاء جميع الجلسات الأخرى"
                }
                .onFailure { err -> statusMessage.value = err.localizedMessage }
        }
    }

    fun createSessionLinkToken() {
        viewModelScope.launch {
            userRepository.createSessionLinkToken()
                .onSuccess { sessionLinkToken.value = it }
                .onFailure { err -> statusMessage.value = err.localizedMessage }
        }
    }

    fun clearSessionLinkToken() {
        sessionLinkToken.value = null
    }

    fun unblockUser(uid: String) {
        viewModelScope.launch { userRepository.unblockUser(uid) }
    }

    fun getCacheSizeMb(): String {
        val bytes = folderSize(context.cacheDir)
        return String.format("%.2f MB", bytes / (1024f * 1024f))
    }

    fun clearMediaCache(isEn: Boolean) {
        context.cacheDir.deleteRecursively()
        statusMessage.value = if (isEn) "Cache cleared" else "تم مسح ذاكرة التخزين المؤقتة"
    }

    fun showMessage(msg: String) {
        statusMessage.value = msg
    }

    fun signOut(onSignedOut: () -> Unit) {
        viewModelScope.launch {
            prefs.setHiddenChatsUnlockedSession(false)
            authRepository.signOut()
            onSignedOut()
        }
    }

    fun deleteAccountPermanently(password: String?, googleIdToken: String?, isEn: Boolean, onDeleted: () -> Unit) {
        viewModelScope.launch {
            isDeletingAccount.value = true
            authRepository.deleteAccountPermanently(
                passwordForReauth = password?.takeIf { it.isNotBlank() },
                googleIdTokenForReauth = googleIdToken?.takeIf { it.isNotBlank() }
            ).onSuccess {
                isDeletingAccount.value = false
                prefs.setHiddenChatsUnlockedSession(false)
                onDeleted()
            }.onFailure { err ->
                isDeletingAccount.value = false
                statusMessage.value = err.localizedMessage ?: if (isEn) "Unable to delete account" else "تعذر حذف الحساب؛ تأكد من إعادة التحقق من الهوية"
            }
        }
    }

    private fun folderSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.listFiles()?.sumOf { if (it.isDirectory) folderSize(it) else it.length() } ?: 0L
    }
}

@Composable
fun SettingsScreen(
    onOpenProfile: () -> Unit,
    onOpenAccountShare: () -> Unit = {},
    onOpenAdminPanel: () -> Unit = {},
    onOpenChat: (String) -> Unit = {},
    onOpenHiddenChatsTab: () -> Unit = {},
    onSignedOut: () -> Unit,
    onOpenAddAccount: (prefillEmail: String?) -> Unit = {},
    onAccountSwitched: (isNewProfile: Boolean) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    val user by viewModel.currentUser.collectAsStateWithLifecycle()
    val isSuperAdmin by viewModel.isSuperAdmin.collectAsStateWithLifecycle()
    val adminPermissions by viewModel.adminPermissions.collectAsStateWithLifecycle()
    val mySubscriptionRequests by viewModel.mySubscriptionRequests.collectAsStateWithLifecycle()
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val savedAccounts by viewModel.savedAccounts.collectAsStateWithLifecycle()
    val isSwitchingAccount by viewModel.isSwitchingAccount.collectAsStateWithLifecycle()
    val isDeletingAccount by viewModel.isDeletingAccount.collectAsStateWithLifecycle()
    val isSubmittingSubscription by viewModel.isSubmittingSubscription.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val sessionLinkToken by viewModel.sessionLinkToken.collectAsStateWithLifecycle()

    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showHiddenLockDialog by remember { mutableStateOf(false) }
    var showNotifDialog by remember { mutableStateOf(false) }
    var showSessionsDialog by remember { mutableStateOf(false) }
    var showBlockedDialog by remember { mutableStateOf(false) }
    var showAccountsDialog by remember { mutableStateOf(false) }
    var showSubscriptionsDialog by remember { mutableStateOf(false) }
    var showSignOutConfirm by remember { mutableStateOf(false) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var cacheText by remember { mutableStateOf(viewModel.getCacheSizeMb()) }

    fun fetchGoogleTokenAndRun(onToken: (String) -> Unit, onNoCredential: () -> Unit = {}) {
        if (activity == null) return
        scope.launch {
            try {
                val webClientResId = activity.resources.getIdentifier("default_web_client_id", "string", activity.packageName)
                val serverClientId = if (webClientResId != 0) activity.getString(webClientResId) else ""
                if (serverClientId.isBlank()) {
                    viewModel.showMessage(if (strings.isEnglish) "Google OAuth Client ID missing" else "معرّف Google OAuth غير متوفر")
                    return@launch
                }
                val credentialManager = CredentialManager.create(activity)
                runCatching { credentialManager.clearCredentialState(ClearCredentialStateRequest()) }
                suspend fun fetch(onlyAuthorized: Boolean): GoogleIdTokenCredential {
                    val option = GetGoogleIdOption.Builder()
                        .setFilterByAuthorizedAccounts(onlyAuthorized)
                        .setAutoSelectEnabled(false)
                        .setServerClientId(serverClientId)
                        .build()
                    val req = GetCredentialRequest.Builder().addCredentialOption(option).build()
                    val res = credentialManager.getCredential(activity, req)
                    return GoogleIdTokenCredential.createFrom(res.credential.data)
                }
                val cred = try {
                    fetch(true)
                } catch (_: NoCredentialException) {
                    fetch(false)
                }
                onToken(cred.idToken)
            } catch (_: GetCredentialCancellationException) {
                viewModel.showMessage(if (strings.isEnglish) "Cancelled" else "تم الإلغاء")
            } catch (_: NoCredentialException) {
                onNoCredential()
            } catch (e: Exception) {
                viewModel.showMessage(e.localizedMessage ?: "Error")
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        statusMessage?.let { msg ->
            item {
                Surface(color = palette.emerald.copy(alpha = 0.16f), shape = RoundedCornerShape(10.dp)) {
                    Text(msg, color = palette.emerald, modifier = Modifier.padding(12.dp))
                }
            }
        }

        // User Profile Card (Centered portrait on top, neon Name, ID & @username underneath)
        item {
            Surface(
                color = palette.surface,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, palette.border),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenProfile)
            ) {
                Box(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
                    IconButton(
                        onClick = onOpenAccountShare,
                        modifier = Modifier.align(Alignment.TopStart).size(34.dp)
                    ) {
                        Icon(Icons.Default.QrCode2, contentDescription = strings.shareAccountQr, tint = palette.iconTint)
                    }
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = strings.edit,
                        tint = palette.iconTint,
                        modifier = Modifier.align(Alignment.TopEnd).size(20.dp)
                    )

                    val isNeonOn = user?.neonEnabled != false
                    val activeNeonColor = if (isNeonOn) user?.neonColor.orEmpty() else ""
                    val activeGlow = user?.neonGlowIntensity ?: 0.7f
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WesalAvatar(
                            imageUrl = user?.photoUrl.orEmpty(),
                            name = user?.displayName ?: "WESAL",
                            size = 76.dp,
                            frameId = user?.frameId.orEmpty(),
                            frameExpiresAt = user?.frameExpiresAt ?: 0L,
                            isCutout = user?.photoRemovedBg == true,
                            iconUrl = WesalFrameLibrary.resolveIconUrl(user?.iconId, user?.iconUrl.orEmpty()),
                            neonColor = activeNeonColor,
                            neonGlowIntensity = activeGlow
                        )
                        WesalNameWithBadge(
                            name = user?.displayName?.ifBlank { if (strings.isEnglish) "Complete your name" else "أكمل اسمك" }
                                ?: strings.loading,
                            isVerified = user?.isVerified == true,
                            style = MaterialTheme.typography.titleLarge,
                            color = palette.textPrimary,
                            fontWeight = FontWeight.Bold,
                            verificationType = user?.verificationType ?: "BLUE",
                            neonColor = activeNeonColor,
                            neonGlowIntensity = activeGlow,
                            neonEnabled = isNeonOn
                        )
                        val numericLabel = user?.numericId?.takeIf { it.isNotBlank() }?.let { "ID: $it" }
                        val idLabel = user?.publicId?.takeIf { it.isNotBlank() }?.let { "@$it" }
                        val idLine = listOfNotNull(numericLabel, idLabel).joinToString("  •  ")
                        if (idLine.isNotBlank()) {
                            Text(
                                text = idLine,
                                style = wesalNeonTextStyle(
                                    baseStyle = MaterialTheme.typography.labelLarge,
                                    neonColorKey = activeNeonColor,
                                    glowIntensity = activeGlow,
                                    fallbackColor = palette.turquoise
                                ),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        val hiddenPhone = user?.privacySettings?.hidePhoneNumber == true
                        val contactLine = if (hiddenPhone) {
                            user?.email?.takeIf { it.isNotBlank() }
                        } else {
                            user?.phoneNumber?.takeIf { it.isNotBlank() }
                                ?: user?.email?.takeIf { it.isNotBlank() }
                        }
                        if (!contactLine.isNullOrBlank()) {
                            Text(
                                text = contactLine,
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (!user?.bio.isNullOrBlank()) {
                            Text(
                                text = user?.bio.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        item {
            SettingsRow(
                icon = Icons.Default.ManageAccounts,
                title = strings.switchAccount,
                subtitle = if (savedAccounts.size > 1) {
                    if (strings.isEnglish) "${savedAccounts.size} accounts saved on this device" else "${savedAccounts.size} حسابات مسجلة على هذا الجهاز"
                } else {
                    if (strings.isEnglish) "Manage saved accounts or add another WESAL account" else "أضف حساب Google أو بريد إلكتروني آخر للتبديل السريع"
                },
                tint = palette.emerald
            ) {
                showAccountsDialog = true
            }
            SettingsRow(
                Icons.Default.AccountCircle,
                if (strings.isEnglish) "Account & Profile" else "الحساب والملف الشخصي",
                if (strings.isEnglish) "Name, @username, cover photo & business hours" else "الاسم، المعرف، الغلاف، الهاتف، وساعات العمل"
            ) {
                onOpenProfile()
            }
            SettingsRow(
                Icons.Default.QrCode2,
                strings.shareAccountQr,
                if (strings.isEnglish) "Share WESAL QR card and official link" else "بطاقة التعريف الشخصية ورمز QR"
            ) {
                onOpenAccountShare()
            }
            SettingsRow(
                Icons.Default.Lock,
                if (strings.isEnglish) "Privacy & Security" else "الخصوصية والأمان",
                if (strings.isEnglish) "Phone, birth date, visits, last seen & receipts" else "إخفاء الهاتف، الميلاد، الزيارات، وآخر ظهور"
            ) {
                showPrivacyDialog = true
            }
            SettingsRow(
                Icons.Default.EnhancedEncryption,
                if (strings.isEnglish) "Hidden Chats Password" else "كلمة مرور المحادثات المخفية",
                if (viewModel.hasHiddenChatsPassword()) {
                    if (strings.isEnglish) "Protected (${appSettings.hiddenChatIds.size} hidden)" else "مفعّلة (${appSettings.hiddenChatIds.size} محادثة مخفية)"
                } else {
                    if (strings.isEnglish) "Set password lock for hidden chats" else "تعيين قفل كلمة مرور للمحادثات المخفية"
                }
            ) {
                showHiddenLockDialog = true
            }
            SettingsRow(
                Icons.Default.Notifications,
                if (strings.isEnglish) "Notifications" else "الإشعارات",
                if (strings.isEnglish) "Messages, groups, calls, and stories" else "الرسائل، المجموعات، المكالمات، والحالات"
            ) {
                showNotifDialog = true
            }
            SettingsRow(
                icon = Icons.Default.DarkMode,
                title = strings.darkThemeTitle,
                subtitle = if (appSettings.darkTheme) strings.darkMode else strings.lightMode,
                trailing = {
                    Switch(
                        checked = appSettings.darkTheme,
                        onCheckedChange = viewModel::setDarkTheme
                    )
                }
            )
            SettingsRow(
                icon = Icons.Default.Language,
                title = strings.languageTitle,
                subtitle = if (appSettings.languageTag == "ar") strings.arabicLabel else strings.englishLabel,
                onClick = {
                    viewModel.setLanguage(if (appSettings.languageTag == "ar") "en" else "ar")
                }
            )
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = if (strings.isEnglish) "Font Size (${(appSettings.fontScale * 100).toInt()}%)" else "حجم الخط (${(appSettings.fontScale * 100).toInt()}%)",
                    color = palette.textPrimary
                )
                Slider(
                    value = appSettings.fontScale,
                    onValueChange = viewModel::setFontScale,
                    valueRange = 0.85f..1.3f,
                    colors = SliderDefaults.colors(thumbColor = palette.turquoise, activeTrackColor = palette.emerald)
                )
            }
            SettingsRow(
                Icons.Default.Storage,
                if (strings.isEnglish) "Storage Cache" else "إدارة التخزين",
                if (strings.isEnglish) "Cache: $cacheText (Tap to clear)" else "الملفات المؤقتة: $cacheText (اضغط للمسح)"
            ) {
                viewModel.clearMediaCache(strings.isEnglish)
                cacheText = viewModel.getCacheSizeMb()
            }
            SettingsRow(
                Icons.Default.Devices,
                if (strings.isEnglish) "Devices & Sessions" else "الأجهزة والجلسات",
                if (strings.isEnglish) "${sessions.size} active sessions" else "${sessions.size} جلسة نشطة"
            ) {
                showSessionsDialog = true
            }
            SettingsRow(
                Icons.Default.Block,
                if (strings.isEnglish) "Blocked Users" else "قائمة الحظر",
                if (strings.isEnglish) "${user?.blockedUsers?.size ?: 0} blocked" else "${user?.blockedUsers?.size ?: 0} مستخدم محظور"
            ) {
                showBlockedDialog = true
            }

            SettingsRow(
                icon = Icons.Default.VerifiedUser,
                title = if (strings.isEnglish) "Subscriptions & Verification Requests" else "الاشتراكات وطلبات التوثيق",
                subtitle = if (mySubscriptionRequests.any { it.status == "PENDING" }) {
                    if (strings.isEnglish) "Pending review — tap to view status" else "لديك طلب قيد المراجعة — اضغط للمتابعة"
                } else {
                    if (strings.isEnglish) "Request verified badge with official ID" else "اطلب توثيق حسابك والشارات عبر صورة الهوية الرسمية"
                },
                tint = palette.verifiedBlue
            ) {
                showSubscriptionsDialog = true
            }

            // Sec 24/25 & User Binding Admin Rule: 10001 Support In-App Chat (replaces external WhatsApp developer link)
            SettingsRow(
                icon = Icons.Default.SupportAgent,
                title = strings.contactWesalAdmin,
                subtitle = strings.contactWesalAdminSubtitle,
                tint = palette.emerald
            ) {
                viewModel.openSupportChatWith10001(strings.isEnglish, onOpenChat)
            }

            // Sec 12 & Sec 51 & User Binding Admin Rule: "إدارة WESAL" for Super Admins (10001 / 10002) & delegated moderators
            if (isSuperAdmin || adminPermissions.hasAnyAccess()) {
                val adminBadgeLabel = when {
                    user?.numericId == Constants.OWNER_ADMIN_NUMERIC_ID ->
                        if (strings.isEnglish) "WESAL Owner Console (ID: 10001)" else "لوحة تحكم المالك والإدارة الشاملة (ID: 10001)"
                    user?.numericId == Constants.SECONDARY_ADMIN_NUMERIC_ID ->
                        if (strings.isEnglish) "WESAL Super Admin Console (ID: 10002)" else "لوحة تحكم الإدارة الكاملة (ID: 10002)"
                    adminPermissions.isFullSuperAdmin ->
                        if (strings.isEnglish) "WESAL Super Admin Console" else "لوحة تحكم الإدارة الشاملة"
                    else ->
                        if (strings.isEnglish) "WESAL Assistant Moderator Console" else "لوحة تحكم المشرف المساعد"
                }
                Surface(
                    color = palette.verifiedBlue.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, palette.verifiedBlue.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clickable(onClick = onOpenAdminPanel)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(Icons.Default.AdminPanelSettings, contentDescription = null, tint = palette.verifiedBlue)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = strings.adminVerificationTitle,
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.verifiedBlue,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = adminBadgeLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textSecondary
                            )
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = palette.verifiedBlue)
                    }
                }
            }

            SettingsRow(
                icon = Icons.AutoMirrored.Filled.ExitToApp,
                title = strings.signOut,
                subtitle = if (strings.isEnglish) "End current device session" else "إنهاء الجلسة على هذا الجهاز",
                tint = palette.error,
                onClick = { showSignOutConfirm = true }
            )

            // Sec 40: Permanent Account Deletion directly below Sign Out
            SettingsRow(
                icon = Icons.Default.DeleteForever,
                title = if (strings.isEnglish) "Permanently Delete Account" else "إزالة الحساب نهائيًا",
                subtitle = if (strings.isEnglish) "Permanently erase account & unlink WESAL data" else "حذف الحساب نهائيًا من WESAL (يتطلب إعادة تأكيد)",
                tint = palette.error,
                onClick = { showDeleteAccountDialog = true }
            )
        }
    }

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            containerColor = palette.surface,
            title = { Text(strings.signOutConfirmTitle, color = palette.textPrimary) },
            text = { Text(strings.signOutConfirmMessage, color = palette.textSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        showSignOutConfirm = false
                        viewModel.signOut(onSignedOut)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.error)
                ) { Text(strings.signOut) }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutConfirm = false }) { Text(strings.cancel) }
            }
        )
    }

    if (showDeleteAccountDialog) {
        var passwordForReauth by remember { mutableStateOf("") }
        var confirmedUnderstanding by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!isDeletingAccount) showDeleteAccountDialog = false },
            containerColor = palette.surface,
            title = {
                Text(
                    text = if (strings.isEnglish) "Permanently Delete Account" else "إزالة الحساب نهائيًا",
                    color = palette.error,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (strings.isEnglish) {
                            "Warning: This action permanently deletes your account, releases your @username, and cannot be undone."
                        } else {
                            "تنبيه هام: حذف الحساب نهائي ولا يمكن التراجع عنه، وسيتم إخفاء حسابك وإلغاء جميع الجلسات النشطة وتحرير معرفك."
                        },
                        color = palette.textSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = passwordForReauth,
                        onValueChange = { passwordForReauth = it },
                        label = { Text(if (strings.isEnglish) "Password (if email login)" else "كلمة المرور (لحسابات البريد)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = confirmedUnderstanding, onCheckedChange = { confirmedUnderstanding = it })
                        Text(
                            text = if (strings.isEnglish) "I understand this is permanent" else "أؤكد رغبتي في حذف الحساب نهائيًا",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textPrimary
                        )
                    }
                    if (isDeletingAccount) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = palette.error)
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = confirmedUnderstanding && !isDeletingAccount,
                        onClick = {
                            fetchGoogleTokenAndRun(
                                onToken = { idToken ->
                                    viewModel.deleteAccountPermanently(null, idToken, strings.isEnglish) {
                                        showDeleteAccountDialog = false
                                        onSignedOut()
                                    }
                                },
                                onNoCredential = {
                                    viewModel.showMessage(if (strings.isEnglish) "Use password or try Google re-auth" else "أدخل كلمة المرور أو أعد المصادقة بـ Google")
                                }
                            )
                        }
                    ) {
                        Text("Google", color = palette.turquoise)
                    }
                    Button(
                        enabled = confirmedUnderstanding && !isDeletingAccount,
                        onClick = {
                            viewModel.deleteAccountPermanently(passwordForReauth, null, strings.isEnglish) {
                                showDeleteAccountDialog = false
                                onSignedOut()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = palette.error)
                    ) { Text(if (strings.isEnglish) "Delete Forever" else "حذف نهائي") }
                }
            },
            dismissButton = {
                TextButton(enabled = !isDeletingAccount, onClick = { showDeleteAccountDialog = false }) {
                    Text(strings.cancel)
                }
            }
        )
    }

    if (showHiddenLockDialog) {
        val hasExisting = remember(showHiddenLockDialog) { viewModel.hasHiddenChatsPassword() }
        val existingQ = remember(showHiddenLockDialog) { viewModel.getHiddenChatsRecoveryQuestion() }
        var currentPass by remember { mutableStateOf("") }
        var newPass by remember { mutableStateOf("") }
        var showPassChars by remember { mutableStateOf(false) }
        var recoveryQ by remember { mutableStateOf(existingQ.ifBlank { if (strings.isEnglish) "Your favorite city?" else "ما اسم مدينتك المفضلة؟" }) }
        var recoveryA by remember { mutableStateOf("") }
        var recoveryMode by remember { mutableStateOf(false) }
        var resetAnswer by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showHiddenLockDialog = false },
            containerColor = palette.surface,
            title = { Text(if (strings.isEnglish) "Hidden Chats Security" else "أمان المحادثات المخفية", color = palette.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (strings.isEnglish) {
                            "Stored locally using salted PBKDF2 + Android Keystore. Forgetting the password without recovery will require unhiding all hidden chats."
                        } else {
                            "يُحفظ التشفير محليًا عبر Android Keystore. إذا نسيت كلمة المرور بدون سؤال الاستعادة ستحتاج لإلغاء إخفاء جميع المحادثات عند إعادة الضبط."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary
                    )
                    OutlinedButton(
                        onClick = {
                            showHiddenLockDialog = false
                            viewModel.openHiddenChatsFromSettings(onOpenHiddenChatsTab)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.LockOpen, contentDescription = null, tint = palette.emerald, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (strings.isEnglish) "Open Hidden Chats Section" else "فتح قسم المحادثات المخفية",
                            color = palette.emerald,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (!recoveryMode) {
                        if (hasExisting) {
                            OutlinedTextField(
                                value = currentPass,
                                onValueChange = { currentPass = it },
                                label = { Text(if (strings.isEnglish) "Current Password" else "كلمة المرور الحالية") },
                                visualTransformation = if (showPassChars) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = {
                                    IconButton(onClick = { showPassChars = !showPassChars }) {
                                        Icon(
                                            if (showPassChars) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = null,
                                            tint = palette.textSecondary
                                        )
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        OutlinedTextField(
                            value = newPass,
                            onValueChange = { newPass = it },
                            label = { Text(if (strings.isEnglish) "New Password (letters/numbers)" else "كلمة المرور الجديدة (حروف وأرقام)") },
                            visualTransformation = if (showPassChars) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { showPassChars = !showPassChars }) {
                                    Icon(
                                        if (showPassChars) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = null,
                                        tint = palette.textSecondary
                                    )
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = recoveryQ,
                            onValueChange = { recoveryQ = it },
                            label = { Text(if (strings.isEnglish) "Recovery Question" else "سؤال الاستعادة") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = recoveryA,
                            onValueChange = { recoveryA = it },
                            label = { Text(if (strings.isEnglish) "Recovery Answer" else "إجابة سؤال الاستعادة") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (hasExisting) {
                            TextButton(onClick = { recoveryMode = true }) {
                                Text(if (strings.isEnglish) "Forgot password? Recover or Reset" else "نسيت كلمة المرور؟ استعادة / مسح القفل", color = palette.warning)
                            }
                        }
                    } else {
                        if (existingQ.isNotBlank()) {
                            Text(existingQ, color = palette.turquoise, fontWeight = FontWeight.SemiBold)
                            OutlinedTextField(
                                value = resetAnswer,
                                onValueChange = { resetAnswer = it },
                                label = { Text(if (strings.isEnglish) "Answer" else "إجابتك") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = {
                                    if (viewModel.resetHiddenChatsWithRecovery(resetAnswer, strings.isEnglish)) {
                                        showHiddenLockDialog = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (strings.isEnglish) "Verify & Unlock" else "تحقق وفك القفل") }
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.forceResetHiddenChats(strings.isEnglish)
                                showHiddenLockDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (strings.isEnglish) "Clear Lock & Unhide All" else "مسح القفل وإظهار جميع المحادثات", color = palette.error)
                        }
                    }
                }
            },
            confirmButton = {
                if (!recoveryMode) {
                    Button(
                        onClick = {
                            if (viewModel.setupHiddenChatsPassword(currentPass, newPass, recoveryQ, recoveryA, strings.isEnglish)) {
                                showHiddenLockDialog = false
                            }
                        }
                    ) { Text(strings.save) }
                }
            },
            dismissButton = {
                TextButton(onClick = { showHiddenLockDialog = false }) { Text(strings.close) }
            }
        )
    }

    if (showAccountsDialog) {
        AlertDialog(
            onDismissRequest = { if (!isSwitchingAccount) showAccountsDialog = false },
            containerColor = palette.surface,
            title = { Text(strings.switchAccount, color = palette.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isSwitchingAccount) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = palette.emerald)
                    }
                    if (savedAccounts.isEmpty()) {
                        Text(
                            text = if (strings.isEnglish) "Add another Google or email account to switch quickly." else "أضف حساب Google أو بريد إلكتروني آخر للتنقل السريع.",
                            color = palette.textSecondary
                        )
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(savedAccounts, key = { it.uid }) { acc ->
                                val isActive = acc.uid == user?.uid
                                Surface(
                                    color = if (isActive) palette.emerald.copy(alpha = 0.12f) else palette.background,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, if (isActive) palette.emerald.copy(alpha = 0.5f) else palette.border),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !isActive && !isSwitchingAccount) {
                                            if (acc.provider == "google") {
                                                fetchGoogleTokenAndRun(
                                                    onToken = { token ->
                                                        showAccountsDialog = false
                                                        viewModel.switchToGoogleIdToken(token, strings.isEnglish, onAccountSwitched)
                                                    },
                                                    onNoCredential = {
                                                        showAccountsDialog = false
                                                        onOpenAddAccount(null)
                                                    }
                                                )
                                            } else {
                                                showAccountsDialog = false
                                                onOpenAddAccount(acc.email.takeIf { it.isNotBlank() })
                                            }
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        WesalAvatar(
                                            imageUrl = acc.photoUrl,
                                            name = acc.displayName.ifBlank { acc.email.ifBlank { "WESAL" } },
                                            size = 40.dp
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(
                                                    text = acc.displayName.ifBlank { acc.email.ifBlank { "WESAL" } },
                                                    color = palette.textPrimary,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                if (isActive) {
                                                    Surface(color = palette.emerald.copy(alpha = 0.2f), shape = RoundedCornerShape(6.dp)) {
                                                        Text(
                                                            text = if (strings.isEnglish) "Active ✓" else "نشط ✓",
                                                            color = palette.emerald,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            val meta = listOfNotNull(
                                                acc.numericId.takeIf { it.isNotBlank() }?.let { "ID: $it" },
                                                acc.publicId.takeIf { it.isNotBlank() }?.let { "@$it" },
                                                acc.email.takeIf { it.isNotBlank() }
                                            ).joinToString(" • ")
                                            if (meta.isNotBlank()) {
                                                Text(meta, style = MaterialTheme.typography.labelSmall, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                        if (!isActive) {
                                            IconButton(onClick = { viewModel.removeSavedAccount(acc.uid) }, modifier = Modifier.size(32.dp)) {
                                                Icon(Icons.Default.Close, contentDescription = strings.delete, tint = palette.textMuted, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            showAccountsDialog = false
                            onOpenAddAccount(null)
                        },
                        enabled = !isSwitchingAccount,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onAccent),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(strings.addAccount, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = { TextButton(enabled = !isSwitchingAccount, onClick = { showAccountsDialog = false }) { Text(strings.close, color = palette.turquoise) } }
        )
    }

    if (showPrivacyDialog && user != null) {
        var ps by remember { mutableStateOf(user!!.privacySettings) }
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            containerColor = palette.surface,
            title = { Text(if (strings.isEnglish) "Privacy Settings" else "إعدادات الخصوصية", color = palette.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrivacyCycleRow(if (strings.isEnglish) "Last Seen" else "آخر ظهور", ps.lastSeenVisibility) { ps = ps.copy(lastSeenVisibility = it) }
                    PrivacyCycleRow(if (strings.isEnglish) "Online Status" else "حالة الاتصال", ps.onlineVisibility) { ps = ps.copy(onlineVisibility = it) }
                    PrivacyCycleRow(if (strings.isEnglish) "Profile Photo" else "صورة الحساب", ps.profilePhotoVisibility) { ps = ps.copy(profilePhotoVisibility = it) }
                    PrivacyCycleRow(if (strings.isEnglish) "Stories" else "الحالات", ps.storiesVisibility) { ps = ps.copy(storiesVisibility = it) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (strings.isEnglish) "Read Receipts" else "إيصالات القراءة", color = palette.textPrimary)
                        Switch(checked = ps.readReceiptsEnabled, onCheckedChange = { ps = ps.copy(readReceiptsEnabled = it) })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(strings.hidePhoneNumberTitle, color = palette.textPrimary)
                        Switch(checked = ps.hidePhoneNumber, onCheckedChange = { ps = ps.copy(hidePhoneNumber = it) })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (strings.isEnglish) "Hide Birth Date" else "إخفاء تاريخ الميلاد", color = palette.textPrimary)
                        Switch(checked = ps.hideBirthDate, onCheckedChange = { ps = ps.copy(hideBirthDate = it) })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (strings.isEnglish) "Record Profile Visits" else "تسجيل زوار الحساب", color = palette.textPrimary)
                        Switch(checked = ps.recordProfileVisits, onCheckedChange = { ps = ps.copy(recordProfileVisits = it) })
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.updatePrivacy(ps); showPrivacyDialog = false }) { Text(strings.save) }
            },
            dismissButton = {
                TextButton(onClick = { showPrivacyDialog = false }) { Text(strings.close) }
            }
        )
    }

    if (showNotifDialog) {
        AlertDialog(
            onDismissRequest = { showNotifDialog = false },
            containerColor = palette.surface,
            title = { Text(if (strings.isEnglish) "Notification Preferences" else "تفضيلات الإشعارات", color = palette.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NotifSwitchRow(if (strings.isEnglish) "Chat Messages" else "رسائل الدردشة", appSettings.notificationsMessages) {
                        viewModel.updateNotificationPrefs(messages = it)
                    }
                    NotifSwitchRow(if (strings.isEnglish) "Group Messages" else "رسائل المجموعات", appSettings.notificationsGroups) {
                        viewModel.updateNotificationPrefs(groups = it)
                    }
                    NotifSwitchRow(if (strings.isEnglish) "Incoming Calls" else "المكالمات الواردة", appSettings.notificationsCalls) {
                        viewModel.updateNotificationPrefs(calls = it)
                    }
                    NotifSwitchRow(if (strings.isEnglish) "New Stories" else "الحالات الجديدة", appSettings.notificationsStories) {
                        viewModel.updateNotificationPrefs(stories = it)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showNotifDialog = false }) { Text(strings.close) } }
        )
    }

    if (showSessionsDialog) {
        AlertDialog(
            onDismissRequest = { showSessionsDialog = false; viewModel.clearSessionLinkToken() },
            containerColor = palette.surface,
            title = { Text(if (strings.isEnglish) "Active Sessions" else "الأجهزة والجلسات النشطة", color = palette.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        items(sessions, key = { it.sessionId }) { session ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = session.deviceName + if (session.isCurrent) (if (strings.isEnglish) " (This Device)" else " (هذا الجهاز)") else "",
                                        color = palette.textPrimary,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "${session.osVersion} • ${DateTimeUtils.formatChatListTime(session.lastActiveAt)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.textSecondary
                                    )
                                }
                                if (!session.isCurrent) {
                                    TextButton(onClick = { viewModel.revokeSession(session.sessionId) }) {
                                        Text(if (strings.isEnglish) "Revoke" else "إنهاء", color = palette.error)
                                    }
                                }
                            }
                        }
                    }
                    if (sessions.size > 1) {
                        OutlinedButton(
                            onClick = { viewModel.revokeAllOtherSessions(strings.isEnglish) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (strings.isEnglish) "End All Other Sessions" else "إنهاء جميع الجلسات الأخرى", color = palette.error)
                        }
                    }
                    OutlinedButton(
                        onClick = { viewModel.createSessionLinkToken() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.QrCode2, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (strings.isEnglish) "Generate 5-Min Link Token" else "رمز ربط جهاز جديد (صالح 5 دقائق)", color = palette.turquoise)
                    }
                    sessionLinkToken?.let { tok ->
                        Surface(
                            color = palette.background,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, palette.turquoise.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    clipboard.setText(AnnotatedString(tok))
                                    Toast.makeText(context, tok, Toast.LENGTH_SHORT).show()
                                }
                        ) {
                            Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(tok, color = palette.turquoise, fontWeight = FontWeight.Bold)
                                Text(
                                    text = if (strings.isEnglish) "Single-use • Tap to copy" else "استخدام لمرة واحدة • اضغط للنسخ",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.textSecondary
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSessionsDialog = false; viewModel.clearSessionLinkToken() }) { Text(strings.close) }
            }
        )
    }

    if (showBlockedDialog) {
        val blocked = user?.blockedUsers.orEmpty()
        AlertDialog(
            onDismissRequest = { showBlockedDialog = false },
            containerColor = palette.surface,
            title = { Text(if (strings.isEnglish) "Blocked Users" else "المستخدمون المحظورون", color = palette.textPrimary) },
            text = {
                if (blocked.isEmpty()) {
                    Text(if (strings.isEnglish) "No blocked users" else "لا يوجد مستخدمون محظورون", color = palette.textSecondary)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 250.dp)) {
                        items(blocked) { uid ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(uid, color = palette.textPrimary, modifier = Modifier.weight(1f))
                                TextButton(onClick = { viewModel.unblockUser(uid) }) {
                                    Text(if (strings.isEnglish) "Unblock" else "إلغاء الحظر", color = palette.turquoise)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showBlockedDialog = false }) { Text(strings.close) } }
        )
    }

    if (showSubscriptionsDialog) {
        var selectedSeal by remember { mutableStateOf(VerificationSealType.BLUE) }
        var idPhotoUri by remember { mutableStateOf<Uri?>(null) }
        var requestNote by remember { mutableStateOf("") }
        val idPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) idPhotoUri = uri
        }

        AlertDialog(
            onDismissRequest = { if (!isSubmittingSubscription) showSubscriptionsDialog = false },
            containerColor = palette.surface,
            title = {
                Text(
                    text = if (strings.isEnglish) "Subscriptions & Verification Requests" else "الاشتراكات وطلبات التوثيق",
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (strings.isEnglish) {
                            "Choose your desired verification seal and attach a clear personal ID photo for admin verification."
                        } else {
                            "اختر شارة التوثيق المطلوبة وأرفق صورة واضحة للهوية الرسمية لإرسال الطلب لإدارة WESAL."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary
                    )
                    Text(
                        text = if (strings.isEnglish) "Desired Badge:" else "الشارة المطلوبة:",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        VerificationSealType.entries.forEach { seal ->
                            FilterChip(
                                selected = selectedSeal == seal,
                                onClick = { selectedSeal = seal },
                                leadingIcon = { WesalVerifiedBadge(size = 16.dp, verificationType = seal.key) },
                                label = { Text(if (strings.isEnglish) seal.labelEn else seal.labelAr, maxLines = 1) }
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            idPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        enabled = !isSubmittingSubscription,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Badge, contentDescription = null, tint = palette.turquoise)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (idPhotoUri == null) {
                                if (strings.isEnglish) "Attach Official ID Photo (Required)" else "إرفاق صورة الهوية الرسمية (إلزامي)"
                            } else {
                                if (strings.isEnglish) "Change Attached ID Photo" else "تغيير صورة الهوية المرفقة ✓"
                            },
                            color = palette.turquoise
                        )
                    }

                    if (idPhotoUri != null) {
                        AsyncImage(
                            model = wesalImageModel(idPhotoUri.toString()),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp)
                                .clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }

                    OutlinedTextField(
                        value = requestNote,
                        onValueChange = { requestNote = it },
                        label = { Text(if (strings.isEnglish) "Reason / Notes (Optional)" else "ملاحظة أو سبب الطلب (اختياري)") },
                        minLines = 2,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (isSubmittingSubscription) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = palette.emerald)
                    }

                    if (mySubscriptionRequests.isNotEmpty()) {
                        HorizontalDivider(color = palette.border)
                        Text(
                            text = if (strings.isEnglish) "Your Previous Requests (${mySubscriptionRequests.size})" else "طلباتك السابقة (${mySubscriptionRequests.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = palette.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        mySubscriptionRequests.take(5).forEach { req ->
                            val statusColor = when (req.status) {
                                "APPROVED" -> palette.emerald
                                "REJECTED" -> palette.error
                                else -> palette.warning
                            }
                            val statusLabel = when (req.status) {
                                "APPROVED" -> if (strings.isEnglish) "Approved" else "تمت الموافقة"
                                "REJECTED" -> if (strings.isEnglish) "Rejected" else "مرفوض"
                                else -> if (strings.isEnglish) "Pending Review" else "قيد المراجعة"
                            }
                            Surface(
                                color = palette.surfaceElevated,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            WesalVerifiedBadge(size = 15.dp, verificationType = req.desiredSeal)
                                            Text(req.desiredSeal, color = palette.textPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                                        }
                                        Text(statusLabel, color = statusColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    }
                                    if (req.adminResponse.isNotBlank()) {
                                        Text(
                                            text = if (strings.isEnglish) "Admin: ${req.adminResponse}" else "رد الإدارة: ${req.adminResponse}",
                                            color = palette.turquoise,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Text(
                                        text = DateTimeUtils.formatChatListTime(req.createdAt),
                                        color = palette.textMuted,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = idPhotoUri != null && !isSubmittingSubscription,
                    onClick = {
                        val uri = idPhotoUri ?: return@Button
                        viewModel.submitSubscriptionRequest(
                            requestType = "VERIFICATION_${selectedSeal.key}",
                            desiredSeal = selectedSeal.key,
                            idUri = uri,
                            note = requestNote,
                            isEn = strings.isEnglish
                        ) {
                            idPhotoUri = null
                            requestNote = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.emerald)
                ) {
                    Text(if (strings.isEnglish) "Submit Request" else "إرسال الطلب")
                }
            },
            dismissButton = {
                TextButton(enabled = !isSubmittingSubscription, onClick = { showSubscriptionsDialog = false }) {
                    Text(strings.close)
                }
            }
        )
    }
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    tint: Color = WesalColors.current.iconTint,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit = {}
) {
    val palette = WesalColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = palette.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = palette.textSecondary)
        }
        trailing?.invoke()
    }
}

@Composable
private fun PrivacyCycleRow(
    label: String,
    value: PrivacyVisibility,
    onChange: (PrivacyVisibility) -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val labelText = when (value) {
        PrivacyVisibility.EVERYONE -> if (strings.isEnglish) "Everyone" else "الجميع"
        PrivacyVisibility.CONTACTS -> if (strings.isEnglish) "Contacts" else "جهات الاتصال"
        PrivacyVisibility.NOBODY -> if (strings.isEnglish) "Nobody" else "لا أحد"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val next = when (value) {
                    PrivacyVisibility.EVERYONE -> PrivacyVisibility.CONTACTS
                    PrivacyVisibility.CONTACTS -> PrivacyVisibility.NOBODY
                    PrivacyVisibility.NOBODY -> PrivacyVisibility.EVERYONE
                }
                onChange(next)
            }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = palette.textPrimary)
        Text(labelText, color = palette.turquoise, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NotifSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val palette = WesalColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = palette.textPrimary)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
