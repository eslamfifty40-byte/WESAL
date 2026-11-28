package com.wesal.messenger

import com.wesal.messenger.core.util.AccountAlreadyExistsException
import com.wesal.messenger.core.util.AccountDedupeLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No-duplicate-accounts rules (owner ruling, 2026-11-28): one person =
 * one account = one numeric ID. These cover the pure decisions the
 * repository feeds with registry / Firestore evidence — the evidence
 * gathering itself is Firebase-bound and not unit-testable here.
 */
class AccountDedupeLogicTest {

    // ---- registry binding classification ----

    @Test
    fun unboundNumberProceeds() {
        assertEquals(
            AccountDedupeLogic.RegistryBinding.PROCEED,
            AccountDedupeLogic.registryBinding(null, "uidA")
        )
        assertEquals(
            AccountDedupeLogic.RegistryBinding.PROCEED,
            AccountDedupeLogic.registryBinding("", "uidA")
        )
        assertEquals(
            AccountDedupeLogic.RegistryBinding.PROCEED,
            AccountDedupeLogic.registryBinding(null, null)
        )
    }

    @Test
    fun boundToSessionUidIsSelf() {
        assertEquals(
            AccountDedupeLogic.RegistryBinding.BOUND_TO_SELF,
            AccountDedupeLogic.registryBinding("uidA", "uidA")
        )
    }

    @Test
    fun boundToDifferentUidIsOther() {
        assertEquals(
            AccountDedupeLogic.RegistryBinding.BOUND_TO_OTHER,
            AccountDedupeLogic.registryBinding("uidA", "uidB")
        )
        // No active session at all: any binding belongs to an account.
        assertEquals(
            AccountDedupeLogic.RegistryBinding.BOUND_TO_OTHER,
            AccountDedupeLogic.registryBinding("uidA", null)
        )
        assertEquals(
            AccountDedupeLogic.RegistryBinding.BOUND_TO_OTHER,
            AccountDedupeLogic.registryBinding("uidA", "")
        )
    }

    // ---- backend error-code recognition ----

    @Test
    fun phoneInUseDetection() {
        assertTrue(AccountDedupeLogic.isPhoneInUseError("phone_in_use"))
        assertFalse(AccountDedupeLogic.isPhoneInUseError("invalid_code"))
        assertFalse(AccountDedupeLogic.isPhoneInUseError(null))
        assertFalse(AccountDedupeLogic.isPhoneInUseError(""))
    }

    // ---- email duplicate detection ----

    @Test
    fun emailOnAnotherLiveAccountIsDuplicate() {
        val matches = listOf(AccountDedupeLogic.EmailMatch(uid = "uidB", deleted = false))
        assertTrue(AccountDedupeLogic.emailBelongsToAnotherAccount(matches, selfUid = "uidA"))
    }

    @Test
    fun emailOnOwnDocIsNotDuplicate() {
        val matches = listOf(AccountDedupeLogic.EmailMatch(uid = "uidA", deleted = false))
        assertFalse(AccountDedupeLogic.emailBelongsToAnotherAccount(matches, selfUid = "uidA"))
    }

    @Test
    fun emailOnDeletedTombstoneIsNotDuplicate() {
        val matches = listOf(AccountDedupeLogic.EmailMatch(uid = "uidB", deleted = true))
        assertFalse(AccountDedupeLogic.emailBelongsToAnotherAccount(matches, selfUid = "uidA"))
    }

    @Test
    fun noMatchesIsNotDuplicate() {
        assertFalse(
            AccountDedupeLogic.emailBelongsToAnotherAccount(emptyList(), selfUid = "uidA")
        )
    }

    // ---- user-facing copy + exception shape ----

    @Test
    fun accountExistsCopyMatchesOwnerWording() {
        assertEquals(
            "الرقم ده مسجل بحساب موجود — سجل دخولك بحسابك الأصلي",
            AccountDedupeLogic.PHONE_ACCOUNT_EXISTS_MESSAGE
        )
        assertTrue(
            AccountDedupeLogic.EMAIL_ACCOUNT_EXISTS_MESSAGE.contains("مسجل بحساب موجود")
        )
    }

    @Test
    fun accountExistsExceptionIsIllegalStateWithMessage() {
        // The auth error mapping passes authored Arabic
        // IllegalStateException messages through verbatim — the
        // exception type is part of the contract.
        val e = AccountAlreadyExistsException(AccountDedupeLogic.PHONE_ACCOUNT_EXISTS_MESSAGE)
        assertTrue(e is IllegalStateException)
        assertEquals(AccountDedupeLogic.PHONE_ACCOUNT_EXISTS_MESSAGE, e.message)
    }
}
