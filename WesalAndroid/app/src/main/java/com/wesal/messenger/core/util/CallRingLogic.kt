package com.wesal.messenger.core.util

/**
 * Round 12 (addendum A): the caller-ringback play/stop decision, extracted
 * pure so it is JVM-testable.
 *
 * The ringback must sound for an OUTGOING call from the moment it is
 * placed, through both honest states («جاري الاتصال» and «يرن»), and stop
 * the moment the call connects — exactly the round-11 addendum-2b rule.
 * It must never sound for an incoming call (the callee side rings with
 * the ringtone instead), never before media permission exists (the
 * session cannot start), and never again once the call has connected
 * (a mid-call network blip is not a new ring — the [hasConnected] latch).
 */
object CallRingLogic {

    /** How long each ringback beep sounds inside one cycle. */
    const val RINGBACK_TONE_DURATION_MS: Int = 1000

    /** Pause between beeps: duration + delay = the «تيت … تيت» cadence. */
    const val RINGBACK_CYCLE_DELAY_MS: Long = 3500L

    fun shouldPlayRingback(
        isIncoming: Boolean,
        mediaGranted: Boolean,
        hasConnected: Boolean,
        peerConnected: Boolean
    ): Boolean = !isIncoming && mediaGranted && !hasConnected && !peerConnected
}
