package com.wesal.messenger.data.repository

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.wesal.messenger.BuildConfig
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.core.util.PhoneUtils
import com.wesal.messenger.core.util.WesalIdUtils
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.data.local.WesalDatabase
import com.wesal.messenger.domain.model.SavedWesalAccount
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.PhoneAuthEvent
import com.wesal.messenger.security.E2EECryptoManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage,
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

    override suspend fun signInWithGoogleIdToken(idToken: String): Result<Boolean> {
        return try {
            val previousUid = auth.currentUser?.uid
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = auth.signInWithCredential(credential).await()
            val firebaseUser = authResult.user ?: error("Unable to sign in with Google")
            if (previousUid != null && previousUid != firebaseUser.uid) {
                clearActiveSessionForSwitch()
            }
            prefs.clearPendingOtp()
            val isNewProfile = ensureUserSkeletonExists(
                uid = firebaseUser.uid,
                phone = firebaseUser.phoneNumber.orEmpty(),
                email = firebaseUser.email.orEmpty(),
                suggestedName = firebaseUser.displayName.orEmpty(),
                suggestedPhoto = firebaseUser.photoUrl?.toString().orEmpty(),
                phoneVerified = !firebaseUser.phoneNumber.isNullOrBlank(),
                provider = "google"
            )
            registerDeviceSession(firebaseUser.uid)
            syncFcmToken()
            Result.success(isNewProfile)
        } catch (e: Exception) {
            Result.failure(Exception(mapSparkAuthMessage(e, "Unable to sign in with Google"), e))
        }
    }

    override suspend fun signInWithEmail(email: String, password: String): Result<Boolean> {
        return try {
            val previousUid = auth.currentUser?.uid
            val authResult = auth.signInWithEmailAndPassword(email.trim(), password).await()
            val firebaseUser = authResult.user ?: error("Unable to sign in with email")
            if (previousUid != null && previousUid != firebaseUser.uid) {
                clearActiveSessionForSwitch()
            }
            prefs.clearPendingOtp()
            val isNewProfile = ensureUserSkeletonExists(
                uid = firebaseUser.uid,
                phone = firebaseUser.phoneNumber.orEmpty(),
                email = firebaseUser.email ?: email.trim(),
                suggestedName = firebaseUser.displayName.orEmpty(),
                suggestedPhoto = firebaseUser.photoUrl?.toString().orEmpty(),
                phoneVerified = !firebaseUser.phoneNumber.isNullOrBlank(),
                provider = "email"
            )
            registerDeviceSession(firebaseUser.uid)
            syncFcmToken()
            Result.success(isNewProfile)
        } catch (e: Exception) {
            Result.failure(Exception(mapSparkAuthMessage(e, "Email sign-in failed"), e))
        }
    }

    override suspend fun signUpWithEmail(email: String, password: String): Result<Boolean> {
        return try {
            val previousUid = auth.currentUser?.uid
            val authResult = auth.createUserWithEmailAndPassword(email.trim(), password).await()
            val firebaseUser = authResult.user ?: error("Email registration failed")
            if (previousUid != null && previousUid != firebaseUser.uid) {
                clearActiveSessionForSwitch()
            }
            runCatching { firebaseUser.sendEmailVerification().await() }
            prefs.clearPendingOtp()
            val isNewProfile = ensureUserSkeletonExists(
                uid = firebaseUser.uid,
                phone = "",
                email = firebaseUser.email ?: email.trim(),
                suggestedName = "",
                suggestedPhoto = "",
                phoneVerified = false,
                provider = "email"
            )
            registerDeviceSession(firebaseUser.uid)
            syncFcmToken()
            Result.success(isNewProfile)
        } catch (e: Exception) {
            Result.failure(Exception(mapSparkAuthMessage(e, "Email registration failed"), e))
        }
    }

    override suspend fun sendPasswordReset(email: String): Result<Unit> {
        return try {
            auth.sendPasswordResetEmail(email.trim()).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(mapSparkAuthMessage(e, "Failed to send password reset email"), e))
        }
    }

    override fun sendOtp(
        phoneNumber: String,
        activity: Activity,
        resendingToken: PhoneAuthProvider.ForceResendingToken?
    ): Flow<PhoneAuthEvent> = callbackFlow {
        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                launch {
                    try {
                        val result = auth.signInWithCredential(credential).await()
                        val uid = result.user?.uid ?: return@launch
                        prefs.clearPendingOtp()
                        val isNew = ensureUserSkeletonExists(
                            uid = uid,
                            phone = result.user?.phoneNumber ?: phoneNumber,
                            email = result.user?.email.orEmpty(),
                            suggestedName = "",
                            suggestedPhoto = "",
                            phoneVerified = true,
                            provider = "phone"
                        )
                        registerDeviceSession(uid)
                        syncFcmToken()
                        trySend(PhoneAuthEvent.Verified(uid, isNew))
                        close()
                    } catch (e: Exception) {
                        trySend(mapAuthError(e))
                    }
                }
            }

            override fun onVerificationFailed(e: FirebaseException) {
                trySend(mapAuthError(e))
                close()
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken
            ) {
                launch {
                    prefs.savePendingOtp(verificationId, phoneNumber)
                }
                trySend(PhoneAuthEvent.CodeSent(verificationId, token))
            }
        }

        val builder = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(Constants.OTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)

        if (resendingToken != null) {
            builder.setForceResendingToken(resendingToken)
        }

        PhoneAuthProvider.verifyPhoneNumber(builder.build())
        awaitClose { }
    }

    override suspend fun verifyOtpCode(verificationId: String, smsCode: String): Result<Boolean> {
        return try {
            val credential = PhoneAuthProvider.getCredential(verificationId, smsCode.trim())
            val authResult = auth.signInWithCredential(credential).await()
            val uid = authResult.user?.uid ?: error("User UID missing after verification")
            val phone = authResult.user?.phoneNumber.orEmpty()
            prefs.clearPendingOtp()
            val isNewProfile = ensureUserSkeletonExists(
                uid = uid,
                phone = phone,
                email = authResult.user?.email.orEmpty(),
                suggestedName = "",
                suggestedPhoto = "",
                phoneVerified = true,
                provider = "phone"
            )
            registerDeviceSession(uid)
            syncFcmToken()
            Result.success(isNewProfile)
        } catch (e: Exception) {
            val message = when (e) {
                is FirebaseAuthInvalidCredentialsException -> "رمز التحقق غير صحيح أو منتهي الصلاحية"
                is FirebaseNetworkException -> "تعذر الاتصال بالشبكة، تحقق من الإنترنت"
                else -> e.localizedMessage ?: "فشل التحقق من الرمز"
            }
            Result.failure(Exception(message, e))
        }
    }

    override fun sendOtpForPhoneLink(
        phoneNumber: String,
        activity: Activity,
        resendingToken: PhoneAuthProvider.ForceResendingToken?
    ): Flow<PhoneAuthEvent> = callbackFlow {
        val currentFirebaseUser = auth.currentUser
        if (currentFirebaseUser == null) {
            trySend(PhoneAuthEvent.Error("المستخدم غير مسجل الدخول", "User is not signed in"))
            close()
            return@callbackFlow
        }

        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                launch {
                    try {
                        applyPhoneCredentialLink(currentFirebaseUser.uid, credential, phoneNumber)
                        trySend(PhoneAuthEvent.Verified(currentFirebaseUser.uid, false))
                        close()
                    } catch (e: Exception) {
                        trySend(mapPhoneLinkException(e))
                        close()
                    }
                }
            }

            override fun onVerificationFailed(e: FirebaseException) {
                trySend(mapAuthError(e))
                close()
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken
            ) {
                trySend(PhoneAuthEvent.CodeSent(verificationId, token))
            }
        }

        val builder = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(Constants.OTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)

        if (resendingToken != null) {
            builder.setForceResendingToken(resendingToken)
        }

        PhoneAuthProvider.verifyPhoneNumber(builder.build())
        awaitClose { }
    }

    override suspend fun verifyOtpAndLinkPhone(verificationId: String, smsCode: String): Result<Unit> {
        return try {
            val currentFirebaseUser = auth.currentUser ?: error("Not authenticated")
            val credential = PhoneAuthProvider.getCredential(verificationId, smsCode.trim())
            applyPhoneCredentialLink(currentFirebaseUser.uid, credential, currentFirebaseUser.phoneNumber.orEmpty())
            Result.success(Unit)
        } catch (e: Exception) {
            val err = mapPhoneLinkException(e)
            Result.failure(Exception(err.messageAr, e))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun applyPhoneCredentialLink(
        uid: String,
        credential: PhoneAuthCredential,
        fallbackPhone: String
    ) {
        val firebaseUser = auth.currentUser ?: error("Not authenticated")
        firebaseUser.updatePhoneNumber(credential).await()
        firebaseUser.reload().await()

        val verifiedE164 = (firebaseUser.phoneNumber?.takeIf { it.isNotBlank() } ?: fallbackPhone)
            .let { PhoneUtils.normalizeToE164(it) ?: it }
        val hash = PhoneUtils.sha256Hash(verifiedE164)

        val userRef = firestore.collection(Constants.COLLECTION_USERS).document(uid)
        val snap = userRef.get().await()
        val privacyMap = snap.get("privacySettings") as? Map<String, Any?> ?: emptyMap()
        val hidePhone = (privacyMap["hidePhoneNumber"] as? Boolean) ?: false

        // Always persist verified private record
        userRef.collection(Constants.SUBCOLLECTION_PRIVATE)
            .document("info")
            .set(
                mapOf(
                    "phoneNumber" to verifiedE164,
                    "isPhoneVerified" to true,
                    "phoneVerifiedAt" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            ).await()

        val publicUpdates = hashMapOf<String, Any>(
            "isPhoneVerified" to true,
            "phoneNumber" to if (hidePhone) "" else verifiedE164,
            "phoneHash" to if (hidePhone) "" else hash
        )
        userRef.set(publicUpdates, SetOptions.merge()).await()
    }

    private fun mapPhoneLinkException(e: Exception): PhoneAuthEvent.Error {
        return when (e) {
            is FirebaseAuthUserCollisionException -> PhoneAuthEvent.Error(
                "هذا الرقم مرتبط بالفعل بحساب وصال آخر",
                "This phone number is already linked to another WESAL account"
            )
            is FirebaseAuthInvalidCredentialsException -> PhoneAuthEvent.Error(
                "رمز التحقق غير صحيح أو منتهي الصلاحية",
                "Invalid or expired verification code"
            )
            is FirebaseTooManyRequestsException -> PhoneAuthEvent.Error(
                "تم إرسال طلبات كثيرة، يرجى المحاولة لاحقاً",
                "Too many verification requests, please try again later"
            )
            is FirebaseNetworkException -> PhoneAuthEvent.Error(
                "لا يوجد اتصال بالإنترنت",
                "No internet connection"
            )
            else -> PhoneAuthEvent.Error(
                e.localizedMessage ?: "تعذر توثيق رقم الهاتف عبر SMS",
                e.localizedMessage ?: "Failed to verify phone number via SMS"
            )
        }
    }

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
            val newUserMap = hashMapOf(
                "uid" to uid,
                "email" to email.trim(),
                "phoneNumber" to normalizedPhone,
                "phoneHash" to verifiedPhoneHash,
                "isPhoneVerified" to phoneVerified,
                "isVerified" to false,
                "displayName" to suggestedName.trim(),
                "publicId" to uniquePublicId,
                "bio" to "أستخدم تطبيق WESAL",
                "photoUrl" to suggestedPhoto,
                "createdAt" to System.currentTimeMillis(),
                "lastSeen" to FieldValue.serverTimestamp(),
                "isOnline" to true,
                "publicKey" to pubKey,
                "blockedUsers" to emptyList<String>(),
                "privacySettings" to mapOf(
                    "lastSeenVisibility" to "everyone",
                    "onlineVisibility" to "everyone",
                    "profilePhotoVisibility" to "everyone",
                    "storiesVisibility" to "everyone",
                    "readReceiptsEnabled" to true,
                    "hidePhoneNumber" to false
                )
            )
            userRef.set(newUserMap, SetOptions.merge()).await()
            prefs.upsertSavedAccount(
                SavedWesalAccount(
                    uid = uid,
                    displayName = suggestedName.trim(),
                    email = email.trim(),
                    phoneNumber = normalizedPhone,
                    publicId = uniquePublicId,
                    photoUrl = suggestedPhoto,
                    provider = provider,
                    lastUsedAt = System.currentTimeMillis()
                )
            )
            return suggestedName.isBlank()
        } else {
            val existingName = snapshot.getString("displayName").orEmpty()
            val existingPhoto = snapshot.getString("photoUrl").orEmpty()
            val existingPublicId = snapshot.getString("publicId").orEmpty()
            val existingPhone = snapshot.getString("phoneNumber").orEmpty()
            val existingEmail = snapshot.getString("email").orEmpty()
            val existingVerified = snapshot.getBoolean("isPhoneVerified")
                ?: (existingPhone.isNotBlank() && existingEmail.isBlank())

            @Suppress("UNCHECKED_CAST")
            val privacyMap = snapshot.get("privacySettings") as? Map<String, Any?> ?: emptyMap()
            val hidePhone = (privacyMap["hidePhoneNumber"] as? Boolean) ?: false

            val updateMap = hashMapOf<String, Any>(
                "publicKey" to pubKey,
                "isOnline" to true,
                "lastSeen" to FieldValue.serverTimestamp()
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
                val finalPhone = existingPhone.ifBlank { normalizedPhone }
                if (!hidePhone && finalPhone.isNotBlank()) {
                    val norm = PhoneUtils.normalizeToE164(finalPhone) ?: finalPhone
                    updateMap["phoneHash"] = PhoneUtils.sha256Hash(norm)
                }
            } else if (snapshot.getBoolean("isPhoneVerified") == null) {
                updateMap["isPhoneVerified"] = false
            }

            userRef.set(updateMap, SetOptions.merge()).await()
            prefs.upsertSavedAccount(
                SavedWesalAccount(
                    uid = uid,
                    displayName = existingName.ifBlank { suggestedName.trim() },
                    email = existingEmail.ifBlank { email.trim() },
                    phoneNumber = existingPhone.ifBlank { normalizedPhone },
                    publicId = finalPublicId,
                    photoUrl = existingPhoto.ifBlank { suggestedPhoto },
                    provider = provider,
                    lastUsedAt = System.currentTimeMillis()
                )
            )
            return existingName.isBlank() && suggestedName.isBlank()
        }
    }

    override suspend fun completeUserProfile(
        displayName: String,
        bio: String,
        photoUri: Uri?,
        wesalId: String?
    ): Result<WesalUser> {
        return try {
            val uid = currentUserId ?: error("المستخدم غير مسجل الدخول")
            val docRef = firestore.collection(Constants.COLLECTION_USERS).document(uid)
            val existingSnap = docRef.get().await()

            val updates = hashMapOf<String, Any>(
                "displayName" to displayName.trim(),
                "bio" to bio.trim().ifBlank { "أستخدم تطبيق WESAL" },
                "isOnline" to true,
                "lastSeen" to FieldValue.serverTimestamp()
            )

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

            if (photoUri != null) {
                val uploadedUrl = runCatching {
                    val ref = storage.reference.child("${Constants.STORAGE_AVATARS}/$uid/avatar_${UUID.randomUUID()}.jpg")
                    val metadata = StorageMetadata.Builder().setContentType("image/jpeg").build()
                    ref.putFile(photoUri, metadata).await()
                    ref.downloadUrl.await().toString()
                }.getOrElse {
                    uriToBase64Jpeg(context, photoUri, maxSide = 512, quality = 78)
                }
                if (uploadedUrl.isNotBlank()) {
                    updates["photoUrl"] = uploadedUrl
                }
            }

            docRef.set(updates, SetOptions.merge()).await()
            syncFcmToken()
            registerDeviceSession(uid)

            val snap = docRef.get().await()
            val savedUser = snap.toWesalUser()
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
                    phoneNumber = savedUser.phoneNumber,
                    publicId = savedUser.publicId,
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
            val sessionData = mapOf(
                "sessionId" to sessionId,
                "deviceName" to "${Build.MANUFACTURER} ${Build.MODEL}",
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
        } catch (e: Exception) {
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

    override fun observeSavedAccounts(): Flow<List<SavedWesalAccount>> = prefs.savedAccountsFlow

    override suspend fun removeSavedAccount(uid: String) {
        prefs.removeSavedAccount(uid)
    }

    override suspend fun clearActiveSessionForSwitch() {
        val uid = currentUserId
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
        prefs.clearPendingOtp()
    }

    private fun mapSparkAuthMessage(e: Exception, fallback: String): String {
        return when (e) {
            is FirebaseNetworkException -> "لا يوجد اتصال بالإنترنت، تحقق من الشبكة"
            is FirebaseAuthInvalidUserException -> "لا يوجد حساب مسجل بهذا البريد الإلكتروني"
            is FirebaseAuthInvalidCredentialsException -> "البريد الإلكتروني أو كلمة المرور غير صحيحة"
            is FirebaseAuthUserCollisionException -> "هذا البريد الإلكتروني مسجل بالفعل في WESAL"
            is FirebaseAuthWeakPasswordException -> "كلمة المرور ضعيفة جداً — استخدم 6 أحرف على الأقل"
            is FirebaseTooManyRequestsException -> "تم إرسال محاولات كثيرة، انتظر قليلاً ثم أعد المحاولة"
            else -> e.localizedMessage?.takeIf { it.isNotBlank() } ?: fallback
        }
    }

    private fun mapAuthError(e: Exception): PhoneAuthEvent.Error {
        return when (e) {
            is FirebaseNetworkException -> PhoneAuthEvent.Error(
                "لا يوجد اتصال بالإنترنت، يرجى التحقق والمحاولة مجدداً",
                "Network error, please check your connection"
            )
            is FirebaseTooManyRequestsException -> PhoneAuthEvent.Error(
                "تم إرسال طلبات كثيرة، يرجى الانتظار قليلاً قبل المحاولة مجدداً",
                "Too many requests, please wait before retrying"
            )
            is FirebaseAuthInvalidCredentialsException -> PhoneAuthEvent.Error(
                "رقم الهاتف غير صالح، تأكد من رمز الدولة والرقم",
                "Invalid phone number format"
            )
            else -> PhoneAuthEvent.Error(
                e.localizedMessage ?: "حدث خطأ أثناء إرسال رمز التحقق",
                e.localizedMessage ?: "Failed to send verification code"
            )
        }
    }
}
