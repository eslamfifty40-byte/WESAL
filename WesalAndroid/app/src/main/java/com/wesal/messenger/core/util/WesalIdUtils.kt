package com.wesal.messenger.core.util

object WesalIdUtils {
    private val allowedPattern = Regex("^[a-z0-9_]{4,30}$")

    fun normalize(rawInput: String): String {
        return rawInput.trim()
            .removePrefix("@")
            .lowercase()
            .replace(Regex("[^a-z0-9_]"), "")
    }

    fun isValid(normalizedId: String): Boolean {
        return allowedPattern.matches(normalizedId)
    }

    fun generateDefault(uid: String): String {
        val prefix = uid.lowercase().filter { it.isLetterOrDigit() }.take(6).ifBlank { "user" }
        val suffix = (1000..9999).random()
        return "wesal_${prefix}_$suffix"
    }
}
