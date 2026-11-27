package com.wesal.messenger

import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.notifications.ToneChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Channel-id versioning for user-chosen tones. A notification channel's sound is
 * immutable after creation, so each tone URI maps to its own derived channel id.
 * The formula must stay identical to the one WesalFirebaseMessagingService has
 * always used (`<base>_tone_<hashCode>`), or the push path and the listener path
 * would create duplicate channels for the same tone.
 */
class ToneChannelsTest {

    @Test
    fun blankMessageToneUsesBaseChannel() {
        assertEquals(Constants.CHANNEL_MESSAGES, ToneChannels.messageChannelId(""))
        assertEquals(Constants.CHANNEL_MESSAGES, ToneChannels.messageChannelId("   "))
    }

    @Test
    fun customMessageToneDerivesChannelFromUriHash() {
        val uri = "content://media/external/audio/media/42"
        assertEquals(
            "${Constants.CHANNEL_MESSAGES}_tone_${uri.hashCode()}",
            ToneChannels.messageChannelId(uri)
        )
    }

    @Test
    fun messageToneUriIsTrimmedBeforeHashing() {
        val uri = "content://media/external/audio/media/42"
        assertEquals(
            ToneChannels.messageChannelId(uri),
            ToneChannels.messageChannelId("  $uri  ")
        )
    }

    @Test
    fun differentMessageTonesGetDifferentChannels() {
        assertNotEquals(
            ToneChannels.messageChannelId("content://media/external/audio/media/1"),
            ToneChannels.messageChannelId("content://media/external/audio/media/2")
        )
    }

    @Test
    fun blankCallRingtoneUsesBaseAudibleChannel() {
        assertEquals(Constants.CHANNEL_CALLS_AUDIBLE, ToneChannels.audibleCallChannelId(""))
        assertEquals(Constants.CHANNEL_CALLS_AUDIBLE, ToneChannels.audibleCallChannelId("  "))
    }

    @Test
    fun customCallRingtoneDerivesChannelFromUriHash() {
        val uri = "content://com.android.providers.downloads.documents/document/7"
        val id = ToneChannels.audibleCallChannelId(uri)
        assertEquals("${Constants.CHANNEL_CALLS_AUDIBLE}_tone_${uri.hashCode()}", id)
        assertTrue(id.startsWith(Constants.CHANNEL_CALLS_AUDIBLE))
    }

    @Test
    fun sameUriNeverCollidesAcrossMessageAndCallChannels() {
        val uri = "content://media/external/audio/media/42"
        assertNotEquals(
            ToneChannels.messageChannelId(uri),
            ToneChannels.audibleCallChannelId(uri)
        )
    }
}
