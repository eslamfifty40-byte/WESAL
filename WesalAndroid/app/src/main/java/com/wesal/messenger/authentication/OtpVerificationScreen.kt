package com.wesal.messenger.authentication

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.LocalWesalStrings

@Composable
fun OtpVerificationScreen(
    viewModel: AuthViewModel,
    onBackToPhone: () -> Unit,
    onNavigateToProfileSetup: () -> Unit,
    onNavigateToHome: () -> Unit
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity

    LaunchedEffect(state.navigateToProfileSetup) {
        if (state.navigateToProfileSetup) onNavigateToProfileSetup()
    }
    LaunchedEffect(state.navigateToHome) {
        if (state.navigateToHome) onNavigateToHome()
    }

    Scaffold(
        containerColor = palette.background,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    viewModel.cancelPendingOtp()
                    onBackToPhone()
                }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = strings.back,
                        tint = palette.textPrimary
                    )
                }
                Text(
                    text = if (strings.isEnglish) "Phone Verification" else "التحقق من رقم الهاتف",
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.textPrimary
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(
                    text = if (strings.isEnglish) {
                        "Enter the 6-digit verification code sent via SMS to:"
                    } else {
                        "تم إرسال رمز تحقق مكون من 6 أرقام عبر SMS إلى:"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.textSecondary
                )
                Text(
                    text = state.fullPhoneE164,
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.turquoise,
                    fontWeight = FontWeight.Bold
                )

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    BasicTextField(
                        value = state.otpCode,
                        onValueChange = { code ->
                            viewModel.updateOtpCode(code)
                            if (code.length == 6) {
                                viewModel.verifyOtpCode()
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        decorationBox = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                repeat(6) { index ->
                                    val char = state.otpCode.getOrNull(index)?.toString() ?: ""
                                    val isFocused = state.otpCode.length == index
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(58.dp)
                                            .background(palette.surface, RoundedCornerShape(12.dp))
                                            .border(
                                                width = if (isFocused) 2.dp else 1.dp,
                                                color = if (isFocused) palette.turquoise else palette.border,
                                                shape = RoundedCornerShape(12.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = char,
                                            style = MaterialTheme.typography.headlineSmall,
                                            color = palette.textPrimary,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.canResend) {
                        TextButton(
                            onClick = {
                                if (activity != null) viewModel.sendVerificationCode(activity, isResend = true)
                            },
                            enabled = !state.isLoading
                        ) {
                            Text(
                                text = if (strings.isEnglish) "Resend Code" else "إعادة إرسال الرمز",
                                color = palette.turquoise,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Text(
                            text = if (strings.isEnglish) {
                                "Resend available in ${state.countdownSeconds}s"
                            } else {
                                "إعادة الإرسال متاحة خلال ${state.countdownSeconds} ثانية"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textSecondary
                        )
                    }
                }

                if (state.errorMessage != null) {
                    Surface(
                        color = palette.error.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, palette.error.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = state.errorMessage!!,
                            color = palette.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            Button(
                onClick = viewModel::verifyOtpCode,
                enabled = !state.isLoading && state.otpCode.length == 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.emerald,
                    contentColor = palette.onAccent
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
                        text = if (strings.isEnglish) "Verify & Continue" else "تأكيد الرمز والمتابعة",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
