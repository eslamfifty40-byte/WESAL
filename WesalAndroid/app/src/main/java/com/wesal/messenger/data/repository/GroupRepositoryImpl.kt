package com.wesal.messenger.data.repository

import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.domain.model.GroupInfo
import com.wesal.messenger.domain.model.MessageStatus
import com.wesal.messenger.domain.repository.GroupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) : GroupRepository {

    private fun observeAuthUid(): Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { fa -> trySend(fa.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("UNCHECKED_CAST")
    override fun observeUserGroups(): Flow<List<GroupInfo>> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(emptyList())
        } else {
            callbackFlow {
                val reg = firestore.collection(Constants.COLLECTION_GROUPS)
                    .whereArrayContains("memberIds", uid)
                    .addSnapshotListener { snap, _ ->
                        val list = snap?.documents?.map { doc ->
                            GroupInfo(
                                groupId = doc.id,
                                chatId = doc.getString("chatId").orEmpty(),
                                name = doc.getString("name").orEmpty(),
                                description = doc.getString("description").orEmpty(),
                                avatarUrl = doc.getString("avatarUrl").orEmpty(),
                                creatorId = doc.getString("creatorId").orEmpty(),
                                adminIds = (doc.get("adminIds") as? List<String>) ?: emptyList(),
                                memberIds = (doc.get("memberIds") as? List<String>) ?: emptyList(),
                                createdAt = doc.getLong("createdAt") ?: 0L
                            )
                        }.orEmpty().sortedByDescending { it.createdAt }
                        trySend(list)
                    }
                awaitClose { reg.remove() }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun observeGroup(groupId: String): Flow<GroupInfo?> = callbackFlow {
        val reg = firestore.collection(Constants.COLLECTION_GROUPS)
            .document(groupId)
            .addSnapshotListener { doc, _ ->
                if (doc == null || !doc.exists()) {
                    trySend(null)
                    return@addSnapshotListener
                }
                trySend(
                    GroupInfo(
                        groupId = doc.id,
                        chatId = doc.getString("chatId").orEmpty(),
                        name = doc.getString("name").orEmpty(),
                        description = doc.getString("description").orEmpty(),
                        avatarUrl = doc.getString("avatarUrl").orEmpty(),
                        creatorId = doc.getString("creatorId").orEmpty(),
                        adminIds = (doc.get("adminIds") as? List<String>) ?: emptyList(),
                        memberIds = (doc.get("memberIds") as? List<String>) ?: emptyList(),
                        createdAt = doc.getLong("createdAt") ?: 0L
                    )
                )
            }
        awaitClose { reg.remove() }
    }

    override suspend fun createGroup(
        name: String,
        description: String,
        memberIds: List<String>,
        avatarUri: Uri?
    ): Result<GroupInfo> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val groupId = UUID.randomUUID().toString()
        val chatId = "grp_$groupId"
        val allMembers = (memberIds + uid).distinct()

        var avatarUrl = ""
        if (avatarUri != null) {
            avatarUrl = withContext(Dispatchers.IO) {
                runCatching { uriToBase64Jpeg(avatarUri, maxSide = 512, quality = 78) }.getOrDefault("")
            }
        }

        val now = System.currentTimeMillis()
        val groupInfo = GroupInfo(
            groupId = groupId,
            chatId = chatId,
            name = name.trim(),
            description = description.trim(),
            avatarUrl = avatarUrl,
            creatorId = uid,
            adminIds = listOf(uid),
            memberIds = allMembers,
            createdAt = now
        )

        val groupMap = mapOf(
            "groupId" to groupId,
            "chatId" to chatId,
            "name" to groupInfo.name,
            "description" to groupInfo.description,
            "avatarUrl" to avatarUrl,
            "creatorId" to uid,
            "adminIds" to listOf(uid),
            "memberIds" to allMembers,
            "createdAt" to now
        )

        val chatMap = mapOf(
            "chatId" to chatId,
            "isGroup" to true,
            "groupId" to groupId,
            "groupName" to groupInfo.name,
            "groupAvatarUrl" to avatarUrl,
            "participantIds" to allMembers,
            "lastMessagePreview" to "تم إنشاء المجموعة",
            "lastMessageTime" to now,
            "lastMessageSenderId" to uid,
            "lastMessageStatus" to MessageStatus.SENT.raw,
            "unreadCounts" to allMembers.associateWith { 0 }
        )

        val batch = firestore.batch()
        batch.set(firestore.collection(Constants.COLLECTION_GROUPS).document(groupId), groupMap)
        batch.set(firestore.collection(Constants.COLLECTION_CHATS).document(chatId), chatMap)
        batch.commit().await()
        groupInfo
    }

    override suspend fun updateGroupInfo(
        groupId: String,
        name: String,
        description: String,
        avatarUri: Uri?
    ): Result<Unit> = runCatching {
        val groupRef = firestore.collection(Constants.COLLECTION_GROUPS).document(groupId)
        val snap = groupRef.get().await()
        val chatId = snap.getString("chatId").orEmpty()

        val updates = hashMapOf<String, Any>(
            "name" to name.trim(),
            "description" to description.trim()
        )
        val chatUpdates = hashMapOf<String, Any>("groupName" to name.trim())

        if (avatarUri != null) {
            val url = withContext(Dispatchers.IO) {
                runCatching { uriToBase64Jpeg(avatarUri, maxSide = 512, quality = 78) }.getOrDefault("")
            }
            if (url.isNotBlank()) {
                updates["avatarUrl"] = url
                chatUpdates["groupAvatarUrl"] = url
            }
        }

        groupRef.update(updates).await()
        if (chatId.isNotBlank()) {
            firestore.collection(Constants.COLLECTION_CHATS).document(chatId).update(chatUpdates).await()
        }
    }

    override suspend fun addMembers(groupId: String, newMemberIds: List<String>): Result<Unit> = runCatching {
        val groupRef = firestore.collection(Constants.COLLECTION_GROUPS).document(groupId)
        val chatId = groupRef.get().await().getString("chatId").orEmpty()
        groupRef.update("memberIds", FieldValue.arrayUnion(*newMemberIds.toTypedArray())).await()
        if (chatId.isNotBlank()) {
            firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
                .update("participantIds", FieldValue.arrayUnion(*newMemberIds.toTypedArray()))
                .await()
        }
    }

    override suspend fun removeMember(groupId: String, targetUid: String): Result<Unit> = runCatching {
        val groupRef = firestore.collection(Constants.COLLECTION_GROUPS).document(groupId)
        val chatId = groupRef.get().await().getString("chatId").orEmpty()
        groupRef.update(
            mapOf(
                "memberIds" to FieldValue.arrayRemove(targetUid),
                "adminIds" to FieldValue.arrayRemove(targetUid)
            )
        ).await()
        if (chatId.isNotBlank()) {
            firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
                .update("participantIds", FieldValue.arrayRemove(targetUid))
                .await()
        }
    }

    override suspend fun setAdminRole(groupId: String, targetUid: String, isAdmin: Boolean): Result<Unit> = runCatching {
        val op = if (isAdmin) FieldValue.arrayUnion(targetUid) else FieldValue.arrayRemove(targetUid)
        firestore.collection(Constants.COLLECTION_GROUPS).document(groupId).update("adminIds", op).await()
    }

    override suspend fun leaveGroup(groupId: String): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not authenticated"))
        return removeMember(groupId, uid)
    }
}
