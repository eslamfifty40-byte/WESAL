package com.wesal.messenger.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wesal.messenger.core.theme.*
import com.wesal.messenger.presentation.components.WesalAvatar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatContactsScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    viewModel: ContactsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var allUsers by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<com.wesal.messenger.domain.model.WesalUser>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        try {
            val me = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance(com.google.firebase.FirebaseApp.getInstance(), "ai-studio-livo-cd975dac-35e6-4dbb-864e-829e3b638424")
            val snap = db.collection("users").limit(200).get().let { t -> kotlinx.coroutines.suspendCancellableCoroutine<com.google.firebase.firestore.QuerySnapshot> { c -> t.addOnSuccessListener { r -> c.resume(r) {} }.addOnFailureListener { e -> c.resumeWith(Result.failure(e)) } } }
            allUsers = snap.documents.mapNotNull { it.toObject(com.wesal.messenger.domain.model.WesalUser::class.java)?.copy(uid = it.id) }.filter { it.uid != me }
        } catch (e: Exception) { }
    }

    Scaffold(
        containerColor = WesalDeepBlack,
        topBar = {
            TopAppBar(
                title = { Text("محادثة جديدة", color = WesalTextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = WesalTextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WesalSurfaceDark)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::search,
                placeholder = { Text("ابحث بالاسم أو المعرف (@id) أو رقم الهاتف...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = WesalTurquoise) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )

            if (state.isSearching) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = WesalEmerald)
            }

            if (state.query.isBlank()) {
                Text(
                    text = "اكتب رقم الهاتف (مثال: +2010...) أو اسم المستخدم أو المعرف الفريد لبدء محادثة مشفرة.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WesalTextSecondary
                )
            } else if (!state.isSearching && state.results.isEmpty()) {
                Text(
                    text = "لا توجد حسابات مطابقة لبحثك",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WesalTextMuted
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(if (state.query.isBlank()) allUsers else state.results, key = { it.uid }) { user ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.startChatWith(user.uid, onOpenChat)
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        WesalAvatar(imageUrl = user.photoUrl, name = user.displayName, isOnline = user.isOnline)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = user.displayName.ifBlank { user.phoneNumber },
                                style = MaterialTheme.typography.titleMedium,
                                color = WesalTextPrimary
                            )
                            Text(
                                text = "@${user.publicId} • ${user.bio}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = WesalTextSecondary,
                                maxLines = 1
                            )
                        }
                    }
                    HorizontalDivider(color = WesalSurfaceBorder, thickness = 0.5.dp)
                }
            }
        }
    }
}
