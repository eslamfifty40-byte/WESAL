package com.wesal.messenger.core.util

/**
 * Round 12 (addendum 3): the acceptance decision for PHONE-HASH contact
 * discovery, extracted as pure logic so the rule is JVM-testable and can
 * never silently regrow a carve-out.
 *
 * The rule (owner rulings 2026-11-27 16:55–16:58, addendum 3a): discovery
 * by phone hash matches ONLY people whose number the viewer already holds
 * in their phone book — WhatsApp semantics — so the ONLY exclusions are:
 * the viewer themself, banned accounts, deleted accounts, and (for the
 * legacy Firestore-stamp source) accounts whose number is not verified.
 *
 * Deliberately NOT inputs to this decision:
 * - `hiddenFromSearch`: that flag hides an account from PUBLIC search by
 *   name/@id. It must not block hash discovery — and concretely it broke
 *   the owner's own pair: `ensureNumericIdAssigned()` stamps
 *   `hiddenFromSearch=true` onto the secondary-admin account (10002) at
 *   every sign-in, so with the flag in this filter 10002 was
 *   undiscoverable by hash for EVERYONE (including 10001, whose phone
 *   book holds the number), while 10001 (no such stamp) stayed visible
 *   to 10002 — the exact asymmetry the owner screenshotted.
 * - `privacySettings.hidePhoneNumber`: display privacy only (round-11
 *   ruling) — same reasoning.
 * - numericId / admin lists: no account is special here; an admin viewer's
 *   phone book matches an admin target exactly like any other pair.
 */
object PhoneDiscoveryLogic {

    fun acceptsTarget(
        isSelf: Boolean,
        requireVerified: Boolean,
        isPhoneVerified: Boolean,
        isBanned: Boolean,
        deletedAt: Long
    ): Boolean {
        if (isSelf) return false
        if (isBanned) return false
        if (deletedAt != 0L) return false
        if (requireVerified && !isPhoneVerified) return false
        return true
    }
}
