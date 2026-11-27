package com.wesal.messenger.presentation.components

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import android.util.LruCache
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.size.Size
import coil.transform.Transformation
import com.wesal.messenger.BuildConfig
import com.wesal.messenger.core.util.AlphaBounds
import com.wesal.messenger.core.util.WesalFrameCatalog
import com.wesal.messenger.core.util.analyzeAlphaBounds
import com.wesal.messenger.core.util.isCutoutBounds
import kotlin.math.max
import kotlin.math.roundToInt

/** DEBUG-only diagnostics for the framed-avatar alpha pipeline (round-4). */
private const val WESAL_ALPHA_TAG = "WesalAlpha"

private fun logAlphaDecision(message: String) {
    if (BuildConfig.DEBUG) Log.d(WESAL_ALPHA_TAG, message)
}

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
//
// Round-4: the cached record also carries the SOURCE bitmap's pixel dimensions,
// published by AlphaBoundsTransformation from its input bitmap. The layout math
// must convert normalised bounds with the source's own aspect — round 3 read the
// DELIVERED drawable's intrinsic size instead, which for the legacy pop-out
// request was the non-square request box (aspect 0.832 for a square source),
// stretching the rendered person ~1.20x vertically.
// ---------------------------------------------------------------------------

/** Measured alpha bounds plus the pixel dimensions of the bitmap they were measured on. */
data class CachedAlphaBounds(
    val bounds: AlphaBounds,
    val sourceWidthPx: Int,
    val sourceHeightPx: Int
)

private val alphaBoundsCache = LruCache<String, CachedAlphaBounds>(512)

fun getCachedAlphaBounds(cacheKey: String?): CachedAlphaBounds? {
    if (cacheKey.isNullOrBlank()) return null
    return alphaBoundsCache.get(cacheKey)
}

fun putCachedAlphaBounds(cacheKey: String?, record: CachedAlphaBounds) {
    if (cacheKey.isNullOrBlank()) return
    alphaBoundsCache.put(cacheKey, record)
}

/** Side length of the downsampled bitmap the alpha analysis runs on (<= 16K px reads). */
private const val ALPHA_ANALYSIS_MAX_SIDE = 128

/**
 * Measures [bitmap]'s opaque bounding box on a <=128px downsample and returns the
 * DEFINITIVE answer for the bounds cache: the measured [AlphaBounds] when it is a
 * verified cutout, otherwise [AlphaBounds.NOT_A_CUTOUT]. Fractions are normalised, so
 * the downsample does not distort the result.
 *
 * Round-4: the downsample is UNFILTERED (filter = false). Bilinear filtering averages
 * edge alpha toward the threshold and grows the measured box by ~1 sample px per side
 * (~+3% area at 128px) — enough to push a tight cutout past the verdict gate.
 */
fun computeBitmapAlphaBounds(bitmap: Bitmap): AlphaBounds {
    val maxSide = max(bitmap.width, bitmap.height)
    if (maxSide <= 0) {
        logAlphaDecision("verdict=NOT_A_CUTOUT reason=empty-bitmap")
        return AlphaBounds.NOT_A_CUTOUT
    }
    val small = if (maxSide <= ALPHA_ANALYSIS_MAX_SIDE) {
        bitmap
    } else {
        val scale = ALPHA_ANALYSIS_MAX_SIDE.toFloat() / maxSide
        Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            false
        )
    }
    return try {
        if (!small.hasAlpha()) {
            logAlphaDecision("verdict=NOT_A_CUTOUT reason=no-alpha-channel src=${bitmap.width}x${bitmap.height}")
            AlphaBounds.NOT_A_CUTOUT
        } else {
            val pixels = IntArray(small.width * small.height)
            small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
            val bounds = analyzeAlphaBounds(pixels, small.width, small.height)
            if (isCutoutBounds(bounds)) {
                val b = bounds!!
                logAlphaDecision(
                    "verdict=CUTOUT bounds=[${b.left},${b.top},${b.right},${b.bottom}] " +
                        "transparentFraction=${b.transparentFraction} src=${bitmap.width}x${bitmap.height}"
                )
                b
            } else {
                logAlphaDecision(
                    "verdict=NOT_A_CUTOUT reason=gate-rejected bounds=" +
                        (bounds?.let { "[${it.left},${it.top},${it.right},${it.bottom}] tf=${it.transparentFraction}" }
                            ?: "null(fully-transparent)") +
                        " src=${bitmap.width}x${bitmap.height}"
                )
                AlphaBounds.NOT_A_CUTOUT
            }
        }
    } finally {
        if (small !== bitmap) small.recycle()
    }
}

/**
 * Analyses [bitmap] (once) and publishes the verdict into the bounds cache under
 * [cacheKey], together with [bitmap]'s own pixel dimensions (the source aspect the
 * layout math must use). Returns the cached/published record, or null when analysis
 * was impossible (blank key, unreadable bitmap) — callers then simply keep the legacy
 * rendering.
 */
fun analyzeAndPublishBitmapBounds(bitmap: Bitmap, cacheKey: String?): CachedAlphaBounds? {
    if (cacheKey.isNullOrBlank()) return null
    getCachedAlphaBounds(cacheKey)?.let { return it }
    val bounds = runCatching { computeBitmapAlphaBounds(bitmap) }.getOrNull()
    if (bounds == null) {
        logAlphaDecision("verdict=NONE reason=analysis-failed key=$cacheKey")
        return null
    }
    val record = CachedAlphaBounds(
        bounds = bounds,
        sourceWidthPx = bitmap.width.coerceAtLeast(1),
        sourceHeightPx = bitmap.height.coerceAtLeast(1)
    )
    putCachedAlphaBounds(cacheKey, record)
    return record
}

/**
 * Coil transformation that measures the decoded avatar's alpha bounds and publishes
 * them into [alphaBoundsCache] — layout (WesalAvatar) does the placement work with
 * the published numbers.
 *
 * Round 6 (item 10c): for a VERIFIED cutout the returned bitmap is additionally
 * edge-decontaminated (see [bleedCutoutEdgeColors]). ML Kit-style mattes leave
 * semi-transparent edge pixels whose RGB still holds the ORIGINAL (often dark)
 * background colour; composited over the bright frame glow disc — and resampled
 * by Coil — those pixels read as a harsh dark halo around the person. Bleeding
 * the nearest opaque colours outward into the matte (alpha untouched) removes
 * the fringe. Opaque photos are returned UNMODIFIED, exactly as before.
 *
 * Coil always hands transformations a SOFTWARE bitmap, which removes the old corner
 * probe's failure mode (getPixel() throwing on hardware bitmaps for static frames).
 * Attached to framed avatar photo requests only; Coil skips transformations for
 * animated drawables, so GIF/WebP avatars keep their legacy rendering.
 */
class AlphaBoundsTransformation(private val boundsCacheKey: String?) : Transformation {

    // v2 (round-4): the published record now also carries the input bitmap's pixel
    // dimensions (source aspect for the layout), and the analysis/verdict changed
    // (unfiltered downsample, per-axis margin gate).
    // v3 (round-6): verified cutouts are returned edge-decontaminated (halo fix) —
    // the version bump invalidates bitmaps cached by earlier rounds.
    override val cacheKey: String = "wesal_alpha_bounds:v3"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (boundsCacheKey.isNullOrBlank()) return input
        if (getCachedAlphaBounds(boundsCacheKey) == null) {
            // `input` is the decoded source bitmap: its dimensions ARE the source
            // aspect the layout must use (round-4 aspect fix — see alphaBoundsCache).
            analyzeAndPublishBitmapBounds(input, boundsCacheKey)
        }
        val verdict = getCachedAlphaBounds(boundsCacheKey)?.bounds
        return if (isCutoutBounds(verdict)) {
            runCatching { bleedCutoutEdgeColors(input) }.getOrNull() ?: input
        } else {
            input
        }
    }
}

/** Largest bitmap side the edge-bleed runs on (avatar requests are far smaller). */
private const val EDGE_BLEED_MAX_SIDE = 1024

/** Propagation passes: how many pixels deep opaque colour bleeds into the matte. */
private const val EDGE_BLEED_PASSES = 6

/**
 * Round 6 (item 10c): colour-decontaminates a cutout's matte edge.
 *
 * For every pixel with alpha < 255 that touches a MORE opaque neighbour, the
 * pixel's RGB is replaced by the alpha-weighted average of its more-opaque
 * neighbours' RGB (alpha itself is never changed, so the silhouette and the
 * measured bounds are identical). Repeated for [EDGE_BLEED_PASSES] passes so
 * colour propagates a few pixels into the feathered edge — including into
 * fully transparent pixels next to the body, whose (usually black) RGB would
 * otherwise bleed INTO the edge during Coil's resampling and re-create the
 * dark fringe one pixel further in.
 *
 * Returns a NEW bitmap; the input is left untouched. Bitmaps without an alpha
 * channel, fully opaque bitmaps, and oversize bitmaps are returned as-is.
 */
private fun bleedCutoutEdgeColors(input: Bitmap): Bitmap {
    if (!input.hasAlpha()) return input
    val w = input.width
    val h = input.height
    if (w <= 0 || h <= 0 || max(w, h) > EDGE_BLEED_MAX_SIDE) return input
    val total = w * h
    val px = IntArray(total)
    input.getPixels(px, 0, w, 0, 0, w, h)
    var hasMatte = false
    for (p in px) {
        if ((p ushr 24) < 255) {
            hasMatte = true
            break
        }
    }
    if (!hasMatte) return input

    fun alphaAt(a: IntArray, i: Int): Int = (a[i] ushr 24) and 0xFF

    var current = px
    for (pass in 0 until EDGE_BLEED_PASSES) {
        val next = current.copyOf()
        var changed = false
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                val a = alphaAt(current, i)
                if (a >= 255) continue
                // Alpha-weighted RGB average of the neighbours that are MORE
                // opaque than this pixel (colour flows from body into matte,
                // never the other way).
                var rSum = 0L
                var gSum = 0L
                var bSum = 0L
                var wSum = 0L
                fun neighbour(j: Int) {
                    val na = alphaAt(current, j)
                    if (na > a) {
                        val c = current[j]
                        rSum += ((c shr 16) and 0xFF).toLong() * na
                        gSum += ((c shr 8) and 0xFF).toLong() * na
                        bSum += (c and 0xFF).toLong() * na
                        wSum += na
                    }
                }
                if (x > 0) neighbour(i - 1)
                if (x < w - 1) neighbour(i + 1)
                if (y > 0) neighbour(i - w)
                if (y < h - 1) neighbour(i + w)
                if (wSum > 0L) {
                    val r = (rSum / wSum).toInt()
                    val g = (gSum / wSum).toInt()
                    val b = (bSum / wSum).toInt()
                    val blended = (a shl 24) or (r shl 16) or (g shl 8) or b
                    if (blended != next[i]) {
                        next[i] = blended
                        changed = true
                    }
                }
            }
        }
        current = next
        if (!changed) break
    }

    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(current, 0, w, 0, 0, w, h)
    return out
}

/**
 * Coil loader WITHOUT the animated-image decoders, for rendering a frame STILL.
 *
 * WesalApplication's loader registers ImageDecoderDecoder/GifDecoder, so a GIF or
 * animated-WebP frame image animates intrinsically wherever Coil draws it — the
 * per-frame `animated` flag and the master switch could not stop it (round-5 gap).
 * This loader deliberately registers NO animated decoder: only Coil's default
 * components resolve the request, and BitmapFactoryDecoder returns the FIRST frame
 * as a static bitmap — exactly the behaviour WesalApplication's own comment records
 * for the pre-GIF-decoder builds. WesalAvatar (and the admin pickers) route frame
 * images through this loader whenever the frame's effective animation is OFF, and
 * through the normal loader when it is ON, so the admin's on/off choice is honoured
 * even for intrinsically animated files.
 *
 * Separate caches (small memory cache, own disk directory) keep still decodes from
 * colliding with the main loader's animated drawables under the same cache keys.
 */
object WesalStillImageLoader {

    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader =
        instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

    private fun build(appContext: Context): ImageLoader =
        ImageLoader.Builder(appContext)
            .memoryCache {
                MemoryCache.Builder(appContext)
                    .maxSizePercent(0.06)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(appContext.cacheDir.resolve("image_cache_still"))
                    .maxSizeBytes(25L * 1024L * 1024L)
                    .build()
            }
            .crossfade(false)
            .respectCacheHeaders(false)
            .build()
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
