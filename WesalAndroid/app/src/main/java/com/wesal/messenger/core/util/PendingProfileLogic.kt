package com.wesal.messenger.core.util

import org.json.JSONObject

/**
 * Round 13 (Addendum J): the queued profile-completion contract.
 *
 * Device-proven trap this removes: a NEW user (arriving from the promo)
 * filled «حفظ وبدء استخدام WESAL» and got a red banner «انتهت مهلة
 * الاتصال بالخادم…» — and was TRAPPED on the form, because the save's
 * failure (that night: the exhausted Firestore daily write quota) left
 * no way into the app. A registration save must never dead-end:
 *
 *  - On an INFRASTRUCTURE failure (timeout, quota, network, server),
 *    the form's fields persist locally as a [PendingProfile], the user
 *    ENTERS the app immediately, and the completion re-runs in the
 *    background — at once and again on the next launch — until it
 *    lands. Only a quiet «سيتم حفظ بياناتك تلقائياً» note shows.
 *  - USER-FIXABLE failures (a duplicate @ID, a phone number owned by
 *    another account, a banned number) still block with their exact
 *    message: entering with a claim that can never land would be a
 *    silent lie, and the user can fix those on the spot.
 *
 * This object is the pure half: the pending record's shape, its JSON
 * round-trip (stored in DataStore), and the failure classifier both
 * the view model and the repository retry share.
 */
object PendingProfileLogic {

    /** The locally persisted, not-yet-committed profile-completion form. */
    data class PendingProfile(
        val uid: String,
        val displayName: String,
        val bio: String,
        val wesalId: String,
        val phoneE164: String,
        val birthDateIso: String,
        val hideBirthDate: Boolean,
        val locationGranted: Boolean,
        val locationGraceStartedAt: Long,
        /** Content-Uri string of the picked photo, or "" — re-read at retry; an unreadable photo degrades to none. */
        val photoUriString: String,
        val savedAtMs: Long
    )

    fun toJson(p: PendingProfile): String = JSONObject()
        .put("uid", p.uid)
        .put("displayName", p.displayName)
        .put("bio", p.bio)
        .put("wesalId", p.wesalId)
        .put("phoneE164", p.phoneE164)
        .put("birthDateIso", p.birthDateIso)
        .put("hideBirthDate", p.hideBirthDate)
        .put("locationGranted", p.locationGranted)
        .put("locationGraceStartedAt", p.locationGraceStartedAt)
        .put("photoUriString", p.photoUriString)
        .put("savedAtMs", p.savedAtMs)
        .toString()

    /** Parses a stored record; null for blank/corrupt input or a record with no owner. */
    fun fromJson(json: String?): PendingProfile? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(json)
            val uid = o.optString("uid")
            if (uid.isBlank()) return null
            PendingProfile(
                uid = uid,
                displayName = o.optString("displayName"),
                bio = o.optString("bio"),
                wesalId = o.optString("wesalId"),
                phoneE164 = o.optString("phoneE164"),
                birthDateIso = o.optString("birthDateIso"),
                hideBirthDate = o.optBoolean("hideBirthDate"),
                locationGranted = o.optBoolean("locationGranted"),
                locationGraceStartedAt = o.optLong("locationGraceStartedAt"),
                photoUriString = o.optString("photoUriString"),
                savedAtMs = o.optLong("savedAtMs")
            )
        }.getOrNull()
    }

    /**
     * Is this failure one the USER can fix on the form (duplicate @ID,
     * a phone owned by another account, a banned number, a malformed
     * value)? Those keep their exact blocking message. Everything else
     * — timeouts, quota exhaustion, network and server errors — is
     * infrastructure and takes the queue-and-enter path.
     */
    fun isUserFixable(error: Throwable?): Boolean {
        if (error == null) return false
        if (error is IllegalArgumentException) return true
        val msg = error.message.orEmpty()
        return msg.contains("مستخدم بالفعل") ||
            msg.contains("مرتبط بحساب محظور") ||
            msg.contains("اختر معرفاً آخر")
    }

    /** Infrastructure failure = a real error that is not user-fixable. */
    fun isInfrastructureFailure(error: Throwable?): Boolean =
        error != null && !isUserFixable(error)
}
