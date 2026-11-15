package com.wesal.messenger.profile

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.core.content.ContextCompat
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
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Verified
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wesal.messenger.core.network.NetworkMonitor
import com.wesal.messenger.core.theme.*
import com.wesal.messenger.core.util.WesalShareUtils
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.UserRepository
import com.wesal.messenger.media.AvatarCropHelper
import com.wesal.messenger.media.AvatarPreviewConfirmDialog
import com.wesal.messenger.media.AvatarSourceBottomSheet
import com.wesal.messenger.presentation.components.WesalAvatar
import com.yalantis.ucrop.UCrop
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiStatus(
    val isSaving: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val networkMonitor: NetworkMonitor
) : ViewModel() {

    val currentUser: StateFlow<WesalUser?> = userRepository.observeCurrentUser()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _uiStatus = MutableStateFlow(ProfileUiStatus())
    val uiStatus: StateFlow<ProfileUiStatus> = _uiStatus.asStateFlow()

    fun saveProfile(displayName: String, bio: String, publicId: String, photoUri: Uri?) {
        if (displayName.isBlank()) {
            _uiStatus.value = ProfileUiStatus(message = "الاسم الظاهر لا يمكن أن يكون فارغاً", isError = true)
            return
        }
        if (photoUri != null && !networkMonitor.isCurrentlyConnected()) {
            _uiStatus.value = ProfileUiStatus(
                message = "لا يوجد اتصال بالإنترنت لرفع الصورة — تم الاحتفاظ بالصورة السابقة",
                isError = true
            )
            return
        }
        viewModelScope.launch {
            _uiStatus.value = ProfileUiStatus(isSaving = true)
            userRepository.updateProfile(displayName, bio, publicId, photoUri)
                .onSuccess {
                    _uiStatus.value = ProfileUiStatus(message = "تم حفظ الملف الشخصي بنجاح", isError = false)
                }
                .onFailure { err ->
                    _uiStatus.value = ProfileUiStatus(
                        message = err.localizedMessage ?: "تعذر حفظ التعديلات (تمت المحافظة على الصورة الحالية)",
                        isError = true
                    )
                }
        }
    }

    fun updateOptionalPhone(rawPhone: String) {
        if (!networkMonitor.isCurrentlyConnected()) {
            _uiStatus.value = ProfileUiStatus(message = "لا يوجد اتصال بالإنترنت لتحديث رقم الهاتف", isError = true)
            return
        }
        viewModelScope.launch {
            _uiStatus.value = ProfileUiStatus(isSaving = true)
            userRepository.updateOptionalPhone(rawPhone)
                .onSuccess {
                    val msg = if (rawPhone.isBlank()) {
                        "تمت إزالة رقم الهاتف الاختياري"
                    } else {
                        "تم حفظ رقم الهاتف في ملفك الشخصي"
                    }
                    _uiStatus.value = ProfileUiStatus(message = msg, isError = false)
                }
                .onFailure { err ->
                    _uiStatus.value = ProfileUiStatus(
                        message = err.localizedMessage ?: "تعذر تحديث رقم الهاتف",
                        isError = true
                    )
                }
        }
    }

    fun removeOptionalPhone() {
        if (!networkMonitor.isCurrentlyConnected()) {
            _uiStatus.value = ProfileUiStatus(message = "لا يوجد اتصال بالإنترنت لإزالة رقم الهاتف", isError = true)
            return
        }
        viewModelScope.launch {
            _uiStatus.value = ProfileUiStatus(isSaving = true)
            userRepository.removeOptionalPhone()
                .onSuccess {
                    _uiStatus.value = ProfileUiStatus(message = "تمت إزالة رقم الهاتف الاختياري", isError = false)
                }
                .onFailure { err ->
                    _uiStatus.value = ProfileUiStatus(
                        message = err.localizedMessage ?: "تعذر إزالة رقم الهاتف",
                        isError = true
                    )
                }
        }
    }

    fun removePhoto() {
        if (!networkMonitor.isCurrentlyConnected()) {
            _uiStatus.value = ProfileUiStatus(message = "لا يوجد اتصال بالإنترنت لحذف الصورة", isError = true)
            return
        }
        viewModelScope.launch {
            _uiStatus.value = ProfileUiStatus(isSaving = true)
            userRepository.removeProfilePhoto()
                .onSuccess {
                    _uiStatus.value = ProfileUiStatus(message = "تم حذف الصورة الشخصية", isError = false)
                }
                .onFailure { err ->
                    _uiStatus.value = ProfileUiStatus(
                        message = err.localizedMessage ?: "تعذر حذف الصورة الشخصية",
                        isError = true
                    )
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
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val user by viewModel.currentUser.collectAsStateWithLifecycle()
    val uiStatus by viewModel.uiStatus.collectAsStateWithLifecycle()

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

    val cropLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            UCrop.getOutput(result.data!!)?.let { croppedUri ->
                pendingPreviewUri = croppedUri
            }
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

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val uri = AvatarCropHelper.createCameraPhotoUri(context)
            cameraTempUri = uri
            cameraLauncher.launch(uri)
        } else {
            Toast.makeText(context, "يرجى منح صلاحية الكاميرا لالتقاط صورة شخصية", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchCameraFlow() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            val uri = AvatarCropHelper.createCameraPhotoUri(context)
            cameraTempUri = uri
            cameraLauncher.launch(uri)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        containerColor = WesalDeepBlack,
        topBar = {
            TopAppBar(
                title = { Text("الملف الشخصي", color = WesalTextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = WesalTextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WesalSurfaceDark)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(WesalDeepBlack)
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier.clickable(enabled = !uiStatus.isSaving) {
                    showSourceSheet = true
                },
                contentAlignment = Alignment.BottomEnd
            ) {
                WesalAvatar(
                    imageUrl = selectedUri?.toString() ?: user?.photoUrl.orEmpty(),
                    name = name.ifBlank { "وصال" },
                    size = 112.dp
                )
                Surface(
                    color = WesalSurfaceDark,
                    shape = RoundedCornerShape(50),
                    tonalElevation = 4.dp
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "تغيير الصورة",
                        tint = WesalTurquoise,
                        modifier = Modifier.padding(6.dp).size(18.dp)
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    enabled = !uiStatus.isSaving,
                    onClick = { showSourceSheet = true }
                ) {
                    Icon(Icons.Default.AddAPhoto, contentDescription = null, tint = WesalTurquoise, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("تغيير الصورة", color = WesalTurquoise)
                }

                if (selectedUri != null || !user?.photoUrl.isNullOrBlank()) {
                    OutlinedButton(
                        enabled = !uiStatus.isSaving,
                        onClick = { showDeleteConfirm = true }
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = WesalError, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("حذف الصورة", color = WesalError)
                    }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("الاسم الظاهر") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Searchable Unique Wesal ID
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = publicId,
                    onValueChange = { raw ->
                        publicId = raw.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }
                    },
                    label = { Text("معرف وصال الفريد (Wesal ID)") },
                    prefix = { Text("@", color = WesalTurquoise, fontWeight = FontWeight.Bold) },
                    leadingIcon = { Icon(Icons.Default.AlternateEmail, contentDescription = null, tint = WesalTurquoise) },
                    supportingText = {
                        Text(
                            "يمكن للأصدقاء البحث عنك والتواصل معك عبر هذا المعرف دون الحاجة لرقم هاتفك",
                            color = WesalTextSecondary
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            }

            // Copy & Share Wesal ID Bar
            if (publicId.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString("@${publicId.trim()}"))
                            Toast.makeText(context, "تم نسخ معرف وصال @${publicId.trim()}", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = WesalTurquoise, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("نسخ المعرف", color = WesalTurquoise)
                    }
                    OutlinedButton(
                        onClick = {
                            WesalShareUtils.shareViaWhatsApp(
                                context,
                                WesalShareUtils.buildInviteMessage(publicId)
                            )
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = WesalEmerald, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("دعوة عبر واتساب", color = WesalEmerald)
                    }
                }
            }

            OutlinedTextField(
                value = bio,
                onValueChange = { bio = it },
                label = { Text("الوصف الشخصي") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
                shape = RoundedCornerShape(12.dp)
            )

            Button(
                enabled = !uiStatus.isSaving,
                onClick = {
                    viewModel.saveProfile(name, bio, publicId, selectedUri)
                    selectedUri = null
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = WesalEmerald, contentColor = WesalDeepBlack),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (uiStatus.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = WesalDeepBlack)
                } else {
                    Text("حفظ الاسم والمعرف والصورة", fontWeight = FontWeight.Bold)
                }
            }

            // Registered Email Card
            if (!user?.email.isNullOrBlank()) {
                Surface(
                    color = WesalSurfaceDark,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, WesalSurfaceBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Email, contentDescription = null, tint = WesalTurquoise, modifier = Modifier.size(18.dp))
                            Text("البريد الإلكتروني", color = WesalTextSecondary)
                        }
                        Text(user!!.email, color = WesalTextPrimary, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // Optional Phone Number Section with Explicit Verification Status
            Surface(
                color = WesalSurfaceDark,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, WesalSurfaceBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Phone, contentDescription = null, tint = WesalTurquoise, modifier = Modifier.size(18.dp))
                            Text(
                                text = "رقم الهاتف (اختياري)",
                                style = MaterialTheme.typography.titleMedium,
                                color = WesalTextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        val isVerified = user?.isPhoneVerified == true
                        val hasPhone = !user?.phoneNumber.isNullOrBlank()
                        val badgeColor = when {
                            isVerified -> WesalEmerald
                            hasPhone -> WesalWarning
                            else -> WesalTextMuted
                        }
                        val badgeText = when {
                            isVerified -> "رقم موثق ✓"
                            hasPhone -> "غير موثق (اختياري)"
                            else -> "لم تتم الإضافة"
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
                                if (isVerified) {
                                    Icon(Icons.Default.Verified, contentDescription = null, tint = badgeColor, modifier = Modifier.size(14.dp))
                                }
                                Text(
                                    text = badgeText,
                                    color = badgeColor,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Text(
                        text = if (user?.isPhoneVerified == true) {
                            "هذا الرقم موثق ومؤهل للظهور بعلامة «يستخدم وصال» لدى جهات الاتصال المطابقة."
                        } else {
                            "إضافة رقم الهاتف اختيارية تماماً. الأرقام غير الموثقة لا تمنح علامة «يستخدم وصال» في مطابقة جهات الاتصال لحماية الخصوصية ومنع الانتحال."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = WesalTextSecondary
                    )

                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        OutlinedTextField(
                            value = optionalPhone,
                            onValueChange = { optionalPhone = it },
                            label = { Text("رقم الهاتف الاختياري") },
                            placeholder = { Text("+201012345678 أو 01012345678") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            enabled = !uiStatus.isSaving && optionalPhone.isNotBlank(),
                            onClick = { viewModel.updateOptionalPhone(optionalPhone) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("حفظ الرقم", color = WesalTurquoise, fontWeight = FontWeight.Bold)
                        }
                        if (!user?.phoneNumber.isNullOrBlank()) {
                            OutlinedButton(
                                enabled = !uiStatus.isSaving,
                                onClick = {
                                    optionalPhone = ""
                                    viewModel.removeOptionalPhone()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("إزالة الرقم", color = WesalError)
                            }
                        }
                    }
                }
            }

            uiStatus.message?.let { msg ->
                Surface(
                    color = (if (uiStatus.isError) WesalError else WesalEmerald).copy(alpha = 0.14f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = msg,
                        color = if (uiStatus.isError) WesalError else WesalEmerald,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }

    pendingPreviewUri?.let { previewUri ->
        AvatarPreviewConfirmDialog(
            imageUri = previewUri,
            onConfirm = {
                selectedUri = previewUri
                pendingPreviewUri = null
                viewModel.saveProfile(name, bio, publicId, previewUri)
                selectedUri = null
            },
            onRecrop = {
                pendingPreviewUri = null
                pendingRawUri?.let { raw ->
                    cropLauncher.launch(AvatarCropHelper.createCropIntent(context, raw))
                }
            },
            onCancel = {
                pendingPreviewUri = null
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = WesalSurfaceDark,
            title = { Text("حذف الصورة الشخصية", color = WesalTextPrimary) },
            text = { Text("هل تريد حذف صورتك الشخصية الحالية والعودة إلى الحرف الافتراضي؟", color = WesalTextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        selectedUri = null
                        viewModel.removePhoto()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WesalError)
                ) {
                    Text("حذف الصورة")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("إلغاء", color = WesalTextSecondary)
                }
            }
        )
    }

    if (showSourceSheet) {
        AvatarSourceBottomSheet(
            onPickGallery = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onTakePhoto = { launchCameraFlow() },
            onDismiss = { showSourceSheet = false }
        )
    }
}
