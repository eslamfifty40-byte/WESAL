package com.wesal.messenger.profile

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wesal.messenger.core.network.NetworkMonitor
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.core.util.PhoneUtils
import com.wesal.messenger.core.util.WesalShareUtils
import com.wesal.messenger.domain.model.PrivacySettings
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.PhoneAuthEvent
import com.wesal.messenger.domain.repository.UserRepository
import com.wesal.messenger.media.AvatarCropHelper
import com.wesal.messenger.media.AvatarPreviewConfirmDialog
import com.wesal.messenger.media.AvatarSourceBottomSheet
import com.wesal.messenger.presentation.components.WesalAvatar
import com.wesal.messenger.presentation.components.WesalNameWithBadge
import com.yalantis.ucrop.UCrop
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiStatus(
    val isSaving: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    val pendingOtpVerificationId: String? = null
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository,
    private val networkMonitor: NetworkMonitor
) : ViewModel() {

    val currentUser: StateFlow<WesalUser?> = userRepository.observeCurrentUser()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _uiStatus = MutableStateFlow(ProfileUiStatus())
    val uiStatus: StateFlow<ProfileUiStatus> = _uiStatus.asStateFlow()

    fun saveProfile(displayName: String, bio: String, publicId: String, photoUri: Uri?, isEn: Boolean) {
        if (displayName.isBlank()) {
            _uiStatus.value = ProfileUiStatus(
                message = if (isEn) "Display name cannot be empty" else "الاسم الظاهر لا يمكن أن يكون فارغاً",
                isError = true
            )
            return
        }
        if (photoUri != null && !networkMonitor.isCurrentlyConnected()) {
            _uiStatus.value = ProfileUiStatus(
                message = if (isEn) "No internet to upload photo" else "لا يوجد اتصال بالإنترنت لرفع الصورة",
                isError = true
            )
            return
        }
        viewModelScope.launch {
            _uiStatus.value = ProfileUiStatus(isSaving = true)
            userRepository.updateProfile(displayName, bio, publicId, photoUri)
                .onSuccess {
                    _uiStatus.value = ProfileUiStatus(
                        message = if (isEn) "Profile saved" else "تم حفظ الملف الشخصي بنجاح",
                        isError = false
                    )
                }
                .onFailure { err ->
                    _uiStatus.value = ProfileUiStatus(
                        message = err.localizedMessage ?: if (isEn) "Failed to save profile" else "تعذر حفظ التعديلات",
                        isError = true
                    )
                }
        }
    }

    fun updateOptionalPhone(rawPhone: String, isEn: Boolean) {
        if (!networkMonitor.isCurrentlyConnected()) {
            _uiStatus.value = ProfileUiStatus(message = if (isEn) "No internet connection" else "لا يوجد اتصال بالإنترنت لتحديث رقم الهاتف", isError = true)
            return
        }
        viewModelScope.launch {
            _uiStatus.value = ProfileUiStatus(isSaving = true)
            userRepository.updateOptionalPhone(rawPhone)
                .onSuccess {
                    val msg = if (rawPhone.isBlank()) {
                        if (isEn) "Phone number removed" else "تمت إزالة رقم الهاتف"
                    } else {
                        if (isEn) "Phone saved — tap Verify OTP to confirm ownership" else "تم حفظ الرقم — اضغط «تأكيد بـ OTP» لتوثيق ملكيته"
                    }
                    _uiStatus.value = ProfileUiStatus(message = msg, isError = false)
                }
                .onFailure { err ->
                    _uiStatus.value = ProfileUiStatus(message = err.localizedMessage ?: "Error", isError = true)
                }
        }
    }

    fun sendPhoneVerificationOtp(rawPhone: String, activity: Activity, isEn: Boolean) {
        if (!networkMonitor.isCurrentlyConnected()) {
            _uiStatus.value = ProfileUiStatus(message = if (isEn) "No internet connection" else "لا يوجد اتصال بالإنترنت", isError = true)
            return
        }
        val normalized = PhoneUtils.normalizeToE164(rawPhone)
        if (normalized == null) {
            _uiStatus.value = ProfileUiStatus(
                message = if (isEn) "Invalid E.164 phone format (e.g. +201012345678)" else "صيغة الرقم غير صحيحة (مثال: +201012345678 أو 01012345678)",
                isError = true
            )
            return
        }
        viewModelScope.launch {
            _uiStatus.value = ProfileUiStatus(isSaving = true)
            authRepository.sendOtpForPhoneLink(normalized, activity).collect { event ->
                when (event) {
                    is PhoneAuthEvent.CodeSent -> {
                        _uiStatus.value = ProfileUiStatus(
                            isSaving = false,
                            pendingOtpVerificationId = event.verificationId,
                            message = if (isEn) "SMS code sent to $normalized" else "تم إرسال رمز SMS إلى $normalized"
                        )
                    }
                    is PhoneAuthEvent.Verified -> {
                        _uiStatus.value = ProfileUiStatus(
                            isSaving = false,
                            pendingOtpVerificationId = null,
                            message = if (isEn) "Phone verified via SMS ✓" else "تم تأكيد رقم الهاتف بنجاح ✓"
                        )
                    }
                    is PhoneAuthEvent.Error -> {
                        _uiStatus.value = ProfileUiStatus(
                            isSaving = false,
                            isError = true,
                            message = if (isEn) event.messageEn else event.messageAr
                        )
                    }
                }
            }
        }
    }

    fun confirmPhoneVerificationOtp(smsCode: String, isEn: Boolean) {
        val vid = _uiStatus.value.pendingOtpVerificationId ?: return
        if (smsCode.trim().length < 4) return
        viewModelScope.launch {
            _uiStatus.value = _uiStatus.value.copy(isSaving = true)
            authRepository.verifyOtpAndLinkPhone(vid, smsCode)
                .onSuccess {
                    _uiStatus.value = ProfileUiStatus(
                        isSaving = false,
                        pendingOtpVerificationId = null,
                        message = if (isEn) "Phone verified via SMS ✓" else "تم توثيق رقم الهاتف عبر SMS بنجاح ✓"
                    )
                }
                .onFailure { err ->
                    _uiStatus.value = _uiStatus.value.copy(
                        isSaving = false,
                        isError = true,
                        message = err.localizedMessage ?: if (isEn) "Invalid verification code" else "رمز التحقق غير صحيح"
                    )
                }
        }
    }

    fun dismissOtpDialog() {
        _uiStatus.value = _uiStatus.value.copy(pendingOtpVerificationId = null)
    }

    fun removeOptionalPhone(isEn: Boolean) {
        if (!networkMonitor.isCurrentlyConnected()) {
            _uiStatus.value = ProfileUiStatus(message = if (isEn) "No internet connection" else "لا يوجد اتصال بالإنترنت", isError = true)
            return
        }
        viewModelScope.launch {
            _uiStatus.value = ProfileUiStatus(isSaving = true)
            userRepository.removeOptionalPhone()
                .onSuccess {
                    _uiStatus.value = ProfileUiStatus(
                        message = if (isEn) "Phone number removed" else "تمت إزالة رقم الهاتف",
                        isError = false
                    )
                }
                .onFailure { err ->
                    _uiStatus.value = ProfileUiStatus(message = err.localizedMessage ?: "Error", isError = true)
                }
        }
    }

    fun toggleHidePhoneNumber(currentSettings: PrivacySettings, hide: Boolean) {
        viewModelScope.launch {
            userRepository.updatePrivacySettings(currentSettings.copy(hidePhoneNumber = hide))
        }
    }

    fun removePhoto(isEn: Boolean) {
        if (!networkMonitor.isCurrentlyConnected()) {
            _uiStatus.value = ProfileUiStatus(message = if (isEn) "No internet connection" else "لا يوجد اتصال بالإنترنت", isError = true)
            return
        }
        viewModelScope.launch {
            _uiStatus.value = ProfileUiStatus(isSaving = true)
            userRepository.removeProfilePhoto()
                .onSuccess {
                    _uiStatus.value = ProfileUiStatus(message = if (isEn) "Photo removed" else "تم حذف الصورة الشخصية", isError = false)
                }
                .onFailure { err ->
                    _uiStatus.value = ProfileUiStatus(message = err.localizedMessage ?: "Error", isError = true)
                }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val context = LocalContext.current
    val activity = context as? Activity
    val clipboard = LocalClipboardManager.current
    val user by viewModel.currentUser.collectAsStateWithLifecycle()
    val uiStatus by viewModel.uiStatus.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)

    var name by remember(user?.uid) { mutableStateOf(user?.displayName.orEmpty()) }
    var bio by remember(user?.uid) { mutableStateOf(user?.bio.orEmpty()) }
    var publicId by remember(user?.uid) { mutableStateOf(user?.publicId.orEmpty()) }
    var optionalPhone by remember(user?.uid, user?.phoneNumber) { mutableStateOf(user?.phoneNumber.orEmpty()) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var pendingRawUri by remember { mutableStateOf<Uri?>(null) }
    var pendingPreviewUri by remember { mutableStateOf<Uri?>(null) }
    var cameraTempUri by remember { mutableStateOf<Uri?>(null) }
    var showSourceSheet by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val cropLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            UCrop.getOutput(result.data!!)?.let { croppedUri -> pendingPreviewUri = croppedUri }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            pendingRawUri = uri
            cropLauncher.launch(AvatarCropHelper.createCropIntent(context, uri))
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val captured = cameraTempUri
        if (success && captured != null) {
            pendingRawUri = captured
            cropLauncher.launch(AvatarCropHelper.createCropIntent(context, captured))
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val uri = AvatarCropHelper.createCameraPhotoUri(context)
            cameraTempUri = uri
            cameraLauncher.launch(uri)
        } else {
            Toast.makeText(context, if (strings.isEnglish) "Camera permission required" else "يرجى منح صلاحية الكاميرا", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchCameraFlow() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            val uri = AvatarCropHelper.createCameraPhotoUri(context)
            cameraTempUri = uri
            cameraLauncher.launch(uri)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        containerColor = palette.background,
        topBar = {
            TopAppBar(
                title = { Text(if (strings.isEnglish) "Profile" else "الملف الشخصي", color = palette.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back, tint = palette.textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier.clickable(enabled = !uiStatus.isSaving) { showSourceSheet = true },
                contentAlignment = Alignment.BottomEnd
            ) {
                WesalAvatar(
                    imageUrl = selectedUri?.toString() ?: user?.photoUrl.orEmpty(),
                    name = name.ifBlank { "WESAL" },
                    size = 112.dp
                )
                Surface(color = palette.surface, shape = RoundedCornerShape(50), tonalElevation = 4.dp) {
                    Icon(Icons.Default.Edit, contentDescription = strings.edit, tint = palette.turquoise, modifier = Modifier.padding(6.dp).size(18.dp))
                }
            }

            if (user != null) {
                WesalNameWithBadge(
                    name = name.ifBlank { "@$publicId" },
                    isVerified = user?.isVerified == true,
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.textPrimary
                )
                val immutableNumericId = user?.numericId.orEmpty()
                if (immutableNumericId.isNotBlank()) {
                    Surface(
                        color = palette.surfaceElevated,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, palette.turquoise.copy(alpha = 0.45f)),
                        modifier = Modifier.clickable {
                            clipboard.setText(AnnotatedString(immutableNumericId))
                            Toast.makeText(
                                context,
                                "${strings.wesalNumericIdCopied} $immutableNumericId",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "${strings.wesalNumericIdLabel}: $immutableNumericId",
                                style = MaterialTheme.typography.labelLarge,
                                color = palette.turquoise,
                                fontWeight = FontWeight.Bold
                            )
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = strings.copy,
                                tint = palette.turquoise,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(enabled = !uiStatus.isSaving, onClick = { showSourceSheet = true }) {
                    Icon(Icons.Default.AddAPhoto, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (strings.isEnglish) "Change Photo" else "تغيير الصورة", color = palette.turquoise)
                }

                if (selectedUri != null || !user?.photoUrl.isNullOrBlank()) {
                    OutlinedButton(enabled = !uiStatus.isSaving, onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = palette.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (strings.isEnglish) "Remove Photo" else "حذف الصورة", color = palette.error)
                    }
                }
            }

            OutlinedTextField(
colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = palette.textPrimary,
                unfocusedTextColor = palette.textPrimary,
                focusedLabelColor = palette.turquoise,
                unfocusedLabelColor = palette.textSecondary,
                focusedPlaceholderColor = palette.textMuted,
                unfocusedPlaceholderColor = palette.textMuted,
                cursorColor = palette.turquoise
            ),
                            value = name,
                onValueChange = { name = it },
                label = { Text(if (strings.isEnglish) "Display Name" else "الاسم الظاهر") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = palette.textPrimary,
                unfocusedTextColor = palette.textPrimary,
                focusedLabelColor = palette.turquoise,
                unfocusedLabelColor = palette.textSecondary,
                focusedPlaceholderColor = palette.textMuted,
                unfocusedPlaceholderColor = palette.textMuted,
                cursorColor = palette.turquoise
            ),
                                value = publicId,
                    onValueChange = { raw ->
                        publicId = raw.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }
                    },
                    label = { Text("WESAL ID (@username)") },
                    prefix = { Text("@", color = palette.turquoise, fontWeight = FontWeight.Bold) },
                    leadingIcon = { Icon(Icons.Default.AlternateEmail, contentDescription = null, tint = palette.turquoise) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            }

            if (publicId.isNotBlank()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString("@${publicId.trim()}"))
                            Toast.makeText(context, "@${publicId.trim()}", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (strings.isEnglish) "Copy ID" else "نسخ المعرف", color = palette.turquoise)
                    }
                    OutlinedButton(
                        onClick = {
                            WesalShareUtils.shareViaWhatsApp(
                                context,
                                WesalShareUtils.buildInviteMessage(publicId, isEnglish = strings.isEnglish)
                            )
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = palette.emerald, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.inviteViaWhatsApp, color = palette.emerald)
                    }
                }
            }

            OutlinedTextField(
colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = palette.textPrimary,
                unfocusedTextColor = palette.textPrimary,
                focusedLabelColor = palette.turquoise,
                unfocusedLabelColor = palette.textSecondary,
                focusedPlaceholderColor = palette.textMuted,
                unfocusedPlaceholderColor = palette.textMuted,
                cursorColor = palette.turquoise
            ),
                            value = bio,
                onValueChange = { bio = it },
                label = { Text(if (strings.isEnglish) "Bio" else "الوصف الشخصي") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
                shape = RoundedCornerShape(12.dp)
            )

            Button(
                enabled = !uiStatus.isSaving,
                onClick = {
                    viewModel.saveProfile(name, bio, publicId, selectedUri, strings.isEnglish)
                    selectedUri = null
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = palette.onAccent),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (uiStatus.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = palette.onAccent)
                } else {
                    Text(strings.save, fontWeight = FontWeight.Bold)
                }
            }

            if (!user?.email.isNullOrBlank()) {
                Surface(
                    color = palette.surface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, palette.border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Email, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(18.dp))
                            Text(if (strings.isEnglish) "Email" else "البريد الإلكتروني", color = palette.textSecondary)
                        }
                        Text(user!!.email, color = palette.textPrimary, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // Phone Number + SMS OTP Verification + Privacy Hide Toggle
            Surface(
                color = palette.surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, palette.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Phone, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(18.dp))
                            Text(strings.phoneNumberLabel, style = MaterialTheme.typography.titleMedium, color = palette.textPrimary, fontWeight = FontWeight.Bold)
                        }

                        val isPhoneVerified = user?.isPhoneVerified == true
                        val hasPhone = !user?.phoneNumber.isNullOrBlank()
                        val badgeColor = when {
                            isPhoneVerified -> palette.emerald
                            hasPhone -> palette.warning
                            else -> palette.textMuted
                        }
                        val badgeText = when {
                            isPhoneVerified -> strings.phoneVerifiedBadge
                            hasPhone -> strings.phoneUnverifiedBadge
                            else -> if (strings.isEnglish) "Not added" else "لم يُضف"
                        }
                        Surface(
                            color = badgeColor.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (isPhoneVerified) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = palette.emerald,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                Text(badgeText, color = badgeColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        OutlinedTextField(
colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = palette.textPrimary,
                unfocusedTextColor = palette.textPrimary,
                focusedLabelColor = palette.turquoise,
                unfocusedLabelColor = palette.textSecondary,
                focusedPlaceholderColor = palette.textMuted,
                unfocusedPlaceholderColor = palette.textMuted,
                cursorColor = palette.turquoise
            ),
                                        value = optionalPhone,
                            onValueChange = { optionalPhone = it },
                            label = { Text(strings.phoneNumberLabel) },
                            placeholder = { Text("+201012345678") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = !uiStatus.isSaving && optionalPhone.isNotBlank(),
                            onClick = { viewModel.updateOptionalPhone(optionalPhone, strings.isEnglish) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(strings.save, color = palette.turquoise, fontWeight = FontWeight.Bold)
                        }
                        if (!user?.phoneNumber.isNullOrBlank()) {
                            OutlinedButton(
                                enabled = !uiStatus.isSaving,
                                onClick = {
                                    optionalPhone = ""
                                    viewModel.removeOptionalPhone(strings.isEnglish)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(strings.delete, color = palette.error)
                            }
                        }
                    }

                    val savedPhoneNorm = PhoneUtils.normalizeToE164(user?.phoneNumber.orEmpty()) ?: user?.phoneNumber.orEmpty()
                    val inputPhoneNorm = PhoneUtils.normalizeToE164(optionalPhone) ?: optionalPhone.trim()
                    val isSameVerifiedPhone = user?.isPhoneVerified == true &&
                        savedPhoneNorm.isNotBlank() &&
                        inputPhoneNorm == savedPhoneNorm

                    if (isSameVerifiedPhone) {
                        Text(
                            text = strings.phoneAlreadyVerifiedNote,
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.emerald,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else if (optionalPhone.isNotBlank() && activity != null) {
                        Button(
                            enabled = !uiStatus.isSaving,
                            onClick = { viewModel.sendPhoneVerificationOtp(optionalPhone, activity, strings.isEnglish) },
                            colors = ButtonDefaults.buttonColors(containerColor = palette.turquoise, contentColor = palette.onAccent),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Sms, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(strings.verifyPhoneNow, fontWeight = FontWeight.Bold)
                        }
                    }

                    HorizontalDivider(color = palette.border)

                    user?.privacySettings?.let { ps ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(strings.hidePhoneNumberTitle, color = palette.textPrimary, fontWeight = FontWeight.SemiBold)
                                Text(strings.hidePhoneNumberSubtitle, style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
                            }
                            Switch(
                                checked = ps.hidePhoneNumber,
                                onCheckedChange = { hide -> viewModel.toggleHidePhoneNumber(ps, hide) }
                            )
                        }
                    }
                }
            }

            uiStatus.message?.let { msg ->
                Surface(
                    color = (if (uiStatus.isError) palette.error else palette.emerald).copy(alpha = 0.14f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = msg,
                        color = if (uiStatus.isError) palette.error else palette.emerald,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }

    // In-Profile SMS OTP Code Entry Dialog
    if (uiStatus.pendingOtpVerificationId != null) {
        var smsCode by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = viewModel::dismissOtpDialog,
            containerColor = palette.surface,
            title = { Text(strings.verifyPhoneNow, color = palette.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (strings.isEnglish) "Enter the 6-digit SMS code sent to $optionalPhone" else "أدخل رمز التحقق المكوّن من 6 أرقام المرسل إلى $optionalPhone",
                        color = palette.textSecondary
                    )
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        OutlinedTextField(
colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = palette.textPrimary,
                unfocusedTextColor = palette.textPrimary,
                focusedLabelColor = palette.turquoise,
                unfocusedLabelColor = palette.textSecondary,
                focusedPlaceholderColor = palette.textMuted,
                unfocusedPlaceholderColor = palette.textMuted,
                cursorColor = palette.turquoise
            ),
                                        value = smsCode,
                            onValueChange = { if (it.length <= 6) smsCode = it.filter(Char::isDigit) },
                            label = { Text("OTP") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = smsCode.length >= 6 && !uiStatus.isSaving,
                    onClick = { viewModel.confirmPhoneVerificationOtp(smsCode, strings.isEnglish) }
                ) { Text(strings.confirm) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissOtpDialog) { Text(strings.cancel) }
            }
        )
    }

    pendingPreviewUri?.let { previewUri ->
        AvatarPreviewConfirmDialog(
            imageUri = previewUri,
            onConfirm = {
                selectedUri = previewUri
                pendingPreviewUri = null
                viewModel.saveProfile(name, bio, publicId, previewUri, strings.isEnglish)
                selectedUri = null
            },
            onRecrop = {
                pendingPreviewUri = null
                pendingRawUri?.let { raw -> cropLauncher.launch(AvatarCropHelper.createCropIntent(context, raw)) }
            },
            onCancel = { pendingPreviewUri = null }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = palette.surface,
            title = { Text(if (strings.isEnglish) "Remove Photo" else "حذف الصورة الشخصية", color = palette.textPrimary) },
            text = { Text(if (strings.isEnglish) "Revert to default monogram avatar?" else "هل تريد حذف صورتك الشخصية الحالية؟", color = palette.textSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        selectedUri = null
                        viewModel.removePhoto(strings.isEnglish)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.error)
                ) { Text(strings.delete) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(strings.cancel) }
            }
        )
    }

    if (showSourceSheet) {
        AvatarSourceBottomSheet(
            onPickGallery = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onTakePhoto = { launchCameraFlow() },
            onDismiss = { showSourceSheet = false }
        )
    }
}
