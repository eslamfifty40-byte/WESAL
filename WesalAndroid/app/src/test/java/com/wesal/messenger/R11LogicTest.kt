package com.wesal.messenger

import com.wesal.messenger.contacts.ContactsCacheCodec
import com.wesal.messenger.contacts.ContactsCacheSnapshot
import com.wesal.messenger.contacts.DeviceContact
import com.wesal.messenger.contacts.MatchedWesalContact
import com.wesal.messenger.core.util.ModerationLogic
import com.wesal.messenger.core.util.PhoneVerificationMirror
import com.wesal.messenger.domain.model.WesalUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-11 pure-logic tests: the registry→Firestore mirror diff (item 1),
 * the moderation role model (item 9), and the contacts cache codec
 * (addendum 3).
 */
class R11LogicTest {

    // ---------------------------------------------------------------
    // Item 1: PhoneVerificationMirror
    // ---------------------------------------------------------------

    @Test
    fun mirror_stampsAllFields_whenDocIsBlank() {
        // The proven 10001 case: registry row exists, users doc is empty.
        val updates = PhoneVerificationMirror.mirrorUpdates(
            registryPhoneE164 = "+201027503464",
            registryPhoneHash = "a320b51c",
            currentPhoneNumber = "",
            currentPhoneHash = "",
            currentPhoneDigits = "2010275034646", // garbled legacy digits get repaired too
            currentIsPhoneVerified = true
        )
        assertEquals("+201027503464", updates["phoneNumber"])
        assertEquals("a320b51c", updates["phoneHash"])
        assertEquals("201027503464", updates["phoneDigits"])
        // Flag already true -> not rewritten.
        assertFalse(updates.containsKey("isPhoneVerified"))
    }

    @Test
    fun mirror_isNoOp_whenDocAlreadyMatchesRegistry() {
        val updates = PhoneVerificationMirror.mirrorUpdates(
            registryPhoneE164 = "+201122072195",
            registryPhoneHash = "0a3f20cf",
            currentPhoneNumber = "+201122072195",
            currentPhoneHash = "0a3f20cf",
            currentPhoneDigits = "201122072195",
            currentIsPhoneVerified = true
        )
        assertTrue(updates.isEmpty())
    }

    @Test
    fun mirror_neverStampsFromBlankRegistryRow() {
        val updates = PhoneVerificationMirror.mirrorUpdates(
            registryPhoneE164 = "",
            registryPhoneHash = "",
            currentPhoneNumber = "",
            currentPhoneHash = "",
            currentPhoneDigits = "",
            currentIsPhoneVerified = false
        )
        assertTrue(updates.isEmpty())
    }

    @Test
    fun mirror_setsVerifiedFlag_whenRegistryRowExistsButFlagMissing() {
        val updates = PhoneVerificationMirror.mirrorUpdates(
            registryPhoneE164 = "+201000000000",
            registryPhoneHash = "deadbeef",
            currentPhoneNumber = "+201000000000",
            currentPhoneHash = "deadbeef",
            currentPhoneDigits = "201000000000",
            currentIsPhoneVerified = false
        )
        assertEquals(true, updates["isPhoneVerified"])
        assertEquals(1, updates.size)
    }

    @Test
    fun completeMirror_rejectsFabricatedFlagOnlyState() {
        // The Esraa case: flag true, fields empty -> NOT verified.
        assertFalse(PhoneVerificationMirror.isCompleteMirror(true, "", ""))
        assertFalse(PhoneVerificationMirror.isCompleteMirror(true, "+201018863942", ""))
        assertFalse(PhoneVerificationMirror.isCompleteMirror(false, "+201018863942", "abc"))
        assertTrue(PhoneVerificationMirror.isCompleteMirror(true, "+201018863942", "abc"))
    }

    // ---------------------------------------------------------------
    // Item 2: caller-name stamp can never be blank
    // ---------------------------------------------------------------

    @Test
    fun callerStamp_prefersDisplayName_thenHandle_thenFallback() {
        assertEquals(
            "اسلام احمد شحاتة",
            com.wesal.messenger.data.repository.resolveCallerStampName("اسلام احمد شحاتة", "eslamahmed")
        )
        assertEquals(
            "@eslamahmed",
            com.wesal.messenger.data.repository.resolveCallerStampName("", "eslamahmed")
        )
        assertEquals(
            "@eslamahmed",
            com.wesal.messenger.data.repository.resolveCallerStampName(null, "eslamahmed")
        )
        // The proven production failure: both sources blank/null (an
        // unpopulated cache skeleton at call time) must still stamp a name.
        assertEquals(
            "WESAL User",
            com.wesal.messenger.data.repository.resolveCallerStampName("", "")
        )
        assertEquals(
            "WESAL User",
            com.wesal.messenger.data.repository.resolveCallerStampName(null, null)
        )
        assertEquals(
            "WESAL User",
            com.wesal.messenger.data.repository.resolveCallerStampName("   ", "  ")
        )
    }

    // ---------------------------------------------------------------
    // Item 9: ModerationLogic
    // ---------------------------------------------------------------

    @Test
    fun group_adminCanRemoveRegularMember_butNeverCreator() {
        val admins = listOf("admin1", "creator")
        assertTrue(ModerationLogic.canRemoveGroupMember("admin1", "member9", "creator", admins))
        assertFalse(ModerationLogic.canRemoveGroupMember("admin1", "creator", "creator", admins))
        // The creator removing THEMSELF is the leave path — always allowed,
        // like any member's self-removal (dedicated delete stays separate).
        assertTrue(ModerationLogic.canRemoveGroupMember("creator", "creator", "creator", admins))
        // A plain member cannot remove anyone else.
        assertFalse(ModerationLogic.canRemoveGroupMember("member9", "member8", "creator", admins))
        // Leaving (self-removal) is always allowed.
        assertTrue(ModerationLogic.canRemoveGroupMember("member9", "member9", "creator", admins))
    }

    @Test
    fun group_adminRole_changesRequireAdmin_andCreatorIsInherent() {
        val admins = listOf("admin1", "creator")
        assertTrue(ModerationLogic.canSetGroupAdmin("admin1", "member9", "creator", admins))
        assertFalse(ModerationLogic.canSetGroupAdmin("member9", "member8", "creator", admins))
        assertFalse(ModerationLogic.canSetGroupAdmin("admin1", "creator", "creator", admins))
    }

    @Test
    fun channel_moderatorAssignment_isOwnerOnly_butManagementIsShared() {
        assertTrue(ModerationLogic.canSetChannelModerator("owner", "owner"))
        assertFalse(ModerationLogic.canSetChannelModerator("mod1", "owner"))
        assertTrue(ModerationLogic.isChannelManager("mod1", "owner", listOf("mod1")))
        assertTrue(ModerationLogic.isChannelManager("owner", "owner", emptyList()))
        assertFalse(ModerationLogic.isChannelManager("sub1", "owner", listOf("mod1")))
    }

    @Test
    fun channel_selfServiceChange_cannotAppointOrTouchOthers() {
        // Leaving: removing yourself from subscribers (and your mod seat) is fine.
        assertTrue(
            ModerationLogic.isSelfOnlyMembershipChange(
                callerUid = "mod1",
                addedSubscribers = emptySet(),
                removedSubscribers = setOf("mod1"),
                addedModerators = emptySet(),
                removedModerators = setOf("mod1")
            )
        )
        // Self-promotion to moderator is exactly the hole being closed.
        assertFalse(
            ModerationLogic.isSelfOnlyMembershipChange(
                callerUid = "sub1",
                addedSubscribers = emptySet(),
                removedSubscribers = emptySet(),
                addedModerators = setOf("sub1"),
                removedModerators = emptySet()
            )
        )
        // Kicking someone else out of the subscriber list is not self-service.
        assertFalse(
            ModerationLogic.isSelfOnlyMembershipChange(
                callerUid = "sub1",
                addedSubscribers = emptySet(),
                removedSubscribers = setOf("sub2"),
                addedModerators = emptySet(),
                removedModerators = emptySet()
            )
        )
    }

    // ---------------------------------------------------------------
    // Addendum 3: ContactsCacheCodec
    // ---------------------------------------------------------------

    @Test
    fun contactsCache_roundTripsMatchedAndUnmatched() {
        val snapshot = ContactsCacheSnapshot(
            matched = listOf(
                MatchedWesalContact(
                    deviceName = "بابا",
                    user = WesalUser(
                        uid = "uid-1",
                        displayName = "WESALadmin",
                        publicId = "wesaladmin",
                        numericId = "10001",
                        photoUrl = "https://example.com/a.jpg",
                        frameId = "admin_07",
                        isVerified = true,
                        neonEnabled = true,
                        neonColor = "GOLD"
                    )
                )
            ),
            unmatched = listOf(
                DeviceContact(
                    contactId = "42",
                    displayName = "خالتي",
                    rawPhone = "0100 123 4567",
                    e164Phone = "+201001234567",
                    phoneHash = "hash-1",
                    candidateHashes = listOf("hash-1", "hash-2")
                ),
                DeviceContact(
                    contactId = "43",
                    displayName = "No Number Match",
                    rawPhone = "123",
                    e164Phone = null,
                    phoneHash = null,
                    candidateHashes = emptyList()
                )
            ),
            savedAtMs = 1_700_000_000_000L
        )
        val decoded = ContactsCacheCodec.decode(ContactsCacheCodec.encode(snapshot))
        assertNotNull(decoded)
        decoded!!
        assertEquals(1_700_000_000_000L, decoded.savedAtMs)
        assertEquals(1, decoded.matched.size)
        assertEquals("uid-1", decoded.matched[0].user.uid)
        assertEquals("بابا", decoded.matched[0].deviceName)
        assertEquals("admin_07", decoded.matched[0].user.frameId)
        assertEquals("GOLD", decoded.matched[0].user.neonColor)
        assertEquals(2, decoded.unmatched.size)
        assertEquals("+201001234567", decoded.unmatched[0].e164Phone)
        assertEquals(listOf("hash-1", "hash-2"), decoded.unmatched[0].candidateHashes)
        assertNull(decoded.unmatched[1].e164Phone)
        assertNull(decoded.unmatched[1].phoneHash)
    }

    @Test
    fun contactsCache_corruptJson_decodesToNull() {
        assertNull(ContactsCacheCodec.decode("not json"))
        assertNull(ContactsCacheCodec.decode(""))
    }
}
