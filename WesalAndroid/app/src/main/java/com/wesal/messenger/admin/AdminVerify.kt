package com.wesal.messenger.admin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val ADMIN_DB = "ai-studio-livo-cd975dac-35e6-4dbb-864e-829e3b638424"

fun adminDb(): FirebaseFirestore = FirebaseFirestore.getInstance(FirebaseApp.getInstance(), ADMIN_DB)

suspend fun checkIsAdmin(): Boolean {
    return try {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) false
        else adminDb().collection("users").document(uid).get().await().getBoolean("isAdmin") == true
    } catch (e: Exception) {
        false
    }
}

class AdminVerifyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                AdminVerifyScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun AdminVerifyScreen(onClose: () -> Unit) {
    var allowed by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { allowed = checkIsAdmin() }
    var id by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun apply(value: Boolean) {
        scope.launch {
            busy = true
            msg = try {
                val handle = id.trim().removePrefix("@").lowercase()
                val q = adminDb().collection("users").whereEqualTo("publicId", handle).get().await()
                if (q.isEmpty) {
                    "مفيش حساب بالمعرّف ده"
                } else {
                    q.documents.first().reference.update("isVerified", value).await()
                    if (value) "تم توثيق الحساب" else "تم إلغاء التوثيق"
                }
            } catch (e: Exception) {
                "حصل خطأ: " + (e.message ?: "")
            }
            busy = false
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("لوحة المطور: توثيق الحسابات", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("رجوع") }
            }
            when (allowed) {
                null -> CircularProgressIndicator()
                false -> Text("غير مصرح لك بدخول هذه الصفحة")
                true -> {
                    OutlinedTextField(value = id, onValueChange = { id = it }, label = { Text("معرّف الحساب (WESAL ID)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { apply(true) }, enabled = !busy && id.isNotBlank(), modifier = Modifier.weight(1f)) { Text("توثيق") }
                        OutlinedButton(onClick = { apply(false) }, enabled = !busy && id.isNotBlank(), modifier = Modifier.weight(1f)) { Text("إلغاء التوثيق") }
                    }
                    if (msg.isNotBlank()) Text(msg)
                }
            }
        }
    }
}
