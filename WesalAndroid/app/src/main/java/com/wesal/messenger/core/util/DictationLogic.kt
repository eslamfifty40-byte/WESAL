package com.wesal.messenger.core.util

/**
 * Round 13 (Addenda I.3 + I2): pure decisions for CONTINUOUS voice
 * dictation (Android SpeechRecognizer → text field).
 *
 * The pre-r13 listener was single-shot: the first pause ended the
 * session (`onResults`/`onError` cleared the dictating flag), a thinking
 * pause surfaced a "not recognized" toast, and on devices whose
 * recognition service is broken the tap failed silently. The continuous
 * contract implemented by the composer:
 *
 *  - While dictation mode is ON, end-of-speech and transient errors
 *    RESTART listening automatically; recognized segments keep
 *    appending to the field (see [appendSegment]).
 *  - NO silence timeout ends the session — [isPauseError] errors are
 *    exactly the ones a pause produces, and they restart quietly.
 *  - Dictation stops ONLY when the user taps the mic/stop control, or
 *    when a hard failure makes continuing pointless: a permission /
 *    language error, or [MAX_FAST_FAILURES] restarts in a row that each
 *    died almost immediately (the recognition service is unavailable —
 *    the UI then says so honestly in Arabic instead of looping).
 *
 * Error codes mirror android.speech.SpeechRecognizer constants (they
 * are compile-time ints; mirrored here so this object stays unit
 * testable without the framework).
 */
object DictationLogic {

    const val ERROR_NETWORK_TIMEOUT = 1
    const val ERROR_NETWORK = 2
    const val ERROR_AUDIO = 3
    const val ERROR_SERVER = 4
    const val ERROR_CLIENT = 5
    const val ERROR_SPEECH_TIMEOUT = 6
    const val ERROR_NO_MATCH = 7
    const val ERROR_RECOGNIZER_BUSY = 8
    const val ERROR_INSUFFICIENT_PERMISSIONS = 9
    const val ERROR_TOO_MANY_REQUESTS = 10
    const val ERROR_SERVER_DISCONNECTED = 11
    const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    const val ERROR_LANGUAGE_UNAVAILABLE = 13

    /** A restart that dies within this window counts as a fast failure. */
    const val FAST_FAILURE_WINDOW_MS = 700L

    /** Consecutive fast failures after which dictation gives up honestly. */
    const val MAX_FAST_FAILURES = 3

    /** Quiet delay before an automatic restart (lets the engine release). */
    const val RESTART_DELAY_MS = 250L

    /** Errors produced by a mere pause or a transient engine hiccup. */
    fun isPauseOrTransientError(error: Int): Boolean = when (error) {
        ERROR_SPEECH_TIMEOUT, ERROR_NO_MATCH, ERROR_NETWORK, ERROR_NETWORK_TIMEOUT,
        ERROR_AUDIO, ERROR_SERVER, ERROR_SERVER_DISCONNECTED, ERROR_RECOGNIZER_BUSY,
        ERROR_CLIENT, ERROR_TOO_MANY_REQUESTS -> true
        else -> false
    }

    /** Errors that must stop dictation immediately with an honest notice. */
    fun isHardError(error: Int): Boolean = when (error) {
        ERROR_INSUFFICIENT_PERMISSIONS,
        ERROR_LANGUAGE_NOT_SUPPORTED,
        ERROR_LANGUAGE_UNAVAILABLE -> true
        else -> false
    }

    /**
     * May listening auto-restart after [error]? Only while the user
     * still wants dictation ([dictationActive]), the error is not a hard
     * stop, and the fast-failure streak has not proven the service dead.
     */
    fun shouldRestart(
        dictationActive: Boolean,
        error: Int,
        consecutiveFastFailures: Int
    ): Boolean =
        dictationActive &&
            !isHardError(error) &&
            isPauseOrTransientError(error) &&
            consecutiveFastFailures < MAX_FAST_FAILURES

    /**
     * The new fast-failure streak after a session that lived
     * [sessionDurationMs]: a session that survived past the fast window
     * resets the streak; a quick death extends it.
     */
    fun nextFastFailureStreak(previousStreak: Int, sessionDurationMs: Long): Int =
        if (sessionDurationMs >= FAST_FAILURE_WINDOW_MS) 0 else previousStreak + 1

    /**
     * Appends one recognized [segment] to the already-committed field
     * text: single-space joined, trimmed — the exact composition the
     * composer renders (committed base + live segment).
     */
    fun appendSegment(committed: String, segment: String): String {
        val base = committed.trim()
        val seg = segment.trim()
        return when {
            seg.isBlank() -> base
            base.isBlank() -> seg
            else -> "$base $seg"
        }
    }
}
