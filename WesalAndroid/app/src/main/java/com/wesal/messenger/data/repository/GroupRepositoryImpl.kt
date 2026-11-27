package com.wesal.messenger.data.repository

import android.content.Context
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.domain.model.GroupAddPrivacy
import com.wesal.messenger.domain.model.GroupInfo
import com.wesal.messenger.domain.model.GroupJoinMode
import com.wesal.messenger.domain.model.GroupJoinRequest
import com.wesal.messenger.domain.model.MessageStatus
import com.wesal.messenger.domain.repository.GroupRepository
import com.wesal.messenger.domain.repository.UserRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
    private val userRepository: UserRepository
) : GroupRepository {

    private fun observeAuthUid(): Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { fa -> trySend(fa.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    private fun generateInviteCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        return buildString(10) {
            repeat(10) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapGroup(doc: DocumentSnapshot): GroupInfo? {
        if (!doc.exists()) return null
        return GroupInfo(
            groupId = doc.id,
            chatId = doc.getString("chatId").orEmpty(),
            name = doc.getString("name").orEmpty(),
            description = doc.getString("description").orEmpty(),
            avatarUrl = doc.getString("avatarUrl").orEmpty(),
            creatorId = doc.getString("creatorId").orEmpty(),
            adminIds = (doc.get("adminIds") as? List<String>) ?: emptyList(),
            memberIds = (doc.get("memberIds") as? List<String>) ?: emptyList(),
            joinMode = GroupJoinMode.fromRaw(doc.getString("joinMode")),
            inviteCode = doc.getString("inviteCode").orEmpty(),
            inviteRevokedAt = doc.getLong("inviteRevokedAt") ?: 0L,
            createdAt = doc.getLong("createdAt") ?: 0L
        )
    }

    private fun mapJoinRequest(doc: DocumentSnapshot, groupId: String): GroupJoinRequest? {
        if (!doc.exists()) return null
        return GroupJoinRequest(
            requestId = doc.getString("requestId") ?: doc.id,
            groupId = doc.getString("groupId") ?: groupId,
            requesterUid = doc.getString("requesterUid").orEmpty(),
            requesterName = doc.getString("requesterName").orEmpty(),
            requesterNumericId = doc.getString("requesterNumericId").orEmpty(),
            requesterAvatarUrl = doc.getString("requesterAvatarUrl").orEmpty(),
            status = doc.getString("status") ?: "PENDING",
            createdAt = doc.getLong("createdAt") ?: 0L
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeUserGroups(): Flow<List<GroupInfo>> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(emptyList())
        } else {
            callbackFlow {
                val reg = firestore.collection(Constants.COLLECTION_GROUPS)
                    .whereArrayContains("memberIds", uid)
                    .addSnapshotListener { snap, err ->
                        if (err != null || snap == null) return@addSnapshotListener
                        val list = snap.documents.mapNotNull(::mapGroup)
                            .sortedByDescending { it.createdAt }
                        trySend(list)
                    }
                awaitClose { reg.remove() }
            }
        }
    }

    override fun observeGroup(groupId: String): Flow<GroupInfo?> = callbackFlow {
        val reg = firestore.collection(Constants.COLLECTION_GROUPS)
            .document(groupId)
            .addSnapshotListener { doc, err ->
                if (err != null) return@addSnapshotListener
                trySend(doc?.let(::mapGroup))
            }
        awaitClose { reg.remove() }
    }

    override suspend fun findGroupByInviteCode(code: String): GroupInfo? {
        val clean = code.trim()
            .removePrefix("wesal://group?code=")
            .substringAfter("code=", code.trim())
            .trim()
            .uppercase()
        if (clean.isBlank()) return null
        val snap = firestore.collection(Constants.COLLECTION_GROUPS)
            .whereEqualTo("inviteCode", clean)
            .limit(1)
            .get()
            .await()
        val group = snap.documents.firstOrNull()?.let(::mapGroup) ?: return null
        if (group.joinMode == GroupJoinMode.CLOSED) return null
        return group
    }

    private suspend fun filterAllowedGroupMembers(
        candidateIds: List<String>,
        inviterUid: String
    ): Pair<List<String>, List<String>> {
        val allowed = mutableListOf<String>()
        val rejectedNames = mutableListOf<String>()
        for (targetUid in candidateIds.filter { it.isNotBlank() && it != inviterUid }.distinct()) {
            val targetUser = userRepository.getUserOnce(targetUid)
            val label = targetUser?.displayName?.ifBlank { "@${targetUser.publicId}" } ?: targetUid
            val privacy = targetUser?.groupAddPrivacy ?: GroupAddPrivacy.EVERYONE
            val canAdd = when (privacy) {
                GroupAddPrivacy.EVERYONE -> true
                GroupAddPrivacy.CONTACTS -> userRepository.areContactsAccepted(targetUid)
                GroupAddPrivacy.NOBODY -> false
            }
            if (canAdd) {
                allowed += targetUid
            } else {
                rejectedNames += label
            }
        }
        return allowed to rejectedNames
    }

    override suspend fun createGroup(
        name: String,
        description: String,
        memberIds: List<String>,
        avatarUri: Uri?,
        joinMode: GroupJoinMode
    ): Result<GroupInfo> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val trimmedName = name.trim()
        require(trimmedName.isNotBlank()) { "اسم المجموعة مطلوب" }

        val nonSelfCandidates = memberIds.filter { it.isNotBlank() && it != uid }.distinct()
        val (allowedOthers, rejectedNames) = filterAllowedGroupMembers(nonSelfCandidates, uid)
        if (nonSelfCandidates.isNotEmpty() && allowedOthers.isEmpty()) {
            error("تعذر إضافة الأعضاء (${rejectedNames.joinToString("، ")}) بسبب إعدادات خصوصية المجموعات لديهم")
        }

        val groupId = UUID.randomUUID().toString()
        val chatId = "grp_$groupId"
        val allMembers = (allowedOthers + uid).distinct()

        val avatarUrl = if (avatarUri != null) {
            withContext(Dispatchers.IO) {
                uploadProfileMediaToStorageOrSmallFallback(
                    ctx = context,
                    storagePath = "groups/$groupId/avatar_${System.currentTimeMillis()}.jpg",
                    uri = avatarUri,
                    maxSide = 512,
                    quality = 80
                )
            }
        } else {
            ""
        }

        val now = System.currentTimeMillis()
        val initialInviteCode = if (joinMode == GroupJoinMode.CLOSED) "" else generateInviteCode()
        val groupInfo = GroupInfo(
            groupId = groupId,
            chatId = chatId,
            name = trimmedName,
            description = description.trim(),
            avatarUrl = avatarUrl,
            creatorId = uid,
            adminIds = listOf(uid),
            memberIds = allMembers,
            joinMode = joinMode,
            inviteCode = initialInviteCode,
            inviteRevokedAt = if (joinMode == GroupJoinMode.CLOSED) now else 0L,
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
            "joinMode" to joinMode.raw,
            "inviteCode" to initialInviteCode,
            "inviteRevokedAt" to groupInfo.inviteRevokedAt,
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
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val groupRef = firestore.collection(Constants.COLLECTION_GROUPS).document(groupId)
        val snap = groupRef.get().await()
        @Suppress("UNCHECKED_CAST")
        val admins = (snap.get("adminIds") as? List<String>).orEmpty()
        require(admins.contains(uid)) { "فقط مشرف المجموعة يمكنه تعديل البيانات" }

        val chatId = snap.getString("chatId").orEmpty()
        val trimmedName = name.trim()
        require(trimmedName.isNotBlank()) { "اسم المجموعة مطلوب" }

        val updates = hashMapOf<String, Any>(
            "name" to trimmedName,
            "description" to description.trim()
        )
        val chatUpdates = hashMapOf<String, Any>("groupName" to trimmedName)

        if (avatarUri != null) {
            val url = withContext(Dispatchers.IO) {
                uploadProfileMediaToStorageOrSmallFallback(
                    ctx = context,
                    storagePath = "groups/$groupId/avatar_${System.currentTimeMillis()}.jpg",
                    uri = avatarUri,
                    maxSide = 512,
                    quality = 80
                )
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

    override suspend fun updateGroupJoinMode(groupId: String, joinMode: GroupJoinMode): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val groupRef = firestore.collection(Constants.COLLECTION_GROUPS).document(groupId)
        val snap = groupRef.get().await()
        @Suppress("UNCHECKED_CAST")
        val admins = (snap.get("adminIds") as? List<String>).orEmpty()
        require(admins.contains(uid)) { "فقط مشرف المجموعة يمكنه تغيير نظام الانضمام" }

        val now = System.currentTimeMillis()
        val updates = hashMapOf<String, Any>("joinMode" to joinMode.raw)
        if (joinMode == GroupJoinMode.CLOSED) {
            updates["inviteCode"] = ""
            updates["inviteRevokedAt"] = now
        } else {
            val existingCode = snap.getString("inviteCode").orEmpty()
            if (existingCode.isBlank()) {
                updates["inviteCode"] = generateInviteCode()
            }
        }
        groupRef.update(updates).await()
    }

    override suspend fun regenerateGroupInviteCode(groupId: String): Result<String> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val groupRef = firestore.collection(Constants.COLLECTION_GROUPS).document(groupId)
        val snap = groupRef.get().await()
        @Suppress("UNCHECKED_CAST")
        val admins = (snap.get("adminIds") as? List<String>).orEmpty()
        require(admins.contains(uid)) { "فقط مشرف المجموعة يمكنه تجديد رابط الدعوة" }

        val mode = GroupJoinMode.fromRaw(snap.getString("joinMode"))
        require(mode != GroupJoinMode.CLOSED) { "المجموعة مغلقة حالياً — غيّر وضع الانضمام أولاً" }

        val newCode = generateInviteCode()
        groupRef.update(
            mapOf(
                "inviteCode" to newCode,
                "inviteRevokedAt" to System.currentTimeMillis()
            )
        ).await()
        newCode
    }

    override suspend fun requestJoinGroup(groupId: String): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val groupRef = firestore.collection(Constants.COLLECTION_GROUPS).document(groupId)
        val snap = groupRef.get().await()
        require(snap.exists()) { "المجموعة غير موجودة" }

        @Suppress("UNCHECKED_CAST")
        val members = (snap.get("memberIds") as? List<String>).orEmpty()
        if (members.contains(uid)) return@runCatching

        val mode = GroupJoinMode.fromRaw(snap.getString("joinMode"))
        when (mode) {
            GroupJoinMode.CLOSED -> error("تم إغلاق الانضمام لهذه المجموعة من قِبل الإدارة")
            GroupJoinMode.OPEN, GroupJoinMode.INVITE_ONLY -> {
                val chatId = snap.getString("chatId").orEmpty()
                groupRef.update("memberIds", FieldValue.arrayUnion(uid)).await()
                if (chatId.isNotBlank()) {
                    firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
                        .update(
                            mapOf(
                                "participantIds" to FieldValue.arrayUnion(uid),
                                "unreadCounts.$uid" to 0
                            )
                        )
                        .await()
                }
            }
            GroupJoinMode.APPROVAL_REQUIRED -> {
                val me = userRepository.getUserOnce(uid)
                val reqData = mapOf(
                    "requestId" to uid,
                    "groupId" to groupId,
                    "requesterUid" to uid,
                    "requesterName" to (me?.displayName?.ifBlank { "@${me.publicId}" } ?: "WESAL User"),
                    "requesterNumericId" to (me?.numericId ?: ""),
                    "requesterAvatarUrl" to (me?.photoUrl ?: ""),
                    "status" to "PENDING",
                    "createdAt" to System.currentTimeMillis()
                )
                groupRef.collection(Constants.SUBCOLLECTION_JOIN_REQUESTS)
                    .document(uid)
                    .set(reqData)
                    .await()
            }
        }
    }

    override fun observeGroupJoinRequests(groupId: String): Flow<List<GroupJoinRequest>> = callbackFlow {
        val reg = firestore.collection(Constants.COLLECTION_GROUPS)
            .document(groupId)
            .collection(Constants.SUBCOLLECTION_JOIN_REQUESTS)
            .whereEqualTo("status", "PENDING")
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener
                val list = snap.documents.mapNotNull { mapJoinRequest(it, groupId) }
                    .sortedByDescending { it.createdAt }
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    override suspend fun approveGroupJoinRequest(
        groupId: String,
        requestId: String,
        requesterUid: String
    ): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val groupRef = firestore.collection(Constants.COLLECTION_GROUPS).document(groupId)
        val snap = groupRef.get().await()
        @Suppress("UNCHECKED_CAST")
        val admins = (snap.get("adminIds") as? List<String>).orEmpty()
        require(admins.contains(uid)) { "فقط مشرف المجموعة يمكنه قبول طلبات الانضمام" }

        val chatId = snap.getString("chatId").orEmpty()
        val reqRef = groupRef.collection(Constants.SUBCOLLECTION_JOIN_REQUESTS).document(requestId)
        val batch = firestore.batch()
        batch.update(groupRef, "memberIds", FieldValue.arrayUnion(requesterUid))
        if (chatId.isNotBlank()) {
            val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
            batch.update(
                chatRef,
                mapOf(
                    "participantIds" to FieldValue.arrayUnion(requesterUid),
                    "unreadCounts.$requesterUid" to 0
                )
            )
        }
        batch.update(reqRef, "status", "APPROVED")
        batch.commit().await()
    }

    override suspend fun rejectGroupJoinRequest(groupId: String, requestId: String): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val groupRef = firestore.collection(Constants.COLLECTION_GROUPS).document(groupId)
        val snap = groupRef.get().await()
        @Suppress("UNCHECKED_CAST")
        val admins = (snap.get("adminIds") as? List<String>).orEmpty()
        require(admins.contains(uid)) { "فقط مشرف المجموعة يمكنه رفض طلبات الانضمام" }

        groupRef.collection(Constants.SUBCOLLECTION_JOIN_REQUESTS)
            .document(requestId)
            .update("status", "REJECTED")
            .await()
    }

    /**
     * The pre-§12.5 direct write: group memberIds + linked chat participants,
     * using the client privacy pre-filter's verdict. Kept ONLY as the
     * not-yet-deployed fallback for addMembers (see below) — once the
     * changeGroupMembership function is live and the hardened groups rule is
     * deployed, direct client adds are denied by rules and this path can no
     * longer write anything.
     */
    private suspend fun writeMembersDirectly(groupId: String, allowedIds: List<String>) {
        val groupRef = firestore.collection(Constants.COLLECTION_GROUPS).document(groupId)
        val chatId = groupRef.get().await().getString("chatId").orEmpty()
        groupRef.update("memberIds", FieldValue.arrayUnion(*allowedIds.toTypedArray())).await()
        if (chatId.isNotBlank()) {
            val chatUpdates = hashMapOf<String, Any>(
                "participantIds" to FieldValue.arrayUnion(*allowedIds.toTypedArray())
            )
            allowedIds.forEach { memberUid ->
                chatUpdates["unreadCounts.$memberUid"] = 0
            }
            firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
                .update(chatUpdates)
                .await()
        }
    }

    override suspend fun addMembers(groupId: String, newMemberIds: List<String>): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val cleanIds = newMemberIds.filter { it.isNotBlank() && it != uid }.distinct()
        if (cleanIds.isEmpty()) return@runCatching
        // Client-side pre-filter stays as the UX layer (fast, friendly names).
        // It is NOT the enforcement: the callable re-checks every candidate
        // server-side and its verdict is authoritative (§12.5).
        val (allowedIds, rejectedNames) = filterAllowedGroupMembers(cleanIds, uid)
        if (allowedIds.isEmpty()) {
            error("لا يمكن إضافة الأعضاء المحددين (${rejectedNames.joinToString("، ")}) بسبب إعدادات خصوصية المجموعات لديهم")
        }

        // §12.5: membership additions are performed by the changeGroupMembership
        // callable (Admin SDK), which enforces each candidate's groupAddPrivacy.
        // Degrade-safely contract (deploy ordering):
        //  - UNAVAILABLE / NOT_FOUND / UNIMPLEMENTED mean the function is not
        //    deployed yet -> fall back to the existing client path above
        //    (pre-filter + direct write, still permitted by the not-yet-hardened
        //    groups rule; the hardened rule ships only after the function is
        //    live, so this fallback can never bypass server enforcement once
        //    both are deployed — a late fallback write is simply denied).
        //  - A definitive answer FROM the function (its added/rejected verdict,
        //    or a PERMISSION_DENIED / any other error) is honored as-is and
        //    surfaced — NEVER retried through the client path.
        val resultData = try {
            functions.getHttpsCallable("changeGroupMembership")
                .call(mapOf("groupId" to groupId, "memberIds" to allowedIds))
                .await()
                .data as? Map<*, *>
        } catch (e: FirebaseFunctionsException) {
            when (e.code) {
                FirebaseFunctionsException.Code.UNAVAILABLE,
                FirebaseFunctionsException.Code.NOT_FOUND,
                FirebaseFunctionsException.Code.UNIMPLEMENTED -> {
                    writeMembersDirectly(groupId, allowedIds)
                    return@runCatching
                }
                FirebaseFunctionsException.Code.PERMISSION_DENIED ->
                    throw IllegalStateException("فقط مشرف المجموعة يمكنه إضافة أعضاء", e)
                FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                    throw IllegalStateException("يلزم تسجيل الدخول أولاً", e)
                else ->
                    throw IllegalStateException("تعذر إضافة الأعضاء عبر الخادم — حاول مرة أخرى لاحقاً", e)
            }
        } ?: throw IllegalStateException("تعذر إضافة الأعضاء: رد غير متوقع من الخادم")

        val serverAdded = (resultData["added"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        val serverRejected = (resultData["rejected"] as? List<*>).orEmpty()
        if (serverAdded.isEmpty() && serverRejected.isNotEmpty()) {
            val names = serverRejected.mapNotNull { entry ->
                (entry as? Map<*, *>)?.get("name") as? String
            }
            val label = names.joinToString("، ").ifBlank { rejectedNames.joinToString("، ") }
            error("لا يمكن إضافة الأعضاء المحددين ($label) بسبب إعدادات خصوصية المجموعات لديهم")
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
