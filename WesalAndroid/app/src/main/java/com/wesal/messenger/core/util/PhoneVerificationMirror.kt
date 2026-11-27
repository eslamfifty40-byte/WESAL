package com.wesal.messenger.core.util

/**
 * Round 11 (item 1): the Firestore users-doc MIRROR of a Supabase
 * `verified_phones` registry row — the diff-only patch the registry-keyed
 * self-heal applies.
 *
 * Why this exists (PROVEN on live data, 2026-11-27): account 10001 held a
 * registry row (verified via OTP) while its users doc carried
 * phoneNumber=""/phoneHash="" — so contacts discovery, which matches on the
 * doc's phoneHash, could never see the account. A console data repair fixed
 * that one doc; this logic is the permanent in-app version: whenever a
 * registry row exists for the signed-in uid, the doc must carry EXACTLY the
 * registry's values, and only the fields that differ are patched (no
 * full-doc rewrite). When NO registry row exists, nothing here ever stamps
 * from local fields — the registry is the only verification authority, and
 * a bare `isPhoneVerified=true` flag with an incomplete mirror is NOT a
 * verification (see [isCompleteMirror]).
 *
 * Pure logic (no Android/Firebase types) — unit-tested on the JVM.
 */
object PhoneVerificationMirror {

    /** Field names on the users doc, kept in one place so writers can't drift. */
    const val FIELD_PHONE_NUMBER = "phoneNumber"
    const val FIELD_PHONE_HASH = "phoneHash"
    const val FIELD_PHONE_DIGITS = "phoneDigits"
    const val FIELD_IS_PHONE_VERIFIED = "isPhoneVerified"

    /** Digits-only form of an E.164 number ("+201027503464" → "201027503464"). */
    fun digitsOf(e164: String): String = e164.filter { it.isDigit() }

    /**
     * The complete-mirror test the gate and every verification consumer must
     * use instead of the bare flag: a verification counts only when the doc
     * carries a non-blank number AND a non-blank hash AND the flag. A legacy
     * fabricated state (flag true, fields empty — the Esraa case) fails this
     * test and is treated as UNVERIFIED.
     */
    fun isCompleteMirror(
        isPhoneVerified: Boolean,
        phoneNumber: String?,
        phoneHash: String?
    ): Boolean =
        isPhoneVerified && !phoneNumber.isNullOrBlank() && !phoneHash.isNullOrBlank()

    /**
     * Computes the users-doc patch that makes the mirror equal the registry
     * row. Returns an EMPTY map when the mirror already matches (the heal is
     * then a true no-op) or when the registry row itself is unusable (blank
     * E.164/hash — never stamp blanks).
     *
     * [stampsPhoneNumber] is false for hide-phone accounts (owner ruling,
     * round 11 addendum 2a): the public doc keeps phoneNumber/phoneDigits
     * blanked — the number itself stays private in the private/info backup —
     * while phoneHash is STILL stamped, because the hash is what phonebook
     * discovery matches on and it reveals nothing to anyone who doesn't
     * already hold the number.
     */
    fun mirrorUpdates(
        registryPhoneE164: String,
        registryPhoneHash: String,
        currentPhoneNumber: String?,
        currentPhoneHash: String?,
        currentPhoneDigits: String?,
        currentIsPhoneVerified: Boolean,
        stampsPhoneNumber: Boolean = true
    ): Map<String, Any> {
        val e164 = registryPhoneE164.trim()
        val hash = registryPhoneHash.trim()
        if (e164.isBlank() || hash.isBlank()) return emptyMap()
        val digits = digitsOf(e164)
        val out = LinkedHashMap<String, Any>(4)
        if (stampsPhoneNumber) {
            if (currentPhoneNumber.orEmpty() != e164) out[FIELD_PHONE_NUMBER] = e164
            if (currentPhoneDigits.orEmpty() != digits) out[FIELD_PHONE_DIGITS] = digits
        }
        if (currentPhoneHash.orEmpty() != hash) out[FIELD_PHONE_HASH] = hash
        if (!currentIsPhoneVerified) out[FIELD_IS_PHONE_VERIFIED] = true
        return out
    }
}
