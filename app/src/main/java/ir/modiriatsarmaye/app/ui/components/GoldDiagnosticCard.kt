package ir.modiriatsarmaye.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * کامپوننت گزارش و عیب‌یابی فنی همگام‌سازی آنلاین نرخ طلا (TGJU Diagnostic UI)
 * تفکیک مراحل: Provider Result -> Persistence Result -> Portfolio Result -> UI Result
 */
@Composable
fun GoldDiagnosticCard(
    diagnostics: List<StrategyDiagnostic>,
    modifier: Modifier = Modifier,
    pipelineDiagnostic: SyncPipelineDiagnostic? = null,
    initialExpanded: Boolean = false
) {
    if (diagnostics.isEmpty() && pipelineDiagnostic == null) return

    var isExpanded by remember { mutableStateOf(initialExpanded) }
    val primaryDiag = diagnostics.firstOrNull { it.isSuccess } ?: diagnostics.firstOrNull()

    // محاسبه وضعیت نهایی بر اساس خط لوله کامل
    val isCompleteSuccess = pipelineDiagnostic?.let {
        it.providerResult && it.persistenceSuccess && it.readBackSuccess && it.portfolioCalculationSuccess
    } ?: diagnostics.any { it.isSuccess }

    val statusText = if (isCompleteSuccess) "SUCCESS" else "FAILED"
    val statusColor = if (isCompleteSuccess) ProfitGreen else LossRed

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("gold_diagnostic_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // هدر قابل کلیک برای باز و بسته کردن «جزئیات فنی»
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
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val headerTitle = if (diagnostics.any { it.instrumentType == "STOCK" || it.instrumentType == "FUND" }) {
                        "جزئیات فنی استعلام نرخ بازار (طلا، سهام، صندوق‌ها)"
                    } else {
                        "جزئیات فنی دریافت نرخ طلا"
                    }
                    Text(
                        text = headerTitle,
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
                        contentDescription = "باز/بسته کردن جزئیات فنی",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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

                    // بخش ۱: مراحل خط لوله اعتبارسنجی (Pipeline Stages)
                    Text(
                        text = "مراحل چرخه همگام‌سازی (Pipeline Stages):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    val providerOk = pipelineDiagnostic?.providerResult ?: diagnostics.any { it.isSuccess }
                    val httpCode = pipelineDiagnostic?.httpStatusCode ?: primaryDiag?.httpStatusCode ?: 0
                    val parserOk = pipelineDiagnostic?.parserSuccess ?: (primaryDiag?.isSuccess == true)
                    val extractedPrice = pipelineDiagnostic?.extractedPriceRial ?: primaryDiag?.extractedPriceRial
                    val mappingOk = pipelineDiagnostic?.assetMappingSuccess ?: providerOk
                    val persistenceOk = pipelineDiagnostic?.persistenceSuccess ?: false
                    val readBackOk = pipelineDiagnostic?.readBackSuccess ?: false
                    val portfolioOk = pipelineDiagnostic?.portfolioCalculationSuccess ?: false
                    val uiState = pipelineDiagnostic?.finalUiState ?: (if (isCompleteSuccess) PriceStatus.FRESH else PriceStatus.UNAVAILABLE)

                    DiagnosticItemRow(
                        label = "Provider",
                        value = if (providerOk) "SUCCESS" else "FAILED",
                        valueColor = if (providerOk) ProfitGreen else LossRed
                    )
                    DiagnosticItemRow(
                        label = "HTTP",
                        value = if (httpCode > 0) "$httpCode" else "اتصال برقرار نشد"
                    )
                    DiagnosticItemRow(
                        label = "Parser",
                        value = if (parserOk) "SUCCESS" else "FAILED",
                        valueColor = if (parserOk) ProfitGreen else LossRed
                    )
                    if (extractedPrice != null) {
                        DiagnosticItemRow(
                            label = "Extracted Price",
                            value = "${PersianUtils.formatNumber(extractedPrice)} ریال",
                            valueColor = ProfitGreen
                        )
                    }
                    DiagnosticItemRow(
                        label = "Asset Mapping",
                        value = if (mappingOk) "SUCCESS" else "FAILED",
                        valueColor = if (mappingOk) ProfitGreen else LossRed
                    )
                    if (pipelineDiagnostic != null && pipelineDiagnostic.mappedAssetId.isNotBlank()) {
                        DiagnosticItemRow(
                            label = "Mapped Asset",
                            value = "${pipelineDiagnostic.mappedAssetId} (${pipelineDiagnostic.mappedAssetName})"
                        )
                    }
                    DiagnosticItemRow(
                        label = "Persistence",
                        value = if (persistenceOk) "SUCCESS" else if (providerOk) "FAILED" else "-",
                        valueColor = if (persistenceOk) ProfitGreen else if (providerOk) LossRed else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    DiagnosticItemRow(
                        label = "Read-back",
                        value = if (readBackOk) "SUCCESS" else if (persistenceOk) "FAILED" else "-",
                        valueColor = if (readBackOk) ProfitGreen else if (persistenceOk) LossRed else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    DiagnosticItemRow(
                        label = "Portfolio",
                        value = if (portfolioOk) "SUCCESS" else if (readBackOk) "FAILED" else "-",
                        valueColor = if (portfolioOk) ProfitGreen else if (readBackOk) LossRed else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    DiagnosticItemRow(
                        label = "Final UI State",
                        value = uiState.name,
                        valueColor = when (uiState) {
                            PriceStatus.FRESH -> ProfitGreen
                            PriceStatus.STALE -> WarningYellow
                            PriceStatus.MANUAL -> MaterialTheme.colorScheme.primary
                            PriceStatus.UNAVAILABLE -> LossRed
                        }
                    )

                    if (pipelineDiagnostic?.failureReason != null) {
                        DiagnosticItemRow(
                            label = "Failure Reason",
                            value = pipelineDiagnostic.failureReason,
                            valueColor = LossRed
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "ارزیابی تفکیکی راهبردها (TGJU Tests):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    // لیست راهبردها (TEST A, TEST B, TEST C)
                    diagnostics.forEach { diag ->
                        StrategyTestDetailCard(diag)
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StrategyTestDetailCard(diag: StrategyDiagnostic) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (diag.isSuccess) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${diag.testId}: ${diag.testNameFa}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (diag.isSuccess) "SUCCESS" else "FAILED",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (diag.isSuccess) ProfitGreen else LossRed
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = diag.url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "HTTP ${diag.httpStatusCode} • ${diag.contentType}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${diag.durationMs} میلی‌ثانیه",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!diag.isSuccess && diag.parserFailureReason != null) {
                Text(
                    text = "دلیل عدم موفقیت: ${diag.parserFailureReason}",
                    style = MaterialTheme.typography.labelSmall,
                    color = LossRed,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (diag.responseBodyPreview.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Body Preview: ${diag.responseBodyPreview}",
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

@Composable
private fun DiagnosticItemRow(
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
