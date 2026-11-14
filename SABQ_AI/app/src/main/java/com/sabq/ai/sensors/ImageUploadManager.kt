package com.sabq.ai.sensors

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class PreparedAndroidImage(
    val compressedFile: File,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long
)

data class UploadedImageResult(
    val imageId: String,
    val serverUrl: String,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long
)

/**
 * SABQ Real Image Upload Manager (Android):
 * - Supports Camera capture URI, Gallery picker URI, and Document/File URI.
 * - Validates JPEG / PNG / WebP and max 20 MB limit.
 * - Safely downsamples large bitmaps with inSampleSize to prevent OutOfMemoryError.
 * - Uploads via real multipart/form-data over OkHttp with timeout and clean error reporting.
 */
class ImageUploadManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(40, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private val allowedMimes = setOf("image/jpeg", "image/png", "image/webp")

    fun prepareAndCompressFromUri(userId: String, uri: Uri): PreparedAndroidImage {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "image/jpeg"
        require(mime in allowedMimes) {
            "صيغة الصورة غير مدعومة. الصيغ المقبولة فقط: JPEG أو PNG أو WebP."
        }

        // First pass: decode bounds only to prevent OutOfMemoryError on 12MP+ gallery images
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
            ?: error("تعذر فتح ملف الصورة المختار.")

        require(boundsOpts.outWidth > 0 && boundsOpts.outHeight > 0) {
            "الملف المختار تالف أو لا يحتوي على صورة صالحة."
        }

        var inSample = 1
        while ((boundsOpts.outWidth / inSample) > 1400 || (boundsOpts.outHeight / inSample) > 1400) {
            inSample *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = inSample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
            ?: error("تعذر قراءة بيانات الصورة.")

        val userDir = File(context.filesDir, "uploads/$userId").apply { mkdirs() }
        val ext = when (mime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val outFile = File(userDir, "upload_${System.currentTimeMillis()}.$ext")
        val format = if (mime == "image/png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        FileOutputStream(outFile).use { fos ->
            bitmap.compress(format, 88, fos)
        }
        val w = bitmap.width
        val h = bitmap.height
        bitmap.recycle()

        val size = outFile.length()
        require(size in 1..(20L * 1024L * 1024L)) {
            "حجم الصورة يتجاوز الحد الأقصى المسموح به (20 ميجابايت)."
        }

        return PreparedAndroidImage(
            compressedFile = outFile,
            mimeType = mime,
            width = w,
            height = h,
            sizeBytes = size
        )
    }

    fun uploadMultipart(
        baseUrl: String,
        userId: String,
        sectionId: String,
        sourceOrigin: String,
        prepared: PreparedAndroidImage
    ): UploadedImageResult {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("sectionId", sectionId)
            .addFormDataPart("sourceOrigin", sourceOrigin)
            .addFormDataPart(
                "image",
                prepared.compressedFile.name,
                prepared.compressedFile.asRequestBody(prepared.mimeType.toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/uploads/$userId")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrNull()
            if (!response.isSuccessful || json?.optBoolean("ok") != true) {
                val msg = json?.optString("error").orEmpty().ifBlank {
                    "فشل رفع الصورة (رمز ${response.code}). يمكنك إعادة المحاولة دون فقدان الصورة."
                }
                error(msg)
            }
            val imgObj = json.getJSONObject("image")
            return UploadedImageResult(
                imageId = imgObj.getString("id"),
                serverUrl = imgObj.getString("url"),
                localPath = prepared.compressedFile.absolutePath,
                mimeType = prepared.mimeType,
                sizeBytes = prepared.sizeBytes
            )
        }
    }
}
