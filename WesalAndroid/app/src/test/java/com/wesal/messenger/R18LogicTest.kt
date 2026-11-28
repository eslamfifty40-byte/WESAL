package com.wesal.messenger

import com.wesal.messenger.core.util.CameraSwitchStateMachine
import com.wesal.messenger.core.util.OfficialChatLockLogic
import com.wesal.messenger.core.util.OfficialStoryRequestBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 18: the official «WESAL Messenger» welcome conversation is
 * one-way for regular users; admins (numericId 10001/10002, or
 * super-admin identity) keep full access; every other chat — including
 * the «تواصل مع إدارة WESAL» support conversation — is never locked.
 */
class R18LogicTest {

    private val officialChatId = "wesal_official_user123"
    // The real support conversation: a deterministic direct chat with
    // the 10001 account (ChatRepositoryImpl.openSupportChatByNumericId).
    private val supportChatId = "dm_adminuid_user123"
    private val directChatId = "dm_alice_bob"

    @Test
    fun officialChat_regularUser_isLocked_byPrefixAlone() {
        // The flag may not have loaded yet; the id prefix alone locks.
        assertTrue(
            OfficialChatLockLogic.isSendLocked(
                chatId = officialChatId,
                isOfficialFlag = false,
                isSuperAdmin = false,
                myNumericId = "55555"
            )
        )
    }

    @Test
    fun officialChat_regularUser_isLocked_byFlagAlone() {
        assertTrue(
            OfficialChatLockLogic.isSendLocked(
                chatId = "some_other_id",
                isOfficialFlag = true,
                isSuperAdmin = false,
                myNumericId = null
            )
        )
    }

    @Test
    fun officialChat_adminNumericIds_keepFullAccess() {
        for (adminId in listOf("10001", "10002")) {
            assertFalse(
                "admin $adminId must not be locked",
                OfficialChatLockLogic.isSendLocked(
                    chatId = officialChatId,
                    isOfficialFlag = true,
                    isSuperAdmin = false,
                    myNumericId = adminId
                )
            )
        }
    }

    @Test
    fun officialChat_superAdminIdentity_keepsFullAccess() {
        assertFalse(
            OfficialChatLockLogic.isSendLocked(
                chatId = officialChatId,
                isOfficialFlag = true,
                isSuperAdmin = true,
                myNumericId = "55555"
            )
        )
    }

    @Test
    fun supportAndDirectChats_areNeverLocked() {
        for (chatId in listOf(supportChatId, directChatId)) {
            assertFalse(
                OfficialChatLockLogic.isSendLocked(
                    chatId = chatId,
                    isOfficialFlag = false,
                    isSuperAdmin = false,
                    myNumericId = "55555"
                )
            )
        }
    }

    @Test
    fun adminNumericId_matchingIsExactAndTrimmed() {
        assertTrue(OfficialChatLockLogic.isAdminNumericId("10001"))
        assertTrue(OfficialChatLockLogic.isAdminNumericId(" 10002 "))
        assertFalse(OfficialChatLockLogic.isAdminNumericId("100010"))
        assertFalse(OfficialChatLockLogic.isAdminNumericId(""))
        assertFalse(OfficialChatLockLogic.isAdminNumericId(null))
    }

    // ---- Task 2: the admin-panel → Home hand-off for official stories ----

    @Test
    fun officialStoryBus_requestThenConsumeExactlyOnce() {
        // Start clean regardless of test order.
        OfficialStoryRequestBus.consume(OfficialStoryRequestBus.pendingRequest.value)
        assertEquals(0, OfficialStoryRequestBus.pendingRequest.value)

        OfficialStoryRequestBus.requestPublish()
        val pending = OfficialStoryRequestBus.pendingRequest.value
        assertTrue(pending > 0)

        // A stale consume (wrong value) must not clear a live request.
        OfficialStoryRequestBus.consume(pending + 99)
        assertEquals(pending, OfficialStoryRequestBus.pendingRequest.value)

        OfficialStoryRequestBus.consume(pending)
        assertEquals(0, OfficialStoryRequestBus.pendingRequest.value)
    }

    // ---- Task 3: two-stage camera-switch watchdog ----

    @Test
    fun watchdog_firstTickExtends_secondTickExpires() {
        val machine = CameraSwitchStateMachine(initialFront = true)
        assertTrue(machine.tryBegin())
        assertEquals(
            CameraSwitchStateMachine.WatchdogVerdict.EXTEND_ONCE,
            machine.onWatchdogTick()
        )
        // The extension must NOT close the flight.
        assertTrue(machine.switchInFlight)
        assertEquals(
            CameraSwitchStateMachine.WatchdogVerdict.EXPIRED,
            machine.onWatchdogTick()
        )
        // Expiry itself doesn't close it either — the caller closes via
        // onTimeout (the round-12 contract is unchanged).
        assertTrue(machine.switchInFlight)
        assertTrue(machine.onTimeout())
        assertFalse(machine.switchInFlight)
    }

    @Test
    fun watchdog_slowHealthySwitchCompletesDuringExtension() {
        // The r17 device scenario: the switch outlasts the first window
        // but completes during the extension — its done callback must be
        // ACCEPTED (previously it was discarded as stale after the
        // watchdog destroyed the mid-switch capturer).
        val machine = CameraSwitchStateMachine(initialFront = true)
        assertTrue(machine.tryBegin())
        assertEquals(
            CameraSwitchStateMachine.WatchdogVerdict.EXTEND_ONCE,
            machine.onWatchdogTick()
        )
        assertTrue(machine.onDone(nowFront = false))
        assertFalse(machine.isFrontCamera)
        assertFalse(machine.switchInFlight)
    }

    @Test
    fun watchdog_extensionAllowanceResetsPerFlight() {
        val machine = CameraSwitchStateMachine(initialFront = true)
        assertTrue(machine.tryBegin())
        assertEquals(
            CameraSwitchStateMachine.WatchdogVerdict.EXTEND_ONCE,
            machine.onWatchdogTick()
        )
        assertTrue(machine.onDone(nowFront = false))
        // A fresh flight gets a fresh extension.
        assertTrue(machine.tryBegin())
        assertEquals(
            CameraSwitchStateMachine.WatchdogVerdict.EXTEND_ONCE,
            machine.onWatchdogTick()
        )
        assertTrue(machine.onError())
    }

    @Test
    fun watchdog_tickWithNoFlightIsExpired() {
        val machine = CameraSwitchStateMachine(initialFront = true)
        assertEquals(
            CameraSwitchStateMachine.WatchdogVerdict.EXPIRED,
            machine.onWatchdogTick()
        )
    }
}
