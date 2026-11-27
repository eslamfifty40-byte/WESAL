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
import androidx.compose.ui.unit.dp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.ContentCopy
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.theme.WesalNameplateColorPreset
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.core.util.NeonColorPreset
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

    // Master switch (system_config/global_appearance.framesAnimationEnabled): when off,
    // every frame renders statically even if its own `animated` flag is on.
    val isAnimatedFrame = activeFrame?.animated == true && WesalFrameLibrary.framesAnimationEnabled
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
    // Owner's pop-out geometry (definitive spec): a framed CUTOUT photo is rendered in a
    // virtual box taller than the aperture by a strip of 10% of the container height, so
    // the head can rise above the aperture's top edge over the frame's upper ring.
    val framedCutout = detectedCutout && activeFrame != null
    // Corner-alpha probe for uncached, unflagged framed photos (drives cutout detection).
    val needSoftwareCheck = activeFrame != null && cachedCutout == null && !isCutout
    val popOutStrip = containerSize * 0.10f
    val popOutStripPx = with(density) { popOutStrip.roundToPx() }.coerceAtLeast(1)
    val apertureOffsetYPx = with(density) { apertureOffsetY.roundToPx() }
    val apertureRadiusPx = innerPx / 2f
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
                        .apply { if (imageCacheKey != null) memoryCacheKey(imageCacheKey) }
                        .crossfade(false)
                        .apply { if (withCallbacks && needSoftwareCheck && isAnimatedFrame) allowHardware(false) }
                        .build(),
                    contentDescription = name,
                    onError = if (withCallbacks) ({ loadFailed = true }) else null,
                    onSuccess = if (withCallbacks) {
                        { st ->
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
        val innerBackdropColor = when {
            activeFrame?.id == "admin_39" -> Color(0xFFFF1744)
            else -> avatarGlowColor ?: palette.turquoise
        }
        Box(
            modifier = Modifier
                .size(innerAvatarSize)
                .offset(y = apertureOffsetY)
                .clip(CircleShape)
                .then(
                    when {
                        // Framed avatars sit on a FULLY OPAQUE backing disc (owner's
                        // definitive spec): a dark radial gradient echoing the reference
                        // artwork's interior — deep dark tint of the frame's glow colour
                        // falling to near-black, every stop at alpha 1. Neither the
                        // profile cover nor any background can ever show through the
                        // frame's opening (the owner's screenshot bug — a cutout photo
                        // floated over the cover inside the crest on a 0.04-alpha disc).
                        activeFrame != null -> Modifier.background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(
                                        red = innerBackdropColor.red * 0.30f,
                                        green = innerBackdropColor.green * 0.30f,
                                        blue = innerBackdropColor.blue * 0.30f,
                                        alpha = 1f
                                    ),
                                    Color(0xFF080708)
                                )
                            )
                        )
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
                        // Software decode is forced ONLY for animated framed avatars (rare,
                        // admin-side) where the corner-alpha probe below reads pixels.
                        // Static framed avatars — every customer, every list row — keep
                        // hardware bitmaps; the probe then fails safe (see onSuccess).
                        .apply { if (needSoftwareCheck && isAnimatedFrame) allowHardware(false) }
                        .build(),
                    contentDescription = name,
                    onError = { loadFailed = true },
                    onSuccess = { st ->
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
        // UNDER the frame artwork (same virtual rendering as the head strip below).
        if (framedCutout && imageUrl.isNotBlank() && !loadFailed) {
            PopOutPhotoLayer(clipShape = apertureClipShape, withCallbacks = true)
        }

        // Frame draws around/over the circular inner aperture so wings, crown, and bottom crest remain crisp
        if (activeFrame != null) {
            FrameLayer(activeFrame)
        }

        // Pop-out window 2 of 2: the SAME rendering clipped to the strip above the
        // aperture's top edge, drawn OVER the frame — the head/shoulders of the cutout
        // overlap the frame's upper ring, exactly like the owner's reference composite.
        if (framedCutout && imageUrl.isNotBlank() && !loadFailed) {
            PopOutPhotoLayer(clipShape = popOutStripClipShape, withCallbacks = false)
        }

        if (iconUrl.isNotBlank()) {
            val iconTransition = rememberInfiniteTransition(label = "wesal_icon_anim")
            val iconPulse by iconTransition.animateFloat(
                initialValue = 0.94f,
                targetValue = 1.06f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1500),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "icon_pulse"
            )
            val iconTilt by iconTransition.animateFloat(
                initialValue = -3.5f,
                targetValue = 3.5f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1900),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "icon_tilt"
            )
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
