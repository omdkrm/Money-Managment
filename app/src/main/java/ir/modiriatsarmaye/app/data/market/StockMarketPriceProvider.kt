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
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.net.ssl.SSLException

/**
 * ارائه‌دهنده نرخ‌های بازار سهام بر بستر سامانه داده‌های عمومی TGJU
 * منبع اصلی: سامانه اطلاع‌رسانی TGJU (بخش بازار سهام)
 * صفحات اصلی:
 * https://gem.tgju.org/markets/stock
 * https://www.tgju.org/stock
 * و صفحات مشخصات نمادها (Profile)
 */
class StockMarketPriceProvider(
    private val client: OkHttpClient = defaultClient()
) : MarketDataProvider {

    companion object {
        const val TGJU_GEM_STOCK_URL = "https://gem.tgju.org/markets/stock"
        const val TGJU_STOCK_URL = "https://www.tgju.org/stock"
        const val TSETMC_FALLBACK_URL = "https://cdn.tsetmc.com/api/ClosingPrice/GetMarketWatch?market=0&showAll=true"

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

    override val providerName: String = "سامانه نرخ سهام TGJU"
    override val supportedAssetClasses: Set<AssetClass> = setOf(AssetClass.STOCK)

    var lastDiagnostics: List<StrategyDiagnostic> = emptyList()
        private set

    /**
     * همگام‌سازی کل سهام موجود در بازار از منبع عمومی TGJU
     */
    override suspend fun fetchPrices(): Result<List<MarketPrice>> = withContext(Dispatchers.IO) {
        val diagnosticsList = mutableListOf<StrategyDiagnostic>()
        val allPrices = mutableMapOf<String, MarketPrice>()

        // ۱. تلاش با وب‌پیج اصلی TGJU GEM Stock
        val diagGem = executeStockFetch(
            testId = "TGJU_STOCK_GEM",
            testNameFa = "بازار سهام TGJU (Gem)",
            url = TGJU_GEM_STOCK_URL
        )
        diagnosticsList.add(diagGem.first)
        diagGem.second.forEach { allPrices[it.symbolOrName] = it }

        // ۲. اگر نتیجه خالی بود، تلاش با صفحه ثانویه TGJU Stock
        if (allPrices.isEmpty()) {
            val diagWww = executeStockFetch(
                testId = "TGJU_STOCK_WWW",
                testNameFa = "بازار سهام TGJU (WWW)",
                url = TGJU_STOCK_URL
            )
            diagnosticsList.add(diagWww.first)
            diagWww.second.forEach { allPrices[it.symbolOrName] = it }
        }

        // ۳. راهبرد جایگزین (TSETMC CDN) در صورت عدم دسترس‌پذیری TGJU
        if (allPrices.isEmpty()) {
            val diagFallback = executeTsetmcFallback(
                testId = "TSETMC_FALLBACK",
                testNameFa = "پایگاه دیده‌بان بازار جایگزین",
                url = TSETMC_FALLBACK_URL
            )
            diagnosticsList.add(diagFallback.first)
            diagFallback.second.forEach { allPrices[it.symbolOrName] = it }
        }

        lastDiagnostics = diagnosticsList

        if (allPrices.isNotEmpty()) {
            Result.success(allPrices.values.toList())
        } else {
            val lastErr = diagnosticsList.lastOrNull()?.parserFailureReason ?: "عدم دریافت اطلاعات بازار سهام"
            Result.failure(IOException(lastErr))
        }
    }

    /**
     * دریافت نرخ برای یک سهم مشخص بر اساس نماد قطعی
     */
    override suspend fun fetchPriceForAsset(symbolOrName: String, assetClass: AssetClass): Result<MarketPrice?> = withContext(Dispatchers.IO) {
        val resolvedSymbol = StockInstrumentMapper.resolveStockSymbol(symbolOrName)
        if (resolvedSymbol.isNullOrBlank()) {
            return@withContext Result.failure(IllegalArgumentException("نماد بورسی نامعتبر یا تعریف‌نشده است: $symbolOrName"))
        }

        // ابتدا در صورت وجود کش/لیست کلی جستجو می‌کنیم
        val allResult = fetchPrices()
        if (allResult.isSuccess) {
            val match = allResult.getOrNull()?.firstOrNull {
                StockInstrumentMapper.normalizeSymbol(it.symbolOrName) == resolvedSymbol
            }
            if (match != null) {
                return@withContext Result.success(match)
            }
        }

        // در صورت عدم یافتن در جدول کلی، صفحه پروفایل اختصاصی نماد در TGJU بررسی می‌شود
        val profileUrl = "https://www.tgju.org/profile/stock-$resolvedSymbol"
        val diag = executeSingleStockProfile(resolvedSymbol, profileUrl)
        lastDiagnostics = lastDiagnostics + diag.first

        if (diag.second != null) {
            Result.success(diag.second)
        } else {
            Result.failure(IOException(diag.first.parserFailureReason ?: "نرخ نماد $resolvedSymbol در دسترس نیست"))
        }
    }

    private fun executeStockFetch(
        testId: String,
        testNameFa: String,
        url: String
    ): Pair<StrategyDiagnostic, List<MarketPrice>> {
        val startTime = System.currentTimeMillis()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json,*/*;q=0.8")
            .header("Accept-Language", "fa,en;q=0.9")
            .build()

        var response: Response? = null
        var httpCode = 0
        var contentType = "-"
        var contentLength = -1L
        var isRedirected = false
        var dnsException: String? = null
        var tlsException: String? = null
        var socketException: String? = null
        var timeoutException: String? = null
        var bodyPreview = ""
        var failureReason: String? = null
        val extractedPrices = mutableListOf<MarketPrice>()

        try {
            response = client.newCall(request).execute()
            httpCode = response.code
            contentType = response.header("Content-Type") ?: "-"
            contentLength = response.body?.contentLength() ?: -1L
            isRedirected = response.priorResponse != null

            if (httpCode == 200) {
                val body = response.body?.string() ?: ""
                bodyPreview = body.take(300).replace("\n", " ").replace("\r", "")
                if (body.isBlank()) {
                    failureReason = "پاسخ سرور خالی است"
                } else {
                    extractedPrices.addAll(parseStockTableHtml(body))
                    if (extractedPrices.isEmpty()) {
                        failureReason = "هیچ نماد سهامی در جدول استخراج نشد"
                    }
                }
            } else {
                failureReason = "کد پاسخ ناموفق: HTTP $httpCode"
            }
        } catch (e: UnknownHostException) {
            dnsException = e.localizedMessage
            failureReason = "خطای DNS"
        } catch (e: SocketTimeoutException) {
            timeoutException = e.localizedMessage
            failureReason = "پایان مهلت زمانی (Timeout)"
        } catch (e: SSLException) {
            tlsException = e.localizedMessage
            failureReason = "خطای SSL/TLS"
        } catch (e: IOException) {
            socketException = e.localizedMessage
            failureReason = "خطای ورودی/خروجی شبکه"
        } catch (e: Throwable) {
            failureReason = "خطای غیرمنتظره: ${e.message}"
        } finally {
            try { response?.close() } catch (_: Throwable) {}
        }

        val diag = StrategyDiagnostic(
            testId = testId,
            testNameFa = testNameFa,
            url = url,
            httpMethod = "GET",
            httpStatusCode = httpCode,
            contentType = contentType,
            contentLength = contentLength,
            isRedirected = isRedirected,
            dnsException = dnsException,
            tlsException = tlsException,
            socketException = socketException,
            timeoutException = timeoutException,
            responseBodyPreview = bodyPreview,
            parserStrategy = "TGJU HTML Stock Table Parser",
            parserFailureReason = failureReason,
            isSuccess = extractedPrices.isNotEmpty(),
            durationMs = System.currentTimeMillis() - startTime,
            providerName = "TGJU",
            instrumentType = "STOCK"
        )

        return Pair(diag, extractedPrices)
    }

    private fun executeSingleStockProfile(
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
                val priceRial = parseSingleStockPriceFromProfile(body)
                if (priceRial != null && priceRial > 0.0) {
                    val priceToman = PersianUtils.rialToToman(priceRial)
                    marketPrice = MarketPrice(
                        symbolOrName = symbol,
                        name = symbol,
                        assetClass = AssetClass.STOCK,
                        price = priceToman,
                        currency = CurrencyType.TOMAN,
                        unit = "سهم",
                        priceType = "STOCK_EQUITY",
                        source = "TGJU",
                        timestamp = System.currentTimeMillis(),
                        status = PriceStatus.FRESH,
                        originalPrice = priceRial,
                        originalCurrency = CurrencyType.RIAL,
                        originalUnit = "سهم",
                        instrumentId = symbol,
                        latestPrice = priceToman,
                        closingPrice = priceToman
                    )
                } else {
                    failureReason = "قیمت نماد $symbol در صفحه پروفایل یافت نشد"
                }
            } else {
                failureReason = "کد پاسخ ناموفق: HTTP $httpCode"
            }
        } catch (e: Throwable) {
            failureReason = e.message ?: "خطای ارتباط"
        } finally {
            try { response?.close() } catch (_: Throwable) {}
        }

        val diag = StrategyDiagnostic(
            testId = "TGJU_PROFILE_$symbol",
            testNameFa = "پروفایل اختصاصی نماد $symbol",
            url = url,
            httpMethod = "GET",
            httpStatusCode = httpCode,
            contentType = contentType,
            parserStrategy = "TGJU Single Profile Parser",
            parserFailureReason = failureReason,
            isSuccess = marketPrice != null,
            extractedPriceRial = marketPrice?.originalPrice,
            durationMs = System.currentTimeMillis() - startTime,
            providerName = "TGJU",
            instrumentType = "STOCK",
            symbol = symbol
        )

        return Pair(diag, marketPrice)
    }

    private fun executeTsetmcFallback(
        testId: String,
        testNameFa: String,
        url: String
    ): Pair<StrategyDiagnostic, List<MarketPrice>> {
        val startTime = System.currentTimeMillis()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        var response: Response? = null
        var httpCode = 0
        var failureReason: String? = null
        val list = mutableListOf<MarketPrice>()

        try {
            response = client.newCall(request).execute()
            httpCode = response.code
            if (httpCode == 200) {
                val json = response.body?.string() ?: ""
                val root = JSONObject(json)
                val mw = root.optJSONArray("marketwatch")
                if (mw != null) {
                    for (i in 0 until mw.length()) {
                        val item = mw.optJSONObject(i) ?: continue
                        val l18 = item.optString("l18", "").trim()
                        val l30 = item.optString("l30", "").trim()
                        val pClosingRial = item.optDouble("pClosing", 0.0)
                        val pDrCotValRial = item.optDouble("pDrCotVal", 0.0)

                        val symbol = StockInstrumentMapper.normalizeSymbol(l18)
                        if (symbol.isNotBlank()) {
                            val priceRial = if (pDrCotValRial > 0.0) pDrCotValRial else pClosingRial
                            if (priceRial > 0.0) {
                                val priceToman = PersianUtils.rialToToman(priceRial)
                                list.add(
                                    MarketPrice(
                                        symbolOrName = symbol,
                                        name = l30.ifBlank { symbol },
                                        assetClass = AssetClass.STOCK,
                                        price = priceToman,
                                        currency = CurrencyType.TOMAN,
                                        unit = "سهم",
                                        priceType = "STOCK_EQUITY",
                                        source = "TGJU / TSETMC",
                                        timestamp = System.currentTimeMillis(),
                                        status = PriceStatus.FRESH,
                                        originalPrice = priceRial,
                                        originalCurrency = CurrencyType.RIAL,
                                        originalUnit = "سهم",
                                        instrumentId = symbol,
                                        latestPrice = PersianUtils.rialToToman(pDrCotValRial),
                                        closingPrice = PersianUtils.rialToToman(pClosingRial)
                                    )
                                )
                            }
                        }
                    }
                }
            } else {
                failureReason = "HTTP $httpCode"
            }
        } catch (e: Throwable) {
            failureReason = e.message
        } finally {
            try { response?.close() } catch (_: Throwable) {}
        }

        val diag = StrategyDiagnostic(
            testId = testId,
            testNameFa = testNameFa,
            url = url,
            httpMethod = "GET",
            httpStatusCode = httpCode,
            parserStrategy = "TSETMC JSON MarketWatch",
            parserFailureReason = failureReason,
            isSuccess = list.isNotEmpty(),
            durationMs = System.currentTimeMillis() - startTime,
            providerName = "TSETMC",
            instrumentType = "STOCK"
        )
        return Pair(diag, list)
    }

    /**
     * تجزیه جدول HTML بازار سهام TGJU
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
            // ساختار جایگزین سلول داده
            val cellPattern = Pattern.compile("""<td[^>]*class=["'][^"']*title[^"']*["'][^>]*>(?:<[^>]+>)*\s*([^<]+)""", Pattern.CASE_INSENSITIVE)
            val cm = cellPattern.matcher(rowHtml)
            if (cm.find()) {
                rawSymbol = cm.group(1)?.trim() ?: ""
            }
        }

        val symbol = StockInstrumentMapper.normalizeSymbol(rawSymbol)
        if (symbol.isBlank() || symbol.length > 15) return null

        // ۲. استخراج قیمت‌ها
        val prices = mutableListOf<Double>()
        val priceCellPattern = Pattern.compile("""<td[^>]*>(?:<[^>]+>)*\s*([0-9۰-۹,٬]+)\s*(?:<[^>]+>)*</td>""", Pattern.CASE_INSENSITIVE)
        val pm = priceCellPattern.matcher(rowHtml)
        while (pm.find()) {
            val pStr = pm.group(1) ?: continue
            val cleaned = cleanNumber(pStr)
            if (cleaned != null && cleaned > 0.0) {
                prices.add(cleaned)
            }
        }

        if (prices.isEmpty()) {
            // ساختار data-price
            val dpPattern = Pattern.compile("""data-price=["']([0-9۰-۹,٬]+)["']""", Pattern.CASE_INSENSITIVE)
            val dpm = dpPattern.matcher(rowHtml)
            while (dpm.find()) {
                val pStr = dpm.group(1) ?: continue
                val cleaned = cleanNumber(pStr)
                if (cleaned != null && cleaned > 0.0) prices.add(cleaned)
            }
        }

        if (prices.isEmpty()) return null

        val latestPriceRial = prices.firstOrNull() ?: return null
        val closingPriceRial = if (prices.size > 1) prices[1] else latestPriceRial
        val valuationPriceToman = PersianUtils.rialToToman(latestPriceRial)

        return MarketPrice(
            symbolOrName = symbol,
            name = if (rawName.isNotBlank()) rawName else symbol,
            assetClass = AssetClass.STOCK,
            price = valuationPriceToman,
            currency = CurrencyType.TOMAN,
            unit = "سهم",
            priceType = "STOCK_EQUITY",
            source = "TGJU",
            timestamp = System.currentTimeMillis(),
            status = PriceStatus.FRESH,
            originalPrice = latestPriceRial,
            originalCurrency = CurrencyType.RIAL,
            originalUnit = "سهم",
            instrumentId = symbol,
            latestPrice = valuationPriceToman,
            closingPrice = PersianUtils.rialToToman(closingPriceRial)
        )
    }

    private fun parseSingleStockPriceFromProfile(html: String): Double? {
        val patterns = listOf(
            Pattern.compile("""data-col=["']info\.last_trade\.PDrCotVal["'][^>]*>(?:<[^>]+>)*\s*([0-9۰-۹,٬]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""data-col=["']info\.last_price["'][^>]*>(?:<[^>]+>)*\s*([0-9۰-۹,٬]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""<span[^>]*class=["'][^"']*info-price[^"']*["'][^>]*>(?:<[^>]+>)*\s*([0-9۰-۹,٬]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""<td[^>]*>[^<]*آخرین معامله[^<]*</td>\s*<td[^>]*>(?:<[^>]+>)*\s*([0-9۰-۹,٬]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""<td[^>]*>[^<]*قیمت پایانی[^<]*</td>\s*<td[^>]*>(?:<[^>]+>)*\s*([0-9۰-۹,٬]+)""", Pattern.CASE_INSENSITIVE)
        )
        for (pat in patterns) {
            val m = pat.matcher(html)
            if (m.find()) {
                val num = cleanNumber(m.group(1) ?: "")
                if (num != null && num > 0.0) return num
            }
        }
        return null
    }

    private fun cleanNumber(raw: String): Double? {
        if (raw.isBlank()) return null
        val clean = PersianUtils.toEnglishDigits(raw)
            .replace(",", "")
            .replace("٬", "")
            .replace(" ", "")
            .trim()
        return clean.toDoubleOrNull()
    }
}
