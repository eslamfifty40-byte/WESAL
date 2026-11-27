package com.wesal.messenger

import com.wesal.messenger.core.util.PhoneOtpLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phone-phase tests for the pure OTP input/display helpers used by the
 * phone sign-in option and the mandatory verify-your-number gate. The server
 * owns code generation/expiry/attempts; these cover only the client-side
 * shaping rules that must match it (6 ASCII digits after folding).
 */
class PhoneOtpLogicTest {

    @Test
    fun sanitizeKeepsAsciiDigitsAndCapsAtSix() {
        assertEquals("123456", PhoneOtpLogic.sanitizeOtpInput("123456"))
        assertEquals("123456", PhoneOtpLogic.sanitizeOtpInput("12345678"))
        assertEquals("1234", PhoneOtpLogic.sanitizeOtpInput("12 34"))
        assertEquals("", PhoneOtpLogic.sanitizeOtpInput(""))
    }

    @Test
    fun sanitizeFoldsArabicIndicDigits() {
        // Arabic-Indic (U+0660 block) — Egyptian users type these from Arabic keyboards.
        assertEquals("123456", PhoneOtpLogic.sanitizeOtpInput("١٢٣٤٥٦"))
        // Extended Arabic-Indic (U+06F0 block).
        assertEquals("123456", PhoneOtpLogic.sanitizeOtpInput("۱۲۳۴۵۶"))
    }

    @Test
    fun sanitizeDropsLettersAndSymbols() {
        assertEquals("123", PhoneOtpLogic.sanitizeOtpInput("a1-b2_c3"))
        assertEquals("", PhoneOtpLogic.sanitizeOtpInput("الكود"))
    }

    @Test
    fun completeOtpRequiresExactlySixDigits() {
        assertTrue(PhoneOtpLogic.isCompleteOtp("123456"))
        assertTrue(PhoneOtpLogic.isCompleteOtp("000000"))
        assertFalse(PhoneOtpLogic.isCompleteOtp("12345"))
        assertFalse(PhoneOtpLogic.isCompleteOtp("1234567"))
        assertFalse(PhoneOtpLogic.isCompleteOtp("12345a"))
        assertFalse(PhoneOtpLogic.isCompleteOtp(""))
    }

    @Test
    fun maskKeepsPrefixAndLastThreeDigits() {
        assertEquals("+20 ••• ••• 678", PhoneOtpLogic.maskPhoneForDisplay("+201012345678"))
        assertEquals("+96 ••• ••• 567", PhoneOtpLogic.maskPhoneForDisplay("+966501234567"))
    }

    @Test
    fun maskLeavesShortValuesUntouched() {
        assertEquals("+2010", PhoneOtpLogic.maskPhoneForDisplay("+2010"))
        assertEquals("", PhoneOtpLogic.maskPhoneForDisplay(""))
    }

    @Test
    fun channelNamesAreEgyptianArabic() {
        assertEquals("واتساب", PhoneOtpLogic.channelDisplayNameAr("whatsapp"))
        assertEquals("واتساب", PhoneOtpLogic.channelDisplayNameAr("WhatsApp"))
        assertEquals("رسالة نصية", PhoneOtpLogic.channelDisplayNameAr("sms"))
        assertEquals("رسالة", PhoneOtpLogic.channelDisplayNameAr("carrier-pigeon"))
        assertEquals("رسالة", PhoneOtpLogic.channelDisplayNameAr(""))
    }
}
