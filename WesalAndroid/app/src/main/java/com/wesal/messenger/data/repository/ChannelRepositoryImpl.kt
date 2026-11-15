package com.wesal.messenger.data.repository

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.domain.model.ChannelComment
import com.wesal.messenger.domain.model.ChannelJoinRequest
import com.wesal.messenger.domain.model.ChannelPost
import com.wesal.messenger.domain.model.ChannelPostType
import com.wesal.messenger.domain.model.WesalChannel
import com.wesal.messenger.domain.repository.ChannelRepository
import com.wesal.messenger.domain.repository.UserRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val userRepository: UserRepository
) : ChannelRepository {

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

    private fun mapChannel(doc: DocumentSnapshot): WesalChannel? {
        if (!doc.exists()) return null
        val subscribers = (doc.get("subscriberIds") as? List<String>).orEmpty()
        return WesalChannel(
            channelId = doc.getString("channelId") ?: doc.id,
            title = doc.getString("title").orEmpty(),
            description = doc.getString("description").orEmpty(),
            avatarUrl = doc.getString("avatarUrl").orEmpty(),
            isPublic = doc.getBoolean("isPublic") ?: true,
            inviteCode = doc.getString("inviteCode").orEmpty(),
            ownerUid = doc.getString("ownerUid").orEmpty(),
            moderatorIds = (doc.get("moderatorIds") as? List<String>).orEmpty(),
            subscriberIds = subscribers,
            subscribersCount = (doc.getLong("subscribersCount") ?: subscribers.size.toLong()).toInt(),
            isVerified = doc.getBoolean("isVerified") ?: false,
            createdAt = doc.getLong("createdAt") ?: 0L
        )
    }

    private fun mapPost(doc: DocumentSnapshot, channelId: String): ChannelPost? {
        if (!doc.exists()) return null
        @Suppress("UNCHECKED_CAST")
        val rawReactions = (doc.get("reactionCounts") as? Map<String, Number>).orEmpty()
            .mapValues { it.value.toInt() }
        @Suppress("UNCHECKED_CAST")
        val userReactions = (doc.get("userReactions") as? Map<String, String>).orEmpty()
        return ChannelPost(
            postId = doc.getString("postId") ?: doc.id,
            channelId = doc.getString("channelId") ?: channelId,
            authorUid = doc.getString("authorUid").orEmpty(),
            authorName = doc.getString("authorName").orEmpty(),
            authorAvatarUrl = doc.getString("authorAvatarUrl").orEmpty(),
            authorVerified = doc.getBoolean("authorVerified") ?: false,
            type = ChannelPostType.fromRaw(doc.getString("type")),
            text = doc.getString("text").orEmpty(),
            mediaUrl = doc.getString("mediaUrl").orEmpty(),
            mediaMimeType = doc.getString("mediaMimeType").orEmpty(),
            mediaFileName = doc.getString("mediaFileName").orEmpty(),
            mediaSizeBytes = doc.getLong("mediaSizeBytes") ?: 0L,
            mediaDurationMs = doc.getLong("mediaDurationMs") ?: 0L,
            reactionCounts = rawReactions,
            userReactions = userReactions,
            isPinned = doc.getBoolean("isPinned") ?: false,
            commentsCount = (doc.getLong("commentsCount") ?: 0L).toInt(),
            createdAt = doc.getLong("createdAt") ?: 0L
        )
    }

    private fun mapComment(doc: DocumentSnapshot, channelId: String, postId: String): ChannelComment? {
        if (!doc.exists()) return null
        return ChannelComment(
            commentId = doc.getString("commentId") ?: doc.id,
            postId = doc.getString("postId") ?: postId,
            channelId = doc.getString("channelId") ?: channelId,
            authorUid = doc.getString("authorUid").orEmpty(),
            authorName = doc.getString("authorName").orEmpty(),
            authorAvatarUrl = doc.getString("authorAvatarUrl").orEmpty(),
            authorVerified = doc.getBoolean("authorVerified") ?: false,
            text = doc.getString("text").orEmpty(),
            createdAt = doc.getLong("createdAt") ?: 0L
        )
    }

    private fun mapJoinRequest(doc: DocumentSnapshot, channelId: String): ChannelJoinRequest? {
        if (!doc.exists()) return null
        return ChannelJoinRequest(
            requestId = doc.getString("requestId") ?: doc.id,
            channelId = doc.getString("channelId") ?: channelId,
            requesterUid = doc.getString("requesterUid").orEmpty(),
            requesterName = doc.getString("requesterName").orEmpty(),
            requesterPublicId = doc.getString("requesterPublicId").orEmpty(),
            requesterAvatarUrl = doc.getString("requesterAvatarUrl").orEmpty(),
            status = doc.getString("status") ?: "PENDING",
            createdAt = doc.getLong("createdAt") ?: 0L
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observePublicChannels(): Flow<List<WesalChannel>> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(emptyList())
        } else {
            callbackFlow {
                val reg = firestore.collection(Constants.COLLECTION_CHANNELS)
                    .whereEqualTo("isPublic", true)
                    .limit(100)
                    .addSnapshotListener { snap, err ->
                        if (err != null || snap == null) return@addSnapshotListener
                        val list = snap.documents.mapNotNull(::mapChannel)
                            .sortedByDescending { it.subscribersCount }
                        trySend(list)
                    }
                awaitClose { reg.remove() }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeMyChannels(): Flow<List<WesalChannel>> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(emptyList())
        } else {
            callbackFlow {
                val reg = firestore.collection(Constants.COLLECTION_CHANNELS)
                    .whereArrayContains("subscriberIds", uid)
                    .addSnapshotListener { snap, err ->
                        if (err != null || snap == null) return@addSnapshotListener
                        val list = snap.documents.mapNotNull(::mapChannel)
                            .sortedByDescending { it.createdAt }
                        trySend(list)
                    }
                awaitClose { reg.remove() }
            }
        }
    }

    override fun observeChannel(channelId: String): Flow<WesalChannel?> = callbackFlow {
        val reg = firestore.collection(Constants.COLLECTION_CHANNELS)
            .document(channelId)
            .addSnapshotListener { doc, err ->
                if (err != null) return@addSnapshotListener
                trySend(doc?.let(::mapChannel))
            }
        awaitClose { reg.remove() }
    }

    override suspend fun findChannelByInviteCode(code: String): WesalChannel? {
        val clean = code.trim().uppercase()
        if (clean.isBlank()) return null
        val snap = firestore.collection(Constants.COLLECTION_CHANNELS)
            .whereEqualTo("inviteCode", clean)
            .limit(1)
            .get()
            .await()
        return snap.documents.firstOrNull()?.let(::mapChannel)
    }

    override suspend fun createChannel(
        title: String,
        description: String,
        isPublic: Boolean,
        avatarUri: Uri?
    ): Result<WesalChannel> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val trimmedTitle = title.trim()
        require(trimmedTitle.isNotBlank()) { "اسم القناة مطلوب" }

        val channelId = UUID.randomUUID().toString()
        val avatarUrl = if (avatarUri != null) uploadChannelAvatar(channelId, avatarUri) else ""
        val inviteCode = generateInviteCode()
        val now = System.currentTimeMillis()
        val me = userRepository.getUserOnce(uid)

        val channel = WesalChannel(
            channelId = channelId,
            title = trimmedTitle,
            description = description.trim(),
            avatarUrl = avatarUrl,
            isPublic = isPublic,
            inviteCode = inviteCode,
            ownerUid = uid,
            moderatorIds = listOf(uid),
            subscriberIds = listOf(uid),
            subscribersCount = 1,
            isVerified = me?.isVerified == true,
            createdAt = now
        )

        val docData = mapOf(
            "channelId" to channel.channelId,
            "title" to channel.title,
            "description" to channel.description,
            "avatarUrl" to channel.avatarUrl,
            "isPublic" to channel.isPublic,
            "inviteCode" to channel.inviteCode,
            "ownerUid" to channel.ownerUid,
            "moderatorIds" to channel.moderatorIds,
            "subscriberIds" to channel.subscriberIds,
            "subscribersCount" to channel.subscribersCount,
            "isVerified" to channel.isVerified,
            "createdAt" to channel.createdAt
        )
        firestore.collection(Constants.COLLECTION_CHANNELS)
            .document(channelId)
            .set(docData)
            .await()

        channel
    }

    override suspend fun updateChannel(
        channelId: String,
        title: String,
        description: String,
        isPublic: Boolean,
        avatarUri: Uri?
    ): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val ref = firestore.collection(Constants.COLLECTION_CHANNELS).document(channelId)
        val snap = ref.get().await()
        val owner = snap.getString("ownerUid")
        val mods = (snap.get("moderatorIds") as? List<String>).orEmpty()
        require(uid == owner || mods.contains(uid)) { "ليس لديك صلاحية تعديل القناة" }

        val updates = mutableMapOf<String, Any>(
            "title" to title.trim(),
            "description" to description.trim(),
            "isPublic" to isPublic
        )
        if (avatarUri != null) {
            updates["avatarUrl"] = uploadChannelAvatar(channelId, avatarUri)
        }
        ref.update(updates).await()
    }

    override suspend fun regenerateInviteCode(channelId: String): Result<String> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val ref = firestore.collection(Constants.COLLECTION_CHANNELS).document(channelId)
        val snap = ref.get().await()
        val owner = snap.getString("ownerUid")
        val mods = (snap.get("moderatorIds") as? List<String>).orEmpty()
        require(uid == owner || mods.contains(uid)) { "ليس لديك صلاحية تجديد رابط الدعوة" }

        val newCode = generateInviteCode()
        ref.update("inviteCode", newCode).await()
        newCode
    }

    override suspend fun subscribePublicChannel(channelId: String): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val ref = firestore.collection(Constants.COLLECTION_CHANNELS).document(channelId)
        firestore.runTransaction { tx ->
            val snap = tx.get(ref)
            val current = (snap.get("subscriberIds") as? List<String>).orEmpty()
            if (!current.contains(uid)) {
                val updated = current + uid
                tx.update(
                    ref,
                    mapOf(
                        "subscriberIds" to updated,
                        "subscribersCount" to updated.size
                    )
                )
            }
        }.await()
    }

    override suspend fun unsubscribeChannel(channelId: String): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val ref = firestore.collection(Constants.COLLECTION_CHANNELS).document(channelId)
        firestore.runTransaction { tx ->
            val snap = tx.get(ref)
            require(snap.getString("ownerUid") != uid) { "لا يمكن لمالك القناة مغادرتها" }
            val current = (snap.get("subscriberIds") as? List<String>).orEmpty()
            if (current.contains(uid)) {
                val updated = current.filterNot { it == uid }
                val mods = (snap.get("moderatorIds") as? List<String>).orEmpty().filterNot { it == uid }
                tx.update(
                    ref,
                    mapOf(
                        "subscriberIds" to updated,
                        "moderatorIds" to mods,
                        "subscribersCount" to updated.size
                    )
                )
            }
        }.await()
    }

    override suspend fun requestJoinPrivateChannel(channelId: String): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val me = userRepository.getUserOnce(uid)
        val reqData = mapOf(
            "requestId" to uid,
            "channelId" to channelId,
            "requesterUid" to uid,
            "requesterName" to (me?.displayName?.ifBlank { "@${me.publicId}" } ?: "WESAL User"),
            "requesterPublicId" to (me?.publicId ?: ""),
            "requesterAvatarUrl" to (me?.photoUrl ?: ""),
            "status" to "PENDING",
            "createdAt" to System.currentTimeMillis()
        )
        firestore.collection(Constants.COLLECTION_CHANNELS)
            .document(channelId)
            .collection(Constants.SUBCOLLECTION_JOIN_REQUESTS)
            .document(uid)
            .set(reqData)
            .await()
    }

    override fun observeJoinRequests(channelId: String): Flow<List<ChannelJoinRequest>> = callbackFlow {
        val reg = firestore.collection(Constants.COLLECTION_CHANNELS)
            .document(channelId)
            .collection(Constants.SUBCOLLECTION_JOIN_REQUESTS)
            .whereEqualTo("status", "PENDING")
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener
                val list = snap.documents.mapNotNull { mapJoinRequest(it, channelId) }
                    .sortedByDescending { it.createdAt }
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    override suspend fun approveJoinRequest(
        channelId: String,
        requestId: String,
        requesterUid: String
    ): Result<Unit> = runCatching {
        val channelRef = firestore.collection(Constants.COLLECTION_CHANNELS).document(channelId)
        val reqRef = channelRef.collection(Constants.SUBCOLLECTION_JOIN_REQUESTS).document(requestId)
        firestore.runTransaction { tx ->
            val snap = tx.get(channelRef)
            val subs = (snap.get("subscriberIds") as? List<String>).orEmpty()
            val updated = if (subs.contains(requesterUid)) subs else subs + requesterUid
            tx.update(
                channelRef,
                mapOf(
                    "subscriberIds" to updated,
                    "subscribersCount" to updated.size
                )
            )
            tx.update(reqRef, "status", "APPROVED")
        }.await()
    }

    override suspend fun rejectJoinRequest(channelId: String, requestId: String): Result<Unit> = runCatching {
        firestore.collection(Constants.COLLECTION_CHANNELS)
            .document(channelId)
            .collection(Constants.SUBCOLLECTION_JOIN_REQUESTS)
            .document(requestId)
            .update("status", "REJECTED")
            .await()
    }

    override suspend fun setModerator(
        channelId: String,
        targetUid: String,
        makeModerator: Boolean
    ): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val ref = firestore.collection(Constants.COLLECTION_CHANNELS).document(channelId)
        val snap = ref.get().await()
        require(snap.getString("ownerUid") == uid) { "فقط مالك القناة يمكنه تعيين المشرفين" }
        val op = if (makeModerator) FieldValue.arrayUnion(targetUid) else FieldValue.arrayRemove(targetUid)
        ref.update("moderatorIds", op).await()
    }

    override fun observePosts(channelId: String): Flow<List<ChannelPost>> = callbackFlow {
        val reg = firestore.collection(Constants.COLLECTION_CHANNELS)
            .document(channelId)
            .collection(Constants.SUBCOLLECTION_POSTS)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(150)
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener
                val list = snap.documents.mapNotNull { mapPost(it, channelId) }
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    override fun publishPost(
        channelId: String,
        type: ChannelPostType,
        text: String,
        mediaUri: Uri?,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        durationMs: Long
    ): Flow<Result<Float>> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            trySend(Result.failure(IllegalStateException("Not authenticated")))
            close()
            return@callbackFlow
        }

        launch {
            try {
                val channelRef = firestore.collection(Constants.COLLECTION_CHANNELS).document(channelId)
                val channelSnap = channelRef.get().await()
                val ownerUid = channelSnap.getString("ownerUid")
                val moderators = (channelSnap.get("moderatorIds") as? List<String>).orEmpty()
                require(uid == ownerUid || moderators.contains(uid)) {
                    "فقط المالك والمشرفون يمكنهم النشر في القناة"
                }

                val me = userRepository.getUserOnce(uid)
                var mediaUrl = ""
                if (mediaUri != null) {
                    trySend(Result.success(0.25f))
                    mediaUrl = when (type) {
                        ChannelPostType.IMAGE -> {
                            val b64 = withContext(Dispatchers.IO) {
                                uriToBase64Jpeg(context, mediaUri, maxSide = 720, quality = 72)
                            }
                            require(b64.isNotBlank()) { "تعذر ضغط الصورة المحددة" }
                            trySend(Result.success(0.85f))
                            b64
                        }
                        ChannelPostType.AUDIO -> {
                            val audioB64 = withContext(Dispatchers.IO) {
                                uriToBase64Audio(context, mediaUri, maxBytes = 420_000)
                            } ?: throw IllegalStateException("المقطع الصوتي أكبر من الحد المسموح به (420 كيلوبايت) بدون Firebase Storage")
                            trySend(Result.success(0.85f))
                            audioB64
                        }
                        else -> {
                            throw IllegalStateException("نشر الفيديو والملفات الكبيرة غير متاح لأن Firebase Storage غير مفعّل — يمكنك نشر نصوص أو صور مضغوطة أو مقاطع صوتية قصيرة")
                        }
                    }
                } else {
                    trySend(Result.success(0.5f))
                }

                val postId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val postData = mapOf(
                    "postId" to postId,
                    "channelId" to channelId,
                    "authorUid" to uid,
                    "authorName" to (me?.displayName?.ifBlank { "@${me.publicId}" } ?: "WESAL Channel"),
                    "authorAvatarUrl" to (me?.photoUrl ?: ""),
                    "authorVerified" to (me?.isVerified == true),
                    "type" to type.raw,
                    "text" to text.trim(),
                    "mediaUrl" to mediaUrl,
                    "mediaMimeType" to mimeType,
                    "mediaFileName" to fileName,
                    "mediaSizeBytes" to sizeBytes,
                    "mediaDurationMs" to durationMs,
                    "reactionCounts" to emptyMap<String, Int>(),
                    "userReactions" to emptyMap<String, String>(),
                    "isPinned" to false,
                    "commentsCount" to 0,
                    "createdAt" to now
                )
                channelRef.collection(Constants.SUBCOLLECTION_POSTS)
                    .document(postId)
                    .set(postData)
                    .await()

                trySend(Result.success(1.0f))
                close()
            } catch (e: Exception) {
                trySend(Result.failure(e))
                close()
            }
        }
        awaitClose { }
    }

    override suspend fun deletePost(channelId: String, postId: String): Result<Unit> = runCatching {
        firestore.collection(Constants.COLLECTION_CHANNELS)
            .document(channelId)
            .collection(Constants.SUBCOLLECTION_POSTS)
            .document(postId)
            .delete()
            .await()
    }

    override suspend fun pinPost(channelId: String, postId: String, pinned: Boolean): Result<Unit> = runCatching {
        firestore.collection(Constants.COLLECTION_CHANNELS)
            .document(channelId)
            .collection(Constants.SUBCOLLECTION_POSTS)
            .document(postId)
            .update("isPinned", pinned)
            .await()
    }

    override suspend fun toggleReaction(channelId: String, postId: String, emoji: String): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val postRef = firestore.collection(Constants.COLLECTION_CHANNELS)
            .document(channelId)
            .collection(Constants.SUBCOLLECTION_POSTS)
            .document(postId)

        firestore.runTransaction { tx ->
            val snap = tx.get(postRef)
            @Suppress("UNCHECKED_CAST")
            val userReactions = ((snap.get("userReactions") as? Map<String, String>) ?: emptyMap()).toMutableMap()
            @Suppress("UNCHECKED_CAST")
            val reactionCounts = ((snap.get("reactionCounts") as? Map<String, Number>) ?: emptyMap())
                .mapValues { it.value.toInt() }
                .toMutableMap()

            val prev = userReactions[uid]
            if (prev == emoji) {
                userReactions.remove(uid)
                val updatedCount = (reactionCounts[emoji] ?: 1) - 1
                if (updatedCount <= 0) reactionCounts.remove(emoji) else reactionCounts[emoji] = updatedCount
            } else {
                if (!prev.isNullOrBlank()) {
                    val prevCount = (reactionCounts[prev] ?: 1) - 1
                    if (prevCount <= 0) reactionCounts.remove(prev) else reactionCounts[prev] = prevCount
                }
                userReactions[uid] = emoji
                reactionCounts[emoji] = (reactionCounts[emoji] ?: 0) + 1
            }

            tx.update(
                postRef,
                mapOf(
                    "userReactions" to userReactions,
                    "reactionCounts" to reactionCounts
                )
            )
        }.await()
    }

    override fun observeComments(channelId: String, postId: String): Flow<List<ChannelComment>> = callbackFlow {
        val reg = firestore.collection(Constants.COLLECTION_CHANNELS)
            .document(channelId)
            .collection(Constants.SUBCOLLECTION_POSTS)
            .document(postId)
            .collection(Constants.SUBCOLLECTION_COMMENTS)
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .limit(100)
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener
                val list = snap.documents.mapNotNull { mapComment(it, channelId, postId) }
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    override suspend fun addComment(channelId: String, postId: String, text: String): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val trimmed = text.trim()
        require(trimmed.isNotBlank()) { "التعليق فارغ" }
        val me = userRepository.getUserOnce(uid)
        val postRef = firestore.collection(Constants.COLLECTION_CHANNELS)
            .document(channelId)
            .collection(Constants.SUBCOLLECTION_POSTS)
            .document(postId)
        val commentId = UUID.randomUUID().toString()
        val commentData = mapOf(
            "commentId" to commentId,
            "postId" to postId,
            "channelId" to channelId,
            "authorUid" to uid,
            "authorName" to (me?.displayName?.ifBlank { "@${me.publicId}" } ?: "WESAL User"),
            "authorAvatarUrl" to (me?.photoUrl ?: ""),
            "authorVerified" to (me?.isVerified == true),
            "text" to trimmed,
            "createdAt" to System.currentTimeMillis()
        )
        postRef.collection(Constants.SUBCOLLECTION_COMMENTS)
            .document(commentId)
            .set(commentData)
            .await()
        postRef.update("commentsCount", FieldValue.increment(1)).await()
    }

    override suspend fun deleteComment(channelId: String, postId: String, commentId: String): Result<Unit> = runCatching {
        val postRef = firestore.collection(Constants.COLLECTION_CHANNELS)
            .document(channelId)
            .collection(Constants.SUBCOLLECTION_POSTS)
            .document(postId)
        postRef.collection(Constants.SUBCOLLECTION_COMMENTS).document(commentId).delete().await()
        postRef.update("commentsCount", FieldValue.increment(-1)).await()
    }

    private suspend fun uploadChannelAvatar(channelId: String, uri: Uri): String =
        withContext(Dispatchers.IO) {
            runCatching { uriToBase64Jpeg(context, uri, maxSide = 360, quality = 78) }.getOrDefault("")
        }
}
