package com.wesal.messenger.data.repository

fun uriToBase64Jpeg(uri: android.net.Uri, maxSide: Int, quality: Int): String {
    val ctx = com.google.firebase.FirebaseApp.getInstance().applicationContext
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    var bmp = ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) } ?: return ""
    val rot = try {
        ctx.contentResolver.openInputStream(uri)?.use {
            when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90f
                3 -> 180f
                8 -> 270f
                else -> 0f
            }
        } ?: 0f
    } catch (e: Exception) {
        0f
    }
    if (rot != 0f) {
        val m = android.graphics.Matrix()
        m.postRotate(rot)
        bmp = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
    val sc = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
    val small = if (sc < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true) else bmp
    val bos = java.io.ByteArrayOutputStream()
    small.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, bos)
    return "data:image/jpeg;base64," + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
}
