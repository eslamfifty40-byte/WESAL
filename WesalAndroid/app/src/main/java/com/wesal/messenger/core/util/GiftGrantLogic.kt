package com.wesal.messenger.core.util

/**
 * Round 12 (addendum 6): pure decisions for admin GIFT grants (timed
 * badge / frame) — snapshot-on-grant, restore-on-expiry, and the
 * recipient notification text. JVM-tested; the repository/ViewModel
 * layers only execute what these functions decide.
 *
 * Snapshot contract (fields on the recipient's user doc):
 * - Badge gift: `giftBadgeActive` (marker) + `preGiftIsVerified`,
 *   `preGiftVerificationType`, `preGiftVerificationGlowColor`,
 *   `preGiftVerificationGlowEnabled`, `preGiftVerificationGlowIntensity`,
 *   `preGiftVerificationExpiresAt` — the recipient's badge state captured
 *   at the moment the FIRST timed grant lands (a second timed grant while
 *   one is active must NOT overwrite the snapshot with the gift itself).
 * - Frame gift: `giftFrameActive` (marker) + `preGiftFrameId`,
 *   `preGiftFrameExpiresAt` — same rule.
 * A permanent grant or an explicit revoke/removal clears the marker: the
 * gift is over and nothing may be restored later.
 */
object GiftGrantLogic {

    /** Snapshot only for a genuine, timed grant that starts a NEW gift. */
    fun shouldSnapshot(giftAlreadyActive: Boolean, timed: Boolean, isGrant: Boolean): Boolean =
        isGrant && timed && !giftAlreadyActive

    /** A marker must be cleared on permanent grants and on revoke/removal. */
    fun shouldClearSnapshot(timed: Boolean, isGrant: Boolean): Boolean =
        !isGrant || !timed

    enum class ExpiryAction { NONE, RESTORE }

    /**
     * At [nowMs]: an active gift whose expiry has passed must RESTORE its
     * snapshot (which may itself be "nothing" — restoring then means
     * clearing to none, exactly the pre-gift state). Anything else is
     * untouched.
     */
    fun expiryAction(giftActive: Boolean, giftExpiresAt: Long, nowMs: Long): ExpiryAction =
        if (giftActive && giftExpiresAt > 0L && nowMs > giftExpiresAt) ExpiryAction.RESTORE
        else ExpiryAction.NONE

    fun badgeGiftExpired(giftBadgeActive: Boolean, verificationExpiresAt: Long, nowMs: Long): Boolean =
        expiryAction(giftBadgeActive, verificationExpiresAt, nowMs) == ExpiryAction.RESTORE

    fun frameGiftExpired(giftFrameActive: Boolean, frameExpiresAt: Long, nowMs: Long): Boolean =
        expiryAction(giftFrameActive, frameExpiresAt, nowMs) == ExpiryAction.RESTORE

    /**
     * The recipient notification (one message per grant), composed from
     * the actual grant contents. [badgeLabel] e.g. «توثيق (الشارة
     * الزرقاء)», [frameName] the granted frame's display name,
     * [durationLabel] the admin panel's human duration («ساعة»,
     * «يوم»…), [note] the admin's optional note («هديه تسجيل»).
     * Permanent grants read «بشكل دائم» instead of a duration.
     */
    fun giftMessageText(
        badgeLabel: String?,
        frameName: String?,
        durationLabel: String?,
        permanent: Boolean,
        note: String?
    ): String {
        val parts = mutableListOf<String>()
        if (!badgeLabel.isNullOrBlank()) parts += badgeLabel.trim()
        if (!frameName.isNullOrBlank()) parts += "إطار ${frameName.trim()}"
        val what = parts.joinToString(" + ").ifBlank { "هدية" }
        val duration = when {
            permanent -> "بشكل دائم"
            !durationLabel.isNullOrBlank() -> "لمدة ${durationLabel.trim()}"
            else -> ""
        }
        return buildString {
            append("🎁 وصلك هدية من إدارة WESAL: ")
            append(what)
            if (duration.isNotBlank()) append(" ").append(duration)
            append(". استمتع بيها!")
            if (!note.isNullOrBlank()) append(" (${note.trim()})")
        }
    }
}

/**
 * Round 12 (addendum 4): the automatic welcome shown in the official
 * account (10001) support chat. Pure text composition, JVM-tested.
 */
object OfficialChatWelcome {

    /** Fallback when the user's display name is blank. */
    const val FALLBACK_NAME = "صديقنا"

    fun textFor(displayName: String?): String {
        val name = displayName?.trim().orEmpty().ifBlank { FALLBACK_NAME }
        return "أهلاً بيك يا $name 🌹 اكتب مشكلتك هنا وسيتم التواصل معك قريباً."
    }
}
