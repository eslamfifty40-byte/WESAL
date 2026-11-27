package com.wesal.messenger.core.util

/**
 * Referral invitee gift (owner's idea, approved 2026-11-27): a brand-new
 * account that registered THROUGH someone's invite (attribution stamped
 * as `referredByUid`, or the locally persisted invite id still present —
 * see [isLocalInviteEvidence]) receives an upgraded welcome package at
 * the same once-ever trigger as the standard welcome gift:
 *
 * - the welcome frame (anim_04) for 24 hours instead of 1 hour, and
 * - a timed gift verification badge for 24 hours, through the exact
 *   addendum-6 gift-badge machinery (snapshot + `giftBadgeActive` +
 *   read-side/write-back auto-revert at expiry).
 *
 * The invite message (WesalShareUtils.buildInviteMessage) promises
 * precisely this package — frame + verification badge for 24 hours — and
 * nothing more; the non-referred welcome gift is unchanged (1-hour frame
 * only, [WelcomeGiftLogic] copy verbatim).
 *
 * Slot discipline mirrors the welcome gift's MARK_ONLY precedent, per
 * slot: if an admin gift is already active on the frame slot, the frame
 * component is not granted (the admin snapshot is never stomped); same
 * for the badge slot. If both slots are busy the package is superseded —
 * the once-ever stamp is written and nothing is announced.
 *
 * Pure decisions only (JVM-tested); UserRepositoryImpl executes them.
 */
object ReferralGiftLogic {

    const val DURATION_MS: Long = 86_400_000L // exactly 24 hours

    /** What a welcome-gift grant actually delivered; drives the announcement. */
    enum class Outcome { NONE, STANDARD_FRAME, REFERRAL_FRAME, REFERRAL_BADGE, REFERRAL_PACKAGE }

    data class Plan(
        val grantFrame: Boolean,
        val grantBadge: Boolean,
        val frameDurationMs: Long,
        /** MARK_ONLY: write the once-ever stamp, grant nothing, announce nothing. */
        val stampOnly: Boolean,
        val outcome: Outcome
    ) {
        companion object {
            val NONE = Plan(
                grantFrame = false, grantBadge = false,
                frameDurationMs = 0L, stampOnly = false, outcome = Outcome.NONE
            )
            val STAMP_ONLY = Plan(
                grantFrame = false, grantBadge = false,
                frameDurationMs = 0L, stampOnly = true, outcome = Outcome.NONE
            )
        }
    }

    fun plan(
        welcomeGiftGrantedAt: Long,
        profileComplete: Boolean,
        phoneVerified: Boolean,
        referred: Boolean,
        giftFrameActive: Boolean,
        giftBadgeActive: Boolean
    ): Plan {
        if (!referred) {
            // The standard welcome gift, decided by its own logic so the
            // two paths can never drift apart (1-hour frame, MARK_ONLY
            // when an admin frame gift is live, once-ever stamp).
            return when (
                WelcomeGiftLogic.decide(
                    welcomeGiftGrantedAt = welcomeGiftGrantedAt,
                    profileComplete = profileComplete,
                    phoneVerified = phoneVerified,
                    giftFrameActive = giftFrameActive
                )
            ) {
                WelcomeGiftLogic.Decision.SKIP -> Plan.NONE
                WelcomeGiftLogic.Decision.MARK_ONLY -> Plan.STAMP_ONLY
                WelcomeGiftLogic.Decision.GRANT -> Plan(
                    grantFrame = true,
                    grantBadge = false,
                    frameDurationMs = WelcomeGiftLogic.DURATION_MS,
                    stampOnly = false,
                    outcome = Outcome.STANDARD_FRAME
                )
            }
        }
        if (welcomeGiftGrantedAt > 0L || !profileComplete || !phoneVerified) return Plan.NONE
        val frame = !giftFrameActive
        val badge = !giftBadgeActive
        return when {
            frame && badge -> Plan(
                grantFrame = true, grantBadge = true,
                frameDurationMs = DURATION_MS, stampOnly = false,
                outcome = Outcome.REFERRAL_PACKAGE
            )
            frame -> Plan(
                grantFrame = true, grantBadge = false,
                frameDurationMs = DURATION_MS, stampOnly = false,
                outcome = Outcome.REFERRAL_FRAME
            )
            badge -> Plan(
                grantFrame = false, grantBadge = true,
                frameDurationMs = 0L, stampOnly = false,
                outcome = Outcome.REFERRAL_BADGE
            )
            else -> Plan.STAMP_ONLY
        }
    }

    /**
     * True when a locally persisted invite id (the deep-link pending id,
     * or the hand-typed «مين دعاك؟» manual id) names SOMEONE ELSE — i.e.
     * this registration demonstrably arrived through an invite even if
     * the fire-and-forget attribution stamp has not landed on the user
     * doc yet (both stored ids are cleared only when the stamp lands).
     * Mirrors the self-referral rejection in
     * AuthRepositoryImpl.stampReferralAttribution (which compares the
     * resolved inviter uid): here the cheap local forms are compared —
     * the raw uid, the numeric id, and the normalized @handle.
     */
    fun isLocalInviteEvidence(
        inviteId: String?,
        ownUid: String,
        ownPublicId: String,
        ownNumericId: String
    ): Boolean {
        val raw = inviteId?.trim().orEmpty()
        if (raw.isBlank()) return false
        if (raw == ownUid) return false
        val numeric = WesalIdUtils.extractNumericIdQuery(raw)
        if (numeric.isNotBlank() && numeric == ownNumericId.trim()) return false
        val handle = WesalIdUtils.normalize(raw)
        if (handle.isNotBlank() && handle == WesalIdUtils.normalize(ownPublicId)) return false
        return true
    }

    /**
     * Announcement for the referred outcomes (the actual package granted,
     * 24 hours). Returns null for the standard path — its copy is owned
     * by [WelcomeGiftLogic.announcement] and stays byte-identical.
     */
    fun announcement(outcome: Outcome, isArabic: Boolean): String? = when (outcome) {
        Outcome.REFERRAL_PACKAGE -> if (isArabic) {
            "أهلاً بيك في وِصال 🎉 تم توثيق حسابك، ولأنك سجّلت عن طريق دعوة، أضفنا لك إطار هدية وعلامة توثيق لمدة ٢٤ ساعة."
        } else {
            "Welcome to WESAL 🎉 Your account is verified, and because you joined through an invite, we've added a gift frame and a verification badge for 24 hours."
        }
        Outcome.REFERRAL_FRAME -> if (isArabic) {
            "أهلاً بيك في وِصال 🎉 تم توثيق حسابك، ولأنك سجّلت عن طريق دعوة، أضفنا لك إطار هدية لمدة ٢٤ ساعة."
        } else {
            "Welcome to WESAL 🎉 Your account is verified, and because you joined through an invite, we've added a gift frame for 24 hours."
        }
        Outcome.REFERRAL_BADGE -> if (isArabic) {
            "أهلاً بيك في وِصال 🎉 تم توثيق حسابك، ولأنك سجّلت عن طريق دعوة، أضفنا لك علامة توثيق هدية لمدة ٢٤ ساعة."
        } else {
            "Welcome to WESAL 🎉 Your account is verified, and because you joined through an invite, we've added a gift verification badge for 24 hours."
        }
        else -> null
    }
}
