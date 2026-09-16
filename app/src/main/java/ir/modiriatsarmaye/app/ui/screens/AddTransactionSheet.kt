package ir.modiriatsarmaye.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import ir.modiriatsarmaye.app.ui.components.StatusBadge
import ir.modiriatsarmaye.app.ui.theme.ProfitGreen
import ir.modiriatsarmaye.app.util.PersianUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionSheet(
    initialTransaction: TransactionEntity? = null,
    onDismiss: () -> Unit,
    onSave: (TransactionEntity) -> Unit
) {
    var datePersian by remember { mutableStateOf(initialTransaction?.datePersian ?: PersianUtils.getCurrentPersianDate()) }
    var selectedAssetClass by remember { mutableStateOf(initialTransaction?.assetClass ?: AssetClass.GOLD) }
    var assetName by remember { mutableStateOf(initialTransaction?.assetName ?: "") }
    var assetSymbol by remember { mutableStateOf(initialTransaction?.assetSymbol ?: "") }
    var selectedAction by remember { mutableStateOf(initialTransaction?.action ?: TransactionAction.BUY) }
    var quantityText by remember {
        mutableStateOf(
            initialTransaction?.quantity?.let {
                if (it % 1.0 == 0.0) it.toLong().toString() else it.toString()
            } ?: ""
        )
    }
    var unit by remember { mutableStateOf(initialTransaction?.unit ?: selectedAssetClass.defaultUnit) }
    var unitPriceText by remember { mutableStateOf(initialTransaction?.unitPrice?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "") }
    var selectedCurrency by remember { mutableStateOf(initialTransaction?.currency ?: CurrencyType.TOMAN) }
    var selectedCommissionType by remember { mutableStateOf(initialTransaction?.commissionType ?: CommissionType.FIXED) }
    var commissionInputText by remember {
        mutableStateOf(
            if (initialTransaction != null) {
                if (initialTransaction.commissionType == CommissionType.PERCENTAGE) {
                    val r = initialTransaction.commissionRate
                    if (r % 1.0 == 0.0) r.toLong().toString() else r.toString()
                } else {
                    val f = initialTransaction.fees
                    if (f % 1.0 == 0.0) f.toLong().toString() else f.toString()
                }
            } else "0"
        )
    }
    var brokerOrSource by remember { mutableStateOf(initialTransaction?.brokerOrSource ?: "") }
    var notes by remember { mutableStateOf(initialTransaction?.notes ?: "") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // پیشنهادات سریع دارایی بر اساس دسته انتخابی
    val assetSuggestions = remember(selectedAssetClass) {
        when (selectedAssetClass) {
            AssetClass.GOLD -> listOf("طلای فیزیکی (۱۸ عیار)", "سکه امامی", "نیم سکه", "ربع سکه")
            AssetClass.GOLD_FUND -> listOf("لوتوس", "عیار", "کهربا", "زرین", "طلا")
            AssetClass.EQUITY_FUND -> listOf("آگاس", "افق", "سرو", "فیروزه", "انار")
            AssetClass.FIXED_INCOME_FUND -> listOf("کمند", "اعتماد", "کارین", "کیان", "همای")
            AssetClass.STOCK -> listOf("فولاد", "فملی", "شپنا", "شستا", "خودرو")
            AssetClass.USD -> listOf("دلار آمریکا (نقدی)", "دلار حواله")
            AssetClass.EUR -> listOf("یورو نقدی")
            AssetClass.AED -> listOf("درهم امارات")
            AssetClass.CRYPTO -> listOf("بیت‌کوین (BTC)", "اتریوم (ETH)", "تتر (USDT)")
            AssetClass.DEPOSIT -> listOf("سپرده بلندمدت بانک ملی", "سپرده کوتاه‌مدت")
            AssetClass.CASH -> listOf("تنخواه و کیف پول", "کارت جاری")
            else -> listOf("سایر دارایی‌ها")
        }
    }

    // محاسبه زنده مبلغ معامله، کارمزد و مبلغ نهایی
    val q = quantityText.toDoubleOrNull() ?: 0.0
    val p = unitPriceText.toDoubleOrNull()
    val commInput = commissionInputText.toDoubleOrNull() ?: 0.0

    val baseTransactionAmount = if (p != null && q > 0.0) q * p else null

    val calculatedCommissionAmount = if (baseTransactionAmount != null) {
        if (selectedAction in listOf(TransactionAction.BUY, TransactionAction.SELL)) {
            when (selectedCommissionType) {
                CommissionType.PERCENTAGE -> baseTransactionAmount * (commInput / 100.0)
                CommissionType.FIXED -> commInput
            }
        } else 0.0
    } else {
        if (selectedCommissionType == CommissionType.FIXED) commInput else 0.0
    }

    val calculatedTotal = if (baseTransactionAmount != null) {
        when (selectedAction) {
            TransactionAction.BUY -> baseTransactionAmount + calculatedCommissionAmount
            TransactionAction.SELL -> (baseTransactionAmount - calculatedCommissionAmount).coerceAtLeast(0.0)
            else -> baseTransactionAmount
        }
    } else null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (initialTransaction == null) "ثبت تراکنش جدید" else "ویرایش تراکنش",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "بستن")
                }
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ۱. دسته‌بندی دارایی (Asset Class)
            Text(
                text = "۱. نوع دارایی",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ScrollableTabRow(
                    selectedTabIndex = AssetClass.entries.indexOf(selectedAssetClass),
                    edgePadding = 0.dp,
                    divider = {}
                ) {
                    AssetClass.entries.forEach { ac ->
                        Tab(
                            selected = selectedAssetClass == ac,
                            onClick = {
                                selectedAssetClass = ac
                                unit = ac.defaultUnit
                            },
                            text = { Text(ac.titleFa) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ۲. مشخصات دارایی
            Text(
                text = "۲. مشخصات دارایی",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            // پیشنهادات سریع
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                assetSuggestions.take(4).forEach { suggestion ->
                    SuggestionChip(
                        onClick = { assetName = suggestion },
                        label = { Text(suggestion, fontSize = 11.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = assetName,
                    onValueChange = {
                        assetName = it
                        errorMessage = null
                    },
                    label = { Text("نام دارایی *") },
                    placeholder = { Text("مثال: آگاس، لوتوس، طلا") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1.5f)
                        .testTag("asset_name_input")
                )

                val symbolLabel = when (selectedAssetClass) {
                    AssetClass.STOCK -> "نماد بورسی *"
                    AssetClass.EQUITY_FUND, AssetClass.FIXED_INCOME_FUND, AssetClass.GOLD_FUND -> "نماد صندوق *"
                    else -> "نماد (اختیاری)"
                }
                val symbolPlaceholder = when (selectedAssetClass) {
                    AssetClass.STOCK -> "مثال: فملی، شپنا"
                    AssetClass.EQUITY_FUND, AssetClass.FIXED_INCOME_FUND, AssetClass.GOLD_FUND -> "مثال: آگاس، کمند"
                    else -> "مثال: GOLD18"
                }

                OutlinedTextField(
                    value = assetSymbol,
                    onValueChange = { assetSymbol = it },
                    label = { Text(symbolLabel) },
                    placeholder = { Text(symbolPlaceholder) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("asset_symbol_input")
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ۳. نوع عملیات (Action)
            Text(
                text = "۳. نوع عملیات",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TransactionAction.entries.take(3).forEach { act ->
                    FilterChip(
                        selected = selectedAction == act,
                        onClick = { selectedAction = act },
                        label = { Text(act.titleFa) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ۴. مقدار و واحد
            Text(
                text = "۴. مقدار و واحد سنجش",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = {
                        quantityText = PersianUtils.toEnglishDigits(it)
                        errorMessage = null
                    },
                    label = { Text("تعداد / مقدار *") },
                    placeholder = { Text("مثال: ۰٫۹۹ یا ۱۰۰") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1.5f)
                        .testTag("quantity_input")
                )

                OutlinedTextField(
                    value = unit,
                    onValueChange = { unit = it },
                    label = { Text("واحد *") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("unit_input")
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ۵. قیمت واحد و ارز
            Text(
                text = "۵. قیمت هر واحد و واحد پول",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "در صورتی که قیمت خرید را به یاد ندارید، می‌توانید آن را خالی بگذارید و بعداً وارد کنید.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = unitPriceText,
                    onValueChange = { unitPriceText = PersianUtils.toEnglishDigits(it) },
                    label = { Text("قیمت هر واحد") },
                    placeholder = { Text("اختیاری") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1.5f)
                        .testTag("unit_price_input")
                )

                Column(modifier = Modifier.weight(1f)) {
                    FilterChip(
                        selected = selectedCurrency == CurrencyType.TOMAN,
                        onClick = { selectedCurrency = CurrencyType.TOMAN },
                        label = { Text("تومان") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    FilterChip(
                        selected = selectedCurrency == CurrencyType.RIAL,
                        onClick = { selectedCurrency = CurrencyType.RIAL },
                        label = { Text("ریال") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ۶. کارمزد معامله (درصدی / مبلغ ثابت)
            if (selectedAction in listOf(TransactionAction.BUY, TransactionAction.SELL)) {
                Text(
                    text = "۶. کارمزد معامله",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "نوع کارمزد:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilterChip(
                        selected = selectedCommissionType == CommissionType.PERCENTAGE,
                        onClick = { selectedCommissionType = CommissionType.PERCENTAGE },
                        label = { Text("درصدی (٪)") },
                        modifier = Modifier.weight(1f).testTag("commission_type_percentage")
                    )
                    FilterChip(
                        selected = selectedCommissionType == CommissionType.FIXED,
                        onClick = { selectedCommissionType = CommissionType.FIXED },
                        label = { Text("مبلغ ثابت") },
                        modifier = Modifier.weight(1f).testTag("commission_type_fixed")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = commissionInputText,
                    onValueChange = {
                        commissionInputText = PersianUtils.toEnglishDigits(it)
                        errorMessage = null
                    },
                    label = {
                        Text(
                            if (selectedCommissionType == CommissionType.PERCENTAGE)
                                "درصد کارمزد (٪)"
                            else
                                "مبلغ کارمزد (${selectedCurrency.titleFa})"
                        )
                    },
                    placeholder = {
                        Text(if (selectedCommissionType == CommissionType.PERCENTAGE) "مثال: ۰٫۵ یا ۵" else "مثال: ۵۰۰۰۰۰")
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("commission_input")
                )

                Spacer(modifier = Modifier.height(16.dp))
            }

            // ۷. تاریخ، کارگزاری و یادداشت
            Text(
                text = "۷. تاریخ، منبع و توضیحات",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = datePersian,
                    onValueChange = { datePersian = it },
                    label = { Text("تاریخ تراکنش *") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )

                OutlinedTextField(
                    value = brokerOrSource,
                    onValueChange = { brokerOrSource = it },
                    label = { Text("کارگزاری / منبع") },
                    placeholder = { Text("مثال: آگاه، بازار طلا") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("یادداشت و توضیحات") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ۸. پیش‌نمایش زنده محاسبات مالی
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("calculation_preview_card"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "پیش‌نمایش زنده محاسبات مالی:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (baseTransactionAmount != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "مبلغ معامله:", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = PersianUtils.formatMoney(baseTransactionAmount, selectedCurrency),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))

                        if (selectedAction in listOf(TransactionAction.BUY, TransactionAction.SELL)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (selectedCommissionType == CommissionType.PERCENTAGE)
                                        "کارمزد (${PersianUtils.formatNumber(commInput)}٪):"
                                    else
                                        "مبلغ کارمزد:",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = PersianUtils.formatMoney(calculatedCommissionAmount, selectedCurrency),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "مبلغ نهایی:",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = PersianUtils.formatMoney(calculatedTotal, selectedCurrency),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        if (selectedCurrency == CurrencyType.RIAL) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "معادل: ${PersianUtils.formatMoney(PersianUtils.rialToToman(calculatedTotal ?: 0.0), CurrencyType.TOMAN)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "معادل: ${PersianUtils.formatMoney(PersianUtils.tomanToRial(calculatedTotal ?: 0.0), CurrencyType.RIAL)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            text = "قیمت خرید یا مقدار ثبت نشده است (بعداً قابل تکمیل)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // دکمه ثبت تراکنش
            Button(
                onClick = {
                    val parsedQuantity = quantityText.toDoubleOrNull()
                    if (assetName.isBlank()) {
                        errorMessage = "لطفاً نام دارایی را وارد کنید."
                        return@Button
                    }
                    if (parsedQuantity == null || parsedQuantity <= 0.0) {
                        errorMessage = "مقدار / تعداد باید عددی بزرگتر از صفر باشد."
                        return@Button
                    }
                    if (unit.isBlank()) {
                        errorMessage = "واحد سنجش را مشخص کنید."
                        return@Button
                    }

                    val comm = commissionInputText.toDoubleOrNull()
                    if (comm == null || comm < 0.0) {
                        errorMessage = "کارمزد نمی‌تواند منفی یا نامعتبر باشد."
                        return@Button
                    }
                    if (selectedCommissionType == CommissionType.PERCENTAGE && comm > 100.0) {
                        errorMessage = "درصد کارمزد باید بین ۰ تا ۱۰۰ درصد باشد."
                        return@Button
                    }

                    val parsedPrice = unitPriceText.toDoubleOrNull()

                    val tx = TransactionEntity(
                        id = initialTransaction?.id ?: 0L,
                        datePersian = datePersian,
                        timestamp = initialTransaction?.timestamp ?: System.currentTimeMillis(),
                        assetClass = selectedAssetClass,
                        assetName = assetName.trim(),
                        assetSymbol = assetSymbol.trim(),
                        action = selectedAction,
                        quantity = parsedQuantity,
                        unit = unit.trim(),
                        unitPrice = parsedPrice,
                        currency = selectedCurrency,
                        fees = calculatedCommissionAmount,
                        commissionType = selectedCommissionType,
                        commissionRate = comm,
                        totalAmount = calculatedTotal,
                        brokerOrSource = brokerOrSource.trim(),
                        notes = notes.trim()
                    )
                    onSave(tx)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("submit_transaction_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(imageVector = Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (initialTransaction == null) "ثبت نهایی تراکنش" else "ذخیره تغییرات",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
