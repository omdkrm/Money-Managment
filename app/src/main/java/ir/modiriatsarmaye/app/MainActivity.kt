package ir.modiriatsarmaye.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.modiriatsarmaye.app.data.local.AppDatabase
import ir.modiriatsarmaye.app.data.model.AssetClass
import ir.modiriatsarmaye.app.data.model.CurrencyType
import ir.modiriatsarmaye.app.data.model.TransactionEntity
import ir.modiriatsarmaye.app.data.repository.WealthRepository
import ir.modiriatsarmaye.app.ui.components.AppButtonDefaults
import ir.modiriatsarmaye.app.ui.components.AppIcons
import ir.modiriatsarmaye.app.ui.components.UpdatePriceDialog
import ir.modiriatsarmaye.app.ui.screens.*
import ir.modiriatsarmaye.app.ui.theme.ModiriatSarmayeTheme
import ir.modiriatsarmaye.app.ui.viewmodel.WealthUiState
import ir.modiriatsarmaye.app.ui.viewmodel.WealthViewModel
import ir.modiriatsarmaye.app.util.CalculationEngine
import ir.modiriatsarmaye.app.util.HoldingItem

data class SelectedAssetKey(
    val assetName: String,
    val assetSymbol: String,
    val assetClass: AssetClass,
    val unit: String
)

enum class AppDestination(
    val titleFa: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val route: String
) {
    HOME("خانه", Icons.Filled.Home, Icons.Outlined.Home, "home"),
    PORTFOLIO("پرتفوی", AppIcons.PieChart, AppIcons.PieChartOutlined, "portfolio"),
    TRANSACTIONS("تراکنش‌ها", AppIcons.ReceiptLong, AppIcons.ReceiptLongOutlined, "transactions"),
    REPORTS("گزارش‌ها", AppIcons.Analytics, AppIcons.AnalyticsOutlined, "reports"),
    MORE("بیشتر", AppIcons.MoreHoriz, AppIcons.MoreHorizOutlined, "more")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = AppDatabase.getInstance(applicationContext)
        val repository = WealthRepository(database, applicationContext)

        setContent {
            ModiriatSarmayeTheme {
                // اعمال چیدمان راست‌به‌چپ (RTL) برای تمامی مؤلفه‌ها و صفحات فارسی
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    val viewModel: WealthViewModel = viewModel {
                        WealthViewModel(repository)
                    }

                    WealthAppContent(viewModel = viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WealthAppContent(
    viewModel: WealthViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var currentDestination by remember { mutableStateOf(AppDestination.HOME) }
    var reportsInitialTab by remember { mutableIntStateOf(0) }
    var selectedAssetKey by remember { mutableStateOf<SelectedAssetKey?>(null) }

    // مدیریت وضعیت شیت‌ها و دیالوگ‌ها
    var showAddTransactionSheet by remember { mutableStateOf(false) }
    var editingTransaction by remember { mutableStateOf<TransactionEntity?>(null) }
    var holdingToUpdatePrice by remember { mutableStateOf<HoldingItem?>(null) }

    // نمایش پیام‌های بازخورد کاربر در اسنک‌بار
    LaunchedEffect(uiState.userSuccessMessage, uiState.userErrorMessage) {
        if (uiState.userSuccessMessage != null) {
            snackbarHostState.showSnackbar(
                message = uiState.userSuccessMessage!!,
                duration = SnackbarDuration.Short
            )
            viewModel.clearMessages()
        } else if (uiState.userErrorMessage != null) {
            snackbarHostState.showSnackbar(
                message = uiState.userErrorMessage!!,
                duration = SnackbarDuration.Long
            )
            viewModel.clearMessages()
        }
    }

    // دیالوگ بروزرسانی قیمت روز
    if (holdingToUpdatePrice != null) {
        val h = holdingToUpdatePrice!!
        UpdatePriceDialog(
            initialName = h.assetName,
            initialSymbol = h.assetSymbol,
            initialAssetClass = h.assetClass,
            currentPrice = h.currentPriceOriginal ?: h.currentPriceToman,
            currentCurrency = h.currentPriceCurrency,
            onDismiss = { holdingToUpdatePrice = null },
            onSave = { price, currency ->
                viewModel.updateCurrentPrice(
                    symbolOrName = if (h.assetSymbol.isNotBlank()) h.assetSymbol else h.assetName,
                    name = h.assetName,
                    assetClass = h.assetClass,
                    price = price,
                    currency = currency
                )
                holdingToUpdatePrice = null
            }
        )
    }

    // شیت افزودن / ویرایش تراکنش
    if (showAddTransactionSheet || editingTransaction != null) {
        AddTransactionSheet(
            initialTransaction = editingTransaction,
            onDismiss = {
                showAddTransactionSheet = false
                editingTransaction = null
            },
            onSave = { tx ->
                viewModel.saveTransaction(tx) { success, _ ->
                    if (success) {
                        showAddTransactionSheet = false
                        editingTransaction = null
                    }
                }
            }
        )
    }

    // بررسی بروزرسانی خودکار بر اساس زمان‌بندی انتخابی کاربر و بهینه‌سازی مصرف باتری و شبکه
    LaunchedEffect(Unit) {
        val freq = uiState.settings.priceUpdateFrequency
        val lastUpdate = uiState.settings.lastPriceUpdateTimestamp
        val now = System.currentTimeMillis()

        val shouldUpdate = when (freq) {
            ir.modiriatsarmaye.app.data.model.PriceUpdateFrequency.MANUAL_ONLY -> false
            ir.modiriatsarmaye.app.data.model.PriceUpdateFrequency.ON_APP_OPEN -> {
                // حداقل فاصله ۱۵ دقیقه بین دو بروزرسانی متوالی در زمان باز شدن برنامه
                now - lastUpdate > 15 * 60 * 1000L
            }
            ir.modiriatsarmaye.app.data.model.PriceUpdateFrequency.DAILY -> {
                // حداقل فاصله ۲۴ ساعت بین دو بروزرسانی روزانه
                now - lastUpdate > 24 * 60 * 60 * 1000L
            }
            ir.modiriatsarmaye.app.data.model.PriceUpdateFrequency.ON_CONNECTIVITY -> {
                now - lastUpdate > 30 * 60 * 1000L
            }
        }

        if (shouldUpdate) {
            viewModel.syncMarketPrices(forceRefresh = false)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            if (selectedAssetKey == null) {
                ExtendedFloatingActionButton(
                    onClick = {
                        editingTransaction = null
                        showAddTransactionSheet = true
                    },
                    shape = AppButtonDefaults.Shape,
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(AppButtonDefaults.IconSize)
                        )
                    },
                    text = {
                        Text(
                            text = "ثبت تراکنش",
                            style = AppButtonDefaults.textStyle()
                        )
                    },
                    modifier = Modifier.testTag("global_fab_add_transaction")
                )
            }
        },
        bottomBar = {
            if (selectedAssetKey == null) {
                NavigationBar(
                    tonalElevation = 8.dp,
                    modifier = Modifier.testTag("bottom_nav_bar")
                ) {
                    AppDestination.entries.forEach { destination ->
                        val isSelected = currentDestination == destination
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = {
                                selectedAssetKey = null
                                currentDestination = destination
                            },
                            icon = {
                                Icon(
                                    imageVector = if (isSelected) destination.selectedIcon else destination.unselectedIcon,
                                    contentDescription = destination.titleFa
                                )
                            },
                            label = {
                                Text(
                                    text = destination.titleFa,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.testTag("nav_item_${destination.route}")
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (currentDestination) {
                AppDestination.HOME -> {
                    DashboardScreen(
                        uiState = uiState,
                        onAddTransactionClick = {
                            editingTransaction = null
                            showAddTransactionSheet = true
                        },
                        onNavigateToPortfolio = { currentDestination = AppDestination.PORTFOLIO },
                        onNavigateToTransactions = { currentDestination = AppDestination.TRANSACTIONS },
                        onNavigateToReports = { tabIndex ->
                            reportsInitialTab = tabIndex
                            currentDestination = AppDestination.REPORTS
                        },
                        onTransactionClick = { tx ->
                            editingTransaction = tx
                        }
                    )
                }
                AppDestination.PORTFOLIO -> {
                    val activeKey = selectedAssetKey
                    if (activeKey != null) {
                        val assetDetail = remember(
                            activeKey,
                            uiState.transactions,
                            uiState.prices
                        ) {
                            CalculationEngine.calculateAssetDetail(
                                assetName = activeKey.assetName,
                                assetSymbol = activeKey.assetSymbol,
                                assetClass = activeKey.assetClass,
                                unit = activeKey.unit,
                                transactions = uiState.transactions,
                                prices = uiState.prices
                            )
                        }
                        val matchingHolding = uiState.portfolioSummary.holdings.find {
                            it.assetName.equals(activeKey.assetName, ignoreCase = true) &&
                                it.assetClass == activeKey.assetClass
                        }
                        AssetDetailScreen(
                            detail = assetDetail,
                            displayCurrency = uiState.settings.displayCurrency,
                            onBack = { selectedAssetKey = null },
                            onAddTransactionForAsset = {
                                editingTransaction = TransactionEntity(
                                    id = 0,
                                    datePersian = "",
                                    timestamp = System.currentTimeMillis(),
                                    assetClass = assetDetail.assetClass,
                                    assetName = assetDetail.assetName,
                                    assetSymbol = assetDetail.assetSymbol,
                                    action = ir.modiriatsarmaye.app.data.model.TransactionAction.BUY,
                                    quantity = 0.0,
                                    unit = assetDetail.unit,
                                    unitPrice = null,
                                    currency = CurrencyType.TOMAN
                                )
                                showAddTransactionSheet = true
                            },
                            onEditTransaction = { tx ->
                                editingTransaction = tx
                            },
                            onDeleteTransaction = { tx ->
                                viewModel.deleteTransaction(tx)
                            },
                            onUpdatePriceClick = matchingHolding?.let { h ->
                                { holdingToUpdatePrice = h }
                            }
                        )
                    } else {
                        PortfolioScreen(
                            uiState = uiState,
                            onUpdatePriceClick = { holding ->
                                holdingToUpdatePrice = holding
                            },
                            onAddTransactionForAsset = { assetClass, name ->
                                editingTransaction = TransactionEntity(
                                    id = 0,
                                    datePersian = "",
                                    timestamp = System.currentTimeMillis(),
                                    assetClass = assetClass,
                                    assetName = name,
                                    assetSymbol = "",
                                    action = ir.modiriatsarmaye.app.data.model.TransactionAction.BUY,
                                    quantity = 0.0,
                                    unit = assetClass.defaultUnit,
                                    unitPrice = null,
                                    currency = CurrencyType.TOMAN
                                )
                                showAddTransactionSheet = true
                            },
                            onAddDividendClick = {
                                // هدایت به افزودن تراکنش سود یا دیالوگ سود
                                editingTransaction = TransactionEntity(
                                    id = 0,
                                    datePersian = "",
                                    timestamp = System.currentTimeMillis(),
                                    assetClass = AssetClass.STOCK,
                                    assetName = "",
                                    assetSymbol = "",
                                    action = ir.modiriatsarmaye.app.data.model.TransactionAction.DIVIDEND,
                                    quantity = 1.0,
                                    unit = "تومان",
                                    unitPrice = null,
                                    currency = CurrencyType.TOMAN
                                )
                                showAddTransactionSheet = true
                            },
                            onSyncMarketPrices = {
                                viewModel.syncMarketPrices(forceRefresh = true)
                            },
                            onUpdateAllStocks = {
                                viewModel.syncAllStockPrices(forceRefresh = true)
                            },
                            onUpdateIndividualStock = { symbol ->
                                viewModel.syncStockPrice(symbol, forceRefresh = true)
                            },
                            onHoldingClick = { holding ->
                                selectedAssetKey = SelectedAssetKey(
                                    assetName = holding.assetName,
                                    assetSymbol = holding.assetSymbol,
                                    assetClass = holding.assetClass,
                                    unit = holding.unit
                                )
                            }
                        )
                    }
                }
                AppDestination.TRANSACTIONS -> {
                    TransactionsScreen(
                        uiState = uiState,
                        onAddTransactionClick = {
                            editingTransaction = null
                            showAddTransactionSheet = true
                        },
                        onEditTransaction = { tx ->
                            editingTransaction = tx
                        },
                        onDeleteTransaction = { tx ->
                            viewModel.deleteTransaction(tx)
                        },
                        onSearchChange = { query ->
                            viewModel.setSearchQuery(query)
                        },
                        onSelectClassFilter = { ac ->
                            viewModel.setFilterClass(ac)
                        },
                        onSelectActionFilter = { action ->
                            viewModel.setFilterAction(action)
                        }
                    )
                }
                AppDestination.REPORTS -> {
                    ReportsScreen(
                        uiState = uiState,
                        initialTab = reportsInitialTab,
                        onSaveGoal = { goal ->
                            viewModel.saveGoal(goal)
                        },
                        onDeleteGoal = { goal ->
                            viewModel.deleteGoal(goal)
                        },
                        onSaveLiability = { liability ->
                            viewModel.saveLiability(liability)
                        },
                        onDeleteLiability = { liability ->
                            viewModel.deleteLiability(liability)
                        },
                        onSaveSettings = { newSettings ->
                            viewModel.saveSettings(newSettings)
                        }
                    )
                }
                AppDestination.MORE -> {
                    MoreScreen(
                        uiState = uiState,
                        onSaveSettings = { newSettings ->
                            viewModel.saveSettings(newSettings)
                        },
                        onExportBackup = {
                            viewModel.exportBackupJson()
                        },
                        onRestoreBackup = { json, replaceExisting ->
                            viewModel.restoreBackupJson(json, replaceExisting = replaceExisting) { _, _ -> }
                        },
                        onResetDatabase = {
                            viewModel.resetToInitialData()
                        },
                        onSyncMarketPrices = {
                            viewModel.syncMarketPrices(forceRefresh = true)
                        }
                    )
                }
            }
        }
    }
}
