package com.wesal.messenger.presentation.components

fun wesalImageModel(url: String?): Any? {
    if (url != null && url.startsWith("data:image")) {
        return try {
            android.util.Base64.decode(url.substringAfter("base64,"), android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            null
        }
    }
    return url
}
