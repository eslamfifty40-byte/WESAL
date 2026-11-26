package com.wesal.messenger.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.domain.model.CallRecord
import com.wesal.messenger.domain.model.CallStatus
import com.wesal.messenger.domain.model.MessageStatus
import com.wesal.messenger.domain.model.MessageType
import com.wesal.messenger.domain.model.ScheduledCallRecord
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.CallRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) : CallRepository {

    private fun observeAuthUid(): Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { fa -> trySend(fa.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    @Suppress("UNCHECKED_CAST")
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeCallHistory(): Flow<List<CallRecord>> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(emptyList())
        } else {
            callbackFlow {
                val reg = firestore.collection(Constants.COLLECTION_CALLS)
                    .whereArrayContains("participants", uid)
                    .addSnapshotListener { snap, err ->
                        if (err != null) return@addSnapshotListener
                        val list = snap?.documents?.mapNotNull { doc ->
                            val deletedFor = (doc.get("deletedForUsers") as? List<String>).orEmpty()
                            if (deletedFor.contains(uid)) return@mapNotNull null
                            CallRecord(
                                callId = doc.id,
                                callerId = doc.getString("callerId").orEmpty(),
                                callerName = doc.getString("callerName").orEmpty(),
                                callerAvatarUrl = doc.getString("callerAvatarUrl").orEmpty(),
                                callerVerified = doc.getBoolean("callerVerified") ?: false,
                                calleeId = doc.getString("calleeId").orEmpty(),
                                calleeName = doc.getString("calleeName").orEmpty(),
                                calleeAvatarUrl = doc.getString("calleeAvatarUrl").orEmpty(),
                                calleeVerified = doc.getBoolean("calleeVerified") ?: false,
                                isVideo = doc.getBoolean("isVideo") ?: false,
                                status = CallStatus.fromRaw(doc.getString("status")),
                                calleeRingingAck = doc.getBoolean("calleeRingingAck") ?: false,
                                ringingAt = doc.getLong("ringingAt") ?: 0L,
                                deletedForUsers = deletedFor,
                                startedAt = doc.getLong("startedAt") ?: 0L,
                                endedAt = doc.getLong("endedAt") ?: 0L,
                                durationSeconds = doc.getLong("durationSeconds") ?: 0L
                            )
                        }.orEmpty().sortedByDescending { it.startedAt }
                        trySend(list)
                    }
                awaitClose { reg.remove() }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeIncomingCalls(): Flow<CallRecord?> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(null)
        } else {
            callbackFlow {
                var lastLoggedRingId = ""
                com.wesal.messenger.chat.WesalDiag.ringLog("calls listener ON")
                // NOTE: must filter on the same field the security rule checks
                // (uid in participants). A whereEqualTo("calleeId", uid) query cannot be
                // proven readable by the rules engine, so the listener was denied and
                // incoming calls never appeared. participants-arrayContains is provable.
                val reg = firestore.collection(Constants.COLLECTION_CALLS)
                    .whereArrayContains("participants", uid)
                    .addSnapshotListener { snap, err ->
                        if (err != null) {
                            com.wesal.messenger.chat.WesalDiag.ringLog("calls listener ERR ${(err as? FirebaseFirestoreException)?.code ?: err.javaClass.simpleName}")
                            return@addSnapshotListener
                        }
                        // Single-field arrayContains query: no composite index needed. Status is
                        // filtered client-side. Cutoff is generous (5 min) so caller/callee phone
                        // clock skew cannot hide a live call.
                        val cutoff = System.currentTimeMillis() - 300_000L
                        run {
                            val total = snap?.size() ?: 0
                            val live = snap?.documents?.count {
                                val st = it.getString("status")
                                st == CallStatus.CONNECTING.raw || st == CallStatus.RINGING.raw
                            } ?: 0
                            val newest = snap?.documents?.maxOfOrNull { it.getLong("startedAt") ?: 0L } ?: 0L
                            val skewMs = if (newest > 0L) System.currentTimeMillis() - newest else -1L
                            com.wesal.messenger.chat.WesalDiag.ringLog(
                                "calls snap docs=$total live=$live cache=${snap?.metadata?.isFromCache} newestAgeS=${if (skewMs >= 0) skewMs / 1000 else -1}"
                            )
                        }
                        val activeRing = snap?.documents
                            ?.mapNotNull { doc ->
                                val startedAt = doc.getLong("startedAt") ?: 0L
                                val callerId = doc.getString("callerId").orEmpty()
                                val st = doc.getString("status")
                                if (st != CallStatus.CONNECTING.raw && st != CallStatus.RINGING.raw) {
                                    return@mapNotNull null
                                }
                                if (startedAt < cutoff || callerId.isBlank() || callerId == uid) {
                                    return@mapNotNull null
                                }
                                if (doc.getString("calleeId").orEmpty() != uid) {
                                    return@mapNotNull null
                                }
                                CallRecord(
                                    callId = doc.id,
                                    callerId = callerId,
                                    callerName = doc.getString("callerName").orEmpty(),
                                    callerAvatarUrl = doc.getString("callerAvatarUrl").orEmpty(),
                                    callerVerified = doc.getBoolean("callerVerified") ?: false,
                                    calleeId = doc.getString("calleeId").orEmpty(),
                                    calleeName = doc.getString("calleeName").orEmpty(),
                                    calleeAvatarUrl = doc.getString("calleeAvatarUrl").orEmpty(),
                                    calleeVerified = doc.getBoolean("calleeVerified") ?: false,
                                    isVideo = doc.getBoolean("isVideo") ?: false,
                                    status = CallStatus.fromRaw(doc.getString("status")),
                                    calleeRingingAck = doc.getBoolean("calleeRingingAck") ?: false,
                                    ringingAt = doc.getLong("ringingAt") ?: 0L,
                                    offerSdp = doc.getString("offerSdp").orEmpty(),
                                    startedAt = startedAt
                                )
                            }
                            ?.maxByOrNull { it.startedAt }
                        if (activeRing != null && activeRing.callId != lastLoggedRingId) {
                            lastLoggedRingId = activeRing.callId
                            com.wesal.messenger.chat.WesalDiag.ringLog("incoming visible ${activeRing.callId.take(6)} status=${activeRing.status}")
                        }
                        trySend(activeRing)
                    }
                awaitClose { reg.remove() }
            }
        }
    }

    override fun observeCall(callId: String): Flow<CallRecord?> = callbackFlow {
        val reg = firestore.collection(Constants.COLLECTION_CALLS)
            .document(callId)
            .addSnapshotListener { doc, err ->
                if (err != null) return@addSnapshotListener
                if (doc == null || !doc.exists()) {
                    trySend(null)
                    return@addSnapshotListener
                }
                trySend(
                    CallRecord(
                        callId = doc.id,
                        callerId = doc.getString("callerId").orEmpty(),
                        callerName = doc.getString("callerName").orEmpty(),
                        callerAvatarUrl = doc.getString("callerAvatarUrl").orEmpty(),
                        callerVerified = doc.getBoolean("callerVerified") ?: false,
                        calleeId = doc.getString("calleeId").orEmpty(),
                        calleeName = doc.getString("calleeName").orEmpty(),
                        calleeAvatarUrl = doc.getString("calleeAvatarUrl").orEmpty(),
                        calleeVerified = doc.getBoolean("calleeVerified") ?: false,
                        isVideo = doc.getBoolean("isVideo") ?: false,
                        status = CallStatus.fromRaw(doc.getString("status")),
                        calleeRingingAck = doc.getBoolean("calleeRingingAck") ?: false,
                        ringingAt = doc.getLong("ringingAt") ?: 0L,
                        offerSdp = doc.getString("offerSdp").orEmpty(),
                        answerSdp = doc.getString("answerSdp").orEmpty(),
                        startedAt = doc.getLong("startedAt") ?: 0L,
                        endedAt = doc.getLong("endedAt") ?: 0L,
                        durationSeconds = doc.getLong("durationSeconds") ?: 0L
                    )
                )
            }
        awaitClose { reg.remove() }
    }

    override suspend fun initiateCall(callee: WesalUser, isVideo: Boolean, offerSdp: String): Result<String> = runCatching {
        val callerUid = auth.currentUser?.uid ?: error("Not authenticated")
        val callerSnap = firestore.collection(Constants.COLLECTION_USERS).document(callerUid).get().await()
        val callerHandle = callerSnap.getString("publicId")?.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty()
        val calleeHandle = callee.publicId.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty()
        val callId = UUID.randomUUID().toString()
        val data = mapOf(
            "callId" to callId,
            "participants" to listOf(callerUid, callee.uid),
            "callerId" to callerUid,
            "callerName" to (callerSnap.getString("displayName")?.ifBlank { callerHandle } ?: "WESAL User"),
            "callerAvatarUrl" to (callerSnap.getString("photoUrl") ?: ""),
            "callerVerified" to (callerSnap.getBoolean("isVerified") ?: false),
            "calleeId" to callee.uid,
            "calleeName" to callee.displayName.ifBlank { calleeHandle.ifBlank { "WESAL User" } },
            "calleeAvatarUrl" to callee.photoUrl,
            "calleeVerified" to callee.isVerified,
            "isVideo" to isVideo,
            // Starts in CONNECTING; switches to RINGING only when callee confirms active reception
            "status" to CallStatus.CONNECTING.raw,
            "calleeRingingAck" to false,
            "ringingAt" to 0L,
            "offerSdp" to offerSdp,
            "answerSdp" to "",
            "deletedForUsers" to emptyList<String>(),
            "startedAt" to System.currentTimeMillis(),
            "endedAt" to 0L,
            "durationSeconds" to 0L
        )
        firestore.collection(Constants.COLLECTION_CALLS).document(callId).set(data).await()
        com.wesal.messenger.chat.WesalDiag.ringLog("caller wrote call ${callId.take(6)} participants=2 calleeUid=${callee.uid.take(6)}")
        upsertChatCallLog(
            callId = callId,
            callerId = callerUid,
            calleeId = callee.uid,
            callerName = data["callerName"] as String,
            isVideo = isVideo,
            status = CallStatus.CONNECTING,
            durationSeconds = 0L,
            timestamp = data["startedAt"] as Long
        )
        callId
    }

    override suspend fun acknowledgeIncomingRing(callId: String): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val callRef = firestore.collection(Constants.COLLECTION_CALLS).document(callId)
        firestore.runTransaction { tx ->
            val snap = tx.get(callRef)
            if (!snap.exists()) return@runTransaction
            if (snap.getString("calleeId") != uid) return@runTransaction
            val status = CallStatus.fromRaw(snap.getString("status"))
            if (status == CallStatus.CONNECTING) {
                tx.update(
                    callRef,
                    mapOf(
                        "status" to CallStatus.RINGING.raw,
                        "calleeRingingAck" to true,
                        "ringingAt" to System.currentTimeMillis()
                    )
                )
            }
        }.await()
    }

    override suspend fun submitAnswer(callId: String, answerSdp: String): Result<Unit> = runCatching {
        firestore.collection(Constants.COLLECTION_CALLS)
            .document(callId)
            .update(
                mapOf(
                    "answerSdp" to answerSdp,
                    "status" to CallStatus.ACTIVE.raw
                )
            ).await()
    }

    override suspend fun addIceCandidate(
        callId: String,
        sdpMid: String,
        sdpMLineIndex: Int,
        candidate: String,
        fromCaller: Boolean
    ) {
        runCatching {
            val data = mapOf(
                "sdpMid" to sdpMid,
                "sdpMLineIndex" to sdpMLineIndex,
                "candidate" to candidate,
                "fromCaller" to fromCaller,
                "createdAt" to System.currentTimeMillis()
            )
            firestore.collection(Constants.COLLECTION_CALLS)
                .document(callId)
                .collection(Constants.SUBCOLLECTION_CANDIDATES)
                .add(data)
                .await()
        }
    }

    override fun observeRemoteIceCandidates(callId: String, listenForCaller: Boolean): Flow<Triple<String, Int, String>> = callbackFlow {
        val reg = firestore.collection(Constants.COLLECTION_CALLS)
            .document(callId)
            .collection(Constants.SUBCOLLECTION_CANDIDATES)
            .whereEqualTo("fromCaller", listenForCaller)
            .addSnapshotListener { snap, _ ->
                snap?.documentChanges?.forEach { change ->
                    if (change.type == DocumentChange.Type.ADDED) {
                        val doc = change.document
                        val mid = doc.getString("sdpMid").orEmpty()
                        val idx = doc.getLong("sdpMLineIndex")?.toInt() ?: 0
                        val cand = doc.getString("candidate").orEmpty()
                        if (cand.isNotBlank()) trySend(Triple(mid, idx, cand))
                    }
                }
            }
        awaitClose { reg.remove() }
    }

    override suspend fun updateCallStatus(callId: String, status: CallStatus, durationSeconds: Long) {
        runCatching {
            val now = System.currentTimeMillis()
            val updates = hashMapOf<String, Any>(
                "status" to status.raw,
                "durationSeconds" to durationSeconds
            )
            if (status == CallStatus.ENDED || status == CallStatus.REJECTED || status == CallStatus.MISSED) {
                updates["endedAt"] = now
            }
            val callRef = firestore.collection(Constants.COLLECTION_CALLS).document(callId)
            callRef.update(updates).await()
            if (status == CallStatus.ACTIVE || status == CallStatus.ENDED || status == CallStatus.REJECTED || status == CallStatus.MISSED) {
                val snap = callRef.get().await()
                val callerId = snap.getString("callerId").orEmpty()
                val calleeId = snap.getString("calleeId").orEmpty()
                val callerName = snap.getString("callerName").orEmpty().ifBlank { "WESAL User" }
                val isVideo = snap.getBoolean("isVideo") ?: false
                val startedAt = snap.getLong("startedAt") ?: now
                if (callerId.isNotBlank() && calleeId.isNotBlank()) {
                    upsertChatCallLog(
                        callId = callId,
                        callerId = callerId,
                        calleeId = calleeId,
                        callerName = callerName,
                        isVideo = isVideo,
                        status = status,
                        durationSeconds = durationSeconds,
                        timestamp = startedAt
                    )
                }
            }
        }
    }

    private suspend fun upsertChatCallLog(
        callId: String,
        callerId: String,
        calleeId: String,
        callerName: String,
        isVideo: Boolean,
        status: CallStatus,
        durationSeconds: Long,
        timestamp: Long
    ) {
        if (callerId.isBlank() || calleeId.isBlank() || callerId == calleeId) return
        val myUid = auth.currentUser?.uid ?: return
        runCatching {
            val sortedIds = listOf(callerId, calleeId).sorted()
            val chatId = "dm_${sortedIds[0]}_${sortedIds[1]}"
            val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
            val chatSnap = chatRef.get().await()
            if (!chatSnap.exists()) return@runCatching

            val prefix = if (isVideo) "📹 مكالمة فيديو" else "📞 مكالمة صوتية"
            val summary = when (status) {
                CallStatus.CONNECTING, CallStatus.RINGING -> "$prefix • جاري الاتصال..."
                CallStatus.ACTIVE -> "$prefix • جارية الآن"
                CallStatus.MISSED -> "$prefix • لم يتم الرد"
                CallStatus.REJECTED -> "$prefix • مرفوضة"
                CallStatus.ENDED -> if (durationSeconds > 0L) {
                    val mins = durationSeconds / 60L
                    val secs = durationSeconds % 60L
                    "$prefix • ${"%02d:%02d".format(mins, secs)}"
                } else {
                    "$prefix • انتهت"
                }
            }
            val msgId = "call_$callId"
            val msgRef = chatRef.collection(Constants.COLLECTION_MESSAGES).document(msgId)
            val msgSnap = msgRef.get().await()
            if (!msgSnap.exists() && myUid == callerId) {
                msgRef.set(
                    mapOf(
                        "messageId" to msgId,
                        "chatId" to chatId,
                        "senderId" to callerId,
                        "senderName" to callerName,
                        "type" to MessageType.CALL_LOG.raw,
                        "text" to summary,
                        "ciphertext" to "",
                        "isEncrypted" to false,
                        "mediaDurationMs" to durationSeconds * 1000L,
                        "status" to MessageStatus.DELIVERED.raw,
                        "timestamp" to timestamp
                    )
                ).await()
            } else if (msgSnap.exists() && msgSnap.getString("senderId") == myUid) {
                msgRef.set(
                    mapOf(
                        "text" to summary,
                        "mediaDurationMs" to durationSeconds * 1000L,
                        "status" to MessageStatus.DELIVERED.raw
                    ),
                    SetOptions.merge()
                ).await()
            }
            chatRef.set(
                mapOf(
                    "lastMessagePreview" to summary,
                    "lastMessageTime" to System.currentTimeMillis(),
                    "lastMessageSenderId" to callerId,
                    "lastMessageStatus" to MessageStatus.DELIVERED.raw
                ),
                SetOptions.merge()
            ).await()
        }
    }

    override suspend fun deleteCallForMe(callId: String): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        firestore.collection(Constants.COLLECTION_CALLS)
            .document(callId)
            .update("deletedForUsers", FieldValue.arrayUnion(uid))
            .await()
    }

    override suspend fun deleteCallsForMe(callIds: List<String>): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val cleanIds = callIds.filter { it.isNotBlank() }.distinct()
        if (cleanIds.isEmpty()) return@runCatching
        cleanIds.chunked(400).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { cid ->
                val ref = firestore.collection(Constants.COLLECTION_CALLS).document(cid)
                batch.update(ref, "deletedForUsers", FieldValue.arrayUnion(uid))
            }
            batch.commit().await()
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun clearCallHistoryForMe(): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val snap = firestore.collection(Constants.COLLECTION_CALLS)
            .whereArrayContains("participants", uid)
            .get()
            .await()
        val toHide = snap.documents.filter { doc ->
            val deletedFor = (doc.get("deletedForUsers") as? List<String>).orEmpty()
            !deletedFor.contains(uid)
        }
        toHide.chunked(400).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { doc ->
                batch.update(doc.reference, "deletedForUsers", FieldValue.arrayUnion(uid))
            }
            batch.commit().await()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeScheduledCalls(): Flow<List<ScheduledCallRecord>> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(emptyList())
        } else {
            callbackFlow {
                val reg = firestore.collection(Constants.COLLECTION_SCHEDULED_CALLS)
                    .whereArrayContains("participants", uid)
                    .addSnapshotListener { snap, err ->
                        if (err != null) return@addSnapshotListener
                        val list = snap?.documents?.map { d ->
                            ScheduledCallRecord(
                                scheduleId = d.getString("scheduleId") ?: d.id,
                                creatorUid = d.getString("creatorUid").orEmpty(),
                                peerUid = d.getString("peerUid").orEmpty(),
                                peerName = d.getString("peerName").orEmpty(),
                                peerAvatarUrl = d.getString("peerAvatarUrl").orEmpty(),
                                isVideo = d.getBoolean("isVideo") ?: false,
                                scheduledAt = d.getLong("scheduledAt") ?: 0L,
                                note = d.getString("note").orEmpty(),
                                createdAt = d.getLong("createdAt") ?: 0L
                            )
                        }.orEmpty().sortedBy { it.scheduledAt }
                        trySend(list)
                    }
                awaitClose { reg.remove() }
            }
        }
    }

    override suspend fun scheduleCall(
        peer: WesalUser,
        isVideo: Boolean,
        scheduledAt: Long,
        note: String
    ): Result<ScheduledCallRecord> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        require(peer.uid.isNotBlank() && peer.uid != uid) { "يرجى اختيار جهة اتصال صالحة" }
        val scheduleId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val record = ScheduledCallRecord(
            scheduleId = scheduleId,
            creatorUid = uid,
            peerUid = peer.uid,
            peerName = peer.displayName.ifBlank { peer.publicId.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "WESAL User" },
            peerAvatarUrl = peer.photoUrl,
            isVideo = isVideo,
            scheduledAt = scheduledAt,
            note = note.trim(),
            createdAt = now
        )
        firestore.collection(Constants.COLLECTION_SCHEDULED_CALLS)
            .document(scheduleId)
            .set(
                mapOf(
                    "scheduleId" to scheduleId,
                    "creatorUid" to uid,
                    "peerUid" to peer.uid,
                    "participants" to listOf(uid, peer.uid).distinct(),
                    "peerName" to record.peerName,
                    "peerAvatarUrl" to record.peerAvatarUrl,
                    "isVideo" to isVideo,
                    "scheduledAt" to scheduledAt,
                    "note" to record.note,
                    "createdAt" to now
                )
            )
            .await()
        record
    }

    override suspend fun deleteScheduledCall(scheduleId: String): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val ref = firestore.collection(Constants.COLLECTION_SCHEDULED_CALLS).document(scheduleId)
        val snap = ref.get().await()
        @Suppress("UNCHECKED_CAST")
        val participants = (snap.get("participants") as? List<String>).orEmpty()
        require(!snap.exists() || snap.getString("creatorUid") == uid || participants.contains(uid)) {
            "لا تملك صلاحية حذف هذه المكالمة المجدولة"
        }
        ref.delete().await()
    }
}
