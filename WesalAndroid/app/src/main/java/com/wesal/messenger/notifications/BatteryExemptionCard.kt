package com.wesal.messenger.notifications

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.LocalWesalStrings

/**
 * §2.4 one-time battery-exemption card. Renders NOTHING when the app is already
 * exempt from battery optimisation (PowerManager.isIgnoringBatteryOptimizations)
 * or when the user dismissed it for this account (per-account DataStore flag,
 * wired by the caller). Otherwise explains — in the app's bilingual style — that
 * battery optimisation can stop incoming calls from ringing when the app is
 * killed, and offers the system exemption dialog plus a persistent dismiss.
 * Exemption is re-checked on every ON_RESUME so the card disappears as soon as
 * the user grants it in system settings.
 */
@Composable
fun BatteryExemptionCard(
    dismissed: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val context = LocalContext.current
    val powerManager = remember {
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }
    var isExempt by remember {
        mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, powerManager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isExempt = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (isExempt || dismissed) return

    Card(
        colors = CardDefaults.cardColors(containerColor = palette.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.BatteryAlert,
                    contentDescription = null,
                    tint = palette.warning,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (strings.isEnglish) "Allow calls to ring" else "اسمح للمكالمات بالرنين",
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Text(
                text = if (strings.isEnglish) {
                    "Battery optimisation can stop WESAL in the background, so incoming calls may not ring. Exempt WESAL from battery optimisation to make sure calls always reach you."
                } else {
                    "تحسين البطارية قد يوقف WESAL في الخلفية، فلا ترن المكالمات الواردة. استثنِ WESAL من تحسين البطارية لتضمن وصول المكالمات إليك دائمًا."
                },
                color = palette.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                    .setData(Uri.parse("package:" + context.packageName))
                            )
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (strings.isEnglish) "Allow" else "سماح")
                }
                TextButton(onClick = onDismiss) {
                    Text(
                        if (strings.isEnglish) "Not now" else "ليس الآن",
                        color = palette.textSecondary
                    )
                }
            }
        }
    }
}
