package com.wesal.messenger

import com.wesal.messenger.core.util.OwnProfileSnapshot
import com.wesal.messenger.domain.model.CachedOwnProfile
import com.wesal.messenger.domain.model.WesalUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failover design §3.2/§3.3: entry decisions over the on-device
 * own-profile snapshot — the matrix that decides whether a returning
 * user enters when Firestore is quota-dead. The snapshot may only
 * carry genuinely-earned state: the phone verdict is the COMPLETE
 * mirror (flag + number + hash), never the raw flag, and ban/deleted
 * state is enforced from the snapshot exactly as the live path
 * enforces it.
 */
class OwnProfileSnapshotTest {

    private val now = 1_800_000_000_000L

    private fun snapshot(
        profileCompleted: Boolean = true,
        phoneVerifiedMirror: Boolean = true,
        phoneHash: String = "hash",
        isBanned: Boolean = false,
        banType: String = "",
        banExpiresAt: Long = 0L,
        deletedAt: Long = 0L,
        deletedPermanent: Boolean = false
    ) = CachedOwnProfile(
        uid = "uidA",
        profileCompleted = profileCompleted,
        phoneHash = phoneHash,
        phoneVerifiedMirror = phoneVerifiedMirror,
        isBanned = isBanned,
        banType = banType,
        banExpiresAt = banExpiresAt,
        deletedAt = deletedAt,
        deletedPermanent = deletedPermanent,
        savedAtMs = now
    )

    private fun decide(
        s: CachedOwnProfile?,
        registryVerified: Boolean = false
    ) = OwnProfileSnapshot.entryDecision(s, registryVerified, now)

    // ---------------------------------------------------------------
    // Entry matrix
    // ---------------------------------------------------------------

    @Test
    fun absentSnapshotIsTheColdCase() {
        assertEquals(OwnProfileSnapshot.EntryDecision.NO_SNAPSHOT, decide(null))
        // Even a registry hit cannot manufacture a snapshot decision —
        // the caller handles NO_SNAPSHOT (design §3.4) separately.
        assertEquals(OwnProfileSnapshot.EntryDecision.NO_SNAPSHOT, decide(null, registryVerified = true))
    }

    @Test
    fun completeAndVerifiedEnters() {
        assertEquals(OwnProfileSnapshot.EntryDecision.ENTER, decide(snapshot()))
    }

    @Test
    fun incompleteProfileGoesToSetup() {
        assertEquals(
            OwnProfileSnapshot.EntryDecision.ENTER_PROFILE_SETUP,
            decide(snapshot(profileCompleted = false))
        )
    }

    @Test
    fun completeButUnverifiedIsHeldAtTheGate() {
        assertEquals(
            OwnProfileSnapshot.EntryDecision.ENTER_VERIFY_PHONE,
            decide(snapshot(phoneVerifiedMirror = false))
        )
    }

    @Test
    fun liveRegistryHitLiftsTheGateOverAStaleMirror() {
        // Verified on another device after the snapshot was written:
        // the registry is the authority and the verdict is ENTER.
        assertEquals(
            OwnProfileSnapshot.EntryDecision.ENTER,
            decide(snapshot(phoneVerifiedMirror = false), registryVerified = true)
        )
    }

    @Test
    fun deletedIsRefusedAndWinsOverEverything() {
        assertEquals(
            OwnProfileSnapshot.EntryDecision.REFUSE_DELETED,
            decide(snapshot(deletedAt = now - 1_000L))
        )
        assertEquals(
            OwnProfileSnapshot.EntryDecision.REFUSE_DELETED,
            decide(snapshot(deletedPermanent = true))
        )
        // Deleted + banned + incomplete: deletion is judged first.
        assertEquals(
            OwnProfileSnapshot.EntryDecision.REFUSE_DELETED,
            decide(
                snapshot(
                    profileCompleted = false,
                    isBanned = true,
                    banType = "PERMANENT",
                    deletedAt = now - 1_000L
                )
            )
        )
    }

    @Test
    fun activeBansAreRefused() {
        assertEquals(
            OwnProfileSnapshot.EntryDecision.REFUSE_BANNED,
            decide(snapshot(isBanned = true, banType = "PERMANENT"))
        )
        assertEquals(
            OwnProfileSnapshot.EntryDecision.REFUSE_BANNED,
            decide(snapshot(isBanned = true, banType = "TEMPORARY", banExpiresAt = now + 60_000L))
        )
        // Ban beats the profile/gate decisions.
        assertEquals(
            OwnProfileSnapshot.EntryDecision.REFUSE_BANNED,
            decide(snapshot(profileCompleted = false, isBanned = true, banType = "PERMANENT"))
        )
    }

    @Test
    fun expiredAndFlagOnlyBansDoNotRefuse() {
        // Temporary ban whose expiry passed since the snapshot was written.
        assertEquals(
            OwnProfileSnapshot.EntryDecision.ENTER,
            decide(snapshot(isBanned = true, banType = "TEMPORARY", banExpiresAt = now - 1_000L))
        )
        // Positive-ban-only (isBanClearlyActive semantics): a bare flag
        // with no PERMANENT type and no future expiry never bans alone.
        assertEquals(
            OwnProfileSnapshot.EntryDecision.ENTER,
            decide(snapshot(isBanned = true, banType = "", banExpiresAt = 0L))
        )
        assertFalse(OwnProfileSnapshot.isBanActive(snapshot(isBanned = false, banType = "PERMANENT"), now))
    }

    // ---------------------------------------------------------------
    // fromUser: only genuinely-earned state enters the snapshot
    // ---------------------------------------------------------------

    private fun user(
        isPhoneVerified: Boolean = false,
        phoneNumber: String = "",
        phoneHash: String = "",
        profileCompleted: Boolean = false,
        displayName: String = "",
        birthDate: String = ""
    ) = WesalUser(
        uid = "uidA",
        displayName = displayName,
        phoneNumber = phoneNumber,
        birthDate = birthDate,
        isPhoneVerified = isPhoneVerified,
        phoneHash = phoneHash,
        profileCompleted = profileCompleted
    )

    @Test
    fun fromUser_completeMirrorIsEarnedVerification() {
        val s = OwnProfileSnapshot.fromUser(
            user(isPhoneVerified = true, phoneNumber = "+201012345678", phoneHash = "abc123"),
            now
        )
        assertTrue(s.phoneVerifiedMirror)
        assertEquals("abc123", s.phoneHash)
    }

    @Test
    fun fromUser_flagOnlyMirrorIsNotEvidence() {
        // The production fabrication (flag set, number + hash blank —
        // the Esraa case) must NOT become a snapshot-carried verdict.
        val s = OwnProfileSnapshot.fromUser(
            user(isPhoneVerified = true, phoneNumber = "", phoneHash = ""),
            now
        )
        assertFalse(s.phoneVerifiedMirror)
        // Flag + number but no hash is equally incomplete.
        val s2 = OwnProfileSnapshot.fromUser(
            user(isPhoneVerified = true, phoneNumber = "+201012345678", phoneHash = ""),
            now
        )
        assertFalse(s2.phoneVerifiedMirror)
        // No flag at all.
        val s3 = OwnProfileSnapshot.fromUser(
            user(isPhoneVerified = false, phoneNumber = "+201012345678", phoneHash = "abc123"),
            now
        )
        assertFalse(s3.phoneVerifiedMirror)
    }

    @Test
    fun fromUser_profileCompletenessMirrorsTheLiveRule() {
        assertTrue(OwnProfileSnapshot.fromUser(user(profileCompleted = true), now).profileCompleted)
        // The fallback derivation the live routing uses: name + number + birth date.
        assertTrue(
            OwnProfileSnapshot.fromUser(
                user(displayName = "Eslam", phoneNumber = "+201012345678", birthDate = "1990-01-01"),
                now
            ).profileCompleted
        )
        assertFalse(
            OwnProfileSnapshot.fromUser(
                user(displayName = "Eslam", phoneNumber = "+201012345678", birthDate = ""),
                now
            ).profileCompleted
        )
    }

    @Test
    fun fromUser_copiesBanAndDeletedFields() {
        val u = WesalUser(
            uid = "uidA",
            isBanned = true,
            banType = "TEMPORARY",
            banExpiresAt = now + 5_000L,
            deletedAt = 0L,
            deletedPermanent = true
        )
        val s = OwnProfileSnapshot.fromUser(u, now)
        assertTrue(s.isBanned)
        assertEquals("TEMPORARY", s.banType)
        assertEquals(now + 5_000L, s.banExpiresAt)
        assertTrue(s.deletedPermanent)
        assertEquals(now, s.savedAtMs)
        assertEquals("uidA", s.uid)
    }
}
