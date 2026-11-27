package com.wesal.messenger.core.util

/**
 * Round 12 (addenda 10/10b/10d): the first-contact decision, extracted as
 * pure logic so it is unit-testable.
 *
 * PROVEN defect this replaces: `getOrCreateDirectChat` hard-threw for any
 * fresh pair without an ACCEPTED contact request, and the live Firestore
 * rules only allow a normal 1:1 chat create when the pair is already
 * contacts-accepted (or support/admin) — so the WhatsApp-style flow
 * (open the thread, send, the recipient sees a message REQUEST) could
 * never start: the مراسلة tap died silently, and where a create was
 * attempted anyway it could be server-denied while the creator's device
 * still rendered the locally-pending thread (creator-only conversation,
 * message stuck at a single ✓). The fix creates the chat directly in the
 * PENDING request state (see the narrowly-scoped create arm added to
 * firestore.rules, flagged REQUIRES CONSOLE DEPLOY in FIXLOG_R12.md).
 */
object DirectChatRequestLogic {

    enum class InitialState { ACCEPTED, PENDING, REJECTED }

    /**
     * The state a brand-new 1:1 chat is born with.
     *
     * - The official support target and verified super-admin callers keep
     *   the historical direct-open behaviour (ACCEPTED).
     * - An ACCEPTED contact request means the pair is already cleared.
     * - A REJECTED contact request is respected: no new thread is forced
     *   on the recipient (the caller gets a clear Arabic error instead of
     *   a silent no-op).
     * - Everything else (no request yet, or one still PENDING) is born as
     *   a PENDING message request: the requester lands in the thread and
     *   can send; the recipient sees it in طلبات المراسلة.
     */
    fun initialState(
        isSupportTarget: Boolean,
        callerIsSuperAdmin: Boolean,
        contactRequestStatus: String?
    ): InitialState = when {
        isSupportTarget || callerIsSuperAdmin -> InitialState.ACCEPTED
        contactRequestStatus == "ACCEPTED" -> InitialState.ACCEPTED
        contactRequestStatus == "REJECTED" -> InitialState.REJECTED
        else -> InitialState.PENDING
    }

    /**
     * Whether the in-chat «طلب مراسلة — بانتظار قبول الطرف الآخر» label is
     * shown to the SENDER. Driven by the RAW chat fields (the chats-list
     * preview deliberately rewrites the requester's state to ACCEPTED so
     * the thread stays in his main list, so the detail screen reads the
     * raw doc state through `observeChatRequestState`).
     */
    fun senderPendingLabelVisible(
        rawRequestStatus: String?,
        requestFromUid: String?,
        myUid: String?
    ): Boolean =
        rawRequestStatus == "PENDING" &&
            !requestFromUid.isNullOrBlank() &&
            !myUid.isNullOrBlank() &&
            requestFromUid == myUid
}

/**
 * Round 12 (addendum 10c): the in-flight duplicate guard for the chat
 * composer. PROVEN gap: `sendOrEditText` minted a brand-new message id on
 * every invocation and the send button was never disabled while a send
 * was in flight, so a user whose send stalled (up to the 15s batch
 * timeout) re-typed/re-tapped and every attempt eventually landed as a
 * SEPARATE copy — the reported 3–4× duplicates. A submission whose exact
 * (text, reply target) already has a SENDING echo inside the window is
 * the same composed message being retried by hand and is ignored; the
 * echo already on screen is the user's feedback. Retries of a FAILED
 * message are unaffected — they reuse the same client id by design.
 */
object SendInFlightGuard {

    data class Candidate(
        val statusRaw: String,
        val text: String,
        val replyToMessageId: String?,
        val timestampMs: Long
    )

    const val WINDOW_MS: Long = 20_000L

    fun isDuplicateInFlight(
        candidates: List<Candidate>,
        text: String,
        replyToMessageId: String?,
        nowMs: Long,
        windowMs: Long = WINDOW_MS
    ): Boolean = candidates.any { c ->
        c.statusRaw == "SENDING" &&
            c.text == text &&
            c.replyToMessageId == replyToMessageId &&
            nowMs - c.timestampMs in 0..windowMs
    }
}
