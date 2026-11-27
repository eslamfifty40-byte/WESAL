package com.wesal.messenger.core.util

/**
 * Round 12: pure decisions for the verification/subscription request flow
 * (الاشتراكات وطلبات التوثيق).
 *
 * DEVICE-PROVEN defect this serves: `submitSubscriptionRequest` uploaded the
 * ID image to Firebase Storage FIRST and rethrew on failure, so on this
 * project (Storage effectively unprovisioned — OBJECT_NOT_FOUND) the
 * Firestore `subscription_requests` doc was NEVER written and the admin
 * panel stayed empty, while Settings showed the raw English exception.
 *
 * The writer now ALWAYS writes the doc. The ID image payload is chosen
 * here, purely:
 *  - STORAGE — the upload succeeded; the doc carries the download URL.
 *  - INLINE  — the upload failed; the doc carries a compact inline copy
 *    (a `data:image/jpeg;base64,...` string) sized by the ladder below so
 *    the whole doc stays safely under Firestore's 1 MiB document limit.
 *  - NONE    — the picked image could not even be decoded locally; the
 *    request still reaches the admins (they see it carries no image).
 *
 * The user-facing strings live here too so no surface can ever leak raw
 * exception text again: every failure maps to ONE human message.
 */
object SubscriptionRequestLogic {

    /**
     * Hard cap on the base64 payload of the inline ID image, in chars.
     * Firestore's document limit is 1,048,576 bytes; the rest of the
     * request doc is well under 1 KB of text fields, so a 750,000-char
     * payload keeps the whole document at roughly three quarters of the
     * limit with generous headroom.
     */
    const val MAX_INLINE_BASE64_CHARS: Int = 750_000

    /** Longest side (px) the inline ID image is ever encoded at. */
    const val MAX_INLINE_SIDE: Int = 1024

    /** Side ladder, largest first — a step down only happens when every
     *  quality at the current side still exceeds the budget. */
    val SIDE_LADDER: List<Int> = listOf(1024, 768, 512, 384)

    /** JPEG quality ladder, highest first, tried at each side. */
    val QUALITY_LADDER: List<Int> = listOf(85, 72, 60, 48, 36, 28)

    /** One encode attempt: decode at [maxSide], compress at [quality]. */
    data class EncodeStep(val maxSide: Int, val quality: Int)

    /**
     * The full ordered encode plan (side-major: all qualities at the
     * largest side first). The encoder walks it and keeps the FIRST
     * result whose base64 length fits [MAX_INLINE_BASE64_CHARS] — i.e.
     * the best quality that is provably storable.
     */
    fun encodePlan(): List<EncodeStep> =
        SIDE_LADDER.flatMap { side -> QUALITY_LADDER.map { q -> EncodeStep(side, q) } }

    /** Exact base64 length (NO_WRAP, with padding) for [byteCount] bytes. */
    fun base64EncodedLength(byteCount: Int): Int {
        if (byteCount <= 0) return 0
        return ((byteCount + 2) / 3) * 4
    }

    /** Would an image of [byteCount] JPEG bytes fit the inline budget? */
    fun fitsInlineBudget(byteCount: Int): Boolean =
        byteCount > 0 && base64EncodedLength(byteCount) <= MAX_INLINE_BASE64_CHARS

    /** Where the request's ID image ended up. */
    enum class IdDocumentSource { STORAGE, INLINE, NONE }

    /** The payload the writer stamps onto the request doc. */
    data class IdDocumentPayload(
        val source: IdDocumentSource,
        val url: String,
        val inlineData: String
    )

    /**
     * Payload choice: a real Storage URL always wins; the inline copy is
     * used only when there is no URL; with neither, the request is still
     * written (source NONE) — only a Firestore write failure may fail a
     * submission.
     */
    fun resolvePayload(storageUrl: String?, inlineData: String?): IdDocumentPayload = when {
        !storageUrl.isNullOrBlank() ->
            IdDocumentPayload(IdDocumentSource.STORAGE, storageUrl, "")
        !inlineData.isNullOrBlank() ->
            IdDocumentPayload(IdDocumentSource.INLINE, "", inlineData)
        else ->
            IdDocumentPayload(IdDocumentSource.NONE, "", "")
    }

    /**
     * The single image reference a reader surface (admin card / preview)
     * should render for a request: the Storage URL when present, else the
     * inline data string, else blank (no image attached).
     */
    fun displayImageRef(idDocumentUrl: String?, idDocumentInline: String?): String =
        idDocumentUrl?.takeIf { it.isNotBlank() } ?: idDocumentInline.orEmpty()

    /** Success confirmation: the request arrived and is pending review. */
    fun submitSuccessMessage(isEnglish: Boolean): String = if (isEnglish) {
        "Your request was submitted successfully and is now pending review."
    } else {
        "تم إرسال طلبك بنجاح، وهو الآن قيد المراجعة"
    }

    /**
     * The ONLY failure text this flow may show. Raw exception text (e.g.
     * the English Storage error the owner saw) must never reach the user.
     */
    fun submitFailureMessage(isEnglish: Boolean): String = if (isEnglish) {
        "Something went wrong while sending your request, please try again."
    } else {
        "حصلت مشكلة وإحنا بنبعت الطلب، حاول تاني"
    }
}
