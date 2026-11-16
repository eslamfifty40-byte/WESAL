package com.wesal.messenger.authentication

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.core.util.LocationVerificationHelper
import com.wesal.messenger.core.util.PhoneUtils
import com.wesal.messenger.core.util.WesalIdUtils
import com.wesal.messenger.crop.CropActivity
import com.wesal.messenger.domain.model.CountryCode
import com.wesal.messenger.media.AvatarCropHelper
import com.wesal.messenger.media.AvatarPreviewConfirmDialog
import com.wesal.messenger.media.AvatarSourceBottomSheet
import java.time.LocalDate
import java.util.Calendar
import java.util.Locale

@Composable
fun ProfileSetupScreen(
    viewModel: AuthViewModel,
    onNavigateToHome: () -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var displayName by remember { mutableStateOf(viewModel.defaultDisplayName()) }
    var wesalId by remember { mutableStateOf(viewModel.defaultWesalIdSuggestion()) }
    var bio by remember {
        mutableStateOf(if (strings.isEnglish) "Using WESAL Messenger" else "أستخدم تطبيق WESAL")
    }
    var selectedCountry by remember { mutableStateOf(CountryCatalog.defaultEgypt) }
    var localPhone by remember { mutableStateOf("") }
    var birthDateIso by remember { mutableStateOf("") }
    var hideBirthDate by remember { mutableStateOf(false) }

    var locationGranted by remember {
        mutableStateOf(LocationVerificationHelper.hasLocationPermission(context))
    }
    var locationGraceStartedAt by remember { mutableLongStateOf(0L) }
    var showCountryPicker by remember { mutableStateOf(false) }

    var selectedPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var pendingRawUri by remember { mutableStateOf<Uri?>(null) }
    var pendingPreviewUri by remember { mutableStateOf<Uri?>(null) }
    var cameraTempUri by remember { mutableStateOf<Uri?>(null) }
    var showSourceSheet by remember { mutableStateOf(false) }

    val cropLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            AvatarCropHelper.getCropOutput(result.data)?.let { cropped ->
                pendingPreviewUri = cropped
            }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            pendingRawUri = uri
            cropLauncher.launch(
                AvatarCropHelper.createCropIntent(
                    context = context,
                    sourceUri = uri,
                    mode = CropActivity.MODE_CIRCLE_AVATAR
                )
            )
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val captured = cameraTempUri
        if (success && captured != null) {
            pendingRawUri = captured
            cropLauncher.launch(
                AvatarCropHelper.createCropIntent(
                    context = context,
                    sourceUri = captured,
                    mode = CropActivity.MODE_CIRCLE_AVATAR
                )
            )
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
            Toast.makeText(
                context,
                if (strings.isEnglish) "Grant camera permission to take a photo" else "يرجى منح صلاحية الكاميرا لالتقاط صورة",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        locationGranted = granted
        if (!granted && locationGraceStartedAt == 0L) {
            locationGraceStartedAt = System.currentTimeMillis()
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

    LaunchedEffect(state.navigateToHome) {
        if (state.navigateToHome) onNavigateToHome()
    }

    val phoneValidation = remember(selectedCountry, localPhone) {
        PhoneUtils.validateForCountry(selectedCountry, localPhone)
    }
    val wesalIdValid = WesalIdUtils.isValid(WesalIdUtils.normalize(wesalId))
    val dobValid = birthDateIso.isNotBlank()
    val nameValid = displayName.trim().length >= 2
    val canSubmit = !state.isLoading && nameValid && wesalIdValid && phoneValidation.isValid && dobValid

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = palette.turquoise,
        unfocusedBorderColor = palette.border,
        focusedTextColor = palette.textPrimary,
        unfocusedTextColor = palette.textPrimary,
        focusedLabelColor = palette.turquoise,
        unfocusedLabelColor = palette.textSecondary,
        focusedContainerColor = palette.surface,
        unfocusedContainerColor = palette.surface
    )

    Scaffold(containerColor = palette.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (strings.isEnglish) "Complete Your WESAL Account" else "استكمال بيانات حساب WESAL",
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (strings.isEnglish) {
                        "Fill in all mandatory fields below (Name, Username, Phone Number & Birth Date) before starting."
                    } else {
                        "أكمل جميع الخانات الإلزامية أدناه (الاسم، Username، رقم الهاتف الدولي، وتاريخ الميلاد) للمتابعة"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )

                // Circular Avatar + Crop + BG removal
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(116.dp)
                            .clip(CircleShape)
                            .background(palette.surfaceElevated)
                            .border(2.dp, palette.turquoise, CircleShape)
                            .clickable { showSourceSheet = true },
                        contentAlignment = Alignment.Center
                    ) {
                        if (selectedPhotoUri != null) {
                            AsyncImage(
                                model = selectedPhotoUri,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Person, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(44.dp))
                                Icon(Icons.Default.AddAPhoto, contentDescription = null, tint = palette.turquoise, modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    Text(
                        text = if (strings.isEnglish) "Tap to choose, crop or remove background" else "اضغط للاختيار والقص وإزالة الخلفية",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.turquoise
                    )

                    if (selectedPhotoUri != null) {
                        TextButton(onClick = { selectedPhotoUri = null }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = palette.error, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (strings.isEnglish) "Remove Photo" else "إزالة الصورة", color = palette.error)
                        }
                    }
                }

                // 1. Display Name (Mandatory)
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(if (strings.isEnglish) "Display Name *" else "الاسم الظاهر *") },
                    placeholder = { Text(if (strings.isEnglish) "e.g., Ahmed Mohamed" else "مثال: أحمد محمد") },
                    isError = displayName.isNotEmpty() && !nameValid,
                    supportingText = if (!nameValid) {
                        { Text("مطلوب حرفان على الأقل", color = palette.error) }
                    } else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors
                )

                // 2. @Wesal ID (Username, Mandatory)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = wesalId,
                        onValueChange = { raw ->
                            wesalId = raw.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }
                        },
                        label = { Text(if (strings.isEnglish) "@Username (Wesal ID) *" else "معرف وصال (@Username) *") },
                        placeholder = { Text("ahmed_mohamed") },
                        prefix = {
                            Text("@", color = palette.turquoise, fontWeight = FontWeight.Bold)
                        },
                        isError = wesalId.isNotEmpty() && !wesalIdValid,
                        supportingText = {
                            Text(
                                text = if (wesalIdValid) {
                                    if (strings.isEnglish) "4–30 letters, digits, or (_)" else "4–30 حرفاً إنجليزياً أو أرقاماً أو (_)"
                                } else {
                                    "يجب إدخال 4 أحرف إنجليزية أو أرقام أو (_) على الأقل"
                                },
                                color = if (wesalIdValid) palette.textSecondary else palette.error
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = fieldColors
                    )
                }

                // 3. International Phone (All countries, Country dial code box + Local number box, NO "رقم موثوق" icon)
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = if (strings.isEnglish) "Phone Number (All Countries Supported) *" else "رقم الهاتف الدولي (يدعم جميع الدول) *",
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.textPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            OutlinedButton(
                                onClick = { showCountryPicker = true },
                                modifier = Modifier.height(56.dp),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, palette.turquoise.copy(alpha = 0.6f)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = palette.surface,
                                    contentColor = palette.textPrimary
                                )
                            ) {
                                Text("${selectedCountry.flagEmoji} ${selectedCountry.dialCode}", fontWeight = FontWeight.Bold)
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = palette.turquoise)
                            }
                            OutlinedTextField(
                                value = localPhone,
                                onValueChange = { raw -> localPhone = raw.filter { it.isDigit() } },
                                label = { Text(if (strings.isEnglish) "Local Number *" else "الرقم المحلي *") },
                                placeholder = {
                                    Text(if (selectedCountry.dialCode == "+20") "010xxxxxxxx" else "5xxxxxxxx")
                                },
                                leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null, tint = palette.turquoise) },
                                isError = localPhone.isNotEmpty() && !phoneValidation.isValid,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                colors = fieldColors
                            )
                        }
                    }
                    if (!phoneValidation.isValid) {
                        Text(
                            text = if (localPhone.isBlank()) {
                                "رقم الهاتف مطلوب لإتمام الحساب (${selectedCountry.nameAr} ${selectedCountry.dialCode})"
                            } else {
                                phoneValidation.errorAr.ifBlank { "تنسيق الرقم غير صحيح" }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (localPhone.isBlank()) palette.textSecondary else palette.error
                        )
                    }
                }

                // 4. Birth Date (Mandatory calendar picker, non-future, >=13 years, independent hide toggle)
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        color = palette.surface,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, if (dobValid) palette.turquoise.copy(alpha = 0.6f) else palette.border),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { openBirthDatePicker() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Default.Cake, contentDescription = null, tint = palette.turquoise)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (strings.isEnglish) "Birth Date *" else "تاريخ الميلاد *",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = palette.textSecondary
                                )
                                Text(
                                    text = birthDateIso.ifBlank {
                                        if (strings.isEnglish) "Tap to select from calendar" else "اضغط لاختيار اليوم والشهر والسنة"
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (dobValid) palette.textPrimary else palette.turquoise,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (strings.isEnglish) "Hide birth date from others" else "إخفاء تاريخ الميلاد عن المستخدمين الآخرين",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary
                        )
                        Switch(
                            checked = hideBirthDate,
                            onCheckedChange = { hideBirthDate = it }
                        )
                    }
                }

                // 5. Location Verification / 48-hour grace period (no user forced out immediately)
                Surface(
                    color = palette.surfaceElevated,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, if (locationGranted) palette.emerald.copy(alpha = 0.6f) else palette.border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (locationGranted) Icons.Default.CheckCircle else Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = if (locationGranted) palette.emerald else palette.turquoise
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (locationGranted) {
                                    "تم تفعيل الموقع الجغرافي ✓"
                                } else {
                                    "تأكيد الموقع الجغرافي (مهلة ٤٨ ساعة)"
                                },
                                color = palette.textPrimary,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                text = if (locationGranted) {
                                    "تم تأكيد إذن الموقع لحسابك."
                                } else {
                                    "يمكنك المتابعة الآن مع مهلة 48 ساعة لتفعيل إذن الموقع."
                                },
                                color = palette.textSecondary,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        if (!locationGranted) {
                            TextButton(
                                onClick = {
                                    locationPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                }
                            ) {
                                Text("تفعيل", color = palette.turquoise, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // 6. Bio
                OutlinedTextField(
                    value = bio,
                    onValueChange = { bio = it },
                    label = { Text(if (strings.isEnglish) "Bio" else "الوصف الشخصي") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors
                )

                if (state.errorMessage != null) {
                    Surface(
                        color = palette.error.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, palette.error.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            state.errorMessage!!,
                            color = palette.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            Button(
                onClick = {
                    val graceStart = if (!locationGranted && locationGraceStartedAt == 0L) {
                        System.currentTimeMillis().also { locationGraceStartedAt = it }
                    } else {
                        locationGraceStartedAt
                    }
                    viewModel.completeProfile(
                        displayName = displayName,
                        bio = bio,
                        photoUri = selectedPhotoUri,
                        wesalId = wesalId,
                        country = selectedCountry,
                        localPhone = localPhone,
                        birthDateIso = birthDateIso,
                        hideBirthDate = hideBirthDate,
                        locationGranted = locationGranted,
                        locationGraceStartedAt = graceStart
                    )
                },
                enabled = canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.emerald,
                    contentColor = palette.onAccent,
                    disabledContainerColor = palette.surfaceElevated,
                    disabledContentColor = palette.textMuted
                )
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = palette.onAccent)
                } else {
                    Text(
                        if (strings.isEnglish) "Save & Start Using WESAL" else "حفظ وبدء استخدام WESAL",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    if (showCountryPicker) {
        var searchQuery by remember { mutableStateOf("") }
        val filteredCountries = remember(searchQuery) { CountryCatalog.search(searchQuery) }
        AlertDialog(
            onDismissRequest = { showCountryPicker = false },
            containerColor = palette.surface,
            title = {
                Text(
                    if (strings.isEnglish) "Select Country" else "اختر الدولة",
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(if (strings.isEnglish) "Country name or +code" else "ابحث بالاسم أو +رمز الدولة") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = palette.turquoise) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors
                    )
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(filteredCountries, key = { it.iso + it.dialCode }) { country ->
                            ListItem(
                                headlineContent = {
                                    Text("${country.flagEmoji} ${country.nameAr} (${country.nameEn})", color = palette.textPrimary)
                                },
                                trailingContent = {
                                    Text(country.dialCode, color = palette.turquoise, fontWeight = FontWeight.Bold)
                                },
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
                TextButton(onClick = { showCountryPicker = false }) {
                    Text(strings.close, color = palette.turquoise)
                }
            }
        )
    }

    pendingPreviewUri?.let { previewUri ->
        AvatarPreviewConfirmDialog(
            imageUri = previewUri,
            isCircular = true,
            onConfirm = {
                selectedPhotoUri = previewUri
                pendingPreviewUri = null
            },
            onRecrop = {
                pendingPreviewUri = null
                pendingRawUri?.let { raw ->
                    cropLauncher.launch(
                        AvatarCropHelper.createCropIntent(
                            context = context,
                            sourceUri = raw,
                            mode = CropActivity.MODE_CIRCLE_AVATAR
                        )
                    )
                }
            },
            onCancel = { pendingPreviewUri = null }
        )
    }

    if (showSourceSheet) {
        AvatarSourceBottomSheet(
            onPickGallery = {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onTakePhoto = { launchCameraFlow() },
            onDismiss = { showSourceSheet = false }
        )
    }
}
