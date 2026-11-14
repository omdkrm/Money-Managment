package ir.modiriatsarmaye.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.modiriatsarmaye.app.data.market.StockInstrumentMapper
import ir.modiriatsarmaye.app.data.model.*
import ir.modiriatsarmaye.app.ui.components.*
import ir.modiriatsarmaye.app.ui.theme.*
import ir.modiriatsarmaye.app.util.CalculationEngine
import ir.modiriatsarmaye.app.util.PersianUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetDetailScreen(
    detail: CalculationEngine.AssetDetailSummary,
    displayCurrency: CurrencyType,
    onBack: () -> Unit,
    onAddTransactionForAsset: () -> Unit,
    onEditTransaction: (TransactionEntity) -> Unit,
    onDeleteTransaction: (TransactionEntity) -> Unit,
    onUpdatePriceClick: (() -> Unit)? = null
) {
    BackHandler { onBack() }

    var transactionToDelete by remember { mutableStateOf<TransactionEntity?>(null) }

    if (transactionToDelete != null) {
        val targetTx = transactionToDelete!!
        ConfirmationDialog(
            title = "حذف تراکنش",
            message = "آیا از حذف تراکنش ${targetTx.action.titleFa} «${targetTx.assetName}» در تاریخ «${PersianUtils.toPersianDigits(targetTx.datePersian)}» اطمینان دارید؟",
            confirmButtonText = "تایید و حذف",
            isDestructive = true,
            onConfirm = {
                onDeleteTransaction(targetTx)
                transactionToDelete = null
            },
            onDismiss = { transactionToDelete = null }
        )
    }

    val resolvedSymbolText = remember(detail.assetClass, detail.assetSymbol, detail.assetName) {
        when {
            detail.assetClass == AssetClass.STOCK -> {
                StockInstrumentMapper.resolveStockSymbol(detail.assetSymbol)
                    ?: detail.assetSymbol.takeIf { it.isNotBlank() && it != detail.assetName }
            }
            detail.assetSymbol.isNotBlank() && !detail.assetSymbol.equals(detail.assetName, ignoreCase = true) -> {
                detail.assetSymbol
            }
            else -> null
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("asset_detail_screen"),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = detail.assetName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (resolvedSymbolText != null) {
                                "${detail.assetClass.titleFa} • نماد: $resolvedSymbolText"
                            } else {
                                detail.assetClass.titleFa
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    AppIconActionButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "بازگشت به پرتفوی",
                        onClick = onBack,
                        modifier = Modifier.testTag("asset_detail_back_btn")
                    )
                },
                actions = {
                    AppTonalButton(
                        text = "ثبت تراکنش",
                        onClick = onAddTransactionForAsset,
                        icon = Icons.Default.Add,
                        contentPadding = AppButtonDefaults.CompactContentPadding,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .testTag("asset_detail_add_tx_btn")
                    )
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .testTag("asset_detail_list"),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ۱. کارت خلاصه وضعیت دارایی (Asset Summary)
            item {
                AssetSummaryDetailCard(
                    detail = detail,
                    resolvedSymbol = resolvedSymbolText,
                    displayCurrency = displayCurrency,
                    onUpdatePriceClick = onUpdatePriceClick
                )
            }

            // ۲. سرتیتر تاریخچه تراکنش‌ها
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "تاریخچه تراکنش‌های دارایی",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "مرتب‌سازی از جدیدترین به قدیمی‌ترین",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    StatusBadge(
                        text = "${PersianUtils.toPersianDigits(detail.transactions.size.toString())} تراکنش",
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // ۳. فهرست تراکنش‌های دارایی یا وضعیت خالی
            if (detail.transactions.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("asset_detail_empty_history"),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    ) {
                        EmptyStateView(
                            title = "تراکنشی برای این دارایی ثبت نشده است",
                            description = "با ثبت تراکنش خرید، فروش یا دریافت سود نقدی (DPS)، تاریخچه کامل آن در اینجا نمایش داده می‌شود.",
                            actionButtonText = "+ ثبت تراکنش جدید",
                            onActionClick = onAddTransactionForAsset
                        )
                    }
                }
            } else {
                items(detail.transactions, key = { it.id }) { tx ->
                    AssetTransactionHistoryCard(
                        transaction = tx,
                        onEdit = { onEditTransaction(tx) },
                        onDelete = { transactionToDelete = tx }
                    )
                }
            }
        }
    }
}

@Composable
private fun AssetSummaryDetailCard(
    detail: CalculationEngine.AssetDetailSummary,
    resolvedSymbol: String?,
    displayCurrency: CurrencyType,
    onUpdatePriceClick: (() -> Unit)?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("asset_detail_summary_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // هدر نام و نماد و وضعیت موجودی
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    AssetClassBadge(assetClass = detail.assetClass)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = detail.assetName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (resolvedSymbol != null) {
                            Text(
                                text = "نماد: $resolvedSymbol",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                StatusBadge(
                    text = if (detail.isSoldOut) "تسویه‌شده (موجودی صفر)" else "دارایی فعال",
                    color = if (detail.isSoldOut) MaterialTheme.colorScheme.onSurfaceVariant else ProfitGreen
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ارزش روز کل و موجودی فعلی
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "موجودی فعلی",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${PersianUtils.formatNumber(detail.currentQuantity)} ${detail.unit}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = "ارزش روز کل",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    if (detail.currentValueToman != null) {
                        Text(
                            text = PersianUtils.formatMoney(detail.currentValueToman, displayCurrency),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            text = "قیمت روز نامشخص",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(modifier = Modifier.height(12.dp))

            // قیمت روز بازار و میانگین موزون قیمت خرید
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "قیمت روز بازار",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    if (detail.currentPriceToman != null && detail.currentPriceToman > 0.0) {
                        Text(
                            text = "${PersianUtils.formatMoney(detail.currentPriceToman, CurrencyType.TOMAN)} / ${detail.unit}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (detail.currentPriceCurrency == CurrencyType.RIAL && detail.currentPriceOriginal != null && detail.currentPriceOriginal > 0.0) {
                            Text(
                                text = "معادل: ${PersianUtils.formatMoney(detail.currentPriceOriginal, CurrencyType.RIAL)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        val statusColor = when (detail.currentPriceStatus) {
                            PriceStatus.FRESH -> ProfitGreen
                            PriceStatus.STALE -> WarningYellow
                            PriceStatus.MANUAL -> MaterialTheme.colorScheme.primary
                            PriceStatus.UNAVAILABLE -> MaterialTheme.colorScheme.error
                        }
                        val statusText = when (detail.currentPriceStatus) {
                            PriceStatus.FRESH -> "بروز"
                            PriceStatus.STALE -> "تاریخ‌گذشته"
                            PriceStatus.MANUAL -> "دستی"
                            PriceStatus.UNAVAILABLE -> "نامشخص"
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusBadge(text = statusText, color = statusColor)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = detail.currentPriceSource,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            text = "در دسترس نیست",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = "میانگین موزون قیمت خرید",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    when {
                        detail.isSoldOut -> {
                            Text(
                                text = PersianUtils.formatMoney(0.0, displayCurrency),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        detail.weightedAveragePurchasePriceToman != null -> {
                            Text(
                                text = PersianUtils.formatMoney(detail.weightedAveragePurchasePriceToman, displayCurrency),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        else -> {
                            StatusBadge(
                                text = "قیمت خرید نامشخص",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(modifier = Modifier.height(12.dp))

            // بهای تمام‌شده باقی‌مانده و سود/زیان تحقق‌نیافته
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "بهای تمام‌شده موقعیت فعلی",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = PersianUtils.formatMoney(detail.remainingCostBasisToman, displayCurrency),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = "سود / زیان تحقق‌نیافته",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    when {
                        detail.isSoldOut -> {
                            Text(
                                text = "${PersianUtils.formatMoney(0.0, displayCurrency)} (تسویه‌شده)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        detail.unrealizedProfitLossToman != null -> {
                            val isProfit = detail.unrealizedProfitLossToman >= 0.0
                            val pctText = detail.unrealizedReturnPercent?.let {
                                " (${if (isProfit) "+" else ""}${PersianUtils.formatNumber(it, 1)}٪)"
                            } ?: ""
                            Text(
                                text = "${PersianUtils.formatMoney(detail.unrealizedProfitLossToman, displayCurrency)}$pctText",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isProfit) ProfitGreen else LossRed
                            )
                        }
                        else -> {
                            Text(
                                text = "داده ناکافی",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (detail.totalDividendsToman > 0.0) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "مجموع سود نقدی دریافتی (DPS):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = PersianUtils.formatMoney(detail.totalDividendsToman, displayCurrency),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = ProfitGreen
                    )
                }
            }

            if (onUpdatePriceClick != null && !detail.isSoldOut) {
                Spacer(modifier = Modifier.height(14.dp))
                AppOutlinedButton(
                    text = "ویرایش یا ثبت دستی قیمت روز",
                    onClick = onUpdatePriceClick,
                    icon = AppIcons.EditCalendar,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("asset_detail_update_price_btn")
                )
            }
        }
    }
}

@Composable
private fun AssetTransactionHistoryCard(
    transaction: TransactionEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val actionColor = when (transaction.action) {
        TransactionAction.BUY -> MaterialTheme.colorScheme.primary
        TransactionAction.SELL -> GoldDark
        TransactionAction.DIVIDEND -> ProfitGreen
        TransactionAction.DEPOSIT -> InfoBlue
        TransactionAction.WITHDRAWAL -> LossRed
    }

    val grossAmount = if (transaction.unitPrice != null && transaction.quantity > 0.0) {
        transaction.quantity * transaction.unitPrice
    } else null

    val netAmount = transaction.totalAmount ?: grossAmount?.let { gross ->
        when (transaction.action) {
            TransactionAction.BUY -> gross + transaction.fees
            TransactionAction.SELL -> (gross - transaction.fees).coerceAtLeast(0.0)
            else -> gross
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("asset_detail_tx_card_${transaction.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ۱. نوع تراکنش و تاریخ شمسی
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusBadge(
                    text = transaction.action.titleFa,
                    color = actionColor
                )

                Text(
                    text = PersianUtils.toPersianDigits(transaction.datePersian),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ۲. مقدار و قیمت واحد
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "مقدار",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${PersianUtils.formatNumber(transaction.quantity)} ${transaction.unit}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = "قیمت واحد",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (transaction.unitPrice != null) {
                            PersianUtils.formatMoney(transaction.unitPrice, transaction.currency)
                        } else {
                            "ثبت نشده"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (transaction.unitPrice != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(modifier = Modifier.height(10.dp))

            // ۳. مبلغ خام، کارمزد (در صورت وجود) و مبلغ خالص/نهایی
            if (grossAmount != null) {
                DetailKeyValueRow(
                    label = "مبلغ کل (ناخالص):",
                    value = PersianUtils.formatMoney(grossAmount, transaction.currency)
                )
            }

            if (transaction.fees > 0.0) {
                Spacer(modifier = Modifier.height(4.dp))
                val feeLabel = if (transaction.commissionType == CommissionType.PERCENTAGE && transaction.commissionRate > 0.0) {
                    "کارمزد (${PersianUtils.formatNumber(transaction.commissionRate)}٪):"
                } else {
                    "کارمزد:"
                }
                DetailKeyValueRow(
                    label = feeLabel,
                    value = PersianUtils.formatMoney(transaction.fees, transaction.currency),
                    valueColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (netAmount != null) {
                Spacer(modifier = Modifier.height(4.dp))
                val netLabel = when (transaction.action) {
                    TransactionAction.BUY -> "مبلغ خالص پرداختی:"
                    TransactionAction.SELL -> "مبلغ خالص دریافتی:"
                    TransactionAction.DIVIDEND -> "مبلغ خالص سود:"
                    else -> "مبلغ خالص:"
                }
                DetailKeyValueRow(
                    label = netLabel,
                    value = PersianUtils.formatMoney(netAmount, transaction.currency),
                    valueColor = MaterialTheme.colorScheme.primary,
                    boldValue = true
                )
                if (transaction.currency == CurrencyType.RIAL) {
                    Text(
                        text = "معادل: ${PersianUtils.formatMoney(PersianUtils.rialToToman(netAmount), CurrencyType.TOMAN)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            } else {
                StatusBadge(
                    text = "مبلغ نهایی ثبت نشده است",
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (transaction.brokerOrSource.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "کارگزاری / منبع: ${transaction.brokerOrSource}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (transaction.notes.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "یادداشت: ${transaction.notes}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))

            // ۴. عملیات ویرایش و حذف یکپارچه
            TransactionCardActionRow(
                editLabel = "ویرایش",
                onEdit = onEdit,
                onDelete = onDelete,
                editTestTag = "asset_detail_edit_tx_${transaction.id}",
                deleteTestTag = "asset_detail_delete_tx_${transaction.id}"
            )
        }
    }
}

@Composable
private fun DetailKeyValueRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    boldValue: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (boldValue) FontWeight.Bold else FontWeight.SemiBold,
            color = valueColor
        )
    }
}
