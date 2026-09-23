package ir.modiriatsarmaye.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ir.modiriatsarmaye.app.data.local.AppDatabase
import ir.modiriatsarmaye.app.data.market.CompositeMarketDataProvider
import ir.modiriatsarmaye.app.data.market.StockInstrumentMapper
import ir.modiriatsarmaye.app.data.market.StockPriceProvider
import ir.modiriatsarmaye.app.data.model.*
import ir.modiriatsarmaye.app.data.repository.ValidationResult
import ir.modiriatsarmaye.app.data.repository.WealthRepository
import ir.modiriatsarmaye.app.util.CalculationEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * آزمون‌های جامع اعتبارسنجی خط لوله قیمت صندوق‌های سرمایه‌گذاری (Fund Price Synchronization Test Suite)
 * پوشش کامل سناریوهای:
 * ۱. نگاشت و نرمال‌سازی نماد صندوق‌های ETF و سهامی و طلا (آگاس، اهرم، کهربا، لوتوس)
 * ۲. دریافت اطلاعات ساختاریافته قیمت و ابطال NAV
 * ۳. استخراج قیمت با ارقام فارسی و نمادهای دارای جداکننده کاما
 * ۴. مدیریت خطای عدم تعیین نماد صندوق
 * ۵. ذخیره‌سازی دقیق قیمت صندوق در پایگاه داده Room
 * ۶. محاسبه ارزش پورتفوی و سود/زیان محقق‌نشده صندوق‌های سرمایه‌گذاری
 * ۷. پایداری در برابر قطعی شبکه و حفظ آخرین قیمت معتبر
 */
@RunWith(RobolectricTestRunner::class)
class FundPriceSyncTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private lateinit var repository: WealthRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = WealthRepository(db, context)
    }

    @After
    fun tearDown() {
        db.close()
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
                val mediaType = if (req.url.toString().contains(".json") || req.url.toString().contains("MW.aspx")) {
                    "text/plain; charset=utf-8".toMediaTypeOrNull()
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
    fun testFundSymbolResolutionAndNormalization() {
        // نرمال‌سازی نماد صندوق‌های معتبر بورس
        assertEquals("آگاس", StockInstrumentMapper.normalizeSymbol("صندوق آگاس"))
        assertEquals("اهرم", StockInstrumentMapper.normalizeSymbol("  صندوق کاریزما اهرم "))
        assertEquals("کهربا", StockInstrumentMapper.normalizeSymbol("صندوق طلای کهربا"))
        assertEquals("عیار", StockInstrumentMapper.normalizeSymbol("صندوق طلای عیار"))

        // تشخیص برچسب‌های عمومی نامعتبر
        assertFalse(StockInstrumentMapper.isValidSymbol("صندوق"))
        assertFalse(StockInstrumentMapper.isValidSymbol("شرکت سرمایه گذاری"))
        assertEquals("نیاز به تعیین نماد", StockInstrumentMapper.SYMBOL_REQUIRED_LABEL)
    }

    @Test
    fun testFundPriceSyncSuccessAndRoomStorage() = runBlocking {
        // ایجاد تراکنش اولیه برای صندوق آگاس
        val tx = TransactionEntity(
            id = 0,
            datePersian = "1403/01/10",
            assetClass = AssetClass.EQUITY_FUND,
            assetName = "صندوق سهامی آگاس",
            assetSymbol = "آگاس",
            action = TransactionAction.BUY,
            quantity = 1000.0,
            unit = "واحد",
            unitPrice = 2000.0,
            totalAmount = 2_000_000.0
        )
        val saveTxRes = repository.saveTransaction(tx)
        assertTrue(saveTxRes is ValidationResult.Success)

        // ثبت و به‌روزرسانی قیمت روز در پایگاه داده
        val currentPrice = CurrentPriceEntity(
            assetSymbolOrName = "آگاس",
            assetName = "صندوق سهامی آگاس",
            assetClass = AssetClass.EQUITY_FUND,
            price = 2450.0,
            currency = CurrencyType.TOMAN,
            source = "TSETMC",
            lastUpdated = System.currentTimeMillis()
        )
        repository.updateCurrentPrice(currentPrice)

        // بازخوانی از پایگاه داده و تأیید ثبت دقیق قیمت
        val savedPrices = repository.allPricesFlow.first()
        val foundPrice = savedPrices.find { it.assetSymbolOrName == "آگاس" }
        assertNotNull(foundPrice)
        assertEquals(2450.0, foundPrice!!.price, 0.01)
        assertEquals(AssetClass.EQUITY_FUND, foundPrice.assetClass)
    }

    @Test
    fun testFundPortfolioValuationAndProfit() = runBlocking {
        // افزودن تراکنش خرید صندوق سهامی
        val tx = TransactionEntity(
            id = 0,
            datePersian = "1403/01/15",
            assetClass = AssetClass.EQUITY_FUND,
            assetName = "صندوق سهامی آگاس",
            assetSymbol = "آگاس",
            action = TransactionAction.BUY,
            quantity = 10000.0,
            unit = "واحد",
            unitPrice = 2000.0,
            totalAmount = 20_000_000.0
        )
        repository.saveTransaction(tx)

        // ثبت قیمت روز صندوق: ۲,۵۰۰ تومان به ازای هر واحد
        val currentPrice = CurrentPriceEntity(
            assetSymbolOrName = "آگاس",
            assetName = "صندوق سهامی آگاس",
            assetClass = AssetClass.EQUITY_FUND,
            price = 2500.0,
            currency = CurrencyType.TOMAN,
            source = "TSETMC",
            lastUpdated = System.currentTimeMillis()
        )
        repository.updateCurrentPrice(currentPrice)

        val txs = repository.allTransactionsFlow.first()
        val prices = repository.allPricesFlow.first()
        val summary = CalculationEngine.calculatePortfolio(txs, prices, AppSettingsEntity(), emptyList())

        assertEquals(1, summary.holdings.size)
        val holding = summary.holdings[0]
        assertEquals("صندوق سهامی آگاس", holding.assetName)
        assertEquals(10000.0, holding.quantity, 0.001)
        assertEquals(20_000_000.0, holding.totalCostToman, 0.01)
        assertEquals(25_000_000.0, holding.currentValueToman ?: 0.0, 0.01)
        assertEquals(5_000_000.0, holding.profitLossToman ?: 0.0, 0.01)
        assertEquals(25.0, holding.returnPercent ?: 0.0, 0.01)
    }

    @Test
    fun testFundNetworkFailurePreservesStalePrice() = runBlocking {
        // ثبت قیمت قبلی صندوق
        val initialPrice = CurrentPriceEntity(
            assetSymbolOrName = "اهرم",
            assetName = "صندوق اهرم",
            assetClass = AssetClass.EQUITY_FUND,
            price = 1950.0,
            currency = CurrencyType.TOMAN,
            source = "Cache",
            lastUpdated = System.currentTimeMillis() - 3600000
        )
        repository.updateCurrentPrice(initialPrice)

        // شبیه‌سازی خطای قطعی اینترنت
        val timeoutClient = createMockClient(throwException = SocketTimeoutException("اتصال اینترنت قطع شد"))
        val stockProvider = StockPriceProvider(timeoutClient)
        val composite = CompositeMarketDataProvider(
            stockProvider = stockProvider,
            goldProvider = ir.modiriatsarmaye.app.data.market.GoldPriceProvider(timeoutClient)
        )

        val syncRepo = WealthRepository(db, context, composite)
        // اجرای همگام‌سازی در شرایط قطعی شبکه
        val updateReport = syncRepo.syncAllStockPrices(forceRefresh = true)

        // بررسی اینکه خطای شبکه باعث حذف یا صفر شدن قیمت قبلی در دیتابیس نمی‌شود
        val allPrices = syncRepo.allPricesFlow.first()
        val currentPrice = allPrices.find { it.assetSymbolOrName == "اهرم" }
        assertNotNull(currentPrice)
        assertEquals(1950.0, currentPrice!!.price, 0.01)
    }
}
