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

    @Test
    fun frameCatalog_royalCrestsHaveAlignedApertureAndFanRotationDefault() {
        val admin39 = com.wesal.messenger.core.util.WesalFrameCatalog.adminFrames.first { it.id == "admin_39" }
        assertTrue(admin39.defaultAnimated)
        assertEquals("ROTATE_AURA", admin39.defaultAnimationStyle)
        assertTrue(admin39.apertureCenterYFraction < 0.45f)
        assertTrue(admin39.apertureDiameterFraction in 0.42f..0.58f)

        val admin02 = com.wesal.messenger.core.util.WesalFrameCatalog.adminFrames.first { it.id == "admin_02" }
        assertTrue(admin02.defaultAnimated)
        assertEquals("ROTATE_AURA", admin02.defaultAnimationStyle)
        assertTrue(admin02.apertureCenterYFraction < 0.45f)
    }

    @Test
    fun personalThemePresets_resolveDefaultAndNamedKeysSafely() {
        assertEquals(
            com.wesal.messenger.core.theme.WesalPersonalAccentPreset.ROYAL_GOLD,
            com.wesal.messenger.core.theme.WesalPersonalAccentPreset.fromKey("royal_gold")
        )
        assertEquals(
            com.wesal.messenger.core.theme.WesalPersonalAccentPreset.DEFAULT,
            com.wesal.messenger.core.theme.WesalPersonalAccentPreset.fromKey("nonexistent")
        )
        assertEquals(
            com.wesal.messenger.core.theme.WesalPersonalSurfacePreset.MIDNIGHT_NAVY,
            com.wesal.messenger.core.theme.WesalPersonalSurfacePreset.fromKey("midnight_navy")
        )
        assertEquals(
            com.wesal.messenger.core.theme.WesalPersonalSurfacePreset.DEFAULT,
            com.wesal.messenger.core.theme.WesalPersonalSurfacePreset.fromKey(null)
        )
    }

    @Test
    fun phoneUtils_normalizePhoneCandidatesResolvesEgyptAndGulfNumbersAcrossDeviceLocales() {
        val egyptCandidates = PhoneUtils.normalizePhoneCandidates("01012345678", "+1")
        assertTrue(egyptCandidates.contains("+201012345678"))

        val saudiCandidates = PhoneUtils.normalizePhoneCandidates("+966 50 123 4567", "+20")
        assertTrue(saudiCandidates.contains("+966501234567"))

        val arabicSaudiLocal = PhoneUtils.normalizePhoneCandidates("٠٥٠١٢٣٤٥٦٧", "+20")
        assertTrue(arabicSaudiLocal.contains("+966501234567"))
    }

    @Test
    fun expandedThemeAndBuiltinIconCatalogsHaveDistinctEntries() {
        val accents = com.wesal.messenger.core.theme.WesalPersonalAccentPreset.entries
        val surfaces = com.wesal.messenger.core.theme.WesalPersonalSurfacePreset.entries
        val icons = com.wesal.messenger.core.util.WesalFrameCatalog.builtinIcons

        assertTrue(accents.size >= 14)
        assertTrue(surfaces.size >= 8)
        assertTrue(icons.size >= 12)
        assertTrue(icons.all { it.id.isNotBlank() && it.resId != 0 })
        assertEquals(icons.size, icons.map { it.id }.distinct().size)
    }
}

