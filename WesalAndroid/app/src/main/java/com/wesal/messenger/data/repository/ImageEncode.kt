package com.wesal.messenger.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import com.google.firebase.FirebaseApp
import java.io.ByteArrayOutputStream

fun uriToBase64Jpeg(uri: Uri, maxSide: Int = 720, quality: Int = 78): String {
    val ctx = FirebaseApp.getInstance().applicationContext
    return uriToBase64Jpeg(ctx, uri, maxSide, quality)
}

fun uriToBase64Jpeg(ctx: Context, uri: Uri, maxSide: Int = 720, quality: Int = 78): String {
    return uriToBase64Smart(ctx, uri, maxSide, quality, preserveAlphaIfPng = true)
}

private fun decodeArgbBitmap(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    var bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
    val rot = try {
        ctx.contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90f
                3 -> 180f
                8 -> 270f
                else -> 0f
            }
        } ?: 0f
    } catch (_: Exception) {
        0f
    }
    if (rot != 0f) {
        val m = Matrix().apply { postRotate(rot) }
        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
    val sc = maxSide.toFloat() / maxOf(bmp.width, bmp.height).coerceAtLeast(1)
    return if (sc < 1f) {
        Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * sc).toInt().coerceAtLeast(1),
            (bmp.height * sc).toInt().coerceAtLeast(1),
            true
        )
    } else {
        bmp
    }
}

fun uriToBase64Smart(
    ctx: Context,
    uri: Uri,
    maxSide: Int = 720,
    quality: Int = 78,
    preserveAlphaIfPng: Boolean = true
): String {
    val small = decodeArgbBitmap(ctx, uri, maxSide) ?: return ""
    val mimeHint = runCatching { ctx.contentResolver.getType(uri).orEmpty().lowercase() }.getOrDefault("")
    val uriLower = uri.toString().lowercase()
    val hasAlpha = hasTransparency(small)
    val isAlphaCandidate = preserveAlphaIfPng &&
        (hasAlpha || uriLower.endsWith(".png") || uriLower.endsWith(".webp") || mimeHint.contains("png") || mimeHint.contains("webp"))

    if (isAlphaCandidate) {
        // First try lossless PNG if compact enough
        val bos = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.PNG, 100, bos)
        val pngBytes = bos.toByteArray()
        if (pngBytes.size <= 260_000) {
            return "data:image/png;base64," + Base64.encodeToString(pngBytes, Base64.NO_WRAP)
        }

        // Compress with WEBP (supports full ARGB transparency cleanly on API 26+) so alpha is NEVER lost to opaque JPEG
        var targetBmp = small
        repeat(3) { step ->
            if (step > 0) {
                val nextSide = (maxOf(targetBmp.width, targetBmp.height) * 0.72f).toInt().coerceAtLeast(192)
                val sc2 = nextSide.toFloat() / maxOf(targetBmp.width, targetBmp.height).coerceAtLeast(1)
                if (sc2 < 1f) {
                    targetBmp = Bitmap.createScaledBitmap(
                        targetBmp,
                        (targetBmp.width * sc2).toInt().coerceAtLeast(1),
                        (targetBmp.height * sc2).toInt().coerceAtLeast(1),
                        true
                    )
                }
            }
            var wq = quality.coerceIn(55, 92)
            while (wq >= 40) {
                val webpBos = ByteArrayOutputStream()
                @Suppress("DEPRECATION")
                targetBmp.compress(Bitmap.CompressFormat.WEBP, wq, webpBos)
                val webpBytes = webpBos.toByteArray()
                if (webpBytes.size <= 280_000) {
                    return "data:image/webp;base64," + Base64.encodeToString(webpBytes, Base64.NO_WRAP)
                }
                wq -= 12
            }
        }
        // Final guaranteed compact PNG preserving transparency
        val safeScale = 240f / maxOf(small.width, small.height).coerceAtLeast(1)
        val tiny = if (safeScale < 1f) {
            Bitmap.createScaledBitmap(
                small,
                (small.width * safeScale).toInt().coerceAtLeast(1),
                (small.height * safeScale).toInt().coerceAtLeast(1),
                true
            )
        } else small
        val finalBos = ByteArrayOutputStream()
        tiny.compress(Bitmap.CompressFormat.PNG, 100, finalBos)
        return "data:image/png;base64," + Base64.encodeToString(finalBos.toByteArray(), Base64.NO_WRAP)
    }

    var q = quality.coerceIn(35, 92)
    var bytes: ByteArray
    do {
        val bos = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, q, bos)
        bytes = bos.toByteArray()
        q -= 12
    } while (bytes.size > 320_000 && q >= 40)

    return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
}

fun hasUriAlphaChannel(ctx: Context, uri: Uri): Boolean {
    val bmp = decodeArgbBitmap(ctx, uri, maxSide = 256) ?: return false
    return try {
        hasTransparency(bmp)
    } finally {
        runCatching { bmp.recycle() }
    }
}

private fun hasTransparency(bmp: Bitmap): Boolean {
    if (!bmp.hasAlpha()) return false
    val stepX = maxOf(1, bmp.width / 20)
    val stepY = maxOf(1, bmp.height / 20)
    for (y in 0 until bmp.height step stepY) {
        for (x in 0 until bmp.width step stepX) {
            if (((bmp.getPixel(x, y) ushr 24) and 0xFF) < 248) return true
        }
    }
    return false
}

suspend fun uploadRawBytesToStorage(
    storagePath: String,
    bytes: ByteArray,
    mimeType: String = "application/octet-stream",
    onProgress: (Float) -> Unit = {}
): Result<String> = runCatching {
    require(bytes.isNotEmpty()) { "الملف فارغ" }
    val ref = com.google.firebase.storage.FirebaseStorage.getInstance().reference.child(storagePath)
    val metadata = com.google.firebase.storage.storageMetadata {
        contentType = mimeType.ifBlank { "application/octet-stream" }
    }
    onProgress(0.05f)
    val task = ref.putBytes(bytes, metadata)
    task.addOnProgressListener { snap ->
        val total = snap.totalByteCount.coerceAtLeast(1L)
        val frac = (snap.bytesTransferred.toFloat() / total.toFloat()).coerceIn(0.05f, 0.94f)
        onProgress(frac)
    }
    task.await()
    onProgress(0.97f)
    val url = ref.downloadUrl.await().toString()
    onProgress(1f)
    url
}

suspend fun uploadProfileMediaToStorageOrSmallFallback(
    ctx: Context,
    storagePath: String,
    uri: Uri,
    maxSide: Int = 720,
    quality: Int = 84
): String {
    val mime = runCatching { ctx.contentResolver.getType(uri).orEmpty() }.getOrDefault("image/jpeg")
    val storageUrl = runCatching {
        val compactImage = uriToBase64Smart(ctx, uri, maxSide = maxSide, quality = quality, preserveAlphaIfPng = true)
        val base64Part = compactImage.substringAfter("base64,", "")
        if (base64Part.isNotBlank()) {
            val outMime = compactImage.substringAfter("data:", "image/jpeg").substringBefore(";base64", "image/jpeg")
            val rawBytes = Base64.decode(base64Part, Base64.DEFAULT)
            uploadRawBytesToStorage(storagePath, rawBytes, outMime).getOrNull()
        } else {
            val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (raw != null && raw.isNotEmpty()) uploadRawBytesToStorage(storagePath, raw, mime).getOrNull() else null
        }
    }.getOrNull()
    if (!storageUrl.isNullOrBlank()) return storageUrl

    return uriToBase64Smart(
        ctx = ctx,
        uri = uri,
        maxSide = minOf(maxSide, 512),
        quality = minOf(quality, 78),
        preserveAlphaIfPng = true
    )
}

suspend fun uploadLibraryImageOrDataUrl(
    ctx: Context,
    storagePath: String,
    uri: Uri,
    maxSide: Int = 512
): String {
    return uploadProfileMediaToStorageOrSmallFallback(
        ctx = ctx,
        storagePath = storagePath,
        uri = uri,
        maxSide = maxSide,
        quality = 88
    )
}

suspend fun uploadFileToFirebaseStorage(
    storagePath: String,
    uri: Uri,
    onProgress: (Float) -> Unit = {}
): Result<String> = runCatching {
    val ctx = FirebaseApp.getInstance().applicationContext
    val mime = runCatching { ctx.contentResolver.getType(uri).orEmpty() }
        .getOrDefault("application/octet-stream")
        .ifBlank { "application/octet-stream" }
    onProgress(0.05f)
    val storageRef = com.google.firebase.storage.FirebaseStorage.getInstance().reference.child(storagePath)
    val metadata = com.google.firebase.storage.storageMetadata {
        contentType = mime
    }
    val uploadResult = runCatching {
        val task = storageRef.putFile(uri, metadata)
        task.addOnProgressListener { snap ->
            val total = snap.totalByteCount.coerceAtLeast(1L)
            val frac = (snap.bytesTransferred.toFloat() / total.toFloat()).coerceIn(0.06f, 0.94f)
            onProgress(frac)
        }
        task.await()
        onProgress(0.97f)
        val url = storageRef.downloadUrl.await().toString()
        onProgress(1f)
        url
    }
    uploadResult.getOrNull()?.takeIf { it.isNotBlank() }?.let { return@runCatching it }

    // Safe small-image inline fallback ONLY if Storage bucket is unreachable and image fits inline budget
    if (mime.startsWith("image/", ignoreCase = true)) {
        val dataUrl = uriToBase64Smart(ctx, uri, maxSide = 760, quality = 80, preserveAlphaIfPng = true)
        check(dataUrl.isNotBlank()) { "تعذر رفع أو ضغط الصورة" }
        onProgress(1f)
        return@runCatching dataUrl
    }
    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw (uploadResult.exceptionOrNull() ?: IllegalStateException("تعذر قراءة الملف"))
    if (bytes.size <= 450_000) {
        onProgress(1f)
        return@runCatching "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
    throw (uploadResult.exceptionOrNull() ?: IllegalStateException("فشل رفع الملف إلى التخزين السحابي"))
}

fun uriToBase64Audio(ctx: Context, uri: Uri, maxBytes: Int = 450_000): String? {
    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    if (bytes.isEmpty() || bytes.size > maxBytes) return null
    return "data:audio/mp4;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
}
