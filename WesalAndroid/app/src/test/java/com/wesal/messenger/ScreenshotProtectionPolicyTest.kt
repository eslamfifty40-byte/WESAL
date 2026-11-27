package com.wesal.messenger

import com.wesal.messenger.core.util.ScreenshotProtectionPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the round-5 screenshot-protection precedence
 * ([ScreenshotProtectionPolicy.shouldSecureChat]):
 *  - the user toggle governs ordinary chats;
 *  - hidden chats and view-once viewers stay protected even with the toggle OFF.
 */
class ScreenshotProtectionPolicyTest {

    @Test
    fun toggleOn_securesOrdinaryChat() {
        assertTrue(
            ScreenshotProtectionPolicy.shouldSecureChat(
                toggleEnabled = true,
                isHiddenChat = false,
                viewOnceViewerOpen = false
            )
        )
    }

    @Test
    fun toggleOff_leavesOrdinaryChatUnprotected() {
        assertFalse(
            ScreenshotProtectionPolicy.shouldSecureChat(
                toggleEnabled = false,
                isHiddenChat = false,
                viewOnceViewerOpen = false
            )
        )
    }

    @Test
    fun toggleOff_hiddenChatStaysProtected() {
        assertTrue(
            ScreenshotProtectionPolicy.shouldSecureChat(
                toggleEnabled = false,
                isHiddenChat = true,
                viewOnceViewerOpen = false
            )
        )
    }

    @Test
    fun toggleOff_viewOnceViewerStaysProtected() {
        assertTrue(
            ScreenshotProtectionPolicy.shouldSecureChat(
                toggleEnabled = false,
                isHiddenChat = false,
                viewOnceViewerOpen = true
            )
        )
    }

    @Test
    fun toggleOn_hiddenChatAndViewOnceProtected() {
        assertTrue(
            ScreenshotProtectionPolicy.shouldSecureChat(
                toggleEnabled = true,
                isHiddenChat = true,
                viewOnceViewerOpen = true
            )
        )
    }
}
