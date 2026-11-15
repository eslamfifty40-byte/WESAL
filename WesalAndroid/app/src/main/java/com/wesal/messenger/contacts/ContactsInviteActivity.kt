package com.wesal.messenger.contacts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class InviteContact(val name: String, val number: String)

class ContactsInviteActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ContactsInviteScreen(onClose = { finish() })
            }
        }
    }
}

private fun loadContacts(ctx: android.content.Context): List<InviteContact> {
    val out = LinkedHashMap<String, InviteContact>()
    val c = ctx.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
        null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
    )
    c?.use {
        while (it.moveToNext()) {
            val name = it.getString(0) ?: continue
            val num = (it.getString(1) ?: continue).replace(" ", "")
            if (!out.containsKey(num)) out[num] = InviteContact(name, num)
        }
    }
    return out.values.toList()
}

@Composable
private fun ContactsInviteScreen(onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.READ_CONTACTS) }
    var contacts by remember { mutableStateOf(emptyList<InviteContact>()) }
    var registered by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) {
        try {
            com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("users").get().addOnSuccessListener { snap -> registered = snap.documents.mapNotNull { it.getString("phoneDigits") }.toSet() }
        } catch (e: Exception) { }
    }
    LaunchedEffect(granted) { if (granted) contacts = withContext(Dispatchers.IO) { loadContacts(ctx) } }
    var q by remember { mutableStateOf("") }
    val shown = contacts.filter { q.isBlank() || it.name.contains(q, true) || it.number.contains(q) }
    Column(Modifier.fillMaxSize().background(Color(0xFF050A0F)).systemBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("جهات الاتصال", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("رجوع") }
        }
        OutlinedTextField(value = q, onValueChange = { q = it }, label = { Text("بحث") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        if (!granted) {
            Text("اسمح بالوصول لجهات الاتصال عشان تظهر هنا", color = Color.White)
            Button(onClick = { launcher.launch(Manifest.permission.READ_CONTACTS) }) { Text("السماح") }
        }
        LazyColumn {
            items(shown) { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = Color.White)
                        Text(c.number, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }
                    if (isRegisteredNumber(c.number, registered)) Text("\u0645\u0633\u062c\u0644", color = Color.Green) else Button(onClick = {
                        val i = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "أنا بستخدم تطبيق وصال للمراسلة. حمّله وسجّل معايا!")
                        }
                        ctx.startActivity(Intent.createChooser(i, "دعوة"))
                    }) { Text("دعوة") }
                }
            }
        }
    }
}


private fun isRegisteredNumber(num: String, registered: Set<String>): Boolean {
    var d = num.filter { it.isDigit() }
    if (d.startsWith("00")) d = d.substring(2) else if (d.startsWith("0")) d = "20" + d.substring(1)
    return d in registered
}
