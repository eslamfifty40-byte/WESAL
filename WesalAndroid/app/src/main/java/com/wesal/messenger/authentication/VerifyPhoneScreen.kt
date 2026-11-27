package com.wesal.messenger.authentication

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wesal.messenger.core.theme.WesalColors
import com.wesal.messenger.core.util.LocalWesalStrings

/**
 * Mandatory phone-verification gate screen (phone phase). Shown after any
 * sign-in while the account's number is not OTP-verified; it cannot be
 * skipped — the system back button is swallowed and the only exits are
 * completing verification (the gate lifts itself when the server stamp
 * lands) or signing out. Nothing about the account is blocked or deleted:
 * this one step is all that stands between the user and the app.
 */
@Composable
fun VerifyPhoneScreen(
    viewModel: VerifyPhoneViewModel
) {
    val palette = WesalColors.current
    val strings = LocalWesalStrings.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showCountryPicker by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        // Deliberately swallowed: the gate has no back exit.
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = palette.turquoise,
        unfocusedBorderColor = palette.border,
        focusedTextColor = palette.textPrimary,
        unfocusedTextColor = palette.textPrimary,
        focusedLabelColor = palette.turquoise,
        unfocusedLabelColor = palette.textSecondary,
        focusedContainerColor = palette.surface,
        unfocusedContainerColor = palette.surface,
        cursorColor = palette.turquoise
    )

    Scaffold(containerColor = palette.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    Icons.Default.VerifiedUser,
                    contentDescription = null,
                    tint = palette.turquoise,
                    modifier = Modifier.size(54.dp)
                )
                Text(
                    text = if (strings.isEnglish) "Verify Your Phone Number" else "توثيق رقم الهاتف",
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = if (strings.isEnglish) {
                        "One quick step before you continue: confirm your phone number with a code we send you on WhatsApp or by SMS. A verified number is how your friends find you on WESAL."
                    } else {
                        "خطوة سريعة قبل ما تكمل: أكّد رقم هاتفك بكود هنبعتهولك على واتساب أو برسالة نصية. الرقم الموثّق هو اللي بيخلي صحابك يلاقوك على وصال."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary,
                    textAlign = TextAlign.Center
                )
            }

            if (state.step == 0) {
                if (state.prefilledFromAccount) {
                    Text(
                        text = if (strings.isEnglish) {
                            "This is the number saved on your account — send the code to it to confirm, or change it to verify a different number."
                        } else {
                            "ده الرقم المسجل في حسابك — ابعت الكود عليه للتأكيد، أو غيّره لو عايز توثّق رقم تاني."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary
                    )
                }
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        OutlinedButton(
                            onClick = { showCountryPicker = true },
                            modifier = Modifier.height(56.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, palette.turquoise.copy(alpha = 0.6f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = palette.surface,
                                contentColor = palette.textPrimary
                            )
                        ) {
                            Text(
                                "${state.selectedCountry.flagEmoji} ${state.selectedCountry.dialCode}",
                                fontWeight = FontWeight.Bold
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = palette.turquoise)
                        }
                        OutlinedTextField(
                            value = state.phoneNumberInput,
                            onValueChange = viewModel::updatePhoneInput,
                            label = { Text(if (strings.isEnglish) "Phone Number" else "رقم الهاتف") },
                            placeholder = { Text("010xxxxxxxx") },
                            leadingIcon = {
                                Icon(Icons.Default.Phone, contentDescription = null, tint = palette.turquoise)
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = fieldColors
                        )
                    }
                }
            } else {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = state.otpInput,
                        onValueChange = viewModel::updateOtpInput,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (strings.isEnglish) "Verification Code" else "كود التحقق") },
                        placeholder = { Text("••••••") },
                        leadingIcon = {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = palette.turquoise)
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        shape = RoundedCornerShape(14.dp),
                        colors = fieldColors
                    )
                }
            }

            state.errorMessage?.let { err ->
                Surface(
                    color = palette.error.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, palette.error.copy(alpha = 0.45f)),
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
                    color = palette.turquoise.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, palette.turquoise.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = info,
                        color = palette.textPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Button(
                onClick = { if (state.step == 0) viewModel.sendCode() else viewModel.verify() },
                enabled = !state.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.emerald,
                    contentColor = palette.onPrimary,
                    disabledContainerColor = palette.surfaceElevated,
                    disabledContentColor = palette.textSecondary
                )
            ) {
                if (state.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = palette.textPrimary,
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Text(
                        text = if (state.step == 0) {
                            if (strings.isEnglish) "Send the Code" else "ابعت الكود"
                        } else {
                            if (strings.isEnglish) "Confirm" else "تأكيد"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (state.step == 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = viewModel::resendCode,
                        enabled = state.resendSecondsLeft == 0 && !state.isBusy
                    ) {
                        Text(
                            text = if (state.resendSecondsLeft > 0) {
                                if (strings.isEnglish) {
                                    "Resend code (${state.resendSecondsLeft}s)"
                                } else {
                                    "إعادة إرسال الكود (${state.resendSecondsLeft} ث)"
                                }
                            } else {
                                if (strings.isEnglish) "Resend code" else "إعادة إرسال الكود"
                            },
                            color = if (state.resendSecondsLeft > 0) palette.textSecondary else palette.turquoise,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    TextButton(
                        onClick = viewModel::backToNumberStep,
                        enabled = !state.isBusy
                    ) {
                        Text(
                            text = if (strings.isEnglish) "Change number" else "تغيير الرقم",
                            color = palette.textSecondary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            TextButton(
                onClick = viewModel::signOut,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    text = if (strings.isEnglish) "Sign out" else "تسجيل الخروج",
                    color = palette.textSecondary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    if (showCountryPicker) {
        AlertDialog(
            onDismissRequest = { showCountryPicker = false },
            containerColor = palette.surface,
            title = {
                Text(
                    if (strings.isEnglish) "Choose country" else "اختر الدولة",
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(CountryCatalog.countries) { country ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.selectCountry(country)
                                    showCountryPicker = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(country.flagEmoji)
                            Text(
                                text = if (strings.isEnglish) country.nameEn else country.nameAr,
                                color = palette.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(country.dialCode, color = palette.textSecondary)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCountryPicker = false }) {
                    Text(strings.close, color = palette.turquoise)
                }
            }
        )
    }
}
