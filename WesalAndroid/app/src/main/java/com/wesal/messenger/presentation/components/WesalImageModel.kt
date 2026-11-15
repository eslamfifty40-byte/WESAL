package com.wesal.messenger.presentation.components

import android.util.Base64
import android.util.LruCache

private val decodedDataUriCache = object : LruCache<String, ByteArray>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ByteArray): Int = value.size.coerceAtLeast(1)
}

fun wesalImageModel(url: String?): Any? {
    if (url.isNullOrBlank()) return url
    if (url.startsWith("data:image")) {
        decodedDataUriCache.get(url)?.let { return it }
        return try {
            val bytes = Base64.decode(url.substringAfter("base64,"), Base64.DEFAULT)
            if (bytes != null && bytes.isNotEmpty()) {
                decodedDataUriCache.put(url, bytes)
            }
            bytes
        } catch (e: Exception) {
            null
        }
    }
    return url
}
