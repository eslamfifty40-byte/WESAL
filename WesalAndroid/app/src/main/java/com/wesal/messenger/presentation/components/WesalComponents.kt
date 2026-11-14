package com.wesal.messenger.presentation.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wesal.messenger.core.theme.*
import com.wesal.messenger.domain.model.MessageStatus

@Composable
fun WesalAvatar(
    imageUrl: String,
    name: String,
    size: Dp = 50.dp,
    isOnline: Boolean = false
) {
    Box(modifier = Modifier.size(size)) {
        if (imageUrl.isNotBlank()) {
            AsyncImage(
                model = com.wesal.messenger.presentation.components.wesalImageModel(imageUrl),
                contentDescription = name,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .border(1.dp, WesalSurfaceBorder, CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "و"
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(WesalEmeraldDark.copy(alpha = 0.45f))
                    .border(1.dp, WesalEmerald.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initial,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = WesalTextPrimary
                )
            }
        }
        if (isOnline) {
            Box(
                modifier = Modifier
                    .size(size * 0.26f)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(WesalEmerald)
                    .border(2.dp, WesalDeepBlack, CircleShape)
            )
        }
    }
}

@Composable
fun MessageStatusTick(
    status: MessageStatus,
    modifier: Modifier = Modifier
) {
    val (icon, tint) = when (status) {
        MessageStatus.SENDING -> Icons.Default.Schedule to WesalTextMuted
        MessageStatus.SENT -> Icons.Default.Done to WesalTextSecondary
        MessageStatus.DELIVERED -> Icons.Default.DoneAll to WesalTextSecondary
        MessageStatus.READ -> Icons.Default.DoneAll to WesalReadTick
        MessageStatus.FAILED -> Icons.Default.ErrorOutline to WesalError
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
    if (!isOnline) {
        Surface(
            color = WesalWarning.copy(alpha = 0.16f),
            shape = RoundedCornerShape(0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.WifiOff, contentDescription = null, tint = WesalWarning, modifier = Modifier.size(16.dp))
                Text(
                    text = "لا يوجد اتصال بالإنترنت — ستتم المزامنة تلقائياً عند العودة",
                    style = MaterialTheme.typography.labelMedium,
                    color = WesalWarning
                )
            }
        }
    }
}
