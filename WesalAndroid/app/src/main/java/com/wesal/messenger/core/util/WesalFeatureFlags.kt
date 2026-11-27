package com.wesal.messenger.core.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

/**
 * Pure default rule for the bulk-invite flag (unit-tested): the feature is ENABLED
 * unless an admin explicitly stored `false`. A missing document, a missing field or a
 * listener error must never disable a user feature on its own.
 */
fun resolveBulkWhatsAppInvitesEnabled(stored: Boolean?): Boolean = stored ?: true

/**
 * Admin-controlled feature switches, stored in `system_config/features` and observed
 * LIVE by every client — the same propagation pattern as the master frame-animation
 * switch (`system_config/global_appearance.framesAnimationEnabled` in [WesalFrameLibrary]).
 * Firestore rules already cover the doc: any signed-in user may read `system_config/*`,
 * only super admins / authorized moderators may write it.
 */
object WesalFeatureFlags {

    const val FEATURES_DOC_ID = "features"
    const val FIELD_BULK_WHATSAPP_INVITES = "bulkWhatsAppInvitesEnabled"

    private const val FIRESTORE_DB_ID = "ai-studio-livo-cd975dac-35e6-4dbb-864e-829e3b638424"

    private fun db(): FirebaseFirestore =
        FirebaseFirestore.getInstance(FirebaseApp.getInstance(), FIRESTORE_DB_ID)

    /**
     * Kill-switch for the bulk WhatsApp invites in the contacts screen (owner request):
     * stored as `bulkWhatsAppInvitesEnabled` in `system_config/features` (default true).
     * Compose snapshot state — the contacts screen recomposes the moment an admin flips
     * the switch in the admin panel, no app restart needed.
     */
    var bulkWhatsAppInvitesEnabled: Boolean by mutableStateOf(true)
        private set

    private var registration: ListenerRegistration? = null
    private var authHooked = false

    /** Call once from Application.onCreate: (re)starts the listener whenever a user signs in. */
    fun startWithAuth() {
        if (authHooked) return
        runCatching {
            authHooked = true
            com.google.firebase.auth.FirebaseAuth.getInstance().addAuthStateListener { fa ->
                stop()
                if (fa.currentUser != null) {
                    start()
                } else {
                    // Signed out: fall back to the fail-open default for the next session.
                    bulkWhatsAppInvitesEnabled = true
                }
            }
        }
    }

    private fun start() {
        if (registration != null) return
        runCatching {
            registration = db().collection(Constants.COLLECTION_SYSTEM_CONFIG)
                .document(FEATURES_DOC_ID)
                .addSnapshotListener { snap, error ->
                    if (error != null) return@addSnapshotListener
                    val enabled = resolveBulkWhatsAppInvitesEnabled(
                        if (snap != null && snap.exists()) snap.getBoolean(FIELD_BULK_WHATSAPP_INVITES) else null
                    )
                    if (enabled != bulkWhatsAppInvitesEnabled) bulkWhatsAppInvitesEnabled = enabled
                }
        }
    }

    fun stop() {
        runCatching { registration?.remove() }
        registration = null
    }

    /** Admin write path for the kill-switch (merge: never touches other config keys). */
    suspend fun setBulkWhatsAppInvitesEnabled(enabled: Boolean): Result<Unit> = runCatching {
        db().collection(Constants.COLLECTION_SYSTEM_CONFIG)
            .document(FEATURES_DOC_ID)
            .set(
                mapOf(
                    FIELD_BULK_WHATSAPP_INVITES to enabled,
                    "updatedAt" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            ).await()
        bulkWhatsAppInvitesEnabled = enabled
    }
}
