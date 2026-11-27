package com.wesal.messenger.core.util

/**
 * Pure decision logic for screenshot / screen-recording protection (round 5,
 * FIX 3 — owner's «حماية المحادثات من تصوير الشاشة» toggle).
 *
 * Kept Android/Compose-free so the precedence is unit-tested on the JVM. The
 * chat destinations (ChatDetailScreen, ChannelDetailScreen) must go through
 * [shouldSecureChat] instead of re-deriving the rule inline.
 *
 * The rule, in order:
 *  1. A hidden chat is ALWAYS protected — hiding a chat is itself a privacy
 *     act, so the user toggle must never be able to expose it.
 *  2. An open view-once media viewer is ALWAYS protected — view-once content
 *     exists precisely so it cannot be captured.
 *  3. Otherwise the user's privacy toggle decides (default ON).
 *
 * While the current-user document has not loaded yet, callers pass
 * `toggleEnabled = true` (the [PrivacySettings] default) so there is never an
 * unprotected flash at chat entry.
 */
object ScreenshotProtectionPolicy {

    fun shouldSecureChat(
        toggleEnabled: Boolean,
        isHiddenChat: Boolean,
        viewOnceViewerOpen: Boolean
    ): Boolean = toggleEnabled || isHiddenChat || viewOnceViewerOpen
}
