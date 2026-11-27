package com.wesal.messenger.core.util

/**
 * Pure helpers for the phone-OTP flows (Zavu-delivered codes, phone phase):
 * the sign-in screen's phone option and the mandatory verify-your-number gate.
 * Kept free of Android/Firebase types so the rules are unit-testable — the
 * server (sendPhoneCode / verifyPhoneCode*) owns generation, expiry and the
 * attempt budget; these helpers only shape what the user types and sees.
 */
object PhoneOtpLogic {

    const val OTP_LENGTH = 6

    /** Client mirror of the server's 60s minimum interval between sends. */
    const val RESEND_COOLDOWN_SECONDS = 60

    /**
     * Sanitizes raw OTP input as the user types: Arabic-Indic digits are
     * folded to ASCII (the server does the same before hashing), everything
     * non-numeric is dropped, and the result is capped at [OTP_LENGTH].
     */
    fun sanitizeOtpInput(raw: String): String =
        PhoneUtils.normalizeAsciiDigits(raw).filter { it.isDigit() }.take(OTP_LENGTH)

    /** True only for a complete, ready-to-submit code. */
    fun isCompleteOtp(code: String): Boolean =
        code.length == OTP_LENGTH && code.all { it.isDigit() }

    /**
     * Display-safe masking for the "code sent to …" line: keeps the country
     * prefix and the last three digits, hides the rest
     * (+201012345678 → "+20 ••• ••• 678").
     */
    fun maskPhoneForDisplay(e164: String): String {
        val clean = e164.trim()
        if (clean.length <= 6) return clean
        return "${clean.take(3)} ••• ••• ${clean.takeLast(3)}"
    }

    /** Arabic display name for the channel the server reports back. */
    fun channelDisplayNameAr(channel: String): String = when (channel.trim().lowercase()) {
        "whatsapp" -> "واتساب"
        "sms" -> "رسالة نصية"
        else -> "رسالة"
    }
}
