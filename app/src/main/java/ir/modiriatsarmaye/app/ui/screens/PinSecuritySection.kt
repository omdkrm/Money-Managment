package ir.modiriatsarmaye.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ir.modiriatsarmaye.app.data.model.AppSettingsEntity
import ir.modiriatsarmaye.app.security.Pbkdf2PinHasher
import ir.modiriatsarmaye.app.ui.components.*
import ir.modiriatsarmaye.app.ui.theme.ProfitGreen
import ir.modiriatsarmaye.app.util.PersianUtils

@Composable
fun PinSecuritySection(
    settings: AppSettingsEntity,
    onSetOrChangePin: (currentPin: String?, newPin: String, onComplete: (Boolean, String?) -> Unit) -> Unit,
    onDisablePin: (currentPin: String, onComplete: (Boolean, String?) -> Unit) -> Unit,
    onToggleBiometric: (Boolean) -> Unit,
    onLockAppNow: () -> Unit
) {
    var showPinSetupDialog by remember { mutableStateOf(false) }
    var showDisablePinDialog by remember { mutableStateOf(false) }

    if (showPinSetupDialog) {
        PinSetupOrChangeDialog(
            isChangingExisting = settings.isPinEnabled,
            onDismiss = { showPinSetupDialog = false },
            onSubmit = { currentPin, newPin, onValidationResult ->
                onSetOrChangePin(currentPin, newPin) { ok, err ->
                    if (ok) {
                        showPinSetupDialog = false
                    } else {
                        onValidationResult(err ?: "ثبت رمز ورود انجام نشد.")
                    }
                }
            }
        )
    }

    if (showDisablePinDialog) {
        DisablePinDialog(
            onDismiss = { showDisablePinDialog = false },
            onSubmit = { currentPin, onValidationResult ->
                onDisablePin(currentPin) { ok, err ->
                    if (ok) {
                        showDisablePinDialog = false
                    } else {
                        onValidationResult(err ?: "غیرفعال‌سازی رمز انجام نشد.")
                    }
                }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("pin_security_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = AppIcons.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "امنیت و رمز ورود (PIN)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                StatusBadge(
                    text = if (settings.isPinEnabled) "فعال" else "غیرفعال",
                    color = if (settings.isPinEnabled) ProfitGreen else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "رمز ورود شما با الگوریتم PBKDF2-HMAC-SHA256 درهم‌سازی و با کلید سخت‌افزاری Android Keystore AES-GCM رمزنگاری می‌شود و در فایل‌های پشتیبان JSON قرار نمی‌گیرد.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))

            if (!settings.isPinEnabled) {
                AppPrimaryButton(
                    text = "تنظیم رمز ورود (PIN)",
                    onClick = { showPinSetupDialog = true },
                    icon = AppIcons.Lock,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("setup_pin_btn")
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = AppIcons.Fingerprint,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "ورود با اثر انگشت / قفل دستگاه",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "باز کردن سریع قفل علاوه بر رمز PIN",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = settings.isBiometricEnabled,
                        onCheckedChange = onToggleBiometric,
                        modifier = Modifier.testTag("biometric_toggle_switch")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                AppActionPair(
                    firstButton = { itemModifier ->
                        AppPrimaryButton(
                            text = "تغییر رمز ورود",
                            onClick = { showPinSetupDialog = true },
                            icon = AppIcons.Lock,
                            contentPadding = AppButtonDefaults.CompactContentPadding,
                            modifier = itemModifier.testTag("change_pin_btn")
                        )
                    },
                    secondButton = { itemModifier ->
                        AppOutlinedButton(
                            text = "غیرفعال‌سازی رمز",
                            onClick = { showDisablePinDialog = true },
                            borderColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.error,
                            contentPadding = AppButtonDefaults.CompactContentPadding,
                            modifier = itemModifier.testTag("disable_pin_btn")
                        )
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                AppTonalButton(
                    text = "قفل کردن فوری برنامه",
                    onClick = onLockAppNow,
                    icon = AppIcons.Lock,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("lock_app_now_btn")
                )
            }
        }
    }
}

@Composable
private fun PinSetupOrChangeDialog(
    isChangingExisting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (currentPin: String?, newPin: String, onError: (String) -> Unit) -> Unit
) {
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("pin_setup_dialog"),
        title = {
            Text(
                text = if (isChangingExisting) "تغییر رمز ورود (PIN)" else "تنظیم رمز ورود (PIN)",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (isChangingExisting) {
                    OutlinedTextField(
                        value = currentPin,
                        onValueChange = { raw ->
                            currentPin = PersianUtils.toEnglishDigits(raw).filter { it in '0'..'9' }.take(8)
                        },
                        label = { Text("رمز ورود فعلی") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pin_setup_current_input")
                    )
                }

                OutlinedTextField(
                    value = newPin,
                    onValueChange = { raw ->
                        newPin = PersianUtils.toEnglishDigits(raw).filter { it in '0'..'9' }.take(8)
                    },
                    label = { Text("رمز جدید (۴ تا ۸ رقم)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pin_setup_new_input")
                )

                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { raw ->
                        confirmPin = PersianUtils.toEnglishDigits(raw).filter { it in '0'..'9' }.take(8)
                    },
                    label = { Text("تکرار رمز جدید") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pin_setup_confirm_input")
                )
            }
        },
        confirmButton = {
            AppDialogActionPair(
                confirmText = "ذخیره رمز امن",
                onConfirm = {
                    val validNew = Pbkdf2PinHasher.validateNewPinFormat(newPin)
                    if (isChangingExisting && currentPin.isBlank()) {
                        errorMessage = "رمز ورود فعلی را وارد کنید."
                        return@AppDialogActionPair
                    }
                    if (validNew == null) {
                        errorMessage = "رمز جدید باید بین ۴ تا ۸ رقم عددی باشد."
                        return@AppDialogActionPair
                    }
                    if (validNew != Pbkdf2PinHasher.normalizeDigits(confirmPin)) {
                        errorMessage = "رمز جدید و تکرار آن یکسان نیستند."
                        return@AppDialogActionPair
                    }
                    onSubmit(if (isChangingExisting) currentPin else null, validNew) { err ->
                        errorMessage = err
                    }
                },
                dismissText = "انصراف",
                onDismiss = onDismiss,
                confirmTestTag = "confirm_pin_setup_btn",
                dismissTestTag = "cancel_pin_setup_btn"
            )
        }
    )
}

@Composable
private fun DisablePinDialog(
    onDismiss: () -> Unit,
    onSubmit: (currentPin: String, onError: (String) -> Unit) -> Unit
) {
    var currentPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("pin_disable_dialog"),
        title = {
            Text(
                text = "غیرفعال‌سازی قفل PIN",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "برای غیرفعال کردن قفل برنامه، رمز ورود فعلی خود را وارد کنید.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedTextField(
                    value = currentPin,
                    onValueChange = { raw ->
                        currentPin = PersianUtils.toEnglishDigits(raw).filter { it in '0'..'9' }.take(8)
                    },
                    label = { Text("رمز ورود فعلی") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pin_disable_current_input")
                )
            }
        },
        confirmButton = {
            AppDialogActionPair(
                confirmText = "غیرفعال‌سازی قفل",
                onConfirm = {
                    if (currentPin.isBlank()) {
                        errorMessage = "رمز ورود فعلی را وارد کنید."
                        return@AppDialogActionPair
                    }
                    onSubmit(currentPin) { err ->
                        errorMessage = err
                    }
                },
                dismissText = "انصراف",
                onDismiss = onDismiss,
                isDestructive = true,
                confirmTestTag = "confirm_disable_pin_btn",
                dismissTestTag = "cancel_disable_pin_btn"
            )
        }
    )
}
