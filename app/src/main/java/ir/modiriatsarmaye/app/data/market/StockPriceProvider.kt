package ir.modiriatsarmaye.app.data.market

import android.util.Log
import ir.modiriatsarmaye.app.data.model.AssetClass
import ir.modiriatsarmaye.app.data.model.CurrencyType
import ir.modiriatsarmaye.app.data.model.PriceStatus
import ir.modiriatsarmaye.app.util.PersianUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.net.ssl.SSLException

/**
 * ارائه‌دهنده اختصاصی و چندمرحله‌ای نرخ سهام بازار بورس و فرابورس ایران
 * با اجرای دقیق زنجیره اولویت‌بندی استعلام:
 * ۱. TSETMC اختصاصی نماد بر اساس InsCode (GetClosingPriceInfo)
 * ۲. داده‌های ساخت‌یافته دیده‌بان بازار بورس TSETMC (GetMarketWatch)
 * ۳. سامانه ره‌آورد ۳۶۵ به عنوان منبع پشتیبان (Rahavard365 Fallback)
 * ۴. پروفایل تاییدشده نماد در TGJU (با بررسی اکید تعلق محتوا به نماد)
 * ۵. راهبرد ورود دستی / حفظ آخرین نرخ معتبر با وضعیت STALE
 */
class StockPriceProvider(
    private val client: OkHttpClient = defaultClient()
) : MarketDataProvider {

    companion object {
        private const val TAG = "StockPriceSync"
        const val TSETMC_MARKETWATCH_URL = "https://cdn.tsetmc.com/api/ClosingPrice/GetMarketWatch?market=0&showAll=true"
        const val TSETMC_SEARCH_URL_PREFIX = "https://cdn.tsetmc.com/api/Instrument/GetInstrumentSearch/"
        const val TSETMC_CLOSING_URL_PREFIX = "https://cdn.tsetmc.com/api/ClosingPrice/GetClosingPriceInfo/"
        const val RAHAVARD365_SYMBOL_URL_PREFIX = "https://rahavard365.com/symbol/"
        const val TGJU_STOCK_TABLE_URL = "https://www.tgju.org/stock"
        const val TGJU_GEM_STOCK_URL = "https://gem.tgju.org/markets/stock"
        const val TGJU_PROFILE_URL_PREFIX = "https://www.tgju.org/profile/stock-"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36 ModiriatSarmaye/1.0"

        private fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    override val providerName: String = "سامانه نرخ سهام بورس (TSETMC / TGJU)"
    override val supportedAssetClasses: Set<AssetClass> = setOf(AssetClass.STOCK)

    var lastDiagnostics: List<StrategyDiagnostic> = emptyList()
        private set

    /**
     * همگام‌سازی عمومی قیمت‌های سهام از منابع آنلاین معتبر
     */
    override suspend fun fetchPrices(): Result<List<MarketPrice>> = withContext(Dispatchers.IO) {
        val diagnosticsList = mutableListOf<StrategyDiagnostic>()
        val allPrices = mutableMapOf<String, MarketPrice>()

        // ۱. منبع اصلی و ساخت‌یافته: TSETMC MarketWatch JSON
        val diagTsetmc = executeTsetmcJsonFetch(
            testId = "TSETMC_STRUCTURED_JSON",
            testNameFa = "دیده‌بان بازار بورس (TSETMC JSON)",
            url = TSETMC_MARKETWATCH_URL
        )
        diagnosticsList.add(diagTsetmc.first)
        diagTsetmc.second.forEach {
            val key = StockInstrumentMapper.normalizeSymbol(it.symbolOrName)
            if (key.isNotBlank() && StockInstrumentMapper.isValidStockSymbol(key) && !allPrices.containsKey(key)) {
                allPrices[key] = it
            }
        }

        // ۲. در صورت خالی بودن داده‌ها، راهبرد پشتیبان: جدول بازار سهام TGJU (HTML Fallback)
        if (allPrices.isEmpty()) {
            val diagHtml = executeTgjuTableFetch(
                testId = "TGJU_STOCK_HTML_TABLE",
                testNameFa = "جدول بازار سهام TGJU (HTML Fallback)",
                url = TGJU_STOCK_TABLE_URL
            )
            diagnosticsList.add(diagHtml.first)
            diagHtml.second.forEach {
                val key = StockInstrumentMapper.normalizeSymbol(it.symbolOrName)
                if (key.isNotBlank() && StockInstrumentMapper.isValidStockSymbol(key) && !allPrices.containsKey(key)) {
                    allPrices[key] = it
                }
            }
        }

        // ۳. در صورت نیاز، منبع مکمل دوم TGJU Gem
        if (allPrices.isEmpty()) {
            val diagGem = executeTgjuTableFetch(
                testId = "TGJU_GEM_STOCK_HTML",
                testNameFa = "صفحه معاملات سهام TGJU Gem",
                url = TGJU_GEM_STOCK_URL
            )
            diagnosticsList.add(diagGem.first)
            diagGem.second.forEach {
                val key = StockInstrumentMapper.normalizeSymbol(it.symbolOrName)
                if (key.isNotBlank() && StockInstrumentMapper.isValidStockSymbol(key) && !allPrices.containsKey(key)) {
                    allPrices[key] = it
                }
            }
        }

        lastDiagnostics = diagnosticsList

        if (allPrices.isNotEmpty()) {
            Result.success(allPrices.values.toList())
        } else {
            val lastErr = diagnosticsList.lastOrNull()?.errorMessage
                ?: diagnosticsList.lastOrNull()?.parserFailureReason
                ?: "امکان دریافت اطلاعات بازار سهام از منابع برخط میسر نشد."
            Result.failure(IOException(lastErr))
        }
    }

    /**
     * استعلام و دریافت نرخ روز برای یک سهم مشخص بر اساس نماد قطعی
     * مطابق زنجیره اولویت‌های تعیین شده:
     * ۱. TSETMC اختصاصی نماد بر اساس InsCode
     * ۲. TSETMC دیده‌بان بازار JSON
     * ۳. ره‌آورد ۳۶۵ (Rahavard365)
     * ۴. پروفایل تاییدشده TGJU
     * ۵. سطر اختصاصی جدول بازار در صورت وجود
     */
    override suspend fun fetchPriceForAsset(symbolOrName: String, assetClass: AssetClass): Result<MarketPrice?> = withContext(Dispatchers.IO) {
        if (assetClass != AssetClass.STOCK) return@withContext Result.success(null)

        // شناسایی قطعی و اعتبارسنجی سازوکار مالی (Instrument Resolution)
        val resolvedInstrument = StockInstrumentMapper.resolveInstrument(symbolOrName)
        Log.d(TAG, "fetchPriceForAsset - Input: '$symbolOrName' -> Resolved: $resolvedInstrument")

        if (resolvedInstrument == null) {
            val errDiag = StrategyDiagnostic(
                testId = "STOCK_SYMBOL_VALIDATION",
                testNameFa = "اعتبارسنجی نماد بورس",
                symbol = symbolOrName,
                parserStrategy = "Deterministic Instrument Resolver",
                parserFailureReason = StockInstrumentMapper.SYMBOL_REQUIRED_LABEL,
                errorMessage = StockInstrumentMapper.SYMBOL_REQUIRED_LABEL,
                isSuccess = false,
                providerResult = false,
                symbolMappingSuccess = false,
                parsingSuccess = false,
                instrumentType = "STOCK"
            )
            lastDiagnostics = listOf(errDiag)
            Log.w(TAG, "Instrument validation failed: '$symbolOrName' -> ${StockInstrumentMapper.SYMBOL_REQUIRED_LABEL}")
            return@withContext Result.failure(IllegalArgumentException(StockInstrumentMapper.SYMBOL_REQUIRED_LABEL))
        }

        val resolvedSymbol = resolvedInstrument.symbol
        var insCode = resolvedInstrument.insCode
        var isin = resolvedInstrument.isin
        val officialName = resolvedInstrument.name

        // در صورت نبود InsCode در کاتالوگ، استعلام برخط از API جستجوی نماد TSETMC
        if (insCode.isNullOrBlank()) {
            val searchResult = executeTsetmcInstrumentSearch(resolvedSymbol)
            if (searchResult != null) {
                insCode = searchResult.insCode
                if (isin.isNullOrBlank()) isin = searchResult.isin
            }
        }

        val allDiags = mutableListOf<StrategyDiagnostic>()

        // ۱. اولویت اول: استعلام اختصاصی نماد TSETMC از طریق InsCode
        if (!insCode.isNullOrBlank()) {
            Log.d(TAG, "Step 1: Querying TSETMC specific instrument for $resolvedSymbol with InsCode: $insCode")
            val (tsetmcInstDiag, tsetmcInstPrice) = executeTsetmcClosingPrice(
                symbol = resolvedSymbol,
                name = officialName,
                insCode = insCode,
                isin = isin
            )
            allDiags.add(tsetmcInstDiag)

            if (tsetmcInstPrice != null && tsetmcInstPrice.price > 0.0) {
                Log.i(TAG, "Step 1 SUCCESS (TSETMC Instrument): $resolvedSymbol -> ${tsetmcInstPrice.price} Toman")
                lastDiagnostics = allDiags
                return@withContext Result.success(tsetmcInstPrice)
            }
        }

        // ۲. اولویت دوم: استعلام داده‌های ساخت‌یافته JSON دیده‌بان بازار TSETMC (GetMarketWatch)
        Log.d(TAG, "Step 2: Checking structured TSETMC MarketWatch JSON for $resolvedSymbol")
        val (mwDiag, mwList) = executeTsetmcJsonFetch(
            testId = "TSETMC_MARKETWATCH_JSON",
            testNameFa = "دیده‌بان بازار بورس TSETMC (JSON)",
            url = TSETMC_MARKETWATCH_URL
        )
        allDiags.add(mwDiag)

        val mwMatch = mwList.firstOrNull {
            StockInstrumentMapper.normalizeSymbol(it.symbolOrName) == resolvedSymbol ||
            StockInstrumentMapper.normalizeSymbol(it.instrumentId) == resolvedSymbol
        }

        if (mwMatch != null && mwMatch.price > 0.0) {
            val successDiag = StrategyDiagnostic(
                testId = "STOCK_MATCH_$resolvedSymbol",
                testNameFa = "یافتن نماد $resolvedSymbol در دیده‌بان بازار",
                url = TSETMC_MARKETWATCH_URL,
                httpStatusCode = 200,
                contentType = "application/json",
                parserStrategy = "TSETMC MarketWatch Matcher",
                isSuccess = true,
                extractedSymbol = resolvedSymbol,
                extractedPrice = mwMatch.price,
                extractedPriceRial = mwMatch.originalPrice,
                priceUnit = "ریال / سهم",
                symbol = resolvedSymbol,
                latestPriceRial = mwMatch.latestPrice?.let { PersianUtils.tomanToRial(it) } ?: mwMatch.originalPrice,
                closingPriceRial = mwMatch.closingPrice?.let { PersianUtils.tomanToRial(it) },
                latestPriceToman = mwMatch.latestPrice,
                closingPriceToman = mwMatch.closingPrice,
                providerResult = true,
                symbolMappingSuccess = true,
                parsingSuccess = true,
                instrumentType = "STOCK",
                insCode = insCode,
                isin = isin,
                providerName = "TSETMC"
            )
            Log.i(TAG, "Step 2 SUCCESS (TSETMC MarketWatch): $resolvedSymbol -> ${mwMatch.price} Toman")
            allDiags.add(0, successDiag)
            lastDiagnostics = allDiags
            return@withContext Result.success(mwMatch)
        }

        // ۳. اولویت سوم: استعلام سامانه ره‌آورد ۳۶۵ (Rahavard365 Fallback)
        Log.d(TAG, "Step 3: Checking Rahavard365 fallback for $resolvedSymbol")
        val (rahavardDiag, rahavardPrice) = executeRahavard365Fetch(resolvedSymbol, officialName)
        allDiags.add(rahavardDiag)

        if (rahavardPrice != null && rahavardPrice.price > 0.0) {
            Log.i(TAG, "Step 3 SUCCESS (Rahavard365): $resolvedSymbol -> ${rahavardPrice.price} Toman")
            lastDiagnostics = allDiags
            return@withContext Result.success(rahavardPrice)
        }

        // ۴. اولویت چهارم: استعلام صفحه اختصاصی پروفایل نماد در TGJU (با تایید تعلق محتوا)
        val profileUrl = "$TGJU_PROFILE_URL_PREFIX$resolvedSymbol"
        Log.d(TAG, "Step 4: Querying TGJU verified profile for $resolvedSymbol at $profileUrl")
        val (profileDiag, profilePrice) = executeSingleStockProfile(resolvedSymbol, profileUrl)
        allDiags.add(profileDiag)

        if (profilePrice != null && profilePrice.price > 0.0) {
            Log.i(TAG, "Step 4 SUCCESS (TGJU Profile): $resolvedSymbol -> ${profilePrice.price} Toman")
            lastDiagnostics = allDiags
            return@withContext Result.success(profilePrice)
        }

        // ۵. بررسی سطر اختصاصی جدول بازار سهام در صورت وجود (HTML Table Fallback)
        Log.d(TAG, "Step 5: Checking TGJU fallback table for exact row matching $resolvedSymbol")
        val (tgjuDiag, tgjuList) = executeTgjuTableFetch(
            testId = "TGJU_STOCK_TABLE_HTML",
            testNameFa = "جدول سهام TGJU (HTML Fallback)",
            url = TGJU_STOCK_TABLE_URL
        )
        allDiags.add(tgjuDiag)

        val tableMatch = tgjuList.firstOrNull {
            StockInstrumentMapper.normalizeSymbol(it.symbolOrName) == resolvedSymbol ||
            StockInstrumentMapper.normalizeSymbol(it.instrumentId) == resolvedSymbol
        }

        if (tableMatch != null && tableMatch.price > 0.0) {
            val tableDiag = StrategyDiagnostic(
                testId = "STOCK_TABLE_MATCH_$resolvedSymbol",
                testNameFa = "یافتن سطر اختصاصی نماد $resolvedSymbol در جدول",
                url = TGJU_STOCK_TABLE_URL,
                httpStatusCode = 200,
                contentType = "text/html",
                parserStrategy = "TGJU HTML Row Matcher",
                isSuccess = true,
                extractedSymbol = resolvedSymbol,
                extractedPrice = tableMatch.price,
                extractedPriceRial = tableMatch.originalPrice,
                priceUnit = "ریال / سهم",
                symbol = resolvedSymbol,
                latestPriceRial = tableMatch.latestPrice?.let { PersianUtils.tomanToRial(it) } ?: tableMatch.originalPrice,
                closingPriceRial = tableMatch.closingPrice?.let { PersianUtils.tomanToRial(it) },
                latestPriceToman = tableMatch.latestPrice,
                closingPriceToman = tableMatch.closingPrice,
                providerResult = true,
                symbolMappingSuccess = true,
                parsingSuccess = true,
                instrumentType = "STOCK",
                insCode = insCode,
                isin = isin,
                providerName = "TGJU"
            )
            Log.i(TAG, "Step 5 SUCCESS (TGJU Table Row): $resolvedSymbol -> ${tableMatch.price} Toman")
            allDiags.add(0, tableDiag)
            lastDiagnostics = allDiags
            return@withContext Result.success(tableMatch)
        }

        // در صورت عدم یافتن در هیچ‌یک از منابع
        val networkError = allDiags.firstOrNull { 
            it.errorMessage?.contains("وقفه", ignoreCase = true) == true || 
            it.errorMessage?.contains("timeout", ignoreCase = true) == true ||
            it.errorMessage?.contains("timed out", ignoreCase = true) == true
        }?.errorMessage

        val notFoundMsg = networkError ?: "نرخ روز نماد $resolvedSymbol در هیچ‌یک از منابع معتبر بازار یافت نشد."
        Log.w(TAG, notFoundMsg)
        val failureDiag = StrategyDiagnostic(
            testId = "STOCK_NOT_FOUND_$resolvedSymbol",
            testNameFa = "جستجوی نماد $resolvedSymbol در بازار",
            url = if (!insCode.isNullOrBlank()) "$TSETMC_CLOSING_URL_PREFIX$insCode" else profileUrl,
            httpStatusCode = allDiags.firstOrNull { it.httpStatusCode != 0 }?.httpStatusCode ?: 0,
            contentType = allDiags.firstOrNull { it.contentType != "-" }?.contentType ?: "-",
            parserStrategy = "Multi-Provider Stock Sync Pipeline",
            parserFailureReason = notFoundMsg,
            errorMessage = notFoundMsg,
            isSuccess = false,
            providerResult = false,
            symbolMappingSuccess = true,
            parsingSuccess = false,
            instrumentType = "STOCK",
            symbol = resolvedSymbol,
            insCode = insCode,
            isin = isin
        )
        allDiags.add(0, failureDiag)
        lastDiagnostics = allDiags
        return@withContext Result.failure(IOException(notFoundMsg))
    }

    /**
     * استعلام اطلاعات و شناسه اختصاصی نماد از API جستجوی TSETMC
     */
    fun executeTsetmcInstrumentSearch(keyword: String): StockInstrument? {
        val encoded = try {
            URLEncoder.encode(keyword.trim(), "UTF-8")
        } catch (_: Exception) {
            keyword.trim()
        }
        val url = "$TSETMC_SEARCH_URL_PREFIX$encoded"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 200) {
                    val body = response.body?.string() ?: ""
                    return parseTsetmcSearchJson(body, keyword)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TSETMC search failed for $keyword: ${e.message}")
        }
        return null
    }

    /**
     * استخراج InsCode و ISIN از پاسخ JSON جستجوی TSETMC
     */
    fun parseTsetmcSearchJson(jsonString: String, requestedKeyword: String): StockInstrument? {
        val norm = StockInstrumentMapper.normalizeSymbol(requestedKeyword)
        try {
            val rootArray = if (jsonString.trim().startsWith("[")) {
                JSONArray(jsonString)
            } else {
                val root = JSONObject(jsonString)
                root.optJSONArray("instrumentSearch")
                    ?: root.optJSONArray("data")
                    ?: root.optJSONArray("instruments")
            } ?: return null

            for (i in 0 until rootArray.length()) {
                val item = rootArray.optJSONObject(i) ?: continue
                val ticker = StockInstrumentMapper.normalizeSymbol(item.optString("lVal18AFC", item.optString("l18", "")))
                val name = item.optString("lVal30", item.optString("l30", "")).trim()
                val insCode = item.optString("insCode", item.optString("inscode", "")).trim()
                val isin = item.optString("cIsin", item.optString("isin", "")).trim()

                if (ticker == norm || StockInstrumentMapper.normalizeSymbol(name).contains(norm)) {
                    return StockInstrument(
                        symbol = ticker.ifBlank { norm },
                        name = name.ifBlank { ticker },
                        insCode = insCode.ifBlank { null },
                        isin = isin.ifBlank { null }
                    )
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * استعلام نرخ اختصاصی نماد با InsCode از وب‌سرویس GetClosingPriceInfo در TSETMC
     */
    fun executeTsetmcClosingPrice(
        symbol: String,
        name: String,
        insCode: String,
        isin: String?
    ): Pair<StrategyDiagnostic, MarketPrice?> {
        val startTime = System.currentTimeMillis()
        val url = "$TSETMC_CLOSING_URL_PREFIX$insCode"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .build()

        var response: Response? = null
        var httpCode = 0
        var contentType = "-"
        var failureReason: String? = null
        var bodyPreview = ""
        var marketPrice: MarketPrice? = null

        try {
            response = client.newCall(request).execute()
            httpCode = response.code
            contentType = response.header("Content-Type") ?: "-"

            if (httpCode == 200) {
                val json = response.body?.string() ?: ""
                bodyPreview = json.take(300).replace("\n", " ").replace("\r", "")
                if (json.isBlank()) {
                    failureReason = "پاسخ وب‌سرویس اختصاصی نماد در TSETMC خالی است."
                } else {
                    marketPrice = parseTsetmcClosingPriceJson(json, symbol, name, insCode, isin)
                    if (marketPrice == null || marketPrice.price <= 0.0) {
                        failureReason = "اطلاعات قیمت معتبر برای نماد $symbol در پاسخ TSETMC یافت نشد."
                    }
                }
            } else {
                failureReason = "خطای وب‌سرویس TSETMC: HTTP $httpCode"
            }
        } catch (e: SocketTimeoutException) {
            failureReason = "وقفه زمانی در ارتباط با سرور (Connection timed out)"
        } catch (e: Throwable) {
            failureReason = e.message ?: "خطای ارتباط با TSETMC"
        } finally {
            try { response?.close() } catch (_: Throwable) {}
        }

        val isOk = marketPrice != null && marketPrice.price > 0.0
        val diag = StrategyDiagnostic(
            testId = "TSETMC_INSTRUMENT_$symbol",
            testNameFa = "استعلام اختصاصی نماد $symbol (TSETMC)",
            url = url,
            httpMethod = "GET",
            httpStatusCode = httpCode,
            contentType = contentType,
            parserStrategy = "TSETMC Instrument ClosingPrice Parser",
            parserFailureReason = failureReason,
            errorMessage = failureReason,
            isSuccess = isOk,
            responseBodyPreview = bodyPreview,
            durationMs = System.currentTimeMillis() - startTime,
            providerName = "TSETMC",
            instrumentType = "STOCK",
            extractedSymbol = if (isOk) symbol else "",
            extractedPrice = marketPrice?.price,
            extractedPriceRial = marketPrice?.originalPrice,
            priceUnit = "ریال / سهم",
            symbol = symbol,
            latestPriceRial = marketPrice?.latestPrice?.let { PersianUtils.tomanToRial(it) } ?: marketPrice?.originalPrice,
            closingPriceRial = marketPrice?.closingPrice?.let { PersianUtils.tomanToRial(it) },
            latestPriceToman = marketPrice?.latestPrice,
            closingPriceToman = marketPrice?.closingPrice,
            providerResult = httpCode == 200 && isOk,
            symbolMappingSuccess = isOk,
            parsingSuccess = isOk,
            insCode = insCode,
            isin = isin
        )

        return Pair(diag, marketPrice)
    }

    /**
     * تجزیه ساختار پاسخ اختصاصی GetClosingPriceInfo در TSETMC
     * به همراه پشتیبانی منعطف از ساختارهای متداول و دیده‌بان
     */
    fun parseTsetmcClosingPriceJson(
        jsonString: String,
        symbol: String,
        name: String,
        insCode: String,
        isin: String?
    ): MarketPrice? {
        try {
            val root = JSONObject(jsonString)
            val infoObj = root.optJSONObject("closingPriceInfo")
                ?: root.optJSONObject("data")
                ?: if (root.has("pClosing") || root.has("pDrCotVal")) root else null

            if (infoObj != null) {
                val pClosingRial = infoObj.optDouble("pClosing", 0.0)
                val pDrCotValRial = infoObj.optDouble("pDrCotVal", 0.0)
                val pAdjustedRial = infoObj.optDouble("pAdjusted", 0.0)

                val hasLastTrade = pDrCotValRial > 0.0
                val hasClosing = pClosingRial > 0.0

                val effectivePriceRial = when {
                    hasLastTrade -> pDrCotValRial
                    hasClosing -> pClosingRial
                    pAdjustedRial > 0.0 -> pAdjustedRial
                    else -> 0.0
                }

                if (effectivePriceRial > 0.0) {
                    val priceToman = PersianUtils.rialToToman(effectivePriceRial)
                    val lastTradeToman = if (hasLastTrade) PersianUtils.rialToToman(pDrCotValRial) else null
                    val closingToman = if (hasClosing) PersianUtils.rialToToman(pClosingRial) else null
                    val adjustedToman = if (pAdjustedRial > 0.0) PersianUtils.rialToToman(pAdjustedRial) else null

                    return MarketPrice(
                        symbolOrName = symbol,
                        name = name.ifBlank { symbol },
                        assetClass = AssetClass.STOCK,
                        price = priceToman,
                        currency = CurrencyType.TOMAN,
                        unit = "سهم",
                        priceType = if (hasLastTrade) "STOCK_LAST_TRADE" else "STOCK_CLOSING",
                        source = "بورس تهران (TSETMC اختصاصی نماد)",
                        timestamp = System.currentTimeMillis(),
                        status = PriceStatus.FRESH,
                        originalPrice = effectivePriceRial,
                        originalCurrency = CurrencyType.RIAL,
                        originalUnit = "سهم",
                        instrumentId = symbol,
                        latestPrice = lastTradeToman,
                        closingPrice = closingToman,
                        adjustedPrice = adjustedToman
                    )
                }
            }

            // در صورتی که پاسخ شامل آرایه marketwatch باشد (مانند پاسخ‌های تلفیقی)
            val mw = root.optJSONArray("marketwatch") ?: root.optJSONArray("data")
            if (mw != null) {
                val norm = StockInstrumentMapper.normalizeSymbol(symbol)
                for (i in 0 until mw.length()) {
                    val item = mw.optJSONObject(i) ?: continue
                    val l18 = StockInstrumentMapper.normalizeSymbol(item.optString("l18", item.optString("lVal18AFC", "")))
                    if (l18 == norm) {
                        val pClosingRial = item.optDouble("pClosing", 0.0)
                        val pDrCotValRial = item.optDouble("pDrCotVal", 0.0)
                        val effectiveRial = if (pDrCotValRial > 0.0) pDrCotValRial else pClosingRial
                        if (effectiveRial > 0.0) {
                            return MarketPrice(
                                symbolOrName = symbol,
                                name = name.ifBlank { symbol },
                                assetClass = AssetClass.STOCK,
                                price = PersianUtils.rialToToman(effectiveRial),
                                currency = CurrencyType.TOMAN,
                                unit = "سهم",
                                priceType = if (pDrCotValRial > 0.0) "STOCK_LAST_TRADE" else "STOCK_CLOSING",
                                source = "بورس تهران (TSETMC اختصاصی نماد)",
                                timestamp = System.currentTimeMillis(),
                                status = PriceStatus.FRESH,
                                originalPrice = effectiveRial,
                                originalCurrency = CurrencyType.RIAL,
                                originalUnit = "سهم",
                                instrumentId = symbol,
                                latestPrice = if (pDrCotValRial > 0.0) PersianUtils.rialToToman(pDrCotValRial) else null,
                                closingPrice = if (pClosingRial > 0.0) PersianUtils.rialToToman(pClosingRial) else null
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * استعلام سامانه ره‌آورد ۳۶۵ (Rahavard365 Fallback Provider)
     * در صورت وجود محافظت امنیتی (HTTP 403 / Cloudflare / CAPTCHA)، بدون دور زدن خطا گزارش شده
     * و فرآیند بدون توقف وارد منبع بعدی می‌شود.
     */
    fun executeRahavard365Fetch(
        symbol: String,
        name: String
    ): Pair<StrategyDiagnostic, MarketPrice?> {
        val startTime = System.currentTimeMillis()
        val url = "$RAHAVARD365_SYMBOL_URL_PREFIX$symbol"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "fa,en;q=0.9")
            .build()

        var response: Response? = null
        var httpCode = 0
        var contentType = "-"
        var failureReason: String? = null
        var bodyPreview = ""
        var marketPrice: MarketPrice? = null

        try {
            response = client.newCall(request).execute()
            httpCode = response.code
            contentType = response.header("Content-Type") ?: "-"

            if (httpCode in setOf(401, 403, 429)) {
                failureReason = "سامانه ره‌آورد ۳۶۵ دسترسی خودکار را مسدود کرده است (HTTP $httpCode / نیاز به تأیید امنیتی یا احراز هویت)."
                Log.w(TAG, failureReason)
            } else if (httpCode == 200) {
                val body = response.body?.string() ?: ""
                bodyPreview = body.take(300).replace("\n", " ").replace("\r", "")
                if (body.contains("cf-browser-verification", ignoreCase = true) ||
                    body.contains("challenge-running", ignoreCase = true) ||
                    body.contains("recaptcha", ignoreCase = true)) {
                    failureReason = "سامانه ره‌آورد ۳۶۵ نیازمند تأیید هویت انسانی (CAPTCHA / Cloudflare) است."
                    Log.w(TAG, failureReason)
                } else if (!verifyHtmlBelongsToSymbol(body, symbol)) {
                    failureReason = "صفحه ره‌آورد ۳۶۵ متعلق به نماد درخواستی ($symbol) نیست."
                } else {
                    val priceInfo = parseSingleStockPriceFromProfile(body, symbol)
                    if (priceInfo != null && priceInfo.first > 0.0) {
                        val latestPriceRial = priceInfo.first
                        val closingPriceRial = priceInfo.second ?: latestPriceRial
                        val valuationToman = PersianUtils.rialToToman(latestPriceRial)

                        marketPrice = MarketPrice(
                            symbolOrName = symbol,
                            name = name.ifBlank { symbol },
                            assetClass = AssetClass.STOCK,
                            price = valuationToman,
                            currency = CurrencyType.TOMAN,
                            unit = "سهم",
                            priceType = "STOCK_LAST_TRADE",
                            source = "ره‌آورد ۳۶۵ (HTML)",
                            timestamp = System.currentTimeMillis(),
                            status = PriceStatus.FRESH,
                            originalPrice = latestPriceRial,
                            originalCurrency = CurrencyType.RIAL,
                            originalUnit = "سهم",
                            instrumentId = symbol,
                            latestPrice = valuationToman,
                            closingPrice = PersianUtils.rialToToman(closingPriceRial)
                        )
                    } else {
                        failureReason = "قیمت معتبر برای نماد $symbol در ره‌آورد ۳۶۵ یافت نشد."
                    }
                }
            } else {
                failureReason = "خطای سرور ره‌آورد ۳۶۵: HTTP $httpCode"
            }
        } catch (e: SocketTimeoutException) {
            failureReason = "وقفه زمانی در ارتباط با سرور (Connection timed out)"
        } catch (e: Throwable) {
            failureReason = e.message ?: "خطای اتصال به ره‌آورد ۳۶۵"
        } finally {
            try { response?.close() } catch (_: Throwable) {}
        }

        val isOk = marketPrice != null && marketPrice.price > 0.0
        val diag = StrategyDiagnostic(
            testId = "RAHAVARD365_$symbol",
            testNameFa = "استعلام نماد $symbol از ره‌آورد ۳۶۵",
            url = url,
            httpMethod = "GET",
            httpStatusCode = httpCode,
            contentType = contentType,
            parserStrategy = "Rahavard365 Parser",
            parserFailureReason = failureReason,
            errorMessage = failureReason,
            isSuccess = isOk,
            responseBodyPreview = bodyPreview,
            durationMs = System.currentTimeMillis() - startTime,
            providerName = "Rahavard365",
            instrumentType = "STOCK",
            extractedSymbol = if (isOk) symbol else "",
            extractedPrice = marketPrice?.price,
            extractedPriceRial = marketPrice?.originalPrice,
            priceUnit = "ریال / سهم",
            symbol = symbol,
            latestPriceRial = marketPrice?.originalPrice,
            closingPriceRial = marketPrice?.closingPrice?.let { PersianUtils.tomanToRial(it) },
            latestPriceToman = marketPrice?.latestPrice,
            closingPriceToman = marketPrice?.closingPrice,
            providerResult = httpCode == 200 && isOk,
            symbolMappingSuccess = isOk,
            parsingSuccess = isOk
        )

        return Pair(diag, marketPrice)
    }

    /**
     * استخراج داده‌های ساخت‌یافته JSON از دیده‌بان بورس TSETMC
     */
    fun executeTsetmcJsonFetch(
        testId: String,
        testNameFa: String,
        url: String
    ): Pair<StrategyDiagnostic, List<MarketPrice>> {
        val startTime = System.currentTimeMillis()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "fa,en;q=0.9")
            .build()

        var response: Response? = null
        var httpCode = 0
        var contentType = "-"
        var failureReason: String? = null
        var bodyPreview = ""
        val list = mutableListOf<MarketPrice>()

        try {
            response = client.newCall(request).execute()
            httpCode = response.code
            contentType = response.header("Content-Type") ?: "-"

            if (httpCode == 200) {
                val json = response.body?.string() ?: ""
                bodyPreview = json.take(300).replace("\n", " ").replace("\r", "")
                if (json.isBlank()) {
                    failureReason = "پاسخ سرور بورس خالی است."
                } else {
                    list.addAll(parseTsetmcMarketWatchJson(json))
                    if (list.isEmpty()) {
                        failureReason = "هیچ داده معتبری از نمادهای سهام در ساختار JSON یافت نشد."
                    }
                }
            } else {
                failureReason = "خطای سرور بورس: HTTP $httpCode"
            }
        } catch (e: UnknownHostException) {
            failureReason = "عدم دسترسی به شبکه و DNS سرور بورس"
        } catch (e: SocketTimeoutException) {
            failureReason = "پایان مهلت زمانی اتصال به سرور بورس (Timeout)"
        } catch (e: SSLException) {
            failureReason = "خطای امنیتی SSL/TLS در اتصال به بورس"
        } catch (e: IOException) {
            failureReason = "خطای ورودی/خروجی شبکه: ${e.localizedMessage ?: "قطع اتصال اینترنت"}"
        } catch (e: Throwable) {
            failureReason = "خطای سیستمی: ${e.message}"
        } finally {
            try { response?.close() } catch (_: Throwable) {}
        }

        val firstItem = list.firstOrNull()
        val diag = StrategyDiagnostic(
            testId = testId,
            testNameFa = testNameFa,
            url = url,
            httpMethod = "GET",
            httpStatusCode = httpCode,
            contentType = contentType,
            parserStrategy = "TSETMC JSON MarketWatch Parser",
            parserFailureReason = failureReason,
            errorMessage = failureReason,
            isSuccess = list.isNotEmpty(),
            responseBodyPreview = bodyPreview,
            durationMs = System.currentTimeMillis() - startTime,
            providerName = "TSETMC",
            instrumentType = "STOCK",
            extractedSymbol = firstItem?.symbolOrName ?: "",
            extractedPrice = firstItem?.price,
            extractedPriceRial = firstItem?.originalPrice,
            priceUnit = "ریال / سهم",
            providerResult = httpCode == 200,
            symbolMappingSuccess = list.isNotEmpty(),
            parsingSuccess = list.isNotEmpty()
        )

        return Pair(diag, list)
    }

    /**
     * تجزیه ساختار JSON بازار بورس (TSETMC MarketWatch)
     * با استخراج دقیق آخرین معامله (pDrCotVal)، قیمت پایانی (pClosing) و قیمت تعدیل شده
     */
    fun parseTsetmcMarketWatchJson(jsonString: String): List<MarketPrice> {
        val list = mutableListOf<MarketPrice>()
        try {
            val root = JSONObject(jsonString)
            val mw = root.optJSONArray("marketwatch")
                ?: root.optJSONArray("marketWatch")
                ?: root.optJSONArray("data")
                ?: return list

            for (i in 0 until mw.length()) {
                val item = mw.optJSONObject(i) ?: continue
                val l18 = item.optString("l18", item.optString("lVal18AFC", "")).trim()
                val l30 = item.optString("l30", item.optString("lVal30", "")).trim()

                val pClosingRial = item.optDouble("pClosing", 0.0)
                val pDrCotValRial = item.optDouble("pDrCotVal", 0.0)
                val pAdjustedRial = item.optDouble("pAdjusted", 0.0)

                val symbol = StockInstrumentMapper.normalizeSymbol(l18)
                if (symbol.isNotBlank()) {
                    val hasLastTrade = pDrCotValRial > 0.0
                    val hasClosing = pClosingRial > 0.0
                    val effectivePriceRial = when {
                        hasLastTrade -> pDrCotValRial
                        hasClosing -> pClosingRial
                        pAdjustedRial > 0.0 -> pAdjustedRial
                        else -> 0.0
                    }

                    if (effectivePriceRial > 0.0) {
                        val priceToman = PersianUtils.rialToToman(effectivePriceRial)
                        val lastTradeToman = if (hasLastTrade) PersianUtils.rialToToman(pDrCotValRial) else null
                        val closingToman = if (hasClosing) PersianUtils.rialToToman(pClosingRial) else null
                        val adjustedToman = if (pAdjustedRial > 0.0) PersianUtils.rialToToman(pAdjustedRial) else null

                        val priceType = when {
                            hasLastTrade -> "STOCK_LAST_TRADE"
                            hasClosing -> "STOCK_CLOSING"
                            else -> "STOCK_ADJUSTED"
                        }

                        list.add(
                            MarketPrice(
                                symbolOrName = symbol,
                                name = l30.ifBlank { symbol },
                                assetClass = AssetClass.STOCK,
                                price = priceToman,
                                currency = CurrencyType.TOMAN,
                                unit = "سهم",
                                priceType = priceType,
                                source = "بورس تهران (TSETMC)",
                                timestamp = System.currentTimeMillis(),
                                status = PriceStatus.FRESH,
                                originalPrice = effectivePriceRial,
                                originalCurrency = CurrencyType.RIAL,
                                originalUnit = "سهم",
                                instrumentId = symbol,
                                latestPrice = lastTradeToman,
                                closingPrice = closingToman,
                                adjustedPrice = adjustedToman
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        return list
    }

    /**
     * استخراج داده‌های جدول سهام با استفاده از راهبرد مقاوم HTML (TGJU Fallback)
     */
    fun executeTgjuTableFetch(
        testId: String,
        testNameFa: String,
        url: String
    ): Pair<StrategyDiagnostic, List<MarketPrice>> {
        val startTime = System.currentTimeMillis()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "fa,en;q=0.9")
            .build()

        var response: Response? = null
        var httpCode = 0
        var contentType = "-"
        var failureReason: String? = null
        var bodyPreview = ""
        val extractedPrices = mutableListOf<MarketPrice>()

        try {
            response = client.newCall(request).execute()
            httpCode = response.code
            contentType = response.header("Content-Type") ?: "-"

            if (httpCode == 200) {
                val body = response.body?.string() ?: ""
                bodyPreview = body.take(300).replace("\n", " ").replace("\r", "")
                if (body.isBlank()) {
                    failureReason = "پاسخ سرور خالی است."
                } else {
                    extractedPrices.addAll(parseStockTableHtml(body))
                    if (extractedPrices.isEmpty()) {
                        failureReason = "هیچ نماد سهامی در جدول HTML استخراج نشد."
                    }
                }
            } else {
                failureReason = "کد پاسخ ناموفق: HTTP $httpCode"
            }
        } catch (e: Throwable) {
            failureReason = e.message ?: "خطای ارتباطی با سرور TGJU"
        } finally {
            try { response?.close() } catch (_: Throwable) {}
        }

        val firstItem = extractedPrices.firstOrNull()
        val isOk = extractedPrices.isNotEmpty()
        val diag = StrategyDiagnostic(
            testId = testId,
            testNameFa = testNameFa,
            url = url,
            httpMethod = "GET",
            httpStatusCode = httpCode,
            contentType = contentType,
            parserStrategy = "TGJU HTML Fallback Table Parser",
            parserFailureReason = failureReason,
            errorMessage = failureReason,
            isSuccess = isOk,
            responseBodyPreview = bodyPreview,
            durationMs = System.currentTimeMillis() - startTime,
            providerName = "TGJU",
            instrumentType = "STOCK",
            extractedSymbol = firstItem?.symbolOrName ?: "",
            extractedPrice = firstItem?.price,
            extractedPriceRial = firstItem?.originalPrice,
            priceUnit = "ریال / سهم",
            providerResult = httpCode == 200 && isOk,
            symbolMappingSuccess = isOk,
            parsingSuccess = isOk
        )

        return Pair(diag, extractedPrices)
    }

    /**
     * استعلام پروفایل اختصاصی یک نماد سهام از وبگاه
     */
    fun executeSingleStockProfile(
        symbol: String,
        url: String
    ): Pair<StrategyDiagnostic, MarketPrice?> {
        val startTime = System.currentTimeMillis()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        var response: Response? = null
        var httpCode = 0
        var contentType = "-"
        var failureReason: String? = null
        var marketPrice: MarketPrice? = null

        try {
            response = client.newCall(request).execute()
            httpCode = response.code
            contentType = response.header("Content-Type") ?: "-"

            if (httpCode == 200) {
                val body = response.body?.string() ?: ""
                if (!verifyHtmlBelongsToSymbol(body, symbol)) {
                    failureReason = "صفحه دریافت شده متعلق به نماد درخواستی ($symbol) نیست."
                    Log.w(TAG, "Rejected profile page for $symbol: HTML does not belong to ticker $symbol")
                } else {
                    val priceInfo = parseSingleStockPriceFromProfile(body, symbol)
                    if (priceInfo != null && priceInfo.first > 0.0) {
                        val latestPriceRial = priceInfo.first
                        val closingPriceRial = priceInfo.second ?: latestPriceRial
                        val valuationToman = PersianUtils.rialToToman(latestPriceRial)

                        marketPrice = MarketPrice(
                            symbolOrName = symbol,
                            name = symbol,
                            assetClass = AssetClass.STOCK,
                            price = valuationToman,
                            currency = CurrencyType.TOMAN,
                            unit = "سهم",
                            priceType = "STOCK_LAST_TRADE",
                            source = "سامانه TGJU (Profile HTML)",
                            timestamp = System.currentTimeMillis(),
                            status = PriceStatus.FRESH,
                            originalPrice = latestPriceRial,
                            originalCurrency = CurrencyType.RIAL,
                            originalUnit = "سهم",
                            instrumentId = symbol,
                            latestPrice = valuationToman,
                            closingPrice = PersianUtils.rialToToman(closingPriceRial)
                        )
                        Log.i(TAG, "Extracted profile price for $symbol: Rial=$latestPriceRial, Toman=$valuationToman")
                    } else {
                        failureReason = "قیمت معتبر برای نماد $symbol در محتوای صفحه پروفایل یافت نشد."
                    }
                }
            } else {
                failureReason = "کد پاسخ ناموفق: HTTP $httpCode"
            }
        } catch (e: SocketTimeoutException) {
            failureReason = "وقفه زمانی در ارتباط با سرور (Connection timed out)"
        } catch (e: Throwable) {
            failureReason = e.message ?: "خطای اتصال به پروفایل نماد"
        } finally {
            try { response?.close() } catch (_: Throwable) {}
        }

        val isOk = marketPrice != null && marketPrice.price > 0.0
        val diag = StrategyDiagnostic(
            testId = "STOCK_PROFILE_$symbol",
            testNameFa = "پروفایل اختصاصی نماد $symbol",
            url = url,
            httpMethod = "GET",
            httpStatusCode = httpCode,
            contentType = contentType,
            parserStrategy = "TGJU HTML Profile Parser",
            parserFailureReason = failureReason,
            errorMessage = failureReason,
            isSuccess = isOk,
            extractedSymbol = if (isOk) symbol else "",
            extractedPrice = marketPrice?.price,
            extractedPriceRial = marketPrice?.originalPrice,
            priceUnit = "ریال / سهم",
            durationMs = System.currentTimeMillis() - startTime,
            providerName = "TGJU",
            instrumentType = "STOCK",
            symbol = symbol,
            latestPriceRial = marketPrice?.originalPrice,
            closingPriceRial = marketPrice?.closingPrice?.let { PersianUtils.tomanToRial(it) },
            latestPriceToman = marketPrice?.latestPrice,
            closingPriceToman = marketPrice?.closingPrice,
            providerResult = httpCode == 200 && isOk,
            symbolMappingSuccess = isOk,
            parsingSuccess = isOk
        )

        return Pair(diag, marketPrice)
    }

    /**
     * بررسی تعلق سند HTML به نماد درخواستی بورسی
     */
    fun verifyHtmlBelongsToSymbol(html: String, symbol: String): Boolean {
        val norm = StockInstrumentMapper.normalizeSymbol(symbol)
        if (norm.isBlank()) return false

        // ۱. بررسی تگ title
        val titleMatcher = Pattern.compile("""<title[^>]*>([^<]+)</title>""", Pattern.CASE_INSENSITIVE).matcher(html)
        if (titleMatcher.find()) {
            val titleText = StockInstrumentMapper.normalizeSymbol(titleMatcher.group(1) ?: "")
            if (titleText.contains(norm)) return true
        }

        // ۲. بررسی تگ‌های عنوان یا نماد
        val headingMatcher = Pattern.compile("""<(?:h1|h2|h3|strong|span|div)[^>]*class=["'][^"']*(?:title|symbol|item-name|box-title)[^"']*["'][^>]*>(?:<[^>]+>)*\s*([^<]+)""", Pattern.CASE_INSENSITIVE).matcher(html)
        while (headingMatcher.find()) {
            val text = StockInstrumentMapper.normalizeSymbol(headingMatcher.group(1) ?: "")
            if (text.contains(norm)) return true
        }

        // ۳. بررسی شناسه‌ها یا کلاس‌های صفحه
        if (html.contains("stock-$norm", ignoreCase = true) || 
            html.contains("item-$norm", ignoreCase = true) ||
            html.contains("profile/stock-$norm", ignoreCase = true)) {
            return true
        }

        // ۴. تطبیق صریح نماد به عنوان واژه مستقل
        val wordPattern = Pattern.compile("""(?<![\p{L}\p{N}])${Pattern.quote(norm)}(?![\p{L}\p{N}])""")
        return wordPattern.matcher(html).find()
    }

    /**
     * تجزیه جدول HTML سهام با پشتیبانی از ساختارهای سنتی table و مدرن div/card
     * با اعتبارسنجی اکید نماد هر سطر و عدم استخراج عناوین جنریک
     */
    fun parseStockTableHtml(html: String): List<MarketPrice> {
        val result = mutableListOf<MarketPrice>()
        val rowPattern = Pattern.compile("""<tr[^>]*>(.*?)</tr>""", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
        val matcher = rowPattern.matcher(html)

        while (matcher.find()) {
            val rowContent = matcher.group(1) ?: continue
            val item = parseStockRow(rowContent)
            if (item != null && StockInstrumentMapper.isValidStockSymbol(item.symbolOrName)) {
                result.add(item)
            }
        }

        // اگر جدولی وجود نداشت، ساختارهای متداول div مانند instrument-box یا stock-box را بررسی می‌کنیم
        if (result.isEmpty()) {
            val boxPattern = Pattern.compile("""<div[^>]*class=["'][^"']*(?:instrument|stock|item|card)[^"']*["'][^>]*>(.*?)(?=<div[^>]*class=["'][^"']*(?:instrument|stock|item|card)|$)""", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
            val bm = boxPattern.matcher(html)
            while (bm.find()) {
                val boxContent = bm.group(1) ?: continue
                val item = parseStockRow(boxContent)
                if (item != null && StockInstrumentMapper.isValidStockSymbol(item.symbolOrName)) {
                    result.add(item)
                }
            }
        }
        return result
    }

    private fun parseStockRow(rowHtml: String): MarketPrice? {
        // ۱. استخراج نماد
        val symbolPattern = Pattern.compile(
            """<a[^>]*href=["'][^"']*/profile/(?:stock-)?([^"'/]+)["'][^>]*>([^<]+)</a>""",
            Pattern.CASE_INSENSITIVE
        )
        val symMatcher = symbolPattern.matcher(rowHtml)
        var rawSymbol = ""
        var rawName = ""
        if (symMatcher.find()) {
            rawSymbol = symMatcher.group(2)?.trim() ?: symMatcher.group(1)?.trim() ?: ""
        } else {
            val cellPattern = Pattern.compile("""<(?:td|span|strong|div)[^>]*class=["'][^"']*(?:title|symbol|symbol-name|item-name)[^"']*["'][^>]*>(?:<[^>]+>)*\s*([^<]+)""", Pattern.CASE_INSENSITIVE)
            val cm = cellPattern.matcher(rowHtml)
            if (cm.find()) {
                rawSymbol = cm.group(1)?.trim() ?: ""
            } else {
                val firstTd = Pattern.compile("""<td[^>]*>([^<0-9۰-۹٠-٩,٬]{2,15})</td>""", Pattern.CASE_INSENSITIVE)
                val ftm = firstTd.matcher(rowHtml)
                if (ftm.find()) {
                    rawSymbol = ftm.group(1)?.trim() ?: ""
                }
            }
        }

        val symbol = StockInstrumentMapper.normalizeSymbol(rawSymbol)
        if (!StockInstrumentMapper.isValidStockSymbol(symbol)) return null

        // ۲. استخراج قیمت‌ها
        var lastPriceRial: Double? = null
        var closingPriceRial: Double? = null

        val lastPricePattern = Pattern.compile("""<(?:td|span|div)[^>]*class=["'][^"']*(?:last[-_]?price|trade|price-val|price_val)[^"']*["'][^>]*>(?:<[^>]+>)*\s*([0-9۰-۹٠-٩,٬\s&;]+)""", Pattern.CASE_INSENSITIVE)
        val lpm = lastPricePattern.matcher(rowHtml)
        if (lpm.find()) {
            lastPriceRial = cleanNumber(lpm.group(1) ?: "")
        }

        val closingPattern = Pattern.compile("""<(?:td|span|div)[^>]*class=["'][^"']*(?:closing|close)[^"']*["'][^>]*>(?:<[^>]+>)*\s*([0-9۰-۹٠-٩,٬\s&;]+)""", Pattern.CASE_INSENSITIVE)
        val cpm = closingPattern.matcher(rowHtml)
        if (cpm.find()) {
            closingPriceRial = cleanNumber(cpm.group(1) ?: "")
        }

        val dataTradePattern = Pattern.compile("""data-(?:trade-)?price=["']([0-9۰-۹٠-٩,٬\s&;]+)["']""", Pattern.CASE_INSENSITIVE)
        val dtpm = dataTradePattern.matcher(rowHtml)
        if (lastPriceRial == null && dtpm.find()) {
            lastPriceRial = cleanNumber(dtpm.group(1) ?: "")
        }

        val prices = mutableListOf<Double>()
        val priceCellPattern = Pattern.compile("""<td[^>]*>(?:<[^>]+>)*\s*([0-9۰-۹٠-٩,٬\s&;]+?)\s*(?:<[^>]+>)*</td>""", Pattern.CASE_INSENSITIVE)
        val pm = priceCellPattern.matcher(rowHtml)
        while (pm.find()) {
            val pStr = pm.group(1) ?: continue
            val cleaned = cleanNumber(pStr)
            if (cleaned != null && cleaned > 0.0) {
                prices.add(cleaned)
            }
        }

        if (lastPriceRial == null) {
            lastPriceRial = prices.lastOrNull() ?: prices.firstOrNull()
        }
        if (closingPriceRial == null && prices.size > 1) {
            closingPriceRial = prices.firstOrNull()
        }

        val finalLatest = lastPriceRial ?: return null
        if (finalLatest <= 0.0) return null

        val finalClosing = closingPriceRial ?: finalLatest
        val valuationPriceToman = PersianUtils.rialToToman(finalLatest)

        return MarketPrice(
            symbolOrName = symbol,
            name = if (rawName.isNotBlank()) rawName else symbol,
            assetClass = AssetClass.STOCK,
            price = valuationPriceToman,
            currency = CurrencyType.TOMAN,
            unit = "سهم",
            priceType = "STOCK_LAST_TRADE",
            source = "سامانه TGJU (HTML)",
            timestamp = System.currentTimeMillis(),
            status = PriceStatus.FRESH,
            originalPrice = finalLatest,
            originalCurrency = CurrencyType.RIAL,
            originalUnit = "سهم",
            instrumentId = symbol,
            latestPrice = valuationPriceToman,
            closingPrice = PersianUtils.rialToToman(finalClosing)
        )
    }

    /**
     * استخراج قیمت از صفحه پروفایل سهم (برگرداندن Pair(آخرین معامله, قیمت پایانی))
     */
    fun parseSingleStockPriceFromProfile(html: String, requestedSymbol: String? = null): Pair<Double, Double?>? {
        if (requestedSymbol != null && !verifyHtmlBelongsToSymbol(html, requestedSymbol)) {
            return null
        }

        var lastTrade: Double? = null
        var closing: Double? = null

        val lastTradePatterns = listOf(
            Pattern.compile("""data-trade-price=["']([0-9۰-۹٠-٩,٬\s&;]+)["']""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""data-col=["']info\.last_trade\.PDrCotVal["'][^>]*>(?:<[^>]+>)*\s*([0-9۰-۹٠-٩,٬\s&;]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""data-col=["']info\.last_price["'][^>]*>(?:<[^>]+>)*\s*([0-9۰-۹٠-٩,٬\s&;]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""data-price=["']([0-9۰-۹٠-٩,٬\s&;]+)["']""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""<(?:span|div|td)[^>]*class=["'][^"']*(?:price-val|price_val|info-price|last[-_]?price|trade)[^"']*["'][^>]*>(?:<[^>]+>)*\s*([0-9۰-۹٠-٩,٬\s&;]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""<td[^>]*>[^<]*آخرین معامله[^<]*</td>\s*<td[^>]*>(?:<[^>]+>)*\s*([0-9۰-۹٠-٩,٬\s&;]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""([0-9۰-۹٠-٩,٬]{3,12})\s*ریال""", Pattern.CASE_INSENSITIVE)
        )

        for (pat in lastTradePatterns) {
            val m = pat.matcher(html)
            if (m.find()) {
                val num = cleanNumber(m.group(1) ?: "")
                if (num != null && num > 0.0) {
                    lastTrade = num
                    break
                }
            }
        }

        val closingPatterns = listOf(
            Pattern.compile("""data-col=["']info\.close["'][^>]*>(?:<[^>]+>)*\s*([0-9۰-۹٠-٩,٬\s&;]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""<(?:span|div|td)[^>]*class=["'][^"']*(?:closing|close)[^"']*["'][^>]*>(?:<[^>]+>)*\s*([0-9۰-۹٠-٩,٬\s&;]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""<td[^>]*>[^<]*قیمت پایانی[^<]*</td>\s*<td[^>]*>(?:<[^>]+>)*\s*([0-9۰-۹٠-٩,٬\s&;]+)""", Pattern.CASE_INSENSITIVE)
        )

        for (pat in closingPatterns) {
            val m = pat.matcher(html)
            if (m.find()) {
                val num = cleanNumber(m.group(1) ?: "")
                if (num != null && num > 0.0) {
                    closing = num
                    break
                }
            }
        }

        val effectivePrice = lastTrade ?: closing ?: return null
        if (effectivePrice <= 0.0) return null
        return Pair(effectivePrice, closing)
    }

    /**
     * تمیزکاری پیشرفته اعداد، پشتیبانی از ارقام فارسی و عربی، علائم نگارشی،
     * کاراکترهای پنهان و کدهای اسکی HTML
     * رد اعداد نامربوط مانند درصدها، تاریخ‌ها، و زمان‌ها
     */
    fun cleanNumber(raw: String): Double? {
        if (raw.isBlank()) return null
        var text = raw.trim()

        if (text.contains("%") || text.contains("٪") || text.contains(":") || text.contains("/")) {
            return null
        }

        text = text
            .replace("&nbsp;", " ")
            .replace("&#160;", " ")
            .replace("&zwnj;", "")
            .replace("&#8204;", "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")

        text = text
            .replace("\u200C", "") // ZWNJ
            .replace("\u200B", "") // ZWSP
            .replace("\u200E", "") // LTR mark
            .replace("\u200F", "") // RTL mark
            .replace("\uFEFF", "") // BOM
            .replace("\u00A0", " ") // Non-breaking space

        val sb = StringBuilder()
        for (ch in text) {
            when (ch) {
                in '۰'..'۹' -> sb.append(ch - '۰')
                in '٠'..'٩' -> sb.append(ch - '٠')
                else -> sb.append(ch)
            }
        }
        text = sb.toString()

        text = text
            .replace(",", "")
            .replace("٬", "")
            .replace(" ", "")
            .trim()

        val parsed = text.toDoubleOrNull()
        if (parsed == null || parsed <= 0.0) return null
        return parsed
    }
}

/**
 * تایپ‌آلیس جهت سازگاری کامل با کدهای قبلی پروژه
 */
typealias StockMarketPriceProvider = StockPriceProvider
