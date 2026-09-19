package ir.modiriatsarmaye.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ir.modiriatsarmaye.app.data.local.AppDatabase
import ir.modiriatsarmaye.app.data.market.CompositeMarketDataProvider
import ir.modiriatsarmaye.app.data.market.GoldPriceProvider
import ir.modiriatsarmaye.app.data.market.StockInstrumentMapper
import ir.modiriatsarmaye.app.data.market.StockPriceProvider
import ir.modiriatsarmaye.app.data.model.*
import ir.modiriatsarmaye.app.data.repository.WealthRepository
import ir.modiriatsarmaye.app.util.CalculationEngine
import ir.modiriatsarmaye.app.util.PersianUtils
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * آزمون‌های جامع اعتبارسنجی خط لوله قیمت سهام (Stock Price Synchronization Pipeline Tests)
 * شامل ۱۳ آزمون دقیق مطابق نیازمندی‌های پروژه:
 * 1. Symbol mapping
 * 2. HTTP 200 with structured data (TSETMC MarketWatch JSON)
 * 3. HTTP 200 with HTML fallback (TGJU / Web table)
 * 4. Persian digits in prices
 * 5. Comma-separated numbers
 * 6. Missing symbol error case ("نیاز به تعیین نماد")
 * 7. Network timeout
 * 8. HTTP 500 error
 * 9. HTML parser resilience against minor DOM changes
 * 10. Correct storage in Room
 * 11. Stale price preservation on network failure
 * 12. Conversion between Rial and Toman
 * 13. Calculation of portfolio value and growth percent
 */
@RunWith(RobolectricTestRunner::class)
class StockPriceSyncTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

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
                val mediaType = if (req.url.toString().contains("json") || req.url.toString().contains("MarketWatch")) {
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

    /**
     * ۱. اعتبارسنجی نگاشت دقیق نماد بورسی و رد نام‌های مبهم
     */
    @Test
    fun testSymbolMapping() {
        // نمادهای معتبر بورسی با رعایت حروف فارسی
        assertEquals("فولاد", StockInstrumentMapper.resolveStockSymbol("فولاد"))
        assertEquals("فملی", StockInstrumentMapper.resolveStockSymbol("  فملی  "))
        assertEquals("شپنا", StockInstrumentMapper.resolveStockSymbol("شپنا"))
        assertEquals("خساپا", StockInstrumentMapper.resolveStockSymbol("خساپا"))
        assertEquals("وبملت", StockInstrumentMapper.resolveStockSymbol("وبملت"))

        // نام‌های توصیفی طولانی و مبهم که نماد بورسی نیستند باید null شوند
        assertNull(StockInstrumentMapper.resolveStockSymbol("سهام پتروشیمی خلیج فارس"))
        assertNull(StockInstrumentMapper.resolveStockSymbol("شرکت فولاد مبارکه اصفهان"))
        assertNull(StockInstrumentMapper.resolveStockSymbol("صندوق سرمایه گذاری طلای کهربا"))
        assertNull(StockInstrumentMapper.resolveStockSymbol(""))
        assertNull(StockInstrumentMapper.resolveStockSymbol("   "))

        // بررسی برچسب خطای الزامی
        assertEquals("نیاز به تعیین نماد", StockInstrumentMapper.SYMBOL_REQUIRED_LABEL)
        assertFalse(StockInstrumentMapper.isValidSymbol(""))
        assertFalse(StockInstrumentMapper.isValidSymbol("شرکت صنعتی سرمایه گذاری"))
        assertTrue(StockInstrumentMapper.isValidSymbol("فولاد"))
    }

    /**
     * ۲. پاسخ HTTP 200 با داده‌های ساخت‌یافته JSON از سرور دیده‌بان بازار TSETMC
     */
    @Test
    fun testHttp200WithStructuredData() = runBlocking {
        val tsetmcJson = """
            {
                "marketwatch": [
                    { "l18": "فولاد", "l30": "فولاد مبارکه اصفهان", "heven": 123000, "pDrCotVal": 5230, "pClosing": 5210, "qTitng": 15000000 },
                    { "l18": "شپنا", "l30": "پالایش نفت اصفهان", "heven": 123000, "pDrCotVal": 4120, "pClosing": 4100, "qTitng": 8000000 }
                ]
            }
        """.trimIndent()

        val client = createMockClient(responseCode = 200, responseBody = tsetmcJson)
        val stockProvider = StockPriceProvider(client)

        val result = stockProvider.fetchPriceForAsset("فولاد", AssetClass.STOCK)
        assertTrue("Expected successful fetch on structured JSON", result.isSuccess)

        val price = result.getOrNull()
        assertNotNull(price)
        assertEquals("فولاد", price!!.assetSymbolOrName)
        // 5230 Rial in TSETMC -> 523.0 Toman
        assertEquals(523.0, price.price, 0.01)
        assertEquals(CurrencyType.TOMAN, price.currency)
        assertEquals(5230.0, price.originalPrice!!, 0.01)
        assertTrue(price.source.contains("TSETMC"))
        assertEquals(PriceStatus.FRESH, price.status)
        assertEquals("سهم", price.unit)
    }

    /**
     * ۳. پاسخ HTTP 200 با داده‌های HTML (Fallback)
     */
    @Test
    fun testHttp200WithHtmlFallback() = runBlocking {
        val fallbackHtml = """
            <!DOCTYPE html>
            <html lang="fa">
            <head><title>نرخ سهام فولاد</title></head>
            <body>
                <table class="stock-table">
                    <tr><th>نماد</th><th>قیمت پایانی</th><th>آخرین معامله</th></tr>
                    <tr>
                        <td class="symbol">فولاد</td>
                        <td class="closing">۵,۱۸۰</td>
                        <td class="last-price">۵,۲۰۰</td>
                    </tr>
                </table>
            </body>
            </html>
        """.trimIndent()

        val client = createMockClient(responseCode = 200, responseBody = fallbackHtml)
        val stockProvider = StockPriceProvider(client)

        val result = stockProvider.fetchPriceForAsset("فولاد", AssetClass.STOCK)
        assertTrue("Expected successful fetch on HTML fallback", result.isSuccess)

        val price = result.getOrNull()
        assertNotNull(price)
        assertEquals("فولاد", price!!.assetSymbolOrName)
        // 5200 Rial in HTML -> 520.0 Toman
        assertEquals(520.0, price.price, 0.01)
        assertEquals(5200.0, price.originalPrice!!, 0.01)
        assertTrue(price.source.contains("HTML"))
    }

    /**
     * ۴. پشتیبانی از ارقام فارسی در قیمت‌ها
     */
    @Test
    fun testPersianDigitsInPrices() {
        val persianPriceStr = "۴۵٬۶۷۰"
        val parsed = PersianUtils.parsePersianDouble(persianPriceStr)
        assertNotNull(parsed)
        assertEquals(45670.0, parsed!!, 0.01)

        val persianDigitsOnly = "۱۲۳۴۵"
        val parsed2 = PersianUtils.parsePersianDouble(persianDigitsOnly)
        assertEquals(12345.0, parsed2!!, 0.01)
    }

    /**
     * ۵. پشتیبانی از اعداد جداشده با کاما و ارقام فارسی و انگلیسی
     */
    @Test
    fun testCommaSeparatedNumbers() {
        val mixedCommas = "۱,۲۳۴,۵۰۰"
        val cleaned = PersianUtils.parsePersianDouble(mixedCommas)
        assertNotNull(cleaned)
        assertEquals(1234500.0, cleaned!!, 0.01)

        val standardCommas = "12,345"
        val cleaned2 = PersianUtils.parsePersianDouble(standardCommas)
        assertNotNull(cleaned2)
        assertEquals(12345.0, cleaned2!!, 0.01)
    }

    /**
     * ۶. مدیریت خطای فقدان نماد و نمایش پیام "نیاز به تعیین نماد"
     */
    @Test
    fun testMissingSymbolErrorCase() = runBlocking {
        val client = createMockClient(responseCode = 200, responseBody = "{}")
        val stockProvider = StockPriceProvider(client)
        val goldProvider = GoldPriceProvider(client)
        val composite = CompositeMarketDataProvider(goldProvider = goldProvider, stockProvider = stockProvider)
        val repository = WealthRepository(
            database = db,
            marketDataProvider = composite
        )

        // همگام‌سازی نماد نامعتبر / خالی
        val report = repository.syncStockPrice("")
        assertEquals(0, report.updatedCount)
        assertEquals(1, report.failedCount)
        assertEquals(StockInstrumentMapper.SYMBOL_REQUIRED_LABEL, report.messageFa)
        assertEquals(StockInstrumentMapper.SYMBOL_REQUIRED_LABEL, report.stockPipelineDiagnostic?.mappedAssetId)
        assertEquals(StockInstrumentMapper.SYMBOL_REQUIRED_LABEL, report.stockPipelineDiagnostic?.failureReason)
        assertEquals(PriceStatus.UNAVAILABLE, report.stockPipelineDiagnostic?.finalUiState)
    }

    /**
     * ۷. تاب‌آوری در برابر وقفه شبکه (Network Timeout)
     */
    @Test
    fun testNetworkTimeout() = runBlocking {
        val client = createMockClient(throwException = SocketTimeoutException("Connection timed out"))
        val stockProvider = StockPriceProvider(client)

        val result = stockProvider.fetchPriceForAsset("فولاد", AssetClass.STOCK)
        assertFalse("Timeout must report failure", result.isSuccess)
        val diag = stockProvider.lastDiagnostics.firstOrNull()
        assertNotNull(diag)
        assertFalse(diag!!.isSuccess)
        assertTrue(diag.errorMessage?.contains("وقفه") == true || diag.errorMessage?.contains("timed out") == true)
    }

    /**
     * ۸. تاب‌آوری و گزارش خطای سرور HTTP 500
     */
    @Test
    fun testHttp500Error() = runBlocking {
        val client = createMockClient(responseCode = 500, responseBody = "Internal Server Error")
        val stockProvider = StockPriceProvider(client)

        val result = stockProvider.fetchPriceForAsset("فولاد", AssetClass.STOCK)
        assertFalse("HTTP 500 must fail gracefully", result.isSuccess)
        val diag = stockProvider.lastDiagnostics.firstOrNull()
        assertNotNull(diag)
        assertEquals(500, diag!!.httpStatusCode)
        assertFalse(diag.isSuccess)
    }

    /**
     * ۹. انعطاف‌پذیری تحلیل‌گر HTML در برابر تغییرات ساختار DOM
     */
    @Test
    fun testHtmlParserResilienceAgainstMinorDomChanges() = runBlocking {
        // ساختار متفاوت بدون جدول، با div و span و کلاس‌های سفارشی
        val modernDomHtml = """
            <div class="instrument-box" id="item-foolad">
                <div class="info-row">
                    <span class="lbl">نماد:</span>
                    <strong class="symbol-name"> فولاد </strong>
                </div>
                <div class="price-box">
                    <span class="price-val" data-trade-price="5250"> ۵,۲۵۰ ریال </span>
                </div>
            </div>
        """.trimIndent()

        val client = createMockClient(responseCode = 200, responseBody = modernDomHtml)
        val stockProvider = StockPriceProvider(client)

        val result = stockProvider.fetchPriceForAsset("فولاد", AssetClass.STOCK)
        assertTrue("Parser should be resilient to DOM variations", result.isSuccess)
        val price = result.getOrNull()
        assertNotNull(price)
        // 5250 Rial -> 525.0 Toman
        assertEquals(525.0, price!!.price, 0.01)
    }

    /**
     * ۱۰. ذخیره‌سازی صحیح در Room و موفقیت چرخه بازخوانی (Read-back verification)
     */
    @Test
    fun testCorrectStorageInRoom() = runBlocking {
        val tsetmcJson = """
            {
                "marketwatch": [
                    { "l18": "فولاد", "l30": "فولاد مبارکه", "pDrCotVal": 5300, "pClosing": 5280 }
                ]
            }
        """.trimIndent()

        val client = createMockClient(responseCode = 200, responseBody = tsetmcJson)
        val stockProvider = StockPriceProvider(client)
        val goldProvider = GoldPriceProvider(client)
        val composite = CompositeMarketDataProvider(goldProvider = goldProvider, stockProvider = stockProvider)
        val repository = WealthRepository(
            database = db,
            marketDataProvider = composite
        )

        val report = repository.syncStockPrice("فولاد")
        assertEquals(1, report.updatedCount)
        assertEquals(0, report.failedCount)

        // بررسی بازخوانی مستقیم از پایگاه داده Room
        val priceInDb = db.currentPriceDao().getPrice("فولاد")
        assertNotNull(priceInDb)
        assertEquals(530.0, priceInDb!!.price, 0.01) // 5300 Rial = 530 Toman
        assertEquals(CurrencyType.TOMAN, priceInDb.currency)
        assertEquals(PriceStatus.FRESH, priceInDb.status)

        val pipeline = report.stockPipelineDiagnostic
        assertNotNull(pipeline)
        assertTrue(pipeline!!.providerResult)
        assertTrue(pipeline.persistenceSuccess)
        assertTrue(pipeline.readBackSuccess)
        assertEquals(PriceStatus.FRESH, pipeline.finalUiState)
    }

    /**
     * ۱۱. حفظ آخرین قیمت معتبر با وضعیت STALE در صورت قطعی اینترنت
     */
    @Test
    fun testStalePricePreservationOnNetworkFailure() = runBlocking {
        // درج قیمت معتبر قبلی در دیتابیس
        val oldPrice = CurrentPriceEntity(
            assetSymbolOrName = "فولاد",
            assetName = "فولاد مبارکه",
            assetClass = AssetClass.STOCK,
            price = 500.0,
            currency = CurrencyType.TOMAN,
            source = "Manual Entry",
            lastUpdated = System.currentTimeMillis() - 100000,
            status = PriceStatus.FRESH
        )
        db.currentPriceDao().insertPrices(listOf(oldPrice))

        // شبیه‌سازی قطعی ارتباط سرور
        val client = createMockClient(throwException = IOException("Network unreachable"))
        val stockProvider = StockPriceProvider(client)
        val goldProvider = GoldPriceProvider(client)
        val composite = CompositeMarketDataProvider(goldProvider = goldProvider, stockProvider = stockProvider)
        val repository = WealthRepository(
            database = db,
            marketDataProvider = composite
        )

        val report = repository.syncStockPrice("فولاد")
        assertEquals(0, report.updatedCount)
        assertEquals(1, report.failedCount)

        // قیمت قبلی در Room نباید صفر یا حذف شود، بلکه باید با وضعیت STALE حفظ گردد
        val preservedPrice = db.currentPriceDao().getPrice("فولاد")
        assertNotNull(preservedPrice)
        assertEquals(500.0, preservedPrice!!.price, 0.01)
        assertEquals(PriceStatus.STALE, preservedPrice.status)
        assertEquals(PriceStatus.STALE, report.stockPipelineDiagnostic?.finalUiState)
    }

    /**
     * ۱۲. تبدیل دقیق بین ریال و تومان
     */
    @Test
    fun testConversionBetweenRialAndToman() {
        val rialPrice = 52_000.0
        val tomanPrice = PersianUtils.rialToToman(rialPrice)
        assertEquals(5_200.0, tomanPrice, 0.01)

        val convertedBack = PersianUtils.tomanToRial(tomanPrice)
        assertEquals(rialPrice, convertedBack, 0.01)

        val converted = PersianUtils.convertCurrency(rialPrice, CurrencyType.RIAL, CurrencyType.TOMAN)
        assertEquals(5_200.0, converted, 0.01)
    }

    /**
     * ۱۳. محاسبه ارزش روز سبد و درصد رشد سهام بر اساس قیمت‌های همگام‌شده
     */
    @Test
    fun testCalculationOfPortfolioValueAndGrowthPercent() = runBlocking {
        // ثبت تراکنش خرید ۱۰۰۰ سهم فولاد با قیمت هر سهم ۴۰۰ تومان
        val buyTx = TransactionEntity(
            id = 1,
            datePersian = "1403/05/10",
            timestamp = System.currentTimeMillis() - 86400000,
            assetClass = AssetClass.STOCK,
            assetName = "فولاد مبارکه",
            assetSymbol = "فولاد",
            action = TransactionAction.BUY,
            quantity = 1000.0,
            unit = "سهم",
            unitPrice = 400.0,
            currency = CurrencyType.TOMAN,
            totalAmount = 400_000.0
        )
        db.transactionDao().insertTransaction(buyTx)

        // نرخ روز هر سهم ۵۰۰ تومان در Room
        val currentPrice = CurrentPriceEntity(
            assetSymbolOrName = "فولاد",
            assetName = "فولاد مبارکه",
            assetClass = AssetClass.STOCK,
            price = 500.0,
            currency = CurrencyType.TOMAN,
            source = "Online Sync",
            lastUpdated = System.currentTimeMillis(),
            status = PriceStatus.FRESH
        )
        db.currentPriceDao().insertPrices(listOf(currentPrice))

        val transactions = db.transactionDao().getAllTransactions()
        val prices = db.currentPriceDao().getAllPrices()
        val settings = AppSettingsEntity()

        val portfolio = CalculationEngine.calculatePortfolio(
            transactions = transactions,
            prices = prices,
            settings = settings
        )

        val holding = portfolio.holdings.find { it.assetSymbol == "فولاد" }
        assertNotNull(holding)
        assertEquals(1000.0, holding!!.quantity, 0.01)
        assertEquals(400.0, holding.averagePurchasePriceToman!!, 0.01)
        assertEquals(500.0, holding.currentPriceToman!!, 0.01)
        // ارزش روز کل = ۱۰۰۰ * ۵۰۰ = ۵۰۰,۰۰۰ تومان
        assertEquals(500_000.0, holding.currentValueToman!!, 0.01)
        // سود کل = ۵۰۰,۰۰۰ - ۴۰۰,۰۰۰ = ۱۰۰,۰۰۰ تومان
        assertEquals(100_000.0, holding.profitLossToman!!, 0.01)
        // درصد رشد قیمت روز نسبت به میانگین خرید = ((500 - 400) / 400) * 100 = 25%
        assertEquals(25.0, holding.priceGrowthPercent!!, 0.01)
    }

    /**
     * ۱۴. رد قاطع عناوین و برچسب‌های عمومی نظیر «سهام»، «بازار سهام»، «بورس»، «قیمت سهام»
     */
    @Test
    fun testRejectionOfGenericLabelsAsStockSymbol() {
        val genericLabels = listOf("سهام", "بازار سهام", "بورس", "قیمت سهام", "سهام ", " بورس")
        for (label in genericLabels) {
            assertNull("Label '$label' must NOT be resolved as a valid stock symbol", StockInstrumentMapper.resolveStockSymbol(label))
            assertFalse("Label '$label' must be rejected by isValidStockSymbol", StockInstrumentMapper.isValidStockSymbol(label))
            assertFalse("Label '$label' must be rejected by isValidSymbol", StockInstrumentMapper.isValidSymbol(label))
        }

        // نمادهای واقعی باید پذیرفته شوند
        assertTrue(StockInstrumentMapper.isValidStockSymbol("فولاد"))
        assertTrue(StockInstrumentMapper.isValidStockSymbol("فملی"))
        assertTrue(StockInstrumentMapper.isValidStockSymbol("شپنا"))
        assertTrue(StockInstrumentMapper.isValidStockSymbol("وبملت"))
    }

    /**
     * ۱۵. عدم استخراج اعداد تصادفی از صفحه عمومی بازار در صورت عدم تطابق نماد درخواستی
     */
    @Test
    fun testRejectionOfUnrelatedNumbersFromGeneralPage() = runBlocking {
        // صفحه عمومی بازار با عنوان "سهام" و عدد ۹,۴۳۳
        val genericMarketHtml = """
            <!DOCTYPE html>
            <html lang="fa">
            <head><title>بازار سهام و بورس - قیمت سهام</title></head>
            <body>
                <div class="market-overview">
                    <span class="title">سهام</span>
                    <span class="index-value">9,433</span>
                </div>
            </body>
            </html>
        """.trimIndent()

        val client = createMockClient(responseCode = 200, responseBody = genericMarketHtml)
        val stockProvider = StockPriceProvider(client)

        // استعلام برای نماد واقعی "فولاد" نباید عدد 9433 صفحه عمومی را استخراج کند
        val result = stockProvider.fetchPriceForAsset("فولاد", AssetClass.STOCK)
        assertFalse("Generic page number must not be extracted for specific stock", result.isSuccess)
    }

    /**
     * ۱۶. اعتبارسنجی شفاف و صادقانه ۷ مرحله خط لوله در صورت نماد نامعتبر یا خطای استخراج
     */
    @Test
    fun testPipelineDiagnosticHonestyOnGenericSymbol() = runBlocking {
        val client = createMockClient(responseCode = 200, responseBody = "<html><body>سهام 9433</body></html>")
        val stockProvider = StockPriceProvider(client)
        val goldProvider = GoldPriceProvider(client)
        val composite = CompositeMarketDataProvider(goldProvider = goldProvider, stockProvider = stockProvider)
        val repository = WealthRepository(database = db, marketDataProvider = composite)

        val report = repository.syncStockPrice("سهام")
        val diag = report.stockPipelineDiagnostic
        assertNotNull(diag)
        // بررسی صادقانه مراحل: هیچ مرحله‌ای نباید به دروغ SUCCESS گزارش شود
        assertFalse("Provider result must be false for generic symbol", diag!!.providerResult)
        assertFalse("Asset mapping must fail for generic symbol", diag.assetMappingSuccess)
        assertFalse("Parser success must fail", diag.parserSuccess)
        assertFalse("Persistence success must fail", diag.persistenceSuccess)
        assertFalse("Read back success must fail", diag.readBackSuccess)
        assertEquals(StockInstrumentMapper.SYMBOL_REQUIRED_LABEL, diag.mappedAssetId)
        assertEquals(PriceStatus.UNAVAILABLE, diag.finalUiState)
    }
}
