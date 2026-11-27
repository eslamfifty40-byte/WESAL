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

    /**
     * Spec §4.5 range for user-facing numeric-ID SEARCH: 10000..100000.
     * Deliberately narrower than [isValidNumericId]: IDs were historically allocated up to
     * 1,000,000, and the exact resolvers (findExactUserByNumericId / findUserByPublicIdOrUid,
     * invite links, QR) must keep resolving those accounts — so only the search surface is
     * restricted to the spec range, never the resolvers.
     */
    fun isSpecSearchNumericId(raw: String?): Boolean {
        val cleaned = normalizeDigits(raw?.trim().orEmpty())
        if (cleaned.isBlank() || !cleaned.all { it.isDigit() }) return false
        val num = cleaned.toLongOrNull() ?: return false
        return num in 10_000L..100_000L
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

    /**
     * Spec §4.4 (round 4, P2-1): the canonical key stored on the user doc as
     * `nameSearchKey` and used for the Firestore prefix query in
     * UserRepositoryImpl.searchUsers (orderBy("nameSearchKey").startAt(q).endAt(q+"\uf8ff")).
     * It deliberately reuses [normalizeSearchText], which already performs every
     * required folding: lowercase, Arabic diacritic/tashkeel stripping
     * (U+064B..U+065F, U+0670, tatweel U+0640), ة→ه, ى→ي, أ/إ/آ/ٱ→ا (plus ؤ→و, ئ→ي),
     * Arabic-Indic digit normalization and whitespace collapsing. Writers (profile
     * create/update) and the search query MUST both go through this single function
     * so stored keys and query prefixes always agree.
     */
    fun normalizeNameSearchKey(raw: String): String = normalizeSearchText(raw)

    fun normalizeSearchText(raw: String): String {
        val digitsFixed = normalizeDigits(raw.trim()).lowercase()
        val sb = StringBuilder(digitsFixed.length)
        var lastWasSpace = false
        for (ch in digitsFixed) {
            when {
                ch in '\u064B'..'\u065F' || ch == '\u0670' || ch == '\u0640' -> Unit
                ch == 'أ' || ch == 'إ' || ch == 'آ' || ch == 'ٱ' -> {
                    sb.append('ا')
                    lastWasSpace = false
                }
                ch == 'ى' -> {
                    sb.append('ي')
                    lastWasSpace = false
                }
                ch == 'ة' -> {
                    sb.append('ه')
                    lastWasSpace = false
                }
                ch == 'ؤ' -> {
                    sb.append('و')
                    lastWasSpace = false
                }
                ch == 'ئ' -> {
                    sb.append('ي')
                    lastWasSpace = false
                }
                ch.isWhitespace() -> {
                    if (!lastWasSpace && sb.isNotEmpty()) {
                        sb.append(' ')
                        lastWasSpace = true
                    }
                }
                else -> {
                    sb.append(ch)
                    lastWasSpace = false
                }
            }
        }
        return sb.toString().trim()
    }
}

