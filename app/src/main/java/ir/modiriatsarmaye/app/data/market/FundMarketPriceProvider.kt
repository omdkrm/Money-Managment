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
 * ارائه‌دهنده آنلاین نرخ صندوق‌های سرمایه‌گذاری (سهامی، درآمد ثابت، طلا)
 * منبع اصلی: سامانه اطلاع‌رسانی TGJU (بخش بازار صندوق‌ها)
 * صفحات اصلی:
 * https://gem.tgju.org/markets/fund
 * https://www.tgju.org/fund
 * و صفحات مشخصات نمادها (Profile)
 */
class FundMarketPriceProvider(
    private val client: OkHttpClient = defaultClient()
) : MarketDataProvider {

    companion object {
        const val TGJU_GEM_FUND_URL = "https://gem.tgju.org/markets/fund"
        const val TGJU_FUND_URL = "https://www.tgju.org/fund"
        const val TSETMC_ETF_FALLBACK_URL =
            "https://cdn.tsetmc.com/api/ClosingPrice/GetMarketWatch?market=0&paperTypes[0]=7&paperTypes[1]=8&showAll=true"

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

    override val providerName: String = "سامانه نرخ صندوق‌های سرمایه‌گذاری TGJU"
    override val supportedAssetClasses: Set<AssetClass> = setOf(
        AssetClass.EQUITY_FUND,
        AssetClass.FIXED_INCOME_FUND,
        AssetClass.GOLD_FUND
    )

    var lastDiagnostics: List<StrategyDiagnostic> = emptyList()
        private set

    override suspend fun fetchPrices(): Result<List<MarketPrice>> = withContext(Dispatchers.IO) {
        val diagnosticsList = mutableListOf<StrategyDiagnostic>()
        val allPrices = mutableMapOf<String, MarketPrice>()

        // ۱. تلاش با صفحه اصلی TGJU Gem Fund
        val diagGem = executeFundFetch(
            testId = "TGJU_FUND_GEM",
            testNameFa = "بازار صندوق‌های TGJU (Gem)",
            url = TGJU_GEM_FUND_URL
        )
        diagnosticsList.add(diagGem.first)
        diagGem.second.forEach { allPrices[it.symbolOrName] = it }

        // ۲. تلاش با صفحه ثانویه TGJU Fund
        if (allPrices.isEmpty()) {
            val diagWww = executeFundFetch(
                testId = "TGJU_FUND_WWW",
                testNameFa = "بازار صندوق‌های TGJU (WWW)",
                url = TGJU_FUND_URL
            )
            diagnosticsList.add(diagWww.first)
            diagWww.second.forEach { allPrices[it.symbolOrName] = it }
        }

        // ۳. راهبرد جایگزین TSETMC ETF
        if (allPrices.isEmpty()) {
            val diagFallback = executeTsetmcEtfFallback(
                testId = "TSETMC_ETF_FALLBACK",
                testNameFa = "پایگاه دیده‌بان صندوق‌های ETF جایگزین",
                url = TSETMC_ETF_FALLBACK_URL
            )
            diagnosticsList.add(diagFallback.first)
            diagFallback.second.forEach { allPrices[it.symbolOrName] = it }
        }

        lastDiagnostics = diagnosticsList

        if (allPrices.isNotEmpty()) {
            Result.success(allPrices.values.toList())
        } else {
            val lastErr = diagnosticsList.lastOrNull()?.parserFailureReason ?: "عدم دریافت اطلاعات بازار صندوق‌ها"
            Result.failure(IOException(lastErr))
        }
    }

    override suspend fun fetchPriceForAsset(symbolOrName: String, assetClass: AssetClass): Result<MarketPrice?> = withContext(Dispatchers.IO) {
        val resolvedSymbol = FundInstrumentMapper.resolveFundSymbol(symbolOrName)
        if (resolvedSymbol.isNullOrBlank()) {
            return@withContext Result.failure(IllegalArgumentException("نماد صندوق نامعتبر یا تعریف‌نشده است: $symbolOrName"))
        }

        val allResult = fetchPrices()
        if (allResult.isSuccess) {
            val match = allResult.getOrNull()?.firstOrNull {
                FundInstrumentMapper.normalizeFundSymbol(it.symbolOrName) == resolvedSymbol
            }
            if (match != null) {
                return@withContext Result.success(match.copy(assetClass = assetClass))
            }
        }

        // بررسی صفحه اختصاصی نماد صندوق در TGJU
        val profileUrl = "https://www.tgju.org/profile/fund-$resolvedSymbol"
        val diag = executeSingleFundProfile(resolvedSymbol, profileUrl, assetClass)
        lastDiagnostics = lastDiagnostics + diag.first

        if (diag.second != null) {
            Result.success(diag.second)
        } else {
            Result.failure(IOException(diag.first.parserFailureReason ?: "نرخ صندوق $resolvedSymbol در دسترس نیست"))
        }
    }

    private fun executeFundFetch(
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
                    extractedPrices.addAll(parseFundTableHtml(body))
                    if (extractedPrices.isEmpty()) {
                        failureReason = "هیچ نماد صندوقی در جدول استخراج نشد"
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
            parserStrategy = "TGJU HTML Fund Table Parser",
            parserFailureReason = failureReason,
            isSuccess = extractedPrices.isNotEmpty(),
            durationMs = System.currentTimeMillis() - startTime,
            providerName = "TGJU",
            instrumentType = "FUND"
        )

        return Pair(diag, extractedPrices)
    }

    private fun executeSingleFundProfile(
        symbol: String,
        url: String,
        fallbackClass: AssetClass
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
                val priceRial = parseSingleFundPriceFromProfile(body)
                if (priceRial != null && priceRial > 0.0) {
                    val priceToman = PersianUtils.rialToToman(priceRial)
                    val targetClass = classifyFund(symbol, fallbackClass)
                    marketPrice = MarketPrice(
                        symbolOrName = symbol,
                        name = symbol,
                        assetClass = targetClass,
                        price = priceToman,
                        currency = CurrencyType.TOMAN,
                        unit = "واحد",
                        priceType = "ETF_FUND",
                        source = "TGJU",
                        timestamp = System.currentTimeMillis(),
                        status = PriceStatus.FRESH,
                        originalPrice = priceRial,
                        originalCurrency = CurrencyType.RIAL,
                        originalUnit = "واحد",
                        instrumentId = symbol,
                        latestPrice = priceToman,
                        closingPrice = priceToman
                    )
                } else {
                    failureReason = "قیمت صندوق $symbol در صفحه پروفایل یافت نشد"
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
            testNameFa = "پروفایل اختصاصی صندوق $symbol",
            url = url,
            httpMethod = "GET",
            httpStatusCode = httpCode,
            contentType = contentType,
            parserStrategy = "TGJU Single Fund Profile Parser",
            parserFailureReason = failureReason,
            isSuccess = marketPrice != null,
            extractedPriceRial = marketPrice?.originalPrice,
            durationMs = System.currentTimeMillis() - startTime,
            providerName = "TGJU",
            instrumentType = "FUND",
            symbol = symbol
        )

        return Pair(diag, marketPrice)
    }

    private fun executeTsetmcEtfFallback(
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

                        val symbol = FundInstrumentMapper.normalizeFundSymbol(l18)
                        if (symbol.isNotBlank()) {
                            val priceRial = if (pDrCotValRial > 0.0) pDrCotValRial else pClosingRial
                            if (priceRial > 0.0) {
                                val priceToman = PersianUtils.rialToToman(priceRial)
                                val classified = classifyFund(symbol, AssetClass.EQUITY_FUND)
                                list.add(
                                    MarketPrice(
                                        symbolOrName = symbol,
                                        name = l30.ifBlank { symbol },
                                        assetClass = classified,
                                        price = priceToman,
                                        currency = CurrencyType.TOMAN,
                                        unit = "واحد",
                                        priceType = "ETF_FUND",
                                        source = "TGJU / TSETMC",
                                        timestamp = System.currentTimeMillis(),
                                        status = PriceStatus.FRESH,
                                        originalPrice = priceRial,
                                        originalCurrency = CurrencyType.RIAL,
                                        originalUnit = "واحد",
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
            parserStrategy = "TSETMC JSON ETF MarketWatch",
            parserFailureReason = failureReason,
            isSuccess = list.isNotEmpty(),
            durationMs = System.currentTimeMillis() - startTime,
            providerName = "TSETMC",
            instrumentType = "FUND"
        )
        return Pair(diag, list)
    }

    /**
     * تجزیه جدول HTML بازار صندوق‌ها TGJU
     */
    fun parseFundTableHtml(html: String): List<MarketPrice> {
        val result = mutableListOf<MarketPrice>()
        val rowPattern = Pattern.compile("""<tr[^>]*>(.*?)</tr>""", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
        val matcher = rowPattern.matcher(html)

        while (matcher.find()) {
            val rowContent = matcher.group(1) ?: continue
            val item = parseFundRow(rowContent)
            if (item != null) {
                result.add(item)
            }
        }
        return result
    }

    private fun parseFundRow(rowHtml: String): MarketPrice? {
        val symbolPattern = Pattern.compile(
            """<a[^>]*href=["'][^"']*/profile/(?:fund-)?([^"'/]+)["'][^>]*>([^<]+)</a>""",
            Pattern.CASE_INSENSITIVE
        )
        val symMatcher = symbolPattern.matcher(rowHtml)
        var rawSymbol = ""
        var rawName = ""
        if (symMatcher.find()) {
            rawSymbol = symMatcher.group(2)?.trim() ?: symMatcher.group(1)?.trim() ?: ""
        } else {
            val cellPattern = Pattern.compile("""<td[^>]*class=["'][^"']*title[^"']*["'][^>]*>(?:<[^>]+>)*\s*([^<]+)""", Pattern.CASE_INSENSITIVE)
            val cm = cellPattern.matcher(rowHtml)
            if (cm.find()) {
                rawSymbol = cm.group(1)?.trim() ?: ""
            }
        }

        val symbol = FundInstrumentMapper.normalizeFundSymbol(rawSymbol)
        if (symbol.isBlank() || symbol.length > 15) return null

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
        val classifiedClass = classifyFund(symbol, AssetClass.EQUITY_FUND)

        return MarketPrice(
            symbolOrName = symbol,
            name = if (rawName.isNotBlank()) rawName else symbol,
            assetClass = classifiedClass,
            price = valuationPriceToman,
            currency = CurrencyType.TOMAN,
            unit = "واحد",
            priceType = "ETF_FUND",
            source = "TGJU",
            timestamp = System.currentTimeMillis(),
            status = PriceStatus.FRESH,
            originalPrice = latestPriceRial,
            originalCurrency = CurrencyType.RIAL,
            originalUnit = "واحد",
            instrumentId = symbol,
            latestPrice = valuationPriceToman,
            closingPrice = PersianUtils.rialToToman(closingPriceRial)
        )
    }

    private fun parseSingleFundPriceFromProfile(html: String): Double? {
        val patterns = listOf(
            Pattern.compile("""data-col=["']info\.last_trade\.PDrCotVal["'][^>]*>(?:<[^>]+>)*\s*([0-9۰-۹,٬]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""data-col=["']info\.last_price["'][^>]*>(?:<[^>]+>)*\s*([0-9۰-۹,٬]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""<span[^>]*class=["'][^"']*info-price[^"']*["'][^>]*>(?:<[^>]+>)*\s*([0-9۰-۹,٬]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""<td[^>]*>[^<]*آخرین قیمت[^<]*</td>\s*<td[^>]*>(?:<[^>]+>)*\s*([0-9۰-۹,٬]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""<td[^>]*>[^<]*قیمت پایانی[^<]*</td>\s*<td[^>]*>(?:<[^>]+>)*\s*([0-9۰-۹,٬]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""<td[^>]*>[^<]*NAV[^<]*</td>\s*<td[^>]*>(?:<[^>]+>)*\s*([0-9۰-۹,٬]+)""", Pattern.CASE_INSENSITIVE)
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

    /**
     * دسته‌بندی هوشمند صندوق به طلا، درآمد ثابت یا سهامی
     */
    fun classifyFund(symbol: String, fallback: AssetClass = AssetClass.EQUITY_FUND): AssetClass {
        val s = FundInstrumentMapper.normalizeFundSymbol(symbol).lowercase()
        val goldKeywords = listOf("عیار", "طلا", "کهربا", "لوتوس", "زر", "زرفام", "گوهر", "ناب", "تابش", "نفیس", "سخا", "آلتون", "lotus", "tala", "ayar")
        if (goldKeywords.any { s.contains(it) }) {
            return AssetClass.GOLD_FUND
        }

        val fixedIncomeKeywords = listOf("کمند", "افران", "همای", "کارین", "یاقوت", "اعتماد", "سامین", "صابت", "فیکس", "kamand", "afran")
        if (fixedIncomeKeywords.any { s.contains(it) }) {
            return AssetClass.FIXED_INCOME_FUND
        }

        return fallback
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
