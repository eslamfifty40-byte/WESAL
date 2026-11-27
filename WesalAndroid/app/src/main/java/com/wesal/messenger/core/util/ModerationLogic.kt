package com.wesal.messenger.core.util

/**
 * Round 11 (item 9): pure role decisions for group/channel moderation.
 *
 * The repositories used to inline these checks (and one surface — channel
 * join-request approval — had none at all). Centralising them here makes
 * the role model explicit and JVM-testable:
 *
 * GROUPS (role = membership of `adminIds`; the creator is `creatorId`):
 *  - a group admin manages members and roles;
 *  - the CREATOR can never be removed or demoted by anyone (not even by
 *    another admin) — a group must not lose its owner to a moderator
 *    action; the creator leaves/deletes through the dedicated paths;
 *  - removing YOURSELF is the leave path and is always allowed.
 *
 * CHANNELS (owner = `ownerUid`; moderators = `moderatorIds`):
 *  - the owner alone assigns/removes moderators;
 *  - owners and moderators manage the channel day-to-day (edit, posts,
 *    join requests);
 *  - a moderator leaving gives up the moderator seat with the
 *    subscription (self-service removal of ONESELF only).
 */
object ModerationLogic {

    /** Group: may [callerUid] remove [targetUid] from the group? */
    fun canRemoveGroupMember(
        callerUid: String,
        targetUid: String,
        creatorId: String,
        adminIds: List<String>
    ): Boolean {
        if (callerUid.isBlank() || targetUid.isBlank()) return false
        if (targetUid == callerUid) return true // leaving is always allowed
        if (targetUid == creatorId) return false // the creator is untouchable
        return callerUid in adminIds
    }

    /** Group: may [callerUid] grant/revoke [targetUid]'s admin role? */
    fun canSetGroupAdmin(
        callerUid: String,
        targetUid: String,
        creatorId: String,
        adminIds: List<String>
    ): Boolean {
        if (callerUid.isBlank() || targetUid.isBlank()) return false
        if (targetUid == creatorId) return false // the creator's role is inherent
        return callerUid in adminIds
    }

    /** Channel: is [uid] the owner or a moderator (a "channel manager")? */
    fun isChannelManager(uid: String, ownerUid: String, moderatorIds: List<String>): Boolean =
        uid.isNotBlank() && (uid == ownerUid || uid in moderatorIds)

    /** Channel: may [callerUid] assign/remove [targetUid] as moderator? Owner only. */
    fun canSetChannelModerator(callerUid: String, ownerUid: String): Boolean =
        callerUid.isNotBlank() && callerUid == ownerUid

    /**
     * Channel self-service edits (subscribe/unsubscribe style writes): the
     * caller may only ever add/remove THEMSELVES in `subscriberIds`, and in
     * `moderatorIds` may only REMOVE themself (leaving gives up the seat) —
     * never appoint anyone, never touch anyone else's membership. Mirrors
     * the tightened channels update arms in firestore.rules.
     */
    fun isSelfOnlyMembershipChange(
        callerUid: String,
        addedSubscribers: Set<String>,
        removedSubscribers: Set<String>,
        addedModerators: Set<String>,
        removedModerators: Set<String>
    ): Boolean {
        if (callerUid.isBlank()) return false
        if (addedModerators.isNotEmpty()) return false
        return (addedSubscribers + removedSubscribers + removedModerators)
            .all { it == callerUid }
    }
}
