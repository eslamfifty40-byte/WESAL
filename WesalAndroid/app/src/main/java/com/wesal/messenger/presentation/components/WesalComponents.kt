package com.wesal.messenger.presentation.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
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
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.ContentCopy
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.math.roundToInt
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.theme.WesalNameplateColorPreset
import com.wesal.messenger.core.util.FrameGlowDisc
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
    iconUrl: String = "",
    neonColor: String = "",
    neonGlowIntensity: Float = 0.7f,
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
    val cachedBounds = remember(imageCacheKey) { getCachedAlphaBounds(imageCacheKey) }
    var detectedBounds by remember(imageUrl, cachedBounds) { mutableStateOf(cachedBounds) }
    // Pixel size of the decoded photo bitmap; the cutout layout math needs its aspect.
    var photoBitmapSize by remember(imageUrl) { mutableStateOf<IntSize?>(null) }
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

    // Perf: small list avatars (chat/contacts rows) render STATICALLY — no infinite
    // transitions are created for them at all, so a scrolling list never pays for frame
    // pulse/rotation or icon pulse animations it cannot meaningfully show. Larger
    // avatars (profile, settings, detail screens) keep the full motion.
    val allowMotion = size >= 72.dp
    // Master switch (system_config/global_appearance.framesAnimationEnabled): when off,
    // every frame renders statically even if its own `animated` flag is on.
    val isAnimatedFrame = activeFrame?.animated == true && WesalFrameLibrary.framesAnimationEnabled && allowMotion
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

    val containerSize = if (activeFrame != null) size * 1.38f else size
    // Measured aperture used verbatim (spec §5.5): the old x1.07 inflation is gone — the frame
    // artwork is drawn OVER the photo, so a photo disc of exactly the aperture size meets the
    // artwork's ring cleanly, matching the measured reference geometry.
    val effectiveAperture = activeFrame?.apertureDiameterFraction ?: 0.62f
    val innerAvatarSize = if (activeFrame == null) {
        size
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
    val verifiedBounds = detectedBounds?.takeIf { isCutoutBounds(it) }
    // Owner's pop-out geometry (definitive spec): a framed CUTOUT photo is rendered in a
    // virtual box taller than the aperture by a strip of 10% of the container height, so
    // the head can rise above the aperture's top edge over the frame's upper ring.
    // The aperture window opens for a verified cutout OR the legacy flag; the head strip
    // (below) opens for verified bounds ONLY.
    val framedCutout = activeFrame != null && (verifiedBounds != null || detectedCutout)
    // Corner-alpha probe for uncached, unflagged framed photos (drives cutout detection).
    val needSoftwareCheck = activeFrame != null && cachedCutout == null && !isCutout
    val popOutStrip = containerSize * 0.10f
    val popOutStripPx = with(density) { popOutStrip.roundToPx() }.coerceAtLeast(1)
    val apertureOffsetYPx = with(density) { apertureOffsetY.roundToPx() }
    val apertureRadiusPx = innerPx / 2f
    // Bounds-driven placement of the PERSON (not the padded canvas) inside the aperture:
    // person width == aperture diameter, head at the strip's top, tall cutouts height-capped.
    // Null until verified bounds AND the decoded bitmap's aspect are both known; the legacy
    // full-canvas rendering covers the first frame(s) until then (bounds cache makes later
    // binds synchronous).
    val cutoutTransform: FramedCutoutTransform? = if (verifiedBounds != null && activeFrame != null) {
        photoBitmapSize?.let { bmpSize ->
            FramedCutoutLayout.compute(
                containerPx = containerPx.toFloat(),
                apertureCenterYFraction = activeFrame.apertureCenterYFraction,
                apertureDiameterFraction = effectiveAperture,
                stripFraction = 0.10f,
                bounds = verifiedBounds,
                imageWidthPx = bmpSize.width,
                imageHeightPx = bmpSize.height
            )
        }
    } else null

    // Shared success handling for every framed photo request: record the decoded bitmap's
    // size (the layout math needs its aspect) and pick up the alpha bounds — published by
    // AlphaBoundsTransformation during the load, or analysed here from the delivered
    // bitmap when the cache has no entry yet (e.g. first load raced the transformation).
    fun onPhotoLoaded(drawable: Drawable) {
        if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
            photoBitmapSize = IntSize(drawable.intrinsicWidth, drawable.intrinsicHeight)
        }
        val fromCache = getCachedAlphaBounds(imageCacheKey)
        if (fromCache != null) {
            if (detectedBounds != fromCache) detectedBounds = fromCache
        } else if (activeFrame != null) {
            val bmp = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            if (bmp != null) {
                analyzeAndPublishBitmapBounds(bmp, imageCacheKey)?.let { detectedBounds = it }
            }
        }
    }
    val avatarGlowAlpha = (if (isRotateFanStyle) {
        baseGlowAlpha.coerceIn(0.25f, 0.92f)
    } else {
        (baseGlowAlpha * animAlpha).coerceIn(0.1f, 0.95f)
    } * if (activeFrame != null) frameGlowIntensity else 1f).coerceIn(0f, 0.95f)

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

    @Composable
    fun FrameLayer(frame: ResolvedFrame) {
        val animModifier = when {
            !isAnimatedFrame -> Modifier.fillMaxSize()
            isRotateFanStyle -> Modifier.fillMaxSize() // Frame crest remains stationary in-place without scaling/bouncing
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
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (isAnimatedFrame && isRotateFanStyle) {
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
                    modifier = animModifier,
                    contentScale = ContentScale.Fit
                )
            }
        }
    }

    // Head pop-out (owner's definitive spec) for framed CUTOUT photos: the photo is rendered
    // ONCE virtually — a box of aperture width and (aperture height + strip) height, bottom
    // aligned with the aperture, Crop/TopCenter — and shown through two clip windows of that
    // same rendering: the aperture circle (under the frame) and the strip above the
    // aperture's top edge (over the frame). Same scale/offset in both windows, so the person
    // is one coherent figure whose head rises out of the frame's upper ring; the cutout's
    // transparency guarantees no rectangular artifacts in the strip.
    val apertureClipShape = GenericShape { size, _ ->
        val cx = size.width / 2f
        val cy = size.height / 2f + apertureOffsetYPx
        addOval(Rect(cx - apertureRadiusPx, cy - apertureRadiusPx, cx + apertureRadiusPx, cy + apertureRadiusPx))
    }
    val popOutStripClipShape = GenericShape { size, _ ->
        val left = (size.width - innerPx) / 2f
        val apertureTop = size.height / 2f + apertureOffsetYPx - apertureRadiusPx
        addRect(Rect(left, apertureTop - popOutStripPx, left + innerPx, apertureTop))
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
                        .size(innerPx, innerPx + popOutStripPx)
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

    // Bounds-driven photo layer (round-3): the SAME clip-window architecture as
    // PopOutPhotoLayer, but the bitmap is placed by the measured alpha-bounds transform
    // (person fills the aperture, head in the strip) instead of ContentScale.Crop over
    // the padded canvas. ContentScale.FillBounds is exact here: drawW/drawH are computed
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
        if (activeFrame != null && isAnimatedFrame && isRotateFanStyle) {
            FanAuraSpinner(
                frame = activeFrame,
                spinnerSize = containerSize * 0.94f,
                centerOffsetY = apertureOffsetY,
                includeBlades = true
            )
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
                                intensity = frameGlowIntensity
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
                        effectiveCutout || neonPreset != NeonColorPreset.NONE -> Modifier.background(
                            Brush.radialGradient(
                                colors = listOf(
                                    innerBackdropColor.copy(alpha = 0.58f),
                                    innerBackdropColor.copy(alpha = 0.22f),
                                    if (effectiveCutout) innerBackdropColor.copy(alpha = 0.04f) else palette.surface.copy(alpha = 0.96f)
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
                        .size(innerPx)
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
                        // No zoom/shrink tricks: ContentScale.Crop + TopCenter fills the
                        // frame's circular opening exactly, so the whole photo (and the
                        // face at the top) stays visible inside the opening — for cutout
                        // photos too, which framed avatars now render as normal opaque
                        // photos over the opaque disc (see the backdrop above).
                        .clip(CircleShape)
                        .then(
                            if (activeFrame == null) {
                                Modifier.border(borderWidth, borderColor, CircleShape)
                            } else Modifier
                        ),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter
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

        // Pop-out window 2 of 2: the SAME rendering clipped to the strip above the
        // aperture's top edge, drawn OVER the frame — the head/shoulders of the cutout
        // overlap the frame's upper ring, exactly like the owner's reference composite.
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
    verificationGlowIntensity: Float = 0.7f
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
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
    crimsonColor: Color = Color(0xFFD50026)
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
                    verificationGlowIntensity = verificationGlowIntensity
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
