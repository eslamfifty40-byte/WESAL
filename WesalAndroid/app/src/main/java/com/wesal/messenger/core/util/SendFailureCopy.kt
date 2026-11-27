package com.wesal.messenger.core.util

/**
 * Round 12 (addendum 12): signal that a message write was accepted into
 * the Firestore SDK's persistent mutation queue but not acknowledged
 * inside the ACK window (slow network). This is NOT a failure: the SDK
 * syncs the queued write when the network allows, and the persistent
 * outbox entry (same client message id) remains as a backstop. It must
 * never be rendered to the user as an error — the message simply stays
 * in its SENDING/queued state, WhatsApp-style.
 */
class MessageQueuedException : Exception("Message queued for delivery")

/**
 * Round 12 (addendum 12): the ONLY copy a send failure may ever show.
 * Raw exception text (`localizedMessage`, `Throwable.message`) leaked
 * into the chat UI before — the owner was shown «تعذر إرسال الرسالة»
 * followed by raw English containing "timeout" and "15000" from the
 * old REST-fallback error. Every send-failure surface now maps through
 * here; the mapping is pure and unit-tested.
 */
object SendFailureCopy {

    /** Queued on a slow network — will deliver on its own. */
    const val QUEUED_AR = "تعذر إرسال الرسالة — سيتم إرسالها تلقائياً عند تحسن الاتصال"

    /** Permanent failure (rejected write) — the bubble offers retry. */
    const val FAILED_AR = "تعذر إرسال الرسالة — دوس على الرسالة لإعادة المحاولة"

    const val MEDIA_FAILED_AR = "فشل إرسال الوسائط — حاول تاني"

    fun isQueued(error: Throwable?): Boolean = error is MessageQueuedException

    /** The toast for a text-send outcome; never contains exception text. */
    fun toastFor(error: Throwable?): String =
        if (isQueued(error)) QUEUED_AR else FAILED_AR
}
