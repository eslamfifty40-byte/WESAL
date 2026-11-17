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
        if (bytes2.size <= 450_000) {
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

/**
 * Prepares a profile/cover image: fixes EXIF rotation, scales, keeps real transparency as PNG
 * (so cut-out portraits keep their alpha) and everything else as JPEG.
 * Returns the encoded bytes and their MIME type, or null if the image can't be read.
 */
fun encodeProfileImage(ctx: Context, uri: Uri, maxSide: Int, quality: Int): Pair<ByteArray, String>? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
    var bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
    val rot = try {
        ctx.contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
            }
        } ?: 0f
    } catch (_: Exception) { 0f }
    if (rot != 0f) {
        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
    }
    val sc = maxSide.toFloat() / maxOf(bmp.width, bmp.height).coerceAtLeast(1)
    if (sc < 1f) {
        bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt().coerceAtLeast(1), (bmp.height * sc).toInt().coerceAtLeast(1), true)
    }
    if (hasTransparency(bmp)) {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return bos.toByteArray() to "image/png"
    }
    val bos = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(40, 95), bos)
    return bos.toByteArray() to "image/jpeg"
}

/**
 * Uploads a profile/cover image to Firebase Storage (explicit content-type, correct extension) and returns
 * the download URL. If Storage is unavailable the image is stored as a compact data: URI instead, so the
 * picture is ALWAYS persisted in Firebase and never silently lost.
 * Throws if the image cannot be read, so callers can show a real error.
 */
suspend fun uploadProfileMediaToStorageOrSmallFallback(
    ctx: Context,
    storagePath: String,
    uri: Uri,
    maxSide: Int = 512,
    quality: Int = 82
): String {
    val (bytes, mime) = encodeProfileImage(ctx, uri, maxSide, quality)
        ?: throw IllegalStateException("تعذر قراءة الصورة المختارة")
    val ext = if (mime == "image/png") "png" else "jpg"
    val path = storagePath.substringBeforeLast('.', storagePath).let { base ->
        // drop any old extension only when one was present
        if (storagePath.endsWith(".jpg") || storagePath.endsWith(".png")) base else storagePath
    } + "." + ext
    val storageUrl = runCatching {
        val ref = FirebaseStorage.getInstance().reference.child(path)
        val meta = com.google.firebase.storage.StorageMetadata.Builder().setContentType(mime).build()
        ref.putBytes(bytes, meta).await()
        ref.downloadUrl.await().toString()
    }.getOrNull()
    if (!storageUrl.isNullOrBlank()) return storageUrl

    // Fallback: compact data URI kept in Firestore (must stay well below the 1 MB document limit)
    var side = maxSide
    var out = bytes
    var m = mime
    while (out.size > 380_000 && side > 200) {
        side = (side * 0.8f).toInt()
        val again = encodeProfileImage(ctx, uri, side, 78) ?: break
        out = again.first; m = again.second
    }
    if (out.size > 420_000) {
        // last resort for huge transparent PNGs: flatten to JPEG
        val bmp = BitmapFactory.decodeByteArray(out, 0, out.size)
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 70, bos)
        out = bos.toByteArray(); m = "image/jpeg"
    }
    return "data:$m;base64," + Base64.encodeToString(out, Base64.NO_WRAP)
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
