package com.wesal.messenger.presentation.components

import android.util.Base64
import android.util.LruCache
import com.wesal.messenger.core.util.WesalFrameCatalog

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
