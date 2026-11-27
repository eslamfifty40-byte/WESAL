package com.wesal.messenger.core.util

import com.google.firebase.firestore.FirebaseFirestoreException
import java.io.IOException

/**
 * Round 13 (Addendum L/L5): the ONLY way an error may be turned into UI
 * text anywhere in the app.
 *
 * Device-proven defect (2026-11-27, a new user's phone under the
 * exhausted free-tier quota): multiple screens rendered the RAW
 * Firestore exception — the full English
 * "RESOURCE_EXHAUSTED: Quota limit exceeded … project_number:
 * 255196025471 …" — as visible content: the official channel screen,
 * the contacts screen's error state, and the account-data screen. The
 * mechanism was uniform: `Throwable.message` / `localizedMessage`
 * (directly, or via `?: fallback` — a Firestore exception ALWAYS has a
 * message, so the fallback never fired) assigned into a UI state field
 * and rendered.
 *
 * [friendly] therefore NEVER returns a platform/SDK message:
 *  - Firestore exceptions map by their typed [FirebaseFirestoreException.Code]
 *    to a fixed honest Arabic state (quota / connection / not-allowed),
 *  - IO failures map to the connection state,
 *  - an exception whose message is one of OUR OWN authored Arabic
 *    strings (e.g. an IllegalStateException guard like «الحساب غير
 *    موجود») passes through untouched — those are deliberate copy,
 *  - everything else collapses to the caller's fixed fallback.
 *
 * The authored-message test is deliberately strict: the text must
 * contain Arabic script and NONE of the technical markers that appear
 * in SDK messages. Pure and unit-tested (ErrorCopyTest).
 */
object ErrorCopy {

    const val GENERIC_AR = "حدث خطأ غير متوقع — حاول مرة أخرى"

    /** The free daily quota is exhausted; service resumes on reset. */
    const val QUOTA_AR =
        "الخادم وصل لحد الاستخدام المجاني اليومي حالياً — كل حاجة هتشتغل تلقائياً بعد تجدد الحصة، حاول بعد قليل"

    const val CONNECTION_AR = "تعذر الاتصال بالخادم — تحقق من الإنترنت وحاول مرة أخرى"

    const val NOT_ALLOWED_AR = "غير مسموح بهذا الإجراء"

    private val TECHNICAL_MARKERS = listOf(
        "Exception", "RESOURCE_EXHAUSTED", "PERMISSION_DENIED", "UNAVAILABLE",
        "DEADLINE_EXCEEDED", "project_number", "Quota", "quota", "grpc",
        "com.google", "java.", "kotlin.", "StatusRuntime", "Firestore"
    )

    fun friendly(error: Throwable?, fallback: String = GENERIC_AR): String {
        if (error == null) return fallback
        if (error is FirebaseFirestoreException) {
            return forCodeName(error.code.name, fallback)
        }
        if (error is IOException) return CONNECTION_AR
        val msg = error.message?.trim().orEmpty()
        return if (msg.isNotEmpty() && isAuthored(msg)) msg else fallback
    }

    /**
     * The Firestore code → copy mapping, keyed by code NAME so it is
     * pure and unit-testable (local JVM tests cannot initialize
     * Firebase exception classes).
     */
    internal fun forCodeName(codeName: String, fallback: String): String =
        when (codeName) {
            "RESOURCE_EXHAUSTED" -> QUOTA_AR
            "UNAVAILABLE", "DEADLINE_EXCEEDED" -> CONNECTION_AR
            "PERMISSION_DENIED" -> NOT_ALLOWED_AR
            else -> fallback
        }

    /** True only for our own user-facing Arabic copy, never SDK text. */
    internal fun isAuthored(msg: String): Boolean =
        msg.any { it in '\u0600'..'\u06FF' } &&
            TECHNICAL_MARKERS.none { marker -> msg.contains(marker) }
}
