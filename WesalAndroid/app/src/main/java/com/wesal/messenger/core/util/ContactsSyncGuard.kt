package com.wesal.messenger.core.util

/**
 * Scaling pass: differential guard for the phone-book sync.
 *
 * Contacts discovery itself writes nothing to Firestore, but a full sync
 * re-runs every hash-lookup chunk (Supabase registry + Firestore
 * fallbacks). The screen syncs on every ON_RESUME, and the bulk-invite
 * flow bounces the user between WESAL and WhatsApp per contact, so the
 * same unchanged phone book was being re-looked-up many times a minute.
 *
 * The guard fingerprints the candidate hash set of the phone book: a
 * non-forced sync may be skipped only when the fingerprint is identical
 * to the last SUCCESSFUL sync's and that sync finished less than
 * [FRESH_WINDOW_MS] ago. Any phone-book change (add/edit/delete — the
 * paths that follow a mutation re-read the book, so the fingerprint
 * changes) re-runs the lookup immediately, and the live listeners keep
 * the visible list fresh while the screen is open regardless.
 */
object ContactsSyncGuard {

    const val FRESH_WINDOW_MS: Long = 60_000L

    /** Order-independent fingerprint of a phone book's candidate hashes. */
    fun fingerprint(candidateHashes: Collection<String>): Int =
        candidateHashes.distinct().sorted().hashCode()

    fun shouldSkipSync(
        force: Boolean,
        currentFingerprint: Int,
        lastSuccessfulFingerprint: Int?,
        lastSuccessfulAtMs: Long,
        nowMs: Long
    ): Boolean {
        if (force) return false
        if (lastSuccessfulFingerprint == null || lastSuccessfulFingerprint != currentFingerprint) {
            return false
        }
        return nowMs - lastSuccessfulAtMs < FRESH_WINDOW_MS
    }
}
