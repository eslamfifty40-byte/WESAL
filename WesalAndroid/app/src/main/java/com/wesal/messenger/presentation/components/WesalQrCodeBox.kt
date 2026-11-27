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
import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.imageLoader
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.wesal.messenger.core.util.LocalWesalStrings
import java.security.MessageDigest
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

// ---------------------------------------------------------------------------
// Unified byte-exact media saver (round-4, spec: حفظ الصور والوسائط).
//
// One saver for every surface: it copies the ORIGINAL bytes (no decode /
// re-encode, so animated GIF/WebP and untouched JPEGs survive intact) into
// the MediaStore collection matching the media kind — Images → Pictures/WESAL,
// Video → Movies/WESAL, Audio → Music/WESAL, anything else → Download/WESAL.
// Sources can be a File, a Uri, or a String (http(s), file://, content://,
// a plain filesystem path, a data: URL, or a wesal-plain:// payload).
// wesal-inline:// payloads are E2EE ciphertext: they can only be saved after
// the chat layer decrypts them to a File (decryptMediaToCache), so the saver
// fails honestly for them instead of writing garbage and claiming success.
// ---------------------------------------------------------------------------

/** Kinds of media the shared saver routes to the right MediaStore collection. */
enum class WesalMediaKind { IMAGE, VIDEO, AUDIO, DOCUMENT }

/** A save on API ≤ 28 was attempted before the legacy storage permission was granted. */
class StoragePermissionRequiredException(message: String) : Exception(message)

private data class ResolvedSaveSource(
    val file: File,
    val deleteAfter: Boolean,
    val declaredMime: String?
)

private fun newSaveTempFile(context: Context): File {
    val dir = File(context.cacheDir, "gallery_save").apply { mkdirs() }
    return File(dir, "save_${System.currentTimeMillis()}_${(0..99999).random()}.tmp")
}

private fun md5Hex(value: String): String =
    MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/**
 * Best-effort read of Coil's disk cache: when the app already downloaded this
 * URL for display, reuse those bytes instead of a second network fetch.
 * Returns a temp copy (safe to use after the snapshot closes), or null on any
 * miss/mismatch — the caller then falls back to a direct download.
 */
private fun coilCachedFileCopy(context: Context, url: String): File? {
    val cache = runCatching { context.imageLoader.diskCache }.getOrNull() ?: return null
    for (key in listOf(url, md5Hex(url))) {
        val snapshot = runCatching { cache.openSnapshot(key) }.getOrNull() ?: continue
        val copied = runCatching {
            snapshot.use { snap ->
                val src = File(snap.data.toString())
                if (!src.exists() || src.length() == 0L) {
                    null
                } else {
                    val tmp = newSaveTempFile(context)
                    src.inputStream().use { inp -> tmp.outputStream().use { out -> inp.copyTo(out) } }
                    tmp
                }
            }
        }.getOrNull()
        if (copied != null && copied.length() > 0L) return copied
    }
    return null
}

private fun resolveStringSaveSource(context: Context, raw: String): ResolvedSaveSource {
    val clean = raw.trim()
    require(clean.isNotBlank()) { "لا توجد وسائط صالحة للحفظ" }
    return when {
        clean.startsWith("data:", ignoreCase = true) -> {
            val declaredMime = clean.substringAfter("data:").substringBefore(";")
                .takeIf { it.contains('/') }
            val base64Idx = clean.indexOf("base64,")
            require(base64Idx >= 0) { "تنسيق الوسائط غير مدعوم" }
            val bytes = Base64.decode(clean.substring(base64Idx + 7), Base64.DEFAULT)
            val tmp = newSaveTempFile(context)
            tmp.writeBytes(bytes)
            ResolvedSaveSource(tmp, deleteAfter = true, declaredMime = declaredMime)
        }
        clean.startsWith("wesal-plain://", ignoreCase = true) -> {
            // Same raw-payload encoding ChatRepositoryImpl.decryptMediaToCache accepts.
            val bytes = Base64.decode(clean.substring("wesal-plain://".length), Base64.NO_WRAP)
            val tmp = newSaveTempFile(context)
            tmp.writeBytes(bytes)
            ResolvedSaveSource(tmp, deleteAfter = true, declaredMime = null)
        }
        clean.startsWith("wesal-inline://", ignoreCase = true) -> {
            error("هذا الملف مشفّر — افتحه من المحادثة أولاً ثم احفظه من هناك")
        }
        clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true) -> {
            coilCachedFileCopy(context, clean)?.let {
                return ResolvedSaveSource(it, deleteAfter = true, declaredMime = null)
            }
            val tmp = newSaveTempFile(context)
            val conn = URL(clean).openConnection()
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.getInputStream().use { inp -> tmp.outputStream().use { out -> inp.copyTo(out) } }
            ResolvedSaveSource(tmp, deleteAfter = true, declaredMime = null)
        }
        clean.startsWith("content://", ignoreCase = true) -> {
            val uri = Uri.parse(clean)
            val tmp = newSaveTempFile(context)
            context.contentResolver.openInputStream(uri)?.use { inp ->
                tmp.outputStream().use { out -> inp.copyTo(out) }
            } ?: error("تعذر قراءة الملف المختار")
            ResolvedSaveSource(
                tmp,
                deleteAfter = true,
                declaredMime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            )
        }
        clean.startsWith("file://", ignoreCase = true) -> {
            val path = Uri.parse(clean).path ?: error("مسار غير صالح")
            ResolvedSaveSource(File(path), deleteAfter = false, declaredMime = null)
        }
        else -> {
            val file = File(clean)
            require(file.exists()) { "تعذر العثور على الملف" }
            ResolvedSaveSource(file, deleteAfter = false, declaredMime = null)
        }
    }
}

private fun resolveSaveSource(context: Context, source: Any): ResolvedSaveSource =
    when (source) {
        is File -> ResolvedSaveSource(source, deleteAfter = false, declaredMime = null)
        is Uri -> when (source.scheme?.lowercase()) {
            "file" -> ResolvedSaveSource(
                File(source.path ?: error("مسار غير صالح")),
                deleteAfter = false,
                declaredMime = null
            )
            else -> {
                val tmp = newSaveTempFile(context)
                context.contentResolver.openInputStream(source)?.use { inp ->
                    tmp.outputStream().use { out -> inp.copyTo(out) }
                } ?: error("تعذر قراءة الملف المختار")
                ResolvedSaveSource(
                    tmp,
                    deleteAfter = true,
                    declaredMime = runCatching { context.contentResolver.getType(source) }.getOrNull()
                )
            }
        }
        is String -> resolveStringSaveSource(context, source)
        else -> error("مصدر وسائط غير مدعوم")
    }

private fun wesalMimeForExtension(ext: String): String? = when (ext.lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "mp4" -> "video/mp4"
    "mov" -> "video/quicktime"
    "m4a" -> "audio/mp4"
    "mp3" -> "audio/mpeg"
    "aac" -> "audio/aac"
    "wav" -> "audio/x-wav"
    "ogg" -> "audio/ogg"
    "pdf" -> "application/pdf"
    "txt" -> "text/plain"
    "zip" -> "application/zip"
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls" -> "application/vnd.ms-excel"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    else -> null
}

private fun wesalExtensionForMime(mime: String): String? = when (mime) {
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    "video/mp4" -> "mp4"
    "video/quicktime" -> "mov"
    "audio/mp4" -> "m4a"
    "audio/mpeg" -> "mp3"
    "audio/aac" -> "aac"
    "audio/x-wav", "audio/wav" -> "wav"
    "audio/ogg" -> "ogg"
    "application/pdf" -> "pdf"
    "text/plain" -> "txt"
    "application/zip" -> "zip"
    else -> null
}

/** Sniffs the real MIME type from the file's magic bytes; null when unrecognised. */
private fun sniffWesalMime(file: File): String? = runCatching {
    val header = ByteArray(16)
    val read = file.inputStream().use { it.read(header) }
    if (read < 4) return null
    fun str(off: Int, len: Int) = String(header, off, len, Charsets.US_ASCII)
    when {
        header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte() -> "image/jpeg"
        header[0] == 0x89.toByte() && str(1, 3) == "PNG" -> "image/png"
        str(0, 4) == "GIF8" -> "image/gif"
        str(0, 4) == "RIFF" && read >= 12 && str(8, 4) == "WEBP" -> "image/webp"
        str(0, 4) == "RIFF" && read >= 12 && str(8, 4) == "WAVE" -> "audio/x-wav"
        read >= 8 && str(4, 4) == "ftyp" -> when {
            read >= 12 && str(8, 4).startsWith("M4A") -> "audio/mp4"
            read >= 12 && str(8, 4).startsWith("qt") -> "video/quicktime"
            else -> "video/mp4"
        }
        // ADTS/AAC first: its 12-bit syncword with zeroed layer bits also
        // satisfies the looser MP3 test below and was saved mislabelled as
        // .mp3 (review MINOR-9).
        header[0] == 0xFF.toByte() && (header[1].toInt() and 0xF6) == 0xF0 -> "audio/aac"
        str(0, 3) == "ID3" ||
            (header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0 &&
                (header[1].toInt() and 0x06) != 0) -> "audio/mpeg"
        str(0, 4) == "OggS" -> "audio/ogg"
        str(0, 4) == "%PDF" -> "application/pdf"
        else -> null
    }
}.getOrNull()

private fun buildSaveDisplayName(
    requested: String?,
    prefix: String,
    mime: String,
    sniffedExt: String
): String {
    val raw = requested?.trim().orEmpty()
    if (raw.isBlank()) return "${prefix}_${System.currentTimeMillis()}.$sniffedExt"
    val sanitized = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    val nameExt = sanitized.substringAfterLast('.', "").lowercase()
    val nameMime = wesalMimeForExtension(nameExt)
    return when {
        nameExt.isBlank() -> "$sanitized.$sniffedExt"
        // The name's extension disagrees with the actual bytes inside the same
        // media family (e.g. a WebP payload named .jpg, an m4a named .mp3):
        // trust the bytes so gallery/player apps open the file correctly.
        nameMime != null && mime != "application/octet-stream" && nameMime != mime &&
            nameMime.substringBefore('/') == mime.substringBefore('/') ->
            sanitized.substringBeforeLast('.') + "." + sniffedExt
        else -> sanitized
    }
}

private fun writeMediaToPublicStorage(
    context: Context,
    src: File,
    kind: WesalMediaKind,
    displayName: String,
    mime: String
) {
    val resolver = context.contentResolver
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val (collection, relativeDir) = when (kind) {
            WesalMediaKind.IMAGE ->
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_PICTURES
            WesalMediaKind.VIDEO ->
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES
            WesalMediaKind.AUDIO ->
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MUSIC
            WesalMediaKind.DOCUMENT ->
                MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativeDir/WESAL")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("تعذر إنشاء ملف الحفظ")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { inp -> inp.copyTo(out) }
            } ?: error("تعذر الكتابة للملف")
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    } else {
        // API 26–28: scoped storage does not exist yet; the public directory is
        // written directly and then media-scanned so gallery/file apps see it.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw StoragePermissionRequiredException(
                "يلزم إذن التخزين لحفظ الملف على هذا الإصدار من أندرويد"
            )
        }
        val baseDir = when (kind) {
            WesalMediaKind.IMAGE -> Environment.DIRECTORY_PICTURES
            WesalMediaKind.VIDEO -> Environment.DIRECTORY_MOVIES
            WesalMediaKind.AUDIO -> Environment.DIRECTORY_MUSIC
            WesalMediaKind.DOCUMENT -> Environment.DIRECTORY_DOWNLOADS
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(baseDir), "WESAL")
            .apply { mkdirs() }
        val dest = File(dir, displayName)
        src.inputStream().use { inp -> dest.outputStream().use { out -> inp.copyTo(out) } }
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
    }
}

/**
 * Saves [source] (File / Uri / URL-or-path String) byte-for-byte into the
 * public WESAL folder for its [kind]. Returns the saved display name.
 */
suspend fun saveMediaToGallery(
    context: Context,
    source: Any,
    kind: WesalMediaKind,
    fileName: String? = null,
    fileNamePrefix: String = "WESAL"
): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        val resolved = resolveSaveSource(context, source)
        try {
            val srcFile = resolved.file
            require(srcFile.exists() && srcFile.length() > 0L) { "ملف الوسائط فارغ" }
            val hintExt = (fileName ?: srcFile.name).substringAfterLast('.', "")
                .lowercase().ifBlank { null }
            val mime = sniffWesalMime(srcFile)
                ?: resolved.declaredMime?.takeIf { it.contains('/') }
                ?: hintExt?.let { wesalMimeForExtension(it) }
                ?: when (kind) {
                    WesalMediaKind.IMAGE -> "image/jpeg"
                    WesalMediaKind.VIDEO -> "video/mp4"
                    WesalMediaKind.AUDIO -> "audio/mpeg"
                    WesalMediaKind.DOCUMENT -> "application/octet-stream"
                }
            val ext = wesalExtensionForMime(mime) ?: hintExt ?: "bin"
            val displayName = buildSaveDisplayName(fileName, fileNamePrefix, mime, ext)
            writeMediaToPublicStorage(context, srcFile, kind, displayName, mime)
            displayName
        } finally {
            if (resolved.deleteAfter) runCatching { resolved.file.delete() }
        }
    }
}

/**
 * Image-only entry point kept for the existing call sites (profile/cover/
 * channel saves). Delegates to [saveMediaToGallery]: same destination
 * (Pictures/WESAL), now byte-exact instead of decode + re-encode.
 */
suspend fun saveImageUrlToGallery(
    context: Context,
    imageUrl: String,
    fileNamePrefix: String = "WESAL_IMG"
): Result<String> = saveMediaToGallery(
    context = context,
    source = imageUrl,
    kind = WesalMediaKind.IMAGE,
    fileNamePrefix = fileNamePrefix
)

/**
 * Save-time permission gate for API ≤ 28: returns a wrapper that runs the
 * action immediately on API 29+ (scoped storage needs no permission) or when
 * WRITE_EXTERNAL_STORAGE is already granted; otherwise it requests the
 * permission and tells the user to tap save again — a save is never faked.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun rememberSavePermissionGate(): (action: () -> Unit) -> Unit {
    val context = LocalContext.current
    val strings = LocalWesalStrings.current
    val permission = rememberPermissionState(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    return { action ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || permission.status.isGranted) {
            action()
        } else {
            permission.launchPermissionRequest()
            Toast.makeText(
                context,
                if (strings.isEnglish) {
                    "Allow storage permission, then tap save again"
                } else {
                    "اسمح بإذن التخزين ثم اضغط حفظ مرة أخرى"
                },
                Toast.LENGTH_LONG
            ).show()
        }
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
