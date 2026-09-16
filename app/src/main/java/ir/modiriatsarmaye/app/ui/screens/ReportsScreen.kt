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
import ir.modiriatsarmaye.app.data.model.*
import ir.modiriatsarmaye.app.ui.components.*
import ir.modiriatsarmaye.app.ui.theme.*
import ir.modiriatsarmaye.app.ui.viewmodel.WealthUiState
import ir.modiriatsarmaye.app.util.*

@Composable
fun ReportsScreen(
    uiState: WealthUiState,
    initialTab: Int = 0,
    onSaveGoal: (GoalEntity) -> Unit,
    onDeleteGoal: (GoalEntity) -> Unit,
    onSaveLiability: (LiabilityEntity) -> Unit,
    onDeleteLiability: (LiabilityEntity) -> Unit,
    onSaveSettings: (AppSettingsEntity) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(initialTab) }
    val tabTitles = listOf(
        "بازتنظیم سبد",
        "شبیه‌ساز بازنشستگی",
        "اهداف مالی",
        "گزارش ماهانه و سالانه",
        "ریسک و شاخص‌ها",
        "جریان نقدی و بدهی‌ها",
        "نسخه چاپی گزارش"
    )

    val summary = uiState.portfolioSummary
    val settings = uiState.settings
    val displayCurrency = settings.displayCurrency

    // دیالوگ افزودن هدف جدید
    var showAddGoalDialog by remember { mutableStateOf(false) }
    // دیالوگ افزودن بدهی جدید
    var showAddLiabilityDialog by remember { mutableStateOf(false) }

    if (showAddGoalDialog) {
        AddGoalDialog(
            onDismiss = { showAddGoalDialog = false },
            onSave = {
                onSaveGoal(it)
                showAddGoalDialog = false
            }
        )
    }

    if (showAddLiabilityDialog) {
        AddLiabilityDialog(
            onDismiss = { showAddLiabilityDialog = false },
            onSave = {
                onSaveLiability(it)
                showAddLiabilityDialog = false
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                Text(
                    text = "گزارش‌ها، تحلیل و برنامه‌ریزی",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "ابزارهای تحلیلی، سناریوهای بازنشستگی و توازن پرتفوی",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // نوار تب‌های فرعی
        item {
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 0.dp,
                divider = {}
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }
        }

        // ۱. تب بازتنظیم سبد (Rebalancing)
        if (selectedTab == 0) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "راهنمای هوشمند بازتنظیم سبد دارایی (Rebalance)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "آستانه انحراف مجاز: ±${PersianUtils.toPersianDigits(settings.rebalanceThresholdPct.toInt().toString())} درصد. توصیه‌ها صرفاً محاسبات ریاضی مقایسه با اهداف است و مشاوره قطعی سرمایه‌گذاری نمی‌باشد.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(summary.rebalanceItems) { item ->
                RebalanceCardItem(item = item, displayCurrency = displayCurrency)
            }
        }

        // ۲. تب شبیه‌ساز بازنشستگی (Retirement Simulation)
        if (selectedTab == 1) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "فرض شبیه‌سازی — نه پیش‌بینی قطعی بازار",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "سرمایه اولیه: ${PersianUtils.formatMoney(summary.totalPortfolioValueToman, displayCurrency)} • سرمایه‌گذاری ماهانه: ${PersianUtils.formatMoney(settings.monthlyInvestmentToman, displayCurrency)} • افق: ${PersianUtils.toPersianDigits(settings.retirementHorizonYears.toString())} سال • نرخ تورم فرضی: ${PersianUtils.toPersianDigits(settings.inflationRatePct.toInt().toString())}٪",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(uiState.retirementScenarios) { scenario ->
                RetirementScenarioCard(scenario = scenario, displayCurrency = displayCurrency)
            }
        }

        // ۳. تب اهداف مالی (Goals)
        if (selectedTab == 2) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "اهداف مالی و استقلال مالی",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Button(
                        onClick = { showAddGoalDialog = true },
                        modifier = Modifier.testTag("add_goal_btn")
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("هدف جدید")
                    }
                }
            }

            items(uiState.goals) { goal ->
                GoalProgressCard(
                    goal = goal,
                    currentPortfolioValue = summary.totalPortfolioValueToman,
                    displayCurrency = displayCurrency,
                    onDelete = { onDeleteGoal(goal) }
                )
            }
        }

        // ۴. تب گزارش ماهانه و سالانه (Monthly & Annual)
        if (selectedTab == 3) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "گزارش عملکرد دوره‌ای",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("کل سرمایه‌گذاری شده", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(PersianUtils.formatMoney(summary.totalInvestedCostToman, displayCurrency), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("ارزش پایانی سبد", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(PersianUtils.formatMoney(summary.totalPortfolioValueToman, displayCurrency), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("سود / زیان کل", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(PersianUtils.formatMoney(summary.totalProfitLossToman, displayCurrency), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (summary.totalProfitLossToman >= 0) ProfitGreen else LossRed)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("نرخ بازدهی کل", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${if (summary.totalReturnPct >= 0) "+" else ""}${PersianUtils.formatNumber(summary.totalReturnPct, 1)}٪", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (summary.totalReturnPct >= 0) ProfitGreen else LossRed)
                            }
                        }
                    }
                }
            }
        }

        // ۵. تب ریسک و شاخص‌ها (Risk & Benchmarks)
        if (selectedTab == 4) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "پایش ریسک و تمرکز دارایی‌ها",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // بررسی ریسک تمرکز
                        val highestHolding = summary.holdings.maxByOrNull { it.portfolioPercent }
                        if (highestHolding != null && highestHolding.portfolioPercent > 40.0) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "هشدار تمرکز ریسک: دارایی «${highestHolding.assetName}» بیش از ۴۰٪ از کل پرتفوی (${PersianUtils.formatNumber(highestHolding.portfolioPercent, 1)}٪) را تشکیل داده است.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = "تنوع‌بخشی سبد دارایی‌ها در وضعیت مناسبی قرار دارد.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ProfitGreen
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "مقایسه با شاخص‌های بازار (دستی / آینده API):",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "داده کافی برای محاسبه شاخص‌های تاریخی پیچیده وجود ندارد (اطلاعات بازار جعل نمی‌گردد).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ۶. تب جریان نقدی و بدهی‌ها (Cash Flow & Net Worth)
        if (selectedTab == 5) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "مدیریت بدهی‌ها و تعهدات",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Button(onClick = { showAddLiabilityDialog = true }) {
                                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("ثبت بدهی")
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("کل بدهی‌ها:", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = PersianUtils.formatMoney(summary.totalLiabilitiesToman, displayCurrency),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = LossRed
                            )
                        }
                    }
                }
            }

            items(uiState.liabilities) { liability ->
                LiabilityCardItem(
                    liability = liability,
                    displayCurrency = displayCurrency,
                    onDelete = { onDeleteLiability(liability) }
                )
            }
        }

        // ۷. تب نسخه چاپی گزارش
        if (selectedTab == 6) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "گزارش جامع وضعیت ثروت و پرتفوی",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = PersianUtils.getCurrentPersianDate(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(16.dp))

                        Text(text = "• ارزش کل سبد دارایی‌ها: ${PersianUtils.formatMoney(summary.totalPortfolioValueToman, displayCurrency)}", style = MaterialTheme.typography.bodyLarge)
                        Text(text = "• بهای تمام‌شده سرمایه‌گذاری: ${PersianUtils.formatMoney(summary.totalInvestedCostToman, displayCurrency)}", style = MaterialTheme.typography.bodyLarge)
                        Text(text = "• سود / زیان تحقق‌نیافته: ${PersianUtils.formatMoney(summary.totalProfitLossToman, displayCurrency)} (${PersianUtils.formatNumber(summary.totalReturnPct, 1)}٪)", style = MaterialTheme.typography.bodyLarge)
                        Text(text = "• کل تعهدات و بدهی‌ها: ${PersianUtils.formatMoney(summary.totalLiabilitiesToman, displayCurrency)}", style = MaterialTheme.typography.bodyLarge)
                        Text(text = "• دارایی خالص (Net Worth): ${PersianUtils.formatMoney(summary.netWorthToman, displayCurrency)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "این گزارش بر پایه اطلاعات ذخیره شده در برنامه مدیریت سرمایه آماده شده است.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RebalanceCardItem(
    item: RebalanceItem,
    displayCurrency: CurrencyType
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isAlert) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssetClassBadge(assetClass = item.assetClass)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = item.assetClass.titleFa,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                StatusBadge(
                    text = item.recommendedAction,
                    color = if (item.isAlert) GoldDark else ProfitGreen
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "سهم هدف: ${PersianUtils.formatNumber(item.targetPct, 1)}٪",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "سهم فعلی: ${PersianUtils.formatNumber(item.currentPct, 1)}٪",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "انحراف: ${if (item.diffPct >= 0) "+" else ""}${PersianUtils.formatNumber(item.diffPct, 1)}٪",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.isAlert) LossRed else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (item.isAlert && item.recommendedAmountToman > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "مبلغ پیشنهادی جهت تعادل: ${PersianUtils.formatMoney(item.recommendedAmountToman, displayCurrency)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = GoldDark
                )
            }
        }
    }
}

@Composable
fun RetirementScenarioCard(
    scenario: RetirementScenarioResult,
    displayCurrency: CurrencyType
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = scenario.nameFa,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                StatusBadge(
                    text = "بازدهی سالانه ${PersianUtils.toPersianDigits(scenario.annualReturnRatePct.toInt().toString())}٪",
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("ارزش اسمی آینده", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = PersianUtils.formatMoney(scenario.finalNominalValueToman, displayCurrency),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text("ارزش واقعی (تعدیل با تورم)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = PersianUtils.formatMoney(scenario.finalRealValueToman, displayCurrency),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = GoldDark
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "مجموع پس‌انداز شخصی: ${PersianUtils.formatMoney(scenario.totalContributionsToman, displayCurrency)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "سود مرکب: ${PersianUtils.formatMoney(scenario.investmentProfitToman, displayCurrency)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = ProfitGreen
                )
            }
        }
    }
}

@Composable
fun GoalProgressCard(
    goal: GoalEntity,
    currentPortfolioValue: Double,
    displayCurrency: CurrencyType,
    onDelete: () -> Unit
) {
    val progress = if (goal.targetAmountToman > 0) {
        (currentPortfolioValue / goal.targetAmountToman).coerceIn(0.0, 1.0)
    } else 0.0

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = goal.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDelete) {
                    Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            LinearProgressIndicator(
                progress = { progress.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "پیشرفت: ${PersianUtils.formatNumber(progress * 100.0, 1)}٪",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "هدف: ${PersianUtils.formatMoney(goal.targetAmountToman, displayCurrency)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun LiabilityCardItem(
    liability: LiabilityEntity,
    displayCurrency: CurrencyType,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = liability.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (liability.monthlyPaymentToman > 0) {
                    Text(text = "قسط ماهانه: ${PersianUtils.formatMoney(liability.monthlyPaymentToman, displayCurrency)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = PersianUtils.formatMoney(liability.totalAmountToman, displayCurrency), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = LossRed)
                IconButton(onClick = onDelete) {
                    Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun AddGoalDialog(
    onDismiss: () -> Unit,
    onSave: (GoalEntity) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var targetAmountText by remember { mutableStateOf("") }
    var targetDate by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعریف هدف مالی جدید", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("عنوان هدف") },
                    placeholder = { Text("مثال: خرید مسکن، ۵ میلیارد تومان") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = targetAmountText,
                    onValueChange = { targetAmountText = PersianUtils.toEnglishDigits(it) },
                    label = { Text("مبلغ هدف (تومان)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = targetDate,
                    onValueChange = { targetDate = it },
                    label = { Text("سال یا تاریخ هدف") },
                    placeholder = { Text("مثال: ۱۴۱۰") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amount = targetAmountText.toDoubleOrNull() ?: 0.0
                    if (title.isNotBlank() && amount > 0) {
                        onSave(
                            GoalEntity(
                                title = title.trim(),
                                targetAmountToman = amount,
                                targetDatePersian = targetDate.trim()
                            )
                        )
                    }
                }
            ) {
                Text("ذخیره هدف")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف") }
        }
    )
}

@Composable
fun AddLiabilityDialog(
    onDismiss: () -> Unit,
    onSave: (LiabilityEntity) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var monthlyPaymentText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ثبت بدهی یا تعهد جدید", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("عنوان بدهی / وام") },
                    placeholder = { Text("مثال: وام مسکن، قرض الحسنه") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = PersianUtils.toEnglishDigits(it) },
                    label = { Text("مبلغ کل بدهی (تومان)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = monthlyPaymentText,
                    onValueChange = { monthlyPaymentText = PersianUtils.toEnglishDigits(it) },
                    label = { Text("قسط ماهانه (تومان)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val total = amountText.toDoubleOrNull() ?: 0.0
                    val monthly = monthlyPaymentText.toDoubleOrNull() ?: 0.0
                    if (title.isNotBlank() && total > 0) {
                        onSave(
                            LiabilityEntity(
                                title = title.trim(),
                                totalAmountToman = total,
                                monthlyPaymentToman = monthly
                            )
                        )
                    }
                }
            ) {
                Text("ثبت بدهی")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف") }
        }
    )
}
