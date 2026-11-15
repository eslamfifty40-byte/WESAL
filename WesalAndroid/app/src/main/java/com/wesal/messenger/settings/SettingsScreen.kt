package com.wesal.messenger.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.core.util.LocaleManager
import com.wesal.messenger.data.local.AppUserSettings
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.domain.model.DeviceSession
import com.wesal.messenger.domain.model.PrivacySettings
import com.wesal.messenger.domain.model.PrivacyVisibility
import com.wesal.messenger.domain.model.SavedWesalAccount
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.UserRepository
import com.wesal.messenger.presentation.components.WesalAvatar
import com.wesal.messenger.presentation.components.WesalNameWithBadge
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

private const val DEVELOPER_WHATSAPP_URL = "https://wa.me/message/EPAFTW53B7WLA1"

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val prefs: UserPreferencesDataStore
) : ViewModel() {

    val currentUser: StateFlow<WesalUser?> = userRepository.observeCurrentUser()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val appSettings: StateFlow<AppUserSettings> = prefs.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppUserSettings())

    val sessions: StateFlow<List<DeviceSession>> = userRepository.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedAccounts: StateFlow<List<SavedWesalAccount>> = authRepository.observeSavedAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isSwitchingAccount = MutableStateFlow(false)
    val statusMessage = MutableStateFlow<String?>(null)

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
            authRepository.signOut()
            onSignedOut()
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
    onSignedOut: () -> Unit,
    onOpenAddAccount: (prefillEmail: String?) -> Unit = {},
    onAccountSwitched: (isNewProfile: Boolean) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val user by viewModel.currentUser.collectAsStateWithLifecycle()
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val savedAccounts by viewModel.savedAccounts.collectAsStateWithLifecycle()
    val isSwitchingAccount by viewModel.isSwitchingAccount.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()

    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showNotifDialog by remember { mutableStateOf(false) }
    var showSessionsDialog by remember { mutableStateOf(false) }
    var showBlockedDialog by remember { mutableStateOf(false) }
    var showAccountsDialog by remember { mutableStateOf(false) }
    var showSignOutConfirm by remember { mutableStateOf(false) }
    var cacheText by remember { mutableStateOf(viewModel.getCacheSizeMb()) }

    fun switchToAuthorizedGoogleAccount() {
        if (activity == null || isSwitchingAccount) return
        scope.launch {
            try {
                val webClientResId = activity.resources.getIdentifier(
                    "default_web_client_id",
                    "string",
                    activity.packageName
                )
                val serverClientId = if (webClientResId != 0) activity.getString(webClientResId) else ""
                if (serverClientId.isBlank()) {
                    viewModel.showMessage(if (strings.isEnglish) "Google OAuth Client ID missing" else "معرّف Google OAuth غير متوفر")
                    return@launch
                }
                val credentialManager = CredentialManager.create(activity)
                runCatching { credentialManager.clearCredentialState(ClearCredentialStateRequest()) }
                // Use setFilterByAuthorizedAccounts(true) so clicking a previously authorized Google account switches cleanly instead of opening generic OS signup chooser
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(true)
                    .setAutoSelectEnabled(false)
                    .setServerClientId(serverClientId)
                    .build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()
                val result = credentialManager.getCredential(activity, request)
                val googleCred = GoogleIdTokenCredential.createFrom(result.credential.data)
                showAccountsDialog = false
                viewModel.switchToGoogleIdToken(googleCred.idToken, strings.isEnglish, onAccountSwitched)
            } catch (_: GetCredentialCancellationException) {
                viewModel.showMessage(if (strings.isEnglish) "Google switch cancelled" else "تم إلغاء تبديل الحساب")
            } catch (_: NoCredentialException) {
                showAccountsDialog = false
                onOpenAddAccount(null)
            } catch (e: Exception) {
                viewModel.showMessage(e.localizedMessage ?: if (strings.isEnglish) "Unable to switch account" else "تعذر تبديل الحساب")
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

        // User Profile Card
        item {
            Surface(
                color = palette.surface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenProfile)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    WesalAvatar(
                        imageUrl = user?.photoUrl.orEmpty(),
                        name = user?.displayName ?: "WESAL",
                        size = 64.dp
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        WesalNameWithBadge(
                            name = user?.displayName?.ifBlank { if (strings.isEnglish) "Complete your name" else "أكمل اسمك" }
                                ?: strings.loading,
                            isVerified = user?.isVerified == true,
                            style = MaterialTheme.typography.titleLarge,
                            color = palette.textPrimary
                        )
                        val hiddenPhone = user?.privacySettings?.hidePhoneNumber == true
                        val contactLine = if (hiddenPhone) {
                            user?.email?.takeIf { it.isNotBlank() }
                        } else {
                            user?.phoneNumber?.takeIf { it.isNotBlank() }
                                ?: user?.email?.takeIf { it.isNotBlank() }
                        }
                        val idLabel = user?.publicId?.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty()
                        Text(
                            text = listOfNotNull(
                                idLabel.takeIf { it.isNotBlank() },
                                contactLine
                            ).joinToString(" • "),
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.turquoise
                        )
                        Text(
                            text = user?.bio.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textSecondary,
                            maxLines = 1
                        )
                    }
                    Icon(Icons.Default.Edit, contentDescription = strings.edit, tint = palette.turquoise)
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
                if (strings.isEnglish) "Name, @wesal_id, phone OTP & avatar" else "الاسم، المعرف الفريد، توثيق الهاتف، وصورة الحساب"
            ) {
                onOpenProfile()
            }
            SettingsRow(
                Icons.Default.Lock,
                if (strings.isEnglish) "Privacy & Security" else "الخصوصية والأمان",
                if (strings.isEnglish) "Hide phone, last seen, online status & read receipts" else "إخفاء الهاتف، آخر ظهور، الحالة، وإيصالات القراءة"
            ) {
                showPrivacyDialog = true
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
                icon = Icons.Default.SupportAgent,
                title = if (strings.isEnglish) "Contact Developer" else "التواصل مع مطور التطبيق",
                subtitle = if (strings.isEnglish) "Direct WhatsApp support" else "تواصل مباشر عبر واتساب",
                tint = palette.emerald
            ) {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DEVELOPER_WHATSAPP_URL)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }.onFailure {
                    viewModel.showMessage(if (strings.isEnglish) "Unable to open WhatsApp" else "تعذر فتح واتساب على هذا الجهاز")
                }
            }
            SettingsRow(
                icon = Icons.AutoMirrored.Filled.ExitToApp,
                title = strings.signOut,
                subtitle = if (strings.isEnglish) "End current device session" else "إنهاء الجلسة على هذا الجهاز",
                tint = palette.error,
                onClick = { showSignOutConfirm = true }
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

    if (showAccountsDialog) {
        AlertDialog(
            onDismissRequest = { if (!isSwitchingAccount) showAccountsDialog = false },
            containerColor = palette.surface,
            title = {
                Text(strings.switchAccount, color = palette.textPrimary, fontWeight = FontWeight.Bold)
            },
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
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 260.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(savedAccounts, key = { it.uid }) { acc ->
                                val isActive = acc.uid == user?.uid
                                Surface(
                                    color = if (isActive) palette.emerald.copy(alpha = 0.12f) else palette.background,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isActive) palette.emerald.copy(alpha = 0.5f) else palette.border
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !isActive && !isSwitchingAccount) {
                                            if (acc.provider == "google") {
                                                switchToAuthorizedGoogleAccount()
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
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
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
                                                acc.publicId.takeIf { it.isNotBlank() }?.let { "@$it" },
                                                acc.email.takeIf { it.isNotBlank() }
                                            ).joinToString(" • ")
                                            if (meta.isNotBlank()) {
                                                Text(
                                                    text = meta,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = palette.textSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                        if (!isActive) {
                                            IconButton(
                                                onClick = { viewModel.removeSavedAccount(acc.uid) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
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
            confirmButton = {
                TextButton(enabled = !isSwitchingAccount, onClick = { showAccountsDialog = false }) {
                    Text(strings.close, color = palette.turquoise)
                }
            }
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
            confirmButton = {
                TextButton(onClick = { showNotifDialog = false }) { Text(strings.close) }
            }
        )
    }

    if (showSessionsDialog) {
        AlertDialog(
            onDismissRequest = { showSessionsDialog = false },
            containerColor = palette.surface,
            title = { Text(if (strings.isEnglish) "Active Sessions" else "الأجهزة والجلسات النشطة", color = palette.textPrimary) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
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
            },
            confirmButton = { TextButton(onClick = { showSessionsDialog = false }) { Text(strings.close) } }
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
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    tint: Color = WesalColors.current.turquoise,
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
