package com.wesal.messenger.presentation.components

import android.graphics.Bitmap
import android.util.Base64
import android.util.LruCache
import coil.size.Size
import coil.transform.Transformation
import com.wesal.messenger.core.util.AlphaBounds
import com.wesal.messenger.core.util.WesalFrameCatalog
import com.wesal.messenger.core.util.analyzeAlphaBounds
import com.wesal.messenger.core.util.isCutoutBounds
import kotlin.math.max
import kotlin.math.roundToInt

private val decodedDataUriCache = object : LruCache<String, ByteArray>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ByteArray): Int = value.size.coerceAtLeast(1)
}

private val cutoutAlphaCache = LruCache<String, Boolean>(512)

fun getCachedCutoutAlpha(cacheKey: String?): Boolean? {
    if (cacheKey.isNullOrBlank()) return null
    return cutoutAlphaCache.get(cacheKey)
}

fun putCachedCutoutAlpha(cacheKey: String?, isCutout: Boolean) {
    if (cacheKey.isNullOrBlank()) return
    cutoutAlphaCache.put(cacheKey, isCutout)
}

// ---------------------------------------------------------------------------
// Round-3 alpha-bounds cache: measured opaque bounding box per avatar URL.
// Negative results are stored as AlphaBounds.NOT_A_CUTOUT so each URL is
// analysed at most once per process. Keyed by wesalImageCacheKey(url), the
// same key the boolean cutout cache above uses.
// ---------------------------------------------------------------------------

private val alphaBoundsCache = LruCache<String, AlphaBounds>(512)

fun getCachedAlphaBounds(cacheKey: String?): AlphaBounds? {
    if (cacheKey.isNullOrBlank()) return null
    return alphaBoundsCache.get(cacheKey)
}

fun putCachedAlphaBounds(cacheKey: String?, bounds: AlphaBounds) {
    if (cacheKey.isNullOrBlank()) return
    alphaBoundsCache.put(cacheKey, bounds)
}

/** Side length of the downsampled bitmap the alpha analysis runs on (<= 16K px reads). */
private const val ALPHA_ANALYSIS_MAX_SIDE = 128

/**
 * Measures [bitmap]'s opaque bounding box on a <=128px downsample and returns the
 * DEFINITIVE answer for the bounds cache: the measured [AlphaBounds] when it is a
 * verified cutout, otherwise [AlphaBounds.NOT_A_CUTOUT]. Fractions are normalised, so
 * the downsample does not distort the result.
 */
fun computeBitmapAlphaBounds(bitmap: Bitmap): AlphaBounds {
    val maxSide = max(bitmap.width, bitmap.height)
    if (maxSide <= 0) return AlphaBounds.NOT_A_CUTOUT
    val small = if (maxSide <= ALPHA_ANALYSIS_MAX_SIDE) {
        bitmap
    } else {
        val scale = ALPHA_ANALYSIS_MAX_SIDE.toFloat() / maxSide
        Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
    }
    return try {
        if (!small.hasAlpha()) {
            AlphaBounds.NOT_A_CUTOUT
        } else {
            val pixels = IntArray(small.width * small.height)
            small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
            val bounds = analyzeAlphaBounds(pixels, small.width, small.height)
            if (isCutoutBounds(bounds)) bounds!! else AlphaBounds.NOT_A_CUTOUT
        }
    } finally {
        if (small !== bitmap) small.recycle()
    }
}

/**
 * Analyses [bitmap] (once) and publishes the verdict into the bounds cache under
 * [cacheKey]. Returns the cached/published value, or null when analysis was impossible
 * (blank key, unreadable bitmap) — callers then simply keep the legacy rendering.
 */
fun analyzeAndPublishBitmapBounds(bitmap: Bitmap, cacheKey: String?): AlphaBounds? {
    if (cacheKey.isNullOrBlank()) return null
    getCachedAlphaBounds(cacheKey)?.let { return it }
    val bounds = runCatching { computeBitmapAlphaBounds(bitmap) }.getOrNull() ?: return null
    putCachedAlphaBounds(cacheKey, bounds)
    return bounds
}

/**
 * Coil transformation that measures the decoded avatar's alpha bounds and publishes
 * them into [alphaBoundsCache], returning the bitmap UNMODIFIED — layout (WesalAvatar)
 * does the visual work with the published numbers.
 *
 * Coil always hands transformations a SOFTWARE bitmap, which removes the old corner
 * probe's failure mode (getPixel() throwing on hardware bitmaps for static frames).
 * Attached to framed avatar photo requests only; Coil skips transformations for
 * animated drawables, so GIF/WebP avatars keep their legacy rendering.
 */
class AlphaBoundsTransformation(private val boundsCacheKey: String?) : Transformation {

    override val cacheKey: String = "wesal_alpha_bounds:v1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (!boundsCacheKey.isNullOrBlank() && getCachedAlphaBounds(boundsCacheKey) == null) {
            analyzeAndPublishBitmapBounds(input, boundsCacheKey)
        }
        return input
    }
}

fun wesalImageCacheKey(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return if (url.startsWith("data:image")) {
        "wesal_b64_${url.length}_${url.hashCode()}"
    } else {
        url
    }
}

fun wesalImageModel(url: String?): Any? {
    if (url.isNullOrBlank()) return url
    if (url.startsWith("builtin_icon:")) {
        val id = url.removePrefix("builtin_icon:").trim()
        return WesalFrameCatalog.builtinIcons.firstOrNull { it.id == id }?.resId
    }
    if (url.startsWith("data:image")) {
        val cacheKey = wesalImageCacheKey(url) ?: return null
        decodedDataUriCache.get(cacheKey)?.let { return it }
        return try {
            val bytes = Base64.decode(url.substringAfter("base64,"), Base64.DEFAULT)
            if (bytes != null && bytes.isNotEmpty()) {
                decodedDataUriCache.put(cacheKey, bytes)
            }
            bytes
        } catch (_: Exception) {
            null
        }
    }
    return url
}
