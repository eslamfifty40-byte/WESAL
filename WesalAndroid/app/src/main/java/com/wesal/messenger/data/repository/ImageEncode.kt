package com.wesal.messenger.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import com.google.firebase.FirebaseApp
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream

fun uriToBase64Jpeg(uri: Uri, maxSide: Int = 720, quality: Int = 78): String {
    val ctx = FirebaseApp.getInstance().applicationContext
    return uriToBase64Jpeg(ctx, uri, maxSide, quality)
}

fun uriToBase64Jpeg(ctx: Context, uri: Uri, maxSide: Int = 720, quality: Int = 78): String {
    return uriToBase64Smart(ctx, uri, maxSide, quality, preserveAlphaIfPng = true)
}

fun uriToBase64Smart(
    ctx: Context,
    uri: Uri,
    maxSide: Int = 720,
    quality: Int = 78,
    preserveAlphaIfPng: Boolean = true
): String {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return ""
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    var bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return ""
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
    val small = if (sc < 1f) {
        Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * sc).toInt().coerceAtLeast(1),
            (bmp.height * sc).toInt().coerceAtLeast(1),
            true
        )
    } else {
        bmp
    }

    val isPngWithAlpha = preserveAlphaIfPng &&
        (uri.toString().lowercase().endsWith(".png") || hasTransparency(small))

    if (isPngWithAlpha) {
        val bos = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.PNG, 100, bos)
        val pngBytes = bos.toByteArray()
        if (pngBytes.size <= 380_000) {
            return "data:image/png;base64," + Base64.encodeToString(pngBytes, Base64.NO_WRAP)
        }
        val smallerSide = (maxSide * 0.65f).toInt().coerceAtLeast(240)
        val sc2 = smallerSide.toFloat() / maxOf(small.width, small.height).coerceAtLeast(1)
        val resized = if (sc2 < 1f) {
            Bitmap.createScaledBitmap(
                small,
                (small.width * sc2).toInt().coerceAtLeast(1),
                (small.height * sc2).toInt().coerceAtLeast(1),
                true
            )
        } else {
            small
        }
        val bos2 = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.PNG, 100, bos2)
        val bytes2 = bos2.toByteArray()
        if (true) {
            return "data:image/png;base64," + Base64.encodeToString(bytes2, Base64.NO_WRAP)
        }
    }

    var q = quality.coerceIn(35, 92)
    var bytes: ByteArray
    do {
        val bos = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, q, bos)
        bytes = bos.toByteArray()
        q -= 12
    } while (bytes.size > 380_000 && q >= 40)

    return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
}

private fun hasTransparency(bmp: Bitmap): Boolean {
    if (!bmp.hasAlpha()) return false
    val stepX = maxOf(1, bmp.width / 16)
    val stepY = maxOf(1, bmp.height / 16)
    for (y in 0 until bmp.height step stepY) {
        for (x in 0 until bmp.width step stepX) {
            if (((bmp.getPixel(x, y) ushr 24) and 0xFF) < 250) return true
        }
    }
    return false
}

suspend fun uploadProfileMediaToStorageOrSmallFallback(
    ctx: Context,
    storagePath: String,
    uri: Uri,
    maxSide: Int = 512,
    quality: Int = 82
): String {
    val storageResult = runCatching {
        val ref = FirebaseStorage.getInstance().reference.child(storagePath)
        ref.putFile(uri).await()
        ref.downloadUrl.await().toString()
    }
    storageResult.getOrNull()?.let { if (it.isNotBlank()) return it }
    // Safe compact avatar/cover fallback only (<= 380KB, never used for large attachments/videos)
    return uriToBase64Smart(ctx, uri, maxSide = maxSide, quality = quality, preserveAlphaIfPng = true)
}

suspend fun uploadFileToFirebaseStorage(
    storagePath: String,
    uri: Uri,
    onProgress: (Float) -> Unit = {}
): Result<String> = runCatching {
    val ref = FirebaseStorage.getInstance().reference.child(storagePath)
    val task = ref.putFile(uri)
    task.addOnProgressListener { snap ->
        val total = snap.totalByteCount
        if (total > 0L) {
            onProgress((snap.bytesTransferred.toFloat() / total.toFloat()).coerceIn(0.05f, 0.95f))
        }
    }
    task.await()
    onProgress(0.96f)
    ref.downloadUrl.await().toString()
}

fun uriToBase64Audio(ctx: Context, uri: Uri, maxBytes: Int = 450_000): String? {
    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    if (bytes.isEmpty() || bytes.size > maxBytes) return null
    return "data:audio/mp4;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
}
