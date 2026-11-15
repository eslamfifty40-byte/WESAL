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
import com.wesal.messenger.core.theme.*
import com.wesal.messenger.media.AvatarCropHelper
import com.wesal.messenger.media.AvatarPreviewConfirmDialog
import com.wesal.messenger.media.AvatarSourceBottomSheet
import com.yalantis.ucrop.UCrop

@Composable
fun ProfileSetupScreen(
    viewModel: AuthViewModel,
    onNavigateToHome: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var displayName by remember { mutableStateOf(viewModel.defaultDisplayName()) }
    var wesalId by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("أستخدم تطبيق وصال") }
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
            Toast.makeText(context, "يرجى منح صلاحية الكاميرا لالتقاط صورة", Toast.LENGTH_SHORT).show()
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

    Scaffold(containerColor = WesalDeepBlack) { padding ->
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
                    text = "إعداد ملفك الشخصي",
                    style = MaterialTheme.typography.headlineSmall,
                    color = WesalTextPrimary
                )
                Text(
                    text = "أضف اسمك ومعرف وصال الفريد (Wesal ID) ليتمكن أصدقاؤك من إضافتك بدون رقم هاتف",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WesalTextSecondary
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(116.dp)
                            .clip(CircleShape)
                            .background(WesalSurfaceElevated)
                            .border(2.dp, WesalTurquoise, CircleShape)
                            .clickable { showSourceSheet = true },
                        contentAlignment = Alignment.Center
                    ) {
                        if (selectedPhotoUri != null) {
                            AsyncImage(
                                model = selectedPhotoUri,
                                contentDescription = "صورة الحساب",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Person, contentDescription = null, tint = WesalTextSecondary, modifier = Modifier.size(44.dp))
                                Icon(Icons.Default.AddAPhoto, contentDescription = null, tint = WesalTurquoise, modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    if (selectedPhotoUri != null) {
                        TextButton(onClick = { selectedPhotoUri = null }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = WesalError, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("إزالة الصورة", color = WesalError)
                        }
                    }
                }

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("الاسم الظاهر") },
                    placeholder = { Text("مثال: أحمد محمد") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = wesalId,
                        onValueChange = { raw ->
                            wesalId = raw.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }
                        },
                        label = { Text("معرف وصال الفريد (Wesal ID) — اختياري") },
                        placeholder = { Text("ahmed_mohamed") },
                        prefix = {
                            Text(
                                text = "@",
                                color = WesalTurquoise,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        supportingText = {
                            Text(
                                text = "4–30 حرفاً إنجليزياً أو أرقاماً أو (_). إن تركته فارغاً سننشئ لك معرفاً تلقائياً.",
                                color = WesalTextSecondary
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )
                }

                OutlinedTextField(
                    value = bio,
                    onValueChange = { bio = it },
                    label = { Text("الوصف الشخصي") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )

                if (state.errorMessage != null) {
                    Text(state.errorMessage!!, color = WesalError, style = MaterialTheme.typography.bodyMedium)
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
                    containerColor = WesalEmerald,
                    contentColor = WesalDeepBlack
                )
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = WesalDeepBlack)
                } else {
                    Text("بدء استخدام وصال", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
