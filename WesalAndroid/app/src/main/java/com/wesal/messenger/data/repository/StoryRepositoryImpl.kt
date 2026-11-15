package com.wesal.messenger.data.repository

import android.content.Context
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.wesal.messenger.core.network.NetworkMonitor
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.core.util.DateTimeUtils
import com.wesal.messenger.domain.model.PrivacyVisibility
import com.wesal.messenger.domain.model.StoryItem
import com.wesal.messenger.domain.model.StoryType
import com.wesal.messenger.domain.model.StoryViewer
import com.wesal.messenger.domain.repository.StoryPublishEvent
import com.wesal.messenger.domain.repository.StoryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StoryRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage,
    private val networkMonitor: NetworkMonitor
) : StoryRepository {

    @Suppress("UNCHECKED_CAST")
    override fun observeActiveStories(): Flow<List<StoryItem>> = callbackFlow {
        val now = System.currentTimeMillis()
        val myUid = auth.currentUser?.uid.orEmpty()
        val reg = firestore.collection(Constants.COLLECTION_STORIES)
            .whereGreaterThan("expiresAt", now)
            .addSnapshotListener { snap, error ->
                if (error != null) return@addSnapshotListener
                val list = snap?.documents?.map { doc ->
                    val ownerUid = doc.getString("ownerUid").orEmpty()
                    val viewerIds = ((doc.get("viewerIds") as? List<String>) ?: emptyList())
                        .filter { it.isNotBlank() && it != ownerUid }
                    StoryItem(
                        storyId = doc.id,
                        ownerUid = ownerUid,
                        ownerName = doc.getString("ownerName").orEmpty(),
                        ownerAvatarUrl = doc.getString("ownerAvatarUrl").orEmpty(),
                        type = StoryType.fromRaw(doc.getString("type")),
                        textContent = doc.getString("textContent").orEmpty(),
                        backgroundColorHex = doc.getLong("backgroundColorHex") ?: 0xFF064E3B,
                        mediaUrl = doc.getString("mediaUrl").orEmpty(),
                        caption = doc.getString("caption").orEmpty(),
                        createdAt = doc.getLong("createdAt") ?: 0L,
                        expiresAt = doc.getLong("expiresAt") ?: 0L,
                        visibility = PrivacyVisibility.fromKey(doc.getString("visibility")),
                        viewerIds = viewerIds
                    )
                }.orEmpty()
                    .filter { story ->
                        story.expiresAt > System.currentTimeMillis() &&
                            (story.ownerUid == myUid || story.visibility != PrivacyVisibility.NOBODY)
                    }
                    .sortedByDescending { it.createdAt }
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    override fun observeStoryViewers(storyId: String): Flow<List<StoryViewer>> = callbackFlow {
        val storyRef = firestore.collection(Constants.COLLECTION_STORIES).document(storyId)
        var ownerUid = ""
        launch {
            ownerUid = runCatching { storyRef.get().await().getString("ownerUid").orEmpty() }.getOrDefault("")
        }
        val reg = storyRef.collection("viewers")
            .orderBy("viewedAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, error ->
                if (error != null) return@addSnapshotListener
                val list = snap?.documents?.mapNotNull { doc ->
                    val uid = doc.getString("uid") ?: doc.id
                    if (uid.isBlank() || (ownerUid.isNotBlank() && uid == ownerUid)) return@mapNotNull null
                    StoryViewer(
                        uid = uid,
                        displayName = doc.getString("displayName")?.ifBlank { "مستخدم وصال" } ?: "مستخدم وصال",
                        photoUrl = doc.getString("photoUrl").orEmpty(),
                        viewedAt = doc.getLong("viewedAt") ?: 0L
                    )
                }.orEmpty()
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    override suspend fun publishTextStory(
        text: String,
        backgroundColorHex: Long,
        visibility: PrivacyVisibility
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
            "ownerName" to (userSnap.getString("displayName")?.takeIf { it.isNotBlank() } ?: userSnap.getString("publicId")?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "مستخدم WESAL"),
            "ownerAvatarUrl" to (userSnap.getString("photoUrl") ?: ""),
            "type" to StoryType.TEXT.raw,
            "textContent" to text.trim(),
            "backgroundColorHex" to backgroundColorHex,
            "mediaUrl" to "",
            "caption" to "",
            "createdAt" to now,
            "expiresAt" to (now + Constants.STORY_EXPIRATION_MS),
            "visibility" to visibility.key,
            "viewerIds" to emptyList<String>()
        )
        firestore.collection(Constants.COLLECTION_STORIES).document(storyId).set(data).await()
    }

    override fun publishMediaStory(
        uri: Uri,
        isVideo: Boolean,
        caption: String,
        visibility: PrivacyVisibility
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
                trySend(StoryPublishEvent.Progress(0.05f))
                val userSnap = firestore.collection(Constants.COLLECTION_USERS).document(uid).get().await()
                val ext = if (isVideo) "mp4" else "jpg"
                val mime = if (isVideo) "video/mp4" else "image/jpeg"
                val storyId = UUID.randomUUID().toString()
                val storageRef = storage.reference.child("${Constants.STORAGE_STORIES}/$uid/$storyId.$ext")
                val metadata = StorageMetadata.Builder().setContentType(mime).build()

                val mediaUrl = try {
                    val uploadTask = storageRef.putFile(uri, metadata)
                    uploadTask.addOnProgressListener { snap ->
                        val total = snap.totalByteCount.coerceAtLeast(1L)
                        val pct = (snap.bytesTransferred.toFloat() / total.toFloat()).coerceIn(0.05f, 0.95f)
                        trySend(StoryPublishEvent.Progress(pct))
                    }
                    uploadTask.await()
                    storageRef.downloadUrl.await().toString()
                } catch (storageErr: Exception) {
                    if (!isVideo) {
                        val b64 = uriToBase64Jpeg(context, uri, maxSide = 900, quality = 75)
                        if (b64.isNotBlank()) {
                            trySend(StoryPublishEvent.Progress(0.85f))
                            b64
                        } else {
                            throw storageErr
                        }
                    } else {
                        throw IllegalStateException("تعذر رفع الفيديو إلى Firebase Storage — تأكد من تفعيل Storage في مشروع Firebase أو أرسل صورة/حالة نصية")
                    }
                }

                val now = System.currentTimeMillis()
                val data = mapOf(
                    "storyId" to storyId,
                    "ownerUid" to uid,
                    "ownerName" to (userSnap.getString("displayName")?.takeIf { it.isNotBlank() } ?: userSnap.getString("publicId")?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "مستخدم WESAL"),
                    "ownerAvatarUrl" to (userSnap.getString("photoUrl") ?: ""),
                    "type" to if (isVideo) StoryType.VIDEO.raw else StoryType.IMAGE.raw,
                    "textContent" to "",
                    "backgroundColorHex" to 0xFF06090BL,
                    "mediaUrl" to mediaUrl,
                    "caption" to caption.trim(),
                    "createdAt" to now,
                    "expiresAt" to (now + Constants.STORY_EXPIRATION_MS),
                    "visibility" to visibility.key,
                    "viewerIds" to emptyList<String>()
                )
                firestore.collection(Constants.COLLECTION_STORIES).document(storyId).set(data).await()
                trySend(StoryPublishEvent.Progress(1.0f))
                trySend(StoryPublishEvent.Success)
                close()
            } catch (e: Exception) {
                trySend(StoryPublishEvent.Failure(e.localizedMessage ?: "تعذر رفع الحالة، حاول مرة أخرى"))
                close()
            }
        }
        awaitClose { }
    }

    override fun publishAudioStory(
        audioUri: Uri,
        durationMs: Long,
        caption: String,
        visibility: PrivacyVisibility
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
                trySend(StoryPublishEvent.Progress(0.1f))
                val userSnap = firestore.collection(Constants.COLLECTION_USERS).document(uid).get().await()
                val storyId = UUID.randomUUID().toString()
                val storageRef = storage.reference.child("${Constants.STORAGE_STORIES}/$uid/$storyId.m4a")
                val metadata = StorageMetadata.Builder().setContentType("audio/mp4").build()

                val mediaUrl = try {
                    val uploadTask = storageRef.putFile(audioUri, metadata)
                    uploadTask.addOnProgressListener { snap ->
                        val total = snap.totalByteCount.coerceAtLeast(1L)
                        val pct = (snap.bytesTransferred.toFloat() / total.toFloat()).coerceIn(0.1f, 0.95f)
                        trySend(StoryPublishEvent.Progress(pct))
                    }
                    uploadTask.await()
                    storageRef.downloadUrl.await().toString()
                } catch (storageErr: Exception) {
                    uriToBase64Audio(context, audioUri)
                        ?: throw IllegalStateException("تعذر رفع المقطع الصوتي — المقطع طويل جداً لخطة Spark بدون Firebase Storage")
                }

                val now = System.currentTimeMillis()
                val durationLabel = DateTimeUtils.formatDurationMillis(durationMs)
                val finalCaption = if (caption.isNotBlank()) "${caption.trim()} • $durationLabel" else "مقطع صوتي • $durationLabel"
                val data = mapOf(
                    "storyId" to storyId,
                    "ownerUid" to uid,
                    "ownerName" to (userSnap.getString("displayName")?.takeIf { it.isNotBlank() } ?: userSnap.getString("publicId")?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "مستخدم WESAL"),
                    "ownerAvatarUrl" to (userSnap.getString("photoUrl") ?: ""),
                    "type" to StoryType.AUDIO.raw,
                    "textContent" to "",
                    "backgroundColorHex" to 0xFF064E3BL,
                    "mediaUrl" to mediaUrl,
                    "caption" to finalCaption,
                    "createdAt" to now,
                    "expiresAt" to (now + Constants.STORY_EXPIRATION_MS),
                    "visibility" to visibility.key,
                    "viewerIds" to emptyList<String>()
                )
                firestore.collection(Constants.COLLECTION_STORIES).document(storyId).set(data).await()
                trySend(StoryPublishEvent.Progress(1.0f))
                trySend(StoryPublishEvent.Success)
                close()
            } catch (e: Exception) {
                trySend(StoryPublishEvent.Failure(e.localizedMessage ?: "تعذر رفع الحالة الصوتية"))
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
            // Never record story owner's own views
            if (uid == ownerUid) return@runCatching

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
                        "viewedAt" to System.currentTimeMillis()
                    )
                ).await()
            }
            storyRef.update("viewerIds", FieldValue.arrayUnion(uid)).await()
        }
    }

    override suspend fun deleteStory(storyId: String): Result<Unit> = runCatching {
        val docRef = firestore.collection(Constants.COLLECTION_STORIES).document(storyId)
        val mediaUrl = docRef.get().await().getString("mediaUrl").orEmpty()
        docRef.delete().await()
        if (mediaUrl.startsWith("http")) {
            runCatching { storage.getReferenceFromUrl(mediaUrl).delete().await() }
        }
    }
}
