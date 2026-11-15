package com.wesal.messenger.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import android.media.ExifInterface
import com.google.firebase.FirebaseApp
import java.io.ByteArrayOutputStream

fun uriToBase64Jpeg(uri: Uri, maxSide: Int = 720, quality: Int = 78): String {
    val ctx = FirebaseApp.getInstance().applicationContext
    return uriToBase64Jpeg(ctx, uri, maxSide, quality)
}

fun uriToBase64Jpeg(ctx: Context, uri: Uri, maxSide: Int = 720, quality: Int = 78): String {
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
        Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt().coerceAtLeast(1), (bmp.height * sc).toInt().coerceAtLeast(1), true)
    } else {
        bmp
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

fun uriToBase64Audio(ctx: Context, uri: Uri, maxBytes: Int = 450_000): String? {
    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    if (bytes.isEmpty() || bytes.size > maxBytes) return null
    return "data:audio/mp4;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
}
