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
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.LocalWesalStrings
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
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
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
                        if (strings.isEnglish) {
                            "Google OAuth client ID is missing in google-services.json — enable Google Sign-In in Firebase Console"
                        } else {
                            "معرّف Google OAuth غير متوفر في google-services.json — فعّل Google Sign-In في Firebase Console"
                        }
                    )
                    return@launch
                }

                val credentialManager = CredentialManager.create(activity)
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
                viewModel.setAuthError(
                    if (strings.isEnglish) "Google account selection canceled" else "تم إلغاء اختيار حساب Google"
                )
            } catch (_: NoCredentialException) {
                viewModel.setAuthError(
                    if (strings.isEnglish) {
                        "No Google account available on this device — sign in with email below"
                    } else {
                        "لا يوجد حساب Google متاح على هذا الجهاز — يمكنك تسجيل الدخول بالبريد الإلكتروني أدناه"
                    }
                )
            } catch (e: Exception) {
                viewModel.setAuthError(
                    if (strings.isEnglish) {
                        "Google Sign-In failed: ${e.localizedMessage ?: "verify SHA-1 and Firebase Google Sign-In"}"
                    } else {
                        "تعذر تسجيل الدخول عبر Google: ${e.localizedMessage ?: "تأكد من تفعيل Google في Firebase وبصمة SHA-1"}"
                    }
                )
            } finally {
                googleLaunching = false
            }
        }
    }

    val inputColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = palette.turquoise,
        unfocusedBorderColor = palette.border,
        focusedTextColor = palette.textPrimary,
        unfocusedTextColor = palette.textPrimary,
        focusedLabelColor = palette.turquoise,
        unfocusedLabelColor = palette.textSecondary,
        focusedContainerColor = palette.surface,
        unfocusedContainerColor = palette.surface
    )

    Scaffold(containerColor = palette.background) { padding ->
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
                            contentDescription = strings.back,
                            tint = palette.textPrimary
                        )
                    }
                    Text(
                        text = strings.switchAccount,
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.turquoise,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.emerald.copy(alpha = 0.14f))
                        .border(1.dp, palette.turquoise.copy(alpha = 0.45f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = palette.turquoise
                    )
                }
                Column {
                    Text(
                        text = if (isAddAccountMode) {
                            if (strings.isEnglish) "Add a WESAL Account" else "إضافة حساب جديد إلى WESAL"
                        } else {
                            if (strings.isEnglish) "Welcome to WESAL Messenger" else "مرحباً بك في WESAL Messenger"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (strings.isEnglish) {
                            "Free & secure sign-in via Google or Email"
                        } else {
                            "دخول مجاني وآمن عبر Google أو البريد الإلكتروني — بدون SMS"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.turquoise
                    )
                }
            }

            Text(
                text = if (isAddAccountMode) {
                    if (strings.isEnglish) {
                        "Choose another Google account or sign in with email to switch smoothly without losing saved accounts."
                    } else {
                        "اختر حساب Google جديداً أو سجّل الدخول ببريد إلكتروني آخر للتبديل فوراً دون فقدان حساباتك المحفوظة."
                    }
                } else {
                    if (strings.isEnglish) {
                        "Sign in directly with Google or email—no phone SMS required. You can optionally link and verify a phone number in your profile anytime."
                    } else {
                        "سجّل الدخول بحساب Google أو بريدك الإلكتروني مباشرة دون الحاجة إلى رقم هاتف أو رسائل تحقق، ويمكنك إضافة رقم هاتف وتوثيقه اختيارياً من ملفك الشخصي."
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary
            )

            OutlinedButton(
                onClick = { launchGooglePicker() },
                enabled = !state.isLoading && !googleLaunching,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, palette.turquoise.copy(alpha = 0.6f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = palette.surfaceElevated,
                    contentColor = palette.textPrimary
                )
            ) {
                if (googleLaunching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = palette.turquoise,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (strings.isEnglish) "Opening Google Account Picker..." else "جارٍ فتح قائمة حسابات Google..."
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = null,
                        tint = palette.turquoise
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (isAddAccountMode) {
                            if (strings.isEnglish) "Choose Another Google Account" else "اختيار حساب Google آخر"
                        } else {
                            if (strings.isEnglish) "Continue with Google (Gmail)" else "المتابعة بحساب Google (Gmail)"
                        },
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
                HorizontalDivider(modifier = Modifier.weight(1f), color = palette.border)
                Text(
                    text = if (strings.isEnglish) "Or via Email" else "أو عبر البريد الإلكتروني",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.textSecondary
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = palette.border)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.surface)
                    .border(1.dp, palette.border, RoundedCornerShape(12.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val signInSelected = !state.isSignUpMode
                Button(
                    onClick = { viewModel.toggleSignUpMode(false) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (signInSelected) palette.emerald else palette.surface,
                        contentColor = if (signInSelected) palette.onAccent else palette.textSecondary
                    ),
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp)
                ) {
                    Text(
                        if (strings.isEnglish) "Sign In" else "تسجيل الدخول",
                        fontWeight = FontWeight.Bold
                    )
                }
                Button(
                    onClick = { viewModel.toggleSignUpMode(true) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!signInSelected) palette.emerald else palette.surface,
                        contentColor = if (!signInSelected) palette.onAccent else palette.textSecondary
                    ),
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp)
                ) {
                    Text(
                        if (strings.isEnglish) "Create Account" else "إنشاء حساب جديد",
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = state.emailInput,
                    onValueChange = viewModel::updateEmailInput,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (strings.isEnglish) "Email" else "البريد الإلكتروني") },
                    placeholder = { Text("name@example.com") },
                    leadingIcon = {
                        Icon(Icons.Default.Email, contentDescription = null, tint = palette.turquoise)
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
                    label = { Text(if (strings.isEnglish) "Password" else "كلمة المرور") },
                    placeholder = { Text(if (strings.isEnglish) "At least 6 characters" else "6 أحرف أو أكثر") },
                    leadingIcon = {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = palette.turquoise)
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                                tint = palette.textSecondary
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
                        Text(
                            if (strings.isEnglish) "Forgot password?" else "نسيت كلمة المرور؟",
                            color = palette.turquoise
                        )
                    }
                }
            }

            state.errorMessage?.let { err ->
                Surface(
                    color = palette.error.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, palette.error.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = err,
                        color = palette.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            state.infoMessage?.let { info ->
                Surface(
                    color = palette.emerald.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, palette.emerald.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = info,
                        color = palette.emerald,
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
                    containerColor = palette.emerald,
                    contentColor = palette.onAccent,
                    disabledContainerColor = palette.surfaceElevated,
                    disabledContentColor = palette.textMuted
                )
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = palette.onAccent,
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Text(
                        text = if (state.isSignUpMode) {
                            if (strings.isEnglish) "Create WESAL Account" else "إنشاء حساب في WESAL"
                        } else {
                            if (strings.isEnglish) "Sign In with Email" else "تسجيل الدخول بالبريد الإلكتروني"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (savedAccounts.isNotEmpty() && !isAddAccountMode) {
                HorizontalDivider(color = palette.border)
                Text(
                    text = if (strings.isEnglish) "Saved Accounts on This Device" else "الحسابات المسجلة مسبقاً على هذا الجهاز",
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.turquoise,
                    fontWeight = FontWeight.Bold
                )
                savedAccounts.forEach { acc ->
                    Surface(
                        color = palette.surface,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, palette.border),
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
                                name = acc.displayName.ifBlank { acc.email.ifBlank { strings.appShortName } },
                                size = 40.dp
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = acc.displayName.ifBlank { acc.email.ifBlank { strings.appDisplayName } },
                                    color = palette.textPrimary,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val subtitle = listOfNotNull(
                                    acc.publicId.takeIf { it.isNotBlank() }?.let { "@$it" },
                                    acc.email.takeIf { it.isNotBlank() }
                                ).joinToString(" • ")
                                if (subtitle.isNotBlank()) {
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            IconButton(onClick = { viewModel.removeSavedAccount(acc.uid) }) {
                                Icon(Icons.Default.Close, contentDescription = strings.delete, tint = palette.textMuted)
                            }
                        }
                    }
                }
            }

            Text(
                text = if (strings.isEnglish) {
                    "Your session is saved automatically. Every account receives a unique @Wesal ID for private discovery without sharing phone numbers."
                } else {
                    "يتم حفظ جلستك تلقائياً بعد تسجيل الدخول. بعد الدخول يحصل حسابك على معرف وصال فريد (@Wesal ID) للتواصل مع أصدقائك بخصوصية."
                },
                style = MaterialTheme.typography.labelSmall,
                color = palette.textMuted
            )
        }
    }
}
