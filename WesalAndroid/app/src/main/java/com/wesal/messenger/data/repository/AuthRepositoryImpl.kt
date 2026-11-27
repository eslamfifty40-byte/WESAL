package com.wesal.messenger.data.repository

import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
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
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
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
import com.wesal.messenger.settings.SessionRevokeNotice
import com.wesal.messenger.settings.WesalCredentialVault
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.google.android.gms.tasks.Task
import java.time.LocalDate
import java.time.Period
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
    private val messaging: FirebaseMessaging,
    private val prefs: UserPreferencesDataStore,
    private val database: WesalDatabase,
    private val cryptoManager: E2EECryptoManager
) : AuthRepository {

    private companion object {
        const val TAG = "AuthRepository"
        const val SESSION_HEARTBEAT_MS = 5 * 60_000L
        const val LEGACY_SESSION_STALE_MS = 7 * 24 * 60 * 60_000L
    }

    /**
     * Application-scope for post-auth side effects. Sign-in must return as soon as the
     * user is authenticated and the skeleton exists; everything else (session
     * registration, FCM token, welcome chat, session guard) runs here, bounded,
     * fire-and-forget — never on the spinner's critical path.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** True while THIS device is signing out on purpose, so the session self-check
     *  does not mistake our own sign-out cleanup for a remote revocation (§3.5). */
    @Volatile
    private var manualSignOutInProgress = false

    @Volatile
    private var sessionGuardUid: String? = null
    private var sessionGuardJob: Job? = null
    private var sessionDocRegistration: com.google.firebase.firestore.ListenerRegistration? = null

    override val currentUserId: String?
        get() = auth.currentUser?.uid

    override val isLoggedIn: Boolean
        get() = auth.currentUser != null

    override val currentUserDisplayName: String?
        get() = auth.currentUser?.displayName

    override val currentUserEmail: String?
        get() = auth.currentUser?.email

    override val currentUserPhoneNumber: String?
        get() = auth.currentUser?.phoneNumber

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
        val canonicalId = "email_${PhoneUtils.sha256Hash(clean)}"
        val legacyId = PhoneUtils.sha256Hash("email:$clean")
        val col = firestore.collection(Constants.COLLECTION_BANNED_IDENTIFIERS)
        // Bounded reads (awaitBounded): these run BEFORE the password is even checked, so a
        // stalled stream must fail open (not banned) instead of freezing the login spinner.
        // Round 4: the canonical and legacy identifier reads are independent, so they run
        // in PARALLEL, and each cap dropped from 12s to 4s — sequential 12s bounds here
        // used to stack into most of the sign-in spinner's 20s life on a wedged stream.
        val snap = coroutineScope {
            val canonical = async {
                runCatching { col.document(canonicalId).get().awaitBounded(4_000L) }.getOrNull()
                    ?.takeIf { it.exists() }
            }
            val legacy = async {
                runCatching { col.document(legacyId).get().awaitBounded(4_000L) }.getOrNull()
                    ?.takeIf { it.exists() }
            }
            canonical.await() ?: legacy.await()
        } ?: return false to ""
        val type = snap.getString("type").orEmpty()
        val expiresAt = snap.getLong("expiresAt") ?: 0L
        val permanent = snap.getBoolean("permanent") ?: (expiresAt == 0L)
        if (!permanent && expiresAt > 0L && System.currentTimeMillis() > expiresAt) {
            return false to ""
        }
        val defaultReason = if (type == "DELETED_PERMANENT") {
            "تم حذف هذا الحساب نهائياً ولا يمكن إعادة تسجيله"
        } else {
            ""
        }
        val reason = snap.getString("reason").orEmpty().ifBlank { defaultReason }
        return true to reason
    }

    private suspend fun assertUserNotBannedOrSignOut(uid: String) {
        // Bounded read: on timeout/failure this check is skipped rather than hanging sign-in.
        // Round 4: cap dropped from 12s to 4s so a wedged stream fails fast (the check is
        // skipped, sign-in proceeds) instead of stacking onto the spinner's ceiling.
        val snap = runCatching {
            firestore.collection(Constants.COLLECTION_USERS).document(uid).get().awaitBounded(4_000L)
        }.getOrNull() ?: return
        val deletedAt = snap.getLong("deletedAt") ?: 0L
        val deletedPermanent = snap.getBoolean("deletedPermanent") ?: false
        if (deletedAt > 0L || deletedPermanent) {
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
                    ).awaitQueued()
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
            // Success returns NOW. Session registration, FCM token sync and the official
            // welcome chat moved to bounded fire-and-forget (launchPostAuthTail): they used
            // raw unbounded awaits and froze the spinner long after the user was signed in.
            launchPostAuthTail(firebaseUser.uid, firebaseUser.displayName.orEmpty(), needsSetup)
            Result.success(needsSetup)
        } catch (e: CancellationException) {
            throw e
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
            // Success returns NOW — see launchPostAuthTail (bounded fire-and-forget tail).
            launchPostAuthTail(firebaseUser.uid, firebaseUser.displayName.orEmpty(), needsSetup)
            Result.success(needsSetup)
        } catch (e: CancellationException) {
            throw e
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
            // Bounded: verification email + auth profile update are best-effort and must
            // never hold account creation hostage on a slow connection.
            withTimeoutOrNull(5_000L) { runCatching { firebaseUser.sendEmailVerification().await() } }
            if (fullName.isNotBlank()) {
                withTimeoutOrNull(5_000L) {
                    runCatching {
                        val profileUpdates = com.google.firebase.auth.UserProfileChangeRequest.Builder()
                            .setDisplayName(fullName.trim())
                            .build()
                        firebaseUser.updateProfile(profileUpdates).await()
                    }
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
            // Success returns NOW — session/FCM run in the bounded fire-and-forget tail.
            // (No welcome here: completeUserProfile delivers it, as before.)
            launchPostAuthTail(firebaseUser.uid, fullName.trim(), needsSetup, includeWelcome = false)
            Result.success(needsSetup)
        } catch (e: CancellationException) {
            throw e
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

    // ---------------------------------------------------------------------
    // Phone phase (round 4): OTP registration + mandatory verification gate.
    // The server (sendPhoneCode / verifyPhoneCode / verifyPhoneCodeForCurrentUser
    // callables) generates, delivers (Zavu), expires and verifies the codes;
    // the client only shapes the calls. The callables' Arabic HttpsError
    // messages are user-facing by design and are surfaced verbatim.
    // ---------------------------------------------------------------------

    /** Generous bound: the server may try WhatsApp delivery, then SMS. */
    private suspend fun <T> phoneFunctionCall(block: suspend () -> T): T =
        withTimeoutOrNull(50_000L) { block() }
            ?: throw IllegalStateException("انتهت مهلة الاتصال بخادم التحقق، تحقق من الإنترنت وحاول مرة أخرى")

    private fun mapPhoneFunctionError(e: Exception, fallback: String): String {
        if (e is FirebaseFunctionsException) {
            val serverMessage = e.message?.trim().orEmpty()
            // Callable errors carry the server's Arabic message; a bare code
            // name (e.g. functions not deployed yet) is not user-facing.
            return if (serverMessage.isNotBlank() &&
                serverMessage != e.code.name &&
                !serverMessage.startsWith("UNKNOWN")
            ) {
                serverMessage
            } else {
                fallback
            }
        }
        if (e is IllegalStateException && !e.message.isNullOrBlank()) return e.message!!
        return e.localizedMessage?.takeIf { it.isNotBlank() } ?: fallback
    }

    override suspend fun sendPhoneOtp(phoneE164: String): Result<String> {
        return try {
            val normalized = PhoneUtils.normalizeToE164(phoneE164)
                ?: error("يرجى إدخال رقم هاتف صحيح بالصيغة الدولية")
            val result = phoneFunctionCall {
                functions.getHttpsCallable("sendPhoneCode")
                    .call(mapOf("phone" to normalized))
                    .await()
            }
            val channel = (result.getData() as? Map<*, *>)?.get("channel")?.toString()
                ?.takeIf { it.isNotBlank() } ?: "sms"
            Result.success(channel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception(mapPhoneFunctionError(e, "تعذر إرسال كود التحقق، حاول مرة أخرى"), e))
        }
    }

    override suspend fun verifyPhoneOtpAndSignIn(phoneE164: String, code: String): Result<Boolean> {
        return try {
            val normalized = PhoneUtils.normalizeToE164(phoneE164)
                ?: error("يرجى إدخال رقم هاتف صحيح بالصيغة الدولية")
            val verifyResult = phoneFunctionCall {
                functions.getHttpsCallable("verifyPhoneCode")
                    .call(mapOf("phone" to normalized, "code" to code.trim()))
                    .await()
            }
            val data = verifyResult.getData() as? Map<*, *>
            val customToken = data?.get("customToken")?.toString()?.takeIf { it.isNotBlank() }
                ?: error("تعذر إتمام تسجيل الدخول بالرقم، حاول مرة أخرى")

            val previousUid = auth.currentUser?.uid
            val authResult = auth.signInWithCustomToken(customToken).await()
            val firebaseUser = authResult.user ?: error("تعذر تسجيل الدخول بالرقم")

            // Same ban gates as the other sign-in paths: a banned phone
            // identifier (deleted/banned account) or a banned user doc stops
            // the sign-in here too.
            val phoneBannedReason = bannedPhoneIdentifierReason(normalized)
            if (phoneBannedReason != null) {
                auth.signOut()
                error(
                    if (phoneBannedReason.isNotBlank()) "هذا الرقم محظور من استخدام WESAL: $phoneBannedReason"
                    else "هذا الرقم محظور من استخدام WESAL"
                )
            }
            assertUserNotBannedOrSignOut(firebaseUser.uid)

            if (previousUid != null && previousUid != firebaseUser.uid) {
                clearActiveSessionForSwitch(previousUid)
            }
            // The Auth user was created/found BY PHONE (verifyPhoneCode), so the
            // number is OTP-verified by construction: the skeleton stamps
            // phone + isPhoneVerified + phoneHash + the uniqueness lock.
            val needsSetup = ensureUserSkeletonExists(
                uid = firebaseUser.uid,
                phone = normalized,
                email = firebaseUser.email.orEmpty(),
                suggestedName = firebaseUser.displayName.orEmpty(),
                suggestedPhoto = firebaseUser.photoUrl?.toString().orEmpty(),
                phoneVerified = true,
                provider = "phone"
            )
            prefs.setCachedStartupProfileState(firebaseUser.uid, !needsSetup)
            launchPostAuthTail(firebaseUser.uid, firebaseUser.displayName.orEmpty(), needsSetup)
            Result.success(needsSetup)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception(mapPhoneFunctionError(e, "تعذر التحقق من الكود، حاول مرة أخرى"), e))
        }
    }

    override suspend fun verifyPhoneOtpForCurrentUser(phoneE164: String, code: String): Result<Unit> {
        return try {
            val normalized = PhoneUtils.normalizeToE164(phoneE164)
                ?: error("يرجى إدخال رقم هاتف صحيح بالصيغة الدولية")
            phoneFunctionCall {
                functions.getHttpsCallable("verifyPhoneCodeForCurrentUser")
                    .call(mapOf("phone" to normalized, "code" to code.trim()))
                    .await()
            }
            // The function stamped phone/isPhoneVerified/phoneHash server-side.
            // No local-cache mirror is needed here: the mandatory gate observes
            // the user doc live and lifts itself as soon as the stamp lands.
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception(mapPhoneFunctionError(e, "تعذر التحقق من الكود، حاول مرة أخرى"), e))
        }
    }

    /**
     * banned_identifiers check for a phone number (doc id "phone_<sha256>"),
     * mirroring reserveUniquePhone's ban read. Returns the ban reason ("" when
     * the doc carries none), or null when the number is not actively banned.
     * Fail-open on read errors, like the email identifier check: a stalled
     * stream must not freeze sign-in.
     */
    private suspend fun bannedPhoneIdentifierReason(e164Phone: String): String? {
        val hash = PhoneUtils.sha256Hash(e164Phone)
        val snap = runCatching {
            firestore.collection(Constants.COLLECTION_BANNED_IDENTIFIERS)
                .document("phone_$hash")
                .get()
                .awaitBounded(4_000L)
        }.getOrNull() ?: return null
        if (!snap.exists()) return null
        val expiresAt = snap.getLong("expiresAt") ?: 0L
        val permanent = snap.getBoolean("permanent") ?: (expiresAt == 0L)
        if (!permanent && expiresAt > 0L && System.currentTimeMillis() > expiresAt) {
            return null
        }
        return snap.getString("reason").orEmpty()
    }

    override suspend fun signInGuestForSupport(): Result<String> {
        return try {
            val currentUser = auth.currentUser
            if (currentUser != null && !currentUser.isAnonymous && currentUser.uid.isNotBlank()) {
                return Result.success(currentUser.uid)
            }
            if (currentUser?.isAnonymous == true) {
                runCatching { auth.signOut() }
            }
            Result.failure(
                IllegalStateException("يرجى تسجيل الدخول بحساب Google أو البريد الإلكتروني للتواصل المباشر مع إدارة WESAL (10001)")
            )
        } catch (e: Exception) {
            Result.failure(
                Exception(mapSparkAuthMessage(e, "سجّل الدخول بحسابك لبدء المحادثة المباشرة مع إدارة WESAL (10001)"), e)
            )
        }
    }

    private data class GlobalCustomerDefaults(
        val enabled: Boolean,
        val frameId: String,
        val iconId: String,
        val iconUrl: String
    )

    /**
     * Single reader for the admin-configured customer defaults, from
     * `system_config/global_appearance` — the document the admin panel actually
     * writes (setGlobalCustomerDefaults / setGlobalCustomerFrameEnabled) and that
     * UserRepositoryImpl observes. This replaces the old reader of
     * `system_config/admin_settings`, a document NOTHING in the codebase ever
     * writes, which made the toggle silently default to ON forever and ignored
     * the admin's chosen default frame/icon at registration/profile completion.
     *
     * Bounded: this fetch sits on the registration path and must never stall
     * sign-in; on timeout/failure the historical default (enabled, default
     * all-customers frame, no icon) applies.
     */
    private suspend fun readGlobalCustomerDefaults(): GlobalCustomerDefaults {
        val snap = withTimeoutOrNull(5_000L) {
            runCatching {
                firestore.collection(Constants.COLLECTION_SYSTEM_CONFIG)
                    .document("global_appearance")
                    .get()
                    .await()
            }.getOrNull()
        }
        if (snap == null || !snap.exists()) {
            return GlobalCustomerDefaults(
                enabled = true,
                frameId = WesalFrameCatalog.DEFAULT_ALL_CUSTOMERS_FRAME_ID,
                iconId = "",
                iconUrl = ""
            )
        }
        val frameId = snap.getString("defaultFrameId").orEmpty()
            .ifBlank { snap.getString("defaultCustomerFrameId").orEmpty() }
            .ifBlank { WesalFrameCatalog.DEFAULT_ALL_CUSTOMERS_FRAME_ID }
        return GlobalCustomerDefaults(
            enabled = snap.getBoolean("globalCustomerFrameEnabled") ?: true,
            frameId = frameId,
            iconId = snap.getString("defaultCustomerIconId").orEmpty(),
            iconUrl = snap.getString("defaultCustomerIconUrl").orEmpty()
        )
    }

    private suspend fun allocateUniqueNumericId(uid: String, email: String = auth.currentUser?.email.orEmpty()): String {
        val userRef = firestore.collection(Constants.COLLECTION_USERS).document(uid)
        val cleanEmail = email.trim()
        if (Constants.SUPER_ADMIN_EMAIL.equals(cleanEmail, ignoreCase = true)) {
            userRef.set(mapOf("numericId" to Constants.OWNER_ADMIN_NUMERIC_ID), SetOptions.merge()).awaitQueued()
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
                    userRef.set(mapOf("numericId" to candidate), SetOptions.merge()).awaitQueued()
                    return candidate
                }
            }
        }

        val fallback = WesalIdUtils.generateRandomNumericCandidate()
        userRef.set(mapOf("numericId" to fallback), SetOptions.merge()).awaitQueued()
        return fallback
    }

    /**
     * Resolves an invite id from a /join?id= link (publicId handle, numeric WESAL ID, or a
     * raw uid) to the inviter's account. Mirrors UserRepositoryImpl.findUserByPublicIdOrUid
     * — duplicated here because AuthRepositoryImpl must not depend on UserRepository.
     * Every lookup is fail-silent: attribution is best-effort and never blocks sign-up.
     */
    private suspend fun resolveInviterByInviteId(inviteId: String): WesalUser? {
        val raw = inviteId.trim()
        if (raw.isBlank()) return null
        val usersCol = firestore.collection(Constants.COLLECTION_USERS)

        val numericQuery = WesalIdUtils.extractNumericIdQuery(raw)
        if (WesalIdUtils.isValidNumericId(numericQuery)) {
            val snap = runCatching {
                usersCol.whereEqualTo("numericId", numericQuery).limit(1).get().await()
            }.getOrNull()
            val matched = snap?.documents?.firstOrNull()
            if (matched != null) return matched.toWesalUser()
        }

        val handle = WesalIdUtils.normalize(raw)
        if (handle.isNotBlank()) {
            val snap = runCatching {
                usersCol.whereEqualTo("publicId", handle).limit(1).get().await()
            }.getOrNull()
            val matched = snap?.documents?.firstOrNull()
            if (matched != null) return matched.toWesalUser()
        }

        val byUid = runCatching { usersCol.document(raw).get().await() }.getOrNull()
        return byUid?.takeIf { it.exists() }?.toWesalUser()
    }

    /**
     * Stamps referredByUid / referredByNumericId on a brand-new user doc (round 3).
     * Fire-and-forget in appScope, fully bounded and fail-silent. Self-referral is
     * rejected, and the write is skipped if the fields are already set — combined with
     * the fact that the only caller sits in the doc-creation branch of
     * ensureUserSkeletonExists, attribution is create-only and can never be re-pointed
     * by later sign-ins. Both stored invite sources (the deep-link pending invite and
     * the manually typed inviter id) are cleared once the stamp lands.
     */
    private fun stampReferralAttribution(newUid: String, inviteId: String) {
        appScope.launch {
            val inviter = runCatching {
                withTimeoutOrNull(8_000L) { resolveInviterByInviteId(inviteId) }
            }.getOrNull() ?: return@launch
            if (inviter.uid.isBlank() || inviter.uid == newUid) return@launch
            val stamped = runCatching {
                val userRef = firestore.collection(Constants.COLLECTION_USERS).document(newUid)
                val fresh = withTimeoutOrNull(6_000L) { userRef.get().await() } ?: return@runCatching false
                val alreadyStamped = fresh.getString("referredByUid").orEmpty().isNotBlank() ||
                    (fresh.getLong("referredByNumericId") ?: 0L) > 0L
                if (alreadyStamped) return@runCatching false
                userRef.set(
                    mapOf(
                        "referredByUid" to inviter.uid,
                        "referredByNumericId" to (inviter.numericId.toLongOrNull() ?: 0L)
                    ),
                    SetOptions.merge()
                ).awaitQueued()
                true
            }.getOrDefault(false)
            if (stamped) {
                runCatching { prefs.clearPendingInviteId() }
                runCatching { prefs.clearManualInviteId() }
            }
        }
    }

    /** Uid whose blank frameId this process has already tried to self-heal. */
    @Volatile
    private var frameHealAttemptedUid: String? = null

    /**
     * Round 4 frame self-heal for the "new users' frames are not saved" reports.
     * The registration/profile-completion stamps write frameId inside one atomic
     * set() with ~13–24 other fields, and awaitQueued hides a server denial — so
     * under the pre-round-3 rules (which protected frameId on owner updates) the
     * stamp could be lost invisibly, and no later sign-in ever retried it (the
     * existing-doc branch below never touched frameId). When a sign-in finds the
     * user's own doc with a BLANK frameId, re-stamp the CURRENT global default
     * with a small dedicated update containing frameId only: under the round-3
     * rules that write is allowed by the self-pick/global-default carve-out, and
     * standing alone it cannot be dragged down by an unrelated field. Fire-and-
     * forget in appScope, at most once per session per user, and it never
     * overwrites a non-blank frame.
     */
    private fun healMissingFrameStamp(uid: String) {
        if (uid.isBlank() || frameHealAttemptedUid == uid) return
        frameHealAttemptedUid = uid
        appScope.launch {
            runCatching {
                val defaults = readGlobalCustomerDefaults()
                if (!defaults.enabled || defaults.frameId.isBlank()) return@runCatching
                firestore.collection(Constants.COLLECTION_USERS).document(uid)
                    .set(mapOf("frameId" to defaults.frameId), SetOptions.merge())
                    .awaitQueued(8_000L)
            }.onFailure { Log.w(TAG, "frame self-heal failed: ${it.message}") }
        }
    }

    /** Uid whose blank phoneHash this process has already tried to self-heal. */
    @Volatile
    private var phoneHashHealAttemptedUid: String? = null

    /**
     * Round 4 phoneHash self-heal (contacts M1 — the dominant cause of registered
     * friends not appearing in contacts). Accounts that registered via Google/email
     * were created with phoneHash = "" and only ever got a hash at profile
     * completion; grandfathered accounts skipped completion, and the sign-in
     * re-stamp was gated on phone verification they never had (see the note in the
     * existing-doc branch below), so their stored hash stayed blank and the
     * contacts whereIn("phoneHash", …) query could never match them. When a
     * sign-in finds the user's own doc with a BLANK phoneHash but a stored phone
     * number, repair it with a dedicated merge update containing phoneHash ONLY —
     * standing alone it cannot be dragged down by an unrelated denied field in
     * the batched sign-in update. The hash uses the same canonical path as the
     * profile-completion stamp (normalizeToE164 → sha256Hash of the E.164 string),
     * so the value is identical to what completion would have written; if the
     * stored number cannot be normalized there is no canonical hash and the heal
     * skips rather than writing a hash no lookup candidate can equal. The
     * phone_uniqueness lock doc is mirrored from reserveUniquePhone so the
     * contacts fallback lookup can find the account too — but a lock already
     * owned by a different uid is never stolen. Fire-and-forget in appScope, at
     * most once per session per user, never overwriting a non-blank hash.
     *
     * Phone phase (owner rule, 2026-11-27): the heal is now GATED on the number
     * being VERIFIED ([verified] = this sign-in's phoneVerified flag or the
     * doc's stored isPhoneVerified). Contacts discoverability requires a
     * verified phone — an unverified number stays on the profile with NO
     * phoneHash, which also stops number spoofing in contacts. Unverified
     * accounts get their hash from the mandatory verification gate instead:
     * verifyPhoneCodeForCurrentUser stamps it server-side the moment the OTP
     * checks out.
     */
    private fun healMissingPhoneHash(uid: String, rawPhone: String, verified: Boolean) {
        if (!verified) return
        if (uid.isBlank() || rawPhone.isBlank() || phoneHashHealAttemptedUid == uid) return
        phoneHashHealAttemptedUid = uid
        appScope.launch {
            runCatching {
                val normalizedPhone = PhoneUtils.normalizeToE164(rawPhone) ?: return@runCatching
                val hash = PhoneUtils.sha256Hash(normalizedPhone)
                firestore.collection(Constants.COLLECTION_USERS).document(uid)
                    .set(mapOf("phoneHash" to hash), SetOptions.merge())
                    .awaitQueued(8_000L)
                val lockRef = firestore.collection(Constants.COLLECTION_PHONE_UNIQUENESS)
                    .document(hash)
                runCatching {
                    firestore.runTransaction { tx ->
                        val lockSnap = tx.get(lockRef)
                        val ownerUid = lockSnap.getString("uid").orEmpty()
                        if (!lockSnap.exists() || ownerUid.isBlank() || ownerUid == uid) {
                            tx.set(
                                lockRef,
                                mapOf(
                                    "uid" to uid,
                                    "updatedAt" to System.currentTimeMillis()
                                )
                            )
                        }
                    }.await()
                }
            }.onFailure { Log.w(TAG, "phoneHash self-heal failed: ${it.message}") }
        }
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
        // Round 4: cap dropped from 12s to 4s — on a healthy connection this read is
        // sub-second; on a wedged stream it now fails fast with the honest timeout
        // error instead of burning most of the sign-in ceiling before failing.
        val snapshot = userRef.get().awaitBounded(4_000L)
        val pubKey = cryptoManager.getOrCreateIdentityPublicKeyBase64(uid)
        val normalizedPhone = if (phone.isNotBlank()) PhoneUtils.normalizeToE164(phone) ?: phone else ""
        val verifiedPhoneHash = if (phoneVerified && normalizedPhone.isNotBlank()) {
            PhoneUtils.sha256Hash(normalizedPhone)
        } else {
            ""
        }

        if (!snapshot.exists()) {
            val uniquePublicId = WesalIdUtils.generateDefault(uid)
            val globalDefaults = readGlobalCustomerDefaults()
            val initialFrame = if (globalDefaults.enabled) globalDefaults.frameId else ""
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
                "iconId" to if (globalDefaults.enabled) globalDefaults.iconId else "",
                "iconUrl" to if (globalDefaults.enabled) globalDefaults.iconUrl else "",
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
            userRef.set(newUserMap, SetOptions.merge()).awaitQueued()
            // Phone phase: a phone-registered account (OTP-verified by
            // construction — verifyPhoneCode created/found the Auth user by
            // number) also gets its phone_uniqueness lock at skeleton time, so
            // the number is claimed from day one and not only at profile
            // completion. Same no-steal rule as the heal: a lock already owned
            // by a different uid is never taken over. Bounded + fail-silent —
            // reserveUniquePhone at profile completion re-checks ownership.
            if (phoneVerified && normalizedPhone.isNotBlank()) {
                val lockHash = PhoneUtils.sha256Hash(normalizedPhone)
                runCatching {
                    withTimeoutOrNull(5_000L) {
                        val lockRef = firestore.collection(Constants.COLLECTION_PHONE_UNIQUENESS)
                            .document(lockHash)
                        firestore.runTransaction { tx ->
                            val lockSnap = tx.get(lockRef)
                            val ownerUid = lockSnap.getString("uid").orEmpty()
                            if (!lockSnap.exists() || ownerUid.isBlank() || ownerUid == uid) {
                                tx.set(
                                    lockRef,
                                    mapOf(
                                        "uid" to uid,
                                        "updatedAt" to System.currentTimeMillis()
                                    )
                                )
                            }
                        }.await()
                    }
                }
            }
            val assignedNumericId = kotlinx.coroutines.withTimeoutOrNull(5_000L) { allocateUniqueNumericId(uid, email) }.orEmpty()
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
            // Referral attribution (round 3): this branch runs ONLY when the user doc is
            // being created, so capturing the pending invite here makes the stamp
            // create-only by construction. The id is read now (bounded, local DataStore)
            // because the nav host may consume/clear the pref as soon as Home opens;
            // resolving + writing runs fire-and-forget in appScope so registration never
            // waits on — or fails because of — attribution.
            val pendingInviteId = withTimeoutOrNull(2_000L) {
                runCatching { prefs.settingsFlow.first().pendingInviteId }.getOrNull()
            }?.trim().orEmpty()
            if (pendingInviteId.isNotBlank()) {
                stampReferralAttribution(uid, pendingInviteId)
            }
            return true
        } else {
            val deletedAt = snapshot.getLong("deletedAt") ?: 0L
            val deletedPermanent = snapshot.getBoolean("deletedPermanent") ?: false
            if (deletedAt > 0L || deletedPermanent) {
                auth.signOut()
                throw IllegalStateException("تم حذف هذا الحساب نهائياً")
            }
            val existingName = snapshot.getString("displayName").orEmpty()
            val existingPhoto = snapshot.getString("photoUrl").orEmpty()
            val existingFrameId = snapshot.getString("frameId").orEmpty()
            val existingPublicId = snapshot.getString("publicId").orEmpty()
            val existingNumericId = snapshot.getString("numericId").orEmpty()
            val existingPhone = snapshot.getString("phoneNumber").orEmpty()
            val existingPhoneHash = snapshot.getString("phoneHash").orEmpty()
            val existingEmail = snapshot.getString("email").orEmpty()
            val existingBirthDate = snapshot.getString("birthDate").orEmpty()
            val existingCompleted = snapshot.getBoolean("profileCompleted")
            val existingVerified = snapshot.getBoolean("isPhoneVerified")
                ?: (existingPhone.isNotBlank() && existingEmail.isBlank())

            val privSnap = runCatching {
                withTimeoutOrNull(5_000L) {
                    userRef.collection(Constants.SUBCOLLECTION_PRIVATE).document("info").get().await()
                }
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
            } else if (snapshot.getBoolean("isPhoneVerified") == null) {
                updateMap["isPhoneVerified"] = false
            }
            // Round 4 (contacts M1) originally UNGATED this re-stamp (any stored
            // phone + blank hash) so Google/email accounts became discoverable.
            // Phone phase (owner rule, 2026-11-27) supersedes that: contacts
            // discoverability now REQUIRES the number to be verified — only a
            // verified account may carry a phoneHash, and an unverified number
            // stays on the profile with NO hash (this also prevents number
            // spoofing in contacts). The re-stamp is therefore gated again on
            // verification provenance (this sign-in's verified Auth phone, or
            // the doc's stored isPhoneVerified). Unverified accounts are routed
            // through the mandatory verification gate, whose callable stamps
            // the hash server-side at OTP success; verified accounts whose hash
            // was lost are still repaired by healMissingPhoneHash below.
            if (!hidePhone && effectivePhone.isNotBlank() &&
                (phoneVerified || existingVerified)
            ) {
                // Review MINOR-8: stamp ONLY the canonical hash space —
                // sha256 of the normalized E.164 number, the exact value the
                // contacts lookup hashes device numbers into. The old fallback
                // hashed the RAW string when normalization failed, a value no
                // lookup can ever reproduce (and inconsistent with
                // healMissingPhoneHash, which skips such numbers). An
                // un-normalizable number simply gets no stamp here; the heal
                // applies the same rule.
                PhoneUtils.normalizeToE164(effectivePhone)?.let { norm ->
                    updateMap["phoneHash"] = PhoneUtils.sha256Hash(norm)
                }
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
            userRef.set(updateMap, SetOptions.merge()).awaitQueued()
            // Round 4: a blank frameId on an existing doc means the registration
            // stamp was lost (see healMissingFrameStamp) — heal it fire-and-forget.
            if (existingFrameId.isBlank()) {
                healMissingFrameStamp(uid)
            }
            // Round 4 (contacts M1): a blank phoneHash with a stored phone number
            // means this account is invisible to contacts discovery (see
            // healMissingPhoneHash) — heal it fire-and-forget. Skipped when the
            // user hides their number: the completion/update paths deliberately
            // blank the hash in that state and that privacy choice wins. Phone
            // phase: also skipped for UNVERIFIED numbers (owner rule — only
            // verified accounts get a phoneHash; the gate stamps it instead).
            if (existingPhoneHash.isBlank() && effectivePhone.isNotBlank() && !hidePhone &&
                (phoneVerified || existingVerified)
            ) {
                healMissingPhoneHash(uid, effectivePhone, verified = true)
            }
            val finalNumericId = when {
                Constants.SECONDARY_ADMIN_EMAIL.equals(resolvedEmail, ignoreCase = true) -> Constants.SECONDARY_ADMIN_NUMERIC_ID
                Constants.SUPER_ADMIN_EMAIL.equals(resolvedEmail, ignoreCase = true) -> Constants.OWNER_ADMIN_NUMERIC_ID
                WesalIdUtils.isValidNumericId(existingNumericId) -> existingNumericId
                // Bounded: numeric-id allocation is retryable on a later sign-in and must
                // never hold the current sign-in hostage. Round 4: cap 8s → 5s.
                else -> withTimeoutOrNull(5_000L) { allocateUniqueNumericId(uid, resolvedEmail) }
                    ?: existingNumericId
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
            val resolvedName = existingName.ifBlank { suggestedName.trim() }
            if (resolvedName.isNotBlank() && auth.currentUser?.displayName.isNullOrBlank()) {
                runCatching {
                    val req = com.google.firebase.auth.UserProfileChangeRequest.Builder()
                        .setDisplayName(resolvedName)
                        .build()
                    // Bounded (awaitQueued): cosmetic profile sync must not stall sign-in.
                    auth.currentUser?.updateProfile(req)?.awaitQueued(4_000L)
                }
            }
            // Grandfathered existing completed accounts stay logged in if they already completed onboarding or have mandatory fields filled
            val hasName = resolvedName.isNotBlank()
            val hasPhone = effectivePhone.isNotBlank()
            val hasDob = effectiveDob.isNotBlank()
            val isComplete = when (existingCompleted) {
                true -> hasName || (hasPhone && hasDob)
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
            // Bounded: the uniqueness check must complete or fail with a message — never spin
            // forever (it is NOT skipped on timeout, to protect phone uniqueness).
            val phoneReserved = withTimeoutOrNull(12_000L) {
                reserveUniquePhone(uid, normalizedPhone)
                true
            } ?: false
            if (!phoneReserved) {
                throw IllegalStateException("انتهت مهلة التحقق من رقم الهاتف، تحقق من الإنترنت وحاول مرة أخرى")
            }

            val validDob = validateBirthDate(birthDate.orEmpty())

            val docRef = firestore.collection(Constants.COLLECTION_USERS).document(uid)
            val existingSnap = docRef.get().awaitBounded()
            val existingPrivacy = (existingSnap.get("privacySettings") as? Map<String, Any?>)?.toMutableMap()
                ?: mutableMapOf()
            val hidePhone = (existingPrivacy["hidePhoneNumber"] as? Boolean) ?: false
            existingPrivacy["hideBirthDate"] = hideBirthDate

            // Phone phase (owner rule, 2026-11-27): the completion phoneHash
            // stamp is VERIFIED-ONLY. The number being completed counts as
            // verified only when it IS the account's already-verified number —
            // the Auth phone number (phone-registered accounts, OTP-verified
            // by construction) or the number the user doc already carries as
            // isPhoneVerified. Any other typed number is stored on the profile
            // with NO phoneHash and isPhoneVerified=false; the mandatory gate
            // then OTP-verifies it before the app opens, and the callable
            // stamps the hash at success. (reserveUniquePhone above still
            // claims the uniqueness lock for the typed number — duplicate
            // prevention is unchanged, and the gate's lock check accepts a
            // lock owned by this same uid.)
            val authVerifiedPhone = auth.currentUser?.phoneNumber
                ?.takeIf { it.isNotBlank() }
                ?.let { PhoneUtils.normalizeToE164(it) }
            val docVerifiedPhone = if (existingSnap.getBoolean("isPhoneVerified") == true) {
                existingSnap.getString("phoneNumber")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { PhoneUtils.normalizeToE164(it) }
            } else {
                null
            }
            val completedPhoneVerified =
                normalizedPhone == authVerifiedPhone || normalizedPhone == docVerifiedPhone

            val updates = hashMapOf<String, Any>(
                "displayName" to cleanName,
                "bio" to bio.trim().ifBlank { "أستخدم تطبيق WESAL" },
                "privacySettings" to existingPrivacy,
                "birthDate" to if (hideBirthDate) "" else validDob,
                "phoneNumber" to if (hidePhone) "" else normalizedPhone,
                "phoneHash" to if (!hidePhone && completedPhoneVerified) {
                    PhoneUtils.sha256Hash(normalizedPhone)
                } else {
                    ""
                },
                "isPhoneVerified" to completedPhoneVerified,
                "locationGranted" to locationGranted,
                "locationFallbackStartedAt" to locationGraceStartedAt,
                "profileCompleted" to true,
                "isOnline" to true,
                "lastSeen" to FieldValue.serverTimestamp(),
                "lastHeartbeatAt" to System.currentTimeMillis()
            )

            // Assign the admin-configured global customer frame/icon if the user has
            // none and the global option is enabled (single global_appearance reader).
            val currentFrameId = existingSnap.getString("frameId").orEmpty()
            val currentIconId = existingSnap.getString("iconId").orEmpty()
            if (currentFrameId.isBlank() || currentIconId.isBlank()) {
                val globalDefaults = readGlobalCustomerDefaults()
                if (globalDefaults.enabled) {
                    if (currentFrameId.isBlank()) {
                        updates["frameId"] = globalDefaults.frameId
                        updates["frameExpiresAt"] = 0L
                    }
                    if (currentIconId.isBlank() && globalDefaults.iconId.isNotBlank()) {
                        updates["iconId"] = globalDefaults.iconId
                        updates["iconUrl"] = globalDefaults.iconUrl
                    }
                }
            }

            if (!wesalId.isNullOrBlank()) {
                val normalizedId = WesalIdUtils.normalize(wesalId)
                require(WesalIdUtils.isValid(normalizedId)) {
                    "معرف وصال (Wesal ID) يجب أن يتكون من 4 إلى 30 حرفاً بالإنجليزية أو أرقام أو _"
                }
                val dupSnap = firestore.collection(Constants.COLLECTION_USERS)
                    .whereEqualTo("publicId", normalizedId)
                    .get()
                    .awaitBounded()
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
                .awaitQueued()

            // Queued-bounded: Firestore persists this write locally and syncs it; waiting for a
            // server ack on a stalled stream was the other half of the "profile setup spins
            // forever" hang.
            docRef.set(updates, SetOptions.merge()).awaitQueued(8_000L)
            if (!WesalIdUtils.isValidNumericId(existingSnap.getString("numericId"))) {
                withTimeoutOrNull(8_000L) { allocateUniqueNumericId(uid) }
            }
            // Manual referral attribution (round 3): the optional "who invited you" id
            // typed on the profile-setup screen is persisted locally before this call.
            // This is the reliable path for sideloaded installs, where the /join link
            // parameter never reaches the app. stampReferralAttribution is fire-and-forget,
            // create-only (it re-reads the doc and skips when attribution already exists,
            // e.g. stamped from a deep-link pending invite at skeleton creation) and
            // rejects self-referral — so this can never block or fail profile completion.
            val manualInviteId = withTimeoutOrNull(2_000L) {
                runCatching { prefs.settingsFlow.first().manualInviteId }.getOrNull()
            }?.trim().orEmpty()
            if (manualInviteId.isNotBlank()) {
                stampReferralAttribution(uid, manualInviteId)
            }
            prefs.setCachedStartupProfileState(uid, true)
            prefs.setLocationState(locationGranted, locationGraceStartedAt)
            // Side effects (token + session registration) are bounded and best-effort here:
            // they must never block profile completion.
            startSessionGuard(uid)
            withTimeoutOrNull(8_000L) { runCatching { syncFcmToken() } }
            withTimeoutOrNull(8_000L) { runCatching { registerDeviceSession(uid) } }

            // Deliver single deterministic official WESAL Messenger welcome message (Sec 15 & Sec 26)
            // Bounded: the welcome write contains unbounded awaits internally and must not
            // block profile completion (it is retried on later sign-ins via welcomeSent).
            withTimeoutOrNull(10_000L) { runCatching { sendSingleOfficialWelcomeIfNeeded(uid, cleanName) } }

            // Bounded read-back: on timeout, fall back to the pre-write snapshot + the fields
            // just saved instead of hanging the completion flow.
            val snap = withTimeoutOrNull(8_000L) { docRef.get().await() }
            val savedUser = (snap?.toWesalUser() ?: existingSnap.toWesalUser().copy(profileCompleted = true)).copy(
                displayName = cleanName,
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
            userRef.set(mapOf("welcomeSent" to true), SetOptions.merge()).awaitQueued()
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

        userRef.set(mapOf("welcomeSent" to true), SetOptions.merge()).awaitQueued()
    }

    override suspend fun syncFcmToken() {
        val uid = currentUserId ?: return
        runCatching {
            val token = messaging.token.await()
            firestore.collection(Constants.COLLECTION_USERS)
                .document(uid)
                .update("fcmToken", token)
                .awaitQueued()
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
            val sessionsCol = firestore.collection(Constants.COLLECTION_USERS)
                .document(uid)
                .collection(Constants.COLLECTION_SESSIONS)
            sessionsCol.document(sessionId).set(sessionData, SetOptions.merge()).awaitQueued()
            // §3.5: prune LEGACY duplicate session docs for this device (older builds used
            // non-stable ids, so one phone could appear several times). Conservative on
            // purpose: only same-device-name docs with a legacy (non "dev_") id that have
            // been inactive for over a week — never the current doc, never link tokens,
            // never another (possibly identical-model) phone's live session.
            runCatching {
                val staleBefore = System.currentTimeMillis() - LEGACY_SESSION_STALE_MS
                sessionsCol.get().awaitBounded(8_000L).documents
                    .filter { doc ->
                        doc.id != sessionId &&
                            !doc.id.startsWith("link_") &&
                            !doc.id.startsWith("dev_") &&
                            (doc.getString("deviceName") ?: "") == deviceName &&
                            (doc.getLong("lastActiveAt") ?: 0L) < staleBefore
                    }
                    .take(5)
                    .forEach { doc -> runCatching { doc.reference.delete().awaitQueued() } }
            }
            val activeCount = runCatching {
                sessionsCol.get().awaitBounded(8_000L).documents.count { !it.id.startsWith("link_") }
            }.getOrDefault(1).coerceAtLeast(1)
            firestore.collection(Constants.COLLECTION_USERS)
                .document(uid)
                .set(
                    mapOf(
                        "lastDeviceName" to deviceName,
                        "lastAppVersion" to BuildConfig.VERSION_NAME,
                        "activeSessionsCount" to activeCount
                    ),
                    SetOptions.merge()
                ).awaitQueued()
        }
    }

    /**
     * Bounded fire-and-forget tail for a successful sign-in/sign-up. The caller returns
     * Result.success immediately; these side effects (session registration, FCM token,
     * official welcome chat) each run in the app scope with their own timeout and only
     * ever log failures. This is the fix for the round-1 miss: the tail used to run
     * BEFORE success was returned, with raw unbounded awaits (PERF_AUTH §D2 #3/#4/#5).
     */
    private fun launchPostAuthTail(
        uid: String,
        displayName: String,
        needsSetup: Boolean,
        includeWelcome: Boolean = true
    ) {
        manualSignOutInProgress = false
        startSessionGuard(uid)
        runTailBounded(12_000L) { registerDeviceSession(uid) }
        runTailBounded(10_000L) { syncFcmToken() }
        if (includeWelcome && !needsSetup) {
            runTailBounded(15_000L) { sendSingleOfficialWelcomeIfNeeded(uid, displayName) }
        }
    }

    private fun runTailBounded(timeoutMs: Long, block: suspend () -> Unit) {
        appScope.launch {
            withTimeoutOrNull(timeoutMs) {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "post-auth side effect failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Session guard for the signed-in device (§3.5), started after every sign-in:
     * (a) a throttled heartbeat keeps THIS session doc's lastActiveAt fresh — the
     *     presence heartbeat only touches the user doc, so session activity went stale;
     * (b) a revocation self-check: if our own session doc disappears while we are
     *     signed in and did NOT sign out manually, the session was ended from another
     *     device — sign out locally and leave a one-shot message for the login screen.
     */
    private fun startSessionGuard(uid: String) {
        if (uid.isBlank()) return
        if (sessionGuardUid == uid && sessionGuardJob?.isActive == true) return
        stopSessionGuard()
        sessionGuardUid = uid
        sessionGuardJob = appScope.launch {
            val sessionId = runCatching { prefs.getOrCreateDeviceSessionId() }.getOrNull().orEmpty()
            if (sessionId.isBlank()) return@launch
            val sessionRef = firestore.collection(Constants.COLLECTION_USERS)
                .document(uid)
                .collection(Constants.COLLECTION_SESSIONS)
                .document(sessionId)
            val seenExisting = java.util.concurrent.atomic.AtomicBoolean(false)
            sessionDocRegistration = sessionRef.addSnapshotListener { snap, _ ->
                if (snap == null) return@addSnapshotListener
                if (snap.exists()) {
                    seenExisting.set(true)
                } else if (seenExisting.get() && !manualSignOutInProgress &&
                    auth.currentUser?.uid == uid
                ) {
                    onOwnSessionRevoked(uid)
                }
            }
            try {
                while (isActive) {
                    delay(SESSION_HEARTBEAT_MS)
                    if (auth.currentUser?.uid != uid) break
                    runCatching {
                        sessionRef.set(
                            mapOf(
                                "sessionId" to sessionId,
                                "lastActiveAt" to System.currentTimeMillis()
                            ),
                            SetOptions.merge()
                        ).awaitQueued(10_000L)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    private fun stopSessionGuard() {
        sessionDocRegistration?.remove()
        sessionDocRegistration = null
        sessionGuardJob?.cancel()
        sessionGuardJob = null
        sessionGuardUid = null
    }

    private fun onOwnSessionRevoked(uid: String) {
        if (manualSignOutInProgress) return
        manualSignOutInProgress = true
        stopSessionGuard()
        SessionRevokeNotice.stash(
            context,
            "تم إنهاء جلسة هذا الجهاز من جهاز آخر، سجّل الدخول مرة أخرى للمتابعة"
        )
        appScope.launch {
            // Local-only sign-out: the session doc is already gone server-side, so skip
            // the server cleanup and just drop local state, mirroring signOut()'s local half.
            runCatching { prefs.clearUserSessionState() }
            runCatching {
                withContext(Dispatchers.IO) {
                    database.wesalDao().clearAllChats()
                    database.wesalDao().clearAllMessages()
                }
            }
            runCatching { auth.signOut() }
        }
    }

    override suspend fun signOut(): Result<Unit> {
        // Mark FIRST: sign-out cleanup deletes our own session doc, and the §3.5
        // self-check must not read that as a remote revocation.
        manualSignOutInProgress = true
        stopSessionGuard()
        return try {
            val uid = currentUserId
            if (uid != null) {
                val sessionId = prefs.getOrCreateDeviceSessionId()
                runCatching {
                    // Bounded: server-side sign-out cleanup is best-effort and must never
                    // prevent the LOCAL sign-out below (a stalled stream here used to leave
                    // the user stuck "signed in" until the app was killed).
                    withTimeoutOrNull(6_000L) {
                        val userRef = firestore.collection(Constants.COLLECTION_USERS).document(uid)
                        userRef.collection(Constants.COLLECTION_SESSIONS)
                            .document(sessionId)
                            .delete()
                            .await()
                        val remainingSessions = runCatching {
                            userRef.collection(Constants.COLLECTION_SESSIONS).get().await()
                                .documents.count { !it.id.startsWith("link_") }
                        }.getOrDefault(0)
                        userRef.update(
                            mapOf(
                                "isOnline" to false,
                                "lastSeen" to FieldValue.serverTimestamp(),
                                "lastHeartbeatAt" to 0L,
                                "fcmToken" to "",
                                "activeSessionsCount" to remainingSessions
                            )
                        ).await()
                    }
                }
            }

            // Bounded: FCM token deletion is best-effort and must never delay local sign-out.
            runCatching { withTimeoutOrNull(3_000L) { messaging.deleteToken().await() } }
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
        } catch (e: CancellationException) {
            throw e
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

            // Delete contact requests involving this user
            runCatching {
                val reqs = firestore.collection(Constants.COLLECTION_CONTACT_REQUESTS)
                    .whereArrayContains("participants", uid)
                    .get()
                    .await()
                reqs.documents.forEach { d -> runCatching { d.reference.delete().await() } }
            }

            // Delete active stories created by this user
            runCatching {
                val stories = firestore.collection(Constants.COLLECTION_STORIES)
                    .whereEqualTo("ownerUid", uid)
                    .get()
                    .await()
                stories.documents.forEach { d -> runCatching { d.reference.delete().await() } }
            }

            // Remove user from groups
            runCatching {
                val groups = firestore.collection(Constants.COLLECTION_GROUPS)
                    .whereArrayContains("memberIds", uid)
                    .get()
                    .await()
                groups.documents.forEach { d ->
                    runCatching {
                        d.reference.update(
                            mapOf(
                                "memberIds" to FieldValue.arrayRemove(uid),
                                "adminIds" to FieldValue.arrayRemove(uid)
                            )
                        ).await()
                    }
                }
            }

            // Unsubscribe user from channels
            runCatching {
                val channels = firestore.collection(Constants.COLLECTION_CHANNELS)
                    .whereArrayContains("subscriberIds", uid)
                    .get()
                    .await()
                channels.documents.forEach { d ->
                    if (d.getString("ownerUid") != uid) {
                        runCatching {
                            d.reference.update(
                                mapOf(
                                    "subscriberIds" to FieldValue.arrayRemove(uid),
                                    "moderatorIds" to FieldValue.arrayRemove(uid)
                                )
                            ).await()
                        }
                    }
                }
            }

            // Delete custom contact lists & scheduled calls created by user
            runCatching {
                val lists = firestore.collection(Constants.COLLECTION_CONTACT_LISTS)
                    .whereEqualTo("ownerUid", uid)
                    .get()
                    .await()
                lists.documents.forEach { d -> runCatching { d.reference.delete().await() } }
            }
            runCatching {
                val scheds = firestore.collection(Constants.COLLECTION_SCHEDULED_CALLS)
                    .whereEqualTo("creatorUid", uid)
                    .get()
                    .await()
                scheds.documents.forEach { d -> runCatching { d.reference.delete().await() } }
            }

            // Hide user's chats cleanly from deleted account side
            runCatching {
                val chats = firestore.collection(Constants.COLLECTION_CHATS)
                    .whereArrayContains("participantIds", uid)
                    .get()
                    .await()
                chats.documents.forEach { d ->
                    runCatching {
                        d.reference.update("deletedForUsers", FieldValue.arrayUnion(uid)).await()
                    }
                }
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
                    "activeSessionsCount" to 0,
                    "hiddenFromSearch" to true,
                    "deletedAt" to now,
                    "deletedPermanent" to true,
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
        // Removing a saved account also deletes its stored login credential (§3.2),
        // so a removed account can never be fast-switched back in.
        WesalCredentialVault.removeByUid(context, uid)
    }

    override suspend fun clearActiveSessionForSwitch(previousUid: String?) {
        val uid = previousUid ?: currentUserId
        if (uid != null) {
            runCatching {
                // Bounded (awaitQueued): presence-off for the previous account is
                // best-effort and must not stall the incoming sign-in during a switch.
                firestore.collection(Constants.COLLECTION_USERS)
                    .document(uid)
                    .update(mapOf("isOnline" to false, "lastSeen" to FieldValue.serverTimestamp()))
                    .awaitQueued()
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

/**
 * Firestore writes are queued locally and acknowledged by the server later. When the server is slow to ack,
 * waiting for it must never freeze sign-in: give it a few seconds, then carry on (the write still goes out).
 */
private suspend fun <T> Task<T>.awaitQueued(timeoutMs: Long = 6_000L) {
    withTimeoutOrNull(timeoutMs) { await() }
}

/**
 * Reads on the sign-in path must be bounded too: a Firestore get() on a stalled gRPC stream
 * can suspend forever, which is exactly what froze login/registration on a permanent spinner
 * (killing and reopening the app "fixed" it because a fresh stream was created). On timeout
 * this throws a clear, retryable error instead of hanging.
 */
private suspend fun <T> Task<T>.awaitBounded(timeoutMs: Long = 12_000L): T {
    return withTimeoutOrNull(timeoutMs) { await() }
        ?: throw IllegalStateException("انتهت مهلة الاتصال بالخادم، تحقق من الإنترنت وحاول مرة أخرى")
}
