package ir.modiriatsarmaye.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.modiriatsarmaye.app.data.market.StrategyDiagnostic
import ir.modiriatsarmaye.app.data.model.PriceStatus
import ir.modiriatsarmaye.app.data.model.SyncPipelineDiagnostic
import ir.modiriatsarmaye.app.ui.theme.LossRed
import ir.modiriatsarmaye.app.ui.theme.ProfitGreen
import ir.modiriatsarmaye.app.ui.theme.WarningYellow
import ir.modiriatsarmaye.app.util.PersianUtils

/**
 * کارت جامع عیب‌یابی و گزارش فنی نرخ سهام (Stock Diagnostic Card)
 * نمایش دقیق ۷ مرحله اعتبارسنجی:
 * 1. Provider Result
 * 2. Symbol Mapping Result
 * 3. Parsing Result
 * 4. Persistence Result
 * 5. Room Read-back Result
 * 6. Portfolio Result
 * 7. UI Result
 *
 * و نمایش ۱۰ فیلد کلیدی تشخیصی:
 * - Stock symbol
 * - Provider URL
 * - HTTP status
 * - Content type
 * - Parser used
 * - Extracted symbol
 * - Extracted price
 * - Price unit
 * - Update timestamp
 * - Error message
 * به همراه دکمه‌های بروزرسانی کلی و تکی قیمت سهام.
 */
@Composable
fun StockDiagnosticCard(
    diagnostics: List<StrategyDiagnostic>,
    modifier: Modifier = Modifier,
    pipelineDiagnostic: SyncPipelineDiagnostic? = null,
    onUpdateAllStocks: (() -> Unit)? = null,
    onUpdateIndividualStock: ((String) -> Unit)? = null,
    initialExpanded: Boolean = false
) {
    if (diagnostics.isEmpty() && pipelineDiagnostic == null) return

    var isExpanded by remember { mutableStateOf(initialExpanded) }
    var showSingleSymbolDialog by remember { mutableStateOf(false) }
    var inputSymbol by remember { mutableStateOf("") }

    val primaryDiag = diagnostics.firstOrNull { it.isSuccess } ?: diagnostics.firstOrNull()

    val isCompleteSuccess = pipelineDiagnostic?.let {
        it.providerResult && it.persistenceSuccess && it.readBackSuccess && it.portfolioCalculationSuccess
    } ?: diagnostics.any { it.isSuccess }

    val statusText = if (isCompleteSuccess) "SUCCESS" else "FAILED"
    val statusColor = if (isCompleteSuccess) ProfitGreen else LossRed

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("stock_diagnostic_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // هدر کارت عیب‌یابی سهام با قابلیت باز و بسته شدن
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.QueryStats,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "جزئیات فنی و عیب‌یابی نرخ سهام",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = statusColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "باز/بسته کردن عیب‌یابی سهام",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // دکمه‌های عملیاتی: بروزرسانی همه سهام / بروزرسانی یک سهم مشخص
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onUpdateAllStocks != null) {
                    Button(
                        onClick = onUpdateAllStocks,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("update_all_stocks_btn"),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("بروزرسانی همه سهام", style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (onUpdateIndividualStock != null) {
                    OutlinedButton(
                        onClick = { showSingleSymbolDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("update_single_stock_btn"),
                        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("استعلام نماد تکی", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(10.dp))

                    // بخش ۱: مراحل ۷گانه عیب‌یابی (7 Diagnostic Stages)
                    Text(
                        text = "مراحل ۷‌گانه خط لوله قیمت سهام (Diagnostic Stages):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    val providerResult = pipelineDiagnostic?.providerResult ?: primaryDiag?.providerResult ?: primaryDiag?.isSuccess ?: false
                    val symbolMappingResult = pipelineDiagnostic?.assetMappingSuccess ?: primaryDiag?.symbolMappingSuccess ?: (primaryDiag?.extractedSymbol?.isNotBlank() == true)
                    val parsingResult = pipelineDiagnostic?.parserSuccess ?: primaryDiag?.parsingSuccess ?: primaryDiag?.isSuccess ?: false
                    val persistenceResult = pipelineDiagnostic?.persistenceSuccess ?: primaryDiag?.persistenceSuccess ?: false
                    val readBackResult = pipelineDiagnostic?.readBackSuccess ?: primaryDiag?.readBackSuccess ?: false
                    val portfolioResult = pipelineDiagnostic?.portfolioCalculationSuccess ?: primaryDiag?.portfolioSuccess ?: false
                    val uiResult = pipelineDiagnostic?.finalUiState ?: primaryDiag?.finalUiState ?: if (isCompleteSuccess) PriceStatus.FRESH else PriceStatus.UNAVAILABLE

                    DiagnosticStageRow("Provider Result", providerResult)
                    DiagnosticStageRow("Symbol Mapping Result", symbolMappingResult)
                    DiagnosticStageRow("Parsing Result", parsingResult)
                    DiagnosticStageRow("Persistence Result", persistenceResult)
                    DiagnosticStageRow("Room Read-back Result", readBackResult)
                    DiagnosticStageRow("Portfolio Result", portfolioResult)

                    // نمایش UI Result
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "UI Result:",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = uiResult.name,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = when (uiResult) {
                                PriceStatus.FRESH -> ProfitGreen
                                PriceStatus.STALE -> WarningYellow
                                PriceStatus.MANUAL -> MaterialTheme.colorScheme.primary
                                PriceStatus.UNAVAILABLE -> LossRed
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(10.dp))

                    // بخش ۲: ۱۰ فیلد کلیدی تشخیصی (Key Diagnostic Information)
                    Text(
                        text = "مشخصات فنی و استعلام (Diagnostic Information):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    val stockSymbol = primaryDiag?.symbol?.ifBlank { primaryDiag.extractedSymbol }
                        ?: pipelineDiagnostic?.mappedAssetId?.ifBlank { "نامشخص" }
                        ?: "نامشخص"
                    val providerUrl = primaryDiag?.url?.ifBlank { "-" } ?: "-"
                    val httpStatus = primaryDiag?.httpStatusCode ?: pipelineDiagnostic?.httpStatusCode ?: 0
                    val contentType = primaryDiag?.contentType ?: "-"
                    val parserUsed = primaryDiag?.parserStrategy?.ifBlank { "TSETMC / TGJU Parser" } ?: "TSETMC / TGJU Parser"
                    val extractedSymbol = primaryDiag?.extractedSymbol?.ifBlank { pipelineDiagnostic?.mappedAssetId } ?: "-"
                    val extractedPriceRial = primaryDiag?.extractedPriceRial ?: pipelineDiagnostic?.extractedPriceRial
                    val priceUnit = primaryDiag?.priceUnit?.ifBlank { "ریال / سهم" } ?: "ریال / سهم"
                    val updateTimestamp = primaryDiag?.updateTimestamp ?: System.currentTimeMillis()
                    val errorMessage = primaryDiag?.errorMessage
                        ?: primaryDiag?.parserFailureReason
                        ?: pipelineDiagnostic?.failureReason
                        ?: "بدون خطا"

                    StockInfoRow(label = "Stock symbol", value = stockSymbol)
                    StockInfoRow(label = "Provider URL", value = providerUrl)
                    StockInfoRow(
                        label = "HTTP status",
                        value = if (httpStatus > 0) "$httpStatus" else "عدم ارتباط",
                        valueColor = if (httpStatus == 200) ProfitGreen else LossRed
                    )
                    StockInfoRow(label = "Content type", value = contentType)
                    StockInfoRow(label = "Parser used", value = parserUsed)
                    val providerName = primaryDiag?.providerName ?: pipelineDiagnostic?.providerName
                    if (!providerName.isNullOrBlank()) {
                        StockInfoRow(label = "Provider Name", value = providerName)
                    }
                    val insCode = primaryDiag?.insCode ?: pipelineDiagnostic?.insCode
                    if (!insCode.isNullOrBlank()) {
                        StockInfoRow(label = "TSETMC InsCode", value = insCode)
                    }
                    val isin = primaryDiag?.isin ?: pipelineDiagnostic?.isin
                    if (!isin.isNullOrBlank()) {
                        StockInfoRow(label = "ISIN Code", value = isin)
                    }
                    StockInfoRow(label = "Extracted symbol", value = extractedSymbol)
                    StockInfoRow(
                        label = "Extracted price",
                        value = if (extractedPriceRial != null) "${PersianUtils.formatMoney(extractedPriceRial, ir.modiriatsarmaye.app.data.model.CurrencyType.RIAL)}" else "یافت نشد",
                        valueColor = if (extractedPriceRial != null) ProfitGreen else LossRed
                    )
                    // تفکیک قیمت آخرین معامله و پایانی در صورت وجود
                    if (primaryDiag?.latestPriceRial != null || primaryDiag?.closingPriceRial != null) {
                        if (primaryDiag.latestPriceRial != null) {
                            StockInfoRow(
                                label = "  • آخرین معامله (Last Trade)",
                                value = "${PersianUtils.formatMoney(primaryDiag.latestPriceRial, ir.modiriatsarmaye.app.data.model.CurrencyType.RIAL)}"
                            )
                        }
                        if (primaryDiag.closingPriceRial != null) {
                            StockInfoRow(
                                label = "  • قیمت پایانی (Closing Price)",
                                value = "${PersianUtils.formatMoney(primaryDiag.closingPriceRial, ir.modiriatsarmaye.app.data.model.CurrencyType.RIAL)}"
                            )
                        }
                    }
                    StockInfoRow(label = "Price unit", value = priceUnit)
                    StockInfoRow(
                        label = "Update timestamp",
                        value = PersianUtils.formatTimestampToPersian(updateTimestamp)
                    )
                    StockInfoRow(
                        label = "Error message",
                        value = errorMessage,
                        valueColor = if (errorMessage == "بدون خطا") ProfitGreen else LossRed
                    )

                    // نمایش پیش‌نمایش پاسخ در صورت وجود خطا
                    if (primaryDiag?.responseBodyPreview?.isNotBlank() == true && !isCompleteSuccess) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Preview: ${primaryDiag.responseBodyPreview}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                modifier = Modifier.padding(6.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // دیالوگ ورود نماد جهت استعلام تکی
    if (showSingleSymbolDialog) {
        AlertDialog(
            onDismissRequest = { showSingleSymbolDialog = false },
            title = { Text("استعلام قیمت یک نماد سهام") },
            text = {
                Column {
                    Text(
                        text = "نماد دقیق بورسی دارایی را وارد نمایید (مانند: فولاد، فملی، شپنا):",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = inputSymbol,
                        onValueChange = { inputSymbol = it },
                        label = { Text("نماد بورسی") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("single_stock_symbol_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val sym = inputSymbol.trim()
                        if (sym.isNotBlank()) {
                            onUpdateIndividualStock?.invoke(sym)
                        }
                        showSingleSymbolDialog = false
                    },
                    modifier = Modifier.testTag("submit_single_stock_btn")
                ) {
                    Text("استعلام آنلاین")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSingleSymbolDialog = false }) {
                    Text("انصراف")
                }
            }
        )
    }
}

@Composable
private fun DiagnosticStageRow(
    stageName: String,
    isSuccess: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$stageName:",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = if (isSuccess) "SUCCESS" else "FAILED",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = if (isSuccess) ProfitGreen else LossRed
        )
    }
}

@Composable
private fun StockInfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = valueColor,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}
