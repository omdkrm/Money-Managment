package ir.modiriatsarmaye.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ir.modiriatsarmaye.app.ui.components.*
import ir.modiriatsarmaye.app.ui.viewmodel.WealthUiState
import ir.modiriatsarmaye.app.util.PersianUtils

@Composable
fun SecurityCheckingSplashScreen() {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("security_checking_splash"),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = AppIcons.Security,
                        contentDescription = "بررسی وضعیت امنیت",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp)
                    )
                }
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Text(
                    text = "در حال بررسی تنظیمات امنیتی...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun PinLockScreen(
    uiState: WealthUiState,
    canUseDeviceAuth: Boolean,
    onUnlockWithPin: (String) -> Unit,
    onTriggerBiometricUnlock: () -> Unit,
    onTriggerPinRecovery: () -> Unit,
    onConfirmPinRecovery: () -> Unit,
    onCancelPinRecovery: () -> Unit
) {
    var enteredPin by remember { mutableStateOf("") }
    var showPin by remember { mutableStateOf(false) }

    if (uiState.pinRecoveryAwaitingConfirmation) {
        AlertDialog(
            onDismissRequest = onCancelPinRecovery,
            modifier = Modifier.testTag("pin_recovery_confirm_dialog"),
            title = {
                Text(
                    text = "تایید حذف قفل PIN",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "احراز هویت دستگاه با موفقیت انجام شد. لطفاً پیش از ادامه به موارد زیر توجه کنید:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "• کلید امنیتی یا رمز PIN قبلی قابل بازیابی یا نمایش نیست.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "• با تایید این مرحله، قفل رمز ورود فعلی غیرفعال می‌شود تا بتوانید رمز جدیدی تنظیم کنید.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "• تمامی تراکنش‌ها، قیمت‌ها، اهداف، بدهی‌ها، سودها و تنظیمات مالی شما کاملاً حفظ خواهند شد.",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            },
            confirmButton = {
                AppDialogActionPair(
                    confirmText = "تایید و حذف قفل PIN",
                    onConfirm = onConfirmPinRecovery,
                    dismissText = "انصراف",
                    onDismiss = onCancelPinRecovery,
                    isDestructive = true,
                    confirmTestTag = "confirm_pin_recovery_btn",
                    dismissTestTag = "cancel_pin_recovery_btn"
                )
            }
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("pin_lock_screen"),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 460.dp),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = AppIcons.Lock,
                            contentDescription = "قفل برنامه",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "مدیریت سرمایه",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = if (uiState.isKeystorePermanentlyUnavailable) {
                            "کلید امنیتی رمز ورود در این دستگاه نامعتبر یا غیرقابل دسترس شده است. برای دسترسی به اطلاعات خود، قفل را با احراز هویت دستگاه بازیابی کنید."
                        } else {
                            "برای مشاهده اطلاعات مالی، رمز ورود (PIN) خود را وارد کنید."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    if (uiState.pinErrorMessage != null) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("pin_lock_error_banner")
                        ) {
                            Text(
                                text = uiState.pinErrorMessage,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    if (!uiState.isKeystorePermanentlyUnavailable) {
                        Spacer(modifier = Modifier.height(18.dp))

                        OutlinedTextField(
                            value = enteredPin,
                            onValueChange = { raw ->
                                val normalized = PersianUtils.toEnglishDigits(raw)
                                    .filter { it in '0'..'9' }
                                    .take(8)
                                enteredPin = normalized
                            },
                            label = { Text("رمز ورود (PIN)") },
                            singleLine = true,
                            visualTransformation = if (showPin) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                AppIconActionButton(
                                    icon = if (showPin) AppIcons.VisibilityOff else AppIcons.Visibility,
                                    contentDescription = if (showPin) "مخفی کردن رمز" else "نمایش رمز",
                                    onClick = { showPin = !showPin }
                                )
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (enteredPin.isNotBlank()) {
                                        onUnlockWithPin(enteredPin)
                                    }
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("pin_unlock_input")
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        AppPrimaryButton(
                            text = "باز کردن قفل",
                            onClick = { onUnlockWithPin(enteredPin) },
                            enabled = enteredPin.isNotBlank(),
                            icon = AppIcons.LockOpen,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("pin_unlock_submit_btn")
                        )
                    }

                    if (uiState.settings.isBiometricEnabled &&
                        !uiState.isKeystorePermanentlyUnavailable &&
                        canUseDeviceAuth
                    ) {
                        Spacer(modifier = Modifier.height(10.dp))
                        AppOutlinedButton(
                            text = "ورود با اثر انگشت یا قفل دستگاه",
                            onClick = onTriggerBiometricUnlock,
                            icon = AppIcons.Fingerprint,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("pin_biometric_unlock_btn")
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    AppTextButton(
                        text = "فراموشی رمز / بازیابی با احراز هویت دستگاه",
                        onClick = onTriggerPinRecovery,
                        icon = AppIcons.Security,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pin_recovery_trigger_btn")
                    )
                }
            }
        }
    }
}
