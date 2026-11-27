package com.wesal.messenger

import com.wesal.messenger.contacts.DeviceContact
import com.wesal.messenger.contacts.deviceContactKey
import com.wesal.messenger.core.util.WesalShareUtils
import com.wesal.messenger.core.util.resolveBulkWhatsAppInvitesEnabled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bulk WhatsApp invites (round 5): the admin kill-switch default rule, the wa.me
 * target normalization used to open a SPECIFIC contact's chat, the personalization
 * guarantee of the invite message, and the selection-key stability.
 */
class BulkInviteLogicTest {

    @Test
    fun bulkInvitesFlag_enabledWhenAbsent() {
        // Missing doc / missing field / listener error all arrive as null → enabled.
        assertTrue(resolveBulkWhatsAppInvitesEnabled(null))
    }

    @Test
    fun bulkInvitesFlag_respectsStoredValue() {
        assertTrue(resolveBulkWhatsAppInvitesEnabled(true))
        assertFalse(resolveBulkWhatsAppInvitesEnabled(false))
    }

    @Test
    fun inviteTargetDigits_stripsPlusAndSpaces() {
        assertEquals("201012345678", WesalShareUtils.inviteTargetDigits("+20 101 234 5678"))
        assertEquals("201012345678", WesalShareUtils.inviteTargetDigits("+201012345678"))
        assertEquals("", WesalShareUtils.inviteTargetDigits(null))
        assertEquals("", WesalShareUtils.inviteTargetDigits(""))
    }

    @Test
    fun isInvitablePhone_requiresPlausibleInternationalNumber() {
        assertTrue(WesalShareUtils.isInvitablePhone("+201012345678"))
        assertFalse(WesalShareUtils.isInvitablePhone(null))
        assertFalse(WesalShareUtils.isInvitablePhone(""))
        assertFalse(WesalShareUtils.isInvitablePhone("0123"))
        assertFalse(WesalShareUtils.isInvitablePhone("abc"))
    }

    @Test
    fun inviteMessage_greetsTheContactByHisOwnSavedName() {
        val msg = WesalShareUtils.buildInviteMessage(
            myWesalId = "eslamahmed",
            targetContactName = "أمل",
            isEnglish = false,
            senderName = "إسلام"
        )
        assertTrue(msg.startsWith("مرحبًا أمل 👋"))
        assertTrue(msg.contains("معرفي: eslamahmed"))
        assertFalse(msg.contains("/join", ignoreCase = true))
    }

    @Test
    fun deviceContactKey_usesTheListRowKeyPair() {
        val contact = DeviceContact(
            contactId = "42",
            displayName = "أمل",
            rawPhone = "01012345678",
            e164Phone = "+201012345678",
            phoneHash = null
        )
        // Same pair the LazyColumn rows are keyed by: "invite_${contactId}_${rawPhone}".
        assertEquals("42_01012345678", deviceContactKey(contact))
    }
}
