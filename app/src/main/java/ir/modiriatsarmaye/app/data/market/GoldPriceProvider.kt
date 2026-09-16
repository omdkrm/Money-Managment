package ir.modiriatsarmaye.app.data.market

import ir.modiriatsarmaye.app.data.model.AssetClass
import ir.modiriatsarmaye.app.data.model.CurrencyType
import ir.modiriatsarmaye.app.data.model.PriceStatus
import ir.modiriatsarmaye.app.util.PersianUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
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
 * ارائه‌دهنده نرخ رسمی و لحظه‌ای طلای ۱۸ عیار / ۷۵۰
 * منبع داده: سامانه اطلاع‌رسانی قیمت طلا و ارز (TGJU - طلای ۱۸ عیار / ۷۵۰)
 * واحد سنجش: ریال برای هر گرم طلای ۱۸ عیار با عیار ۷۵۰
 */
class GoldPriceProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
        .build()
) : MarketDataProvider {

    companion object {
        private const val TAG = "GoldPriceProvider"
        const val TGJU_AJAX_URL = "https://call.tgju.org/ajax.json"
        const val TGJU_GERAM18_URL = "https://www.tgju.org/profile/geram18"
        const val TGJU_GOLD_TABLE_URL = "https://www.tgju.org/gold-chart"

        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

        private fun logDebug(tag: String, msg: String) {
            try {
                android.util.Log.d(tag, msg)
            } catch (_: Throwable) {
                println("[$tag] DEBUG: $msg")
            }
        }

        private fun logError(tag: String, msg: String, tr: Throwable? = null) {
            try {
                android.util.Log.e(tag, msg, tr)
            } catch (_: Throwable) {
                System.err.println("[$tag] ERROR: $msg ${tr?.message ?: ""}")
            }
        }
    }

    override val providerName: String = "سامانه نرخ رسمی طلا (TGJU)"
    override val supportedAssetClasses: Set<AssetClass> = setOf(AssetClass.GOLD)

    // نگهداری آخرین نتایج عیب‌یابی برای ارزیابی در محیط کاربری و گزارش تست
    var lastDiagnostics: List<StrategyDiagnostic> = emptyList()
        private set

    /**
     * همگام‌سازی نرخ طلا با اجرای مجزای راهبردهای TEST A, TEST B, TEST C و ثبت کامل اطلاعات عیب‌یابی
     */
    override suspend fun fetchPrices(): Result<List<MarketPrice>> = withContext(Dispatchers.IO) {
        val diagnosticsList = mutableListOf<StrategyDiagnostic>()
        var successfulPrice: MarketPrice? = null

        // -------------------------------------------------------------
        // TEST A: وب‌سرویس ساخت‌یافته TGJU (ajax.json)
        // -------------------------------------------------------------
        val diagA = executeStrategyTest(
            testId = "TEST A",
            testNameFa = "وب‌سرویس ساخت‌یافته TGJU",
            url = TGJU_AJAX_URL,
            isJsonExpected = true,
            parserStrategyName = "JSON Object (geram18.p) + Regex Fallback"
        ) { body ->
            parseGold18PriceFromJson(body)
        }
        diagnosticsList.add(diagA)
        if (diagA.isSuccess && diagA.extractedPriceRial != null) {
            successfulPrice = createGold18MarketPrice(diagA.extractedPriceRial)
        }

        // -------------------------------------------------------------
        // TEST B: صفحه اختصاصی طلای ۱۸ عیار (profile/geram18)
        // -------------------------------------------------------------
        val diagB = executeStrategyTest(
            testId = "TEST B",
            testNameFa = "صفحه اختصاصی طلای ۱۸ عیار",
            url = TGJU_GERAM18_URL,
            isJsonExpected = false,
            parserStrategyName = "Semantic HTML Extraction (data-col, info-price, table row)"
        ) { body ->
            parseGold18PriceFromProfileHtml(body)
        }
        diagnosticsList.add(diagB)
        if (successfulPrice == null && diagB.isSuccess && diagB.extractedPriceRial != null) {
            successfulPrice = createGold18MarketPrice(diagB.extractedPriceRial)
        }

        // -------------------------------------------------------------
        // TEST C: جدول تجمیعی نرخ طلا و مسکوکات (gold-chart)
        // -------------------------------------------------------------
        val diagC = executeStrategyTest(
            testId = "TEST C",
            testNameFa = "جدول جامع نرخ طلای TGJU",
            url = TGJU_GOLD_TABLE_URL,
            isJsonExpected = false,
            parserStrategyName = "Market Table Regex (data-market-nameslug='geram18')"
        ) { body ->
            parseGold18PriceFromTableHtml(body)
        }
        diagnosticsList.add(diagC)
        if (successfulPrice == null && diagC.isSuccess && diagC.extractedPriceRial != null) {
            successfulPrice = createGold18MarketPrice(diagC.extractedPriceRial)
        }

        lastDiagnostics = diagnosticsList

        if (successfulPrice != null) {
            logDebug(TAG, "Successfully acquired 18K gold price: ${successfulPrice.price} Rials")
            Result.success(listOf(successfulPrice))
        } else {
            val failureSummary = diagnosticsList.joinToString(" | ") { "${it.testId}: ${it.parserFailureReason ?: it.socketException ?: it.httpStatusCode}" }
            val userFriendlyError = when {
                diagnosticsList.any { it.dnsException != null } -> "اتصال اینترنت یا سامانه نام دامنه (DNS) در دسترس نیست."
                diagnosticsList.any { it.timeoutException != null } -> "مهلت اتصال به سامانه قیمت به پایان رسید (Timeout)."
                diagnosticsList.any { it.tlsException != null } -> "خطای برقراری ارتباط امن (TLS/SSL)."
                diagnosticsList.any { it.httpStatusCode == 403 || it.httpStatusCode == 401 } -> "دسترسی به سامانه قیمت با محدودیت مواجه شد (403 Forbidden)."
                diagnosticsList.any { it.httpStatusCode == 404 } -> "آدرس منبع دریافت قیمت طلا تغییر کرده است (404 Not Found)."
                diagnosticsList.any { it.httpStatusCode in 500..599 } -> "سرور سامانه قیمت طلا موقتاً پاسخگو نیست (خطای سرور)."
                else -> "منبع دریافت قیمت طلا در دسترس نیست."
            }
            logError(TAG, "All gold strategies failed. Diagnostics: $failureSummary")
            Result.failure(Exception(userFriendlyError))
        }
    }

    private fun executeStrategyTest(
        testId: String,
        testNameFa: String,
        url: String,
        isJsonExpected: Boolean,
        parserStrategyName: String,
        parseBlock: (String) -> Double?
    ): StrategyDiagnostic {
        val startTime = System.currentTimeMillis()
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "fa-IR,fa;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Cache-Control", "no-cache")

        if (isJsonExpected) {
            requestBuilder.header("Accept", "application/json, text/javascript, text/plain, */*; q=0.01")
            requestBuilder.header("Referer", "https://www.tgju.org/")
            requestBuilder.header("Origin", "https://www.tgju.org")
            requestBuilder.header("Sec-Fetch-Dest", "empty")
            requestBuilder.header("Sec-Fetch-Mode", "cors")
            requestBuilder.header("Sec-Fetch-Site", "same-site")
        } else {
            requestBuilder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            requestBuilder.header("Sec-Fetch-Dest", "document")
            requestBuilder.header("Sec-Fetch-Mode", "navigate")
            requestBuilder.header("Sec-Fetch-Site", "none")
            requestBuilder.header("Sec-Fetch-User", "?1")
            requestBuilder.header("Upgrade-Insecure-Requests", "1")
        }

        val request = requestBuilder.build()
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
        var parserFailureReason: String? = null
        var extractedPrice: Double? = null
        var isSuccess = false

        try {
            response = client.newCall(request).execute()
            httpCode = response.code
            contentType = response.header("Content-Type") ?: "unknown"
            contentLength = response.body?.contentLength() ?: -1L
            isRedirected = response.priorResponse != null

            if (httpCode == 200) {
                val body = response.body?.string() ?: ""
                bodyPreview = body.take(300).replace("\n", " ").replace("\r", "")

                if (body.isBlank()) {
                    parserFailureReason = "پاسخ سرور خالی است (Empty response body)"
                } else {
                    extractedPrice = parseBlock(body)
                    if (extractedPrice != null && extractedPrice > 100_000.0) {
                        isSuccess = true
                    } else {
                        parserFailureReason = "نرخ طلای ۱۸ عیار (geram18) در پاسخ استخراج نشد."
                    }
                }
            } else {
                parserFailureReason = "کد پاسخ ناموفق: HTTP $httpCode"
            }
        } catch (e: UnknownHostException) {
            dnsException = e.localizedMessage ?: "UnknownHostException"
            parserFailureReason = "خطای سامانه DNS / عدم تفکیک دامنه"
        } catch (e: SocketTimeoutException) {
            timeoutException = e.localizedMessage ?: "SocketTimeoutException"
            parserFailureReason = "پایان مهلت زمانی ارتباط (Timeout)"
        } catch (e: SSLException) {
            tlsException = e.localizedMessage ?: "SSLException"
            parserFailureReason = "خطای پروتکل امنیتی SSL/TLS"
        } catch (e: IOException) {
            socketException = e.localizedMessage ?: "IOException"
            parserFailureReason = "خطای ورودی/خروجی شبکه (Network I/O)"
        } catch (e: Throwable) {
            socketException = e.localizedMessage ?: e.javaClass.simpleName
            parserFailureReason = "خطای غیرمنتظره: ${e.message}"
        } finally {
            try {
                response?.close()
            } catch (_: Throwable) {}
        }

        val duration = System.currentTimeMillis() - startTime
        return StrategyDiagnostic(
            testId = testId,
            testNameFa = testNameFa,
            url = url,
            httpMethod = request.method,
            httpStatusCode = httpCode,
            contentType = contentType,
            contentLength = contentLength,
            isRedirected = isRedirected,
            dnsException = dnsException,
            tlsException = tlsException,
            socketException = socketException,
            timeoutException = timeoutException,
            responseBodyPreview = bodyPreview,
            parserStrategy = parserStrategyName,
            parserFailureReason = parserFailureReason,
            isSuccess = isSuccess,
            extractedPriceRial = extractedPrice,
            durationMs = duration
        )
    }

    override suspend fun fetchPriceForAsset(symbolOrName: String, assetClass: AssetClass): Result<MarketPrice?> = withContext(Dispatchers.IO) {
        if (assetClass != AssetClass.GOLD) {
            return@withContext Result.success(null)
        }

        val allRes = fetchPrices()
        if (allRes.isSuccess) {
            val prices = allRes.getOrNull() ?: emptyList()
            val matched = prices.find { priceItem ->
                priceItem.symbolOrName.equals(symbolOrName, ignoreCase = true) ||
                priceItem.name.equals(symbolOrName, ignoreCase = true) ||
                symbolOrName.contains(priceItem.name, ignoreCase = true) ||
                priceItem.name.contains(symbolOrName, ignoreCase = true) ||
                (symbolOrName.contains("طلا", ignoreCase = true) && priceItem.priceType == "GOLD_18K_750")
            }
            Result.success(matched ?: prices.firstOrNull())
        } else {
            Result.failure(allRes.exceptionOrNull() ?: Exception("منبع دریافت قیمت طلا در دسترس نیست."))
        }
    }

    /**
     * استخراج قیمت طلای ۱۸ عیار از پاسخ JSON وب‌سرویس TGJU
     */
    fun parseGold18PriceFromJson(jsonString: String): Double? {
        val trimmed = jsonString.trim()
        try {
            val root = JSONObject(trimmed)
            val currentObj = if (root.has("current")) root.getJSONObject("current") else root

            // ۱. جستجوی کلید اختصاصی geram18
            if (currentObj.has("geram18")) {
                val item = currentObj.optJSONObject("geram18")
                val pStr = if (item != null) {
                    item.optString("p", item.optString("price", item.optString("last_price", "")))
                } else {
                    currentObj.optString("geram18", "")
                }
                val clean = cleanNumberString(pStr)
                if (clean != null && clean > 100_000.0) return clean
            }

            // ۲. جستجوی کلید gold_18k
            if (currentObj.has("gold_18k")) {
                val item = currentObj.optJSONObject("gold_18k")
                val pStr = if (item != null) {
                    item.optString("p", item.optString("price", item.optString("last_price", "")))
                } else {
                    currentObj.optString("gold_18k", "")
                }
                val clean = cleanNumberString(pStr)
                if (clean != null && clean > 100_000.0) return clean
            }
        } catch (_: Throwable) {}

        // Regex Fallback اختصاصی برای geram18 در متن JSON
        val patterns = listOf(
            Pattern.compile(""""geram18"\s*:\s*\{[^}]*?"p"\s*:\s*"([0-9۰-۹,٬]+)"""", Pattern.CASE_INSENSITIVE),
            Pattern.compile(""""geram18"\s*:\s*\{[^}]*?"price"\s*:\s*"([0-9۰-۹,٬]+)"""", Pattern.CASE_INSENSITIVE),
            Pattern.compile(""""gold_18k"\s*:\s*\{[^}]*?"p"\s*:\s*"([0-9۰-۹,٬]+)"""", Pattern.CASE_INSENSITIVE)
        )
        for (p in patterns) {
            val matcher = p.matcher(trimmed)
            if (matcher.find()) {
                val raw = matcher.group(1) ?: continue
                val clean = cleanNumberString(raw)
                if (clean != null && clean > 100_000.0) return clean
            }
        }
        return null
    }

    /**
     * استخراج قیمت طلای ۱۸ عیار / ۷۵۰ از صفحه اختصاصی (profile/geram18)
     */
    fun parseGold18PriceFromProfileHtml(html: String): Double? {
        val patterns = listOf(
            // ساختار داده‌ای استاندارد TGJU
            Pattern.compile("""data-col=["']info\.last_trade\.PDrCotVal["'][^>]*>(?:<[^>]+>)*\s*([0-9۰-۹,٬]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""data-col=["']info\.last_price["'][^>]*>(?:<[^>]+>)*\s*([0-9۰-۹,٬]+)""", Pattern.CASE_INSENSITIVE),
            // ردیف جدول مشخصات
            Pattern.compile("""<td[^>]*>[^<]*نرخ فعلی[^<]*</td>\s*<td[^>]*>(?:<[^>]+>)*\s*([0-9۰-۹,٬]+)""", Pattern.CASE_INSENSITIVE),
            // ساختار info-price و شناسه geram18
            Pattern.compile("""id=["']l-geram18["'][\s\S]{0,150}?<span[^>]*class=["'][^"']*info-price[^"']*["'][^>]*>\s*([0-9۰-۹,٬]+)""", Pattern.CASE_INSENSITIVE),
            // ساختار سوال/جواب و توضیحات متا
            Pattern.compile("""طلای\s*18\s*عیار[\s\S]{0,100}?class=["']price["'][^>]*>\s*([0-9۰-۹,٬]+)\s*ریال""", Pattern.CASE_INSENSITIVE),
            // ساختار عمومی قیمت در صفحه اختصاصی geram18
            Pattern.compile("""<span[^>]*class=["'][^"']*info-price[^"']*["'][^>]*>(?:<[^>]+>)*\s*([0-9۰-۹,٬]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""<span[^>]*class=["'][^"']*value[^"']*["'][^>]*data-col=["']info\.last_trade\.PDrCotVal["'][^>]*>\s*([0-9۰-۹,٬]+)""", Pattern.CASE_INSENSITIVE)
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(html)
            if (matcher.find()) {
                val raw = matcher.group(1) ?: continue
                val clean = cleanNumberString(raw)
                if (clean != null && clean > 100_000.0) {
                    return clean
                }
            }
        }
        return null
    }

    /**
     * استخراج قیمت طلای ۱۸ عیار از صفحه جدول جامع طلا (gold-chart)
     */
    fun parseGold18PriceFromTableHtml(html: String): Double? {
        val patterns = listOf(
            Pattern.compile("""data-market-nameslug=["']geram18["'][^>]*data-price=["']([0-9۰-۹,٬]+)["']""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""data-market-row=["']geram18["'][^>]*data-price=["']([0-9۰-۹,٬]+)["']""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""data-market-nameslug=["']geram18["'][\s\S]{0,150}?class=["']price["'][^>]*>\s*([0-9۰-۹,٬]+)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""طلای\s*18\s*عیار[\s\S]{0,150}?data-price=["']([0-9۰-۹,٬]+)["']""", Pattern.CASE_INSENSITIVE)
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(html)
            if (matcher.find()) {
                val raw = matcher.group(1) ?: continue
                val clean = cleanNumberString(raw)
                if (clean != null && clean > 100_000.0) {
                    return clean
                }
            }
        }
        return null
    }

    /**
     * تجزیه پاسخ دریافتی (جهت پشتیبانی از کدهای تست قدیمی و رابط‌های عمومی)
     */
    fun parseGoldPricesFromResponse(body: String): List<MarketPrice> {
        val trimmed = body.trim()
        val price = if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            parseGold18PriceFromJson(trimmed)
        } else {
            parseGold18PriceFromProfileHtml(trimmed) ?: parseGold18PriceFromTableHtml(trimmed)
        }

        return if (price != null && price > 100_000.0) {
            listOf(createGold18MarketPrice(price))
        } else {
            emptyList()
        }
    }

    /**
     * پاکسازی کاراکترهای رشته قیمت، تبدیل ارقام فارسی و عربی به لاتین، و حذف جداکننده‌های هزارگان
     */
    fun cleanNumberString(raw: String): Double? {
        if (raw.isBlank()) return null
        var text = raw.replace("&nbsp;", "")
            .replace("&#160;", "")
            .replace("&rlm;", "")
            .replace("&lrm;", "")
            .replace("\u200C", "") // ZWNJ
            .replace("\u200B", "") // ZWSP
            .replace("\u200E", "") // LRM
            .replace("\u200F", "") // RLM
            .trim()

        text = PersianUtils.toEnglishDigits(text)
        text = text.replace(",", "")
            .replace("٬", "")
            .replace(" ", "")
            .trim()

        val value = text.toDoubleOrNull() ?: return null
        if (value <= 0.0) return null
        return value
    }

    /**
     * ایجاد شیء استاندارد نرخ طلای ۱۸ عیار / ۷۵۰
     */
    fun createGold18MarketPrice(priceInRial: Double): MarketPrice {
        return MarketPrice(
            symbolOrName = AssetInstrumentMapper.INSTRUMENT_GERAM18,
            name = AssetInstrumentMapper.CANONICAL_GOLD18_NAME,
            assetClass = AssetClass.GOLD,
            price = priceInRial,
            currency = CurrencyType.RIAL,
            unit = AssetInstrumentMapper.CANONICAL_GOLD18_UNIT,
            priceType = "GOLD_18K_750",
            source = "TGJU",
            timestamp = System.currentTimeMillis(),
            status = PriceStatus.FRESH,
            purity = AssetInstrumentMapper.CANONICAL_GOLD18_PURITY,
            originalPrice = priceInRial,
            originalCurrency = CurrencyType.RIAL,
            originalUnit = AssetInstrumentMapper.CANONICAL_GOLD18_UNIT,
            instrumentId = AssetInstrumentMapper.INSTRUMENT_GERAM18
        )
    }
}
