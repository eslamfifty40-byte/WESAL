package com.wesal.messenger.data.repository

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.core.util.BackendHealthStore
import com.wesal.messenger.core.util.InboxMergeLogic
import com.wesal.messenger.core.util.MessageMergeLogic
import com.wesal.messenger.core.util.MessagePlaneRouter
import com.wesal.messenger.core.util.ErrorCopy
import com.wesal.messenger.core.util.DirectChatRequestLogic
import com.wesal.messenger.core.util.MessageQueuedException
import com.wesal.messenger.core.util.OfficialChatWelcome
import com.wesal.messenger.data.local.UserPreferencesDataStore
import com.wesal.messenger.data.local.dao.WesalDao
import com.wesal.messenger.data.local.entity.CachedChatEntity
import com.wesal.messenger.data.local.entity.CachedMessageEntity
import com.wesal.messenger.domain.model.*
import com.wesal.messenger.domain.repository.ChatRepository
import com.wesal.messenger.security.E2EECryptoManager
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Round 12 (addendum 12): the ACK window for a message write — how long
 * the send waits for the Firestore SDK to acknowledge the commit before
 * the write is treated as QUEUED in the SDK's persistent mutation queue
 * (see MessageQueuedException). It is NOT a send deadline: a normal
 * send can no longer die on it.
 */
private const val SEND_WRITE_TIMEOUT_MS = 15_000L

/** Spec §13.3 pagination: live listeners page instead of streaming whole collections. */
private const val CHAT_PAGE_SIZE = 100
private const val MESSAGE_PAGE_SIZE = 60

/**
 * Failover stage 2 (§5.3): bound on the per-chat in-memory Supabase-plane
 * message list (poll deltas accumulate across a long open-chat session;
 * older history stays reachable through fetchOlderMessages + Room).
 */
private const val PLANE_IN_MEMORY_CAP = 300

/** Failover stage 2 (§5.3): open-chat delta-poll cadence — active, then idle. */
private const val PLANE_POLL_ACTIVE_MS = 4_000L
private const val PLANE_POLL_IDLE_MS = 15_000L

/** Hard ceiling for the paged chats listener (FIXLOG_R4_F's claimed cap, now enforced). */
private const val CHAT_PAGE_MAX = 1000

/**
 * Spec §1.4: resumable-upload session URIs, persisted per (account, message) so a media
 * retry — in this process or after process death — resumes the same Firebase Storage
 * session instead of restarting from byte 0. Separate store: the text outbox has no
 * media entries, and a session URI is not sensitive data.
 */
private val Context.uploadSessionDataStore by preferencesDataStore(name = "wesal_upload_sessions")

@Singleton
class ChatRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val wesalDao: WesalDao,
    private val cryptoManager: E2EECryptoManager,
    private val prefs: UserPreferencesDataStore,
    private val supabaseClient: SupabaseMessageClient
) : ChatRepository {

    private data class CachedUserEntry(val user: WesalUser, val fetchedAtMs: Long)

    // Chats-list sync state (spec §13.3): the snapshot listener below used to swallow its
    // errors, so a failed first load looked exactly like an empty account. The state is
    // published here so the list UI can show loading (which always stops) and error+retry.
    private val _chatListState = MutableStateFlow(ChatListSyncState())
    private val chatListStateFlow: StateFlow<ChatListSyncState> = _chatListState.asStateFlow()

    override fun observeChatListState(): Flow<ChatListSyncState> = chatListStateFlow

    // Messages sync state (spec §§1.2/13.4): the per-chat messages listener used to
    // swallow its errors entirely, so a dead listener looked like an empty chat. One
    // chat detail screen is open at a time, so a single shared state is sufficient;
    // it also carries the cached-vs-live flag (§13.3) from snapshot metadata.
    private val _messageSyncState = MutableStateFlow(ChatListSyncState())
    private val messageSyncStateFlow: StateFlow<ChatListSyncState> = _messageSyncState.asStateFlow()

    override fun observeMessageSyncState(): Flow<ChatListSyncState> = messageSyncStateFlow

    // Round 12 (addendum 10b): raw request state of a single chat doc.
    override fun observeChatRequestState(chatId: String): Flow<ChatRequestState> = callbackFlow {
        if (chatId.isBlank()) {
            trySend(ChatRequestState())
            close()
            return@callbackFlow
        }
        val registration = firestore.collection(Constants.COLLECTION_CHATS)
            .document(chatId)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || !snapshot.exists()) {
                    trySend(ChatRequestState())
                    return@addSnapshotListener
                }
                trySend(
                    ChatRequestState(
                        requestStatus = snapshot.getString("requestStatus").orEmpty(),
                        requestFromUid = snapshot.getString("requestFromUid").orEmpty(),
                        isMessageRequest = snapshot.getBoolean("isMessageRequest") ?: false
                    )
                )
            }
        awaitClose { registration.remove() }
    }.distinctUntilChanged()

    // Chats pagination (spec §13.3): the live listener pages at this size; the list UI
    // asks for more via requestMoreChats() instead of the whole collection streaming.
    private val _chatLimit = MutableStateFlow(CHAT_PAGE_SIZE)

    override fun requestMoreChats() {
        // Review MINOR-3: FIXLOG_R4_F claimed a 1000 cap that the code did not
        // enforce — the limit grew without bound. Enforce it: past the cap the
        // listener cost defeats the point of paging.
        _chatLimit.value = (_chatLimit.value + CHAT_PAGE_SIZE).coerceAtMost(CHAT_PAGE_MAX)
    }

    // Whether the latest PAGED chats snapshot came back full, i.e. more chats
    // may exist beyond the current page. Drives the list's load-more footer
    // from repository page state rather than the filtered UI list (MINOR-4).
    private val _chatsHasMore = MutableStateFlow(false)
    private val chatsHasMoreFlow: StateFlow<Boolean> = _chatsHasMore.asStateFlow()

    override fun observeChatsHasMore(): Flow<Boolean> = chatsHasMoreFlow

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Hidden-chat ids of the ACTIVE account (spec §8.3 cache exclusion reads this). */
    private suspend fun hiddenChatIdsForActiveAccount(): Set<String> =
        runCatching { prefs.settingsFlow.firstOrNull()?.hiddenChatIds }.getOrNull().orEmpty()

    // --- Resumable-upload session persistence (spec §1.4) ---
    private fun uploadSessionKey(uid: String, messageId: String) =
        stringPreferencesKey("session_${uid}_$messageId")

    private suspend fun storedUploadSession(uid: String, messageId: String): Uri? =
        runCatching {
            context.uploadSessionDataStore.data.first()[uploadSessionKey(uid, messageId)]
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }

    private fun persistUploadSession(uid: String, messageId: String, sessionUri: Uri) {
        ioScope.launch {
            runCatching {
                context.uploadSessionDataStore.edit {
                    it[uploadSessionKey(uid, messageId)] = sessionUri.toString()
                }
            }
        }
    }

    private suspend fun clearUploadSession(uid: String, messageId: String) {
        runCatching {
            context.uploadSessionDataStore.edit { it.remove(uploadSessionKey(uid, messageId)) }
        }
    }

    private val userCache = ConcurrentHashMap<String, CachedUserEntry>()
    private val userCacheTtlMs = 20_000L
    private val deliveredThrottleMs = ConcurrentHashMap<String, Long>()

    // -------------------------------------------------------------------
    // Failover stage 2 (design §5): Supabase message-plane state.
    //
    // - attemptedStores: the store each in-flight/recent send actually
    //   targeted, by message id — the outbox stamps it on queued
    //   re-enqueue so replay checks the right store (§5.2 rule 5).
    // - planeMessageFlows: per-chat merged Supabase message lists
    //   (delta-poll results + own sends), unioned with the Firestore
    //   listener's list inside observeMessages via MessageMergeLogic.
    // - planeTypingByChat: live typing sets from msg-list responses,
    //   unioned into ChatPreview.typingUsers in mapChatPreview (§5.5).
    // - planeOldestSeq: per-chat lowest seq seen, the fetchOlderMessages
    //   plane cursor (the high-water cursor persists in DataStore).
    // - _planeInbox: the latest msg-inbox snapshot, merged into chats
    //   previews (§5.4) and diffed by the listener service (§6).
    // -------------------------------------------------------------------
    private val attemptedStores = ConcurrentHashMap<String, String>()
    private val planeMessageFlows =
        ConcurrentHashMap<String, MutableStateFlow<List<WesalMessage>>>()
    private val planeTypingByChat = ConcurrentHashMap<String, Set<String>>()
    private val planeOldestSeq = ConcurrentHashMap<String, Long>()
    private val _planeInbox = MutableStateFlow<List<SupabaseInboxRow>>(emptyList())
    private var lastInboxRefreshMs = 0L

    private fun planeMessagesFlow(chatId: String): MutableStateFlow<List<WesalMessage>> =
        planeMessageFlows.getOrPut(chatId) { MutableStateFlow(emptyList()) }

    override fun attemptedStoreFor(messageId: String): String? = attemptedStores[messageId]

    /** Text resolver for plane rows: the ONE decrypt path (1:1 scope — stage 2). */
    private fun planeTextResolver(
        chatId: String,
        myUid: String,
        peerPubKeyCache: MutableMap<String, String>
    ): SupabaseMessageMapper.PlaneTextResolver =
        SupabaseMessageMapper.PlaneTextResolver { senderId, ciphertext, isEncrypted,
            plainFallback, isDeletedForEveryone, wrappedKeys ->
            resolveMessageText(
                senderId = senderId,
                myUid = myUid,
                chatId = chatId,
                isGroup = false,
                peerPubKeyCache = peerPubKeyCache,
                ciphertext = ciphertext,
                isEncrypted = isEncrypted,
                plainFallback = plainFallback,
                isDeletedForEveryone = isDeletedForEveryone,
                wrappedKeys = wrappedKeys
            )
        }

    /**
     * Whether the Supabase plane carries (or is about to carry) traffic
     * for this chat — the engagement gate for polls/merges (§4.4): a
     * persisted cursor, messages already flowing, the plane being the
     * routed primary, or Firestore being unroutable. A dark deployment
     * (Firestore primary + healthy, no plane traffic yet) never engages,
     * so it spends zero function invocations.
     */
    private suspend fun isPlaneEngagedFor(chatId: String): Boolean {
        if (planeMessagesFlow(chatId).value.isNotEmpty()) return true
        if (runCatching { prefs.planeChatCursor(chatId) }.getOrNull() != null) return true
        return planeEngagedGlobally()
    }

    /** Account-wide engagement: routed primary is Supabase, or Firestore is unroutable. */
    private fun planeEngagedGlobally(): Boolean {
        val health = BackendHealthStore.currentState()
        if (!BackendHealthStore.isRoutable(health, BackendHealthStore.Backend.FIRESTORE)) {
            return true
        }
        return MessagePlaneRouter.effectivePrimary(
            MessagePlaneRouter.currentConfig(), health
        ) == MessagePlaneRouter.Store.SUPABASE
    }

    /**
     * Failover §5.4: merge the latest Supabase inbox snapshot into
     * Firestore-built previews. A chat's summary comes from whichever
     * store saw the newer last message ((clientTs, id) order); unread
     * counts SUM (the two stores' increments are disjoint by
     * construction); typing sets union. Chats with no inbox row pass
     * through untouched. The Firestore side carries no lastMessageId
     * today, so its tiebreak id is null (the repair pass starts
     * stamping it into the doc for future compares).
     */
    private fun mergePlaneInboxIntoPreviews(
        previews: List<ChatPreview>
    ): List<ChatPreview> {
        val inbox = _planeInbox.value
        if (inbox.isEmpty()) return previews
        val byChat = inbox.associateBy { it.chatId }
        var anyRow = false
        val merged = previews.map { preview ->
            val row = byChat[preview.chatId] ?: return@map preview
            anyRow = true
            var out = preview
            if (InboxMergeLogic.isInboxNewer(preview.lastMessageTime, null, row)) {
                out = out.copy(
                    lastMessagePreview = row.lastMessagePreview
                        .ifBlank { out.lastMessagePreview },
                    lastMessageTime = maxOf(
                        preview.lastMessageTime,
                        row.lastMessageServerTsMs.takeIf { it > 0L }
                            ?: row.lastMessageClientTs
                    ),
                    lastMessageSenderId = row.lastMessageSenderId
                        ?: out.lastMessageSenderId,
                    lastMessageStatus = row.lastMessageStatus
                        ?.let { MessageStatus.fromRaw(it) } ?: out.lastMessageStatus
                )
            }
            val summed = InboxMergeLogic.mergedUnreadCount(
                preview.unreadCount, row.unreadCount
            )
            if (summed != out.unreadCount) out = out.copy(unreadCount = summed)
            val planeTyping = planeTypingByChat[preview.chatId].orEmpty()
            if (planeTyping.isNotEmpty()) {
                out = out.copy(
                    typingUsers = (out.typingUsers + planeTyping).distinct()
                )
            }
            out
        }
        if (!anyRow) return previews
        return merged.sortedWith(
            compareByDescending<ChatPreview> { it.isOfficialWesalChat }
                .thenByDescending { it.isPinned }
                .thenByDescending { it.lastMessageTime }
        )
    }

    override suspend fun refreshPlaneInbox(): List<SupabaseInboxRow> {
        // Engagement gate (§4.4): a dark deployment never calls
        // msg-inbox — zero function invocations until the plane
        // carries traffic or Firestore fails.
        val engaged = runCatching { prefs.planeEngaged() }.getOrDefault(false) ||
            planeEngagedGlobally()
        if (!engaged) return _planeInbox.value
        // Throttle: the chats flow, the listener service and chat
        // opens all ask; the inbox changes at message cadence.
        val now = System.currentTimeMillis()
        if (now - lastInboxRefreshMs < 15_000L && _planeInbox.value.isNotEmpty()) {
            return _planeInbox.value
        }
        lastInboxRefreshMs = now
        val rows = runCatching { supabaseClient.inbox() }.getOrDefault(emptyList())
        if (rows.isNotEmpty()) {
            _planeInbox.value = rows
            runCatching { prefs.setPlaneEngaged() }
            repairFirestoreSummaries(rows)
        }
        return rows
    }

    /**
     * §5.4 summary repair: while Firestore is healthy, any chat whose
     * plane inbox summary is newer than its Firestore doc gets the
     * doc's lastMessage* fields rewritten from the inbox row (guarded
     * compare against a fresh read; one update per stale chat; NO
     * unread increment — the preview merge already sums, and the
     * inbox counter is the plane's authority). This is what heals
     * summaries for Firestore-only peers after an outage.
     */
    private suspend fun repairFirestoreSummaries(rows: List<SupabaseInboxRow>) {
        val health = BackendHealthStore.currentState()
        if (!BackendHealthStore.isRoutable(health, BackendHealthStore.Backend.FIRESTORE)) {
            return
        }
        rows.forEach { row ->
            runCatching {
                val ref = firestore.collection(Constants.COLLECTION_CHATS)
                    .document(row.chatId)
                val snap = ref.get().await()
                if (!snap.exists()) return@runCatching
                val fsTime = snap.getLong("lastMessageTime") ?: 0L
                val fsId = snap.getString("lastMessageId")
                if (!InboxMergeLogic.isInboxNewer(fsTime, fsId, row)) {
                    return@runCatching
                }
                ref.update(
                    hashMapOf<String, Any?>(
                        "lastMessagePreview" to row.lastMessagePreview,
                        "lastMessageTime" to row.lastMessageClientTs,
                        "lastMessageSenderId" to row.lastMessageSenderId,
                        "lastMessageStatus" to row.lastMessageStatus,
                        "lastMessageId" to row.lastMessageId
                    )
                ).await()
            }.onFailure { BackendHealthStore.reportFirestoreError(it) }
        }
    }

    /** Merges [rows] into the chat's plane list (bounded) and returns the new list. */
    private fun publishPlaneMessages(
        chatId: String,
        rows: List<WesalMessage>
    ): List<WesalMessage> {
        if (rows.isEmpty()) return planeMessagesFlow(chatId).value
        val flow = planeMessagesFlow(chatId)
        val merged = MessageMergeLogic.merge(flow.value, rows)
        flow.value = if (merged.size > PLANE_IN_MEMORY_CAP) {
            merged.takeLast(PLANE_IN_MEMORY_CAP)
        } else {
            merged
        }
        return flow.value
    }

    /** Room upsert for plane-homed rows (store-stamped; hidden chats excluded, §8.3). */
    private suspend fun upsertPlaneRowsToRoom(chatId: String, myUid: String, rows: List<WesalMessage>) {
        if (rows.isEmpty()) return
        if (chatId in hiddenChatIdsForActiveAccount()) return
        wesalDao.upsertMessages(rows.map {
            CachedMessageEntity(
                messageId = it.messageId,
                chatId = it.chatId,
                senderId = it.senderId,
                senderName = it.senderName,
                type = it.type.raw,
                text = it.text,
                mediaUrl = it.mediaUrl,
                mediaLocalPath = it.mediaLocalPath,
                mediaMimeType = it.mediaMimeType,
                mediaFileName = it.mediaFileName,
                mediaSizeBytes = it.mediaSizeBytes,
                mediaDurationMs = it.mediaDurationMs,
                status = it.status.raw,
                timestamp = it.timestamp,
                ownerUid = myUid,
                store = MessagePlaneRouter.wireName(MessagePlaneRouter.Store.SUPABASE)
            )
        })
    }

    private fun observeAuthUid(): Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { fa -> trySend(fa.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    private suspend fun verifyIsOwnerSuperAdmin(uid: String): Boolean {
        val currentUser = auth.currentUser ?: return false
        if (currentUser.uid != uid) return false
        val email = currentUser.email.orEmpty()
        if (!Constants.isSuperAdminEmail(email) || !currentUser.isEmailVerified) {
            return false
        }
        val adminSnap = runCatching {
            firestore.collection(Constants.COLLECTION_SUPER_ADMINS).document(uid).get().await()
        }.getOrNull() ?: return false
        val userSnap = runCatching {
            firestore.collection(Constants.COLLECTION_USERS).document(uid).get().await()
        }.getOrNull()
        val numericId = userSnap?.getString("numericId").orEmpty()
        return adminSnap.exists() && numericId in Constants.ADMIN_NUMERIC_IDS
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeChats(): Flow<List<ChatPreview>> =
        combine(observeAuthUid(), _chatLimit) { uid, limit -> uid to limit }
            .flatMapLatest { (uid, chatLimit) ->
                chatsFlow(uid, limit = chatLimit.toLong(), paged = true)
            }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAllChats(): Flow<List<ChatPreview>> =
        observeAuthUid().flatMapLatest { uid -> chatsFlow(uid, limit = null, paged = false) }

    /**
     * Shared chats-listener body behind [observeChats] / [observeAllChats].
     * The paged form (the main list, spec §13.3) limits the query to [limit]
     * and drives the shared list sync state + has-more flag. The unpaged form
     * (review M-3) serves picker/lookup consumers — forward targets, camera
     * share targets, profile direct-chat / mutual-groups lookups — restoring
     * their pre-paging behaviour (the whole list) without un-paging the main
     * list, and leaves the main list's state untouched.
     */
    private fun chatsFlow(uid: String?, limit: Long?, paged: Boolean): Flow<List<ChatPreview>> {
        userCache.clear()
        deliveredThrottleMs.clear()
        if (uid == null) {
            if (paged) _chatListState.value = ChatListSyncState(ChatListSyncStatus.READY)
            return flowOf(emptyList())
        }
        return callbackFlow {
            if (paged) _chatListState.value = ChatListSyncState(ChatListSyncStatus.LOADING)
                val hasRemoteEmission = AtomicBoolean(false)
                // Failover stage 2 (§5.4): the latest Firestore-built
                // previews BEFORE the plane merge — the base the inbox
                // merge re-applies over when the inbox snapshot changes
                // (merging an already-merged list would double-count
                // unread, since the merge sums).
                var latestFirestorePreviews: List<ChatPreview> = emptyList()
                val previewLock = Any()
                launch(Dispatchers.IO) {
                    val cached = runCatching { wesalDao.observeChats(uid).firstOrNull() }.getOrNull()
                    if (!hasRemoteEmission.get() && !cached.isNullOrEmpty()) {
                        trySend(
                            cached.map {
                                ChatPreview(
                                    chatId = it.chatId,
                                    isGroup = it.isGroup,
                                    peerUid = it.peerUid,
                                    title = it.title.ifBlank { "محادثة WESAL" },
                                    avatarUrl = it.avatarUrl,
                                    lastMessagePreview = it.lastMessagePreview,
                                    lastMessageTime = it.lastMessageTime,
                                    unreadCount = it.unreadCount,
                                    isPinned = it.isPinned,
                                    isMuted = it.isMuted,
                                    isArchived = it.isArchived,
                                    isOfficialWesalChat = it.chatId.startsWith(Constants.OFFICIAL_CHAT_PREFIX)
                                )
                            }
                        )
                    }
                }

                // Spec §13.3: page the listener (newest first). The ordered+limited
                // form needs a composite index (participantIds array-contains +
                // lastMessageTime); if the backend reports FAILED_PRECONDITION the
                // subscription falls back to the plain form so the list never dies.
                var registration: ListenerRegistration? = null
                fun subscribeChats(ordered: Boolean) {
                    val baseQuery = firestore.collection(Constants.COLLECTION_CHATS)
                        .whereArrayContains("participantIds", uid)
                    val orderedQuery = baseQuery.orderBy("lastMessageTime", Query.Direction.DESCENDING)
                    val query = if (ordered) {
                        if (limit != null) orderedQuery.limit(limit) else orderedQuery
                    } else {
                        baseQuery
                    }
                    // Round 10 (item 1, second root cause): MetadataChanges.INCLUDE is
                    // load-bearing for the sync state. With the default (EXCLUDE), the
                    // listener fires for the initial CACHE snapshot (isFromCache=true)
                    // and then NEVER fires again for the server's confirmation when the
                    // documents are unchanged — a pure metadata transition raises no
                    // event — so ChatListSyncState.fromCache latched true forever and
                    // the chats list showed «غير متصل» permanently on a healthy
                    // network (owner screenshot 168803, r9). INCLUDE delivers the
                    // cache→server transition, so fromCache tracks reality.
                    registration = query.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                        if (error != null || snapshot == null) {
                            // Failover design §5.1 (stage 1, observing):
                            // the router's health store learns of quota
                            // death at the same choke points that already
                            // route errors through ErrorCopy.
                            if (error != null) BackendHealthStore.reportFirestoreError(error)
                            if (ordered &&
                                error?.code == FirebaseFirestoreException.Code.FAILED_PRECONDITION
                            ) {
                                subscribeChats(ordered = false)
                                return@addSnapshotListener
                            }
                            // Surface the failure instead of swallowing it: the chats UI
                            // shows an error state with retry (Firestore drops the listener
                            // after an error, so retry re-subscribes via a fresh collection).
                            if (paged) {
                                _chatListState.value = ChatListSyncState(
                                    status = ChatListSyncStatus.ERROR,
                                    // Round 13 (Addendum L5): fixed Arabic
                                    // state only — never the raw exception.
                                    errorMessage = ErrorCopy.friendly(error, "تعذر تحميل المحادثات")
                                )
                            }
                            return@addSnapshotListener
                        }
                        hasRemoteEmission.set(true)
                        // Failover stage 2: a live chats snapshot is a
                        // successful Firestore probe (heals quota-death;
                        // no-op while healthy — see BackendHealthStore).
                        BackendHealthStore.recordProbeResult(
                            BackendHealthStore.Backend.FIRESTORE, true
                        )
                        if (paged) {
                            _chatListState.value = ChatListSyncState(
                                status = ChatListSyncStatus.READY,
                                fromCache = snapshot.metadata.isFromCache
                            )
                            // Review MINOR-4: a full ordered page means more
                            // chats may exist beyond it — the load-more footer
                            // reads this flag, not the filtered UI list. The
                            // unordered fallback is unpaged, so it ends paging.
                            _chatsHasMore.value = ordered && limit != null &&
                                snapshot.size().toLong() >= limit
                        }
                        launch(Dispatchers.IO) {
                            val now = System.currentTimeMillis()
                            val missingPeerUids = snapshot.documents
                                .asSequence()
                                .filter { it.getBoolean("isGroup") != true && it.getBoolean("isOfficialWesalChat") != true }
                                .mapNotNull { doc ->
                                    @Suppress("UNCHECKED_CAST")
                                    (doc.get("participantIds") as? List<String>)?.firstOrNull { it != uid }
                                }
                                .filter { peerId ->
                                    val cached = userCache[peerId]
                                    cached == null || (now - cached.fetchedAtMs >= userCacheTtlMs)
                                }
                                .distinct()
                                .toList()

                            if (missingPeerUids.isNotEmpty()) {
                                coroutineScope {
                                    missingPeerUids.map { peerId ->
                                        async(Dispatchers.IO) { runCatching { fetchUserCached(peerId) } }
                                    }.awaitAll()
                                }
                            }

                            val sortedFirestorePreviews = snapshot.documents.mapNotNull { doc ->
                                mapChatPreview(doc, uid)
                            }.sortedWith(
                                compareByDescending<ChatPreview> { it.isOfficialWesalChat }
                                    .thenByDescending { it.isPinned }
                                    .thenByDescending { it.lastMessageTime }
                            )
                            synchronized(previewLock) {
                                latestFirestorePreviews = sortedFirestorePreviews
                            }
                            // Failover stage 2 (§5.4): union the plane
                            // inbox snapshot into the previews.
                            val previews = mergePlaneInboxIntoPreviews(sortedFirestorePreviews)
                            // Spec §8.3: hidden chats are never written to the Room
                            // cache and any earlier rows of theirs are purged — the
                            // previews still emit (the UI filters hidden chats itself).
                            // Spec §3.3: rows are stamped with the owning account.
                            val hiddenIds = hiddenChatIdsForActiveAccount()
                            if (hiddenIds.isNotEmpty()) {
                                previews.filter { it.chatId in hiddenIds }.forEach { hidden ->
                                    wesalDao.deleteChat(hidden.chatId)
                                    wesalDao.deleteMessagesForChat(hidden.chatId)
                                }
                            }
                            wesalDao.upsertChats(previews.filter { it.chatId !in hiddenIds }.map {
                                CachedChatEntity(
                                    chatId = it.chatId,
                                    isGroup = it.isGroup,
                                    title = it.title,
                                    avatarUrl = it.avatarUrl,
                                    peerUid = it.peerUid,
                                    lastMessagePreview = it.lastMessagePreview,
                                    lastMessageTime = it.lastMessageTime,
                                    unreadCount = it.unreadCount,
                                    isPinned = it.isPinned,
                                    isArchived = it.isArchived,
                                    isMuted = it.isMuted,
                                    ownerUid = uid
                                )
                            })
                            trySend(previews)
                        }
                    }
                }
                subscribeChats(ordered = true)

                // Failover stage 2 (§5.4): re-merge + re-emit whenever
                // the plane inbox snapshot changes. During a Firestore
                // outage the snapshot listener goes silent — this is
                // what keeps the list's summaries/unread counts moving.
                launch(Dispatchers.IO) {
                    refreshPlaneInbox()
                    _planeInbox.collect {
                        val base = synchronized(previewLock) { latestFirestorePreviews }
                        if (base.isNotEmpty()) {
                            trySend(mergePlaneInboxIntoPreviews(base))
                        }
                    }
                }

                awaitClose { registration?.remove() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun mapChatPreview(doc: DocumentSnapshot, myUid: String): ChatPreview? {
        val participantIds = (doc.get("participantIds") as? List<String>) ?: return null
        val deletedBy = (doc.get("deletedForUsers") as? List<String>) ?: emptyList()
        if (deletedBy.contains(myUid)) return null

        val isGroup = doc.getBoolean("isGroup") ?: false
        val isOfficialWesalChat = (doc.getBoolean("isOfficialWesalChat") ?: false) ||
            doc.id.startsWith(Constants.OFFICIAL_CHAT_PREFIX)
        val groupId = doc.getString("groupId")
        val pinnedMap = (doc.get("pinnedBy") as? Map<String, Boolean>) ?: emptyMap()
        val mutedMap = (doc.get("mutedBy") as? Map<String, Boolean>) ?: emptyMap()
        val archivedMap = (doc.get("archivedBy") as? Map<String, Boolean>) ?: emptyMap()
        val unreadMap = (doc.get("unreadCounts") as? Map<String, Number>) ?: emptyMap()
        val typingMap = (doc.get("typingUsers") as? Map<String, Boolean>) ?: emptyMap()

        var title = doc.getString("groupName").orEmpty()
        var avatar = doc.getString("groupAvatarUrl").orEmpty()
        var peerUid = ""
        var peerPubKey = ""
        var peerOnline = false
        var peerLastSeen = 0L
        var peerVerified = false
        var peerVerificationType = "BLUE"
        var peerVerificationGlowColor = ""
        var peerVerificationGlowEnabled = true
        var peerVerificationGlowIntensity = 0.7f
        var peerFrameId = ""
        var peerFrameExpiresAt = 0L
        var peerIconUrl = ""
        var peerPhotoRemovedBg = false
        var peerNeonColor = ""
        var peerNeonGlowIntensity = 0.7f
        var peerNeonEnabled = false
        var peerNameplateColor = ""
        var peerPhotoUserFramed = false

        val unreadCount = unreadMap[myUid]?.toInt() ?: 0
        val lastSenderId = doc.getString("lastMessageSenderId").orEmpty()
        val lastStatus = MessageStatus.fromRaw(doc.getString("lastMessageStatus"))

        if (isOfficialWesalChat) {
            title = title.ifBlank { "WESAL Messenger" }
            // Round 5: photo, frame and badge derive from the official account's
            // own user doc (10001) — whatever the admin sets on that account is
            // what the welcome conversation shows. Missing doc → historical
            // defaults (verified BLUE badge; the UI falls back to the royal
            // crest when no frame is set).
            val ownerIdentity = fetchOwnerAdminIdentity()
            if (avatar.isBlank()) {
                avatar = ownerIdentity?.photoUrl.orEmpty()
            }
            peerVerified = ownerIdentity?.isVerified ?: true
            peerVerificationType = ownerIdentity?.verificationType ?: "BLUE"
            peerFrameId = ownerIdentity?.frameId.orEmpty()
            peerFrameExpiresAt = ownerIdentity?.frameExpiresAt ?: 0L
            peerPhotoUserFramed = ownerIdentity?.photoUserFramed ?: false
        } else if (!isGroup) {
            peerUid = participantIds.firstOrNull { it != myUid }.orEmpty()
            if (peerUid.isNotBlank()) {
                val peer = fetchUserCached(peerUid)
                if (peer != null) {
                    if (peer.deletedAt > 0L || peer.deletedPermanent) {
                        title = "حساب محذوف"
                        avatar = ""
                        peerOnline = false
                        peerVerified = false
                    } else {
                        val safeHandle = peer.publicId.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty()
                        title = peer.displayName.ifBlank { safeHandle }
                        avatar = if (peer.privacySettings.profilePhotoVisibility == PrivacyVisibility.NOBODY || peer.privacySettings.hideAvatarPhoto) "" else peer.photoUrl
                        peerPubKey = peer.publicKey
                        peerOnline = peer.isOnline && peer.privacySettings.onlineVisibility != PrivacyVisibility.NOBODY
                        peerLastSeen = if (peer.privacySettings.lastSeenVisibility == PrivacyVisibility.NOBODY) 0L else peer.lastSeen
                        peerVerified = peer.isVerified
                        peerVerificationType = peer.verificationType
                        peerVerificationGlowColor = peer.verificationGlowColor
                        peerVerificationGlowEnabled = peer.verificationGlowEnabled
                        peerVerificationGlowIntensity = peer.verificationGlowIntensity
                        peerFrameId = peer.frameId
                        peerFrameExpiresAt = peer.frameExpiresAt
                        peerIconUrl = com.wesal.messenger.core.util.WesalFrameLibrary.resolveIconUrl(peer.iconId, peer.iconUrl)
                        peerPhotoRemovedBg = peer.photoRemovedBg
                        peerNeonColor = peer.neonColor
                        peerNeonGlowIntensity = peer.neonGlowIntensity
                        peerNeonEnabled = peer.neonEnabled
                        peerNameplateColor = peer.nameplateColor
                        peerPhotoUserFramed = peer.photoUserFramed
                    }
                }
            }
        }

        if (lastSenderId.isNotBlank() && lastSenderId != myUid && lastStatus == MessageStatus.SENT) {
            markIncomingMessagesDelivered(doc.id, myUid)
        }

        val rawPreview = doc.getString("lastMessagePreview").orEmpty()
        val sanitizedPreview = if (rawPreview == "رسالة مشفرة") "رسالة سابقة" else rawPreview
        // AUDIT SEC-A fix (A11 directionality): requestStatus is viewer-relative.
        // A pending/held/rejected request binds the RECIPIENT, never the person who
        // sent the request: previously both participants saw the chat as a "message
        // request", so the sender's own chat vanished from their main list into the
        // requests inbox and the in-chat banner offered them Accept/Hold/Reject on
        // their own outgoing request. requestFromUid is stamped by
        // setMessageRequestDecision; legacy docs without it keep the old behaviour.
        val rawReqStatus = doc.getString("requestStatus")?.takeIf { it.isNotBlank() } ?: "ACCEPTED"
        val requestFromUid = doc.getString("requestFromUid").orEmpty()
        val iAmRequester = requestFromUid.isNotBlank() && requestFromUid == myUid
        val reqStatus = if (iAmRequester) "ACCEPTED" else rawReqStatus
        val isReqFlag = !iAmRequester &&
            (doc.getBoolean("isMessageRequest") ?: false) && reqStatus != "ACCEPTED"

        return ChatPreview(
            chatId = doc.id,
            isGroup = isGroup,
            groupId = groupId,
            participantIds = participantIds,
            peerUid = peerUid,
            title = title.ifBlank { "محادثة WESAL" },
            avatarUrl = avatar,
            peerPublicKey = peerPubKey,
            peerOnline = peerOnline,
            peerLastSeen = peerLastSeen,
            peerVerified = peerVerified,
            peerVerificationType = peerVerificationType,
            peerVerificationGlowColor = peerVerificationGlowColor,
            peerVerificationGlowEnabled = peerVerificationGlowEnabled,
            peerVerificationGlowIntensity = peerVerificationGlowIntensity,
            peerFrameId = peerFrameId,
            peerFrameExpiresAt = peerFrameExpiresAt,
            peerIconUrl = peerIconUrl,
            peerPhotoRemovedBg = peerPhotoRemovedBg,
            peerNeonColor = peerNeonColor,
            peerNeonGlowIntensity = peerNeonGlowIntensity,
            peerNeonEnabled = peerNeonEnabled,
            peerNameplateColor = peerNameplateColor,
            peerPhotoUserFramed = peerPhotoUserFramed,
            officialWallpaperUrl = doc.getString("officialWallpaperUrl").orEmpty(),
            lastMessagePreview = sanitizedPreview,
            lastMessageTime = doc.getLong("lastMessageTime") ?: 0L,
            lastMessageSenderId = lastSenderId,
            lastMessageStatus = lastStatus,
            unreadCount = unreadCount,
            isPinned = pinnedMap[myUid] ?: isOfficialWesalChat,
            isMuted = mutedMap[myUid] ?: false,
            isArchived = archivedMap[myUid] ?: false,
            isSupportChat = doc.getBoolean("isSupportChat") ?: false,
            isOfficialWesalChat = isOfficialWesalChat,
            isMessageRequest = isReqFlag,
            requestStatus = reqStatus,
            disappearingDurationSec = doc.getLong("disappearingDurationSec") ?: 0L,
            deletedForUsers = deletedBy,
            // Failover stage 2 (§5.5): plane typing rows (from msg-list
            // polls) union with the Firestore typingUsers map.
            typingUsers = (
                typingMap.filter { it.key != myUid && it.value }.keys +
                    (planeTypingByChat[doc.id] ?: emptySet())
                ).distinct()
        )
    }

    private suspend fun markIncomingMessagesDelivered(chatId: String, myUid: String) {
        val now = System.currentTimeMillis()
        val lastRun = deliveredThrottleMs[chatId] ?: 0L
        if (now - lastRun < 5_000L) return
        deliveredThrottleMs[chatId] = now
        runCatching {
            val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
            val sentSnap = chatRef.collection(Constants.COLLECTION_MESSAGES)
                .whereEqualTo("status", MessageStatus.SENT.raw)
                .get()
                .await()
            val batch = firestore.batch()
            var anyUpdated = false
            sentSnap.documents.forEach { msgDoc ->
                if (msgDoc.getString("senderId") != myUid) {
                    batch.update(msgDoc.reference, "status", MessageStatus.DELIVERED.raw)
                    anyUpdated = true
                }
            }
            if (anyUpdated) {
                batch.update(chatRef, "lastMessageStatus", MessageStatus.DELIVERED.raw)
                batch.commit().await()
            }
        }
    }

    private suspend fun fetchUserCached(uid: String, forceServer: Boolean = false): WesalUser? {
        val now = System.currentTimeMillis()
        val existing = if (forceServer) null else userCache[uid]
        if (existing != null && now - existing.fetchedAtMs < userCacheTtlMs) {
            return existing.user
        }
        val ref = firestore.collection(Constants.COLLECTION_USERS).document(uid)
        val snap = runCatching {
            if (forceServer) ref.get(Source.SERVER).await() else ref.get().await()
        }.getOrNull() ?: runCatching { ref.get().await() }.getOrNull() ?: return userCache[uid]?.user
        if (!snap.exists()) return null
        // Defence in depth (round-3): the strict parse must never be able to fail
        // every feature that reads users. One malformed field in an old document
        // (legacy-typed ban fields are the known case) made toWesalUser() unusable
        // for that doc, and because the send path reads the sender's own doc first,
        // a single bad field failed EVERY send for that user. On any parse failure
        // fall back to a minimal user built from type-tolerant raw reads.
        val user = runCatching { snap.toWesalUser() }.getOrElse {
            buildFallbackUser(snap)
        }
        userCache[uid] = CachedUserEntry(user, now)
        return user
    }

    /**
     * Minimal user built from type-tolerant raw reads, used only when the strict
     * [toWesalUser] parse fails on a malformed field in an old document. Carries the
     * fields the chat/call paths actually depend on (identity, display name, E2E
     * public key, ban state, deletion state); everything else keeps model defaults.
     */
    private fun buildFallbackUser(snap: DocumentSnapshot): WesalUser {
        val data = snap.data ?: emptyMap()
        fun str(key: String): String = (data[key] as? String).orEmpty()
        return WesalUser(
            uid = str("uid").ifBlank { snap.id },
            email = str("email"),
            phoneNumber = str("phoneNumber"),
            displayName = str("displayName"),
            publicId = str("publicId"),
            numericId = str("numericId"),
            photoUrl = str("photoUrl"),
            coverUrl = str("coverUrl"),
            publicKey = str("publicKey"),
            isBanned = isBanClearlyActive(data["isBanned"], data["banType"], data["banExpiresAt"]),
            banType = str("banType"),
            banReason = str("banReason"),
            banExpiresAt = coerceEpochMillis(data["banExpiresAt"]) ?: 0L,
            hiddenFromSearch = (data["hiddenFromSearch"] as? Boolean) ?: false,
            deletedAt = (data["deletedAt"] as? Number)?.toLong() ?: 0L,
            deletedPermanent = (data["deletedPermanent"] as? Boolean) ?: false,
            isOnline = (data["isOnline"] as? Boolean) ?: false,
            lastSeen = coerceEpochMillis(data["lastSeen"]) ?: 0L
        )
    }

    /** Epoch millis from a raw Firestore value of any plausible stored type; null when unreadable. */
    private fun coerceEpochMillis(raw: Any?): Long? = when (raw) {
        is Number -> raw.toLong()
        is com.google.firebase.Timestamp -> raw.toDate().time
        is java.util.Date -> raw.time
        is String -> raw.trim().toLongOrNull()
        else -> null
    }

    /**
     * Whether a ban described by raw doc values is CLEARLY active: the flag is set
     * AND (the type says PERMANENT OR a readable expiry is still in the future).
     * A missing/malformed expiry never blocks on its own — mirroring the hardened
     * isCurrentlyBanned() in firestore.rules.
     */
    private fun isBanClearlyActive(isBannedRaw: Any?, banTypeRaw: Any?, banExpiresAtRaw: Any?): Boolean {
        if (isBannedRaw as? Boolean != true) return false
        if ((banTypeRaw as? String).orEmpty().equals("PERMANENT", ignoreCase = true)) return true
        val expiry = coerceEpochMillis(banExpiresAtRaw) ?: return false
        return expiry > System.currentTimeMillis()
    }

    /**
     * Type-tolerant self-ban gate for the send paths. Reads the caller's own doc
     * RAW rather than trusting the parsed model: the strict parse derives isBanned
     * from a Long-typed expiry, so a legacy Timestamp/String expiry silently
     * degraded to "permanent" and blocked every send. Any read failure also
     * resolves to "not banned" here — this pre-check only exists to show a friendly
     * message; the write itself (and firestore.rules) remains the enforcement point.
     */
    private suspend fun isSelfClearlyBanned(myUid: String): Boolean {
        val snap = runCatching {
            firestore.collection(Constants.COLLECTION_USERS).document(myUid).get().await()
        }.getOrNull() ?: return false
        if (!snap.exists()) return false
        return isBanClearlyActive(snap.get("isBanned"), snap.get("banType"), snap.get("banExpiresAt"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeMessages(chatId: String): Flow<List<WesalMessage>> = observeAuthUid().flatMapLatest { myUid ->
        if (myUid == null) {
            flowOf(emptyList())
        } else {
            callbackFlow {
                val hasRemoteMessages = AtomicBoolean(false)
                // Failover stage 2 (§5.3): merged reads. latestFirestore
                // is the newest Firestore-listener list; latestCached is
                // the Room placeholder; the chat's Supabase-plane list
                // lives in planeMessagesFlow (poll deltas + own sends).
                // Every emission is the MessageMergeLogic union of the
                // two stores — a message appears exactly once, ordered
                // by (server timestamp, id), whichever store homes it.
                var latestCached: List<WesalMessage> = emptyList()
                var latestFirestore: List<WesalMessage> = emptyList()
                var firestoreReady = false
                val mergeLock = Any()
                fun emitMerged() {
                    val merged = synchronized(mergeLock) {
                        MessageMergeLogic.merge(
                            if (firestoreReady) latestFirestore else latestCached,
                            planeMessagesFlow(chatId).value
                        )
                    }
                    trySend(merged)
                }
                launch(Dispatchers.IO) {
                    val cachedMsgs = runCatching { wesalDao.observeMessages(chatId, myUid).firstOrNull() }.getOrNull()
                    if (!hasRemoteMessages.get() && !cachedMsgs.isNullOrEmpty()) {
                        synchronized(mergeLock) {
                            latestCached = cachedMsgs.map {
                                WesalMessage(
                                    messageId = it.messageId,
                                    chatId = it.chatId,
                                    senderId = it.senderId,
                                    senderName = it.senderName,
                                    type = MessageType.fromRaw(it.type),
                                    text = it.text,
                                    mediaUrl = it.mediaUrl,
                                    mediaLocalPath = it.mediaLocalPath,
                                    mediaMimeType = it.mediaMimeType,
                                    mediaFileName = it.mediaFileName,
                                    mediaSizeBytes = it.mediaSizeBytes,
                                    mediaDurationMs = it.mediaDurationMs,
                                    status = MessageStatus.fromRaw(it.status),
                                    timestamp = it.timestamp
                                )
                            }
                        }
                        emitMerged()
                    }
                }

                val (isGroup, peerPubKeyCache) = loadPeerPubKeyCache(chatId, myUid)

                _messageSyncState.value = ChatListSyncState(ChatListSyncStatus.LOADING, chatId = chatId)
                val registration = firestore.collection(Constants.COLLECTION_CHATS)
                    .document(chatId)
                    .collection(Constants.COLLECTION_MESSAGES)
                    .orderBy("timestamp", Query.Direction.ASCENDING)
                    .limitToLast(MESSAGE_PAGE_SIZE.toLong())
                    // Round 10 (item 1): INCLUDE, same latch as the chats-list
                    // listener above — without it the in-chat «غير متصل» line
                    // (messageSyncState.fromCache) also never cleared.
                    .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                        if (error != null || snapshot == null) {
                            // Failover design §5.1 (stage 1, observing).
                            if (error != null) BackendHealthStore.reportFirestoreError(error)
                            // Spec §§1.2/13.4: surface the failure (the chat screen
                            // renders it with retry) instead of silently freezing.
                            _messageSyncState.value = ChatListSyncState(
                                status = ChatListSyncStatus.ERROR,
                                errorMessage = ErrorCopy.friendly(error, "تعذر مزامنة الرسائل"),
                                chatId = chatId
                            )
                            return@addSnapshotListener
                        }
                        hasRemoteMessages.set(true)
                        // Failover stage 2 (wiring the stage-1 state
                        // machine): a live snapshot IS a successful
                        // Firestore probe — it heals a quota-dead state
                        // and is a no-op while Firestore is healthy.
                        BackendHealthStore.recordProbeResult(
                            BackendHealthStore.Backend.FIRESTORE, true
                        )
                        _messageSyncState.value = ChatListSyncState(
                            status = ChatListSyncStatus.READY,
                            fromCache = snapshot.metadata.isFromCache,
                            chatId = chatId
                        )
                        launch(Dispatchers.Default) {
                            val pendingDeliveryRefs = snapshot.documents.mapNotNull { doc ->
                                val sId = doc.getString("senderId").orEmpty()
                                val statusRaw = doc.getString("status")
                                if (sId.isNotBlank() && sId != myUid && sId != "wesal_system" &&
                                    MessageStatus.fromRaw(statusRaw) == MessageStatus.SENT
                                ) doc.reference else null
                            }
                            if (pendingDeliveryRefs.isNotEmpty()) {
                                launch(Dispatchers.IO) {
                                    markMessageRefsDeliveredBatched(chatId, pendingDeliveryRefs)
                                }
                            }
                            val messages = snapshot.documents.mapNotNull { doc ->
                                mapAndDecryptMessage(doc, chatId, myUid, isGroup, peerPubKeyCache)
                            }
                            withContext(Dispatchers.IO) {
                                // Spec §8.3: a hidden chat's messages are never
                                // written to the Room cache (existing rows purged).
                                // Spec §3.3: rows are stamped with the owning account.
                                if (chatId in hiddenChatIdsForActiveAccount()) {
                                    wesalDao.deleteMessagesForChat(chatId)
                                } else {
                                    wesalDao.upsertMessages(messages.map {
                                        CachedMessageEntity(
                                            messageId = it.messageId,
                                            chatId = it.chatId,
                                            senderId = it.senderId,
                                            senderName = it.senderName,
                                            type = it.type.raw,
                                            text = it.text,
                                            mediaUrl = it.mediaUrl,
                                            mediaLocalPath = it.mediaLocalPath,
                                            mediaMimeType = it.mediaMimeType,
                                            mediaFileName = it.mediaFileName,
                                            mediaSizeBytes = it.mediaSizeBytes,
                                            mediaDurationMs = it.mediaDurationMs,
                                            status = it.status.raw,
                                            timestamp = it.timestamp,
                                            ownerUid = myUid
                                        )
                                    })
                                }
                            }
                            synchronized(mergeLock) {
                                latestFirestore = messages
                                firestoreReady = true
                            }
                            emitMerged()
                        }
                    }

                // Stage 2 (§5.3): plane list changes (delta polls, own
                // sends) re-emit the merged list.
                launch(Dispatchers.Default) {
                    planeMessagesFlow(chatId).collect { emitMerged() }
                }
                // Stage 2 (§5.3): the open chat's delta poll — engaged
                // chats only (the §4.4 gate keeps a dark deployment at
                // zero function invocations). Cadence: 4 s while the
                // chat is active, backing off to 15 s after ~60 s
                // without changes.
                launch(Dispatchers.IO) {
                    var idleMs = 0L
                    while (isActive) {
                        val engaged = runCatching { isPlaneEngagedFor(chatId) }
                            .getOrDefault(false)
                        if (!engaged) {
                            delay(5_000L)
                            continue
                        }
                        val changed = runCatching {
                            pollPlaneChatOnce(chatId, myUid, peerPubKeyCache)
                        }.getOrDefault(false)
                        idleMs = if (changed) 0L else idleMs + PLANE_POLL_ACTIVE_MS
                        delay(
                            if (idleMs < 60_000L) PLANE_POLL_ACTIVE_MS
                            else PLANE_POLL_IDLE_MS
                        )
                    }
                }

                awaitClose { registration.remove() }
            }
        }
    }

    /**
     * Failover §5.3: one delta-poll round for an open chat. First
     * contact fetches the newest page and establishes the persisted
     * (seq, updated_at watermark) cursor; later rounds fetch seq
     * deltas OR watermark updates (status edits to already-seen rows).
     * Rows are mapped through the shared plane mapper, cached in Room
     * (store-stamped), published into the chat's plane list, and
     * incoming SENT rows are acked DELIVERED under the same 5 s/chat
     * throttle as the Firestore path. Live typing rows ride along into
     * [planeTypingByChat] (§5.5). A chat with no mirror yet
     * (chat_not_registered) simply reports "no change" — the mirror is
     * created by whichever side sends on the plane first.
     * Returns true when anything changed (drives the poll cadence).
     */
    private suspend fun pollPlaneChatOnce(
        chatId: String,
        myUid: String,
        peerPubKeyCache: MutableMap<String, String>
    ): Boolean {
        val cursor = runCatching { prefs.planeChatCursor(chatId) }.getOrNull()
        val page = try {
            if (cursor == null) {
                supabaseClient.listMessages(chatId, limit = MESSAGE_PAGE_SIZE)
            } else {
                supabaseClient.listMessages(
                    chatId,
                    afterSeq = cursor.first,
                    watermarkMs = cursor.second,
                    limit = 100
                )
            }
        } catch (e: SupabaseMessageException) {
            if (e.errorCode == "chat_not_registered" ||
                e.errorCode == "not_a_participant"
            ) {
                return false
            }
            throw e
        }
        val typingBefore = planeTypingByChat[chatId]
        val typingNow = page.typingUserIds.filter { it != myUid }.toSet()
        if (typingNow != typingBefore) planeTypingByChat[chatId] = typingNow
        val typingChanged = typingNow != typingBefore

        if (page.rows.isEmpty()) return typingChanged

        val resolver = planeTextResolver(chatId, myUid, peerPubKeyCache)
        val mapped = page.rows.mapNotNull { row ->
            SupabaseMessageMapper.rowToMessage(row, chatId, myUid, resolver)
        }
        if (mapped.isEmpty()) return typingChanged

        val messages = mapped.map { it.message }
        runCatching {
            prefs.setPlaneChatCursor(
                chatId,
                maxOf(cursor?.first ?: 0L, mapped.maxOf { it.seq }),
                maxOf(cursor?.second ?: 0L, mapped.maxOf { it.updatedAtMs })
            )
        }
        runCatching { prefs.setPlaneEngaged() }
        val oldest = mapped.minOf { it.seq }
        val prevOldest = planeOldestSeq[chatId]
        if (prevOldest == null || oldest < prevOldest) planeOldestSeq[chatId] = oldest

        publishPlaneMessages(chatId, messages)
        upsertPlaneRowsToRoom(chatId, myUid, messages)

        // Delivery acks for incoming SENT rows — the Firestore path's
        // 5 s/chat throttle map is shared across stores (§5.3 cadence).
        val sentIds = messages.filter {
            it.senderId != myUid && it.senderId != "wesal_system" &&
                it.status == MessageStatus.SENT
        }.map { it.messageId }
        if (sentIds.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val lastRun = deliveredThrottleMs[chatId] ?: 0L
            if (now - lastRun >= 5_000L) {
                deliveredThrottleMs[chatId] = now
                runCatching {
                    supabaseClient.ackMessages(chatId, sentIds, MessageStatus.DELIVERED.raw)
                }
            }
        }
        return true
    }

    /**
     * Loads the chat document once and pre-fetches the peers' E2EE public keys.
     * Shared by the live messages listener and the older-messages backfill fetch.
     * Returns (isGroup, peerUid → publicKey).
     */
    private suspend fun loadPeerPubKeyCache(
        chatId: String,
        myUid: String
    ): Pair<Boolean, ConcurrentHashMap<String, String>> {
        val chatDoc = runCatching {
            firestore.collection(Constants.COLLECTION_CHATS).document(chatId).get().await()
        }.getOrNull()
        @Suppress("UNCHECKED_CAST")
        val participantIds = (chatDoc?.get("participantIds") as? List<String>).orEmpty()
        val isGroup = chatDoc?.getBoolean("isGroup") ?: false
        val peerPubKeyCache = ConcurrentHashMap<String, String>()
        val peerUids = participantIds.filter { it != myUid }.distinct()
        if (peerUids.isNotEmpty()) {
            coroutineScope {
                peerUids.map { uid ->
                    async(Dispatchers.IO) {
                        runCatching {
                            fetchUserCached(uid)?.publicKey?.let { key ->
                                if (key.isNotBlank()) peerPubKeyCache[uid] = key
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        return isGroup to peerPubKeyCache
    }

    /**
     * Spec §13.3: one-shot backfill of one page of older messages (the live listener
     * only streams the newest [MESSAGE_PAGE_SIZE]). Fetched messages are decrypted
     * with the same mapper as the live path and merged into the Room cache (unless
     * the chat is hidden — §8.3). Returns the page in ascending timestamp order;
     * a short page (< [pageSize]) means the beginning of the chat was reached.
     */
    override suspend fun fetchOlderMessages(
        chatId: String,
        beforeTimestamp: Long,
        pageSize: Int
    ): List<WesalMessage> {
        val myUid = auth.currentUser?.uid ?: return emptyList()
        val (isGroup, peerPubKeyCache) = loadPeerPubKeyCache(chatId, myUid)
        val snap = runCatching {
            firestore.collection(Constants.COLLECTION_CHATS)
                .document(chatId)
                .collection(Constants.COLLECTION_MESSAGES)
                // The field holds Firestore Timestamps — the bound must be one too
                // (a raw Long never matches: numbers sort before timestamps).
                .whereLessThan(
                    "timestamp",
                    com.google.firebase.Timestamp(java.util.Date(beforeTimestamp))
                )
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(pageSize.toLong())
                .get()
                .await()
        }.getOrNull()
        // Failover stage 2: a dead Firestore no longer zeroes the page —
        // the plane page below still answers (§5.3 two-cursor merge).
        val messages = snap?.documents.orEmpty().mapNotNull { doc ->
            mapAndDecryptMessage(doc, chatId, myUid, isGroup, peerPubKeyCache)
        }.reversed()
        if (messages.isNotEmpty() && chatId !in hiddenChatIdsForActiveAccount()) {
            withContext(Dispatchers.IO) {
                wesalDao.upsertMessages(messages.map {
                    CachedMessageEntity(
                        messageId = it.messageId,
                        chatId = it.chatId,
                        senderId = it.senderId,
                        senderName = it.senderName,
                        type = it.type.raw,
                        text = it.text,
                        mediaUrl = it.mediaUrl,
                        mediaLocalPath = it.mediaLocalPath,
                        mediaMimeType = it.mediaMimeType,
                        mediaFileName = it.mediaFileName,
                        mediaSizeBytes = it.mediaSizeBytes,
                        mediaDurationMs = it.mediaDurationMs,
                        status = it.status.raw,
                        timestamp = it.timestamp,
                        ownerUid = myUid
                    )
                })
            }
        }
        // Failover stage 2 (§5.3): the plane page of the same history,
        // paged by seq below the lowest seq seen for this chat. The
        // merged page is short only when BOTH stores answer short, so
        // the caller's beginning-of-history signal stays honest.
        val planeMessages = fetchOlderPlanePage(chatId, myUid, peerPubKeyCache, pageSize)
        if (planeMessages.isEmpty()) return messages
        if (messages.isEmpty()) return planeMessages
        return MessageMergeLogic.merge(messages, planeMessages)
    }

    /**
     * Failover §5.3: one older page from the Supabase plane
     * (msg-list beforeSeq), mapped + Room-cached + published like a
     * poll page. Empty when the chat has no plane traffic or its
     * beginning was already reached (lowest seq == 1).
     */
    private suspend fun fetchOlderPlanePage(
        chatId: String,
        myUid: String,
        peerPubKeyCache: MutableMap<String, String>,
        pageSize: Int
    ): List<WesalMessage> {
        val oldestSeq = planeOldestSeq[chatId]
            ?: runCatching { prefs.planeChatCursor(chatId) }.getOrNull()?.first
            ?: return emptyList()
        if (oldestSeq <= 1L) return emptyList()
        val page = runCatching {
            supabaseClient.listMessages(chatId, beforeSeq = oldestSeq, limit = pageSize)
        }.getOrNull() ?: return emptyList()
        if (page.rows.isEmpty()) return emptyList()
        val resolver = planeTextResolver(chatId, myUid, peerPubKeyCache)
        val mapped = page.rows.mapNotNull { row ->
            SupabaseMessageMapper.rowToMessage(row, chatId, myUid, resolver)
        }
        if (mapped.isEmpty()) return emptyList()
        val newOldest = mapped.minOf { it.seq }
        if (newOldest < oldestSeq) planeOldestSeq[chatId] = newOldest
        val messages = mapped.map { it.message }
        upsertPlaneRowsToRoom(chatId, myUid, messages)
        publishPlaneMessages(chatId, messages)
        return messages
    }
     * history, but [observeMessages] now streams only the newest page. This
     * one-shot walks the full subcollection with DOCUMENT cursors — a snapshot
     * cursor is type-exact, so the Long/Timestamp mix stored in `timestamp`
     * cannot break the walk the way a value bound could — decrypting with the
     * same mapper as the live path. Bounded by [maxMessages] to cap the cost.
     * Returns ascending timestamp order.
     */
    override suspend fun fetchChatMessagesForGallery(
        chatId: String,
        maxMessages: Int
    ): List<WesalMessage> {
        val myUid = auth.currentUser?.uid ?: return emptyList()
        val (isGroup, peerPubKeyCache) = loadPeerPubKeyCache(chatId, myUid)
        val collected = ArrayList<WesalMessage>()
        var cursor: DocumentSnapshot? = null
        while (collected.size < maxMessages) {
            val pageLimit = minOf(200, maxMessages - collected.size)
            var query = firestore.collection(Constants.COLLECTION_CHATS)
                .document(chatId)
                .collection(Constants.COLLECTION_MESSAGES)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(pageLimit.toLong())
            cursor?.let { query = query.startAfter(it) }
            val snap = runCatching { query.get().await() }.getOrNull() ?: break
            if (snap.isEmpty) break
            cursor = snap.documents.last()
            collected += snap.documents.mapNotNull { doc ->
                mapAndDecryptMessage(doc, chatId, myUid, isGroup, peerPubKeyCache)
            }
            if (snap.size() < pageLimit) break
        }
        return collected.reversed()
    }

    /** Spec §8.3: drop every cached row of a chat (used when the chat becomes hidden). */
    override suspend fun purgeHiddenChatCache(chatId: String) {
        runCatching {
            wesalDao.deleteChat(chatId)
            wesalDao.deleteMessagesForChat(chatId)
        }
    }

    /**
     * The ONE message-text resolution path (decrypt-or-fallback), shared
     * by the Firestore mapper ([mapAndDecryptMessage]) and the Supabase
     * plane mapper (via [planeTextResolver]) — failover design §5.3:
     * both stores' rows resolve text identically. Extracted verbatim
     * from mapAndDecryptMessage in stage 2; behavior is unchanged.
     */
    private suspend fun resolveMessageText(
        senderId: String,
        myUid: String,
        chatId: String,
        isGroup: Boolean,
        peerPubKeyCache: MutableMap<String, String>,
        ciphertext: String,
        isEncrypted: Boolean,
        plainFallback: String,
        isDeletedForEveryone: Boolean,
        wrappedKeys: Map<String, String>
    ): String = when {
        isDeletedForEveryone -> "تم حذف هذه الرسالة"
        plainFallback.isNotBlank() -> plainFallback
        isEncrypted && ciphertext.isNotBlank() -> {
            val keyOwnerUid = if (senderId == myUid) {
                peerPubKeyCache.keys.firstOrNull()
            } else {
                senderId
            }
            val attemptDecrypt: (String) -> String = { peerPub ->
                if (!isGroup) {
                    cryptoManager.decryptDirectMessage(
                        E2EECryptoManager.EncryptedPayload.fromWireString(ciphertext),
                        peerPub,
                        chatId
                    )
                } else if (wrappedKeys.containsKey(myUid)) {
                    val symKey = cryptoManager.unwrapKeyFromPeer(wrappedKeys[myUid]!!, peerPub, chatId)
                    val bytes = cryptoManager.decryptBytesWithKey(
                        E2EECryptoManager.EncryptedPayload.fromWireString(ciphertext),
                        symKey,
                        chatId.toByteArray()
                    )
                    String(bytes, Charsets.UTF_8)
                } else {
                    "رسالة سابقة غير متاحة"
                }
            }
            val firstPub = keyOwnerUid?.let {
                peerPubKeyCache[it] ?: fetchUserCached(it)?.publicKey?.also { pk ->
                    if (pk.isNotBlank()) peerPubKeyCache[it] = pk
                }
            }
            val firstTry = if (!firstPub.isNullOrBlank()) runCatching { attemptDecrypt(firstPub) } else Result.failure(IllegalStateException("Missing key"))
            firstTry.getOrElse {
                val freshPub = keyOwnerUid?.let { uid ->
                    userCache.remove(uid)
                    peerPubKeyCache.remove(uid)
                    fetchUserCached(uid, forceServer = true)?.publicKey?.also { pk ->
                        if (pk.isNotBlank()) peerPubKeyCache[uid] = pk
                    }
                }
                if (!freshPub.isNullOrBlank() && freshPub != firstPub) {
                    runCatching { attemptDecrypt(freshPub) }.getOrElse { "رسالة سابقة غير متاحة" }
                } else {
                    "رسالة سابقة غير متاحة"
                }
            }
        }
        else -> plainFallback
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun mapAndDecryptMessage(
        doc: DocumentSnapshot,
        chatId: String,
        myUid: String,
        isGroup: Boolean,
        peerPubKeyCache: MutableMap<String, String>
    ): WesalMessage? {
        val deletedFor = (doc.get("deletedForUsers") as? List<String>) ?: emptyList()
        if (deletedFor.contains(myUid)) return null
        val disappearingExpiresAt = doc.getLong("disappearingExpiresAt") ?: 0L
        if (disappearingExpiresAt > 0L && System.currentTimeMillis() > disappearingExpiresAt) {
            return null
        }

        val senderId = doc.getString("senderId").orEmpty()
        val isDeletedForEveryone = doc.getBoolean("isDeletedForEveryone") ?: false
        val ciphertext = doc.getString("ciphertext").orEmpty()
        val isEncrypted = doc.getBoolean("isEncrypted") ?: false
        val isViewOnce = doc.getBoolean("isViewOnce") ?: false
        val viewedBy = (doc.get("viewedBy") as? List<String>) ?: emptyList()
        val wrappedKeys = (doc.get("wrappedKeys") as? Map<String, String>) ?: emptyMap()
        val plainFallback = doc.getString("text").orEmpty()

        val resolvedText = resolveMessageText(
            senderId = senderId,
            myUid = myUid,
            chatId = chatId,
            isGroup = isGroup,
            peerPubKeyCache = peerPubKeyCache,
            ciphertext = ciphertext,
            isEncrypted = isEncrypted,
            plainFallback = plainFallback,
            isDeletedForEveryone = isDeletedForEveryone,
            wrappedKeys = wrappedKeys
        )

        val rawStatus = MessageStatus.fromRaw(doc.getString("status"))
        // Batched delivery acks run once per snapshot in observeMessages()
        // instead of per document (see markMessageRefsDeliveredBatched).

        val mediaUrl = if (isViewOnce && viewedBy.isNotEmpty() && senderId != myUid) {
            ""
        } else {
            doc.getString("mediaUrl").orEmpty()
        }

        return WesalMessage(
            messageId = doc.id,
            chatId = chatId,
            senderId = senderId,
            senderName = doc.getString("senderName").orEmpty(),
            senderIconId = doc.getString("senderIconId").orEmpty(),
            senderIconUrl = doc.getString("senderIconUrl").orEmpty(),
            senderVerified = doc.getBoolean("senderVerified") ?: false,
            senderVerificationType = doc.getString("senderVerificationType") ?: "BLUE",
            senderNeonColor = doc.getString("senderNeonColor").orEmpty(),
            senderNeonEnabled = doc.getBoolean("senderNeonEnabled") ?: false,
            senderNeonGlowIntensity = doc.getDouble("senderNeonGlowIntensity")?.toFloat() ?: 0.7f,
            type = MessageType.fromRaw(doc.getString("type")),
            ciphertext = ciphertext,
            text = resolvedText,
            isEncrypted = isEncrypted,
            isViewOnce = isViewOnce,
            viewedBy = viewedBy,
            mediaUrl = mediaUrl,
            mediaMimeType = doc.getString("mediaMimeType").orEmpty(),
            mediaFileName = doc.getString("mediaFileName").orEmpty(),
            mediaSizeBytes = doc.getLong("mediaSizeBytes") ?: 0L,
            mediaDurationMs = doc.getLong("mediaDurationMs") ?: 0L,
            waveformAmplitudes = ((doc.get("waveform") as? List<Number>)?.map { it.toInt() }) ?: emptyList(),
            wrappedKeys = wrappedKeys,
            latitude = doc.getDouble("latitude"),
            longitude = doc.getDouble("longitude"),
            locationAddress = doc.getString("locationAddress").orEmpty(),
            liveLocationExpiresAt = doc.getLong("liveLocationExpiresAt") ?: 0L,
            disappearingExpiresAt = disappearingExpiresAt,
            replyToMessageId = doc.getString("replyToMessageId"),
            replyToPreview = doc.getString("replyToPreview"),
            forwardedFromName = doc.getString("forwardedFromName"),
            isEdited = doc.getBoolean("isEdited") ?: false,
            isDeletedForEveryone = isDeletedForEveryone,
            deletedForUsers = deletedFor,
            status = rawStatus,
            // Spec §1.2 + review B-1: sends now write `timestamp` as a server
            // timestamp, but the entire pre-round-4 corpus stores it as a Long —
            // and Firestore's typed getters do NOT coerce across stored types
            // (getTimestamp() on a number-typed field throws inside the snapshot
            // mapping). Read the RAW value and coerce it (Number / Timestamp /
            // Date / String — the coerceEpochMillis pattern), falling back to
            // the sender's clientTimestamp (also the value visible while a
            // serverTimestamp write is still pending, when the raw field reads
            // as null), then to now.
            timestamp = coerceEpochMillis(doc.get("timestamp"))
                ?: coerceEpochMillis(doc.get("clientTimestamp"))
                ?: System.currentTimeMillis()
        )
    }

    override suspend fun getOrCreateDirectChat(peerUid: String): Result<String> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        require(peerUid.isNotBlank() && peerUid != myUid) { "Invalid peer user" }
        val me = fetchUserCached(myUid, forceServer = true)
        val peer = fetchUserCached(peerUid, forceServer = true)
        require(!isSelfClearlyBanned(myUid)) { "الحساب محظور ولا يمكنه بدء محادثات جديدة" }
        require(peer != null && peer.deletedAt == 0L && !peer.isBanned) { "هذا الحساب غير متاح حالياً" }
        require(!me?.blockedUsers.orEmpty().contains(peerUid) && !peer.blockedUsers.contains(myUid)) {
            "تعذر فتح المحادثة بسبب وجود حظر بين الطرفين"
        }

        val sortedIds = listOf(myUid, peerUid).sorted()
        val deterministicChatId = "dm_${sortedIds[0]}_${sortedIds[1]}"
        val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(deterministicChatId)
        val snap = chatRef.get().await()
        if (!snap.exists()) {
            val isOwnerSupportTarget = peer.numericId == Constants.OWNER_ADMIN_NUMERIC_ID
            val callerIsSuperAdmin = verifyIsOwnerSuperAdmin(myUid)
            // Round 12 (addenda 10/10b/10d): the old code hard-threw here
            // for any fresh pair without an ACCEPTED contact request, so
            // مراسلة on a new contact died silently and the WhatsApp-style
            // request chat could never be born (the live rules likewise
            // only accepted already-accepted pairs). The birth state is
            // now decided by DirectChatRequestLogic: a fresh pair's chat
            // is created directly in the PENDING message-request state
            // (narrowly-scoped rules arm added to firestore.rules —
            // REQUIRES CONSOLE DEPLOY, see FIXLOG_R12.md), and the
            // matching contact_requests doc is kept in PENDING alongside.
            val contactRequestStatus = if (!isOwnerSupportTarget && !callerIsSuperAdmin) {
                val reqId = "${sortedIds[0]}_${sortedIds[1]}"
                runCatching {
                    firestore.collection(Constants.COLLECTION_CONTACT_REQUESTS)
                        .document(reqId)
                        .get()
                        .await()
                        .getString("status")
                }.getOrNull()
            } else {
                null
            }
            val initialState = DirectChatRequestLogic.initialState(
                isSupportTarget = isOwnerSupportTarget,
                callerIsSuperAdmin = callerIsSuperAdmin,
                contactRequestStatus = contactRequestStatus
            )
            require(initialState != DirectChatRequestLogic.InitialState.REJECTED) {
                "تم رفض طلب التعارف من قبل، ولا يمكن بدء محادثة جديدة"
            }
            val isPendingRequest =
                initialState == DirectChatRequestLogic.InitialState.PENDING
            val data = mapOf(
                "chatId" to deterministicChatId,
                "isGroup" to false,
                "isSupportChat" to isOwnerSupportTarget,
                // Stamped explicitly: the round-12 PENDING-request rules
                // arm reads this field, and a missing field fails the arm.
                "isOfficialWesalChat" to false,
                "participantIds" to sortedIds,
                "lastMessagePreview" to "",
                "lastMessageTime" to System.currentTimeMillis(),
                "lastMessageSenderId" to "",
                "lastMessageStatus" to MessageStatus.SENT.raw,
                "unreadCounts" to mapOf(myUid to 0, peerUid to 0),
                "pinnedBy" to emptyMap<String, Boolean>(),
                "mutedBy" to emptyMap<String, Boolean>(),
                "deletedForUsers" to emptyList<String>(),
                "typingUsers" to emptyMap<String, Boolean>(),
                "isMessageRequest" to isPendingRequest,
                "requestStatus" to if (isPendingRequest) "PENDING" else "ACCEPTED",
                "requestFromUid" to if (isPendingRequest) myUid else ""
            )
            if (isPendingRequest) {
                try {
                    chatRef.set(data, SetOptions.merge()).await()
                } catch (denied: FirebaseFirestoreException) {
                    if (denied.code == FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED) {
                        // Round 13 (Addendum L4): the server is quota-dead
                        // (device-proven 2026-11-27) — «طلب مراسلة» must not
                        // die silently. Queue the birth write locally: the
                        // chat opens now from the SDK's latency-compensated
                        // snapshot and the server commits it when the daily
                        // quota resets. (PERMISSION_DENIED keeps its honest
                        // fallback below — that signal must stay synchronous.)
                        chatRef.set(data, SetOptions.merge()).awaitQueued()
                        ensurePendingContactRequestDoc(
                            sortedIds = sortedIds,
                            myUid = myUid,
                            me = me,
                            peerUid = peerUid,
                            peer = peer
                        )
                        return@runCatching deterministicChatId
                    }
                    if (denied.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        throw denied
                    }
                    // The PENDING-request create arm is not live yet
                    // (console deploy pending): fall back to the classic
                    // contact request — which the live rules DO allow —
                    // and tell the user the truth instead of failing
                    // silently or faking a chat that exists only locally.
                    ensurePendingContactRequestDoc(
                        sortedIds = sortedIds,
                        myUid = myUid,
                        me = me,
                        peerUid = peerUid,
                        peer = peer
                    )
                    throw IllegalStateException(
                        "تم إرسال طلب تعارف إلى ${peer.displayName.ifBlank { "@${peer.publicId}" }} — هتقدر تراسله أول ما يقبل الطلب"
                    )
                }
                ensurePendingContactRequestDoc(
                    sortedIds = sortedIds,
                    myUid = myUid,
                    me = me,
                    peerUid = peerUid,
                    peer = peer
                )
            } else {
                // Round 13 (Addendum L4): queued write — the chat is born
                // in the local snapshot immediately and commits when the
                // server accepts it; a quota-dead server can no longer
                // make «مراسلة» a dead tap.
                chatRef.set(data, SetOptions.merge()).awaitQueued()
            }
        } else {
            chatRef.update("deletedForUsers", FieldValue.arrayRemove(myUid)).awaitQueued()
        }
        deterministicChatId
    }

    /**
     * Round 12 (addendum 10): writes the PENDING `contact_requests` doc
     * that accompanies a fresh message-request chat, in exactly the shape
     * `UserRepositoryImpl.sendContactRequest` writes (same deterministic
     * sorted id), so the recipient's contact-request surfaces light up
     * alongside the chats-list requests inbox. An existing doc is left
     * untouched (its status is owned by the request decision flows).
     * Best-effort: a failure here never aborts opening the chat.
     */
    private suspend fun ensurePendingContactRequestDoc(
        sortedIds: List<String>,
        myUid: String,
        me: WesalUser?,
        peerUid: String,
        peer: WesalUser
    ) {
        runCatching {
            val reqId = "${sortedIds[0]}_${sortedIds[1]}"
            val reqRef = firestore.collection(Constants.COLLECTION_CONTACT_REQUESTS)
                .document(reqId)
            if (reqRef.get().await().exists()) return
            val now = System.currentTimeMillis()
            val sender = me ?: WesalUser(uid = myUid)
            val map = mapOf(
                "requestId" to reqId,
                "participants" to sortedIds,
                "senderUid" to myUid,
                "senderName" to sender.displayName.ifBlank { "@${sender.publicId}" },
                "senderPublicId" to sender.publicId,
                "senderAvatarUrl" to sender.photoUrl,
                "senderVerified" to sender.isVerified,
                "senderVerificationType" to sender.verificationType,
                "senderIconId" to sender.iconId,
                "senderIconUrl" to sender.iconUrl,
                "senderNeonColor" to sender.neonColor,
                "senderNeonEnabled" to sender.neonEnabled,
                "senderNeonGlowIntensity" to sender.neonGlowIntensity,
                "receiverUid" to peerUid,
                "receiverName" to peer.displayName.ifBlank { "@${peer.publicId}" },
                "receiverPublicId" to peer.publicId,
                "receiverAvatarUrl" to peer.photoUrl,
                "receiverVerified" to peer.isVerified,
                "receiverVerificationType" to peer.verificationType,
                "receiverIconId" to peer.iconId,
                "receiverIconUrl" to peer.iconUrl,
                "receiverNeonColor" to peer.neonColor,
                "receiverNeonEnabled" to peer.neonEnabled,
                "receiverNeonGlowIntensity" to peer.neonGlowIntensity,
                "status" to "PENDING",
                "createdAt" to now,
                "updatedAt" to now
            )
            reqRef.set(map, SetOptions.merge()).await()
        }
    }

    // ------------------------------------------------------------------
    // Round 13 (Item C): instant support-chat open. The admin uid behind
    // a numeric id is resolved once and cached on-device; afterwards the
    // deterministic chat id is computable locally, entry points navigate
    // immediately, and the ensure below runs in the background.
    // ------------------------------------------------------------------

    private val supportCachePrefs by lazy {
        context.getSharedPreferences("wesal_support_cache", Context.MODE_PRIVATE)
    }

    private fun cachedAdminUid(targetNumericId: String): String? =
        supportCachePrefs.getString("admin_uid_$targetNumericId", null)?.takeIf { it.isNotBlank() }

    private fun storeAdminUid(targetNumericId: String, uid: String) {
        supportCachePrefs.edit().putString("admin_uid_$targetNumericId", uid).apply()
    }

    override fun directChatIdFor(peerUid: String): String? {
        val myUid = auth.currentUser?.uid ?: return null
        if (peerUid.isBlank() || peerUid == myUid) return null
        val sorted = listOf(myUid, peerUid).sorted()
        return "dm_${sorted[0]}_${sorted[1]}"
    }

    override fun cachedSupportChatId(targetNumericId: String): String? {
        val cleanTarget = targetNumericId.trim().ifBlank { Constants.OWNER_ADMIN_NUMERIC_ID }
        val adminUid = cachedAdminUid(cleanTarget) ?: return null
        return directChatIdFor(adminUid)
    }

    override fun ensureSupportChatInBackground(targetNumericId: String) {
        ioScope.launch {
            runCatching { openSupportChatByNumericId(targetNumericId) }
        }
    }

    override suspend fun openSupportChatByNumericId(targetNumericId: String): Result<String> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val cleanTarget = targetNumericId.trim().ifBlank { Constants.OWNER_ADMIN_NUMERIC_ID }
        require(cleanTarget == Constants.OWNER_ADMIN_NUMERIC_ID || verifyIsOwnerSuperAdmin(myUid)) {
            "حساب الدعم الرسمي المعتمد هو ${Constants.OWNER_ADMIN_NUMERIC_ID} فقط"
        }

        // Round 13 (Item C): a previously resolved admin uid is cached
        // on-device, so repeat opens skip the users query entirely.
        val adminUid = cachedAdminUid(cleanTarget) ?: run {
            val colQuery = firestore.collection(Constants.COLLECTION_USERS)
                .whereEqualTo("numericId", cleanTarget)
                .limit(5)
            val snap = runCatching { colQuery.get(Source.SERVER).await() }.getOrNull()
                ?: colQuery.get().await()
            val adminDoc = snap.documents.firstOrNull { it.getString("numericId")?.trim() == cleanTarget }
                ?: error("تعذر العثور على حساب إدارة WESAL ($cleanTarget) في قاعدة البيانات")
            val resolved = adminDoc.getString("uid")?.takeIf { it.isNotBlank() } ?: adminDoc.id
            storeAdminUid(cleanTarget, resolved)
            resolved
        }
        require(adminUid != myUid) { "أنت مسجّل الدخول حالياً بحساب الإدارة ($cleanTarget)" }

        val sortedIds = listOf(myUid, adminUid).sorted()
        val deterministicChatId = "dm_${sortedIds[0]}_${sortedIds[1]}"
        val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(deterministicChatId)
        val existingChat = runCatching { chatRef.get(Source.SERVER).await() }.getOrNull()
            ?: runCatching { chatRef.get().await() }.getOrNull()

        val now = System.currentTimeMillis()
        // Round 12 (addendum 4): the automatic welcome is PERSONALIZED with
        // the opening user's own display name. It is persisted below as a
        // SYSTEM message in this chat only, exactly once (deterministic
        // doc id + the hasAnyMessages gate). Rules note: the message doc
        // is authored from the USER's session, so senderId must be the
        // user's own uid (a doc claiming the admin's uid would be
        // impersonation and is rejected); it is presented under the
        // official sender name, as this support-intro has always been.
        val myDisplayName = runCatching {
            firestore.collection(Constants.COLLECTION_USERS).document(myUid).get().await()
                .getString("displayName").orEmpty()
        }.getOrDefault("")
        val introText = OfficialChatWelcome.textFor(myDisplayName)
        val welcomeSupportMsgId = "support_intro_${deterministicChatId}"

        if (existingChat == null || !existingChat.exists()) {
            val data = mapOf(
                "chatId" to deterministicChatId,
                "isGroup" to false,
                "isSupportChat" to true,
                "supportTargetNumericId" to cleanTarget,
                "participantIds" to sortedIds,
                "lastMessagePreview" to introText,
                "lastMessageTime" to now,
                "lastMessageSenderId" to adminUid,
                "lastMessageStatus" to MessageStatus.DELIVERED.raw,
                "unreadCounts" to mapOf(myUid to 0, adminUid to 0),
                "pinnedBy" to emptyMap<String, Boolean>(),
                "mutedBy" to emptyMap<String, Boolean>(),
                "deletedForUsers" to emptyList<String>(),
                "typingUsers" to emptyMap<String, Boolean>(),
                "requestStatus" to "ACCEPTED",
                "isMessageRequest" to false
            )
            chatRef.set(data, SetOptions.merge()).await()
        } else {
            val existingPreview = existingChat.getString("lastMessagePreview").orEmpty()
            val updateFields = hashMapOf<String, Any>(
                "isSupportChat" to true,
                "supportTargetNumericId" to cleanTarget,
                "deletedForUsers" to FieldValue.arrayRemove(myUid)
            )
            if (existingPreview.isBlank()) {
                updateFields["lastMessagePreview"] = introText
                updateFields["lastMessageTime"] = now
            }
            chatRef.update(updateFields).await()
        }

        val hasAnyMessages = runCatching {
            !chatRef.collection(Constants.COLLECTION_MESSAGES).limit(1).get().await().isEmpty
        }.getOrDefault(false)
        if (!hasAnyMessages) {
            runCatching {
                chatRef.collection(Constants.COLLECTION_MESSAGES)
                    .document(welcomeSupportMsgId)
                    .set(
                        mapOf(
                            "messageId" to welcomeSupportMsgId,
                            "chatId" to deterministicChatId,
                            "senderId" to myUid,
                            "senderName" to "إدارة WESAL ($cleanTarget)",
                            "type" to MessageType.SYSTEM.raw,
                            "text" to introText,
                            "status" to MessageStatus.DELIVERED.raw,
                            "timestamp" to now
                        )
                    )
                    .await()
            }
        }
        deterministicChatId
    }

    /**
     * Round 5 (owner requirement): the official presence IS the 10001 account —
     * a real user doc like any other. Its photo, frame and verification badge are
     * read from that doc so the official/welcome conversation renders EXACTLY
     * like the account itself (profile page included). Cached for 60s like the
     * avatar-only fetch this replaces; null when the doc does not exist (yet),
     * in which case callers keep the historical defaults.
     */
    private data class OwnerAdminIdentity(
        val photoUrl: String,
        val frameId: String,
        val frameExpiresAt: Long,
        val isVerified: Boolean,
        val verificationType: String,
        val photoUserFramed: Boolean = false
    )

    private var cachedOwnerAdminIdentity: Pair<Long, OwnerAdminIdentity?>? = null

    private suspend fun fetchOwnerAdminIdentity(): OwnerAdminIdentity? {
        val now = System.currentTimeMillis()
        val cached = cachedOwnerAdminIdentity
        if (cached != null && now - cached.first < 60_000L) return cached.second
        val identity = runCatching {
            val doc = firestore.collection(Constants.COLLECTION_USERS)
                .whereEqualTo("numericId", Constants.OWNER_ADMIN_NUMERIC_ID)
                .limit(1)
                .get()
                .await()
                .documents
                .firstOrNull()
                ?: return@runCatching null
            val frameExpiresAt = doc.getLong("frameExpiresAt") ?: 0L
            val rawFrameId = doc.getString("frameId").orEmpty()
            val frameLive = rawFrameId.isNotBlank() && !(frameExpiresAt > 0L && now > frameExpiresAt)
            val verificationExpiresAt = doc.getLong("verificationExpiresAt") ?: 0L
            OwnerAdminIdentity(
                photoUrl = doc.getString("photoUrl").orEmpty(),
                frameId = if (frameLive) rawFrameId else "",
                frameExpiresAt = if (frameLive) frameExpiresAt else 0L,
                isVerified = (doc.getBoolean("isVerified") ?: false) &&
                    (verificationExpiresAt == 0L || now <= verificationExpiresAt),
                verificationType = doc.getString("verificationType")?.ifBlank { "BLUE" } ?: "BLUE",
                photoUserFramed = doc.getBoolean("photoUserFramed") ?: false
            )
        }.getOrNull()
        cachedOwnerAdminIdentity = now to identity
        return identity
    }

    private suspend fun fetchOwnerAdminAvatarUrl(): String =
        fetchOwnerAdminIdentity()?.photoUrl.orEmpty()

    override suspend fun ensureOfficialWelcomeChat(displayName: String, forceResend: Boolean): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: return@runCatching
        val cfgSnap = runCatching {
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

        val enabled = cfgSnap?.getBoolean("enabled") ?: true
        if (!enabled && !forceResend) return@runCatching

        val template = cfgSnap?.getString("messageTemplate")?.takeIf { it.isNotBlank() }
            ?: SystemWelcomeConfig.DEFAULT_OFFICIAL_WELCOME_TEMPLATE
        val userName = displayName.trim().ifBlank { "صديقي" }
        val personalizedBody = template.replace("[اسم المستخدم]", userName)

        val officialChatId = "${Constants.OFFICIAL_CHAT_PREFIX}$myUid"
        val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(officialChatId)
        val chatSnap = runCatching { chatRef.get().await() }.getOrNull()
        @Suppress("UNCHECKED_CAST")
        val wasDeletedByUser = (chatSnap?.get("deletedForUsers") as? List<String>)?.contains(myUid) == true
        if (!forceResend && wasDeletedByUser) {
            return@runCatching
        }

        val messageId = if (forceResend) "welcome_test_${System.currentTimeMillis()}" else "welcome_$myUid"
        val msgRef = chatRef.collection(Constants.COLLECTION_MESSAGES).document(messageId)
        val hasAnyMessage = runCatching {
            !chatRef.collection(Constants.COLLECTION_MESSAGES).limit(1).get().await().isEmpty
        }.getOrDefault(false)
        val now = System.currentTimeMillis()

        val adminAvatarUrl = fetchOwnerAdminAvatarUrl()
        // Round 13 (Addendum L3): chat doc + welcome message + preview
        // are written as ONE atomic batch (see the commit below). The
        // old sequence of three separately-awaited writes could land
        // the chat doc (list shows the welcome preview) and then die on
        // the message write under the exhausted write quota — leaving
        // a conversation that opens bare-empty (device-proven).
        val batch = firestore.batch()
        var batchHasWrites = false
        if (chatSnap == null || !chatSnap.exists()) {
            batch.set(
                chatRef,
                mapOf(
                    "chatId" to officialChatId,
                    "isGroup" to false,
                    "isOfficialWesalChat" to true,
                    "groupName" to "WESAL Messenger",
                    "groupAvatarUrl" to adminAvatarUrl,
                    "participantIds" to listOf(myUid),
                    "pinnedBy" to mapOf(myUid to true),
                    "mutedBy" to emptyMap<String, Boolean>(),
                    "archivedBy" to emptyMap<String, Boolean>(),
                    "deletedForUsers" to emptyList<String>(),
                    "typingUsers" to emptyMap<String, Boolean>(),
                    "unreadCounts" to mapOf(myUid to 1),
                    "lastMessagePreview" to personalizedBody.take(80),
                    "lastMessageTime" to now,
                    "lastMessageSenderId" to Constants.OFFICIAL_WESAL_SENDER_ID,
                    "lastMessageStatus" to MessageStatus.DELIVERED.raw
                ),
                SetOptions.merge()
            )
            batchHasWrites = true
        } else if (adminAvatarUrl.isNotBlank() && chatSnap.getString("groupAvatarUrl").orEmpty().isBlank()) {
            batch.set(chatRef, mapOf("groupAvatarUrl" to adminAvatarUrl), SetOptions.merge())
            batchHasWrites = true
        }

        if (!hasAnyMessage || forceResend) {
            batch.set(
                msgRef,
                mapOf(
                    "messageId" to messageId,
                    "chatId" to officialChatId,
                    "senderId" to Constants.OFFICIAL_WESAL_SENDER_ID,
                    "senderName" to "WESAL Messenger",
                    "type" to MessageType.TEXT.raw,
                    "text" to personalizedBody,
                    "ciphertext" to "",
                    "isEncrypted" to false,
                    "isEdited" to false,
                    "isDeletedForEveryone" to false,
                    "deletedForUsers" to emptyList<String>(),
                    "status" to MessageStatus.DELIVERED.raw,
                    "timestamp" to now
                )
            )
            batchHasWrites = true

            val updatePayload = hashMapOf<String, Any>(
                "isOfficialWesalChat" to true,
                "groupName" to "WESAL Messenger",
                "pinnedBy.$myUid" to true,
                "lastMessagePreview" to personalizedBody.take(80),
                "lastMessageTime" to now,
                "lastMessageSenderId" to Constants.OFFICIAL_WESAL_SENDER_ID,
                "lastMessageStatus" to MessageStatus.DELIVERED.raw,
                "unreadCounts.$myUid" to FieldValue.increment(1)
            )
            if (forceResend) {
                updatePayload["deletedForUsers"] = FieldValue.arrayRemove(myUid)
            }
            batch.set(chatRef, updatePayload, SetOptions.merge())
            batchHasWrites = true
        }
        // ONE atomic commit: chat doc + welcome message + preview land
        // together or not at all, and awaitQueued surfaces them in the
        // local snapshot immediately even while the server cannot
        // accept writes (quota) — the commit lands when it resets.
        if (batchHasWrites) batch.commit().awaitQueued()

        if (!hasAnyMessage || forceResend) {
            firestore.collection(Constants.COLLECTION_USERS).document(myUid)
                .set(mapOf("welcomeSent" to true), SetOptions.merge())
                .awaitQueued()
        }
    }

    override suspend fun setChatOfficialWallpaper(chatId: String, wallpaperUrl: String?): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        require(verifyIsOwnerSuperAdmin(myUid)) { "تغيير الخلفية الرسمية المشتركة للطرفين متاح لحساب الإدارة 10001 فقط" }
        val resolvedValue = if (wallpaperUrl.isNullOrBlank()) {
            ""
        } else if (wallpaperUrl.startsWith("http", ignoreCase = true) || wallpaperUrl.startsWith("data:", ignoreCase = true)) {
            wallpaperUrl
        } else {
            val uri = Uri.parse(wallpaperUrl)
            uploadProfileMediaToStorageOrSmallFallback(
                ctx = context,
                storagePath = "chat_wallpapers/$chatId.jpg",
                uri = uri,
                maxSide = 960,
                quality = 78
            ).also {
                require(it.isNotBlank()) { "تعذر حفظ الخلفية المشتركة" }
            }
        }
        firestore.collection(Constants.COLLECTION_CHATS)
            .document(chatId)
            .update("officialWallpaperUrl", resolvedValue)
            .await()
    }

    override suspend fun sendTextMessage(
        chatId: String,
        text: String,
        replyTo: WesalMessage?,
        forwardedFromName: String?,
        clientMessageId: String?
    ): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val me = fetchUserCached(myUid)
        require(!isSelfClearlyBanned(myUid)) { "حسابك محظور ولا يمكنك إرسال رسائل حالياً" }

        val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
        // Failover stage 2: the chat-doc read itself can die with the
        // Firestore quota. A THROWN read is not "chat does not exist" —
        // when the plane carries the send, metadata falls back to
        // locally-derived values below; a snapshot that arrives and
        // says the doc is missing keeps the honest addendum-10d failure.
        val chatSnap = runCatching { chatRef.get().await() }
            .onFailure { BackendHealthStore.reportFirestoreError(it) }
            .getOrNull()
        if (chatSnap != null) {
            // Round 12 (addendum 10d): never write into the void. A chat doc
            // that does not exist (e.g. a legacy locally-cached row whose
            // create the server denied) makes the batch below fail opaquely
            // and the message hang at SENDING, visible to nobody else — fail
            // honestly instead so the echo flips to FAILED with a real reason.
            require(chatSnap.exists()) { "المحادثة غير موجودة — أعد فتحها وحاول تاني" }
        }
        val isOfficial = (chatSnap?.getBoolean("isOfficialWesalChat") ?: false) ||
            chatId.startsWith(Constants.OFFICIAL_CHAT_PREFIX)
        if (isOfficial) {
            require(verifyIsOwnerSuperAdmin(myUid)) {
                "قناة إشعارات WESAL Messenger الرسمية مخصصة للقراءة فقط"
            }
        }

        @Suppress("UNCHECKED_CAST")
        val participants = if (chatSnap != null) {
            (chatSnap.get("participantIds") as? List<String>).orEmpty()
        } else {
            // Firestore unreachable: derive the 1:1 participants from the
            // locally cached messages (their senders + me). Used only to
            // register the chat mirror on the plane; when even that is
            // unknown (no cached history), the send queues below instead
            // of guessing a participant list.
            val cachedSenders = runCatching {
                wesalDao.observeMessages(chatId, myUid).firstOrNull().orEmpty()
            }.getOrDefault(emptyList()).map { it.senderId }
            (cachedSenders + myUid).distinct()
        }
        val disappearingSec = chatSnap?.getLong("disappearingDurationSec") ?: 0L
        val now = System.currentTimeMillis()
        val disappearingExpiresAt = if (disappearingSec > 0L) now + disappearingSec * 1000L else 0L
        // A caller-supplied id (outbox) makes retries idempotent: the same message document is
        // rewritten instead of a second copy being created when the first attempt actually landed.
        val messageId = clientMessageId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val cleanText = text.trim()
        require(cleanText.isNotEmpty()) { "الرسالة فارغة" }

        val messageData = hashMapOf<String, Any?>(
            "messageId" to messageId,
            "chatId" to chatId,
            "senderId" to myUid,
            "senderName" to (me?.displayName?.ifBlank {
                me.publicId.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "WESAL User"
            } ?: "WESAL User"),
            "senderIconId" to (me?.iconId ?: ""),
            "senderIconUrl" to (me?.iconUrl ?: ""),
            "senderVerified" to (me?.isVerified ?: false),
            "senderVerificationType" to (me?.verificationType ?: "BLUE"),
            "senderNeonColor" to (me?.neonColor ?: ""),
            "senderNeonEnabled" to (me?.neonEnabled ?: false),
            "senderNeonGlowIntensity" to (me?.neonGlowIntensity ?: 0.7f),
            "type" to MessageType.TEXT.raw,
            "ciphertext" to "",
            "text" to cleanText,
            "isEncrypted" to false,
            "isViewOnce" to false,
            "viewedBy" to emptyList<String>(),
            "wrappedKeys" to emptyMap<String, String>(),
            "disappearingExpiresAt" to disappearingExpiresAt,
            "replyToMessageId" to replyTo?.messageId,
            "replyToPreview" to replyTo?.text?.take(80),
            "forwardedFromName" to forwardedFromName,
            "isEdited" to false,
            "isDeletedForEveryone" to false,
            "deletedForUsers" to emptyList<String>(),
            "status" to MessageStatus.SENT.raw,
            "timestamp" to now,
            "clientTimestamp" to now
        )

        val preview = cleanText.take(80)
        val chatUpdates = hashMapOf<String, Any>(
            "lastMessagePreview" to preview,
            "lastMessageTime" to now,
            "lastMessageSenderId" to myUid,
            "lastMessageStatus" to MessageStatus.SENT.raw,
            "deletedForUsers" to emptyList<String>()
        )
        participants.filter { it != myUid }.forEach { otherUid ->
            chatUpdates["unreadCounts.$otherUid"] = FieldValue.increment(1)
        }

        // ------------------------------------------------------------------
        // Failover stage 2 (§5.2): plane routing. Eligibility is 1:1
        // chats only (groups stay on Firestore until stage 3) and never
        // the official chat (rule 1 — its guard above already ran).
        // Dark-launch property: with the config on Firestore and
        // Firestore healthy, effectivePrimary is FIRESTORE and this
        // block is skipped entirely — the send below is then
        // byte-identical to the pre-plane app.
        // ------------------------------------------------------------------
        val planeEligible = !isOfficial && participants.size == 2 && myUid in participants
        val routedTarget = MessagePlaneRouter.effectivePrimary(
            MessagePlaneRouter.currentConfig(),
            BackendHealthStore.currentState()
        )
        if (planeEligible && routedTarget == MessagePlaneRouter.Store.SUPABASE) {
            val landed = sendTextViaPlane(chatId, messageData, participants)
            if (landed != null) {
                publishOwnPlaneMessage(chatId, myUid, messageData, landed)
                coWriteChatSummaryOnFirestore(chatRef, chatUpdates)
                return@runCatching
            }
            // Plane store unreachable → discover-and-retry ONCE against
            // Firestore below (rule 3), same message id + payload.
        }

        // Atomic write: message + chat summary commit together (previously two separate
        // awaits — if the second failed, the peer's chat list never bumped, and a retry created
        // a duplicate message with a fresh id). The commit is awaited only for the ACK
        // window (SEND_WRITE_TIMEOUT_MS): the Firestore gRPC write stream can stall
        // silently, and an unbounded await would leave the send hanging forever — but
        // the queued write itself is durable (addendum 12, below).
        val messageRef = chatRef.collection(Constants.COLLECTION_MESSAGES).document(messageId)
        // Spec §1.2: the SDK batch writes the authoritative server timestamp (the
        // REST fallback below cannot encode FieldValue, so it keeps the client value;
        // both paths carry clientTimestamp).
        val sdkMessageData = HashMap(messageData).apply {
            put("timestamp", FieldValue.serverTimestamp())
        }
        val batch = firestore.batch()
        batch.set(messageRef, sdkMessageData)
        batch.update(chatRef, chatUpdates)
        // Round 12 (addendum 12): the timeout is an ACK WINDOW, not a
        // deadline. PROVEN defect: on a very slow connection (~1 KB/s)
        // the batch rarely ACKs within SEND_WRITE_TIMEOUT_MS, the old
        // code then crawled through an HTTP REST fallback that failed
        // too, and its check() threw a message embedding raw exception
        // text ("timeout", "15000") straight into the user's error
        // dialog. But the SDK batch is DURABLE: with persistence on,
        // the write sits in the SDK's mutation queue and syncs when
        // the network allows (the r6 comment below already knew
        // withTimeoutOrNull cannot cancel it). So:
        // - ACK inside the window → committed.
        // - The commit THREW (a real rejection, e.g. PERMISSION_DENIED)
        //   → rethrow: a permanent failure the UI maps to Arabic.
        // - Window elapsed with no error → the write is QUEUED in the
        //   SDK queue: throw MessageQueuedException, which the UI
        //   treats as "still sending" (echo + outbox entry stay, same
        //   client id — the outbox replay backstops a queue entry the
        //   server later rejects). NEVER a user-visible failure.
        // The REST fallback is REMOVED from the send path: idempotency
        // never needed it (the message doc id IS the client id on the
        // SDK path), and on the links where the SDK stalls, HTTP
        // stalls identically — its failure was the raw-error dialog.
        attemptedStores[messageId] =
            MessagePlaneRouter.wireName(MessagePlaneRouter.Store.FIRESTORE)
        var sdkError: Throwable? = null
        val sdkCommitted = withTimeoutOrNull(SEND_WRITE_TIMEOUT_MS) {
            runCatching { batch.commit().await() }
                .onFailure { sdkError = it }
                .isSuccess
        } ?: false
        when {
            sdkCommitted -> Unit
            sdkError != null -> {
                // Failover design §5.1: a quota rejection flips the
                // health store (stage 1 wiring).
                BackendHealthStore.reportFirestoreError(sdkError)
                // Stage 2, §5.2 rule 3: an INFRASTRUCTURE failure
                // (quota death, unavailable) discovers the other store
                // ONCE with the same message id + payload. Definitive
                // rejections (rules, validation) keep the honest
                // failure they have today.
                val infra = BackendHealthStore.isFirestoreInfraCode(
                    (sdkError as? FirebaseFirestoreException)?.code?.name
                )
                if (infra && planeEligible) {
                    val landed = runCatching {
                        sendTextViaPlane(chatId, messageData, participants)
                    }.getOrNull()
                    if (landed != null) {
                        publishOwnPlaneMessage(chatId, myUid, messageData, landed)
                        return@runCatching
                    }
                    // Both stores unreachable → QUEUED, never "failed"
                    // (rule 6): the outbox replays when either recovers.
                    throw MessageQueuedException()
                }
                if (infra && chatSnap == null) {
                    // The metadata read died with the same outage and
                    // no store can be targeted safely — queue rather
                    // than fail; replay re-reads when Firestore recovers.
                    throw MessageQueuedException()
                }
                throw sdkError!!
            }
            else -> throw MessageQueuedException()
        }
    }

    /**
     * Failover §5.2: one send attempt against the Supabase plane.
     * Returns the landed result, or null when the plane store is
     * unreachable (the caller then discovers the other store once).
     * A missing chat mirror is registered and the send retried ONCE
     * (§4.4). Definitive rejections (invalid message, not a
     * participant, unauthenticated) propagate — they are honest
     * failures, not routing signals.
     */
    private suspend fun sendTextViaPlane(
        chatId: String,
        messageData: Map<String, Any?>,
        participants: List<String>
    ): SupabaseMessageClient.PlaneSendResult? {
        val messageId = messageData["messageId"] as? String ?: return null
        attemptedStores[messageId] =
            MessagePlaneRouter.wireName(MessagePlaneRouter.Store.SUPABASE)
        try {
            return supabaseClient.sendMessage(messageData)
        } catch (e: SupabaseMessageException) {
            when (e.errorCode) {
                "store_unavailable" -> return null
                "chat_not_registered" -> Unit // register + retry below
                else -> throw e
            }
        }
        supabaseClient.registerChat(chatId, "direct", participants)
        return try {
            supabaseClient.sendMessage(messageData)
        } catch (e: SupabaseMessageException) {
            if (e.errorCode == "store_unavailable") null else throw e
        }
    }

    /**
     * §5.2: after a plane landing, the sender publishes the message
     * locally at once (merged list + Room) and advances the chat's
     * delta cursor past it — the message is visible in the open chat
     * immediately, not one poll later. Advancing the seq cursor cannot
     * strand a peer message: the poll's watermark arm (§5.3) still
     * returns any row whose updated_at is newer than the watermark.
     */
    private suspend fun publishOwnPlaneMessage(
        chatId: String,
        myUid: String,
        messageData: Map<String, Any?>,
        landed: SupabaseMessageClient.PlaneSendResult
    ) {
        runCatching { prefs.setPlaneEngaged() }
        val message = WesalMessage(
            messageId = messageData["messageId"] as String,
            chatId = chatId,
            senderId = myUid,
            senderName = messageData["senderName"] as? String ?: "",
            senderIconId = messageData["senderIconId"] as? String ?: "",
            senderIconUrl = messageData["senderIconUrl"] as? String ?: "",
            senderVerified = messageData["senderVerified"] as? Boolean ?: false,
            senderVerificationType = messageData["senderVerificationType"] as? String ?: "BLUE",
            senderNeonColor = messageData["senderNeonColor"] as? String ?: "",
            senderNeonEnabled = messageData["senderNeonEnabled"] as? Boolean ?: false,
            senderNeonGlowIntensity =
                (messageData["senderNeonGlowIntensity"] as? Number)?.toFloat() ?: 0.7f,
            type = MessageType.TEXT,
            text = messageData["text"] as? String ?: "",
            status = MessageStatus.SENT,
            timestamp = landed.serverTsMs.takeIf { it > 0L }
                ?: (messageData["timestamp"] as? Long ?: System.currentTimeMillis()),
            replyToMessageId = messageData["replyToMessageId"] as? String,
            replyToPreview = messageData["replyToPreview"] as? String,
            forwardedFromName = messageData["forwardedFromName"] as? String,
            disappearingExpiresAt =
                (messageData["disappearingExpiresAt"] as? Number)?.toLong() ?: 0L
        )
        publishPlaneMessages(chatId, listOf(message))
        upsertPlaneRowsToRoom(chatId, myUid, listOf(message))
        if (landed.seq > 0L) {
            val existing = runCatching { prefs.planeChatCursor(chatId) }.getOrNull()
            runCatching {
                prefs.setPlaneChatCursor(
                    chatId,
                    maxOf(existing?.first ?: 0L, landed.seq),
                    maxOf(existing?.second ?: 0L, landed.serverTsMs)
                )
            }
            val oldest = planeOldestSeq[chatId]
            if (oldest == null || landed.seq < oldest) planeOldestSeq[chatId] = landed.seq
        }
    }

    /**
     * §5.2 rule 4: best-effort Firestore summary co-write after a plane
     * landing, so peers on Firestore-only builds still see the chat
     * bump. Failure is expected during an outage (and feeds the health
     * store); the §5.4 repair pass in [refreshPlaneInbox] heals the
     * summary later.
     */
    private suspend fun coWriteChatSummaryOnFirestore(
        chatRef: com.google.firebase.firestore.DocumentReference,
        chatUpdates: Map<String, Any>
    ) {
        runCatching {
            withTimeoutOrNull(6_000L) { chatRef.update(chatUpdates).await() }
        }.onFailure { BackendHealthStore.reportFirestoreError(it) }
    }

    override suspend fun isOutboxMessageLanded(chatId: String, messageId: String): Boolean {
        if (chatId.isBlank() || messageId.isBlank()) return false
        return runCatching {
            firestore.collection(Constants.COLLECTION_CHATS)
                .document(chatId)
                .collection(Constants.COLLECTION_MESSAGES)
                .document(messageId)
                .get()
                .await()
                .exists()
        }.onFailure { BackendHealthStore.reportFirestoreError(it) }
            .getOrDefault(false)
    }

    override suspend fun isOutboxMessageLandedOnPlane(
        chatId: String,
        messageId: String
    ): Boolean {
        if (chatId.isBlank() || messageId.isBlank()) return false
        return runCatching {
            supabaseClient.listMessagesByIds(chatId, listOf(messageId)).rows.isNotEmpty()
        }.getOrDefault(false)
    }

    override fun sendMediaMessage(
        chatId: String,
        uri: Uri,
        type: MessageType,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        durationMs: Long,
        waveform: List<Int>,
        caption: String,
        isViewOnce: Boolean,
        clientMessageId: String?
    ): Flow<Result<Float>> = callbackFlow {
        val myUid = auth.currentUser?.uid
        if (myUid == null) {
            trySend(Result.failure(IllegalStateException("Not authenticated")))
            close()
            return@callbackFlow
        }

        launch {
            try {
                val me = fetchUserCached(myUid)
                require(!isSelfClearlyBanned(myUid)) { "حسابك محظور ولا يمكنك إرسال الوسائط" }
                val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
                val chatSnap = chatRef.get().await()
                val isOfficial = (chatSnap.getBoolean("isOfficialWesalChat") ?: false) ||
                    chatId.startsWith(Constants.OFFICIAL_CHAT_PREFIX)
                if (isOfficial) {
                    require(verifyIsOwnerSuperAdmin(myUid)) {
                        "محادثة WESAL Messenger الرسمية مخصصة للقراءة فقط"
                    }
                }

                @Suppress("UNCHECKED_CAST")
                val participants = (chatSnap.get("participantIds") as? List<String>).orEmpty()
                val disappearingSec = chatSnap.getLong("disappearingDurationSec") ?: 0L
                // A caller-supplied id (pending-upload retry) keeps the message id —
                // and therefore the storage path — stable across attempts (§1.4).
                val messageId = clientMessageId?.takeIf { it.isNotBlank() }
                    ?: UUID.randomUUID().toString()

                trySend(Result.success(0.08f))
                var resolvedSizeBytes = sizeBytes
                if (resolvedSizeBytes <= 0L) {
                    resolvedSizeBytes = runCatching {
                        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
                    }.getOrDefault(0L)
                }
                val storageFolder = when (type) {
                    MessageType.IMAGE -> "images"
                    MessageType.VIDEO -> "videos"
                    MessageType.VOICE -> "voices"
                    MessageType.AUDIO -> "audios"
                    MessageType.DOCUMENT -> "documents"
                    else -> throw IllegalStateException("نوع المرفق غير مدعوم للإرسال")
                }
                val safeExt = fileName.substringAfterLast('.', "").take(8).let { if (it.isNotBlank()) ".$it" else "" }
                val storagePath = "$storageFolder/$chatId/$messageId$safeExt"
                // Spec §1.4: on a retry with the same message id, resume the
                // persisted Storage session instead of restarting from byte 0; the
                // session URI is persisted as soon as the upload task exposes one
                // and cleared once the upload completes.
                val resumeSessionUri = clientMessageId?.let { storedUploadSession(myUid, it) }
                val uploadProgress: (Float) -> Unit = { frac ->
                    trySend(Result.success((0.08f + frac * 0.84f).coerceIn(0.08f, 0.94f)))
                }
                val uploadSessionSink: (Uri) -> Unit = { sessionUri ->
                    clientMessageId?.let { persistUploadSession(myUid, it, sessionUri) }
                }
                var uploadResult = uploadFileToFirebaseStorage(
                    storagePath = storagePath,
                    uri = uri,
                    onProgress = uploadProgress,
                    resumeSessionUri = resumeSessionUri,
                    onSessionUri = uploadSessionSink
                )
                if (uploadResult.isFailure && resumeSessionUri != null && clientMessageId != null) {
                    // Review M-1: a stored session URI the server no longer
                    // honours (expired, or the upload already finalised) used
                    // to brick this pending message permanently — the session
                    // was cleared only on success, so every retry resumed the
                    // same dead session and failed again. Drop it and retry
                    // ONCE with a fresh session before surfacing the failure.
                    // A still-valid session never reaches this branch: the
                    // resumed upload above succeeds.
                    clearUploadSession(myUid, clientMessageId)
                    uploadResult = uploadFileToFirebaseStorage(
                        storagePath = storagePath,
                        uri = uri,
                        onProgress = uploadProgress,
                        resumeSessionUri = null,
                        onSessionUri = uploadSessionSink
                    )
                }
                val downloadUrl = uploadResult.getOrThrow()
                if (clientMessageId != null) {
                    clearUploadSession(myUid, clientMessageId)
                }
                trySend(Result.success(0.95f))

                val now = System.currentTimeMillis()
                val disappearingExpiresAt = if (disappearingSec > 0L) now + disappearingSec * 1000L else 0L
                val msgData = hashMapOf<String, Any?>(
                    "messageId" to messageId,
                    "chatId" to chatId,
                    "senderId" to myUid,
                    "senderName" to (me?.displayName?.ifBlank {
                        me.publicId.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "WESAL User"
                    } ?: "WESAL User"),
                    "senderIconId" to (me?.iconId ?: ""),
                    "senderIconUrl" to (me?.iconUrl ?: ""),
                    "senderVerified" to (me?.isVerified ?: false),
                    "senderVerificationType" to (me?.verificationType ?: "BLUE"),
                    "senderNeonColor" to (me?.neonColor ?: ""),
                    "senderNeonEnabled" to (me?.neonEnabled ?: false),
                    "senderNeonGlowIntensity" to (me?.neonGlowIntensity ?: 0.7f),
                    "type" to type.raw,
                    "text" to caption.trim(),
                    "isEncrypted" to false,
                    "isViewOnce" to isViewOnce,
                    "viewedBy" to emptyList<String>(),
                    "wrappedKeys" to emptyMap<String, String>(),
                    "mediaUrl" to downloadUrl,
                    "mediaMimeType" to mimeType,
                    "mediaFileName" to fileName,
                    "mediaSizeBytes" to resolvedSizeBytes,
                    "mediaDurationMs" to durationMs,
                    "waveform" to waveform,
                    "disappearingExpiresAt" to disappearingExpiresAt,
                    "isEdited" to false,
                    "isDeletedForEveryone" to false,
                    "deletedForUsers" to emptyList<String>(),
                    "status" to MessageStatus.SENT.raw,
                    // Spec §1.2: authoritative server timestamp + client clock kept
                    // alongside for optimistic ordering/diagnostics.
                    "timestamp" to FieldValue.serverTimestamp(),
                    "clientTimestamp" to now
                )
                chatRef.collection(Constants.COLLECTION_MESSAGES).document(messageId).set(msgData).await()
                // R6 FIX (duplicate: delivered copy + error copy): from here on the message
                // document is durably written. The chat-summary update below is derived
                // display data — if IT fails, the send must NOT be reported as failed,
                // or the UI keeps an error-state upload bubble next to the delivered
                // message (two copies of one send). See the guarded update below.
                val previewLabel = when {
                    isViewOnce && type == MessageType.IMAGE -> "صورة للعرض مرة واحدة"
                    isViewOnce && type == MessageType.VIDEO -> "فيديو للعرض مرة واحدة"
                    type == MessageType.IMAGE -> if (caption.isNotBlank()) "صورة: ${caption.trim().take(40)}" else "صورة"
                    type == MessageType.VIDEO -> if (caption.isNotBlank()) "فيديو: ${caption.trim().take(40)}" else "فيديو"
                    type == MessageType.VOICE -> "رسالة صوتية"
                    type == MessageType.AUDIO -> if (fileName.isNotBlank()) "ملف صوتي: $fileName" else "ملف صوتي"
                    type == MessageType.DOCUMENT -> "ملف: $fileName"
                    else -> "مرفق"
                }
                val chatUpdates = hashMapOf<String, Any>(
                    "lastMessagePreview" to previewLabel,
                    "lastMessageTime" to now,
                    "lastMessageSenderId" to myUid,
                    "lastMessageStatus" to MessageStatus.SENT.raw,
                    "deletedForUsers" to emptyList<String>()
                )
                participants.filter { it != myUid }.forEach { otherUid ->
                    chatUpdates["unreadCounts.$otherUid"] = FieldValue.increment(1)
                }
                // R6 FIX (see the note at the message set() above): best-effort once the
                // message document exists — a summary failure must not fail the whole send.
                runCatching { chatRef.update(chatUpdates).await() }
                trySend(Result.success(1.0f))
                close()
            } catch (e: Exception) {
                // Failover design §5.1 (stage 1, observing).
                BackendHealthStore.reportFirestoreError(e)
                trySend(Result.failure(e))
                close()
            }
        }
        awaitClose { }
    }

    override suspend fun markViewOnceOpened(chatId: String, messageId: String): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val msgRef = firestore.collection(Constants.COLLECTION_CHATS)
            .document(chatId)
            .collection(Constants.COLLECTION_MESSAGES)
            .document(messageId)
        val snap = msgRef.get().await()
        if (!snap.exists()) return@runCatching
        val senderId = snap.getString("senderId").orEmpty()
        if (senderId == myUid) return@runCatching

        // Receiver opened view-once media -> permanently record viewer and scrub media payload
        msgRef.update(
            mapOf(
                "viewedBy" to FieldValue.arrayUnion(myUid),
                "mediaUrl" to "",
                "ciphertext" to ""
            )
        ).await()

        withContext(Dispatchers.IO) {
            val cacheDir = File(context.cacheDir, "decrypted_media")
            cacheDir.listFiles()?.filter { it.nameWithoutExtension == messageId }?.forEach {
                runCatching { it.delete() }
            }
        }
    }

    override suspend fun decryptMediaToCache(message: WesalMessage): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val myUid = auth.currentUser?.uid ?: error("Not authenticated")
            if (message.isViewOnce && message.senderId != myUid && message.viewedBy.contains(myUid)) {
                error("تمت مشاهدة هذه الرسالة لمرة واحدة ولا يمكن فتحها مجدداً")
            }
            val cacheDir = File(context.cacheDir, "decrypted_media").apply { mkdirs() }
            val ext = message.mediaFileName.substringAfterLast('.', "").ifBlank {
                when (message.type) {
                    MessageType.IMAGE -> "jpg"
                    MessageType.VIDEO -> "mp4"
                    MessageType.VOICE -> "m4a"
                    MessageType.AUDIO -> "mp3"
                    else -> "bin"
                }
            }
            val targetFile = File(cacheDir, "${message.messageId}.$ext")
            if (targetFile.exists() && targetFile.length() > 0L) {
                return@runCatching targetFile
            }

            if (message.mediaUrl.startsWith("http", ignoreCase = true)) {
                URL(message.mediaUrl).openStream().use { input ->
                    targetFile.outputStream().use { output -> input.copyTo(output) }
                }
                if (targetFile.exists() && targetFile.length() > 0L) {
                    return@runCatching targetFile
                }
            }

            if (message.mediaUrl.startsWith("wesal-plain://")) {
                val raw = Base64.decode(message.mediaUrl.removePrefix("wesal-plain://"), Base64.NO_WRAP)
                targetFile.writeBytes(raw)
                return@runCatching targetFile
            }

            if (message.mediaUrl.startsWith("data:")) {
                val raw = Base64.decode(message.mediaUrl.substringAfter("base64,"), Base64.NO_WRAP)
                targetFile.writeBytes(raw)
                return@runCatching targetFile
            }

            val encryptedBytes = when {
                message.mediaUrl.startsWith("wesal-inline://") -> {
                    Base64.decode(message.mediaUrl.removePrefix("wesal-inline://"), Base64.NO_WRAP)
                }
                else -> {
                    error("تعذر تحميل المرفق أو تم حذفه")
                }
            }

            val wrappedKey = message.wrappedKeys[myUid]
            val plainBytes = if (!wrappedKey.isNullOrBlank()) {
                val senderPub = fetchUserCached(message.senderId)?.publicKey.orEmpty()
                val peerPub = if (message.senderId == myUid) {
                    fetchUserCached(myUid)?.publicKey.orEmpty()
                } else {
                    senderPub
                }
                val symKey = cryptoManager.unwrapKeyFromPeer(wrappedKey, peerPub, message.chatId)
                cryptoManager.decryptCombinedMediaBytes(
                    encryptedBytes,
                    Base64.encodeToString(symKey, Base64.NO_WRAP)
                )
            } else {
                encryptedBytes
            }

            targetFile.writeBytes(plainBytes)
            targetFile
        }
    }

    override suspend fun sendLocationMessage(
        chatId: String,
        latitude: Double,
        longitude: Double,
        address: String,
        liveDurationMinutes: Int
    ): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val me = fetchUserCached(myUid)
        val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
        val chatSnap = chatRef.get().await()
        // Round 12 (addendum 10d): same existence guard as sendTextMessage.
        require(chatSnap.exists()) { "المحادثة غير موجودة — أعد فتحها وحاول تاني" }
        val isOfficial = (chatSnap.getBoolean("isOfficialWesalChat") ?: false) ||
            chatId.startsWith(Constants.OFFICIAL_CHAT_PREFIX)
        if (isOfficial) {
            require(verifyIsOwnerSuperAdmin(myUid)) { "قناة WESAL Messenger الرسمية للقراءة فقط" }
        }

        @Suppress("UNCHECKED_CAST")
        val participants = (chatSnap.get("participantIds") as? List<String>).orEmpty()
        val disappearingSec = chatSnap.getLong("disappearingDurationSec") ?: 0L
        val now = System.currentTimeMillis()
        val messageId = UUID.randomUUID().toString()
        val isLive = liveDurationMinutes > 0
        val liveExpiresAt = if (isLive) now + liveDurationMinutes * 60_000L else 0L
        val disappearingExpiresAt = if (disappearingSec > 0L) now + disappearingSec * 1000L else 0L

        val msg = hashMapOf<String, Any>(
            "messageId" to messageId,
            "chatId" to chatId,
            "senderId" to myUid,
            "senderName" to (me?.displayName?.ifBlank {
                me.publicId.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "WESAL User"
            } ?: "WESAL User"),
            "senderIconId" to (me?.iconId ?: ""),
            "senderIconUrl" to (me?.iconUrl ?: ""),
            "senderVerified" to (me?.isVerified ?: false),
            "senderVerificationType" to (me?.verificationType ?: "BLUE"),
            "senderNeonColor" to (me?.neonColor ?: ""),
            "senderNeonEnabled" to (me?.neonEnabled ?: false),
            "senderNeonGlowIntensity" to (me?.neonGlowIntensity ?: 0.7f),
            "type" to if (isLive) MessageType.LIVE_LOCATION.raw else MessageType.LOCATION.raw,
            "text" to address.ifBlank { if (isLive) "مشاركة الموقع المباشر" else "الموقع الجغرافي" },
            "latitude" to latitude,
            "longitude" to longitude,
            "locationAddress" to address,
            "liveLocationExpiresAt" to liveExpiresAt,
            "disappearingExpiresAt" to disappearingExpiresAt,
            "isEncrypted" to false,
            "status" to MessageStatus.SENT.raw,
            "timestamp" to now,
            "clientTimestamp" to now
        )
        val chatUpdates = hashMapOf<String, Any>(
            "lastMessagePreview" to if (isLive) "موقع مباشر ($liveDurationMinutes د)" else "موقع جغرافي",
            "lastMessageTime" to now,
            "lastMessageSenderId" to myUid,
            "lastMessageStatus" to MessageStatus.SENT.raw,
            "deletedForUsers" to emptyList<String>()
        )
        participants.filter { it != myUid }.forEach { otherUid ->
            chatUpdates["unreadCounts.$otherUid"] = FieldValue.increment(1)
        }
        // Spec §1.2: server timestamp on the SDK batch.
        // Round 12 (addendum 12): same ACK-window semantics as
        // sendTextMessage — a slow network queues the write in the
        // SDK's persistent mutation queue (MessageQueuedException),
        // a thrown commit is a permanent failure, and the REST
        // fallback is removed (see sendTextMessage for the full note).
        val sdkMsg = msg.toMutableMap().apply {
            put("timestamp", FieldValue.serverTimestamp())
        }
        val locationBatch = firestore.batch()
        locationBatch.set(chatRef.collection(Constants.COLLECTION_MESSAGES).document(messageId), sdkMsg)
        locationBatch.update(chatRef, chatUpdates)
        var locationSdkError: Throwable? = null
        val locationCommitted = withTimeoutOrNull(SEND_WRITE_TIMEOUT_MS) {
            runCatching { locationBatch.commit().await() }
                .onFailure { locationSdkError = it }
                .isSuccess
        } ?: false
        when {
            locationCommitted -> Unit
            locationSdkError != null -> {
                // Failover design §5.1 (stage 1, observing).
                BackendHealthStore.reportFirestoreError(locationSdkError)
                throw locationSdkError!!
            }
            else -> throw MessageQueuedException()
        }
    }

    override suspend fun setChatDisappearingTimer(chatId: String, durationSeconds: Long): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
        val snap = chatRef.get().await()
        @Suppress("UNCHECKED_CAST")
        val participants = (snap.get("participantIds") as? List<String>).orEmpty()
        require(myUid in participants) { "لا يمكنك تعديل مؤقت هذه المحادثة" }
        chatRef.update("disappearingDurationSec", durationSeconds.coerceAtLeast(0L)).await()
    }

    override suspend fun setMessageRequestDecision(
        chatId: String,
        peerUid: String,
        decision: String
    ): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val normalized = decision.trim().uppercase()
        val reqStatus = when (normalized) {
            "ACCEPT", "ACCEPTED" -> "ACCEPTED"
            "HOLD", "HELD" -> "HELD"
            "REJECT", "REJECTED", "DELETE", "BLOCK" -> "REJECTED"
            else -> normalized
        }
        if (chatId.isNotBlank()) {
            val chatPatch = hashMapOf<String, Any>(
                "requestStatus" to reqStatus,
                "isMessageRequest" to (reqStatus != "ACCEPTED")
            )
            // AUDIT SEC-A fix (A11 directionality): record WHO sent the request.
            // Callers pass peerUid = the other participant, i.e. the requester from
            // the decider's point of view. mapChatPreview uses this so the request
            // state only binds the recipient, not the requester.
            if (peerUid.isNotBlank() && peerUid != myUid) {
                chatPatch["requestFromUid"] = peerUid
            }
            if (reqStatus == "REJECTED" || normalized == "DELETE") {
                chatPatch["deletedForUsers"] = FieldValue.arrayUnion(myUid)
            }
            runCatching {
                firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
                    .set(chatPatch, SetOptions.merge())
                    .await()
            }
        }
        if (peerUid.isNotBlank() && peerUid != myUid) {
            val sorted = listOf(myUid, peerUid).sorted()
            val reqId = "${sorted[0]}_${sorted[1]}"
            val reqRef = firestore.collection(Constants.COLLECTION_CONTACT_REQUESTS).document(reqId)
            val reqSnap = runCatching { reqRef.get().await() }.getOrNull()
            if (reqSnap != null && reqSnap.exists()) {
                if (normalized == "DELETE") {
                    reqRef.delete().await()
                } else {
                    reqRef.set(
                        mapOf(
                            "status" to reqStatus,
                            "updatedAt" to System.currentTimeMillis()
                        ),
                        SetOptions.merge()
                    ).await()
                }
            }
        }
    }

    override suspend fun sendBusinessBroadcast(
        recipientUids: List<String>,
        text: String
    ): Result<Map<String, Boolean>> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val cleanText = text.trim()
        require(cleanText.isNotEmpty()) { "نص الرسالة مطلوب" }
        val distinctRecipients = recipientUids.filter { it.isNotBlank() && it != myUid }.distinct()
        require(distinctRecipients.isNotEmpty()) { "يرجى اختيار مستلم واحد على الأقل" }
        val report = linkedMapOf<String, Boolean>()
        for (peerUid in distinctRecipients) {
            val res = runCatching {
                val chatId = getOrCreateDirectChat(peerUid).getOrThrow()
                sendTextMessage(chatId = chatId, text = cleanText).getOrThrow()
            }
            report[peerUid] = res.isSuccess
        }
        report
    }

    /**
     * Studio-2 take (batched delivery acks): one throttled, chunked batch
     * per messages snapshot replaces the per-document DELIVERED update()
     * the mapper used to fire for every SENT message. The throttle is the
     * same 5 s per-chat window (deliveredThrottleMs, shared with
     * markIncomingMessagesDelivered) that also gated r14's identical
     * lastMessageStatus stamps — the stamp now rides the first batch chunk
     * and only fires when a real SENT→DELIVERED transition exists in the
     * snapshot, so the r14 semantics (no repeated identical stamps) hold.
     */
    private suspend fun markMessageRefsDeliveredBatched(
        chatId: String,
        refs: List<com.google.firebase.firestore.DocumentReference>
    ) {
        if (refs.isEmpty()) return
        val now = System.currentTimeMillis()
        val lastRun = deliveredThrottleMs[chatId] ?: 0L
        if (now - lastRun < 5_000L) return
        deliveredThrottleMs[chatId] = now
        runCatching {
            val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
            refs.chunked(400).forEachIndexed { idx, chunk ->
                val batch = firestore.batch()
                chunk.forEach { batch.update(it, "status", MessageStatus.DELIVERED.raw) }
                if (idx == 0) {
                    batch.update(chatRef, "lastMessageStatus", MessageStatus.DELIVERED.raw)
                }
                batch.commit().await()
            }
        }
    }

    override suspend fun setTyping(chatId: String, isTyping: Boolean) {
        val myUid = auth.currentUser?.uid ?: return
        // Failover stage 2 (§5.5): typing follows the chat's home
        // store — plane chats signal through chat-typing (6 s
        // server-side expiry, matching the TypingSignalGate cadence);
        // everything else keeps the Firestore field update.
        if (runCatching { isPlaneEngagedFor(chatId) }.getOrDefault(false)) {
            runCatching { supabaseClient.setTyping(chatId, isTyping) }
            return
        }
        runCatching {
            firestore.collection(Constants.COLLECTION_CHATS)
                .document(chatId)
                .update("typingUsers.$myUid", isTyping)
                .await()
        }
    }

    override suspend fun markChatAsRead(chatId: String) {
        val myUid = auth.currentUser?.uid ?: return
        runCatching {
            val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
            val chatSnap = chatRef.get().await()
            // Scaling pass: the zeroing write is billed even when the
            // counter is already 0 (the common case once receipts are
            // batched) — the snapshot just fetched says so, so skip it.
            val myUnreadCount = ((chatSnap.get("unreadCounts") as? Map<*, *>)
                ?.get(myUid) as? Number)?.toInt() ?: 0
            if (myUnreadCount > 0) {
                chatRef.update("unreadCounts.$myUid", 0).await()
            }

            val me = fetchUserCached(myUid)
            if (me?.privacySettings?.readReceiptsEnabled == false) {
                return@runCatching
            }

            // Spec §13.1: page the unread fetch (450/batch, Firestore's batch cap is
            // 500) instead of pulling the whole SENT/DELIVERED set in one get().
            var anyUpdated = false
            var roundsLeft = 20
            var pageCursor: DocumentSnapshot? = null
            while (roundsLeft-- > 0) {
                var unreadQuery = chatRef.collection(Constants.COLLECTION_MESSAGES)
                    .whereIn("status", listOf(MessageStatus.SENT.raw, MessageStatus.DELIVERED.raw))
                    .limit(450)
                pageCursor?.let { unreadQuery = unreadQuery.startAfter(it) }
                val unreadSnap = unreadQuery.get().await()
                if (unreadSnap.isEmpty) break
                pageCursor = unreadSnap.documents.last()
                val batch = firestore.batch()
                var pageUpdated = false
                unreadSnap.documents.forEach { doc ->
                    if (doc.getString("senderId") != myUid) {
                        batch.update(doc.reference, "status", MessageStatus.READ.raw)
                        pageUpdated = true
                    }
                }
                if (pageUpdated) {
                    batch.commit().await()
                    anyUpdated = true
                }
                // Review MINOR-5: the walk is cursor-based, so a full page of
                // only my own messages (which stay SENT/DELIVERED for the peer
                // and are never updatable here) no longer stalls it — the
                // cursor steps over them and later pages holding the peer's
                // unread messages are still reached.
                if (unreadSnap.size() < 450) break
            }
            if (chatSnap.getString("lastMessageSenderId") != myUid &&
                chatSnap.getString("lastMessageStatus") != MessageStatus.READ.raw
            ) {
                chatRef.update("lastMessageStatus", MessageStatus.READ.raw)
                anyUpdated = true
            }
        }
        // Failover stage 2 (§5.3): the plane arm of read receipts —
        // ack every locally-known unacked incoming plane message READ
        // (rpc_ack_messages also zeroes this chat's inbox unread
        // counter). Mirrors the Firestore arm's privacy gate: no READ
        // acks at all when the user disabled read receipts. Kept
        // independent of the Firestore block above so a Firestore
        // outage cannot skip it.
        runCatching {
            val me = fetchUserCached(myUid)
            if (me?.privacySettings?.readReceiptsEnabled != false) {
                val ids = wesalDao.supabaseIncomingUnackedIds(chatId, myUid, myUid)
                ids.chunked(200).forEach { chunk ->
                    supabaseClient.ackMessages(chatId, chunk, MessageStatus.READ.raw)
                }
            }
        }
    }

    override suspend fun editMessage(chatId: String, messageId: String, newText: String): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val msgRef = firestore.collection(Constants.COLLECTION_CHATS)
            .document(chatId)
            .collection(Constants.COLLECTION_MESSAGES)
            .document(messageId)
        val snap = msgRef.get().await()
        require(snap.getString("senderId") == myUid) { "لا يمكنك تعديل رسالة شخص آخر" }
        // Review M-2: raw coercion, same hazard as B-1 — new messages store
        // `timestamp` as a Firestore Timestamp (serverTimestamp), legacy ones
        // as a Long; the typed getLong() cannot read the Timestamp form, so
        // every edit of a new message was rejected as window-expired.
        // clientTimestamp (always written by the new send paths) is the fallback.
        val ts = coerceEpochMillis(snap.get("timestamp"))
            ?: coerceEpochMillis(snap.get("clientTimestamp"))
            ?: 0L
        require(System.currentTimeMillis() - ts <= Constants.MESSAGE_EDIT_WINDOW_MS) {
            "انتهت مهلة تعديل الرسالة (15 دقيقة)"
        }
        msgRef.update(mapOf("text" to newText.trim(), "ciphertext" to "", "isEncrypted" to false, "isEdited" to true)).await()
    }

    override suspend fun deleteMessageForMe(chatId: String, messageId: String): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        firestore.collection(Constants.COLLECTION_CHATS)
            .document(chatId)
            .collection(Constants.COLLECTION_MESSAGES)
            .document(messageId)
            .update("deletedForUsers", FieldValue.arrayUnion(myUid))
            .await()
    }

    override suspend fun deleteMessageForEveryone(chatId: String, messageId: String): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val msgRef = firestore.collection(Constants.COLLECTION_CHATS)
            .document(chatId)
            .collection(Constants.COLLECTION_MESSAGES)
            .document(messageId)
        val snap = msgRef.get().await()
        require(snap.getString("senderId") == myUid || verifyIsOwnerSuperAdmin(myUid)) {
            "لا يمكنك حذف رسالة مستخدم آخر للجميع"
        }
        msgRef.update(
            mapOf(
                "isDeletedForEveryone" to true,
                "text" to "",
                "ciphertext" to "",
                "mediaUrl" to ""
            )
        ).await()
    }

    override suspend fun togglePinChat(chatId: String, pinned: Boolean): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        firestore.collection(Constants.COLLECTION_CHATS).document(chatId).update("pinnedBy.$myUid", pinned).await()
    }

    override suspend fun toggleMuteChat(chatId: String, muted: Boolean): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        firestore.collection(Constants.COLLECTION_CHATS).document(chatId).update("mutedBy.$myUid", muted).await()
    }

    override suspend fun toggleArchiveChat(chatId: String, archived: Boolean): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        // AUDIT SEC-A fix: this used set(mapOf("archivedBy.$myUid" to archived), merge).
        // A dotted key inside a set() map is stored as a LITERAL top-level field named
        // "archivedBy.<uid>", never as the nested archivedBy map that mapChatPreview
        // reads — so Archive/Unarchive was a placebo (isArchived never changed and the
        // chat never moved to the archived list). update() interprets the dot as a
        // field path, exactly like togglePinChat/toggleMuteChat above.
        firestore.collection(Constants.COLLECTION_CHATS)
            .document(chatId)
            .update("archivedBy.$myUid", archived)
            .await()
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun deleteChat(chatId: String, forEveryone: Boolean): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
        val chatSnap = chatRef.get().await()
        val isOfficial = (chatSnap.getBoolean("isOfficialWesalChat") ?: false) ||
            chatId.startsWith(Constants.OFFICIAL_CHAT_PREFIX)
        val isGroup = chatSnap.getBoolean("isGroup") ?: false

        if (forEveryone && !isOfficial && !isGroup) {
            val participants = (chatSnap.get("participantIds") as? List<String>).orEmpty()
            require(myUid in participants) { "غير مصرح بحذف هذه المحادثة للطرفين" }
            val msgsSnap = chatRef.collection(Constants.COLLECTION_MESSAGES).limit(350).get().await()
            val batch = firestore.batch()
            msgsSnap.documents.forEach { msgDoc ->
                batch.update(
                    msgDoc.reference,
                    mapOf(
                        "isDeletedForEveryone" to true,
                        "text" to "",
                        "ciphertext" to "",
                        "mediaUrl" to "",
                        "deletedForUsers" to participants
                    )
                )
            }
            batch.update(
                chatRef,
                mapOf(
                    "deletedForUsers" to participants,
                    "lastMessagePreview" to "",
                    "lastMessageSenderId" to ""
                )
            )
            batch.commit().await()
        } else {
            chatRef.update("deletedForUsers", FieldValue.arrayUnion(myUid)).await()
        }
        wesalDao.deleteChat(chatId)
        wesalDao.deleteMessagesForChat(chatId)
    }

    override suspend fun clearChatMessagesForMe(chatId: String): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val chatRef = firestore.collection(Constants.COLLECTION_CHATS).document(chatId)
        val msgsSnap = chatRef.collection(Constants.COLLECTION_MESSAGES).limit(400).get().await()
        msgsSnap.documents.chunked(350).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { msgDoc ->
                batch.update(msgDoc.reference, "deletedForUsers", FieldValue.arrayUnion(myUid))
            }
            batch.commit().await()
        }
        // AUDIT R5 coordinator fix: same dotted-key set() bug as toggleArchiveChat —
        // set(mapOf("unreadCounts.$myUid" to 0), merge) stored a literal top-level
        // field named "unreadCounts.<uid>" and never zeroed the nested unreadCounts
        // map entry the chat list reads. update() treats the dot as a field path.
        chatRef.update("unreadCounts.$myUid", 0).await()
    }

    @Suppress("UNCHECKED_CAST")
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeBroadcastLists(): Flow<List<BroadcastList>> = observeAuthUid().flatMapLatest { uid ->
        if (uid == null) flowOf(emptyList())
        else callbackFlow {
            val reg = firestore.collection(Constants.COLLECTION_BROADCAST_LISTS)
                .whereEqualTo("ownerUid", uid)
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    val list = snap?.documents?.map { d ->
                        BroadcastList(
                            listId = d.getString("listId") ?: d.id,
                            ownerUid = d.getString("ownerUid").orEmpty(),
                            name = d.getString("name").orEmpty(),
                            recipientUids = (d.get("recipientUids") as? List<String>).orEmpty(),
                            lastSentText = d.getString("lastSentText").orEmpty(),
                            lastSentAt = d.getLong("lastSentAt") ?: 0L,
                            createdAt = d.getLong("createdAt") ?: 0L,
                            updatedAt = d.getLong("updatedAt") ?: 0L
                        )
                    }.orEmpty().sortedByDescending { it.updatedAt.coerceAtLeast(it.createdAt) }
                    trySend(list)
                }
            awaitClose { reg.remove() }
        }
    }

    override suspend fun createBroadcastList(name: String, recipientUids: List<String>): Result<BroadcastList> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val cleanName = name.trim()
        val cleanRecipients = recipientUids.filter { it.isNotBlank() && it != myUid }.distinct()
        require(cleanName.isNotBlank()) { "اسم قائمة البث مطلوب" }
        require(cleanRecipients.isNotEmpty()) { "اختر مستلمًا واحدًا على الأقل" }
        val listId = "bcast_${myUid}_${System.currentTimeMillis()}"
        val now = System.currentTimeMillis()
        val item = BroadcastList(
            listId = listId,
            ownerUid = myUid,
            name = cleanName,
            recipientUids = cleanRecipients,
            createdAt = now,
            updatedAt = now
        )
        firestore.collection(Constants.COLLECTION_BROADCAST_LISTS).document(listId).set(
            mapOf(
                "listId" to item.listId,
                "ownerUid" to item.ownerUid,
                "name" to item.name,
                "recipientUids" to item.recipientUids,
                "lastSentText" to "",
                "lastSentAt" to 0L,
                "createdAt" to now,
                "updatedAt" to now
            )
        ).await()
        item
    }

    override suspend fun updateBroadcastList(
        listId: String,
        name: String,
        recipientUids: List<String>
    ): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val cleanName = name.trim()
        val cleanRecipients = recipientUids.filter { it.isNotBlank() && it != myUid }.distinct()
        require(listId.isNotBlank() && cleanName.isNotBlank()) { "بيانات قائمة البث غير صالحة" }
        require(cleanRecipients.isNotEmpty()) { "اختر مستلمًا واحدًا على الأقل" }
        val ref = firestore.collection(Constants.COLLECTION_BROADCAST_LISTS).document(listId)
        val snap = ref.get().await()
        require(snap.exists() && snap.getString("ownerUid") == myUid) { "غير مصرح بتعديل هذه القائمة" }
        ref.set(
            mapOf(
                "name" to cleanName,
                "recipientUids" to cleanRecipients,
                "updatedAt" to System.currentTimeMillis()
            ),
            SetOptions.merge()
        ).await()
    }

    override suspend fun deleteBroadcastList(listId: String): Result<Unit> = runCatching {
        val myUid = auth.currentUser?.uid ?: error("Not authenticated")
        val ref = firestore.collection(Constants.COLLECTION_BROADCAST_LISTS).document(listId)
        val snap = ref.get().await()
        require(!snap.exists() || snap.getString("ownerUid") == myUid) { "غير مصرح بحذف هذه القائمة" }
        ref.delete().await()
    }
}


/**
 * Round 13 (Addenda J/L3/L4): bounded wait for a Firestore write's
 * server ACK. The write itself is already queued in the SDK's
 * persistent outbox and visible in local snapshots immediately
 * (latency compensation); waiting for the ACK must never block the
 * user — on timeout OR failure this returns and the SDK keeps
 * retrying the commit in the background (e.g. past a quota reset).
 * Mirrors the helper of the same name in AuthRepositoryImpl, except
 * that failures are also swallowed: chat-birth and welcome writes are
 * fire-and-forget by design.
 */
private suspend fun <T> Task<T>.awaitQueued(timeoutMs: Long = 6_000L) {
    runCatching { withTimeoutOrNull(timeoutMs) { await() } }
}
