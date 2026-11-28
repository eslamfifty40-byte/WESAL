package com.wesal.messenger.core.util

/**
 * Round 18 (owner order 2026-11-28 12:22): the official «WESAL Messenger»
 * welcome conversation — the pinned top row of every user's chats list —
 * is ONE-WAY. A regular user reads what the WESAL team posts there; he
 * must never be able to send into it. (The separate «تواصل مع إدارة
 * WESAL» support conversation is a different chat entirely and stays
 * fully messageable — nothing here keys on it.)
 *
 * The lock is decided from three independent signals, all pure so the
 * rule is JVM-testable:
 *  - the chat is official: its doc carries `isOfficialWesalChat`, OR its
 *    id carries [Constants.OFFICIAL_CHAT_PREFIX] (the id is known from
 *    the navigation argument at the very first frame, so the composer
 *    never flashes visible while the chat preview is still loading);
 *  - the sender is an admin: a super admin by identity, OR his own
 *    user-doc numericId is in [Constants.ADMIN_NUMERIC_IDS] (10001 /
 *    10002 — the owner's definition of an admin account).
 *
 * This client lock is layered over the repository send gate
 * (ChatRepositoryImpl refuses non-super-admin writes into official
 * chats) — it keeps a blocked send from ever creating an optimistic
 * echo or an outbox entry, and gives the user the proper notice
 * instead of a generic send failure. Full server-side enforcement
 * additionally needs a Firestore rules arm (see FIXLOG_R18).
 */
object OfficialChatLockLogic {

    /** The notice shown in place of the composer, and as the block toast. */
    const val LOCKED_NOTICE_AR = "محادثة رسمية من فريق وِصال — لا يمكن الرد عليها"
    const val LOCKED_NOTICE_EN = "Official conversation from the WESAL team — replies are disabled"

    /** Official by flag OR by id prefix — either signal alone is enough. */
    fun isOfficialChat(chatId: String, isOfficialFlag: Boolean): Boolean =
        isOfficialFlag || chatId.startsWith(Constants.OFFICIAL_CHAT_PREFIX)

    /** Admin by the owner's definition: numericId 10001 / 10002. */
    fun isAdminNumericId(numericId: String?): Boolean =
        numericId?.trim()?.let { it in Constants.ADMIN_NUMERIC_IDS } == true

    /**
     * True when this user must not send into this chat: the chat is the
     * official welcome conversation and the user is not an admin under
     * either admin definition (super-admin identity or admin numericId).
     */
    fun isSendLocked(
        chatId: String,
        isOfficialFlag: Boolean,
        isSuperAdmin: Boolean,
        myNumericId: String?
    ): Boolean =
        isOfficialChat(chatId, isOfficialFlag) && !isSuperAdmin && !isAdminNumericId(myNumericId)
}
