package com.wesal.messenger.presentation.components

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import com.wesal.messenger.core.util.ResolvedFrame
import com.wesal.messenger.core.util.WesalFrameLibrary
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import com.wesal.messenger.core.util.WesalFrameCatalog
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
    val glow = glowIntensity.coerceIn(0.15f, 1.0f)
    val resolvedColor = if (palette.isDark) preset.textColorDarkBg else preset.textColorLightBg
    val shadowColor = if (palette.isDark) {
        preset.glowColor.copy(alpha = (0.55f + 0.45f * glow).coerceIn(0f, 1f))
    } else {
        preset.glowColor.copy(alpha = (0.30f * glow).coerceIn(0f, 0.5f))
    }
    return baseStyle.copy(
        color = resolvedColor,
        shadow = Shadow(
            color = shadowColor,
            offset = Offset.Zero,
            blurRadius = if (palette.isDark) 20f * glow else 10f * glow
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
    neonGlowIntensity: Float = 0.7f
) {
    val context = LocalContext.current
    val palette = WesalColors.current
    var loadFailed by remember(imageUrl) { mutableStateOf(false) }
    var detectedCutout by remember(imageUrl, isCutout) { mutableStateOf(isCutout) }
    val imageModel = remember(imageUrl) { wesalImageModel(imageUrl) }
    val activeFrame = WesalFrameLibrary.resolve(frameId, frameExpiresAt)
    val neonPreset = NeonColorPreset.fromKey(neonColor)
    val avatarGlowColor = when {
        activeFrame != null && glowEnabled -> activeFrame.glow
        neonPreset != NeonColorPreset.NONE && glowEnabled -> neonPreset.glowColor
        else -> null
    }
    val avatarGlowAlpha = if (neonPreset != NeonColorPreset.NONE) {
        0.35f + 0.35f * neonGlowIntensity.coerceIn(0.15f, 1.0f)
    } else {
        0.55f
    }

    val containerSize = if (activeFrame != null) size * 1.20f else size
    val innerAvatarSize = if (activeFrame == null) size else size * 0.90f

    @Composable
    fun FrameLayer(frame: ResolvedFrame) {
        if (frame.imageUrl.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(wesalImageModel(frame.imageUrl)).crossfade(false).build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Image(
                painter = painterResource(id = frame.resId),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
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
                                    avatarGlowColor.copy(alpha = avatarGlowAlpha * 0.4f),
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
        if (activeFrame != null && detectedCutout) {
            FrameLayer(activeFrame)
            if (imageUrl.isNotBlank() && !loadFailed) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(imageModel).build(),
                    contentDescription = name,
                    onError = { loadFailed = true },
                    modifier = Modifier
                        .requiredSize(containerSize * 1.14f)
                    .offset(y = containerSize * 0.07f)
                        .align(Alignment.BottomCenter),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomCenter
                )
            }
        } else {
            Box(modifier = Modifier.size(innerAvatarSize), contentAlignment = Alignment.Center) {
                val borderColor = if (neonPreset != NeonColorPreset.NONE) neonPreset.glowColor else palette.border
                val borderWidth = if (neonPreset != NeonColorPreset.NONE) 1.8.dp else 1.dp
                if (imageUrl.isNotBlank() && !loadFailed) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(imageModel)
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
                            .border(borderWidth, borderColor, CircleShape),
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
            if (activeFrame != null) FrameLayer(activeFrame)
        }

        if (iconUrl.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(wesalImageModel(iconUrl)).crossfade(false).build(),
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
    val glow = neonGlowIntensity.coerceIn(0.15f, 1.0f)
    val effectiveNeonKey = if (neonOn) neonColor else ""
    val resolvedStyle = wesalNeonTextStyle(
        baseStyle = style,
        neonColorKey = effectiveNeonKey,
        glowIntensity = neonGlowIntensity,
        fallbackColor = color
    )
    val badgeGlow = if (neonOn) preset.glowColor else glowColor
    val capsuleShape = RoundedCornerShape(50)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (neonOn) {
            // Glowing "button-like" capsule around the name: soft outer glow + thin neon border, text stays crisp.
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .drawBehind {
                        drawRoundRect(
                            brush = Brush.radialGradient(
                                colors = listOf(preset.glowColor.copy(alpha = 0.28f * glow), Color.Transparent),
                                center = center,
                                radius = this.size.maxDimension * 0.75f
                            ),
                            topLeft = Offset(-6.dp.toPx(), -4.dp.toPx()),
                            size = androidx.compose.ui.geometry.Size(this.size.width + 12.dp.toPx(), this.size.height + 8.dp.toPx()),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(100f, 100f)
                        )
                    }
                    .clip(capsuleShape)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                preset.glowColor.copy(alpha = 0.10f + 0.10f * glow),
                                preset.glowColor.copy(alpha = 0.04f),
                                preset.glowColor.copy(alpha = 0.10f + 0.10f * glow)
                            )
                        )
                    )
                    .border(1.dp, preset.glowColor.copy(alpha = 0.45f + 0.4f * glow), capsuleShape)
                    .padding(horizontal = 12.dp, vertical = 3.dp)
            ) {
                Text(
                    text = name,
                    style = resolvedStyle,
                    fontWeight = fontWeight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Text(
                text = name,
                style = resolvedStyle,
                fontWeight = fontWeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
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
