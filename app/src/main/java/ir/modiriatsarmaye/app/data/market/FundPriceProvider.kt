package ir.modiriatsarmaye.app.data.market

import ir.modiriatsarmaye.app.data.model.AssetClass
import ir.modiriatsarmaye.app.data.model.CurrencyType
import ir.modiriatsarmaye.app.data.model.PriceStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ارائه‌دهنده نرخ و ارزش ذاتی (NAV) صندوق‌های سرمایه‌گذاری ETF (طلا، سهامی و درآمد ثابت)
 */
class FundPriceProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) : MarketDataProvider {

    override val providerName: String = "سامانه صندوق‌های سرمایه‌گذاری (ETF)"
    override val supportedAssetClasses: Set<AssetClass> = setOf(
        AssetClass.GOLD_FUND,
        AssetClass.EQUITY_FUND,
        AssetClass.FIXED_INCOME_FUND
    )

    override suspend fun fetchPrices(): Result<List<MarketPrice>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://cdn.tsetmc.com/api/ClosingPrice/GetMarketWatch?market=0&paperTypes[0]=7&paperTypes[1]=8&showAll=true")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) ModiriatSarmayeApp/1.0")
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("خطا در اتصال به سامانه نرخ صندوق‌ها (کد: ${response.code})"))
            }

            val body = response.body?.string() ?: return@withContext Result.failure(Exception("پاسخ سرور صندوق‌ها خالی است."))
            val resultList = parseTsetmcFundPrices(body)
            if (resultList.isEmpty()) {
                return@withContext Result.failure(Exception("اطلاعات قیمت صندوق‌ها یافت نشد."))
            }

            Result.success(resultList)
        } catch (e: Exception) {
            Result.failure(Exception(e.localizedMessage ?: "اتصال اینترنت برقرار نیست یا سامانه نرخ صندوق‌ها در دسترس نمی‌باشد."))
        }
    }

    override suspend fun fetchPriceForAsset(symbolOrName: String, assetClass: AssetClass): Result<MarketPrice?> = withContext(Dispatchers.IO) {
        if (assetClass !in supportedAssetClasses) return@withContext Result.success(null)

        val allRes = fetchPrices()
        if (allRes.isSuccess) {
            val list = allRes.getOrNull() ?: emptyList()
            val matched = list.find { 
                it.symbolOrName.equals(symbolOrName, ignoreCase = true) ||
                it.name.equals(symbolOrName, ignoreCase = true) ||
                symbolOrName.contains(it.name, ignoreCase = true) ||
                it.name.contains(symbolOrName, ignoreCase = true)
            }
            Result.success(matched)
        } else {
            Result.failure(allRes.exceptionOrNull() ?: Exception("خطا در دریافت قیمت صندوق"))
        }
    }

    private fun parseTsetmcFundPrices(jsonString: String): List<MarketPrice> {
        val list = mutableListOf<MarketPrice>()
        try {
            val root = JSONObject(jsonString)
            val watchArray = root.optJSONArray("marketwatch") ?: return list

            // اسامی و نمادهای شناخته‌شده صندوق‌های طلا
            val goldFundSymbols = setOf("عیار", "طلا", "کهربا", "لوتوس", "زر", "زرفام", "گوهر", "ناب", "تابش", "نفیس", "سخا", "آلتون", "AGAS", "LOTUS", "AYAR", "TALA")
            // اسامی صندوق‌های درآمد ثابت
            val fixedIncomeSymbols = setOf("کمند", "افران", "همای", "کارین", "فیروزا", "یاقوت", "اعتماد", "سامین", "صپاد", "امین", "KAMAND", "AFRAN", "HOMAY")

            for (i in 0 until watchArray.length()) {
                val item = watchArray.getJSONObject(i)
                val symbol = item.optString("lVal18AFC", "").trim()
                val name = item.optString("lVal30", "").trim()
                val pClosing = item.optDouble("pClosing", 0.0)
                val pDrCotVal = item.optDouble("pDrCotVal", pClosing)
                val nav = item.optDouble("nav", 0.0)

                val effectivePriceRial = if (pClosing > 0.0) pClosing else pDrCotVal
                if (symbol.isNotBlank() && effectivePriceRial > 0.0) {
                    val assignedClass = when {
                        goldFundSymbols.any { symbol.contains(it, ignoreCase = true) || name.contains(it, ignoreCase = true) } -> AssetClass.GOLD_FUND
                        fixedIncomeSymbols.any { symbol.contains(it, ignoreCase = true) || name.contains(it, ignoreCase = true) } -> AssetClass.FIXED_INCOME_FUND
                        else -> AssetClass.EQUITY_FUND
                    }

                    list.add(
                        MarketPrice(
                            symbolOrName = symbol,
                            name = if (name.isNotBlank()) name else symbol,
                            assetClass = assignedClass,
                            price = effectivePriceRial,
                            currency = CurrencyType.RIAL,
                            unit = "واحد",
                            priceType = if (nav > 0) "FUND_NAV" else "FUND_MARKET_PRICE",
                            source = "بورس و فرابورس ایران (TSETMC)",
                            timestamp = System.currentTimeMillis(),
                            status = PriceStatus.FRESH
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // parsing error handled
        }
        return list
    }
}
