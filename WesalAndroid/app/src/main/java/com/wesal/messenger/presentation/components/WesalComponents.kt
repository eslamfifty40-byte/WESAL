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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.wesal.messenger.core.util.ResolvedFrame
import com.wesal.messenger.core.util.WesalFrameLibrary
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.wesal.messenger.core.theme.WesalColors
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
    var loadFailed by remember(imageUrl) { mutableStateOf(false) }
    var detectedCutout by remember(imageUrl, isCutout) { mutableStateOf(isCutout) }
    val imageModel = remember(imageUrl) { wesalImageModel(imageUrl) }
    val imageCacheKey = remember(imageUrl) { wesalImageCacheKey(imageUrl) }
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
        0.42f + 0.36f * neonGlowIntensity.coerceIn(0.2f, 1.0f)
    } else {
        0.60f
    }

    val isAnimatedFrame = activeFrame?.animated == true
    val infiniteTransition = if (isAnimatedFrame) rememberInfiniteTransition(label = "wesal_frame_anim") else null
    val isRotateFanStyle = activeFrame?.animationStyle == "ROTATE_AURA" || activeFrame?.animationStyle == "ROTATE_FAN"
    val animPulse by (infiniteTransition?.animateFloat(
        initialValue = 0.985f,
        targetValue = 1.025f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    ) ?: remember { mutableStateOf(1f) })
    val animAlpha by (infiniteTransition?.animateFloat(
        initialValue = 0.80f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    ) ?: remember { mutableStateOf(1f) })
    val animRotation by (infiniteTransition?.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotate"
    ) ?: remember { mutableStateOf(0f) })

    val containerSize = if (activeFrame != null) size * 1.28f else size
    // Portrait fits squarely inside the circular aperture of the crest frame (no overflow over lower shield/banner)
    val innerAvatarSize = if (activeFrame == null) {
        size
    } else {
        containerSize * activeFrame.apertureDiameterFraction.coerceIn(0.42f, 0.78f)
    }
    val apertureOffsetY = if (activeFrame == null) {
        0.dp
    } else {
        containerSize * (activeFrame.apertureCenterYFraction - 0.50f)
    }
    val avatarGlowAlpha = if (isRotateFanStyle) {
        baseGlowAlpha.coerceIn(0.25f, 0.92f)
    } else {
        (baseGlowAlpha * animAlpha).coerceIn(0.1f, 0.95f)
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
                                frame.glow.copy(alpha = 0.48f),
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
                    color = if (blade % 2 == 0) frame.glow.copy(alpha = 0.92f) else goldTint.copy(alpha = 0.88f),
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
            !frame.animated -> Modifier.fillMaxSize()
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
            if (frame.animated && isRotateFanStyle) {
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
                        .apply { if (frameKey != null) memoryCacheKey(frameKey) }
                        .crossfade(false)
                        .build(),
                    contentDescription = null,
                    modifier = animModifier,
                    contentScale = ContentScale.Fit
                )
            } else {
                Image(
                    painter = painterResource(id = frame.resId),
                    contentDescription = null,
                    modifier = animModifier,
                    contentScale = ContentScale.Fit
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
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    avatarGlowColor.copy(alpha = avatarGlowAlpha),
                                    avatarGlowColor.copy(alpha = avatarGlowAlpha * 0.42f),
                                    Color.Transparent
                                ),
                                center = center,
                                radius = this.size.minDimension * 0.62f
                            ),
                            radius = this.size.minDimension * 0.62f,
                            center = center
                        )
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        if (activeFrame != null && activeFrame.animated && isRotateFanStyle) {
            FanAuraSpinner(
                frame = activeFrame,
                spinnerSize = containerSize * 0.96f,
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
                    if (detectedCutout || activeFrame != null || neonPreset != NeonColorPreset.NONE) {
                        Modifier.background(
                            Brush.radialGradient(
                                colors = listOf(
                                    innerBackdropColor.copy(alpha = 0.56f),
                                    innerBackdropColor.copy(alpha = 0.24f),
                                    palette.surface.copy(alpha = 0.98f)
                                )
                            )
                        )
                    } else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            val borderColor = if (neonPreset != NeonColorPreset.NONE) neonPreset.glowColor else palette.border
            val borderWidth = if (neonPreset != NeonColorPreset.NONE) 1.8.dp else 1.dp
            if (imageUrl.isNotBlank() && !loadFailed) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(imageModel)
                        .apply { if (imageCacheKey != null) memoryCacheKey(imageCacheKey) }
                        .crossfade(true)
                        .allowHardware(false)
                        .build(),
                    contentDescription = name,
                    onError = { loadFailed = true },
                    onSuccess = { st ->
                        val bmp = (st.result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                        if (bmp != null && bmp.hasAlpha() && bmp.width > 8 && bmp.height > 8) {
                            val c1 = (bmp.getPixel(1, 1) ushr 24) < 20
                            val c2 = (bmp.getPixel(bmp.width - 2, 1) ushr 24) < 20
                            detectedCutout = c1 && c2
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
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

        // Frame draws around/over the circular inner aperture so wings, crown, and bottom crest remain crisp
        if (activeFrame != null) {
            FrameLayer(activeFrame)
        }

        if (iconUrl.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(iconModel)
                    .apply { if (iconCacheKey != null) memoryCacheKey(iconCacheKey) }
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                modifier = Modifier
                    .size((innerAvatarSize * 0.36f).coerceIn(16.dp, 26.dp))
                    .align(Alignment.BottomStart)
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
    glowColor: Color? = null
) {
    WesalVerifiedMark(
        badgeSize = size,
        modifier = modifier,
        badgeColor = badgeColor,
        verificationType = verificationType,
        glowColor = glowColor
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
    glowColor: Color? = null
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
    val badgeGlow = if (neonOn) preset.glowColor else glowColor

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
            WesalVerifiedBadge(size = badgeSize, verificationType = verificationType, glowColor = badgeGlow)
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
