package com.wesal.messenger.core.util

/**
 * No-duplicate-accounts rules (owner ruling, 2026-11-28): one person =
 * one account = one numeric ID. If someone who is already registered
 * registers again by any method, the system must recognize them and NOT
 * mint a new account or ID — they are routed back to their original
 * account instead.
 *
 * Pure decision helpers for the client-side enforceable subset, kept
 * free of Android/Firebase types so the rules are unit-testable. The
 * evidence sources the repository feeds in:
 *  - the Supabase `verified_phones` registry (the sole authority on
 *    which uid a verified number belongs to),
 *  - Firestore claims (`phone_uniqueness` lock owner + a user doc
 *    carrying the number's phoneHash),
 *  - user docs carrying the same email under a different uid.
 *
 * Every check is evidence-based: "no evidence found" (including a
 * failed lookup) NEVER blocks — the backend's own verify-time
 * `phone_in_use` rejection and the transactional numeric-ID claim
 * remain the backstops.
 */
object AccountDedupeLogic {

    /** Shown when a phone number already belongs to an existing account. */
    const val PHONE_ACCOUNT_EXISTS_MESSAGE =
        "الرقم ده مسجل بحساب موجود — سجل دخولك بحسابك الأصلي"

    /** Shown when an email already belongs to an existing account. */
    const val EMAIL_ACCOUNT_EXISTS_MESSAGE =
        "البريد ده مسجل بحساب موجود — سجل دخولك بحسابك الأصلي"

    /** Shown when the number belongs to the account currently signed in. */
    const val PHONE_BOUND_TO_CURRENT_MESSAGE =
        "الرقم ده مسجل بحسابك الحالي"

    /** How a registry binding relates to the session attempting a phone flow. */
    enum class RegistryBinding {
        /** No binding found — nothing recognizes this number yet. */
        PROCEED,

        /** Bound to the very uid of the current session. */
        BOUND_TO_SELF,

        /** Bound to a different uid — an existing account owns this number. */
        BOUND_TO_OTHER
    }

    /**
     * Classifies a registry binding ([boundUid], null/blank when the
     * number is unbound — or when the lookup failed, which callers treat
     * as "no evidence") against the current session's uid. Each call
     * site decides what BOUND_TO_SELF means for its flow: the mandatory
     * gate proceeds (it IS the owner's number), the phone-login flow
     * stops (the account that owns the number is the one already
     * signed in — a fresh anonymous session must not replace it).
     */
    fun registryBinding(boundUid: String?, sessionUid: String?): RegistryBinding {
        val bound = boundUid?.trim().orEmpty()
        if (bound.isBlank()) return RegistryBinding.PROCEED
        val session = sessionUid?.trim().orEmpty()
        return if (session.isNotBlank() && bound == session) {
            RegistryBinding.BOUND_TO_SELF
        } else {
            RegistryBinding.BOUND_TO_OTHER
        }
    }

    /** True for the phone backend's duplicate-number rejection code. */
    fun isPhoneInUseError(errorCode: String?): Boolean =
        errorCode?.trim() == "phone_in_use"

    /** One user-doc email match, reduced to what the duplicate rule needs. */
    data class EmailMatch(val uid: String, val deleted: Boolean)

    /**
     * True when some OTHER live account already carries this email.
     * Deleted-account tombstones do not count: re-registration after a
     * deletion is governed by the banned-identifiers gate, not by this
     * rule, and a tombstone must not masquerade as a live duplicate.
     */
    fun emailBelongsToAnotherAccount(matches: List<EmailMatch>, selfUid: String): Boolean =
        matches.any { it.uid.isNotBlank() && it.uid != selfUid && !it.deleted }
}

/**
 * Thrown when a sign-up / sign-in would create a SECOND account for a
 * person who already has one. An [IllegalStateException] so the
 * existing error mapping surfaces its authored Arabic message verbatim
 * (mapSparkAuthMessage / mapSupabasePhoneError / ErrorCopy all pass
 * authored Arabic IllegalStateException copy through untouched).
 */
class AccountAlreadyExistsException(message: String) : IllegalStateException(message)
