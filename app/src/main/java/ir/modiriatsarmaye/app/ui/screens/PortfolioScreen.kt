package ir.modiriatsarmaye.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
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
import ir.modiriatsarmaye.app.data.market.StockInstrumentMapper
import ir.modiriatsarmaye.app.data.model.*
import ir.modiriatsarmaye.app.ui.components.*
import ir.modiriatsarmaye.app.ui.theme.*
import ir.modiriatsarmaye.app.ui.viewmodel.WealthUiState
import ir.modiriatsarmaye.app.util.HoldingItem
import ir.modiriatsarmaye.app.util.PersianUtils

@Composable
fun PortfolioScreen(
    uiState: WealthUiState,
    onUpdatePriceClick: (HoldingItem) -> Unit,
    onAddTransactionForAsset: (AssetClass, String) -> Unit,
    onAddDividendClick: () -> Unit,
    onSyncMarketPrices: () -> Unit,
    onUpdateAllStocks: (() -> Unit)? = null,
    onUpdateIndividualStock: ((String) -> Unit)? = null,
    onHoldingClick: (HoldingItem) -> Unit = {}
) {
    var selectedCategoryIndex by remember { mutableIntStateOf(0) }
    val categories = listOf("همه دارایی‌ها", "طلا و سکه", "صندوق طلا", "صندوق سهامی", "درآمد ثابت", "سهام", "ارز و دلار", "سود نقدی مجامع")

    val summary = uiState.portfolioSummary
    val displayCurrency = uiState.settings.displayCurrency

    // فیلتر کردن دارایی‌ها بر اساس تب انتخابی
    val filteredHoldings = remember(selectedCategoryIndex, summary.holdings) {
        when (selectedCategoryIndex) {
            0 -> summary.holdings // همه
            1 -> summary.holdings.filter { it.assetClass == AssetClass.GOLD }
            2 -> summary.holdings.filter { it.assetClass == AssetClass.GOLD_FUND }
            3 -> summary.holdings.filter { it.assetClass == AssetClass.EQUITY_FUND }
            4 -> summary.holdings.filter { it.assetClass == AssetClass.FIXED_INCOME_FUND }
            5 -> summary.holdings.filter { it.assetClass == AssetClass.STOCK }
            6 -> summary.holdings.filter { it.assetClass in listOf(AssetClass.USD, AssetClass.EUR, AssetClass.AED) }
            else -> emptyList()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("portfolio_screen_list"),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // هدر تب پرتفوی
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "سبد دارایی‌ها (پرتفوی)",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "مشتق‌شده مستقیم از سوابق تراکنش‌ها و نرخ‌های بازار",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // دکمه بروزرسانی سریع نرخ‌ها در هدر با هدف لمسی ۴۸ دی‌پی
                if (uiState.isMarketUpdating) {
                    Box(
                        modifier = Modifier
                            .size(AppButtonDefaults.MinTouchTarget)
                            .testTag("portfolio_sync_prices_btn"),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                } else {
                    AppIconActionButton(
                        icon = AppIcons.Sync,
                        contentDescription = "بروزرسانی آنلاین نرخ‌های بازار",
                        onClick = onSyncMarketPrices,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("portfolio_sync_prices_btn")
                    )
                }
            }
        }

        // کارت خطا یا گزارش همگام‌سازی ناموفق - صرفاً در صورتی که کلیه منابع ناموفق بوده و هیچ قیمتی بروز نشده باشد
        val lastReport = uiState.lastMarketUpdateReport
        if (lastReport != null && lastReport.updatedCount == 0 && (lastReport.pipelineDiagnostic == null || !lastReport.pipelineDiagnostic.providerResult)) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("sync_error_card"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(14.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = lastReport.messageFa,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = "داده‌های قبلی در پایگاه داده محلی حفظ شده‌اند.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                                )
                            }
                        }

                        AppDestructiveButton(
                            text = "تلاش مجدد",
                            onClick = onSyncMarketPrices,
                            icon = Icons.Default.Refresh,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("retry_sync_btn")
                        )
                    }
                }
            }
        }

        // بخش عیب‌یابی فنی طلا (Real-device Diagnostics)
        if (lastReport != null && (lastReport.goldDiagnostics.isNotEmpty() || lastReport.pipelineDiagnostic != null)) {
            item {
                GoldDiagnosticCard(
                    diagnostics = lastReport.goldDiagnostics,
                    pipelineDiagnostic = lastReport.pipelineDiagnostic
                )
            }
        }

        // بخش عیب‌یابی فنی سهام (Stock Diagnostics)
        if (lastReport != null && (lastReport.stockDiagnostics.isNotEmpty() || lastReport.stockPipelineDiagnostic != null || selectedCategoryIndex == 5)) {
            item {
                StockDiagnosticCard(
                    diagnostics = lastReport.stockDiagnostics,
                    pipelineDiagnostic = lastReport.stockPipelineDiagnostic,
                    bulkSummaryFa = lastReport.bulkUpdateSummaryFa,
                    onUpdateAllStocks = onUpdateAllStocks,
                    onUpdateIndividualStock = onUpdateIndividualStock
                )
            }
        }

        // کارت خلاصه پرتفوی
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "ارزش کل سبد",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = PersianUtils.formatMoney(summary.totalPortfolioValueToman, displayCurrency),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "بازدهی کل سبد",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${if (summary.totalReturnPct >= 0) "+" else ""}${PersianUtils.formatNumber(summary.totalReturnPct, 1)}٪",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (summary.totalReturnPct >= 0) ProfitGreen else LossRed
                        )
                    }
                }
            }
        }

        // نوتیس وضعیت بروزرسانی قیمت‌ها
        if (uiState.settings.lastPriceUpdateTimestamp > 0) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(imageVector = AppIcons.QueryStats, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "آخرین بروزرسانی بازار: ${PersianUtils.formatTimestampToPersian(uiState.settings.lastPriceUpdateTimestamp)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    AppTextButton(
                        text = "بروزرسانی مجدد",
                        onClick = onSyncMarketPrices
                    )
                }
            }
        }

        // دسته‌بندی تب‌ها (Chips)
        item {
            ScrollableTabRow(
                selectedTabIndex = selectedCategoryIndex,
                edgePadding = 0.dp,
                divider = {}
            ) {
                categories.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedCategoryIndex == index,
                        onClick = { selectedCategoryIndex = index },
                        text = {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selectedCategoryIndex == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }
        }

        // نمایش لیست دارایی‌ها یا سود مجامع بر اساس تب انتخابی
        if (selectedCategoryIndex == 7) {
            // تب سود نقدی مجامع
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "سودهای نقدی مصوب مجامع سهام (DPS)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    AppTonalButton(
                        text = "ثبت سود مجمع",
                        onClick = onAddDividendClick,
                        icon = Icons.Default.Add,
                        contentPadding = AppButtonDefaults.CompactContentPadding
                    )
                }
            }

            if (uiState.dividends.isEmpty()) {
                item {
                    EmptyStateCard(
                        title = "سود مجمعی ثبت نشده است",
                        description = "در صورت شرکت در مجمع عمومی سهام بورسی، سود نقدی تقسیمی (DPS) را در این بخش ثبت نمایید."
                    )
                }
            } else {
                items(uiState.dividends) { dividend ->
                    DividendCardItem(dividend = dividend, displayCurrency = displayCurrency)
                }
            }
        } else {
            // لیست دارایی‌های هلدینگ
            if (filteredHoldings.isEmpty()) {
                item {
                    EmptyStateCard(
                        title = "دارایی فعالی در این دسته وجود ندارد",
                        description = "با ثبت تراکنش‌های خرید در بخش تراکنش‌ها یا استفاده از دکمه ثبت تراکنش جدید، سبد سرمایه‌گذاری خود را تشکیل دهید."
                    )
                }
            } else {
                items(filteredHoldings) { holding ->
                    HoldingCardItem(
                        holding = holding,
                        displayCurrency = displayCurrency,
                        onUpdatePrice = { onUpdatePriceClick(holding) },
                        onClick = { onHoldingClick(holding) }
                    )
                }
            }
        }
    }
}

@Composable
fun HoldingCardItem(
    holding: HoldingItem,
    displayCurrency: CurrencyType,
    onUpdatePrice: () -> Unit,
    onClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("holding_card_${holding.assetName}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ۱. هدر کارت: کلاس دارایی، نام دارایی و درصد سهم از کل پرتفوی
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssetClassBadge(assetClass = holding.assetClass)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = holding.assetName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (holding.assetClass == AssetClass.STOCK) {
                            val validSymbol = StockInstrumentMapper.resolveStockSymbol(holding.assetSymbol)
                            if (validSymbol != null) {
                                Text(
                                    text = "نماد: $validSymbol",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    text = StockInstrumentMapper.SYMBOL_REQUIRED_LABEL,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        } else if (holding.assetSymbol.isNotBlank() && holding.assetSymbol != holding.assetName) {
                            Text(
                                text = holding.assetSymbol,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Text(
                    text = "${PersianUtils.formatNumber(holding.portfolioPercent, 1)}٪ از سبد",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ۲. ردیف اطلاعات مقداری و میانگین قیمت خرید
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "موجودی فعلی",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${PersianUtils.formatNumber(holding.quantity)} ${holding.unit}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "میانگین بهای خرید",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (holding.averagePurchasePriceToman != null) {
                        Text(
                            text = PersianUtils.formatMoney(holding.averagePurchasePriceToman, displayCurrency),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        StatusBadge(
                            text = "قیمت خرید نامشخص",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(modifier = Modifier.height(10.dp))

            // ۳. ردیف قیمت روز، منبع نرخ، وضعیت و رشد قیمت نسبت به میانگین خرید
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1.2f)) {
                    val priceLabel = if (holding.currentPriceStatus == PriceStatus.STALE) {
                        "آخرین قیمت معتبر"
                    } else {
                        "قیمت روز"
                    }
                    Text(
                        text = priceLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (holding.currentPriceToman != null && holding.currentPriceToman > 0.0) {
                        Text(
                            text = "${PersianUtils.formatMoney(holding.currentPriceToman, CurrencyType.TOMAN)} / ${holding.unit.ifBlank { "واحد" }}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (holding.currentPriceCurrency == CurrencyType.RIAL && holding.currentPriceOriginal != null && holding.currentPriceOriginal > 0.0) {
                            Text(
                                text = "معادل: ${PersianUtils.formatMoney(holding.currentPriceOriginal, CurrencyType.RIAL)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            text = "قیمت روز در دسترس نیست",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    // نشان منبع، وضعیت قیمت و تاریخ آخرین بروزرسانی
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val statusColor = when (holding.currentPriceStatus) {
                            PriceStatus.FRESH -> ProfitGreen
                            PriceStatus.STALE -> WarningYellow
                            PriceStatus.MANUAL -> MaterialTheme.colorScheme.primary
                            PriceStatus.UNAVAILABLE -> MaterialTheme.colorScheme.error
                        }
                        val statusText = when (holding.currentPriceStatus) {
                            PriceStatus.FRESH -> "بروز"
                            PriceStatus.STALE -> "تاریخ‌گذشته (حفظ آخرین نرخ)"
                            PriceStatus.MANUAL -> "دستی"
                            PriceStatus.UNAVAILABLE -> "نامشخص"
                        }
                        StatusBadge(text = statusText, color = statusColor)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "• ${holding.currentPriceSource}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (holding.currentPriceLastUpdated > 0L) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "آخرین بروزرسانی: ${PersianUtils.formatTimestampToPersian(holding.currentPriceLastUpdated)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ستون رشد قیمت روز دارایی نسبت به میانگین بهای خرید
                Column(modifier = Modifier.weight(0.8f), horizontalAlignment = Alignment.End) {
                    Text(
                        text = "رشد قیمت نسبت به میانگین خرید",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (holding.priceGrowthPercent != null) {
                        val isGrowth = holding.priceGrowthPercent >= 0
                        Text(
                            text = "${if (isGrowth) "+" else ""}${PersianUtils.formatNumber(holding.priceGrowthPercent, 1)}٪",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isGrowth) ProfitGreen else LossRed
                        )
                    } else {
                        Text(
                            text = "رشد قیمت قابل محاسبه نیست",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(modifier = Modifier.height(10.dp))

            // ۴. ردیف ارزش کل روز و سود/زیان کل سرمایه‌گذاری
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "ارزش روز کل دارایی",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (holding.currentValueToman != null) {
                        Text(
                            text = PersianUtils.formatMoney(holding.currentValueToman, displayCurrency),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    } else {
                        Text(
                            text = "قیمت روز در دسترس نیست",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "بازده کل سرمایه‌گذاری",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (holding.profitLossToman != null) {
                        val isProfit = holding.profitLossToman >= 0
                        Text(
                            text = "${PersianUtils.formatMoney(holding.profitLossToman, displayCurrency)} (${if (isProfit) "+" else ""}${PersianUtils.formatNumber(holding.returnPercent ?: 0.0, 1)}٪)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isProfit) ProfitGreen else LossRed
                        )
                    } else {
                        Text(
                            text = "داده ناکافی",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ۵. دکمه ویرایش دستی قیمت روز و راهنمای مشاهده جزئیات
            AppOutlinedButton(
                text = if (holding.currentPriceToman != null) {
                    "ویرایش یا ثبت دستی نرخ (${PersianUtils.formatMoney(holding.currentPriceOriginal, holding.currentPriceCurrency)})"
                } else {
                    "ثبت دستی قیمت روز دارایی"
                },
                onClick = onUpdatePrice,
                icon = AppIcons.EditCalendar,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("update_price_btn_${holding.assetName}")
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "مشاهده جزئیات و تاریخچه تراکنش‌ها",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun DividendCardItem(
    dividend: DividendEntity,
    displayCurrency: CurrencyType
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${dividend.stockName} (${dividend.stockSymbol})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                StatusBadge(
                    text = if (dividend.isPaid) "دریافت شده" else "در انتظار پرداخت",
                    color = if (dividend.isPaid) ProfitGreen else WarningYellow
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "سود هر سهم (DPS): ${PersianUtils.formatMoney(dividend.dpsToman, displayCurrency)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "تعداد سهام: ${PersianUtils.formatNumber(dividend.sharesHeld)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "مبلغ کل سود: ${PersianUtils.formatMoney(dividend.receivableAmountToman, displayCurrency)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = ProfitGreen
                )
                if (dividend.paymentDatePersian.isNotBlank()) {
                    Text(
                        text = "تاریخ پرداخت: ${dividend.paymentDatePersian}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
