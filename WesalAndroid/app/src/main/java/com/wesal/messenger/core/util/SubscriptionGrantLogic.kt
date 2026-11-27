package com.wesal.messenger.core.util

/**
 * Round 13 (Addendum E/E2): the pure mapping from an APPROVED
 * subscription/verification request to the grant that must land on the
 * target user's document.
 *
 * Device-proven break this supports fixing: the request lifecycle worked
 * end-to-end (submit → «قيد المراجعة» → approve → «تمت الموافقة» + the
 * admin reply on the card) but the approved badge/frame/glow never
 * appeared on the account. The approval therefore applies this plan
 * FIRST (verification grant, then frame grant — any failure aborts the
 * review and the request stays PENDING with a visible error) and flips
 * the request status LAST, so "approved" can never again be a lie.
 *
 * The plan normalizes the request's raw fields through the same
 * [VerificationSealType.fromKey] / [NeonColorPreset.fromKey] fallbacks
 * the submit side and the grant writers use, so the values the plan
 * carries are exactly the values the profile renderers read back
 * (`verificationType`, `verificationGlowColor`, `frameId` on the user
 * doc — the same field names the writers store and `toWesalUser` maps).
 */
object SubscriptionGrantLogic {

    /** What approving a request must write to the target user doc. */
    data class GrantPlan(
        /** Normalized badge/seal key for `verificationType`. */
        val sealKey: String,
        /** Normalized glow key for `verificationGlowColor`. */
        val glowKey: String,
        /** Frame id for `frameId`, or null when the request asked for none. */
        val frameId: String?,
        /** Permanent grants stamp expiry 0 (the review flow is permanent-only). */
        val expiresAt: Long = 0L
    ) {
        val applyFrame: Boolean get() = !frameId.isNullOrBlank()
    }

    /**
     * Builds the grant plan from a request's stored fields
     * (`desiredSeal`, `desiredGlowColor`, `desiredFrameId`). Unknown
     * seal/glow keys fall back exactly like the writers do
     * ([VerificationSealType.fromKey] → its default seal,
     * [NeonColorPreset.fromKey] → NONE), and a blank frame id means
     * "no frame grant" rather than "clear the frame".
     */
    fun planFor(
        desiredSeal: String?,
        desiredGlowColor: String?,
        desiredFrameId: String?
    ): GrantPlan = GrantPlan(
        sealKey = VerificationSealType.fromKey(desiredSeal.orEmpty()).key,
        glowKey = NeonColorPreset.fromKey(desiredGlowColor.orEmpty()).key,
        frameId = desiredFrameId?.trim()?.takeIf { it.isNotBlank() }
    )
}
