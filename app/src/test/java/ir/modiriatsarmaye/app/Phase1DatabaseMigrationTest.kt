package ir.modiriatsarmaye.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.LayoutDirection
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ir.modiriatsarmaye.app.data.local.AppDatabase
import ir.modiriatsarmaye.app.data.local.InitialData
import ir.modiriatsarmaye.app.data.model.*
import ir.modiriatsarmaye.app.ui.screens.MoreScreen
import ir.modiriatsarmaye.app.ui.theme.ModiriatSarmayeTheme
import ir.modiriatsarmaye.app.ui.viewmodel.WealthUiState
import ir.modiriatsarmaye.app.util.CalculationEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase1DatabaseMigrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private val openedDatabases = mutableListOf<AppDatabase>()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        openedDatabases.forEach { db ->
            if (db.isOpen) {
                db.close()
            }
        }
        openedDatabases.clear()
    }

    private fun openProductionConfiguredTempDb(dbFile: File): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            dbFile.absolutePath
        )
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .addCallback(AppDatabase.createCallback())
            .allowMainThreadQueries()
            .build()
            .also { openedDatabases.add(it) }
    }

    private fun createSyntheticV4Database(
        dbFile: File,
        populateData: (SQLiteDatabase) -> Unit
    ) {
        val sqlDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        sqlDb.use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `datePersian` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `assetClass` TEXT NOT NULL, `assetName` TEXT NOT NULL, `assetSymbol` TEXT NOT NULL, `action` TEXT NOT NULL, `quantity` REAL NOT NULL, `unit` TEXT NOT NULL, `unitPrice` REAL, `currency` TEXT NOT NULL, `fees` REAL NOT NULL, `commissionType` TEXT NOT NULL, `commissionRate` REAL NOT NULL, `totalAmount` REAL, `brokerOrSource` TEXT NOT NULL, `notes` TEXT NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `current_prices` (`assetSymbolOrName` TEXT NOT NULL, `assetName` TEXT NOT NULL, `assetClass` TEXT NOT NULL, `price` REAL NOT NULL, `currency` TEXT NOT NULL, `source` TEXT NOT NULL, `lastUpdated` INTEGER NOT NULL, `unit` TEXT NOT NULL, `priceType` TEXT NOT NULL, `isAutoUpdated` INTEGER NOT NULL, `status` TEXT NOT NULL, `errorMessage` TEXT, `instrumentId` TEXT NOT NULL, PRIMARY KEY(`assetSymbolOrName`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `financial_goals` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `targetAmountToman` REAL NOT NULL, `targetDatePersian` TEXT NOT NULL, `category` TEXT NOT NULL, `notes` TEXT NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `liabilities` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `totalAmountToman` REAL NOT NULL, `monthlyPaymentToman` REAL NOT NULL, `dueDatePersian` TEXT NOT NULL, `notes` TEXT NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `dividends` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `stockSymbol` TEXT NOT NULL, `stockName` TEXT NOT NULL, `sharesHeld` REAL NOT NULL, `dpsToman` REAL NOT NULL, `receivableAmountToman` REAL NOT NULL, `meetingDatePersian` TEXT NOT NULL, `paymentDatePersian` TEXT NOT NULL, `receivedAmountToman` REAL NOT NULL, `isPaid` INTEGER NOT NULL, `notes` TEXT NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `app_settings` (`id` INTEGER NOT NULL, `displayCurrency` TEXT NOT NULL, `monthlyInvestmentToman` REAL NOT NULL, `annualInvestmentIncreasePct` REAL NOT NULL, `targetGoldPct` REAL NOT NULL, `targetEquityFundPct` REAL NOT NULL, `targetFixedIncomePct` REAL NOT NULL, `targetUsdPct` REAL NOT NULL, `targetCashPct` REAL NOT NULL, `rebalanceThresholdPct` REAL NOT NULL, `inflationRatePct` REAL NOT NULL, `scenarioConservativeReturnPct` REAL NOT NULL, `scenarioBalancedReturnPct` REAL NOT NULL, `scenarioOptimisticReturnPct` REAL NOT NULL, `retirementHorizonYears` INTEGER NOT NULL, `retirementTargetToman` REAL NOT NULL, `pinCode` TEXT NOT NULL, `isPinEnabled` INTEGER NOT NULL, `isBiometricEnabled` INTEGER NOT NULL, `hideSensitiveAmounts` INTEGER NOT NULL, `notifyMonthlyInvestment` INTEGER NOT NULL, `notifyPriceUpdate` INTEGER NOT NULL, `notifyRebalance` INTEGER NOT NULL, `notifyGoalProgress` INTEGER NOT NULL, `notifyMonthlyReport` INTEGER NOT NULL, `notifyPortfolioDecline` INTEGER NOT NULL, `creatorName` TEXT NOT NULL, `supportEmail` TEXT NOT NULL, `priceUpdateFrequency` TEXT NOT NULL, `lastPriceUpdateTimestamp` INTEGER NOT NULL, `googleAccountEmail` TEXT, `googleAccountName` TEXT, `lastGoogleDriveBackupTimestamp` INTEGER, `lastGoogleDriveBackupSummary` TEXT, PRIMARY KEY(`id`))"
            )
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '7bef1574e9f3f6e8b9d7a80f8ca926f9')")
            populateData(db)
            db.version = 4
        }
    }

    private fun insertStandardSyntheticV4Records(db: SQLiteDatabase) {
        // 1. Transactions (covering fractional gold, RIAL vs TOMAN, null unitPrice, commissions, SELL)
        db.execSQL(
            """
            INSERT INTO transactions (id, datePersian, timestamp, assetClass, assetName, assetSymbol, action, quantity, unit, unitPrice, currency, fees, commissionType, commissionRate, totalAmount, brokerOrSource, notes)
            VALUES (101, '1404/11/10', 1738200000000, 'GOLD', 'طلای ۱۸ عیار', 'GOLD18', 'BUY', 0.99, 'گرم', 4250000.5, 'TOMAN', 21037.502475, 'PERCENTAGE', 0.5, 4228538.002475, 'طلافروشی نمونه', 'خرید آزمایشی طلا')
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO transactions (id, datePersian, timestamp, assetClass, assetName, assetSymbol, action, quantity, unit, unitPrice, currency, fees, commissionType, commissionRate, totalAmount, brokerOrSource, notes)
            VALUES (102, '1404/11/15', 1738600000000, 'STOCK', 'فولاد مبارکه', 'فولاد', 'BUY', 1500.0, 'سهم', NULL, 'RIAL', 45000.0, 'FIXED', 0.0, 9876543210.125, 'کارگزاری مفید', 'ثبت بدون قیمت واحد')
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO transactions (id, datePersian, timestamp, assetClass, assetName, assetSymbol, action, quantity, unit, unitPrice, currency, fees, commissionType, commissionRate, totalAmount, brokerOrSource, notes)
            VALUES (103, '1404/12/01', 1739900000000, 'GOLD', 'طلای ۱۸ عیار', 'GOLD18', 'SELL', 0.001, 'گرم', 4500000.0, 'TOMAN', 100.0, 'FIXED', 100.0, 4400.0, 'طلافروشی نمونه', 'فروش جزئی')
            """.trimIndent()
        )

        // 2. Current prices (including duplicate empty instrumentId to prove non-unique index safety)
        db.execSQL(
            """
            INSERT INTO current_prices (assetSymbolOrName, assetName, assetClass, price, currency, source, lastUpdated, unit, priceType, isAutoUpdated, status, errorMessage, instrumentId)
            VALUES ('طلای ۱۸ عیار', 'طلای ۱۸ عیار', 'GOLD', 4612345.67, 'TOMAN', 'TGJU', 1740000000000, 'گرم', 'GOLD_18K', 1, 'FRESH', NULL, 'GERAM18')
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO current_prices (assetSymbolOrName, assetName, assetClass, price, currency, source, lastUpdated, unit, priceType, isAutoUpdated, status, errorMessage, instrumentId)
            VALUES ('فولاد', 'فولاد مبارکه', 'STOCK', 52500.0, 'RIAL', 'TSETMC', 1740000001000, 'سهم', 'STOCK_EQUITY', 1, 'STALE', 'تست پیام خطا', 'IRO1FOLD0001')
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO current_prices (assetSymbolOrName, assetName, assetClass, price, currency, source, lastUpdated, unit, priceType, isAutoUpdated, status, errorMessage, instrumentId)
            VALUES ('دلار آزاد', 'دلار آمریکا', 'USD', 62150.25, 'TOMAN', 'ورود دستی', 1740000002000, 'دلار', 'FX', 0, 'MANUAL', NULL, '')
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO current_prices (assetSymbolOrName, assetName, assetClass, price, currency, source, lastUpdated, unit, priceType, isAutoUpdated, status, errorMessage, instrumentId)
            VALUES ('یورو آزاد', 'یورو', 'EUR', 67420.75, 'TOMAN', 'ورود دستی', 1740000003000, 'یورو', 'FX', 0, 'MANUAL', NULL, '')
            """.trimIndent()
        )

        // 3. Financial goals
        db.execSQL(
            """
            INSERT INTO financial_goals (id, title, targetAmountToman, targetDatePersian, category, notes)
            VALUES (201, 'صندوق اضطراری', 250000000.5, '1405/06/31', 'نقدینگی', 'هدف اول')
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO financial_goals (id, title, targetAmountToman, targetDatePersian, category, notes)
            VALUES (202, 'خرید مسکن', 5000000000.0, '1408/12/29', 'مسکن', 'هدف بلندمدت')
            """.trimIndent()
        )

        // 4. Liabilities
        db.execSQL(
            """
            INSERT INTO liabilities (id, title, totalAmountToman, monthlyPaymentToman, dueDatePersian, notes)
            VALUES (301, 'تسهیلات بانکی', 120000000.25, 4500000.5, '1406/03/15', 'قسط ماهانه')
            """.trimIndent()
        )

        // 5. Dividends
        db.execSQL(
            """
            INSERT INTO dividends (id, stockSymbol, stockName, sharesHeld, dpsToman, receivableAmountToman, meetingDatePersian, paymentDatePersian, receivedAmountToman, isPaid, notes)
            VALUES (401, 'فولاد', 'فولاد مبارکه', 1500.0, 125.5, 188250.0, '1404/04/20', '1404/08/10', 0.0, 0, 'در انتظار واریز')
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO dividends (id, stockSymbol, stockName, sharesHeld, dpsToman, receivableAmountToman, meetingDatePersian, paymentDatePersian, receivedAmountToman, isPaid, notes)
            VALUES (402, 'فملی', 'ملی مس', 500.0, 210.0, 105000.0, '1404/04/25', '1404/07/15', 105000.0, 1, 'واریز شده')
            """.trimIndent()
        )

        // 6. Custom App Settings (including legacy PIN and cloud metadata)
        db.execSQL(
            """
            INSERT INTO app_settings (id, displayCurrency, monthlyInvestmentToman, annualInvestmentIncreasePct, targetGoldPct, targetEquityFundPct, targetFixedIncomePct, targetUsdPct, targetCashPct, rebalanceThresholdPct, inflationRatePct, scenarioConservativeReturnPct, scenarioBalancedReturnPct, scenarioOptimisticReturnPct, retirementHorizonYears, retirementTargetToman, pinCode, isPinEnabled, isBiometricEnabled, hideSensitiveAmounts, notifyMonthlyInvestment, notifyPriceUpdate, notifyRebalance, notifyGoalProgress, notifyMonthlyReport, notifyPortfolioDecline, creatorName, supportEmail, priceUpdateFrequency, lastPriceUpdateTimestamp, googleAccountEmail, googleAccountName, lastGoogleDriveBackupTimestamp, lastGoogleDriveBackupSummary)
            VALUES (1, 'RIAL', 25000000.0, 20.0, 35.0, 25.0, 15.0, 15.0, 10.0, 7.5, 38.0, 22.0, 32.0, 45.0, 12, 15000000000.0, '1379', 1, 1, 1, 0, 1, 0, 1, 0, 1, 'کاربر آزمایشی', 'custom@example.ir', 'DAILY', 1739500000000, 'synthetic@example.com', 'Synthetic User', 1739600000000, 'ok')
            """.trimIndent()
        )
    }

    @Test
    fun registeredMigrations_coverAllSequentialVersionsThrough5() {
        val migrations = AppDatabase.ALL_MIGRATIONS.map { it.startVersion to it.endVersion }
        assertEquals(listOf(1 to 2, 2 to 3, 3 to 4, 4 to 5), migrations)
    }

    @Test
    fun freshDatabaseCreation_seedsCleanSettingsAndNoSampleData() = runBlocking {
        val dbFile = tempFolder.newFile("fresh_v5.db").apply { delete() }
        val db = openProductionConfiguredTempDb(dbFile)

        // Trigger Room initialization
        val settings = db.settingsDao().getSettingsDirect()
        assertNotNull(settings)
        assertEquals(InitialData.defaultSettings(), settings)
        assertEquals(0, db.transactionDao().getAllTransactions().size)
        assertEquals(0, db.currentPriceDao().getAllPrices().size)
        assertEquals(0, db.goalDao().getAllGoals().size)
        assertEquals(0, db.liabilityDao().getAllLiabilities().size)
        assertEquals(0, db.dividendDao().getAllDividends().size)

        val writableDb = db.openHelper.writableDatabase
        assertEquals(5, writableDb.version)

        val expectedTables = setOf(
            "transactions",
            "current_prices",
            "financial_goals",
            "liabilities",
            "dividends",
            "app_settings"
        )
        val actualTables = mutableSetOf<String>()
        writableDb.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
            while (cursor.moveToNext()) {
                actualTables.add(cursor.getString(0))
            }
        }
        assertTrue(actualTables.containsAll(expectedTables))

        val indexNames = mutableSetOf<String>()
        writableDb.query("SELECT name FROM sqlite_master WHERE type='index'").use { cursor ->
            while (cursor.moveToNext()) {
                indexNames.add(cursor.getString(0))
            }
        }
        assertTrue(indexNames.contains("index_transactions_timestamp"))
        assertTrue(indexNames.contains("index_current_prices_instrumentId_lastUpdated"))
    }

    @Test
    fun migration4To5_preservesAllTablesRecordsFieldsAndPrimaryKeys() = runBlocking {
        val dbFile = tempFolder.newFile("migrate_v4_v5.db")
        createSyntheticV4Database(dbFile, ::insertStandardSyntheticV4Records)

        val db = openProductionConfiguredTempDb(dbFile)
        val txs = db.transactionDao().getAllTransactions()
        val prices = db.currentPriceDao().getAllPrices()
        val goals = db.goalDao().getAllGoals()
        val liabilities = db.liabilityDao().getAllLiabilities()
        val dividends = db.dividendDao().getAllDividends()
        val settings = db.settingsDao().getSettingsDirect()

        assertEquals(5, db.openHelper.readableDatabase.version)

        // Verify all 3 transactions & exact primary keys / fields
        assertEquals(3, txs.size)
        val tx101 = txs.first { it.id == 101L }
        assertEquals("1404/11/10", tx101.datePersian)
        assertEquals(1738200000000L, tx101.timestamp)
        assertEquals(AssetClass.GOLD, tx101.assetClass)
        assertEquals("طلای ۱۸ عیار", tx101.assetName)
        assertEquals("GOLD18", tx101.assetSymbol)
        assertEquals(TransactionAction.BUY, tx101.action)
        assertEquals(0.99, tx101.quantity, 0.0)
        assertEquals("گرم", tx101.unit)
        assertEquals(4250000.5, tx101.unitPrice!!, 0.0)
        assertEquals(CurrencyType.TOMAN, tx101.currency)
        assertEquals(21037.502475, tx101.fees, 0.0)
        assertEquals(CommissionType.PERCENTAGE, tx101.commissionType)
        assertEquals(0.5, tx101.commissionRate, 0.0)
        assertEquals(4228538.002475, tx101.totalAmount!!, 0.0)
        assertEquals("طلافروشی نمونه", tx101.brokerOrSource)
        assertEquals("خرید آزمایشی طلا", tx101.notes)

        val tx102 = txs.first { it.id == 102L }
        assertNull(tx102.unitPrice)
        assertEquals(CurrencyType.RIAL, tx102.currency)
        assertEquals(1500.0, tx102.quantity, 0.0)
        assertEquals(9876543210.125, tx102.totalAmount!!, 0.0)

        val tx103 = txs.first { it.id == 103L }
        assertEquals(TransactionAction.SELL, tx103.action)
        assertEquals(0.001, tx103.quantity, 0.0)
        assertEquals(4400.0, tx103.totalAmount!!, 0.0)

        // Verify all 4 current_prices including duplicate empty instrumentId rows
        assertEquals(4, prices.size)
        val goldPrice = db.currentPriceDao().getPriceByInstrumentId("GERAM18")
        assertNotNull(goldPrice)
        assertEquals("طلای ۱۸ عیار", goldPrice!!.assetSymbolOrName)
        assertEquals(4612345.67, goldPrice.price, 0.0)
        assertNull(goldPrice.errorMessage)
        assertTrue(goldPrice.isAutoUpdated)

        val stockPrice = db.currentPriceDao().getPrice("فولاد")
        assertNotNull(stockPrice)
        assertEquals(CurrencyType.RIAL, stockPrice!!.currency)
        assertEquals(PriceStatus.STALE, stockPrice.status)
        assertEquals("تست پیام خطا", stockPrice.errorMessage)

        val emptyInstrumentPrices = db.currentPriceDao().getPricesByInstrumentId("")
        assertEquals(2, emptyInstrumentPrices.size)
        assertEquals(setOf("دلار آزاد", "یورو آزاد"), emptyInstrumentPrices.map { it.assetSymbolOrName }.toSet())

        // Verify goals & PKs
        assertEquals(2, goals.size)
        assertEquals(201L, goals[0].id)
        assertEquals(250000000.5, goals[0].targetAmountToman, 0.0)
        assertEquals(202L, goals[1].id)
        assertEquals("مسکن", goals[1].category)

        // Verify liabilities & PKs
        assertEquals(1, liabilities.size)
        assertEquals(301L, liabilities[0].id)
        assertEquals(120000000.25, liabilities[0].totalAmountToman, 0.0)
        assertEquals(4500000.5, liabilities[0].monthlyPaymentToman, 0.0)

        // Verify dividends & PKs
        assertEquals(2, dividends.size)
        val div401 = dividends.first { it.id == 401L }
        assertFalse(div401.isPaid)
        assertEquals(188250.0, div401.receivableAmountToman, 0.0)
        val div402 = dividends.first { it.id == 402L }
        assertTrue(div402.isPaid)
        assertEquals(105000.0, div402.receivedAmountToman, 0.0)

        // Verify custom settings & legacy PIN untouched
        assertNotNull(settings)
        assertEquals(1, settings!!.id)
        assertEquals(CurrencyType.RIAL, settings.displayCurrency)
        assertEquals(25000000.0, settings.monthlyInvestmentToman, 0.0)
        assertEquals("1379", settings.pinCode)
        assertTrue(settings.isPinEnabled)
        assertTrue(settings.isBiometricEnabled)
        assertTrue(settings.hideSensitiveAmounts)
        assertEquals(PriceUpdateFrequency.DAILY, settings.priceUpdateFrequency)
        assertEquals("synthetic@example.com", settings.googleAccountEmail)
        assertEquals("Synthetic User", settings.googleAccountName)
        assertEquals(1739600000000L, settings.lastGoogleDriveBackupTimestamp)
        assertEquals("ok", settings.lastGoogleDriveBackupSummary)
    }

    @Test
    fun migration4To5_preservesFinancialCalculationsExactly() = runBlocking {
        val dbFile = tempFolder.newFile("calc_preserve_v4_v5.db")
        createSyntheticV4Database(dbFile, ::insertStandardSyntheticV4Records)

        val expectedTxs = listOf(
            TransactionEntity(
                id = 101L,
                datePersian = "1404/11/10",
                timestamp = 1738200000000L,
                assetClass = AssetClass.GOLD,
                assetName = "طلای ۱۸ عیار",
                assetSymbol = "GOLD18",
                action = TransactionAction.BUY,
                quantity = 0.99,
                unit = "گرم",
                unitPrice = 4250000.5,
                currency = CurrencyType.TOMAN,
                fees = 21037.502475,
                commissionType = CommissionType.PERCENTAGE,
                commissionRate = 0.5,
                totalAmount = 4228538.002475,
                brokerOrSource = "طلافروشی نمونه",
                notes = "خرید آزمایشی طلا"
            ),
            TransactionEntity(
                id = 102L,
                datePersian = "1404/11/15",
                timestamp = 1738600000000L,
                assetClass = AssetClass.STOCK,
                assetName = "فولاد مبارکه",
                assetSymbol = "فولاد",
                action = TransactionAction.BUY,
                quantity = 1500.0,
                unit = "سهم",
                unitPrice = null,
                currency = CurrencyType.RIAL,
                fees = 45000.0,
                commissionType = CommissionType.FIXED,
                commissionRate = 0.0,
                totalAmount = 9876543210.125,
                brokerOrSource = "کارگزاری مفید",
                notes = "ثبت بدون قیمت واحد"
            ),
            TransactionEntity(
                id = 103L,
                datePersian = "1404/12/01",
                timestamp = 1739900000000L,
                assetClass = AssetClass.GOLD,
                assetName = "طلای ۱۸ عیار",
                assetSymbol = "GOLD18",
                action = TransactionAction.SELL,
                quantity = 0.001,
                unit = "گرم",
                unitPrice = 4500000.0,
                currency = CurrencyType.TOMAN,
                fees = 100.0,
                commissionType = CommissionType.FIXED,
                commissionRate = 100.0,
                totalAmount = 4400.0,
                brokerOrSource = "طلافروشی نمونه",
                notes = "فروش جزئی"
            )
        )
        val expectedPrices = listOf(
            CurrentPriceEntity("طلای ۱۸ عیار", "طلای ۱۸ عیار", AssetClass.GOLD, 4612345.67, CurrencyType.TOMAN, "TGJU", 1740000000000L, "گرم", "GOLD_18K", true, PriceStatus.FRESH, null, "GERAM18"),
            CurrentPriceEntity("فولاد", "فولاد مبارکه", AssetClass.STOCK, 52500.0, CurrencyType.RIAL, "TSETMC", 1740000001000L, "سهم", "STOCK_EQUITY", true, PriceStatus.STALE, "تست پیام خطا", "IRO1FOLD0001"),
            CurrentPriceEntity("دلار آزاد", "دلار آمریکا", AssetClass.USD, 62150.25, CurrencyType.TOMAN, "ورود دستی", 1740000002000L, "دلار", "FX", false, PriceStatus.MANUAL, null, ""),
            CurrentPriceEntity("یورو آزاد", "یورو", AssetClass.EUR, 67420.75, CurrencyType.TOMAN, "ورود دستی", 1740000003000L, "یورو", "FX", false, PriceStatus.MANUAL, null, "")
        )
        val expectedLiabilities = listOf(
            LiabilityEntity(301L, "تسهیلات بانکی", 120000000.25, 4500000.5, "1406/03/15", "قسط ماهانه")
        )
        val expectedSettings = AppSettingsEntity(id = 1, displayCurrency = CurrencyType.RIAL)

        val beforeSummary = CalculationEngine.calculatePortfolio(
            transactions = expectedTxs,
            prices = expectedPrices,
            settings = expectedSettings,
            liabilities = expectedLiabilities
        )

        val db = openProductionConfiguredTempDb(dbFile)
        val migratedTxs = db.transactionDao().getAllTransactions()
        val migratedPrices = db.currentPriceDao().getAllPrices()
        val migratedLiabilities = db.liabilityDao().getAllLiabilities()
        val migratedSettings = db.settingsDao().getSettingsDirect()!!

        val afterSummary = CalculationEngine.calculatePortfolio(
            transactions = migratedTxs,
            prices = migratedPrices,
            settings = migratedSettings,
            liabilities = migratedLiabilities
        )

        assertEquals(beforeSummary.totalPortfolioValueToman, afterSummary.totalPortfolioValueToman, 0.0)
        assertEquals(beforeSummary.totalLiabilitiesToman, afterSummary.totalLiabilitiesToman, 0.0)
        assertEquals(beforeSummary.netWorthToman, afterSummary.netWorthToman, 0.0)
        assertEquals(beforeSummary.totalInvestedCostToman, afterSummary.totalInvestedCostToman, 0.0)
        assertEquals(beforeSummary.totalProfitLossToman, afterSummary.totalProfitLossToman, 0.0)
        assertEquals(beforeSummary.totalReturnPct, afterSummary.totalReturnPct, 0.0)
        assertEquals(beforeSummary.holdings.size, afterSummary.holdings.size)

        val beforeGoldHolding = beforeSummary.holdings.first { it.assetName == "طلای ۱۸ عیار" }
        val goldHolding = afterSummary.holdings.first { it.assetName == "طلای ۱۸ عیار" }
        assertEquals(0.989, goldHolding.quantity, 0.00000001)
        assertEquals(beforeGoldHolding.currentValueToman!!, goldHolding.currentValueToman!!, 0.0)
    }

    @Test
    fun reopeningMigratedDatabaseAndRepeatedStartup_neverReseedsOrModifiesExistingRecords() = runBlocking {
        val dbFile = tempFolder.newFile("reopen_v4_v5.db")
        createSyntheticV4Database(dbFile, ::insertStandardSyntheticV4Records)

        // 1st open -> migrates 4 -> 5
        val firstOpen = openProductionConfiguredTempDb(dbFile)
        assertEquals(3, firstOpen.transactionDao().getTransactionCount())
        assertEquals("کاربر آزمایشی", firstOpen.settingsDao().getSettingsDirect()!!.creatorName)
        firstOpen.close()

        // 2nd open + repeated InitialData.populateDatabase calls (simulating repeated startup)
        val secondOpen = openProductionConfiguredTempDb(dbFile)
        InitialData.populateDatabase(secondOpen)
        InitialData.populateDatabase(secondOpen)

        val settingsRowsCount = secondOpen.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM app_settings").use { c ->
                c.moveToFirst()
                c.getInt(0)
            }
        assertEquals(1, settingsRowsCount)
        assertEquals("کاربر آزمایشی", secondOpen.settingsDao().getSettingsDirect()!!.creatorName)
        assertEquals("1379", secondOpen.settingsDao().getSettingsDirect()!!.pinCode)
        assertEquals(3, secondOpen.transactionDao().getTransactionCount())
        assertEquals(4, secondOpen.currentPriceDao().getAllPrices().size)
        assertEquals(2, secondOpen.goalDao().getAllGoals().size)
        assertEquals(1, secondOpen.liabilityDao().getAllLiabilities().size)
        assertEquals(2, secondOpen.dividendDao().getAllDividends().size)
        secondOpen.close()

        // 3rd open
        val thirdOpen = openProductionConfiguredTempDb(dbFile)
        assertEquals(3, thirdOpen.transactionDao().getTransactionCount())
        assertEquals(25000000.0, thirdOpen.settingsDao().getSettingsDirect()!!.monthlyInvestmentToman, 0.0)
    }

    @Test
    fun noDestructiveFallback_unmappedVersionFailsLoudlyAndPreservesDatabaseOnDisk() {
        val dbFile = tempFolder.newFile("unmapped_v99.db")
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { rawDb ->
            rawDb.execSQL(
                "CREATE TABLE IF NOT EXISTS `transactions` (`id` INTEGER PRIMARY KEY NOT NULL, `assetName` TEXT NOT NULL, `quantity` REAL NOT NULL)"
            )
            rawDb.execSQL(
                "INSERT INTO transactions (id, assetName, quantity) VALUES (777, 'دارایی حیاتی', 12.345)"
            )
            rawDb.version = 99
        }

        val db = openProductionConfiguredTempDb(dbFile)
        var failedWithMissingMigration = false
        try {
            db.openHelper.writableDatabase
        } catch (e: IllegalStateException) {
            failedWithMissingMigration = true
        }
        assertTrue(
            "Opening an unmapped DB version must fail explicitly rather than destructively wiping user data",
            failedWithMissingMigration
        )
        db.close()

        // Verify synthetic row was NOT wiped on disk
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { rawDb ->
            assertEquals(99, rawDb.version)
            rawDb.rawQuery("SELECT id, assetName, quantity FROM transactions", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(777L, c.getLong(0))
                assertEquals("دارایی حیاتی", c.getString(1))
                assertEquals(12.345, c.getDouble(2), 0.0)
            }
        }
    }

    @Test
    fun migrationAndStartup_doNotTriggerSafeDataCleanupOrResetToInitialData() = runBlocking {
        // Synthetic rows intentionally include patterns such as empty notes / legacy manual prices
        // and custom settings that resetToInitialData() would clear if mistakenly called during startup/migration.
        val dbFile = tempFolder.newFile("no_cleanup_reset_v4_v5.db")
        createSyntheticV4Database(dbFile) { rawDb ->
            insertStandardSyntheticV4Records(rawDb)
            // Add an extra synthetic transaction with sample-looking title to prove SafeDataCleanup is NOT invoked on migration/startup
            rawDb.execSQL(
                """
                INSERT INTO transactions (id, datePersian, timestamp, assetClass, assetName, assetSymbol, action, quantity, unit, unitPrice, currency, fees, commissionType, commissionRate, totalAmount, brokerOrSource, notes)
                VALUES (104, '1404/12/05', 1740100000000, 'EQUITY_FUND', 'صندوق آگاس', 'آگاس', 'BUY', 250.0, 'واحد', 25000.0, 'TOMAN', 0.0, 'FIXED', 0.0, 6250000.0, 'بورس', 'تراکنش واقعی کاربر')
                """.trimIndent()
            )
        }

        val db = openProductionConfiguredTempDb(dbFile)
        InitialData.populateDatabase(db)

        val allTxs = db.transactionDao().getAllTransactions()
        assertEquals(4, allTxs.size)
        assertTrue(allTxs.any { it.id == 104L && it.assetName == "صندوق آگاس" })
        assertEquals("کاربر آزمایشی", db.settingsDao().getSettingsDirect()!!.creatorName)
        assertEquals(2, db.goalDao().getAllGoals().size)
        assertEquals(1, db.liabilityDao().getAllLiabilities().size)
        assertEquals(2, db.dividendDao().getAllDividends().size)
    }

    @Test
    fun freshInstall_repeatedCallbackExecutionIsIdempotent() {
        val dbFile = tempFolder.newFile("idempotent_callback.db").apply { delete() }
        val db = openProductionConfiguredTempDb(dbFile)
        val sqliteDb = db.openHelper.writableDatabase

        // Modify existing settings via SQLite directly, then simulate seedDefaultSettingsIfMissing again
        sqliteDb.execSQL("UPDATE app_settings SET creatorName = 'تنظیم سفارشی کاربر', monthlyInvestmentToman = 5000000.0 WHERE id = 1")
        InitialData.seedDefaultSettingsIfMissing(sqliteDb)

        runBlocking {
            val settings = db.settingsDao().getSettingsDirect()!!
            assertEquals("تنظیم سفارشی کاربر", settings.creatorName)
            assertEquals(5000000.0, settings.monthlyInvestmentToman, 0.0)
        }
    }

    @Test
    fun packageIdentity_remainsModiriatSarmayeEverywhere() {
        assertEquals("ir.modiriatsarmaye.app", context.packageName)
        assertEquals("ir.modiriatsarmaye.app", BuildConfig.APPLICATION_ID)
        assertEquals("modiriat_sarmaye_db", AppDatabase.DATABASE_NAME)
    }

    @Test
    fun moreScreenUi_doesNotExposeDestructiveDatabaseResetButton() {
        val settings = InitialData.defaultSettings()
        val uiState = WealthUiState(
            settings = settings,
            portfolioSummary = CalculationEngine.calculatePortfolio(emptyList(), emptyList(), settings, emptyList())
        )
        composeTestRule.setContent {
            ModiriatSarmayeTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    MoreScreen(
                        uiState = uiState,
                        onSaveSettings = {},
                        onExportBackup = { "{}" },
                        onRestoreBackup = { _, _ -> },
                        onSyncMarketPrices = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("more_screen_list")
            .performScrollToNode(hasTestTag("local_backup_card"))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("local_backup_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("export_file_backup_btn").assertIsDisplayed()
        composeTestRule.onNodeWithTag("restore_file_backup_btn").assertIsDisplayed()

        assertEquals(
            0,
            composeTestRule.onAllNodesWithText("بازنشانی پایگاه داده به مقادیر پیش‌فرض اولیه", substring = true)
                .fetchSemanticsNodes().size
        )
        assertEquals(
            0,
            composeTestRule.onAllNodesWithText("بازنشانی داده‌ها به حالت اولیه", substring = true)
                .fetchSemanticsNodes().size
        )
        assertEquals(
            0,
            composeTestRule.onAllNodesWithText("بازنشانی داده‌ها", substring = true)
                .fetchSemanticsNodes().size
        )
    }
}
