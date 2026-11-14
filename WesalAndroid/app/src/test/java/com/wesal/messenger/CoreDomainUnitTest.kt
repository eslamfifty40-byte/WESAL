package com.wesal.messenger

import com.wesal.messenger.authentication.CountryCatalog
import com.wesal.messenger.core.util.DateTimeUtils
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CoreDomainUnitTest {

    @Test
    fun defaultCountry_isEgyptPlus20() {
        assertEquals("EG", CountryCatalog.defaultEgypt.iso)
        assertEquals("+20", CountryCatalog.defaultEgypt.dialCode)
        assertEquals("مصر", CountryCatalog.defaultEgypt.nameAr)
        assertEquals(CountryCatalog.defaultEgypt, CountryCatalog.countries.first())
    }

    @Test
    fun durationFormatting_isAccurate() {
        assertEquals("00:00", DateTimeUtils.formatDurationSeconds(0))
        assertEquals("01:24", DateTimeUtils.formatDurationSeconds(84))
        assertEquals("02:05", DateTimeUtils.formatDurationMillis(125_000L))
    }

    @Test
    fun ecdhAesGcmRoundTrip_succeedsAndDetectsTampering() {
        val kpg = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val alice = kpg.generateKeyPair()
        val bob = kpg.generateKeyPair()

        val aliceAgreement = KeyAgreement.getInstance("ECDH").apply {
            init(alice.private)
            doPhase(bob.public, true)
        }
        val bobAgreement = KeyAgreement.getInstance("ECDH").apply {
            init(bob.private)
            doPhase(alice.public, true)
        }

        val aliceSecret = aliceAgreement.generateSecret()
        val bobSecret = bobAgreement.generateSecret()
        assertArrayEquals(aliceSecret, bobSecret)

        val aesKey = SecretKeySpec(aliceSecret.copyOf(32), "AES")
        val iv = ByteArray(12) { 7 }
        val plaintext = "مرحباً بك في وصال WESAL".toByteArray(Charsets.UTF_8)

        val enc = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        }.doFinal(plaintext)

        val dec = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(bobSecret.copyOf(32), "AES"), GCMParameterSpec(128, iv))
        }.doFinal(enc)

        assertEquals("مرحباً بك في وصال WESAL", String(dec, Charsets.UTF_8))
    }
}
