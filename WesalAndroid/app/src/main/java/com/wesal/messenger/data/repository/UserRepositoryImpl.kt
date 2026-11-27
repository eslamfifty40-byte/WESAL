package com.wesal.messenger.data.repository

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctions
import com.wesal.messenger.BuildConfig
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.core.util.NeonColorPreset
import com.wesal.messenger.core.util.PhoneUtils
import com.wesal.messenger.core.util.VerificationSealType
import com.wesal.messenger.core.util.WesalFrameCatalog
import com.wesal.messenger.core.util.WesalFrameLibrary
import com.wesal.messenger.core.util.WesalIdUtils
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.domain.model.*
import com.wesal.messenger.domain.repository.UserRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

/**
 * Epoch millis from a raw Firestore value of any plausible stored type; null when
 * unreadable. Mirrors ChatRepositoryImpl.coerceEpochMillis (that one is a private
 * member of its class, so it cannot be shared from here).
 */
internal fun coerceBanEpochMillis(raw: Any?): Long? = when (raw) {
    is Number -> raw.toLong()
    is Timestamp -> raw.toDate().time
    is java.util.Date -> raw.time
    is String -> raw.trim().toLongOrNull()
    else -> null
}

/**
 * Positive-ban-only, type-tolerant ban verdict (round 4, contacts M2): a ban counts
 * ONLY when the flag is set AND (the type says PERMANENT OR a readable expiry is
 * still in the future). The previous parse read `banExpiresAt` with getLong() only,
 * so a legacy doc whose expiry is missing or stored as a Timestamp/String degraded
 * to 0L = "no expiry = permanent ban" and the user silently vanished from contacts
 * (considerUserDoc drops banned users). A missing/malformed expiry never bans on
 * its own — the same semantics as firestore.rules isCurrentlyBanned() and
 * ChatRepositoryImpl.isBanClearlyActive.
 */
internal fun isBanClearlyActive(
    isBannedRaw: Any?,
    banTypeRaw: Any?,
    banExpiresAtRaw: Any?,
    nowMillis: Long
): Boolean {
    if (isBannedRaw as? Boolean != true) return false
    if ((banTypeRaw as? String).orEmpty().equals("PERMANENT", ignoreCase = true)) return true
    val expiry = coerceBanEpochMillis(banExpiresAtRaw) ?: return false
    return expiry > nowMillis
}

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
    val hideAvatar = (privacyMap["hideAvatarPhoto"] as? Boolean) ?: false
    val hideCover = (privacyMap["hideCoverPhoto"] as? Boolean) ?: false
    val recordVisits = (privacyMap["recordProfileVisits"] as? Boolean) ?: true

    val rawPhone = getString("phoneNumber").orEmpty()
    val phone = if (hidePhone && !includePrivateUnredacted) "" else rawPhone
    val rawBirth = getString("birthDate").orEmpty()
    val birthDate = if (hideBirth && !includePrivateUnredacted) "" else rawBirth
    val rawPhoto = getString("photoUrl").orEmpty()
    val photoUrl = if (hideAvatar && !includePrivateUnredacted) "" else rawPhoto
    val rawCover = getString("coverUrl").orEmpty()
    val coverUrl = if (hideCover && !includePrivateUnredacted) "" else rawCover

    val verificationExpiresAt = getLong("verificationExpiresAt") ?: 0L
    val rawVerified = getBoolean("isVerified") ?: false
    val isCurrentlyVerified = rawVerified && (verificationExpiresAt == 0L || now <= verificationExpiresAt)

    val frameExpiresAt = getLong("frameExpiresAt") ?: 0L
    val rawFrameId = getString("frameId").orEmpty()
    val numericIdRaw = getString("numericId").orEmpty()
    val isAdminAccount = numericIdRaw in Constants.ADMIN_NUMERIC_IDS
    // Round 5 (owner requirement): the OFFICIAL account (10001) is a natural
    // account like any other — a frame granted/set on it is honoured, and the
    // golden ADMIN crest is only its DEFAULT when no live frame is set (or the
    // set frame expired), so the official presence never renders unframed.
    // The secondary admin account (10002) keeps the historical always-crest rule.
    val isOfficialAccount = numericIdRaw == Constants.OWNER_ADMIN_NUMERIC_ID
    val officialFrameLive = rawFrameId.isNotBlank() && !(frameExpiresAt > 0L && now > frameExpiresAt)
    val activeFrameId = when {
        isOfficialAccount -> if (officialFrameLive) rawFrameId else WesalFrameLibrary.ADMIN_FRAME_ID
        isAdminAccount -> WesalFrameLibrary.ADMIN_FRAME_ID
        frameExpiresAt > 0L && now > frameExpiresAt -> ""
        else -> rawFrameId
    }
    val activeFrameExpiresAt = when {
        isOfficialAccount -> if (officialFrameLive) frameExpiresAt else 0L
        isAdminAccount -> 0L
        else -> frameExpiresAt
    }

    // Round 4 (contacts M2): positive-ban-only, type-tolerant parse — see
    // isBanClearlyActive. banExpiresAt keeps the coerced millis for display/admin
    // use (0L when the stored value is unreadable, which no longer implies a ban).
    val banExpiresAt = coerceBanEpochMillis(get("banExpiresAt")) ?: 0L
    val isCurrentlyBanned = isBanClearlyActive(
        get("isBanned"),
        get("banType"),
        get("banExpiresAt"),
        now
    )

    val lastHeartbeatAt = getLong("lastHeartbeatAt") ?: 0L
    val rawOnline = getBoolean("isOnline") ?: false
    val effectiveOnline = rawOnline && (lastHeartbeatAt == 0L || (now - lastHeartbeatAt) <= Constants.PRESENCE_ONLINE_TTL_MS)

    val email = getString("email").orEmpty()
    // Round 6 fix: the old fallback `?: (phone.isNotBlank() && email.isBlank())`
    // FABRICATED verification — any doc holding a number but no isPhoneVerified
    // field and no email read as OTP-verified with no code ever checked, which
    // (a) let the mandatory gate pass on the mirror path and (b) made the
    // legacy contacts fallback treat unverified numbers as verified. Absent
    // field now means exactly what it says: not verified.
    val isPhoneVerified = getBoolean("isPhoneVerified") ?: false
    // Owner-only extras (round 6): the canonical hash + legacy digits field,
    // exposed only on the unredacted self parse the gate / verify prefill use.
    val phoneHash = if (includePrivateUnredacted) getString("phoneHash").orEmpty() else ""
    val phoneDigits = if (includePrivateUnredacted) getString("phoneDigits").orEmpty() else ""

    val rawIconId = getString("iconId").orEmpty()
    val rawIconUrl = getString("iconUrl").orEmpty()
    val resolvedIconUrl = WesalFrameLibrary.resolveIconUrl(rawIconId, rawIconUrl)
    val rawNeonColor = getString("neonColor").orEmpty()
    val rawNeonEnabled = getBoolean("neonEnabled") ?: rawNeonColor.isNotBlank()

    return WesalUser(
        uid = getString("uid") ?: id,
        email = email,
        phoneNumber = phone,
        isPhoneVerified = isPhoneVerified,
        phoneHash = phoneHash,
        phoneDigits = phoneDigits,
        isVerified = isCurrentlyVerified,
        verificationType = getString("verificationType") ?: "BLUE",
        verificationGlowColor = getString("verificationGlowColor").orEmpty(),
        verificationGlowEnabled = getBoolean("verificationGlowEnabled") ?: true,
        verificationGlowIntensity = (getDouble("verificationGlowIntensity")?.toFloat()) ?: 0.7f,
        verificationExpiresAt = verificationExpiresAt,
        verifiedByUid = getString("verifiedByUid").orEmpty(),
        verifiedAt = getLong("verifiedAt") ?: 0L,
        verificationNote = getString("verificationNote").orEmpty(),
        displayName = getString("displayName").orEmpty(),
        publicId = getString("publicId").orEmpty(),
        numericId = getString("numericId").orEmpty(),
        bio = getString("bio") ?: "أستخدم تطبيق WESAL",
        photoUrl = photoUrl,
        coverUrl = coverUrl,
        birthDate = birthDate,
        frameId = activeFrameId,
        frameExpiresAt = activeFrameExpiresAt,
        grantedFrameIds = (get("grantedFrameIds") as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
        iconId = rawIconId,
        iconUrl = resolvedIconUrl,
        photoRemovedBg = getBoolean("photoRemovedBg") ?: false,
        photoUserFramed = getBoolean("photoUserFramed") ?: false,
        neonEnabled = rawNeonEnabled,
        neonColor = rawNeonColor,
        neonGlowIntensity = (getDouble("neonGlowIntensity")?.toFloat()) ?: 0.7f,
        nameplateColor = getString("nameplateColor").orEmpty().ifBlank { "CRIMSON" },
        groupAddPrivacy = GroupAddPrivacy.fromRaw(getString("groupAddPrivacy")),
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
        deletedPermanent = getBoolean("deletedPermanent") ?: false,
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
        referredByUid = getString("referredByUid").orEmpty(),
        referredByNumericId = getLong("referredByNumericId")
            ?: getString("referredByNumericId")?.toLongOrNull()
            ?: 0L,
        privacySettings = PrivacySettings(
            lastSeenVisibility = PrivacyVisibility.fromKey(privacyMap["lastSeenVisibility"] as? String),
            onlineVisibility = PrivacyVisibility.fromKey(privacyMap["onlineVisibility"] as? String),
            profilePhotoVisibility = PrivacyVisibility.fromKey(privacyMap["profilePhotoVisibility"] as? String),
            storiesVisibility = PrivacyVisibility.fromKey(privacyMap["storiesVisibility"] as? String),
            readReceiptsEnabled = (privacyMap["readReceiptsEnabled"] as? Boolean) ?: true,
            hidePhoneNumber = hidePhone,
            hideBirthDate = hideBirth,
            hideAvatarPhoto = hideAvatar,
            hideCoverPhoto = hideCover,
            recordProfileVisits = recordVisits,
            // FIX 3 (round 5): absent key (docs written before this field
            // existed) = ON, matching the PrivacySettings default.
            screenshotProtectionEnabled = (privacyMap["screenshotProtectionEnabled"] as? Boolean) ?: true
        )
    )
}

@Singleton
class UserRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
    private val supabasePhone: SupabasePhoneClient,
    private val prefs: UserPreferencesDataStore
) : UserRepository {

    // Detached scope for fire-and-forget server mirrors (e.g. §10.3 ban claims):
    // work launched here must never block or fail the Firestore write that
    // triggered it.
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Spec §10.3 (round 4): mirror a ban/unban into the target's Auth custom
     * claims via the setUserBanState callable — on ban the function stamps
     * `banned: true` (read by the rules gate) and revokes the target's refresh
     * tokens; on unban it strips the claim. STRICTLY fire-and-forget: the
     * Firestore ban write in banUser()/unbanUser() is the source of truth and
     * never waits on, nor fails because of, this call — a failure (including
     * "functions not deployed yet") is only logged.
     */
    private fun notifyBanStateChanged(targetUid: String, banned: Boolean, banType: String, banExpiresAt: Long) {
        backgroundScope.launch {
            runCatching {
                functions.getHttpsCallable("setUserBanState")
                    .call(
                        mapOf(
                            "targetUid" to targetUid,
                            "banned" to banned,
                            "banType" to banType,
                            "banExpiresAt" to banExpiresAt
                        )
                    )
                    .await()
            }.onFailure { e ->
                Log.w("UserRepositoryImpl", "setUserBanState callable failed for $targetUid (banned=$banned)", e)
            }
        }
    }

    // Spec §13.4 (round 4, P1-10): sync state for the sessions and contact-lists
    // listeners, mirroring ChatRepositoryImpl's chatListStateFlow pattern. LOADING when
    // a listener (re)registers, READY on each successful snapshot, ERROR (with the
    // listener exception's message) on error — so the sessions dialog and the
    // contact-lists section can render loading/error/retry instead of a silent empty.
    private val sessionsSyncState = MutableStateFlow(ChatListSyncState())
    private val contactListsSyncState = MutableStateFlow(ChatListSyncState())

    override fun observeSessionsSyncState(): Flow<ChatListSyncState> = sessionsSyncState

    override fun observeContactListsSyncState(): Flow<ChatListSyncState> = contactListsSyncState

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
        var privatePhotoUrl = ""
        var privateCoverUrl = ""
        var numericBackfillStarted = false
        var nameSearchKeyBackfillStarted = false

        fun emitMerged() {
            val pub = latestPublic ?: return
            val mergedPhone = pub.phoneNumber.ifBlank { privatePhone }
            val mergedVerified = pub.isPhoneVerified || privateVerified
            val mergedBirth = pub.birthDate.ifBlank { privateBirthDate }
            val mergedPhoto = pub.photoUrl.ifBlank { privatePhotoUrl }
            val mergedCover = pub.coverUrl.ifBlank { privateCoverUrl }
            trySend(
                pub.copy(
                    phoneNumber = mergedPhone,
                    isPhoneVerified = mergedVerified,
                    birthDate = mergedBirth,
                    photoUrl = mergedPhoto,
                    coverUrl = mergedCover
                )
            )
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
                    // Spec §4.4 (round 4, P2-1) lazy self-backfill: if my own doc has a
                    // display name but no nameSearchKey yet, write it once per session,
                    // fire-and-forget — it never blocks or fails this flow.
                    if (!nameSearchKeyBackfillStarted && snapshot != null && snapshot.exists()) {
                        val ownName = snapshot.getString("displayName").orEmpty()
                        val existingKey = snapshot.getString("nameSearchKey")
                        if (existingKey.isNullOrBlank() && ownName.isNotBlank()) {
                            nameSearchKeyBackfillStarted = true
                            val key = WesalIdUtils.normalizeNameSearchKey(ownName)
                            if (key.isNotBlank()) {
                                launch {
                                    runCatching {
                                        firestore.collection(Constants.COLLECTION_USERS)
                                            .document(uid)
                                            .set(mapOf("nameSearchKey" to key), SetOptions.merge())
                                            .await()
                                    }
                                }
                            }
                        }
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
                    privatePhotoUrl = snap.getString("hiddenPhotoUrl").orEmpty()
                    privateCoverUrl = snap.getString("hiddenCoverUrl").orEmpty()
                    emitMerged()
                }
            }

        awaitClose {
            pubReg.remove()
            privReg.remove()
        }
    }

    override fun observeUser(uid: String): Flow<WesalUser?> = callbackFlow {
        val isSelf = uid == auth.currentUser?.uid
        val registration = firestore.collection(Constants.COLLECTION_USERS)
            .document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(null)
                    return@addSnapshotListener
                }
                trySend(snapshot?.takeIf { it.exists() }?.toWesalUser(includePrivateUnredacted = isSelf))
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
        val isSelf = uid == auth.currentUser?.uid
        val snap = getDocCachedOrServer(uid)
        if (snap == null || !snap.exists()) return null
        val user = snap.toWesalUser(includePrivateUnredacted = isSelf)
        if (!isSelf) return user
        val privSnap = runCatching {
            firestore.collection(Constants.COLLECTION_USERS)
                .document(uid)
                .collection(Constants.SUBCOLLECTION_PRIVATE)
                .document("info")
                .get()
                .await()
        }.getOrNull() ?: return user
        if (!privSnap.exists()) return user
        return user.copy(
            phoneNumber = user.phoneNumber.ifBlank { privSnap.getString("phoneNumber").orEmpty() },
            isPhoneVerified = user.isPhoneVerified || (privSnap.getBoolean("isPhoneVerified") ?: false),
            birthDate = user.birthDate.ifBlank { privSnap.getString("birthDate").orEmpty() },
            photoUrl = user.photoUrl.ifBlank { privSnap.getString("hiddenPhotoUrl").orEmpty() },
            coverUrl = user.coverUrl.ifBlank { privSnap.getString("hiddenCoverUrl").orEmpty() }
        )
    }

    override suspend fun ensureNumericIdAssigned(): String {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val authEmail = auth.currentUser?.email.orEmpty().trim()
        val userRef = firestore.collection(Constants.COLLECTION_USERS).document(uid)

        if (Constants.SUPER_ADMIN_EMAIL.equals(authEmail, ignoreCase = true)) {
            userRef.set(mapOf("numericId" to Constants.OWNER_ADMIN_NUMERIC_ID), SetOptions.merge()).await()
            return Constants.OWNER_ADMIN_NUMERIC_ID
        }
        if (Constants.SECONDARY_ADMIN_EMAIL.equals(authEmail, ignoreCase = true)) {
            userRef.set(
                mapOf(
                    "numericId" to Constants.SECONDARY_ADMIN_NUMERIC_ID,
                    "hiddenFromSearch" to true
                ),
                SetOptions.merge()
            ).await()
            return Constants.SECONDARY_ADMIN_NUMERIC_ID
        }

        val snap = runCatching { userRef.get().await() }.getOrNull()
        val existing = snap?.getString("numericId").orEmpty()
        if (WesalIdUtils.isValidNumericId(existing)) {
            return existing
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

    /**
     * Spec §4.7 (round 4, P2-2): the PUBLIC resolvers must not leak hidden/deleted
     * accounts. This guard mirrors searchUsers' consider() filter EXACTLY for the
     * account-state checks consider() applies: a matched user is dropped when
     * hiddenFromSearch || deletedAt > 0 || deletedPermanent || isBanned.
     * (consider() additionally drops the signed-in user themself, the secondary-admin
     * account and block-list counterparts — those are caller-context checks that do
     * not belong in a resolver; the secondary admin is also hiddenFromSearch=true,
     * so it is covered here in practice.)
     */
    private fun WesalUser.takeIfPubliclyResolvable(): WesalUser? =
        takeIf { !it.hiddenFromSearch && !it.deletedPermanent && it.deletedAt <= 0L && !it.isBanned }

    override suspend fun findUserByPublicIdOrUid(idOrUid: String): WesalUser? =
        findUserByPublicIdOrUidInternal(idOrUid, guarded = true)

    override suspend fun findUserByPublicIdOrUidUnfiltered(idOrUid: String): WesalUser? =
        findUserByPublicIdOrUidInternal(idOrUid, guarded = false)

    private suspend fun findUserByPublicIdOrUidInternal(idOrUid: String, guarded: Boolean): WesalUser? {
        val raw = idOrUid.trim()
        if (raw.isBlank()) return null

        fun WesalUser?.applyGuard(): WesalUser? =
            if (guarded) this?.takeIfPubliclyResolvable() else this

        val numericQuery = WesalIdUtils.extractNumericIdQuery(raw)
        if (WesalIdUtils.isValidNumericId(numericQuery)) {
            val byNumeric = getQueryCachedOrServer(
                firestore.collection(Constants.COLLECTION_USERS)
                    .whereEqualTo("numericId", numericQuery)
                    .limit(1)
            )
            val matchedNumeric = byNumeric?.documents?.firstOrNull()
            if (matchedNumeric != null) return matchedNumeric.toWesalUser().applyGuard()
        }

        val cleanHandle = WesalIdUtils.normalize(raw)
        if (cleanHandle.isNotBlank()) {
            val byHandle = getQueryCachedOrServer(
                firestore.collection(Constants.COLLECTION_USERS)
                    .whereEqualTo("publicId", cleanHandle)
                    .limit(1)
            )
            val matchedDoc = byHandle?.documents?.firstOrNull()
            if (matchedDoc != null) return matchedDoc.toWesalUser().applyGuard()
        }
        return getUserOnce(raw).applyGuard()
    }

    override suspend fun findExactUserByNumericId(numericId: String): WesalUser? =
        findExactUserByNumericIdInternal(numericId, guarded = true)

    override suspend fun findExactUserByNumericIdUnfiltered(numericId: String): WesalUser? =
        findExactUserByNumericIdInternal(numericId, guarded = false)

    private suspend fun findExactUserByNumericIdInternal(numericId: String, guarded: Boolean): WesalUser? {
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
        val user = matched.toWesalUser()
        return if (guarded) user.takeIfPubliclyResolvable() else user
    }

    /**
     * Spec §4.4 (round 4, P2-1): one-shot backfill for the admin panel / a future
     * migration hook. Pages through the users collection with a startAfter cursor
     * (page size 200) and merge-writes `nameSearchKey` for every doc that has a
     * displayName but no key yet, stopping once [limit] docs have been written or
     * the collection is exhausted. Returns the number of docs written. Not gated
     * here by design — the admin hook that calls it gates at its own layer, and
     * firestore.rules remain the real write barrier for non-admin callers.
     */
    override suspend fun backfillNameSearchKeys(limit: Int): Result<Int> = runCatching {
        val target = limit.coerceIn(1, 5_000)
        var written = 0
        var lastDoc: DocumentSnapshot? = null
        val usersCol = firestore.collection(Constants.COLLECTION_USERS)
        while (written < target) {
            var pageQuery = usersCol.limit(200)
            if (lastDoc != null) pageQuery = pageQuery.startAfter(lastDoc!!)
            val snap = pageQuery.get().await()
            if (snap.isEmpty) break
            lastDoc = snap.documents.last()
            val missing = snap.documents.filter { doc ->
                doc.getString("nameSearchKey").isNullOrBlank() &&
                    !doc.getString("displayName").isNullOrBlank()
            }
            for (chunk in missing.chunked(380)) {
                if (written >= target) break
                val batch = firestore.batch()
                var chunkWrites = 0
                for (doc in chunk) {
                    if (written + chunkWrites >= target) break
                    val key = WesalIdUtils.normalizeNameSearchKey(doc.getString("displayName").orEmpty())
                    if (key.isBlank()) continue
                    batch.set(doc.reference, mapOf("nameSearchKey" to key), SetOptions.merge())
                    chunkWrites++
                }
                if (chunkWrites > 0) {
                    batch.commit().await()
                    written += chunkWrites
                }
            }
            if (snap.size() < 200) break
        }
        written
    }

    /**
     * Global Active User Search:
     * Searches all active, non-hidden, non-banned, non-deleted accounts by displayName,
     * @username (publicId), or numeric WESAL ID. Numeric-ID search is EXACT-only and limited
     * to the spec §4.5 range (10000..100000); name search is a Firestore prefix query
     * over the maintained nameSearchKey field with cursor pagination (round 4, P2-1).
     * Throws IllegalStateException when every lookup failed so callers can show an error
     * instead of a misleading empty result.
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
            if (user.hiddenFromSearch || user.deletedAt > 0L || user.deletedPermanent || user.isBanned) return
            if (user.numericId == Constants.SECONDARY_ADMIN_NUMERIC_ID || Constants.SECONDARY_ADMIN_EMAIL.equals(user.email, ignoreCase = true)) return
            if (meBlocked.contains(user.uid) || user.blockedUsers.contains(currentUid)) return
            results[user.uid] = user
        }

        val normalizedDigits = WesalIdUtils.normalizeDigits(trimmed)
            .removePrefix("#")
            .removePrefix("@")
            .removePrefix("ID:")
            .removePrefix("id:")
            .trim()
        // Spec §4.5: numeric-ID SEARCH is exact-only and limited to the spec range
        // 10000..100000. Prefix/contains matching (and the old "1001" special case) are gone:
        // a partial ID must never return other people's accounts. Exact resolvers used by
        // invite links/QR (findExactUserByNumericId) are untouched and keep their wider range.
        val searchNumericId = normalizedDigits.takeIf { WesalIdUtils.isSpecSearchNumericId(it) }.orEmpty()
        val cleanHandle = WesalIdUtils.normalize(trimmed)
        val anyLookupSucceeded = java.util.concurrent.atomic.AtomicBoolean(false)

        coroutineScope {
            val exactNumDef = if (searchNumericId.isNotBlank()) {
                async {
                    getQueryCachedOrServer(
                        usersCollection
                            .whereEqualTo("numericId", searchNumericId)
                            .limit(5)
                    )?.also { anyLookupSucceeded.set(true) }
                }
            } else null
            val exactHandleDef = if (cleanHandle.length >= 2) {
                async {
                    getQueryCachedOrServer(
                        usersCollection
                            .whereEqualTo("publicId", cleanHandle)
                            .limit(5)
                    )?.also { anyLookupSucceeded.set(true) }
                }
            } else null

            exactNumDef?.await()?.documents?.forEach { consider(it.toWesalUser()) }
            exactHandleDef?.await()?.documents?.forEach { consider(it.toWesalUser()) }
        }

        // Name search (spec §4.4, round 4 P2-1): Firestore prefix query over the
        // maintained `nameSearchKey` field instead of the old bounded client scan
        // (5 pages x 120 newest users — older accounts were unfindable by name).
        // The query prefix is normalized with the exact same folder the writers use
        // (WesalIdUtils.normalizeNameSearchKey), pages of 40 are walked with a
        // startAfter cursor, and every hit still passes through consider(), so the
        // hidden/deleted/banned/blocked filtering is unchanged. Docs without a
        // nameSearchKey yet are invisible to this path until the self-backfill /
        // backfillNameSearchKeys() writes one — that is the spec's design.
        // Numeric "contains" matching is intentionally NOT done here (exact-only, see above).
        if (results.size < 5 && searchNumericId.isBlank()) {
            val nameQuery = WesalIdUtils.normalizeNameSearchKey(trimmed)
            if (nameQuery.isNotBlank()) {
                var lastDoc: DocumentSnapshot? = null
                var pagesFetched = 0
                while (pagesFetched < 10 && results.size < 20) {
                    var pageQuery = usersCollection
                        .orderBy("nameSearchKey")
                        .endAt(nameQuery + "\uf8ff")
                        .limit(40)
                    pageQuery = if (lastDoc == null) {
                        pageQuery.startAt(nameQuery)
                    } else {
                        pageQuery.startAfter(lastDoc!!)
                    }
                    val snap = getQueryCachedOrServer(pageQuery) ?: break
                    anyLookupSucceeded.set(true)
                    pagesFetched++
                    val docs = snap.documents
                    if (docs.isEmpty()) break
                    lastDoc = docs.last()
                    docs.forEach { doc -> consider(doc.toWesalUser()) }
                    // Cursor walked past the prefix range: nothing more can match.
                    val lastKey = docs.last().getString("nameSearchKey").orEmpty()
                    if (!lastKey.startsWith(nameQuery)) break
                    if (docs.size < 40) break
                }
            }
            // Handle prefix search on the same pattern (replaces the scan's partial
            // publicId matching; the exact-handle lookup above is untouched).
            if (cleanHandle.length >= 2 && results.size < 20) {
                val handleSnap = getQueryCachedOrServer(
                    usersCollection
                        .orderBy("publicId")
                        .startAt(cleanHandle)
                        .endAt(cleanHandle + "\uf8ff")
                        .limit(40)
                )
                if (handleSnap != null) {
                    anyLookupSucceeded.set(true)
                    handleSnap.documents.forEach { doc -> consider(doc.toWesalUser()) }
                }
            }
        }

        // Failure must not look like "no results": if every lookup failed, surface the error.
        // Callers (ContactsViewModel / CallHistoryScreen / GroupsScreen) all runCatching this.
        if (results.isEmpty() && !anyLookupSucceeded.get()) {
            throw IllegalStateException("تعذر إجراء البحث حاليًا، تحقق من الاتصال وحاول مرة أخرى")
        }

        return results.values.sortedWith(
            compareByDescending<WesalUser> { it.numericId == normalizedDigits || (cleanHandle.isNotBlank() && it.publicId.equals(cleanHandle, ignoreCase = true)) }
                .thenByDescending { it.isVerified }
                .thenBy { it.displayName.lowercase() }
        )
    }

    /**
     * Public account directory browsing is DISABLED for normal users (Sec 25 & Sec 31).
     * Only Super Admins / active moderators receive results here.
     */
    override suspend fun listDiscoverableUsers(limit: Int): List<WesalUser> {
        if (!getCurrentAdminPermissions().hasAnyAccess()) return emptyList()
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
        val matched = java.util.concurrent.ConcurrentHashMap<String, WesalUser>()
        // Failure tracking: the cached-or-server helpers below swallow every error into null,
        // which used to make a failed lookup indistinguishable from "nobody is registered" —
        // every contact then fell into the invite section and the registered section vanished
        // with no error shown. Bounded (15s) so a stalled stream cannot hang the sync either.
        val failedPrimaryChunks = java.util.concurrent.atomic.AtomicInteger(0)
        val chunks = distinct.chunked(30)

        // [requireVerified] distinguishes the two match sources below: a
        // Supabase-registry match is proof of verification by construction
        // (rows are written only by the verification backend after an OTP
        // check), so the hydrated doc needs no stamp of its own; a legacy
        // Firestore-stamp match must additionally show isPhoneVerified on the
        // doc, because those stamps are owner-writable.
        fun considerUserDoc(hash: String, doc: DocumentSnapshot, requireVerified: Boolean) {
            if (!doc.exists() || hash.isBlank()) return
            val user = doc.toWesalUser()
            if (
                user.uid != currentUid &&
                (!requireVerified || user.isPhoneVerified) &&
                // Round 10 (item 4): hidePhoneNumber no longer excludes a user
                // from phone-hash discovery. The toggle is DISPLAY privacy —
                // round 9 removed the digits from every surface — but this
                // filter also made the account undiscoverable by the very
                // people who already hold its number in their phone book
                // (the owner's own 10002 runs with the toggle ON, so his
                // verified number could never match for his 10001 account —
                // his repeated "سجلت الأرقام وما ظهرتش" report). Hash matching
                // reveals nothing but "this contact is on WESAL", exactly
                // like WhatsApp; the number itself is never rendered.
                // hiddenFromSearch below stays: that toggle is explicitly
                // about discoverability.
                !user.hiddenFromSearch &&
                // Round 9 (item 8): the secondary-admin exclusion that used to
                // sit here made account 10002 (the owner's own second account)
                // undiscoverable by phone matching for EVERYONE — including
                // his 10001 account, whose phone book holds the number. The
                // owner's rule is discovery by verified number for all
                // accounts; only SELF is excluded (uid check above), plus the
                // privacy/ban/hidden filters. (The number itself is never
                // displayed — round-9 item 1.)
                !user.isBanned &&
                user.deletedAt == 0L
            ) {
                matched[hash] = user
            }
        }

        coroutineScope {
            chunks.map { chunk ->
                async(Dispatchers.IO) {
                    // Primary source (round 5): the Supabase verified_phones
                    // registry — the sole authority on which number belongs
                    // to which account. Registry rows map phoneHash →
                    // firebase_uid; the Firestore user doc is then read only
                    // to hydrate the profile and apply the usual privacy /
                    // ban / hidden filters above.
                    val registry = supabasePhone.findVerifiedPhonesByHashes(chunk)
                    if (registry != null) {
                        registry.values.map { row ->
                            async(Dispatchers.IO) {
                                withTimeoutOrNull(10_000L) { getDocCachedOrServer(row.firebaseUid) }
                                    ?.let { uDoc -> considerUserDoc(row.phoneHash, uDoc, requireVerified = false) }
                            }
                        }.awaitAll()
                    }

                    // Legacy fallback for hashes the registry did not match:
                    // the pre-Supabase Firestore stamps (users.phoneHash, then
                    // phone_uniqueness for accounts missing the hash on /users).
                    val missingHashes = chunk.filter { !matched.containsKey(it) }
                    var firestoreFailed = false
                    if (missingHashes.isNotEmpty()) {
                        val snap = withTimeoutOrNull(15_000L) {
                            getQueryCachedOrServer(
                                firestore.collection(Constants.COLLECTION_USERS)
                                    .whereIn("phoneHash", missingHashes)
                            )
                        }
                        if (snap == null) firestoreFailed = true
                        snap?.documents?.forEach { doc ->
                            val hash = doc.getString("phoneHash").orEmpty()
                            considerUserDoc(hash, doc, requireVerified = true)
                        }

                        val stillMissing = missingHashes.filter { !matched.containsKey(it) }
                        if (stillMissing.isNotEmpty()) {
                            val lockSnap = withTimeoutOrNull(15_000L) {
                                getQueryCachedOrServer(
                                    firestore.collection(Constants.COLLECTION_PHONE_UNIQUENESS)
                                        .whereIn(FieldPath.documentId(), stillMissing)
                                )
                            }
                            val missingTargets = lockSnap?.documents
                                ?.mapNotNull { lockDoc ->
                                    val hash = lockDoc.id
                                    val ownerUid = lockDoc.getString("uid").orEmpty()
                                    if (ownerUid.isNotBlank() && ownerUid != currentUid && !matched.containsKey(hash)) {
                                        hash to ownerUid
                                    } else null
                                }
                                .orEmpty()
                            if (missingTargets.isNotEmpty()) {
                                missingTargets.map { (hash, ownerUid) ->
                                    async(Dispatchers.IO) {
                                        withTimeoutOrNull(10_000L) { getDocCachedOrServer(ownerUid) }
                                            ?.let { uDoc -> considerUserDoc(hash, uDoc, requireVerified = true) }
                                    }
                                }.awaitAll()
                            }
                        }
                    }
                    // A chunk counts as failed only when BOTH sources failed:
                    // the registry is authoritative, so its success alone is a
                    // complete answer for the chunk (nobody registered), and a
                    // Firestore outage must not mask it.
                    if (registry == null && firestoreFailed) failedPrimaryChunks.incrementAndGet()
                }
            }.awaitAll()
        }
        if (com.wesal.messenger.BuildConfig.DEBUG) {
            // Round 9 (item 8): make lookup mismatches visible in logcat — a
            // silent empty result used to be indistinguishable from a failure.
            android.util.Log.d(
                "PhoneHashLookup",
                "requested=${distinct.size} matched=${matched.size} failedChunks=${failedPrimaryChunks.get()}/${chunks.size}"
            )
        }
        // Total lookup failure must surface as an error (ContactsViewModel shows it), not as a
        // silently empty "registered" section. Partial failures keep the matches that did load.
        if (matched.isEmpty() && failedPrimaryChunks.get() >= chunks.size && chunks.isNotEmpty()) {
            throw IllegalStateException("تعذر التحقق من جهات الاتصال المسجلة على WESAL، تحقق من الإنترنت وحاول مرة أخرى")
        }
        return matched
    }

    override fun observeUsersByPhoneHashes(phoneHashes: List<String>): Flow<Map<String, WesalUser>> =
        callbackFlow {
            val distinct = phoneHashes.filter { it.isNotBlank() }.distinct()
            if (distinct.isEmpty()) {
                trySend(emptyMap())
                awaitClose { }
                return@callbackFlow
            }
            val currentUid = auth.currentUser?.uid
            val lock = Any()
            val perChunk = HashMap<Int, Map<String, WesalUser>>()
            val registrations = distinct.chunked(30).mapIndexed { index, chunk ->
                firestore.collection(Constants.COLLECTION_USERS)
                    .whereIn("phoneHash", chunk)
                    .addSnapshotListener { snap, err ->
                        if (err != null || snap == null) return@addSnapshotListener
                        val chunkMatches = HashMap<String, WesalUser>()
                        for (doc in snap.documents) {
                            val hash = doc.getString("phoneHash").orEmpty()
                            if (hash.isBlank() || hash !in chunk) continue
                            // Raw-field acceptance (round 11, item 1 audit):
                            // the whereIn query proves the stored hash; the
                            // doc must also carry the verified flag itself.
                            if (doc.getBoolean("isPhoneVerified") != true) continue
                            val user = doc.toWesalUser()
                            if (user.uid == currentUid) continue
                            if (user.hiddenFromSearch || user.isBanned || user.deletedAt != 0L) continue
                            chunkMatches[hash] = user
                        }
                        val merged = synchronized(lock) {
                            perChunk[index] = chunkMatches
                            val all = HashMap<String, WesalUser>()
                            perChunk.values.forEach { all.putAll(it) }
                            all
                        }
                        trySend(merged)
                    }
            }
            awaitClose { registrations.forEach { runCatching { it.remove() } } }
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
                // Spec §4.4 (round 4, P2-1): keep the search key in lockstep with the name.
                "nameSearchKey" to WesalIdUtils.normalizeNameSearchKey(trimmedName),
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

            val hideAvatar = (existingPrivacy["hideAvatarPhoto"] as? Boolean) ?: false
            val hideCover = (existingPrivacy["hideCoverPhoto"] as? Boolean) ?: false

            if (photoUri != null) {
                val isCutout = withContext(Dispatchers.IO) { hasUriAlphaChannel(context, photoUri) }
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
                    updates["photoUrl"] = if (hideAvatar) "" else uploadedAvatar
                    val hasAlphaFlag = isCutout ||
                        uploadedAvatar.contains("image/png", ignoreCase = true) ||
                        uploadedAvatar.contains("image/webp", ignoreCase = true) ||
                        uploadedAvatar.contains(".png", ignoreCase = true)
                    updates["photoRemovedBg"] = hasAlphaFlag
                    // Round 9 (item 7): a photo saved through this path carries no
                    // manual-framing verdict — reset the flag so the renderer's
                    // auto-fit applies to the new photo.
                    updates["photoUserFramed"] = false
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
                    updates["coverUrl"] = if (hideCover) "" else uploadedCover
                    userRef.collection(Constants.SUBCOLLECTION_PRIVATE).document("info")
                        .set(mapOf("hiddenCoverUrl" to uploadedCover), SetOptions.merge())
                        .await()
                }
            }

            userRef.set(updates, SetOptions.merge()).await()

            val updatedSnap = if (photoUri != null || coverUri != null) {
                runCatching { userRef.get(Source.SERVER).await() }.getOrNull() ?: getDocCachedOrServer(uid)
            } else {
                getDocCachedOrServer(uid)
            }
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

    override suspend fun updateProfileImages(photoUri: Uri?, coverUri: Uri?, photoUserFramed: Boolean): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("المستخدم غير مسجل الدخول")
        require(photoUri != null || coverUri != null) { "يرجى اختيار صورة أولاً" }

        val userRef = firestore.collection(Constants.COLLECTION_USERS).document(uid)
        val privRef = userRef.collection(Constants.SUBCOLLECTION_PRIVATE).document("info")
        val updates = hashMapOf<String, Any>("uid" to uid)
        val privateUpdates = hashMapOf<String, Any>("updatedAt" to System.currentTimeMillis())

        val existingSnap = runCatching { userRef.get().await() }.getOrNull()
        @Suppress("UNCHECKED_CAST")
        val existingPrivacy = existingSnap?.get("privacySettings") as? Map<String, Any?> ?: emptyMap()
        val hideAvatar = (existingPrivacy["hideAvatarPhoto"] as? Boolean) ?: false
        val hideCover = (existingPrivacy["hideCoverPhoto"] as? Boolean) ?: false

        var expectedAvatar: String? = null
        var expectedCover: String? = null

        if (photoUri != null) {
            val hasAlphaSource = withContext(Dispatchers.IO) { hasUriAlphaChannel(context, photoUri) }
            val uploadedAvatar = withContext(Dispatchers.IO) {
                uploadProfileMediaToStorageOrSmallFallback(
                    ctx = context,
                    storagePath = "avatars/$uid/avatar_${System.currentTimeMillis()}.jpg",
                    uri = photoUri,
                    maxSide = 512,
                    quality = 84
                )
            }
            check(uploadedAvatar.isNotBlank()) { "تعذر تجهيز أو رفع الصورة الشخصية" }
            expectedAvatar = uploadedAvatar
            updates["photoUrl"] = if (hideAvatar) "" else uploadedAvatar
            val isCutoutPng = hasAlphaSource ||
                uploadedAvatar.contains("image/png", ignoreCase = true) ||
                uploadedAvatar.contains("image/webp", ignoreCase = true) ||
                uploadedAvatar.contains(".png", ignoreCase = true)
            updates["photoRemovedBg"] = isCutoutPng
            // Round 9 (item 7): stamp the crop flow's framing verdict with every
            // new avatar — true only when the user manually framed the shot in
            // the editor (composition baked into the saved square); any other
            // new photo resets it so the renderer's auto-fit applies again.
            updates["photoUserFramed"] = photoUserFramed
            privateUpdates["hiddenPhotoUrl"] = uploadedAvatar
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
            check(uploadedCover.isNotBlank()) { "تعذر تجهيز أو رفع صورة الغلاف" }
            expectedCover = uploadedCover
            updates["coverUrl"] = if (hideCover) "" else uploadedCover
            privateUpdates["hiddenCoverUrl"] = uploadedCover
        }

        userRef.set(updates, SetOptions.merge()).await()
        runCatching { privRef.set(privateUpdates, SetOptions.merge()).await() }

        // Read back from Firebase server to guarantee permanent persistence across restarts
        val serverSnap = userRef.get(Source.SERVER).await()
        check(serverSnap.exists()) { "تعذر التحقق من حفظ الصورة على الخادم" }
        if (expectedAvatar != null && !hideAvatar) {
            check(serverSnap.getString("photoUrl") == expectedAvatar) { "لم يتم تثبيت الصورة الشخصية على Firebase" }
        }
        if (expectedCover != null && !hideCover) {
            check(serverSnap.getString("coverUrl") == expectedCover) { "لم يتم تثبيت صورة الغلاف على Firebase" }
        }

        serverSnap.toWesalUser(includePrivateUnredacted = true).let { baseSaved ->
            val savedUser = baseSaved.copy(
                photoUrl = baseSaved.photoUrl.ifBlank { expectedAvatar.orEmpty() },
                coverUrl = baseSaved.coverUrl.ifBlank { expectedCover.orEmpty() }
            )
            val providerIds = auth.currentUser?.providerData?.map { it.providerId }.orEmpty()
            val provider = if (providerIds.contains("google.com")) "google" else "email"
            prefs.upsertSavedAccount(
                SavedWesalAccount(
                    uid = savedUser.uid,
                    displayName = savedUser.displayName,
                    email = savedUser.email,
                    phoneNumber = savedUser.phoneNumber,
                    publicId = savedUser.publicId,
                    numericId = savedUser.numericId,
                    photoUrl = savedUser.photoUrl,
                    provider = provider,
                    lastUsedAt = System.currentTimeMillis()
                )
            )
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

        // Phone phase (owner rule, 2026-11-27): this path only FORMAT-validates
        // the number (ProfileScreen.updateCountryPhone) — it performs no OTP
        // check, so it must not assert verification. Verification survives the
        // save only when the saved number IS the account's already-verified
        // number (private backup wins, mirroring updatePrivacySettings); any
        // other number is stored unverified with NO phoneHash, and the
        // mandatory gate re-engages to OTP-verify it before the app opens.
        val verifiedPhone = when {
            oldPriv?.getBoolean("isPhoneVerified") == true -> oldPriv.getString("phoneNumber").orEmpty()
            snap.getBoolean("isPhoneVerified") == true -> snap.getString("phoneNumber").orEmpty()
            else -> ""
        }
        val staysVerified = verifiedPhone.isNotBlank() &&
            PhoneUtils.normalizeToE164(verifiedPhone) == normalized

        privRef.set(
            mapOf("phoneNumber" to normalized, "isPhoneVerified" to staysVerified),
            SetOptions.merge()
        ).await()

        val updates = hashMapOf<String, Any>(
            "phoneNumber" to if (hidePhone) "" else normalized,
            "isPhoneVerified" to staysVerified,
            // Round 11 (addendum 2a): the hash is stamped for a verified
            // number EVEN when the number is hidden — hidePhoneNumber hides
            // the number's DISPLAY, never phonebook-hash discovery (the
            // hash only ever matches people who already hold the number).
            "phoneHash" to if (staysVerified) fullHash else ""
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
        val userRef = firestore.collection(Constants.COLLECTION_USERS).document(uid)
        userRef.update("photoUrl", "").await()
        runCatching {
            userRef.collection(Constants.SUBCOLLECTION_PRIVATE).document("info")
                .set(mapOf("hiddenPhotoUrl" to ""), SetOptions.merge())
                .await()
        }
    }

    override suspend fun removeProfileCover(): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val userRef = firestore.collection(Constants.COLLECTION_USERS).document(uid)
        userRef.update("coverUrl", "").await()
        runCatching {
            userRef.collection(Constants.SUBCOLLECTION_PRIVATE).document("info")
                .set(mapOf("hiddenCoverUrl" to ""), SetOptions.merge())
                .await()
        }
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
            val currentPhoto = snap.getString("photoUrl").orEmpty().ifBlank { privSnap?.getString("hiddenPhotoUrl").orEmpty() }
            val currentCover = snap.getString("coverUrl").orEmpty().ifBlank { privSnap?.getString("hiddenCoverUrl").orEmpty() }

            val map = hashMapOf<String, Any>(
                "privacySettings" to mapOf(
                    "lastSeenVisibility" to settings.lastSeenVisibility.key,
                    "onlineVisibility" to settings.onlineVisibility.key,
                    "profilePhotoVisibility" to settings.profilePhotoVisibility.key,
                    "storiesVisibility" to settings.storiesVisibility.key,
                    "readReceiptsEnabled" to settings.readReceiptsEnabled,
                    "hidePhoneNumber" to settings.hidePhoneNumber,
                    "hideBirthDate" to settings.hideBirthDate,
                    "hideAvatarPhoto" to settings.hideAvatarPhoto,
                    "hideCoverPhoto" to settings.hideCoverPhoto,
                    "recordProfileVisits" to settings.recordProfileVisits,
                    "screenshotProtectionEnabled" to settings.screenshotProtectionEnabled
                )
            )

            val privateBackup = hashMapOf<String, Any>()
            if (settings.hidePhoneNumber) {
                if (currentPhone.isNotBlank()) {
                    privateBackup["phoneNumber"] = currentPhone
                    privateBackup["isPhoneVerified"] = currentVerified
                }
                map["phoneNumber"] = ""
                // Round 11 (addendum 2a): phoneHash is deliberately NOT
                // blanked here anymore. The old blanking made a hidden
                // verified account undiscoverable by the very people who
                // already have its number (the proven 10001 breakage).
                // Hiding covers display only; the hash stays for discovery.
            } else if (currentPhone.isNotBlank()) {
                val norm = PhoneUtils.normalizeToE164(currentPhone) ?: currentPhone
                map["phoneNumber"] = norm
                // Phone phase (owner rule): un-hiding restores the phoneHash
                // only for a VERIFIED number — an unverified number stays
                // hash-less (undiscoverable) until the gate OTP-verifies it.
                map["phoneHash"] = if (currentVerified) PhoneUtils.sha256Hash(norm) else ""
            }

            if (settings.hideBirthDate) {
                if (currentBirth.isNotBlank()) {
                    privateBackup["birthDate"] = currentBirth
                }
                map["birthDate"] = ""
            } else if (currentBirth.isNotBlank()) {
                map["birthDate"] = currentBirth
            }

            if (settings.hideAvatarPhoto) {
                if (currentPhoto.isNotBlank()) {
                    privateBackup["hiddenPhotoUrl"] = currentPhoto
                }
                map["photoUrl"] = ""
            } else if (currentPhoto.isNotBlank()) {
                map["photoUrl"] = currentPhoto
            }

            if (settings.hideCoverPhoto) {
                if (currentCover.isNotBlank()) {
                    privateBackup["hiddenCoverUrl"] = currentCover
                }
                map["coverUrl"] = ""
            } else if (currentCover.isNotBlank()) {
                map["coverUrl"] = currentCover
            }

            if (privateBackup.isNotEmpty()) {
                privRef.set(privateBackup, SetOptions.merge()).await()
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
            sessionsSyncState.value = ChatListSyncState(ChatListSyncStatus.READY)
            flowOf(emptyList())
        } else {
            callbackFlow {
                var currentSessionId = ""
                launch { currentSessionId = prefs.getOrCreateDeviceSessionId() }

                // Spec §13.4 (round 4, P1-10): the listener error used to be ignored
                // entirely (`{ snap, _ ->`). Now it drives observeSessionsSyncState();
                // on error the flow simply keeps its last emitted value.
                sessionsSyncState.value = ChatListSyncState(ChatListSyncStatus.LOADING)
                val reg = firestore.collection(Constants.COLLECTION_USERS)
                    .document(uid)
                    .collection(Constants.COLLECTION_SESSIONS)
                    .addSnapshotListener { snap, error ->
                        if (error != null) {
                            sessionsSyncState.value = ChatListSyncState(
                                ChatListSyncStatus.ERROR,
                                error.message ?: "تعذر تحميل الجلسات"
                            )
                            return@addSnapshotListener
                        }
                        sessionsSyncState.value = ChatListSyncState(ChatListSyncStatus.READY)
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
        val userRef = firestore.collection(Constants.COLLECTION_USERS).document(uid)
        userRef.collection(Constants.COLLECTION_SESSIONS)
            .document(sessionId)
            .delete()
            .await()
        val remaining = runCatching {
            userRef.collection(Constants.COLLECTION_SESSIONS).get().await()
                .documents.count { !it.id.startsWith("link_") }
        }.getOrDefault(1)
        runCatching {
            userRef.set(mapOf("activeSessionsCount" to remaining), SetOptions.merge()).await()
        }
    }

    override suspend fun revokeAllOtherSessions(): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val currentSessionId = prefs.getOrCreateDeviceSessionId()
        val userRef = firestore.collection(Constants.COLLECTION_USERS).document(uid)
        val snap = userRef.collection(Constants.COLLECTION_SESSIONS)
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
        val remaining = runCatching {
            userRef.collection(Constants.COLLECTION_SESSIONS).get().await()
                .documents.count { !it.id.startsWith("link_") }
        }.getOrDefault(1)
        runCatching {
            userRef.set(mapOf("activeSessionsCount" to remaining), SetOptions.merge()).await()
        }
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
                "visitorIconId" to me.iconId,
                "visitorIconUrl" to me.iconUrl,
                "visitorNeonColor" to me.neonColor,
                "visitorNeonEnabled" to me.neonEnabled,
                "visitorNeonGlowIntensity" to me.neonGlowIntensity,
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
                                visitorIconId = doc.getString("visitorIconId").orEmpty(),
                                visitorIconUrl = doc.getString("visitorIconUrl").orEmpty(),
                                visitorNeonColor = doc.getString("visitorNeonColor").orEmpty(),
                                visitorNeonEnabled = doc.getBoolean("visitorNeonEnabled") ?: false,
                                visitorNeonGlowIntensity = doc.getDouble("visitorNeonGlowIntensity")?.toFloat() ?: 0.7f,
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
    // -----------------------------------------------------------------------
    // Super Admin Lock (10001 + 10002) & Delegated Moderator Permissions
    // -----------------------------------------------------------------------

    private fun DocumentSnapshot.toAdminPermissions(): AdminPermissions = AdminPermissions(
        canManageVerification = getBoolean("canManageVerification") ?: false,
        canManageFramesAndNeon = getBoolean("canManageFramesAndNeon") ?: false,
        canManageLibrary = getBoolean("canManageLibrary") ?: false,
        canManageUsersModeration = getBoolean("canManageUsersModeration") ?: false,
        canInspectPrivateData = getBoolean("canInspectPrivateData") ?: false,
        canManageReports = getBoolean("canManageReports") ?: false,
        canBroadcastAnnouncements = getBoolean("canBroadcastAnnouncements") ?: false,
        canCustomizeGlobalTheme = getBoolean("canCustomizeGlobalTheme") ?: false,
        canManageAdmins = getBoolean("canManageAdmins") ?: false,
        isFullSuperAdmin = getBoolean("isFullSuperAdmin") ?: false,
        active = getBoolean("active") ?: true,
        grantedByUid = getString("grantedByUid").orEmpty(),
        updatedAt = getLong("updatedAt") ?: 0L
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeIsSuperAdmin(): Flow<Boolean> = observeAuthUid().flatMapLatest { uid ->
        val user = auth.currentUser
        val email = user?.email.orEmpty()
        val emailOrUidMatch = uid != null && user != null && (
            Constants.isSuperAdminUid(uid) ||
                (Constants.isSuperAdminEmail(email) && user.isEmailVerified)
            )
        if (!emailOrUidMatch) {
            flowOf(false)
        } else {
            callbackFlow {
                val reg = firestore.collection(Constants.COLLECTION_SUPER_ADMINS)
                    .document(uid!!)
                    .addSnapshotListener { snap, error ->
                        if (error != null || snap == null || !snap.exists()) {
                            trySend(true)
                            return@addSnapshotListener
                        }
                        val docNumId = snap.getString("numericId").orEmpty()
                        val active = snap.getBoolean("active") != false &&
                            (docNumId.isBlank() || Constants.isProtectedAdminNumericId(docNumId))
                        trySend(active)
                    }
                awaitClose { reg.remove() }
            }
        }
    }

    override suspend fun isSuperAdmin(): Boolean {
        val user = auth.currentUser ?: return false
        val email = user.email.orEmpty()
        val allowedIdentity = Constants.isSuperAdminUid(user.uid) ||
            (Constants.isSuperAdminEmail(email) && user.isEmailVerified)
        if (!allowedIdentity) return false

        val saDoc = runCatching {
            firestore.collection(Constants.COLLECTION_SUPER_ADMINS).document(user.uid).get(Source.SERVER).await()
        }.getOrNull()
        if (saDoc != null && saDoc.exists()) {
            if (saDoc.getBoolean("active") == false) return false
            val saNumeric = saDoc.getString("numericId").orEmpty()
            if (saNumeric.isNotBlank() && !Constants.isProtectedAdminNumericId(saNumeric)) return false
        }

        val userDoc = runCatching {
            firestore.collection(Constants.COLLECTION_USERS).document(user.uid).get(Source.SERVER).await()
        }.getOrNull()
        val userNumId = userDoc?.getString("numericId").orEmpty()
        if (userNumId.isNotBlank() && !Constants.isProtectedAdminNumericId(userNumId)) return false

        return true
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeMyAdminPermissions(): Flow<AdminPermissions> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(AdminPermissions(active = false))
        } else {
            val modFlow = callbackFlow {
                val reg = firestore.collection(Constants.COLLECTION_MODERATORS)
                    .document(uid)
                    .addSnapshotListener { snap, err ->
                        if (err != null || snap == null || !snap.exists()) {
                            trySend(AdminPermissions(active = false))
                        } else {
                            trySend(snap.toAdminPermissions())
                        }
                    }
                awaitClose { reg.remove() }
            }
            combine(observeIsSuperAdmin(), modFlow) { superAdmin, modPerms ->
                if (superAdmin) AdminPermissions.fullSuperAdmin(uid) else modPerms
            }
        }
    }

    private suspend fun getCurrentAdminPermissions(): AdminPermissions {
        val uid = auth.currentUser?.uid ?: return AdminPermissions(active = false)
        if (isSuperAdmin()) return AdminPermissions.fullSuperAdmin(uid)
        val snap = runCatching {
            firestore.collection(Constants.COLLECTION_MODERATORS).document(uid).get(Source.SERVER).await()
        }.getOrNull()
            ?: runCatching { firestore.collection(Constants.COLLECTION_MODERATORS).document(uid).get().await() }.getOrNull()
        return if (snap != null && snap.exists()) snap.toAdminPermissions() else AdminPermissions(active = false)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeModerators(): Flow<List<Pair<WesalUser, AdminPermissions>>> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) flowOf(emptyList())
        else callbackFlow {
            val reg = firestore.collection(Constants.COLLECTION_MODERATORS)
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    val docs = snap?.documents.orEmpty()
                    launch {
                        val result = docs.mapNotNull { doc ->
                            val perms = doc.toAdminPermissions()
                            val user = getUserOnce(doc.id) ?: WesalUser(uid = doc.id)
                            user to perms
                        }
                        trySend(result)
                    }
                }
            awaitClose { reg.remove() }
        }
    }

    override suspend fun setModeratorPermissions(targetUid: String, permissions: AdminPermissions?): Result<Unit> = runCatching {
        val admin = auth.currentUser ?: error("Not authenticated")
        val myPerms = getCurrentAdminPermissions()
        require(myPerms.isFullSuperAdmin || myPerms.canManageAdmins) { "صلاحية إدارة المشرفين مطلوبة" }

        val targetRef = firestore.collection(Constants.COLLECTION_USERS).document(targetUid)
        val targetSnap = targetRef.get().await()
        require(targetSnap.exists()) { "الحساب غير موجود" }
        val targetUser = targetSnap.toWesalUser(includePrivateUnredacted = true)
        require(!Constants.isProtectedAdminNumericId(targetUser.numericId) && !Constants.isSuperAdminEmail(targetUser.email)) {
            "لا يمكن تعديل صلاحيات حسابات الإدارة المحمية"
        }

        val modRef = firestore.collection(Constants.COLLECTION_MODERATORS).document(targetUid)
        if (permissions == null) {
            modRef.delete().await()
            writeAdminActionLog(
                action = "MODERATOR_REVOKED",
                targetUid = targetUid,
                targetNumericId = targetUser.numericId,
                targetPublicId = targetUser.publicId,
                targetName = targetUser.displayName,
                details = "سحب صلاحيات المشرف المساعد"
            )
        } else {
            val data = mapOf(
                "uid" to targetUid,
                "canManageVerification" to permissions.canManageVerification,
                "canManageFramesAndNeon" to permissions.canManageFramesAndNeon,
                "canManageLibrary" to permissions.canManageLibrary,
                "canManageUsersModeration" to permissions.canManageUsersModeration,
                "canInspectPrivateData" to permissions.canInspectPrivateData,
                "canManageReports" to permissions.canManageReports,
                "canBroadcastAnnouncements" to permissions.canBroadcastAnnouncements,
                "canCustomizeGlobalTheme" to permissions.canCustomizeGlobalTheme,
                "canManageAdmins" to permissions.canManageAdmins,
                "isFullSuperAdmin" to false,
                "active" to permissions.active,
                "grantedByUid" to admin.uid,
                "updatedAt" to System.currentTimeMillis()
            )
            modRef.set(data, SetOptions.merge()).await()
            writeAdminActionLog(
                action = "MODERATOR_UPDATED",
                targetUid = targetUid,
                targetNumericId = targetUser.numericId,
                targetPublicId = targetUser.publicId,
                targetName = targetUser.displayName,
                details = "تحديث صلاحيات المشرف المساعد"
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeGlobalAppearance(): Flow<GlobalAppearanceConfig> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) flowOf(GlobalAppearanceConfig())
        else callbackFlow {
            val reg = firestore.collection(Constants.COLLECTION_SYSTEM_CONFIG)
                .document("global_appearance")
                .addSnapshotListener { snap, err ->
                    if (err != null || snap == null || !snap.exists()) {
                        trySend(GlobalAppearanceConfig())
                        return@addSnapshotListener
                    }
                    trySend(
                        GlobalAppearanceConfig(
                            enabled = snap.getBoolean("enabled") ?: false,
                            emeraldHex = snap.getLong("emeraldHex") ?: 0xFF10B981L,
                            turquoiseHex = snap.getLong("turquoiseHex") ?: 0xFF00D9E8L,
                            backgroundHex = snap.getLong("backgroundHex") ?: 0L,
                            surfaceHex = snap.getLong("surfaceHex") ?: 0L,
                            textPrimaryHex = snap.getLong("textPrimaryHex") ?: 0L,
                            iconTintHex = snap.getLong("iconTintHex") ?: 0L,
                            iconPack = snap.getString("iconPack").orEmpty().ifBlank { "DEFAULT" },
                            globalCustomerFrameEnabled = snap.getBoolean("globalCustomerFrameEnabled") ?: true,
                            defaultCustomerFrameId = snap.getString("defaultFrameId").orEmpty()
                                .ifBlank { snap.getString("defaultCustomerFrameId").orEmpty() }
                                .ifBlank { WesalFrameCatalog.DEFAULT_ALL_CUSTOMERS_FRAME_ID },
                            defaultCustomerIconId = snap.getString("defaultCustomerIconId").orEmpty(),
                            defaultCustomerIconUrl = snap.getString("defaultCustomerIconUrl").orEmpty(),
                            updatedAt = snap.getLong("updatedAt") ?: 0L,
                            updatedByUid = snap.getString("updatedByUid").orEmpty()
                        )
                    )
                }
            awaitClose { reg.remove() }
        }
    }

    override suspend fun updateGlobalAppearance(config: GlobalAppearanceConfig): Result<Unit> = runCatching {
        val admin = auth.currentUser ?: error("Not authenticated")
        val perms = getCurrentAdminPermissions()
        require(perms.isFullSuperAdmin || perms.canCustomizeGlobalTheme) { "صلاحية تخصيص المظهر العام مطلوبة" }
        val now = System.currentTimeMillis()
        // IMPORTANT (round-2 fix): the frame/icon default keys (globalCustomerFrameEnabled,
        // defaultFrameId, defaultCustomerFrameId, defaultCustomerIconId/Url) are deliberately
        // NOT written here. This theme save used to copy them from the passed config object —
        // whose frame fields can be stale or factory defaults (e.g. the section's "reset"
        // button builds a fresh GlobalAppearanceConfig) — silently changing the stored
        // default WITHOUT running the user batch. That corrupted setGlobalCustomerDefaults'
        // prevFrameId/prevIconId tracking: users still wearing the older default no longer
        // matched "previous default" and were skipped by every later apply. Frame/icon
        // defaults are owned exclusively by setGlobalCustomerDefaults /
        // setGlobalCustomerFrameEnabled; merge leaves the stored values untouched here.
        firestore.collection(Constants.COLLECTION_SYSTEM_CONFIG)
            .document("global_appearance")
            .set(
                mapOf(
                    "enabled" to config.enabled,
                    "emeraldHex" to config.emeraldHex,
                    "turquoiseHex" to config.turquoiseHex,
                    "backgroundHex" to config.backgroundHex,
                    "surfaceHex" to config.surfaceHex,
                    "textPrimaryHex" to config.textPrimaryHex,
                    "iconTintHex" to config.iconTintHex,
                    "iconPack" to config.iconPack.ifBlank { "DEFAULT" },
                    "updatedAt" to now,
                    "updatedByUid" to admin.uid
                ),
                SetOptions.merge()
            ).await()
        writeAdminActionLog(
            action = if (config.enabled) "GLOBAL_THEME_UPDATED" else "GLOBAL_THEME_RESET",
            details = if (config.enabled) "تحديث ألوان ومظهر التطبيق العام" else "استعادة ألوان WESAL الافتراضية"
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAppUpdateConfig(): Flow<AppUpdateConfig> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) flowOf(AppUpdateConfig())
        else callbackFlow {
            val reg = firestore.collection(Constants.COLLECTION_SYSTEM_CONFIG)
                .document("app_update")
                .addSnapshotListener { snap, err ->
                    // Fail-open by design (same contract as observeGlobalAppearance): a
                    // missing doc, a listener error or a malformed field must NEVER
                    // produce a blocking update dialog on users' devices.
                    if (err != null || snap == null || !snap.exists()) {
                        trySend(AppUpdateConfig())
                        return@addSnapshotListener
                    }
                    trySend(
                        AppUpdateConfig(
                            enabled = snap.getBoolean("enabled") ?: false,
                            latestVersionCode = (snap.getLong("latestVersionCode") ?: 0L).toInt(),
                            minSupportedVersionCode = (snap.getLong("minSupportedVersionCode") ?: 0L).toInt(),
                            apkUrl = snap.getString("apkUrl").orEmpty(),
                            messageAr = snap.getString("messageAr").orEmpty(),
                            messageEn = snap.getString("messageEn").orEmpty(),
                            mandatory = snap.getBoolean("mandatory") ?: false,
                            updatedAt = snap.getLong("updatedAt") ?: 0L,
                            updatedByUid = snap.getString("updatedByUid").orEmpty()
                        )
                    )
                }
            awaitClose { reg.remove() }
        }
    }

    override suspend fun updateAppUpdateConfig(config: AppUpdateConfig): Result<Unit> = runCatching {
        val admin = auth.currentUser ?: error("Not authenticated")
        val perms = getCurrentAdminPermissions()
        require(perms.isFullSuperAdmin || perms.canCustomizeGlobalTheme) { "صلاحية إدارة تحديث التطبيق مطلوبة" }
        val now = System.currentTimeMillis()
        firestore.collection(Constants.COLLECTION_SYSTEM_CONFIG)
            .document("app_update")
            .set(
                mapOf(
                    "enabled" to config.enabled,
                    "latestVersionCode" to config.latestVersionCode,
                    "minSupportedVersionCode" to config.minSupportedVersionCode,
                    "apkUrl" to config.apkUrl.trim(),
                    "messageAr" to config.messageAr.trim(),
                    "messageEn" to config.messageEn.trim(),
                    "mandatory" to config.mandatory,
                    "updatedAt" to now,
                    "updatedByUid" to admin.uid
                ),
                SetOptions.merge()
            ).await()
        writeAdminActionLog(
            action = "APP_UPDATE_CONFIG_UPDATED",
            details = "تحديث سياسة تحديث التطبيق: مفعّل=${config.enabled}، أحدث نسخة=${config.latestVersionCode}، حد أدنى مدعوم=${config.minSupportedVersionCode}، إجباري=${config.mandatory}"
        )
    }

    private fun DocumentSnapshot.toSubscriptionRequest(): SubscriptionRequest = SubscriptionRequest(
        requestId = getString("requestId") ?: id,
        userUid = getString("userUid").orEmpty(),
        userName = getString("userName").orEmpty(),
        userNumericId = getString("userNumericId").orEmpty(),
        userPublicId = getString("userPublicId").orEmpty(),
        requestType = getString("requestType") ?: "VERIFICATION_BLUE",
        desiredSeal = getString("desiredSeal") ?: "BLUE",
        desiredFrameId = getString("desiredFrameId").orEmpty(),
        desiredGlowColor = getString("desiredGlowColor").orEmpty(),
        idDocumentUrl = getString("idDocumentUrl").orEmpty(),
        note = getString("note").orEmpty(),
        status = getString("status") ?: "PENDING",
        adminResponse = getString("adminResponse").orEmpty(),
        reviewedByUid = getString("reviewedByUid").orEmpty(),
        reviewedAt = getLong("reviewedAt") ?: 0L,
        createdAt = getLong("createdAt") ?: 0L
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeSubscriptionRequests(): Flow<List<SubscriptionRequest>> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) flowOf(emptyList())
        else callbackFlow {
            val reg = firestore.collection(Constants.COLLECTION_SUBSCRIPTION_REQUESTS)
                .limit(250)
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    val sorted = snap?.documents
                        ?.map { it.toSubscriptionRequest() }
                        .orEmpty()
                        .sortedByDescending { it.createdAt }
                    trySend(sorted)
                }
            awaitClose { reg.remove() }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeMySubscriptionRequests(): Flow<List<SubscriptionRequest>> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) flowOf(emptyList())
        else callbackFlow {
            val reg = firestore.collection(Constants.COLLECTION_SUBSCRIPTION_REQUESTS)
                .whereEqualTo("userUid", uid)
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    val sorted = snap?.documents?.map { it.toSubscriptionRequest() }.orEmpty()
                        .sortedByDescending { it.createdAt }
                    trySend(sorted)
                }
            awaitClose { reg.remove() }
        }
    }

    override suspend fun submitSubscriptionRequest(
        requestType: String,
        desiredSeal: String,
        idUri: Uri,
        note: String,
        desiredFrameId: String,
        desiredGlowColor: String
    ): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val me = getUserOnce(uid) ?: WesalUser(uid = uid)
        val reqId = "sub_${uid}_${System.currentTimeMillis()}"
        val validSeal = VerificationSealType.fromKey(desiredSeal).key
        val validGlow = NeonColorPreset.fromKey(desiredGlowColor).key
        val cleanFrame = desiredFrameId.trim()
        val idDocUrl = withContext(Dispatchers.IO) {
            uploadProfileMediaToStorageOrSmallFallback(
                ctx = context,
                storagePath = "subscription_ids/$uid/$reqId.jpg",
                uri = idUri,
                maxSide = 960,
                quality = 82
            )
        }
        check(idDocUrl.isNotBlank()) { "تعذر رفع صورة الهوية" }
        val now = System.currentTimeMillis()
        firestore.collection(Constants.COLLECTION_SUBSCRIPTION_REQUESTS).document(reqId).set(
            mapOf(
                "requestId" to reqId,
                "userUid" to uid,
                "userName" to me.displayName.ifBlank { "@${me.publicId}" },
                "userNumericId" to me.numericId,
                "userPublicId" to me.publicId,
                "requestType" to requestType.ifBlank { "VERIFICATION_$validSeal" },
                "desiredSeal" to validSeal,
                "desiredFrameId" to cleanFrame,
                "desiredGlowColor" to validGlow,
                "idDocumentUrl" to idDocUrl,
                "note" to note.trim(),
                "status" to "PENDING",
                "adminResponse" to "",
                "reviewedByUid" to "",
                "reviewedAt" to 0L,
                "createdAt" to now
            )
        ).await()
    }

    override suspend fun reviewSubscriptionRequest(
        requestId: String,
        approved: Boolean,
        adminNote: String
    ): Result<Unit> = runCatching {
        val admin = auth.currentUser ?: error("Not authenticated")
        val perms = getCurrentAdminPermissions()
        require(perms.isFullSuperAdmin || perms.canManageVerification) { "صلاحية مراجعة طلبات التوثيق مطلوبة" }

        val reqRef = firestore.collection(Constants.COLLECTION_SUBSCRIPTION_REQUESTS).document(requestId)
        val snap = reqRef.get().await()
        require(snap.exists()) { "الطلب غير موجود" }
        val req = snap.toSubscriptionRequest()

        val newStatus = if (approved) "APPROVED" else "REJECTED"
        val now = System.currentTimeMillis()
        reqRef.set(
            mapOf(
                "status" to newStatus,
                "adminResponse" to adminNote.trim(),
                "reviewedByUid" to admin.uid,
                "reviewedAt" to now
            ),
            SetOptions.merge()
        ).await()

        if (approved && req.userUid.isNotBlank()) {
            setAccountVerification(
                targetUid = req.userUid,
                verified = true,
                note = adminNote.ifBlank { "تمت الموافقة على طلب التوثيق" },
                verificationType = req.desiredSeal,
                expiresAt = 0L,
                durationLabel = "دائم",
                glowEnabled = true,
                glowIntensity = 0.78f,
                verificationGlowColor = req.desiredGlowColor
            ).getOrThrow()
            if (req.desiredFrameId.isNotBlank()) {
                runCatching {
                    setAccountFrame(
                        targetUid = req.userUid,
                        frameId = req.desiredFrameId,
                        expiresAt = 0L,
                        durationLabel = "دائم"
                    )
                }
            }
        }

        writeAdminActionLog(
            action = if (approved) "SUBSCRIPTION_APPROVED" else "SUBSCRIPTION_REJECTED",
            targetUid = req.userUid,
            targetNumericId = req.userNumericId,
            targetPublicId = req.userPublicId,
            targetName = req.userName,
            note = adminNote.trim(),
            details = if (approved) {
                buildString {
                    append("الموافقة على طلب توثيق (${req.desiredSeal})")
                    if (req.desiredGlowColor.isNotBlank()) append(" • هالة: ${req.desiredGlowColor}")
                    if (req.desiredFrameId.isNotBlank()) append(" • إطار: ${req.desiredFrameId}")
                }
            } else "رفض طلب توثيق"
        )
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
        if (!getCurrentAdminPermissions().hasAnyAccess()) return emptyList()
        val snap = runCatching {
            firestore.collection(Constants.COLLECTION_USERS)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit.toLong().coerceIn(20L, 400L))
                .get()
                .await()
        }.getOrNull() ?: return emptyList()
        val byUid = linkedMapOf<String, WesalUser>()
        // Always ensure Super Admin 10001 appears at the top of the list even if older than the limit window
        runCatching {
            firestore.collection(Constants.COLLECTION_USERS)
                .whereEqualTo("numericId", Constants.OWNER_ADMIN_NUMERIC_ID)
                .limit(2)
                .get()
                .await()
        }.getOrNull()?.documents?.forEach { doc ->
            val u = doc.toWesalUser(includePrivateUnredacted = true)
            byUid[u.uid] = u
        }
        snap.documents.forEach { doc ->
            val u = doc.toWesalUser(includePrivateUnredacted = true)
            byUid.putIfAbsent(u.uid, u)
        }
        return byUid.values.toList()
    }

    override suspend fun adminSearchUsers(query: String): List<WesalUser> {
        if (!getCurrentAdminPermissions().hasAnyAccess()) return emptyList()
        val q = query.trim()
        if (q.isBlank()) return getAdminUsersList(120)
        val usersCol = firestore.collection(Constants.COLLECTION_USERS)
        val results = linkedMapOf<String, WesalUser>()

        // 1. Direct UID
        runCatching { usersCol.document(q).get().await() }.getOrNull()?.takeIf { it.exists() }?.let {
            val u = it.toWesalUser(includePrivateUnredacted = true)
            results[u.uid] = u
        }

        // 2. Numeric WESAL ID (10000..1000000 + 1001)
        val numQ = WesalIdUtils.extractSearchNumericIdQuery(q)
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

        // 4. Local pool filter for partial name matches.
        // Round 4 (P2-11): the pool used to be a single limit(250) fetch, so accounts
        // beyond the first 250 were invisible to admin search. It is now walked with
        // limit + startAfter cursor pages of 250 (safety cap 40 pages = 10,000 docs)
        // applying the exact same per-doc filter, so the returned data only grows.
        var poolLastDoc: DocumentSnapshot? = null
        var poolPages = 0
        while (poolPages < 40) {
            var poolQuery = usersCol.limit(250)
            if (poolLastDoc != null) poolQuery = poolQuery.startAfter(poolLastDoc!!)
            val pageSnap = getQueryCachedOrServer(poolQuery) ?: break
            poolPages++
            if (pageSnap.isEmpty) break
            poolLastDoc = pageSnap.documents.last()
            pageSnap.documents.forEach { doc ->
                val u = doc.toWesalUser(includePrivateUnredacted = true)
                if (u.displayName.contains(q, ignoreCase = true) ||
                    u.publicId.contains(handle, ignoreCase = true) ||
                    u.numericId.contains(q, ignoreCase = true) ||
                    u.email.contains(q, ignoreCase = true)
                ) {
                    results[u.uid] = u
                }
            }
            if (pageSnap.size() < 250) break
        }
        return results.values.toList()
    }

    override suspend fun adminGetUserFullDetails(targetUid: String, reason: String): Result<WesalUser> = runCatching {
        val perms = getCurrentAdminPermissions()
        require(perms.isFullSuperAdmin || perms.canInspectPrivateData || perms.canManageUsersModeration || perms.canManageReports) {
            "صلاحية معاينة بيانات المستخدم مطلوبة"
        }
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
        if (!getCurrentAdminPermissions().hasAnyAccess()) return AdminDashboardStats()
        // Round 4 (P2-11): the stats used to be computed over a single limit(500)
        // fetch, silently capping every count at the first 500 accounts. The users
        // collection is now walked in limit(500) + startAfter cursor pages and the
        // exact same derived counting below runs over the full set. A Firestore
        // aggregate count() query is supported by the pinned SDK (Firebase BoM
        // 33.6.0 → firebase-firestore 25.x, well past the 24.4 / BoM 31.2 floor)
        // but was deliberately NOT used here: most dashboard numbers are DERIVED
        // (online = heartbeat-TTL effective presence, banned = positive-ban-only
        // verdict, verified = expiry-aware), which a raw aggregate count over
        // stored fields cannot reproduce — pagination preserves the exact numbers.
        val users = mutableListOf<WesalUser>()
        var statsLastDoc: DocumentSnapshot? = null
        while (true) {
            var pageQuery = firestore.collection(Constants.COLLECTION_USERS).limit(500)
            if (statsLastDoc != null) pageQuery = pageQuery.startAfter(statsLastDoc!!)
            val pageSnap = runCatching { pageQuery.get().await() }.getOrNull()
            if (pageSnap == null) {
                if (users.isEmpty()) return AdminDashboardStats()
                break
            }
            if (pageSnap.isEmpty) break
            statsLastDoc = pageSnap.documents.last()
            users += pageSnap.documents.map { it.toWesalUser(includePrivateUnredacted = true) }
            if (pageSnap.size() < 500) break
        }

        val now = System.currentTimeMillis()
        val dayMs = 24L * 60L * 60L * 1000L

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
            deletedCount = users.count { it.deletedAt > 0L || it.deletedPermanent },
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
        durationLabel: String,
        glowEnabled: Boolean,
        glowIntensity: Float,
        verificationGlowColor: String
    ): Result<Unit> = runCatching {
        val admin = auth.currentUser ?: error("Not authenticated")
        val perms = getCurrentAdminPermissions()
        require(perms.isFullSuperAdmin || perms.canManageVerification) { "صلاحية إدارة التوثيق غير مفعلة لهذا الحساب" }

        val now = System.currentTimeMillis()
        val targetRef = firestore.collection(Constants.COLLECTION_USERS).document(targetUid)
        val targetSnap = targetRef.get().await()
        require(targetSnap.exists()) { "الحساب غير موجود" }
        val targetUser = targetSnap.toWesalUser(includePrivateUnredacted = true)
        if (!perms.isFullSuperAdmin) {
            require(!Constants.isProtectedAdminNumericId(targetUser.numericId) && !Constants.isSuperAdminEmail(targetUser.email)) {
                "لا يمكن تعديل حساب الإدارة الأساسي إلا من المشرف الأعلى"
            }
        }

        val safeType = VerificationSealType.fromKey(verificationType).key
        val safeGlowColor = NeonColorPreset.fromKey(verificationGlowColor).key
        val actionName = if (verified) "GRANTED" else "REVOKED"
        // Operation ID bucketed gently to prevent accidental duplicate write from rapid double-tap retries
        val opBucket = now / 5000L
        val operationId = "ver_${targetUid}_${actionName}_${safeType}_${safeGlowColor}_${expiresAt}_$opBucket"

        val updates = hashMapOf<String, Any>(
            "isVerified" to verified,
            "verificationType" to safeType,
            "verificationGlowColor" to if (verified) safeGlowColor else "",
            "verificationGlowEnabled" to glowEnabled,
            "verificationGlowIntensity" to glowIntensity.coerceIn(0.15f, 1.0f).toDouble(),
            "verificationExpiresAt" to if (verified) expiresAt else 0L,
            "verifiedByUid" to admin.uid,
            "verifiedAt" to now,
            "verificationNote" to note.trim()
        )
        targetRef.set(updates, SetOptions.merge()).await()

        val targetNum = targetUser.numericId
        val targetPub = targetUser.publicId
        val targetName = targetUser.displayName
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
            "details" to if (verified) {
                buildString {
                    append("توثيق الحساب ($safeType) - ${durationLabel.ifBlank { "دائم" }}")
                    if (safeGlowColor.isNotBlank()) append(" • وهج: $safeGlowColor")
                }
            } else "إلغاء التوثيق",
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
        val perms = getCurrentAdminPermissions()
        require(perms.isFullSuperAdmin || perms.canManageFramesAndNeon) { "صلاحية إدارة الإطارات والمظهر مطلوبة" }
        val targetRef = firestore.collection(Constants.COLLECTION_USERS).document(targetUid)
        val snap = targetRef.get().await()
        require(snap.exists()) { "الحساب غير موجود" }
        val targetUser = snap.toWesalUser(includePrivateUnredacted = true)
        if (!perms.isFullSuperAdmin) {
            require(!Constants.isProtectedAdminNumericId(targetUser.numericId) && !Constants.isSuperAdminEmail(targetUser.email)) {
                "لا يمكن تعديل إطار الإدارة إلا من المشرف الأعلى"
            }
        }

        val framePatch = mutableMapOf<String, Any>(
            "frameId" to frameId.trim(),
            "frameExpiresAt" to if (frameId.isBlank()) 0L else expiresAt
        )
        // Remember every granted frame on the user doc: the profile picker may later
        // re-apply any of them via setOwnFrame, and firestore.rules checks the same list.
        if (frameId.isNotBlank()) {
            framePatch["grantedFrameIds"] = FieldValue.arrayUnion(frameId.trim())
        }
        targetRef.set(framePatch, SetOptions.merge()).await()

        val opId = "frame_${targetUid}_${frameId}_${expiresAt}_${System.currentTimeMillis() / 5000L}"
        writeAdminActionLog(
            action = if (frameId.isBlank()) "FRAME_REMOVED" else "FRAME_GRANTED",
            targetUid = targetUid,
            targetNumericId = targetUser.numericId,
            targetPublicId = targetUser.publicId,
            targetName = targetUser.displayName,
            durationLabel = durationLabel,
            expiresAt = expiresAt,
            details = if (frameId.isBlank()) "إزالة الإطار الشخصي" else "إهداء إطار: $frameId ($durationLabel)",
            operationId = opId,
            collectionName = Constants.COLLECTION_VERIFICATION_LOGS
        )
    }

    override suspend fun setOwnFrame(frameId: String): Result<Unit> = runCatching {
        val me = auth.currentUser ?: error("Not authenticated")
        val clean = frameId.trim()
        val myRef = firestore.collection(Constants.COLLECTION_USERS).document(me.uid)
        if (clean.isNotBlank()) {
            // Self-pick is limited to frames an admin already granted this account
            // (grantedFrameIds on the user doc). Admin callers are exempt. The same
            // rule is enforced server-side in firestore.rules, so a modified client
            // cannot wear an ungranted frame either.
            val perms = getCurrentAdminPermissions()
            val callerIsAdmin = perms.isFullSuperAdmin || perms.hasAnyAccess()
            if (!callerIsAdmin) {
                val snap = myRef.get().await()
                require(snap.exists()) { "الحساب غير موجود" }
                val isAdminAccount = snap.getString("numericId").orEmpty() in Constants.ADMIN_NUMERIC_IDS
                val granted = (snap.get("grantedFrameIds") as? List<*>)
                    ?.mapNotNull { it as? String } ?: emptyList()
                // Re-applying the frame already on the doc is always allowed (covers
                // grants made before grantedFrameIds existed — nothing is lost).
                val currentFrame = snap.getString("frameId").orEmpty()
                require(isAdminAccount || clean in granted || clean == currentFrame) { "هذا الإطار غير ممنوح لحسابك" }
            }
        }
        myRef.set(
            mapOf(
                "frameId" to clean,
                "frameExpiresAt" to 0L
            ),
            SetOptions.merge()
        ).await()
    }

    override suspend fun setAccountIcon(targetUid: String, iconId: String, iconUrl: String): Result<Unit> = runCatching {
        val perms = getCurrentAdminPermissions()
        require(perms.isFullSuperAdmin || perms.canManageFramesAndNeon) { "صلاحية إدارة الأيقونات مطلوبة" }
        val targetRef = firestore.collection(Constants.COLLECTION_USERS).document(targetUid)
        val snap = targetRef.get().await()
        require(snap.exists()) { "الحساب غير موجود" }
        val targetUser = snap.toWesalUser(includePrivateUnredacted = true)
        if (!perms.isFullSuperAdmin) {
            require(!Constants.isProtectedAdminNumericId(targetUser.numericId) && !Constants.isSuperAdminEmail(targetUser.email)) {
                "لا يمكن تعديل أيقونة الإدارة إلا من المشرف الأعلى"
            }
        }

        val cleanId = iconId.trim()
        val resolvedUrl = if (cleanId.isBlank()) "" else iconUrl.trim().ifBlank { WesalFrameLibrary.resolveIconUrl(cleanId) }
        targetRef.set(
            mapOf(
                "iconId" to cleanId,
                "iconUrl" to resolvedUrl
            ),
            SetOptions.merge()
        ).await()

        writeAdminActionLog(
            action = if (cleanId.isBlank()) "ICON_REMOVED" else "ICON_GRANTED",
            targetUid = targetUid,
            targetNumericId = targetUser.numericId,
            targetPublicId = targetUser.publicId,
            targetName = targetUser.displayName,
            details = if (cleanId.isBlank()) "إزالة أيقونة الحساب" else "تعيين أيقونة الحساب: $cleanId"
        )
    }

    override suspend fun setAccountNameplateColor(targetUid: String, colorKey: String): Result<Unit> = runCatching {
        // Owner rule (2026-11-27): nameplate colours are granted ONLY from the admin panel
        // (admins 10001/10002 or a delegated admin holding canManageFramesAndNeon) — the
        // self-service card was removed from ProfileScreen and updateSelfCustomization()
        // refuses nameplate/icon changes for non-owner accounts.
        val perms = getCurrentAdminPermissions()
        require(perms.isFullSuperAdmin || perms.canManageFramesAndNeon) { "صلاحية إدارة الإطارات والمظهر مطلوبة" }
        val targetRef = firestore.collection(Constants.COLLECTION_USERS).document(targetUid)
        val snap = targetRef.get().await()
        require(snap.exists()) { "الحساب غير موجود" }
        val targetUser = snap.toWesalUser(includePrivateUnredacted = true)
        if (!perms.isFullSuperAdmin) {
            require(!Constants.isProtectedAdminNumericId(targetUser.numericId) && !Constants.isSuperAdminEmail(targetUser.email)) {
                "لا يمكن تعديل مظهر الإدارة إلا من المشرف الأعلى"
            }
        }

        val cleanKey = colorKey.trim().uppercase()
        targetRef.set(
            mapOf("nameplateColor" to cleanKey),
            SetOptions.merge()
        ).await()

        writeAdminActionLog(
            action = if (cleanKey.isBlank()) "NAMEPLATE_COLOR_RESET" else "NAMEPLATE_COLOR_GRANTED",
            targetUid = targetUid,
            targetNumericId = targetUser.numericId,
            targetPublicId = targetUser.publicId,
            targetName = targetUser.displayName,
            details = if (cleanKey.isBlank()) "إعادة تعيين لون لوحة الاسم" else "تعيين لون لوحة الاسم: $cleanKey"
        )
    }

    override suspend fun getGlobalCustomerFrameEnabled(): Boolean {
        val snap = runCatching {
            firestore.collection(Constants.COLLECTION_SYSTEM_CONFIG).document("global_appearance").get().await()
        }.getOrNull()
        return snap?.getBoolean("globalCustomerFrameEnabled") ?: true
    }

    override suspend fun setGlobalCustomerFrameEnabled(enabled: Boolean): Result<Int> {
        val snap = runCatching {
            firestore.collection(Constants.COLLECTION_SYSTEM_CONFIG).document("global_appearance").get().await()
        }.getOrNull()
        val currentFrame = snap?.getString("defaultFrameId").orEmpty()
            .ifBlank { snap?.getString("defaultCustomerFrameId").orEmpty() }
            .ifBlank { WesalFrameCatalog.DEFAULT_ALL_CUSTOMERS_FRAME_ID }
        val currentIconId = snap?.getString("defaultCustomerIconId").orEmpty()
        val currentIconUrl = snap?.getString("defaultCustomerIconUrl").orEmpty()
        return setGlobalCustomerDefaults(enabled, currentFrame, currentIconId, currentIconUrl)
    }

    override suspend fun setGlobalCustomerDefaults(
        enabled: Boolean,
        frameId: String,
        iconId: String,
        iconUrl: String
    ): Result<Int> = runCatching {
        val perms = getCurrentAdminPermissions()
        require(perms.isFullSuperAdmin || perms.canManageFramesAndNeon) { "صلاحية إدارة الإطارات مطلوبة" }
        val configDocRef = firestore.collection(Constants.COLLECTION_SYSTEM_CONFIG).document("global_appearance")
        val prevSnap = runCatching { configDocRef.get().await() }.getOrNull()
        val prevFrameId = prevSnap?.getString("defaultFrameId").orEmpty()
            .ifBlank { prevSnap?.getString("defaultCustomerFrameId").orEmpty() }
            .ifBlank { WesalFrameCatalog.DEFAULT_ALL_CUSTOMERS_FRAME_ID }
        val prevIconId = prevSnap?.getString("defaultCustomerIconId").orEmpty()

        val chosenFrame = frameId.trim().ifBlank { WesalFrameCatalog.DEFAULT_ALL_CUSTOMERS_FRAME_ID }
        val chosenIconId = iconId.trim()
        val chosenIconUrl = if (chosenIconId.isBlank()) "" else iconUrl.trim().ifBlank { WesalFrameLibrary.resolveIconUrl(chosenIconId) }

        // Round-2 rewrite of the propagation batch:
        // 1) NO 500-user cap: the users collection is paged with startAfter until exhausted,
        //    so the apply genuinely reaches every account.
        // 2) The config doc is written LAST. It used to be written first, so a batch that
        //    failed midway left the stored "previous default" pointing at the NEW frame —
        //    users still wearing the older default then matched nothing on the next run and
        //    were silently skipped forever (the prevFrameId/prevIconId tracking corruption).
        // 3) Semantics: a user is patched when their frame/icon is blank or equals the
        //    previous default (or the "all_customers" default id for frames). Individually
        //    granted frames/icons (any other value) are never stomped. When the admin clears
        //    the global icon while enabling, users still wearing the previous global icon
        //    are cleared too — symmetric with the frame rule.
        var updatedCount = 0
        var lastDoc: com.google.firebase.firestore.DocumentSnapshot? = null
        val pageSize = 400L
        while (true) {
            val baseQuery = firestore.collection(Constants.COLLECTION_USERS).limit(pageSize)
            val pageSnap = if (lastDoc != null) {
                baseQuery.startAfter(lastDoc).get().await()
            } else {
                baseQuery.get().await()
            }
            if (pageSnap.isEmpty) break
            for (chunk in pageSnap.documents.chunked(380)) {
                val batch = firestore.batch()
                var chunkWrites = 0
                for (doc in chunk) {
                    val numId = doc.getString("numericId").orEmpty()
                    if (Constants.isProtectedAdminNumericId(numId)) continue
                    val currentFrame = doc.getString("frameId").orEmpty()
                    val currentIcon = doc.getString("iconId").orEmpty()
                    val patch = hashMapOf<String, Any>()

                    if (enabled) {
                        if (currentFrame.isBlank() || currentFrame == prevFrameId || currentFrame == WesalFrameCatalog.DEFAULT_ALL_CUSTOMERS_FRAME_ID) {
                            patch["frameId"] = chosenFrame
                            patch["frameExpiresAt"] = 0L
                        }
                        if (chosenIconId.isNotBlank()) {
                            if (currentIcon.isBlank() || currentIcon == prevIconId) {
                                patch["iconId"] = chosenIconId
                                patch["iconUrl"] = chosenIconUrl
                            }
                        } else if (prevIconId.isNotBlank() && currentIcon == prevIconId) {
                            patch["iconId"] = ""
                            patch["iconUrl"] = ""
                        }
                    } else {
                        if (currentFrame == prevFrameId || currentFrame == chosenFrame || currentFrame == WesalFrameCatalog.DEFAULT_ALL_CUSTOMERS_FRAME_ID) {
                            patch["frameId"] = ""
                            patch["frameExpiresAt"] = 0L
                        }
                        if (prevIconId.isNotBlank() && currentIcon == prevIconId) {
                            patch["iconId"] = ""
                            patch["iconUrl"] = ""
                        }
                    }

                    if (patch.isNotEmpty()) {
                        batch.set(doc.reference, patch, SetOptions.merge())
                        chunkWrites++
                    }
                }
                if (chunkWrites > 0) {
                    batch.commit().await()
                    updatedCount += chunkWrites
                }
            }
            lastDoc = pageSnap.documents.last()
            if (pageSnap.size() < pageSize) break
        }

        configDocRef.set(
            mapOf(
                "globalCustomerFrameEnabled" to enabled,
                "defaultFrameId" to chosenFrame,
                "defaultCustomerFrameId" to chosenFrame,
                "defaultCustomerIconId" to chosenIconId,
                "defaultCustomerIconUrl" to chosenIconUrl,
                "updatedAt" to System.currentTimeMillis()
            ),
            SetOptions.merge()
        ).await()

        writeAdminActionLog(
            action = if (enabled) "GLOBAL_FRAME_ENABLED" else "GLOBAL_FRAME_DISABLED",
            details = "تطبيق الإطار/الأيقونة الافتراضية على العملاء ($chosenFrame • $updatedCount حساب)"
        )
        updatedCount
    }

    override suspend fun setAccountNeonStyle(
        targetUid: String,
        neonColor: String,
        glowIntensity: Float,
        enabled: Boolean
    ): Result<Unit> = runCatching {
        val perms = getCurrentAdminPermissions()
        require(perms.isFullSuperAdmin || perms.canManageFramesAndNeon) { "صلاحية إدارة النيون مطلوبة" }
        val ref = firestore.collection(Constants.COLLECTION_USERS).document(targetUid)
        val snap = ref.get().await()
        require(snap.exists()) { "الحساب غير موجود" }
        val targetUser = snap.toWesalUser(includePrivateUnredacted = true)
        if (!perms.isFullSuperAdmin) {
            require(!Constants.isProtectedAdminNumericId(targetUser.numericId) && !Constants.isSuperAdminEmail(targetUser.email)) {
                "لا يمكن تعديل نيون الإدارة إلا من المشرف الأعلى"
            }
        }

        val cleanColor = if (enabled) neonColor.trim() else ""
        val effectiveEnabled = enabled && cleanColor.isNotBlank()
        val safeGlow = glowIntensity.coerceIn(0.15f, 1.0f)
        ref.set(
            mapOf(
                "neonEnabled" to effectiveEnabled,
                "neonColor" to cleanColor,
                "neonGlowIntensity" to safeGlow.toDouble()
            ),
            SetOptions.merge()
        ).await()

        writeAdminActionLog(
            action = "NEON_UPDATED",
            targetUid = targetUid,
            targetNumericId = targetUser.numericId,
            targetPublicId = targetUser.publicId,
            targetName = targetUser.displayName,
            details = if (!effectiveEnabled) "إيقاف النيون" else "نيون: $cleanColor (${(safeGlow * 100).toInt()}%)"
        )
    }

    override suspend fun setAccountHiddenFromSearch(targetUid: String, hidden: Boolean, reason: String): Result<Unit> = runCatching {
        val admin = auth.currentUser ?: error("Not authenticated")
        val perms = getCurrentAdminPermissions()
        require(perms.isFullSuperAdmin || perms.canManageUsersModeration) { "صلاحية إدارة المستخدمين مطلوبة" }
        val ref = firestore.collection(Constants.COLLECTION_USERS).document(targetUid)
        val snap = ref.get().await()
        require(snap.exists()) { "الحساب غير موجود" }
        val targetUser = snap.toWesalUser(includePrivateUnredacted = true)
        if (!perms.isFullSuperAdmin) {
            require(!Constants.isProtectedAdminNumericId(targetUser.numericId) && !Constants.isSuperAdminEmail(targetUser.email)) {
                "لا يمكن تعديل ظهور حساب الإدارة المحمي"
            }
        }

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
        val perms = getCurrentAdminPermissions()
        require(perms.isFullSuperAdmin || perms.canManageUsersModeration) { "صلاحية الحظر مطلوبة" }
        val ref = firestore.collection(Constants.COLLECTION_USERS).document(targetUid)
        val snap = ref.get().await()
        require(snap.exists()) { "الحساب غير موجود" }
        val user = snap.toWesalUser(includePrivateUnredacted = true)
        require(!Constants.isProtectedAdminNumericId(user.numericId) && !Constants.isSuperAdminEmail(user.email)) {
            "لا يمكن حظر حساب الإدارة المحمي"
        }

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

        // §10.3: stamp the banned claim + revoke the target's refresh tokens
        // server-side. Fire-and-forget — the Firestore write above already
        // succeeded and stands on its own if this call fails.
        notifyBanStateChanged(
            targetUid = targetUid,
            banned = true,
            banType = banType,
            banExpiresAt = if (permanent) 0L else expiresAt
        )

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
        val perms = getCurrentAdminPermissions()
        require(perms.isFullSuperAdmin || perms.canManageUsersModeration) { "صلاحية فك الحظر مطلوبة" }
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

        // §10.3: strip the banned claim server-side. Fire-and-forget, exactly
        // like the ban path — the Firestore write above stands on its own.
        notifyBanStateChanged(targetUid = targetUid, banned = false, banType = "", banExpiresAt = 0L)

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

    override suspend fun adminDeleteUser(targetUid: String, permanent: Boolean): Result<Unit> = runCatching {
        if (permanent) {
            deleteAccountPermanently(targetUid, "حذف نهائي من لوحة الإدارة").getOrThrow()
        } else {
            val perms = getCurrentAdminPermissions()
            require(perms.isFullSuperAdmin || perms.canManageUsersModeration) { "صلاحية تعطيل الحسابات مطلوبة" }
            val ref = firestore.collection(Constants.COLLECTION_USERS).document(targetUid)
            val snap = ref.get().await()
            require(snap.exists()) { "الحساب غير موجود" }
            val user = snap.toWesalUser(includePrivateUnredacted = true)
            require(!Constants.isProtectedAdminNumericId(user.numericId) && !Constants.isSuperAdminEmail(user.email)) {
                "لا يمكن حذف أو تعطيل حساب الإدارة المحمي"
            }
            val now = System.currentTimeMillis()
            ref.set(
                mapOf(
                    "deletedAt" to now,
                    "hiddenFromSearch" to true,
                    "isOnline" to false
                ),
                SetOptions.merge()
            ).await()
            writeAdminActionLog(
                action = "USER_SOFT_DELETED",
                targetUid = targetUid,
                targetNumericId = user.numericId,
                targetPublicId = user.publicId,
                targetName = user.displayName,
                details = "تعطيل الحساب وإخفاؤه من البحث"
            )
        }
    }

    override suspend fun deleteAccountPermanently(targetUid: String, reason: String): Result<Unit> = runCatching {
        val perms = getCurrentAdminPermissions()
        require(perms.isFullSuperAdmin) { "صلاحية المشرف الأعلى مطلوبة للحذف النهائي" }
        val ref = firestore.collection(Constants.COLLECTION_USERS).document(targetUid)
        val snap = ref.get().await()
        require(snap.exists()) { "الحساب غير موجود" }
        val user = snap.toWesalUser(includePrivateUnredacted = true)
        require(!Constants.isProtectedAdminNumericId(user.numericId) && !Constants.isSuperAdminEmail(user.email)) {
            "لا يمكن حذف حساب الإدارة المحمي نهائيًا"
        }

        val privSnap = runCatching { ref.collection(Constants.SUBCOLLECTION_PRIVATE).document("info").get().await() }.getOrNull()
        val realPhone = privSnap?.getString("phoneNumber").orEmpty().ifBlank { user.phoneNumber }
        if (realPhone.isNotBlank()) {
            val pHash = PhoneUtils.sha256Hash(realPhone)
            runCatching { firestore.collection(Constants.COLLECTION_PHONE_UNIQUENESS).document(pHash).delete().await() }
        }
        if (user.numericId.isNotBlank()) {
            runCatching { firestore.collection(Constants.COLLECTION_WESAL_ID_INDEX).document(user.numericId).delete().await() }
        }
        runCatching { firestore.collection(Constants.COLLECTION_PRESENCE).document(targetUid).delete().await() }
        runCatching { firestore.collection(Constants.COLLECTION_MODERATORS).document(targetUid).delete().await() }
        runCatching { ref.collection(Constants.SUBCOLLECTION_PRIVATE).document("info").delete().await() }

        val sessions = runCatching { ref.collection(Constants.COLLECTION_SESSIONS).get().await() }.getOrNull()
        if (sessions != null && !sessions.isEmpty) {
            val batch = firestore.batch()
            sessions.documents.forEach { batch.delete(it.reference) }
            runCatching { batch.commit().await() }
        }

        val officialChatRef = firestore.collection(Constants.COLLECTION_CHATS)
            .document("${Constants.OFFICIAL_CHAT_PREFIX}$targetUid")
        val officialMsgs = runCatching { officialChatRef.collection(Constants.COLLECTION_MESSAGES).limit(200).get().await() }.getOrNull()
        if (officialMsgs != null && !officialMsgs.isEmpty) {
            val msgBatch = firestore.batch()
            officialMsgs.documents.forEach { msgBatch.delete(it.reference) }
            runCatching { msgBatch.commit().await() }
        }
        runCatching { officialChatRef.delete().await() }

        // Scrub remaining references across contact_requests, stories, groups, channels, contact_lists, scheduled_calls, chats
        runCatching {
            firestore.collection(Constants.COLLECTION_CONTACT_REQUESTS)
                .whereArrayContains("participants", targetUid).get().await()
                .documents.forEach { d -> runCatching { d.reference.delete().await() } }
        }
        runCatching {
            firestore.collection(Constants.COLLECTION_STORIES)
                .whereEqualTo("ownerUid", targetUid).get().await()
                .documents.forEach { d -> runCatching { d.reference.delete().await() } }
        }
        runCatching {
            firestore.collection(Constants.COLLECTION_GROUPS)
                .whereArrayContains("memberIds", targetUid).get().await()
                .documents.forEach { d ->
                    runCatching {
                        d.reference.update(
                            mapOf(
                                "memberIds" to FieldValue.arrayRemove(targetUid),
                                "adminIds" to FieldValue.arrayRemove(targetUid)
                            )
                        ).await()
                    }
                }
        }
        runCatching {
            firestore.collection(Constants.COLLECTION_CHANNELS)
                .whereArrayContains("subscriberIds", targetUid).get().await()
                .documents.forEach { d ->
                    if (d.getString("ownerUid") != targetUid) {
                        runCatching {
                            d.reference.update(
                                mapOf(
                                    "subscriberIds" to FieldValue.arrayRemove(targetUid),
                                    "moderatorIds" to FieldValue.arrayRemove(targetUid)
                                )
                            ).await()
                        }
                    }
                }
        }
        runCatching {
            firestore.collection(Constants.COLLECTION_CONTACT_LISTS)
                .whereEqualTo("ownerUid", targetUid).get().await()
                .documents.forEach { d -> runCatching { d.reference.delete().await() } }
        }
        runCatching {
            firestore.collection(Constants.COLLECTION_SCHEDULED_CALLS)
                .whereEqualTo("creatorUid", targetUid).get().await()
                .documents.forEach { d -> runCatching { d.reference.delete().await() } }
        }
        runCatching {
            firestore.collection(Constants.COLLECTION_CHATS)
                .whereArrayContains("participantIds", targetUid).get().await()
                .documents.forEach { d ->
                    runCatching { d.reference.update("deletedForUsers", FieldValue.arrayUnion(targetUid)).await() }
                }
        }

        val now = System.currentTimeMillis()
        val cleanReason = reason.trim().ifBlank { "تم حذف هذا الحساب نهائياً" }
        if (user.email.isNotBlank()) {
            val eHash = PhoneUtils.sha256Hash(user.email.trim().lowercase())
            firestore.collection(Constants.COLLECTION_BANNED_IDENTIFIERS)
                .document("email_$eHash")
                .set(
                    mapOf(
                        "type" to "DELETED_PERMANENT",
                        "targetUid" to targetUid,
                        "permanent" to true,
                        "expiresAt" to 0L,
                        "reason" to cleanReason,
                        "createdAt" to now
                    )
                ).await()
        }
        if (realPhone.isNotBlank()) {
            val pHash = PhoneUtils.sha256Hash(realPhone)
            firestore.collection(Constants.COLLECTION_BANNED_IDENTIFIERS)
                .document("phone_$pHash")
                .set(
                    mapOf(
                        "type" to "DELETED_PERMANENT",
                        "targetUid" to targetUid,
                        "permanent" to true,
                        "expiresAt" to 0L,
                        "reason" to cleanReason,
                        "createdAt" to now
                    )
                ).await()
        }

        // Write scrubbed tombstone so deleted account stays tracked in DELETED stats/filters and cannot be recreated
        ref.set(
            mapOf(
                "displayName" to "حساب محذوف",
                "nameSearchKey" to WesalIdUtils.normalizeNameSearchKey("حساب محذوف"),
                "bio" to "",
                "photoUrl" to "",
                "coverUrl" to "",
                "phoneNumber" to "",
                "phoneHash" to "",
                "birthDate" to "",
                "email" to "",
                "fcmToken" to "",
                "publicKey" to "",
                "facebookUrl" to "",
                "instagramUrl" to "",
                "isVerified" to false,
                "isBanned" to false,
                "banReason" to "",
                "frameId" to "",
                "iconId" to "",
                "iconUrl" to "",
                "neonEnabled" to false,
                "neonColor" to "",
                "isOnline" to false,
                "activeSessionsCount" to 0,
                "hiddenFromSearch" to true,
                "deletedAt" to now,
                "deletedPermanent" to true,
                "lastSeen" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).await()

        // Spec §10.2: dedicated deleted-accounts log — one tombstone doc per deleted
        // account (doc id = uid), separate from the audit logs so the record survives
        // audit-log clearing/rotation. Rules: readable by super admins and moderators
        // with canManageUsersModeration; creatable only by this admin flow's auth
        // context; deletable only by a super admin. Best-effort: the deletion itself
        // above is the primary operation and must not fail because the log write did.
        runCatching {
            firestore.collection(Constants.COLLECTION_DELETED_ACCOUNTS)
                .document(targetUid)
                .set(
                    mapOf(
                        "uid" to targetUid,
                        "numericId" to user.numericId,
                        "publicId" to user.publicId,
                        "deletedBy" to (auth.currentUser?.uid.orEmpty()),
                        "deletedAt" to now,
                        "reason" to cleanReason
                    )
                ).await()
        }

        writeAdminActionLog(
            action = "USER_DELETED_PERMANENT",
            targetUid = targetUid,
            targetNumericId = user.numericId,
            targetPublicId = user.publicId,
            targetName = user.displayName,
            note = cleanReason,
            details = "حذف الحساب نهائياً وتصفية بياناته وحظر إعادة التسجيل"
        )
    }

    override suspend fun warnUser(targetUid: String, reason: String): Result<Unit> = runCatching {
        val perms = getCurrentAdminPermissions()
        require(perms.isFullSuperAdmin || perms.canManageUsersModeration) { "صلاحية إرسال الإنذارات مطلوبة" }
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
        val perms = getCurrentAdminPermissions()
        require(perms.isFullSuperAdmin || perms.canManageReports) { "صلاحية معالجة البلاغات مطلوبة" }
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
        val perms = getCurrentAdminPermissions()
        if (!perms.isFullSuperAdmin && !perms.canManageReports && !perms.canInspectPrivateData) return
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
                    }.orEmpty().distinctBy { log ->
                        "${log.operationId}_${log.targetUid}_${log.action}"
                    }
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
        val perms = getCurrentAdminPermissions()
        require(perms.isFullSuperAdmin || perms.canBroadcastAnnouncements) { "صلاحية تعديل رسالة الترحيب مطلوبة" }
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
        val perms = getCurrentAdminPermissions()
        require(perms.isFullSuperAdmin || perms.canBroadcastAnnouncements) { "صلاحية اختبار رسالة الترحيب مطلوبة" }
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
        val perms = getCurrentAdminPermissions()
        require(perms.isFullSuperAdmin || perms.canBroadcastAnnouncements) { "صلاحية نشر الإعلانات الرسمية مطلوبة" }
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

    /**
     * Spec §9.6 (round 4, P1-7) — official identity photo writer.
     * Targets identified from the existing writers (mirroring
     * ChatRepositoryImpl.setChatOfficialWallpaper's chats-doc pattern and
     * ChannelRepositoryImpl.ensureOfficialTeamChannel):
     *  - Team channel: channels/{Constants.OFFICIAL_WESAL_TEAM_CHANNEL_ID}
     *    ("wesal_official_team_channel"), photo field `avatarUrl`.
     *  - Official chats: there is NO single "main" official chat document — the
     *    official WESAL chat is a PER-USER doc chats/{OFFICIAL_CHAT_PREFIX}<uid>
     *    (created by ensureOfficialWelcomeChat / warnUser / announcements), and its
     *    photo field is `groupAvatarUrl` (the field the chats-list parser reads and
     *    ensureOfficialWelcomeChat writes). So this writer merge-updates
     *    `groupAvatarUrl` on EVERY chats doc with isOfficialWesalChat == true,
     *    walked with limit(200) + startAfter cursor pages and batched writes; if
     *    none exists yet it seeds the caller's own official chat doc.
     */
    override suspend fun updateOfficialChatIdentity(photoUrl: String): Result<Unit> = runCatching {
        val admin = auth.currentUser ?: error("Not authenticated")
        val perms = getCurrentAdminPermissions()
        require(perms.isFullSuperAdmin || perms.canManageFramesAndNeon) {
            "صلاحية إدارة الهوية الرسمية مطلوبة"
        }
        val cleanUrl = photoUrl.trim()
        require(cleanUrl.isNotBlank()) { "رابط صورة الهوية الرسمية مطلوب" }

        // 1) Team channel avatar.
        firestore.collection(Constants.COLLECTION_CHANNELS)
            .document(Constants.OFFICIAL_WESAL_TEAM_CHANNEL_ID)
            .set(
                mapOf(
                    "channelId" to Constants.OFFICIAL_WESAL_TEAM_CHANNEL_ID,
                    "isOfficialTeamChannel" to true,
                    "avatarUrl" to cleanUrl,
                    // Marker for ChannelRepositoryImpl.ensureOfficialTeamChannel:
                    // an editor-set avatar is sticky — the owner-admin photo
                    // auto-sync must not overwrite it (round-4 P1-7 carve-out).
                    "avatarSetByOfficialEditor" to true
                ),
                SetOptions.merge()
            ).await()

        // 2) Every per-user official chat doc's photo field.
        var updatedChats = 0
        var lastDoc: DocumentSnapshot? = null
        while (true) {
            var query = firestore.collection(Constants.COLLECTION_CHATS)
                .whereEqualTo("isOfficialWesalChat", true)
                .limit(200)
            if (lastDoc != null) query = query.startAfter(lastDoc!!)
            val snap = query.get().await()
            if (snap.isEmpty) break
            lastDoc = snap.documents.last()
            for (chunk in snap.documents.chunked(380)) {
                val batch = firestore.batch()
                chunk.forEach { doc ->
                    batch.set(doc.reference, mapOf("groupAvatarUrl" to cleanUrl), SetOptions.merge())
                }
                batch.commit().await()
                updatedChats += chunk.size
            }
            if (snap.size() < 200) break
        }
        if (updatedChats == 0) {
            val ownChatId = "${Constants.OFFICIAL_CHAT_PREFIX}${admin.uid}"
            firestore.collection(Constants.COLLECTION_CHATS).document(ownChatId).set(
                mapOf(
                    "chatId" to ownChatId,
                    "isGroup" to false,
                    "isOfficialWesalChat" to true,
                    "groupName" to "WESAL Messenger",
                    "groupAvatarUrl" to cleanUrl,
                    "participantIds" to listOf(admin.uid)
                ),
                SetOptions.merge()
            ).await()
        }

        // 3) Round 5 (owner consistency requirement): the official ACCOUNT's
        // own profile photo. 10001 is a real user doc and the SAME identity as
        // the official presence, so its profile page must show the same photo
        // as the official chat/channel ("from outside vs inside" complaint).
        // Best-effort: steps 1–2 already landed, so a failure here (e.g. the
        // 10001 doc does not exist yet) must not fail the whole update.
        runCatching {
            val ownerSnap = firestore.collection(Constants.COLLECTION_USERS)
                .whereEqualTo("numericId", Constants.OWNER_ADMIN_NUMERIC_ID)
                .limit(1)
                .get()
                .await()
            ownerSnap.documents.firstOrNull()?.reference
                ?.set(mapOf("photoUrl" to cleanUrl), SetOptions.merge())
                ?.await()
        }

        writeAdminActionLog(
            action = "OFFICIAL_IDENTITY_PHOTO_UPDATED",
            details = "تحديث صورة الهوية الرسمية (المحادثة الرسمية + قناة الفريق) على $updatedChats محادثة"
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
        senderVerificationType = getString("senderVerificationType") ?: "BLUE",
        senderIconId = getString("senderIconId").orEmpty(),
        senderIconUrl = getString("senderIconUrl").orEmpty(),
        senderNeonColor = getString("senderNeonColor").orEmpty(),
        senderNeonEnabled = getBoolean("senderNeonEnabled") ?: false,
        senderNeonGlowIntensity = getDouble("senderNeonGlowIntensity")?.toFloat() ?: 0.7f,
        receiverUid = getString("receiverUid").orEmpty(),
        receiverName = getString("receiverName").orEmpty(),
        receiverPublicId = getString("receiverPublicId").orEmpty(),
        receiverAvatarUrl = getString("receiverAvatarUrl").orEmpty(),
        receiverVerified = getBoolean("receiverVerified") ?: false,
        receiverVerificationType = getString("receiverVerificationType") ?: "BLUE",
        receiverIconId = getString("receiverIconId").orEmpty(),
        receiverIconUrl = getString("receiverIconUrl").orEmpty(),
        receiverNeonColor = getString("receiverNeonColor").orEmpty(),
        receiverNeonEnabled = getBoolean("receiverNeonEnabled") ?: false,
        receiverNeonGlowIntensity = getDouble("receiverNeonGlowIntensity")?.toFloat() ?: 0.7f,
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
                                                receiverVerified = peerUser.isVerified,
                                                receiverVerificationType = peerUser.verificationType,
                                                receiverIconId = peerUser.iconId,
                                                receiverIconUrl = peerUser.iconUrl,
                                                receiverNeonColor = peerUser.neonColor,
                                                receiverNeonEnabled = peerUser.neonEnabled,
                                                receiverNeonGlowIntensity = peerUser.neonGlowIntensity
                                            )
                                        } else {
                                            req.copy(
                                                senderName = peerUser.displayName.ifBlank { req.senderName },
                                                senderAvatarUrl = peerUser.photoUrl.ifBlank { req.senderAvatarUrl },
                                                senderVerified = peerUser.isVerified,
                                                senderVerificationType = peerUser.verificationType,
                                                senderIconId = peerUser.iconId,
                                                senderIconUrl = peerUser.iconUrl,
                                                senderNeonColor = peerUser.neonColor,
                                                senderNeonEnabled = peerUser.neonEnabled,
                                                senderNeonGlowIntensity = peerUser.neonGlowIntensity
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

    /**
     * Accounts that registered through MY invite link (round 3). Attribution is stamped
     * once at account creation as referredByUid == my uid (see AuthRepositoryImpl), so a
     * single-field equality query lists them — no phone-book or phoneHash involvement,
     * which is exactly why Gmail-only invitees show up here when they can't appear in the
     * device-contacts sections. Deleted accounts are filtered out; newest first.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeMyReferrals(): Flow<List<WesalUser>> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(emptyList())
        } else {
            callbackFlow {
                val reg = firestore.collection(Constants.COLLECTION_USERS)
                    .whereEqualTo("referredByUid", uid)
                    .addSnapshotListener { snap, error ->
                        if (error != null) {
                            trySend(emptyList())
                            return@addSnapshotListener
                        }
                        val list = snap?.documents
                            ?.map { it.toWesalUser() }
                            .orEmpty()
                            .filter { it.deletedAt == 0L && !it.deletedPermanent }
                            .sortedByDescending { it.createdAt }
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
            senderVerificationType = me.verificationType,
            senderIconId = me.iconId,
            senderIconUrl = me.iconUrl,
            senderNeonColor = me.neonColor,
            senderNeonEnabled = me.neonEnabled,
            senderNeonGlowIntensity = me.neonGlowIntensity,
            receiverUid = targetUid,
            receiverName = target.displayName.ifBlank { "@${target.publicId}" },
            receiverPublicId = target.publicId,
            receiverAvatarUrl = target.photoUrl,
            receiverVerified = target.isVerified,
            receiverVerificationType = target.verificationType,
            receiverIconId = target.iconId,
            receiverIconUrl = target.iconUrl,
            receiverNeonColor = target.neonColor,
            receiverNeonEnabled = target.neonEnabled,
            receiverNeonGlowIntensity = target.neonGlowIntensity,
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
            "senderVerificationType" to request.senderVerificationType,
            "senderIconId" to request.senderIconId,
            "senderIconUrl" to request.senderIconUrl,
            "senderNeonColor" to request.senderNeonColor,
            "senderNeonEnabled" to request.senderNeonEnabled,
            "senderNeonGlowIntensity" to request.senderNeonGlowIntensity,
            "receiverUid" to targetUid,
            "receiverName" to request.receiverName,
            "receiverPublicId" to request.receiverPublicId,
            "receiverAvatarUrl" to request.receiverAvatarUrl,
            "receiverVerified" to request.receiverVerified,
            "receiverVerificationType" to request.receiverVerificationType,
            "receiverIconId" to request.receiverIconId,
            "receiverIconUrl" to request.receiverIconUrl,
            "receiverNeonColor" to request.receiverNeonColor,
            "receiverNeonEnabled" to request.receiverNeonEnabled,
            "receiverNeonGlowIntensity" to request.receiverNeonGlowIntensity,
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

    override suspend fun deleteAuditLog(collectionName: String, logId: String): Result<Unit> = runCatching {
        val perms = getCurrentAdminPermissions()
        require(perms.isFullSuperAdmin) { "صلاحية المشرف الأعلى مطلوبة لحذف السجل" }
        val allowedCollections = setOf(
            Constants.COLLECTION_VERIFICATION_LOGS,
            Constants.COLLECTION_ADMIN_LOGS,
            Constants.COLLECTION_HIDDEN_LOGS
        )
        require(collectionName in allowedCollections && logId.isNotBlank()) { "معرف سجل غير صالح" }
        val col = firestore.collection(collectionName)
        col.document(logId).delete().await()
        val byOp = runCatching { col.whereEqualTo("operationId", logId).get().await() }.getOrNull()
        val byLogId = runCatching { col.whereEqualTo("logId", logId).get().await() }.getOrNull()
        val matchingDocs = (byOp?.documents.orEmpty() + byLogId?.documents.orEmpty())
            .distinctBy { it.id }
            .filter { it.id != logId }
        if (matchingDocs.isNotEmpty()) {
            matchingDocs.chunked(350).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
        }
    }

    override suspend fun clearAuditLogs(collectionName: String): Result<Unit> = runCatching {
        val perms = getCurrentAdminPermissions()
        require(perms.isFullSuperAdmin) { "صلاحية المشرف الأعلى مطلوبة لتصفية السجل" }
        val allowedCollections = setOf(
            Constants.COLLECTION_VERIFICATION_LOGS,
            Constants.COLLECTION_ADMIN_LOGS,
            Constants.COLLECTION_HIDDEN_LOGS
        )
        require(collectionName in allowedCollections) { "سجل غير صالح" }
        val col = firestore.collection(collectionName)
        while (true) {
            val snap = col.limit(350).get().await()
            if (snap.isEmpty) break
            val batch = firestore.batch()
            snap.documents.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
    }

    override suspend fun holdContactRequest(requestId: String): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val ref = firestore.collection(Constants.COLLECTION_CONTACT_REQUESTS).document(requestId)
        val snap = ref.get().await()
        require(snap.exists() && snap.getString("receiverUid") == myUid) { "لا تملك صلاحية تعليق هذا الطلب" }
        ref.update(mapOf("status" to ContactRequestStatus.HELD.raw, "updatedAt" to System.currentTimeMillis())).await()
    }

    @Suppress("UNCHECKED_CAST")
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeContactLists(): Flow<List<ContactLabelList>> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) {
            contactListsSyncState.value = ChatListSyncState(ChatListSyncStatus.READY)
            flowOf(emptyList())
        }
        else callbackFlow {
            // Spec §13.4 (round 4, P1-10): on listener error this flow used to emit
            // emptyList() as if it were data, wiping the section. Now the error goes
            // to observeContactListsSyncState() and the flow keeps its last value.
            contactListsSyncState.value = ChatListSyncState(ChatListSyncStatus.LOADING)
            val reg = firestore.collection(Constants.COLLECTION_CONTACT_LISTS)
                .whereEqualTo("ownerUid", uid)
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        contactListsSyncState.value = ChatListSyncState(
                            ChatListSyncStatus.ERROR,
                            err.message ?: "تعذر تحميل القوائم"
                        )
                        return@addSnapshotListener
                    }
                    contactListsSyncState.value = ChatListSyncState(ChatListSyncStatus.READY)
                    val list = snap?.documents?.map { d ->
                        ContactLabelList(
                            listId = d.getString("listId") ?: d.id,
                            ownerUid = d.getString("ownerUid").orEmpty(),
                            name = d.getString("name").orEmpty(),
                            memberUids = (d.get("memberUids") as? List<String>).orEmpty(),
                            isDefaultPreset = d.getBoolean("isDefaultPreset") ?: false,
                            createdAt = d.getLong("createdAt") ?: 0L,
                            updatedAt = d.getLong("updatedAt") ?: 0L
                        )
                    }.orEmpty().sortedBy { it.createdAt }
                    trySend(list)
                }
            awaitClose { reg.remove() }
        }
    }

    override suspend fun upsertContactList(
        listId: String?,
        name: String,
        memberUids: List<String>
    ): Result<ContactLabelList> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val cleanName = name.trim()
        require(cleanName.isNotBlank()) { "اسم القائمة مطلوب" }
        val resolvedId = listId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val cleanMembers = memberUids.filter { it.isNotBlank() }.distinct()
        val payload = mapOf(
            "listId" to resolvedId,
            "ownerUid" to myUid,
            "name" to cleanName,
            "memberUids" to cleanMembers,
            "isDefaultPreset" to resolvedId.startsWith("preset_"),
            "createdAt" to now,
            "updatedAt" to now
        )
        firestore.collection(Constants.COLLECTION_CONTACT_LISTS).document(resolvedId)
            .set(payload, SetOptions.merge())
            .await()
        ContactLabelList(
            listId = resolvedId,
            ownerUid = myUid,
            name = cleanName,
            memberUids = cleanMembers,
            isDefaultPreset = resolvedId.startsWith("preset_"),
            createdAt = now,
            updatedAt = now
        )
    }

    override suspend fun addUidToContactList(listId: String, uid: String): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        require(listId.isNotBlank() && uid.isNotBlank()) { "بيانات غير صالحة" }
        val ref = firestore.collection(Constants.COLLECTION_CONTACT_LISTS).document(listId)
        val snap = ref.get().await()
        require(snap.exists() && snap.getString("ownerUid") == myUid) { "القائمة غير موجودة" }
        ref.update(
            mapOf(
                "memberUids" to FieldValue.arrayUnion(uid),
                "updatedAt" to System.currentTimeMillis()
            )
        ).await()
    }

    override suspend fun removeUidFromContactList(listId: String, uid: String): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val ref = firestore.collection(Constants.COLLECTION_CONTACT_LISTS).document(listId)
        val snap = ref.get().await()
        require(snap.exists() && snap.getString("ownerUid") == myUid) { "القائمة غير موجودة" }
        ref.update(
            mapOf(
                "memberUids" to FieldValue.arrayRemove(uid),
                "updatedAt" to System.currentTimeMillis()
            )
        ).await()
    }

    override suspend fun deleteContactList(listId: String): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val ref = firestore.collection(Constants.COLLECTION_CONTACT_LISTS).document(listId)
        val snap = ref.get().await()
        require(!snap.exists() || snap.getString("ownerUid") == myUid) { "لا تملك صلاحية حذف هذه القائمة" }
        ref.delete().await()
    }

    @Suppress("UNCHECKED_CAST")
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAppEvents(): Flow<List<AppEventItem>> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) flowOf(emptyList())
        else callbackFlow {
            val reg = firestore.collection(Constants.COLLECTION_EVENTS)
                .orderBy("eventTime", Query.Direction.ASCENDING)
                .limit(100)
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    val list = snap?.documents?.map { d ->
                        AppEventItem(
                            eventId = d.getString("eventId") ?: d.id,
                            creatorUid = d.getString("creatorUid").orEmpty(),
                            creatorName = d.getString("creatorName").orEmpty(),
                            title = d.getString("title").orEmpty(),
                            description = d.getString("description").orEmpty(),
                            locationName = d.getString("locationName").orEmpty(),
                            eventTime = d.getLong("eventTime") ?: 0L,
                            participantUids = (d.get("participantUids") as? List<String>).orEmpty(),
                            createdAt = d.getLong("createdAt") ?: 0L
                        )
                    }.orEmpty()
                    trySend(list)
                }
            awaitClose { reg.remove() }
        }
    }

    override suspend fun createAppEvent(
        title: String,
        description: String,
        locationName: String,
        eventTime: Long
    ): Result<AppEventItem> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val cleanTitle = title.trim()
        require(cleanTitle.isNotBlank()) { "عنوان المناسبة مطلوب" }
        val me = getUserOnce(myUid)
        val eventId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val item = AppEventItem(
            eventId = eventId,
            creatorUid = myUid,
            creatorName = me?.displayName?.ifBlank { "@${me.publicId}" } ?: "WESAL User",
            title = cleanTitle,
            description = description.trim(),
            locationName = locationName.trim(),
            eventTime = eventTime,
            participantUids = listOf(myUid),
            createdAt = now
        )
        firestore.collection(Constants.COLLECTION_EVENTS).document(eventId).set(
            mapOf(
                "eventId" to item.eventId,
                "creatorUid" to item.creatorUid,
                "creatorName" to item.creatorName,
                "title" to item.title,
                "description" to item.description,
                "locationName" to item.locationName,
                "eventTime" to item.eventTime,
                "participantUids" to item.participantUids,
                "createdAt" to item.createdAt
            )
        ).await()
        item
    }

    override suspend fun deleteAppEvent(eventId: String): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val ref = firestore.collection(Constants.COLLECTION_EVENTS).document(eventId)
        val snap = ref.get().await()
        require(!snap.exists() || snap.getString("creatorUid") == myUid || isSuperAdmin()) {
            "فقط منشئ المناسبة أو الإدارة يمكنه حذفها"
        }
        ref.delete().await()
    }

    override suspend fun rsvpAppEvent(eventId: String, join: Boolean): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val op = if (join) FieldValue.arrayUnion(myUid) else FieldValue.arrayRemove(myUid)
        firestore.collection(Constants.COLLECTION_EVENTS).document(eventId)
            .update("participantUids", op)
            .await()
    }

    override suspend fun updateSelfCustomization(
        nameplateColor: String?,
        iconId: String?,
        iconUrl: String?,
        photoRemovedBg: Boolean?
    ): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        // Owner rule (2026-11-27): nameplate colour + account icon are admin-granted only
        // (admin panel, numeric IDs 10001/10002). The self-service card was removed from
        // ProfileScreen; this guard keeps the repository honest too — a non-owner account
        // can no longer change its own nameplate/icon through this legacy path. Other
        // fields (e.g. photoRemovedBg) are unaffected.
        if (nameplateColor != null || iconId != null || iconUrl != null) {
            val mySnap = firestore.collection(Constants.COLLECTION_USERS).document(uid).get().await()
            val myNumericId = mySnap.get("numericId")?.toString().orEmpty()
            require(myNumericId in Constants.ADMIN_NUMERIC_IDS) {
                "تخصيص لون الاسم والأيقونة متاح من لوحة الإدارة فقط"
            }
        }
        val updates = hashMapOf<String, Any>()
        if (nameplateColor != null) {
            updates["nameplateColor"] = nameplateColor.trim().ifBlank { "CRIMSON" }
        }
        if (iconId != null || iconUrl != null) {
            val cleanId = iconId.orEmpty().trim()
            val resolvedUrl = if (cleanId.isBlank() && iconUrl.isNullOrBlank()) {
                ""
            } else {
                iconUrl.orEmpty().trim().ifBlank { WesalFrameLibrary.resolveIconUrl(cleanId) }
            }
            updates["iconId"] = cleanId
            updates["iconUrl"] = resolvedUrl
        }
        if (photoRemovedBg != null) {
            updates["photoRemovedBg"] = photoRemovedBg
        }
        if (updates.isNotEmpty()) {
            firestore.collection(Constants.COLLECTION_USERS)
                .document(uid)
                .set(updates, SetOptions.merge())
                .await()
        }
    }

    override suspend fun updateGroupAddPrivacy(privacy: GroupAddPrivacy): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        firestore.collection(Constants.COLLECTION_USERS)
            .document(uid)
            .set(mapOf("groupAddPrivacy" to privacy.raw), SetOptions.merge())
            .await()
        prefs.setGroupsAddPermission(privacy.raw)
    }

    private fun DocumentSnapshot.toSupportTicket(): SupportTicket = SupportTicket(
        ticketId = getString("ticketId") ?: id,
        referenceNumber = getString("referenceNumber").orEmpty(),
        userUid = getString("userUid").orEmpty(),
        userName = getString("userName").orEmpty(),
        userNumericId = getString("userNumericId").orEmpty(),
        userPublicId = getString("userPublicId").orEmpty(),
        subject = getString("subject").orEmpty(),
        message = getString("message").orEmpty(),
        attachmentUrl = getString("attachmentUrl").orEmpty(),
        status = getString("status") ?: "OPEN",
        lastReplyText = getString("lastReplyText").orEmpty(),
        lastReplyByUid = getString("lastReplyByUid").orEmpty(),
        lastReplyAt = getLong("lastReplyAt") ?: 0L,
        createdAt = getLong("createdAt") ?: 0L,
        updatedAt = getLong("updatedAt") ?: 0L
    )

    override suspend fun submitSupportTicket(
        subject: String,
        message: String,
        attachmentUri: Uri?
    ): Result<String> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val cleanSubject = subject.trim()
        val cleanMessage = message.trim()
        require(cleanSubject.isNotBlank()) { "عنوان طلب الدعم مطلوب" }
        require(cleanMessage.isNotBlank()) { "وصف المشكلة مطلوب" }

        val me = getUserOnce(uid) ?: WesalUser(uid = uid)
        val ticketId = "tkt_${uid}_${System.currentTimeMillis()}"
        val refNum = "WS-${(100000..999999).random()}"
        val attachmentUrl = if (attachmentUri != null) {
            withContext(Dispatchers.IO) {
                uploadProfileMediaToStorageOrSmallFallback(
                    ctx = context,
                    storagePath = "support_attachments/$ticketId/attach_${System.currentTimeMillis()}.jpg",
                    uri = attachmentUri,
                    maxSide = 1080,
                    quality = 82
                )
            }
        } else {
            ""
        }
        val now = System.currentTimeMillis()
        val ticketRef = firestore.collection(Constants.COLLECTION_SUPPORT_TICKETS).document(ticketId)
        val ticketData = mapOf(
            "ticketId" to ticketId,
            "referenceNumber" to refNum,
            "userUid" to uid,
            "userName" to me.displayName.ifBlank { "@${me.publicId}" },
            "userNumericId" to me.numericId,
            "userPublicId" to me.publicId,
            "subject" to cleanSubject,
            "message" to cleanMessage,
            "attachmentUrl" to attachmentUrl,
            "status" to "OPEN",
            "lastReplyText" to "",
            "lastReplyByUid" to "",
            "lastReplyAt" to 0L,
            "createdAt" to now,
            "updatedAt" to now
        )
        val initialMsgId = UUID.randomUUID().toString()
        val initialMsg = mapOf(
            "messageId" to initialMsgId,
            "ticketId" to ticketId,
            "senderUid" to uid,
            "senderName" to me.displayName.ifBlank { "@${me.publicId}" },
            "isFromAdmin" to false,
            "text" to cleanMessage,
            "createdAt" to now
        )
        ticketRef.set(ticketData).await()
        ticketRef.collection(Constants.SUBCOLLECTION_MESSAGES).document(initialMsgId).set(initialMsg).await()
        refNum
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeMySupportTickets(): Flow<List<SupportTicket>> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) flowOf(emptyList())
        else callbackFlow {
            val reg = firestore.collection(Constants.COLLECTION_SUPPORT_TICKETS)
                .whereEqualTo("userUid", uid)
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    trySend(
                        snap?.documents?.map { it.toSupportTicket() }.orEmpty()
                            .sortedByDescending { it.updatedAt.coerceAtLeast(it.createdAt) }
                    )
                }
            awaitClose { reg.remove() }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAllSupportTickets(): Flow<List<SupportTicket>> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) flowOf(emptyList())
        else callbackFlow {
            val reg = firestore.collection(Constants.COLLECTION_SUPPORT_TICKETS)
                .limit(250)
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    trySend(
                        snap?.documents?.map { it.toSupportTicket() }.orEmpty()
                            .sortedByDescending { it.updatedAt.coerceAtLeast(it.createdAt) }
                    )
                }
            awaitClose { reg.remove() }
        }
    }

    override fun observeSupportTicketMessages(ticketId: String): Flow<List<SupportTicketMessage>> = callbackFlow {
        if (ticketId.isBlank()) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        val reg = firestore.collection(Constants.COLLECTION_SUPPORT_TICKETS)
            .document(ticketId)
            .collection(Constants.SUBCOLLECTION_MESSAGES)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val list = snap?.documents?.map { d ->
                    SupportTicketMessage(
                        messageId = d.getString("messageId") ?: d.id,
                        ticketId = d.getString("ticketId") ?: ticketId,
                        senderUid = d.getString("senderUid").orEmpty(),
                        senderName = d.getString("senderName").orEmpty(),
                        isFromAdmin = d.getBoolean("isFromAdmin") ?: false,
                        text = d.getString("text").orEmpty(),
                        createdAt = d.getLong("createdAt") ?: 0L
                    )
                }.orEmpty().sortedBy { it.createdAt }
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    override suspend fun replySupportTicket(
        ticketId: String,
        text: String,
        updateStatus: String?
    ): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val cleanText = text.trim()
        require(ticketId.isNotBlank() && cleanText.isNotBlank()) { "نص الرد مطلوب" }
        val ticketRef = firestore.collection(Constants.COLLECTION_SUPPORT_TICKETS).document(ticketId)
        val ticketSnap = ticketRef.get().await()
        require(ticketSnap.exists()) { "تذكرة الدعم غير موجودة" }
        val perms = getCurrentAdminPermissions()
        val isAdminReply = perms.hasAnyAccess()
        val isOwner = ticketSnap.getString("userUid") == uid
        require(isOwner || isAdminReply) { "غير مصرح بالرد على هذه التذكرة" }

        val me = getUserOnce(uid)
        val now = System.currentTimeMillis()
        val msgId = UUID.randomUUID().toString()
        val senderLabel = if (isAdminReply && !isOwner) {
            "إدارة WESAL (${me?.numericId?.takeIf { it.isNotBlank() } ?: "10001"})"
        } else {
            me?.displayName?.ifBlank { "@${me.publicId}" } ?: "مستخدم WESAL"
        }
        ticketRef.collection(Constants.SUBCOLLECTION_MESSAGES).document(msgId).set(
            mapOf(
                "messageId" to msgId,
                "ticketId" to ticketId,
                "senderUid" to uid,
                "senderName" to senderLabel,
                "isFromAdmin" to (isAdminReply && !isOwner),
                "text" to cleanText,
                "createdAt" to now
            )
        ).await()

        val nextStatus = updateStatus?.takeIf { it.isNotBlank() }
            ?: if (isAdminReply && !isOwner) "IN_PROGRESS" else "OPEN"
        ticketRef.set(
            mapOf(
                "status" to nextStatus,
                "lastReplyText" to cleanText.take(140),
                "lastReplyByUid" to uid,
                "lastReplyAt" to now,
                "updatedAt" to now
            ),
            SetOptions.merge()
        ).await()
    }

    override suspend fun updateSupportTicketStatus(ticketId: String, status: String): Result<Unit> = runCatching {
        val perms = getCurrentAdminPermissions()
        require(perms.hasAnyAccess()) { "صلاحية الإدارة مطلوبة" }
        val cleanStatus = status.trim().uppercase()
        require(cleanStatus in setOf("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED")) { "حالة التذكرة غير صالحة" }
        firestore.collection(Constants.COLLECTION_SUPPORT_TICKETS)
            .document(ticketId)
            .set(
                mapOf(
                    "status" to cleanStatus,
                    "updatedAt" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            )
            .await()
    }
}

