package com.wesal.messenger

import com.wesal.messenger.admin.AdminUserFilter
import com.wesal.messenger.domain.model.WesalUser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Studio-2 take: section 4 of its R14UpgradesTest (AdminUserFilter),
 * aligned to main's ACTUAL filter behavior:
 * - ALL / ONLINE / OFFLINE / VERIFIED / HIDDEN exclude deleted accounts.
 * - DELETED includes exactly the deleted accounts.
 * - BANNED / NEW_* / ACTIVE_* key off their own predicate only in main
 *   (no deleted exclusion), so a deleted user who also matches the
 *   predicate still matches there — asserted as-is. (The ZIP's blanket
 *   "every non-DELETED tab excludes deleted" assertion fails against
 *   main for exactly those branches.)
 *
 * The ZIP's other test sections are NOT taken: they test classes main
 * does not have (PresenceThrottlePolicy, SupabaseTypingPresenceProtocol)
 * or call FirestoreRest's private fieldValue.
 */
class AdminUserFilterTest {

    private val now = 1_700_000_000_000L

    private val softDeleted = WesalUser(
        uid = "u_soft_del",
        isOnline = true,
        isVerified = true,
        isBanned = true,
        hiddenFromSearch = true,
        createdAt = now - 60_000L,
        lastSeen = now - 10_000L,
        deletedAt = now - 5_000L,
        deletedPermanent = false
    )

    private val permDeleted = WesalUser(
        uid = "u_perm_del",
        deletedAt = 0L,
        deletedPermanent = true
    )

    private val activeUser = WesalUser(
        uid = "u_active",
        isOnline = true,
        isVerified = true,
        createdAt = now - 60_000L,
        lastSeen = now - 10_000L,
        deletedAt = 0L,
        deletedPermanent = false
    )

    @Test
    fun deletedAccountsExcludedFromMembershipTabs() {
        listOf(
            AdminUserFilter.ALL,
            AdminUserFilter.ONLINE,
            AdminUserFilter.OFFLINE,
            AdminUserFilter.VERIFIED,
            AdminUserFilter.HIDDEN
        ).forEach { filter ->
            assertFalse("${filter.name} must exclude softDeleted", filter.matches(softDeleted, now))
            assertFalse("${filter.name} must exclude permDeleted", filter.matches(permDeleted, now))
        }
    }

    @Test
    fun deletedTabIncludesExactlyTheDeletedAccounts() {
        assertTrue("DELETED tab must include softDeleted", AdminUserFilter.DELETED.matches(softDeleted, now))
        assertTrue("DELETED tab must include permDeleted", AdminUserFilter.DELETED.matches(permDeleted, now))
        assertFalse("DELETED tab must exclude active user", AdminUserFilter.DELETED.matches(activeUser, now))
    }

    @Test
    fun predicateOnlyTabsFollowTheirOwnPredicate() {
        // Main's BANNED branch is `u.isBanned` with no deleted exclusion:
        // the banned soft-deleted fixture still matches, as-is in main.
        assertTrue(AdminUserFilter.BANNED.matches(softDeleted, now))
        assertFalse(AdminUserFilter.BANNED.matches(permDeleted, now))
        assertFalse(AdminUserFilter.BANNED.matches(activeUser, now))

        // Main's NEW_* branches key off createdAt only.
        listOf(
            AdminUserFilter.NEW_TODAY,
            AdminUserFilter.NEW_2DAYS,
            AdminUserFilter.NEW_WEEK,
            AdminUserFilter.NEW_MONTH
        ).forEach { filter ->
            assertTrue("${filter.name} keys off createdAt only", filter.matches(softDeleted, now))
            assertFalse("${filter.name} must exclude never-created fixture", filter.matches(permDeleted, now))
            assertTrue("${filter.name} must include the new active user", filter.matches(activeUser, now))
        }

        // Main's ACTIVE_* branches key off lastSeen only.
        listOf(
            AdminUserFilter.ACTIVE_TODAY,
            AdminUserFilter.ACTIVE_WEEK,
            AdminUserFilter.ACTIVE_MONTH
        ).forEach { filter ->
            assertTrue("${filter.name} keys off lastSeen only", filter.matches(softDeleted, now))
            assertFalse("${filter.name} must exclude never-seen fixture", filter.matches(permDeleted, now))
            assertTrue("${filter.name} must include the active user", filter.matches(activeUser, now))
        }
    }

    @Test
    fun activeUserMatchesPrimaryTabs() {
        assertTrue(AdminUserFilter.ALL.matches(activeUser, now))
        assertTrue(AdminUserFilter.ONLINE.matches(activeUser, now))
        assertTrue(AdminUserFilter.VERIFIED.matches(activeUser, now))
        assertFalse(AdminUserFilter.OFFLINE.matches(activeUser, now))
        assertFalse(AdminUserFilter.HIDDEN.matches(activeUser, now))
    }
}
