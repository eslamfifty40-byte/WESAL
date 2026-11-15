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
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.core.util.PhoneUtils
import com.wesal.messenger.core.util.WesalIdUtils
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.domain.model.ContactRequest
import com.wesal.messenger.domain.model.ContactRequestStatus
import com.wesal.messenger.domain.model.DeviceSession
import com.wesal.messenger.domain.model.PrivacySettings
import com.wesal.messenger.domain.model.PrivacyVisibility
import com.wesal.messenger.domain.model.SavedWesalAccount
import com.wesal.messenger.domain.model.VerificationAuditLog
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.UserRepository
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
    val hidePhone = (privacyMap["hidePhoneNumber"] as? Boolean) ?: false
    val phone = if (hidePhone) "" else getString("phoneNumber").orEmpty()
    val email = getString("email").orEmpty()
    val isPhoneVerified = getBoolean("isPhoneVerified") ?: (phone.isNotBlank() && email.isBlank())

    return WesalUser(
        uid = getString("uid") ?: id,
        email = email,
        phoneNumber = phone,
        isPhoneVerified = isPhoneVerified,
        isVerified = getBoolean("isVerified") ?: false,
        verifiedByUid = getString("verifiedByUid").orEmpty(),
        verifiedAt = getLong("verifiedAt") ?: 0L,
        verificationNote = getString("verificationNote").orEmpty(),
        displayName = getString("displayName").orEmpty(),
        publicId = getString("publicId").orEmpty(),
        bio = getString("bio") ?: "أستخدم تطبيق WESAL",
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
            readReceiptsEnabled = (privacyMap["readReceiptsEnabled"] as? Boolean) ?: true,
            hidePhoneNumber = hidePhone
        )
    )
}

@Singleton
class UserRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val prefs: UserPreferencesDataStore
) : UserRepository {

    private fun observeAuthUid(): Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { fa -> trySend(fa.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeCurrentUser(): Flow<WesalUser?> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) flowOf(null) else observeOwnUserWithPrivatePhone(uid)
    }

    private fun observeOwnUserWithPrivatePhone(uid: String): Flow<WesalUser?> = callbackFlow {
        var latestPublic: WesalUser? = null
        var privatePhone = ""
        var privateVerified = false

        fun emitMerged() {
            val pub = latestPublic ?: return
            val mergedPhone = pub.phoneNumber.ifBlank { privatePhone }
            val mergedVerified = pub.isPhoneVerified || privateVerified
            trySend(pub.copy(phoneNumber = mergedPhone, isPhoneVerified = mergedVerified))
        }

        val pubReg = firestore.collection(Constants.COLLECTION_USERS)
            .document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(null)
                    return@addSnapshotListener
                }
                latestPublic = snapshot?.takeIf { it.exists() }?.toWesalUser()
                if (latestPublic == null) trySend(null) else emitMerged()
            }

        val privReg = firestore.collection(Constants.COLLECTION_USERS)
            .document(uid)
            .collection(Constants.SUBCOLLECTION_PRIVATE)
            .document("info")
            .addSnapshotListener { snap, _ ->
                if (snap != null && snap.exists()) {
                    privatePhone = snap.getString("phoneNumber").orEmpty()
                    privateVerified = snap.getBoolean("isPhoneVerified") ?: false
                    emitMerged()
                }
            }

        awaitClose {
            pubReg.remove()
            privReg.remove()
        }
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

    override suspend fun findUserByPublicIdOrUid(idOrUid: String): WesalUser? {
        val raw = idOrUid.trim()
        if (raw.isBlank()) return null
        val cleanHandle = WesalIdUtils.normalize(raw)
        if (cleanHandle.isNotBlank()) {
            val byHandle = getQueryFresh(
                firestore.collection(Constants.COLLECTION_USERS)
                    .whereEqualTo("publicId", cleanHandle)
                    .limit(1)
            )
            val matchedDoc = byHandle?.documents?.firstOrNull()
            if (matchedDoc != null) return matchedDoc.toWesalUser()
        }
        return getUserOnce(raw)
    }

    /**
     * Searches strictly by WESAL ID (@publicId) and Display Name.
     * Deliberately excludes phone number & email matching to prevent phone leakage.
     */
    override suspend fun searchUsers(query: String): List<WesalUser> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val currentUid = auth.currentUser?.uid
        val usersCollection = firestore.collection(Constants.COLLECTION_USERS)
        val results = linkedMapOf<String, WesalUser>()

        val cleanHandle = WesalIdUtils.normalize(trimmed)
        if (cleanHandle.isNotBlank()) {
            val idSnap = getQueryFresh(
                usersCollection
                    .whereGreaterThanOrEqualTo("publicId", cleanHandle)
                    .whereLessThanOrEqualTo("publicId", "$cleanHandle\uf8ff")
                    .limit(25)
            )
            idSnap?.documents?.forEach { doc ->
                val user = doc.toWesalUser()
                if (user.uid != currentUid) results[user.uid] = user
            }
        }

        val nameSnap = getQueryFresh(
            usersCollection
                .whereGreaterThanOrEqualTo("displayName", trimmed)
                .whereLessThanOrEqualTo("displayName", "$trimmed\uf8ff")
                .limit(25)
        )
        nameSnap?.documents?.forEach { doc ->
            val user = doc.toWesalUser()
            if (user.uid != currentUid) results[user.uid] = user
        }

        if (results.size < 12) {
            val poolSnap = getQueryFresh(usersCollection.limit(80))
            poolSnap?.documents?.forEach { doc ->
                val user = doc.toWesalUser()
                if (user.uid != currentUid) {
                    val matchesName = user.displayName.contains(trimmed, ignoreCase = true)
                    val matchesId = cleanHandle.isNotBlank() && user.publicId.contains(cleanHandle, ignoreCase = true)
                    if (matchesName || matchesId) {
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
            ?.sortedWith(
                compareByDescending<WesalUser> { it.isVerified }
                    .thenByDescending { it.isOnline }
                    .thenByDescending { it.lastSeen }
            )
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
                // Only verified & non-hidden phone accounts can be discovered by phone hash
                if (hash.isNotBlank() && user.uid != currentUid && user.isPhoneVerified && !user.privacySettings.hidePhoneNumber) {
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
                val encodedAvatar = withContext(Dispatchers.IO) {
                    uriToBase64Jpeg(photoUri, maxSide = 512, quality = 78)
                }
                if (encodedAvatar.isNotBlank()) {
                    updates["photoUrl"] = encodedAvatar
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
        @Suppress("UNCHECKED_CAST")
        val privacyMap = snap.get("privacySettings") as? Map<String, Any?> ?: emptyMap()
        val hidePhone = (privacyMap["hidePhoneNumber"] as? Boolean) ?: false

        val privSnap = runCatching {
            docRef.collection(Constants.SUBCOLLECTION_PRIVATE).document("info").get().await()
        }.getOrNull()
        val existingPhone = (privSnap?.getString("phoneNumber").orEmpty()).ifBlank { snap.getString("phoneNumber").orEmpty() }
        val existingVerified = (privSnap?.getBoolean("isPhoneVerified") ?: snap.getBoolean("isPhoneVerified")) ?: false

        val keepVerified = existingVerified && existingPhone == normalized
        val hash = if (keepVerified && !hidePhone) PhoneUtils.sha256Hash(normalized) else ""

        docRef.collection(Constants.SUBCOLLECTION_PRIVATE).document("info")
            .set(
                mapOf(
                    "phoneNumber" to normalized,
                    "isPhoneVerified" to keepVerified
                ),
                SetOptions.merge()
            ).await()

        val updates = hashMapOf<String, Any>(
            "phoneNumber" to if (hidePhone) "" else normalized,
            "isPhoneVerified" to keepVerified,
            "phoneHash" to hash
        )
        docRef.set(updates, SetOptions.merge()).await()
    }

    override suspend fun removeOptionalPhone(): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val docRef = firestore.collection(Constants.COLLECTION_USERS).document(uid)
        runCatching {
            docRef.collection(Constants.SUBCOLLECTION_PRIVATE).document("info")
                .set(mapOf("phoneNumber" to "", "isPhoneVerified" to false), SetOptions.merge())
                .await()
        }
        docRef.set(
            mapOf(
                "phoneNumber" to "",
                "phoneHash" to "",
                "isPhoneVerified" to false
            ),
            SetOptions.merge()
        ).await()
    }

    override suspend fun removeProfilePhoto(): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        firestore.collection(Constants.COLLECTION_USERS)
            .document(uid)
            .update("photoUrl", "")
            .await()
    }

    override suspend fun updatePrivacySettings(settings: PrivacySettings): Result<Unit> {
        return try {
            val uid = auth.currentUser?.uid ?: error("Not authenticated")
            val docRef = firestore.collection(Constants.COLLECTION_USERS).document(uid)
            val snap = docRef.get().await()
            val privRef = docRef.collection(Constants.SUBCOLLECTION_PRIVATE).document("info")
            val privSnap = runCatching { privRef.get().await() }.getOrNull()

            val currentPhone = privSnap?.getString("phoneNumber").orEmpty().ifBlank { snap.getString("phoneNumber").orEmpty() }
            val currentVerified = (privSnap?.getBoolean("isPhoneVerified") ?: snap.getBoolean("isPhoneVerified")) ?: false

            val map = hashMapOf<String, Any>(
                "privacySettings" to mapOf(
                    "lastSeenVisibility" to settings.lastSeenVisibility.key,
                    "onlineVisibility" to settings.onlineVisibility.key,
                    "profilePhotoVisibility" to settings.profilePhotoVisibility.key,
                    "storiesVisibility" to settings.storiesVisibility.key,
                    "readReceiptsEnabled" to settings.readReceiptsEnabled,
                    "hidePhoneNumber" to settings.hidePhoneNumber
                )
            )

            if (settings.hidePhoneNumber) {
                if (currentPhone.isNotBlank()) {
                    privRef.set(
                        mapOf("phoneNumber" to currentPhone, "isPhoneVerified" to currentVerified),
                        SetOptions.merge()
                    ).await()
                }
                map["phoneNumber"] = ""
                map["phoneHash"] = ""
            } else if (currentPhone.isNotBlank()) {
                val norm = PhoneUtils.normalizeToE164(currentPhone) ?: currentPhone
                map["phoneNumber"] = norm
                map["phoneHash"] = if (currentVerified) PhoneUtils.sha256Hash(norm) else ""
            }

            docRef.set(map, SetOptions.merge()).await()
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

    // -----------------------------------------------------------------------
    // Super Admin Lock & Verification Management
    // -----------------------------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeIsSuperAdmin(): Flow<Boolean> = observeAuthUid().flatMapLatest { uid ->
        val email = auth.currentUser?.email.orEmpty()
        if (uid == null || !email.equals(Constants.SUPER_ADMIN_EMAIL, ignoreCase = true)) {
            flowOf(false)
        } else if (Constants.CONFIRMED_SUPER_ADMIN_UID.isNotBlank() && uid != Constants.CONFIRMED_SUPER_ADMIN_UID) {
            flowOf(false)
        } else {
            callbackFlow {
                val reg = firestore.collection(Constants.COLLECTION_SUPER_ADMINS)
                    .document(uid)
                    .addSnapshotListener { snap, error ->
                        if (error != null) {
                            trySend(false)
                            return@addSnapshotListener
                        }
                        val validDoc = snap != null && snap.exists() &&
                            snap.getBoolean("active") != false
                        trySend(validDoc)
                    }
                awaitClose { reg.remove() }
            }
        }
    }

    override suspend fun isSuperAdmin(): Boolean {
        val user = auth.currentUser ?: return false
        val email = user.email.orEmpty()
        if (!email.equals(Constants.SUPER_ADMIN_EMAIL, ignoreCase = true)) return false
        if (Constants.CONFIRMED_SUPER_ADMIN_UID.isNotBlank() && user.uid != Constants.CONFIRMED_SUPER_ADMIN_UID) {
            return false
        }
        val doc = runCatching {
            firestore.collection(Constants.COLLECTION_SUPER_ADMINS).document(user.uid).get(Source.SERVER).await()
        }.getOrNull() ?: return false
        return doc.exists() && doc.getBoolean("active") != false
    }

    override suspend fun setAccountVerification(
        targetUid: String,
        verified: Boolean,
        note: String
    ): Result<Unit> = runCatching {
        val admin = auth.currentUser ?: error("Not authenticated")
        require(isSuperAdmin()) { "صلاحية المشرف الأعلى غير مفعلة لهذا الحساب" }

        val now = System.currentTimeMillis()
        val targetRef = firestore.collection(Constants.COLLECTION_USERS).document(targetUid)
        val targetSnap = targetRef.get().await()
        require(targetSnap.exists()) { "الحساب غير موجود" }

        val updates = hashMapOf<String, Any>(
            "isVerified" to verified,
            "verifiedByUid" to admin.uid,
            "verifiedAt" to now,
            "verificationNote" to note.trim()
        )
        targetRef.set(updates, SetOptions.merge()).await()

        val logId = UUID.randomUUID().toString()
        val logData = mapOf(
            "logId" to logId,
            "targetUid" to targetUid,
            "targetPublicId" to targetSnap.getString("publicId").orEmpty(),
            "targetName" to targetSnap.getString("displayName").orEmpty(),
            "action" to if (verified) "GRANTED" else "REVOKED",
            "adminUid" to admin.uid,
            "adminEmail" to (admin.email ?: Constants.SUPER_ADMIN_EMAIL),
            "note" to note.trim(),
            "timestamp" to now
        )
        firestore.collection(Constants.COLLECTION_VERIFICATION_LOGS)
            .document(logId)
            .set(logData)
            .await()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeVerificationAuditLogs(): Flow<List<VerificationAuditLog>> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(emptyList())
        } else {
            callbackFlow {
                val reg = firestore.collection(Constants.COLLECTION_VERIFICATION_LOGS)
                    .orderBy("timestamp", Query.Direction.DESCENDING)
                    .limit(50)
                    .addSnapshotListener { snap, error ->
                        if (error != null) {
                            trySend(emptyList())
                            return@addSnapshotListener
                        }
                        val logs = snap?.documents?.map { doc ->
                            VerificationAuditLog(
                                logId = doc.id,
                                targetUid = doc.getString("targetUid").orEmpty(),
                                targetPublicId = doc.getString("targetPublicId").orEmpty(),
                                targetName = doc.getString("targetName").orEmpty(),
                                action = doc.getString("action") ?: "GRANTED",
                                adminUid = doc.getString("adminUid").orEmpty(),
                                adminEmail = doc.getString("adminEmail").orEmpty(),
                                note = doc.getString("note").orEmpty(),
                                timestamp = doc.getLong("timestamp") ?: 0L
                            )
                        }.orEmpty()
                        trySend(logs)
                    }
                awaitClose { reg.remove() }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Contact Requests & Friendship Gating
    // -----------------------------------------------------------------------

    private fun deterministicRequestId(uidA: String, uidB: String): String {
        val sorted = listOf(uidA, uidB).sorted()
        return "${sorted[0]}_${sorted[1]}"
    }

    private fun DocumentSnapshot.toContactRequest(): ContactRequest = ContactRequest(
        requestId = getString("requestId") ?: id,
        senderUid = getString("senderUid").orEmpty(),
        senderName = getString("senderName").orEmpty(),
        senderPublicId = getString("senderPublicId").orEmpty(),
        senderAvatarUrl = getString("senderAvatarUrl").orEmpty(),
        senderVerified = getBoolean("senderVerified") ?: false,
        receiverUid = getString("receiverUid").orEmpty(),
        receiverName = getString("receiverName").orEmpty(),
        receiverPublicId = getString("receiverPublicId").orEmpty(),
        receiverAvatarUrl = getString("receiverAvatarUrl").orEmpty(),
        receiverVerified = getBoolean("receiverVerified") ?: false,
        status = ContactRequestStatus.fromRaw(getString("status")),
        createdAt = getLong("createdAt") ?: 0L,
        updatedAt = getLong("updatedAt") ?: 0L
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeContactRequests(): Flow<List<ContactRequest>> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(emptyList())
        } else {
            callbackFlow {
                val reg = firestore.collection(Constants.COLLECTION_CONTACT_REQUESTS)
                    .whereArrayContains("participants", uid)
                    .addSnapshotListener { snap, error ->
                        if (error != null) {
                            trySend(emptyList())
                            return@addSnapshotListener
                        }
                        val list = snap?.documents?.map { it.toContactRequest() }
                            .orEmpty()
                            .sortedByDescending { it.updatedAt }
                        trySend(list)
                    }
                awaitClose { reg.remove() }
            }
        }
    }

    override suspend fun sendContactRequest(targetUid: String): Result<ContactRequest> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        require(targetUid.isNotBlank() && targetUid != myUid) { "حساب غير صالح" }

        val reqId = deterministicRequestId(myUid, targetUid)
        val reqRef = firestore.collection(Constants.COLLECTION_CONTACT_REQUESTS).document(reqId)
        val existing = runCatching { reqRef.get().await() }.getOrNull()
        if (existing != null && existing.exists()) {
            val current = existing.toContactRequest()
            if (current.status == ContactRequestStatus.ACCEPTED ||
                (current.status == ContactRequestStatus.PENDING && current.senderUid == myUid)
            ) {
                return@runCatching current
            }
            if (current.status == ContactRequestStatus.PENDING && current.receiverUid == myUid) {
                // Both sides expressed interest -> auto-accept cleanly
                val now = System.currentTimeMillis()
                reqRef.update(
                    mapOf("status" to ContactRequestStatus.ACCEPTED.raw, "updatedAt" to now)
                ).await()
                return@runCatching current.copy(status = ContactRequestStatus.ACCEPTED, updatedAt = now)
            }
        }

        val me = getUserOnce(myUid) ?: WesalUser(uid = myUid)
        val target = getUserOnce(targetUid) ?: error("لم يتم العثور على المستخدم")
        val now = System.currentTimeMillis()

        val request = ContactRequest(
            requestId = reqId,
            senderUid = myUid,
            senderName = me.displayName.ifBlank { "@${me.publicId}" },
            senderPublicId = me.publicId,
            senderAvatarUrl = me.photoUrl,
            senderVerified = me.isVerified,
            receiverUid = targetUid,
            receiverName = target.displayName.ifBlank { "@${target.publicId}" },
            receiverPublicId = target.publicId,
            receiverAvatarUrl = target.photoUrl,
            receiverVerified = target.isVerified,
            status = ContactRequestStatus.PENDING,
            createdAt = now,
            updatedAt = now
        )
        val map = mapOf(
            "requestId" to reqId,
            "participants" to listOf(myUid, targetUid).sorted(),
            "senderUid" to myUid,
            "senderName" to request.senderName,
            "senderPublicId" to request.senderPublicId,
            "senderAvatarUrl" to request.senderAvatarUrl,
            "senderVerified" to request.senderVerified,
            "receiverUid" to targetUid,
            "receiverName" to request.receiverName,
            "receiverPublicId" to request.receiverPublicId,
            "receiverAvatarUrl" to request.receiverAvatarUrl,
            "receiverVerified" to request.receiverVerified,
            "status" to ContactRequestStatus.PENDING.raw,
            "createdAt" to now,
            "updatedAt" to now
        )
        reqRef.set(map, SetOptions.merge()).await()
        request
    }

    override suspend fun acceptContactRequest(requestId: String): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val ref = firestore.collection(Constants.COLLECTION_CONTACT_REQUESTS).document(requestId)
        val snap = ref.get().await()
        require(snap.exists() && snap.getString("receiverUid") == myUid) {
            "لا تملك صلاحية قبول هذا الطلب"
        }
        ref.update(
            mapOf(
                "status" to ContactRequestStatus.ACCEPTED.raw,
                "updatedAt" to System.currentTimeMillis()
            )
        ).await()
    }

    override suspend fun rejectContactRequest(requestId: String): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val ref = firestore.collection(Constants.COLLECTION_CONTACT_REQUESTS).document(requestId)
        val snap = ref.get().await()
        require(snap.exists() && snap.getString("receiverUid") == myUid) {
            "لا تملك صلاحية رفض هذا الطلب"
        }
        ref.update(
            mapOf(
                "status" to ContactRequestStatus.REJECTED.raw,
                "updatedAt" to System.currentTimeMillis()
            )
        ).await()
    }

    override suspend fun cancelOrRemoveContactRequest(requestId: String): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val ref = firestore.collection(Constants.COLLECTION_CONTACT_REQUESTS).document(requestId)
        val snap = ref.get().await()
        @Suppress("UNCHECKED_CAST")
        val participants = (snap.get("participants") as? List<String>).orEmpty()
        require(snap.exists() && participants.contains(myUid)) { "الطلب غير موجود" }
        ref.delete().await()
    }

    override suspend fun areContactsAccepted(peerUid: String): Boolean {
        val myUid = auth.currentUser?.uid ?: return false
        if (peerUid.isBlank() || peerUid == myUid) return false
        val reqId = deterministicRequestId(myUid, peerUid)
        val snap = runCatching {
            firestore.collection(Constants.COLLECTION_CONTACT_REQUESTS).document(reqId).get().await()
        }.getOrNull()
        return snap != null && snap.exists() && snap.getString("status") == ContactRequestStatus.ACCEPTED.raw
    }
}
