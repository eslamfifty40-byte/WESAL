package com.sabq.ai.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabq.ai.SabqApplication
import com.sabq.ai.data.local.ChatMessageEntity
import com.sabq.ai.data.preferences.SabqSettingsState
import com.sabq.ai.ui.theme.SabqColors
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun SecondBrainScreen(app: SabqApplication, settings: SabqSettingsState) {
    val scope = rememberCoroutineScope()
    val dao = app.database.sabqDao()
    val chats by dao.observeChats(settings.activeUserId).collectAsState(initial = emptyList())
    val memories by dao.observeMemories(settings.activeUserId).collectAsState(initial = emptyList())
    val scans by dao.observeScans(settings.activeUserId).collectAsState(initial = emptyList())

    val proactiveInsights = remember(memories, scans, settings) {
        app.secondBrainEngine.buildProactiveInsights(
            memories = memories,
            recentScans = scans,
            quietStart = settings.quietHoursStartHour,
            quietEnd = settings.quietHoursEndHour
        )
    }

    var input by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("العقل الإضافي الذكي — سَبَق", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("يفهم العربية واللهجة المصرية، ويربط ذاكرتك بدون ادعاء قراءة الأفكار.", fontSize = 12.sp, color = SabqColors.TextMuted)
        Spacer(modifier = Modifier.height(8.dp))

        if (proactiveInsights.isNotEmpty()) {
            Surface(
                color = SabqColors.SurfaceElevated,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, SabqColors.Purple.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("مقترح استباقي: ${proactiveInsights.first().title}", color = SabqColors.Cyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(proactiveInsights.first().body, color = Color.White, fontSize = 12.sp)
                    Text(proactiveInsights.first().basisDescription, color = SabqColors.TextMuted, fontSize = 11.sp)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chats) { msg ->
                val isUser = msg.role == "user"
                Surface(
                    color = if (isUser) SabqColors.Purple.copy(alpha = 0.28f) else SabqColors.Surface,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(msg.content, color = Color.White, modifier = Modifier.weight(1f), fontSize = 13.sp)
                        if (!isUser && settings.voiceEnabled) {
                            IconButton(onClick = { app.wearableAudioManager.speakArabic(msg.content, settings.voiceVolume) }) {
                                Icon(Icons.Default.VolumeUp, contentDescription = "قراءة صوتية", tint = SabqColors.Cyan)
                            }
                        }
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("اكتب سؤالك بالعربية أو المصرية...") },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                enabled = !isSending && input.isNotBlank(),
                onClick = {
                    val text = input.trim()
                    input = ""
                    isSending = true
                    scope.launch {
                        dao.insertChat(
                            ChatMessageEntity(
                                id = UUID.randomUUID().toString(),
                                userId = settings.activeUserId,
                                role = "user",
                                content = text
                            )
                        )
                        val result = app.secondBrainEngine.sendChatPrompt(settings.activeUserId, text, memories)
                        val reply = result.getOrElse { "تعذر الاتصال بخدمة التحليل: ${it.message}" }
                        dao.insertChat(
                            ChatMessageEntity(
                                id = UUID.randomUUID().toString(),
                                userId = settings.activeUserId,
                                role = "assistant",
                                content = reply
                            )
                        )
                        if (settings.voiceEnabled) {
                            app.wearableAudioManager.speakArabic(reply, settings.voiceVolume)
                        }
                        isSending = false
                    }
                }
            ) {
                Icon(Icons.Default.Send, contentDescription = "إرسال", tint = SabqColors.Cyan)
            }
        }
    }
}
