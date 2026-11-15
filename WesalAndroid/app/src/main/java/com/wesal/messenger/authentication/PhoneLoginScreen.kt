package com.wesal.messenger.authentication

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.wesal.messenger.core.theme.*
import com.wesal.messenger.presentation.components.WesalAvatar
import kotlinx.coroutines.launch

@Composable
fun PhoneLoginScreen(
    viewModel: AuthViewModel,
    onNavigateToOtp: () -> Unit,
    onNavigateToProfileSetup: () -> Unit,
    onNavigateToHome: () -> Unit,
    isAddAccountMode: Boolean = false,
    prefillEmail: String = "",
    onCancelAddAccount: (() -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val savedAccounts by viewModel.savedAccounts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    var passwordVisible by remember { mutableStateOf(false) }
    var googleLaunching by remember { mutableStateOf(false) }

    LaunchedEffect(prefillEmail) {
        if (prefillEmail.isNotBlank()) {
            viewModel.updateEmailInput(prefillEmail)
            viewModel.toggleSignUpMode(false)
        }
    }

    LaunchedEffect(state.navigateToOtp) {
        if (state.navigateToOtp) onNavigateToOtp()
    }
    LaunchedEffect(state.navigateToProfileSetup) {
        if (state.navigateToProfileSetup) onNavigateToProfileSetup()
    }
    LaunchedEffect(state.navigateToHome) {
        if (state.navigateToHome) onNavigateToHome()
    }

    fun launchGooglePicker() {
        if (activity == null || state.isLoading || googleLaunching) return
        viewModel.clearError()
        googleLaunching = true
        scope.launch {
            try {
                val webClientResId = activity.resources.getIdentifier(
                    "default_web_client_id",
                    "string",
                    activity.packageName
                )
                val serverClientId = if (webClientResId != 0) {
                    activity.getString(webClientResId)
                } else {
                    ""
                }
                if (serverClientId.isBlank()) {
                    viewModel.setAuthError(
                        "معرّف Google OAuth غير متوفر في google-services.json — فعّل Google Sign-In في Firebase Console"
                    )
                    return@launch
                }

                val credentialManager = CredentialManager.create(activity)
                // Always clear Credential Manager selected state so Google shows full account picker
                runCatching { credentialManager.clearCredentialState(ClearCredentialStateRequest()) }

                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setAutoSelectEnabled(false)
                    .setServerClientId(serverClientId)
                    .build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val result = credentialManager.getCredential(activity, request)
                val googleCred = GoogleIdTokenCredential.createFrom(result.credential.data)
                viewModel.signInWithGoogleIdToken(googleCred.idToken)
            } catch (_: GetCredentialCancellationException) {
                viewModel.setAuthError("تم إلغاء اختيار حساب Google")
            } catch (_: NoCredentialException) {
                viewModel.setAuthError("لا يوجد حساب Google متاح على هذا الجهاز — يمكنك تسجيل الدخول بالبريد الإلكتروني أدناه")
            } catch (e: Exception) {
                viewModel.setAuthError("تعذر تسجيل الدخول عبر Google: ${e.localizedMessage ?: "تأكد من تفعيل Google في Firebase وبصمة SHA-1"}")
            } finally {
                googleLaunching = false
            }
        }
    }

    val inputColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = WesalTurquoise,
        unfocusedBorderColor = WesalSurfaceBorder,
        focusedTextColor = WesalTextPrimary,
        unfocusedTextColor = WesalTextPrimary,
        focusedLabelColor = WesalTurquoise,
        unfocusedLabelColor = WesalTextSecondary,
        focusedContainerColor = WesalSurfaceDark,
        unfocusedContainerColor = WesalSurfaceDark
    )

    Scaffold(containerColor = WesalDeepBlack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isAddAccountMode && onCancelAddAccount != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onCancelAddAccount) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "العودة",
                            tint = WesalTextPrimary
                        )
                    }
                    Text(
                        text = "إضافة أو تبديل حساب وصال",
                        style = MaterialTheme.typography.titleMedium,
                        color = WesalTurquoise,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Header Branding
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(WesalEmerald.copy(alpha = 0.14f))
                        .border(1.dp, WesalTurquoise.copy(alpha = 0.45f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = WesalTurquoise
                    )
                }
                Column {
                    Text(
                        text = if (isAddAccountMode) "إضافة حساب جديد إلى وصال" else "مرحباً بك في وصال",
                        style = MaterialTheme.typography.headlineSmall,
                        color = WesalTextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "دخول مجاني وآمن عبر Google أو البريد الإلكتروني — بدون SMS",
                        style = MaterialTheme.typography.labelMedium,
                        color = WesalTurquoise
                    )
                }
            }

            Text(
                text = if (isAddAccountMode) {
                    "اختر حساب Google جديداً أو سجّل الدخول ببريد إلكتروني آخر للتبديل فوراً دون فقدان حساباتك المحفوظة."
                } else {
                    "سجّل الدخول بحساب Google أو بريدك الإلكتروني مباشرة دون الحاجة إلى رقم هاتف أو رسائل تحقق، ويمكنك إضافة رقم هاتف اختيارياً لاحقاً من ملفك الشخصي."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = WesalTextSecondary
            )

            // Primary Google Sign-In CTA (always opens account chooser)
            OutlinedButton(
                onClick = { launchGooglePicker() },
                enabled = !state.isLoading && !googleLaunching,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, WesalTurquoise.copy(alpha = 0.6f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = WesalSurfaceElevated,
                    contentColor = WesalTextPrimary
                )
            ) {
                if (googleLaunching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = WesalTurquoise,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("جارٍ فتح قائمة حسابات Google...")
                } else {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = null,
                        tint = WesalTurquoise
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (isAddAccountMode) "اختيار حساب Google آخر" else "المتابعة بحساب Google (Gmail)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = WesalSurfaceBorder)
                Text(
                    text = "أو عبر البريد الإلكتروني",
                    style = MaterialTheme.typography.labelMedium,
                    color = WesalTextSecondary
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = WesalSurfaceBorder)
            }

            // Sign In / Sign Up Mode Selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(WesalSurfaceDark)
                    .border(1.dp, WesalSurfaceBorder, RoundedCornerShape(12.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val signInSelected = !state.isSignUpMode
                Button(
                    onClick = { viewModel.toggleSignUpMode(false) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (signInSelected) WesalEmerald else WesalSurfaceDark,
                        contentColor = if (signInSelected) WesalDeepBlack else WesalTextSecondary
                    ),
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp)
                ) {
                    Text("تسجيل الدخول", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { viewModel.toggleSignUpMode(true) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!signInSelected) WesalEmerald else WesalSurfaceDark,
                        contentColor = if (!signInSelected) WesalDeepBlack else WesalTextSecondary
                    ),
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp)
                ) {
                    Text("إنشاء حساب جديد", fontWeight = FontWeight.Bold)
                }
            }

            // Email & Password Inputs (LTR inside RTL for email/password correctness)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = state.emailInput,
                    onValueChange = viewModel::updateEmailInput,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("البريد الإلكتروني") },
                    placeholder = { Text("name@example.com") },
                    leadingIcon = {
                        Icon(Icons.Default.Email, contentDescription = null, tint = WesalTurquoise)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    shape = RoundedCornerShape(14.dp),
                    colors = inputColors
                )

                OutlinedTextField(
                    value = state.passwordInput,
                    onValueChange = viewModel::updatePasswordInput,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("كلمة المرور") },
                    placeholder = { Text("6 أحرف أو أكثر") },
                    leadingIcon = {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = WesalTurquoise)
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "إظهار أو إخفاء كلمة المرور",
                                tint = WesalTextSecondary
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(14.dp),
                    colors = inputColors
                )
            }

            if (!state.isSignUpMode) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = { viewModel.sendPasswordReset() },
                        enabled = !state.isLoading,
                        modifier = Modifier.align(Alignment.CenterStart)
                    ) {
                        Text("نسيت كلمة المرور؟", color = WesalTurquoise)
                    }
                }
            }

            state.errorMessage?.let { err ->
                Surface(
                    color = WesalError.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, WesalError.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = err,
                        color = WesalError,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            state.infoMessage?.let { info ->
                Surface(
                    color = WesalEmerald.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, WesalEmerald.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = info,
                        color = WesalEmerald,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Button(
                onClick = viewModel::submitEmailAuth,
                enabled = !state.isLoading && !googleLaunching,
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
                        text = if (state.isSignUpMode) "إنشاء حساب في وصال" else "تسجيل الدخول بالبريد الإلكتروني",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (savedAccounts.isNotEmpty() && !isAddAccountMode) {
                HorizontalDivider(color = WesalSurfaceBorder)
                Text(
                    text = "الحسابات المسجلة مسبقاً على هذا الجهاز",
                    style = MaterialTheme.typography.titleSmall,
                    color = WesalTurquoise,
                    fontWeight = FontWeight.Bold
                )
                savedAccounts.forEach { acc ->
                    Surface(
                        color = WesalSurfaceDark,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, WesalSurfaceBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (acc.provider == "google") {
                                    launchGooglePicker()
                                } else if (acc.email.isNotBlank()) {
                                    viewModel.toggleSignUpMode(false)
                                    viewModel.updateEmailInput(acc.email)
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            WesalAvatar(
                                imageUrl = acc.photoUrl,
                                name = acc.displayName.ifBlank { acc.email.ifBlank { "وصال" } },
                                size = 40.dp
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = acc.displayName.ifBlank { acc.email.ifBlank { "حساب وصال" } },
                                    color = WesalTextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val subtitle = listOfNotNull(
                                    acc.publicId.takeIf { it.isNotBlank() }?.let { "@$it" },
                                    acc.email.takeIf { it.isNotBlank() } ?: acc.phoneNumber.takeIf { it.isNotBlank() }
                                ).joinToString(" • ")
                                if (subtitle.isNotBlank()) {
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = WesalTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            IconButton(onClick = { viewModel.removeSavedAccount(acc.uid) }) {
                                Icon(Icons.Default.Close, contentDescription = "إزالة من القائمة", tint = WesalTextMuted)
                            }
                        }
                    }
                }
            }

            Text(
                text = "يعمل التطبيق بالكامل على خطة Firebase Spark المجانية، ويتم حفظ جلستك تلقائياً بعد تسجيل الدخول. بعد الدخول يحصل حسابك على معرف وصال فريد (Wesal ID) للتواصل مع أصدقائك بسهولة.",
                style = MaterialTheme.typography.labelSmall,
                color = WesalTextMuted
            )
        }
    }
}
