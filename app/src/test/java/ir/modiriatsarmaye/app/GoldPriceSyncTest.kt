package ir.modiriatsarmaye.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ir.modiriatsarmaye.app.data.local.AppDatabase
import ir.modiriatsarmaye.app.data.market.AssetInstrumentMapper
import ir.modiriatsarmaye.app.data.market.CompositeMarketDataProvider
import ir.modiriatsarmaye.app.data.market.GoldPriceProvider
import ir.modiriatsarmaye.app.data.model.*
import ir.modiriatsarmaye.app.data.repository.WealthRepository
import ir.modiriatsarmaye.app.util.CalculationEngine
import ir.modiriatsarmaye.app.util.PersianUtils
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.SocketTimeoutException

@RunWith(RobolectricTestRunner::class)
class GoldPriceSyncTest {

    private fun createMockClient(
        responseCode: Int = 200,
        responseBody: String = "",
        throwException: IOException? = null
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (throwException != null) {
                    throw throwException
                }
                val req = chain.request()
                val mediaType = if (req.url.toString().endsWith(".json")) {
                    "application/json; charset=utf-8".toMediaTypeOrNull()
                } else {
                    "text/html; charset=utf-8".toMediaTypeOrNull()
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(responseCode)
                    .message(if (responseCode == 200) "OK" else "Error")
                    .body(responseBody.toResponseBody(mediaType))
                    .build()
            }
            .build()
    }

    @Test
    fun testHttp200ValidTgjuHtmlGoldPrice() = runBlocking {
        val tgjuHtml = """
            <!DOCTYPE html>
            <html>
            <head><title>قیمت طلای ۱۸ عیار / ۷۵۰</title></head>
            <body>
                <div class="summary">
                    <span class="title">طلای ۱۸ عیار / ۷۵۰</span>
                    <span class="value" data-col="info.last_price">۴۵٬۰۰۰٬۰۰۰</span>
                </div>
            </body>
            </html>
        """.trimIndent()

        val client = createMockClient(responseCode = 200, responseBody = tgjuHtml)
        val provider = GoldPriceProvider(client)

        val result = provider.fetchPrices()
        assertTrue("Expected successful fetch on HTTP 200", result.isSuccess)

        val list = result.getOrNull() ?: emptyList()
        assertFalse(list.isEmpty())

        val gold18 = list.first()
        assertEquals(45_000_000.0, gold18.price, 0.01)
        assertEquals(CurrencyType.RIAL, gold18.currency)
        assertEquals("گرم", gold18.unit)
        assertEquals("18K / 750", gold18.purity)
        assertEquals("GOLD_18K_750", gold18.priceType)
        assertEquals(PriceStatus.FRESH, gold18.status)
        assertTrue(gold18.source.contains("TGJU"))

        // Conversion to Toman: 45,000,000 Rial = 4,500,000 Toman
        val priceInToman = PersianUtils.convertCurrency(gold18.price, gold18.currency, CurrencyType.TOMAN)
        assertEquals(4_500_000.0, priceInToman, 0.01)
    }

    @Test
    fun testHttp200ValidTgjuJsonGoldPrice() = runBlocking {
        val tgjuJson = """
            {
                "current": {
                    "geram18": { "p": "45,000,000", "d": "1000", "dp": "0.5" },
                    "geram24": { "p": "60,000,000", "d": "2000", "dp": "0.6" }
                }
            }
        """.trimIndent()

        val client = createMockClient(responseCode = 200, responseBody = tgjuJson)
        val provider = GoldPriceProvider(client)

        val result = provider.fetchPrices()
        assertTrue(result.isSuccess)
        val list = result.getOrNull() ?: emptyList()
        val gold18 = list.find { it.purity == "18K / 750" }
        assertNotNull(gold18)
        assertEquals(45_000_000.0, gold18!!.price, 0.01)
        assertEquals(CurrencyType.RIAL, gold18.currency)
    }

    @Test
    fun testTgjuGoldChartHtmlParsing() = runBlocking {
        val chartHtml = """
            <table class="market-table">
                <tr data-market-nameslug="geram18" data-price="46,500,000">
                    <td>طلای ۱۸ عیار / ۷۵۰</td>
                    <td class="price">۴۶٬۵۰۰٬۰۰۰</td>
                </tr>
            </table>
        """.trimIndent()

        val client = createMockClient(responseCode = 200, responseBody = chartHtml)
        val provider = GoldPriceProvider(client)

        val result = provider.fetchPrices()
        assertTrue(result.isSuccess)
        val list = result.getOrNull() ?: emptyList()
        assertEquals(46_500_000.0, list.first().price, 0.01)
    }

    @Test
    fun testGoldDiagnosticsCaptureDetails() = runBlocking {
        val client = createMockClient(responseCode = 200, responseBody = "<html><body>No match</body></html>")
        val provider = GoldPriceProvider(client)

        val result = provider.fetchPrices()
        assertTrue(result.isFailure)

        val diagnostics = provider.lastDiagnostics
        assertEquals(3, diagnostics.size) // TEST A, TEST B, TEST C

        val diagA = diagnostics[0]
        assertEquals("TEST A", diagA.testId)
        assertEquals(GoldPriceProvider.TGJU_AJAX_URL, diagA.url)
        assertEquals("GET", diagA.httpMethod)
        assertEquals(200, diagA.httpStatusCode)
        assertFalse(diagA.isSuccess)
        assertNotNull(diagA.parserFailureReason)
        assertTrue(diagA.responseBodyPreview.isNotBlank())
    }

    @Test
    fun testDigitsAndCommasNormalization() {
        val provider = GoldPriceProvider()

        // Persian digits with Persian comma separator
        val p1 = provider.cleanNumberString("۲۱۸٬۳۹۶٬۰۰۰")
        assertEquals(218_396_000.0, p1 ?: 0.0, 0.01)

        // Arabic indic digits with English comma
        val p2 = provider.cleanNumberString("٢١٨,٣٩٦,٠٠٠")
        assertEquals(218_396_000.0, p2 ?: 0.0, 0.01)

        // HTML entities & spaces
        val p3 = provider.cleanNumberString("&nbsp;45,000,000&#160;")
        assertEquals(45_000_000.0, p3 ?: 0.0, 0.01)

        // Invalid non-positive or empty numbers
        assertNull(provider.cleanNumberString("0"))
        assertNull(provider.cleanNumberString(""))
        assertNull(provider.cleanNumberString("-1000"))
    }

    @Test
    fun testHttp404PreservesStalePriceAndShowsExactMessage() = runBlocking {
        val client = createMockClient(responseCode = 404, responseBody = "Not Found")
        val provider = GoldPriceProvider(client)

        val result = provider.fetchPrices()
        assertTrue("Provider must fail on 404", result.isFailure)
        val errorMsg = result.exceptionOrNull()?.message
        assertTrue(errorMsg?.contains("تغییر کرده است") == true || errorMsg?.contains("منبع دریافت قیمت طلا در دسترس نیست") == true)

        // Test Composite provider fallback preserving existing valid price in Room
        val composite = CompositeMarketDataProvider(listOf(provider))
        val existingPrices = listOf(
            CurrentPriceEntity(
                assetSymbolOrName = "طلای ۱۸ عیار",
                assetName = "طلای ۱۸ عیار / ۷۵۰",
                assetClass = AssetClass.GOLD,
                price = 40_000_000.0, // Existing valid price in Rial
                currency = CurrencyType.RIAL,
                source = "سامانه TGJU (طلای ۱۸ عیار / ۷۵۰)",
                lastUpdated = 1700000000L,
                status = PriceStatus.FRESH
            )
        )

        val report = composite.syncCurrentPrices(
            existingPrices = existingPrices,
            assetsToUpdate = listOf(Pair("طلای ۱۸ عیار", AssetClass.GOLD)),
            forceRefresh = true
        )

        assertEquals(0, report.updatedCount)
        assertEquals(1, report.failedCount)

        // Verify existing price was NOT overwritten with 0 or fake price, but marked STALE
        assertFalse(report.updatedPrices.isEmpty())
        val preservedPrice = report.updatedPrices.first()
        assertEquals(40_000_000.0, preservedPrice.price, 0.01)
        assertEquals(CurrencyType.RIAL, preservedPrice.currency)
        assertEquals(PriceStatus.STALE, preservedPrice.status)
        assertFalse(report.goldDiagnostics.isEmpty())
    }

    @Test
    fun testHttp401And403AccessDeniedMessage() = runBlocking {
        val client = createMockClient(responseCode = 403, responseBody = "Forbidden")
        val provider = GoldPriceProvider(client)

        val result = provider.fetchPrices()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("403 Forbidden") == true || result.exceptionOrNull()?.message?.contains("محدودیت") == true)
    }

    @Test
    fun testNetworkTimeoutPreservesStalePrice() = runBlocking {
        val client = createMockClient(throwException = SocketTimeoutException("Read timed out"))
        val provider = GoldPriceProvider(client)

        val result = provider.fetchPrices()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Timeout") == true || result.exceptionOrNull()?.message?.contains("مهلت") == true)

        val composite = CompositeMarketDataProvider(listOf(provider))
        val existingPrices = listOf(
            CurrentPriceEntity(
                assetSymbolOrName = "طلای ۱۸ عیار",
                assetName = "طلای ۱۸ عیار / ۷۵۰",
                assetClass = AssetClass.GOLD,
                price = 38_000_000.0,
                currency = CurrencyType.RIAL,
                source = "سامانه TGJU",
                lastUpdated = 1700000000L,
                status = PriceStatus.FRESH
            )
        )

        val report = composite.syncCurrentPrices(
            existingPrices = existingPrices,
            assetsToUpdate = listOf(Pair("طلای ۱۸ عیار", AssetClass.GOLD)),
            forceRefresh = true
        )

        val preserved = report.updatedPrices.first()
        assertEquals(38_000_000.0, preserved.price, 0.01)
        assertEquals(PriceStatus.STALE, preserved.status)
    }

    @Test
    fun testPriceGrowthCalculationAccuracy() {
        // Weighted Average Purchase Price = 100,000,000 Rial (10,000,000 Toman)
        // Current Price = 120,000,000 Rial (12,000,000 Toman)
        // Expected Price Growth % = (120,000,000 - 100,000,000) / 100,000,000 * 100 = +20.0%

        val tx = TransactionEntity(
            id = 1,
            datePersian = "1403/01/10",
            timestamp = 1000L,
            assetClass = AssetClass.GOLD,
            assetName = "طلای ۱۸ عیار",
            assetSymbol = "GOLD18",
            action = TransactionAction.BUY,
            quantity = 2.0, // 2 grams
            unit = "گرم",
            unitPrice = 100_000_000.0, // 100M Rial per gram
            currency = CurrencyType.RIAL,
            fees = 0.0,
            commissionType = CommissionType.FIXED,
            commissionRate = 0.0,
            totalAmount = 200_000_000.0
        )

        val currentPrice = CurrentPriceEntity(
            assetSymbolOrName = "طلای ۱۸ عیار",
            assetName = "طلای ۱۸ عیار / ۷۵۰",
            assetClass = AssetClass.GOLD,
            price = 120_000_000.0, // 120M Rial per gram
            currency = CurrencyType.RIAL,
            source = "سامانه TGJU (طلای ۱۸ عیار / ۷۵۰)",
            lastUpdated = System.currentTimeMillis(),
            status = PriceStatus.FRESH
        )

        val summary = CalculationEngine.calculatePortfolio(
            transactions = listOf(tx),
            prices = listOf(currentPrice),
            settings = AppSettingsEntity(),
            liabilities = emptyList()
        )

        val holding = summary.holdings.first { it.assetName == "طلای ۱۸ عیار" }
        assertNotNull(holding.priceGrowthPercent)
        assertEquals(20.0, holding.priceGrowthPercent ?: 0.0, 0.001)

        // 100M Rial = 10M Toman average purchase price
        assertEquals(10_000_000.0, holding.averagePurchasePriceToman ?: 0.0, 0.001)
        // 120M Rial = 12M Toman current price
        assertEquals(12_000_000.0, holding.currentPriceToman ?: 0.0, 0.001)
        // Total Invested = 2 * 10M = 20M Toman
        assertEquals(20_000_000.0, holding.totalCostToman, 0.001)
        // Current Value = 2 * 12M = 24M Toman
        assertEquals(24_000_000.0, holding.currentValueToman ?: 0.0, 0.001)
        // Total Profit = 4M Toman (+20.0%)
        assertEquals(4_000_000.0, holding.profitLossToman ?: 0.0, 0.001)
        assertEquals(20.0, holding.returnPercent ?: 0.0, 0.001)
    }

    @Test
    fun testDeterministicMappingFromTgjuJsonToPhysicalGoldHolding() = runBlocking {
        // Real-device extracted price: 334,194,000 Rial (33,419,400 Toman)
        val tgjuJson = """
            {
                "current": {
                    "geram18": { "p": "334,194,000", "d": "12000", "dp": "1.2" }
                }
            }
        """.trimIndent()

        val client = createMockClient(responseCode = 200, responseBody = tgjuJson)
        val provider = GoldPriceProvider(client)
        val fetchResult = provider.fetchPrices()

        assertTrue("Provider must succeed with TGJU AJAX", fetchResult.isSuccess)
        val fetchedList = fetchResult.getOrNull() ?: emptyList()
        val goldPrice = fetchedList.first()
        assertEquals(AssetInstrumentMapper.INSTRUMENT_GERAM18, goldPrice.instrumentId)
        assertEquals(334_194_000.0, goldPrice.price, 0.01)
        assertEquals(CurrencyType.RIAL, goldPrice.currency)

        // Verify conversion to Toman is exactly once: 334,194,000 Rial -> 33,419,400 Toman
        val priceInToman = PersianUtils.convertCurrency(goldPrice.price, goldPrice.currency, CurrencyType.TOMAN)
        assertEquals(33_419_400.0, priceInToman, 0.01)

        // Test with user's physical gold holding with fractional weight (e.g. 1.345 grams)
        val tx = TransactionEntity(
            id = 10,
            datePersian = "1403/05/15",
            timestamp = 2000L,
            assetClass = AssetClass.GOLD,
            assetName = "طلای فیزیکی (۱۸ عیار)",
            assetSymbol = "GOLD18",
            action = TransactionAction.BUY,
            quantity = 1.345, // Fractional weight in grams
            unit = "گرم",
            unitPrice = 300_000_000.0, // 300M Rial per gram (30M Toman)
            currency = CurrencyType.RIAL,
            fees = 0.0,
            commissionType = CommissionType.FIXED,
            commissionRate = 0.0,
            totalAmount = 1.345 * 300_000_000.0
        )

        val composite = CompositeMarketDataProvider(listOf(provider))
        val report = composite.syncCurrentPrices(
            existingPrices = emptyList(),
            assetsToUpdate = listOf(Pair("طلای فیزیکی (۱۸ عیار)", AssetClass.GOLD)),
            forceRefresh = true
        )

        assertEquals(2, report.updatedPrices.size) // Asset holding + Canonical GERAM18
        val holdingPriceEntity = report.updatedPrices.find { it.assetSymbolOrName == "طلای فیزیکی (۱۸ عیار)" }
        assertNotNull(holdingPriceEntity)
        assertEquals(334_194_000.0, holdingPriceEntity!!.price, 0.01)
        assertEquals(CurrencyType.RIAL, holdingPriceEntity.currency)
        assertEquals(AssetInstrumentMapper.INSTRUMENT_GERAM18, holdingPriceEntity.instrumentId)
        assertEquals(PriceStatus.FRESH, holdingPriceEntity.status)

        // Verify CalculationEngine connects the price to the holding through deterministic mapping
        val portfolio = CalculationEngine.calculatePortfolio(
            transactions = listOf(tx),
            prices = report.updatedPrices,
            settings = AppSettingsEntity(),
            liabilities = emptyList()
        )

        val holding = portfolio.holdings.first { it.assetName == "طلای فیزیکی (۱۸ عیار)" }
        assertEquals(PriceStatus.FRESH, holding.currentPriceStatus)
        assertEquals(33_419_400.0, holding.currentPriceToman ?: 0.0, 0.01)

        // Growth = (334,194,000 - 300,000,000) / 300,000,000 * 100 = 11.398%
        assertNotNull(holding.priceGrowthPercent)
        assertEquals(11.398, holding.priceGrowthPercent ?: 0.0, 0.01)

        // Total Cost Toman = 1.345 * 30,000,000 = 40,350,000 Toman
        assertEquals(40_350_000.0, holding.totalCostToman, 0.01)

        // Current Value Toman = 1.345 * 33,419,400 = 44,949,093 Toman
        assertEquals(44_949_093.0, holding.currentValueToman ?: 0.0, 0.01)
    }

    @Test
    fun testRoomPersistenceVerificationAndNoDuplicateTransactions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            val txDao = db.transactionDao()
            val priceDao = db.currentPriceDao()

            // 1. Initial State: User has a physical gold transaction
            val initialTx = TransactionEntity(
                id = 1,
                datePersian = "1403/06/01",
                timestamp = 3000L,
                assetClass = AssetClass.GOLD,
                assetName = "طلای فیزیکی (۱۸ عیار)",
                assetSymbol = "GERAM18",
                action = TransactionAction.BUY,
                quantity = 5.0,
                unit = "گرم",
                unitPrice = 310_000_000.0,
                currency = CurrencyType.RIAL,
                fees = 0.0,
                commissionType = CommissionType.FIXED,
                commissionRate = 0.0,
                totalAmount = 5.0 * 310_000_000.0
            )
            txDao.insertTransaction(initialTx)
            assertEquals(1, txDao.getAllTransactions().size)

            // 2. Perform Sync with mock TGJU provider
            val tgjuJson = """
                {
                    "current": {
                        "geram18": { "p": "334,194,000", "d": "5000", "dp": "0.8" }
                    }
                }
            """.trimIndent()
            val client = createMockClient(responseCode = 200, responseBody = tgjuJson)
            val provider = GoldPriceProvider(client)
            val composite = CompositeMarketDataProvider(listOf(provider))

            val existingPrices = priceDao.getAllPrices()
            val report = composite.syncCurrentPrices(
                existingPrices = existingPrices,
                assetsToUpdate = listOf(Pair("طلای فیزیکی (۱۸ عیار)", AssetClass.GOLD)),
                forceRefresh = true
            )

            // 3. Persist to Room
            priceDao.insertPrices(report.updatedPrices)

            // 4. Verify no duplicate transactions were created
            val afterTxs = txDao.getAllTransactions()
            assertEquals(1, afterTxs.size)

            // 5. Read-back verification from Room
            val readBackByInstrument = priceDao.getPriceByInstrumentId(AssetInstrumentMapper.INSTRUMENT_GERAM18)
            assertNotNull("Room read-back by instrumentId must succeed", readBackByInstrument)
            assertEquals(334_194_000.0, readBackByInstrument!!.price, 0.01)
            assertEquals(CurrencyType.RIAL, readBackByInstrument.currency)
            assertEquals(PriceStatus.FRESH, readBackByInstrument.status)

            // Verify canonical price is stored in RIAL, NOT converted Toman stored as Rial
            assertTrue("Price must be canonical Rial (> 100M)", readBackByInstrument.price > 100_000_000.0)

            // 6. Verify Portfolio calculation with persisted data
            val persistedPrices = priceDao.getAllPrices()
            val portfolio = CalculationEngine.calculatePortfolio(
                transactions = afterTxs,
                prices = persistedPrices,
                settings = AppSettingsEntity(),
                liabilities = emptyList()
            )

            val holding = portfolio.holdings.first { it.assetName == "طلای فیزیکی (۱۸ عیار)" }
            assertEquals(PriceStatus.FRESH, holding.currentPriceStatus)
            assertEquals(33_419_400.0, holding.currentPriceToman ?: 0.0, 0.01)
            assertNotNull(holding.priceGrowthPercent)
            // Growth = (334,194,000 - 310,000,000) / 310,000,000 * 100 = +7.804%
            assertEquals(7.804, holding.priceGrowthPercent ?: 0.0, 0.01)
        } finally {
            db.close()
        }
    }
}
