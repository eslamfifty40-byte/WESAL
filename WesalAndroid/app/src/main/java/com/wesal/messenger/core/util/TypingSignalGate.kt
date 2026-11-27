package com.wesal.messenger.core.util

/**
 * Scaling pass: typing-signal transition gate (one instance per chat).
 *
 * Before this gate, every keystroke issued a Firestore update of
 * `typingUsers.{uid}` — the value was rewritten even when it was already
 * `true`, so a single typed message cost one write per character. The
 * reader renders only the boolean, so the only writes that can ever
 * matter are the transitions, plus a slow refresh while typing continues
 * (so a single lost `true` write cannot mute the indicator for a whole
 * long message). The stop behaviour is unchanged: the indicator clears
 * when the text empties, when the existing 2.5 s idle timer fires, or
 * when the message is sent.
 */
class TypingSignalGate(
    private val refreshMs: Long = DEFAULT_REFRESH_MS
) {
    enum class Action { NONE, SEND_TRUE, SEND_FALSE }

    private var sentTyping: Boolean = false
    private var lastTrueSentAtMs: Long = 0L

    /** The composer text changed; [isBlank] is the new emptiness. */
    fun onTextChanged(isBlank: Boolean, nowMs: Long): Action {
        if (isBlank) {
            if (!sentTyping) return Action.NONE
            sentTyping = false
            return Action.SEND_FALSE
        }
        if (!sentTyping) {
            sentTyping = true
            lastTrueSentAtMs = nowMs
            return Action.SEND_TRUE
        }
        if (nowMs - lastTrueSentAtMs >= refreshMs) {
            lastTrueSentAtMs = nowMs
            return Action.SEND_TRUE
        }
        return Action.NONE
    }

    /** The idle-stop timer fired (no keystroke for the debounce window). */
    fun onStopTimer(): Action {
        if (!sentTyping) return Action.NONE
        sentTyping = false
        return Action.SEND_FALSE
    }

    /** A message was just sent from the composer. */
    fun onMessageSent(): Action = onStopTimer()

    companion object {
        const val DEFAULT_REFRESH_MS: Long = 10_000L
    }
}
