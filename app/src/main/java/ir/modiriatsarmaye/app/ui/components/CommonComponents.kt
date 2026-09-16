package ir.modiriatsarmaye.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.modiriatsarmaye.app.data.model.AssetClass
import ir.modiriatsarmaye.app.data.model.CurrencyType
import ir.modiriatsarmaye.app.ui.theme.*
import ir.modiriatsarmaye.app.util.PersianUtils

@Composable
fun WealthMetricCard(
    title: String,
    value: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    isPositive: Boolean? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("metric_card_${title.replace(" ", "_")}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (icon != null) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp),
                fontWeight = FontWeight.Bold,
                color = when (isPositive) {
                    true -> ProfitGreen
                    false -> LossRed
                    null -> MaterialTheme.colorScheme.onSurface
                }
            )

            if (subtitle != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (isPositive) {
                        true -> ProfitGreen
                        false -> LossRed
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
fun StatusBadge(
    text: String,
    color: Color,
    backgroundColor: Color = color.copy(alpha = 0.15f),
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
fun AssetClassBadge(
    assetClass: AssetClass,
    modifier: Modifier = Modifier
) {
    val (color, bg) = when (assetClass) {
        AssetClass.GOLD, AssetClass.GOLD_FUND -> Pair(GoldDark, GoldLight.copy(alpha = 0.4f))
        AssetClass.EQUITY_FUND, AssetClass.STOCK -> Pair(EmeraldPrimary, EmeraldContainer.copy(alpha = 0.6f))
        AssetClass.FIXED_INCOME_FUND -> Pair(InfoBlue, InfoBlue.copy(alpha = 0.15f))
        AssetClass.USD, AssetClass.EUR, AssetClass.AED -> Pair(Color(0xFF0D9488), Color(0xFFCCFBF1))
        AssetClass.DEPOSIT, AssetClass.CASH -> Pair(Color(0xFF6B7280), Color(0xFFF3F4F6))
        else -> Pair(Color(0xFF8B5CF6), Color(0xFFEDE9FE))
    }

    StatusBadge(
        text = assetClass.titleFa,
        color = color,
        backgroundColor = bg,
        modifier = modifier
    )
}

@Composable
fun UpdatePriceDialog(
    initialName: String,
    initialSymbol: String,
    initialAssetClass: AssetClass,
    currentPrice: Double?,
    currentCurrency: CurrencyType,
    onDismiss: () -> Unit,
    onSave: (Double, CurrencyType) -> Unit,
    onSaveWithSymbol: ((Double, CurrencyType, String) -> Unit)? = null
) {
    var priceText by remember { mutableStateOf(currentPrice?.toLong()?.toString() ?: "") }
    var symbolText by remember { mutableStateOf(initialSymbol) }
    var selectedCurrency by remember { mutableStateOf(currentCurrency) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val symbolLabel = when (initialAssetClass) {
        AssetClass.STOCK -> "نماد بورسی"
        AssetClass.EQUITY_FUND, AssetClass.FIXED_INCOME_FUND, AssetClass.GOLD_FUND -> "نماد صندوق"
        else -> "نماد دارایی"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "بروزرسانی قیمت روز: $initialName",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "قیمت لحظه‌ای یا روز این دارایی را برای محاسبه دقیق سود و زیان وارد کنید:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (initialAssetClass in listOf(AssetClass.STOCK, AssetClass.EQUITY_FUND, AssetClass.FIXED_INCOME_FUND, AssetClass.GOLD_FUND)) {
                    OutlinedTextField(
                        value = symbolText,
                        onValueChange = { symbolText = it },
                        label = { Text(symbolLabel) },
                        placeholder = { Text("مثال: فملی، شپنا، آگاس") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("symbol_edit_field")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                OutlinedTextField(
                    value = priceText,
                    onValueChange = {
                        priceText = PersianUtils.toEnglishDigits(it)
                        errorMessage = null
                    },
                    label = { Text("قیمت هر واحد") },
                    singleLine = true,
                    isError = errorMessage != null,
                    supportingText = {
                        if (errorMessage != null) {
                            Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                        } else if (priceText.isNotBlank()) {
                            val p = priceText.toDoubleOrNull() ?: 0.0
                            if (selectedCurrency == CurrencyType.RIAL) {
                                Text("معادل: ${PersianUtils.formatMoney(PersianUtils.rialToToman(p), CurrencyType.TOMAN)}")
                            } else {
                                Text("معادل: ${PersianUtils.formatMoney(PersianUtils.tomanToRial(p), CurrencyType.RIAL)}")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("price_input_field")
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedCurrency == CurrencyType.TOMAN,
                        onClick = { selectedCurrency = CurrencyType.TOMAN },
                        label = { Text("به تومان") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedCurrency == CurrencyType.RIAL,
                        onClick = { selectedCurrency = CurrencyType.RIAL },
                        label = { Text("به ریال") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val p = priceText.toDoubleOrNull()
                    if (p == null || p <= 0) {
                        errorMessage = "لطفاً مبلغ معتبر و بزرگتر از صفر وارد کنید."
                    } else {
                        if (onSaveWithSymbol != null) {
                            onSaveWithSymbol(p, selectedCurrency, symbolText.trim())
                        } else {
                            onSave(p, selectedCurrency)
                        }
                        onDismiss()
                    }
                },
                modifier = Modifier.testTag("save_price_button")
            ) {
                Text("ذخیره قیمت")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("انصراف")
            }
        }
    )
}

@Composable
fun ConfirmationDialog(
    title: String,
    message: String,
    confirmButtonText: String = "تایید و حذف",
    isDestructive: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                colors = if (isDestructive) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(confirmButtonText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("انصراف")
            }
        }
    )
}

@Composable
fun EmptyStateCard(
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        EmptyStateView(
            title = title,
            description = description,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
fun EmptyStateView(
    title: String,
    description: String,
    icon: ImageVector = Icons.Default.Inbox,
    actionButtonText: String? = null,
    onActionClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionButtonText != null && onActionClick != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onActionClick) {
                Text(actionButtonText)
            }
        }
    }
}
