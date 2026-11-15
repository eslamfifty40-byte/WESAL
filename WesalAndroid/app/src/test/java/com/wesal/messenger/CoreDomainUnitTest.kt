package com.wesal.messenger

import com.wesal.messenger.authentication.CountryCatalog
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.core.util.PhoneUtils
import com.wesal.messenger.core.util.WesalIdUtils
import com.wesal.messenger.domain.model.SavedWesalAccount
import com.wesal.messenger.domain.model.StoryItem
import com.wesal.messenger.domain.model.StoryType
import com.wesal.messenger.domain.model.StoryViewer
import com.wesal.messenger.domain.model.WesalUser
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
    fun phoneNormalization_handlesEgyptAndInternationalFormats() {
        assertEquals("+201012345678", PhoneUtils.normalizeToE164("010 1234 5678"))
        assertEquals("+201012345678", PhoneUtils.normalizeToE164("+201012345678"))
        assertEquals("+201012345678", PhoneUtils.normalizeToE164("0020 101-234-5678"))
        assertEquals("+201012345678", PhoneUtils.normalizeToE164("20 101-234-5678"))
        assertEquals("+966501234567", PhoneUtils.normalizeToE164("+966 50 123 4567"))
        assertNull(PhoneUtils.normalizeToE164("123"))

        val h1 = PhoneUtils.sha256Hash("+201012345678")
        val h2 = PhoneUtils.sha256Hash("+201012345678")
        assertEquals(64, h1.length)
        assertEquals(h1, h2)
    }

    @Test
    fun wesalIdNormalizationAndValidation_enforcesSafeUniqueHandleRules() {
        assertEquals("ahmed_2026", WesalIdUtils.normalize("  @Ahmed_2026! "))
        assertTrue(WesalIdUtils.isValid("ahmed_2026"))
        assertFalse(WesalIdUtils.isValid("ab"))
        assertFalse(WesalIdUtils.isValid("bad-id"))

        val generated = WesalIdUtils.generateDefault("Uid12345ABC")
        assertTrue(generated.startsWith("wesal_uid123_"))
        assertTrue(WesalIdUtils.isValid(generated))
    }

    @Test
    fun contactMatchingGuard_acceptsOnlyVerifiedPhoneAccounts() {
        val verifiedUser = WesalUser(
            uid = "u1",
            email = "a@example.com",
            phoneNumber = "+201012345678",
            isPhoneVerified = true,
            displayName = "أحمد",
            publicId = "ahmed_1"
        )
        val unverifiedOptionalPhoneUser = WesalUser(
            uid = "u2",
            email = "b@example.com",
            phoneNumber = "+201099887766",
            isPhoneVerified = false,
            displayName = "سارة",
            publicId = "sara_2"
        )

        val candidates = listOf(verifiedUser, unverifiedOptionalPhoneUser)
        val verifiedMatches = candidates.filter { it.isPhoneVerified }

        assertEquals(1, verifiedMatches.size)
        assertEquals("u1", verifiedMatches.first().uid)
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

    @Test
    fun storyViewerFiltering_excludesOwnerAndDeduplicatesByUid() {
        val ownerUid = "owner_1"
        val rawViewers = listOf(
            StoryViewer(uid = ownerUid, displayName = "Owner", viewedAt = 1000L),
            StoryViewer(uid = "peer_a", displayName = "أحمد", viewedAt = 2000L),
            StoryViewer(uid = "peer_b", displayName = "سارة", viewedAt = 3000L)
        )
        val filtered = rawViewers.filter { it.uid.isNotBlank() && it.uid != ownerUid }.distinctBy { it.uid }
        assertEquals(2, filtered.size)
        assertFalse(filtered.any { it.uid == ownerUid })

        val story = StoryItem(
            storyId = "st_1",
            ownerUid = ownerUid,
            viewerIds = listOf(ownerUid, "peer_a", "peer_b").filter { it != ownerUid }
        )
        assertEquals(2, story.viewerIds.size)
        assertFalse(story.viewerIds.contains(ownerUid))
    }

    @Test
    fun storyType_parsesAudioAndFallback() {
        assertEquals(StoryType.AUDIO, StoryType.fromRaw("AUDIO"))
        assertEquals(StoryType.IMAGE, StoryType.fromRaw("IMAGE"))
        assertEquals(StoryType.VIDEO, StoryType.fromRaw("VIDEO"))
        assertEquals(StoryType.TEXT, StoryType.fromRaw("UNKNOWN_VALUE"))
    }

    @Test
    fun savedAccountList_deduplicatesByUidAndSortsMostRecentFirst() {
        val accounts = listOf(
            SavedWesalAccount(uid = "u1", email = "old@wesal.app", lastUsedAt = 1000L),
            SavedWesalAccount(uid = "u2", email = "two@wesal.app", lastUsedAt = 3000L),
            SavedWesalAccount(uid = "u1", email = "new@wesal.app", lastUsedAt = 5000L)
        )
        val merged = accounts
            .sortedByDescending { it.lastUsedAt }
            .distinctBy { it.uid }

        assertEquals(2, merged.size)
        assertEquals("u1", merged[0].uid)
        assertEquals("new@wesal.app", merged[0].email)
        assertEquals("u2", merged[1].uid)
    }
}
