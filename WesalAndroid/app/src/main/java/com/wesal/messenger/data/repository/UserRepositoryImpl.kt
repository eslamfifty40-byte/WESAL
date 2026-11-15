package com.wesal.messenger.data.repository

import android.net.Uri
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.core.util.PhoneUtils
import com.wesal.messenger.core.util.WesalIdUtils
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.domain.model.DeviceSession
import com.wesal.messenger.domain.model.PrivacySettings
import com.wesal.messenger.domain.model.PrivacyVisibility
import com.wesal.messenger.domain.model.SavedWesalAccount
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.UserRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    val phone = getString("phoneNumber").orEmpty()
    val email = getString("email").orEmpty()
    // Backward-compatible verification inference for legacy Phone Auth accounts
    val isPhoneVerified = getBoolean("isPhoneVerified") ?: (phone.isNotBlank() && email.isBlank())

    return WesalUser(
        uid = getString("uid") ?: id,
        email = email,
        phoneNumber = phone,
        isPhoneVerified = isPhoneVerified,
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

    private fun observeAuthUid(): Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { fa -> trySend(fa.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeCurrentUser(): Flow<WesalUser?> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) flowOf(null) else observeUser(uid)
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

    private suspend fun getDocFresh(uid: String): DocumentSnapshot? {
        val ref = firestore.collection(Constants.COLLECTION_USERS).document(uid)
        return runCatching { ref.get(Source.SERVER).await() }.getOrNull()
            ?: runCatching { ref.get().await() }.getOrNull()
    }

    private suspend fun getQueryFresh(query: Query): QuerySnapshot? {
        return runCatching { query.get(Source.SERVER).await() }.getOrNull()
            ?: runCatching { query.get().await() }.getOrNull()
    }

    override suspend fun getUserOnce(uid: String): WesalUser? {
        val snap = getDocFresh(uid)
        return if (snap != null && snap.exists()) snap.toWesalUser() else null
    }

    override suspend fun searchUsers(query: String): List<WesalUser> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val currentUid = auth.currentUser?.uid
        val usersCollection = firestore.collection(Constants.COLLECTION_USERS)
        val results = linkedMapOf<String, WesalUser>()

        // 1. Search by Wesal ID (@wesal_id or wesal_id)
        val cleanHandle = WesalIdUtils.normalize(trimmed)
        if (cleanHandle.isNotBlank()) {
            val idSnap = getQueryFresh(
                usersCollection
                    .whereGreaterThanOrEqualTo("publicId", cleanHandle)
                    .whereLessThanOrEqualTo("publicId", "$cleanHandle\uf8ff")
                    .limit(20)
            )
            idSnap?.documents?.forEach { doc ->
                val user = doc.toWesalUser()
                if (user.uid != currentUid) results[user.uid] = user
            }
        }

        // 2. Search by Display Name prefix
        val nameSnap = getQueryFresh(
            usersCollection
                .whereGreaterThanOrEqualTo("displayName", trimmed)
                .whereLessThanOrEqualTo("displayName", "$trimmed\uf8ff")
                .limit(20)
        )
        nameSnap?.documents?.forEach { doc ->
            val user = doc.toWesalUser()
            if (user.uid != currentUid) results[user.uid] = user
        }

        // 3. Exact email match (for Gmail/email users)
        if (trimmed.contains("@") && !trimmed.startsWith("@")) {
            val emailSnap = getQueryFresh(usersCollection.whereEqualTo("email", trimmed))
                ?: getQueryFresh(usersCollection.whereEqualTo("email", trimmed.lowercase()))
            emailSnap?.documents?.forEach { doc ->
                val user = doc.toWesalUser()
                if (user.uid != currentUid) results[user.uid] = user
            }
        }

        // 4. Verified phone number search
        val looksLikePhone = trimmed.count { it.isDigit() } >= 9
        if (looksLikePhone) {
            val normalized = PhoneUtils.normalizeToE164(trimmed)
            if (normalized != null) {
                val hash = PhoneUtils.sha256Hash(normalized)
                val hashSnap = getQueryFresh(usersCollection.whereEqualTo("phoneHash", hash))
                hashSnap?.documents?.forEach { doc ->
                    val user = doc.toWesalUser()
                    if (user.uid != currentUid && user.isPhoneVerified) {
                        results[user.uid] = user
                    }
                }
            }
        }

        // 5. Case-insensitive fallback over users so mixed-case names or partial handles match cleanly
        if (results.size < 10) {
            val poolSnap = getQueryFresh(usersCollection.limit(60))
            poolSnap?.documents?.forEach { doc ->
                val user = doc.toWesalUser()
                if (user.uid != currentUid) {
                    val matchesName = user.displayName.contains(trimmed, ignoreCase = true)
                    val matchesId = cleanHandle.isNotBlank() && user.publicId.contains(cleanHandle, ignoreCase = true)
                    val matchesEmail = user.email.contains(trimmed, ignoreCase = true)
                    val matchesVerifiedPhone = user.isPhoneVerified && user.phoneNumber.contains(trimmed)
                    if (matchesName || matchesId || matchesEmail || matchesVerifiedPhone) {
                        results[user.uid] = user
                    }
                }
            }
        }

        return results.values.toList()
    }

    override suspend fun listDiscoverableUsers(limit: Int): List<WesalUser> {
        val currentUid = auth.currentUser?.uid
        val snap = getQueryFresh(
            firestore.collection(Constants.COLLECTION_USERS)
                .limit(limit.toLong().coerceIn(10L, 60L))
        )
        return snap?.documents?.map { it.toWesalUser() }
            ?.filter { it.uid != currentUid && (it.displayName.isNotBlank() || it.publicId.isNotBlank()) }
            ?.sortedWith(compareByDescending<WesalUser> { it.isOnline }.thenByDescending { it.lastSeen })
            .orEmpty()
    }

    override suspend fun findUsersByPhoneHashes(phoneHashes: List<String>): Map<String, WesalUser> {
        val distinct = phoneHashes.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return emptyMap()
        val currentUid = auth.currentUser?.uid
        val matched = mutableMapOf<String, WesalUser>()

        distinct.chunked(30).forEach { chunk ->
            val snap = getQueryFresh(
                firestore.collection(Constants.COLLECTION_USERS)
                    .whereIn("phoneHash", chunk)
            )
            snap?.documents?.forEach { doc ->
                val hash = doc.getString("phoneHash").orEmpty()
                val user = doc.toWesalUser()
                // Strict guard: only verified phone numbers get the "يستخدم وصال" match
                if (hash.isNotBlank() && user.uid != currentUid && user.isPhoneVerified) {
                    matched[hash] = user
                }
            }
        }
        return matched
    }

    override suspend fun updateProfile(
        displayName: String,
        bio: String,
        publicId: String,
        photoUri: Uri?
    ): Result<Unit> {
        return try {
            val uid = auth.currentUser?.uid ?: error("Not authenticated")
            val sanitizedHandle = WesalIdUtils.normalize(publicId)
            require(WesalIdUtils.isValid(sanitizedHandle)) {
                "معرف وصال (Wesal ID) يجب أن يتكون من 4 إلى 30 حرفاً بالإنجليزية أو أرقام أو _"
            }

            val dupSnap = firestore.collection(Constants.COLLECTION_USERS)
                .whereEqualTo("publicId", sanitizedHandle)
                .get()
                .await()
            if (dupSnap.documents.any { it.id != uid }) {
                return Result.failure(IllegalArgumentException("معرف وصال @$sanitizedHandle مستخدم بالفعل، اختر معرفاً آخر"))
            }

            val updates = hashMapOf<String, Any>(
                "displayName" to displayName.trim(),
                "bio" to bio.trim(),
                "publicId" to sanitizedHandle
            )

            if (photoUri != null) {
                val uploadedUrl = runCatching {
                    val ref = storage.reference.child("${Constants.STORAGE_AVATARS}/$uid/${UUID.randomUUID()}.jpg")
                    val metadata = StorageMetadata.Builder().setContentType("image/jpeg").build()
                    ref.putFile(photoUri, metadata).await()
                    ref.downloadUrl.await().toString()
                }.getOrElse {
                    uriToBase64Jpeg(photoUri, maxSide = 512, quality = 78)
                }
                if (uploadedUrl.isNotBlank()) {
                    updates["photoUrl"] = uploadedUrl
                }
            }

            firestore.collection(Constants.COLLECTION_USERS)
                .document(uid)
                .set(updates, SetOptions.merge())
                .await()

            val updatedSnap = getDocFresh(uid)
            updatedSnap?.takeIf { it.exists() }?.toWesalUser()?.let { updatedUser ->
                prefs.upsertSavedAccount(
                    SavedWesalAccount(
                        uid = updatedUser.uid,
                        displayName = updatedUser.displayName,
                        email = updatedUser.email,
                        phoneNumber = updatedUser.phoneNumber,
                        publicId = updatedUser.publicId,
                        photoUrl = updatedUser.photoUrl,
                        lastUsedAt = System.currentTimeMillis()
                    )
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateOptionalPhone(rawPhone: String): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val trimmed = rawPhone.trim()
        if (trimmed.isEmpty()) {
            removeOptionalPhone().getOrThrow()
            return@runCatching
        }

        val normalized = PhoneUtils.normalizeToE164(trimmed)
            ?: throw IllegalArgumentException("صيغة رقم الهاتف غير صحيحة (مثال: +201012345678 أو 01012345678)")

        val docRef = firestore.collection(Constants.COLLECTION_USERS).document(uid)
        val snap = docRef.get().await()
        val existingPhone = snap.getString("phoneNumber").orEmpty()
        val existingVerified = snap.getBoolean("isPhoneVerified") ?: false

        // Only keep verified status & searchable hash if unchanged and already verified
        val keepVerified = existingVerified && existingPhone == normalized
        val updates = hashMapOf<String, Any>(
            "phoneNumber" to normalized,
            "isPhoneVerified" to keepVerified,
            "phoneHash" to if (keepVerified) PhoneUtils.sha256Hash(normalized) else ""
        )
        docRef.set(updates, SetOptions.merge()).await()
    }

    override suspend fun removeOptionalPhone(): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        firestore.collection(Constants.COLLECTION_USERS)
            .document(uid)
            .set(
                mapOf(
                    "phoneNumber" to "",
                    "phoneHash" to "",
                    "isPhoneVerified" to false
                ),
                SetOptions.merge()
            )
            .await()
    }

    override suspend fun removeProfilePhoto(): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val docRef = firestore.collection(Constants.COLLECTION_USERS).document(uid)
        val currentUrl = docRef.get().await().getString("photoUrl").orEmpty()
        docRef.update("photoUrl", "").await()
        if (currentUrl.startsWith("http")) {
            runCatching { storage.getReferenceFromUrl(currentUrl).delete().await() }
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

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeSessions(): Flow<List<DeviceSession>> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(emptyList())
        } else {
            callbackFlow {
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
        }
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
