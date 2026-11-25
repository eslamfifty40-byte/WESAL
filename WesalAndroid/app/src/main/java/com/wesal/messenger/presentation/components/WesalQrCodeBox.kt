package com.wesal.messenger.presentation.components

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

@Composable
fun WesalQrCodeBox(
    payload: String,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    borderColor: Color = Color(0xFFCBD5E1)
) {
    val qrBitmap: ImageBitmap? = remember(payload) {
        generateQrImageBitmap(payload, pixelSize = 512)
    }

    Surface(
        modifier = modifier,
        color = Color.White,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = 2.dp
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .background(Color.White)
                .padding(14.dp),
            contentAlignment = Alignment.Center
        ) {
            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap,
                    contentDescription = "WESAL QR Code",
                    modifier = Modifier.size(size - 28.dp),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

fun generateQrAndroidBitmap(content: String, pixelSize: Int = 512): Bitmap? {
    if (content.isBlank()) return null
    return runCatching {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, pixelSize, pixelSize, hints)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
            }
        }
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }.getOrNull()
}

fun generateQrImageBitmap(content: String, pixelSize: Int = 512): ImageBitmap? =
    generateQrAndroidBitmap(content, pixelSize)?.asImageBitmap()

fun saveQrBitmapToGallery(context: Context, payload: String, fileName: String = "WESAL_QR_${System.currentTimeMillis()}.png"): Result<Unit> = runCatching {
    val bmp = generateQrAndroidBitmap(payload, 768) ?: error("تعذر إنشاء رمز QR")
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WESAL")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("تعذر حفظ الصورة")
    resolver.openOutputStream(uri)?.use { out ->
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
}

suspend fun saveImageUrlToGallery(
    context: Context,
    imageUrl: String,
    fileNamePrefix: String = "WESAL_IMG"
): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        val clean = imageUrl.trim()
        require(clean.isNotBlank()) { "لا توجد صورة صالحة للحفظ" }

        val rawBytes: ByteArray = when {
            clean.startsWith("data:", ignoreCase = true) -> {
                val base64Idx = clean.indexOf("base64,")
                require(base64Idx >= 0) { "تنسيق الصورة غير مدعوم" }
                android.util.Base64.decode(clean.substring(base64Idx + 7), android.util.Base64.DEFAULT)
            }
            clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true) -> {
                val conn = URL(clean).openConnection()
                conn.connectTimeout = 10_000
                conn.readTimeout = 12_000
                conn.getInputStream().use { it.readBytes() }
            }
            clean.startsWith("content://", ignoreCase = true) -> {
                context.contentResolver.openInputStream(Uri.parse(clean))?.use { it.readBytes() }
                    ?: error("تعذر قراءة الصورة المختارة")
            }
            clean.startsWith("file://", ignoreCase = true) -> {
                val path = Uri.parse(clean).path ?: error("مسار غير صالح")
                File(path).readBytes()
            }
            else -> {
                val file = File(clean)
                require(file.exists()) { "تعذر العثور على الصورة" }
                file.readBytes()
            }
        }

        require(rawBytes.isNotEmpty()) { "ملف الصورة فارغ" }
        val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
        val isPng = clean.contains("image/png", ignoreCase = true) || (bmp?.hasAlpha() == true)
        val ext = if (isPng) "png" else "jpg"
        val mime = if (isPng) "image/png" else "image/jpeg"
        val fileName = "${fileNamePrefix}_${System.currentTimeMillis()}.$ext"

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WESAL")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("تعذر حفظ الصورة في المعرض")
        resolver.openOutputStream(uri)?.use { out ->
            if (bmp != null) {
                bmp.compress(if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, out)
            } else {
                out.write(rawBytes)
            }
        } ?: error("تعذر فتح مسار الحفظ")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        fileName
    }
}

fun shareQrBitmapViaFileProvider(context: Context, payload: String, caption: String): Result<Unit> = runCatching {
    val bmp = generateQrAndroidBitmap(payload, 768) ?: error("تعذر إنشاء رمز QR")
    val dir = File(context.cacheDir, "shared_qr").apply { mkdirs() }
    val file = File(dir, "wesal_qr_share.png")
    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, caption)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "WESAL QR"))
}

fun decodeQrFromBitmap(bmp: Bitmap): String? = runCatching {
    val width = bmp.width
    val height = bmp.height
    val pixels = IntArray(width * height)
    bmp.getPixels(pixels, 0, width, 0, 0, width, height)
    val source = RGBLuminanceSource(width, height, pixels)
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    MultiFormatReader().decode(bitmap).text
}.getOrNull()

fun decodeQrFromUri(context: Context, uri: Uri): String? = runCatching {
    val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
    val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
    decodeQrFromBitmap(raw)
}.getOrNull()
