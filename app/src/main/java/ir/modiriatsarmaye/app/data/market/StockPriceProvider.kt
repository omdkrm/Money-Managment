package ir.modiriatsarmaye.app.data.market

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
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.net.ssl.SSLException

/**
 * ارائه‌دهنده اختصاصی و جامع نرخ سهام بازار بورس و فرابورس ایران
 * با پشتیبانی از داده‌های ساخت‌یافته (JSON) سامانه TSETMC،
 * راهبرد پشتیبان HTML جداول بازار TGJU، و صفحات پروفایل نمادها
 * به همراه تفکیک دقیق قیمت آخرین معامله (Last Traded) و قیمت پایانی (Closing)
 * و ثبت دقیق اطلاعات عیب‌یابی (Diagnostics).
 */
class StockPriceProvider(
    private val client: OkHttpClient = defaultClient()
) : MarketDataProvider {

    companion object {
        const val TSETMC_MARKETWATCH_URL = "https://cdn.tsetmc.com/api/ClosingPrice/GetMarketWatch?market=0&showAll=true"
        const val TGJU_STOCK_TABLE_URL = "https://www.tgju.org/stock"
        const val TGJU_GEM_STOCK_URL = "https://gem.tgju.org/markets/stock"

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
        diagTsetmc.second.forEach { allPrices[it.symbolOrName] = it }

        // ۲. در صورت خالی بودن داده‌ها، راهبرد پشتیبان: جدول بازار سهام TGJU (HTML Fallback)
        if (allPrices.isEmpty()) {
            val diagHtml = executeTgjuTableFetch(
                testId = "TGJU_STOCK_HTML_TABLE",
                testNameFa = "جدول بازار سهام TGJU (HTML Fallback)",
                url = TGJU_STOCK_TABLE_URL
            )
            diagnosticsList.add(diagHtml.first)
            diagHtml.second.forEach { allPrices[it.symbolOrName] = it }
        }

        // ۳. در صورت نیاز، منبع مکمل دوم TGJU Gem
        if (allPrices.isEmpty()) {
            val diagGem = executeTgjuTableFetch(
                testId = "TGJU_GEM_STOCK_HTML",
                testNameFa = "صفحه معاملات سهام TGJU Gem",
                url = TGJU_GEM_STOCK_URL
            )
            diagnosticsList.add(diagGem.first)
            diagGem.second.forEach { allPrices[it.symbolOrName] = it }
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
     * از حدس زدن نماد بر اساس نام‌های مبهم فارسی خودداری می‌شود.
     */
    override suspend fun fetchPriceForAsset(symbolOrName: String, assetClass: AssetClass): Result<MarketPrice?> = withContext(Dispatchers.IO) {
        if (assetClass != AssetClass.STOCK) return@withContext Result.success(null)

        val resolvedSymbol = StockInstrumentMapper.resolveStockSymbol(symbolOrName)
        if (resolvedSymbol.isNullOrBlank()) {
            val errDiag = StrategyDiagnostic(
                testId = "STOCK_SYMBOL_VALIDATION",
                testNameFa = "اعتبارسنجی نماد بورس",
                symbol = symbolOrName,
                parserStrategy = "Deterministic Symbol Resolver",
                parserFailureReason = StockInstrumentMapper.SYMBOL_REQUIRED_LABEL,
                errorMessage = StockInstrumentMapper.SYMBOL_REQUIRED_LABEL,
                isSuccess = false,
                providerResult = false,
                symbolMappingSuccess = false,
                parsingSuccess = false,
                instrumentType = "STOCK"
            )
            lastDiagnostics = listOf(errDiag)
            return@withContext Result.failure(IllegalArgumentException(StockInstrumentMapper.SYMBOL_REQUIRED_LABEL))
        }

        // ۱. ابتدا جستجو در منبع کلی / کش ساخت‌یافته
        val allResult = fetchPrices()
        if (allResult.isSuccess) {
            val list = allResult.getOrNull() ?: emptyList()
            val match = list.firstOrNull {
                StockInstrumentMapper.normalizeSymbol(it.symbolOrName) == resolvedSymbol ||
                StockInstrumentMapper.normalizeSymbol(it.instrumentId) == resolvedSymbol
            }
            if (match != null) {
                // ثبت نتیجه موفق برای این نماد در دیاگنوستیک
                val successDiag = StrategyDiagnostic(
                    testId = "STOCK_MATCH_$resolvedSymbol",
                    testNameFa = "یافتن نماد $resolvedSymbol در دیده‌بان بازار",
                    url = TSETMC_MARKETWATCH_URL,
                    httpStatusCode = 200,
                    contentType = "application/json",
                    parserStrategy = "Structured MarketWatch Matcher",
                    isSuccess = true,
                    extractedSymbol = resolvedSymbol,
                    extractedPrice = match.price,
                    extractedPriceRial = match.originalPrice,
                    priceUnit = "ریال / سهم",
                    symbol = resolvedSymbol,
                    latestPriceRial = match.latestPrice?.let { PersianUtils.tomanToRial(it) } ?: match.originalPrice,
                    closingPriceRial = match.closingPrice?.let { PersianUtils.tomanToRial(it) },
                    latestPriceToman = match.latestPrice,
                    closingPriceToman = match.closingPrice,
                    providerResult = true,
                    symbolMappingSuccess = true,
                    parsingSuccess = true,
                    instrumentType = "STOCK"
                )
                lastDiagnostics = listOf(successDiag) + lastDiagnostics
                return@withContext Result.success(match)
            }
        }

        // ۲. در صورت عدم وجود در دیده‌بان کلی، استعلام صفحه اختصاصی پروفایل نماد
        val profileUrl = "https://www.tgju.org/profile/stock-$resolvedSymbol"
        val diag = executeSingleStockProfile(resolvedSymbol, profileUrl)
        lastDiagnostics = listOf(diag.first) + lastDiagnostics

        if (diag.second != null) {
            Result.success(diag.second)
        } else {
            val reason = diag.first.errorMessage ?: diag.first.parserFailureReason ?: "نرخ روز نماد $resolvedSymbol یافت نشد."
            Result.failure(IOException(reason))
        }
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
                    // تفکیک صریح قیمت‌ها:
                    // آخرین معامله: pDrCotValRial (اگر ناموجود بود از pClosingRial استفاده می‌شود)
                    // قیمت پایانی: pClosingRial
                    val hasLastTrade = pDrCotValRial > 0.0
                    val hasClosing = pClosingRial > 0.0
                    val effectivePriceRial = when {
                        hasLastTrade -> pDrCotValRial
                        hasClosing -> pClosingRial
                        pAdjustedRial > 0.0 -> pAdjustedRial
                        else -> 0.0
                    }

                    // هیچ‌گاه قیمت صفر یا منفی ثبت نمی‌شود
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
        } catch (_: Exception) {
            // parsing error
        }
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
            isSuccess = extractedPrices.isNotEmpty(),
            responseBodyPreview = bodyPreview,
            durationMs = System.currentTimeMillis() - startTime,
            providerName = "TGJU",
            instrumentType = "STOCK",
            extractedSymbol = firstItem?.symbolOrName ?: "",
            extractedPrice = firstItem?.price,
            extractedPriceRial = firstItem?.originalPrice,
            priceUnit = "ریال / سهم",
            providerResult = httpCode == 200,
            symbolMappingSuccess = extractedPrices.isNotEmpty(),
            parsingSuccess = extractedPrices.isNotEmpty()
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
                val priceInfo = parseSingleStockPriceFromProfile(body)
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
                        source = "سامانه TGJU",
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
                    failureReason = "قیمت نماد $symbol در محتوای صفحه پروفایل یافت نشد."
                }
            } else {
                failureReason = "کد پاسخ ناموفق: HTTP $httpCode"
            }
        } catch (e: Throwable) {
            failureReason = e.message ?: "خطای اتصال به پروفایل نماد"
        } finally {
            try { response?.close() } catch (_: Throwable) {}
        }

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
            isSuccess = marketPrice != null,
            extractedSymbol = symbol,
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
            providerResult = httpCode == 200,
            symbolMappingSuccess = true,
            parsingSuccess = marketPrice != null
        )

        return Pair(diag, marketPrice)
    }

    /**
     * تجزیه جدول HTML سهام با پشتیبانی از ساختارهای سنتی table و مدرن div/card
     */
    fun parseStockTableHtml(html: String): List<MarketPrice> {
        val result = mutableListOf<MarketPrice>()
        val rowPattern = Pattern.compile("""<tr[^>]*>(.*?)</tr>""", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
        val matcher = rowPattern.matcher(html)

        while (matcher.find()) {
            val rowContent = matcher.group(1) ?: continue
            val item = parseStockRow(rowContent)
            if (item != null) {
                result.add(item)
            }
        }

        // اگر جدولی وجود نداشت یا نمادی استخراج نشد، ساختارهای متداول div مانند instrument-box یا stock-box را بررسی می‌کنیم
        if (result.isEmpty()) {
            val boxPattern = Pattern.compile("""<div[^>]*class=["'][^"']*(?:instrument|stock|item|card)[^"']*["'][^>]*>(.*?)(?=<div[^>]*class=["'][^"']*(?:instrument|stock|item|card)|$)""", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
            val bm = boxPattern.matcher(html)
            while (bm.find()) {
                val boxContent = bm.group(1) ?: continue
                val item = parseStockRow(boxContent)
                if (item != null) {
                    result.add(item)
                }
            }
            if (result.isEmpty()) {
                val singleItem = parseStockRow(html)
                if (singleItem != null) {
                    result.add(singleItem)
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
        if (symbol.isBlank() || symbol.length > 15) return null

        // ۲. استخراج قیمت‌ها
        var lastPriceRial: Double? = null
        var closingPriceRial: Double? = null

        // استخراج کلاس‌های اختصاصی آخرین معامله
        val lastPricePattern = Pattern.compile("""<(?:td|span|div)[^>]*class=["'][^"']*(?:last[-_]?price|trade|price-val|price_val)[^"']*["'][^>]*>(?:<[^>]+>)*\s*([0-9۰-۹٠-٩,٬\s&;]+)""", Pattern.CASE_INSENSITIVE)
        val lpm = lastPricePattern.matcher(rowHtml)
        if (lpm.find()) {
            lastPriceRial = cleanNumber(lpm.group(1) ?: "")
        }

        // استخراج کلاس‌های اختصاصی قیمت پایانی
        val closingPattern = Pattern.compile("""<(?:td|span|div)[^>]*class=["'][^"']*(?:closing|close)[^"']*["'][^>]*>(?:<[^>]+>)*\s*([0-9۰-۹٠-٩,٬\s&;]+)""", Pattern.CASE_INSENSITIVE)
        val cpm = closingPattern.matcher(rowHtml)
        if (cpm.find()) {
            closingPriceRial = cleanNumber(cpm.group(1) ?: "")
        }

        // استخراج خصیصه‌های داده‌ای مانند data-trade-price یا data-price
        val dataTradePattern = Pattern.compile("""data-(?:trade-)?price=["']([0-9۰-۹٠-٩,٬\s&;]+)["']""", Pattern.CASE_INSENSITIVE)
        val dtpm = dataTradePattern.matcher(rowHtml)
        if (lastPriceRial == null && dtpm.find()) {
            lastPriceRial = cleanNumber(dtpm.group(1) ?: "")
        }

        // استخراج تمام اعداد داخل سلول‌های td
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
    fun parseSingleStockPriceFromProfile(html: String): Pair<Double, Double?>? {
        var lastTrade: Double? = null
        var closing: Double? = null

        val lastTradePatterns = listOf(
            Pattern.compile("""data-trade-price=["']([0-9۰-۹٠-٩,٬\s&;]+)["']""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""data-col=["']info\.last_trade\.PDrCotVal["'][^>]*>(?:<[^>]+>)*\s*([0-9۰-۹٠-٩,٬\s&;]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""data-col=["']info\.last_price["'][^>]*>(?:<[^>]+>)*\s*([0-9۰-۹٠-٩,٬\s&;]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""data-price=["']([0-9۰-۹٠-٩,٬\s&;]+)["']""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""<(?:span|div)[^>]*class=["'][^"']*(?:price-val|price_val|info-price|last[-_]?price)[^"']*["'][^>]*>(?:<[^>]+>)*\s*([0-9۰-۹٠-٩,٬\s&;]+)""", Pattern.CASE_INSENSITIVE),
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
        return Pair(effectivePrice, closing)
    }

    /**
     * تمیزکاری پیشرفته اعداد، پشتیبانی از ارقام فارسی و عربی، علائم نگارشی،
     * کاراکترهای پنهان و کدهای اسکی HTML
     */
    fun cleanNumber(raw: String): Double? {
        if (raw.isBlank()) return null
        var text = raw.trim()

        // حذف و تبدیل هویتهای HTML
        text = text
            .replace("&nbsp;", " ")
            .replace("&#160;", " ")
            .replace("&zwnj;", "")
            .replace("&#8204;", "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")

        // حذف کاراکترهای مخفی و کنترلی
        text = text
            .replace("\u200C", "") // ZWNJ
            .replace("\u200B", "") // ZWSP
            .replace("\u200E", "") // LTR mark
            .replace("\u200F", "") // RTL mark
            .replace("\uFEFF", "") // BOM
            .replace("\u00A0", " ") // Non-breaking space

        // تبدیل ارقام فارسی و عربی به انگلیسی
        val sb = StringBuilder()
        for (ch in text) {
            when (ch) {
                in '۰'..'۹' -> sb.append(ch - '۰')
                in '٠'..'٩' -> sb.append(ch - '٠')
                else -> sb.append(ch)
            }
        }
        text = sb.toString()

        // حذف جداکننده‌ها
        text = text
            .replace(",", "")
            .replace("٬", "")
            .replace(" ", "")
            .trim()

        return text.toDoubleOrNull()
    }
}

/**
 * تایپ‌آلیس جهت سازگاری کامل با کدهای قبلی پروژه
 */
typealias StockMarketPriceProvider = StockPriceProvider
