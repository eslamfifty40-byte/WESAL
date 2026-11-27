package com.wesal.messenger.core.util

/**
 * «مساعد وِصال» (Wesal Assistant, round 8): the in-app AI assistant is a
 * VIRTUAL contact — it has no Firestore user doc and no Firestore chat. Its
 * conversation lives only on the device (per-uid local history) and each
 * question is answered by the Supabase Edge Function `ai-assistant`
 * (Gemini free tier behind it), authenticated by the caller's Firebase ID
 * token carried in the request body — the same transport shape as the
 * round-5 phone-OTP backend.
 *
 * This file holds the feature's message model plus the pure shaping rules
 * (kept free of Android/Firebase types so they are unit-testable):
 * - the greeting window (owner's Meta-AI reference: «صباح الخير يا {name}»),
 * - the suggestion chips shown in the empty state,
 * - the backend payload window: at most the last [MAX_PAYLOAD_MESSAGES]
 *   messages, an attachment ONLY on the newest message, and text
 *   placeholders for older media messages,
 * - the local-history cap.
 */

enum class AssistantRole { USER, ASSISTANT }

enum class AssistantMediaKind { NONE, IMAGE, VOICE }

/**
 * One message in the assistant conversation. Media messages reference an
 * app-private local file ([mediaPath]) so they re-render on reopen; the
 * bytes are read and base64-encoded only when the message is the newest
 * one being sent. [isErrorNotice] marks transient Arabic error bubbles
 * (connection failure / daily limit): they render in the list but are
 * never persisted and never sent to the backend.
 */
data class AssistantMessage(
    val id: String,
    val role: AssistantRole,
    val text: String,
    val mediaKind: AssistantMediaKind = AssistantMediaKind.NONE,
    val mediaPath: String = "",
    val mediaMimeType: String = "",
    val durationMs: Long = 0L,
    val createdAtMs: Long = 0L,
    val isErrorNotice: Boolean = false
)

/** Attachment payload for the newest message only (backend contract). */
data class AssistantPayloadMedia(
    val mimeType: String,
    val dataBase64: String
)

/** One message in the backend `messages` array (backend contract). */
data class AssistantPayloadMessage(
    val role: String,
    val text: String,
    val media: AssistantPayloadMedia? = null
)

object AssistantLogic {

    /** Local history cap (per uid). */
    const val MAX_HISTORY_MESSAGES = 100

    /** Backend window: at most this many messages are sent per ask. */
    const val MAX_PAYLOAD_MESSAGES = 10

    /** Question substituted when a photo is sent with no caption. */
    const val IMAGE_DEFAULT_QUESTION = "اشرح لي الصورة دي"

    /** Prompt substituted when a voice note is sent (it carries no text). */
    const val VOICE_DEFAULT_TEXT = "اسمع الرسالة الصوتية دي ورد عليّا"

    /** History-window placeholder for an older image message with no caption. */
    const val IMAGE_PLACEHOLDER = "[صورة]"

    /** History-window placeholder for an older voice message. */
    const val VOICE_PLACEHOLDER = "[رسالة صوتية]"

    /** Generic Arabic failure bubble. */
    const val ERROR_GENERIC = "حصلت مشكلة في الاتصال، جرّب تاني"

    /** Daily free-limit bubble (backend HTTP 429). */
    const val ERROR_DAILY_LIMIT = "وصلت لحد الأسئلة المجانية النهارده — ارجع لي بكرة 🌹"

    /** Empty-state suggestion chips (Egyptian dialect, tap-to-send). */
    val SUGGESTIONS: List<String> = listOf(
        "ساعدني أكتب رسالة حلوة",
        "اشرح لي موضوع صعب ببساطة",
        "اديني أفكار لهدية لحد بحبه",
        "اكتب لي وصف لنشاطي التجاري",
        "اعمل لي جدول مذاكرة",
        "اديني أفكار لفتح مشروع صغير",
        "ترجم لي كلام للإنجليزي",
        "إزاي أتجنب الإرهاق والتعب؟"
    )

    /** First name token of a display name ("" when blank). */
    fun firstNameOf(displayName: String?): String =
        displayName?.trim()?.split(Regex("\\s+"))?.firstOrNull().orEmpty()

    /**
     * Time-of-day greeting for the empty state: «صباح الخير» from 05:00 to
     * 11:59, «مساء الخير» otherwise; «يا {name}» is appended only when a
     * first name is known.
     */
    fun greetingFor(hourOfDay: Int, firstName: String): String {
        val base = if (hourOfDay in 5..11) "صباح الخير" else "مساء الخير"
        val name = firstName.trim()
        return if (name.isBlank()) base else "$base يا $name"
    }

    /** Keeps at most the newest [MAX_HISTORY_MESSAGES] messages. */
    fun capHistory(messages: List<AssistantMessage>): List<AssistantMessage> =
        messages.takeLast(MAX_HISTORY_MESSAGES)

    /**
     * Shapes the backend `messages` array from the local conversation
     * ([history] must already include the new user message as its last
     * element):
     * - error notices are dropped and at most the last
     *   [MAX_PAYLOAD_MESSAGES] messages are kept;
     * - [attachmentForLast] is attached ONLY to the last message and only
     *   when that message actually carries media;
     * - a media message with blank text sends its default question when it
     *   is the newest message, and a bracketed placeholder when it is an
     *   older history entry; a captioned media message sends its caption.
     */
    fun buildPayload(
        history: List<AssistantMessage>,
        attachmentForLast: AssistantPayloadMedia? = null
    ): List<AssistantPayloadMessage> {
        val window = history.filterNot { it.isErrorNotice }.takeLast(MAX_PAYLOAD_MESSAGES)
        return window.mapIndexed { index, message ->
            val isLast = index == window.lastIndex
            val text = when {
                message.text.isNotBlank() -> message.text
                message.mediaKind == AssistantMediaKind.IMAGE ->
                    if (isLast) IMAGE_DEFAULT_QUESTION else IMAGE_PLACEHOLDER
                message.mediaKind == AssistantMediaKind.VOICE ->
                    if (isLast) VOICE_DEFAULT_TEXT else VOICE_PLACEHOLDER
                else -> message.text
            }
            AssistantPayloadMessage(
                role = if (message.role == AssistantRole.USER) "user" else "assistant",
                text = text,
                media = if (isLast && message.mediaKind != AssistantMediaKind.NONE) {
                    attachmentForLast
                } else {
                    null
                }
            )
        }
    }
}
