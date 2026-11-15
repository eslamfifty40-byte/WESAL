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
import com.wesal.messenger.core.theme.*
import com.wesal.messenger.core.util.DateTimeUtils
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
        viewModelScope.launch {
            authRepository.removeSavedAccount(uid)
        }
    }

    fun switchToGoogleIdToken(idToken: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            isSwitchingAccount.value = true
            authRepository.signInWithGoogleIdToken(idToken)
                .onSuccess { isNewProfile ->
                    isSwitchingAccount.value = false
                    statusMessage.value = "تم تبديل الحساب بنجاح"
                    onComplete(isNewProfile)
                }
                .onFailure { err ->
                    isSwitchingAccount.value = false
                    statusMessage.value = err.localizedMessage ?: "تعذر تبديل حساب Google"
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

    fun clearMediaCache() {
        context.cacheDir.deleteRecursively()
        statusMessage.value = "تم مسح ذاكرة التخزين المؤقتة"
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
    var cacheText by remember { mutableStateOf(viewModel.getCacheSizeMb()) }

    fun launchGoogleAccountChooser() {
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
                    viewModel.showMessage("معرّف Google OAuth غير متوفر في google-services.json")
                    return@launch
                }
                val credentialManager = CredentialManager.create(activity)
                runCatching { credentialManager.clearCredentialState(ClearCredentialStateRequest()) }
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setAutoSelectEnabled(false)
                    .setServerClientId(serverClientId)
                    .build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()
                val result = credentialManager.getCredential(activity, request)
                val googleCred = GoogleIdTokenCredential.createFrom(result.credential.data)
                showAccountsDialog = false
                viewModel.switchToGoogleIdToken(googleCred.idToken, onAccountSwitched)
            } catch (_: GetCredentialCancellationException) {
                viewModel.showMessage("تم إلغاء اختيار حساب Google")
            } catch (_: NoCredentialException) {
                showAccountsDialog = false
                onOpenAddAccount(null)
            } catch (e: Exception) {
                viewModel.showMessage("تعذر فتح قائمة حسابات Google: ${e.localizedMessage ?: "حاول مرة أخرى"}")
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(WesalDeepBlack)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        statusMessage?.let { msg ->
            item {
                Surface(color = WesalEmerald.copy(alpha = 0.16f), shape = RoundedCornerShape(10.dp)) {
                    Text(msg, color = WesalEmerald, modifier = Modifier.padding(12.dp))
                }
            }
        }

        // User Profile Card -> opens full ProfileScreen with Crop & Delete Photo
        item {
            Surface(
                color = WesalSurfaceDark,
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
                        name = user?.displayName ?: "وصال",
                        size = 64.dp
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = user?.displayName?.ifBlank { "أكمل اسمك" } ?: "جاري التحميل...",
                            style = MaterialTheme.typography.titleLarge,
                            color = WesalTextPrimary
                        )
                        val contactLine = user?.phoneNumber?.takeIf { it.isNotBlank() }
                            ?: user?.email?.takeIf { it.isNotBlank() }
                        val idLabel = user?.publicId?.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty()
                        Text(
                            text = listOfNotNull(
                                idLabel.takeIf { it.isNotBlank() },
                                contactLine
                            ).joinToString(" • "),
                            style = MaterialTheme.typography.labelMedium,
                            color = WesalTurquoise
                        )
                        Text(
                            text = user?.bio.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = WesalTextSecondary,
                            maxLines = 1
                        )
                    }
                    Icon(Icons.Default.Edit, contentDescription = "تعديل", tint = WesalTurquoise)
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        item {
            SettingsRow(
                icon = Icons.Default.ManageAccounts,
                title = "تبديل الحساب وإدارة الحسابات",
                subtitle = if (savedAccounts.size > 1) {
                    "${savedAccounts.size} حسابات مسجلة على هذا الجهاز"
                } else {
                    "أضف حساب Google أو بريد إلكتروني آخر للتبديل الفوري"
                },
                tint = WesalEmerald
            ) {
                showAccountsDialog = true
            }
            SettingsRow(Icons.Default.AccountCircle, "الحساب والملف الشخصي", "تعديل الاسم، المعرف الفريد، وصورة الحساب") {
                onOpenProfile()
            }
            SettingsRow(Icons.Default.Lock, "الخصوصية والأمان", "آخر ظهور، الحالة، صورة الحساب، وإيصالات القراءة") {
                showPrivacyDialog = true
            }
            SettingsRow(Icons.Default.Notifications, "الإشعارات", "الرسائل، المجموعات، المكالمات، والحالات") {
                showNotifDialog = true
            }
            SettingsRow(
                icon = Icons.Default.DarkMode,
                title = "المظهر والوضع الليلي",
                subtitle = if (appSettings.darkTheme) "الوضع الليلي الداكن (الرسمي)" else "الوضع الفاتح",
                trailing = {
                    Switch(
                        checked = appSettings.darkTheme,
                        onCheckedChange = viewModel::setDarkTheme
                    )
                }
            )
            SettingsRow(
                icon = Icons.Default.Language,
                title = "لغة التطبيق",
                subtitle = if (appSettings.languageTag == "ar") "العربية (RTL)" else "English",
                onClick = {
                    viewModel.setLanguage(if (appSettings.languageTag == "ar") "en" else "ar")
                }
            )
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("حجم الخط (${(appSettings.fontScale * 100).toInt()}%)", color = WesalTextPrimary)
                Slider(
                    value = appSettings.fontScale,
                    onValueChange = viewModel::setFontScale,
                    valueRange = 0.85f..1.3f,
                    colors = SliderDefaults.colors(thumbColor = WesalTurquoise, activeTrackColor = WesalEmerald)
                )
            }
            SettingsRow(Icons.Default.Storage, "إدارة التخزين", "الملفات المؤقتة: $cacheText (اضغط للمسح)") {
                viewModel.clearMediaCache()
                cacheText = viewModel.getCacheSizeMb()
            }
            SettingsRow(Icons.Default.Devices, "الأجهزة والجلسات", "${sessions.size} جلسة نشطة") {
                showSessionsDialog = true
            }
            SettingsRow(Icons.Default.Block, "قائمة الحظر", "${user?.blockedUsers?.size ?: 0} مستخدم محظور") {
                showBlockedDialog = true
            }
            SettingsRow(
                icon = Icons.Default.SupportAgent,
                title = "التواصل مع مطور التطبيق",
                subtitle = "تواصل مباشر عبر واتساب",
                tint = WesalEmerald
            ) {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DEVELOPER_WHATSAPP_URL)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }.onFailure {
                    viewModel.showMessage("تعذر فتح واتساب أو المتصفح على هذا الجهاز")
                }
            }
            SettingsRow(
                icon = Icons.AutoMirrored.Filled.ExitToApp,
                title = "تسجيل الخروج",
                subtitle = "إنهاء الجلسة على هذا الجهاز",
                tint = WesalError,
                onClick = { viewModel.signOut(onSignedOut) }
            )
        }
    }

    if (showAccountsDialog) {
        AlertDialog(
            onDismissRequest = { if (!isSwitchingAccount) showAccountsDialog = false },
            containerColor = WesalSurfaceDark,
            title = {
                Text(
                    text = "إدارة وتبديل الحسابات",
                    color = WesalTextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isSwitchingAccount) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = WesalEmerald)
                    }
                    if (savedAccounts.isEmpty()) {
                        Text(
                            text = "أضف حساب Google أو بريد إلكتروني آخر للتنقل السريع بين الحسابات.",
                            color = WesalTextSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 260.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(savedAccounts, key = { it.uid }) { acc ->
                                val isActive = acc.uid == user?.uid
                                Surface(
                                    color = if (isActive) WesalEmerald.copy(alpha = 0.12f) else WesalDeepBlack,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isActive) WesalEmerald.copy(alpha = 0.5f) else WesalSurfaceBorder
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !isActive && !isSwitchingAccount) {
                                            if (acc.provider == "google") {
                                                launchGoogleAccountChooser()
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
                                            name = acc.displayName.ifBlank { acc.email.ifBlank { "وصال" } },
                                            size = 40.dp
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = acc.displayName.ifBlank { acc.email.ifBlank { "حساب وصال" } },
                                                    color = WesalTextPrimary,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                if (isActive) {
                                                    Surface(
                                                        color = WesalEmerald.copy(alpha = 0.2f),
                                                        shape = RoundedCornerShape(6.dp)
                                                    ) {
                                                        Text(
                                                            text = "نشط ✓",
                                                            color = WesalEmerald,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            val meta = listOfNotNull(
                                                acc.publicId.takeIf { it.isNotBlank() }?.let { "@$it" },
                                                acc.email.takeIf { it.isNotBlank() } ?: acc.phoneNumber.takeIf { it.isNotBlank() }
                                            ).joinToString(" • ")
                                            if (meta.isNotBlank()) {
                                                Text(
                                                    text = meta,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = WesalTextSecondary,
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
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = "إزالة الحساب",
                                                    tint = WesalTextMuted,
                                                    modifier = Modifier.size(18.dp)
                                                )
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
                        colors = ButtonDefaults.buttonColors(
                            containerColor = WesalEmerald,
                            contentColor = WesalDeepBlack
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("إضافة حساب جديد", fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isSwitchingAccount,
                    onClick = { showAccountsDialog = false }
                ) {
                    Text("إغلاق", color = WesalTurquoise)
                }
            }
        )
    }

    if (showPrivacyDialog && user != null) {
        var ps by remember { mutableStateOf(user!!.privacySettings) }
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            containerColor = WesalSurfaceDark,
            title = { Text("إعدادات الخصوصية", color = WesalTextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrivacyCycleRow("آخر ظهور", ps.lastSeenVisibility) { ps = ps.copy(lastSeenVisibility = it) }
                    PrivacyCycleRow("حالة الاتصال", ps.onlineVisibility) { ps = ps.copy(onlineVisibility = it) }
                    PrivacyCycleRow("صورة الحساب", ps.profilePhotoVisibility) { ps = ps.copy(profilePhotoVisibility = it) }
                    PrivacyCycleRow("الحالات", ps.storiesVisibility) { ps = ps.copy(storiesVisibility = it) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("إيصالات القراءة", color = WesalTextPrimary)
                        Switch(checked = ps.readReceiptsEnabled, onCheckedChange = { ps = ps.copy(readReceiptsEnabled = it) })
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.updatePrivacy(ps); showPrivacyDialog = false }) { Text("حفظ") }
            },
            dismissButton = {
                TextButton(onClick = { showPrivacyDialog = false }) { Text("إغلاق") }
            }
        )
    }

    if (showNotifDialog) {
        AlertDialog(
            onDismissRequest = { showNotifDialog = false },
            containerColor = WesalSurfaceDark,
            title = { Text("تفضيلات الإشعارات", color = WesalTextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NotifSwitchRow("رسائل الدردشة", appSettings.notificationsMessages) {
                        viewModel.updateNotificationPrefs(messages = it)
                    }
                    NotifSwitchRow("رسائل المجموعات", appSettings.notificationsGroups) {
                        viewModel.updateNotificationPrefs(groups = it)
                    }
                    NotifSwitchRow("المكالمات الواردة", appSettings.notificationsCalls) {
                        viewModel.updateNotificationPrefs(calls = it)
                    }
                    NotifSwitchRow("الحالات الجديدة", appSettings.notificationsStories) {
                        viewModel.updateNotificationPrefs(stories = it)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showNotifDialog = false }) { Text("تم") }
            }
        )
    }

    if (showSessionsDialog) {
        AlertDialog(
            onDismissRequest = { showSessionsDialog = false },
            containerColor = WesalSurfaceDark,
            title = { Text("الأجهزة والجلسات النشطة", color = WesalTextPrimary) },
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
                                    text = session.deviceName + if (session.isCurrent) " (هذا الجهاز)" else "",
                                    color = WesalTextPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "${session.osVersion} • ${DateTimeUtils.formatChatListTime(session.lastActiveAt)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = WesalTextSecondary
                                )
                            }
                            if (!session.isCurrent) {
                                TextButton(onClick = { viewModel.revokeSession(session.sessionId) }) {
                                    Text("إنهاء", color = WesalError)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSessionsDialog = false }) { Text("إغلاق") }
            }
        )
    }

    if (showBlockedDialog) {
        val blocked = user?.blockedUsers.orEmpty()
        AlertDialog(
            onDismissRequest = { showBlockedDialog = false },
            containerColor = WesalSurfaceDark,
            title = { Text("المستخدمون المحظورون", color = WesalTextPrimary) },
            text = {
                if (blocked.isEmpty()) {
                    Text("لا يوجد مستخدمون محظورون", color = WesalTextSecondary)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 250.dp)) {
                        items(blocked) { uid ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(uid, color = WesalTextPrimary, modifier = Modifier.weight(1f))
                                TextButton(onClick = { viewModel.unblockUser(uid) }) {
                                    Text("إلغاء الحظر", color = WesalTurquoise)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBlockedDialog = false }) { Text("إغلاق") }
            }
        )
    }
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    tint: androidx.compose.ui.graphics.Color = WesalTurquoise,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit = {}
) {
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
            Text(title, style = MaterialTheme.typography.titleMedium, color = WesalTextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = WesalTextSecondary)
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
    val labelText = when (value) {
        PrivacyVisibility.EVERYONE -> "الجميع"
        PrivacyVisibility.CONTACTS -> "جهات الاتصال"
        PrivacyVisibility.NOBODY -> "لا أحد"
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
        Text(label, color = WesalTextPrimary)
        Text(labelText, color = WesalTurquoise, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NotifSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = WesalTextPrimary)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
