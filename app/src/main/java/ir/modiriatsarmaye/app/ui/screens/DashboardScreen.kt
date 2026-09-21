package ir.modiriatsarmaye.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.modiriatsarmaye.app.data.model.CurrencyType
import ir.modiriatsarmaye.app.data.model.TransactionEntity
import ir.modiriatsarmaye.app.ui.components.*
import ir.modiriatsarmaye.app.ui.theme.*
import ir.modiriatsarmaye.app.ui.viewmodel.WealthUiState
import ir.modiriatsarmaye.app.util.PersianUtils

@Composable
fun DashboardScreen(
    uiState: WealthUiState,
    onAddTransactionClick: () -> Unit,
    onNavigateToPortfolio: () -> Unit,
    onNavigateToTransactions: () -> Unit,
    onNavigateToReports: (Int) -> Unit, // 0: Rebalance, 1: Retirement, 2: Goals, etc.
    onTransactionClick: (TransactionEntity) -> Unit
) {
    val summary = uiState.portfolioSummary
    val settings = uiState.settings
    var hideAmounts by remember { mutableStateOf(settings.hideSensitiveAmounts) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("dashboard_screen")
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ۱. هدر اصلی داشبورد
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "مدیریت سرمایه",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "سامانه جامع پایش ثروت و پرتفوی",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = { hideAmounts = !hideAmounts },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Icon(
                        imageVector = if (hideAmounts) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "مخفی‌سازی مبالغ",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // بخش عیب‌یابی فنی طلا (Real-device Diagnostics)
        val lastReport = uiState.lastMarketUpdateReport
        if (lastReport != null && (lastReport.goldDiagnostics.isNotEmpty() || lastReport.pipelineDiagnostic != null)) {
            item {
                GoldDiagnosticCard(
                    diagnostics = lastReport.goldDiagnostics,
                    pipelineDiagnostic = lastReport.pipelineDiagnostic
                )
            }
        }

        // ۲. کارت جامع ارزش کل پرتفوی و دارایی خالص (Hero Card)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dashboard_hero_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ارزش کل سبد دارایی‌ها",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        StatusBadge(
                            text = if (summary.totalReturnPct >= 0) "+${PersianUtils.formatNumber(summary.totalReturnPct, 1)}٪" else "${PersianUtils.formatNumber(summary.totalReturnPct, 1)}٪",
                            color = if (summary.totalReturnPct >= 0) ProfitGreen else LossRed,
                            backgroundColor = MaterialTheme.colorScheme.surface
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (hideAmounts) "••••••••" else PersianUtils.formatMoney(summary.totalPortfolioValueToman, settings.displayCurrency),
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = 28.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "کل مبلغ سرمایه‌گذاری‌شده",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                            Text(
                                text = if (hideAmounts) "••••••" else PersianUtils.formatMoney(summary.totalInvestedCostToman, settings.displayCurrency),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "سود / زیان کل",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                            Text(
                                text = if (hideAmounts) "••••••" else PersianUtils.formatMoney(summary.totalProfitLossToman, settings.displayCurrency),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (summary.totalProfitLossToman >= 0) ProfitGreen else LossRed
                            )
                        }
                    }
                }
            }
        }

        // ۳. کارت‌های شاخص‌های مالی (دارایی خالص و پس‌انداز ماهانه)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                WealthMetricCard(
                    title = "دارایی خالص (Net Worth)",
                    value = if (hideAmounts) "••••••" else PersianUtils.formatMoney(summary.netWorthToman, settings.displayCurrency),
                    subtitle = if (summary.totalLiabilitiesToman > 0) "بدهی: ${PersianUtils.formatMoney(summary.totalLiabilitiesToman, settings.displayCurrency)}" else "بدون بدهی ثبت‌شده",
                    icon = Icons.Default.AccountBalance,
                    modifier = Modifier.weight(1f)
                )

                WealthMetricCard(
                    title = "سرمایه‌گذاری ماهانه",
                    value = if (hideAmounts) "••••••" else PersianUtils.formatMoney(settings.monthlyInvestmentToman, settings.displayCurrency),
                    subtitle = "افزایش سالانه: ${PersianUtils.toPersianDigits(settings.annualInvestmentIncreasePct.toInt().toString())}٪",
                    icon = Icons.Default.Savings,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ۴. هشدار بازتنظیم پرتفوی در صورت انحراف از اهداف (Rebalancing Alert)
        if (summary.hasRebalanceAlert) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToReports(0) }
                        .testTag("rebalance_alert_card"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "نیاز به بازتنظیم پرتفوی (Rebalance)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "تخصیص برخی دارایی‌ها بیش از ۵٪ از هدف انحراف دارد. برای مشاهده پیشنهادات ضربه بزنید.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // ۵. نوار ترکیب سبد دارایی‌ها (Portfolio Composition / Asset Allocation)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("portfolio_composition_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ترکیب سبد دارایی‌ها",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(
                            onClick = onNavigateToPortfolio,
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("view_all_holdings_btn")
                        ) {
                            Text("مشاهده همه دارایی‌ها")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val hasPortfolioValue = summary.totalPortfolioValueToman > 0.0 && summary.holdings.isNotEmpty()
                    val validAllocations = summary.allocationByClass.entries
                        .filter { (_, pct) -> !pct.isNaN() && !pct.isInfinite() && pct > 0.05 }
                        .sortedByDescending { it.value }

                    if (!hasPortfolioValue || validAllocations.isEmpty()) {
                        // حالت خالی شفاف و کاربرپسند بدون نمایش هرگونه NaN یا Infinity
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .testTag("composition_empty_state"),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PieChart,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(36.dp)
                                )
                                Text(
                                    text = "هنوز دارایی فعالی برای نمایش ترکیب سبد ثبت نشده است",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "با ثبت معاملات خرید، ترکیب درصدی دارایی‌ها در اینجا محاسبه و نمایش داده می‌شود.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        // میله گرافیکی چندبخشی تخصیص دارایی متناسب با درصدها
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(14.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            validAllocations.forEach { (assetClass, pct) ->
                                val color = when (assetClass) {
                                    ir.modiriatsarmaye.app.data.model.AssetClass.GOLD, ir.modiriatsarmaye.app.data.model.AssetClass.GOLD_FUND -> GoldAccent
                                    ir.modiriatsarmaye.app.data.model.AssetClass.EQUITY_FUND, ir.modiriatsarmaye.app.data.model.AssetClass.STOCK -> EmeraldPrimary
                                    ir.modiriatsarmaye.app.data.model.AssetClass.FIXED_INCOME_FUND -> InfoBlue
                                    ir.modiriatsarmaye.app.data.model.AssetClass.USD, ir.modiriatsarmaye.app.data.model.AssetClass.EUR, ir.modiriatsarmaye.app.data.model.AssetClass.AED -> Color(0xFF0D9488)
                                    ir.modiriatsarmaye.app.data.model.AssetClass.CASH, ir.modiriatsarmaye.app.data.model.AssetClass.DEPOSIT -> Color(0xFF8B5CF6)
                                    else -> Color(0xFF6B7280)
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(pct.toFloat().coerceAtLeast(0.01f))
                                        .fillMaxHeight()
                                        .background(color)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // فهرست عمودی خوانا و تفکیک‌شده برای راهنمای ترکیب سبد دارایی‌ها با رعایت استانداردهای دسترس‌پذیری
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("composition_legend_list"),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            validAllocations.forEach { (ac, pct) ->
                                val color = when (ac) {
                                    ir.modiriatsarmaye.app.data.model.AssetClass.GOLD, ir.modiriatsarmaye.app.data.model.AssetClass.GOLD_FUND -> GoldAccent
                                    ir.modiriatsarmaye.app.data.model.AssetClass.EQUITY_FUND, ir.modiriatsarmaye.app.data.model.AssetClass.STOCK -> EmeraldPrimary
                                    ir.modiriatsarmaye.app.data.model.AssetClass.FIXED_INCOME_FUND -> InfoBlue
                                    ir.modiriatsarmaye.app.data.model.AssetClass.USD, ir.modiriatsarmaye.app.data.model.AssetClass.EUR, ir.modiriatsarmaye.app.data.model.AssetClass.AED -> Color(0xFF0D9488)
                                    ir.modiriatsarmaye.app.data.model.AssetClass.CASH, ir.modiriatsarmaye.app.data.model.AssetClass.DEPOSIT -> Color(0xFF8B5CF6)
                                    else -> Color(0xFF6B7280)
                                }
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp)),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .defaultMinSize(minHeight = 48.dp)
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f, fill = false)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(12.dp)
                                                    .clip(CircleShape)
                                                    .background(color)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                text = ac.titleFa,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(12.dp))

                                        Text(
                                            text = "${PersianUtils.formatNumber(pct, 1)}٪",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = color
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ۶. پیش‌نمایش شبیه‌ساز بازنشستگی
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToReports(1) },
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
                                imageVector = Icons.Default.Timeline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "شبیه‌ساز بازنشستگی (${PersianUtils.toPersianDigits(settings.retirementHorizonYears.toString())} ساله)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "فرض شبیه‌سازی — نه پیش‌بینی قطعی بازار",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    val balancedScenario = uiState.retirementScenarios.getOrNull(1)
                    if (balancedScenario != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("سناریوی متعادل (${PersianUtils.toPersianDigits(settings.scenarioBalancedReturnPct.toInt().toString())}٪)", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    text = if (hideAmounts) "••••••" else PersianUtils.formatMoney(balancedScenario.finalNominalValueToman, settings.displayCurrency),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("ارزش تعدیل‌شده با تورم", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    text = if (hideAmounts) "••••••" else PersianUtils.formatMoney(balancedScenario.finalRealValueToman, settings.displayCurrency),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = GoldDark
                                )
                            }
                        }
                    }
                }
            }
        }

        // ۷. آخرین تراکنش‌ها (Recent Transactions)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "آخرین تراکنش‌ها",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onNavigateToTransactions) {
                    Text("مشاهده همه (${PersianUtils.toPersianDigits(uiState.transactions.size.toString())})")
                }
            }
        }

        if (uiState.transactions.isEmpty()) {
            item {
                EmptyStateView(
                    title = "تراکنشی ثبت نشده است",
                    description = "با دکمه «+ ثبت تراکنش» اولین خرید یا سرمایه‌گذاری خود را اضافه کنید.",
                    actionButtonText = "+ ثبت تراکنش",
                    onActionClick = onAddTransactionClick
                )
            }
        } else {
            items(uiState.transactions.take(4)) { tx ->
                TransactionCardItem(
                    transaction = tx,
                    displayCurrency = settings.displayCurrency,
                    hideAmounts = hideAmounts,
                    onClick = { onTransactionClick(tx) }
                )
            }
        }
    }
}

@Composable
fun TransactionCardItem(
    transaction: TransactionEntity,
    displayCurrency: CurrencyType,
    hideAmounts: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("transaction_item_${transaction.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                AssetClassBadge(assetClass = transaction.assetClass)
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = transaction.assetName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${transaction.action.titleFa} • ${PersianUtils.formatNumber(transaction.quantity)} ${transaction.unit} • ${transaction.datePersian}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                if (transaction.totalAmount != null) {
                    Text(
                        text = if (hideAmounts) "••••••" else PersianUtils.formatMoney(transaction.totalAmount, transaction.currency),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (transaction.action == ir.modiriatsarmaye.app.data.model.TransactionAction.BUY) MaterialTheme.colorScheme.onSurface else ProfitGreen
                    )
                    if (transaction.currency == CurrencyType.RIAL) {
                        Text(
                            text = "معادل: ${PersianUtils.formatMoney(PersianUtils.rialToToman(transaction.totalAmount), CurrencyType.TOMAN)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    StatusBadge(
                        text = "قیمت خرید نامشخص",
                        color = MaterialTheme.colorScheme.error,
                        backgroundColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}
