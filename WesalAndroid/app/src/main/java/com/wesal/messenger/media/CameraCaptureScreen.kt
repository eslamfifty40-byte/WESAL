package com.wesal.messenger.media

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.presentation.components.WesalMediaKind
import com.wesal.messenger.presentation.components.rememberSavePermissionGate
import com.wesal.messenger.presentation.components.saveMediaToGallery
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraCaptureScreen(
    onMediaConfirmed: (uri: Uri, isVideo: Boolean, caption: String) -> Unit,
    onClose: () -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savePermissionGate = rememberSavePermissionGate()
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    val bothPermissions = rememberMultiplePermissionsState(
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    )

    var lensFacing by rememberSaveable { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var isVideoMode by rememberSaveable { mutableStateOf(false) }
    var isRecordingVideo by remember { mutableStateOf(false) }
    var recordingElapsedSec by remember { mutableLongStateOf(0L) }
    var capturedUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var capturedFilePath by rememberSaveable { mutableStateOf<String?>(null) }
    var capturedIsVideo by rememberSaveable { mutableStateOf(false) }
    var caption by rememberSaveable { mutableStateOf("") }
    var captureError by remember { mutableStateOf<String?>(null) }

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }

    LaunchedEffect(Unit) {
        if (!bothPermissions.allPermissionsGranted) {
            bothPermissions.launchMultiplePermissionRequest()
        }
    }

    LaunchedEffect(isRecordingVideo) {
        if (isRecordingVideo) {
            recordingElapsedSec = 0L
            while (isRecordingVideo) {
                delay(1000L)
                recordingElapsedSec += 1L
            }
        }
    }

    if (!cameraPermission.status.isGranted) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = if (strings.isEnglish) {
                        "Camera permission is required to take photos and videos"
                    } else {
                        "مطلوب إذن الكاميرا لالتقاط الصور والفيديو"
                    },
                    color = palette.textPrimary
                )
                Button(
                    onClick = { bothPermissions.launchMultiplePermissionRequest() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.emerald,
                        contentColor = palette.onAccent
                    )
                ) {
                    Text(if (strings.isEnglish) "Grant Permission" else "منح الإذن")
                }
                TextButton(onClick = onClose) {
                    Text(strings.cancel, color = palette.textSecondary)
                }
            }
        }
        return
    }

    // Preview screen after capture before sending
    if (capturedUri != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (!capturedIsVideo) {
                AsyncImage(
                    model = capturedUri,
                    contentDescription = if (strings.isEnglish) "Photo preview" else "معاينة الصورة",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                VideoPlayerBox(uri = capturedUri!!, modifier = Modifier.fillMaxSize())
            }

            IconButton(
                onClick = {
                    capturedFilePath?.let { path -> runCatching { File(path).delete() } }
                    capturedFilePath = null
                    capturedUri = null
                    caption = ""
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = if (strings.isEnglish) "Retake" else "إعادة الالتقاط",
                    tint = Color.White
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    placeholder = {
                        Text(
                            if (strings.isEnglish) "Add a caption..." else "أضف تعليقاً...",
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = palette.turquoise,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.35f)
                    )
                )
                FloatingActionButton(
                    onClick = {
                        val confirmedUri = capturedUri!!
                        val confirmedPath = capturedFilePath
                        val confirmedIsVideo = capturedIsVideo
                        // Round-4: a confirmed capture is also kept in the device
                        // gallery (Pictures/WESAL or Movies/WESAL), byte-for-byte.
                        // Fire-and-forget alongside the send; the outcome is
                        // reported honestly by toast, never faked.
                        if (confirmedPath != null) {
                            savePermissionGate {
                                scope.launch {
                                    saveMediaToGallery(
                                        context,
                                        File(confirmedPath),
                                        if (confirmedIsVideo) WesalMediaKind.VIDEO else WesalMediaKind.IMAGE
                                    ).onSuccess {
                                        Toast.makeText(
                                            context,
                                            if (strings.isEnglish) "Saved to gallery ✓" else "تم الحفظ في المعرض ✓",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }.onFailure { err ->
                                        Toast.makeText(
                                            context,
                                            err.localizedMessage ?: if (strings.isEnglish) "Could not save to gallery" else "تعذر الحفظ في المعرض",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        }
                        onMediaConfirmed(confirmedUri, confirmedIsVideo, caption)
                    },
                    containerColor = palette.emerald,
                    contentColor = palette.onAccent
                ) {
                    Icon(Icons.Default.Send, contentDescription = strings.send)
                }
            }
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val previewView = remember { PreviewView(context) }

        LaunchedEffect(lensFacing) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val imgCap = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD))
                    .build()
                val vidCap = VideoCapture.withOutput(recorder)

                imageCapture = imgCap
                videoCapture = vidCap

                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                runCatching {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imgCap, vidCap)
                }.onFailure { err ->
                    captureError = err.localizedMessage ?: if (strings.isEnglish) "Failed to start camera" else "تعذر تشغيل الكاميرا"
                }
            }, ContextCompat.getMainExecutor(context))
        }

        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Clean top area: only show recording indicator when recording or error banner when present
        if (isRecordingVideo) {
            Surface(
                color = palette.error,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 20.dp)
            ) {
                Text(
                    text = "● ${DateTimeUtils.formatDurationSeconds(recordingElapsedSec)}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        captureError?.let { err ->
            Surface(
                color = palette.error.copy(alpha = 0.92f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (isRecordingVideo) 66.dp else 20.dp, start = 16.dp, end = 16.dp)
            ) {
                Text(
                    text = err,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

        // Bottom-docked controls: Photo/Video mode selector + Close / Shutter / Switch Camera
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.42f))
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(
                    selected = !isVideoMode,
                    onClick = { if (!isRecordingVideo) isVideoMode = false },
                    label = { Text(strings.photoMode, maxLines = 1, softWrap = false) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = palette.emerald,
                        selectedLabelColor = palette.onAccent,
                        containerColor = Color.Black.copy(alpha = 0.5f),
                        labelColor = Color.White
                    )
                )
                FilterChip(
                    selected = isVideoMode,
                    onClick = { if (!isRecordingVideo) isVideoMode = true },
                    label = { Text(strings.videoMode, maxLines = 1, softWrap = false) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = palette.emerald,
                        selectedLabelColor = palette.onAccent,
                        containerColor = Color.Black.copy(alpha = 0.5f),
                        labelColor = Color.White
                    )
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = strings.close,
                        tint = Color.White
                    )
                }

                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (isRecordingVideo) palette.error
                            else if (isVideoMode) palette.error.copy(alpha = 0.85f)
                            else palette.emerald
                        )
                        .clickable {
                            captureError = null
                            if (!isVideoMode) {
                                val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                                val file = File(dir, "img_${UUID.randomUUID()}.jpg")
                                val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()
                                imageCapture?.takePicture(
                                    outputOptions,
                                    ContextCompat.getMainExecutor(context),
                                    object : ImageCapture.OnImageSavedCallback {
                                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                            capturedIsVideo = false
                                            capturedFilePath = file.absolutePath
                                            capturedUri = FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                file
                                            )
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            runCatching { file.delete() }
                                            captureError = exception.localizedMessage ?: if (strings.isEnglish) {
                                                "Failed to capture photo"
                                            } else {
                                                "تعذر التقاط الصورة"
                                            }
                                        }
                                    }
                                )
                            } else {
                                if (isRecordingVideo) {
                                    activeRecording?.stop()
                                    isRecordingVideo = false
                                } else {
                                    val hasMic = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                                    val file = File(dir, "vid_${UUID.randomUUID()}.mp4")
                                    val outputOptions = FileOutputOptions.Builder(file).build()
                                    val pending = videoCapture?.output?.prepareRecording(context, outputOptions)?.let { prep ->
                                        if (hasMic) prep.withAudioEnabled() else prep
                                    }
                                    activeRecording = pending?.start(ContextCompat.getMainExecutor(context)) { event ->
                                        if (event is VideoRecordEvent.Finalize) {
                                            isRecordingVideo = false
                                            if (!event.hasError()) {
                                                capturedIsVideo = true
                                                capturedFilePath = file.absolutePath
                                                capturedUri = FileProvider.getUriForFile(
                                                    context,
                                                    "${context.packageName}.fileprovider",
                                                    file
                                                )
                                            } else {
                                                runCatching { file.delete() }
                                                captureError = if (strings.isEnglish) {
                                                    "Video recording failed (${event.error})"
                                                } else {
                                                    "تعذر تسجيل الفيديو (${event.error})"
                                                }
                                            }
                                        }
                                    }
                                    if (activeRecording != null) {
                                        isRecordingVideo = true
                                    } else {
                                        captureError = if (strings.isEnglish) {
                                            "Camera is initializing, please wait"
                                        } else {
                                            "الكاميرا قيد التهيئة، حاول بعد لحظة"
                                        }
                                    }
                                }
                            }
                        }
                )

                IconButton(
                    onClick = {
                        if (!isRecordingVideo) {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                ) {
                    Icon(
                        Icons.Default.Cameraswitch,
                        contentDescription = if (strings.isEnglish) "Switch camera" else "تبديل الكاميرا",
                        tint = Color.White
                    )
                }
            }
        }
    }
}
