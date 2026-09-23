package ir.modiriatsarmaye.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.modiriatsarmaye.app.data.model.*
import ir.modiriatsarmaye.app.ui.components.*
import ir.modiriatsarmaye.app.ui.theme.*
import ir.modiriatsarmaye.app.ui.viewmodel.WealthUiState
import ir.modiriatsarmaye.app.util.PersianUtils

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransactionsScreen(
    uiState: WealthUiState,
    onAddTransactionClick: () -> Unit,
    onEditTransaction: (TransactionEntity) -> Unit,
    onDeleteTransaction: (TransactionEntity) -> Unit,
    onSearchChange: (String) -> Unit,
    onSelectClassFilter: (AssetClass?) -> Unit,
    onSelectActionFilter: (TransactionAction?) -> Unit
) {
    var transactionToDelete by remember { mutableStateOf<TransactionEntity?>(null) }
    val displayCurrency = uiState.settings.displayCurrency

    if (transactionToDelete != null) {
        ConfirmationDialog(
            title = "حذف تراکنش",
            message = "آیا از حذف تراکنش «${transactionToDelete!!.assetName}» به تاریخ «${transactionToDelete!!.datePersian}» اطمینان دارید؟ تمامی محاسبات پرتفوی پس از حذف مجدداً محاسبه خواهند شد.",
            confirmButtonText = "تایید و حذف تراکنش",
            isDestructive = true,
            onConfirm = {
                onDeleteTransaction(transactionToDelete!!)
                transactionToDelete = null
            },
            onDismiss = { transactionToDelete = null }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // هدر
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "دفتر تراکنش‌ها (مرجع واحد)",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "کل اطلاعات پرتفوی از این سوابق مشتق می‌شود",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = onAddTransactionClick,
                    modifier = Modifier.testTag("add_transaction_top_btn"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("ثبت جدید")
                }
            }
        }

        // نوار جستجو
        item {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("جستجو در نام دارایی، نماد یا توضیحات...") },
                leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.searchQuery.isNotBlank()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(imageVector = Icons.Default.Clear, contentDescription = "پاک کردن")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("transaction_search_input"),
                shape = RoundedCornerShape(12.dp)
            )
        }

        // ردیف فیلتر دسته‌بندی دارایی
        item {
            Column {
                Text(
                    text = "فیلتر دسته دارایی:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("filter_row_asset_classes")
                ) {
                    item {
                        FilterChip(
                            selected = uiState.selectedAssetClassFilter == null,
                            onClick = { onSelectClassFilter(null) },
                            label = { Text("همه دسته‌ها") },
                            modifier = Modifier
                                .defaultMinSize(minHeight = 44.dp)
                                .testTag("filter_chip_class_all")
                        )
                    }
                    items(AssetClass.entries) { ac ->
                        FilterChip(
                            selected = uiState.selectedAssetClassFilter == ac,
                            onClick = { onSelectClassFilter(if (uiState.selectedAssetClassFilter == ac) null else ac) },
                            label = { Text(ac.titleFa) },
                            modifier = Modifier
                                .defaultMinSize(minHeight = 44.dp)
                                .testTag("filter_chip_class_${ac.name}")
                        )
                    }
                }
            }
        }

        // بخش فیلتر نوع عملیات تراکنش (FlowRow ریسپانسیو و خوانا در RTL بدون هیچ‌گونه فشردگی یا شکست متن)
        item {
            Column {
                Text(
                    text = "فیلتر نوع عملیات:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("filter_flowrow_transaction_actions"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = uiState.selectedActionFilter == null,
                        onClick = { onSelectActionFilter(null) },
                        label = {
                            Text(
                                text = "همه عملیات",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (uiState.selectedActionFilter == null) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        modifier = Modifier
                            .defaultMinSize(minHeight = 44.dp)
                            .testTag("filter_chip_action_all")
                    )

                    TransactionAction.entries.forEach { act ->
                        val isSelected = uiState.selectedActionFilter == act
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectActionFilter(if (isSelected) null else act) },
                            label = {
                                Text(
                                    text = act.titleFa,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            modifier = Modifier
                                .defaultMinSize(minHeight = 44.dp)
                                .testTag("filter_chip_action_${act.name}")
                        )
                    }
                }
            }
        }

        // نمایش تعداد نتایج
        item {
            Text(
                text = "تعداد تراکنش‌های منطبق: ${PersianUtils.toPersianDigits(uiState.filteredTransactions.size.toString())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // لیست تراکنش‌ها
        if (uiState.filteredTransactions.isEmpty()) {
            item {
                EmptyStateView(
                    title = "تراکنشی یافت نشد",
                    description = "با توجه به فیلترها یا جستجوی فعلی، تراکنشی پیدا نشد.",
                    actionButtonText = "+ ثبت تراکنش جدید",
                    onActionClick = onAddTransactionClick
                )
            }
        } else {
            items(uiState.filteredTransactions, key = { it.id }) { tx ->
                TransactionDetailCard(
                    transaction = tx,
                    displayCurrency = displayCurrency,
                    onEdit = { onEditTransaction(tx) },
                    onDelete = { transactionToDelete = tx }
                )
            }
        }
    }
}

@Composable
fun TransactionDetailCard(
    transaction: TransactionEntity,
    displayCurrency: CurrencyType,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tx_card_${transaction.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // هدر تراکنش
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssetClassBadge(assetClass = transaction.assetClass)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = transaction.assetName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                StatusBadge(
                    text = transaction.action.titleFa,
                    color = when (transaction.action) {
                        TransactionAction.BUY -> MaterialTheme.colorScheme.primary
                        TransactionAction.SELL -> GoldDark
                        TransactionAction.DIVIDEND -> ProfitGreen
                        TransactionAction.DEPOSIT -> InfoBlue
                        TransactionAction.WITHDRAWAL -> LossRed
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // جزئیات مقدار و قیمت
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "مقدار: ${PersianUtils.formatNumber(transaction.quantity)} ${transaction.unit}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    if (transaction.unitPrice != null) {
                        Text(
                            text = "قیمت واحد: ${PersianUtils.formatMoney(transaction.unitPrice, transaction.currency)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "قیمت واحد: نامشخص",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    if (transaction.totalAmount != null) {
                        Text(
                            text = PersianUtils.formatMoney(transaction.totalAmount, transaction.currency),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
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
                            text = "قیمت خرید خالی است",
                            color = MaterialTheme.colorScheme.error,
                            backgroundColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // تاریخ و کارگزاری
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "تاریخ: ${transaction.datePersian}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (transaction.brokerOrSource.isNotBlank()) {
                    Text(
                        text = "منبع: ${transaction.brokerOrSource}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (transaction.notes.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "توضیحات: ${transaction.notes}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // دکمه‌های عملیاتی ویرایش و حذف
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onEdit,
                    modifier = Modifier.testTag("edit_tx_${transaction.id}")
                ) {
                    Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (transaction.unitPrice == null) "تکمیل قیمت خرید" else "ویرایش")
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_tx_${transaction.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "حذف",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
