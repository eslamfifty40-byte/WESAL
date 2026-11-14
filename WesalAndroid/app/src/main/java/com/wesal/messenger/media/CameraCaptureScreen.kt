package com.wesal.messenger.media

import android.Manifest
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.wesal.messenger.core.theme.*
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
    val permissions = rememberMultiplePermissionsState(
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    )

    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var isVideoMode by remember { mutableStateOf(false) }
    var isRecordingVideo by remember { mutableStateOf(false) }
    var capturedUri by remember { mutableStateOf<Uri?>(null) }
    var capturedIsVideo by remember { mutableStateOf(false) }
    var caption by remember { mutableStateOf("") }

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }

    LaunchedEffect(Unit) {
        if (!permissions.allPermissionsGranted) {
            permissions.launchMultiplePermissionRequest()
        }
    }

    if (!permissions.allPermissionsGranted) {
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
                Text("مطلوب إذن الكاميرا والميكروفون لالتقاط الصور والفيديو", color = WesalTextPrimary)
                Button(onClick = { permissions.launchMultiplePermissionRequest() }) {
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
                }
            }, ContextCompat.getMainExecutor(context))
        }

        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.White)
            }
            IconButton(
                onClick = {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                },
                modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.Default.Cameraswitch, contentDescription = "تبديل الكاميرا", tint = Color.White)
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

                                    override fun onError(exception: ImageCaptureException) {}
                                }
                            )
                        } else {
                            if (isRecordingVideo) {
                                activeRecording?.stop()
                                isRecordingVideo = false
                            } else {
                                val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                                val file = File(dir, "vid_${UUID.randomUUID()}.mp4")
                                val outputOptions = FileOutputOptions.Builder(file).build()
                                activeRecording = videoCapture?.output
                                    ?.prepareRecording(context, outputOptions)
                                    ?.withAudioEnabled()
                                    ?.start(ContextCompat.getMainExecutor(context)) { event ->
                                        if (event is VideoRecordEvent.Finalize && !event.hasError()) {
                                            capturedIsVideo = true
                                            capturedUri = Uri.fromFile(file)
                                        }
                                    }
                                isRecordingVideo = true
                            }
                        }
                    }
            )
        }
    }
}
