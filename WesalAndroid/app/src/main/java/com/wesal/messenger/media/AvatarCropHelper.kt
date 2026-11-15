package com.wesal.messenger.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.wesal.messenger.R
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.LocalWesalStrings
import com.yalantis.ucrop.UCrop
import java.io.File

object AvatarCropHelper {

    fun createCropIntent(context: Context, sourceUri: Uri): Intent {
        val cacheDir = File(context.cacheDir, "media").apply { mkdirs() }
        val destinationFile = File(cacheDir, "avatar_crop_${System.currentTimeMillis()}.jpg")
        val destinationUri = Uri.fromFile(destinationFile)

        val options = UCrop.Options().apply {
            setCircleDimmedLayer(true)
            setShowCropGrid(false)
            setShowCropFrame(false)
            setCompressionFormat(Bitmap.CompressFormat.JPEG)
            setCompressionQuality(90)
            setToolbarTitle("تعديل الصورة الشخصية")
            setToolbarColor(ContextCompat.getColor(context, R.color.wesal_black))
            setStatusBarColor(ContextCompat.getColor(context, R.color.wesal_black))
            setToolbarWidgetColor(0xFFFFFFFF.toInt())
            setActiveControlsWidgetColor(ContextCompat.getColor(context, R.color.wesal_turquoise))
            setRootViewBackgroundColor(0xFF0B1115.toInt())
            setFreeStyleCropEnabled(false)
        }

        return UCrop.of(sourceUri, destinationUri)
            .withAspectRatio(1f, 1f)
            .withMaxResultSize(800, 800)
            .withOptions(options)
            .getIntent(context)
    }

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
                text = if (strings.isEnglish) "Choose Profile Photo" else "اختيار الصورة الشخصية",
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
                text = if (strings.isEnglish) "Preview Profile Photo" else "معاينة الصورة الشخصية",
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
                AsyncImage(
                    model = imageUri,
                    contentDescription = null,
                    modifier = Modifier
                        .size(180.dp)
                        .clip(CircleShape)
                        .border(2.5.dp, palette.turquoise, CircleShape),
                    contentScale = ContentScale.Crop
                )
                Text(
                    text = if (strings.isEnglish) {
                        "Your avatar will appear in a circular frame across contacts and chats."
                    } else {
                        "ستظهر صورتك بهذا الشكل الدائري لدى جهات الاتصال والمحادثات."
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
