package ir.modiriatsarmaye.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ir.modiriatsarmaye.app.data.cloud.DriveBackupInfo
import ir.modiriatsarmaye.app.data.local.AppDatabase
import ir.modiriatsarmaye.app.data.model.*
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

        val (gy, gm, gd) = jalali.toGregorian()
        assertEquals(2024, gy)
        assertEquals(3, gm)
        assertEquals(20, gd)
    }

    @Test
    fun testJalaliMonthLengthsAndLeapYear() {
        // 1403 is a leap year (30 days in Esfand)
        assertTrue(JalaliDate.isLeapYear(1403))
        assertEquals(30, JalaliDate.getMonthLength(1403, 12))

        // 1402 is not a leap year (29 days in Esfand)
        assertFalse(JalaliDate.isLeapYear(1402))
        assertEquals(29, JalaliDate.getMonthLength(1402, 12))

        // Months 1..6 have 31 days
        for (m in 1..6) {
            assertEquals(31, JalaliDate.getMonthLength(1403, m))
        }

        // Months 7..11 have 30 days
        for (m in 7..11) {
            assertEquals(30, JalaliDate.getMonthLength(1403, m))
        }
    }

    @Test
    fun testJalaliCanonicalFormats() {
        val date = JalaliDate(1403, 5, 9)
        assertEquals("1403/05/09", date.canonicalString)

        val parsedEn = PersianUtils.parseJalaliDate("1403/05/09")
        assertNotNull(parsedEn)
        assertEquals(1403, parsedEn!!.year)
        assertEquals(5, parsedEn.month)
        assertEquals(9, parsedEn.day)

        // Parsing Persian digits
        val parsedFa = PersianUtils.parseJalaliDate("۱۴۰۳/۰۵/۰۹")
        assertNotNull(parsedFa)
        assertEquals(1403, parsedFa!!.year)
        assertEquals(5, parsedFa.month)
        assertEquals(9, parsedFa.day)
    }

    @Test
    fun testJalaliFormatToPersianText() {
        val formatted = PersianUtils.formatJalaliDate(1403, 1, 15)
        assertTrue(formatted.contains("فروردین"))
        assertTrue(formatted.contains("۱۴۰۳") || formatted.contains("1403"))
    }

    @Test
    fun testDriveBackupInfoProperties() {
        val info = DriveBackupInfo(
            id = "drive-file-123",
            fileName = "modiriat_sarmaye_backup_1403.json",
            timestamp = 1711000000000L,
            sizeBytes = 2048L,
            transactionsCount = 15,
            goalsCount = 2,
            liabilitiesCount = 1
        )
        assertEquals("drive-file-123", info.id)
        assertEquals("modiriat_sarmaye_backup_1403.json", info.fileName)
        assertEquals(15, info.transactionsCount)
        assertEquals(2, info.goalsCount)
        assertEquals(1, info.liabilitiesCount)
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
        repository.insertTransaction(existingTx)

        val txsBefore = repository.getAllTransactions().first()
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
                        "symbol": "AGAS",
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
        assertTrue(mergeResult.isSuccess)
        val txsAfterMerge = repository.getAllTransactions().first()
        assertEquals(2, txsAfterMerge.size)
        assertTrue(txsAfterMerge.any { it.assetName == "طلای آب‌شده" })
        assertTrue(txsAfterMerge.any { it.assetName == "صندوق آگاس" })

        // Step 4: Test Replace (replaceExisting = true)
        val replaceResult = repository.restoreBackupJson(backupJson, replaceExisting = true)
        assertTrue(replaceResult.isSuccess)
        val txsAfterReplace = repository.getAllTransactions().first()
        assertEquals(1, txsAfterReplace.size)
        assertEquals("صندوق آگاس", txsAfterReplace[0].assetName)
    }
}
