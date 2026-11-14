package com.wesal.messenger.authentication

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.wesal.messenger.core.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneLoginScreen(
    viewModel: AuthViewModel,
    onNavigateToOtp: () -> Unit,
    onNavigateToProfileSetup: () -> Unit,
    onNavigateToHome: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    var showCountrySheet by remember { mutableStateOf(false) }
    var googleLoading by remember { mutableStateOf(false) }
    var googleError by remember { mutableStateOf<String?>(null) }
    val googleScope = rememberCoroutineScope()

    LaunchedEffect(state.navigateToOtp) {
        if (state.navigateToOtp) onNavigateToOtp()
    }
    LaunchedEffect(state.navigateToProfileSetup) {
        if (state.navigateToProfileSetup) onNavigateToProfileSetup()
    }
    LaunchedEffect(state.navigateToHome) {
        if (state.navigateToHome) onNavigateToHome()
    }

    Scaffold(
        containerColor = WesalDeepBlack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(WesalEmerald.copy(alpha = 0.14f))
                            .border(1.dp, WesalTurquoise.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            tint = WesalTurquoise
                        )
                    }
                    Column {
                        Text(
                            text = "تسجيل الدخول برقم الهاتف",
                            style = MaterialTheme.typography.headlineSmall,
                            color = WesalTextPrimary
                        )
                        Text(
                            text = "حسابات حقيقية موثقة برمز SMS",
                            style = MaterialTheme.typography.labelMedium,
                            color = WesalTurquoise
                        )
                    }
                }

                Text(
                    text = "أدخل رقم هاتفك وسنرسل لك رسالة نصية تحتوي على رمز التحقق لتفعيل حسابك في وصال.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WesalTextSecondary
                )

                // Country Selector Card
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { showCountrySheet = true },
                    color = WesalSurfaceElevated,
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, WesalSurfaceBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(text = state.selectedCountry.flagEmoji, style = MaterialTheme.typography.titleLarge)
                            Column {
                                Text(
                                    text = state.selectedCountry.nameAr,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = WesalTextPrimary
                                )
                                Text(
                                    text = state.selectedCountry.nameEn,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = WesalTextSecondary
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = state.selectedCountry.dialCode,
                                style = MaterialTheme.typography.titleMedium,
                                color = WesalEmerald,
                                fontWeight = FontWeight.Bold
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "اختيار الدولة",
                                tint = WesalTextSecondary
                            )
                        }
                    }
                }

                // Phone Number Input (LTR inside RTL for numeric phone correctness)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = state.phoneNumberInput,
                        onValueChange = viewModel::updatePhoneInput,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("رقم الهاتف") },
                        placeholder = { Text("1012345678") },
                        leadingIcon = {
                            Text(
                                text = state.selectedCountry.dialCode,
                                color = WesalTurquoise,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 12.dp, end = 4.dp)
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = WesalTurquoise,
                            unfocusedBorderColor = WesalSurfaceBorder,
                            focusedTextColor = WesalTextPrimary,
                            unfocusedTextColor = WesalTextPrimary,
                            focusedLabelColor = WesalTurquoise,
                            unfocusedLabelColor = WesalTextSecondary,
                            focusedContainerColor = WesalSurfaceDark,
                            unfocusedContainerColor = WesalSurfaceDark
                        )
                    )
                }

                if (state.errorMessage != null) {
                    Surface(
                        color = WesalError.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, WesalError.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = state.errorMessage!!,
                            color = WesalError,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        if (activity != null) viewModel.sendVerificationCode(activity, isResend = false)
                    },
                    enabled = !state.isLoading && state.phoneNumberInput.length >= 7,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WesalEmerald,
                        contentColor = WesalDeepBlack,
                        disabledContainerColor = WesalSurfaceElevated,
                        disabledContentColor = WesalTextMuted
                    )
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = WesalDeepBlack,
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        Text(
                            text = "إرسال رمز التحقق",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                                OutlinedButton(
                    onClick = {
                        if (activity == null || googleLoading) return@OutlinedButton
                        googleLoading = true
                        googleError = null
                        googleScope.launch {
                            try {
                                val option = GetGoogleIdOption.Builder()
                                    .setFilterByAuthorizedAccounts(false)
                                    .setServerClientId(activity.getString(activity.resources.getIdentifier("default_web_client_id", "string", activity.packageName)))
                                    .build()
                                val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                                val result = CredentialManager.create(activity).getCredential(activity, request)
                                val token = GoogleIdTokenCredential.createFrom(result.credential.data).idToken
                                val cred = GoogleAuthProvider.getCredential(token, null)
                                FirebaseAuth.getInstance().signInWithCredential(cred).await()
                                onNavigateToProfileSetup()
                            } catch (e: Exception) {
                                googleError = "تعذر تسجيل الدخول بجوجل: " + (e.message ?: "")
                            } finally {
                                googleLoading = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = if (googleLoading) "جارٍ الدخول..." else "المتابعة بحساب جوجل",
                        style = MaterialTheme.typography.titleMedium,
                        color = WesalTextPrimary
                    )
                }
                if (googleError != null) {
                    Text(text = googleError!!, color = WesalError, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    text = "بمتابعتك فإنك توافق على التحقق من رقم هاتفك عبر Firebase Authentication لضمان أمان حسابك.",
                    style = MaterialTheme.typography.labelSmall,
                    color = WesalTextMuted
                )
            }
        }
    }

    if (showCountrySheet) {
        var searchQuery by remember { mutableStateOf("") }
        ModalBottomSheet(
            onDismissRequest = { showCountrySheet = false },
            containerColor = WesalSurfaceDark
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = "اختر الدولة ومفتاح الاتصال",
                    style = MaterialTheme.typography.titleLarge,
                    color = WesalTextPrimary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("ابحث باسم الدولة أو الرمز...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(12.dp))
                val filtered = remember(searchQuery) {
                    CountryCatalog.countries.filter {
                        searchQuery.isBlank() ||
                            it.nameAr.contains(searchQuery, ignoreCase = true) ||
                            it.nameEn.contains(searchQuery, ignoreCase = true) ||
                            it.dialCode.contains(searchQuery)
                    }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(filtered, key = { it.iso }) { country ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    viewModel.selectCountry(country)
                                    showCountrySheet = false
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(country.flagEmoji, style = MaterialTheme.typography.titleLarge)
                                Text(country.nameAr, color = WesalTextPrimary, style = MaterialTheme.typography.bodyLarge)
                            }
                            Text(
                                country.dialCode,
                                color = WesalTurquoise,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
