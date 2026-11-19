package com.wesal.messenger.presentation.components

import android.util.Base64
import android.util.LruCache

private val decodedDataUriCache = object : LruCache<String, ByteArray>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ByteArray): Int = value.size.coerceAtLeast(1)
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
