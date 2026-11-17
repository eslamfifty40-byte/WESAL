package com.wesal.messenger.profile

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
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
import coil.compose.AsyncImage
import com.wesal.messenger.authentication.CountryCatalog
import com.wesal.messenger.core.network.NetworkMonitor
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.core.util.NeonColorPreset
import com.wesal.messenger.core.util.PhoneUtils
import com.wesal.messenger.core.util.WesalFrameCatalog
import com.wesal.messenger.core.util.WesalFrameLibrary
import androidx.compose.ui.graphics.Color
import com.wesal.messenger.core.util.WesalShareUtils
import com.wesal.messenger.crop.CropActivity
import com.wesal.messenger.domain.model.BusinessDayHours
import com.wesal.messenger.domain.model.BusinessDaySlot
import com.wesal.messenger.domain.model.BusinessHoursSchedule
import com.wesal.messenger.domain.model.CountryCode
import com.wesal.messenger.domain.model.PrivacySettings
import com.wesal.messenger.domain.model.ProfileVisitor
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.UserRepository
import com.wesal.messenger.media.AvatarCropHelper
import com.wesal.messenger.media.AvatarPreviewConfirmDialog
import com.wesal.messenger.media.AvatarSourceBottomSheet
import com.wesal.messenger.presentation.components.WesalAvatar
import com.wesal.messenger.presentation.components.WesalNameWithBadge
import com.wesal.messenger.presentation.components.wesalImageModel
import com.wesal.messenger.presentation.components.wesalNeonTextStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

data class ProfileUiStatus(
    val isSaving: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    val navigateBackOnSuccess: Boolean = false
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val networkMonitor: NetworkMonitor
) : ViewModel() {

    val currentUser: StateFlow<WesalUser?> = userRepository.observeCurrentUser()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val profileVisitors: StateFlow<List<ProfileVisitor>> = userRepository.observeProfileVisitors()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiStatus = MutableStateFlow(ProfileUiStatus())
    val uiStatus: StateFlow<ProfileUiStatus> = _uiStatus.asStateFlow()

    fun clearMessage() {
        _uiStatus.update { it.copy(message = null, isError = false, navigateBackOnSuccess = false) }
    }

    fun saveFullProfile(
        displayName: String,
        bio: String,
        publicId: String,
        photoUri: Uri?,
        coverUri: Uri?,
        birthDate: String,
        hideBirthDate: Boolean,
        facebookUrl: String,
        instagramUrl: String,
        businessHours: BusinessHoursSchedule,
        isEn: Boolean,
        navigateBackAfterSave: Boolean = true
    ) {
        if (displayName.trim().length < 2) {
            _uiStatus.value = ProfileUiStatus(
                message = if (isEn) "Display name must be at least 2 characters" else "الاسم الظاهر يجب أن يكون حرفين على الأقل",
                isError = true
            )
            return
        }
        if ((photoUri != null || coverUri != null) && !networkMonitor.isCurrentlyConnected()) {
            _uiStatus.value = ProfileUiStatus(
                message = if (isEn) "No internet to upload photo" else "لا يوجد اتصال بالإنترنت لرفع الصورة",
                isError = true
            )
            return
        }
        viewModelScope.launch {
            _uiStatus.value = ProfileUiStatus(isSaving = true)
            userRepository.updateProfile(
                displayName = displayName,
                bio = bio,
                publicId = publicId,
                photoUri = photoUri,
                coverUri = coverUri,
                birthDate = birthDate.takeIf { it.isNotBlank() },
                hideBirthDate = hideBirthDate,
                facebookUrl = facebookUrl.trim(),
                instagramUrl = instagramUrl.trim(),
                businessHours = businessHours
            ).onSuccess {
                _uiStatus.value = ProfileUiStatus(
                    message = if (isEn) "Profile saved" else "تم حفظ الملف الشخصي بنجاح",
                    isError = false,
                    navigateBackOnSuccess = navigateBackAfterSave
                )
            }.onFailure { err ->
                _uiStatus.value = ProfileUiStatus(
                    message = err.localizedMessage ?: if (isEn) "Failed to save profile" else "تعذر حفظ التعديلات",
                    isError = true,
                    navigateBackOnSuccess = false
                )
            }
        }
    }

    fun saveImages(photoUri: Uri?, coverUri: Uri?, isEn: Boolean) {
        if (!networkMonitor.isCurrentlyConnected()) {
            _uiStatus.value = ProfileUiStatus(
                message = if (isEn) "No internet to upload photo" else "لا يوجد اتصال بالإنترنت لرفع الصورة",
                isError = true
            )
            return
        }
        viewModelScope.launch {
            _uiStatus.value = ProfileUiStatus(isSaving = true)
            userRepository.updateProfileImages(photoUri, coverUri)
                .onSuccess {
                    _uiStatus.value = ProfileUiStatus(
                        message = if (isEn) "Photo saved" else "تم حفظ الصورة على حسابك",
                        isError = false
                    )
                }
                .onFailure { err ->
                    _uiStatus.value = ProfileUiStatus(
                        message = (if (isEn) "Photo not saved: " else "لم يتم حفظ الصورة: ") + (err.localizedMessage ?: ""),
                        isError = true
                    )
                }
        }
    }

    fun updateCountryPhone(country: CountryCode, localPhone: String, isEn: Boolean) {
        if (!networkMonitor.isCurrentlyConnected()) {
            _uiStatus.value = ProfileUiStatus(
                message = if (isEn) "No internet connection" else "لا يوجد اتصال بالإنترنت لتحديث رقم الهاتف",
                isError = true
            )
            return
        }
        val check = PhoneUtils.validateForCountry(country, localPhone)
        if (!check.isValid || check.e164.isNullOrBlank()) {
            _uiStatus.value = ProfileUiStatus(
                message = if (isEn) check.errorEn else check.errorAr,
                isError = true
            )
            return
        }
        viewModelScope.launch {
            _uiStatus.value = ProfileUiStatus(isSaving = true)
            userRepository.updatePhoneValidated(check.e164)
                .onSuccess {
                    _uiStatus.value = ProfileUiStatus(
                        message = if (isEn) "Phone number saved" else "تم حفظ رقم الهاتف بنجاح",
                        isError = false
                    )
                }
                .onFailure { err ->
                    _uiStatus.value = ProfileUiStatus(
                        message = err.localizedMessage ?: if (isEn) "Phone number unavailable" else "تعذر حفظ رقم الهاتف",
                        isError = true
                    )
                }
        }
    }

    fun removeOptionalPhone(isEn: Boolean) {
        if (!networkMonitor.isCurrentlyConnected()) {
            _uiStatus.value = ProfileUiStatus(
                message = if (isEn) "No internet connection" else "لا يوجد اتصال بالإنترنت",
                isError = true
            )
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

    fun updatePrivacy(settings: PrivacySettings) {
        viewModelScope.launch {
            userRepository.updatePrivacySettings(settings)
        }
    }

    fun removePhoto(isEn: Boolean) {
        if (!networkMonitor.isCurrentlyConnected()) {
            _uiStatus.value = ProfileUiStatus(
                message = if (isEn) "No internet connection" else "لا يوجد اتصال بالإنترنت",
                isError = true
            )
            return
        }
        viewModelScope.launch {
            _uiStatus.value = ProfileUiStatus(isSaving = true)
            userRepository.removeProfilePhoto()
                .onSuccess {
                    _uiStatus.value = ProfileUiStatus(
                        message = if (isEn) "Photo removed" else "تم حذف الصورة الشخصية",
                        isError = false
                    )
                }
                .onFailure { err ->
                    _uiStatus.value = ProfileUiStatus(message = err.localizedMessage ?: "Error", isError = true)
                }
        }
    }

    fun removeCover(isEn: Boolean) {
        if (!networkMonitor.isCurrentlyConnected()) {
            _uiStatus.value = ProfileUiStatus(
                message = if (isEn) "No internet connection" else "لا يوجد اتصال بالإنترنت",
                isError = true
            )
            return
        }
        viewModelScope.launch {
            _uiStatus.value = ProfileUiStatus(isSaving = true)
            userRepository.removeProfileCover()
                .onSuccess {
                    _uiStatus.value = ProfileUiStatus(
                        message = if (isEn) "Cover removed" else "تم حذف صورة الغلاف",
                        isError = false
                    )
                }
                .onFailure { err ->
                    _uiStatus.value = ProfileUiStatus(message = err.localizedMessage ?: "Error", isError = true)
                }
        }
    }

    fun clearVisitors(isEn: Boolean) {
        viewModelScope.launch {
            userRepository.clearProfileVisitors()
                .onSuccess {
                    _uiStatus.value = ProfileUiStatus(
                        message = if (isEn) "Visitors list cleared" else "تم مسح سجل زوار الحساب",
                        isError = false
                    )
                }
                .onFailure { err ->
                    _uiStatus.value = ProfileUiStatus(message = err.localizedMessage ?: "Error", isError = true)
                }
        }
    }
}

private enum class PendingCropKind {
    AVATAR,
    COVER
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onOpenAccountShare: () -> Unit = {},
    onOpenUserProfile: (String) -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val user by viewModel.currentUser.collectAsStateWithLifecycle()
    val visitors by viewModel.profileVisitors.collectAsStateWithLifecycle()
    val uiStatus by viewModel.uiStatus.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)

    // Keep user edits preserved on validation error; only initialize once per uid load
    var initializedForUid by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var publicId by remember { mutableStateOf("") }
    var selectedCountry by remember { mutableStateOf(CountryCatalog.defaultEgypt) }
    var localPhone by remember { mutableStateOf("") }
    var birthDateIso by remember { mutableStateOf("") }
    var hideBirthDate by remember { mutableStateOf(false) }
    var facebookUrl by remember { mutableStateOf("") }
    var instagramUrl by remember { mutableStateOf("") }
    var businessHours by remember { mutableStateOf(BusinessHoursSchedule()) }

    LaunchedEffect(user?.uid, user?.profileCompleted) {
        val loaded = user ?: return@LaunchedEffect
        if (initializedForUid != loaded.uid) {
            initializedForUid = loaded.uid
            name = loaded.displayName
            bio = loaded.bio
            publicId = loaded.publicId
            val (country, local) = CountryCatalog.detectFromE164(loaded.phoneNumber)
            selectedCountry = country
            localPhone = local
            birthDateIso = loaded.birthDate
            hideBirthDate = loaded.privacySettings.hideBirthDate
            facebookUrl = loaded.facebookUrl
            instagramUrl = loaded.instagramUrl
            businessHours = loaded.businessHours
        }
    }

    LaunchedEffect(uiStatus.message, uiStatus.navigateBackOnSuccess) {
        val msg = uiStatus.message ?: return@LaunchedEffect
        if (uiStatus.navigateBackOnSuccess && !uiStatus.isError) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
            onBack()
        } else {
            delay(3400L)
            viewModel.clearMessage()
        }
    }

    var selectedAvatarUri by remember { mutableStateOf<Uri?>(null) }
    var selectedCoverUri by remember { mutableStateOf<Uri?>(null) }
    // If a save failed, drop the local preview so the screen never shows a photo that was not really saved.
    LaunchedEffect(uiStatus.message, uiStatus.isError) {
        if (uiStatus.isError && uiStatus.message != null) {
            selectedAvatarUri = null
            selectedCoverUri = null
        }
    }
    var activeCropKind by remember { mutableStateOf(PendingCropKind.AVATAR) }
    var pendingRawUri by remember { mutableStateOf<Uri?>(null) }
    var pendingPreviewUri by remember { mutableStateOf<Uri?>(null) }
    var cameraTempUri by remember { mutableStateOf<Uri?>(null) }
    var showSourceSheetFor by remember { mutableStateOf<PendingCropKind?>(null) }
    var showDeleteAvatarConfirm by remember { mutableStateOf(false) }
    var showDeleteCoverConfirm by remember { mutableStateOf(false) }
    var showCountryPicker by remember { mutableStateOf(false) }
    var showBusinessHoursEditor by remember { mutableStateOf(false) }
    var showVisitorsSheet by remember { mutableStateOf(false) }

    val inputColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = palette.textPrimary,
        unfocusedTextColor = palette.textPrimary,
        focusedLabelColor = palette.turquoise,
        unfocusedLabelColor = palette.textSecondary,
        focusedPlaceholderColor = palette.textMuted,
        unfocusedPlaceholderColor = palette.textMuted,
        focusedBorderColor = palette.turquoise,
        unfocusedBorderColor = palette.border,
        cursorColor = palette.turquoise
    )

    val cropLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            AvatarCropHelper.getCropOutput(result.data)?.let { croppedUri ->
                pendingPreviewUri = croppedUri
            }
        }
    }

    fun launchCropFor(kind: PendingCropKind, sourceUri: Uri) {
        activeCropKind = kind
        pendingRawUri = sourceUri
        val mode = if (kind == PendingCropKind.COVER) CropActivity.MODE_COVER_16_9 else CropActivity.MODE_CIRCLE_AVATAR
        cropLauncher.launch(AvatarCropHelper.createCropIntent(context, sourceUri, mode))
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            launchCropFor(activeCropKind, uri)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val captured = cameraTempUri
        if (success && captured != null) {
            launchCropFor(activeCropKind, captured)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val uri = AvatarCropHelper.createCameraPhotoUri(context)
            cameraTempUri = uri
            cameraLauncher.launch(uri)
        } else {
            Toast.makeText(
                context,
                if (strings.isEnglish) "Camera permission required" else "يرجى منح صلاحية الكاميرا",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun launchCameraFlow(kind: PendingCropKind) {
        activeCropKind = kind
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            val uri = AvatarCropHelper.createCameraPhotoUri(context)
            cameraTempUri = uri
            cameraLauncher.launch(uri)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun openBirthDatePicker() {
        val cal = Calendar.getInstance()
        val initial = runCatching { LocalDate.parse(birthDateIso) }.getOrNull()
        val initYear = initial?.year ?: (cal.get(Calendar.YEAR) - 20)
        val initMonth = initial?.monthValue?.minus(1) ?: cal.get(Calendar.MONTH)
        val initDay = initial?.dayOfMonth ?: cal.get(Calendar.DAY_OF_MONTH)
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                birthDateIso = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth)
            },
            initYear,
            initMonth,
            initDay
        ).apply {
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    fun openExternalUrl(rawUrl: String) {
        val clean = rawUrl.trim().let {
            if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "https://$it"
        }
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(clean)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            Toast.makeText(
                context,
                if (strings.isEnglish) "Invalid link" else "تعذر فتح الرابط",
                Toast.LENGTH_SHORT
            ).show()
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
                actions = {
                    IconButton(onClick = onOpenAccountShare) {
                        Icon(Icons.Default.QrCode2, contentDescription = strings.shareAccountQr, tint = palette.turquoise)
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
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Cover Banner + Circular Avatar Header
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.BottomCenter
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(palette.surfaceElevated)
                            .border(
                                1.dp,
                                androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    listOf(palette.turquoise.copy(alpha = 0.75f), palette.emerald.copy(alpha = 0.75f))
                                ),
                                RoundedCornerShape(20.dp)
                            )
                            .clickable(enabled = !uiStatus.isSaving) {
                                activeCropKind = PendingCropKind.COVER
                                showSourceSheetFor = PendingCropKind.COVER
                            }
                    ) {
                        val coverPreview = selectedCoverUri?.toString() ?: user?.coverUrl.orEmpty()
                        if (coverPreview.isNotBlank()) {
                            AsyncImage(
                                model = wesalImageModel(coverPreview),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.Wallpaper, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(30.dp))
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = if (strings.isEnglish) "Tap to add cover photo (16:9)" else "اضغط لإضافة صورة غلاف (16:9)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = palette.textSecondary
                                )
                            }
                        }

                        // dark fade at the bottom of the cover so the avatar / name blend into the page
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    androidx.compose.ui.graphics.Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, palette.background.copy(alpha = 0.10f), palette.background.copy(alpha = 0.80f))
                                    )
                                )
                        )
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                color = palette.background.copy(alpha = 0.72f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.clickable(enabled = !uiStatus.isSaving) {
                                    activeCropKind = PendingCropKind.COVER
                                    showSourceSheetFor = PendingCropKind.COVER
                                }
                            ) {
                                Icon(
                                    Icons.Default.AddPhotoAlternate,
                                    contentDescription = null,
                                    tint = palette.turquoise,
                                    modifier = Modifier.padding(6.dp).size(18.dp)
                                )
                            }
                            if (coverPreview.isNotBlank()) {
                                Surface(
                                    color = palette.background.copy(alpha = 0.72f),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.clickable(enabled = !uiStatus.isSaving) {
                                        showDeleteCoverConfirm = true
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = null,
                                        tint = palette.error,
                                        modifier = Modifier.padding(6.dp).size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(74.dp))
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .clickable(enabled = !uiStatus.isSaving) {
                            activeCropKind = PendingCropKind.AVATAR
                            showSourceSheetFor = PendingCropKind.AVATAR
                        },
                    contentAlignment = Alignment.BottomEnd
                ) {
                    WesalAvatar(
                        imageUrl = selectedAvatarUri?.toString() ?: user?.photoUrl.orEmpty(),
                        name = name.ifBlank { "WESAL" },
                        size = 132.dp,
                        frameId = user?.frameId.orEmpty(),
                        frameExpiresAt = user?.frameExpiresAt ?: 0L
                    )
                    Surface(color = palette.surface, shape = RoundedCornerShape(50), tonalElevation = 4.dp) {
                        Icon(Icons.Default.Edit, contentDescription = strings.edit, tint = palette.turquoise, modifier = Modifier.padding(6.dp).size(18.dp))
                    }
                }
            }

            if (user != null) {
                val activeNeon = user!!.neonColor
                val activeGlow = user!!.neonGlowIntensity
                WesalNameWithBadge(
                    name = name.ifBlank { "@$publicId" },
                    isVerified = user?.isVerified == true,
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold,
                    verificationType = user!!.verificationType,
                    neonColor = activeNeon,
                    neonGlowIntensity = activeGlow,
                    glowColor = WesalFrameLibrary.resolve(user?.frameId, user?.frameExpiresAt ?: 0L)?.glow
                )
                val immutableNumericId = user?.numericId.orEmpty()
                if (immutableNumericId.isNotBlank()) {
                    Surface(
                        color = palette.surfaceElevated,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, palette.turquoise.copy(alpha = 0.45f)),
                        modifier = Modifier.clickable {
                            clipboard.setText(AnnotatedString(immutableNumericId))
                            Toast.makeText(context, "${strings.wesalNumericIdCopied} $immutableNumericId", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "${strings.wesalNumericIdLabel}: $immutableNumericId",
                                style = wesalNeonTextStyle(MaterialTheme.typography.labelLarge, activeNeon, activeGlow, palette.turquoise),
                                fontWeight = FontWeight.Bold
                            )
                            Icon(Icons.Default.ContentCopy, contentDescription = strings.copy, tint = palette.turquoise, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                // Frame / Neon / Seal active summary badge if any customization is granted
                val activeFrame = WesalFrameLibrary.resolve(user?.frameId, user?.frameExpiresAt ?: 0L)
                val activeFrameTitleAr = activeFrame?.id?.let { WesalFrameLibrary.docs[it] }?.titleAr?.takeIf { it.isNotBlank() }
                    ?: WesalFrameCatalog.allFrames.firstOrNull { it.id == activeFrame?.id }?.titleAr ?: activeFrame?.id.orEmpty()
                val activeNeonPreset = NeonColorPreset.fromKey(user?.neonColor)
                if (user?.isVerified == true || activeFrame != null || activeNeonPreset != NeonColorPreset.NONE) {
                    Surface(
                        color = palette.surface,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, palette.border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (user?.isVerified == true) {
                                val expLabel = if ((user?.verificationExpiresAt ?: 0L) > 0L) {
                                    DateTimeUtils.formatChatListTime(user!!.verificationExpiresAt)
                                } else {
                                    if (strings.isEnglish) "Permanent" else "دائم"
                                }
                                Text(
                                    text = if (strings.isEnglish) "Verified (${user!!.verificationType}) • $expLabel" else "التوثيق: شارة ${user!!.verificationType} • $expLabel",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = palette.emerald
                                )
                            }
                            if (activeFrame != null) {
                                Text(
                                    text = if (strings.isEnglish) "Active Frame: $activeFrameTitleAr" else "الإطار المفعّل: $activeFrameTitleAr",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = palette.turquoise
                                )
                            }
                            if (activeNeonPreset != NeonColorPreset.NONE) {
                                Text(
                                    text = if (strings.isEnglish) "Neon Text: ${activeNeonPreset.labelEn}" else "لون النيون: ${activeNeonPreset.labelAr}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = palette.textSecondary
                                )
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = !uiStatus.isSaving,
                    onClick = {
                        activeCropKind = PendingCropKind.AVATAR
                        showSourceSheetFor = PendingCropKind.AVATAR
                    }
                ) {
                    Icon(Icons.Default.AddAPhoto, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (strings.isEnglish) "Change Avatar" else "تغيير الصورة", color = palette.turquoise)
                }

                if (selectedAvatarUri != null || !user?.photoUrl.isNullOrBlank()) {
                    OutlinedButton(enabled = !uiStatus.isSaving, onClick = { showDeleteAvatarConfirm = true }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = palette.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (strings.isEnglish) "Remove" else "حذف الصورة", color = palette.error)
                    }
                }
            }

            OutlinedButton(
                onClick = onOpenAccountShare,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, palette.turquoise.copy(alpha = 0.55f))
            ) {
                Icon(Icons.Default.QrCode2, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(strings.shareAccountQr, color = palette.turquoise, fontWeight = FontWeight.Bold)
            }

            // Basic Profile Fields
            OutlinedTextField(
                colors = inputColors,
                value = name,
                onValueChange = { name = it },
                label = { Text(if (strings.isEnglish) "Display Name" else "الاسم الظاهر") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    colors = inputColors,
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
                colors = inputColors,
                value = bio,
                onValueChange = { bio = it },
                label = { Text(if (strings.isEnglish) "Bio" else "الوصف الشخصي") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
                shape = RoundedCornerShape(12.dp)
            )

            // Birth Date + Independent Hide Birth Date Switch
            Surface(
                color = palette.surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, palette.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { openBirthDatePicker() },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.Cake, contentDescription = null, tint = palette.turquoise)
                            Column {
                                Text(if (strings.isEnglish) "Birth Date" else "تاريخ الميلاد", style = MaterialTheme.typography.labelMedium, color = palette.textSecondary)
                                Text(
                                    text = birthDateIso.ifBlank { if (strings.isEnglish) "Tap to select" else "اضغط لتحديد التاريخ" },
                                    color = palette.textPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = palette.turquoise)
                    }
                    HorizontalDivider(color = palette.border)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (strings.isEnglish) "Hide birth date from others" else "إخفاء تاريخ الميلاد عن المستخدمين الآخرين",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = hideBirthDate, onCheckedChange = { hideBirthDate = it })
                    }
                }
            }

            // Facebook & Instagram Social Links (Sec 28)
            Surface(
                color = palette.surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, palette.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (strings.isEnglish) "Social Links" else "روابط التواصل الاجتماعي",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        OutlinedTextField(
                            colors = inputColors,
                            value = facebookUrl,
                            onValueChange = { facebookUrl = it },
                            label = { Text("Facebook URL") },
                            placeholder = { Text("https://facebook.com/username") },
                            trailingIcon = {
                                if (facebookUrl.isNotBlank()) {
                                    Row {
                                        IconButton(onClick = { openExternalUrl(facebookUrl) }) {
                                            Icon(Icons.Default.OpenInNew, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(18.dp))
                                        }
                                        IconButton(onClick = { facebookUrl = "" }) {
                                            Icon(Icons.Default.Close, contentDescription = null, tint = palette.error, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        OutlinedTextField(
                            colors = inputColors,
                            value = instagramUrl,
                            onValueChange = { instagramUrl = it },
                            label = { Text("Instagram URL") },
                            placeholder = { Text("https://instagram.com/username") },
                            trailingIcon = {
                                if (instagramUrl.isNotBlank()) {
                                    Row {
                                        IconButton(onClick = { openExternalUrl(instagramUrl) }) {
                                            Icon(Icons.Default.OpenInNew, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(18.dp))
                                        }
                                        IconButton(onClick = { instagramUrl = "" }) {
                                            Icon(Icons.Default.Close, contentDescription = null, tint = palette.error, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
            }

            // Business Hours Editor Summary Card (Sec 29)
            Surface(
                color = palette.surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, palette.border),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showBusinessHoursEditor = true }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Schedule, contentDescription = null, tint = palette.turquoise)
                        Column {
                            Text(
                                text = if (strings.isEnglish) "Business Hours" else "ساعات العمل التجارية",
                                color = palette.textPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (businessHours.enabled) {
                                    val label = businessHours.businessName.ifBlank { if (strings.isEnglish) "Active Schedule" else "جدول ساعات العمل مفعل" }
                                    "$label • ${businessHours.timeZoneId}"
                                } else {
                                    if (strings.isEnglish) "Disabled (Tap to configure)" else "غير مفعل (اضغط لتحديد المواعيد)"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (businessHours.enabled) palette.emerald else palette.textSecondary
                            )
                        }
                    }
                    Icon(Icons.Default.Edit, contentDescription = strings.edit, tint = palette.turquoise)
                }
            }

            Button(
                enabled = !uiStatus.isSaving,
                onClick = {
                    viewModel.saveFullProfile(
                        displayName = name,
                        bio = bio,
                        publicId = publicId,
                        photoUri = selectedAvatarUri,
                        coverUri = selectedCoverUri,
                        birthDate = birthDateIso,
                        hideBirthDate = hideBirthDate,
                        facebookUrl = facebookUrl,
                        instagramUrl = instagramUrl,
                        businessHours = businessHours,
                        isEn = strings.isEnglish,
                        navigateBackAfterSave = true
                    )
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

            // Profile Visitors Section (Sec 23)
            user?.let { current ->
                Surface(
                    color = palette.surface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, palette.border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Visibility, contentDescription = null, tint = palette.turquoise)
                                Text(
                                    text = if (strings.isEnglish) "Who Visited My Profile" else "من زار حسابي",
                                    color = palette.textPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (current.isVerified) {
                                TextButton(onClick = { showVisitorsSheet = true }) {
                                    Text(
                                        text = if (strings.isEnglish) "View (${visitors.size})" else "عرض (${visitors.size})",
                                        color = palette.turquoise,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            } else {
                                Surface(color = palette.surfaceElevated, shape = RoundedCornerShape(8.dp)) {
                                    Text(
                                        text = if (strings.isEnglish) "${visitors.size} visits" else "${visitors.size} زيارة",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = palette.turquoise,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                        if (!current.isVerified) {
                            Text(
                                text = if (strings.isEnglish) {
                                    "Detailed visitor names are available only to verified WESAL accounts."
                                } else {
                                    "تظهر أسماء وحسابات الزوار بالتفصيل للحسابات الموثقة فقط؛ ويظهر هنا إجمالي عدد الزيارات."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textSecondary
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (strings.isEnglish) "Allow recording my profile visits" else "تفعيل تسجيل زيارات الحساب",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = current.privacySettings.recordProfileVisits,
                                onCheckedChange = { enabled ->
                                    viewModel.updatePrivacy(current.privacySettings.copy(recordProfileVisits = enabled))
                                }
                            )
                        }
                    }
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
                            Text(if (strings.isEnglish) "Email (Private)" else "البريد الإلكتروني (خاص بك)", color = palette.textSecondary)
                        }
                        Text(user!!.email, color = palette.textPrimary, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // International Phone Section (Sec 9: Country + Local Box, NO "رقم موثوق" icon/badge, NO OTP)
            Surface(
                color = palette.surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, palette.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Phone, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(18.dp))
                        Text(strings.phoneNumberLabel, style = MaterialTheme.typography.titleMedium, color = palette.textPrimary, fontWeight = FontWeight.Bold)
                    }

                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { showCountryPicker = true },
                                modifier = Modifier.height(54.dp),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, palette.turquoise.copy(alpha = 0.55f))
                            ) {
                                Text("${selectedCountry.flagEmoji} ${selectedCountry.dialCode}", color = palette.textPrimary, fontWeight = FontWeight.Bold)
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = palette.turquoise)
                            }
                            OutlinedTextField(
                                colors = inputColors,
                                value = localPhone,
                                onValueChange = { raw -> localPhone = raw.filter { it.isDigit() } },
                                label = { Text(if (strings.isEnglish) "Local Number" else "الرقم المحلي") },
                                placeholder = { Text(if (selectedCountry.iso == "EG") "01012345678" else "5xxxxxxxx") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !uiStatus.isSaving && localPhone.isNotBlank(),
                            onClick = { viewModel.updateCountryPhone(selectedCountry, localPhone, strings.isEnglish) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.turquoise, contentColor = palette.onAccent)
                        ) {
                            Text(strings.verifyPhoneNow, fontWeight = FontWeight.Bold)
                        }
                        if (!user?.phoneNumber.isNullOrBlank()) {
                            OutlinedButton(
                                enabled = !uiStatus.isSaving,
                                onClick = {
                                    localPhone = ""
                                    viewModel.removeOptionalPhone(strings.isEnglish)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(strings.delete, color = palette.error)
                            }
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
                                onCheckedChange = { hide -> viewModel.updatePrivacy(ps.copy(hidePhoneNumber = hide)) }
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

    pendingPreviewUri?.let { previewUri ->
        AvatarPreviewConfirmDialog(
            imageUri = previewUri,
            isCircular = activeCropKind == PendingCropKind.AVATAR,
            onConfirm = {
                if (activeCropKind == PendingCropKind.COVER) {
                    selectedCoverUri = previewUri
                    pendingPreviewUri = null
                    viewModel.saveImages(photoUri = null, coverUri = previewUri, isEn = strings.isEnglish)
                } else {
                    selectedAvatarUri = previewUri
                    pendingPreviewUri = null
                    viewModel.saveImages(photoUri = previewUri, coverUri = null, isEn = strings.isEnglish)
                }
            },
            onRecrop = {
                pendingPreviewUri = null
                pendingRawUri?.let { raw -> launchCropFor(activeCropKind, raw) }
            },
            onCancel = { pendingPreviewUri = null }
        )
    }

    if (showCountryPicker) {
        var searchQuery by remember { mutableStateOf("") }
        val filtered = remember(searchQuery) { CountryCatalog.search(searchQuery) }
        AlertDialog(
            onDismissRequest = { showCountryPicker = false },
            containerColor = palette.surface,
            title = { Text(if (strings.isEnglish) "Select Country" else "اختر الدولة", color = palette.textPrimary) },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        colors = inputColors,
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(if (strings.isEnglish) "Country or +code" else "اسم الدولة أو +رمز") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyColumn {
                        items(filtered, key = { it.iso + it.dialCode }) { country ->
                            ListItem(
                                headlineContent = { Text("${country.flagEmoji} ${country.nameAr} (${country.nameEn})", color = palette.textPrimary) },
                                trailingContent = { Text(country.dialCode, color = palette.turquoise, fontWeight = FontWeight.Bold) },
                                modifier = Modifier.clickable {
                                    selectedCountry = country
                                    showCountryPicker = false
                                },
                                colors = ListItemDefaults.colors(containerColor = palette.surface)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCountryPicker = false }) { Text(strings.close, color = palette.turquoise) }
            }
        )
    }

    if (showBusinessHoursEditor) {
        BusinessHoursEditorDialog(
            initial = businessHours,
            inputColors = inputColors,
            isEn = strings.isEnglish,
            onDismiss = { showBusinessHoursEditor = false },
            onSave = { updated ->
                businessHours = updated
                showBusinessHoursEditor = false
            }
        )
    }

    if (showVisitorsSheet) {
        AlertDialog(
            onDismissRequest = { showVisitorsSheet = false },
            containerColor = palette.surface,
            title = {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (strings.isEnglish) "Profile Visitors" else "من زار حسابي", color = palette.textPrimary, fontWeight = FontWeight.Bold)
                    if (visitors.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearVisitors(strings.isEnglish) }) {
                            Text(strings.clear, color = palette.error)
                        }
                    }
                }
            },
            text = {
                if (visitors.isEmpty()) {
                    Text(if (strings.isEnglish) "No visits recorded yet." else "لا توجد زيارات مسجلة حتى الآن.", color = palette.textSecondary)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(visitors, key = { it.visitorUid + it.visitedAt }) { v ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        showVisitorsSheet = false
                                        onOpenUserProfile(v.visitorUid)
                                    }
                                    .padding(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                WesalAvatar(imageUrl = v.visitorAvatarUrl, name = v.visitorName, size = 40.dp)
                                Column(modifier = Modifier.weight(1f)) {
                                    WesalNameWithBadge(
                                        name = v.visitorName.ifBlank { "@${v.visitorPublicId}" },
                                        isVerified = v.visitorVerified,
                                        verificationType = v.visitorVerificationType,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = DateTimeUtils.formatChatListTime(v.visitedAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.textSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showVisitorsSheet = false }) { Text(strings.close) } }
        )
    }

    if (showDeleteAvatarConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAvatarConfirm = false },
            containerColor = palette.surface,
            title = { Text(if (strings.isEnglish) "Remove Photo" else "حذف الصورة الشخصية", color = palette.textPrimary) },
            text = { Text(if (strings.isEnglish) "Revert to default monogram avatar?" else "هل تريد حذف صورتك الشخصية الحالية؟", color = palette.textSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteAvatarConfirm = false
                        selectedAvatarUri = null
                        viewModel.removePhoto(strings.isEnglish)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.error)
                ) { Text(strings.delete) }
            },
            dismissButton = { TextButton(onClick = { showDeleteAvatarConfirm = false }) { Text(strings.cancel) } }
        )
    }

    if (showDeleteCoverConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteCoverConfirm = false },
            containerColor = palette.surface,
            title = { Text(if (strings.isEnglish) "Remove Cover" else "حذف صورة الغلاف", color = palette.textPrimary) },
            text = { Text(if (strings.isEnglish) "Remove your profile cover banner?" else "هل تريد حذف صورة الغلاف الحالية؟", color = palette.textSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteCoverConfirm = false
                        selectedCoverUri = null
                        viewModel.removeCover(strings.isEnglish)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.error)
                ) { Text(strings.delete) }
            },
            dismissButton = { TextButton(onClick = { showDeleteCoverConfirm = false }) { Text(strings.cancel) } }
        )
    }

    showSourceSheetFor?.let { kind ->
        AvatarSourceBottomSheet(
            title = if (kind == PendingCropKind.COVER) {
                if (strings.isEnglish) "Choose Cover Photo" else "اختيار صورة الغلاف"
            } else null,
            onPickGallery = {
                activeCropKind = kind
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onTakePhoto = { launchCameraFlow(kind) },
            onDismiss = { showSourceSheetFor = null }
        )
    }
}

@Composable
private fun BusinessHoursEditorDialog(
    initial: BusinessHoursSchedule,
    inputColors: TextFieldColors,
    isEn: Boolean,
    onDismiss: () -> Unit,
    onSave: (BusinessHoursSchedule) -> Unit
) {
    val palette = WesalColors.current
    var enabled by remember { mutableStateOf(initial.enabled) }
    var businessName by remember { mutableStateOf(initial.businessName) }
    var timeZoneId by remember { mutableStateOf(initial.timeZoneId.ifBlank { java.util.TimeZone.getDefault().id }) }
    var days by remember {
        mutableStateOf(
            if (initial.days.isEmpty()) BusinessHoursSchedule().days else initial.days
        )
    }

    val dayLabels = remember(isEn) {
        mapOf(
            "SAT" to if (isEn) "Saturday" else "السبت",
            "SUN" to if (isEn) "Sunday" else "الأحد",
            "MON" to if (isEn) "Monday" else "الاثنين",
            "TUE" to if (isEn) "Tuesday" else "الثلاثاء",
            "WED" to if (isEn) "Wednesday" else "الأربعاء",
            "THU" to if (isEn) "Thursday" else "الخميس",
            "FRI" to if (isEn) "Friday" else "الجمعة"
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = {
            Text(
                if (isEn) "Business Hours Schedule" else "جدول ساعات العمل",
                color = palette.textPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (isEn) "Enable Business Hours" else "تفعيل عرض ساعات العمل", color = palette.textPrimary)
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                    OutlinedTextField(
                        colors = inputColors,
                        value = businessName,
                        onValueChange = { businessName = it },
                        label = { Text(if (isEn) "Business / Store Name" else "اسم النشاط التجاري (اختياري)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        colors = inputColors,
                        value = timeZoneId,
                        onValueChange = { timeZoneId = it },
                        label = { Text(if (isEn) "Time Zone ID" else "المنطقة الزمنية") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                items(days.size) { index ->
                    val d = days[index]
                    Surface(
                        color = palette.background,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, palette.border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(dayLabels[d.dayKey] ?: d.dayKey, color = palette.textPrimary, fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = d.isClosed,
                                        onClick = {
                                            days = days.toMutableList().also {
                                                it[index] = d.copy(isClosed = !d.isClosed, isOpen24Hours = false)
                                            }
                                        },
                                        label = { Text(if (isEn) "Closed" else "مغلق") }
                                    )
                                    FilterChip(
                                        selected = d.isOpen24Hours,
                                        onClick = {
                                            days = days.toMutableList().also {
                                                it[index] = d.copy(isOpen24Hours = !d.isOpen24Hours, isClosed = false)
                                            }
                                        },
                                        label = { Text("24h") }
                                    )
                                }
                            }
                            if (!d.isClosed && !d.isOpen24Hours) {
                                val slot = d.slots.firstOrNull() ?: BusinessDaySlot()
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        colors = inputColors,
                                        value = slot.openTime,
                                        onValueChange = { v ->
                                            days = days.toMutableList().also {
                                                it[index] = d.copy(slots = listOf(slot.copy(openTime = v)))
                                            }
                                        },
                                        label = { Text(if (isEn) "From" else "من") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        colors = inputColors,
                                        value = slot.closeTime,
                                        onValueChange = { v ->
                                            days = days.toMutableList().also {
                                                it[index] = d.copy(slots = listOf(slot.copy(closeTime = v)))
                                            }
                                        },
                                        label = { Text(if (isEn) "To" else "إلى") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        BusinessHoursSchedule(
                            enabled = enabled,
                            businessName = businessName.trim(),
                            timeZoneId = timeZoneId.trim().ifBlank { java.util.TimeZone.getDefault().id },
                            days = days
                        )
                    )
                }
            ) {
                Text(if (isEn) "Apply" else "حفظ الجدول")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (isEn) "Cancel" else "إلغاء") }
        }
    )
}
