package com.wesal.messenger.media

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState
import com.wesal.messenger.core.theme.*
import com.wesal.messenger.core.util.DateTimeUtils
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraCaptureScreen(
    onMediaConfirmed: (uri: Uri, isVideo: Boolean, caption: String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    val bothPermissions = rememberMultiplePermissionsState(
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    )

    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var isVideoMode by remember { mutableStateOf(false) }
    var isRecordingVideo by remember { mutableStateOf(false) }
    var recordingElapsedSec by remember { mutableLongStateOf(0L) }
    var capturedUri by remember { mutableStateOf<Uri?>(null) }
    var capturedIsVideo by remember { mutableStateOf(false) }
    var caption by remember { mutableStateOf("") }
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
                .background(WesalDeepBlack),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                Text("مطلوب إذن الكاميرا لالتقاط الصور والفيديو", color = WesalTextPrimary)
                Button(onClick = { bothPermissions.launchMultiplePermissionRequest() }) {
                    Text("منح الإذن")
                }
                TextButton(onClick = onClose) { Text("إلغاء", color = WesalTextSecondary) }
            }
        }
        return
    }

    // Preview screen after capture before sending
    if (capturedUri != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(WesalDeepBlack)
        ) {
            if (!capturedIsVideo) {
                AsyncImage(
                    model = capturedUri,
                    contentDescription = "معاينة الصورة",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                VideoPlayerBox(uri = capturedUri!!, modifier = Modifier.fillMaxSize())
            }

            IconButton(
                onClick = { capturedUri = null },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = "إعادة الالتقاط", tint = Color.White)
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    placeholder = { Text("أضف تعليقاً...") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp)
                )
                FloatingActionButton(
                    onClick = { onMediaConfirmed(capturedUri!!, capturedIsVideo, caption) },
                    containerColor = WesalEmerald,
                    contentColor = WesalDeepBlack
                ) {
                    Icon(Icons.Default.Send, contentDescription = "إرسال")
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
                    captureError = err.localizedMessage ?: "تعذر تشغيل الكاميرا"
                }
            }, ContextCompat.getMainExecutor(context))
        }

        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.White)
            }
            if (isRecordingVideo) {
                Surface(
                    color = WesalError,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        text = "● ${DateTimeUtils.formatDurationSeconds(recordingElapsedSec)}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
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
                modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.Default.Cameraswitch, contentDescription = "تبديل الكاميرا", tint = Color.White)
            }
        }

        captureError?.let { err ->
            Surface(
                color = WesalError.copy(alpha = 0.9f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 74.dp, start = 16.dp, end = 16.dp)
            ) {
                Text(
                    text = err,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FilterChip(
                    selected = !isVideoMode,
                    onClick = { if (!isRecordingVideo) isVideoMode = false },
                    label = { Text("صورة") }
                )
                FilterChip(
                    selected = isVideoMode,
                    onClick = { if (!isRecordingVideo) isVideoMode = true },
                    label = { Text("فيديو") }
                )
            }

            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(if (isRecordingVideo) WesalError else if (isVideoMode) WesalError.copy(alpha = 0.85f) else WesalEmerald)
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
                                        capturedUri = Uri.fromFile(file)
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        captureError = exception.localizedMessage ?: "تعذر التقاط الصورة"
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
                                            capturedUri = Uri.fromFile(file)
                                        } else {
                                            captureError = "تعذر تسجيل الفيديو (${event.error})"
                                        }
                                    }
                                }
                                if (activeRecording != null) {
                                    isRecordingVideo = true
                                } else {
                                    captureError = "الكاميرا قيد التهيئة، حاول بعد لحظة"
                                }
                            }
                        }
                    }
            )
        }
    }
}
