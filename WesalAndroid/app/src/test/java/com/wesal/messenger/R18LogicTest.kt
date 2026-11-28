package com.wesal.messenger

import com.wesal.messenger.core.util.OfficialChatLockLogic
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
}
