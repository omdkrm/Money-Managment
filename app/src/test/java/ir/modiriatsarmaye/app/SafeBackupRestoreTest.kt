package ir.modiriatsarmaye.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ir.modiriatsarmaye.app.data.backup.SafeBackupManager
import ir.modiriatsarmaye.app.data.local.AppDatabase
import ir.modiriatsarmaye.app.data.model.*
import ir.modiriatsarmaye.app.data.repository.ValidationResult
import ir.modiriatsarmaye.app.util.CalculationEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SafeBackupRestoreTest {

    private lateinit var database: AppDatabase
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testParsePriceAsString() {
        val parent = JSONObject("""{"price": "123456"}""")
        val parsed = SafeBackupManager.parseFlexiblePrice(parent, "price")
        assertEquals(123456.0, parsed.amount!!, 0.001)
        assertEquals(CurrencyType.TOMAN, parsed.currency)
        assertTrue(parsed.isLegacyFormat)
    }

    @Test
    fun testParsePriceAsStringWithCommasAndPersianDigits() {
        val parent = JSONObject("""{"price": " ۱۲۳٬۴۵۶٫۵ "}""")
        val parsed = SafeBackupManager.parseFlexiblePrice(parent, "price")
        assertNotNull(parsed.amount)
        assertEquals(123456.5, parsed.amount!!, 0.01)
    }

    @Test
    fun testParsePriceAsStringWithEmbeddedCurrency() {
        val parentRial = JSONObject("""{"price": "2500000 ریال"}""")
        val parsedRial = SafeBackupManager.parseFlexiblePrice(parentRial, "price")
        assertNotNull(parsedRial.amount)
        assertEquals(2500000.0, parsedRial.amount!!, 0.001)
        assertEquals(CurrencyType.RIAL, parsedRial.currency)

        val parentToman = JSONObject("""{"price": "250,000 تومان"}""")
        val parsedToman = SafeBackupManager.parseFlexiblePrice(parentToman, "price")
        assertNotNull(parsedToman.amount)
        assertEquals(250000.0, parsedToman.amount!!, 0.001)
        assertEquals(CurrencyType.TOMAN, parsedToman.currency)
    }

    @Test
    fun testParsePriceAsNumber() {
        val parentInt = JSONObject("""{"price": 500000}""")
        val parsedInt = SafeBackupManager.parseFlexiblePrice(parentInt, "price")
        assertEquals(500000.0, parsedInt.amount!!, 0.001)

        val parentDouble = JSONObject("""{"price": 750000.25}""")
        val parsedDouble = SafeBackupManager.parseFlexiblePrice(parentDouble, "price")
        assertEquals(750000.25, parsedDouble.amount!!, 0.001)
    }

    @Test
    fun testParsePriceAsJsonObjectWithIrr() {
        val parent = JSONObject("""
            {
                "price": {
                    "amount": 1234560,
                    "currency": "IRR"
                }
            }
        """.trimIndent())
        val parsed = SafeBackupManager.parseFlexiblePrice(parent, "price")
        assertEquals(1234560.0, parsed.amount!!, 0.001)
        assertEquals(CurrencyType.RIAL, parsed.currency)
        assertFalse(parsed.isLegacyFormat)
    }

    @Test
    fun testParsePriceAsJsonObjectWithToman() {
        val parent = JSONObject("""
            {
                "price": {
                    "amount": 123456,
                    "currency": "TOMAN"
                }
            }
        """.trimIndent())
        val parsed = SafeBackupManager.parseFlexiblePrice(parent, "price")
        assertEquals(123456.0, parsed.amount!!, 0.001)
        assertEquals(CurrencyType.TOMAN, parsed.currency)
    }

    @Test
    fun testParsePriceAsNull() {
        val parent = JSONObject("""{"price": null}""")
        val parsed = SafeBackupManager.parseFlexiblePrice(parent, "price")
        assertNull(parsed.amount)
    }

    @Test
    fun testMissingPriceField() {
        val parent = JSONObject("""{"otherField": "val"}""")
        val parsed = SafeBackupManager.parseFlexiblePrice(parent, "price")
        assertNull(parsed.amount)
    }

    @Test
    fun testMalformedPriceDoesNotCrash() {
        val parentText = JSONObject("""{"price": "abc_not_a_number"}""")
        val parsedText = SafeBackupManager.parseFlexiblePrice(parentText, "price")
        assertNull(parsedText.amount)

        val parentBool = JSONObject("""{"price": true}""")
        val parsedBool = SafeBackupManager.parseFlexiblePrice(parentBool, "price")
        assertNull(parsedBool.amount)

        val parentArray = JSONObject("""{"price": [1, 2, 3]}""")
        val parsedArray = SafeBackupManager.parseFlexiblePrice(parentArray, "price")
        assertNull(parsedArray.amount)
    }

    @Test
    fun testRestoreLegacyBackupSchemaVersion1() = runBlocking {
        val legacyJson = """
            {
                "version": 1,
                "timestamp": 1710000000000,
                "appName": "مدیریت سرمایه",
                "transactions": [
                    {
                        "datePersian": "1403/01/15",
                        "timestamp": 1710000000000,
                        "assetClass": "GOLD",
                        "assetName": "طلای ۱۸ عیار",
                        "assetSymbol": "GERAM18",
                        "action": "BUY",
                        "quantity": 5.0,
                        "unit": "گرم",
                        "unitPrice": "3450000",
                        "currency": "TOMAN",
                        "fees": 0.0,
                        "totalAmount": 17250000.0
                    }
                ],
                "prices": [
                    {
                        "assetSymbolOrName": "GERAM18",
                        "assetName": "طلای ۱۸ عیار",
                        "assetClass": "GOLD",
                        "price": "3450000",
                        "currency": "TOMAN",
                        "unit": "گرم"
                    }
                ]
            }
        """.trimIndent()

        val report = SafeBackupManager.validateAndParseBackup(legacyJson)
        assertTrue(report.isValid)
        assertEquals(1, report.schemaVersion)
        assertEquals(1, report.validTransactions.size)
        assertEquals(1, report.validPrices.size)
        assertEquals(3450000.0, report.validTransactions[0].unitPrice!!, 0.001)
        assertEquals(3450000.0, report.validPrices[0].price, 0.001)

        val restoreResult = SafeBackupManager.executeSafeRestore(database, report)
        assertTrue(restoreResult is ValidationResult.Success)

        val txs = database.transactionDao().getAllTransactions()
        assertEquals(1, txs.size)
        assertEquals("طلای ۱۸ عیار", txs[0].assetName)
        assertEquals(5.0, txs[0].quantity, 0.001)

        val prices = database.currentPriceDao().getAllPrices()
        assertEquals(1, prices.size)
        assertEquals("GERAM18", prices[0].assetSymbolOrName)
        assertEquals(3450000.0, prices[0].price, 0.001)
    }

    @Test
    fun testRestoreNewBackupSchemaVersion2() = runBlocking {
        val newJson = """
            {
                "schemaVersion": 2,
                "version": 2,
                "timestamp": 1715000000000,
                "appName": "مدیریت سرمایه",
                "transactions": [
                    {
                        "datePersian": "1403/02/20",
                        "timestamp": 1715000000000,
                        "assetClass": "STOCK",
                        "assetName": "فولاد مبارکه",
                        "assetSymbol": "فولاد",
                        "action": "BUY",
                        "quantity": 1000.0,
                        "unit": "سهم",
                        "unitPrice": {
                            "amount": 5000.0,
                            "currency": "TOMAN"
                        },
                        "totalAmount": {
                            "amount": 5000000.0,
                            "currency": "TOMAN"
                        },
                        "currency": "TOMAN"
                    }
                ],
                "prices": [
                    {
                        "assetSymbolOrName": "فولاد",
                        "assetName": "فولاد مبارکه",
                        "assetClass": "STOCK",
                        "price": {
                            "amount": 5500.0,
                            "currency": "TOMAN"
                        },
                        "currency": "TOMAN",
                        "unit": "سهم"
                    }
                ]
            }
        """.trimIndent()

        val report = SafeBackupManager.validateAndParseBackup(newJson)
        assertTrue(report.isValid)
        assertEquals(2, report.schemaVersion)
        assertEquals(1, report.validTransactions.size)
        assertEquals(1, report.validPrices.size)
        assertEquals(5000.0, report.validTransactions[0].unitPrice!!, 0.001)
        assertEquals(5500.0, report.validPrices[0].price, 0.001)

        val restoreResult = SafeBackupManager.executeSafeRestore(database, report)
        assertTrue(restoreResult is ValidationResult.Success)

        val storedPrices = database.currentPriceDao().getAllPrices()
        assertEquals(1, storedPrices.size)
        assertEquals(5500.0, storedPrices[0].price, 0.001)
    }

    @Test
    fun testInvalidJsonSyntaxReturnsFriendlyPersianError() {
        val brokenJson = "{ this is clearly not json..."
        val report = SafeBackupManager.validateAndParseBackup(brokenJson)
        assertFalse(report.isValid)
        assertTrue(report.errors.isNotEmpty())
        assertTrue(report.errors[0].contains("فرمت فایل پشتیبان نامعتبر است"))
    }

    @Test
    fun testMissingTransactionsArrayReturnsFriendlyPersianError() {
        val jsonNoTx = """{"prices": []}"""
        val report = SafeBackupManager.validateAndParseBackup(jsonNoTx)
        assertFalse(report.isValid)
        assertTrue(report.errors.any { it.contains("تراکنش‌ها") })
    }

    @Test
    fun testMixedValidAndInvalidRecords() = runBlocking {
        val mixedJson = """
            {
                "schemaVersion": 2,
                "transactions": [
                    {
                        "datePersian": "1403/03/01",
                        "assetClass": "GOLD",
                        "assetName": "طلای آب‌شده",
                        "quantity": 2.5,
                        "unit": "گرم",
                        "unitPrice": "4000000",
                        "currency": "TOMAN"
                    },
                    {
                        "datePersian": "1403/03/01",
                        "assetClass": "GOLD",
                        "assetName": "",
                        "quantity": 1.0
                    },
                    {
                        "datePersian": "1403/03/01",
                        "assetClass": "GOLD",
                        "assetName": "سکه بهار آزادی",
                        "quantity": -5.0
                    }
                ],
                "prices": [
                    {
                        "assetSymbolOrName": "GERAM18",
                        "price": 0.0
                    },
                    {
                        "assetSymbolOrName": "SEKE_TAMAM",
                        "assetName": "سکه تمام طرح جدید",
                        "assetClass": "GOLD",
                        "price": 45000000.0,
                        "currency": "TOMAN"
                    }
                ]
            }
        """.trimIndent()

        val report = SafeBackupManager.validateAndParseBackup(mixedJson)
        assertTrue(report.isValid)
        assertEquals(1, report.validTransactions.size)
        assertEquals(2, report.skippedTransactionsCount)
        assertEquals(1, report.validPrices.size)
        assertEquals(1, report.skippedPricesCount)

        val restoreRes = SafeBackupManager.executeSafeRestore(database, report)
        assertTrue(restoreRes is ValidationResult.Success)

        val txList = database.transactionDao().getAllTransactions()
        assertEquals(1, txList.size)
        assertEquals("طلای آب‌شده", txList[0].assetName)

        val priceList = database.currentPriceDao().getAllPrices()
        assertEquals(1, priceList.size)
        assertEquals("SEKE_TAMAM", priceList[0].assetSymbolOrName)
        assertEquals(45000000.0, priceList[0].price, 0.001)
    }

    @Test
    fun testPreservingExistingUserDataOnFailure() = runBlocking {
        // ۱. افزودن داده موجود به دیتابیس
        val initialTx = TransactionEntity(
            id = 1,
            datePersian = "1402/12/29",
            timestamp = 1700000000000,
            assetClass = AssetClass.GOLD,
            assetName = "طلای اولیه کاربر",
            action = TransactionAction.BUY,
            quantity = 10.0,
            unit = "گرم",
            unitPrice = 3000000.0,
            totalAmount = 30000000.0
        )
        database.transactionDao().insertTransaction(initialTx)
        assertEquals(1, database.transactionDao().getAllTransactions().size)

        // ۲. تلاش برای بازیابی با JSON نامعتبر
        val invalidJson = "{ broken json"
        val report = SafeBackupManager.validateAndParseBackup(invalidJson)
        val res = SafeBackupManager.executeSafeRestore(database, report)

        assertTrue(res is ValidationResult.Error)
        // داده اولیه کاربر باید کاملاً دست‌نخورده حفظ شده باشد
        val txsAfter = database.transactionDao().getAllTransactions()
        assertEquals(1, txsAfter.size)
        assertEquals("طلای اولیه کاربر", txsAfter[0].assetName)
    }

    @Test
    fun testPortfolioCalculationsAfterRestore() = runBlocking {
        val backupJson = """
            {
                "schemaVersion": 2,
                "transactions": [
                    {
                        "datePersian": "1403/01/01",
                        "assetClass": "GOLD",
                        "assetName": "طلای ۱۸ عیار",
                        "assetSymbol": "GERAM18",
                        "action": "BUY",
                        "quantity": 10.0,
                        "unit": "گرم",
                        "unitPrice": { "amount": 3000000, "currency": "TOMAN" },
                        "totalAmount": { "amount": 30000000, "currency": "TOMAN" },
                        "currency": "TOMAN"
                    }
                ],
                "prices": [
                    {
                        "assetSymbolOrName": "GERAM18",
                        "assetName": "طلای ۱۸ عیار",
                        "assetClass": "GOLD",
                        "price": { "amount": 3500000, "currency": "TOMAN" },
                        "currency": "TOMAN",
                        "unit": "گرم"
                    }
                ]
            }
        """.trimIndent()

        val report = SafeBackupManager.validateAndParseBackup(backupJson)
        val restoreResult = SafeBackupManager.executeSafeRestore(database, report)
        assertTrue(restoreResult is ValidationResult.Success)

        val txs = database.transactionDao().getAllTransactions()
        val prices = database.currentPriceDao().getAllPrices()

        val summary = CalculationEngine.calculatePortfolio(
            transactions = txs,
            prices = prices,
            settings = AppSettingsEntity(),
            liabilities = emptyList()
        )

        // ۱۰ گرم طلا به قیمت روز ۳٬۵۰۰٬۰۰۰ تومان = ۳۵٬۰۰۰٬۰۰۰ تومان
        assertEquals(35000000.0, summary.totalPortfolioValueToman, 1.0)
        // هزینه خرید ۳۰٬۰۰۰٬۰۰۰ تومان
        assertEquals(30000000.0, summary.totalInvestedCostToman, 1.0)
        // سود تحقق‌نیافته ۵٬۰۰۰٬۰۰۰ تومان
        assertEquals(5000000.0, summary.totalProfitLossToman, 1.0)
        // درصد بازدهی: ۱۶.۶۷٪
        assertEquals(16.67, summary.totalReturnPct, 0.1)
    }

    @Test
    fun testConsistentExportOutput() = runBlocking {
        val tx = TransactionEntity(
            id = 1,
            datePersian = "1403/04/01",
            timestamp = 1718000000000,
            assetClass = AssetClass.CRYPTO,
            assetName = "تتر",
            assetSymbol = "USDT",
            action = TransactionAction.BUY,
            quantity = 100.0,
            unit = "واحد",
            unitPrice = 60000.0,
            totalAmount = 6000000.0,
            currency = CurrencyType.TOMAN
        )
        val price = CurrentPriceEntity(
            assetSymbolOrName = "USDT",
            assetName = "تتر",
            assetClass = AssetClass.CRYPTO,
            price = 62000.0,
            currency = CurrencyType.TOMAN,
            unit = "واحد"
        )

        val exportedJson = SafeBackupManager.exportBackupJson(
            transactions = listOf(tx),
            prices = listOf(price),
            goals = emptyList(),
            liabilities = emptyList()
        )

        val root = JSONObject(exportedJson)
        assertEquals(2, root.getInt("schemaVersion"))
        val priceObj = root.getJSONArray("prices").getJSONObject(0).getJSONObject("price")
        assertEquals(62000.0, priceObj.getDouble("amount"), 0.001)
        assertEquals("TOMAN", priceObj.getString("currency"))

        // خروجی اکسپورت باید بلافاصله توسط تابع اعتبارسنجی پذیرفته شود (Round-trip validity)
        val report = SafeBackupManager.validateAndParseBackup(exportedJson)
        assertTrue(report.isValid)
        assertEquals(1, report.validTransactions.size)
        assertEquals(1, report.validPrices.size)
    }
}
