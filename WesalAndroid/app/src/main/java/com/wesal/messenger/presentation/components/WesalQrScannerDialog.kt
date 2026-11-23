package com.wesal.messenger.presentation.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.LocalWesalStrings
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

@Composable
fun WesalQrScannerDialog(
    onDismiss: () -> Unit,
    onQrDetected: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    var hasCameraPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var scanError by remember { mutableStateOf<String?>(null) }
    var alreadyHandled by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPerm = granted
    }

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val decoded = decodeQrFromUri(context, uri)
            if (!decoded.isNullOrBlank()) {
                alreadyHandled = true
                onQrDetected(decoded)
            } else {
                scanError = if (strings.isEnglish) "No QR code found in this image" else "لم يتم العثور على رمز QR في هذه الصورة"
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPerm) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background),
            color = palette.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = palette.turquoise)
                        Text(
                            text = if (strings.isEnglish) "Scan WESAL QR Code" else "مسح رمز QR في WESAL",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = palette.textPrimary
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = strings.close, tint = palette.textPrimary)
                    }
                }

                Spacer(Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (hasCameraPerm) {
                        val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
                        DisposableEffect(Unit) {
                            onDispose { analysisExecutor.shutdown() }
                        }
                        AndroidView(
                            factory = { ctx ->
                                val previewView = PreviewView(ctx)
                                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                                cameraProviderFuture.addListener({
                                    val cameraProvider = runCatching { cameraProviderFuture.get() }.getOrNull() ?: return@addListener
                                    val preview = Preview.Builder().build().also {
                                        it.surfaceProvider = previewView.surfaceProvider
                                    }
                                    val analyzer = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()
                                        .also { analysis ->
                                            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                                if (!alreadyHandled) {
                                                    val code = decodeYuvQr(imageProxy)
                                                    if (!code.isNullOrBlank()) {
                                                        alreadyHandled = true
                                                        previewView.post {
                                                            onQrDetected(code)
                                                        }
                                                    }
                                                }
                                                imageProxy.close()
                                            }
                                        }
                                    runCatching {
                                        cameraProvider.unbindAll()
                                        cameraProvider.bindToLifecycle(
                                            lifecycleOwner,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            preview,
                                            analyzer
                                        )
                                    }
                                }, ContextCompat.getMainExecutor(ctx))
                                previewView
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                            Text(
                                text = if (strings.isEnglish) "Camera permission is required to scan QR codes." else "يلزم منح إذن الكاميرا لمسح رمز QR.",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                                colors = ButtonDefaults.buttonColors(containerColor = palette.emerald, contentColor = Color(0xFF05090B))
                            ) {
                                Text(if (strings.isEnglish) "Grant Camera" else "منح إذن الكاميرا")
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(220.dp)
                            .border(2.5.dp, palette.turquoise, RoundedCornerShape(18.dp))
                    )
                }

                scanError?.let { err ->
                    Spacer(Modifier.height(8.dp))
                    Text(err, color = palette.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = {
                        galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = palette.turquoise)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (strings.isEnglish) "Pick QR Image from Gallery" else "اختيار صورة QR من المعرض",
                        color = palette.textPrimary
                    )
                }
            }
        }
    }
}

private fun decodeYuvQr(image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining())
    buffer.get(data)
    val w = image.width
    val h = image.height
    return runCatching {
        val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        MultiFormatReader().decode(bitmap).text
    }.getOrElse {
        runCatching {
            val yuv = YuvImage(data, ImageFormat.NV21, w, h, null)
            val bos = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, w, h), 85, bos)
            val bmp = BitmapFactory.decodeByteArray(bos.toByteArray(), 0, bos.size()) ?: return@runCatching null
            decodeQrFromBitmap(bmp)
        }.getOrNull()
    }
}
