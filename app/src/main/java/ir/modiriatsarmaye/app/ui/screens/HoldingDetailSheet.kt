package ir.modiriatsarmaye.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.modiriatsarmaye.app.data.db.entity.TransactionEntity
import ir.modiriatsarmaye.app.data.db.entity.TransactionType
import java.text.NumberFormat
import java.util.Locale

/**
 * Fix 2: BottomSheet نمایش تاریخچه معاملات و میانگین قیمت خرید برای یک نماد
 *
 * استفاده در PortfolioScreen:
 *   var selectedSymbol by remember { mutableStateOf<String?>(null) }
 *   if (selectedSymbol != null) {
 *       HoldingDetailSheet(
 *           symbol = selectedSymbol!!,
 *           transactions = transactions.filter { it.symbol == selectedSymbol },
 *           onDismiss = { selectedSymbol = null }
 *       )
 *   }
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoldingDetailSheet(
    symbol: String,
    transactions: List<TransactionEntity>,
    onDismiss: () -> Unit
) {
    val buyTransactions = transactions.filter { it.type == TransactionType.BUY }
    val totalBuyQty = buyTransactions.sumOf { it.quantity }
    val totalBuyCost = buyTransactions.sumOf { it.quantity * it.pricePerUnit }
    val averageBuyPrice = if (totalBuyQty > 0) totalBuyCost / totalBuyQty else 0.0

    val numberFormat = NumberFormat.getNumberInstance(Locale("fa", "IR"))

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // عنوان
            Text(
                text = symbol,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                textAlign = TextAlign.Center
            )

            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            // میانگین قیمت خرید
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "میانگین قیمت خرید",
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${numberFormat.format(averageBuyPrice.toLong())} ریال",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // تعداد کل خرید
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "مجموع تعداد خریداری‌شده",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = numberFormat.format(totalBuyQty),
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "تاریخچه معاملات",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (transactions.isEmpty()) {
                Text(
                    text = "تراکنشی یافت نشد",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    textAlign = TextAlign.Center
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = transactions.sortedByDescending { it.date },
                        key = { it.id }
                    ) { tx ->
                        TransactionHistoryRow(tx, numberFormat)
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionHistoryRow(
    tx: TransactionEntity,
    numberFormat: NumberFormat
) {
    val isBuy = tx.type == TransactionType.BUY
    val typeLabel = if (isBuy) "خرید" else "فروش"
    val typeColor = if (isBuy)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.error

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // نوع + تاریخ
            Column {
                Text(
                    text = typeLabel,
                    fontWeight = FontWeight.Bold,
                    color = typeColor,
                    fontSize = 13.sp
                )
                Text(
                    text = tx.date,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // مقدار + قیمت
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${numberFormat.format(tx.quantity)} واحد",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${numberFormat.format(tx.pricePerUnit.toLong())} ریال",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
