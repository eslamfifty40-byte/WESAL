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
    suspend fun signUpWithEmail(email: String, password: String): Result<Boolean>
    suspend fun sendPasswordReset(email: String): Result<Unit>
    fun sendOtp(
        phoneNumber: String,
        activity: Activity,
        resendingToken: PhoneAuthProvider.ForceResendingToken? = null
    ): Flow<PhoneAuthEvent>
    suspend fun verifyOtpCode(verificationId: String, smsCode: String): Result<Boolean>
    fun sendOtpForPhoneLink(
        phoneNumber: String,
        activity: Activity,
        resendingToken: PhoneAuthProvider.ForceResendingToken? = null
    ): Flow<PhoneAuthEvent>
    suspend fun verifyOtpAndLinkPhone(verificationId: String, smsCode: String): Result<Unit>
    suspend fun completeUserProfile(
        displayName: String,
        bio: String,
        photoUri: Uri?,
        wesalId: String? = null
    ): Result<WesalUser>
    suspend fun syncFcmToken()
    suspend fun signOut(): Result<Unit>
    fun observeSavedAccounts(): Flow<List<SavedWesalAccount>>
    suspend fun removeSavedAccount(uid: String)
    suspend fun clearActiveSessionForSwitch(previousUid: String? = null)
}

interface UserRepository {
    fun observeCurrentUser(): Flow<WesalUser?>
    fun observeUser(uid: String): Flow<WesalUser?>
    suspend fun getUserOnce(uid: String): WesalUser?
    suspend fun findUserByPublicIdOrUid(idOrUid: String): WesalUser?
    suspend fun searchUsers(query: String): List<WesalUser>
    suspend fun listDiscoverableUsers(limit: Int = 30): List<WesalUser>
    suspend fun findUsersByPhoneHashes(phoneHashes: List<String>): Map<String, WesalUser>
    suspend fun updateProfile(displayName: String, bio: String, publicId: String, photoUri: Uri?): Result<Unit>
    suspend fun ensureNumericIdAssigned(): String
    suspend fun updateOptionalPhone(rawPhone: String): Result<Unit>
    suspend fun removeOptionalPhone(): Result<Unit>
    suspend fun removeProfilePhoto(): Result<Unit>
    suspend fun updatePrivacySettings(settings: PrivacySettings): Result<Unit>
    suspend fun blockUser(targetUid: String): Result<Unit>
    suspend fun unblockUser(targetUid: String): Result<Unit>
    suspend fun reportUser(targetUid: String, reason: String): Result<Unit>
    fun observeSessions(): Flow<List<DeviceSession>>
    suspend fun revokeSession(sessionId: String): Result<Unit>

    // Super Admin & Blue Verification Seal
    fun observeIsSuperAdmin(): Flow<Boolean>
    suspend fun isSuperAdmin(): Boolean
    suspend fun setAccountVerification(targetUid: String, verified: Boolean, note: String = ""): Result<Unit>
    fun observeVerificationAuditLogs(): Flow<List<VerificationAuditLog>>

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
        caption: String = ""
    ): Flow<Result<Float>>
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
    suspend fun toggleArchiveChat(chatId: String, archived: Boolean): Result<Unit>
    suspend fun toggleMuteChat(chatId: String, muted: Boolean): Result<Unit>
    suspend fun deleteChat(chatId: String): Result<Unit>
    suspend fun decryptMediaToCache(message: WesalMessage): Result<java.io.File>
}

interface GroupRepository {
    fun observeUserGroups(): Flow<List<GroupInfo>>
    fun observeGroup(groupId: String): Flow<GroupInfo?>
    suspend fun createGroup(
        name: String,
        description: String,
        memberIds: List<String>,
        avatarUri: Uri?
    ): Result<GroupInfo>
    suspend fun updateGroupInfo(groupId: String, name: String, description: String, avatarUri: Uri?): Result<Unit>
    suspend fun addMembers(groupId: String, newMemberIds: List<String>): Result<Unit>
    suspend fun removeMember(groupId: String, targetUid: String): Result<Unit>
    suspend fun setAdminRole(groupId: String, targetUid: String, isAdmin: Boolean): Result<Unit>
    suspend fun leaveGroup(groupId: String): Result<Unit>
}

interface ChannelRepository {
    fun observePublicChannels(): Flow<List<WesalChannel>>
    fun observeMyChannels(): Flow<List<WesalChannel>>
    fun observeChannel(channelId: String): Flow<WesalChannel?>
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
    suspend fun publishTextStory(text: String, backgroundColorHex: Long, visibility: PrivacyVisibility): Result<Unit>
    fun publishMediaStory(uri: Uri, isVideo: Boolean, caption: String, visibility: PrivacyVisibility): Flow<StoryPublishEvent>
    fun publishAudioStory(audioUri: Uri, durationMs: Long, caption: String, visibility: PrivacyVisibility): Flow<StoryPublishEvent>
    suspend fun markStoryViewed(storyId: String)
    suspend fun deleteStory(storyId: String): Result<Unit>
}

interface CallRepository {
    fun observeCallHistory(): Flow<List<CallRecord>>
    fun observeIncomingCalls(): Flow<CallRecord?>
    fun observeCall(callId: String): Flow<CallRecord?>
    suspend fun initiateCall(callee: WesalUser, isVideo: Boolean, offerSdp: String): Result<String>
    suspend fun submitAnswer(callId: String, answerSdp: String): Result<Unit>
    suspend fun addIceCandidate(callId: String, sdpMid: String, sdpMLineIndex: Int, candidate: String, fromCaller: Boolean)
    fun observeRemoteIceCandidates(callId: String, listenForCaller: Boolean): Flow<Triple<String, Int, String>>
    suspend fun updateCallStatus(callId: String, status: CallStatus, durationSeconds: Long = 0L)
    suspend fun deleteCallForMe(callId: String): Result<Unit>
    suspend fun deleteCallsForMe(callIds: List<String>): Result<Unit>
    suspend fun clearCallHistoryForMe(): Result<Unit>
}
