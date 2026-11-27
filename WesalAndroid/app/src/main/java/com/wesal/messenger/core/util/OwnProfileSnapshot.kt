package com.wesal.messenger.core.util

import com.wesal.messenger.domain.model.CachedOwnProfile
import com.wesal.messenger.domain.model.WesalUser

/**
 * Failover design §3.2/§3.3: pure decisions over the on-device
 * own-profile snapshot ([CachedOwnProfile]).
 *
 * The snapshot is the returning user's entry ticket when Firestore is
 * quota-dead: Firebase Auth success + a snapshot decides entry with no
 * Firestore read at all. It is refreshed on every successful own-doc
 * read/write, so it is at most one session stale.
 *
 * Honesty rules baked in here:
 *  - The snapshot's phone verdict is the COMPLETE-mirror verdict
 *    ([PhoneOtpLogic.isCompleteVerificationMirror] over the doc's flag
 *    + number + hash), computed at write time — never the raw
 *    isPhoneVerified flag, which production docs carry in fabricated
 *    states. A snapshot can therefore only carry a verification that
 *    was genuinely earned (a complete mirror, or — at decision time —
 *    a live [com.wesal.messenger.domain.repository.AuthRepository.isPhoneVerifiedInRegistry]
 *    registry hit, the registry being the authority).
 *  - Ban/deleted state is still enforced from the snapshot: a user
 *    whose last-known state is banned or deleted does not get in on a
 *    snapshot. The ban test is positive-ban-only over the parsed
 *    fields, mirroring UserRepositoryImpl.isBanClearlyActive (a flag
 *    with no PERMANENT type and no readable future expiry never bans
 *    on its own).
 */
object OwnProfileSnapshot {

    enum class EntryDecision {
        /** Profile complete + gate satisfied → Home. */
        ENTER,

        /** Profile not completed → ProfileSetup. */
        ENTER_PROFILE_SETUP,

        /** Profile complete but the mandatory phone gate is still owed → VerifyPhone. */
        ENTER_VERIFY_PHONE,

        /** Last-known state is an active ban → refuse + sign out. */
        REFUSE_BANNED,

        /** Last-known state is deleted → refuse + sign out. */
        REFUSE_DELETED,

        /** No snapshot exists for this uid on this device (cold case, design §3.4). */
        NO_SNAPSHOT
    }

    /**
     * Builds the snapshot from a successfully read/written own doc.
     * [WesalUser.isBanned] already carries the parse-time positive-ban
     * verdict (toWesalUser); the raw type/expiry ride along so the
     * decision below can re-judge expiry against decision-time now.
     */
    fun fromUser(user: WesalUser, nowMs: Long): CachedOwnProfile = CachedOwnProfile(
        uid = user.uid,
        profileCompleted = user.profileCompleted ||
            (user.displayName.isNotBlank() && user.phoneNumber.isNotBlank() &&
                user.birthDate.isNotBlank()),
        phoneHash = user.phoneHash,
        phoneVerifiedMirror = PhoneOtpLogic.isCompleteVerificationMirror(
            user.isPhoneVerified, user.phoneNumber, user.phoneHash
        ),
        isBanned = user.isBanned,
        banType = user.banType,
        banExpiresAt = user.banExpiresAt,
        deletedAt = user.deletedAt,
        deletedPermanent = user.deletedPermanent,
        savedAtMs = nowMs
    )

    /** Positive-ban-only over the snapshot's parsed fields, judged at [nowMs]. */
    fun isBanActive(snapshot: CachedOwnProfile, nowMs: Long): Boolean {
        if (!snapshot.isBanned) return false
        if (snapshot.banType.equals("PERMANENT", ignoreCase = true)) return true
        return snapshot.banExpiresAt > 0L && snapshot.banExpiresAt > nowMs
    }

    /** The gate is satisfied by the snapshot's earned mirror OR a live registry hit (§3.3). */
    fun gateSatisfied(snapshot: CachedOwnProfile, registryVerified: Boolean): Boolean =
        snapshot.phoneVerifiedMirror || registryVerified

    /**
     * The entry decision for a returning user when Firestore cannot be
     * consulted. [registryVerified] is the live verified_phones result
     * when the caller ran the §3.3 entry recheck, false when it did not
     * (or it came back negative).
     */
    fun entryDecision(
        snapshot: CachedOwnProfile?,
        registryVerified: Boolean,
        nowMs: Long
    ): EntryDecision {
        if (snapshot == null) return EntryDecision.NO_SNAPSHOT
        if (snapshot.deletedAt > 0L || snapshot.deletedPermanent) {
            return EntryDecision.REFUSE_DELETED
        }
        if (isBanActive(snapshot, nowMs)) return EntryDecision.REFUSE_BANNED
        if (!snapshot.profileCompleted) return EntryDecision.ENTER_PROFILE_SETUP
        if (!gateSatisfied(snapshot, registryVerified)) {
            return EntryDecision.ENTER_VERIFY_PHONE
        }
        return EntryDecision.ENTER
    }
}
