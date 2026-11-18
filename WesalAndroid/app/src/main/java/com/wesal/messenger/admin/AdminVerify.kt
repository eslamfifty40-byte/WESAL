package com.wesal.messenger.admin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.wesal.messenger.core.util.Constants
import com.wesal.messenger.core.util.VerificationSealType
import com.wesal.messenger.core.util.WesalIdUtils
import com.wesal.messenger.presentation.components.WesalNameWithBadge
import com.wesal.messenger.presentation.components.WesalVerifiedBadge
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

private const val ADMIN_DB = "ai-studio-livo-cd975dac-35e6-4dbb-864e-829e3b638424"

fun adminDb(): FirebaseFirestore = FirebaseFirestore.getInstance(FirebaseApp.getInstance(), ADMIN_DB)

suspend fun checkIsAdmin(): Boolean {
    return try {
        val user = FirebaseAuth.getInstance().currentUser ?: return false
        val email = user.email.orEmpty()
        if (Constants.SUPER_ADMIN_EMAILS.none { it.equals(email, ignoreCase = true) } || !user.isEmailVerified) {
            return false
        }
        val db = adminDb()
        val doc = db
            .collection(Constants.COLLECTION_SUPER_ADMINS)
            .document(user.uid)
            .get(Source.SERVER)
            .await()
        if (!doc.exists() || doc.getBoolean("active") == false) return false

        val saNumId = doc.getString("numericId").orEmpty()
        if (saNumId.isNotBlank() && saNumId !in Constants.ADMIN_NUMERIC_IDS) return false

        val userDoc = db.collection(Constants.COLLECTION_USERS).document(user.uid).get(Source.SERVER).await()
        val userNumId = userDoc.getString("numericId").orEmpty()
        if (userNumId.isNotBlank() && userNumId !in Constants.ADMIN_NUMERIC_IDS) return false

        true
    } catch (_: Exception) {
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
    var query by remember { mutableStateOf("") }
    var foundDoc by remember { mutableStateOf<DocumentSnapshot?>(null) }
    var selectedSeal by remember { mutableStateOf(VerificationSealType.BLUE) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun findTarget() {
        scope.launch {
            busy = true
            msg = ""
            try {
                if (!checkIsAdmin()) {
                    allowed = false
                    msg = "غير مصرح لك بهذا الإجراء"
                    busy = false
                    return@launch
                }
                val raw = query.trim()
                val numQuery = WesalIdUtils.extractNumericIdQuery(raw)
                val byNumeric = if (WesalIdUtils.isValidNumericId(numQuery)) {
                    adminDb().collection(Constants.COLLECTION_USERS)
                        .whereEqualTo("numericId", numQuery)
                        .limit(1)
                        .get()
                        .await()
                        .documents
                        .firstOrNull()
                } else null

                val doc = byNumeric ?: run {
                    val handle = WesalIdUtils.normalize(raw)
                    if (handle.isNotBlank()) {
                        adminDb().collection(Constants.COLLECTION_USERS)
                            .whereEqualTo("publicId", handle)
                            .limit(1)
                            .get()
                            .await()
                            .documents
                            .firstOrNull()
                    } else null
                }
                foundDoc = doc
                if (doc == null) {
                    msg = "لم يتم العثور على حساب بهذا المعرّف"
                } else {
                    selectedSeal = VerificationSealType.fromKey(doc.getString("verificationType"))
                }
            } catch (e: Exception) {
                msg = "حصل خطأ: " + (e.message ?: "")
            }
            busy = false
        }
    }

    fun apply(value: Boolean) {
        scope.launch {
            val targetDoc = foundDoc ?: return@launch
            busy = true
            msg = try {
                val adminUser = FirebaseAuth.getInstance().currentUser
                val adminUid = adminUser?.uid.orEmpty()
                if (!checkIsAdmin() || adminUid.isBlank()) {
                    allowed = false
                    "غير مصرح لك بهذا الإجراء"
                } else {
                    val now = System.currentTimeMillis()
                    targetDoc.reference.set(
                        mapOf(
                            "isVerified" to value,
                            "verificationType" to selectedSeal.key,
                            "verificationExpiresAt" to 0L,
                            "verifiedByUid" to adminUid,
                            "verifiedAt" to now
                        ),
                        SetOptions.merge()
                    ).await()

                    val opId = UUID.randomUUID().toString()
                    adminDb().collection(Constants.COLLECTION_VERIFICATION_LOGS).document(opId).set(
                        mapOf(
                            "logId" to opId,
                            "operationId" to opId,
                            "targetUid" to targetDoc.id,
                            "targetNumericId" to targetDoc.getString("numericId").orEmpty(),
                            "targetPublicId" to targetDoc.getString("publicId").orEmpty(),
                            "targetName" to targetDoc.getString("displayName").orEmpty(),
                            "action" to if (value) "GRANTED" else "REVOKED",
                            "verificationType" to selectedSeal.key,
                            "durationLabel" to if (value) "دائم" else "سحب",
                            "expiresAt" to 0L,
                            "adminUid" to adminUid,
                            "adminEmail" to (adminUser?.email ?: Constants.SUPER_ADMIN_EMAIL),
                            "note" to "توثيق من الأداة الإدارية المباشرة",
                            "timestamp" to now
                        )
                    ).await()

                    foundDoc = targetDoc.reference.get().await()
                    if (value) "تم منح ${selectedSeal.labelAr} بنجاح" else "تم سحب التوثيق بنجاح"
                }
            } catch (e: Exception) {
                "حصل خطأ: " + (e.message ?: "")
            }
            busy = false
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("لوحة المطور (${Constants.OWNER_ADMIN_NUMERIC_ID} / ${Constants.SECONDARY_ADMIN_NUMERIC_ID}): توثيق الحسابات", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("رجوع") }
            }
            when (allowed) {
                null -> CircularProgressIndicator()
                false -> Text("غير مصرح لك بدخول هذه الصفحة (حسابات الإدارة المعتمدة فقط)")
                true -> {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("معرّف WESAL الرقمي أو @username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { findTarget() },
                        enabled = !busy && query.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("بحث عن الحساب")
                    }
                    foundDoc?.let { doc ->
                        val name = doc.getString("displayName").orEmpty().ifBlank { "WESAL User" }
                        val numericId = doc.getString("numericId").orEmpty()
                        val publicId = doc.getString("publicId").orEmpty()
                        val verified = doc.getBoolean("isVerified") == true
                        val verType = doc.getString("verificationType") ?: "BLUE"
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                WesalNameWithBadge(
                                    name = name,
                                    isVerified = verified,
                                    verificationType = verType,
                                    fontWeight = FontWeight.Bold
                                )
                                if (numericId.isNotBlank()) Text("ID: $numericId")
                                if (publicId.isNotBlank()) Text("@$publicId")
                                Text(if (verified) "الحالة: موثّق ($verType)" else "الحالة: غير موثّق")

                                val isProtected = Constants.isProtectedAdminNumericId(numericId)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    VerificationSealType.entries.forEach { seal ->
                                        FilterChip(
                                            selected = selectedSeal == seal,
                                            onClick = { selectedSeal = seal },
                                            leadingIcon = { WesalVerifiedBadge(size = 16.dp, verificationType = seal.key) },
                                            label = { Text(seal.labelAr) }
                                        )
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(
                                        onClick = { apply(true) },
                                        enabled = !busy,
                                        modifier = Modifier.weight(1f)
                                    ) { Text(if (verified) "تحديث الشارة" else "منح التوثيق") }
                                    OutlinedButton(
                                        onClick = { apply(false) },
                                        enabled = !busy && verified && !isProtected,
                                        modifier = Modifier.weight(1f)
                                    ) { Text("سحب التوثيق") }
                                }
                            }
                        }
                    }
                    if (msg.isNotBlank()) Text(msg)
                }
            }
        }
    }
}
