package com.wesal.messenger.data.repository

import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.wesal.messenger.BuildConfig
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.core.util.PhoneUtils
import com.wesal.messenger.core.util.WesalFrameCatalog
import com.wesal.messenger.core.util.WesalIdUtils
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.data.local.WesalDatabase
import com.wesal.messenger.domain.model.SavedWesalAccount
import com.wesal.messenger.domain.model.SystemWelcomeConfig
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.security.E2EECryptoManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.Period
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val messaging: FirebaseMessaging,
    private val prefs: UserPreferencesDataStore,
    private val database: WesalDatabase,
    private val cryptoManager: E2EECryptoManager
) : AuthRepository {

    override val currentUserId: String?
        get() = auth.currentUser?.uid

    override val isLoggedIn: Boolean
        get() = auth.currentUser != null

    override val currentUserDisplayName: String?
        get() = auth.currentUser?.displayName

    override val currentUserEmail: String?
        get() = auth.currentUser?.email

    override fun observeAuthState(): Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser?.uid)
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    private suspend fun isIdentifierBanned(rawEmail: String): Pair<Boolean, String> {
        val clean = rawEmail.trim().lowercase()
        if (clean.isBlank()) return false to ""
        val emailHash = PhoneUtils.sha256Hash("email:$clean")
        val snap = runCatching {
            firestore.collection(Constants.COLLECTION_BANNED_IDENTIFIERS)
                .document(emailHash)
                .get()
                .await()
        }.getOrNull() ?: return false to ""
        if (!snap.exists()) return false to ""
        val expiresAt = snap.getLong("expiresAt") ?: 0L
        val permanent = snap.getBoolean("permanent") ?: (expiresAt == 0L)
        if (!permanent && expiresAt > 0L && System.currentTimeMillis() > expiresAt) {
            return false to ""
        }
        val reason = snap.getString("reason").orEmpty()
        return true to reason
    }

    private suspend fun assertUserNotBannedOrSignOut(uid: String) {
        val snap = runCatching {
            firestore.collection(Constants.COLLECTION_USERS).document(uid).get().await()
        }.getOrNull() ?: return
        val deletedAt = snap.getLong("deletedAt") ?: 0L
        if (deletedAt > 0L) {
            auth.signOut()
            throw IllegalStateException("تم حذف هذا الحساب نهائياً")
        }
        val isBanned = snap.getBoolean("isBanned") ?: false
        if (isBanned) {
            val banType = snap.getString("banType").orEmpty()
            val banExpiresAt = snap.getLong("banExpiresAt") ?: 0L
            val reason = snap.getString("banReason").orEmpty()
            val now = System.currentTimeMillis()
            if (banType != "PERMANENT" && banExpiresAt > 0L && now > banExpiresAt) {
                runCatching {
                    firestore.collection(Constants.COLLECTION_USERS).document(uid).set(
                        mapOf(
                            "isBanned" to false,
                            "banType" to "",
                            "banExpiresAt" to 0L
                        ),
                        SetOptions.merge()
                    ).await()
                }
            } else {
                auth.signOut()
                val msg = buildString {
                    append("تم حظر هذا الحساب من استخدام WESAL")
                    if (reason.isNotBlank()) append(": $reason")
                }
                throw IllegalStateException(msg)
            }
        }
    }

    override suspend fun signInWithGoogleIdToken(idToken: String): Result<Boolean> {
        return try {
            val previousUid = auth.currentUser?.uid
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = auth.signInWithCredential(credential).await()
            val firebaseUser = authResult.user ?: error("تعذر تسجيل الدخول عبر Google")

            val (emailBanned, banReason) = isIdentifierBanned(firebaseUser.email.orEmpty())
            if (emailBanned) {
                auth.signOut()
                error(
                    if (banReason.isNotBlank()) "هذا البريد محظور من استخدام WESAL: $banReason"
                    else "هذا البريد محظور من استخدام WESAL"
                )
            }
            assertUserNotBannedOrSignOut(firebaseUser.uid)

            if (previousUid != null && previousUid != firebaseUser.uid) {
                clearActiveSessionForSwitch(previousUid)
            }
            val needsSetup = ensureUserSkeletonExists(
                uid = firebaseUser.uid,
                phone = firebaseUser.phoneNumber.orEmpty(),
                email = firebaseUser.email.orEmpty(),
                suggestedName = firebaseUser.displayName.orEmpty(),
                suggestedPhoto = firebaseUser.photoUrl?.toString().orEmpty(),
                phoneVerified = !firebaseUser.phoneNumber.isNullOrBlank(),
                provider = "google"
            )
            prefs.setCachedStartupProfileState(firebaseUser.uid, !needsSetup)
            registerDeviceSession(firebaseUser.uid)
            syncFcmToken()
            if (!needsSetup) {
                runCatching { sendSingleOfficialWelcomeIfNeeded(firebaseUser.uid, firebaseUser.displayName.orEmpty()) }
            }
            Result.success(needsSetup)
        } catch (e: Exception) {
            Result.failure(Exception(mapSparkAuthMessage(e, "تعذر تسجيل الدخول عبر Google"), e))
        }
    }

    override suspend fun signInWithEmail(email: String, password: String): Result<Boolean> {
        return try {
            val cleanEmail = email.trim()
            val (emailBanned, banReason) = isIdentifierBanned(cleanEmail)
            if (emailBanned) {
                error(
                    if (banReason.isNotBlank()) "هذا البريد محظور من استخدام WESAL: $banReason"
                    else "هذا البريد محظور من استخدام WESAL"
                )
            }
            val previousUid = auth.currentUser?.uid
            val authResult = auth.signInWithEmailAndPassword(cleanEmail, password).await()
            val firebaseUser = authResult.user ?: error("تعذر تسجيل الدخول بالبريد الإلكتروني")
            assertUserNotBannedOrSignOut(firebaseUser.uid)

            if (previousUid != null && previousUid != firebaseUser.uid) {
                clearActiveSessionForSwitch(previousUid)
            }
            val needsSetup = ensureUserSkeletonExists(
                uid = firebaseUser.uid,
                phone = firebaseUser.phoneNumber.orEmpty(),
                email = firebaseUser.email ?: cleanEmail,
                suggestedName = firebaseUser.displayName.orEmpty(),
                suggestedPhoto = firebaseUser.photoUrl?.toString().orEmpty(),
                phoneVerified = !firebaseUser.phoneNumber.isNullOrBlank(),
                provider = "email"
            )
            prefs.setCachedStartupProfileState(firebaseUser.uid, !needsSetup)
            registerDeviceSession(firebaseUser.uid)
            syncFcmToken()
            if (!needsSetup) {
                runCatching { sendSingleOfficialWelcomeIfNeeded(firebaseUser.uid, firebaseUser.displayName.orEmpty()) }
            }
            Result.success(needsSetup)
        } catch (e: Exception) {
            Result.failure(Exception(mapSparkAuthMessage(e, "فشل تسجيل الدخول بالبريد الإلكتروني"), e))
        }
    }

    override suspend fun signUpWithEmail(email: String, password: String, fullName: String): Result<Boolean> {
        return try {
            val cleanEmail = email.trim()
            val (emailBanned, banReason) = isIdentifierBanned(cleanEmail)
            if (emailBanned) {
                error(
                    if (banReason.isNotBlank()) "هذا البريد محظور من التسجيل في WESAL: $banReason"
                    else "هذا البريد محظور من التسجيل في WESAL"
                )
            }

            val previousUid = auth.currentUser?.uid
            val authResult = auth.createUserWithEmailAndPassword(cleanEmail, password).await()
            val firebaseUser = authResult.user ?: error("فشل إنشاء الحساب بالبريد الإلكتروني")
            if (previousUid != null && previousUid != firebaseUser.uid) {
                clearActiveSessionForSwitch(previousUid)
            }
            runCatching { firebaseUser.sendEmailVerification().await() }
            if (fullName.isNotBlank()) {
                runCatching {
                    val profileUpdates = com.google.firebase.auth.UserProfileChangeRequest.Builder()
                        .setDisplayName(fullName.trim())
                        .build()
                    firebaseUser.updateProfile(profileUpdates).await()
                }
            }
            val needsSetup = ensureUserSkeletonExists(
                uid = firebaseUser.uid,
                phone = "",
                email = firebaseUser.email ?: cleanEmail,
                suggestedName = fullName.trim(),
                suggestedPhoto = "",
                phoneVerified = false,
                provider = "email"
            )
            prefs.setCachedStartupProfileState(firebaseUser.uid, !needsSetup)
            registerDeviceSession(firebaseUser.uid)
            syncFcmToken()
            Result.success(needsSetup)
        } catch (e: Exception) {
            Result.failure(Exception(mapSparkAuthMessage(e, "تعذر إنشاء الحساب"), e))
        }
    }

    override suspend fun sendPasswordReset(email: String): Result<Unit> {
        return try {
            auth.sendPasswordResetEmail(email.trim()).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(mapSparkAuthMessage(e, "تعذر إرسال رابط استعادة كلمة المرور"), e))
        }
    }

    override suspend fun signInGuestForSupport(): Result<String> {
        return try {
            val currentUid = auth.currentUser?.uid
            if (!currentUid.isNullOrBlank()) {
                return Result.success(currentUid)
            }
            val anonResult = auth.signInAnonymously().await()
            val uid = anonResult.user?.uid ?: error("تعذر فتح جلسة التواصل السريعة")
            val pubKey = cryptoManager.getOrCreateIdentityPublicKeyBase64(uid)
            firestore.collection(Constants.COLLECTION_USERS).document(uid).set(
                mapOf(
                    "uid" to uid,
                    "displayName" to "زائر دعم WESAL",
                    "publicId" to "guest_${uid.take(6).lowercase()}",
                    "bio" to "تواصل مع إدارة WESAL",
                    "isGuestSupport" to true,
                    "hiddenFromSearch" to true,
                    "isOnline" to true,
                    "publicKey" to pubKey,
                    "createdAt" to System.currentTimeMillis(),
                    "lastSeen" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
            Result.success(uid)
        } catch (e: Exception) {
            val msg = if (e is FirebaseAuthException && e.errorCode == "ERROR_OPERATION_NOT_ALLOWED") {
                "يرجى تسجيل الدخول بحساب Google أو البريد الإلكتروني أولاً للتواصل المباشر مع حساب الإدارة 10001 داخل WESAL."
            } else {
                mapSparkAuthMessage(e, "سجّل الدخول بحسابك لبدء المحادثة المباشرة مع إدارة WESAL (10001)")
            }
            Result.failure(Exception(msg, e))
        }
    }

    private suspend fun isGlobalCustomerFrameEnabled(): Boolean {
        val snap = runCatching {
            firestore.collection(Constants.COLLECTION_SYSTEM_CONFIG)
                .document("admin_settings")
                .get()
                .await()
        }.getOrNull()
        return snap?.getBoolean("globalCustomerFrameEnabled") ?: true
    }

    private suspend fun allocateUniqueNumericId(uid: String, email: String = auth.currentUser?.email.orEmpty()): String {
        val userRef = firestore.collection(Constants.COLLECTION_USERS).document(uid)
        val cleanEmail = email.trim()
        if (Constants.SUPER_ADMIN_EMAIL.equals(cleanEmail, ignoreCase = true)) {
            userRef.set(mapOf("numericId" to Constants.OWNER_ADMIN_NUMERIC_ID), SetOptions.merge()).await()
            return Constants.OWNER_ADMIN_NUMERIC_ID
        }
        if (Constants.SECONDARY_ADMIN_EMAIL.equals(cleanEmail, ignoreCase = true)) {
            userRef.set(
                mapOf(
                    "numericId" to Constants.SECONDARY_ADMIN_NUMERIC_ID,
                    "hiddenFromSearch" to true
                ),
                SetOptions.merge()
            ).await()
            return Constants.SECONDARY_ADMIN_NUMERIC_ID
        }
        val existingSnap = runCatching { userRef.get().await() }.getOrNull()
        val existing = existingSnap?.getString("numericId").orEmpty()
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
            val value = txResult.getOrNull()
            if (!value.isNullOrBlank() && WesalIdUtils.isValidNumericId(value)) {
                return value
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

    @Suppress("UNCHECKED_CAST")
    private suspend fun ensureUserSkeletonExists(
        uid: String,
        phone: String,
        email: String,
        suggestedName: String,
        suggestedPhoto: String,
        phoneVerified: Boolean,
        provider: String = "email"
    ): Boolean {
        val userRef = firestore.collection(Constants.COLLECTION_USERS).document(uid)
        val snapshot = userRef.get().await()
        val pubKey = cryptoManager.getOrCreateIdentityPublicKeyBase64(uid)
        val normalizedPhone = if (phone.isNotBlank()) PhoneUtils.normalizeToE164(phone) ?: phone else ""
        val verifiedPhoneHash = if (phoneVerified && normalizedPhone.isNotBlank()) {
            PhoneUtils.sha256Hash(normalizedPhone)
        } else {
            ""
        }

        if (!snapshot.exists()) {
            val uniquePublicId = WesalIdUtils.generateDefault(uid)
            val applyGlobalFrame = isGlobalCustomerFrameEnabled()
            val initialFrame = if (applyGlobalFrame) WesalFrameCatalog.DEFAULT_ALL_CUSTOMERS_FRAME_ID else ""
            val isSecondaryAdmin = Constants.SECONDARY_ADMIN_EMAIL.equals(email.trim(), ignoreCase = true)
            val newUserMap = hashMapOf(
                "uid" to uid,
                "email" to email.trim(),
                "phoneNumber" to normalizedPhone,
                "phoneHash" to verifiedPhoneHash,
                "isPhoneVerified" to phoneVerified,
                "isVerified" to false,
                "verificationType" to "BLUE",
                "displayName" to suggestedName.trim(),
                "publicId" to uniquePublicId,
                "bio" to "أستخدم تطبيق WESAL",
                "photoUrl" to suggestedPhoto,
                "coverUrl" to "",
                "birthDate" to "",
                "frameId" to initialFrame,
                "frameExpiresAt" to 0L,
                "hiddenFromSearch" to isSecondaryAdmin,
                "profileCompleted" to false,
                "welcomeSent" to false,
                "createdAt" to System.currentTimeMillis(),
                "lastSeen" to FieldValue.serverTimestamp(),
                "lastHeartbeatAt" to System.currentTimeMillis(),
                "isOnline" to true,
                "publicKey" to pubKey,
                "blockedUsers" to emptyList<String>(),
                "privacySettings" to mapOf(
                    "lastSeenVisibility" to "everyone",
                    "onlineVisibility" to "everyone",
                    "profilePhotoVisibility" to "everyone",
                    "storiesVisibility" to "everyone",
                    "readReceiptsEnabled" to true,
                    "hidePhoneNumber" to false,
                    "hideBirthDate" to false,
                    "recordProfileVisits" to true
                )
            )
            userRef.set(newUserMap, SetOptions.merge()).await()
            val assignedNumericId = allocateUniqueNumericId(uid, email)
            prefs.upsertSavedAccount(
                SavedWesalAccount(
                    uid = uid,
                    displayName = suggestedName.trim(),
                    email = email.trim(),
                    phoneNumber = normalizedPhone,
                    publicId = uniquePublicId,
                    numericId = assignedNumericId,
                    photoUrl = suggestedPhoto,
                    provider = provider,
                    lastUsedAt = System.currentTimeMillis()
                )
            )
            return true
        } else {
            val existingName = snapshot.getString("displayName").orEmpty()
            val existingPhoto = snapshot.getString("photoUrl").orEmpty()
            val existingPublicId = snapshot.getString("publicId").orEmpty()
            val existingNumericId = snapshot.getString("numericId").orEmpty()
            val existingPhone = snapshot.getString("phoneNumber").orEmpty()
            val existingEmail = snapshot.getString("email").orEmpty()
            val existingBirthDate = snapshot.getString("birthDate").orEmpty()
            val existingCompleted = snapshot.getBoolean("profileCompleted")
            val existingVerified = snapshot.getBoolean("isPhoneVerified")
                ?: (existingPhone.isNotBlank() && existingEmail.isBlank())

            val privSnap = runCatching {
                userRef.collection(Constants.SUBCOLLECTION_PRIVATE).document("info").get().await()
            }.getOrNull()
            val privatePhone = privSnap?.getString("phoneNumber").orEmpty()
            val privateBirthDate = privSnap?.getString("birthDate").orEmpty()
            val effectivePhone = existingPhone.ifBlank { privatePhone }.ifBlank { normalizedPhone }
            val effectiveDob = existingBirthDate.ifBlank { privateBirthDate }

            val privacyMap = snapshot.get("privacySettings") as? Map<String, Any?> ?: emptyMap()
            val hidePhone = (privacyMap["hidePhoneNumber"] as? Boolean) ?: false

            val updateMap = hashMapOf<String, Any>(
                "publicKey" to pubKey,
                "isOnline" to true,
                "lastSeen" to FieldValue.serverTimestamp(),
                "lastHeartbeatAt" to System.currentTimeMillis()
            )
            val finalPublicId = existingPublicId.ifBlank { WesalIdUtils.generateDefault(uid) }
            if (existingPublicId.isBlank()) {
                updateMap["publicId"] = finalPublicId
            }
            if (existingEmail.isBlank() && email.isNotBlank()) {
                updateMap["email"] = email.trim()
            }
            if (existingName.isBlank() && suggestedName.isNotBlank()) {
                updateMap["displayName"] = suggestedName.trim()
            }
            if (existingPhoto.isBlank() && suggestedPhoto.isNotBlank()) {
                updateMap["photoUrl"] = suggestedPhoto
            }
            if (phoneVerified || existingVerified) {
                updateMap["isPhoneVerified"] = true
                if (!hidePhone && effectivePhone.isNotBlank()) {
                    val norm = PhoneUtils.normalizeToE164(effectivePhone) ?: effectivePhone
                    updateMap["phoneHash"] = PhoneUtils.sha256Hash(norm)
                }
            } else if (snapshot.getBoolean("isPhoneVerified") == null) {
                updateMap["isPhoneVerified"] = false
            }

            val resolvedEmail = existingEmail.ifBlank { email.trim() }
            if (Constants.SECONDARY_ADMIN_EMAIL.equals(resolvedEmail, ignoreCase = true)) {
                updateMap["hiddenFromSearch"] = true
                if (existingNumericId != Constants.SECONDARY_ADMIN_NUMERIC_ID) {
                    updateMap["numericId"] = Constants.SECONDARY_ADMIN_NUMERIC_ID
                }
            } else if (Constants.SUPER_ADMIN_EMAIL.equals(resolvedEmail, ignoreCase = true)) {
                if (existingNumericId != Constants.OWNER_ADMIN_NUMERIC_ID) {
                    updateMap["numericId"] = Constants.OWNER_ADMIN_NUMERIC_ID
                }
            }
            userRef.set(updateMap, SetOptions.merge()).await()
            val finalNumericId = when {
                Constants.SECONDARY_ADMIN_EMAIL.equals(resolvedEmail, ignoreCase = true) -> Constants.SECONDARY_ADMIN_NUMERIC_ID
                Constants.SUPER_ADMIN_EMAIL.equals(resolvedEmail, ignoreCase = true) -> Constants.OWNER_ADMIN_NUMERIC_ID
                WesalIdUtils.isValidNumericId(existingNumericId) -> existingNumericId
                else -> allocateUniqueNumericId(uid, resolvedEmail)
            }
            prefs.upsertSavedAccount(
                SavedWesalAccount(
                    uid = uid,
                    displayName = existingName.ifBlank { suggestedName.trim() },
                    email = existingEmail.ifBlank { email.trim() },
                    phoneNumber = effectivePhone,
                    publicId = finalPublicId,
                    numericId = finalNumericId,
                    photoUrl = existingPhoto.ifBlank { suggestedPhoto },
                    provider = provider,
                    lastUsedAt = System.currentTimeMillis()
                )
            )
            // Grandfathered existing completed accounts stay logged in if they already completed onboarding or have mandatory fields filled
            val hasName = existingName.isNotBlank() || suggestedName.isNotBlank()
            val hasPhone = effectivePhone.isNotBlank()
            val hasDob = effectiveDob.isNotBlank()
            val isComplete = when (existingCompleted) {
                true -> hasName && hasPhone && hasDob
                false -> false
                null -> hasName && hasPhone && hasDob
            }
            return !isComplete
        }
    }

    private suspend fun reserveUniquePhone(uid: String, e164Phone: String) {
        val hash = PhoneUtils.sha256Hash(e164Phone)
        // Check banned identifiers first if applicable
        val banSnap = runCatching {
            firestore.collection(Constants.COLLECTION_BANNED_IDENTIFIERS)
                .document("phone_$hash")
                .get()
                .await()
        }.getOrNull()
        if (banSnap != null && banSnap.exists()) {
            val expiresAt = banSnap.getLong("expiresAt") ?: 0L
            val permanent = banSnap.getBoolean("permanent") ?: (expiresAt == 0L)
            if (permanent || (expiresAt > 0L && System.currentTimeMillis() <= expiresAt)) {
                throw IllegalStateException("هذا الرقم مرتبط بحساب محظور من استخدام WESAL")
            }
        }

        // Check public phoneHash conflicts
        val dupPublic = runCatching {
            firestore.collection(Constants.COLLECTION_USERS)
                .whereEqualTo("phoneHash", hash)
                .limit(3)
                .get()
                .await()
        }.getOrNull()
        if (dupPublic != null && dupPublic.documents.any { it.id != uid }) {
            throw IllegalArgumentException("رقم الهاتف هذا مستخدم بالفعل في حساب WESAL آخر")
        }

        // Check server-side phone uniqueness index (works even if phone is hidden)
        val phoneIdxRef = firestore.collection(Constants.COLLECTION_PHONE_UNIQUENESS).document(hash)
        val idxTx = runCatching {
            firestore.runTransaction { tx ->
                val idxSnap = tx.get(phoneIdxRef)
                val ownerUid = idxSnap.getString("uid").orEmpty()
                if (idxSnap.exists() && ownerUid.isNotBlank() && ownerUid != uid) {
                    throw IllegalArgumentException("رقم الهاتف هذا مرتبط بالفعل بحساب آخر")
                }
                tx.set(
                    phoneIdxRef,
                    mapOf(
                        "uid" to uid,
                        "updatedAt" to System.currentTimeMillis()
                    )
                )
            }.await()
        }
        val ex = idxTx.exceptionOrNull()
        if (ex is IllegalArgumentException) throw ex
    }

    private fun validateBirthDate(birthDateIso: String): String {
        val clean = birthDateIso.trim()
        require(clean.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$"))) {
            "يرجى اختيار تاريخ ميلاد صحيح"
        }
        val parsed = runCatching { LocalDate.parse(clean) }.getOrNull()
            ?: throw IllegalArgumentException("تاريخ الميلاد غير صالح")
        val today = LocalDate.now()
        require(!parsed.isAfter(today)) {
            "لا يمكن أن يكون تاريخ الميلاد في المستقبل"
        }
        val years = Period.between(parsed, today).years
        require(years >= Constants.MIN_AGE_YEARS) {
            "يجب أن يكون العمر ${Constants.MIN_AGE_YEARS} سنة على الأقل لاستخدام WESAL"
        }
        return clean
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun completeUserProfile(
        displayName: String,
        bio: String,
        photoUri: Uri?,
        wesalId: String?,
        phoneE164: String?,
        birthDate: String?,
        hideBirthDate: Boolean,
        locationGranted: Boolean,
        locationGraceStartedAt: Long
    ): Result<WesalUser> {
        return try {
            val uid = currentUserId ?: error("المستخدم غير مسجل الدخول")
            val cleanName = displayName.trim()
            require(cleanName.length >= 2) { "الاسم الظاهر مطلوب (حرفان على الأقل)" }

            val cleanPhone = phoneE164?.trim().orEmpty()
            val normalizedPhone = PhoneUtils.normalizeToE164(cleanPhone)
                ?: throw IllegalArgumentException("يرجى إدخال رقم هاتف دولي صحيح")
            reserveUniquePhone(uid, normalizedPhone)

            val validDob = validateBirthDate(birthDate.orEmpty())

            val docRef = firestore.collection(Constants.COLLECTION_USERS).document(uid)
            val existingSnap = docRef.get().await()
            val existingPrivacy = (existingSnap.get("privacySettings") as? Map<String, Any?>)?.toMutableMap()
                ?: mutableMapOf()
            val hidePhone = (existingPrivacy["hidePhoneNumber"] as? Boolean) ?: false
            existingPrivacy["hideBirthDate"] = hideBirthDate

            val updates = hashMapOf<String, Any>(
                "displayName" to cleanName,
                "bio" to bio.trim().ifBlank { "أستخدم تطبيق WESAL" },
                "privacySettings" to existingPrivacy,
                "birthDate" to if (hideBirthDate) "" else validDob,
                "phoneNumber" to if (hidePhone) "" else normalizedPhone,
                "phoneHash" to if (hidePhone) "" else PhoneUtils.sha256Hash(normalizedPhone),
                "locationGranted" to locationGranted,
                "locationFallbackStartedAt" to locationGraceStartedAt,
                "profileCompleted" to true,
                "isOnline" to true,
                "lastSeen" to FieldValue.serverTimestamp(),
                "lastHeartbeatAt" to System.currentTimeMillis()
            )

            // Assign default global customer frame if user has none and global option enabled
            val currentFrameId = existingSnap.getString("frameId").orEmpty()
            if (currentFrameId.isBlank() && isGlobalCustomerFrameEnabled()) {
                updates["frameId"] = WesalFrameCatalog.DEFAULT_ALL_CUSTOMERS_FRAME_ID
                updates["frameExpiresAt"] = 0L
            }

            if (!wesalId.isNullOrBlank()) {
                val normalizedId = WesalIdUtils.normalize(wesalId)
                require(WesalIdUtils.isValid(normalizedId)) {
                    "معرف وصال (Wesal ID) يجب أن يتكون من 4 إلى 30 حرفاً بالإنجليزية أو أرقام أو _"
                }
                val dupSnap = firestore.collection(Constants.COLLECTION_USERS)
                    .whereEqualTo("publicId", normalizedId)
                    .get()
                    .await()
                if (dupSnap.documents.any { it.id != uid }) {
                    return Result.failure(IllegalArgumentException("معرف وصال @$normalizedId مستخدم بالفعل، اختر معرفاً آخر"))
                }
                updates["publicId"] = normalizedId
            } else if (existingSnap.getString("publicId").isNullOrBlank()) {
                updates["publicId"] = WesalIdUtils.generateDefault(uid)
            }

            var uploadedPhotoUrl: String? = null
            if (photoUri != null) {
                uploadedPhotoUrl = withContext(Dispatchers.IO) {
                    uploadProfileMediaToStorageOrSmallFallback(
                        context,
                        "avatars/$uid/avatar_${System.currentTimeMillis()}",
                        photoUri,
                        maxSide = 512,
                        quality = 82
                    )
                }
                if (uploadedPhotoUrl.isNotBlank()) {
                    updates["photoUrl"] = uploadedPhotoUrl
                }
            }

            // Always persist full private details for owner & Super Admin 10001 inspection (Sec 7, 9, 11)
            val privateInfo = hashMapOf<String, Any>(
                "phoneNumber" to normalizedPhone,
                "birthDate" to validDob,
                "updatedAt" to System.currentTimeMillis()
            )
            if (!uploadedPhotoUrl.isNullOrBlank()) {
                privateInfo["hiddenPhotoUrl"] = uploadedPhotoUrl
            }
            docRef.collection(Constants.SUBCOLLECTION_PRIVATE)
                .document("info")
                .set(privateInfo, SetOptions.merge())
                .await()

            docRef.set(updates, SetOptions.merge()).await()
            if (!WesalIdUtils.isValidNumericId(existingSnap.getString("numericId"))) {
                allocateUniqueNumericId(uid)
            }
            prefs.setCachedStartupProfileState(uid, true)
            prefs.setLocationState(locationGranted, locationGraceStartedAt)
            syncFcmToken()
            registerDeviceSession(uid)

            // Deliver single deterministic official WESAL Messenger welcome message (Sec 15 & Sec 26)
            runCatching { sendSingleOfficialWelcomeIfNeeded(uid, cleanName) }

            val snap = docRef.get().await()
            val savedUser = snap.toWesalUser().copy(
                phoneNumber = normalizedPhone,
                birthDate = validDob
            )
            val providerId = auth.currentUser?.providerData?.map { it.providerId }.orEmpty()
            val provider = when {
                providerId.contains("google.com") -> "google"
                providerId.contains("phone") -> "phone"
                else -> "email"
            }
            prefs.upsertSavedAccount(
                SavedWesalAccount(
                    uid = savedUser.uid,
                    displayName = savedUser.displayName,
                    email = savedUser.email,
                    phoneNumber = normalizedPhone,
                    publicId = savedUser.publicId,
                    numericId = savedUser.numericId,
                    photoUrl = savedUser.photoUrl,
                    provider = provider,
                    lastUsedAt = System.currentTimeMillis()
                )
            )
            Result.success(savedUser)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun sendSingleOfficialWelcomeIfNeeded(uid: String, displayName: String) {
        if (uid.isBlank()) return
        val userRef = firestore.collection(Constants.COLLECTION_USERS).document(uid)
        val uSnap = userRef.get().await()
        if (uSnap.getBoolean("welcomeSent") == true) return
        val numericId = uSnap.getString("numericId").orEmpty()
        if (Constants.isProtectedAdminNumericId(numericId)) return

        val welcomeCfgSnap = runCatching {
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
        val enabled = welcomeCfgSnap?.getBoolean("enabled") ?: true
        if (!enabled) return

        val template = welcomeCfgSnap?.getString("messageTemplate")
            ?.takeIf { it.isNotBlank() }
            ?: SystemWelcomeConfig.DEFAULT_OFFICIAL_WELCOME_TEMPLATE
        val nameForGreeting = displayName.trim().ifBlank { "صديق وصال" }
        val welcomeText = template.replace("[اسم المستخدم]", nameForGreeting)

        val officialChatId = "${Constants.OFFICIAL_CHAT_PREFIX}$uid"
        val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(officialChatId)
        val msgRef = chatRef.collection(Constants.COLLECTION_MESSAGES).document("welcome_$uid")

        val now = System.currentTimeMillis()
        val existingMsgs = runCatching { chatRef.collection(Constants.COLLECTION_MESSAGES).limit(1).get().await() }.getOrNull()
        if (existingMsgs != null && !existingMsgs.isEmpty) {
            userRef.set(mapOf("welcomeSent" to true), SetOptions.merge()).await()
            return
        }

        chatRef.set(
            mapOf(
                "chatId" to officialChatId,
                "isGroup" to false,
                "isOfficialWesalChat" to true,
                "groupName" to "WESAL Messenger",
                "isSupportChat" to false,
                "participantIds" to listOf(uid),
                "lastMessagePreview" to "مرحبًا بك يا $nameForGreeting 👋",
                "lastMessageTime" to now,
                "lastMessageSenderId" to Constants.OFFICIAL_WESAL_SENDER_ID,
                "lastMessageStatus" to "SENT",
                "pinnedBy" to mapOf(uid to true),
                "unreadCounts" to mapOf(uid to 1),
                "createdAt" to now
            ),
            SetOptions.merge()
        ).await()

        msgRef.set(
            mapOf(
                "messageId" to "welcome_$uid",
                "chatId" to officialChatId,
                "senderId" to Constants.OFFICIAL_WESAL_SENDER_ID,
                "senderName" to Constants.OFFICIAL_WESAL_SENDER_NAME,
                "type" to "TEXT",
                "text" to welcomeText,
                "ciphertext" to "",
                "isEncrypted" to false,
                "status" to "SENT",
                "timestamp" to now
            )
        ).await()

        userRef.set(mapOf("welcomeSent" to true), SetOptions.merge()).await()
    }

    override suspend fun syncFcmToken() {
        val uid = currentUserId ?: return
        runCatching {
            val token = messaging.token.await()
            firestore.collection(Constants.COLLECTION_USERS)
                .document(uid)
                .update("fcmToken", token)
                .await()
        }
    }

    private suspend fun registerDeviceSession(uid: String) {
        runCatching {
            val sessionId = prefs.getOrCreateDeviceSessionId()
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            val sessionData = mapOf(
                "sessionId" to sessionId,
                "deviceName" to deviceName,
                "osVersion" to "Android ${Build.VERSION.RELEASE}",
                "appVersion" to BuildConfig.VERSION_NAME,
                "lastActiveAt" to System.currentTimeMillis()
            )
            firestore.collection(Constants.COLLECTION_USERS)
                .document(uid)
                .collection(Constants.COLLECTION_SESSIONS)
                .document(sessionId)
                .set(sessionData, SetOptions.merge())
                .await()
            firestore.collection(Constants.COLLECTION_USERS)
                .document(uid)
                .set(
                    mapOf(
                        "lastDeviceName" to deviceName,
                        "lastAppVersion" to BuildConfig.VERSION_NAME
                    ),
                    SetOptions.merge()
                ).await()
        }
    }

    override suspend fun signOut(): Result<Unit> {
        return try {
            val uid = currentUserId
            if (uid != null) {
                val sessionId = prefs.getOrCreateDeviceSessionId()
                runCatching {
                    firestore.collection(Constants.COLLECTION_USERS)
                        .document(uid)
                        .update(
                            mapOf(
                                "isOnline" to false,
                                "lastSeen" to FieldValue.serverTimestamp(),
                                "lastHeartbeatAt" to 0L,
                                "fcmToken" to ""
                            )
                        ).await()
                    firestore.collection(Constants.COLLECTION_USERS)
                        .document(uid)
                        .collection(Constants.COLLECTION_SESSIONS)
                        .document(sessionId)
                        .delete()
                        .await()
                }
            }

            runCatching { messaging.deleteToken().await() }
            runCatching {
                androidx.credentials.CredentialManager.create(context)
                    .clearCredentialState(androidx.credentials.ClearCredentialStateRequest())
            }
            runCatching {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm?.cancelAll()
            }

            prefs.clearUserSessionState()
            withContext(Dispatchers.IO) {
                database.wesalDao().clearAllChats()
                database.wesalDao().clearAllMessages()
            }

            auth.signOut()
            Result.success(Unit)
        } catch (_: Exception) {
            runCatching { prefs.clearUserSessionState() }
            runCatching {
                androidx.credentials.CredentialManager.create(context)
                    .clearCredentialState(androidx.credentials.ClearCredentialStateRequest())
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    database.wesalDao().clearAllChats()
                    database.wesalDao().clearAllMessages()
                }
            }
            auth.signOut()
            Result.success(Unit)
        }
    }

    override suspend fun deleteAccountPermanently(
        passwordForReauth: String?,
        googleIdTokenForReauth: String?
    ): Result<Unit> {
        return try {
            val firebaseUser = auth.currentUser ?: error("المستخدم غير مسجل الدخول")
            val uid = firebaseUser.uid
            val userRef = firestore.collection(Constants.COLLECTION_USERS).document(uid)
            val snap = userRef.get().await()
            val numericId = snap.getString("numericId").orEmpty()
            if (Constants.isProtectedAdminNumericId(numericId) || Constants.isSuperAdminEmail(firebaseUser.email) || Constants.isSuperAdminUid(uid)) {
                return Result.failure(IllegalStateException("لا يمكن حذف حساب الإدارة الأساسي من التطبيق مباشرة"))
            }

            // Re-authenticate before deleting Firebase Auth account (Sec 42)
            when {
                !googleIdTokenForReauth.isNullOrBlank() -> {
                    val cred = GoogleAuthProvider.getCredential(googleIdTokenForReauth, null)
                    firebaseUser.reauthenticate(cred).await()
                }
                !passwordForReauth.isNullOrBlank() && !firebaseUser.email.isNullOrBlank() -> {
                    val cred = EmailAuthProvider.getCredential(firebaseUser.email!!, passwordForReauth)
                    firebaseUser.reauthenticate(cred).await()
                }
            }

            val privSnap = runCatching {
                userRef.collection(Constants.SUBCOLLECTION_PRIVATE).document("info").get().await()
            }.getOrNull()
            val rawPhone = snap.getString("phoneNumber").orEmpty().ifBlank { privSnap?.getString("phoneNumber").orEmpty() }
            val normPhone = PhoneUtils.normalizeToE164(rawPhone).orEmpty()
            if (normPhone.isNotBlank()) {
                val pHash = PhoneUtils.sha256Hash(normPhone)
                runCatching {
                    firestore.collection(Constants.COLLECTION_PHONE_UNIQUENESS).document(pHash).delete().await()
                }
            }
            if (numericId.isNotBlank()) {
                runCatching {
                    firestore.collection(Constants.COLLECTION_WESAL_ID_INDEX).document(numericId).delete().await()
                }
            }

            // Delete active sessions
            val sessions = runCatching {
                userRef.collection(Constants.COLLECTION_SESSIONS).get().await()
            }.getOrNull()
            sessions?.documents?.forEach { doc ->
                runCatching { doc.reference.delete().await() }
            }

            val now = System.currentTimeMillis()
            // Scrub personal profile fields while honoring minimal tombstone record for deleted account stats (Sec 39 & Sec 42)
            userRef.set(
                mapOf(
                    "displayName" to "حساب محذوف",
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
                    "isOnline" to false,
                    "hiddenFromSearch" to true,
                    "deletedAt" to now,
                    "lastSeen" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()

            runCatching {
                userRef.collection(Constants.SUBCOLLECTION_PRIVATE).document("info").delete().await()
            }

            // Delete Firebase Auth user (triggers FirebaseAuthRecentLoginRequiredException if session is old & no reauth supplied)
            firebaseUser.delete().await()

            runCatching { messaging.deleteToken().await() }
            runCatching {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm?.cancelAll()
            }
            prefs.removeSavedAccount(uid)
            prefs.clearUserSessionState()
            withContext(Dispatchers.IO) {
                database.wesalDao().clearAllChats()
                database.wesalDao().clearAllMessages()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            val msg = when (e) {
                is FirebaseAuthRecentLoginRequiredException ->
                    "لأسباب أمنية، يرجى تأكيد هويتك (إدخال كلمة المرور أو اختيار حساب Google) لإتمام حذف الحساب نهائياً"
                is FirebaseAuthInvalidCredentialsException ->
                    "كلمة المرور غير صحيحة؛ تعذر تأكيد الهوية"
                else -> e.localizedMessage ?: "تعذر حذف الحساب نهائياً"
            }
            Result.failure(Exception(msg, e))
        }
    }

    override fun observeSavedAccounts(): Flow<List<SavedWesalAccount>> = prefs.savedAccountsFlow

    override suspend fun removeSavedAccount(uid: String) {
        prefs.removeSavedAccount(uid)
    }

    override suspend fun clearActiveSessionForSwitch(previousUid: String?) {
        val uid = previousUid ?: currentUserId
        if (uid != null) {
            runCatching {
                firestore.collection(Constants.COLLECTION_USERS)
                    .document(uid)
                    .update(mapOf("isOnline" to false, "lastSeen" to FieldValue.serverTimestamp()))
                    .await()
            }
        }
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm?.cancelAll()
        }
        runCatching {
            withContext(Dispatchers.IO) {
                database.wesalDao().clearAllChats()
                database.wesalDao().clearAllMessages()
            }
        }
    }

    private fun mapSparkAuthMessage(e: Exception, fallback: String): String {
        if (e is IllegalStateException && !e.message.isNullOrBlank()) return e.message!!
        if (e is FirebaseNetworkException) {
            return "لا يوجد اتصال بالإنترنت، تحقق من الشبكة"
        }
        if (e is FirebaseTooManyRequestsException) {
            return "تم إرسال محاولات كثيرة، انتظر قليلاً ثم أعد المحاولة"
        }
        if (e is FirebaseAuthException) {
            when (e.errorCode) {
                "ERROR_OPERATION_NOT_ALLOWED" ->
                    return "طريقة التسجيل هذه غير مفعّلة في Firebase Console. فعّلها ثم أعد المحاولة."
                "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" ->
                    return "هذا البريد مسجل بالفعل بطريقة دخول مختلفة، سجّل الدخول بنفس الطريقة السابقة"
                "ERROR_USER_DISABLED" ->
                    return "تم تعطيل هذا الحساب، تواصل مع إدارة WESAL"
                "ERROR_CREDENTIAL_ALREADY_IN_USE" ->
                    return "بيانات الاعتماد هذه مرتبطة بالفعل بحساب وصال آخر"
                "ERROR_INVALID_EMAIL" ->
                    return "صيغة البريد الإلكتروني غير صحيحة"
            }
        }
        return when (e) {
            is FirebaseAuthInvalidUserException -> "لا يوجد حساب مسجل بهذا البريد الإلكتروني"
            is FirebaseAuthInvalidCredentialsException -> "البريد الإلكتروني أو كلمة المرور غير صحيحة"
            is FirebaseAuthUserCollisionException -> "هذا البريد الإلكتروني مسجل بالفعل في WESAL"
            is FirebaseAuthWeakPasswordException -> "كلمة المرور ضعيفة جداً — استخدم 6 أحرف على الأقل"
            else -> e.localizedMessage?.takeIf { it.isNotBlank() } ?: fallback
        }
    }
}
