package com.wesal.messenger.core.util

/**
 * Round 12 (addendum 2): pure decisions for OFFICIAL stories — admin
 * announcements in the Status tab (WhatsApp-official-status style).
 *
 * An official story is authored by an admin account (numericId in
 * [Constants.ADMIN_NUMERIC_IDS]) and stamped `isOfficial = true` at
 * publish time. Two decisions follow from the flag, both pure:
 *
 *  - VISIBILITY: an official story bypasses the contacts/audience
 *    filter entirely — every signed-in user sees it. (Expiry and the
 *    viewer's own blocked-list still apply; those are checked before
 *    this decision in the feed filter. The Firestore read rule already
 *    allows every signed-in user to read stories, so no rules change
 *    is needed — the contacts restriction was always client-side.)
 *  - ORDERING: official stories pin ABOVE contact stories; inside
 *    each group the order stays newest-first, exactly like the
 *    pre-existing feed.
 */
object OfficialStoryLogic {

    /** Does this story bypass the contacts/audience visibility filter? */
    fun bypassesAudienceFilter(isOfficial: Boolean): Boolean = isOfficial

    /**
     * Round 13 (Item D clarification — defense in depth): may this author
     * publish an OFFICIAL story at all? Only the admin accounts (numericId
     * in [Constants.ADMIN_NUMERIC_IDS]) may. Since round 18 the entry
     * lives only in the admin panel, but the publish path itself
     * re-checks here so a non-admin can never get a story stamped
     * official even if the composer flag were somehow reached. [authorNumericId] is the
     * author's OWN user-doc numericId (string or number representation —
     * callers normalize before calling).
     */
    fun canPublishOfficial(authorNumericId: String): Boolean =
        authorNumericId.trim() in Constants.ADMIN_NUMERIC_IDS

    /**
     * The `isOfficial` value a publish may actually stamp: the composer's
     * request ANDed with the author's admin status. A regular user's
     * story is ALWAYS stamped false, whatever the caller passed.
     */
    fun effectiveIsOfficial(requested: Boolean, authorNumericId: String): Boolean =
        requested && canPublishOfficial(authorNumericId)

    /**
     * Pinned-first ordering for a flat story list: official stories
     * first (newest first among themselves), then regular stories
     * (newest first) — a regular-only list comes out purely
     * newest-first, unchanged from the pre-official feed.
     */
    fun <T> pinOfficialFirst(
        items: List<T>,
        isOfficial: (T) -> Boolean,
        createdAt: (T) -> Long
    ): List<T> = items.sortedWith(
        compareByDescending<T> { isOfficial(it) }.thenByDescending { createdAt(it) }
    )
}
