package ir.modiriatsarmaye.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ir.modiriatsarmaye.app.data.cloud.DriveBackupInfo
import ir.modiriatsarmaye.app.data.local.AppDatabase
import ir.modiriatsarmaye.app.data.model.*
import ir.modiriatsarmaye.app.data.repository.ValidationResult
import ir.modiriatsarmaye.app.data.repository.WealthRepository
import ir.modiriatsarmaye.app.util.JalaliDate
import ir.modiriatsarmaye.app.util.PersianUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class JalaliDateAndCloudBackupTest {

    private lateinit var database: AppDatabase
    private lateinit var context: Context
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
    fun testJalaliDateConversions() {
        // 2024-03-20 is 1403/01/01
        val jalali = JalaliDate.fromGregorian(2024, 3, 20)
        assertEquals(1403, jalali.year)
        assertEquals(1, jalali.month)
        assertEquals(1, jalali.day)

        val gDate = jalali.toGregorian()
        assertEquals(2024, gDate.year)
        assertEquals(3, gDate.monthValue)
        assertEquals(20, gDate.dayOfMonth)
    }

    @Test
    fun testJalaliMonthLengthsAndLeapYear() {
        // 1403 is a leap year (30 days in Esfand)
        assertTrue(JalaliDate.isLeapYear(1403))
        assertEquals(30, JalaliDate.getDaysInMonth(1403, 12))

        // 1402 is not a leap year (29 days in Esfand)
        assertFalse(JalaliDate.isLeapYear(1402))
        assertEquals(29, JalaliDate.getDaysInMonth(1402, 12))

        // Months 1..6 have 31 days
        for (m in 1..6) {
            assertEquals(31, JalaliDate.getDaysInMonth(1403, m))
        }

        // Months 7..11 have 30 days
        for (m in 7..11) {
            assertEquals(30, JalaliDate.getDaysInMonth(1403, m))
        }
    }

    @Test
    fun testJalaliCanonicalFormats() {
        val date = JalaliDate(1403, 5, 9)
        assertEquals("1403/05/09", date.canonicalString)

        val parsedEn = JalaliDate.parseOrNull("1403/05/09")
        assertNotNull(parsedEn)
        assertEquals(1403, parsedEn!!.year)
        assertEquals(5, parsedEn.month)
        assertEquals(9, parsedEn.day)

        // Parsing Persian digits
        val parsedFa = JalaliDate.parseOrNull("۱۴۰۳/۰۵/۰۹")
        assertNotNull(parsedFa)
        assertEquals(1403, parsedFa!!.year)
        assertEquals(5, parsedFa.month)
        assertEquals(9, parsedFa.day)
    }

    @Test
    fun testJalaliFormatToPersianText() {
        val date = JalaliDate(1403, 1, 15)
        val formatted = date.longDisplayString
        assertTrue(formatted.contains("فروردین"))
        assertTrue(formatted.contains("۱۴۰۳") || formatted.contains("1403"))
    }

    @Test
    fun testDriveBackupInfoProperties() {
        val info = DriveBackupInfo(
            fileId = "drive-file-123",
            fileName = "modiriat_sarmaye_backup_1403.json",
            sizeBytes = 2048L,
            timestamp = 1711000000000L,
            transactionsCount = 15,
            pricesCount = 3,
            goalsCount = 2,
            liabilitiesCount = 1,
            rawJson = "{}"
        )
        assertEquals("drive-file-123", info.fileId)
        assertEquals("modiriat_sarmaye_backup_1403.json", info.fileName)
        assertEquals(15, info.transactionsCount)
        assertEquals(2, info.goalsCount)
        assertEquals(1, info.liabilitiesCount)
    }

    @Test
    fun testJalaliLeapYearsExhaustive() {
        // 1399: leap year (true)
        assertTrue(JalaliDate.isLeapYear(1399))
        assertEquals(30, JalaliDate.getDaysInMonth(1399, 12))

        // 1400: non-leap year (false)
        assertFalse(JalaliDate.isLeapYear(1400))
        assertEquals(29, JalaliDate.getDaysInMonth(1400, 12))

        // 1401: non-leap year (false)
        assertFalse(JalaliDate.isLeapYear(1401))
        assertEquals(29, JalaliDate.getDaysInMonth(1401, 12))

        // 1402: non-leap year (false)
        assertFalse(JalaliDate.isLeapYear(1402))
        assertEquals(29, JalaliDate.getDaysInMonth(1402, 12))

        // 1403: leap year (true)
        assertTrue(JalaliDate.isLeapYear(1403))
        assertEquals(30, JalaliDate.getDaysInMonth(1403, 12))

        // 1404: non-leap year (false)
        assertFalse(JalaliDate.isLeapYear(1404))
        assertEquals(29, JalaliDate.getDaysInMonth(1404, 12))
    }

    @Test
    fun testJalaliGregorianRoundTripLeapDays() {
        // 1403/12/30 <-> 2025/03/20
        val j1403 = JalaliDate(1403, 12, 30)
        val g2025 = j1403.toGregorian()
        assertEquals(2025, g2025.year)
        assertEquals(3, g2025.monthValue)
        assertEquals(20, g2025.dayOfMonth)

        val jBack1403 = JalaliDate.fromGregorian(2025, 3, 20)
        assertEquals(1403, jBack1403.year)
        assertEquals(12, jBack1403.month)
        assertEquals(30, jBack1403.day)

        // 1399/12/30 <-> 2021/03/20
        val j1399 = JalaliDate(1399, 12, 30)
        val g2021 = j1399.toGregorian()
        assertEquals(2021, g2021.year)
        assertEquals(3, g2021.monthValue)
        assertEquals(20, g2021.dayOfMonth)

        val jBack1399 = JalaliDate.fromGregorian(2021, 3, 20)
        assertEquals(1399, jBack1399.year)
        assertEquals(12, jBack1399.month)
        assertEquals(30, jBack1399.day)
    }

    @Test
    fun testRestoreBackupWithDifferentFieldNameFormats() = runBlocking {
        // JSON containing transactions with assetName, name, and asset_name
        val mixedBackupJson = """
            {
                "schemaVersion": 2,
                "exportedAtEpoch": 1711000000000,
                "transactions": [
                    {
                        "datePersian": "1403/01/01",
                        "assetClass": "GOLD",
                        "assetName": "طلای ۱۸ عیار",
                        "action": "BUY",
                        "quantity": 10.0,
                        "unit": "گرم",
                        "unitPrice": 3000000.0,
                        "totalAmount": 30000000.0
                    },
                    {
                        "date": "1403/02/01",
                        "category": "EQUITY_FUND",
                        "name": "صندوق آگاس",
                        "type": "BUY",
                        "quantity": 500.0,
                        "unit": "واحد",
                        "price": 10000.0,
                        "totalAmount": 5000000.0
                    },
                    {
                        "date_persian": "1403/03/01",
                        "asset_class": "STOCK",
                        "asset_name": "فولاد مبارکه",
                        "action": "BUY",
                        "quantity": 200.0,
                        "unit": "سهم",
                        "unit_price": 5000.0,
                        "total_amount": 1000000.0
                    }
                ],
                "prices": [],
                "goals": [],
                "liabilities": []
            }
        """.trimIndent()

        val restoreResult = repository.restoreBackupJson(mixedBackupJson, replaceExisting = true)
        assertTrue(restoreResult is ValidationResult.Success)

        val txs = repository.allTransactionsFlow.first()
        assertEquals(3, txs.size)
        assertTrue(txs.any { it.assetName == "طلای ۱۸ عیار" && it.assetClass == AssetClass.GOLD })
        assertTrue(txs.any { it.assetName == "صندوق آگاس" && it.assetClass == AssetClass.EQUITY_FUND })
        assertTrue(txs.any { it.assetName == "فولاد مبارکه" && it.assetClass == AssetClass.STOCK })
    }

    @Test
    fun testRepositoryInstantiationDoesNotModifyOrDeleteUserData() = runBlocking {
        // Insert real user transaction
        val userTx = TransactionEntity(
            id = 0,
            datePersian = "1403/05/01",
            assetClass = AssetClass.GOLD,
            assetName = "طلای پس‌انداز واقعی",
            action = TransactionAction.BUY,
            quantity = 5.0,
            unit = "گرم",
            unitPrice = 4_000_000.0,
            totalAmount = 20_000_000.0
        )
        database.transactionDao().insertTransaction(userTx)
        assertEquals(1, database.transactionDao().getAllTransactions().size)

        // Instantiating a new repository instance must not delete or touch user data
        val newRepo = WealthRepository(database, context)
        val txsAfter = database.transactionDao().getAllTransactions()
        assertEquals(1, txsAfter.size)
        assertEquals("طلای پس‌انداز واقعی", txsAfter[0].assetName)
    }

    @Test
    fun testRestoreReplaceVsMerge() = runBlocking {
        // Step 1: Insert an existing transaction
        val existingTx = TransactionEntity(
            id = 0,
            datePersian = "1403/01/10",
            assetClass = AssetClass.GOLD,
            assetName = "طلای آب‌شده",
            action = TransactionAction.BUY,
            quantity = 10.0,
            unit = "گرم",
            unitPrice = 3_500_000.0,
            totalAmount = 35_000_000.0
        )
        val saveRes = repository.saveTransaction(existingTx)
        assertTrue(saveRes is ValidationResult.Success)

        val txsBefore = repository.allTransactionsFlow.first()
        assertEquals(1, txsBefore.size)
        assertEquals("طلای آب‌شده", txsBefore[0].assetName)

        // Step 2: Prepare JSON backup with a different transaction
        val backupJson = """
            {
                "schemaVersion": 2,
                "exportedAtEpoch": 1711000000000,
                "transactions": [
                    {
                        "date": "1403/02/01",
                        "category": "EQUITY_FUND",
                        "name": "صندوق آگاس",
                        "symbol": "آگاس",
                        "type": "BUY",
                        "quantity": 500.0,
                        "unit": "واحد",
                        "price": 10000.0,
                        "totalAmount": 5000000.0
                    }
                ],
                "prices": [],
                "goals": [],
                "liabilities": []
            }
        """.trimIndent()

        // Step 3: Test Merge (replaceExisting = false)
        val mergeResult = repository.restoreBackupJson(backupJson, replaceExisting = false)
        assertTrue(mergeResult is ValidationResult.Success)
        val txsAfterMerge = repository.allTransactionsFlow.first()
        assertEquals(2, txsAfterMerge.size)
        assertTrue(txsAfterMerge.any { it.assetName == "طلای آب‌شده" })
        assertTrue(txsAfterMerge.any { it.assetName == "صندوق آگاس" })

        // Step 4: Test Replace (replaceExisting = true)
        val replaceResult = repository.restoreBackupJson(backupJson, replaceExisting = true)
        assertTrue(replaceResult is ValidationResult.Success)
        val txsAfterReplace = repository.allTransactionsFlow.first()
        assertEquals(1, txsAfterReplace.size)
        assertEquals("صندوق آگاس", txsAfterReplace[0].assetName)
    }

    @Test
    fun testInvalidEsfand30InNonLeapYearsAndMonthBoundaries() {
        // Invalid Esfand 30 in non-leap years (1400, 1402, 1404)
        assertNull(JalaliDate.parseOrNull("1400/12/30"))
        assertNull(JalaliDate.parseOrNull("1402/12/30"))
        assertNull(JalaliDate.parseOrNull("1404/12/30"))

        try {
            JalaliDate(1404, 12, 30)
            fail("Expected IllegalArgumentException for 1404/12/30")
        } catch (_: IllegalArgumentException) {
            // Expected
        }

        // Valid Esfand 29 in non-leap and Esfand 30 in leap
        assertNotNull(JalaliDate.parseOrNull("1404/12/29"))
        assertNotNull(JalaliDate.parseOrNull("1403/12/30"))

        // Month boundary: 1403/06/31 -> +1 day = 1403/07/01
        val endShahrivar = JalaliDate(1403, 6, 31).toGregorian().plusDays(1)
        val startMehr = JalaliDate.fromGregorian(endShahrivar.year, endShahrivar.monthValue, endShahrivar.dayOfMonth)
        assertEquals(JalaliDate(1403, 7, 1), startMehr)

        // Year boundary (leap year): 1403/12/30 -> +1 day = 1404/01/01
        val endLeapYear = JalaliDate(1403, 12, 30).toGregorian().plusDays(1)
        val startNewYear = JalaliDate.fromGregorian(endLeapYear.year, endLeapYear.monthValue, endLeapYear.dayOfMonth)
        assertEquals(JalaliDate(1404, 1, 1), startNewYear)

        // Year boundary (non-leap year): 1404/12/29 -> +1 day = 1405/01/01
        val endNormalYear = JalaliDate(1404, 12, 29).toGregorian().plusDays(1)
        val start1405 = JalaliDate.fromGregorian(endNormalYear.year, endNormalYear.monthValue, endNormalYear.dayOfMonth)
        assertEquals(JalaliDate(1405, 1, 1), start1405)
    }

    @Test
    fun testBackupRejectsBlankOrMissingAssetNameAndPreservesDatabase() = runBlocking {
        // Existing real user transaction
        val existingTx = TransactionEntity(
            id = 0,
            datePersian = "1403/04/01",
            assetClass = AssetClass.GOLD,
            assetName = "سکه امامی کاربر",
            action = TransactionAction.BUY,
            quantity = 2.0,
            unit = "عدد",
            unitPrice = 40_000_000.0,
            totalAmount = 80_000_000.0
        )
        assertTrue(repository.saveTransaction(existingTx) is ValidationResult.Success)

        // Backup with blank, whitespace, null, and missing assetName, plus invalid quantity
        val invalidBackup = """
            {
                "schemaVersion": 2,
                "transactions": [
                    {
                        "datePersian": "1403/01/01",
                        "assetClass": "GOLD",
                        "assetName": "   ",
                        "quantity": 5.0
                    },
                    {
                        "datePersian": "1403/01/02",
                        "assetClass": "STOCK",
                        "assetName": null,
                        "name": "",
                        "quantity": 10.0
                    },
                    {
                        "datePersian": "1403/01/03",
                        "assetClass": "GOLD",
                        "asset_name": "طلای معتبر با تعداد نامعتبر",
                        "quantity": -3.0
                    }
                ],
                "prices": []
            }
        """.trimIndent()

        val result = repository.restoreBackupJson(invalidBackup, replaceExisting = true)
        assertTrue(result is ValidationResult.Error)

        // Existing user transaction must remain intact
        val txsAfter = repository.allTransactionsFlow.first()
        assertEquals(1, txsAfter.size)
        assertEquals("سکه امامی کاربر", txsAfter[0].assetName)
    }
}
