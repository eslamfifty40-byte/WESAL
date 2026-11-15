package com.wesal.messenger.core.util

import java.security.SecureRandom

object WesalIdUtils {
    private val allowedPattern = Regex("^[a-z0-9_]{4,30}$")
    private val numericIdPattern = Regex("^[1-9][0-9]{4}$")
    private val random = SecureRandom()

    fun normalize(rawInput: String): String {
        return rawInput.trim()
            .removePrefix("@")
            .lowercase()
            .replace(Regex("[^a-z0-9_]"), "")
    }

    fun isValid(normalizedId: String): Boolean {
        return allowedPattern.matches(normalizedId)
    }

    fun isValidNumericId(raw: String?): Boolean {
        val cleaned = raw?.trim().orEmpty()
        return numericIdPattern.matches(cleaned)
    }

    fun extractNumericIdQuery(rawInput: String): String {
        val trimmed = rawInput.trim()
            .removePrefix("#")
            .removePrefix("@")
            .removePrefix("ID:")
            .removePrefix("id:")
            .trim()
        return if (trimmed.isNotEmpty() && trimmed.all { it.isDigit() } && trimmed.length <= 5) {
            trimmed
        } else {
            ""
        }
    }

    fun generateRandomNumericCandidate(): String {
        return (10000 + random.nextInt(90000)).toString()
    }

    fun generateDefault(uid: String): String {
        val prefix = uid.lowercase().filter { it.isLetterOrDigit() }.take(6).ifBlank { "user" }
        val suffix = (1000..9999).random()
        return "wesal_${prefix}_$suffix"
    }
}
