package com.wesal.messenger.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wesal.messenger.core.theme.*
import com.wesal.messenger.domain.model.WesalUser
import com.wesal.messenger.domain.repository.UserRepository
import com.wesal.messenger.presentation.components.WesalAvatar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {
    val currentUser: StateFlow<WesalUser?> = userRepository.observeCurrentUser()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val saveStatus = MutableStateFlow<String?>(null)

    fun saveProfile(displayName: String, bio: String, publicId: String, photoUri: Uri?) {
        viewModelScope.launch {
            userRepository.updateProfile(displayName, bio, publicId, photoUri)
                .onSuccess { saveStatus.value = "تم حفظ التعديلات بنجاح" }
                .onFailure { saveStatus.value = it.localizedMessage ?: "تعذر حفظ الملف الشخصي" }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val user by viewModel.currentUser.collectAsStateWithLifecycle()
    val status by viewModel.saveStatus.collectAsStateWithLifecycle()

    var name by remember(user?.uid) { mutableStateOf(user?.displayName.orEmpty()) }
    var bio by remember(user?.uid) { mutableStateOf(user?.bio.orEmpty()) }
    var publicId by remember(user?.uid) { mutableStateOf(user?.publicId.orEmpty()) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) selectedUri = uri
    }

    Scaffold(
        containerColor = WesalDeepBlack,
        topBar = {
            TopAppBar(
                title = { Text("الملف الشخصي", color = WesalTextPrimary) },
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
                .background(WesalDeepBlack)
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier.clickable {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                contentAlignment = Alignment.BottomEnd
            ) {
                WesalAvatar(
                    imageUrl = selectedUri?.toString() ?: user?.photoUrl.orEmpty(),
                    name = name.ifBlank { "وصال" },
                    size = 104.dp
                )
                Icon(Icons.Default.Edit, contentDescription = "تغيير الصورة", tint = WesalTurquoise)
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("الاسم") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            OutlinedTextField(
                value = publicId,
                onValueChange = { publicId = it },
                label = { Text("المعرف العام الفريد (@id)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            OutlinedTextField(
                value = bio,
                onValueChange = { bio = it },
                label = { Text("الوصف الشخصي") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
                shape = RoundedCornerShape(12.dp)
            )

            status?.let { Text(it, color = WesalEmerald) }

            Button(
                onClick = { viewModel.saveProfile(name, bio, publicId, selectedUri) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = WesalEmerald, contentColor = WesalDeepBlack),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("حفظ التعديلات")
            }
        }
    }
}
