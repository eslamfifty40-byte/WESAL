package com.wesal.messenger.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.crop.CropActivity
import java.io.File

object AvatarCropHelper {

    fun createCropIntent(
        context: Context,
        sourceUri: Uri,
        mode: String = CropActivity.MODE_CIRCLE_AVATAR,
        title: String? = null
    ): Intent {
        return CropActivity.createIntent(context, sourceUri, mode, title)
    }

    fun getCropOutput(resultData: Intent?): Uri? = resultData?.data

    fun createCameraPhotoUri(context: Context): Uri {
        val camDir = File(context.cacheDir, "camera").apply { mkdirs() }
        val photoFile = File(camDir, "avatar_cam_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            photoFile
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarSourceBottomSheet(
    title: String? = null,
    onPickGallery: () -> Unit,
    onTakePhoto: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title ?: if (strings.isEnglish) "Choose Photo" else "اختيار الصورة",
                style = MaterialTheme.typography.titleLarge,
                color = palette.textPrimary,
                fontWeight = FontWeight.Bold
            )
            ListItem(
                headlineContent = {
                    Text(
                        if (strings.isEnglish) "Choose from Gallery" else "اختيار من معرض الصور",
                        color = palette.textPrimary
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        tint = palette.turquoise
                    )
                },
                modifier = Modifier.clickable {
                    onDismiss()
                    onPickGallery()
                },
                colors = ListItemDefaults.colors(containerColor = palette.surface)
            )
            ListItem(
                headlineContent = {
                    Text(
                        if (strings.isEnglish) "Take Photo with Camera" else "التقاط صورة بالكاميرا",
                        color = palette.textPrimary
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = palette.emerald
                    )
                },
                modifier = Modifier.clickable {
                    onDismiss()
                    onTakePhoto()
                },
                colors = ListItemDefaults.colors(containerColor = palette.surface)
            )
        }
    }
}

@Composable
fun AvatarPreviewConfirmDialog(
    imageUri: Uri,
    isCircular: Boolean = true,
    onConfirm: () -> Unit,
    onRecrop: () -> Unit,
    onCancel: () -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = palette.surface,
        title = {
            Text(
                text = if (strings.isEnglish) "Preview Cropped Photo" else "معاينة الصورة المقصوصة",
                color = palette.textPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isCircular) {
                    AsyncImage(
                        model = imageUri,
                        contentDescription = null,
                        modifier = Modifier
                            .size(180.dp)
                            .clip(CircleShape)
                            .border(2.5.dp, palette.turquoise, CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    AsyncImage(
                        model = imageUri,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(14.dp))
                            .border(2.dp, palette.turquoise, RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
                Text(
                    text = if (strings.isEnglish) {
                        if (isCircular) {
                            "Your avatar will appear inside this circle across chats and contacts."
                        } else {
                            "Your cover banner will appear in 16:9 behind your avatar."
                        }
                    } else {
                        if (isCircular) {
                            "ستظهر صورتك بهذا الشكل الدائري لدى جهات الاتصال والمحادثات."
                        } else {
                            "ستظهر صورة الغلاف بهذا الشكل خلف صورتك الشخصية."
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.emerald,
                    contentColor = palette.onAccent
                )
            ) {
                Text(
                    if (strings.isEnglish) "Confirm & Save" else "اعتماد وحفظ",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRecrop) {
                    Text(if (strings.isEnglish) "Re-crop" else "إعادة القص", color = palette.turquoise)
                }
                TextButton(onClick = onCancel) {
                    Text(strings.cancel, color = palette.textSecondary)
                }
            }
        }
    )
}
