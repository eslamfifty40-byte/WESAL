package com.wesal.messenger.core.util

import java.security.SecureRandom

object WesalIdUtils {
    private val allowedPattern = Regex("^[a-z0-9_]{4,30}$")
    // Valid numeric IDs span 10000..1000000 (inclusive)
    private val numericIdPattern = Regex("^(?:1000000|[1-9][0-9]{4,5})$")
    private val random = SecureRandom()

    fun normalizeDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                in '\u0660'..'\u0669' -> sb.append(('0'.code + (ch.code - '\u0660'.code)).toChar())
                in '\u06F0'..'\u06F9' -> sb.append(('0'.code + (ch.code - '\u06F0'.code)).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

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
        val cleaned = normalizeDigits(raw?.trim().orEmpty())
        if (!numericIdPattern.matches(cleaned)) return false
        val num = cleaned.toLongOrNull() ?: return false
        return num in 10_000L..1_000_000L
    }

    fun extractNumericIdQuery(rawInput: String): String {
        val trimmed = normalizeDigits(rawInput)
            .trim()
            .removePrefix("#")
            .removePrefix("@")
            .removePrefix("ID:")
            .removePrefix("id:")
            .trim()
        return if (isValidNumericId(trimmed)) trimmed else ""
    }

    fun extractSearchNumericIdQuery(rawInput: String): String {
        val trimmed = normalizeDigits(rawInput)
            .trim()
            .removePrefix("#")
            .removePrefix("@")
            .removePrefix("ID:")
            .removePrefix("id:")
            .trim()
        return if (isValidNumericId(trimmed) || trimmed == "1001") trimmed else ""
    }

    fun generateRandomNumericCandidate(): String {
        // Random ID within [10000, 1000000] inclusive (990,001 possible values)
        return (10_000 + random.nextInt(990_001)).toString()
    }

    fun generateDefault(uid: String): String {
        val prefix = uid.lowercase().filter { it.isLetterOrDigit() }.take(6).ifBlank { "user" }
        val suffix = (1000..9999).random()
        return "wesal_${prefix}_$suffix"
    }
}
