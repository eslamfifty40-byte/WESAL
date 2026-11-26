package com.wesal.messenger.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.wesal.messenger.chat.WesalDiag
import android.util.Base64
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.data.local.dao.WesalDao
import com.wesal.messenger.data.local.entity.CachedChatEntity
import com.wesal.messenger.data.local.entity.CachedMessageEntity
import com.wesal.messenger.domain.model.ChatPreview
import com.wesal.messenger.domain.model.MessageStatus
import com.wesal.messenger.domain.model.MessageType
import com.wesal.messenger.domain.model.PrivacyVisibility
import com.wesal.messenger.domain.model.SystemWelcomeConfig
import com.wesal.messenger.domain.model.WesalMessage
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.ChatRepository
import com.wesal.messenger.security.E2EECryptoManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val wesalDao: WesalDao,
    private val cryptoManager: E2EECryptoManager
) : ChatRepository {

    private data class CachedUserEntry(val user: WesalUser, val fetchedAtMs: Long)
    private val userCache = ConcurrentHashMap<String, CachedUserEntry>()
    private val userCacheTtlMs = 120_000L
    @Volatile private var cacheOwnerUid: String? = null
    private val deliveredThrottleMs = ConcurrentHashMap<String, Long>()

    private fun observeAuthUid(): Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { fa -> trySend(fa.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    private suspend fun verifyIsOwnerSuperAdmin(uid: String): Boolean {
        val currentUser = auth.currentUser ?: return false
        if (currentUser.uid != uid) return false
        val email = currentUser.email.orEmpty()
        if (!Constants.isSuperAdminEmail(email) || !currentUser.isEmailVerified) {
            return false
        }
        val adminSnap = runCatching {
            firestore.collection(Constants.COLLECTION_SUPER_ADMINS).document(uid).get().await()
        }.getOrNull() ?: return false
        val userSnap = runCatching {
            firestore.collection(Constants.COLLECTION_USERS).document(uid).get().await()
        }.getOrNull()
        val numericId = userSnap?.getString("numericId").orEmpty()
        return adminSnap.exists() && numericId in Constants.ADMIN_NUMERIC_IDS
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeChats(): Flow<List<ChatPreview>> = observeAuthUid().flatMapLatest { uid ->
        // Keep the peer cache across re-subscriptions; only reset it when the signed-in account changes.
        if (cacheOwnerUid != uid) {
            userCache.clear()
            deliveredThrottleMs.clear()
            cacheOwnerUid = uid
        }
        if (uid == null) {
            flowOf(emptyList())
        } else {
            callbackFlow {
                val hasRemoteEmission = AtomicBoolean(false)
                launch(Dispatchers.IO) {
                    val cached = runCatching { wesalDao.observeChats().firstOrNull() }.getOrNull()
                    if (!hasRemoteEmission.get() && !cached.isNullOrEmpty()) {
                        trySend(
                            cached.map {
                                ChatPreview(
                                    chatId = it.chatId,
                                    isGroup = it.isGroup,
                                    peerUid = it.peerUid,
                                    title = it.title.ifBlank { "محادثة WESAL" },
                                    avatarUrl = it.avatarUrl,
                                    lastMessagePreview = it.lastMessagePreview,
                                    lastMessageTime = it.lastMessageTime,
                                    unreadCount = it.unreadCount,
                                    isPinned = it.isPinned,
                                    isMuted = it.isMuted,
                                    isOfficialWesalChat = it.chatId.startsWith(Constants.OFFICIAL_CHAT_PREFIX)
                                )
                            }
                        )
                    }
                }

                val registration = firestore.collection(Constants.COLLECTION_CHATS)
                    .whereArrayContains("participantIds", uid)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null || snapshot == null) return@addSnapshotListener
                        hasRemoteEmission.set(true)
                        launch(Dispatchers.IO) {
                            val now = System.currentTimeMillis()
                            val missingPeerUids = snapshot.documents
                                .asSequence()
                                .filter { it.getBoolean("isGroup") != true && it.getBoolean("isOfficialWesalChat") != true }
                                .mapNotNull { doc ->
                                    @Suppress("UNCHECKED_CAST")
                                    (doc.get("participantIds") as? List<String>)?.firstOrNull { it != uid }
                                }
                                .filter { peerId ->
                                    val cached = userCache[peerId]
                                    cached == null || (now - cached.fetchedAtMs >= userCacheTtlMs)
                                }
                                .distinct()
                                .toList()

                            if (missingPeerUids.isNotEmpty()) {
                                coroutineScope {
                                    missingPeerUids.map { peerId ->
                                        async(Dispatchers.IO) { runCatching { fetchUserCached(peerId) } }
                                    }.awaitAll()
                                }
                            }

                            val previews = snapshot.documents.mapNotNull { doc ->
                                mapChatPreview(doc, uid)
                            }.sortedWith(
                                compareByDescending<ChatPreview> { it.isOfficialWesalChat }
                                    .thenByDescending { it.isPinned }
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
                                    isArchived = false,
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
        val isOfficialWesalChat = (doc.getBoolean("isOfficialWesalChat") ?: false) ||
            doc.id.startsWith(Constants.OFFICIAL_CHAT_PREFIX)
        val groupId = doc.getString("groupId")
        val pinnedMap = (doc.get("pinnedBy") as? Map<String, Boolean>) ?: emptyMap()
        val mutedMap = (doc.get("mutedBy") as? Map<String, Boolean>) ?: emptyMap()
        val unreadMap = (doc.get("unreadCounts") as? Map<String, Number>) ?: emptyMap()
        val typingMap = (doc.get("typingUsers") as? Map<String, Boolean>) ?: emptyMap()

        var title = doc.getString("groupName").orEmpty()
        var avatar = doc.getString("groupAvatarUrl").orEmpty()
        var peerUid = ""
        var peerPubKey = ""
        var peerOnline = false
        var peerLastSeen = 0L
        var peerVerified = false
        var peerVerificationType = "BLUE"
        var peerVerificationGlowColor = ""
        var peerVerificationGlowEnabled = true
        var peerVerificationGlowIntensity = 0.7f
        var peerFrameId = ""
        var peerFrameExpiresAt = 0L
        var peerIconUrl = ""
        var peerPhotoRemovedBg = false
        var peerNeonColor = ""
        var peerNeonGlowIntensity = 0.7f
        var peerNeonEnabled = false

        val unreadCount = unreadMap[myUid]?.toInt() ?: 0
        val lastSenderId = doc.getString("lastMessageSenderId").orEmpty()
        val lastStatus = MessageStatus.fromRaw(doc.getString("lastMessageStatus"))

        if (isOfficialWesalChat) {
            title = title.ifBlank { "WESAL Messenger" }
            if (avatar.isBlank()) {
                avatar = fetchOwnerAdminAvatarUrl()
            }
            peerVerified = true
            peerVerificationType = "BLUE"
        } else if (!isGroup) {
            peerUid = participantIds.firstOrNull { it != myUid }.orEmpty()
            if (peerUid.isNotBlank()) {
                val peer = fetchUserCached(peerUid)
                if (peer != null) {
                    if (peer.deletedAt > 0L || peer.deletedPermanent) {
                        title = "حساب محذوف"
                        avatar = ""
                        peerOnline = false
                        peerVerified = false
                    } else {
                        val safeHandle = peer.publicId.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty()
                        title = peer.displayName.ifBlank { safeHandle }
                        avatar = if (peer.privacySettings.profilePhotoVisibility == PrivacyVisibility.NOBODY || peer.privacySettings.hideAvatarPhoto) "" else peer.photoUrl
                        peerPubKey = peer.publicKey
                        peerOnline = peer.isOnline && peer.privacySettings.onlineVisibility != PrivacyVisibility.NOBODY
                        peerLastSeen = if (peer.privacySettings.lastSeenVisibility == PrivacyVisibility.NOBODY) 0L else peer.lastSeen
                        peerVerified = peer.isVerified
                        peerVerificationType = peer.verificationType
                        peerVerificationGlowColor = peer.verificationGlowColor
                        peerVerificationGlowEnabled = peer.verificationGlowEnabled
                        peerVerificationGlowIntensity = peer.verificationGlowIntensity
                        peerFrameId = peer.frameId
                        peerFrameExpiresAt = peer.frameExpiresAt
                        peerIconUrl = com.wesal.messenger.core.util.WesalFrameLibrary.resolveIconUrl(peer.iconId, peer.iconUrl)
                        peerPhotoRemovedBg = peer.photoRemovedBg
                        peerNeonColor = peer.neonColor
                        peerNeonGlowIntensity = peer.neonGlowIntensity
                        peerNeonEnabled = peer.neonEnabled
                    }
                }
            }
        }

        if (lastSenderId.isNotBlank() && lastSenderId != myUid && lastStatus == MessageStatus.SENT) {
            markIncomingMessagesDelivered(doc.id, myUid)
        }

        val rawPreview = doc.getString("lastMessagePreview").orEmpty()
        val sanitizedPreview = if (rawPreview == "رسالة مشفرة") "رسالة سابقة" else rawPreview
        val reqStatus = doc.getString("requestStatus")?.takeIf { it.isNotBlank() } ?: "ACCEPTED"
        val isReqFlag = (doc.getBoolean("isMessageRequest") ?: false) && reqStatus != "ACCEPTED"

        return ChatPreview(
            chatId = doc.id,
            isGroup = isGroup,
            groupId = groupId,
            participantIds = participantIds,
            peerUid = peerUid,
            title = title.ifBlank { "محادثة WESAL" },
            avatarUrl = avatar,
            peerPublicKey = peerPubKey,
            peerOnline = peerOnline,
            peerLastSeen = peerLastSeen,
            peerVerified = peerVerified,
            peerVerificationType = peerVerificationType,
            peerVerificationGlowColor = peerVerificationGlowColor,
            peerVerificationGlowEnabled = peerVerificationGlowEnabled,
            peerVerificationGlowIntensity = peerVerificationGlowIntensity,
            peerFrameId = peerFrameId,
            peerFrameExpiresAt = peerFrameExpiresAt,
            peerIconUrl = peerIconUrl,
            peerPhotoRemovedBg = peerPhotoRemovedBg,
            peerNeonColor = peerNeonColor,
            peerNeonGlowIntensity = peerNeonGlowIntensity,
            peerNeonEnabled = peerNeonEnabled,
            officialWallpaperUrl = doc.getString("officialWallpaperUrl").orEmpty(),
            lastMessagePreview = sanitizedPreview,
            lastMessageTime = doc.getLong("lastMessageTime") ?: 0L,
            lastMessageSenderId = lastSenderId,
            lastMessageStatus = lastStatus,
            unreadCount = unreadCount,
            isPinned = isOfficialWesalChat || (pinnedMap[myUid] ?: false),
            isMuted = mutedMap[myUid] ?: false,
            isSupportChat = doc.getBoolean("isSupportChat") ?: false,
            isOfficialWesalChat = isOfficialWesalChat,
            isMessageRequest = isReqFlag,
            requestStatus = reqStatus,
            disappearingDurationSec = doc.getLong("disappearingDurationSec") ?: 0L,
            deletedForUsers = deletedBy,
            typingUsers = typingMap.filter { it.key != myUid && it.value }.keys.toList()
        )
    }

    private suspend fun markIncomingMessagesDelivered(chatId: String, myUid: String) {
        val now = System.currentTimeMillis()
        val lastRun = deliveredThrottleMs[chatId] ?: 0L
        if (now - lastRun < 5_000L) return
        deliveredThrottleMs[chatId] = now
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
            WesalDiag.status.value = "uid=NULL (لا يوجد تسجيل دخول جاهز)"
            flowOf(emptyList())
        } else {
            callbackFlow {
                val hasRemoteMessages = AtomicBoolean(false)
                launch(Dispatchers.IO) {
                    val cachedMsgs = runCatching { wesalDao.observeMessages(chatId).firstOrNull() }.getOrNull()
                    if (!hasRemoteMessages.get() && !cachedMsgs.isNullOrEmpty()) {
                        trySend(
                            cachedMsgs.map {
                                WesalMessage(
                                    messageId = it.messageId,
                                    chatId = it.chatId,
                                    senderId = it.senderId,
                                    senderName = it.senderName,
                                    type = MessageType.fromRaw(it.type),
                                    text = it.text,
                                    mediaUrl = it.mediaUrl,
                                    mediaLocalPath = it.mediaLocalPath,
                                    mediaMimeType = it.mediaMimeType,
                                    mediaFileName = it.mediaFileName,
                                    mediaSizeBytes = it.mediaSizeBytes,
                                    mediaDurationMs = it.mediaDurationMs,
                                    status = MessageStatus.fromRaw(it.status),
                                    timestamp = it.timestamp
                                )
                            }
                        )
                    }
                }

                val isGroupRef = java.util.concurrent.atomic.AtomicBoolean(false)
                val peerPubKeyCache = ConcurrentHashMap<String, String>()
                val seqCounter = java.util.concurrent.atomic.AtomicLong(0L)
                val lastSentSeq = java.util.concurrent.atomic.AtomicLong(0L)
                val sendMutex = kotlinx.coroutines.sync.Mutex()
                val lastSnapshotRef = java.util.concurrent.atomic.AtomicReference<com.google.firebase.firestore.QuerySnapshot?>(null)
                val shortChat = chatId.take(6)
                val shortUid = myUid.take(5)
                WesalDiag.status.value = "uid=$shortUid chat=$shortChat listener=attaching"

                fun processSnapshot(snapshot: com.google.firebase.firestore.QuerySnapshot) {
                    val seq = seqCounter.incrementAndGet()
                    launch(Dispatchers.Default) {
                        val messages = runCatching {
                            snapshot.documents.mapNotNull { doc ->
                                mapAndDecryptMessage(doc, chatId, myUid, isGroupRef.get(), peerPubKeyCache)
                            }
                        }.getOrElse { e ->
                            WesalDiag.report("خطأ معالجة الرسائل: ${e.javaClass.simpleName} - ${e.message}")
                            return@launch
                        }
                        // Emit first, in order, so a slow cache write can never hide a message.
                        sendMutex.withLock {
                            if (seq > lastSentSeq.get()) {
                                lastSentSeq.set(seq)
                                trySend(messages)
                            }
                        }
                        val meta = snapshot.metadata
                        WesalDiag.status.value = "uid=$shortUid chat=$shortChat docs=${snapshot.size()} shown=${messages.size} " +
                            "dropped=${snapshot.size() - messages.size} cache=${meta.isFromCache} pending=${meta.hasPendingWrites()} #$seq"
                        runCatching {
                            withContext(Dispatchers.IO) {
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
                            }
                        }.onFailure { Log.e("WesalChat", "cache upsert failed: ${it.message}") }
                    }
                }

                val registrationRef = java.util.concurrent.atomic.AtomicReference<com.google.firebase.firestore.ListenerRegistration?>(null)
                fun attachListener() {
                    registrationRef.set(
                        firestore.collection(Constants.COLLECTION_CHATS)
                            .document(chatId)
                            .collection(Constants.COLLECTION_MESSAGES)
                            .orderBy("timestamp", Query.Direction.ASCENDING)
                            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                                if (error != null) {
                                    Log.e("WesalChat", "messages listener failed for chat $chatId: ${error.code} ${error.message}")
                                    WesalDiag.report("خطأ قراءة الرسائل: ${error.code} - ${error.message}")
                                    WesalDiag.status.value = "uid=$shortUid chat=$shortChat listener=ERROR ${error.code}"
                                    // The listener is dead after an error: re-attach unless it is a permanent denial.
                                    if (error.code != com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                                        launch {
                                            delay(3000L)
                                            registrationRef.get()?.remove()
                                            attachListener()
                                        }
                                    }
                                    return@addSnapshotListener
                                }
                                if (snapshot == null) return@addSnapshotListener
                                hasRemoteMessages.set(true)
                                lastSnapshotRef.set(snapshot)
                                processSnapshot(snapshot)
                            }
                    )
                }
                // Attach immediately: nothing may block the message listener (the chat doc and peer keys load in the background).
                attachListener()

                launch(Dispatchers.IO) {
                    val chatDoc = runCatching {
                        firestore.collection(Constants.COLLECTION_CHATS).document(chatId).get().await()
                    }.getOrNull()
                    val participantIds = (chatDoc?.get("participantIds") as? List<String>).orEmpty()
                    val groupNow = chatDoc?.getBoolean("isGroup") ?: false
                    val peerUids = participantIds.filter { it != myUid }.distinct()
                    peerUids.map { uid ->
                        async {
                            runCatching {
                                fetchUserCached(uid)?.publicKey?.let { key ->
                                    if (key.isNotBlank()) peerPubKeyCache[uid] = key
                                }
                            }
                        }
                    }.awaitAll()
                    if (groupNow != isGroupRef.get() || peerUids.isNotEmpty()) {
                        isGroupRef.set(groupNow)
                        lastSnapshotRef.get()?.let { processSnapshot(it) }
                    }
                }

                awaitClose { registrationRef.get()?.remove() }
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
        val disappearingExpiresAt = doc.getLong("disappearingExpiresAt") ?: 0L
        if (disappearingExpiresAt > 0L && System.currentTimeMillis() > disappearingExpiresAt) {
            return null
        }

        val senderId = doc.getString("senderId").orEmpty()
        val isDeletedForEveryone = doc.getBoolean("isDeletedForEveryone") ?: false
        val ciphertext = doc.getString("ciphertext").orEmpty()
        val isEncrypted = doc.getBoolean("isEncrypted") ?: false
        val isViewOnce = doc.getBoolean("isViewOnce") ?: false
        val viewedBy = (doc.get("viewedBy") as? List<String>) ?: emptyList()
        val wrappedKeys = (doc.get("wrappedKeys") as? Map<String, String>) ?: emptyMap()
        val plainFallback = doc.getString("text").orEmpty()

        val resolvedText = when {
            isDeletedForEveryone -> "تم حذف هذه الرسالة"
            plainFallback.isNotBlank() -> plainFallback
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
                        "رسالة سابقة غير متاحة"
                    }
                }
                val firstPub = keyOwnerUid?.let {
                    peerPubKeyCache[it] ?: fetchUserCached(it)?.publicKey?.also { pk ->
                        if (pk.isNotBlank()) peerPubKeyCache[it] = pk
                    }
                }
                val firstTry = if (!firstPub.isNullOrBlank()) runCatching { attemptDecrypt(firstPub) } else Result.failure(IllegalStateException("Missing key"))
                firstTry.getOrElse {
                    val freshPub = keyOwnerUid?.let { uid ->
                        userCache.remove(uid)
                        peerPubKeyCache.remove(uid)
                        fetchUserCached(uid, forceServer = true)?.publicKey?.also { pk ->
                            if (pk.isNotBlank()) peerPubKeyCache[uid] = pk
                        }
                    }
                    if (!freshPub.isNullOrBlank() && freshPub != firstPub) {
                        runCatching { attemptDecrypt(freshPub) }.getOrElse { "رسالة سابقة غير متاحة" }
                    } else {
                        "رسالة سابقة غير متاحة"
                    }
                }
            }
            else -> plainFallback
        }

        val rawStatus = MessageStatus.fromRaw(doc.getString("status"))
        if (senderId != myUid && senderId != "wesal_system" && rawStatus == MessageStatus.SENT) {
            runCatching { doc.reference.update("status", MessageStatus.DELIVERED.raw) }
            runCatching {
                firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
                    .update("lastMessageStatus", MessageStatus.DELIVERED.raw)
            }
        }

        val mediaUrl = if (isViewOnce && viewedBy.isNotEmpty() && senderId != myUid) {
            ""
        } else {
            doc.getString("mediaUrl").orEmpty()
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
            isViewOnce = isViewOnce,
            viewedBy = viewedBy,
            mediaUrl = mediaUrl,
            mediaMimeType = doc.getString("mediaMimeType").orEmpty(),
            mediaFileName = doc.getString("mediaFileName").orEmpty(),
            mediaSizeBytes = doc.getLong("mediaSizeBytes") ?: 0L,
            mediaDurationMs = doc.getLong("mediaDurationMs") ?: 0L,
            waveformAmplitudes = ((doc.get("waveform") as? List<Number>)?.map { it.toInt() }) ?: emptyList(),
            wrappedKeys = wrappedKeys,
            latitude = doc.getDouble("latitude"),
            longitude = doc.getDouble("longitude"),
            locationAddress = doc.getString("locationAddress").orEmpty(),
            liveLocationExpiresAt = doc.getLong("liveLocationExpiresAt") ?: 0L,
            disappearingExpiresAt = disappearingExpiresAt,
            replyToMessageId = doc.getString("replyToMessageId"),
            replyToPreview = doc.getString("replyToPreview"),
            forwardedFromName = doc.getString("forwardedFromName"),
            isEdited = doc.getBoolean("isEdited") ?: false,
            isDeletedForEveryone = isDeletedForEveryone,
            deletedForUsers = deletedFor,
            status = if (senderId == myUid && doc.metadata.hasPendingWrites()) MessageStatus.SENDING else rawStatus,
            timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis()
        )
    }

    override suspend fun getOrCreateDirectChat(peerUid: String): Result<String> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        require(peerUid.isNotBlank() && peerUid != myUid) { "Invalid peer user" }
        val me = fetchUserCached(myUid, forceServer = true)
        val peer = fetchUserCached(peerUid, forceServer = true)
        require(me?.isBanned != true) { "الحساب محظور ولا يمكنه بدء محادثات جديدة" }
        require(peer != null && peer.deletedAt == 0L && !peer.isBanned) { "هذا الحساب غير متاح حالياً" }
        require(!me?.blockedUsers.orEmpty().contains(peerUid) && !peer.blockedUsers.contains(myUid)) {
            "تعذر فتح المحادثة بسبب وجود حظر بين الطرفين"
        }

        val sortedIds = listOf(myUid, peerUid).sorted()
        val deterministicChatId = "dm_${sortedIds[0]}_${sortedIds[1]}"
        val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(deterministicChatId)
        val snap = chatRef.get().await()
        if (!snap.exists()) {
            val isOwnerSupportTarget = peer.numericId == Constants.OWNER_ADMIN_NUMERIC_ID
            val callerIsSuperAdmin = verifyIsOwnerSuperAdmin(myUid)
            if (!isOwnerSupportTarget && !callerIsSuperAdmin) {
                val reqId = "${sortedIds[0]}_${sortedIds[1]}"
                val reqSnap = firestore.collection(Constants.COLLECTION_CONTACT_REQUESTS)
                    .document(reqId)
                    .get()
                    .await()
                val isAccepted = reqSnap.exists() && reqSnap.getString("status") == "ACCEPTED"
                require(isAccepted) {
                    "لا يمكنك مراسلة هذا المستخدم قبل إرسال طلب تعارف وموافقته"
                }
            }
            val data = mapOf(
                "chatId" to deterministicChatId,
                "isGroup" to false,
                "isSupportChat" to isOwnerSupportTarget,
                "participantIds" to sortedIds,
                "lastMessagePreview" to "",
                "lastMessageTime" to System.currentTimeMillis(),
                "lastMessageSenderId" to "",
                "lastMessageStatus" to MessageStatus.SENT.raw,
                "unreadCounts" to mapOf(myUid to 0, peerUid to 0),
                "pinnedBy" to emptyMap<String, Boolean>(),
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

    override suspend fun openSupportChatByNumericId(targetNumericId: String): Result<String> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val cleanTarget = targetNumericId.trim().ifBlank { Constants.OWNER_ADMIN_NUMERIC_ID }
        require(cleanTarget == Constants.OWNER_ADMIN_NUMERIC_ID || verifyIsOwnerSuperAdmin(myUid)) {
            "حساب الدعم الرسمي المعتمد هو ${Constants.OWNER_ADMIN_NUMERIC_ID} فقط"
        }

        val colQuery = firestore.collection(Constants.COLLECTION_USERS)
            .whereEqualTo("numericId", cleanTarget)
            .limit(5)
        val snap = runCatching { colQuery.get(Source.SERVER).await() }.getOrNull()
            ?: colQuery.get().await()
        val adminDoc = snap.documents.firstOrNull { it.getString("numericId")?.trim() == cleanTarget }
            ?: error("تعذر العثور على حساب إدارة WESAL ($cleanTarget) في قاعدة البيانات")

        val adminUid = adminDoc.getString("uid")?.takeIf { it.isNotBlank() } ?: adminDoc.id
        require(adminUid != myUid) { "أنت مسجّل الدخول حالياً بحساب الإدارة ($cleanTarget)" }

        val sortedIds = listOf(myUid, adminUid).sorted()
        val deterministicChatId = "dm_${sortedIds[0]}_${sortedIds[1]}"
        val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(deterministicChatId)
        val existingChat = runCatching { chatRef.get(Source.SERVER).await() }.getOrNull()
            ?: runCatching { chatRef.get().await() }.getOrNull()

        val now = System.currentTimeMillis()
        val introText = "مرحباً بك في الدعم المباشر لإدارة WESAL ($cleanTarget). اكتب استفسارك أو رسالتك وسنرد عليك في أقرب وقت."
        val welcomeSupportMsgId = "support_intro_${deterministicChatId}"

        if (existingChat == null || !existingChat.exists()) {
            val data = mapOf(
                "chatId" to deterministicChatId,
                "isGroup" to false,
                "isSupportChat" to true,
                "supportTargetNumericId" to cleanTarget,
                "participantIds" to sortedIds,
                "lastMessagePreview" to introText,
                "lastMessageTime" to now,
                "lastMessageSenderId" to adminUid,
                "lastMessageStatus" to MessageStatus.DELIVERED.raw,
                "unreadCounts" to mapOf(myUid to 0, adminUid to 0),
                "pinnedBy" to emptyMap<String, Boolean>(),
                "mutedBy" to emptyMap<String, Boolean>(),
                "deletedForUsers" to emptyList<String>(),
                "typingUsers" to emptyMap<String, Boolean>(),
                "requestStatus" to "ACCEPTED",
                "isMessageRequest" to false
            )
            chatRef.set(data, SetOptions.merge()).await()
        } else {
            val existingPreview = existingChat.getString("lastMessagePreview").orEmpty()
            val updateFields = hashMapOf<String, Any>(
                "isSupportChat" to true,
                "supportTargetNumericId" to cleanTarget,
                "deletedForUsers" to FieldValue.arrayRemove(myUid)
            )
            if (existingPreview.isBlank()) {
                updateFields["lastMessagePreview"] = introText
                updateFields["lastMessageTime"] = now
            }
            chatRef.update(updateFields).await()
        }

        val hasAnyMessages = runCatching {
            !chatRef.collection(Constants.COLLECTION_MESSAGES).limit(1).get().await().isEmpty
        }.getOrDefault(false)
        if (!hasAnyMessages) {
            runCatching {
                chatRef.collection(Constants.COLLECTION_MESSAGES)
                    .document(welcomeSupportMsgId)
                    .set(
                        mapOf(
                            "messageId" to welcomeSupportMsgId,
                            "chatId" to deterministicChatId,
                            "senderId" to myUid,
                            "senderName" to "إدارة WESAL ($cleanTarget)",
                            "type" to MessageType.SYSTEM.raw,
                            "text" to introText,
                            "status" to MessageStatus.DELIVERED.raw,
                            "timestamp" to now
                        )
                    )
                    .await()
            }
        }
        deterministicChatId
    }

    private var cachedOwnerAdminAvatar: Pair<Long, String>? = null
    private suspend fun fetchOwnerAdminAvatarUrl(): String {
        val now = System.currentTimeMillis()
        val cached = cachedOwnerAdminAvatar
        if (cached != null && now - cached.first < 60_000L) return cached.second
        val url = runCatching {
            firestore.collection(Constants.COLLECTION_USERS)
                .whereEqualTo("numericId", Constants.OWNER_ADMIN_NUMERIC_ID)
                .limit(1)
                .get()
                .await()
                .documents
                .firstOrNull()
                ?.getString("photoUrl")
                .orEmpty()
        }.getOrDefault("")
        cachedOwnerAdminAvatar = now to url
        return url
    }

    override suspend fun ensureOfficialWelcomeChat(displayName: String, forceResend: Boolean): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: return@runCatching
        val cfgSnap = runCatching {
            firestore.collection(Constants.COLLECTION_SYSTEM_CONFIG)
                .document("welcome_message")
                .get()
                .await()
        }.getOrNull()?.takeIf { it.exists() } ?: runCatching {
            firestore.collection(Constants.COLLECTION_SYSTEM_CONFIG)
                .document("welcome_config")
                .get()
                .await()
        }.getOrNull()

        val enabled = cfgSnap?.getBoolean("enabled") ?: true
        if (!enabled && !forceResend) return@runCatching

        val template = cfgSnap?.getString("messageTemplate")?.takeIf { it.isNotBlank() }
            ?: SystemWelcomeConfig.DEFAULT_OFFICIAL_WELCOME_TEMPLATE
        val userName = displayName.trim().ifBlank { "صديقي" }
        val personalizedBody = template.replace("[اسم المستخدم]", userName)

        val officialChatId = "${Constants.OFFICIAL_CHAT_PREFIX}$myUid"
        val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(officialChatId)
        val chatSnap = runCatching { chatRef.get().await() }.getOrNull()

        val messageId = if (forceResend) "welcome_test_${System.currentTimeMillis()}" else "welcome_$myUid"
        val msgRef = chatRef.collection(Constants.COLLECTION_MESSAGES).document(messageId)
        val hasAnyMessage = runCatching {
            !chatRef.collection(Constants.COLLECTION_MESSAGES).limit(1).get().await().isEmpty
        }.getOrDefault(false)
        val now = System.currentTimeMillis()

        val adminAvatarUrl = fetchOwnerAdminAvatarUrl()
        if (chatSnap == null || !chatSnap.exists()) {
            chatRef.set(
                mapOf(
                    "chatId" to officialChatId,
                    "isGroup" to false,
                    "isOfficialWesalChat" to true,
                    "groupName" to "WESAL Messenger",
                    "groupAvatarUrl" to adminAvatarUrl,
                    "participantIds" to listOf(myUid),
                    "pinnedBy" to mapOf(myUid to true),
                    "mutedBy" to emptyMap<String, Boolean>(),
                    "deletedForUsers" to emptyList<String>(),
                    "typingUsers" to emptyMap<String, Boolean>(),
                    "unreadCounts" to mapOf(myUid to 1),
                    "lastMessagePreview" to personalizedBody.take(80),
                    "lastMessageTime" to now,
                    "lastMessageSenderId" to Constants.OFFICIAL_WESAL_SENDER_ID,
                    "lastMessageStatus" to MessageStatus.DELIVERED.raw
                ),
                SetOptions.merge()
            ).await()
        } else if (adminAvatarUrl.isNotBlank() && chatSnap.getString("groupAvatarUrl").orEmpty().isBlank()) {
            runCatching { chatRef.set(mapOf("groupAvatarUrl" to adminAvatarUrl), SetOptions.merge()).await() }
        }

        if (!hasAnyMessage || forceResend) {
            msgRef.set(
                mapOf(
                    "messageId" to messageId,
                    "chatId" to officialChatId,
                    "senderId" to Constants.OFFICIAL_WESAL_SENDER_ID,
                    "senderName" to "WESAL Messenger",
                    "type" to MessageType.TEXT.raw,
                    "text" to personalizedBody,
                    "ciphertext" to "",
                    "isEncrypted" to false,
                    "isEdited" to false,
                    "isDeletedForEveryone" to false,
                    "deletedForUsers" to emptyList<String>(),
                    "status" to MessageStatus.DELIVERED.raw,
                    "timestamp" to now
                )
            ).await()

            chatRef.set(
                mapOf(
                    "isOfficialWesalChat" to true,
                    "groupName" to "WESAL Messenger",
                    "pinnedBy.$myUid" to true,
                    "lastMessagePreview" to personalizedBody.take(80),
                    "lastMessageTime" to now,
                    "lastMessageSenderId" to "wesal_system",
                    "lastMessageStatus" to MessageStatus.DELIVERED.raw,
                    "unreadCounts.$myUid" to FieldValue.increment(1),
                    "deletedForUsers" to emptyList<String>()
                ),
                SetOptions.merge()
            ).await()

            firestore.collection(Constants.COLLECTION_USERS).document(myUid)
                .set(mapOf("welcomeSent" to true), SetOptions.merge())
                .await()
        }
    }

    override suspend fun setChatOfficialWallpaper(chatId: String, wallpaperUrl: String?): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        require(verifyIsOwnerSuperAdmin(myUid)) { "تغيير الخلفية الرسمية المشتركة للطرفين متاح لحساب الإدارة 10001 فقط" }
        val resolvedValue = if (wallpaperUrl.isNullOrBlank()) {
            ""
        } else if (wallpaperUrl.startsWith("http", ignoreCase = true) || wallpaperUrl.startsWith("data:", ignoreCase = true)) {
            wallpaperUrl
        } else {
            val uri = Uri.parse(wallpaperUrl)
            uploadProfileMediaToStorageOrSmallFallback(
                ctx = context,
                storagePath = "chat_wallpapers/$chatId.jpg",
                uri = uri,
                maxSide = 960,
                quality = 78
            ).also {
                require(it.isNotBlank()) { "تعذر حفظ الخلفية المشتركة" }
            }
        }
        firestore.collection(Constants.COLLECTION_CHATS)
            .document(chatId)
            .update("officialWallpaperUrl", resolvedValue)
            .await()
    }

    override suspend fun sendTextMessage(
        chatId: String,
        text: String,
        replyTo: WesalMessage?,
        forwardedFromName: String?
    ): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val me = fetchUserCached(myUid)
        require(me?.isBanned != true) { "حسابك محظور ولا يمكنك إرسال رسائل حالياً" }

        val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
        val chatSnap = chatRef.get().await()
        val isOfficial = (chatSnap.getBoolean("isOfficialWesalChat") ?: false) ||
            chatId.startsWith(Constants.OFFICIAL_CHAT_PREFIX)
        if (isOfficial) {
            require(verifyIsOwnerSuperAdmin(myUid)) {
                "قناة إشعارات WESAL Messenger الرسمية مخصصة للقراءة فقط"
            }
        }

        @Suppress("UNCHECKED_CAST")
        val participants = (chatSnap.get("participantIds") as? List<String>).orEmpty()
        val disappearingSec = chatSnap.getLong("disappearingDurationSec") ?: 0L
        val now = System.currentTimeMillis()
        val disappearingExpiresAt = if (disappearingSec > 0L) now + disappearingSec * 1000L else 0L
        val messageId = UUID.randomUUID().toString()
        val cleanText = text.trim()
        require(cleanText.isNotEmpty()) { "الرسالة فارغة" }

        val messageData = hashMapOf<String, Any?>(
            "messageId" to messageId,
            "chatId" to chatId,
            "senderId" to myUid,
            "senderName" to (me?.displayName?.ifBlank {
                me.publicId.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "WESAL User"
            } ?: "WESAL User"),
            "type" to MessageType.TEXT.raw,
            "ciphertext" to "",
            "text" to cleanText,
            "isEncrypted" to false,
            "isViewOnce" to false,
            "viewedBy" to emptyList<String>(),
            "wrappedKeys" to emptyMap<String, String>(),
            "disappearingExpiresAt" to disappearingExpiresAt,
            "replyToMessageId" to replyTo?.messageId,
            "replyToPreview" to replyTo?.text?.take(80),
            "forwardedFromName" to forwardedFromName,
            "isEdited" to false,
            "isDeletedForEveryone" to false,
            "deletedForUsers" to emptyList<String>(),
            "status" to MessageStatus.SENT.raw,
            "timestamp" to now
        )

        val preview = cleanText.take(80)
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
        // One atomic write for the message and the chat summary. While offline the write is queued
        // locally by Firestore (message shows as SENDING) and goes out automatically when back online,
        // so we only wait a bounded time for the server acknowledgement.
        // Two independent writes (no batch) so a stall can be pinned on the message write or on the chat summary.
        val msgTask = chatRef.collection(Constants.COLLECTION_MESSAGES).document(messageId).set(messageData)
        msgTask.addOnFailureListener { e ->
            Log.e("WesalChat", "text send failed for chat $chatId: ${e.message}")
            WesalDiag.report("فشل كتابة الرسالة: ${(e as? com.google.firebase.firestore.FirebaseFirestoreException)?.code ?: e.javaClass.simpleName} - ${e.message}")
        }
        val sumTask = chatRef.update(chatUpdates)
        sumTask.addOnFailureListener { e ->
            Log.e("WesalChat", "chat summary update failed for chat $chatId: ${e.message}")
            WesalDiag.report("فشل تحديث المحادثة: ${(e as? com.google.firebase.firestore.FirebaseFirestoreException)?.code ?: e.javaClass.simpleName} - ${e.message}")
        }
        val acked = withTimeoutOrNull(20_000L) { msgTask.await(); true }
        if (acked == null) {
            val sumDone = sumTask.isComplete
            WesalDiag.report("السيرفر لم يؤكد الإرسال خلال 20 ثانية. msgwrite=PENDING summary=${if (sumDone) "DONE" else "PENDING"}. جاري الفحص...")
            val probe = runCatching { NetProbe.run(firestore, auth) }.getOrElse { "probe failed: ${it.javaClass.simpleName}" }
            WesalDiag.report("السيرفر لم يؤكد الإرسال خلال 20 ثانية. msgwrite=PENDING summary=${if (sumTask.isComplete) "DONE" else "PENDING"} فحص: $probe")
        }
        Unit
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
        caption: String,
        isViewOnce: Boolean
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
                require(me?.isBanned != true) { "حسابك محظور ولا يمكنك إرسال الوسائط" }
                val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
                val chatSnap = chatRef.get().await()
                val isOfficial = (chatSnap.getBoolean("isOfficialWesalChat") ?: false) ||
                    chatId.startsWith(Constants.OFFICIAL_CHAT_PREFIX)
                if (isOfficial) {
                    require(verifyIsOwnerSuperAdmin(myUid)) {
                        "محادثة WESAL Messenger الرسمية مخصصة للقراءة فقط"
                    }
                }

                @Suppress("UNCHECKED_CAST")
                val participants = (chatSnap.get("participantIds") as? List<String>).orEmpty()
                val disappearingSec = chatSnap.getLong("disappearingDurationSec") ?: 0L
                val messageId = UUID.randomUUID().toString()

                trySend(Result.success(0.15f))
                var resolvedSizeBytes = sizeBytes
                val downloadUrl = when (type) {
                    MessageType.IMAGE -> {
                        trySend(Result.success(0.42f))
                        val smartDataUrl = withContext(Dispatchers.IO) {
                            uriToBase64Smart(
                                ctx = context,
                                uri = uri,
                                maxSide = 760,
                                quality = 78,
                                preserveAlphaIfPng = true
                            )
                        }
                        val rawBytes = runCatching {
                            Base64.decode(smartDataUrl.substringAfter("base64,"), Base64.NO_WRAP)
                        }.getOrNull() ?: ByteArray(0)
                        require(smartDataUrl.isNotBlank() && rawBytes.isNotEmpty() && rawBytes.size <= 540_000) {
                            "تعذر ضغط الصورة المحددة للحفظ المباشر"
                        }
                        resolvedSizeBytes = rawBytes.size.toLong()
                        trySend(Result.success(0.88f))
                        smartDataUrl
                    }
                    MessageType.VOICE, MessageType.AUDIO, MessageType.VIDEO, MessageType.DOCUMENT -> {
                        trySend(Result.success(0.42f))
                        val rawBytes = withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                ?: error("تعذر قراءة الملف المحدد من الجهاز")
                        }
                        require(rawBytes.isNotEmpty()) { "الملف المحدد فارغ" }
                        require(rawBytes.size <= 540_000) {
                            when (type) {
                                MessageType.AUDIO, MessageType.VOICE ->
                                    "الملف الصوتي كبير (${rawBytes.size / 1024} KB) والحد 540 KB. سجّل رسالة أقصر أو اختر ملفاً أصغر"
                                MessageType.VIDEO ->
                                    "الفيديو كبير (${rawBytes.size / 1024} KB) والحد 540 KB. اختر فيديو أقصر أو أصغر"
                                else ->
                                    "المستند كبير (${rawBytes.size / 1024} KB) والحد 540 KB. اختر ملفاً أصغر"
                            }
                        }
                        resolvedSizeBytes = rawBytes.size.toLong()
                        trySend(Result.success(0.88f))
                        "wesal-plain://" + Base64.encodeToString(rawBytes, Base64.NO_WRAP)
                    }
                    else -> throw IllegalStateException("نوع المرفق غير مدعوم للإرسال")
                }

                val now = System.currentTimeMillis()
                val disappearingExpiresAt = if (disappearingSec > 0L) now + disappearingSec * 1000L else 0L
                val msgData = hashMapOf<String, Any?>(
                    "messageId" to messageId,
                    "chatId" to chatId,
                    "senderId" to myUid,
                    "senderName" to (me?.displayName?.ifBlank {
                        me.publicId.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "WESAL User"
                    } ?: "WESAL User"),
                    "type" to type.raw,
                    "text" to caption.trim(),
                    "isEncrypted" to false,
                    "isViewOnce" to isViewOnce,
                    "viewedBy" to emptyList<String>(),
                    "wrappedKeys" to emptyMap<String, String>(),
                    "mediaUrl" to downloadUrl,
                    "mediaMimeType" to mimeType,
                    "mediaFileName" to fileName,
                    "mediaSizeBytes" to resolvedSizeBytes,
                    "mediaDurationMs" to durationMs,
                    "waveform" to waveform,
                    "disappearingExpiresAt" to disappearingExpiresAt,
                    "isEdited" to false,
                    "isDeletedForEveryone" to false,
                    "deletedForUsers" to emptyList<String>(),
                    "status" to MessageStatus.SENT.raw,
                    "timestamp" to now
                )
                chatRef.collection(Constants.COLLECTION_MESSAGES).document(messageId).set(msgData).await()

                val previewLabel = when {
                    isViewOnce && type == MessageType.IMAGE -> "صورة للعرض مرة واحدة"
                    isViewOnce && type == MessageType.VIDEO -> "فيديو للعرض مرة واحدة"
                    type == MessageType.IMAGE -> if (caption.isNotBlank()) "صورة: ${caption.trim().take(40)}" else "صورة"
                    type == MessageType.VIDEO -> if (caption.isNotBlank()) "فيديو: ${caption.trim().take(40)}" else "فيديو"
                    type == MessageType.VOICE -> "رسالة صوتية"
                    type == MessageType.AUDIO -> if (fileName.isNotBlank()) "ملف صوتي: $fileName" else "ملف صوتي"
                    type == MessageType.DOCUMENT -> "ملف: $fileName"
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

    override suspend fun markViewOnceOpened(chatId: String, messageId: String): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val msgRef = firestore.collection(Constants.COLLECTION_CHATS)
            .document(chatId)
            .collection(Constants.COLLECTION_MESSAGES)
            .document(messageId)
        val snap = msgRef.get().await()
        if (!snap.exists()) return@runCatching
        val senderId = snap.getString("senderId").orEmpty()
        if (senderId == myUid) return@runCatching

        // Receiver opened view-once media -> permanently record viewer and scrub media payload
        msgRef.update(
            mapOf(
                "viewedBy" to FieldValue.arrayUnion(myUid),
                "mediaUrl" to "",
                "ciphertext" to ""
            )
        ).await()

        withContext(Dispatchers.IO) {
            val cacheDir = File(context.cacheDir, "decrypted_media")
            cacheDir.listFiles()?.filter { it.nameWithoutExtension == messageId }?.forEach {
                runCatching { it.delete() }
            }
        }
    }

    override suspend fun decryptMediaToCache(message: WesalMessage): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val myUid = auth.currentUser?.uid ?: error("Not authenticated")
            if (message.isViewOnce && message.senderId != myUid && message.viewedBy.contains(myUid)) {
                error("تمت مشاهدة هذه الرسالة لمرة واحدة ولا يمكن فتحها مجدداً")
            }
            val cacheDir = File(context.cacheDir, "decrypted_media").apply { mkdirs() }
            val ext = message.mediaFileName.substringAfterLast('.', "").ifBlank {
                when (message.type) {
                    MessageType.IMAGE -> "jpg"
                    MessageType.VIDEO -> "mp4"
                    MessageType.VOICE -> "m4a"
                    MessageType.AUDIO -> "mp3"
                    else -> "bin"
                }
            }
            val targetFile = File(cacheDir, "${message.messageId}.$ext")
            if (targetFile.exists() && targetFile.length() > 0L) {
                return@runCatching targetFile
            }

            if (message.mediaUrl.startsWith("http", ignoreCase = true)) {
                URL(message.mediaUrl).openStream().use { input ->
                    targetFile.outputStream().use { output -> input.copyTo(output) }
                }
                if (targetFile.exists() && targetFile.length() > 0L) {
                    return@runCatching targetFile
                }
            }

            if (message.mediaUrl.startsWith("wesal-plain://")) {
                val raw = Base64.decode(message.mediaUrl.removePrefix("wesal-plain://"), Base64.NO_WRAP)
                targetFile.writeBytes(raw)
                return@runCatching targetFile
            }

            if (message.mediaUrl.startsWith("data:")) {
                val raw = Base64.decode(message.mediaUrl.substringAfter("base64,"), Base64.NO_WRAP)
                targetFile.writeBytes(raw)
                return@runCatching targetFile
            }

            val encryptedBytes = when {
                message.mediaUrl.startsWith("wesal-inline://") -> {
                    Base64.decode(message.mediaUrl.removePrefix("wesal-inline://"), Base64.NO_WRAP)
                }
                else -> {
                    error("تعذر تحميل المرفق أو تم حذفه")
                }
            }

            val wrappedKey = message.wrappedKeys[myUid]
            val plainBytes = if (!wrappedKey.isNullOrBlank()) {
                val senderPub = fetchUserCached(message.senderId)?.publicKey.orEmpty()
                val peerPub = if (message.senderId == myUid) {
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
        address: String,
        liveDurationMinutes: Int
    ): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val me = fetchUserCached(myUid)
        val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
        val chatSnap = chatRef.get().await()
        val isOfficial = (chatSnap.getBoolean("isOfficialWesalChat") ?: false) ||
            chatId.startsWith(Constants.OFFICIAL_CHAT_PREFIX)
        if (isOfficial) {
            require(verifyIsOwnerSuperAdmin(myUid)) { "قناة WESAL Messenger الرسمية للقراءة فقط" }
        }

        @Suppress("UNCHECKED_CAST")
        val participants = (chatSnap.get("participantIds") as? List<String>).orEmpty()
        val disappearingSec = chatSnap.getLong("disappearingDurationSec") ?: 0L
        val now = System.currentTimeMillis()
        val messageId = UUID.randomUUID().toString()
        val isLive = liveDurationMinutes > 0
        val liveExpiresAt = if (isLive) now + liveDurationMinutes * 60_000L else 0L
        val disappearingExpiresAt = if (disappearingSec > 0L) now + disappearingSec * 1000L else 0L

        val msg = mapOf(
            "messageId" to messageId,
            "chatId" to chatId,
            "senderId" to myUid,
            "senderName" to (me?.displayName?.ifBlank {
                me.publicId.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "WESAL User"
            } ?: "WESAL User"),
            "type" to if (isLive) MessageType.LIVE_LOCATION.raw else MessageType.LOCATION.raw,
            "text" to address.ifBlank { if (isLive) "مشاركة الموقع المباشر" else "الموقع الجغرافي" },
            "latitude" to latitude,
            "longitude" to longitude,
            "locationAddress" to address,
            "liveLocationExpiresAt" to liveExpiresAt,
            "disappearingExpiresAt" to disappearingExpiresAt,
            "isEncrypted" to false,
            "status" to MessageStatus.SENT.raw,
            "timestamp" to now
        )
        chatRef.collection(Constants.COLLECTION_MESSAGES).document(messageId).set(msg).await()
        val chatUpdates = hashMapOf<String, Any>(
            "lastMessagePreview" to if (isLive) "موقع مباشر ($liveDurationMinutes د)" else "موقع جغرافي",
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

    override suspend fun setChatDisappearingTimer(chatId: String, durationSeconds: Long): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
        val snap = chatRef.get().await()
        @Suppress("UNCHECKED_CAST")
        val participants = (snap.get("participantIds") as? List<String>).orEmpty()
        require(myUid in participants) { "لا يمكنك تعديل مؤقت هذه المحادثة" }
        chatRef.update("disappearingDurationSec", durationSeconds.coerceAtLeast(0L)).await()
    }

    override suspend fun setMessageRequestDecision(
        chatId: String,
        peerUid: String,
        decision: String
    ): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val normalized = decision.trim().uppercase()
        val reqStatus = when (normalized) {
            "ACCEPT", "ACCEPTED" -> "ACCEPTED"
            "HOLD", "HELD" -> "HELD"
            "REJECT", "REJECTED", "DELETE", "BLOCK" -> "REJECTED"
            else -> normalized
        }
        if (chatId.isNotBlank()) {
            val chatPatch = hashMapOf<String, Any>(
                "requestStatus" to reqStatus,
                "isMessageRequest" to (reqStatus != "ACCEPTED")
            )
            if (reqStatus == "REJECTED" || normalized == "DELETE") {
                chatPatch["deletedForUsers"] = FieldValue.arrayUnion(myUid)
            }
            runCatching {
                firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
                    .set(chatPatch, SetOptions.merge())
                    .await()
            }
        }
        if (peerUid.isNotBlank() && peerUid != myUid) {
            val sorted = listOf(myUid, peerUid).sorted()
            val reqId = "${sorted[0]}_${sorted[1]}"
            val reqRef = firestore.collection(Constants.COLLECTION_CONTACT_REQUESTS).document(reqId)
            val reqSnap = runCatching { reqRef.get().await() }.getOrNull()
            if (reqSnap != null && reqSnap.exists()) {
                if (normalized == "DELETE") {
                    reqRef.delete().await()
                } else {
                    reqRef.set(
                        mapOf(
                            "status" to reqStatus,
                            "updatedAt" to System.currentTimeMillis()
                        ),
                        SetOptions.merge()
                    ).await()
                }
            }
        }
    }

    override suspend fun sendBusinessBroadcast(
        recipientUids: List<String>,
        text: String
    ): Result<Map<String, Boolean>> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val cleanText = text.trim()
        require(cleanText.isNotEmpty()) { "نص الرسالة مطلوب" }
        val distinctRecipients = recipientUids.filter { it.isNotBlank() && it != myUid }.distinct()
        require(distinctRecipients.isNotEmpty()) { "يرجى اختيار مستلم واحد على الأقل" }
        val report = linkedMapOf<String, Boolean>()
        for (peerUid in distinctRecipients) {
            val res = runCatching {
                val chatId = getOrCreateDirectChat(peerUid).getOrThrow()
                sendTextMessage(chatId = chatId, text = cleanText).getOrThrow()
            }
            report[peerUid] = res.isSuccess
        }
        report
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
        require(snap.getString("senderId") == myUid || verifyIsOwnerSuperAdmin(myUid)) {
            "لا يمكنك حذف رسالة مستخدم آخر للجميع"
        }
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

    override suspend fun toggleMuteChat(chatId: String, muted: Boolean): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        firestore.collection(Constants.COLLECTION_CHATS).document(chatId).update("mutedBy.$myUid", muted).await()
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun deleteChat(chatId: String, forEveryone: Boolean): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
        val chatSnap = chatRef.get().await()
        val isOfficial = (chatSnap.getBoolean("isOfficialWesalChat") ?: false) ||
            chatId.startsWith(Constants.OFFICIAL_CHAT_PREFIX)
        val isGroup = chatSnap.getBoolean("isGroup") ?: false

        if (forEveryone && !isOfficial && !isGroup) {
            val participants = (chatSnap.get("participantIds") as? List<String>).orEmpty()
            require(myUid in participants) { "غير مصرح بحذف هذه المحادثة للطرفين" }
            val msgsSnap = chatRef.collection(Constants.COLLECTION_MESSAGES).limit(350).get().await()
            val batch = firestore.batch()
            msgsSnap.documents.forEach { msgDoc ->
                batch.update(
                    msgDoc.reference,
                    mapOf(
                        "isDeletedForEveryone" to true,
                        "text" to "",
                        "ciphertext" to "",
                        "mediaUrl" to "",
                        "deletedForUsers" to participants
                    )
                )
            }
            batch.update(
                chatRef,
                mapOf(
                    "deletedForUsers" to participants,
                    "lastMessagePreview" to "",
                    "lastMessageSenderId" to ""
                )
            )
            batch.commit().await()
        } else {
            chatRef.update("deletedForUsers", FieldValue.arrayUnion(myUid)).await()
        }
        wesalDao.deleteChat(chatId)
    }

    override suspend fun clearChatMessagesForMe(chatId: String): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
        val msgsSnap = chatRef.collection(Constants.COLLECTION_MESSAGES).limit(400).get().await()
        msgsSnap.documents.chunked(350).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { msgDoc ->
                batch.update(msgDoc.reference, "deletedForUsers", FieldValue.arrayUnion(myUid))
            }
            batch.commit().await()
        }
        chatRef.set(mapOf("unreadCounts.$myUid" to 0), SetOptions.merge()).await()
    }
}



