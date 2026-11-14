package com.wesal.messenger.data.repository

import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.domain.model.PrivacyVisibility
import com.wesal.messenger.domain.model.StoryItem
import com.wesal.messenger.domain.model.StoryType
import com.wesal.messenger.domain.repository.StoryRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StoryRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage
) : StoryRepository {

    @Suppress("UNCHECKED_CAST")
    override fun observeActiveStories(): Flow<List<StoryItem>> = callbackFlow {
        val now = System.currentTimeMillis()
        val reg = firestore.collection(Constants.COLLECTION_STORIES)
            .whereGreaterThan("expiresAt", now)
            .addSnapshotListener { snap, _ ->
                val list = snap?.documents?.map { doc ->
                    StoryItem(
                        storyId = doc.id,
                        ownerUid = doc.getString("ownerUid").orEmpty(),
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
                        viewerIds = (doc.get("viewerIds") as? List<String>) ?: emptyList()
                    )
                }.orEmpty()
                    .filter { it.expiresAt > System.currentTimeMillis() }
                    .sortedByDescending { it.createdAt }
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    override suspend fun publishTextStory(
        text: String,
        backgroundColorHex: Long,
        visibility: PrivacyVisibility
    ): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val userSnap = firestore.collection(Constants.COLLECTION_USERS).document(uid).get().await()
        val now = System.currentTimeMillis()
        val storyId = UUID.randomUUID().toString()
        val data = mapOf(
            "storyId" to storyId,
            "ownerUid" to uid,
            "ownerName" to (userSnap.getString("displayName")?.ifBlank { userSnap.getString("phoneNumber") } ?: "مستخدم وصال"),
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

    override suspend fun publishMediaStory(
        uri: Uri,
        isVideo: Boolean,
        caption: String,
        visibility: PrivacyVisibility
    ): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: error("Not authenticated")
        val userSnap = firestore.collection(Constants.COLLECTION_USERS).document(uid).get().await()
        val ext = if (isVideo) "mp4" else "jpg"
        val storyId = UUID.randomUUID().toString()
        val mediaUrl = runCatching { uriToBase64Jpeg(uri, 720, 70) }.getOrDefault("")

        val now = System.currentTimeMillis()
        val data = mapOf(
            "storyId" to storyId,
            "ownerUid" to uid,
            "ownerName" to (userSnap.getString("displayName")?.ifBlank { userSnap.getString("phoneNumber") } ?: "مستخدم وصال"),
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
    }

    override suspend fun markStoryViewed(storyId: String) {
        val uid = auth.currentUser?.uid ?: return
        runCatching {
            firestore.collection(Constants.COLLECTION_STORIES)
                .document(storyId)
                .update("viewerIds", FieldValue.arrayUnion(uid))
                .await()
        }
    }

    override suspend fun deleteStory(storyId: String): Result<Unit> = runCatching {
        firestore.collection(Constants.COLLECTION_STORIES).document(storyId).delete().await()
    }
}
