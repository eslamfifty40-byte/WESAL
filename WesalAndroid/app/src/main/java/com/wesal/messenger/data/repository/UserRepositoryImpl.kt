package com.wesal.messenger.data.repository

import android.net.Uri
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.domain.model.DeviceSession
import com.wesal.messenger.domain.model.PrivacySettings
import com.wesal.messenger.domain.model.PrivacyVisibility
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.UserRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("UNCHECKED_CAST")
internal fun DocumentSnapshot.toWesalUser(): WesalUser {
    val privacyMap = get("privacySettings") as? Map<String, Any?> ?: emptyMap()
    val lastSeenVal = when (val raw = get("lastSeen")) {
        is Timestamp -> raw.toDate().time
        is Number -> raw.toLong()
        else -> 0L
    }
    return WesalUser(
        uid = getString("uid") ?: id,
        phoneNumber = getString("phoneNumber").orEmpty(),
        displayName = getString("displayName").orEmpty(),
        publicId = getString("publicId").orEmpty(),
        bio = getString("bio") ?: "أستخدم تطبيق وصال",
        photoUrl = getString("photoUrl").orEmpty(),
        createdAt = getLong("createdAt") ?: 0L,
        lastSeen = lastSeenVal,
        isOnline = getBoolean("isOnline") ?: false,
        fcmToken = getString("fcmToken").orEmpty(),
        publicKey = getString("publicKey").orEmpty(),
        blockedUsers = (get("blockedUsers") as? List<String>) ?: emptyList(),
        privacySettings = PrivacySettings(
            lastSeenVisibility = PrivacyVisibility.fromKey(privacyMap["lastSeenVisibility"] as? String),
            onlineVisibility = PrivacyVisibility.fromKey(privacyMap["onlineVisibility"] as? String),
            profilePhotoVisibility = PrivacyVisibility.fromKey(privacyMap["profilePhotoVisibility"] as? String),
            storiesVisibility = PrivacyVisibility.fromKey(privacyMap["storiesVisibility"] as? String),
            readReceiptsEnabled = (privacyMap["readReceiptsEnabled"] as? Boolean) ?: true
        )
    )
}

@Singleton
class UserRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage,
    private val prefs: UserPreferencesDataStore
) : UserRepository {

    override fun observeCurrentUser(): Flow<WesalUser?> {
        val uid = auth.currentUser?.uid ?: return kotlinx.coroutines.flow.flowOf(null)
        return observeUser(uid)
    }

    override fun observeUser(uid: String): Flow<WesalUser?> = callbackFlow {
        val registration = firestore.collection(Constants.COLLECTION_USERS)
            .document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(null)
                    return@addSnapshotListener
                }
                trySend(snapshot?.takeIf { it.exists() }?.toWesalUser())
            }
        awaitClose { registration.remove() }
    }

    override suspend fun getUserOnce(uid: String): WesalUser? {
        val snap = firestore.collection(Constants.COLLECTION_USERS).document(uid).get().await()
        return if (snap.exists()) snap.toWesalUser() else null
    }

    override suspend fun searchUsers(query: String): List<WesalUser> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val currentUid = auth.currentUser?.uid
        val usersCollection = firestore.collection(Constants.COLLECTION_USERS)
        val results = linkedMapOf<String, WesalUser>()

        // Search by exact phone number
        if (trimmed.startsWith("+") || trimmed.all { it.isDigit() }) {
            val phoneQuery = usersCollection.whereEqualTo("phoneNumber", trimmed).get().await()
            phoneQuery.documents.forEach { doc ->
                val user = doc.toWesalUser()
                if (user.uid != currentUid) results[user.uid] = user
            }
        }

        // Search by public ID prefix
        val cleanHandle = trimmed.removePrefix("@").lowercase()
        val idSnap = usersCollection
            .whereGreaterThanOrEqualTo("publicId", cleanHandle)
            .whereLessThanOrEqualTo("publicId", "$cleanHandle\uf8ff")
            .limit(20)
            .get()
            .await()
        idSnap.documents.forEach { doc ->
            val user = doc.toWesalUser()
            if (user.uid != currentUid) results[user.uid] = user
        }

        // Search by displayName prefix
        val nameSnap = usersCollection
            .whereGreaterThanOrEqualTo("displayName", trimmed)
            .whereLessThanOrEqualTo("displayName", "$trimmed\uf8ff")
            .limit(20)
            .get()
            .await()
        nameSnap.documents.forEach { doc ->
            val user = doc.toWesalUser()
            if (user.uid != currentUid) results[user.uid] = user
        }

        return results.values.toList()
    }

    override suspend fun updateProfile(
        displayName: String,
        bio: String,
        publicId: String,
        photoUri: Uri?
    ): Result<Unit> {
        return try {
            val uid = auth.currentUser?.uid ?: error("Not authenticated")
            val sanitizedHandle = publicId.trim().removePrefix("@").lowercase()
            require(sanitizedHandle.length >= 4) { "المعرف العام يجب أن لا يقل عن 4 أحرف" }

            // Check publicId uniqueness
            val dupSnap = firestore.collection(Constants.COLLECTION_USERS)
                .whereEqualTo("publicId", sanitizedHandle)
                .get()
                .await()
            if (dupSnap.documents.any { it.id != uid }) {
                return Result.failure(IllegalArgumentException("هذا المعرف مستخدم بالفعل، اختر معرفاً آخر"))
            }

            val updates = hashMapOf<String, Any>(
                "displayName" to displayName.trim(),
                "bio" to bio.trim(),
                "publicId" to sanitizedHandle
            )

            if (photoUri != null) {
                val ref = storage.reference.child("${Constants.STORAGE_AVATARS}/$uid/${UUID.randomUUID()}.jpg")
                ref.putFile(photoUri).await()
                updates["photoUrl"] = ref.downloadUrl.await().toString()
            }

            firestore.collection(Constants.COLLECTION_USERS)
                .document(uid)
                .set(updates, SetOptions.merge())
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updatePrivacySettings(settings: PrivacySettings): Result<Unit> {
        return try {
            val uid = auth.currentUser?.uid ?: error("Not authenticated")
            val map = mapOf(
                "privacySettings" to mapOf(
                    "lastSeenVisibility" to settings.lastSeenVisibility.key,
                    "onlineVisibility" to settings.onlineVisibility.key,
                    "profilePhotoVisibility" to settings.profilePhotoVisibility.key,
                    "storiesVisibility" to settings.storiesVisibility.key,
                    "readReceiptsEnabled" to settings.readReceiptsEnabled
                )
            )
            firestore.collection(Constants.COLLECTION_USERS)
                .document(uid)
                .set(map, SetOptions.merge())
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun blockUser(targetUid: String): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        firestore.collection(Constants.COLLECTION_USERS)
            .document(uid)
            .update("blockedUsers", FieldValue.arrayUnion(targetUid))
            .await()
    }

    override suspend fun unblockUser(targetUid: String): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        firestore.collection(Constants.COLLECTION_USERS)
            .document(uid)
            .update("blockedUsers", FieldValue.arrayRemove(targetUid))
            .await()
    }

    override suspend fun reportUser(targetUid: String, reason: String): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val report = mapOf(
            "reporterUid" to uid,
            "reportedUid" to targetUid,
            "reason" to reason.trim(),
            "createdAt" to System.currentTimeMillis()
        )
        firestore.collection(Constants.COLLECTION_REPORTS).add(report).await()
    }

    override fun observeSessions(): Flow<List<DeviceSession>> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        var currentSessionId = ""
        launch { currentSessionId = prefs.getOrCreateDeviceSessionId() }

        val reg = firestore.collection(Constants.COLLECTION_USERS)
            .document(uid)
            .collection(Constants.COLLECTION_SESSIONS)
            .addSnapshotListener { snap, _ ->
                val list = snap?.documents?.map { doc ->
                    val sid = doc.getString("sessionId") ?: doc.id
                    DeviceSession(
                        sessionId = sid,
                        deviceName = doc.getString("deviceName").orEmpty(),
                        osVersion = doc.getString("osVersion").orEmpty(),
                        appVersion = doc.getString("appVersion").orEmpty(),
                        lastActiveAt = doc.getLong("lastActiveAt") ?: 0L,
                        isCurrent = sid == currentSessionId
                    )
                }.orEmpty().sortedByDescending { it.lastActiveAt }
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    override suspend fun revokeSession(sessionId: String): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        firestore.collection(Constants.COLLECTION_USERS)
            .document(uid)
            .collection(Constants.COLLECTION_SESSIONS)
            .document(sessionId)
            .delete()
            .await()
    }
}
