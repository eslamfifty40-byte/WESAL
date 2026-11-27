package com.wesal.messenger.presentation.components

import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.painterResource
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.core.util.WesalShareUtils
import com.wesal.messenger.core.util.ResolvedFrame
import com.wesal.messenger.core.util.WesalFrameLibrary
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log
import android.widget.Toast
import com.wesal.messenger.BuildConfig
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.ContentCopy
import coil.compose.AsyncImage
import coil.compose.LocalImageLoader
import coil.request.ImageRequest
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.theme.WesalNameplateColorPreset
import com.wesal.messenger.core.util.AlphaBounds
import com.wesal.messenger.core.util.FrameAnimationPolicy
import com.wesal.messenger.core.util.FrameGlowDisc
import com.wesal.messenger.core.util.FrameMotionTier
import com.wesal.messenger.core.util.FramedCutoutLayout
import com.wesal.messenger.core.util.FramedCutoutTransform
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.core.util.NeonColorPreset
import com.wesal.messenger.core.util.isCutoutBounds
import com.wesal.messenger.domain.model.MessageStatus

@Composable
fun wesalNeonTextStyle(
    baseStyle: TextStyle,
    neonColorKey: String,
    glowIntensity: Float,
    fallbackColor: Color = WesalColors.current.textPrimary
): TextStyle {
    val palette = WesalColors.current
    val preset = NeonColorPreset.fromKey(neonColorKey)
    if (preset == NeonColorPreset.NONE) {
        return baseStyle.copy(color = fallbackColor)
    }
    val glow = glowIntensity.coerceIn(0.25f, 1.0f)
    val resolvedColor = if (palette.isDark) preset.textColorDarkBg else preset.textColorLightBg
    val haloColor = if (preset == NeonColorPreset.BLACK) Color(0xFF00E5FF) else preset.glowColor
    val shadowColor = if (palette.isDark) {
        haloColor.copy(alpha = (0.70f + 0.30f * glow).coerceIn(0f, 1f))
    } else {
        haloColor.copy(alpha = (0.45f * glow).coerceIn(0f, 0.72f))
    }
    return baseStyle.copy(
        color = resolvedColor,
        shadow = Shadow(
            color = shadowColor,
            offset = Offset.Zero,
            blurRadius = if (palette.isDark) 28f * glow else 14f * glow
        )
    )
}

/**
 * Round 11 (item 7): shared state between the opaque-fit [ContentScale]
 * and [Alignment] in WesalAvatar. Coil measures the painter with the
 * content scale first and aligns it afterwards within the same pass, so
 * the scale computation publishes the fit's vertical bias here and the
 * alignment consumes it. Defaults to a slightly-high bias before the
 * first measure lands.
 */
private class OpaqueFitHolder {
    @Volatile
    var biasY: Float = -0.5f

    fun compute(srcSize: Size, dstSize: Size): Float {
        val fit = FramedCutoutLayout.opaqueSubjectFit(
            srcSize.width, srcSize.height, dstSize.width, dstSize.height
        )
        biasY = fit.biasY
        return fit.scale
    }

    fun align(size: IntSize, space: IntSize): IntOffset {
        val x = (space.width - size.width) / 2
        val y = ((space.height - size.height) * (biasY + 1f) / 2f).roundToInt()
        return IntOffset(x, y)
    }
}

/**
 * Round 11 (item 6): lets a child of a horizontally padded column span the
 * FULL screen width (the profile/detail cover bands): the element is
 * measured wider by twice [horizontalPadding] and shifted left by it, so
 * its visual edges land exactly on the screen edges while its siblings
 * keep the page padding.
 */
/**
 * Round 13 (Items B + H): the ONE color set for text inputs on themed
 * surfaces. Text, cursor, label, placeholder and container ALL come
 * from the active [WesalPalette] — dark text on light surfaces in
 * light mode, light text on dark surfaces in dark mode — never from
 * the ambient Material scheme (whose container slots the theme does
 * not fully remap when an admin global appearance or a personal preset
 * is active) and never hard-coded. Every OutlinedTextField on a themed
 * surface passes `colors = wesalFieldColors()`; the only exceptions
 * are fields painted over media/camera (story + camera captions) and
 * the login flow's fixed branded dark artwork, where white is the
 * design on an always-dark background.
 */
@Composable
fun wesalFieldColors(): TextFieldColors {
    val palette = WesalColors.current
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = palette.textPrimary,
        unfocusedTextColor = palette.textPrimary,
        disabledTextColor = palette.textMuted,
        errorTextColor = palette.textPrimary,
        focusedContainerColor = palette.surfaceElevated,
        unfocusedContainerColor = palette.surfaceElevated,
        disabledContainerColor = palette.surfaceElevated,
        errorContainerColor = palette.surfaceElevated,
        cursorColor = palette.turquoise,
        errorCursorColor = palette.error,
        focusedBorderColor = palette.turquoise,
        unfocusedBorderColor = palette.border,
        disabledBorderColor = palette.border,
        errorBorderColor = palette.error,
        focusedLabelColor = palette.textSecondary,
        unfocusedLabelColor = palette.textSecondary,
        disabledLabelColor = palette.textMuted,
        errorLabelColor = palette.error,
        focusedPlaceholderColor = palette.textMuted,
        unfocusedPlaceholderColor = palette.textMuted,
        disabledPlaceholderColor = palette.textMuted,
        errorPlaceholderColor = palette.textMuted
    )
}

fun Modifier.pageBleed(horizontalPadding: Dp): Modifier =
    this.then(
        Modifier.layout { measurable, constraints ->
            val extra = (horizontalPadding * 2).roundToPx()
            val widened = constraints.copy(
                minWidth = (constraints.minWidth + extra).coerceAtLeast(0),
                maxWidth = if (constraints.maxWidth == Constraints.Infinity) {
                    Constraints.Infinity
                } else {
                    constraints.maxWidth + extra
                }
            )
            val placeable = measurable.measure(widened)
            // Report the ORIGINAL width to the parent (the column must not
            // think the child grew); the wider placeable is placed shifted
            // left so it visually spans the full screen width.
            val reportedWidth = if (constraints.maxWidth == Constraints.Infinity) {
                placeable.width
            } else {
                constraints.maxWidth
            }
            layout(reportedWidth, placeable.height) {
                placeable.placeRelative(-horizontalPadding.roundToPx(), 0)
            }
        }
    )

@Composable
fun WesalAvatar(
    imageUrl: String,
    name: String,
    size: Dp = 50.dp,
    isOnline: Boolean = false,
    frameId: String = "",
    frameExpiresAt: Long = 0L,
    glowEnabled: Boolean = true,
    isCutout: Boolean = false,
    // Round 9 (item 7): the photo's square already carries the owner's manual
    // crop-editor framing — render that composition as-is (normal-photo path
    // over the glow disc) and never let the alpha-bounds re-fit override it.
    isUserFramed: Boolean = false,
    iconUrl: String = "",
    neonColor: String = "",
    neonGlowIntensity: Float = 0.7f,
    fitInsideBounds: Boolean = false,
    @DrawableRes fallbackResId: Int? = null
) {
    val context = LocalContext.current
    val palette = WesalColors.current
    val imageModel = remember(imageUrl) { wesalImageModel(imageUrl) }
    val imageCacheKey = remember(imageUrl) { wesalImageCacheKey(imageUrl) }
    val cachedCutout = remember(imageCacheKey) { getCachedCutoutAlpha(imageCacheKey) }
    var loadFailed by remember(imageUrl) { mutableStateOf(false) }
    var detectedCutout by remember(imageUrl, isCutout, cachedCutout) {
        mutableStateOf(isCutout || cachedCutout == true)
    }
    // Round-3: measured alpha bounds for this photo (published by AlphaBoundsTransformation
    // into the bounds cache, or analysed in onSuccess as a fallback). A VERIFIED cutout
    // verdict here — not the stored flag — is what unlocks the bounds-driven fit and the
    // over-frame head strip, so a false positive can never paint a rectangle over a crest.
    // Round-4: the cached record also carries the SOURCE bitmap's pixel dimensions,
    // published by the transformation from its input bitmap. Round 3 read the aspect
    // from the delivered drawable instead — the legacy request's non-square box
    // (aspect 0.832 for a square source), which stretched the person ~1.20x vertically.
    val cachedBoundsRecord = remember(imageCacheKey) { getCachedAlphaBounds(imageCacheKey) }
    var detectedBoundsRecord by remember(imageUrl, cachedBoundsRecord) {
        mutableStateOf(cachedBoundsRecord)
    }
    val activeFrame = remember(frameId, frameExpiresAt, WesalFrameLibrary.docs) {
        WesalFrameLibrary.resolve(frameId, frameExpiresAt)
    }
    val iconModel = remember(iconUrl) { wesalImageModel(iconUrl) }
    val iconCacheKey = remember(iconUrl) { wesalImageCacheKey(iconUrl) }
    val neonPreset = NeonColorPreset.fromKey(neonColor)
    val avatarGlowColor = when {
        activeFrame != null && glowEnabled -> activeFrame.glow
        neonPreset != NeonColorPreset.NONE && glowEnabled -> neonPreset.glowColor
        else -> null
    }
    val baseGlowAlpha = if (neonPreset != NeonColorPreset.NONE) {
        0.38f + 0.34f * neonGlowIntensity.coerceIn(0.2f, 1.0f)
    } else {
        0.52f
    }
    // §5.8 per-frame glow controls: intensity scales the glow halo, speed scales durations.
    val frameGlowIntensity = (activeFrame?.glowIntensity ?: 1.0f).coerceIn(0f, 1f)
    val frameGlowSpeed = (activeFrame?.glowSpeed ?: 1.0f).coerceIn(0.5f, 2f)

    // §5.9 (round-4 P2-8) footprint, computed BEFORE the motion tier (round 6):
    // a framed avatar's container is 1.38x the nominal size. Callers in fixed slots
    // pass fitInsideBounds = true: the base size is pre-shrunk by the same factor so
    // the whole framed container fits exactly inside the nominal `size` footprint.
    // Unframed avatars are unaffected either way (footprintBaseSize == size).
    val footprintBaseSize = if (fitInsideBounds && activeFrame != null) size / 1.38f else size
    val containerSize = if (activeFrame != null) footprintBaseSize * 1.38f else footprintBaseSize

    // Motion tiers (round 5, FrameAnimationPolicy — the pure rules are unit-tested):
    // effective animation = the frame's resolved `animated` flag AND the admin master
    // switch (system_config/global_appearance.framesAnimationEnabled). Both inputs are
    // Compose snapshot state, so flipping either switch recomposes every avatar and
    // the change applies immediately, with no restart — the owner's on/off guarantee.
    //   NONE    (< 52dp, or animation off): fully static; no infinite transition is
    //           created at all, so scrolling lists never pay for motion (perf rule).
    //   REDUCED (52..<96dp): glow pulse only — halo + glow-disc breathing.
    //   FULL    (>= 96dp: profile, detail, settings): glow pulse + shine sweep around
    //           the ring + sparkle accents (FrameShineSparkles below).
    // Round 6 (item 10d): the tier is sized by the REAL on-screen footprint
    // (containerSize), not the nominal `size`. A framed avatar's nominal size
    // understates what is actually drawn by 1.38x, so the Settings card avatar
    // (nominal 76dp, drawn at ~105dp) was stuck in the glow-only REDUCED tier and
    // the owner saw "no animation at all" on a large avatar; chat-row framed
    // avatars (nominal ~52dp, drawn ~72dp) sat in NONE and were fully frozen.
    // With the footprint measure: profile/settings/detail are FULL, chat rows are
    // REDUCED (cheap glow breathing only — no sweep/sparkles/fan in lists), and
    // only genuinely tiny avatars (< 52dp on screen) stay NONE.
    // Round 13 (Addendum K): on low-RAM devices (ActivityManager
    // .isLowRamDevice — the Android Go / old-device class the owner
    // reports heaviness on), framed avatars render their STATIC first
    // frame: no infinite transitions are created at all, so scrolling
    // lists never pay for motion the hardware can't afford. Normal
    // devices are unaffected.
    val lowRamDevice = remember(context) {
        (context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as? android.app.ActivityManager)?.isLowRamDevice == true
    }
    val effectiveFrameAnimated = FrameAnimationPolicy.effectiveAnimated(
        frameAnimated = activeFrame?.animated == true,
        masterEnabled = WesalFrameLibrary.framesAnimationEnabled
    ) && !lowRamDevice
    val motionTier = FrameAnimationPolicy.motionTier(
        sizeDp = containerSize.value,
        effectiveAnimated = effectiveFrameAnimated
    )
    val isAnimatedFrame = motionTier != FrameMotionTier.NONE
    val fullMotion = motionTier == FrameMotionTier.FULL
    // The corner icon badge keeps its own historical gate (pulse from 72dp up),
    // independent of the frame tiers above.
    val allowMotion = size >= 72.dp
    val infiniteTransition = if (isAnimatedFrame) rememberInfiniteTransition(label = "wesal_frame_anim") else null
    val isRotateFanStyle = activeFrame?.animationStyle == "ROTATE_AURA" || activeFrame?.animationStyle == "ROTATE_FAN"
    val animPulse by (infiniteTransition?.animateFloat(
        initialValue = 0.985f,
        targetValue = 1.025f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (1500 / frameGlowSpeed).toInt().coerceAtLeast(150)),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    ) ?: remember { mutableStateOf(1f) })
    val animAlpha by (infiniteTransition?.animateFloat(
        initialValue = 0.80f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (1300 / frameGlowSpeed).toInt().coerceAtLeast(130)),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    ) ?: remember { mutableStateOf(1f) })
    val animRotation by (infiniteTransition?.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (2800 / frameGlowSpeed).toInt().coerceAtLeast(280), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotate"
    ) ?: remember { mutableStateOf(0f) })
    // Slow shine-sweep rotation (FULL tier only): one revolution per ~6.2s drives the
    // highlight wedge travelling around the ring in FrameShineSparkles; its phase also
    // clocks the sparkle twinkles, so no extra animation value is needed.
    val animSweep by (if (fullMotion) {
        infiniteTransition?.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = (6200 / frameGlowSpeed).toInt().coerceAtLeast(620), easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "sweep"
        )
    } else {
        null
    } ?: remember { mutableStateOf(0f) })

    // (footprintBaseSize / containerSize are computed above the motion tier —
    // see the round-6 note there.)
    // Measured aperture used verbatim (spec §5.5): the old x1.07 inflation is gone — the frame
    // artwork is drawn OVER the photo, so a photo disc of exactly the aperture size meets the
    // artwork's ring cleanly, matching the measured reference geometry.
    val effectiveAperture = activeFrame?.apertureDiameterFraction ?: 0.62f
    val innerAvatarSize = if (activeFrame == null) {
        footprintBaseSize
    } else {
        containerSize * effectiveAperture.coerceIn(0.46f, 0.80f)
    }
    val apertureOffsetY = if (activeFrame == null) {
        0.dp
    } else {
        containerSize * (activeFrame.apertureCenterYFraction - 0.50f)
    }
    // Explicit decode sizes: Coil must downscale to the composable's pixel size instead of
    // decoding full-size sources (the royal artwork is 1536x1536) for every avatar.
    val density = LocalDensity.current
    val containerPx = with(density) { containerSize.roundToPx() }.coerceAtLeast(1)
    val innerPx = with(density) { innerAvatarSize.roundToPx() }.coerceAtLeast(1)
    val iconPx = with(density) { (innerAvatarSize * 0.36f).coerceIn(16.dp, 26.dp).roundToPx() }.coerceAtLeast(1)
    // Inside a frame, a background-removed (cutout) photo renders like the reference
    // composite — an opaque photo filling the circle — so cutout styling applies ONLY to
    // unframed avatars.
    val effectiveCutout = detectedCutout && activeFrame == null
    // Verified alpha bounds for this photo (round-3): the ONLY evidence allowed to drive
    // the bounds fit and the over-frame head strip.
    val verifiedBounds = detectedBoundsRecord?.bounds?.takeIf { isCutoutBounds(it) }
    // Owner's framed-cutout geometry (round-6 spec, item 10 — supersedes the
    // round-3/4 head pop-out, which the owner rejected on device: figure lifted,
    // body shrunk, dark void inside the ring): FramedCutoutLayout now places the
    // person HEIGHT-FIRST and BOTTOM-ANCHORED inside the aperture — torso fills
    // the ring from its bottom up and continues behind the frame's bottom
    // artwork, head near the top INSIDE the ring (0.03 x aperture clearance —
    // round 9 re-measured it on the owner's definitive reference composite),
    // so no frame ornament ever covers face/hair. The over-frame strip survives
    // only as a crown-margin sliver (see FramedCutoutLayout).
    // The aperture window opens for a verified cutout OR the legacy flag; the
    // strip window (below) opens for verified bounds ONLY.
    // Round 9 (item 7): a user-framed photo's baked composition renders
    // through the normal-photo branch below (square source into the
    // aperture disc = exact fit, glow disc behind its transparent
    // margins), exactly as framed in the crop editor.
    // Round 13 (Addendum F — PROVEN divergence): that exclusion used to
    // apply to CUTOUT photos too, so the SAME photo in the SAME frame
    // rendered two ways — every real surface passes isUserFramed (the
    // Settings header squeezed the figure into the aperture disc,
    // smaller and lower, glow gap above the head), while the
    // subscription dialog's «معاينة حية للطلب» preview does NOT pass it
    // and took the bounds path (figure fills the ring, head high — the
    // owner's ACCEPTED output). The cutout path therefore no longer
    // excludes user-framed photos: a verified/detected cutout takes the
    // bounds geometry at EVERY site, preview included. isUserFramed
    // keeps governing only OPAQUE photos (no cutout evidence), whose
    // editor composition still renders as-is.
    val framedCutout = activeFrame != null && (verifiedBounds != null || detectedCutout)
    // Corner-alpha probe for uncached, unflagged framed photos (drives cutout detection).
    val needSoftwareCheck = activeFrame != null && cachedCutout == null && !isCutout
    val popOutStrip = containerSize * 0.10f
    val popOutStripPx = with(density) { popOutStrip.roundToPx() }.coerceAtLeast(1)
    val apertureOffsetYPx = with(density) { apertureOffsetY.roundToPx() }
    val apertureRadiusPx = innerPx / 2f
    // Bounds-driven placement of the PERSON (not the padded canvas) inside the aperture:
    // round-6 height-first, bottom-anchored fit (see FramedCutoutLayout) — the person
    // spans 1.22 x the aperture height, bottom anchored below the aperture's bottom
    // edge, head just inside its top edge.
    // The aspect comes from the bounds record's SOURCE dimensions (published by
    // AlphaBoundsTransformation from its input bitmap), never from a delivered drawable.
    // Null until verified bounds are known; the legacy full-canvas rendering covers the
    // first frame(s) until then (bounds cache makes later binds synchronous).
    val cutoutTransform: FramedCutoutTransform? = if (verifiedBounds != null && activeFrame != null) {
        detectedBoundsRecord?.let { record ->
            FramedCutoutLayout.compute(
                containerPx = containerPx.toFloat(),
                apertureCenterYFraction = activeFrame.apertureCenterYFraction,
                apertureDiameterFraction = effectiveAperture,
                bounds = verifiedBounds,
                imageWidthPx = record.sourceWidthPx,
                imageHeightPx = record.sourceHeightPx
            )
        }
    } else null

    // Round-4 observability (DEBUG only): every round-3 fallback was silent, which made
    // on-device failures indistinguishable. Log the decision once per (url, record,
    // path) change so logcat shows WHY an avatar took the legacy path.
    remember(imageCacheKey, detectedBoundsRecord, cutoutTransform != null) {
        if (BuildConfig.DEBUG && activeFrame != null && imageUrl.isNotBlank()) {
            val record = detectedBoundsRecord
            val reason = when {
                cutoutTransform != null ->
                    "path=BOUNDS bounds=[${verifiedBounds?.left},${verifiedBounds?.top}," +
                        "${verifiedBounds?.right},${verifiedBounds?.bottom}] " +
                        "src=${record?.sourceWidthPx}x${record?.sourceHeightPx} " +
                        "stripPx=${cutoutTransform.stripPx} scale=${cutoutTransform.scale}"
                record == null ->
                    "path=LEGACY reason=no-bounds-record (not yet analysed, or analysis failed)"
                record.bounds === AlphaBounds.NOT_A_CUTOUT ->
                    "path=LEGACY reason=verdict-not-a-cutout (cached NOT_A_CUTOUT)"
                verifiedBounds == null ->
                    "path=LEGACY reason=gate-rejected bounds=[${record.bounds.left},${record.bounds.top}," +
                        "${record.bounds.right},${record.bounds.bottom}] tf=${record.bounds.transparentFraction}"
                else ->
                    "path=LEGACY reason=verified-bounds-but-no-transform"
            }
            Log.d("WesalAvatar", "key=$imageCacheKey $reason")
        }
    }

    // Shared success handling for every framed photo request: pick up the alpha-bounds
    // record — published by AlphaBoundsTransformation during the load, or analysed here
    // from the delivered bitmap when the cache has no entry yet (e.g. first load raced
    // the transformation).
    fun onPhotoLoaded(drawable: Drawable) {
        val fromCache = getCachedAlphaBounds(imageCacheKey)
        if (fromCache != null) {
            if (detectedBoundsRecord != fromCache) detectedBoundsRecord = fromCache
        } else if (activeFrame != null) {
            val bmp = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            if (bmp != null) {
                analyzeAndPublishBitmapBounds(bmp, imageCacheKey)?.let { detectedBoundsRecord = it }
            }
        }
    }
    val avatarGlowAlpha = (if (isRotateFanStyle) {
        baseGlowAlpha.coerceIn(0.25f, 0.92f)
    } else {
        (baseGlowAlpha * animAlpha).coerceIn(0.1f, 0.95f)
    } * if (activeFrame != null) frameGlowIntensity else 1f).coerceIn(0f, 0.95f)

    // Glow-DISC breathing (round 5): the opaque FrameGlowDisc behind the photo pulses
    // gently by scaling the intensity fed to discStops() with the pulse phase
    // (animAlpha runs 0.80..1.0, mapped to a 0.80..1.0 brightness factor). Applies in
    // the REDUCED and FULL tiers; in the NONE tier the factor is a constant 1f, so
    // the disc renders exactly as before. Opacity is untouched — every disc stop
    // stays alpha 0xFF (FrameGlowDisc invariant).
    val discPulseFactor = if (isAnimatedFrame) {
        0.80f + 0.20f * ((animAlpha - 0.80f) / 0.20f).coerceIn(0f, 1f)
    } else {
        1f
    }

    @Composable
    fun FanAuraSpinner(
        frame: ResolvedFrame,
        spinnerSize: Dp,
        centerOffsetY: Dp,
        includeBlades: Boolean
    ) {
        val goldTint = Color(0xFFFFD54F)
        Canvas(
            modifier = Modifier
                .size(spinnerSize)
                .offset(y = centerOffsetY)
                .graphicsLayer {
                    rotationZ = animRotation
                    transformOrigin = TransformOrigin.Center
                    translationX = 0f
                    translationY = 0f
                }
        ) {
            val r = this.size.minDimension / 2f
            val arcRadius = r * 0.92f
            val arcTopLeft = Offset(center.x - arcRadius, center.y - arcRadius)
            val arcSize = Size(arcRadius * 2f, arcRadius * 2f)

            // 6 curved turbine/fan blades spinning smoothly in-place around the exact center
            for (blade in 0 until 6) {
                val startDeg = blade * 60f
                if (includeBlades) {
                    drawArc(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.Transparent,
                                frame.glow.copy(alpha = 0.48f * frameGlowIntensity),
                                goldTint.copy(alpha = 0.36f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = r
                        ),
                        startAngle = startDeg,
                        sweepAngle = 28f,
                        useCenter = true
                    )
                }
                drawArc(
                    color = if (blade % 2 == 0) frame.glow.copy(alpha = 0.92f * frameGlowIntensity) else goldTint.copy(alpha = 0.88f),
                    startAngle = startDeg + 4f,
                    sweepAngle = 36f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = (r * 0.085f).coerceIn(2.2f, 7f), cap = StrokeCap.Round)
                )
            }
        }
    }

    /**
     * The SVGA-style living treatment (round 5), FULL motion tier only, drawn OVER the
     * frame artwork and UNDER the pop-out head strip. Plain Compose Canvas — no new
     * dependencies:
     *  - a soft shine wedge (sweep gradient in the frame's glow colour with a white
     *    core) travels slowly around the ring band, one revolution per sweep cycle;
     *  - seven four-point sparkles twinkle on the ring band at deterministic
     *    per-frame positions (seeded by the frame id, so every avatar wearing the
     *    same frame sparkles in the same places), phased by the same sweep clock.
     * The whole overlay exists only while the avatar is composed — when it leaves
     * composition the infinite transition stops, so off-screen avatars cost nothing.
     */
    @Composable
    fun FrameShineSparkles(frame: ResolvedFrame) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val ringCenter = Offset(
                x = this.size.width / 2f,
                y = this.size.height / 2f + apertureOffsetYPx
            )
            val ringRadius = apertureRadiusPx * 1.24f
            val intensity = frameGlowIntensity.coerceIn(0f, 1f)

            // Shine sweep: one bright wedge rotating around the ring band.
            rotate(degrees = animSweep, pivot = ringCenter) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.36f to Color.Transparent,
                            0.45f to frame.glow.copy(alpha = 0.20f * intensity),
                            0.50f to Color.White.copy(alpha = 0.32f * intensity),
                            0.55f to frame.glow.copy(alpha = 0.20f * intensity),
                            0.64f to Color.Transparent,
                            1f to Color.Transparent
                        ),
                        center = ringCenter
                    ),
                    radius = ringRadius,
                    center = ringCenter,
                    style = Stroke(width = apertureRadiusPx * 0.34f)
                )
            }

            // Sparkle accents: four-point stars with a concave waist.
            fun sparkle(at: Offset, s: Float, color: Color) {
                val path = Path().apply {
                    moveTo(at.x, at.y - s)
                    quadraticBezierTo(at.x, at.y, at.x + s, at.y)
                    quadraticBezierTo(at.x, at.y, at.x, at.y + s)
                    quadraticBezierTo(at.x, at.y, at.x - s, at.y)
                    quadraticBezierTo(at.x, at.y, at.x, at.y - s)
                    close()
                }
                drawPath(path, color)
            }
            val seed = frame.id.hashCode()
            val phase = animSweep * (Math.PI.toFloat() / 60f) // 3 twinkle cycles per revolution
            for (i in 0 until 7) {
                val h = (seed * 31 + i * 137) and 0xFFFF
                val angRad = Math.toRadians(((h % 360).toFloat() + i * 51.43f).toDouble())
                val rr = ringRadius * (0.94f + ((h / 360) % 16) / 100f)
                val tw = sin(phase + i * 1.93f)
                if (tw <= 0.2f) continue
                val a = ((tw - 0.2f) / 0.8f).coerceIn(0f, 1f) * 0.85f * intensity
                if (a <= 0.01f) continue
                val s = apertureRadiusPx * 0.09f * (0.7f + 0.3f * tw)
                sparkle(
                    at = Offset(
                        x = ringCenter.x + (rr * cos(angRad)).toFloat(),
                        y = ringCenter.y + (rr * sin(angRad)).toFloat()
                    ),
                    s = s,
                    color = Color.White.copy(alpha = a)
                )
            }
        }
    }

    @Composable
    fun FrameLayer(frame: ResolvedFrame) {
        val animModifier = when {
            !isAnimatedFrame -> Modifier.fillMaxSize()
            isRotateFanStyle -> Modifier.fillMaxSize() // Frame crest remains stationary in-place without scaling/bouncing
            // REDUCED tier: glow pulse only — the artwork itself stays still; the
            // breathing lives in the halo + glow disc (avatarGlowAlpha/discPulseFactor).
            motionTier == FrameMotionTier.REDUCED -> Modifier.fillMaxSize()
            frame.animationStyle == "SHIMMER" -> Modifier.fillMaxSize().alpha(animAlpha).graphicsLayer {
                scaleX = animPulse
                scaleY = animPulse
                transformOrigin = TransformOrigin.Center
            }
            else -> Modifier.fillMaxSize().graphicsLayer {
                scaleX = animPulse
                scaleY = animPulse
                transformOrigin = TransformOrigin.Center
            }
        }
        // Intrinsically animated frame files (GIF / animated WebP uploads): the main
        // Coil loader animates them by itself, ignoring every switch. When the frame's
        // effective animation is OFF, route the load through WesalStillImageLoader
        // (no animated decoders → first frame only) so off really means still.
        val frameImageLoader = if (effectiveFrameAnimated) {
            LocalImageLoader.current
        } else {
            WesalStillImageLoader.get(context)
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (isAnimatedFrame && fullMotion && isRotateFanStyle) {
                FanAuraSpinner(
                    frame = frame,
                    spinnerSize = (innerAvatarSize * 1.12f).coerceAtMost(containerSize),
                    centerOffsetY = apertureOffsetY,
                    includeBlades = false
                )
            }
            if (frame.imageUrl.isNotBlank()) {
                val frameModel = remember(frame.imageUrl) { wesalImageModel(frame.imageUrl) }
                val frameKey = remember(frame.imageUrl) { wesalImageCacheKey(frame.imageUrl) }
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(frameModel)
                        .size(containerPx)
                        .apply { if (frameKey != null) memoryCacheKey(frameKey) }
                        .crossfade(false)
                        .build(),
                    contentDescription = null,
                    imageLoader = frameImageLoader,
                    modifier = animModifier,
                    contentScale = ContentScale.Fit
                )
            } else {
                // Built-in artwork goes through Coil with an explicit size too, so a small
                // list avatar never full-decodes the 1536x1536 source.
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(frame.resId)
                        .size(containerPx)
                        .crossfade(false)
                        .build(),
                    contentDescription = null,
                    imageLoader = frameImageLoader,
                    modifier = animModifier,
                    contentScale = ContentScale.Fit
                )
            }
        }
    }

    // LEGACY pre-bounds rendering for framed CUTOUT photos (used only until the
    // alpha-bounds record exists, or for flag-only cutouts): the photo is rendered
    // ONCE virtually — a box of aperture width and (aperture height + strip) height, bottom
    // aligned with the aperture, Crop/TopCenter — and shown through two clip windows of that
    // same rendering: the aperture circle (under the frame) and the strip above the
    // aperture's top edge (over the frame). Same scale/offset in both windows, so the person
    // is one coherent figure; the cutout's transparency guarantees no rectangular
    // artifacts in the strip. The bounds-driven layer below is the round-6 path.
    val apertureClipShape = GenericShape { size, _ ->
        val cx = size.width / 2f
        val cy = size.height / 2f + apertureOffsetYPx
        addOval(Rect(cx - apertureRadiusPx, cy - apertureRadiusPx, cx + apertureRadiusPx, cy + apertureRadiusPx))
    }
    // Round-6: the strip window's height comes from the bounds transform itself,
    // which now returns only the crown-margin sliver — the head renders INSIDE
    // the aperture, so this window normally shows nothing; it exists so a
    // feather of hair at the box top is never shaved by the aperture edge. This
    // shape is only used when a transform exists; the fixed-strip fallback is
    // never actually rendered.
    val stripWindowPx = cutoutTransform?.stripPx ?: popOutStripPx.toFloat()
    val popOutStripClipShape = GenericShape { size, _ ->
        val left = (size.width - innerPx) / 2f
        val apertureTop = size.height / 2f + apertureOffsetYPx - apertureRadiusPx
        addRect(Rect(left, apertureTop - stripWindowPx, left + innerPx, apertureTop))
    }

    @Composable
    fun PopOutPhotoLayer(clipShape: Shape, withCallbacks: Boolean) {
        Box(
            modifier = Modifier.fillMaxSize().clip(clipShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(width = innerAvatarSize, height = innerAvatarSize + popOutStrip)
                    .offset(y = apertureOffsetY - popOutStrip / 2),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(imageModel)
                        // Round-4: SQUARE request. The round-3 non-square size
                        // (innerPx x innerPx+strip) forced the decode into the
                        // aperture-box aspect (0.832 for a square source), which the
                        // old code then mistook for the source aspect. A square box
                        // keeps the decoded bitmap at the source's natural aspect;
                        // ContentScale.Crop below does the window fitting.
                        .size(innerPx + popOutStripPx)
                        // Suffixed key: the pop-out request must not collide with the
                        // square request of the normal branch (cross-size resampling).
                        .apply { if (imageCacheKey != null) memoryCacheKey(imageCacheKey + "#popout") }
                        .crossfade(false)
                        .transformations(AlphaBoundsTransformation(imageCacheKey))
                        .apply { if (withCallbacks && needSoftwareCheck && isAnimatedFrame) allowHardware(false) }
                        .build(),
                    contentDescription = name,
                    onError = if (withCallbacks) ({ loadFailed = true }) else null,
                    onSuccess = if (withCallbacks) {
                        { st ->
                            onPhotoLoaded(st.result.drawable)
                            if (needSoftwareCheck) {
                                val bmp = (st.result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                                val probed: Boolean? = if (bmp != null && bmp.width > 8 && bmp.height > 8) {
                                    if (!bmp.hasAlpha()) {
                                        false
                                    } else {
                                        runCatching {
                                            val c1 = (bmp.getPixel(1, 1) ushr 24) < 20
                                            val c2 = (bmp.getPixel(bmp.width - 2, 1) ushr 24) < 20
                                            c1 && c2
                                        }.getOrNull()
                                    }
                                } else {
                                    null
                                }
                                if (probed != null) {
                                    detectedCutout = probed
                                    putCachedCutoutAlpha(imageCacheKey, probed)
                                }
                            }
                        }
                    } else null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter
                )
            }
        }
    }

    // Bounds-driven photo layer (round-3, round-6 placement): the SAME clip-window
    // architecture as PopOutPhotoLayer, but the bitmap is placed by the measured
    // alpha-bounds transform (round 6: person fills the aperture from its bottom,
    // head inside the ring) instead of ContentScale.Crop over the padded canvas.
    // ContentScale.FillBounds is exact here: drawW/drawH are computed
    // from the bitmap's own aspect, so nothing is distorted.
    @Composable
    fun BoundsPhotoLayer(clipShape: Shape, transform: FramedCutoutTransform, withCallbacks: Boolean) {
        Box(modifier = Modifier.fillMaxSize().clip(clipShape)) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageModel)
                    .size(
                        transform.drawWidthPx.roundToInt().coerceAtLeast(1),
                        transform.drawHeightPx.roundToInt().coerceAtLeast(1)
                    )
                    .apply { if (imageCacheKey != null) memoryCacheKey(imageCacheKey + "#popout") }
                    .crossfade(false)
                    .transformations(AlphaBoundsTransformation(imageCacheKey))
                    .build(),
                contentDescription = name,
                onError = if (withCallbacks) ({ loadFailed = true }) else null,
                onSuccess = if (withCallbacks) {
                    { st -> onPhotoLoaded(st.result.drawable) }
                } else null,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            transform.offsetXPx.roundToInt(),
                            transform.offsetYPx.roundToInt()
                        )
                    }
                    .size(
                        width = with(density) { transform.drawWidthPx.toDp() },
                        height = with(density) { transform.drawHeightPx.toDp() }
                    ),
                contentScale = ContentScale.FillBounds
            )
        }
    }

    Box(
        modifier = Modifier
            .size(containerSize)
            .then(
                if (avatarGlowColor != null) {
                    Modifier.drawBehind {
                        val glowCenter = Offset(
                            x = center.x,
                            y = center.y + apertureOffsetY.toPx()
                        )
                        val glowRadius = if (activeFrame != null) {
                            innerAvatarSize.toPx() * 0.58f
                        } else {
                            this.size.minDimension * 0.50f
                        }
                        val edgeAlpha = if (activeFrame != null) 0.44f else 0.70f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    avatarGlowColor.copy(alpha = avatarGlowAlpha * edgeAlpha),
                                    avatarGlowColor.copy(alpha = avatarGlowAlpha * 0.14f),
                                    Color.Transparent
                                ),
                                center = glowCenter,
                                radius = glowRadius
                            ),
                            radius = glowRadius,
                            center = glowCenter
                        )
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        if (activeFrame != null && isAnimatedFrame && fullMotion && isRotateFanStyle) {
            FanAuraSpinner(
                frame = activeFrame,
                spinnerSize = containerSize * 0.94f,
                centerOffsetY = apertureOffsetY,
                includeBlades = true
            )
        }
        // OPAQUE photos in a frame (alpha-bounds verdict NOT_A_CUTOUT —
        // e.g. the crop editor's glow-background output) get the
        // reference-composite treatment so the figure lands at the same
        // 1.22×-aperture proportions as a bounds-fitted cutout instead of
        // shrinking to the plain disc fill. Round 10 zoomed the whole
        // CANVAS ×1.22 (TopCenter); round 11 (item 7) fits the SUBJECT
        // instead — see FramedCutoutLayout.opaqueSubjectFit. User-framed
        // photos (photoUserFramed) are EXCLUDED — their saved square
        // already IS the owner's composition (round-9 WYSIWYG) and must
        // render as-is; unframed avatars keep plain Crop.
        val framedOpaqueZoom = activeFrame != null && !isUserFramed && !framedCutout
        // Round 11 (item 7): the SUBJECT-aware fit (FramedCutoutLayout
        // .opaqueSubjectFit) supersedes the round-10 canvas zoom — measured
        // on the owner's r10 screenshot, the canvas zoom left the figure at
        // ~0.84x aperture vs the reference 1.22x, because the figure only
        // occupies ~0.70 of the editor canvas. The fit scales/positions by
        // the subject model and reproduces the reference split (figure
        // 1.22x aperture, head clearance 0.03, bottom overflow 0.25).
        val opaqueFitHolder = remember { OpaqueFitHolder() }
        val framedOpaqueContentScale = remember(opaqueFitHolder) {
            object : ContentScale {
                override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor {
                    val factor = opaqueFitHolder.compute(srcSize, dstSize)
                    return ScaleFactor(factor, factor)
                }
            }
        }
        val framedOpaqueAlignment = remember(opaqueFitHolder) {
            Alignment { size, space, _ -> opaqueFitHolder.align(size, space) }
        }
        // The framed backing disc takes its colour from the FRAME (ResolvedFrame.glow via
        // resolve()) — never a hardcoded per-frame special case — so changing the frame
        // recolours the glow behind the photo automatically.
        val innerBackdropColor = avatarGlowColor ?: palette.turquoise
        val frameDiscGlowColor = activeFrame?.glow ?: innerBackdropColor
        Box(
            modifier = Modifier
                .size(innerAvatarSize)
                .offset(y = apertureOffsetY)
                .clip(CircleShape)
                .then(
                    when {
                        // Framed avatars sit on a FULLY OPAQUE GLOW disc (owner's spec):
                        // a luminous radial glow in the frame's own colour behind the
                        // photo — bright core behind the head/torso deepening to a
                        // near-black rim — exactly like the reference composite. Every
                        // stop is alpha 1 (FrameGlowDisc invariant): neither the profile
                        // cover nor any background can ever show through the frame's
                        // opening. Per-frame glowIntensity (§5.8) drives the core's
                        // brightness; at 0 the disc falls back to the old flat dark tint.
                        activeFrame != null -> {
                            val stops = FrameGlowDisc.discStops(
                                glowArgb = frameDiscGlowColor.toArgb().toLong() and 0xFFFFFFFFL,
                                intensity = frameGlowIntensity * discPulseFactor
                            )
                            Modifier.drawBehind {
                                // NOTE: `this.size` is required — WesalAvatar's own
                                // `size: Dp` parameter shadows DrawScope.size here.
                                val drawSize = this.size
                                val discCenter = Offset(
                                    x = drawSize.width / 2f,
                                    y = drawSize.height * FrameGlowDisc.CENTER_Y_FRACTION
                                )
                                val discRadius = drawSize.minDimension / 2f
                                drawRect(
                                    brush = Brush.radialGradient(
                                        colorStops = arrayOf(
                                            0f to Color(stops.coreArgb),
                                            FrameGlowDisc.MID_STOP_FRACTION to Color(stops.midArgb),
                                            1f to Color(stops.rimArgb)
                                        ),
                                        center = discCenter,
                                        radius = discRadius
                                    )
                                )
                            }
                        }
                        effectiveCutout || neonPreset != NeonColorPreset.NONE -> Modifier
                            .background(palette.surface)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        innerBackdropColor.copy(alpha = 0.58f),
                                        innerBackdropColor.copy(alpha = 0.24f),
                                        palette.surface
                                    )
                                )
                            )
                        else -> Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            val borderColor = if (neonPreset != NeonColorPreset.NONE) neonPreset.glowColor else palette.border
            val borderWidth = if (neonPreset != NeonColorPreset.NONE) 1.8.dp else 1.dp
            if (framedCutout && imageUrl.isNotBlank() && !loadFailed) {
                // Framed cutout: the photo itself is drawn by the pop-out layers (circle
                // window under the frame + head strip over it). Keep this disc empty so
                // only the opaque backdrop shows through the cutout's transparency.
            } else if (imageUrl.isNotBlank() && !loadFailed) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(imageModel)
                        // Round 10 (item 9): decode a little larger when the
                        // reference zoom will upscale the draw, so the 1.22x
                        // magnification does not soften large avatars.
                        .size(
                            if (framedOpaqueZoom) {
                                // Round 11 (item 7): the subject fit zooms
                                // ~1.74x past Crop for editor-style photos
                                // (vs the r10 1.22x) — decode larger so the
                                // magnification does not soften big avatars.
                                (innerPx * FramedCutoutLayout.OPAQUE_SUBJECT_DECODE_OVERSAMPLE).roundToInt().coerceAtLeast(1)
                            } else {
                                innerPx
                            }
                        )
                        .apply { if (imageCacheKey != null) memoryCacheKey(imageCacheKey) }
                        .crossfade(false)
                        // Round-3: framed requests carry the alpha-bounds transformation,
                        // which publishes the photo's measured bounds during the load
                        // (Coil hands transformations a software bitmap, so this works
                        // for static frames too — the old corner probe below is now just
                        // a legacy fallback for the boolean flag).
                        .apply { if (activeFrame != null) transformations(AlphaBoundsTransformation(imageCacheKey)) }
                        // Note: with a transformation attached, Coil decodes to a software
                        // bitmap anyway (that is what makes the bounds analysis reliable);
                        // the explicit allowHardware(false) below remains only for the
                        // legacy corner probe on animated framed avatars.
                        .apply { if (needSoftwareCheck && isAnimatedFrame) allowHardware(false) }
                        .build(),
                    contentDescription = name,
                    onError = { loadFailed = true },
                    onSuccess = { st ->
                        if (activeFrame != null) onPhotoLoaded(st.result.drawable)
                        if (needSoftwareCheck) {
                            val bmp = (st.result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                            // getPixel() throws on hardware bitmaps; a failed probe must
                            // neither crash nor poison the cache — it just stays unknown.
                            val probed: Boolean? = if (bmp != null && bmp.width > 8 && bmp.height > 8) {
                                if (!bmp.hasAlpha()) {
                                    false
                                } else {
                                    runCatching {
                                        val c1 = (bmp.getPixel(1, 1) ushr 24) < 20
                                        val c2 = (bmp.getPixel(bmp.width - 2, 1) ushr 24) < 20
                                        c1 && c2
                                    }.getOrNull()
                                }
                            } else {
                                null
                            }
                            if (probed != null) {
                                detectedCutout = probed
                                putCachedCutoutAlpha(imageCacheKey, probed)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        // Unframed + user-framed photos: ContentScale.Crop +
                        // TopCenter fills the circular opening exactly.
                        // Framed OPAQUE photos (round 10, item 9): the
                        // reference scale (Crop × 1.22) with the same
                        // TopCenter anchor — see framedOpaqueContentScale.
                        .clip(CircleShape)
                        .then(
                            if (activeFrame == null) {
                                Modifier.border(borderWidth, borderColor, CircleShape)
                            } else Modifier
                        ),
                    contentScale = if (framedOpaqueZoom) framedOpaqueContentScale else ContentScale.Crop,
                    // Round 11 (item 7): framed opaque photos align by the
                    // subject fit's bias (figure centre on the reference
                    // centre); everything else keeps TopCenter.
                    alignment = if (framedOpaqueZoom) framedOpaqueAlignment else Alignment.TopCenter
                )
            } else if (fallbackResId != null) {
                Image(
                    painter = painterResource(id = fallbackResId),
                    contentDescription = name,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .border(borderWidth, palette.emerald.copy(alpha = 0.55f), CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "W"
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(palette.emeraldDark.copy(alpha = 0.35f))
                        .border(borderWidth, if (neonPreset != NeonColorPreset.NONE) neonPreset.glowColor else palette.emerald.copy(alpha = 0.45f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = initial,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary
                    )
                }
            }
        }

        // Pop-out window 1 of 2: the framed cutout photo seen through the aperture circle,
        // UNDER the frame artwork (same rendering as the head strip below). With verified
        // alpha bounds the PERSON is fitted into the aperture by the bounds transform;
        // before bounds are known (first load) the legacy full-canvas rendering shows.
        if (framedCutout && imageUrl.isNotBlank() && !loadFailed) {
            val transform = cutoutTransform
            if (transform != null) {
                BoundsPhotoLayer(clipShape = apertureClipShape, transform = transform, withCallbacks = true)
            } else {
                PopOutPhotoLayer(clipShape = apertureClipShape, withCallbacks = true)
            }
        }

        // Frame draws around/over the circular inner aperture so wings, crown, and bottom crest remain crisp
        if (activeFrame != null) {
            FrameLayer(activeFrame)
        }

        // Living treatment (round 5): shine sweep + sparkles over the frame artwork,
        // under the pop-out head strip. FULL tier only; ROTATE_AURA frames keep their
        // own fan treatment instead (FanAuraSpinner above).
        if (activeFrame != null && fullMotion && !isRotateFanStyle) {
            FrameShineSparkles(activeFrame)
        }

        // Window 2 of 2: the SAME rendering clipped to the sliver above the
        // aperture's top edge, drawn OVER the frame. Round 6: the transform's
        // strip is only the crown margin and the head sits INSIDE the ring, so
        // this window normally renders nothing — it is a safety sliver, not a
        // pop-out (the round-3/4 head-over-ring rendering is gone: it lifted the
        // figure and left the void the owner complained about).
        // VERIFIED BOUNDS ONLY: this window paints over the frame artwork, so it must
        // never open on the stored flag alone — a false positive would leak a photo
        // rectangle over the crest. Flag-only cutouts keep the in-aperture rendering.
        val stripTransform = cutoutTransform
        if (stripTransform != null && imageUrl.isNotBlank() && !loadFailed) {
            BoundsPhotoLayer(clipShape = popOutStripClipShape, transform = stripTransform, withCallbacks = false)
        }

        if (iconUrl.isNotBlank()) {
            // Small list avatars render the icon statically (no infinite transition) —
            // same allowMotion gate as the frame animation above.
            var iconPulse = 1f
            var iconTilt = 0f
            if (allowMotion) {
                val iconTransition = rememberInfiniteTransition(label = "wesal_icon_anim")
                val pulseState by iconTransition.animateFloat(
                    initialValue = 0.94f,
                    targetValue = 1.06f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 1500),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "icon_pulse"
                )
                val tiltState by iconTransition.animateFloat(
                    initialValue = -3.5f,
                    targetValue = 3.5f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 1900),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "icon_tilt"
                )
                iconPulse = pulseState
                iconTilt = tiltState
            }
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(iconModel)
                    .size(iconPx)
                    .apply { if (iconCacheKey != null) memoryCacheKey(iconCacheKey) }
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                modifier = Modifier
                    .size((innerAvatarSize * 0.36f).coerceIn(16.dp, 26.dp))
                    .align(Alignment.BottomStart)
                    .graphicsLayer {
                        scaleX = iconPulse
                        scaleY = iconPulse
                        rotationZ = iconTilt
                        transformOrigin = TransformOrigin.Center
                    }
                    .clip(CircleShape)
                    .background(palette.surface)
                    .border(1.2.dp, palette.surface, CircleShape),
                contentScale = ContentScale.Fit
            )
        }

        if (isOnline) {
            Box(
                modifier = Modifier
                    .size((innerAvatarSize * 0.25f).coerceIn(10.dp, 18.dp))
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(palette.emerald)
                    .border(2.dp, palette.background, CircleShape)
            )
        }
    }
}

@Composable
fun WesalVerifiedBadge(
    modifier: Modifier = Modifier,
    size: Dp = 17.dp,
    badgeColor: Color = WesalColors.current.verifiedBlue,
    verificationType: String = "BLUE",
    glowColor: Color? = null,
    verificationGlowColor: String = "",
    glowEnabled: Boolean = true,
    glowIntensity: Float = 0.7f
) {
    WesalVerifiedMark(
        badgeSize = size,
        modifier = modifier,
        badgeColor = badgeColor,
        verificationType = verificationType,
        glowColor = glowColor,
        verificationGlowColor = verificationGlowColor,
        glowEnabled = glowEnabled,
        glowIntensity = glowIntensity
    )
}

@Composable
fun WesalNameWithBadge(
    name: String,
    isVerified: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    color: Color = WesalColors.current.textPrimary,
    fontWeight: FontWeight? = null,
    badgeSize: Dp = 17.dp,
    verificationType: String = "BLUE",
    neonColor: String = "",
    neonGlowIntensity: Float = 0.7f,
    neonEnabled: Boolean = true,
    glowColor: Color? = null,
    verificationGlowColor: String = "",
    verificationGlowEnabled: Boolean = true,
    verificationGlowIntensity: Float = 0.7f,
    // FIX 1 (round 5): the user's chosen nameplate icon, resolved from the
    // user-doc fields (WesalFrameLibrary.resolveIconUrl(iconId, iconUrl)).
    // Owner rule: the icon must sit beside EVERY rendered name, so this shared
    // composable renders it right after the name text (before the verified
    // badge) whenever a non-blank resolved URL is supplied. Empty = no icon.
    iconUrl: String = "",
    // Round 12: surfaces that give the name its own full-width line (the
    // subscription-request preview card) opt into wrapping instead of the
    // default single-line ellipsis used by dense list rows.
    maxNameLines: Int = 1
) {
    val preset = NeonColorPreset.fromKey(neonColor)
    val neonOn = neonEnabled && preset != NeonColorPreset.NONE
    val effectiveNeonKey = if (neonOn) neonColor else ""
    val resolvedStyle = wesalNeonTextStyle(
        baseStyle = style,
        neonColorKey = effectiveNeonKey,
        glowIntensity = neonGlowIntensity,
        fallbackColor = color
    )
    val customBadgeGlow = NeonColorPreset.fromKey(verificationGlowColor).takeIf { it != NeonColorPreset.NONE }?.glowColor
    val badgeGlow = glowColor ?: customBadgeGlow ?: if (neonOn) preset.glowColor else null

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = name,
            style = resolvedStyle,
            fontWeight = fontWeight,
            maxLines = maxNameLines.coerceAtLeast(1),
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (iconUrl.isNotBlank()) {
            // Same loading treatment as the avatar-corner icon (cached Coil
            // request, Fit), sized to sit naturally next to the name text.
            // Deliberately no pulse/scale animation here: name rows live in
            // dense lists where constant motion is noise and costs frames.
            val iconCtx = LocalContext.current
            AsyncImage(
                model = ImageRequest.Builder(iconCtx)
                    .data(wesalImageModel(iconUrl))
                    .memoryCacheKey(wesalImageCacheKey(iconUrl))
                    .diskCacheKey(wesalImageCacheKey(iconUrl))
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(badgeSize + 3.dp)
            )
        }
        if (isVerified) {
            WesalVerifiedBadge(
                size = badgeSize,
                verificationType = verificationType,
                glowColor = badgeGlow,
                verificationGlowColor = verificationGlowColor,
                glowEnabled = verificationGlowEnabled,
                glowIntensity = verificationGlowIntensity
            )
        }
    }
}

@Composable
fun WesalRoyalNameplateBanner(
    displayName: String,
    numericId: String,
    modifier: Modifier = Modifier,
    isVerified: Boolean = false,
    verificationType: String = "BLUE",
    verificationGlowColor: String = "",
    verificationGlowEnabled: Boolean = true,
    verificationGlowIntensity: Float = 0.7f,
    neonColor: String = "",
    neonGlowIntensity: Float = 0.7f,
    neonEnabled: Boolean = true,
    nameplateColorKey: String = "CRIMSON",
    accentColor: Color = Color(0xFFFFD54F),
    crimsonColor: Color = Color(0xFFD50026),
    // FIX 1 (round 5): resolved nameplate icon shown beside the name inside
    // the banner (profile pages), same rule as WesalNameWithBadge.
    iconUrl: String = ""
) {
    val context = LocalContext.current
    val strings = LocalWesalStrings.current
    val palette = WesalColors.current
    val cleanId = numericId.trim().removePrefix("@")
    val neonPreset = NeonColorPreset.fromKey(neonColor)
    val bannerPreset = WesalNameplateColorPreset.fromKey(nameplateColorKey)
    val resolvedMid = if (nameplateColorKey.equals("CRIMSON", ignoreCase = true) && crimsonColor != Color(0xFFD50026)) {
        crimsonColor
    } else {
        Color(bannerPreset.midHex)
    }
    val resolvedEdge = Color(bannerPreset.edgeHex)
    val resolvedCenter = Color(bannerPreset.centerHex)
    val resolvedBorderAccent = if (nameplateColorKey.equals("CRIMSON", ignoreCase = true) && accentColor != Color(0xFFFFD54F)) {
        accentColor
    } else {
        Color(bannerPreset.borderAccentHex)
    }
    val glowHighlight = if (neonEnabled && neonPreset != NeonColorPreset.NONE) neonPreset.glowColor else resolvedBorderAccent

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .drawBehind {
                    drawRoundRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                resolvedMid.copy(alpha = 0.44f),
                                glowHighlight.copy(alpha = 0.20f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = size.width * 0.62f
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(24f, 24f)
                    )
                }
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            resolvedEdge,
                            resolvedMid.copy(alpha = 0.94f),
                            resolvedCenter,
                            resolvedMid.copy(alpha = 0.94f),
                            resolvedEdge
                        )
                    )
                )
                .border(
                    width = 1.8.dp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            resolvedBorderAccent.copy(alpha = 0.72f),
                            Color(0xFFFFF8E1),
                            resolvedBorderAccent,
                            Color(0xFFFFF8E1),
                            resolvedBorderAccent.copy(alpha = 0.72f)
                        )
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("✦", color = resolvedBorderAccent, style = MaterialTheme.typography.labelLarge)
                WesalNameWithBadge(
                    name = displayName.ifBlank { "WESAL User" },
                    isVerified = isVerified,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFFFFF8E1),
                    fontWeight = FontWeight.ExtraBold,
                    badgeSize = 20.dp,
                    verificationType = verificationType,
                    neonColor = neonColor,
                    neonGlowIntensity = neonGlowIntensity,
                    neonEnabled = neonEnabled,
                    verificationGlowColor = verificationGlowColor,
                    verificationGlowEnabled = verificationGlowEnabled,
                    verificationGlowIntensity = verificationGlowIntensity,
                    iconUrl = iconUrl
                )
                Text("✦", color = resolvedBorderAccent, style = MaterialTheme.typography.labelLarge)
            }
        }

        if (cleanId.isNotBlank()) {
            val idAccent = if (neonEnabled && neonPreset != NeonColorPreset.NONE) neonPreset.glowColor else resolvedBorderAccent
            Surface(
                color = palette.surfaceElevated.copy(alpha = 0.92f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, idAccent.copy(alpha = 0.60f)),
                modifier = Modifier.clickable {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("wesal_numeric_id", cleanId))
                    Toast.makeText(context, "${strings.wesalNumericIdCopied} $cleanId", Toast.LENGTH_SHORT).show()
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "ID: $cleanId",
                        style = wesalNeonTextStyle(
                            baseStyle = MaterialTheme.typography.titleSmall,
                            neonColorKey = if (neonEnabled) neonColor else "",
                            glowIntensity = neonGlowIntensity,
                            fallbackColor = idAccent
                        ),
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = strings.copy,
                        tint = idAccent,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun MessageStatusTick(
    status: MessageStatus,
    modifier: Modifier = Modifier
) {
    val palette = WesalColors.current
    val (icon, tint) = when (status) {
        MessageStatus.SENDING -> Icons.Default.Schedule to palette.textMuted
        MessageStatus.SENT -> Icons.Default.Done to palette.textSecondary
        MessageStatus.DELIVERED -> Icons.Default.DoneAll to palette.textSecondary
        MessageStatus.READ -> Icons.Default.DoneAll to palette.readTick
        MessageStatus.FAILED -> Icons.Default.ErrorOutline to palette.error
    }
    Icon(
        imageVector = icon,
        contentDescription = status.raw,
        tint = tint,
        modifier = modifier.size(16.dp)
    )
}

@Composable
fun OfflineBanner(isOnline: Boolean) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    if (!isOnline) {
        Surface(
            color = palette.warning.copy(alpha = 0.16f),
            shape = RoundedCornerShape(0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.WifiOff, contentDescription = null, tint = palette.warning, modifier = Modifier.size(16.dp))
                Text(
                    text = strings.offlineBannerText,
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.warning
                )
            }
        }
    }
}

/**
 * Round 10 (item 4): a raw phone number rendered inside the app's RTL layout
 * was bidi-reordered — "+201013521019" showed as "201013521019+" with the
 * plus sign trailing (owner screenshot 168766). Phone numbers are LTR data;
 * this wrapper pins the layout direction for the number text only, so the
 * digits and the leading + always read left-to-right wherever a raw number
 * is displayed (contacts rows today; any future surface should use this).
 */
@Composable
fun PhoneNumberText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(text = text, style = style, color = color, modifier = modifier, maxLines = 1)
    }
}

/**
 * Round 9 (item 4, owner's own design — revised 2026-11-27 15:16/15:18):
 * the «تأكيد الرقم» press itself carries the hidden «ابدأ».
 *
 * Meta refuses free-form WhatsApp messages from a business to a user who has
 * not messaged the business in the last 24 hours (error 131047, verified in
 * the Zavu delivery logs) — so a WhatsApp OTP code silently never arrives
 * unless the user FIRST sends any message to the WESAL WhatsApp Business
 * number. Instead of a separate explanatory step, the primary confirm action
 * IS the handoff: pressing it opens WhatsApp on the WESAL number with «ابدأ»
 * prefilled (zero typing), the user sends it and returns — and the moment
 * the app resumes, the pending code request fires automatically (once,
 * guarded by the armed flag so unrelated resumes never send). If WhatsApp
 * cannot be opened at all, the code request fires immediately instead of
 * stranding the user.
 *
 * Returns the handoff trigger for the screen's primary button. The screens
 * add one short hint line under the button plus a manual direct-send
 * fallback ([WhatsAppOtpManualFallback]) for anyone who skips the trip.
 *
 * Round 10 hardening (owner device report on r9: "الواتساب ما اشتغلتش"):
 *  1. The armed flag used to be a plain `remember` — while the user is inside
 *     WhatsApp, Android routinely kills WESAL's process (especially on
 *     low-RAM devices), the flag died with it, and returning to the app sent
 *     nothing. The flag is now persisted in SharedPreferences with a
 *     timestamp and survives process death; it expires after 10 minutes so a
 *     stale arm can never fire a surprise send much later.
 *  2. The intent used to be a bare ACTION_VIEW on wa.me, which can land in a
 *     browser or a chooser where the «ابدأ» deep-link often stalls. Round
 *     10 targeted WhatsApp explicitly (first installed package) — round 11
 *     (item 4) supersedes that with the owner's CHOOSER: both WhatsApp and
 *     WhatsApp Business are offered when both are installed, because his
 *     gate number lives in the Business app and a forced package can send
 *     «ابدأ» from the wrong number; the generic intent (browser) remains
 *     the fallback when neither is installed.
 *  3. Failures were swallowed by runCatching. Every fallback now shows a
 *     visible Arabic toast, and when WhatsApp cannot be opened at all the
 *     code is sent directly instead of stranding the user.
 */
private const val OTP_HANDOFF_PREFS = "wesal_otp_handoff"
private const val OTP_HANDOFF_KEY_ARMED_AT = "armed_at"
private const val OTP_HANDOFF_TTL_MS = 10 * 60 * 1000L

@Composable
fun rememberWhatsAppOtpHandoff(onSendCode: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val strings = LocalWesalStrings.current
    val sendState = rememberUpdatedState(onSendCode)
    val prefs = remember(context) {
        context.getSharedPreferences(OTP_HANDOFF_PREFS, android.content.Context.MODE_PRIVATE)
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, prefs) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val armedAt = prefs.getLong(OTP_HANDOFF_KEY_ARMED_AT, 0L)
                if (armedAt > 0L) {
                    // Disarm first, whatever happens next — one shot only.
                    prefs.edit().remove(OTP_HANDOFF_KEY_ARMED_AT).apply()
                    if (System.currentTimeMillis() - armedAt <= OTP_HANDOFF_TTL_MS) {
                        sendState.value()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return remember(context, prefs, strings.isEnglish) {
        {
            // Arm BEFORE leaving, persisted — survives process death in WhatsApp.
            prefs.edit().putLong(OTP_HANDOFF_KEY_ARMED_AT, System.currentTimeMillis()).apply()
            // Round 11 (item 4): the open goes through the two-app chooser —
            // when both WhatsApp and WhatsApp Business are installed the
            // OWNER picks which one sends «ابدأ» (his gate number lives in
            // the Business app; the round-10 forced first-installed package
            // could open the wrong account). The armed flag is package-
            // agnostic, so the on-resume auto-send fires whichever app he
            // picked.
            val launched = WesalShareUtils.openWhatsAppLink(
                context,
                Constants.WHATSAPP_OTP_WINDOW_URL
            )
            if (!launched) {
                // Nothing could open WhatsApp: say so out loud and send directly.
                prefs.edit().remove(OTP_HANDOFF_KEY_ARMED_AT).apply()
                android.widget.Toast.makeText(
                    context,
                    if (strings.isEnglish) {
                        "WhatsApp could not be opened — sending the code directly"
                    } else {
                        "تعذر فتح واتساب — سيتم إرسال الكود مباشرة"
                    },
                    android.widget.Toast.LENGTH_LONG
                ).show()
                sendState.value()
            }
        }
    }
}

/** The one short hint line shown under a «تأكيد الرقم» handoff button. */
@Composable
fun WhatsAppOtpHandoffHint(modifier: Modifier = Modifier) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    Text(
        text = if (strings.isEnglish) {
            "Tapping «Confirm number» opens WhatsApp with a ready message — send it and come back, and your code will be sent automatically."
        } else {
            "الضغط على «تأكيد الرقم» هيفتح واتساب برسالة جاهزة — ابعتها وارجع، والكود هيتبعت لك تلقائياً."
        },
        style = MaterialTheme.typography.bodySmall,
        color = palette.textSecondary,
        modifier = modifier.fillMaxWidth()
    )
}

/** Manual fallback for users who skip the WhatsApp trip (window already open). */
@Composable
fun WhatsAppOtpManualFallback(
    enabled: Boolean = true,
    onSendCode: () -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    TextButton(onClick = onSendCode, enabled = enabled) {
        Text(
            text = if (strings.isEnglish) "Send the code directly" else "إرسال الكود مباشرة",
            color = palette.turquoise,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * Round 12 (addendum 9b): the same-number rule, stated once near every
 * WhatsApp OTP send action. The 24-hour window opens on the number that
 * MESSAGES the business — if the user's WhatsApp runs on a different
 * number than the one being verified, the window opens for the wrong
 * number and the code still never arrives.
 */
@Composable
fun WhatsAppOtpSameNumberHint(modifier: Modifier = Modifier) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    Text(
        text = if (strings.isEnglish) {
            "You must send «ابدأ» from the SAME number you are verifying — if your WhatsApp runs on a different number, the code will not arrive."
        } else {
            "لازم تبعت «ابدأ» من نفس الرقم اللي بتوثّقه — لو واتساب شغال برقم تاني، الكود مش هيوصل."
        },
        style = MaterialTheme.typography.bodySmall,
        color = palette.textSecondary,
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * Round 12 (addendum 9): the persistent not-arrived hint on the
 * code-entry state of every WhatsApp OTP flow. A free-form WhatsApp code
 * dies silently on Meta error 131047 when the 24-hour window is closed,
 * and the send API still reports success — so a user whose code never
 * arrived needs the window step spelled out HERE, with a button that
 * performs the handoff (opens the WhatsApp chooser with «ابدأ»
 * prefilled; the code request re-fires automatically on return, which
 * is the resend the text describes). Full lines only — no mid-word wrap.
 */
@Composable
fun WhatsAppOtpNotArrivedHint(
    modifier: Modifier = Modifier,
    onOpenWhatsApp: () -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = if (strings.isEnglish) {
                "If the code doesn't arrive on WhatsApp: first send the word «ابدأ» to WESAL's number 01013545227 on WhatsApp, then tap Resend code."
            } else {
                "لو الكود موصلش على واتساب: ابعت كلمة «ابدأ» لرقم وِصال 01013545227 على واتساب الأول، وبعدين دوس إعادة إرسال الكود."
            },
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(
            onClick = onOpenWhatsApp,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, palette.turquoise.copy(alpha = 0.55f))
        ) {
            Text(
                text = if (strings.isEnglish) "Open WhatsApp and send «ابدأ»" else "افتح واتساب وابعت «ابدأ»",
                color = palette.turquoise,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
