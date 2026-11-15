package com.wesal.messenger.authentication

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.media.AvatarCropHelper
import com.wesal.messenger.media.AvatarPreviewConfirmDialog
import com.wesal.messenger.media.AvatarSourceBottomSheet
import com.yalantis.ucrop.UCrop

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
    var wesalId by remember { mutableStateOf("") }
    var bio by remember {
        mutableStateOf(if (strings.isEnglish) "Using WESAL Messenger" else "أستخدم تطبيق WESAL")
    }
    var selectedPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var pendingRawUri by remember { mutableStateOf<Uri?>(null) }
    var pendingPreviewUri by remember { mutableStateOf<Uri?>(null) }
    var cameraTempUri by remember { mutableStateOf<Uri?>(null) }
    var showSourceSheet by remember { mutableStateOf(false) }

    val cropLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            UCrop.getOutput(result.data!!)?.let { cropped ->
                pendingPreviewUri = cropped
            }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
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
            Toast.makeText(
                context,
                if (strings.isEnglish) "Grant camera permission to take a photo" else "يرجى منح صلاحية الكاميرا لالتقاط صورة",
                Toast.LENGTH_SHORT
            ).show()
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

    LaunchedEffect(state.navigateToHome) {
        if (state.navigateToHome) onNavigateToHome()
    }

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
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (strings.isEnglish) "Set Up Your Profile" else "إعداد ملفك الشخصي",
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (strings.isEnglish) {
                        "Add your name and unique @Wesal ID so friends can find you privately without a phone number."
                    } else {
                        "أضف اسمك ومعرف وصال الفريد (@Wesal ID) ليتمكن أصدقاؤك من إضافتك بدون رقم هاتف"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
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

                    if (selectedPhotoUri != null) {
                        TextButton(onClick = { selectedPhotoUri = null }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = palette.error, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (strings.isEnglish) "Remove Photo" else "إزالة الصورة", color = palette.error)
                        }
                    }
                }

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(if (strings.isEnglish) "Display Name" else "الاسم الظاهر") },
                    placeholder = { Text(if (strings.isEnglish) "e.g., Ahmed Mohamed" else "مثال: أحمد محمد") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors
                )

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = wesalId,
                        onValueChange = { raw ->
                            wesalId = raw.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }
                        },
                        label = { Text(if (strings.isEnglish) "Unique @Wesal ID (optional)" else "معرف وصال الفريد (@Wesal ID) — اختياري") },
                        placeholder = { Text("ahmed_mohamed") },
                        prefix = {
                            Text(
                                text = "@",
                                color = palette.turquoise,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        supportingText = {
                            Text(
                                text = if (strings.isEnglish) {
                                    "4–30 English letters, digits, or (_). Leave empty to auto-generate one."
                                } else {
                                    "4–30 حرفاً إنجليزياً أو أرقاماً أو (_). إن تركته فارغاً سننشئ لك معرفاً تلقائياً."
                                },
                                color = palette.textSecondary
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = fieldColors
                    )
                }

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
                    Text(state.errorMessage!!, color = palette.error, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = {
                    viewModel.completeProfile(
                        displayName = displayName,
                        bio = bio,
                        photoUri = selectedPhotoUri,
                        wesalId = wesalId.takeIf { it.isNotBlank() }
                    )
                },
                enabled = !state.isLoading && displayName.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.emerald,
                    contentColor = palette.onAccent
                )
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = palette.onAccent)
                } else {
                    Text(
                        if (strings.isEnglish) "Start Using WESAL" else "بدء استخدام WESAL",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    pendingPreviewUri?.let { previewUri ->
        AvatarPreviewConfirmDialog(
            imageUri = previewUri,
            onConfirm = {
                selectedPhotoUri = previewUri
                pendingPreviewUri = null
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
