package com.wesal.messenger.data.repository

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.storage.FirebaseStorage
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.data.local.dao.WesalDao
import com.wesal.messenger.data.local.entity.CachedChatEntity
import com.wesal.messenger.data.local.entity.CachedMessageEntity
import com.wesal.messenger.domain.model.ChatPreview
import com.wesal.messenger.domain.model.MessageStatus
import com.wesal.messenger.domain.model.MessageType
import com.wesal.messenger.domain.model.WesalMessage
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.ChatRepository
import com.wesal.messenger.security.E2EECryptoManager
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
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage,
    private val wesalDao: WesalDao,
    private val cryptoManager: E2EECryptoManager
) : ChatRepository {

    private data class CachedUserEntry(val user: WesalUser, val fetchedAtMs: Long)
    private val userCache = mutableMapOf<String, CachedUserEntry>()
    private val userCacheTtlMs = 15_000L

    private fun observeAuthUid(): Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { fa -> trySend(fa.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeChats(): Flow<List<ChatPreview>> = observeAuthUid().flatMapLatest { uid ->
        userCache.clear()
        if (uid == null) {
            flowOf(emptyList())
        } else {
            callbackFlow {
                val registration = firestore.collection(Constants.COLLECTION_CHATS)
                    .whereArrayContains("participantIds", uid)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null || snapshot == null) return@addSnapshotListener
                        launch {
                            val previews = snapshot.documents.mapNotNull { doc ->
                                mapChatPreview(doc, uid)
                            }.sortedWith(
                                compareByDescending<ChatPreview> { it.isPinned }
                                    .thenByDescending { it.lastMessageTime }
                            )
                            wesalDao.upsertChats(previews.map {
                                CachedChatEntity(
                                    chatId = it.chatId,
                                    isGroup = it.isGroup,
                                    title = it.title,
                                    avatarUrl = it.avatarUrl,
                                    peerUid = it.peerUid,
                                    lastMessagePreview = it.lastMessagePreview,
                                    lastMessageTime = it.lastMessageTime,
                                    unreadCount = it.unreadCount,
                                    isPinned = it.isPinned,
                                    isArchived = it.isArchived,
                                    isMuted = it.isMuted
                                )
                            })
                            trySend(previews)
                        }
                    }

                awaitClose { registration.remove() }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun mapChatPreview(doc: DocumentSnapshot, myUid: String): ChatPreview? {
        val participantIds = (doc.get("participantIds") as? List<String>) ?: return null
        val deletedBy = (doc.get("deletedForUsers") as? List<String>) ?: emptyList()
        if (deletedBy.contains(myUid)) return null

        val isGroup = doc.getBoolean("isGroup") ?: false
        val groupId = doc.getString("groupId")
        val pinnedMap = (doc.get("pinnedBy") as? Map<String, Boolean>) ?: emptyMap()
        val archivedMap = (doc.get("archivedBy") as? Map<String, Boolean>) ?: emptyMap()
        val mutedMap = (doc.get("mutedBy") as? Map<String, Boolean>) ?: emptyMap()
        val unreadMap = (doc.get("unreadCounts") as? Map<String, Number>) ?: emptyMap()
        val typingMap = (doc.get("typingUsers") as? Map<String, Boolean>) ?: emptyMap()

        var title = doc.getString("groupName").orEmpty()
        var avatar = doc.getString("groupAvatarUrl").orEmpty()
        var peerUid = ""
        var peerPubKey = ""
        var peerOnline = false
        var peerLastSeen = 0L

        val unreadCount = unreadMap[myUid]?.toInt() ?: 0
        val lastSenderId = doc.getString("lastMessageSenderId").orEmpty()
        val lastStatus = MessageStatus.fromRaw(doc.getString("lastMessageStatus"))

        if (!isGroup) {
            peerUid = participantIds.firstOrNull { it != myUid }.orEmpty()
            if (peerUid.isNotBlank()) {
                val peer = fetchUserCached(peerUid)
                if (peer != null) {
                    title = peer.displayName.ifBlank { peer.phoneNumber }
                    avatar = if (peer.privacySettings.profilePhotoVisibility == com.wesal.messenger.domain.model.PrivacyVisibility.NOBODY) "" else peer.photoUrl
                    peerPubKey = peer.publicKey
                    peerOnline = peer.isOnline && peer.privacySettings.onlineVisibility != com.wesal.messenger.domain.model.PrivacyVisibility.NOBODY
                    peerLastSeen = if (peer.privacySettings.lastSeenVisibility == com.wesal.messenger.domain.model.PrivacyVisibility.NOBODY) 0L else peer.lastSeen
                }
            }
        }

        // Recipient device received the new message snapshot -> confirm real DELIVERED receipt
        if (lastSenderId.isNotBlank() && lastSenderId != myUid && lastStatus == MessageStatus.SENT) {
            markIncomingMessagesDelivered(doc.id, myUid)
        }

        return ChatPreview(
            chatId = doc.id,
            isGroup = isGroup,
            groupId = groupId,
            participantIds = participantIds,
            peerUid = peerUid,
            title = title.ifBlank { "محادثة وصال" },
            avatarUrl = avatar,
            peerPublicKey = peerPubKey,
            peerOnline = peerOnline,
            peerLastSeen = peerLastSeen,
            lastMessagePreview = doc.getString("lastMessagePreview").orEmpty(),
            lastMessageTime = doc.getLong("lastMessageTime") ?: 0L,
            lastMessageSenderId = lastSenderId,
            lastMessageStatus = lastStatus,
            unreadCount = unreadCount,
            isPinned = pinnedMap[myUid] ?: false,
            isArchived = archivedMap[myUid] ?: false,
            isMuted = mutedMap[myUid] ?: false,
            typingUsers = typingMap.filter { it.key != myUid && it.value }.keys.toList()
        )
    }

    private suspend fun markIncomingMessagesDelivered(chatId: String, myUid: String) {
        runCatching {
            val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
            val sentSnap = chatRef.collection(Constants.COLLECTION_MESSAGES)
                .whereEqualTo("status", MessageStatus.SENT.raw)
                .get()
                .await()
            val batch = firestore.batch()
            var anyUpdated = false
            sentSnap.documents.forEach { msgDoc ->
                if (msgDoc.getString("senderId") != myUid) {
                    batch.update(msgDoc.reference, "status", MessageStatus.DELIVERED.raw)
                    anyUpdated = true
                }
            }
            if (anyUpdated) {
                batch.update(chatRef, "lastMessageStatus", MessageStatus.DELIVERED.raw)
                batch.commit().await()
            }
        }
    }

    private suspend fun fetchUserCached(uid: String, forceServer: Boolean = false): WesalUser? {
        val now = System.currentTimeMillis()
        val existing = if (forceServer) null else userCache[uid]
        if (existing != null && now - existing.fetchedAtMs < userCacheTtlMs) {
            return existing.user
        }
        val ref = firestore.collection(Constants.COLLECTION_USERS).document(uid)
        val snap = runCatching {
            if (forceServer) ref.get(Source.SERVER).await() else ref.get().await()
        }.getOrNull() ?: runCatching { ref.get().await() }.getOrNull() ?: return userCache[uid]?.user
        if (!snap.exists()) return null
        val user = snap.toWesalUser()
        userCache[uid] = CachedUserEntry(user, now)
        return user
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeMessages(chatId: String): Flow<List<WesalMessage>> = observeAuthUid().flatMapLatest { myUid ->
        if (myUid == null) {
            flowOf(emptyList())
        } else {
            callbackFlow {
                val chatDoc = runCatching {
                    firestore.collection(Constants.COLLECTION_CHATS).document(chatId).get().await()
                }.getOrNull()
                val participantIds = (chatDoc?.get("participantIds") as? List<String>).orEmpty()
                val isGroup = chatDoc?.getBoolean("isGroup") ?: false

                val peerPubKeyCache = mutableMapOf<String, String>()
                participantIds.filter { it != myUid }.forEach { uid ->
                    fetchUserCached(uid)?.publicKey?.let { if (it.isNotBlank()) peerPubKeyCache[uid] = it }
                }

                val registration = firestore.collection(Constants.COLLECTION_CHATS)
                    .document(chatId)
                    .collection(Constants.COLLECTION_MESSAGES)
                    .orderBy("timestamp", Query.Direction.ASCENDING)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null || snapshot == null) return@addSnapshotListener
                        launch {
                            val messages = snapshot.documents.mapNotNull { doc ->
                                mapAndDecryptMessage(doc, chatId, myUid, isGroup, peerPubKeyCache)
                            }
                            wesalDao.upsertMessages(messages.map {
                                CachedMessageEntity(
                                    messageId = it.messageId,
                                    chatId = it.chatId,
                                    senderId = it.senderId,
                                    senderName = it.senderName,
                                    type = it.type.raw,
                                    text = it.text,
                                    mediaUrl = it.mediaUrl,
                                    mediaLocalPath = it.mediaLocalPath,
                                    mediaMimeType = it.mediaMimeType,
                                    mediaFileName = it.mediaFileName,
                                    mediaSizeBytes = it.mediaSizeBytes,
                                    mediaDurationMs = it.mediaDurationMs,
                                    status = it.status.raw,
                                    timestamp = it.timestamp
                                )
                            })
                            trySend(messages)
                        }
                    }

                awaitClose { registration.remove() }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun mapAndDecryptMessage(
        doc: DocumentSnapshot,
        chatId: String,
        myUid: String,
        isGroup: Boolean,
        peerPubKeyCache: MutableMap<String, String>
    ): WesalMessage? {
        val deletedFor = (doc.get("deletedForUsers") as? List<String>) ?: emptyList()
        if (deletedFor.contains(myUid)) return null

        val senderId = doc.getString("senderId").orEmpty()
        val isDeletedForEveryone = doc.getBoolean("isDeletedForEveryone") ?: false
        val ciphertext = doc.getString("ciphertext").orEmpty()
        val isEncrypted = doc.getBoolean("isEncrypted") ?: false
        val wrappedKeys = (doc.get("wrappedKeys") as? Map<String, String>) ?: emptyMap()
        val plainFallback = doc.getString("text").orEmpty()

        val resolvedText = when {
            isDeletedForEveryone -> "تم حذف هذه الرسالة"
            isEncrypted && ciphertext.isNotBlank() -> {
                val keyOwnerUid = if (senderId == myUid) {
                    peerPubKeyCache.keys.firstOrNull()
                } else {
                    senderId
                }
                val attemptDecrypt: (String) -> String = { peerPub ->
                    if (!isGroup) {
                        cryptoManager.decryptDirectMessage(
                            E2EECryptoManager.EncryptedPayload.fromWireString(ciphertext),
                            peerPub,
                            chatId
                        )
                    } else if (wrappedKeys.containsKey(myUid)) {
                        val symKey = cryptoManager.unwrapKeyFromPeer(wrappedKeys[myUid]!!, peerPub, chatId)
                        val bytes = cryptoManager.decryptBytesWithKey(
                            E2EECryptoManager.EncryptedPayload.fromWireString(ciphertext),
                            symKey,
                            chatId.toByteArray()
                        )
                        String(bytes, Charsets.UTF_8)
                    } else {
                        plainFallback.ifBlank { "رسالة مشفرة" }
                    }
                }
                val firstPub = keyOwnerUid?.let {
                    peerPubKeyCache[it] ?: fetchUserCached(it)?.publicKey?.also { pk ->
                        if (pk.isNotBlank()) peerPubKeyCache[it] = pk
                    }
                }
                val firstTry = if (!firstPub.isNullOrBlank()) runCatching { attemptDecrypt(firstPub) } else Result.failure(IllegalStateException("Missing key"))
                firstTry.getOrElse {
                    // Key might have rotated on peer reinstall/new login: force fresh server key fetch once
                    val freshPub = keyOwnerUid?.let { uid ->
                        userCache.remove(uid)
                        peerPubKeyCache.remove(uid)
                        fetchUserCached(uid, forceServer = true)?.publicKey?.also { pk ->
                            if (pk.isNotBlank()) peerPubKeyCache[uid] = pk
                        }
                    }
                    if (!freshPub.isNullOrBlank() && freshPub != firstPub) {
                        runCatching { attemptDecrypt(freshPub) }.getOrElse { plainFallback.ifBlank { "تعذر فك تشفير الرسالة" } }
                    } else {
                        plainFallback.ifBlank { "تعذر فك تشفير الرسالة" }
                    }
                }
            }
            else -> plainFallback
        }

        // Auto-mark delivered if received by peer
        val rawStatus = MessageStatus.fromRaw(doc.getString("status"))
        if (senderId != myUid && rawStatus == MessageStatus.SENT) {
            runCatching { doc.reference.update("status", MessageStatus.DELIVERED.raw) }
            runCatching {
                firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
                    .update("lastMessageStatus", MessageStatus.DELIVERED.raw)
            }
        }

        return WesalMessage(
            messageId = doc.id,
            chatId = chatId,
            senderId = senderId,
            senderName = doc.getString("senderName").orEmpty(),
            type = MessageType.fromRaw(doc.getString("type")),
            ciphertext = ciphertext,
            text = resolvedText,
            isEncrypted = isEncrypted,
            mediaUrl = doc.getString("mediaUrl").orEmpty(),
            mediaMimeType = doc.getString("mediaMimeType").orEmpty(),
            mediaFileName = doc.getString("mediaFileName").orEmpty(),
            mediaSizeBytes = doc.getLong("mediaSizeBytes") ?: 0L,
            mediaDurationMs = doc.getLong("mediaDurationMs") ?: 0L,
            waveformAmplitudes = ((doc.get("waveform") as? List<Number>)?.map { it.toInt() }) ?: emptyList(),
            wrappedKeys = wrappedKeys,
            latitude = doc.getDouble("latitude"),
            longitude = doc.getDouble("longitude"),
            locationAddress = doc.getString("locationAddress").orEmpty(),
            replyToMessageId = doc.getString("replyToMessageId"),
            replyToPreview = doc.getString("replyToPreview"),
            forwardedFromName = doc.getString("forwardedFromName"),
            isEdited = doc.getBoolean("isEdited") ?: false,
            isDeletedForEveryone = isDeletedForEveryone,
            deletedForUsers = deletedFor,
            status = rawStatus,
            timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis()
        )
    }

    override suspend fun getOrCreateDirectChat(peerUid: String): Result<String> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val sortedIds = listOf(myUid, peerUid).sorted()
        val deterministicChatId = "dm_${sortedIds[0]}_${sortedIds[1]}"
        val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(deterministicChatId)
        val snap = chatRef.get().await()
        if (!snap.exists()) {
            val data = mapOf(
                "chatId" to deterministicChatId,
                "isGroup" to false,
                "participantIds" to sortedIds,
                "lastMessagePreview" to "",
                "lastMessageTime" to System.currentTimeMillis(),
                "lastMessageSenderId" to "",
                "lastMessageStatus" to MessageStatus.SENT.raw,
                "unreadCounts" to mapOf(myUid to 0, peerUid to 0),
                "pinnedBy" to emptyMap<String, Boolean>(),
                "archivedBy" to emptyMap<String, Boolean>(),
                "mutedBy" to emptyMap<String, Boolean>(),
                "deletedForUsers" to emptyList<String>(),
                "typingUsers" to emptyMap<String, Boolean>()
            )
            chatRef.set(data, SetOptions.merge()).await()
        } else {
            chatRef.update("deletedForUsers", FieldValue.arrayRemove(myUid)).await()
        }
        deterministicChatId
    }

    override suspend fun sendTextMessage(
        chatId: String,
        text: String,
        replyTo: WesalMessage?,
        forwardedFromName: String?
    ): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val me = fetchUserCached(myUid)
        val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
        val chatSnap = chatRef.get().await()
        val participants = (chatSnap.get("participantIds") as? List<String>).orEmpty()
        val isGroup = chatSnap.getBoolean("isGroup") ?: false

        var ciphertext = ""
        var isEncrypted = false
        val wrappedKeys = mutableMapOf<String, String>()

        if (!isGroup) {
            val peerUid = participants.firstOrNull { it != myUid }
            val peerPub = peerUid?.let { fetchUserCached(it)?.publicKey }
            if (!peerPub.isNullOrBlank()) {
                ciphertext = cryptoManager.encryptDirectMessage(text, peerPub, chatId).toWireString()
                isEncrypted = true
            }
        } else {
            val symKey = cryptoManager.generateSymmetricKeyBytes()
            val payload = cryptoManager.encryptBytesWithKey(text.toByteArray(Charsets.UTF_8), symKey, chatId.toByteArray())
            ciphertext = payload.toWireString()
            participants.forEach { memberUid ->
                val pub = fetchUserCached(memberUid)?.publicKey
                if (!pub.isNullOrBlank()) {
                    wrappedKeys[memberUid] = cryptoManager.wrapKeyForPeer(symKey, pub, chatId)
                }
            }
            isEncrypted = wrappedKeys.isNotEmpty()
        }

        val now = System.currentTimeMillis()
        val messageId = UUID.randomUUID().toString()
        val messageData = hashMapOf<String, Any?>(
            "messageId" to messageId,
            "chatId" to chatId,
            "senderId" to myUid,
            "senderName" to (me?.displayName?.ifBlank { me.phoneNumber } ?: "مستخدم وصال"),
            "type" to MessageType.TEXT.raw,
            "ciphertext" to ciphertext,
            "text" to if (isEncrypted) "" else text,
            "isEncrypted" to isEncrypted,
            "wrappedKeys" to wrappedKeys,
            "replyToMessageId" to replyTo?.messageId,
            "replyToPreview" to replyTo?.text?.take(80),
            "forwardedFromName" to forwardedFromName,
            "isEdited" to false,
            "isDeletedForEveryone" to false,
            "deletedForUsers" to emptyList<String>(),
            "status" to MessageStatus.SENT.raw,
            "timestamp" to now
        )

        chatRef.collection(Constants.COLLECTION_MESSAGES).document(messageId).set(messageData).await()

        val preview = if (isEncrypted) "رسالة مشفرة" else text.take(60)
        val chatUpdates = hashMapOf<String, Any>(
            "lastMessagePreview" to preview,
            "lastMessageTime" to now,
            "lastMessageSenderId" to myUid,
            "lastMessageStatus" to MessageStatus.SENT.raw,
            "deletedForUsers" to emptyList<String>()
        )
        participants.filter { it != myUid }.forEach { otherUid ->
            chatUpdates["unreadCounts.$otherUid"] = FieldValue.increment(1)
        }
        chatRef.update(chatUpdates).await()
    }

    override fun sendMediaMessage(
        chatId: String,
        uri: Uri,
        type: MessageType,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        durationMs: Long,
        waveform: List<Int>,
        caption: String
    ): Flow<Result<Float>> = callbackFlow {
        val myUid = auth.currentUser?.uid
        if (myUid == null) {
            trySend(Result.failure(IllegalStateException("Not authenticated")))
            close()
            return@callbackFlow
        }

        launch {
            try {
                val me = fetchUserCached(myUid)
                val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
                val chatSnap = chatRef.get().await()
                val participants = (chatSnap.get("participantIds") as? List<String>).orEmpty()

                val folder = when (type) {
                    MessageType.VOICE -> Constants.STORAGE_VOICE_NOTES
                    MessageType.DOCUMENT -> Constants.STORAGE_FILES
                    else -> Constants.STORAGE_CHAT_MEDIA
                }

                val symKey = cryptoManager.generateSymmetricKeyBytes()
                val encryptedBytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        cryptoManager.encryptStreamToBytes(stream, symKey).first
                    } ?: error("تعذر قراءة الملف المحدد")
                }

                val wrappedKeys = mutableMapOf<String, String>()
                participants.forEach { memberUid ->
                    val pub = fetchUserCached(memberUid)?.publicKey
                    if (!pub.isNullOrBlank()) {
                        wrappedKeys[memberUid] = cryptoManager.wrapKeyForPeer(symKey, pub, chatId)
                    }
                }

                val downloadUrl = try {
                    val storageRef = storage.reference.child("$folder/$chatId/${UUID.randomUUID()}.enc")
                    val uploadTask = storageRef.putBytes(encryptedBytes)
                    uploadTask.addOnProgressListener { snap ->
                        val total = snap.totalByteCount.coerceAtLeast(1L)
                        trySend(Result.success(snap.bytesTransferred.toFloat() / total.toFloat()))
                    }
                    uploadTask.await()
                    storageRef.downloadUrl.await().toString()
                } catch (storageErr: Exception) {
                    if (type == MessageType.IMAGE) {
                        val b64Jpeg = uriToBase64Jpeg(context, uri, maxSide = 720, quality = 72)
                        val rawJpegBytes = runCatching {
                            Base64.decode(b64Jpeg.substringAfter("base64,"), Base64.NO_WRAP)
                        }.getOrNull()
                        if (rawJpegBytes != null && rawJpegBytes.isNotEmpty() && rawJpegBytes.size <= 380_000) {
                            val encSmall = cryptoManager.encryptStreamToBytes(rawJpegBytes.inputStream(), symKey).first
                            trySend(Result.success(0.85f))
                            "wesal-inline://" + Base64.encodeToString(encSmall, Base64.NO_WRAP)
                        } else {
                            throw storageErr
                        }
                    } else if (type == MessageType.VOICE && encryptedBytes.size <= 450_000) {
                        trySend(Result.success(0.85f))
                        "wesal-inline://" + Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
                    } else {
                        throw IllegalStateException("تعذر رفع المرفق إلى Firebase Storage — تأكد من تفعيل Storage للملفات الكبيرة")
                    }
                }

                val now = System.currentTimeMillis()
                val messageId = UUID.randomUUID().toString()
                val msgData = hashMapOf<String, Any?>(
                    "messageId" to messageId,
                    "chatId" to chatId,
                    "senderId" to myUid,
                    "senderName" to (me?.displayName?.ifBlank { me.phoneNumber } ?: "مستخدم وصال"),
                    "type" to type.raw,
                    "text" to caption,
                    "isEncrypted" to true,
                    "wrappedKeys" to wrappedKeys,
                    "mediaUrl" to downloadUrl,
                    "mediaMimeType" to mimeType,
                    "mediaFileName" to fileName,
                    "mediaSizeBytes" to sizeBytes,
                    "mediaDurationMs" to durationMs,
                    "waveform" to waveform,
                    "isEdited" to false,
                    "isDeletedForEveryone" to false,
                    "deletedForUsers" to emptyList<String>(),
                    "status" to MessageStatus.SENT.raw,
                    "timestamp" to now
                )
                chatRef.collection(Constants.COLLECTION_MESSAGES).document(messageId).set(msgData).await()

                val previewLabel = when (type) {
                    MessageType.IMAGE -> "صورة"
                    MessageType.VIDEO -> "فيديو"
                    MessageType.VOICE -> "رسالة صوتية"
                    MessageType.DOCUMENT -> "ملف: $fileName"
                    else -> "مرفق"
                }
                val chatUpdates = hashMapOf<String, Any>(
                    "lastMessagePreview" to previewLabel,
                    "lastMessageTime" to now,
                    "lastMessageSenderId" to myUid,
                    "lastMessageStatus" to MessageStatus.SENT.raw,
                    "deletedForUsers" to emptyList<String>()
                )
                participants.filter { it != myUid }.forEach { otherUid ->
                    chatUpdates["unreadCounts.$otherUid"] = FieldValue.increment(1)
                }
                chatRef.update(chatUpdates).await()
                trySend(Result.success(1.0f))
                close()
            } catch (e: Exception) {
                trySend(Result.failure(e))
                close()
            }
        }
        awaitClose { }
    }

    override suspend fun decryptMediaToCache(message: WesalMessage): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val myUid = auth.currentUser?.uid ?: error("Not authenticated")
            val cacheDir = File(context.cacheDir, "decrypted_media").apply { mkdirs() }
            val ext = message.mediaFileName.substringAfterLast('.', "").ifBlank {
                when (message.type) {
                    MessageType.IMAGE -> "jpg"
                    MessageType.VIDEO -> "mp4"
                    MessageType.VOICE -> "m4a"
                    else -> "bin"
                }
            }
            val targetFile = File(cacheDir, "${message.messageId}.$ext")
            if (targetFile.exists() && targetFile.length() > 0L) {
                return@runCatching targetFile
            }

            val encryptedBytes = if (message.mediaUrl.startsWith("wesal-inline://")) {
                Base64.decode(message.mediaUrl.removePrefix("wesal-inline://"), Base64.NO_WRAP)
            } else {
                val ref = storage.getReferenceFromUrl(message.mediaUrl)
                ref.getBytes(Constants.MAX_UPLOAD_SIZE_BYTES).await()
            }

            val wrappedKey = message.wrappedKeys[myUid]
            val plainBytes = if (!wrappedKey.isNullOrBlank()) {
                val senderPub = fetchUserCached(message.senderId)?.publicKey.orEmpty()
                val peerPub = if (message.senderId == myUid) {
                    // Self-sent media: unwrap using own key via any peer or senderPub
                    fetchUserCached(myUid)?.publicKey.orEmpty()
                } else {
                    senderPub
                }
                val symKey = cryptoManager.unwrapKeyFromPeer(wrappedKey, peerPub, message.chatId)
                cryptoManager.decryptCombinedMediaBytes(
                    encryptedBytes,
                    Base64.encodeToString(symKey, Base64.NO_WRAP)
                )
            } else {
                encryptedBytes
            }

            targetFile.writeBytes(plainBytes)
            targetFile
        }
    }

    override suspend fun sendLocationMessage(
        chatId: String,
        latitude: Double,
        longitude: Double,
        address: String
    ): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val me = fetchUserCached(myUid)
        val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
        val participants = (chatRef.get().await().get("participantIds") as? List<String>).orEmpty()
        val now = System.currentTimeMillis()
        val messageId = UUID.randomUUID().toString()

        val msg = mapOf(
            "messageId" to messageId,
            "chatId" to chatId,
            "senderId" to myUid,
            "senderName" to (me?.displayName?.ifBlank { me.phoneNumber } ?: "مستخدم وصال"),
            "type" to MessageType.LOCATION.raw,
            "text" to address.ifBlank { "الموقع الجغرافي" },
            "latitude" to latitude,
            "longitude" to longitude,
            "locationAddress" to address,
            "isEncrypted" to false,
            "status" to MessageStatus.SENT.raw,
            "timestamp" to now
        )
        chatRef.collection(Constants.COLLECTION_MESSAGES).document(messageId).set(msg).await()
        val chatUpdates = hashMapOf<String, Any>(
            "lastMessagePreview" to "موقع جغرافي",
            "lastMessageTime" to now,
            "lastMessageSenderId" to myUid,
            "lastMessageStatus" to MessageStatus.SENT.raw,
            "deletedForUsers" to emptyList<String>()
        )
        participants.filter { it != myUid }.forEach { otherUid ->
            chatUpdates["unreadCounts.$otherUid"] = FieldValue.increment(1)
        }
        chatRef.update(chatUpdates).await()
    }

    override suspend fun setTyping(chatId: String, isTyping: Boolean) {
        val myUid = auth.currentUser?.uid ?: return
        runCatching {
            firestore.collection(Constants.COLLECTION_CHATS)
                .document(chatId)
                .update("typingUsers.$myUid", isTyping)
                .await()
        }
    }

    override suspend fun markChatAsRead(chatId: String) {
        val myUid = auth.currentUser?.uid ?: return
        runCatching {
            val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
            val chatSnap = chatRef.get().await()
            chatRef.update("unreadCounts.$myUid", 0).await()

            val me = fetchUserCached(myUid)
            if (me?.privacySettings?.readReceiptsEnabled == false) {
                return@runCatching
            }

            val unreadSnap = chatRef.collection(Constants.COLLECTION_MESSAGES)
                .whereIn("status", listOf(MessageStatus.SENT.raw, MessageStatus.DELIVERED.raw))
                .get()
                .await()
            val batch = firestore.batch()
            var anyUpdated = false
            unreadSnap.documents.forEach { doc ->
                if (doc.getString("senderId") != myUid) {
                    batch.update(doc.reference, "status", MessageStatus.READ.raw)
                    anyUpdated = true
                }
            }
            if (chatSnap.getString("lastMessageSenderId") != myUid) {
                batch.update(chatRef, "lastMessageStatus", MessageStatus.READ.raw)
                anyUpdated = true
            }
            if (anyUpdated) {
                batch.commit().await()
            }
        }
    }

    override suspend fun editMessage(chatId: String, messageId: String, newText: String): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val msgRef = firestore.collection(Constants.COLLECTION_CHATS)
            .document(chatId)
            .collection(Constants.COLLECTION_MESSAGES)
            .document(messageId)
        val snap = msgRef.get().await()
        require(snap.getString("senderId") == myUid) { "لا يمكنك تعديل رسالة شخص آخر" }
        val ts = snap.getLong("timestamp") ?: 0L
        require(System.currentTimeMillis() - ts <= Constants.MESSAGE_EDIT_WINDOW_MS) {
            "انتهت مهلة تعديل الرسالة (15 دقيقة)"
        }
        msgRef.update(mapOf("text" to newText.trim(), "ciphertext" to "", "isEncrypted" to false, "isEdited" to true)).await()
    }

    override suspend fun deleteMessageForMe(chatId: String, messageId: String): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        firestore.collection(Constants.COLLECTION_CHATS)
            .document(chatId)
            .collection(Constants.COLLECTION_MESSAGES)
            .document(messageId)
            .update("deletedForUsers", FieldValue.arrayUnion(myUid))
            .await()
    }

    override suspend fun deleteMessageForEveryone(chatId: String, messageId: String): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val msgRef = firestore.collection(Constants.COLLECTION_CHATS)
            .document(chatId)
            .collection(Constants.COLLECTION_MESSAGES)
            .document(messageId)
        val snap = msgRef.get().await()
        require(snap.getString("senderId") == myUid) { "لا يمكنك حذف رسالة مستخدم آخر للجميع" }
        msgRef.update(
            mapOf(
                "isDeletedForEveryone" to true,
                "text" to "",
                "ciphertext" to "",
                "mediaUrl" to ""
            )
        ).await()
    }

    override suspend fun togglePinChat(chatId: String, pinned: Boolean): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        firestore.collection(Constants.COLLECTION_CHATS).document(chatId).update("pinnedBy.$myUid", pinned).await()
    }

    override suspend fun toggleArchiveChat(chatId: String, archived: Boolean): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        firestore.collection(Constants.COLLECTION_CHATS).document(chatId).update("archivedBy.$myUid", archived).await()
    }

    override suspend fun toggleMuteChat(chatId: String, muted: Boolean): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        firestore.collection(Constants.COLLECTION_CHATS).document(chatId).update("mutedBy.$myUid", muted).await()
    }

    override suspend fun deleteChat(chatId: String): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
            .update("deletedForUsers", FieldValue.arrayUnion(myUid))
            .await()
        wesalDao.deleteChat(chatId)
    }
}
