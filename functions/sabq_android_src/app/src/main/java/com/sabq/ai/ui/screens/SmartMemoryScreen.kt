package com.sabq.ai.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabq.ai.SabqApplication
import com.sabq.ai.data.local.CertaintyLevel
import com.sabq.ai.data.local.MemoryCategory
import com.sabq.ai.data.local.MemoryItemEntity
import com.sabq.ai.ui.theme.SabqColors
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun SmartMemoryScreen(app: SabqApplication, userId: String) {
    val dao = app.database.sabqDao()
    val scope = rememberCoroutineScope()
    val memories by dao.observeMemories(userId).collectAsState(initial = emptyList())

    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(MemoryCategory.PERSONAL) }
    var selectedCertainty by remember { mutableStateOf(CertaintyLevel.CONFIRMED) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("الذاكرة الذكية — فصل المؤكد عن التوقع والاستنتاج", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)

        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("العنوان") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = content, onValueChange = { content = it }, label = { Text("المعلومة") }, modifier = Modifier.fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                CertaintyLevel.CONFIRMED to "معلومة مؤكدة",
                CertaintyLevel.EXPECTATION to "توقع",
                CertaintyLevel.INFERENCE to "استنتاج"
            ).forEach { (level, label) ->
                FilterChip(selected = selectedCertainty == level, onClick = { selectedCertainty = level }, label = { Text(label, fontSize = 11.sp) })
            }
        }

        Button(
            onClick = {
                if (title.isNotBlank() && content.isNotBlank()) {
                    scope.launch {
                        dao.upsertMemory(
                            MemoryItemEntity(
                                id = UUID.randomUUID().toString(),
                                userId = userId,
                                category = selectedCategory,
                                certainty = selectedCertainty,
                                title = title.trim(),
                                content = content.trim(),
                                source = "حفظ مباشر من المستخدم"
                            )
                        )
                        title = ""
                        content = ""
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("حفظ في الذاكرة الذكية")
        }

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(memories) { item ->
                Surface(
                    color = SabqColors.Surface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, SabqColors.Border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${item.title} • ${item.certainty.name}", fontWeight = FontWeight.Bold, color = SabqColors.Cyan, fontSize = 13.sp)
                            Text(item.content, color = Color.White, fontSize = 12.sp)
                            Text("المصدر: ${item.source}", color = SabqColors.TextMuted, fontSize = 11.sp)
                        }
                        IconButton(onClick = { scope.launch { dao.deleteMemory(item.id, userId) } }) {
                            Icon(Icons.Default.Delete, contentDescription = "حذف بموافقة المستخدم", tint = SabqColors.Warning)
                        }
                    }
                }
            }
        }
    }
}
