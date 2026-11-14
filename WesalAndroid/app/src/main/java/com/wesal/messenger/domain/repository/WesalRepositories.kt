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
    fun observeAuthState(): Flow<String?>
    fun sendOtp(
        phoneNumber: String,
        activity: Activity,
        resendingToken: PhoneAuthProvider.ForceResendingToken? = null
    ): Flow<PhoneAuthEvent>
    suspend fun verifyOtpCode(verificationId: String, smsCode: String): Result<Boolean>
    suspend fun completeUserProfile(displayName: String, bio: String, photoUri: Uri?, phone: String = ""): Result<WesalUser>
    suspend fun syncFcmToken()
    suspend fun signOut(): Result<Unit>
}

interface UserRepository {
    fun observeCurrentUser(): Flow<WesalUser?>
    fun observeUser(uid: String): Flow<WesalUser?>
    suspend fun getUserOnce(uid: String): WesalUser?
    suspend fun searchUsers(query: String): List<WesalUser>
    suspend fun updateProfile(displayName: String, bio: String, publicId: String, photoUri: Uri?): Result<Unit>
    suspend fun updatePrivacySettings(settings: PrivacySettings): Result<Unit>
    suspend fun blockUser(targetUid: String): Result<Unit>
    suspend fun unblockUser(targetUid: String): Result<Unit>
    suspend fun reportUser(targetUid: String, reason: String): Result<Unit>
    fun observeSessions(): Flow<List<DeviceSession>>
    suspend fun revokeSession(sessionId: String): Result<Unit>
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

interface StoryRepository {
    fun observeActiveStories(): Flow<List<StoryItem>>
    suspend fun publishTextStory(text: String, backgroundColorHex: Long, visibility: PrivacyVisibility): Result<Unit>
    suspend fun publishMediaStory(uri: Uri, isVideo: Boolean, caption: String, visibility: PrivacyVisibility): Result<Unit>
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
}
