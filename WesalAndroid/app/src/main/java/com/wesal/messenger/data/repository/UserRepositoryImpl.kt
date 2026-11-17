package com.wesal.messenger.data.repository

import android.content.Context
import android.net.Uri
import android.os.Build
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.wesal.messenger.BuildConfig
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.core.util.PhoneUtils
import com.wesal.messenger.core.util.WesalFrameCatalog
import com.wesal.messenger.core.util.WesalIdUtils
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.domain.model.*
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
import java.time.LocalDate
import java.time.Period
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("UNCHECKED_CAST")
private fun parseBusinessHours(raw: Any?): BusinessHoursSchedule {
    val map = raw as? Map<String, Any?> ?: return BusinessHoursSchedule()
    val rawDays = map["days"] as? List<Map<String, Any?>>
    val parsedDays = rawDays?.map { dMap ->
        val rawSlots = dMap["slots"] as? List<Map<String, Any?>>
        val slots = rawSlots?.map { sMap ->
            BusinessDaySlot(
                openTime = sMap["openTime"] as? String ?: "09:00",
                closeTime = sMap["closeTime"] as? String ?: "17:00"
            )
        } ?: listOf(BusinessDaySlot())
        BusinessDayHours(
            dayKey = dMap["dayKey"] as? String ?: "SAT",
            isClosed = (dMap["isClosed"] as? Boolean) ?: false,
            isOpen24Hours = (dMap["isOpen24Hours"] as? Boolean) ?: false,
            slots = slots
        )
    } ?: BusinessHoursSchedule().days

    return BusinessHoursSchedule(
        enabled = (map["enabled"] as? Boolean) ?: false,
        businessName = map["businessName"] as? String ?: "",
        timeZoneId = map["timeZoneId"] as? String ?: "Africa/Cairo",
        days = parsedDays
    )
}

private fun BusinessHoursSchedule.toFirestoreMap(): Map<String, Any> = mapOf(
    "enabled" to enabled,
    "businessName" to businessName,
    "timeZoneId" to timeZoneId,
    "days" to days.map { d ->
        mapOf(
            "dayKey" to d.dayKey,
            "isClosed" to d.isClosed,
            "isOpen24Hours" to d.isOpen24Hours,
            "slots" to d.slots.map { s ->
                mapOf("openTime" to s.openTime, "closeTime" to s.closeTime)
            }
        )
    }
)

@Suppress("UNCHECKED_CAST")
internal fun DocumentSnapshot.toWesalUser(includePrivateUnredacted: Boolean = false): WesalUser {
    val privacyMap = get("privacySettings") as? Map<String, Any?> ?: emptyMap()
    val lastSeenVal = when (val raw = get("lastSeen")) {
        is Timestamp -> raw.toDate().time
        is Number -> raw.toLong()
        else -> 0L
    }
    val now = System.currentTimeMillis()
    val hidePhone = (privacyMap["hidePhoneNumber"] as? Boolean) ?: false
    val hideBirth = (privacyMap["hideBirthDate"] as? Boolean) ?: false
    val recordVisits = (privacyMap["recordProfileVisits"] as? Boolean) ?: true

    val rawPhone = getString("phoneNumber").orEmpty()
    val phone = if (hidePhone && !includePrivateUnredacted) "" else rawPhone
    val rawBirth = getString("birthDate").orEmpty()
    val birthDate = if (hideBirth && !includePrivateUnredacted) "" else rawBirth

    val verificationExpiresAt = getLong("verificationExpiresAt") ?: 0L
    val rawVerified = getBoolean("isVerified") ?: false
    val isCurrentlyVerified = rawVerified && (verificationExpiresAt == 0L || now <= verificationExpiresAt)

    val frameExpiresAt = getLong("frameExpiresAt") ?: 0L
    val rawFrameId = getString("frameId").orEmpty()
    val activeFrameId = if (frameExpiresAt > 0L && now > frameExpiresAt) "" else rawFrameId

    val banExpiresAt = getLong("banExpiresAt") ?: 0L
    val rawBanned = getBoolean("isBanned") ?: false
    val isCurrentlyBanned = rawBanned && (banExpiresAt == 0L || now <= banExpiresAt)

    val lastHeartbeatAt = getLong("lastHeartbeatAt") ?: 0L
    val rawOnline = getBoolean("isOnline") ?: false
    val effectiveOnline = rawOnline && (lastHeartbeatAt == 0L || (now - lastHeartbeatAt) <= Constants.PRESENCE_ONLINE_TTL_MS)

    val email = getString("email").orEmpty()
    val isPhoneVerified = getBoolean("isPhoneVerified") ?: (phone.isNotBlank() && email.isBlank())

    return WesalUser(
        uid = getString("uid") ?: id,
        email = email,
        phoneNumber = phone,
        isPhoneVerified = isPhoneVerified,
        isVerified = isCurrentlyVerified,
        verificationType = getString("verificationType") ?: "BLUE",
        verificationExpiresAt = verificationExpiresAt,
        verifiedByUid = getString("verifiedByUid").orEmpty(),
        verifiedAt = getLong("verifiedAt") ?: 0L,
        verificationNote = getString("verificationNote").orEmpty(),
        displayName = getString("displayName").orEmpty(),
        publicId = getString("publicId").orEmpty(),
        numericId = getString("numericId").orEmpty(),
        bio = getString("bio") ?: "أستخدم تطبيق WESAL",
        photoUrl = getString("photoUrl").orEmpty(),
        coverUrl = getString("coverUrl").orEmpty(),
        birthDate = birthDate,
        frameId = activeFrameId,
        frameExpiresAt = frameExpiresAt,
        neonColor = getString("neonColor").orEmpty(),
        neonGlowIntensity = (getDouble("neonGlowIntensity")?.toFloat()) ?: 0.7f,
        facebookUrl = getString("facebookUrl").orEmpty(),
        instagramUrl = getString("instagramUrl").orEmpty(),
        businessHours = parseBusinessHours(get("businessHours")),
        locationGranted = getBoolean("locationGranted") ?: false,
        locationFallbackStartedAt = getLong("locationFallbackStartedAt") ?: 0L,
        profileCompleted = getBoolean("profileCompleted") ?: false,
        welcomeSent = getBoolean("welcomeSent") ?: false,
        hiddenFromSearch = getBoolean("hiddenFromSearch") ?: false,
        hideFromSearchReason = getString("hideFromSearchReason").orEmpty(),
        hiddenAt = getLong("hiddenAt") ?: 0L,
        hiddenByUid = getString("hiddenByUid").orEmpty(),
        isBanned = isCurrentlyBanned,
        banType = getString("banType").orEmpty(),
        banReason = getString("banReason").orEmpty(),
        banExpiresAt = banExpiresAt,
        warningsCount = (getLong("warningsCount") ?: 0L).toInt(),
        deletedAt = getLong("deletedAt") ?: 0L,
        createdAt = getLong("createdAt") ?: 0L,
        lastSeen = lastSeenVal,
        lastHeartbeatAt = lastHeartbeatAt,
        isOnline = effectiveOnline,
        activeSessionsCount = (getLong("activeSessionsCount") ?: 0L).toInt(),
        lastDeviceName = getString("lastDeviceName").orEmpty(),
        lastAppVersion = getString("lastAppVersion").orEmpty(),
        lastActivityType = getString("lastActivityType").orEmpty(),
        lastActivityChatId = getString("lastActivityChatId").orEmpty(),
        lastActivityParticipantsCount = (getLong("lastActivityParticipantsCount") ?: 0L).toInt(),
        lastActivityAt = getLong("lastActivityAt") ?: 0L,
        fcmToken = getString("fcmToken").orEmpty(),
        publicKey = getString("publicKey").orEmpty(),
        blockedUsers = (get("blockedUsers") as? List<String>) ?: emptyList(),
        privacySettings = PrivacySettings(
            lastSeenVisibility = PrivacyVisibility.fromKey(privacyMap["lastSeenVisibility"] as? String),
            onlineVisibility = PrivacyVisibility.fromKey(privacyMap["onlineVisibility"] as? String),
            profilePhotoVisibility = PrivacyVisibility.fromKey(privacyMap["profilePhotoVisibility"] as? String),
            storiesVisibility = PrivacyVisibility.fromKey(privacyMap["storiesVisibility"] as? String),
            readReceiptsEnabled = (privacyMap["readReceiptsEnabled"] as? Boolean) ?: true,
            hidePhoneNumber = hidePhone,
            hideBirthDate = hideBirth,
            recordProfileVisits = recordVisits
        )
    )
}

@Singleton
class UserRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
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
        if (uid == null) flowOf(null) else observeOwnUserWithPrivateInfo(uid)
    }

    private fun observeOwnUserWithPrivateInfo(uid: String): Flow<WesalUser?> = callbackFlow {
        var latestPublic: WesalUser? = null
        var privatePhone = ""
        var privateVerified = false
        var privateBirthDate = ""
        var numericBackfillStarted = false

        fun emitMerged() {
            val pub = latestPublic ?: return
            val mergedPhone = pub.phoneNumber.ifBlank { privatePhone }
            val mergedVerified = pub.isPhoneVerified || privateVerified
            val mergedBirth = pub.birthDate.ifBlank { privateBirthDate }
            trySend(pub.copy(phoneNumber = mergedPhone, isPhoneVerified = mergedVerified, birthDate = mergedBirth))
        }

        val pubReg = firestore.collection(Constants.COLLECTION_USERS)
            .document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(null)
                    return@addSnapshotListener
                }
                latestPublic = snapshot?.takeIf { it.exists() }?.toWesalUser(includePrivateUnredacted = true)
                if (latestPublic == null) {
                    trySend(null)
                } else {
                    if (!numericBackfillStarted && !WesalIdUtils.isValidNumericId(latestPublic?.numericId)) {
                        numericBackfillStarted = true
                        launch { runCatching { ensureNumericIdAssigned() } }
                    }
                    emitMerged()
                }
            }

        val privReg = firestore.collection(Constants.COLLECTION_USERS)
            .document(uid)
            .collection(Constants.SUBCOLLECTION_PRIVATE)
            .document("info")
            .addSnapshotListener { snap, _ ->
                if (snap != null && snap.exists()) {
                    privatePhone = snap.getString("phoneNumber").orEmpty()
                    privateVerified = snap.getBoolean("isPhoneVerified") ?: false
                    privateBirthDate = snap.getString("birthDate").orEmpty()
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

    private suspend fun getDocCachedOrServer(uid: String): DocumentSnapshot? {
        val ref = firestore.collection(Constants.COLLECTION_USERS).document(uid)
        return runCatching { ref.get().await() }.getOrNull()
    }

    private suspend fun getQueryCachedOrServer(query: Query): QuerySnapshot? {
        return runCatching { query.get().await() }.getOrNull()
    }

    override suspend fun getUserOnce(uid: String): WesalUser? {
        val snap = getDocCachedOrServer(uid)
        return if (snap != null && snap.exists()) snap.toWesalUser() else null
    }

    override suspend fun ensureNumericIdAssigned(): String {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val userRef = firestore.collection(Constants.COLLECTION_USERS).document(uid)
        val snap = runCatching { userRef.get().await() }.getOrNull()
        val existing = snap?.getString("numericId").orEmpty()
        if (WesalIdUtils.isValidNumericId(existing)) {
            return existing
        }

        // Preserve owner 10001 strictly if linked in super_admins/{uid}
        val saSnap = runCatching {
            firestore.collection(Constants.COLLECTION_SUPER_ADMINS).document(uid).get().await()
        }.getOrNull()
        val adminEmailMatch = auth.currentUser?.email.orEmpty().equals(Constants.SUPER_ADMIN_EMAIL, ignoreCase = true)
        if (adminEmailMatch && saSnap != null && saSnap.exists() && saSnap.getBoolean("active") != false) {
            userRef.set(mapOf("numericId" to Constants.OWNER_ADMIN_NUMERIC_ID), SetOptions.merge()).await()
            return Constants.OWNER_ADMIN_NUMERIC_ID
        }

        var indexRulesMissing = false
        repeat(16) {
            val candidate = WesalIdUtils.generateRandomNumericCandidate()
            val indexRef = firestore.collection(Constants.COLLECTION_WESAL_ID_INDEX).document(candidate)
            val txResult = runCatching {
                firestore.runTransaction { tx ->
                    val uSnap = tx.get(userRef)
                    val currentId = uSnap.getString("numericId").orEmpty()
                    if (WesalIdUtils.isValidNumericId(currentId)) {
                        return@runTransaction currentId
                    }
                    val idxSnap = tx.get(indexRef)
                    if (idxSnap.exists() && idxSnap.getString("uid") != uid) {
                        throw IllegalStateException("NUMERIC_ID_COLLISION")
                    }
                    tx.set(
                        indexRef,
                        mapOf(
                            "uid" to uid,
                            "numericId" to candidate,
                            "createdAt" to System.currentTimeMillis()
                        )
                    )
                    tx.set(userRef, mapOf("numericId" to candidate), SetOptions.merge())
                    candidate
                }.await()
            }
            val assigned = txResult.getOrNull()
            if (!assigned.isNullOrBlank() && WesalIdUtils.isValidNumericId(assigned)) {
                return assigned
            }
            val ex = txResult.exceptionOrNull()
            if (ex is FirebaseFirestoreException && ex.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                indexRulesMissing = true
                return@repeat
            }
        }

        if (indexRulesMissing) {
            repeat(12) {
                val candidate = WesalIdUtils.generateRandomNumericCandidate()
                val conflict = runCatching {
                    firestore.collection(Constants.COLLECTION_USERS)
                        .whereEqualTo("numericId", candidate)
                        .limit(1)
                        .get()
                        .await()
                }.getOrNull()
                if (conflict != null && conflict.documents.none { it.id != uid }) {
                    userRef.set(mapOf("numericId" to candidate), SetOptions.merge()).await()
                    return candidate
                }
            }
        }

        val fallback = WesalIdUtils.generateRandomNumericCandidate()
        userRef.set(mapOf("numericId" to fallback), SetOptions.merge()).await()
        return fallback
    }

    override suspend fun findUserByPublicIdOrUid(idOrUid: String): WesalUser? {
        val raw = idOrUid.trim()
        if (raw.isBlank()) return null

        val numericQuery = WesalIdUtils.extractNumericIdQuery(raw)
        if (WesalIdUtils.isValidNumericId(numericQuery)) {
            val byNumeric = getQueryCachedOrServer(
                firestore.collection(Constants.COLLECTION_USERS)
                    .whereEqualTo("numericId", numericQuery)
                    .limit(1)
            )
            val matchedNumeric = byNumeric?.documents?.firstOrNull()
            if (matchedNumeric != null) return matchedNumeric.toWesalUser()
        }

        val cleanHandle = WesalIdUtils.normalize(raw)
        if (cleanHandle.isNotBlank()) {
            val byHandle = getQueryCachedOrServer(
                firestore.collection(Constants.COLLECTION_USERS)
                    .whereEqualTo("publicId", cleanHandle)
                    .limit(1)
            )
            val matchedDoc = byHandle?.documents?.firstOrNull()
            if (matchedDoc != null) return matchedDoc.toWesalUser()
        }
        return getUserOnce(raw)
    }

    override suspend fun findExactUserByNumericId(numericId: String): WesalUser? {
        val clean = numericId.trim()
        if (!WesalIdUtils.isValidNumericId(clean)) return null
        val col = firestore.collection(Constants.COLLECTION_USERS)
            .whereEqualTo("numericId", clean)
            .limit(5)
        val snap = runCatching { col.get(Source.SERVER).await() }.getOrNull()
            ?: getQueryCachedOrServer(col)
        val matched = snap?.documents
            ?.firstOrNull { it.getString("numericId")?.trim() == clean }
            ?: return null
        return matched.toWesalUser()
    }

    /**
     * Strict User Search (Sec 14, 25, 31):
     * Normal users may ONLY locate users via exact 5-digit numeric WESAL ID OR exact/normalized @username.
     * Excludes hiddenFromSearch, banned, deleted, and blocked accounts.
     */
    override suspend fun searchUsers(query: String): List<WesalUser> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val currentUid = auth.currentUser?.uid
        val meBlocked = currentUid?.let { getUserOnce(it)?.blockedUsers }.orEmpty()
        val usersCollection = firestore.collection(Constants.COLLECTION_USERS)
        val results = linkedMapOf<String, WesalUser>()

        fun consider(user: WesalUser) {
            if (user.uid == currentUid) return
            if (user.hiddenFromSearch || user.deletedAt > 0L || user.isBanned) return
            if (meBlocked.contains(user.uid) || user.blockedUsers.contains(currentUid)) return
            results[user.uid] = user
        }

        val numericQuery = WesalIdUtils.extractNumericIdQuery(trimmed)
        if (WesalIdUtils.isValidNumericId(numericQuery)) {
            val numSnap = getQueryCachedOrServer(
                usersCollection
                    .whereEqualTo("numericId", numericQuery)
                    .limit(5)
            )
            numSnap?.documents?.forEach { consider(it.toWesalUser()) }
        }

        val cleanHandle = WesalIdUtils.normalize(trimmed)
        if (cleanHandle.length >= 4 && !trimmed.all { it.isDigit() }) {
            val exactSnap = getQueryCachedOrServer(
                usersCollection
                    .whereEqualTo("publicId", cleanHandle)
                    .limit(5)
            )
            exactSnap?.documents?.forEach { consider(it.toWesalUser()) }
        }

        return results.values.toList()
    }

    /**
     * Public account directory browsing is DISABLED for normal users (Sec 25 & Sec 31).
     * Only Super Admin (10001) receives results here.
     */
    override suspend fun listDiscoverableUsers(limit: Int): List<WesalUser> {
        if (!isSuperAdmin()) return emptyList()
        val currentUid = auth.currentUser?.uid
        val snap = getQueryCachedOrServer(
            firestore.collection(Constants.COLLECTION_USERS)
                .limit(limit.toLong().coerceIn(10L, 120L))
        )
        return snap?.documents?.map { it.toWesalUser() }
            ?.filter { it.uid != currentUid && it.deletedAt == 0L }
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
            val snap = getQueryCachedOrServer(
                firestore.collection(Constants.COLLECTION_USERS)
                    .whereIn("phoneHash", chunk)
            )
            snap?.documents?.forEach { doc ->
                val hash = doc.getString("phoneHash").orEmpty()
                val user = doc.toWesalUser()
                if (
                    hash.isNotBlank() &&
                    user.uid != currentUid &&
                    !user.privacySettings.hidePhoneNumber &&
                    !user.hiddenFromSearch &&
                    !user.isBanned &&
                    user.deletedAt == 0L
                ) {
                    matched[hash] = user
                }
            }
        }
        return matched
    }

        override suspend fun updateProfileImages(photoUri: Uri?, coverUri: Uri?): Result<Unit> {
        return try {
            val uid = auth.currentUser?.uid ?: error("Not authenticated")
            val userRef = firestore.collection(Constants.COLLECTION_USERS).document(uid)
            val updates = hashMapOf<String, Any?>()
            if (photoUri != null) {
                val url = withContext(Dispatchers.IO) {
                    uploadProfileMediaToStorageOrSmallFallback(
                        ctx = context,
                        storagePath = "avatars/$uid/avatar_${System.currentTimeMillis()}.jpg",
                        uri = photoUri,
                        maxSide = 512,
                        quality = 84
                    )
                }
                if (url.isNotBlank()) {
                    updates["photoUrl"] = url
                    userRef.collection(Constants.SUBCOLLECTION_PRIVATE).document("info")
                        .set(mapOf("hiddenPhotoUrl" to url), SetOptions.merge()).await()
                }
            }
            if (coverUri != null) {
                val url = withContext(Dispatchers.IO) {
                    uploadProfileMediaToStorageOrSmallFallback(
                        ctx = context,
                        storagePath = "avatars/$uid/cover_${System.currentTimeMillis()}.jpg",
                        uri = coverUri,
                        maxSide = 1080,
                        quality = 84
                    )
                }
                if (url.isNotBlank()) {
                    updates["coverUrl"] = url
                    userRef.collection(Constants.SUBCOLLECTION_PRIVATE).document("info")
                        .set(mapOf("hiddenCoverUrl" to url), SetOptions.merge()).await()
                }
            }
            if (updates.isNotEmpty()) userRef.set(updates, SetOptions.merge()).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateProfile(
        displayName: String,
        bio: String,
        publicId: String,
        photoUri: Uri?,
        coverUri: Uri?,
        birthDate: String?,
        hideBirthDate: Boolean?,
        facebookUrl: String?,
        instagramUrl: String?,
        businessHours: BusinessHoursSchedule?
    ): Result<Unit> {
        return try {
            val uid = auth.currentUser?.uid ?: error("Not authenticated")
            val trimmedName = displayName.trim()
            require(trimmedName.length >= 2) { "الاسم الظاهر يجب أن يكون حرفين على الأقل" }

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

            val userRef = firestore.collection(Constants.COLLECTION_USERS).document(uid)
            val existingSnap = runCatching { userRef.get().await() }.getOrNull()
            @Suppress("UNCHECKED_CAST")
            val existingPrivacy = (existingSnap?.get("privacySettings") as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()
            val effectiveHideBirth = hideBirthDate ?: (existingPrivacy["hideBirthDate"] as? Boolean ?: false)
            existingPrivacy["hideBirthDate"] = effectiveHideBirth

            val updates = hashMapOf<String, Any>(
                "displayName" to trimmedName,
                "bio" to bio.trim(),
                "publicId" to sanitizedHandle,
                "privacySettings" to existingPrivacy
            )

            if (birthDate != null && birthDate.isNotBlank()) {
                val parsed = runCatching { LocalDate.parse(birthDate.trim()) }.getOrNull()
                    ?: return Result.failure(IllegalArgumentException("تاريخ الميلاد غير صالح"))
                val today = LocalDate.now()
                if (parsed.isAfter(today)) {
                    return Result.failure(IllegalArgumentException("تاريخ الميلاد لا يمكن أن يكون في المستقبل"))
                }
                if (Period.between(parsed, today).years < Constants.MIN_AGE_YEARS) {
                    return Result.failure(IllegalArgumentException("الحد الأدنى للعمر هو ${Constants.MIN_AGE_YEARS} عاماً"))
                }
                userRef.collection(Constants.SUBCOLLECTION_PRIVATE).document("info")
                    .set(mapOf("birthDate" to birthDate.trim()), SetOptions.merge())
                    .await()
                updates["birthDate"] = if (effectiveHideBirth) "" else birthDate.trim()
            } else if (hideBirthDate != null) {
                val privSnap = runCatching { userRef.collection(Constants.SUBCOLLECTION_PRIVATE).document("info").get().await() }.getOrNull()
                val realBirth = privSnap?.getString("birthDate").orEmpty().ifBlank { existingSnap?.getString("birthDate").orEmpty() }
                if (realBirth.isNotBlank()) {
                    userRef.collection(Constants.SUBCOLLECTION_PRIVATE).document("info")
                        .set(mapOf("birthDate" to realBirth), SetOptions.merge())
                        .await()
                }
                updates["birthDate"] = if (effectiveHideBirth) "" else realBirth
            }

            if (facebookUrl != null) {
                updates["facebookUrl"] = normalizeSocialUrl(facebookUrl)
            }
            if (instagramUrl != null) {
                updates["instagramUrl"] = normalizeSocialUrl(instagramUrl)
            }
            if (businessHours != null) {
                updates["businessHours"] = businessHours.toFirestoreMap()
            }

            if (photoUri != null) {
                val uploadedAvatar = withContext(Dispatchers.IO) {
                    uploadProfileMediaToStorageOrSmallFallback(
                        ctx = context,
                        storagePath = "avatars/$uid/avatar_${System.currentTimeMillis()}.jpg",
                        uri = photoUri,
                        maxSide = 512,
                        quality = 84
                    )
                }
                if (uploadedAvatar.isNotBlank()) {
                    updates["photoUrl"] = uploadedAvatar
                    userRef.collection(Constants.SUBCOLLECTION_PRIVATE).document("info")
                        .set(mapOf("hiddenPhotoUrl" to uploadedAvatar), SetOptions.merge())
                        .await()
                }
            }

            if (coverUri != null) {
                val uploadedCover = withContext(Dispatchers.IO) {
                    uploadProfileMediaToStorageOrSmallFallback(
                        ctx = context,
                        storagePath = "avatars/$uid/cover_${System.currentTimeMillis()}.jpg",
                        uri = coverUri,
                        maxSide = 1080,
                        quality = 84
                    )
                }
                if (uploadedCover.isNotBlank()) {
                    updates["coverUrl"] = uploadedCover
                    userRef.collection(Constants.SUBCOLLECTION_PRIVATE).document("info")
                        .set(mapOf("hiddenCoverUrl" to uploadedCover), SetOptions.merge())
                        .await()
                }
            }

            userRef.set(updates, SetOptions.merge()).await()

            val updatedSnap = getDocCachedOrServer(uid)
            updatedSnap?.takeIf { it.exists() }?.toWesalUser(includePrivateUnredacted = true)?.let { updatedUser ->
                val assignedNumericId = if (WesalIdUtils.isValidNumericId(updatedUser.numericId)) {
                    updatedUser.numericId
                } else {
                    runCatching { ensureNumericIdAssigned() }.getOrDefault(updatedUser.numericId)
                }
                val providerIds = auth.currentUser?.providerData?.map { it.providerId }.orEmpty()
                val provider = when {
                    providerIds.contains("google.com") -> "google"
                    else -> "email"
                }
                prefs.upsertSavedAccount(
                    SavedWesalAccount(
                        uid = updatedUser.uid,
                        displayName = updatedUser.displayName,
                        email = updatedUser.email,
                        phoneNumber = updatedUser.phoneNumber,
                        publicId = updatedUser.publicId,
                        numericId = assignedNumericId,
                        photoUrl = updatedUser.photoUrl,
                        provider = provider,
                        lastUsedAt = System.currentTimeMillis()
                    )
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun normalizeSocialUrl(raw: String): String {
        val clean = raw.trim()
        if (clean.isBlank()) return ""
        return if (clean.startsWith("http://", true) || clean.startsWith("https://", true)) clean else "https://$clean"
    }

    override suspend fun updateOptionalPhone(rawPhone: String): Result<Unit> = runCatching {
        val trimmed = rawPhone.trim()
        if (trimmed.isEmpty()) {
            removeOptionalPhone().getOrThrow()
            return@runCatching
        }
        val normalized = PhoneUtils.normalizeToE164(trimmed)
            ?: throw IllegalArgumentException("صيغة رقم الهاتف غير صحيحة (مثال: +201012345678)")
        updatePhoneValidated(normalized).getOrThrow()
    }

    override suspend fun updatePhoneValidated(e164Phone: String): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val normalized = e164Phone.trim()
        val fullHash = PhoneUtils.sha256Hash(normalized)

        // Check phone uniqueness lock + banned identifiers
        val banCheck = runCatching {
            firestore.collection(Constants.COLLECTION_BANNED_IDENTIFIERS).document("phone_$fullHash").get().await()
        }.getOrNull()
        require(banCheck == null || !banCheck.exists()) { "لا يمكن استخدام رقم الهاتف هذا" }

        val lockRef = firestore.collection("phone_uniqueness").document(fullHash)
        val lockSnap = runCatching { lockRef.get().await() }.getOrNull()
        if (lockSnap != null && lockSnap.exists() && lockSnap.getString("uid") != uid) {
            error("رقم الهاتف مستخدم بالفعل في حساب آخر")
        }

        val docRef = firestore.collection(Constants.COLLECTION_USERS).document(uid)
        val snap = docRef.get().await()
        @Suppress("UNCHECKED_CAST")
        val privacyMap = snap.get("privacySettings") as? Map<String, Any?> ?: emptyMap()
        val hidePhone = (privacyMap["hidePhoneNumber"] as? Boolean) ?: false

        // Release old phone_uniqueness lock if phone changed
        val privRef = docRef.collection(Constants.SUBCOLLECTION_PRIVATE).document("info")
        val oldPriv = runCatching { privRef.get().await() }.getOrNull()
        val oldPhone = oldPriv?.getString("phoneNumber").orEmpty().ifBlank { snap.getString("phoneNumber").orEmpty() }
        if (oldPhone.isNotBlank() && oldPhone != normalized) {
            val oldHash = PhoneUtils.sha256Hash(oldPhone)
            runCatching { firestore.collection("phone_uniqueness").document(oldHash).delete().await() }
        }

        runCatching {
            lockRef.set(mapOf("uid" to uid, "phoneHash" to fullHash, "updatedAt" to System.currentTimeMillis())).await()
        }

        privRef.set(
            mapOf("phoneNumber" to normalized, "isPhoneVerified" to true),
            SetOptions.merge()
        ).await()

        val updates = hashMapOf<String, Any>(
            "phoneNumber" to if (hidePhone) "" else normalized,
            "isPhoneVerified" to true,
            "phoneHash" to if (hidePhone) "" else fullHash
        )
        docRef.set(updates, SetOptions.merge()).await()
    }

    override suspend fun removeOptionalPhone(): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val docRef = firestore.collection(Constants.COLLECTION_USERS).document(uid)
        val privRef = docRef.collection(Constants.SUBCOLLECTION_PRIVATE).document("info")
        val oldPriv = runCatching { privRef.get().await() }.getOrNull()
        val oldPhone = oldPriv?.getString("phoneNumber").orEmpty()
        if (oldPhone.isNotBlank()) {
            val oldHash = PhoneUtils.sha256Hash(oldPhone)
            runCatching { firestore.collection("phone_uniqueness").document(oldHash).delete().await() }
        }
        runCatching {
            privRef.set(mapOf("phoneNumber" to "", "isPhoneVerified" to false), SetOptions.merge()).await()
        }
        docRef.set(
            mapOf("phoneNumber" to "", "phoneHash" to "", "isPhoneVerified" to false),
            SetOptions.merge()
        ).await()
    }

    override suspend fun removeProfilePhoto(): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        firestore.collection(Constants.COLLECTION_USERS).document(uid).update("photoUrl", "").await()
    }

    override suspend fun removeProfileCover(): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        firestore.collection(Constants.COLLECTION_USERS).document(uid).update("coverUrl", "").await()
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
            val currentBirth = privSnap?.getString("birthDate").orEmpty().ifBlank { snap.getString("birthDate").orEmpty() }

            val map = hashMapOf<String, Any>(
                "privacySettings" to mapOf(
                    "lastSeenVisibility" to settings.lastSeenVisibility.key,
                    "onlineVisibility" to settings.onlineVisibility.key,
                    "profilePhotoVisibility" to settings.profilePhotoVisibility.key,
                    "storiesVisibility" to settings.storiesVisibility.key,
                    "readReceiptsEnabled" to settings.readReceiptsEnabled,
                    "hidePhoneNumber" to settings.hidePhoneNumber,
                    "hideBirthDate" to settings.hideBirthDate,
                    "recordProfileVisits" to settings.recordProfileVisits
                )
            )

            if (settings.hidePhoneNumber) {
                if (currentPhone.isNotBlank()) {
                    privRef.set(mapOf("phoneNumber" to currentPhone, "isPhoneVerified" to currentVerified), SetOptions.merge()).await()
                }
                map["phoneNumber"] = ""
                map["phoneHash"] = ""
            } else if (currentPhone.isNotBlank()) {
                val norm = PhoneUtils.normalizeToE164(currentPhone) ?: currentPhone
                map["phoneNumber"] = norm
                map["phoneHash"] = PhoneUtils.sha256Hash(norm)
            }

            if (settings.hideBirthDate) {
                if (currentBirth.isNotBlank()) {
                    privRef.set(mapOf("birthDate" to currentBirth), SetOptions.merge()).await()
                }
                map["birthDate"] = ""
            } else if (currentBirth.isNotBlank()) {
                map["birthDate"] = currentBirth
            }

            docRef.set(map, SetOptions.merge()).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateLocationStatus(granted: Boolean, graceStartedAt: Long): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        prefs.setLocationState(granted, graceStartedAt)
        val updates = mutableMapOf<String, Any>("locationGranted" to granted)
        if (graceStartedAt > 0L) updates["locationFallbackStartedAt"] = graceStartedAt
        firestore.collection(Constants.COLLECTION_USERS).document(uid).set(updates, SetOptions.merge()).await()
    }

    override suspend fun updatePresenceHeartbeat(
        isOnline: Boolean,
        activityType: String,
        activityChatId: String,
        participantsCount: Int
    ) {
        val uid = auth.currentUser?.uid ?: return
        val now = System.currentTimeMillis()
        val device = "${Build.MANUFACTURER} ${Build.MODEL}"
        val appVer = BuildConfig.VERSION_NAME
        val updates = mutableMapOf<String, Any>(
            "isOnline" to isOnline,
            "lastSeen" to now,
            "lastHeartbeatAt" to now,
            "lastDeviceName" to device,
            "lastAppVersion" to appVer
        )
        if (activityType.isNotBlank()) {
            updates["lastActivityType"] = activityType
            updates["lastActivityChatId"] = activityChatId
            updates["lastActivityParticipantsCount"] = participantsCount
            updates["lastActivityAt"] = now
        }
        runCatching {
            firestore.collection(Constants.COLLECTION_USERS).document(uid).set(updates, SetOptions.merge()).await()
        }
        runCatching {
            firestore.collection(Constants.COLLECTION_PRESENCE).document(uid).set(
                mapOf(
                    "uid" to uid,
                    "isOnline" to isOnline,
                    "lastHeartbeatAt" to now,
                    "deviceName" to device,
                    "appVersion" to appVer,
                    "activityType" to activityType,
                    "activityChatId" to activityChatId,
                    "participantsCount" to participantsCount
                ),
                SetOptions.merge()
            ).await()
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

    override suspend fun reportUser(
        targetUid: String,
        reason: String,
        targetType: String,
        targetChatId: String,
        targetMessageId: String,
        targetContentSnapshot: String
    ): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val me = getUserOnce(uid)
        val reported = if (targetUid.isNotBlank()) getUserOnce(targetUid) else null
        val reportId = UUID.randomUUID().toString()
        val report = mapOf(
            "reportId" to reportId,
            "reporterUid" to uid,
            "reporterName" to (me?.displayName.orEmpty().ifBlank { "@${me?.publicId.orEmpty()}" }),
            "reportedUid" to targetUid,
            "reportedName" to (reported?.displayName.orEmpty().ifBlank { "@${reported?.publicId.orEmpty()}" }),
            "targetType" to targetType,
            "targetChatId" to targetChatId,
            "targetMessageId" to targetMessageId,
            "targetContentSnapshot" to targetContentSnapshot,
            "reason" to reason.trim(),
            "status" to "NEW",
            "createdAt" to System.currentTimeMillis()
        )
        firestore.collection(Constants.COLLECTION_REPORTS).document(reportId).set(report).await()
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
                        val list = snap?.documents?.mapNotNull { doc ->
                            if (doc.id.startsWith("link_")) return@mapNotNull null
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

    override suspend fun revokeAllOtherSessions(): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val currentSessionId = prefs.getOrCreateDeviceSessionId()
        val snap = firestore.collection(Constants.COLLECTION_USERS)
            .document(uid)
            .collection(Constants.COLLECTION_SESSIONS)
            .get()
            .await()
        val batch = firestore.batch()
        snap.documents.forEach { doc ->
            val sid = doc.getString("sessionId") ?: doc.id
            if (sid != currentSessionId && !doc.id.startsWith("link_")) {
                batch.delete(doc.reference)
            }
        }
        batch.commit().await()
    }

    override suspend fun createSessionLinkToken(): Result<String> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val token = UUID.randomUUID().toString().replace("-", "").take(16).uppercase()
        val now = System.currentTimeMillis()
        firestore.collection(Constants.COLLECTION_USERS)
            .document(uid)
            .collection(Constants.COLLECTION_SESSIONS)
            .document("link_$token")
            .set(
                mapOf(
                    "token" to token,
                    "uid" to uid,
                    "singleUse" to true,
                    "used" to false,
                    "createdAt" to now,
                    "expiresAt" to now + 5 * 60 * 1000L
                )
            )
            .await()
        "WESAL-LINK:$token"
    }

    // -----------------------------------------------------------------------
    // Profile Visitors (Sec 23)
    // -----------------------------------------------------------------------

    override suspend fun recordProfileVisit(targetUid: String) {
        val myUid = auth.currentUser?.uid ?: return
        if (targetUid.isBlank() || targetUid == myUid) return
        runCatching {
            val me = getUserOnce(myUid) ?: return
            if (!me.privacySettings.recordProfileVisits) return
            val target = getUserOnce(targetUid) ?: return
            if (!target.privacySettings.recordProfileVisits) return
            val visitorDoc = mapOf(
                "visitorUid" to myUid,
                "visitorName" to me.displayName.ifBlank { "@${me.publicId}" },
                "visitorPublicId" to me.publicId,
                "visitorNumericId" to me.numericId,
                "visitorAvatarUrl" to me.photoUrl,
                "visitorVerified" to me.isVerified,
                "visitorVerificationType" to me.verificationType,
                "visitedAt" to System.currentTimeMillis()
            )
            firestore.collection(Constants.COLLECTION_USERS)
                .document(targetUid)
                .collection(Constants.SUBCOLLECTION_PROFILE_VISITORS)
                .document(myUid)
                .set(visitorDoc, SetOptions.merge())
                .await()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeProfileVisitors(): Flow<List<ProfileVisitor>> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(emptyList())
        } else {
            callbackFlow {
                val reg = firestore.collection(Constants.COLLECTION_USERS)
                    .document(uid)
                    .collection(Constants.SUBCOLLECTION_PROFILE_VISITORS)
                    .orderBy("visitedAt", Query.Direction.DESCENDING)
                    .limit(100)
                    .addSnapshotListener { snap, error ->
                        if (error != null) {
                            trySend(emptyList())
                            return@addSnapshotListener
                        }
                        val list = snap?.documents?.map { doc ->
                            ProfileVisitor(
                                visitorUid = doc.getString("visitorUid") ?: doc.id,
                                visitorName = doc.getString("visitorName").orEmpty(),
                                visitorPublicId = doc.getString("visitorPublicId").orEmpty(),
                                visitorNumericId = doc.getString("visitorNumericId").orEmpty(),
                                visitorAvatarUrl = doc.getString("visitorAvatarUrl").orEmpty(),
                                visitorVerified = doc.getBoolean("visitorVerified") ?: false,
                                visitorVerificationType = doc.getString("visitorVerificationType") ?: "BLUE",
                                visitedAt = doc.getLong("visitedAt") ?: 0L
                            )
                        }.orEmpty()
                        trySend(list)
                    }
                awaitClose { reg.remove() }
            }
        }
    }

    override suspend fun clearProfileVisitors(): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val snap = firestore.collection(Constants.COLLECTION_USERS)
            .document(uid)
            .collection(Constants.SUBCOLLECTION_PROFILE_VISITORS)
            .get()
            .await()
        val batch = firestore.batch()
        snap.documents.forEach { batch.delete(it.reference) }
        batch.commit().await()
    }

    // -----------------------------------------------------------------------
    // Super Admin Lock (ONLY 10001 + eslamfifty40@gmail.com) & 19-Section Controls
    // -----------------------------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeIsSuperAdmin(): Flow<Boolean> = observeAuthUid().flatMapLatest { uid ->
        val user = auth.currentUser
        val email = user?.email.orEmpty()
        if (
            uid == null ||
            user == null ||
            !email.equals(Constants.SUPER_ADMIN_EMAIL, ignoreCase = true) ||
            !user.isEmailVerified
        ) {
            flowOf(false)
        } else {
            callbackFlow {
                val reg = firestore.collection(Constants.COLLECTION_SUPER_ADMINS)
                    .document(uid)
                    .addSnapshotListener { snap, error ->
                        if (error != null || snap == null || !snap.exists()) {
                            trySend(false)
                            return@addSnapshotListener
                        }
                        val docNumId = snap.getString("numericId").orEmpty()
                        val active = snap.getBoolean("active") != false &&
                            (docNumId.isBlank() || docNumId == Constants.OWNER_ADMIN_NUMERIC_ID)
                        trySend(active)
                    }
                awaitClose { reg.remove() }
            }
        }
    }

    override suspend fun isSuperAdmin(): Boolean {
        val user = auth.currentUser ?: return false
        val email = user.email.orEmpty()
        if (!email.equals(Constants.SUPER_ADMIN_EMAIL, ignoreCase = true)) return false
        if (!user.isEmailVerified) return false

        val saDoc = runCatching {
            firestore.collection(Constants.COLLECTION_SUPER_ADMINS).document(user.uid).get(Source.SERVER).await()
        }.getOrNull() ?: return false
        if (!saDoc.exists() || saDoc.getBoolean("active") == false) return false

        val saNumeric = saDoc.getString("numericId").orEmpty()
        if (saNumeric.isNotBlank() && saNumeric != Constants.OWNER_ADMIN_NUMERIC_ID) return false

        val userDoc = runCatching {
            firestore.collection(Constants.COLLECTION_USERS).document(user.uid).get(Source.SERVER).await()
        }.getOrNull()
        val userNumId = userDoc?.getString("numericId").orEmpty()
        if (userNumId.isNotBlank() && userNumId != Constants.OWNER_ADMIN_NUMERIC_ID) return false

        return true
    }

    private suspend fun writeAdminActionLog(
        action: String,
        targetUid: String = "",
        targetNumericId: String = "",
        targetPublicId: String = "",
        targetName: String = "",
        note: String = "",
        details: String = "",
        verificationType: String = "BLUE",
        durationLabel: String = "",
        expiresAt: Long = 0L,
        operationId: String = UUID.randomUUID().toString(),
        collectionName: String = Constants.COLLECTION_ADMIN_LOGS
    ) {
        val admin = auth.currentUser ?: return
        val logData = mapOf(
            "logId" to operationId,
            "operationId" to operationId,
            "targetUid" to targetUid,
            "targetNumericId" to targetNumericId,
            "targetPublicId" to targetPublicId,
            "targetName" to targetName,
            "action" to action,
            "verificationType" to verificationType,
            "durationLabel" to durationLabel,
            "expiresAt" to expiresAt,
            "adminUid" to admin.uid,
            "adminEmail" to (admin.email ?: Constants.SUPER_ADMIN_EMAIL),
            "note" to note,
            "details" to details,
            "timestamp" to System.currentTimeMillis()
        )
        runCatching {
            firestore.collection(collectionName).document(operationId).set(logData).await()
        }
    }

    override suspend fun getAdminUsersList(limit: Int): List<WesalUser> {
        if (!isSuperAdmin()) return emptyList()
        val snap = runCatching {
            firestore.collection(Constants.COLLECTION_USERS)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit.toLong().coerceIn(20L, 400L))
                .get()
                .await()
        }.getOrNull() ?: return emptyList()
        return snap.documents.map { it.toWesalUser(includePrivateUnredacted = true) }
    }

    override suspend fun adminSearchUsers(query: String): List<WesalUser> {
        if (!isSuperAdmin()) return emptyList()
        val q = query.trim()
        if (q.isBlank()) return getAdminUsersList(120)
        val usersCol = firestore.collection(Constants.COLLECTION_USERS)
        val results = linkedMapOf<String, WesalUser>()

        // 1. Direct UID
        runCatching { usersCol.document(q).get().await() }.getOrNull()?.takeIf { it.exists() }?.let {
            val u = it.toWesalUser(includePrivateUnredacted = true)
            results[u.uid] = u
        }

        // 2. Numeric WESAL ID
        val numQ = WesalIdUtils.extractNumericIdQuery(q)
        if (numQ.isNotBlank()) {
            getQueryCachedOrServer(usersCol.whereEqualTo("numericId", numQ).limit(5))?.documents?.forEach {
                val u = it.toWesalUser(includePrivateUnredacted = true)
                results[u.uid] = u
            }
        }

        // 3. Username / Handle
        val handle = WesalIdUtils.normalize(q)
        if (handle.isNotBlank()) {
            getQueryCachedOrServer(usersCol.whereEqualTo("publicId", handle).limit(10))?.documents?.forEach {
                val u = it.toWesalUser(includePrivateUnredacted = true)
                results[u.uid] = u
            }
        }

        // 4. Local pool filter for partial name matches
        val allSnap = getQueryCachedOrServer(usersCol.limit(250))
        allSnap?.documents?.forEach { doc ->
            val u = doc.toWesalUser(includePrivateUnredacted = true)
            if (u.displayName.contains(q, ignoreCase = true) ||
                u.publicId.contains(handle, ignoreCase = true) ||
                u.numericId.contains(q, ignoreCase = true) ||
                u.email.contains(q, ignoreCase = true)
            ) {
                results[u.uid] = u
            }
        }
        return results.values.toList()
    }

    override suspend fun adminGetUserFullDetails(targetUid: String, reason: String): Result<WesalUser> = runCatching {
        require(isSuperAdmin()) { "صلاحية المشرف الأعلى مطلوبة" }
        val userRef = firestore.collection(Constants.COLLECTION_USERS).document(targetUid)
        val snap = userRef.get().await()
        require(snap.exists()) { "الحساب غير موجود" }
        var user = snap.toWesalUser(includePrivateUnredacted = true)

        val privSnap = runCatching {
            userRef.collection(Constants.SUBCOLLECTION_PRIVATE).document("info").get().await()
        }.getOrNull()
        if (privSnap != null && privSnap.exists()) {
            val privPhone = privSnap.getString("phoneNumber").orEmpty()
            val privBirth = privSnap.getString("birthDate").orEmpty()
            val privPhoto = privSnap.getString("hiddenPhotoUrl").orEmpty()
            val privCover = privSnap.getString("hiddenCoverUrl").orEmpty()
            user = user.copy(
                phoneNumber = privPhone.ifBlank { user.phoneNumber },
                birthDate = privBirth.ifBlank { user.birthDate },
                photoUrl = user.photoUrl.ifBlank { privPhoto },
                coverUrl = user.coverUrl.ifBlank { privCover }
            )
        }

        writeAdminActionLog(
            action = "ADMIN_INSPECT_USER",
            targetUid = user.uid,
            targetNumericId = user.numericId,
            targetPublicId = user.publicId,
            targetName = user.displayName,
            note = reason,
            details = "معاينة بيانات الحساب الكاملة من لوحة الإدارة"
        )
        user
    }

    override suspend fun computeAdminStats(): AdminDashboardStats {
        if (!isSuperAdmin()) return AdminDashboardStats()
        val snap = runCatching {
            firestore.collection(Constants.COLLECTION_USERS).limit(500).get().await()
        }.getOrNull() ?: return AdminDashboardStats()

        val now = System.currentTimeMillis()
        val dayMs = 24L * 60L * 60L * 1000L
        val users = snap.documents.map { it.toWesalUser(includePrivateUnredacted = true) }

        val onlineNow = users.count { it.isOnline }
        return AdminDashboardStats(
            totalAccounts = users.size,
            onlineNowCount = onlineNow,
            offlineCount = (users.size - onlineNow).coerceAtLeast(0),
            newTodayCount = users.count { it.createdAt > 0 && now - it.createdAt <= dayMs },
            newTwoDaysCount = users.count { it.createdAt > 0 && now - it.createdAt <= 2 * dayMs },
            newWeekCount = users.count { it.createdAt > 0 && now - it.createdAt <= 7 * dayMs },
            newMonthCount = users.count { it.createdAt > 0 && now - it.createdAt <= 30 * dayMs },
            activeTodayCount = users.count { it.lastSeen > 0 && now - it.lastSeen <= dayMs },
            activeWeekCount = users.count { it.lastSeen > 0 && now - it.lastSeen <= 7 * dayMs },
            activeMonthCount = users.count { it.lastSeen > 0 && now - it.lastSeen <= 30 * dayMs },
            bannedCount = users.count { it.isBanned },
            deletedCount = users.count { it.deletedAt > 0L },
            hiddenCount = users.count { it.hiddenFromSearch },
            verifiedCount = users.count { it.isVerified }
        )
    }

    override suspend fun setAccountVerification(
        targetUid: String,
        verified: Boolean,
        note: String,
        verificationType: String,
        expiresAt: Long,
        durationLabel: String
    ): Result<Unit> = runCatching {
        val admin = auth.currentUser ?: error("Not authenticated")
        require(isSuperAdmin()) { "صلاحية المشرف الأعلى غير مفعلة لهذا الحساب" }

        val now = System.currentTimeMillis()
        val targetRef = firestore.collection(Constants.COLLECTION_USERS).document(targetUid)
        val targetSnap = targetRef.get().await()
        require(targetSnap.exists()) { "الحساب غير موجود" }

        val safeType = verificationType.uppercase().takeIf { it in setOf("BLUE", "BLACK", "GREEN") } ?: "BLUE"
        val actionName = if (verified) "GRANTED" else "REVOKED"
        // Operation ID bucketed gently to prevent accidental duplicate write from rapid double-tap retries
        val opBucket = now / 5000L
        val operationId = "ver_${targetUid}_${actionName}_${safeType}_${expiresAt}_$opBucket"

        val updates = hashMapOf<String, Any>(
            "isVerified" to verified,
            "verificationType" to safeType,
            "verificationExpiresAt" to if (verified) expiresAt else 0L,
            "verifiedByUid" to admin.uid,
            "verifiedAt" to now,
            "verificationNote" to note.trim()
        )
        targetRef.set(updates, SetOptions.merge()).await()

        val targetNum = targetSnap.getString("numericId").orEmpty()
        val targetPub = targetSnap.getString("publicId").orEmpty()
        val targetName = targetSnap.getString("displayName").orEmpty()
        val logData = mapOf(
            "logId" to operationId,
            "operationId" to operationId,
            "targetUid" to targetUid,
            "targetNumericId" to targetNum,
            "targetPublicId" to targetPub,
            "targetName" to targetName,
            "action" to actionName,
            "verificationType" to safeType,
            "durationLabel" to durationLabel.ifBlank { if (expiresAt == 0L) "دائم" else "مؤقت" },
            "expiresAt" to expiresAt,
            "adminUid" to admin.uid,
            "adminEmail" to (admin.email ?: Constants.SUPER_ADMIN_EMAIL),
            "note" to note.trim(),
            "details" to if (verified) "توثيق الحساب ($safeType) - ${durationLabel.ifBlank { "دائم" }}" else "إلغاء التوثيق",
            "timestamp" to now
        )
        firestore.collection(Constants.COLLECTION_VERIFICATION_LOGS)
            .document(operationId)
            .set(logData)
            .await()
    }

    override suspend fun setAccountFrame(
        targetUid: String,
        frameId: String,
        expiresAt: Long,
        durationLabel: String
    ): Result<Unit> = runCatching {
        require(isSuperAdmin()) { "صلاحية المشرف الأعلى مطلوبة" }
        val targetRef = firestore.collection(Constants.COLLECTION_USERS).document(targetUid)
        val snap = targetRef.get().await()
        require(snap.exists()) { "الحساب غير موجود" }

        targetRef.set(
            mapOf(
                "frameId" to frameId.trim(),
                "frameExpiresAt" to if (frameId.isBlank()) 0L else expiresAt
            ),
            SetOptions.merge()
        ).await()

        val opId = "frame_${targetUid}_${frameId}_${expiresAt}_${System.currentTimeMillis() / 5000L}"
        writeAdminActionLog(
            action = if (frameId.isBlank()) "FRAME_REMOVED" else "FRAME_GRANTED",
            targetUid = targetUid,
            targetNumericId = snap.getString("numericId").orEmpty(),
            targetPublicId = snap.getString("publicId").orEmpty(),
            targetName = snap.getString("displayName").orEmpty(),
            durationLabel = durationLabel,
            expiresAt = expiresAt,
            details = if (frameId.isBlank()) "إزالة الإطار الشخصي" else "إهداء إطار: $frameId ($durationLabel)",
            operationId = opId,
            collectionName = Constants.COLLECTION_VERIFICATION_LOGS
        )
    }

    override suspend fun getGlobalCustomerFrameEnabled(): Boolean {
        val snap = runCatching {
            firestore.collection(Constants.COLLECTION_SYSTEM_CONFIG).document("global_appearance").get().await()
        }.getOrNull()
        return snap?.getBoolean("globalCustomerFrameEnabled") ?: true
    }

    override suspend fun setGlobalCustomerFrameEnabled(enabled: Boolean): Result<Int> = runCatching {
        require(isSuperAdmin()) { "صلاحية المشرف الأعلى مطلوبة" }
        firestore.collection(Constants.COLLECTION_SYSTEM_CONFIG)
            .document("global_appearance")
            .set(
                mapOf(
                    "globalCustomerFrameEnabled" to enabled,
                    "defaultFrameId" to WesalFrameCatalog.DEFAULT_ALL_CUSTOMERS_FRAME_ID,
                    "updatedAt" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            ).await()

        val usersSnap = firestore.collection(Constants.COLLECTION_USERS).limit(500).get().await()
        var updatedCount = 0
        val chunks = usersSnap.documents.chunked(380)
        for (chunk in chunks) {
            val batch = firestore.batch()
            for (doc in chunk) {
                val currentFrame = doc.getString("frameId").orEmpty()
                val numId = doc.getString("numericId").orEmpty()
                if (numId == Constants.OWNER_ADMIN_NUMERIC_ID) continue
                if (enabled) {
                    if (currentFrame.isBlank()) {
                        batch.set(
                            doc.reference,
                            mapOf("frameId" to WesalFrameCatalog.DEFAULT_ALL_CUSTOMERS_FRAME_ID, "frameExpiresAt" to 0L),
                            SetOptions.merge()
                        )
                        updatedCount++
                    }
                } else if (currentFrame == WesalFrameCatalog.DEFAULT_ALL_CUSTOMERS_FRAME_ID) {
                    batch.set(
                        doc.reference,
                        mapOf("frameId" to "", "frameExpiresAt" to 0L),
                        SetOptions.merge()
                    )
                    updatedCount++
                }
            }
            batch.commit().await()
        }

        writeAdminActionLog(
            action = if (enabled) "GLOBAL_FRAME_ENABLED" else "GLOBAL_FRAME_DISABLED",
            details = "تحديث إطار جميع العملاء ($updatedCount حساب)"
        )
        updatedCount
    }

    override suspend fun setAccountNeonStyle(targetUid: String, neonColor: String, glowIntensity: Float): Result<Unit> = runCatching {
        require(isSuperAdmin()) { "صلاحية المشرف الأعلى مطلوبة" }
        val ref = firestore.collection(Constants.COLLECTION_USERS).document(targetUid)
        val snap = ref.get().await()
        require(snap.exists()) { "الحساب غير موجود" }

        val safeGlow = glowIntensity.coerceIn(0.15f, 1.0f)
        ref.set(
            mapOf("neonColor" to neonColor.trim(), "neonGlowIntensity" to safeGlow.toDouble()),
            SetOptions.merge()
        ).await()

        writeAdminActionLog(
            action = "NEON_UPDATED",
            targetUid = targetUid,
            targetNumericId = snap.getString("numericId").orEmpty(),
            targetPublicId = snap.getString("publicId").orEmpty(),
            targetName = snap.getString("displayName").orEmpty(),
            details = "نيون: ${neonColor.ifBlank { "بدون" }} (${(safeGlow * 100).toInt()}%)"
        )
    }

    override suspend fun setAccountHiddenFromSearch(targetUid: String, hidden: Boolean, reason: String): Result<Unit> = runCatching {
        val admin = auth.currentUser ?: error("Not authenticated")
        require(isSuperAdmin()) { "صلاحية المشرف الأعلى مطلوبة" }
        val ref = firestore.collection(Constants.COLLECTION_USERS).document(targetUid)
        val snap = ref.get().await()
        require(snap.exists()) { "الحساب غير موجود" }

        val now = System.currentTimeMillis()
        ref.set(
            mapOf(
                "hiddenFromSearch" to hidden,
                "hideFromSearchReason" to reason.trim(),
                "hiddenAt" to if (hidden) now else 0L,
                "hiddenByUid" to if (hidden) admin.uid else ""
            ),
            SetOptions.merge()
        ).await()

        val opId = "hide_${targetUid}_${hidden}_${now / 5000L}"
        val numId = snap.getString("numericId").orEmpty()
        val pubId = snap.getString("publicId").orEmpty()
        val name = snap.getString("displayName").orEmpty()
        val actionName = if (hidden) "HIDDEN" else "UNHIDDEN"

        writeAdminActionLog(
            action = actionName,
            targetUid = targetUid,
            targetNumericId = numId,
            targetPublicId = pubId,
            targetName = name,
            note = reason.trim(),
            details = if (hidden) "إخفاء الحساب من البحث والاقتراحات" else "إعادة إظهار الحساب في البحث",
            operationId = opId,
            collectionName = Constants.COLLECTION_HIDDEN_LOGS
        )
        writeAdminActionLog(
            action = actionName,
            targetUid = targetUid,
            targetNumericId = numId,
            targetPublicId = pubId,
            targetName = name,
            note = reason.trim(),
            details = if (hidden) "إخفاء الحساب من البحث" else "إعادة إظهار الحساب",
            operationId = "${opId}_adm",
            collectionName = Constants.COLLECTION_ADMIN_LOGS
        )
    }

    override suspend fun banUser(
        targetUid: String,
        permanent: Boolean,
        expiresAt: Long,
        reason: String,
        blockEmailAndVerifiedPhone: Boolean
    ): Result<Unit> = runCatching {
        require(isSuperAdmin()) { "صلاحية المشرف الأعلى مطلوبة" }
        val ref = firestore.collection(Constants.COLLECTION_USERS).document(targetUid)
        val snap = ref.get().await()
        require(snap.exists()) { "الحساب غير موجود" }
        val user = snap.toWesalUser(includePrivateUnredacted = true)
        require(user.numericId != Constants.OWNER_ADMIN_NUMERIC_ID) { "لا يمكن حظر حساب المالك" }

        val now = System.currentTimeMillis()
        val banType = if (permanent) "PERMANENT" else "TEMPORARY"
        ref.set(
            mapOf(
                "isBanned" to true,
                "banType" to banType,
                "banReason" to reason.trim(),
                "banExpiresAt" to if (permanent) 0L else expiresAt,
                "hiddenFromSearch" to true
            ),
            SetOptions.merge()
        ).await()

        if (blockEmailAndVerifiedPhone) {
            if (user.email.isNotBlank()) {
                val eHash = PhoneUtils.sha256Hash(user.email.trim().lowercase())
                firestore.collection(Constants.COLLECTION_BANNED_IDENTIFIERS)
                    .document("email_$eHash")
                    .set(
                        mapOf(
                            "type" to "EMAIL",
                            "targetUid" to targetUid,
                            "permanent" to permanent,
                            "expiresAt" to if (permanent) 0L else expiresAt,
                            "reason" to reason.trim(),
                            "createdAt" to now
                        )
                    ).await()
            }
            val privSnap = runCatching { ref.collection(Constants.SUBCOLLECTION_PRIVATE).document("info").get().await() }.getOrNull()
            val realPhone = privSnap?.getString("phoneNumber").orEmpty().ifBlank { user.phoneNumber }
            if (realPhone.isNotBlank()) {
                val pHash = PhoneUtils.sha256Hash(realPhone)
                firestore.collection(Constants.COLLECTION_BANNED_IDENTIFIERS)
                    .document("phone_$pHash")
                    .set(
                        mapOf(
                            "type" to "PHONE",
                            "targetUid" to targetUid,
                            "permanent" to permanent,
                            "expiresAt" to if (permanent) 0L else expiresAt,
                            "reason" to reason.trim(),
                            "createdAt" to now
                        )
                    ).await()
            }
        }

        writeAdminActionLog(
            action = if (permanent) "BANNED_PERMANENT" else "BANNED_TEMPORARY",
            targetUid = targetUid,
            targetNumericId = user.numericId,
            targetPublicId = user.publicId,
            targetName = user.displayName,
            note = reason.trim(),
            expiresAt = expiresAt,
            details = if (permanent) "حظر نهائي + حظر المعرفات" else "حظر مؤقت"
        )
    }

    override suspend fun unbanUser(targetUid: String, note: String): Result<Unit> = runCatching {
        require(isSuperAdmin()) { "صلاحية المشرف الأعلى مطلوبة" }
        val ref = firestore.collection(Constants.COLLECTION_USERS).document(targetUid)
        val snap = ref.get().await()
        require(snap.exists()) { "الحساب غير موجود" }
        val user = snap.toWesalUser(includePrivateUnredacted = true)

        ref.set(
            mapOf(
                "isBanned" to false,
                "banType" to "",
                "banReason" to "",
                "banExpiresAt" to 0L
            ),
            SetOptions.merge()
        ).await()

        if (user.email.isNotBlank()) {
            val eHash = PhoneUtils.sha256Hash(user.email.trim().lowercase())
            runCatching {
                firestore.collection(Constants.COLLECTION_BANNED_IDENTIFIERS).document("email_$eHash").delete().await()
            }
        }
        val privSnap = runCatching { ref.collection(Constants.SUBCOLLECTION_PRIVATE).document("info").get().await() }.getOrNull()
        val realPhone = privSnap?.getString("phoneNumber").orEmpty().ifBlank { user.phoneNumber }
        if (realPhone.isNotBlank()) {
            val pHash = PhoneUtils.sha256Hash(realPhone)
            runCatching {
                firestore.collection(Constants.COLLECTION_BANNED_IDENTIFIERS).document("phone_$pHash").delete().await()
            }
        }

        writeAdminActionLog(
            action = "UNBANNED",
            targetUid = targetUid,
            targetNumericId = user.numericId,
            targetPublicId = user.publicId,
            targetName = user.displayName,
            note = note,
            details = "رفع الحظر عن الحساب"
        )
    }

    override suspend fun warnUser(targetUid: String, reason: String): Result<Unit> = runCatching {
        require(isSuperAdmin()) { "صلاحية المشرف الأعلى مطلوبة" }
        val ref = firestore.collection(Constants.COLLECTION_USERS).document(targetUid)
        val snap = ref.get().await()
        require(snap.exists()) { "الحساب غير موجود" }

        ref.update("warningsCount", FieldValue.increment(1)).await()

        val officialChatId = "${Constants.OFFICIAL_CHAT_PREFIX}$targetUid"
        val now = System.currentTimeMillis()
        val msgId = UUID.randomUUID().toString()
        val warningText = "تنبيه إداري من إدارة WESAL Messenger:\n${reason.trim()}"
        runCatching {
            val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(officialChatId)
            chatRef.set(
                mapOf(
                    "chatId" to officialChatId,
                    "isGroup" to false,
                    "isOfficialWesalChat" to true,
                    "isPinned" to true,
                    "participantIds" to listOf(targetUid),
                    "lastMessagePreview" to warningText,
                    "lastMessageTime" to now,
                    "lastMessageSenderId" to "WESAL_SYSTEM",
                    "lastMessageStatus" to MessageStatus.SENT.raw
                ),
                SetOptions.merge()
            ).await()
            chatRef.collection(Constants.COLLECTION_MESSAGES).document(msgId).set(
                mapOf(
                    "messageId" to msgId,
                    "chatId" to officialChatId,
                    "senderId" to "WESAL_SYSTEM",
                    "senderName" to "WESAL Messenger",
                    "type" to MessageType.TEXT.raw,
                    "text" to warningText,
                    "status" to MessageStatus.SENT.raw,
                    "timestamp" to now
                )
            ).await()
        }

        writeAdminActionLog(
            action = "USER_WARNED",
            targetUid = targetUid,
            targetNumericId = snap.getString("numericId").orEmpty(),
            targetPublicId = snap.getString("publicId").orEmpty(),
            targetName = snap.getString("displayName").orEmpty(),
            note = reason.trim(),
            details = "إرسال إنذار إداري رسمي"
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAdminReports(): Flow<List<AdminReportRecord>> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) flowOf(emptyList())
        else callbackFlow {
            val reg = firestore.collection(Constants.COLLECTION_REPORTS)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(150)
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    val list = snap?.documents?.map { d ->
                        AdminReportRecord(
                            reportId = d.getString("reportId") ?: d.id,
                            reporterUid = d.getString("reporterUid").orEmpty(),
                            reporterName = d.getString("reporterName").orEmpty(),
                            reportedUid = d.getString("reportedUid").orEmpty(),
                            reportedName = d.getString("reportedName").orEmpty(),
                            targetType = d.getString("targetType") ?: "USER",
                            targetChatId = d.getString("targetChatId").orEmpty(),
                            targetMessageId = d.getString("targetMessageId").orEmpty(),
                            targetContentSnapshot = d.getString("targetContentSnapshot").orEmpty(),
                            reason = d.getString("reason").orEmpty(),
                            status = d.getString("status") ?: "NEW",
                            adminNote = d.getString("adminNote").orEmpty(),
                            resolutionAction = d.getString("resolutionAction").orEmpty(),
                            reviewedByUid = d.getString("reviewedByUid").orEmpty(),
                            reviewedAt = d.getLong("reviewedAt") ?: 0L,
                            createdAt = d.getLong("createdAt") ?: 0L
                        )
                    }.orEmpty()
                    trySend(list)
                }
            awaitClose { reg.remove() }
        }
    }

    override suspend fun updateReportStatus(
        reportId: String,
        status: String,
        adminNote: String,
        resolutionAction: String
    ): Result<Unit> = runCatching {
        val admin = auth.currentUser ?: error("Not authenticated")
        require(isSuperAdmin()) { "صلاحية المشرف الأعلى مطلوبة" }
        firestore.collection(Constants.COLLECTION_REPORTS).document(reportId).set(
            mapOf(
                "status" to status,
                "adminNote" to adminNote.trim(),
                "resolutionAction" to resolutionAction.trim(),
                "reviewedByUid" to admin.uid,
                "reviewedAt" to System.currentTimeMillis()
            ),
            SetOptions.merge()
        ).await()
        writeAdminActionLog(
            action = "REPORT_$status",
            targetUid = reportId,
            note = adminNote,
            details = "معالجة بلاغ: $status ($resolutionAction)"
        )
    }

    override suspend fun adminLogReportChatInspection(reportId: String, chatId: String, targetUid: String, reason: String) {
        if (!isSuperAdmin()) return
        writeAdminActionLog(
            action = "REPORT_CHAT_INSPECTED",
            targetUid = targetUid,
            note = reason,
            details = "مراجعة بلاغ #$reportId داخل المحادثة $chatId"
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeVerificationAuditLogs(): Flow<List<VerificationAuditLog>> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) flowOf(emptyList())
        else callbackFlow {
            val reg = firestore.collection(Constants.COLLECTION_VERIFICATION_LOGS)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(200)
                .addSnapshotListener { snap, error ->
                    if (error != null) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    val logs = snap?.documents?.map { doc ->
                        VerificationAuditLog(
                            logId = doc.id,
                            operationId = doc.getString("operationId") ?: doc.id,
                            targetUid = doc.getString("targetUid").orEmpty(),
                            targetNumericId = doc.getString("targetNumericId").orEmpty(),
                            targetPublicId = doc.getString("targetPublicId").orEmpty(),
                            targetName = doc.getString("targetName").orEmpty(),
                            action = doc.getString("action") ?: "GRANTED",
                            verificationType = doc.getString("verificationType") ?: "BLUE",
                            durationLabel = doc.getString("durationLabel").orEmpty(),
                            expiresAt = doc.getLong("expiresAt") ?: 0L,
                            adminUid = doc.getString("adminUid").orEmpty(),
                            adminEmail = doc.getString("adminEmail").orEmpty(),
                            note = doc.getString("note").orEmpty(),
                            details = doc.getString("details").orEmpty(),
                            timestamp = doc.getLong("timestamp") ?: 0L
                        )
                    }.orEmpty().distinctBy { it.operationId.ifBlank { it.logId } }
                    trySend(logs)
                }
            awaitClose { reg.remove() }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeHiddenAuditLogs(): Flow<List<VerificationAuditLog>> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) flowOf(emptyList())
        else callbackFlow {
            val reg = firestore.collection(Constants.COLLECTION_HIDDEN_LOGS)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(150)
                .addSnapshotListener { snap, error ->
                    if (error != null) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    val logs = snap?.documents?.map { doc ->
                        VerificationAuditLog(
                            logId = doc.id,
                            operationId = doc.getString("operationId") ?: doc.id,
                            targetUid = doc.getString("targetUid").orEmpty(),
                            targetNumericId = doc.getString("targetNumericId").orEmpty(),
                            targetPublicId = doc.getString("targetPublicId").orEmpty(),
                            targetName = doc.getString("targetName").orEmpty(),
                            action = doc.getString("action") ?: "HIDDEN",
                            adminUid = doc.getString("adminUid").orEmpty(),
                            adminEmail = doc.getString("adminEmail").orEmpty(),
                            note = doc.getString("note").orEmpty(),
                            details = doc.getString("details").orEmpty(),
                            timestamp = doc.getLong("timestamp") ?: 0L
                        )
                    }.orEmpty()
                    trySend(logs)
                }
            awaitClose { reg.remove() }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAdminActionLogs(): Flow<List<VerificationAuditLog>> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) flowOf(emptyList())
        else callbackFlow {
            val reg = firestore.collection(Constants.COLLECTION_ADMIN_LOGS)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(200)
                .addSnapshotListener { snap, error ->
                    if (error != null) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    val logs = snap?.documents?.map { doc ->
                        VerificationAuditLog(
                            logId = doc.id,
                            operationId = doc.getString("operationId") ?: doc.id,
                            targetUid = doc.getString("targetUid").orEmpty(),
                            targetNumericId = doc.getString("targetNumericId").orEmpty(),
                            targetPublicId = doc.getString("targetPublicId").orEmpty(),
                            targetName = doc.getString("targetName").orEmpty(),
                            action = doc.getString("action") ?: "ACTION",
                            verificationType = doc.getString("verificationType") ?: "BLUE",
                            durationLabel = doc.getString("durationLabel").orEmpty(),
                            expiresAt = doc.getLong("expiresAt") ?: 0L,
                            adminUid = doc.getString("adminUid").orEmpty(),
                            adminEmail = doc.getString("adminEmail").orEmpty(),
                            note = doc.getString("note").orEmpty(),
                            details = doc.getString("details").orEmpty(),
                            timestamp = doc.getLong("timestamp") ?: 0L
                        )
                    }.orEmpty()
                    trySend(logs)
                }
            awaitClose { reg.remove() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun getWelcomeConfig(): SystemWelcomeConfig {
        val doc = runCatching {
            firestore.collection(Constants.COLLECTION_SYSTEM_CONFIG).document("welcome_message").get().await()
        }.getOrNull()
        if (doc == null || !doc.exists()) return SystemWelcomeConfig()
        return SystemWelcomeConfig(
            enabled = doc.getBoolean("enabled") ?: true,
            messageTemplate = doc.getString("messageTemplate").orEmpty()
                .ifBlank { SystemWelcomeConfig.DEFAULT_OFFICIAL_WELCOME_TEMPLATE },
            previousVersions = (doc.get("previousVersions") as? List<String>).orEmpty(),
            updatedAt = doc.getLong("updatedAt") ?: 0L,
            updatedByUid = doc.getString("updatedByUid").orEmpty()
        )
    }

    override suspend fun updateWelcomeConfig(enabled: Boolean, template: String): Result<Unit> = runCatching {
        val admin = auth.currentUser ?: error("Not authenticated")
        require(isSuperAdmin()) { "صلاحية المشرف الأعلى مطلوبة" }
        val cleanTemplate = template.trim().ifBlank { SystemWelcomeConfig.DEFAULT_OFFICIAL_WELCOME_TEMPLATE }
        val existing = getWelcomeConfig()
        val history = buildList {
            if (existing.messageTemplate.isNotBlank() && existing.messageTemplate != cleanTemplate) {
                add(existing.messageTemplate)
            }
            addAll(existing.previousVersions)
        }.distinct().take(15)

        firestore.collection(Constants.COLLECTION_SYSTEM_CONFIG)
            .document("welcome_message")
            .set(
                mapOf(
                    "enabled" to enabled,
                    "messageTemplate" to cleanTemplate,
                    "previousVersions" to history,
                    "updatedAt" to System.currentTimeMillis(),
                    "updatedByUid" to admin.uid
                ),
                SetOptions.merge()
            ).await()

        writeAdminActionLog(
            action = "WELCOME_CONFIG_UPDATED",
            details = if (enabled) "تحديث رسالة الترحيب التلقائية" else "تعطيل رسالة الترحيب"
        )
    }

    override suspend fun sendWelcomeTestToSelf(): Result<Unit> = runCatching {
        val admin = auth.currentUser ?: error("Not authenticated")
        require(isSuperAdmin()) { "صلاحية المشرف الأعلى مطلوبة" }
        val adminUser = getUserOnce(admin.uid)
        val config = getWelcomeConfig()
        val name = adminUser?.displayName?.ifBlank { "فريق وصال" } ?: "فريق وصال"
        val rendered = config.messageTemplate.replace("[اسم المستخدم]", name)
        val officialChatId = "${Constants.OFFICIAL_CHAT_PREFIX}${admin.uid}"
        val now = System.currentTimeMillis()
        val msgId = "welcome_test_$now"
        val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(officialChatId)
        chatRef.set(
            mapOf(
                "chatId" to officialChatId,
                "isGroup" to false,
                "isOfficialWesalChat" to true,
                "isPinned" to true,
                "participantIds" to listOf(admin.uid),
                "lastMessagePreview" to rendered,
                "lastMessageTime" to now,
                "lastMessageSenderId" to "WESAL_SYSTEM",
                "lastMessageStatus" to MessageStatus.SENT.raw
            ),
            SetOptions.merge()
        ).await()
        chatRef.collection(Constants.COLLECTION_MESSAGES).document(msgId).set(
            mapOf(
                "messageId" to msgId,
                "chatId" to officialChatId,
                "senderId" to "WESAL_SYSTEM",
                "senderName" to "WESAL Messenger",
                "type" to MessageType.TEXT.raw,
                "text" to rendered,
                "status" to MessageStatus.SENT.raw,
                "timestamp" to now
            )
        ).await()
    }

    override suspend fun publishOfficialAnnouncement(title: String, body: String): Result<Unit> = runCatching {
        val admin = auth.currentUser ?: error("Not authenticated")
        require(isSuperAdmin()) { "صلاحية المشرف الأعلى مطلوبة" }
        val cleanBody = body.trim()
        require(cleanBody.isNotBlank()) { "نص الإعلان مطلوب" }
        val cleanTitle = title.trim().ifBlank { "WESAL Messenger" }
        val now = System.currentTimeMillis()
        val annId = UUID.randomUUID().toString()

        firestore.collection(Constants.COLLECTION_OFFICIAL_ANNOUNCEMENTS).document(annId).set(
            mapOf(
                "announcementId" to annId,
                "title" to cleanTitle,
                "body" to cleanBody,
                "adminUid" to admin.uid,
                "createdAt" to now
            )
        ).await()

        // Broadcast into every registered user's read-only "WESAL Messenger" pinned chat
        val usersSnap = firestore.collection(Constants.COLLECTION_USERS).limit(450).get().await()
        val fullText = if (cleanTitle != "WESAL Messenger") "$cleanTitle\n\n$cleanBody" else cleanBody
        for (chunk in usersSnap.documents.chunked(180)) {
            val batch = firestore.batch()
            for (uDoc in chunk) {
                val targetUid = uDoc.id
                val chatId = "${Constants.OFFICIAL_CHAT_PREFIX}$targetUid"
                val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
                batch.set(
                    chatRef,
                    mapOf(
                        "chatId" to chatId,
                        "isGroup" to false,
                        "isOfficialWesalChat" to true,
                        "isPinned" to true,
                        "participantIds" to listOf(targetUid),
                        "lastMessagePreview" to fullText,
                        "lastMessageTime" to now,
                        "lastMessageSenderId" to "WESAL_SYSTEM",
                        "lastMessageStatus" to MessageStatus.SENT.raw
                    ),
                    SetOptions.merge()
                )
                val msgRef = chatRef.collection(Constants.COLLECTION_MESSAGES).document("ann_$annId")
                batch.set(
                    msgRef,
                    mapOf(
                        "messageId" to "ann_$annId",
                        "chatId" to chatId,
                        "senderId" to "WESAL_SYSTEM",
                        "senderName" to "WESAL Messenger",
                        "type" to MessageType.TEXT.raw,
                        "text" to fullText,
                        "status" to MessageStatus.SENT.raw,
                        "timestamp" to now
                    )
                )
            }
            batch.commit().await()
        }

        writeAdminActionLog(
            action = "ANNOUNCEMENT_PUBLISHED",
            details = "نشر إعلان رسمي عام في محادثات WESAL Messenger"
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeOfficialAnnouncements(): Flow<List<OfficialAnnouncement>> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) flowOf(emptyList())
        else callbackFlow {
            val reg = firestore.collection(Constants.COLLECTION_OFFICIAL_ANNOUNCEMENTS)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(50)
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    val list = snap?.documents?.map { d ->
                        OfficialAnnouncement(
                            announcementId = d.getString("announcementId") ?: d.id,
                            title = d.getString("title") ?: "WESAL Messenger",
                            body = d.getString("body").orEmpty(),
                            adminUid = d.getString("adminUid").orEmpty(),
                            createdAt = d.getLong("createdAt") ?: 0L
                        )
                    }.orEmpty()
                    trySend(list)
                }
            awaitClose { reg.remove() }
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
                        val rawList = snap?.documents?.map { it.toContactRequest() }
                            .orEmpty()
                            .sortedByDescending { it.updatedAt }
                        trySend(rawList)
                        if (rawList.isNotEmpty()) {
                            launch {
                                val enriched = rawList.map { req ->
                                    val peerUid = if (req.senderUid == uid) req.receiverUid else req.senderUid
                                    val peerUser = runCatching { getUserOnce(peerUid) }.getOrNull()
                                    if (peerUser != null) {
                                        if (req.senderUid == uid) {
                                            req.copy(
                                                receiverName = peerUser.displayName.ifBlank { req.receiverName },
                                                receiverAvatarUrl = peerUser.photoUrl.ifBlank { req.receiverAvatarUrl },
                                                receiverVerified = peerUser.isVerified
                                            )
                                        } else {
                                            req.copy(
                                                senderName = peerUser.displayName.ifBlank { req.senderName },
                                                senderAvatarUrl = peerUser.photoUrl.ifBlank { req.senderAvatarUrl },
                                                senderVerified = peerUser.isVerified
                                            )
                                        }
                                    } else req
                                }
                                trySend(enriched)
                            }
                        }
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
                val now = System.currentTimeMillis()
                reqRef.update(mapOf("status" to ContactRequestStatus.ACCEPTED.raw, "updatedAt" to now)).await()
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
        require(snap.exists() && snap.getString("receiverUid") == myUid) { "لا تملك صلاحية قبول هذا الطلب" }
        ref.update(mapOf("status" to ContactRequestStatus.ACCEPTED.raw, "updatedAt" to System.currentTimeMillis())).await()
    }

    override suspend fun rejectContactRequest(requestId: String): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val ref = firestore.collection(Constants.COLLECTION_CONTACT_REQUESTS).document(requestId)
        val snap = ref.get().await()
        require(snap.exists() && snap.getString("receiverUid") == myUid) { "لا تملك صلاحية رفض هذا الطلب" }
        ref.update(mapOf("status" to ContactRequestStatus.REJECTED.raw, "updatedAt" to System.currentTimeMillis())).await()
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
        if (isSuperAdmin()) return true
        val peer = getUserOnce(peerUid)
        if (peer?.numericId == Constants.OWNER_ADMIN_NUMERIC_ID) return true
        val reqId = deterministicRequestId(myUid, peerUid)
        val snap = runCatching {
            firestore.collection(Constants.COLLECTION_CONTACT_REQUESTS).document(reqId).get().await()
        }.getOrNull()
        return snap != null && snap.exists() && snap.getString("status") == ContactRequestStatus.ACCEPTED.raw
    }
}
