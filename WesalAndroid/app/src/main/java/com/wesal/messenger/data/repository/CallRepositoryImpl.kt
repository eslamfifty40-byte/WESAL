package com.wesal.messenger.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.domain.model.CallRecord
import com.wesal.messenger.domain.model.CallStatus
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.CallRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) : CallRepository {

    override fun observeCallHistory(): Flow<List<CallRecord>> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val reg = firestore.collection(Constants.COLLECTION_CALLS)
            .whereArrayContains("participants", uid)
            .addSnapshotListener { snap, _ ->
                val list = snap?.documents?.map { doc ->
                    CallRecord(
                        callId = doc.id,
                        callerId = doc.getString("callerId").orEmpty(),
                        callerName = doc.getString("callerName").orEmpty(),
                        callerAvatarUrl = doc.getString("callerAvatarUrl").orEmpty(),
                        calleeId = doc.getString("calleeId").orEmpty(),
                        calleeName = doc.getString("calleeName").orEmpty(),
                        calleeAvatarUrl = doc.getString("calleeAvatarUrl").orEmpty(),
                        isVideo = doc.getBoolean("isVideo") ?: false,
                        status = CallStatus.fromRaw(doc.getString("status")),
                        startedAt = doc.getLong("startedAt") ?: 0L,
                        endedAt = doc.getLong("endedAt") ?: 0L,
                        durationSeconds = doc.getLong("durationSeconds") ?: 0L
                    )
                }.orEmpty().sortedByDescending { it.startedAt }
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    override fun observeIncomingCalls(): Flow<CallRecord?> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            trySend(null)
            close()
            return@callbackFlow
        }
        val reg = firestore.collection(Constants.COLLECTION_CALLS)
            .whereEqualTo("calleeId", uid)
            .whereEqualTo("status", CallStatus.RINGING.raw)
            .addSnapshotListener { snap, _ ->
                val activeRing = snap?.documents?.firstOrNull()?.let { doc ->
                    CallRecord(
                        callId = doc.id,
                        callerId = doc.getString("callerId").orEmpty(),
                        callerName = doc.getString("callerName").orEmpty(),
                        callerAvatarUrl = doc.getString("callerAvatarUrl").orEmpty(),
                        calleeId = doc.getString("calleeId").orEmpty(),
                        calleeName = doc.getString("calleeName").orEmpty(),
                        calleeAvatarUrl = doc.getString("calleeAvatarUrl").orEmpty(),
                        isVideo = doc.getBoolean("isVideo") ?: false,
                        status = CallStatus.RINGING,
                        offerSdp = doc.getString("offerSdp").orEmpty(),
                        startedAt = doc.getLong("startedAt") ?: 0L
                    )
                }
                trySend(activeRing)
            }
        awaitClose { reg.remove() }
    }

    override fun observeCall(callId: String): Flow<CallRecord?> = callbackFlow {
        val reg = firestore.collection(Constants.COLLECTION_CALLS)
            .document(callId)
            .addSnapshotListener { doc, _ ->
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
                        calleeId = doc.getString("calleeId").orEmpty(),
                        calleeName = doc.getString("calleeName").orEmpty(),
                        calleeAvatarUrl = doc.getString("calleeAvatarUrl").orEmpty(),
                        isVideo = doc.getBoolean("isVideo") ?: false,
                        status = CallStatus.fromRaw(doc.getString("status")),
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
        val callId = UUID.randomUUID().toString()
        val data = mapOf(
            "callId" to callId,
            "participants" to listOf(callerUid, callee.uid),
            "callerId" to callerUid,
            "callerName" to (callerSnap.getString("displayName")?.ifBlank { callerSnap.getString("phoneNumber") } ?: "مستخدم وصال"),
            "callerAvatarUrl" to (callerSnap.getString("photoUrl") ?: ""),
            "calleeId" to callee.uid,
            "calleeName" to callee.displayName.ifBlank { callee.phoneNumber },
            "calleeAvatarUrl" to callee.photoUrl,
            "isVideo" to isVideo,
            "status" to CallStatus.RINGING.raw,
            "offerSdp" to offerSdp,
            "answerSdp" to "",
            "startedAt" to System.currentTimeMillis(),
            "endedAt" to 0L,
            "durationSeconds" to 0L
        )
        firestore.collection(Constants.COLLECTION_CALLS).document(callId).set(data).await()
        callId
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
            val updates = hashMapOf<String, Any>(
                "status" to status.raw,
                "durationSeconds" to durationSeconds
            )
            if (status == CallStatus.ENDED || status == CallStatus.REJECTED || status == CallStatus.MISSED) {
                updates["endedAt"] = System.currentTimeMillis()
            }
            firestore.collection(Constants.COLLECTION_CALLS).document(callId).update(updates).await()
        }
    }
}
