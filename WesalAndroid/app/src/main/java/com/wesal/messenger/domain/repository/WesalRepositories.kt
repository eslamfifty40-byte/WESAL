package com.wesal.messenger.domain.repository

import android.app.Activity
import android.net.Uri
import com.google.firebase.auth.PhoneAuthProvider
import com.wesal.messenger.domain.model.*
import kotlinx.coroutines.flow.Flow

sealed interface PhoneAuthEvent {
    data class CodeSent(
        val verificationId: String,
        val resendingToken: PhoneAuthProvider.ForceResendingToken
    ) : PhoneAuthEvent
    data class Verified(val uid: String, val isNewProfile: Boolean) : PhoneAuthEvent
    data class Error(val messageAr: String, val messageEn: String) : PhoneAuthEvent
}

interface AuthRepository {
    val currentUserId: String?
    val isLoggedIn: Boolean
    val currentUserDisplayName: String?
    val currentUserEmail: String?
    fun observeAuthState(): Flow<String?>
    suspend fun signInWithGoogleIdToken(idToken: String): Result<Boolean>
    suspend fun signInWithEmail(email: String, password: String): Result<Boolean>
    suspend fun signUpWithEmail(email: String, password: String, fullName: String = ""): Result<Boolean>
    suspend fun sendPasswordReset(email: String): Result<Unit>
    suspend fun completeUserProfile(
        displayName: String,
        bio: String,
        photoUri: Uri?,
        wesalId: String? = null,
        phoneE164: String? = null,
        birthDate: String? = null,
        hideBirthDate: Boolean = false,
        locationGranted: Boolean = false,
        locationGraceStartedAt: Long = 0L
    ): Result<WesalUser>
    suspend fun syncFcmToken()
    suspend fun signOut(): Result<Unit>
    suspend fun deleteAccountPermanently(passwordForReauth: String? = null, googleIdTokenForReauth: String? = null): Result<Unit>
    suspend fun signInGuestForSupport(): Result<String>
    fun observeSavedAccounts(): Flow<List<SavedWesalAccount>>
    suspend fun removeSavedAccount(uid: String)
    suspend fun clearActiveSessionForSwitch(previousUid: String? = null)
}

interface UserRepository {
    fun observeCurrentUser(): Flow<WesalUser?>
    fun observeUser(uid: String): Flow<WesalUser?>
    suspend fun getUserOnce(uid: String): WesalUser?
    suspend fun findUserByPublicIdOrUid(idOrUid: String): WesalUser?
    suspend fun findExactUserByNumericId(numericId: String): WesalUser?
    suspend fun searchUsers(query: String): List<WesalUser>
    suspend fun listDiscoverableUsers(limit: Int = 30): List<WesalUser>
    suspend fun findUsersByPhoneHashes(phoneHashes: List<String>): Map<String, WesalUser>
    suspend fun updateProfile(
        displayName: String,
        bio: String,
        publicId: String,
        photoUri: Uri?,
        coverUri: Uri? = null,
        birthDate: String? = null,
        hideBirthDate: Boolean? = null,
        facebookUrl: String? = null,
        instagramUrl: String? = null,
        businessHours: BusinessHoursSchedule? = null
    ): Result<Unit>
    /** Saves ONLY the profile photo and/or cover (independent of name / ID / birth-date validation) and verifies it on the server. */
    suspend fun updateProfileImages(photoUri: Uri?, coverUri: Uri?): Result<Unit>
    suspend fun ensureNumericIdAssigned(): String
    suspend fun updateOptionalPhone(rawPhone: String): Result<Unit>
    suspend fun updatePhoneValidated(e164Phone: String): Result<Unit>
    suspend fun removeOptionalPhone(): Result<Unit>
    suspend fun removeProfilePhoto(): Result<Unit>
    suspend fun removeProfileCover(): Result<Unit>
    suspend fun updatePrivacySettings(settings: PrivacySettings): Result<Unit>
    suspend fun updateLocationStatus(granted: Boolean, graceStartedAt: Long): Result<Unit>
    suspend fun updatePresenceHeartbeat(isOnline: Boolean, activityType: String = "", activityChatId: String = "", participantsCount: Int = 0)
    suspend fun blockUser(targetUid: String): Result<Unit>
    suspend fun unblockUser(targetUid: String): Result<Unit>
    suspend fun reportUser(
        targetUid: String,
        reason: String,
        targetType: String = "USER",
        targetChatId: String = "",
        targetMessageId: String = "",
        targetContentSnapshot: String = ""
    ): Result<Unit>
    fun observeSessions(): Flow<List<DeviceSession>>
    suspend fun revokeSession(sessionId: String): Result<Unit>
    suspend fun revokeAllOtherSessions(): Result<Unit>
    suspend fun createSessionLinkToken(): Result<String>

    // Profile Visitors (Sec 23)
    suspend fun recordProfileVisit(targetUid: String)
    fun observeProfileVisitors(): Flow<List<ProfileVisitor>>
    suspend fun clearProfileVisitors(): Result<Unit>

    // Super Admin & Comprehensive 19-Section Management (10001 only)
    fun observeIsSuperAdmin(): Flow<Boolean>
    suspend fun isSuperAdmin(): Boolean
    suspend fun getAdminUsersList(limit: Int = 200): List<WesalUser>
    suspend fun adminSearchUsers(query: String): List<WesalUser>
    suspend fun adminGetUserFullDetails(targetUid: String, reason: String = "Admin inspection"): Result<WesalUser>
    suspend fun computeAdminStats(): AdminDashboardStats
    suspend fun setAccountVerification(
        targetUid: String,
        verified: Boolean,
        note: String = "",
        verificationType: String = "BLUE",
        expiresAt: Long = 0L,
        durationLabel: String = ""
    ): Result<Unit>
    suspend fun setAccountFrame(
        targetUid: String,
        frameId: String,
        expiresAt: Long = 0L,
        durationLabel: String = ""
    ): Result<Unit>
    suspend fun setGlobalCustomerFrameEnabled(enabled: Boolean): Result<Int>
    suspend fun getGlobalCustomerFrameEnabled(): Boolean
    suspend fun setAccountNeonStyle(targetUid: String, neonColor: String, glowIntensity: Float): Result<Unit>
    suspend fun setAccountHiddenFromSearch(targetUid: String, hidden: Boolean, reason: String = ""): Result<Unit>
    suspend fun banUser(
        targetUid: String,
        permanent: Boolean,
        expiresAt: Long,
        reason: String,
        blockEmailAndVerifiedPhone: Boolean = true
    ): Result<Unit>
    suspend fun deleteAccountPermanently(targetUid: String, reason: String): Result<Unit>
    suspend fun unbanUser(targetUid: String, note: String = ""): Result<Unit>
    suspend fun warnUser(targetUid: String, reason: String): Result<Unit>
    fun observeAdminReports(): Flow<List<AdminReportRecord>>
    suspend fun updateReportStatus(reportId: String, status: String, adminNote: String = "", resolutionAction: String = ""): Result<Unit>
    suspend fun adminLogReportChatInspection(reportId: String, chatId: String, targetUid: String, reason: String)
    fun observeVerificationAuditLogs(): Flow<List<VerificationAuditLog>>
    fun observeHiddenAuditLogs(): Flow<List<VerificationAuditLog>>
    fun observeAdminActionLogs(): Flow<List<VerificationAuditLog>>
    suspend fun getWelcomeConfig(): SystemWelcomeConfig
    suspend fun updateWelcomeConfig(enabled: Boolean, template: String): Result<Unit>
    suspend fun sendWelcomeTestToSelf(): Result<Unit>
    suspend fun publishOfficialAnnouncement(title: String, body: String): Result<Unit>
    fun observeOfficialAnnouncements(): Flow<List<OfficialAnnouncement>>

    // Contact Requests & Friendship Gating
    fun observeContactRequests(): Flow<List<ContactRequest>>
    suspend fun sendContactRequest(targetUid: String): Result<ContactRequest>
    suspend fun acceptContactRequest(requestId: String): Result<Unit>
    suspend fun rejectContactRequest(requestId: String): Result<Unit>
    suspend fun cancelOrRemoveContactRequest(requestId: String): Result<Unit>
    suspend fun areContactsAccepted(peerUid: String): Boolean
}

interface ChatRepository {
    fun observeChats(): Flow<List<ChatPreview>>
    fun observeMessages(chatId: String): Flow<List<WesalMessage>>
    suspend fun getOrCreateDirectChat(peerUid: String): Result<String>
    suspend fun openSupportChatByNumericId(targetNumericId: String = com.wesal.messenger.core.util.Constants.OWNER_ADMIN_NUMERIC_ID): Result<String>
    suspend fun ensureOfficialWelcomeChat(displayName: String, forceResend: Boolean = false): Result<Unit>
    suspend fun setChatOfficialWallpaper(chatId: String, wallpaperUrl: String?): Result<Unit>
    suspend fun sendTextMessage(
        chatId: String,
        text: String,
        replyTo: WesalMessage? = null,
        forwardedFromName: String? = null
    ): Result<Unit>
    fun sendMediaMessage(
        chatId: String,
        uri: Uri,
        type: MessageType,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        durationMs: Long = 0L,
        waveform: List<Int> = emptyList(),
        caption: String = "",
        isViewOnce: Boolean = false
    ): Flow<Result<Float>>
    suspend fun markViewOnceOpened(chatId: String, messageId: String): Result<Unit>
    suspend fun sendLocationMessage(
        chatId: String,
        latitude: Double,
        longitude: Double,
        address: String
    ): Result<Unit>
    suspend fun setTyping(chatId: String, isTyping: Boolean)
    suspend fun markChatAsRead(chatId: String)
    suspend fun editMessage(chatId: String, messageId: String, newText: String): Result<Unit>
    suspend fun deleteMessageForMe(chatId: String, messageId: String): Result<Unit>
    suspend fun deleteMessageForEveryone(chatId: String, messageId: String): Result<Unit>
    suspend fun togglePinChat(chatId: String, pinned: Boolean): Result<Unit>
    suspend fun toggleMuteChat(chatId: String, muted: Boolean): Result<Unit>
    suspend fun deleteChat(chatId: String, forEveryone: Boolean = false): Result<Unit>
    suspend fun decryptMediaToCache(message: WesalMessage): Result<java.io.File>
}

interface GroupRepository {
    fun observeUserGroups(): Flow<List<GroupInfo>>
    fun observeGroup(groupId: String): Flow<GroupInfo?>
    suspend fun findGroupByInviteCode(code: String): GroupInfo?
    suspend fun createGroup(
        name: String,
        description: String,
        memberIds: List<String>,
        avatarUri: Uri?,
        joinMode: GroupJoinMode = GroupJoinMode.OPEN
    ): Result<GroupInfo>
    suspend fun updateGroupInfo(groupId: String, name: String, description: String, avatarUri: Uri?): Result<Unit>
    suspend fun updateGroupJoinMode(groupId: String, joinMode: GroupJoinMode): Result<Unit>
    suspend fun regenerateGroupInviteCode(groupId: String): Result<String>
    suspend fun requestJoinGroup(groupId: String): Result<Unit>
    fun observeGroupJoinRequests(groupId: String): Flow<List<GroupJoinRequest>>
    suspend fun approveGroupJoinRequest(groupId: String, requestId: String, requesterUid: String): Result<Unit>
    suspend fun rejectGroupJoinRequest(groupId: String, requestId: String): Result<Unit>
    suspend fun addMembers(groupId: String, newMemberIds: List<String>): Result<Unit>
    suspend fun removeMember(groupId: String, targetUid: String): Result<Unit>
    suspend fun setAdminRole(groupId: String, targetUid: String, isAdmin: Boolean): Result<Unit>
    suspend fun leaveGroup(groupId: String): Result<Unit>
}

interface ChannelRepository {
    fun observePublicChannels(): Flow<List<WesalChannel>>
    fun observeMyChannels(): Flow<List<WesalChannel>>
    fun observeChannel(channelId: String): Flow<WesalChannel?>
    suspend fun ensureOfficialTeamChannel(): WesalChannel?
    suspend fun findChannelByInviteCode(code: String): WesalChannel?
    suspend fun createChannel(
        title: String,
        description: String,
        isPublic: Boolean,
        avatarUri: Uri?
    ): Result<WesalChannel>
    suspend fun updateChannel(
        channelId: String,
        title: String,
        description: String,
        isPublic: Boolean,
        avatarUri: Uri?
    ): Result<Unit>
    suspend fun deleteChannel(channelId: String): Result<Unit>
    suspend fun adminSetChannelSuspended(channelId: String, suspended: Boolean): Result<Unit>
    suspend fun regenerateInviteCode(channelId: String): Result<String>
    suspend fun subscribePublicChannel(channelId: String): Result<Unit>
    suspend fun unsubscribeChannel(channelId: String): Result<Unit>
    suspend fun requestJoinPrivateChannel(channelId: String): Result<Unit>
    fun observeJoinRequests(channelId: String): Flow<List<ChannelJoinRequest>>
    suspend fun approveJoinRequest(channelId: String, requestId: String, requesterUid: String): Result<Unit>
    suspend fun rejectJoinRequest(channelId: String, requestId: String): Result<Unit>
    suspend fun setModerator(channelId: String, targetUid: String, makeModerator: Boolean): Result<Unit>
    fun observePosts(channelId: String): Flow<List<ChannelPost>>
    fun publishPost(
        channelId: String,
        type: ChannelPostType,
        text: String,
        mediaUri: Uri? = null,
        fileName: String = "",
        mimeType: String = "",
        sizeBytes: Long = 0L,
        durationMs: Long = 0L
    ): Flow<Result<Float>>
    suspend fun editPost(channelId: String, postId: String, newText: String): Result<Unit>
    suspend fun deletePost(channelId: String, postId: String): Result<Unit>
    suspend fun pinPost(channelId: String, postId: String, pinned: Boolean): Result<Unit>
    suspend fun toggleReaction(channelId: String, postId: String, emoji: String): Result<Unit>
    fun observeComments(channelId: String, postId: String): Flow<List<ChannelComment>>
    suspend fun addComment(channelId: String, postId: String, text: String): Result<Unit>
    suspend fun deleteComment(channelId: String, postId: String, commentId: String): Result<Unit>
}

sealed interface StoryPublishEvent {
    data class Progress(val fraction: Float) : StoryPublishEvent
    data object Success : StoryPublishEvent
    data class Failure(val errorMessage: String) : StoryPublishEvent
}

interface StoryRepository {
    fun observeActiveStories(): Flow<List<StoryItem>>
    fun observeStoryViewers(storyId: String): Flow<List<StoryViewer>>
    suspend fun publishTextStory(
        text: String,
        backgroundColorHex: Long,
        visibility: PrivacyVisibility,
        audienceMode: StoryAudienceMode = StoryAudienceMode.CONTACTS,
        allowedViewerIds: List<String> = emptyList(),
        excludedViewerIds: List<String> = emptyList()
    ): Result<Unit>
    fun publishMediaStory(
        uri: Uri,
        isVideo: Boolean,
        caption: String,
        visibility: PrivacyVisibility,
        audienceMode: StoryAudienceMode = StoryAudienceMode.CONTACTS,
        allowedViewerIds: List<String> = emptyList(),
        excludedViewerIds: List<String> = emptyList()
    ): Flow<StoryPublishEvent>
    fun publishAudioStory(
        audioUri: Uri,
        durationMs: Long,
        caption: String,
        visibility: PrivacyVisibility,
        audienceMode: StoryAudienceMode = StoryAudienceMode.CONTACTS,
        allowedViewerIds: List<String> = emptyList(),
        excludedViewerIds: List<String> = emptyList()
    ): Flow<StoryPublishEvent>
    suspend fun markStoryViewed(storyId: String)
    suspend fun deleteStory(storyId: String): Result<Unit>
}

interface CallRepository {
    fun observeCallHistory(): Flow<List<CallRecord>>
    fun observeIncomingCalls(): Flow<CallRecord?>
    fun observeCall(callId: String): Flow<CallRecord?>
    suspend fun initiateCall(callee: WesalUser, isVideo: Boolean, offerSdp: String): Result<String>
    suspend fun acknowledgeIncomingRing(callId: String): Result<Unit>
    suspend fun submitAnswer(callId: String, answerSdp: String): Result<Unit>
    suspend fun addIceCandidate(callId: String, sdpMid: String, sdpMLineIndex: Int, candidate: String, fromCaller: Boolean)
    fun observeRemoteIceCandidates(callId: String, listenForCaller: Boolean): Flow<Triple<String, Int, String>>
    suspend fun updateCallStatus(callId: String, status: CallStatus, durationSeconds: Long = 0L)
    suspend fun deleteCallForMe(callId: String): Result<Unit>
    suspend fun deleteCallsForMe(callIds: List<String>): Result<Unit>
    suspend fun clearCallHistoryForMe(): Result<Unit>
}
