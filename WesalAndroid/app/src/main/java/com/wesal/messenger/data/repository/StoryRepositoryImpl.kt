package com.wesal.messenger.data.repository

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.wesal.messenger.core.network.NetworkMonitor
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.core.util.OfficialStoryLogic
import com.wesal.messenger.domain.model.PrivacyVisibility
import com.wesal.messenger.domain.model.StoryAudienceMode
import com.wesal.messenger.domain.model.StoryItem
import com.wesal.messenger.domain.model.StoryType
import com.wesal.messenger.domain.model.StoryViewer
import com.wesal.messenger.domain.repository.StoryPublishEvent
import com.wesal.messenger.domain.repository.StoryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StoryRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val networkMonitor: NetworkMonitor
) : StoryRepository {

    /**
     * Round 13 (Item D clarification — defense in depth): the ONLY
     * `isOfficial` value any publish path may stamp. The composer's
     * request is ANDed with the author's OWN admin status, read from
     * the author doc the publish already fetched — a non-admin's story
     * is stamped false even if the official composer flag were somehow
     * reached. numericId is normalized across its string/number
     * representations before the check.
     */
    private fun effectiveIsOfficialFor(
        userSnap: com.google.firebase.firestore.DocumentSnapshot,
        requested: Boolean
    ): Boolean {
        val numericId = (userSnap.get("numericId") as? Number)?.toLong()?.toString()
            ?: userSnap.getString("numericId").orEmpty()
        return OfficialStoryLogic.effectiveIsOfficial(requested, numericId)
    }

    private suspend fun fetchAcceptedContactIds(myUid: String): Set<String> {
        if (myUid.isBlank()) return emptySet()
        val contacts = mutableSetOf<String>()
        runCatching {
            val sent = firestore.collection(Constants.COLLECTION_CONTACT_REQUESTS)
                .whereEqualTo("senderUid", myUid)
                .whereEqualTo("status", "ACCEPTED")
                .get()
                .await()
            sent.documents.forEach { d ->
                d.getString("receiverUid")?.takeIf { it.isNotBlank() }?.let { contacts.add(it) }
            }
            val received = firestore.collection(Constants.COLLECTION_CONTACT_REQUESTS)
                .whereEqualTo("receiverUid", myUid)
                .whereEqualTo("status", "ACCEPTED")
                .get()
                .await()
            received.documents.forEach { d ->
                d.getString("senderUid")?.takeIf { it.isNotBlank() }?.let { contacts.add(it) }
            }
        }
        return contacts
    }

    @Suppress("UNCHECKED_CAST")
    override fun observeActiveStories(): Flow<List<StoryItem>> = callbackFlow {
        val now = System.currentTimeMillis()
        val myUid = auth.currentUser?.uid.orEmpty()
        val reg = firestore.collection(Constants.COLLECTION_STORIES)
            .whereGreaterThan("expiresAt", now)
            .addSnapshotListener { snap, error ->
                if (error != null) return@addSnapshotListener
                launch(Dispatchers.IO) {
                    val myContacts = fetchAcceptedContactIds(myUid)
                    val myBlocked = if (myUid.isNotBlank()) {
                        runCatching {
                            firestore.collection(Constants.COLLECTION_USERS).document(myUid).get().await()
                                .get("blockedUsers") as? List<String>
                        }.getOrNull().orEmpty().toSet()
                    } else {
                        emptySet()
                    }

                    val list = snap?.documents?.map { doc ->
                        val ownerUid = doc.getString("ownerUid").orEmpty()
                        val viewerIds = ((doc.get("viewerIds") as? List<String>) ?: emptyList())
                            .filter { it.isNotBlank() && it != ownerUid }
                        val audienceRaw = doc.getString("audienceMode")
                        val visibility = PrivacyVisibility.fromKey(doc.getString("visibility"))
                        val audienceMode = if (!audienceRaw.isNullOrBlank()) {
                            StoryAudienceMode.fromRaw(audienceRaw)
                        } else {
                            StoryAudienceMode.CONTACTS
                        }
                        StoryItem(
                            storyId = doc.id,
                            ownerUid = ownerUid,
                            ownerName = doc.getString("ownerName").orEmpty(),
                            ownerAvatarUrl = doc.getString("ownerAvatarUrl").orEmpty(),
                            ownerVerified = doc.getBoolean("ownerVerified") ?: false,
                            ownerVerificationType = doc.getString("ownerVerificationType") ?: "BLUE",
                            ownerIconId = doc.getString("ownerIconId").orEmpty(),
                            ownerIconUrl = doc.getString("ownerIconUrl").orEmpty(),
                            ownerNeonColor = doc.getString("ownerNeonColor").orEmpty(),
                            ownerNeonEnabled = doc.getBoolean("ownerNeonEnabled") ?: false,
                            ownerNeonGlowIntensity = doc.getDouble("ownerNeonGlowIntensity")?.toFloat() ?: 0.7f,
                            isOfficial = doc.getBoolean("isOfficial") ?: false,
                            type = StoryType.fromRaw(doc.getString("type")),
                            textContent = doc.getString("textContent").orEmpty(),
                            backgroundColorHex = doc.getLong("backgroundColorHex") ?: 0xFF064E3B,
                            mediaUrl = doc.getString("mediaUrl").orEmpty(),
                            caption = doc.getString("caption").orEmpty(),
                            createdAt = doc.getLong("createdAt") ?: 0L,
                            expiresAt = doc.getLong("expiresAt") ?: 0L,
                            visibility = visibility,
                            audienceMode = audienceMode,
                            allowedViewerIds = (doc.get("allowedViewerIds") as? List<String>).orEmpty(),
                            excludedViewerIds = (doc.get("excludedViewerIds") as? List<String>).orEmpty(),
                            viewerIds = viewerIds
                        )
                    }.orEmpty()
                        .filter { story ->
                            if (story.expiresAt <= System.currentTimeMillis()) return@filter false
                            if (story.ownerUid == myUid) return@filter true
                            if (story.ownerUid in myBlocked) return@filter false
                            // Round 12 (addendum 2): official announcements
                            // bypass the contacts/audience filter — every
                            // signed-in user sees them (the Firestore read
                            // rule already allows this; the restriction was
                            // always client-side).
                            if (OfficialStoryLogic.bypassesAudienceFilter(story.isOfficial)) return@filter true
                            if (story.visibility == PrivacyVisibility.NOBODY) return@filter false
                            when (story.audienceMode) {
                                StoryAudienceMode.ONLY_WITH -> myUid in story.allowedViewerIds
                                StoryAudienceMode.CONTACTS_EXCEPT ->
                                    story.ownerUid in myContacts && myUid !in story.excludedViewerIds
                                StoryAudienceMode.ALL_CONTACTS ->
                                    story.ownerUid in myContacts || story.visibility == PrivacyVisibility.EVERYONE
                                StoryAudienceMode.CONTACTS ->
                                    story.ownerUid in myContacts || story.visibility == PrivacyVisibility.EVERYONE
                            }
                        }
                        .let { filtered ->
                            // Official stories pin above contact stories;
                            // newest-first inside each group (a list with
                            // no official stories is purely newest-first,
                            // exactly as before).
                            OfficialStoryLogic.pinOfficialFirst(filtered, { it.isOfficial }, { it.createdAt })
                        }
                    trySend(list)
                }
            }
        awaitClose { reg.remove() }
    }

    override fun observeStoryViewers(storyId: String): Flow<List<StoryViewer>> = callbackFlow {
        val myUid = auth.currentUser?.uid.orEmpty()
        val storyRef = firestore.collection(Constants.COLLECTION_STORIES).document(storyId)
        var ownerUid = ""
        launch {
            ownerUid = runCatching { storyRef.get().await().getString("ownerUid").orEmpty() }.getOrDefault("")
            if (ownerUid.isNotBlank() && ownerUid != myUid) {
                trySend(emptyList())
                close()
                return@launch
            }
        }
        val reg = storyRef.collection("viewers")
            .orderBy("viewedAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, error ->
                if (error != null) return@addSnapshotListener
                if (ownerUid.isNotBlank() && ownerUid != myUid) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val list = snap?.documents?.mapNotNull { doc ->
                    val uid = doc.getString("uid") ?: doc.id
                    if (uid.isBlank() || (ownerUid.isNotBlank() && uid == ownerUid)) return@mapNotNull null
                    StoryViewer(
                        uid = uid,
                        displayName = doc.getString("displayName")?.ifBlank { "مستخدم وصال" } ?: "مستخدم وصال",
                        photoUrl = doc.getString("photoUrl").orEmpty(),
                        viewedAt = doc.getLong("viewedAt") ?: 0L,
                        isVerified = doc.getBoolean("isVerified") ?: false,
                        verificationType = doc.getString("verificationType") ?: "BLUE",
                        iconId = doc.getString("iconId").orEmpty(),
                        iconUrl = doc.getString("iconUrl").orEmpty(),
                        neonColor = doc.getString("neonColor").orEmpty(),
                        neonEnabled = doc.getBoolean("neonEnabled") ?: false,
                        neonGlowIntensity = doc.getDouble("neonGlowIntensity")?.toFloat() ?: 0.7f
                    )
                }.orEmpty()
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    override suspend fun publishTextStory(
        text: String,
        backgroundColorHex: Long,
        visibility: PrivacyVisibility,
        audienceMode: StoryAudienceMode,
        allowedViewerIds: List<String>,
        excludedViewerIds: List<String>,
        isOfficial: Boolean
    ): Result<Unit> = runCatching {
        if (!networkMonitor.isCurrentlyConnected()) {
            error("لا يوجد اتصال بالإنترنت، تحقق من الشبكة وحاول مجدداً")
        }
        val uid = auth.currentUser?.uid ?: error("يجب تسجيل الدخول لنشر الحالة")
        val userSnap = firestore.collection(Constants.COLLECTION_USERS).document(uid).get().await()
        val now = System.currentTimeMillis()
        val storyId = UUID.randomUUID().toString()
        val data = mapOf(
            "storyId" to storyId,
            "ownerUid" to uid,
            "ownerName" to (userSnap.getString("displayName")?.takeIf { it.isNotBlank() }
                ?: userSnap.getString("publicId")?.takeIf { it.isNotBlank() }?.let { "@$it" }
                ?: "مستخدم WESAL"),
            "ownerAvatarUrl" to (userSnap.getString("photoUrl") ?: ""),
            "ownerVerified" to (userSnap.getBoolean("isVerified") ?: false),
            "ownerVerificationType" to (userSnap.getString("verificationType") ?: "BLUE"),
            "ownerIconId" to (userSnap.getString("iconId") ?: ""),
            "ownerIconUrl" to (userSnap.getString("iconUrl") ?: ""),
            "ownerNeonColor" to (userSnap.getString("neonColor") ?: ""),
            "ownerNeonEnabled" to (userSnap.getBoolean("neonEnabled") ?: userSnap.getString("neonColor").orEmpty().isNotBlank()),
            "ownerNeonGlowIntensity" to (userSnap.getDouble("neonGlowIntensity")?.toFloat() ?: 0.7f),
            "isOfficial" to effectiveIsOfficialFor(userSnap, isOfficial),
            "type" to StoryType.TEXT.raw,
            "textContent" to text.trim(),
            "backgroundColorHex" to backgroundColorHex,
            "mediaUrl" to "",
            "caption" to "",
            "createdAt" to now,
            "expiresAt" to (now + Constants.STORY_EXPIRATION_MS),
            "visibility" to visibility.key,
            "audienceMode" to audienceMode.raw,
            "allowedViewerIds" to allowedViewerIds.distinct(),
            "excludedViewerIds" to excludedViewerIds.distinct(),
            "viewerIds" to emptyList<String>()
        )
        firestore.collection(Constants.COLLECTION_STORIES).document(storyId).set(data).await()
    }

    override fun publishMediaStory(
        uri: Uri,
        isVideo: Boolean,
        caption: String,
        visibility: PrivacyVisibility,
        audienceMode: StoryAudienceMode,
        allowedViewerIds: List<String>,
        excludedViewerIds: List<String>,
        backgroundColorHex: Long,
        isOfficial: Boolean
    ): Flow<StoryPublishEvent> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            trySend(StoryPublishEvent.Failure("يجب تسجيل الدخول لنشر الحالة"))
            close()
            return@callbackFlow
        }
        if (!networkMonitor.isCurrentlyConnected()) {
            trySend(StoryPublishEvent.Failure("لا يوجد اتصال بالإنترنت، تحقق من الشبكة وأعد المحاولة"))
            close()
            return@callbackFlow
        }

        launch {
            try {
                trySend(StoryPublishEvent.Progress(0.15f))
                val userSnap = firestore.collection(Constants.COLLECTION_USERS).document(uid).get().await()
                val storyId = UUID.randomUUID().toString()

                val ext = if (isVideo) "mp4" else "jpg"
                val mediaUrl = uploadFileToFirebaseStorage(
                    storagePath = "stories_media/$uid/$storyId.$ext",
                    uri = uri,
                    onProgress = { frac ->
                        trySend(StoryPublishEvent.Progress((0.15f + frac * 0.77f).coerceIn(0.15f, 0.92f)))
                    }
                ).getOrThrow()

                trySend(StoryPublishEvent.Progress(0.92f))

                val now = System.currentTimeMillis()
                val data = mapOf(
                    "storyId" to storyId,
                    "ownerUid" to uid,
                    "ownerName" to (userSnap.getString("displayName")?.takeIf { it.isNotBlank() }
                        ?: userSnap.getString("publicId")?.takeIf { it.isNotBlank() }?.let { "@$it" }
                        ?: "مستخدم WESAL"),
                    "ownerAvatarUrl" to (userSnap.getString("photoUrl") ?: ""),
                    "ownerVerified" to (userSnap.getBoolean("isVerified") ?: false),
                    "ownerVerificationType" to (userSnap.getString("verificationType") ?: "BLUE"),
                    "ownerIconId" to (userSnap.getString("iconId") ?: ""),
                    "ownerIconUrl" to (userSnap.getString("iconUrl") ?: ""),
                    "ownerNeonColor" to (userSnap.getString("neonColor") ?: ""),
                    "ownerNeonEnabled" to (userSnap.getBoolean("neonEnabled") ?: userSnap.getString("neonColor").orEmpty().isNotBlank()),
                    "ownerNeonGlowIntensity" to (userSnap.getDouble("neonGlowIntensity")?.toFloat() ?: 0.7f),
                    "isOfficial" to effectiveIsOfficialFor(userSnap, isOfficial),
                    "type" to if (isVideo) StoryType.VIDEO.raw else StoryType.IMAGE.raw,
                    "textContent" to "",
                    "backgroundColorHex" to backgroundColorHex,
                    "mediaUrl" to mediaUrl,
                    "caption" to caption.trim(),
                    "createdAt" to now,
                    "expiresAt" to (now + Constants.STORY_EXPIRATION_MS),
                    "visibility" to visibility.key,
                    "audienceMode" to audienceMode.raw,
                    "allowedViewerIds" to allowedViewerIds.distinct(),
                    "excludedViewerIds" to excludedViewerIds.distinct(),
                    "viewerIds" to emptyList<String>()
                )
                firestore.collection(Constants.COLLECTION_STORIES).document(storyId).set(data).await()
                trySend(StoryPublishEvent.Progress(1.0f))
                trySend(StoryPublishEvent.Success)
                close()
            } catch (e: Exception) {
                trySend(StoryPublishEvent.Failure("تعذر رفع الحالة، حاول مرة أخرى"))
                close()
            }
        }
        awaitClose { }
    }

    override fun publishAudioStory(
        audioUri: Uri,
        durationMs: Long,
        caption: String,
        visibility: PrivacyVisibility,
        audienceMode: StoryAudienceMode,
        allowedViewerIds: List<String>,
        excludedViewerIds: List<String>,
        backgroundColorHex: Long,
        isOfficial: Boolean
    ): Flow<StoryPublishEvent> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            trySend(StoryPublishEvent.Failure("يجب تسجيل الدخول لنشر الحالة"))
            close()
            return@callbackFlow
        }
        if (!networkMonitor.isCurrentlyConnected()) {
            trySend(StoryPublishEvent.Failure("لا يوجد اتصال بالإنترنت، تحقق من الشبكة وأعد المحاولة"))
            close()
            return@callbackFlow
        }

        launch {
            try {
                trySend(StoryPublishEvent.Progress(0.20f))
                val userSnap = firestore.collection(Constants.COLLECTION_USERS).document(uid).get().await()
                val storyId = UUID.randomUUID().toString()

                val mediaUrl = uploadFileToFirebaseStorage(
                    storagePath = "stories_audio/$uid/$storyId.m4a",
                    uri = audioUri,
                    onProgress = { frac ->
                        trySend(StoryPublishEvent.Progress((0.20f + frac * 0.72f).coerceIn(0.20f, 0.92f)))
                    }
                ).getOrThrow()
                trySend(StoryPublishEvent.Progress(0.92f))

                val now = System.currentTimeMillis()
                val durationLabel = DateTimeUtils.formatDurationMillis(durationMs)
                val finalCaption = if (caption.isNotBlank()) "${caption.trim()} • $durationLabel" else "مقطع صوتي • $durationLabel"
                val data = mapOf(
                    "storyId" to storyId,
                    "ownerUid" to uid,
                    "ownerName" to (userSnap.getString("displayName")?.takeIf { it.isNotBlank() }
                        ?: userSnap.getString("publicId")?.takeIf { it.isNotBlank() }?.let { "@$it" }
                        ?: "مستخدم WESAL"),
                    "ownerAvatarUrl" to (userSnap.getString("photoUrl") ?: ""),
                    "ownerVerified" to (userSnap.getBoolean("isVerified") ?: false),
                    "ownerVerificationType" to (userSnap.getString("verificationType") ?: "BLUE"),
                    "ownerIconId" to (userSnap.getString("iconId") ?: ""),
                    "ownerIconUrl" to (userSnap.getString("iconUrl") ?: ""),
                    "ownerNeonColor" to (userSnap.getString("neonColor") ?: ""),
                    "ownerNeonEnabled" to (userSnap.getBoolean("neonEnabled") ?: userSnap.getString("neonColor").orEmpty().isNotBlank()),
                    "ownerNeonGlowIntensity" to (userSnap.getDouble("neonGlowIntensity")?.toFloat() ?: 0.7f),
                    "isOfficial" to effectiveIsOfficialFor(userSnap, isOfficial),
                    "type" to StoryType.AUDIO.raw,
                    "textContent" to "",
                    "backgroundColorHex" to backgroundColorHex,
                    "mediaUrl" to mediaUrl,
                    "caption" to finalCaption,
                    "createdAt" to now,
                    "expiresAt" to (now + Constants.STORY_EXPIRATION_MS),
                    "visibility" to visibility.key,
                    "audienceMode" to audienceMode.raw,
                    "allowedViewerIds" to allowedViewerIds.distinct(),
                    "excludedViewerIds" to excludedViewerIds.distinct(),
                    "viewerIds" to emptyList<String>()
                )
                firestore.collection(Constants.COLLECTION_STORIES).document(storyId).set(data).await()
                trySend(StoryPublishEvent.Progress(1.0f))
                trySend(StoryPublishEvent.Success)
                close()
            } catch (e: Exception) {
                trySend(StoryPublishEvent.Failure("تعذر رفع الحالة الصوتية"))
                close()
            }
        }
        awaitClose { }
    }

    override suspend fun markStoryViewed(storyId: String) {
        val uid = auth.currentUser?.uid ?: return
        runCatching {
            val storyRef = firestore.collection(Constants.COLLECTION_STORIES).document(storyId)
            val storySnap = storyRef.get().await()
            if (!storySnap.exists()) return@runCatching
            val ownerUid = storySnap.getString("ownerUid").orEmpty()
            if (uid == ownerUid) return@runCatching

            // Scaling pass: the arrayUnion below is a billed write even
            // when the uid is already listed — the snapshot just fetched
            // says whether it is, so a repeat view costs zero writes.
            val alreadyListed = (storySnap.get("viewerIds") as? List<*>)
                ?.any { it?.toString() == uid } == true

            val viewerDocRef = storyRef.collection("viewers").document(uid)
            val existingViewer = viewerDocRef.get().await()
            if (!existingViewer.exists()) {
                val viewerSnap = firestore.collection(Constants.COLLECTION_USERS).document(uid).get().await()
                val viewerName = viewerSnap.getString("displayName")
                    ?.ifBlank { viewerSnap.getString("publicId") }
                    ?.ifBlank { "مستخدم وصال" }
                    ?: "مستخدم وصال"
                val viewerPhoto = viewerSnap.getString("photoUrl").orEmpty()
                viewerDocRef.set(
                    mapOf(
                        "uid" to uid,
                        "displayName" to viewerName,
                        "photoUrl" to viewerPhoto,
                        "viewedAt" to System.currentTimeMillis(),
                        "isVerified" to (viewerSnap.getBoolean("isVerified") ?: false),
                        "verificationType" to (viewerSnap.getString("verificationType") ?: "BLUE"),
                        "iconId" to (viewerSnap.getString("iconId") ?: ""),
                        "iconUrl" to (viewerSnap.getString("iconUrl") ?: ""),
                        "neonColor" to (viewerSnap.getString("neonColor") ?: ""),
                        "neonEnabled" to (viewerSnap.getBoolean("neonEnabled") ?: viewerSnap.getString("neonColor").orEmpty().isNotBlank()),
                        "neonGlowIntensity" to (viewerSnap.getDouble("neonGlowIntensity")?.toFloat() ?: 0.7f)
                    )
                ).await()
            }
            if (!alreadyListed) {
                storyRef.update("viewerIds", FieldValue.arrayUnion(uid)).await()
            }
        }
    }

    override suspend fun deleteStory(storyId: String): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val ref = firestore.collection(Constants.COLLECTION_STORIES).document(storyId)
        val snap = ref.get().await()
        require(snap.getString("ownerUid") == uid) { "لا يمكن حذف حالة مستخدم آخر" }
        ref.delete().await()
    }
}
