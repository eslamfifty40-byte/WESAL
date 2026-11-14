package com.sabq.ai.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabq.ai.SabqApplication
import com.sabq.ai.data.preferences.SabqSettingsState
import com.sabq.ai.ui.theme.SabqColors
import kotlinx.coroutines.launch

@Composable
fun SettingsPrivacyScreen(app: SabqApplication, settings: SabqSettingsState) {
    val scope = rememberCoroutineScope()
    val wearableStatus = remember { app.wearableAudioManager.inspectAudioAndWearableStatus() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("الإعدادات والخصوصية والأجهزة", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)

        Surface(color = SabqColors.Surface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, SabqColors.Border)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingSwitch("الرد الصوتي العربي", settings.voiceEnabled) {
                    scope.launch { app.preferences.setVoiceEnabled(it) }
                }
                Text("مستوى الصوت: ${(settings.voiceVolume * 100).toInt()}%", color = SabqColors.TextMuted, fontSize = 12.sp)
                Slider(value = settings.voiceVolume, onValueChange = { scope.launch { app.preferences.setVoiceVolume(it) } })
                SettingSwitch("الإشعارات الذكية (مع احترام أوقات الهدوء)", settings.notificationsEnabled) {
                    scope.launch { app.preferences.setNotificationsEnabled(it) }
                }
                SettingSwitch("السماح باستخدام الكاميرا عند الطلب", settings.cameraAllowed) {
                    scope.launch { app.preferences.setCameraAllowed(it) }
                }
                SettingSwitch("السماح باستخدام الميكروفون عند الطلب", settings.micAllowed) {
                    scope.launch { app.preferences.setMicAllowed(it) }
                }
                SettingSwitch("قفل الملف الصحي بالمصادقة البيومترية", settings.biometricLockEnabled) {
                    scope.launch { app.preferences.setBiometricLock(it) }
                }
            }
        }

        Surface(color = SabqColors.Surface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, SabqColors.Border)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("حالة السماعات والأجهزة القابلة للارتداء", fontWeight = FontWeight.Bold, color = SabqColors.Cyan)
                Text(wearableStatus.statusSummaryAr, color = SabqColors.TextMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
