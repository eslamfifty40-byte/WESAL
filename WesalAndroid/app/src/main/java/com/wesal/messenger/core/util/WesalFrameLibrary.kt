package com.wesal.messenger.core.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

/**
 * Firestore document in `frame_library/{frameId}`.
 *
 * - Custom (uploaded) frames: builtin = false, imageUrl = Storage download URL (upload failures
 *   surface as Result.failure to the admin UI; no data: URI fallback since round 4, §5.4).
 * - Built-in frames can have an override document with the SAME id: it can hide/soft-delete the frame
 *   from the picker or replace its image, without touching users that already wear it.
 */
data class FrameDoc(
    val id: String,
    val titleAr: String = "",
    val category: String = "CUSTOMER", // CUSTOMER | ADMIN | ICON | OFFICIAL
    val kind: String = "FRAME", // FRAME | ICON
    val imageUrl: String = "",
    val glowColor: Long = 0L,
    val animated: Boolean = false,
    val animationStyle: String = "PULSE_GLOW", // PULSE_GLOW | ROTATE_AURA | SHIMMER | SPARKLE
    val hasAnimatedOverride: Boolean = false,
    val hasAnimationStyleOverride: Boolean = false,
    val glowIntensity: Float = 1.0f, // §5.8: 0..1, scales the frame glow halo
    val glowSpeed: Float = 1.0f, // §5.8: 0.5..2, scales animation durations (higher = faster)
    val hasGlowIntensityOverride: Boolean = false,
    val hasGlowSpeedOverride: Boolean = false,
    val hidden: Boolean = false,
    val deleted: Boolean = false,
    val builtin: Boolean = false,
    val sortOrder: Long = 0L,
    val createdAt: Long = 0L
)

/** What WesalAvatar needs to draw a frame. Either [resId] (built-in) or [imageUrl] (library) is set. */
data class ResolvedFrame(
    val id: String,
    val resId: Int,
    val imageUrl: String,
    val glow: Color,
    val animated: Boolean = false,
    val animationStyle: String = "PULSE_GLOW",
    val apertureCenterYFraction: Float = 0.50f,
    val apertureDiameterFraction: Float = 0.62f,
    val glowIntensity: Float = 1.0f,
    val glowSpeed: Float = 1.0f
)

/** One row in an admin/user picker. */
data class FramePickerItem(
    val id: String,
    val title: String,
    val resId: Int,
    val imageUrl: String,
    val glow: Color,
    val hidden: Boolean,
    val deleted: Boolean,
    val builtin: Boolean,
    val kind: String = "FRAME",
    val animated: Boolean = false,
    val animationStyle: String = "PULSE_GLOW",
    val glowIntensity: Float = 1.0f,
    val glowSpeed: Float = 1.0f
)

object WesalFrameLibrary {
    const val COLLECTION = "frame_library"

    /** Golden ADMIN crest, shown only for admin accounts. Deliberately not in the catalog or any picker. */
    const val ADMIN_FRAME_ID = "wesal_admin_golden"
    private const val FIRESTORE_DB_ID = "ai-studio-livo-cd975dac-35e6-4dbb-864e-829e3b638424"

    private fun db(): FirebaseFirestore =
        FirebaseFirestore.getInstance(FirebaseApp.getInstance(), FIRESTORE_DB_ID)

    /** Compose snapshot state: UI recomposes automatically when the library changes. */
    var docs: Map<String, FrameDoc> by mutableStateOf(emptyMap())
        private set

    /**
     * Master switch for ALL frame animation (owner request): stored as `framesAnimationEnabled`
     * in `system_config/global_appearance` (default true). When off, WesalAvatar renders every
     * frame statically, whatever the per-frame `animated` flag says.
     */
    var framesAnimationEnabled: Boolean by mutableStateOf(true)
        private set

    private var registration: ListenerRegistration? = null
    private var configRegistration: ListenerRegistration? = null

    private var authHooked = false

    // The library holds base64 frame images inside Firestore docs, so reading it is heavy. It must never
    // compete with login and the first screen: start it a moment later and process snapshots off the main thread.
    private val startScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var startJob: Job? = null
    private const val START_DELAY_MS = 2500L

    /** Call once from Application.onCreate: (re)starts the listener whenever a user signs in. */
    fun startWithAuth() {
        if (authHooked) return
        runCatching {
            authHooked = true
            com.google.firebase.auth.FirebaseAuth.getInstance().addAuthStateListener { fa ->
                stop()
                if (fa.currentUser != null) {
                    startJob?.cancel()
                    startJob = startScope.launch {
                        delay(START_DELAY_MS)
                        start()
                    }
                } else {
                    startJob?.cancel()
                    docs = emptyMap()
                }
            }
        }
    }

    private fun start() {
        if (registration != null) return
        runCatching {
            registration = db().collection(COLLECTION)
                .addSnapshotListener(Dispatchers.Default.asExecutor()) { snap, error ->
                    if (error != null || snap == null) return@addSnapshotListener
                    val map = HashMap<String, FrameDoc>()
                    for (d in snap.documents) {
                        val cat = d.getString("category") ?: "CUSTOMER"
                        val kind = d.getString("kind") ?: if (cat == "ICON") "ICON" else "FRAME"
                        map[d.id] = FrameDoc(
                            id = d.id,
                            titleAr = d.getString("titleAr").orEmpty(),
                            category = cat,
                            kind = kind,
                            imageUrl = d.getString("imageUrl").orEmpty(),
                            glowColor = d.getLong("glowColor") ?: 0L,
                            animated = d.getBoolean("animated") ?: false,
                            animationStyle = d.getString("animationStyle") ?: "PULSE_GLOW",
                            hasAnimatedOverride = d.contains("animated"),
                            hasAnimationStyleOverride = !d.getString("animationStyle").isNullOrBlank(),
                            glowIntensity = (d.getDouble("glowIntensity") ?: 1.0).toFloat().coerceIn(0f, 1f),
                            glowSpeed = (d.getDouble("glowSpeed") ?: 1.0).toFloat().coerceIn(0.5f, 2f),
                            hasGlowIntensityOverride = d.contains("glowIntensity"),
                            hasGlowSpeedOverride = d.contains("glowSpeed"),
                            hidden = d.getBoolean("hidden") ?: false,
                            deleted = d.getBoolean("deleted") ?: false,
                            builtin = d.getBoolean("builtin") ?: false,
                            sortOrder = d.getLong("sortOrder") ?: 0L,
                            createdAt = d.getLong("createdAt") ?: 0L
                        )
                    }
                    if (map != docs) docs = map
                }
        }
        startConfigListener()
    }

    /** Keeps [framesAnimationEnabled] in sync with `system_config/global_appearance`. */
    private fun startConfigListener() {
        if (configRegistration != null) return
        runCatching {
            configRegistration = db().collection(Constants.COLLECTION_SYSTEM_CONFIG)
                .document("global_appearance")
                .addSnapshotListener { snap, error ->
                    if (error != null) return@addSnapshotListener
                    val enabled = if (snap != null && snap.exists()) {
                        snap.getBoolean("framesAnimationEnabled") ?: true
                    } else {
                        true
                    }
                    if (enabled != framesAnimationEnabled) framesAnimationEnabled = enabled
                }
        }
    }

    fun stop() {
        startJob?.cancel()
        runCatching { registration?.remove() }
        registration = null
        runCatching { configRegistration?.remove() }
        configRegistration = null
    }

    /** Admin write path for the master animation switch (merge: never touches other config keys). */
    suspend fun setFramesAnimationEnabled(enabled: Boolean): Result<Unit> = runCatching {
        db().collection(Constants.COLLECTION_SYSTEM_CONFIG)
            .document("global_appearance")
            .set(
                mapOf(
                    "framesAnimationEnabled" to enabled,
                    "updatedAt" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            ).await()
        framesAnimationEnabled = enabled
    }

    private fun glowOf(builtinId: String, doc: FrameDoc?): Color {
        val v = doc?.glowColor?.takeIf { it != 0L } ?: WesalFrameCatalog.builtinGlow[builtinId] ?: 0xFFFFC107L
        return Color(v.toInt())
    }

    /**
     * Resolves an icon URL. Even if an icon is deleted/hidden from the library, users who already
     * have it keep displaying either the doc's imageUrl or the snapshot [fallbackUrl] on their user doc.
     */
    fun resolveIconUrl(iconId: String?, fallbackUrl: String = ""): String {
        val id = iconId?.trim().orEmpty()
        if (id.isNotBlank()) {
            val fromLib = docs[id]?.imageUrl?.takeIf { it.isNotBlank() }
            if (!fromLib.isNullOrBlank()) return fromLib
            val builtinIcon = WesalFrameCatalog.builtinIcons.firstOrNull { it.id == id }
            if (builtinIcon != null) return "builtin_icon:${builtinIcon.id}"
        }
        return fallbackUrl.trim()
    }

    /**
     * Resolves the frame a user is wearing. Deleted/hidden frames STILL resolve, so removing a frame
     * from the library never removes it from users who already have it.
     */
    fun resolve(frameId: String?, expiresAt: Long): ResolvedFrame? {
        if (frameId.isNullOrBlank()) return null
        if (frameId == ADMIN_FRAME_ID) {
            // The royal ADMIN crest (1536x1536). Opening of the artwork (measured from its
            // transparent aperture): centre at 39.6% of the height, diameter 49.6% of the width.
            // Routed through the SAME library-override resolution as every other frame, so an
            // admin can replace its image or turn its animation/glow OFF from the admin panel
            // (frame_library/wesal_admin_golden). Defaults preserve the historical look:
            // animated PULSE_GLOW when no override doc exists. Never expires.
            val doc = docs[frameId]
            val isAnimated = if (doc != null && doc.hasAnimatedOverride) doc.animated else true
            val animStyle = if (doc != null && doc.hasAnimationStyleOverride) {
                doc.animationStyle
            } else {
                "PULSE_GLOW"
            }
            val glowI = if (doc != null && doc.hasGlowIntensityOverride) doc.glowIntensity else 1.0f
            val glowS = if (doc != null && doc.hasGlowSpeedOverride) doc.glowSpeed else 1.0f
            val overrideUrl = doc?.imageUrl?.takeIf { it.isNotBlank() }.orEmpty()
            return ResolvedFrame(
                id = ADMIN_FRAME_ID,
                resId = if (overrideUrl.isBlank()) {
                    com.wesal.messenger.R.drawable.wesal_frame_royal_admin_1536
                } else {
                    0
                },
                imageUrl = overrideUrl,
                glow = doc?.glowColor?.takeIf { it != 0L }?.let { Color(it.toInt()) }
                    ?: Color(0xFFFF3B1F),
                animated = isAnimated,
                animationStyle = animStyle,
                apertureCenterYFraction = 0.396f,
                apertureDiameterFraction = 0.496f,
                glowIntensity = glowI,
                glowSpeed = glowS
            )
        }
        if (expiresAt > 0L && System.currentTimeMillis() > expiresAt) return null
        val doc = docs[frameId]
        val builtin = WesalFrameCatalog.allFrames.firstOrNull { it.id == frameId }
        val isAnimated = if (doc != null && doc.hasAnimatedOverride) {
            doc.animated
        } else {
            builtin?.defaultAnimated == true
        }
        val animStyle = if (doc != null && doc.hasAnimationStyleOverride) {
            doc.animationStyle
        } else {
            builtin?.defaultAnimationStyle ?: "PULSE_GLOW"
        }
        val glowI = if (doc != null && doc.hasGlowIntensityOverride) doc.glowIntensity else 1.0f
        val glowS = if (doc != null && doc.hasGlowSpeedOverride) doc.glowSpeed else 1.0f
        val cy = builtin?.apertureCenterYFraction ?: 0.50f
        val dFrac = builtin?.apertureDiameterFraction ?: 0.62f
        if (doc != null && doc.imageUrl.isNotBlank()) {
            return ResolvedFrame(frameId, 0, doc.imageUrl, glowOf(frameId, doc), isAnimated, animStyle, cy, dFrac, glowI, glowS)
        }
        if (builtin != null) {
            return ResolvedFrame(frameId, builtin.resId, "", glowOf(frameId, doc), isAnimated, animStyle, cy, dFrac, glowI, glowS)
        }
        return null
    }

    /** Frames or icons offered in a picker for the given category (CUSTOMER, ADMIN, or ICON). */
    fun pickerItems(category: String, includeHiddenAndDeleted: Boolean): List<FramePickerItem> {
        val out = ArrayList<FramePickerItem>()
        if (category == "ICON") {
            val builtinIconIds = WesalFrameCatalog.builtinIcons.map { it.id }.toSet()
            for (spec in WesalFrameCatalog.builtinIcons) {
                val doc = docs[spec.id]
                val hidden = doc?.hidden == true
                val deleted = doc?.deleted == true
                if (!includeHiddenAndDeleted && (hidden || deleted)) continue
                val customUrl = doc?.imageUrl?.takeIf { it.isNotBlank() }
                out += FramePickerItem(
                    id = spec.id,
                    title = doc?.titleAr?.takeIf { it.isNotBlank() } ?: spec.titleAr,
                    resId = if (customUrl == null) spec.resId else 0,
                    imageUrl = customUrl ?: "builtin_icon:${spec.id}",
                    glow = Color(spec.accentColorHex.toInt()),
                    hidden = hidden,
                    deleted = deleted,
                    builtin = true,
                    kind = "ICON",
                    animated = doc?.animated ?: true,
                    animationStyle = doc?.animationStyle ?: "PULSE_GLOW"
                )
            }
            docs.values
                .filter { it.id !in builtinIconIds && (it.kind == "ICON" || it.category == "ICON") && it.imageUrl.isNotBlank() }
                .sortedBy { it.createdAt }
                .forEach { d ->
                    if (!includeHiddenAndDeleted && (d.hidden || d.deleted)) return@forEach
                    out += FramePickerItem(
                        id = d.id,
                        title = d.titleAr.ifBlank { "أيقونة" },
                        resId = 0,
                        imageUrl = d.imageUrl,
                        glow = glowOf(d.id, d),
                        hidden = d.hidden,
                        deleted = d.deleted,
                        builtin = false,
                        kind = "ICON",
                        animated = d.animated,
                        animationStyle = d.animationStyle
                    )
                }
            return out
        }

        if (category == "ADMIN") {
            // The royal ADMIN crest is not in the bundled catalog, but it MUST be listed here
            // so the admin dialog can manage it (animation on/off, glow, image replace) —
            // resolve() reads the same override doc this item's actions write.
            val doc = docs[ADMIN_FRAME_ID]
            val hidden = doc?.hidden == true
            val deleted = doc?.deleted == true
            if (includeHiddenAndDeleted || !(hidden || deleted)) {
                out += FramePickerItem(
                    id = ADMIN_FRAME_ID,
                    title = doc?.titleAr?.takeIf { it.isNotBlank() } ?: "الإطار الملكي للإدارة (ADMIN)",
                    resId = com.wesal.messenger.R.drawable.wesal_frame_royal_admin_1536,
                    imageUrl = doc?.imageUrl.orEmpty(),
                    glow = doc?.glowColor?.takeIf { it != 0L }?.let { Color(it.toInt()) }
                        ?: Color(0xFFFF3B1F),
                    hidden = hidden,
                    deleted = deleted,
                    builtin = true,
                    kind = "FRAME",
                    animated = if (doc != null && doc.hasAnimatedOverride) doc.animated else true,
                    animationStyle = if (doc != null && doc.hasAnimationStyleOverride) doc.animationStyle else "PULSE_GLOW",
                    glowIntensity = if (doc != null && doc.hasGlowIntensityOverride) doc.glowIntensity else 1.0f,
                    glowSpeed = if (doc != null && doc.hasGlowSpeedOverride) doc.glowSpeed else 1.0f
                )
            }
        }
        // OFFICIAL (§5.7): no bundled artwork — WESAL-team frames live only as library uploads.
        val builtinList = when (category) {
            "ADMIN" -> WesalFrameCatalog.adminFrames
            "OFFICIAL" -> emptyList()
            else -> WesalFrameCatalog.customerFrames
        }
        for (spec in builtinList) {
            val doc = docs[spec.id]
            val hidden = doc?.hidden == true
            val deleted = doc?.deleted == true
            if (!includeHiddenAndDeleted && (hidden || deleted)) continue
            out += FramePickerItem(
                id = spec.id,
                title = doc?.titleAr?.takeIf { it.isNotBlank() } ?: spec.titleAr,
                resId = spec.resId,
                imageUrl = doc?.imageUrl.orEmpty(),
                glow = glowOf(spec.id, doc),
                hidden = hidden,
                deleted = deleted,
                builtin = true,
                kind = "FRAME",
                animated = if (doc != null && doc.hasAnimatedOverride) doc.animated else spec.defaultAnimated,
                animationStyle = if (doc != null && doc.hasAnimationStyleOverride) doc.animationStyle else spec.defaultAnimationStyle,
                glowIntensity = if (doc != null && doc.hasGlowIntensityOverride) doc.glowIntensity else 1.0f,
                glowSpeed = if (doc != null && doc.hasGlowSpeedOverride) doc.glowSpeed else 1.0f
            )
        }
        val builtinIds = WesalFrameCatalog.allFrames.map { it.id }.toSet()
        docs.values
            .filter {
                it.id !in builtinIds &&
                    it.id != ADMIN_FRAME_ID &&
                    it.kind != "ICON" &&
                    it.category == category &&
                    it.imageUrl.isNotBlank()
            }
            .sortedBy { it.createdAt }
            .forEach { d ->
                if (!includeHiddenAndDeleted && (d.hidden || d.deleted)) return@forEach
                out += FramePickerItem(
                    id = d.id,
                    title = d.titleAr.ifBlank { d.id },
                    resId = 0,
                    imageUrl = d.imageUrl,
                    glow = glowOf(d.id, d),
                    hidden = d.hidden,
                    deleted = d.deleted,
                    builtin = false,
                    kind = "FRAME",
                    animated = d.animated,
                    animationStyle = d.animationStyle,
                    glowIntensity = d.glowIntensity,
                    glowSpeed = d.glowSpeed
                )
            }
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // Admin operations (all write to Firestore; Firestore rules allow only the super admin)
    // ---------------------------------------------------------------------------------------------

    private fun col() = db().collection(COLLECTION)

    /** Uploads a NEW frame or icon to the library. Returns the new item id. */
    suspend fun uploadNewFrame(
        ctx: Context,
        uri: Uri,
        titleAr: String,
        category: String,
        kind: String = if (category == "ICON") "ICON" else "FRAME",
        animated: Boolean = false,
        animationStyle: String = "PULSE_GLOW"
    ): Result<String> = runCatching {
        val isIcon = kind == "ICON" || category == "ICON"
        val prefix = when {
            isIcon -> "icon_x_"
            category == "ADMIN" -> "admin_x_"
            category == "OFFICIAL" -> "off_x_"
            else -> "cust_x_"
        }
        val id = prefix + System.currentTimeMillis()
        val mime = runCatching { ctx.contentResolver.getType(uri).orEmpty().lowercase() }.getOrDefault("")
        val isGif = mime.contains("gif") || uri.toString().lowercase().endsWith(".gif")
        val (url, glow) = if (isGif) processAndStoreRawGif(ctx, uri, id) else processAndStore(ctx, uri, id)
        col().document(id).set(
            mapOf(
                "titleAr" to titleAr.ifBlank { if (isIcon) "أيقونة جديدة" else "إطار جديد" },
                "category" to if (isIcon) "ICON" else category,
                "kind" to if (isIcon) "ICON" else "FRAME",
                "imageUrl" to url,
                "glowColor" to glow,
                "animated" to (animated || isGif),
                "animationStyle" to animationStyle,
                "hidden" to false,
                "deleted" to false,
                "builtin" to false,
                "createdAt" to System.currentTimeMillis(),
                "sortOrder" to System.currentTimeMillis()
            )
        ).await()
        verifyExists(id)
        id
    }

    /** Replaces the image of an existing frame or icon. Users wearing it see the new image. */
    suspend fun replaceFrameImage(ctx: Context, frameId: String, uri: Uri): Result<Unit> = runCatching {
        val (url, glow) = processAndStore(ctx, uri, frameId + "_" + System.currentTimeMillis())
        val builtin = WesalFrameCatalog.allFrames.firstOrNull { it.id == frameId }
        val existingDoc = docs[frameId]
        val data = hashMapOf<String, Any>(
            "imageUrl" to url,
            "glowColor" to glow,
            "updatedAt" to System.currentTimeMillis()
        )
        if (builtin != null) {
            data["builtin"] = true
            data["category"] = if (builtin.category == FrameCategory.ADMIN) "ADMIN" else "CUSTOMER"
            data["kind"] = "FRAME"
        } else if (existingDoc != null) {
            data["kind"] = existingDoc.kind
            data["category"] = existingDoc.category
        }
        col().document(frameId).set(data, SetOptions.merge()).await()
        verifyExists(frameId)
    }

    suspend fun setHidden(frameId: String, hidden: Boolean): Result<Unit> = runCatching {
        writeFlag(frameId, "hidden", hidden)
    }

    /** Removes the frame/icon from the library/pickers only. Users who already use it keep it via their saved URL snapshot. */
    suspend fun setDeleted(frameId: String, deleted: Boolean): Result<Unit> = runCatching {
        writeFlag(frameId, "deleted", deleted)
    }

    /** Permanently deletes a custom uploaded frame/icon document from the library collection without touching users' own user docs. */
    suspend fun permanentlyDelete(frameId: String): Result<Unit> = runCatching {
        val builtin = WesalFrameCatalog.allFrames.firstOrNull { it.id == frameId }
        val builtinIcon = WesalFrameCatalog.builtinIcons.firstOrNull { it.id == frameId }
        check(builtin == null && builtinIcon == null) { "لا يمكن حذف الإطارات أو الأيقونات المدمجة نهائيًا؛ استخدم الإزالة من المكتبة" }
        col().document(frameId).delete().await()
    }

    suspend fun rename(frameId: String, titleAr: String): Result<Unit> = runCatching {
        writeFlag(frameId, "titleAr", titleAr.trim())
    }

    suspend fun setAnimated(frameId: String, animated: Boolean, style: String = "PULSE_GLOW"): Result<Unit> = runCatching {
        val builtin = WesalFrameCatalog.allFrames.firstOrNull { it.id == frameId }
        val existingDoc = docs[frameId]
        val data = hashMapOf<String, Any>(
            "animated" to animated,
            "animationStyle" to style,
            "updatedAt" to System.currentTimeMillis()
        )
        if (builtin != null) {
            data["builtin"] = true
            data["category"] = if (builtin.category == FrameCategory.ADMIN) "ADMIN" else "CUSTOMER"
            data["kind"] = "FRAME"
        } else if (existingDoc != null) {
            data["kind"] = existingDoc.kind
            data["category"] = existingDoc.category
        } else if (frameId == ADMIN_FRAME_ID) {
            // The royal crest has no catalog entry; tag its override doc so it stays
            // well-formed (resolve() reads it by id regardless).
            data["builtin"] = true
            data["category"] = "ADMIN"
            data["kind"] = "FRAME"
        }
        col().document(frameId).set(data, SetOptions.merge()).await()
    }

    /** §5.8: per-frame glow intensity (0..1) and animation/glow speed (0.5..2) overrides. */
    suspend fun setGlow(frameId: String, intensity: Float, speed: Float): Result<Unit> = runCatching {
        val builtin = WesalFrameCatalog.allFrames.firstOrNull { it.id == frameId }
        val existingDoc = docs[frameId]
        val data = hashMapOf<String, Any>(
            "glowIntensity" to intensity.coerceIn(0f, 1f).toDouble(),
            "glowSpeed" to speed.coerceIn(0.5f, 2f).toDouble(),
            "updatedAt" to System.currentTimeMillis()
        )
        if (builtin != null) {
            data["builtin"] = true
            data["category"] = if (builtin.category == FrameCategory.ADMIN) "ADMIN" else "CUSTOMER"
            data["kind"] = "FRAME"
        } else if (existingDoc != null) {
            data["kind"] = existingDoc.kind
            data["category"] = existingDoc.category
        } else if (frameId == ADMIN_FRAME_ID) {
            data["builtin"] = true
            data["category"] = "ADMIN"
            data["kind"] = "FRAME"
        }
        col().document(frameId).set(data, SetOptions.merge()).await()
    }

    /** Drops an image replacement on a built-in frame, restoring the original bundled image. */
    suspend fun restoreOriginalImage(frameId: String): Result<Unit> = runCatching {
        col().document(frameId).set(mapOf("imageUrl" to "", "glowColor" to 0L), SetOptions.merge()).await()
    }

    private suspend fun writeFlag(frameId: String, key: String, value: Any) {
        val builtin = WesalFrameCatalog.allFrames.firstOrNull { it.id == frameId }
        val existingDoc = docs[frameId]
        val data = hashMapOf<String, Any>(key to value, "updatedAt" to System.currentTimeMillis())
        if (builtin != null) {
            data["builtin"] = true
            data["category"] = if (builtin.category == FrameCategory.ADMIN) "ADMIN" else "CUSTOMER"
            data["kind"] = "FRAME"
        } else if (existingDoc != null) {
            data["kind"] = existingDoc.kind
            data["category"] = existingDoc.category
        }
        col().document(frameId).set(data, SetOptions.merge()).await()
    }

    /** Reads the document back from the SERVER so a silent failure can never look like success. */
    private suspend fun verifyExists(id: String) {
        val snap = col().document(id).get(com.google.firebase.firestore.Source.SERVER).await()
        check(snap.exists()) { "لم يتم حفظ الإطار على Firebase" }
    }

    /**
     * Makes the image transparent and uploads it to Firebase Storage.
     *
     * §5.4 (round 4): the base64 data-URI fallback is REMOVED — a `data:` string must never be
     * stored as a frame's imageUrl. On Storage failure this throws the real upload exception;
     * uploadNewFrame/replaceFrameImage wrap it in Result.failure, which the admin UI already
     * renders as a visible "فشل الحفظ: …" status message.
     */
    private suspend fun processAndStore(ctx: Context, uri: Uri, name: String): Pair<String, Long> = withContext(Dispatchers.IO) {
        val bmp = FrameImageProcessor.decodeScaled(ctx, uri, 512)
            ?: error("تعذر قراءة الصورة")
        val clean = FrameImageProcessor.makeBackgroundTransparent(bmp)
        val glow = FrameImageProcessor.dominantGlow(clean)
        val out = ByteArrayOutputStream()
        clean.compress(Bitmap.CompressFormat.PNG, 100, out)
        val bytes = out.toByteArray()

        val storageResult = com.wesal.messenger.data.repository.uploadRawBytesToStorage(
            storagePath = "frame_library/${name}.png",
            bytes = bytes,
            mimeType = "image/png"
        )
        val storageUrl = storageResult.getOrNull()
        if (!storageUrl.isNullOrBlank()) {
            return@withContext storageUrl to glow
        }
        throw (storageResult.exceptionOrNull()
            ?: IllegalStateException("تعذر رفع الإطار إلى التخزين السحابي"))
    }

    private suspend fun processAndStoreRawGif(ctx: Context, uri: Uri, name: String): Pair<String, Long> = withContext(Dispatchers.IO) {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("تعذر قراءة ملف GIF")
        val bmp = FrameImageProcessor.decodeScaled(ctx, uri, 256)
        val glow = bmp?.let { FrameImageProcessor.dominantGlow(it) } ?: 0xFF00D9E8L
        val storageResult = com.wesal.messenger.data.repository.uploadRawBytesToStorage(
            storagePath = "frame_library/${name}.gif",
            bytes = bytes,
            mimeType = "image/gif"
        )
        val storageUrl = storageResult.getOrNull()
        if (!storageUrl.isNullOrBlank()) {
            return@withContext storageUrl to glow
        }
        // §5.4 (round 4): no base64 data-URI fallback — fail visibly with the real exception
        // (uploadNewFrame surfaces it to the admin UI as Result.failure).
        throw (storageResult.exceptionOrNull()
            ?: IllegalStateException("تعذر رفع الصورة المتحركة إلى التخزين السحابي"))
    }
}

/** Pixel work for uploaded frames: real transparency + automatic glow colour. */
object FrameImageProcessor {

    fun decodeScaled(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val raw = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val sc = maxSide.toFloat() / maxOf(raw.width, raw.height)
        val scaled = if (sc < 1f) Bitmap.createScaledBitmap(raw, (raw.width * sc).toInt().coerceAtLeast(1), (raw.height * sc).toInt().coerceAtLeast(1), true) else raw
        return scaled.copy(Bitmap.Config.ARGB_8888, true)
    }

    /**
     * If the picture already has real transparency it is returned untouched. Otherwise the white / grey-checker
     * (or near-black) background connected to the border, and the empty centre of a ring frame, is removed.
     */
    fun makeBackgroundTransparent(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var transparent = 0
        for (p in px) if ((p ushr 24) < 200) transparent++
        if (transparent > px.size * 0.10) return src // already truly transparent

        fun lum(p: Int) = maxOf((p shr 16) and 255, (p shr 8) and 255, p and 255)
        fun sat(p: Int): Int {
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            return maxOf(r, g, b) - minOf(r, g, b)
        }
        fun minc(p: Int) = minOf((p shr 16) and 255, (p shr 8) and 255, p and 255)

        // border colour decides between "light" and "dark" backgrounds
        val borderLums = ArrayList<Int>()
        for (x in 0 until w) { borderLums += lum(px[x]); borderLums += lum(px[(h - 1) * w + x]) }
        for (y in 0 until h) { borderLums += lum(px[y * w]); borderLums += lum(px[y * w + w - 1]) }
        borderLums.sort()
        val darkBg = borderLums[borderLums.size / 2] <= 70
        val isBg: (Int) -> Boolean =
            if (darkBg) { p -> lum(p) <= 52 } else { p -> minc(p) >= 205 && sat(p) <= 22 }

        val bg = BooleanArray(w * h)
        val queue = ArrayDeque<Int>()
        fun seed(i: Int) { if (!bg[i] && isBg(px[i])) { bg[i] = true; queue.add(i) } }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
        // seed the central hole of a ring frame
        val cx = w / 2
        val cy = (h * 0.47f).toInt()
        val r = (minOf(w, h) * 0.10f).toInt()
        for (dy in -r..r step 2) for (dx in -r..r step 2) {
            val x = cx + dx; val y = cy + dy
            if (x in 0 until w && y in 0 until h) seed(y * w + x)
        }
        while (queue.isNotEmpty()) {
            val i = queue.poll()!!
            val x = i % w; val y = i / w
            if (x > 0) seed(i - 1)
            if (x < w - 1) seed(i + 1)
            if (y > 0) seed(i - w)
            if (y < h - 1) seed(i + w)
        }

        val out = IntArray(w * h)
        for (i in px.indices) {
            out[i] = if (bg[i]) 0 else (px[i] or (0xFF shl 24))
        }
        // soften the 1px edge so there is no hard white/black halo
        val soft = out.copyOf()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (out[i] != 0) {
                var empty = 0
                if (out[i - 1] == 0) empty++
                if (out[i + 1] == 0) empty++
                if (out[i - w] == 0) empty++
                if (out[i + w] == 0) empty++
                if (empty >= 2) soft[i] = (out[i] and 0x00FFFFFF) or (0x80 shl 24)
            }
        }
        val res = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        res.setPixels(soft, 0, w, 0, 0, w, h)
        return res
    }

    /** Dominant saturated hue of the opaque pixels, as an ARGB Long. */
    fun dominantGlow(bmp: Bitmap): Long {
        val w = bmp.width; val h = bmp.height
        val hist = FloatArray(24)
        val hsv = FloatArray(3)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = bmp.getPixel(x, y)
                if ((p ushr 24) > 200) {
                    android.graphics.Color.colorToHSV(p, hsv)
                    if (hsv[1] > 0.45f && hsv[2] > 0.45f) {
                        hist[((hsv[0] / 360f) * 24f).toInt().coerceIn(0, 23)] += hsv[1] * hsv[2]
                    }
                }
                x += 3
            }
            y += 3
        }
        var best = 0
        for (i in hist.indices) if (hist[i] > hist[best]) best = i
        if (hist[best] <= 0f) return 0xFFFFC107L
        val hue = (best + 0.5f) / 24f * 360f
        val c = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.85f, 1f))
        return (c.toLong() and 0xFFFFFFFFL) or 0xFF000000L
    }
}

