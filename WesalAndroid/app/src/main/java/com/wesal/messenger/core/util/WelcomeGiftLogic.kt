package com.wesal.messenger.core.util

/**
 * Round 12 (addendum 11): the automatic ONE-TIME welcome gift for every
 * new registrant — a catalog frame equipped for exactly one hour when the
 * user first reaches the fully-registered state (completed profile +
 * verified phone), announced in-app, and auto-reverted at expiry by the
 * addendum-6 gift machinery (the grant snapshots the current frame into
 * the same `preGift*` fields and sets `giftFrameActive`, so
 * `revertExpiredGiftGrants` restores the snapshot when the hour passes).
 *
 * Snapshot-interplay rule (documented + tested): the welcome gift and an
 * admin gift SHARE the single addendum-6 gift slot. If an admin gift is
 * already active when the welcome grant would fire, the welcome grant
 * does NOT stomp the admin snapshot — it is marked as granted
 * (MARK_ONLY) and never fires again. If the welcome gift is active and
 * an admin grants afterwards, `GiftGrantLogic.shouldSnapshot` already
 * refuses to overwrite the active snapshot, so at the admin grant's
 * expiry the user returns to his true pre-gift frame.
 */
object WelcomeGiftLogic {

    /** Tier-2 «حلقة ذهبية ملكية» — attractive, renders for everyone. */
    const val WELCOME_FRAME_ID = "anim_04"

    const val DURATION_MS: Long = 3_600_000L // exactly 1 hour

    enum class Decision { GRANT, MARK_ONLY, SKIP }

    fun decide(
        welcomeGiftGrantedAt: Long,
        profileComplete: Boolean,
        phoneVerified: Boolean,
        giftFrameActive: Boolean
    ): Decision = when {
        welcomeGiftGrantedAt > 0L -> Decision.SKIP
        !profileComplete || !phoneVerified -> Decision.SKIP
        giftFrameActive -> Decision.MARK_ONLY
        else -> Decision.GRANT
    }

    /** The announcement text (owner's wording), Arabic / English. */
    fun announcement(isArabic: Boolean): String = if (isArabic) {
        "أهلاً بيك في وِصال 🎉 تم توثيق حسابك، وأضفنا لك إطار هدية لتسجيلك معانا — هيفضل معاك لمدة ساعة."
    } else {
        "Welcome to WESAL 🎉 Your account is verified, and we've added a gift frame for registering with us — it stays with you for one hour."
    }
}
