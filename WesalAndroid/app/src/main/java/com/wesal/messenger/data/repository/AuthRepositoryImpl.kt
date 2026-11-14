package com.wesal.messenger.data.repository

import android.app.Activity
import android.net.Uri
import android.os.Build
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage
import com.wesal.messenger.BuildConfig
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.data.local.WesalDatabase
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.AuthRepository
import com.wesal.messenger.domain.repository.PhoneAuthEvent
import com.wesal.messenger.security.E2EECryptoManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
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

    override fun observeAuthState(): Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser?.uid)
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
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
                        val isNew = ensureUserSkeletonExists(uid, result.user?.phoneNumber ?: phoneNumber)
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
            val isNewProfile = ensureUserSkeletonExists(uid, phone)
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

    private suspend fun ensureUserSkeletonExists(uid: String, phone: String): Boolean {
        val userRef = firestore.collection(Constants.COLLECTION_USERS).document(uid)
        val snapshot = userRef.get().await()
        val pubKey = cryptoManager.getOrCreateIdentityPublicKeyBase64()

        if (!snapshot.exists()) {
            val uniquePublicId = "wesal_${uid.take(6).lowercase()}_${(1000..9999).random()}"
            val newUserMap = hashMapOf(
                "uid" to uid,
                "phoneNumber" to phone,
                "displayName" to "",
                "publicId" to uniquePublicId,
                "bio" to "أستخدم تطبيق وصال",
                "photoUrl" to "",
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
                    "readReceiptsEnabled" to true
                )
            )
            userRef.set(newUserMap, SetOptions.merge()).await()
            return true
        } else {
            userRef.update(
                mapOf(
                    "publicKey" to pubKey,
                    "isOnline" to true,
                    "lastSeen" to FieldValue.serverTimestamp()
                )
            ).await()
            val existingName = snapshot.getString("displayName").orEmpty()
            return existingName.isBlank()
        }
    }

    override suspend fun completeUserProfile(
        displayName: String,
        bio: String,
        photoUri: Uri?
    ): Result<WesalUser> {
        return try {
            val uid = currentUserId ?: error("المستخدم غير مسجل الدخول")
            var photoUrl = ""
            if (photoUri != null) {
                val ref = storage.reference.child("${Constants.STORAGE_AVATARS}/$uid/avatar_${UUID.randomUUID()}.jpg")
                try {
                val bmp = android.graphics.BitmapFactory.decodeFile(photoUri.path)
                if (bmp != null) {
                    val sc = 256f / maxOf(bmp.width, bmp.height)
                    val small = if (sc < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true) else bmp
                    val bos = java.io.ByteArrayOutputStream()
                    small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bos)
                    photoUrl = "data:image/jpeg;base64," + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
                }
            } catch (e: Exception) {
                photoUrl = ""
            }
            }

            val updates = hashMapOf<String, Any>(
                "displayName" to displayName.trim(),
                "bio" to bio.trim().ifBlank { "أستخدم تطبيق وصال" },
                "isOnline" to true,
                "lastSeen" to FieldValue.serverTimestamp()
            )
            if (photoUrl.isNotBlank()) updates["photoUrl"] = photoUrl

            val docRef = firestore.collection(Constants.COLLECTION_USERS).document(uid)
            docRef.set(updates, SetOptions.merge()).await()
            syncFcmToken()
            registerDeviceSession(uid)

            val snap = docRef.get().await()
            Result.success(snap.toWesalUser())
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
            prefs.clearPendingOtp()
            database.wesalDao().clearAllChats()
            database.wesalDao().clearAllMessages()
            auth.signOut()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
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
