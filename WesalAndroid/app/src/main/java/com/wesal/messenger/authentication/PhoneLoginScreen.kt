package com.wesal.messenger.authentication

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
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
import com.wesal.messenger.core.util.LocalWesalStrings
import com.wesal.messenger.presentation.components.WesalAvatar
import kotlinx.coroutines.launch

private val LoginBg = Color(0xFF070B0D)
private val LoginSurface = Color(0xFF0E1519)
private val LoginElevated = Color(0xFF131D23)
private val LoginCyan = Color(0xFF00D9E8)
private val LoginBorder = Color(0xFF1F2E37)
private val LoginTextSecondary = Color(0xFFA0B3BE)
private val LoginError = Color(0xFFFF5E73)
private val LoginSuccess = Color(0xFF00D66B)

@Composable
fun PhoneLoginScreen(
    viewModel: AuthViewModel,
    onNavigateToProfileSetup: () -> Unit,
    onNavigateToHome: () -> Unit,
    onNavigateToSupportChat: (String) -> Unit = {},
    isAddAccountMode: Boolean = false,
    prefillEmail: String = "",
    onCancelAddAccount: (() -> Unit)? = null
) {
    val strings = LocalWesalStrings.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val savedAccounts by viewModel.savedAccounts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }
    var googleLaunching by remember { mutableStateOf(false) }
    var showTermsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(prefillEmail) {
        if (prefillEmail.isNotBlank()) {
            viewModel.updateEmailInput(prefillEmail)
            viewModel.toggleSignUpMode(false)
        }
    }

    LaunchedEffect(state.navigateToSupportChatId) {
        val supportChatId = state.navigateToSupportChatId
        if (!supportChatId.isNullOrBlank()) {
            viewModel.consumeSupportChatNavigation()
            onNavigateToSupportChat(supportChatId)
        }
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
                val serverClientId = if (webClientResId != 0) activity.getString(webClientResId) else ""
                if (serverClientId.isBlank()) {
                    viewModel.setAuthError(
                        if (strings.isEnglish) {
                            "Google OAuth client ID is missing in google-services.json"
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
                        "No Google account available on this device — use email below"
                    } else {
                        "لا يوجد حساب Google متاح على هذا الجهاز — يمكنك تسجيل الدخول بالبريد الإلكتروني"
                    }
                )
            } catch (e: Exception) {
                viewModel.setAuthError(
                    if (strings.isEnglish) {
                        "Google Sign-In failed: ${e.localizedMessage ?: "verify SHA-1 and Google Sign-In"}"
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
        focusedBorderColor = LoginCyan,
        unfocusedBorderColor = LoginBorder,
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedLabelColor = LoginCyan,
        unfocusedLabelColor = LoginTextSecondary,
        focusedPlaceholderColor = LoginTextSecondary.copy(alpha = 0.65f),
        unfocusedPlaceholderColor = LoginTextSecondary.copy(alpha = 0.65f),
        focusedContainerColor = LoginSurface,
        unfocusedContainerColor = LoginSurface,
        cursorColor = LoginCyan
    )

    Scaffold(containerColor = LoginBg) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0A1418), LoginBg, Color(0xFF040709))
                    )
                )
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
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
                            tint = Color.White
                        )
                    }
                    Text(
                        text = strings.switchAccount,
                        style = MaterialTheme.typography.titleMedium,
                        color = LoginCyan,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // App Brand Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(LoginCyan.copy(alpha = 0.22f), LoginSuccess.copy(alpha = 0.16f))
                            )
                        )
                        .border(1.2.dp, LoginCyan.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "WESAL",
                        tint = LoginCyan,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Column {
                    Text(
                        text = "WESAL Messenger",
                        style = MaterialTheme.typography.labelLarge,
                        color = LoginCyan,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isAddAccountMode) {
                            if (strings.isEnglish) "Add Account to WESAL" else "إضافة حساب إلى وِصال"
                        } else {
                            if (strings.isEnglish) "Welcome to WESAL" else "مرحبًا بك في وِصال"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (strings.isEnglish) {
                            "Connect with those you love, wherever you are"
                        } else {
                            "تواصل مع من تحب، أينما كنت"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = LoginTextSecondary
                    )
                }
            }

            // Segmented Auth Mode Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(LoginSurface)
                    .border(1.dp, LoginBorder, RoundedCornerShape(14.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val signInSelected = !state.isSignUpMode
                Button(
                    onClick = { viewModel.toggleSignUpMode(false) },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (signInSelected) LoginCyan else LoginSurface,
                        contentColor = if (signInSelected) Color(0xFF041014) else LoginTextSecondary
                    ),
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp)
                ) {
                    Text(
                        if (strings.isEnglish) "Sign In" else "تسجيل الدخول",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
                Button(
                    onClick = { viewModel.toggleSignUpMode(true) },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!signInSelected) LoginCyan else LoginSurface,
                        contentColor = if (!signInSelected) Color(0xFF041014) else LoginTextSecondary
                    ),
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp)
                ) {
                    Text(
                        if (strings.isEnglish) "Create Account" else "إنشاء حساب جديد",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            // 1. Full Name (SignUp only, first field per Sec 8 item 9)
            AnimatedVisibility(visible = state.isSignUpMode) {
                OutlinedTextField(
                    value = state.fullNameInput,
                    onValueChange = viewModel::updateFullNameInput,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (strings.isEnglish) "Full Name" else "الاسم الكامل") },
                    placeholder = { Text(if (strings.isEnglish) "Enter your full name" else "أدخل اسمك الكامل") },
                    leadingIcon = {
                        Icon(Icons.Default.Person, contentDescription = null, tint = LoginCyan)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        keyboardType = KeyboardType.Text
                    ),
                    shape = RoundedCornerShape(14.dp),
                    colors = inputColors
                )
            }

            // 2. Email + 3. Password (supporting full Capital/Small characters)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = state.emailInput,
                    onValueChange = viewModel::updateEmailInput,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (strings.isEnglish) "Email" else "البريد الإلكتروني") },
                    placeholder = { Text(if (strings.isEnglish) "Enter your email" else "أدخل بريدك الإلكتروني") },
                    leadingIcon = {
                        Icon(Icons.Default.Email, contentDescription = null, tint = LoginCyan)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        keyboardType = KeyboardType.Email
                    ),
                    shape = RoundedCornerShape(14.dp),
                    colors = inputColors
                )

                OutlinedTextField(
                    value = state.passwordInput,
                    onValueChange = viewModel::updatePasswordInput,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (strings.isEnglish) "Password" else "كلمة المرور") },
                    placeholder = { Text(if (strings.isEnglish) "Enter your password" else "أدخل كلمة المرور") },
                    leadingIcon = {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = LoginCyan)
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                                tint = LoginTextSecondary
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        keyboardType = KeyboardType.Password
                    ),
                    shape = RoundedCornerShape(14.dp),
                    colors = inputColors
                )
            }

            // 4. Confirm Password (SignUp only)
            AnimatedVisibility(visible = state.isSignUpMode) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = state.confirmPasswordInput,
                        onValueChange = viewModel::updateConfirmPasswordInput,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (strings.isEnglish) "Confirm Password" else "تأكيد كلمة المرور") },
                        placeholder = { Text(if (strings.isEnglish) "Re-enter password" else "أعد إدخال كلمة المرور") },
                        leadingIcon = {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = LoginCyan)
                        },
                        trailingIcon = {
                            IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                                Icon(
                                    imageVector = if (confirmPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = LoginTextSecondary
                                )
                            }
                        },
                        visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            keyboardType = KeyboardType.Password
                        ),
                        shape = RoundedCornerShape(14.dp),
                        colors = inputColors
                    )
                }
            }

            // 5. Terms checkbox (SignUp) OR Forgot password (SignIn)
            if (state.isSignUpMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { viewModel.toggleTermsAccepted(!state.termsAccepted) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = state.termsAccepted,
                        onCheckedChange = viewModel::toggleTermsAccepted,
                        colors = CheckboxDefaults.colors(
                            checkedColor = LoginCyan,
                            uncheckedColor = LoginTextSecondary,
                            checkmarkColor = Color(0xFF041014)
                        )
                    )
                    Text(
                        text = if (strings.isEnglish) {
                            "I agree to the Terms & Privacy Policy"
                        } else {
                            "أوافق على الشروط وسياسة الخصوصية"
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showTermsDialog = true }) {
                        Text(
                            if (strings.isEnglish) "View" else "عرض",
                            color = LoginCyan,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    TextButton(
                        onClick = { viewModel.sendPasswordReset() },
                        enabled = !state.isLoading
                    ) {
                        Text(
                            if (strings.isEnglish) "Forgot password?" else "نسيت كلمة المرور؟",
                            color = LoginCyan,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            state.errorMessage?.let { err ->
                Surface(
                    color = LoginError.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, LoginError.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = err,
                        color = Color(0xFFFF9AA7),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            state.infoMessage?.let { info ->
                Surface(
                    color = LoginSuccess.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, LoginSuccess.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = info,
                        color = Color(0xFF69F0AE),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            state.supportAuthPromptMessage?.let { msg ->
                Surface(
                    color = LoginCyan.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, LoginCyan.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "التواصل مع إدارة WESAL (10001)",
                            color = LoginCyan,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = msg,
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Primary Submit CTA
            Button(
                onClick = viewModel::submitEmailAuth,
                enabled = !state.isLoading && !googleLaunching,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LoginCyan,
                    contentColor = Color(0xFF041014),
                    disabledContainerColor = LoginElevated,
                    disabledContentColor = LoginTextSecondary
                )
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color(0xFF041014),
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Text(
                        text = if (state.isSignUpMode) {
                            if (strings.isEnglish) "Create New Account" else "إنشاء حساب جديد"
                        } else {
                            if (strings.isEnglish) "Sign In to WESAL" else "تسجيل الدخول إلى وِصال"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Divider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = LoginBorder)
                Text(
                    text = if (strings.isEnglish) "Or using Google" else "أو باستخدام البريد الإلكتروني / Google",
                    style = MaterialTheme.typography.labelMedium,
                    color = LoginTextSecondary
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = LoginBorder)
            }

            // Google Sign-In Button with crisp multicolor Google 'G' Emblem
            OutlinedButton(
                onClick = { launchGooglePicker() },
                enabled = !state.isLoading && !googleLaunching,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.2.dp, LoginCyan.copy(alpha = 0.6f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = LoginElevated,
                    contentColor = Color.White
                )
            ) {
                if (googleLaunching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = LoginCyan,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (strings.isEnglish) "Opening Google Accounts..." else "جارٍ فتح حسابات Google..."
                    )
                } else {
                    GoogleGMark(modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (isAddAccountMode) {
                            if (strings.isEnglish) "Choose Another Google Account" else "اختيار حساب Google آخر"
                        } else {
                            if (strings.isEnglish) "Continue with Google" else "المتابعة باستخدام Google"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            // Direct Contact with WESAL Admin 10001 (below Google button, per Sec 8 + final binding 10001 rule)
            if (!isAddAccountMode) {
                OutlinedButton(
                    onClick = { viewModel.openSupportManagement10001() },
                    enabled = !state.isLoading && !googleLaunching,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, LoginBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = LoginSurface,
                        contentColor = LoginCyan
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.SupportAgent,
                        contentDescription = null,
                        tint = LoginCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (strings.isEnglish) "Contact WESAL Management (10001)" else "تواصل مع إدارة WESAL",
                        fontWeight = FontWeight.SemiBold,
                        color = LoginCyan
                    )
                }
            }

            // Saved Device Accounts
            if (savedAccounts.isNotEmpty() && !isAddAccountMode) {
                HorizontalDivider(color = LoginBorder)
                Text(
                    text = if (strings.isEnglish) "Saved Accounts on Device" else "الحسابات المسجلة مسبقاً على هذا الجهاز",
                    style = MaterialTheme.typography.titleSmall,
                    color = LoginCyan,
                    fontWeight = FontWeight.Bold
                )
                savedAccounts.forEach { acc ->
                    Surface(
                        color = LoginSurface,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, LoginBorder),
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
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val subtitle = listOfNotNull(
                                    acc.numericId.takeIf { it.isNotBlank() }?.let { "ID: $it" },
                                    acc.publicId.takeIf { it.isNotBlank() }?.let { "@$it" }
                                ).joinToString(" • ")
                                if (subtitle.isNotBlank()) {
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = LoginTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            IconButton(onClick = { viewModel.removeSavedAccount(acc.uid) }) {
                                Icon(Icons.Default.Close, contentDescription = strings.delete, tint = LoginTextSecondary)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTermsDialog) {
        AlertDialog(
            onDismissRequest = { showTermsDialog = false },
            containerColor = LoginSurface,
            titleContentColor = Color.White,
            textContentColor = LoginTextSecondary,
            title = {
                Text(
                    if (strings.isEnglish) "WESAL Terms & Privacy Policy" else "الشروط وسياسة الخصوصية — WESAL",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    if (strings.isEnglish) {
                        "WESAL protects your messages with end-to-end encryption, preserves privacy of your phone number and birth date according to your preferences, and prohibits abuse, spam, or illegal content."
                    } else {
                        "يلتزم تطبيق WESAL Messenger بحماية خصوصيتك وتشفير محادثاتك من طرف إلى طرف، وعدم إظهار بريدك الإلكتروني أو رقمك أو تاريخ ميلادك المخفي للآخرين، مع منع الإساءة أو انتحال الهوية أو المحتوى المخالف."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.toggleTermsAccepted(true)
                        showTermsDialog = false
                    }
                ) {
                    Text(if (strings.isEnglish) "Accept" else "موافق", color = LoginCyan, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTermsDialog = false }) {
                    Text(strings.close, color = LoginTextSecondary)
                }
            }
        )
    }
}

@Composable
private fun GoogleGMark(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(3.5.dp)) {
            val stroke = size.minDimension * 0.24f
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(Color(0xFFEA4335), -145f, 110f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
            drawArc(Color(0xFFFBBC05), 145f, 70f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
            drawArc(Color(0xFF34A853), 40f, 105f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
            drawArc(Color(0xFF4285F4), -35f, 78f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
            drawLine(
                color = Color(0xFF4285F4),
                start = Offset(size.width * 0.52f, size.height * 0.5f),
                end = Offset(size.width - inset * 0.6f, size.height * 0.5f),
                strokeWidth = stroke
            )
        }
    }
}
