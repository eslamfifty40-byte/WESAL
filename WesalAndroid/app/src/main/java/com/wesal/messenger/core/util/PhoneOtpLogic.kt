package com.wesal.messenger.core.util

/**
 * Pure helpers for the phone-OTP flows (Zavu-delivered codes, phone phase):
 * the sign-in screen's phone option and the mandatory verify-your-number gate.
 * Kept free of Android/Firebase types so the rules are unit-testable — the
 * server (the Supabase phone backend: send-phone-code / verify-phone-code)
 * owns generation, expiry and the attempt budget; these helpers only shape
 * what the user types and sees, and map the backend's stable error codes to
 * the Arabic messages the phone screens show.
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

    /**
     * Gate evidence rule (round 6, owner-doc finding): a Firestore user-doc
     * mirror counts as proof of phone verification ONLY when it is COMPLETE —
     * the verified flag is set AND the doc carries the verified number AND
     * its canonical phoneHash. A mirror with isPhoneVerified=true but a blank
     * phoneNumber and/or blank phoneHash exists in production (privacy-hide
     * writers blank the public number+hash while the flag/private backup keep
     * the verified state, and legacy writers stamped the flag from mere
     * number presence): trusting the flag alone let such an account sail
     * through the mandatory gate with no registry row and no discoverable
     * hash. An incomplete mirror is NOT evidence — the caller must fall back
     * to the Supabase registry (the authority) or hold the account at the
     * gate.
     */
    fun isCompleteVerificationMirror(
        isPhoneVerified: Boolean,
        phoneNumber: String?,
        phoneHash: String?
    ): Boolean =
        isPhoneVerified && !phoneNumber.isNullOrBlank() && !phoneHash.isNullOrBlank()

    /**
     * The number the verify flow pre-fills for an account: the stored
     * phoneNumber when present, otherwise the legacy digits-only field
     * (phoneDigits, e.g. "201122072195") some older writers left on the doc
     * while phoneNumber stayed blank. Returns the canonical E.164 form, or
     * null when neither field yields a valid number.
     */
    fun verificationPrefillE164(phoneNumber: String?, phoneDigits: String?): String? {
        val raw = phoneNumber?.takeIf { it.isNotBlank() }
            ?: phoneDigits?.takeIf { it.isNotBlank() }
            ?: return null
        return PhoneUtils.normalizeToE164(raw)
    }

    /** Arabic display name for the channel the server reports back. */
    fun channelDisplayNameAr(channel: String): String = when (channel.trim().lowercase()) {
        "whatsapp" -> "واتساب"
        // The deployed send-phone-code returns its literal Zavu channel name
        // "sms_oneway" (v2) — map it to the same label as plain SMS.
        "sms", "sms_oneway" -> "رسالة نصية"
        else -> "رسالة"
    }

    /**
     * Maps a Supabase phone-backend failure to the Arabic message the phone
     * screens show. The backend returns the same Arabic strings as the old
     * Firebase implementation for user-facing validation errors, and those
     * are surfaced verbatim when present; the stable [errorCode] decides
     * whenever the server message is English (the 401/403 pair) or the
     * wording is a product requirement — delivery_failed must read as a
     * clear "the code could not be sent right now" state, never a spinner
     * that runs forever (the Zavu sender is configured owner-side and may
     * legitimately be unavailable).
     */
    fun arabicMessageForErrorCode(errorCode: String?, serverMessage: String?, fallback: String): String {
        when (errorCode?.trim()) {
            "delivery_failed" -> return "تعذر إرسال الكود حالياً — حاول مرة أخرى بعد شوية"
            "rate_limited_resend" -> return "استنى دقيقة قبل ما تطلب كود جديد"
            "rate_limited_hourly" -> return "وصلت للحد الأقصى لطلب الأكواد للرقم ده — حاول مرة أخرى بعد شوية"
            "code_expired" -> return "الكود انتهت صلاحيته — اطلب كود جديد"
            "invalid_code" -> return "الكود غير صحيح — راجعه وحاول تاني"
            "too_many_attempts" -> return "محاولات كتير غلط — اطلب كود جديد وحاول تاني"
            "code_not_found" -> return "مفيش كود تحقق للرقم ده — اطلب كود جديد"
            "phone_in_use" -> return "الرقم ده مربوط بحساب تاني"
            "invalid_phone" -> return "رقم الهاتف غير صحيح — اكتبه بالصيغة الدولية"
            "invalid_code_format" -> return "الكود لازم يكون ٦ أرقام"
            "session_mismatch" -> return "الكود ده اتبعت من جلسة تانية — اطلب كود جديد من هنا"
            "unauthenticated" -> return "انتهت الجلسة — سجّل الدخول من جديد وحاول مرة أخرى"
            "server_error" -> return "خطأ في الخادم — حاول مرة أخرى"
        }
        val message = serverMessage?.trim().orEmpty()
        // Arabic block U+0600..U+06FF: an Arabic server message is user-ready.
        if (message.isNotBlank() && message.any { it in '\u0600'..'\u06FF' }) return message
        return fallback
    }
}
