package ir.modiriatsarmaye.app

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ir.modiriatsarmaye.app.data.local.AppDatabase
import ir.modiriatsarmaye.app.data.model.*
import ir.modiriatsarmaye.app.data.repository.ValidationResult
import ir.modiriatsarmaye.app.data.repository.WealthRepository
import ir.modiriatsarmaye.app.ui.screens.AssetDetailScreen
import ir.modiriatsarmaye.app.ui.theme.ModiriatSarmayeTheme
import ir.modiriatsarmaye.app.ui.viewmodel.WealthViewModel
import ir.modiriatsarmaye.app.util.CalculationEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PortfolioHoldingAndAssetDetailTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: WealthRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = WealthRepository(database, context)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testFullySoldAssetDisappearsFromPortfolioButHistoryIsPreserved() = runBlocking {
        val buy = TransactionEntity(
            datePersian = "1403/01/10",
            timestamp = 1_000L,
            assetClass = AssetClass.STOCK,
            assetName = "فولاد",
            assetSymbol = "FOOLAD",
            action = TransactionAction.BUY,
            quantity = 100.0,
            unit = "سهم",
            unitPrice = 1_000.0,
            totalAmount = 100_000.0
        )
        val sell = TransactionEntity(
            datePersian = "1403/02/10",
            timestamp = 2_000L,
            assetClass = AssetClass.STOCK,
            assetName = "فولاد",
            assetSymbol = "FOOLAD",
            action = TransactionAction.SELL,
            quantity = 100.0,
            unit = "سهم",
            unitPrice = 1_200.0,
            totalAmount = 120_000.0
        )

        assertTrue(repository.saveTransaction(buy) is ValidationResult.Success)
        assertTrue(repository.saveTransaction(sell) is ValidationResult.Success)

        val storedTxs = repository.allTransactionsFlow.first()
        assertEquals(2, storedTxs.size)

        val summary = CalculationEngine.calculatePortfolio(storedTxs, emptyList(), AppSettingsEntity(), emptyList())
        assertTrue("Fully sold asset must not appear in active holdings", summary.holdings.isEmpty())
        assertEquals(0.0, summary.totalInvestedCostToman, 0.001)
        assertEquals(0.0, summary.totalPortfolioValueToman, 0.001)
        assertTrue(summary.allocationByClass.isEmpty())

        val detail = CalculationEngine.calculateAssetDetail(
            assetName = "فولاد",
            assetSymbol = "FOOLAD",
            assetClass = AssetClass.STOCK,
            unit = "سهم",
            transactions = storedTxs,
            prices = emptyList()
        )
        assertTrue(detail.isSoldOut)
        assertEquals(0.0, detail.currentQuantity, 1e-8)
        assertEquals(2, detail.transactions.size)
        assertEquals(TransactionAction.SELL, detail.transactions.first().action)
    }

    @Test
    fun testPartialSaleLeavesExactRemainingQuantity() {
        val txs = listOf(
            TransactionEntity(
                id = 1,
                datePersian = "1403/01/10",
                timestamp = 1_000L,
                assetClass = AssetClass.STOCK,
                assetName = "فملی",
                assetSymbol = "FEMELI",
                action = TransactionAction.BUY,
                quantity = 100.0,
                unit = "سهم",
                unitPrice = 2_000.0,
                totalAmount = 200_000.0
            ),
            TransactionEntity(
                id = 2,
                datePersian = "1403/02/10",
                timestamp = 2_000L,
                assetClass = AssetClass.STOCK,
                assetName = "فملی",
                assetSymbol = "FEMELI",
                action = TransactionAction.SELL,
                quantity = 99.0,
                unit = "سهم",
                unitPrice = 2_500.0,
                totalAmount = 247_500.0
            )
        )

        val summary = CalculationEngine.calculatePortfolio(txs, emptyList(), AppSettingsEntity(), emptyList())
        assertEquals(1, summary.holdings.size)
        val holding = summary.holdings.first()
        assertEquals(1.0, holding.quantity, 1e-8)
        assertEquals(2_000.0, holding.averagePurchasePriceToman ?: 0.0, 0.01)
        assertEquals(2_000.0, holding.totalCostToman, 0.01)
    }

    @Test
    fun testFractionalGoldPrecisionAndTinyValidHoldings() = runBlocking {
        // IEEE 754: 0.1 + 0.2 = 0.30000000000000004
        val buy1 = TransactionEntity(
            datePersian = "1403/01/05",
            timestamp = 1_000L,
            assetClass = AssetClass.GOLD,
            assetName = "طلای ۱۸ عیار",
            assetSymbol = "GOLD18",
            action = TransactionAction.BUY,
            quantity = 0.1,
            unit = "گرم",
            unitPrice = 3_000_000.0,
            totalAmount = 300_000.0
        )
        val buy2 = TransactionEntity(
            datePersian = "1403/01/06",
            timestamp = 2_000L,
            assetClass = AssetClass.GOLD,
            assetName = "طلای ۱۸ عیار",
            assetSymbol = "GOLD18",
            action = TransactionAction.BUY,
            quantity = 0.2,
            unit = "گرم",
            unitPrice = 3_000_000.0,
            totalAmount = 600_000.0
        )
        assertTrue(repository.saveTransaction(buy1) is ValidationResult.Success)
        assertTrue(repository.saveTransaction(buy2) is ValidationResult.Success)

        // Partial sell leaving 0.001 gram (must NOT be false-zeroed!)
        val sellPartial = TransactionEntity(
            datePersian = "1403/01/07",
            timestamp = 3_000L,
            assetClass = AssetClass.GOLD,
            assetName = "طلای ۱۸ عیار",
            assetSymbol = "GOLD18",
            action = TransactionAction.SELL,
            quantity = 0.299,
            unit = "گرم",
            unitPrice = 3_500_000.0,
            totalAmount = 1_046_500.0
        )
        assertTrue(repository.saveTransaction(sellPartial) is ValidationResult.Success)

        val afterPartial = CalculationEngine.calculatePortfolio(
            repository.allTransactionsFlow.first(),
            emptyList(),
            AppSettingsEntity(),
            emptyList()
        )
        assertEquals(1, afterPartial.holdings.size)
        assertEquals(0.001, afterPartial.holdings.first().quantity, 1e-8)

        // Exact final sale of remaining 0.001 gram
        val sellFinal = TransactionEntity(
            datePersian = "1403/01/08",
            timestamp = 4_000L,
            assetClass = AssetClass.GOLD,
            assetName = "طلای ۱۸ عیار",
            assetSymbol = "GOLD18",
            action = TransactionAction.SELL,
            quantity = 0.001,
            unit = "گرم",
            unitPrice = 3_500_000.0,
            totalAmount = 3_500.0
        )
        assertTrue(repository.saveTransaction(sellFinal) is ValidationResult.Success)

        val afterFull = CalculationEngine.calculatePortfolio(
            repository.allTransactionsFlow.first(),
            emptyList(),
            AppSettingsEntity(),
            emptyList()
        )
        assertTrue("Fully sold fractional gold must disappear cleanly", afterFull.holdings.isEmpty())
    }

    @Test
    fun testRebuyAfterFullSaleStartsFreshCostBasisAndReappears() {
        val txs = listOf(
            TransactionEntity(
                id = 1,
                datePersian = "1403/01/10",
                timestamp = 1_000L,
                assetClass = AssetClass.GOLD_FUND,
                assetName = "لوتوس",
                assetSymbol = "LOTUS",
                action = TransactionAction.BUY,
                quantity = 100.0,
                unit = "واحد",
                unitPrice = 1_000.0,
                totalAmount = 100_000.0
            ),
            TransactionEntity(
                id = 2,
                datePersian = "1403/02/10",
                timestamp = 2_000L,
                assetClass = AssetClass.GOLD_FUND,
                assetName = "لوتوس",
                assetSymbol = "LOTUS",
                action = TransactionAction.SELL,
                quantity = 100.0,
                unit = "واحد",
                unitPrice = 1_500.0,
                totalAmount = 150_000.0
            ),
            TransactionEntity(
                id = 3,
                datePersian = "1403/03/10",
                timestamp = 3_000L,
                assetClass = AssetClass.GOLD_FUND,
                assetName = "لوتوس",
                assetSymbol = "LOTUS",
                action = TransactionAction.BUY,
                quantity = 50.0,
                unit = "واحد",
                unitPrice = 2_000.0,
                totalAmount = 100_000.0
            )
        )

        val summary = CalculationEngine.calculatePortfolio(txs, emptyList(), AppSettingsEntity(), emptyList())
        assertEquals(1, summary.holdings.size)
        val holding = summary.holdings.first()
        assertEquals(50.0, holding.quantity, 1e-8)
        assertEquals(2_000.0, holding.averagePurchasePriceToman ?: 0.0, 0.01)
        assertEquals(100_000.0, holding.totalCostToman, 0.01)
    }

    @Test
    fun testMultipleBuysAtDifferentPricesAndPartialThenFullSell() {
        val buy1 = TransactionEntity(
            id = 1, datePersian = "1403/01/01", timestamp = 1_000L,
            assetClass = AssetClass.EQUITY_FUND, assetName = "آگاس", assetSymbol = "AGAS",
            action = TransactionAction.BUY, quantity = 100.0, unit = "واحد",
            unitPrice = 1_000.0, totalAmount = 100_000.0
        )
        val buy2 = TransactionEntity(
            id = 2, datePersian = "1403/01/15", timestamp = 2_000L,
            assetClass = AssetClass.EQUITY_FUND, assetName = "آگاس", assetSymbol = "AGAS",
            action = TransactionAction.BUY, quantity = 100.0, unit = "واحد",
            unitPrice = 2_000.0, totalAmount = 200_000.0
        )
        val sellPartial = TransactionEntity(
            id = 3, datePersian = "1403/02/01", timestamp = 3_000L,
            assetClass = AssetClass.EQUITY_FUND, assetName = "آگاس", assetSymbol = "AGAS",
            action = TransactionAction.SELL, quantity = 50.0, unit = "واحد",
            unitPrice = 2_200.0, totalAmount = 110_000.0
        )
        val deposit = TransactionEntity(
            id = 4, datePersian = "1403/02/05", timestamp = 3_500L,
            assetClass = AssetClass.EQUITY_FUND, assetName = "آگاس", assetSymbol = "AGAS",
            action = TransactionAction.DIVIDEND, quantity = 1.0, unit = "تومان",
            unitPrice = 15_000.0, totalAmount = 15_000.0
        )

        val partialSummary = CalculationEngine.calculatePortfolio(
            listOf(buy1, buy2, sellPartial, deposit),
            emptyList(),
            AppSettingsEntity(),
            emptyList()
        )
        assertEquals(1, partialSummary.holdings.size)
        val h = partialSummary.holdings.first()
        assertEquals(150.0, h.quantity, 1e-8)
        assertEquals(1_500.0, h.averagePurchasePriceToman ?: 0.0, 0.01)
        assertEquals(225_000.0, h.totalCostToman, 0.01)

        val sellRemaining = TransactionEntity(
            id = 5, datePersian = "1403/03/01", timestamp = 4_000L,
            assetClass = AssetClass.EQUITY_FUND, assetName = "آگاس", assetSymbol = "AGAS",
            action = TransactionAction.SELL, quantity = 150.0, unit = "واحد",
            unitPrice = 2_500.0, totalAmount = 375_000.0
        )

        val fullSummary = CalculationEngine.calculatePortfolio(
            listOf(buy1, buy2, sellPartial, deposit, sellRemaining),
            emptyList(),
            AppSettingsEntity(),
            emptyList()
        )
        assertTrue(fullSummary.holdings.isEmpty())
    }

    @Test
    fun testPreventNegativeHoldingsOnValidationAndCalculation() = runBlocking {
        val buy = TransactionEntity(
            datePersian = "1403/01/01",
            timestamp = 1_000L,
            assetClass = AssetClass.STOCK,
            assetName = "شپنا",
            assetSymbol = "SHAPNA",
            action = TransactionAction.BUY,
            quantity = 100.0,
            unit = "سهم",
            unitPrice = 500.0,
            totalAmount = 50_000.0
        )
        assertTrue(repository.saveTransaction(buy) is ValidationResult.Success)

        val excessiveSell = TransactionEntity(
            datePersian = "1403/01/05",
            timestamp = 2_000L,
            assetClass = AssetClass.STOCK,
            assetName = "شپنا",
            assetSymbol = "SHAPNA",
            action = TransactionAction.SELL,
            quantity = 150.0,
            unit = "سهم",
            unitPrice = 600.0,
            totalAmount = 90_000.0
        )
        val result = repository.saveTransaction(excessiveSell)
        assertTrue("Over-selling must be rejected", result is ValidationResult.Error)

        // Even if excessive SELL existed in raw input, CalculationEngine never emits negative holdings
        val calc = CalculationEngine.calculatePortfolio(listOf(buy, excessiveSell), emptyList(), AppSettingsEntity(), emptyList())
        assertTrue(calc.holdings.isEmpty())
    }

    @Test
    fun testAppStartsWithoutGoogleLoginAndNavigatesToAssetDetailAndBack() = runBlocking {
        // جلوگیری از فراخوانی شبکه در محیط تست آفلاین
        repository.saveSettings(AppSettingsEntity(priceUpdateFrequency = PriceUpdateFrequency.MANUAL_ONLY))

        // Pre-populate real transactions & current price in Room
        val buyTx = TransactionEntity(
            datePersian = "1403/04/10",
            timestamp = 1_000L,
            assetClass = AssetClass.STOCK,
            assetName = "فولاد",
            assetSymbol = "FOOLAD",
            action = TransactionAction.BUY,
            quantity = 100.0,
            unit = "سهم",
            unitPrice = 5_000.0,
            fees = 2_500.0,
            commissionType = CommissionType.FIXED,
            commissionRate = 2_500.0,
            totalAmount = 502_500.0,
            notes = "پله اول خرید"
        )
        assertTrue(repository.saveTransaction(buyTx) is ValidationResult.Success)
        repository.updateCurrentPrice(
            CurrentPriceEntity(
                assetSymbolOrName = "FOOLAD",
                assetName = "فولاد",
                assetClass = AssetClass.STOCK,
                price = 6_000.0,
                currency = CurrencyType.TOMAN,
                source = "بورس تهران"
            )
        )

        val viewModel = WealthViewModel(repository)

        composeTestRule.setContent {
            ModiriatSarmayeTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    WealthAppContent(viewModel = viewModel)
                }
            }
        }
        composeTestRule.waitForIdle()

        // ۱. برنامه بدون صفحه ورود گوگل مستقیماً روی داشبورد باز می‌شود
        composeTestRule.onNodeWithTag("dashboard_screen").assertIsDisplayed()

        // ۲. بررسی تب بیشتر: عدم وجود دکمه ورود به گوگل و وجود پشتیبان محلی JSON
        composeTestRule.onNodeWithTag("nav_item_more").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("connect_google_account_btn").assertDoesNotExist()
        composeTestRule.onNodeWithTag("google_drive_backup_card").assertDoesNotExist()
        composeTestRule.onNodeWithTag("more_screen_list")
            .performScrollToNode(hasTestTag("local_backup_card"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("local_backup_card").assertIsDisplayed()

        // ۳. رفتن به پرتفوی و کلیک روی کارت دارایی برای ورود به صفحه جزئیات
        composeTestRule.onNodeWithTag("nav_item_portfolio").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("portfolio_screen_list")
            .performScrollToNode(hasTestTag("holding_card_فولاد"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("holding_card_فولاد").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        // ۴. بررسی نمایش صفحه جزئیات دارایی، خلاصه وضعیت و تاریخچه تراکنش با یادداشت و کارمزد
        composeTestRule.onNodeWithTag("asset_detail_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("asset_detail_summary_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("asset_detail_list")
            .performScrollToNode(hasText("پله اول خرید", substring = true))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("یادداشت: پله اول خرید", substring = true).assertIsDisplayed()

        // ۵. ثبت فروش کامل در Room و مشاهده واکنش لحظه‌ای صفحه جزئیات + ناپدید شدن در پرتفوی پس از بازگشت
        val fullSell = TransactionEntity(
            datePersian = "1403/05/10",
            timestamp = 2_000L,
            assetClass = AssetClass.STOCK,
            assetName = "فولاد",
            assetSymbol = "FOOLAD",
            action = TransactionAction.SELL,
            quantity = 100.0,
            unit = "سهم",
            unitPrice = 6_000.0,
            fees = 3_000.0,
            totalAmount = 597_000.0
        )
        assertTrue(repository.saveTransaction(fullSell) is ValidationResult.Success)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("asset_detail_list")
            .performScrollToNode(hasTestTag("asset_detail_summary_card"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("تسویه‌شده (موجودی صفر)").assertIsDisplayed()

        // بازگشت به صفحه پرتفوی: دارایی کاملاً فروخته‌شده نباید در لیست پرتفوی باشد
        composeTestRule.onNodeWithTag("asset_detail_back_btn").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("holding_card_فولاد").assertDoesNotExist()
    }

    @Test
    fun testAssetDetailEmptyHistoryState() {
        val emptyDetail = CalculationEngine.calculateAssetDetail(
            assetName = "طلای ۱۸ عیار",
            assetSymbol = "GOLD18",
            assetClass = AssetClass.GOLD,
            unit = "گرم",
            transactions = emptyList(),
            prices = emptyList()
        )

        composeTestRule.setContent {
            ModiriatSarmayeTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    AssetDetailScreen(
                        detail = emptyDetail,
                        displayCurrency = CurrencyType.TOMAN,
                        onBack = {},
                        onAddTransactionForAsset = {},
                        onEditTransaction = {},
                        onDeleteTransaction = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("asset_detail_list")
            .performScrollToNode(hasTestTag("asset_detail_empty_history"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("asset_detail_empty_history").assertIsDisplayed()
    }
}
