package com.wesal.messenger

import com.wesal.messenger.authentication.CountryCatalog
import com.wesal.messenger.core.util.PhoneUtils
import com.wesal.messenger.core.util.WesalIdUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WesalLogicUpgradesTest {

    @Test
    fun normalizeDigits_convertsArabicAndPersianNumeralsToAscii() {
        assertEquals("10001", WesalIdUtils.normalizeDigits("١٠٠٠١"))
        assertEquals("10002", WesalIdUtils.normalizeDigits("۱۰٠٠۲"))
        assertEquals("123456", WesalIdUtils.normalizeDigits("1٢3٤56"))
    }

    @Test
    fun isValidNumericId_validatesApprovedRange() {
        assertTrue(WesalIdUtils.isValidNumericId("10000"))
        assertTrue(WesalIdUtils.isValidNumericId("10001"))
        assertTrue(WesalIdUtils.isValidNumericId("١٠٠٠٢"))
        assertTrue(WesalIdUtils.isValidNumericId("1000000"))
        assertFalse(WesalIdUtils.isValidNumericId("9999"))
        assertFalse(WesalIdUtils.isValidNumericId("1000001"))
        assertFalse(WesalIdUtils.isValidNumericId("abc"))
    }

    @Test
    fun extractSearchNumericIdQuery_supportsArabicDigitsAndLegacy1001() {
        assertEquals("10001", WesalIdUtils.extractSearchNumericIdQuery("10001"))
        assertEquals("10002", WesalIdUtils.extractSearchNumericIdQuery("١٠٠٠٢"))
        assertEquals("1001", WesalIdUtils.extractSearchNumericIdQuery("1001"))
        assertEquals("1001", WesalIdUtils.extractSearchNumericIdQuery("@١٠٠١"))
        assertEquals("", WesalIdUtils.extractSearchNumericIdQuery("999"))
        assertEquals("", WesalIdUtils.extractSearchNumericIdQuery("wesal_user"))
    }

    @Test
    fun phoneUtils_normalizesArabicDigitsForEgyptE164AndHashesDeterministically() {
        assertEquals("01012345678", PhoneUtils.normalizeAsciiDigits("٠١٠١٢٣٤٥٦٧٨"))
        val egypt = CountryCatalog.countries.first { it.iso == "EG" }
        val validation = PhoneUtils.validateForCountry(egypt, "٠١٠١٢٣٤٥٦٧٨")
        assertTrue(validation.isValid)
        assertEquals("+201012345678", validation.e164)
        val normalized = PhoneUtils.normalizeToE164("٠١٠١٢٣٤٥٦٧٨", "+20")
        assertEquals("+201012345678", normalized)
        val hashA = PhoneUtils.sha256Hash("+201012345678")
        val hashB = PhoneUtils.sha256Hash(normalized ?: "")
        assertEquals(64, hashA.length)
        assertEquals(hashA, hashB)
    }
}
