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
 * ارائه‌دهنده نرخ سهام بازار بورس اوراق بهادار تهران (TSETMC)
 */
class StockPriceProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) : MarketDataProvider {

    override val providerName: String = "سامانه بورس تهران (TSETMC)"
    override val supportedAssetClasses: Set<AssetClass> = setOf(AssetClass.STOCK)

    override suspend fun fetchPrices(): Result<List<MarketPrice>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://cdn.tsetmc.com/api/ClosingPrice/GetMarketWatch?market=0&showAll=true")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) ModiriatSarmayeApp/1.0")
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("خطا در اتصال به سامانه بورس تهران (کد: ${response.code})"))
            }

            val body = response.body?.string() ?: return@withContext Result.failure(Exception("پاسخ دریافتی از سرور بورس خالی است."))
            val resultList = parseTsetmcStockPrices(body)
            if (resultList.isEmpty()) {
                return@withContext Result.failure(Exception("اطلاعات نمادهای سهام قابل خواندن نبود."))
            }

            Result.success(resultList)
        } catch (e: Exception) {
            Result.failure(Exception(e.localizedMessage ?: "اتصال اینترنت برقرار نیست یا سامانه بورس در دسترس نمی‌باشد."))
        }
    }

    override suspend fun fetchPriceForAsset(symbolOrName: String, assetClass: AssetClass): Result<MarketPrice?> = withContext(Dispatchers.IO) {
        if (assetClass != AssetClass.STOCK) return@withContext Result.success(null)

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
            Result.failure(allRes.exceptionOrNull() ?: Exception("خطا در دریافت قیمت سهام"))
        }
    }

    private fun parseTsetmcStockPrices(jsonString: String): List<MarketPrice> {
        val list = mutableListOf<MarketPrice>()
        try {
            val root = JSONObject(jsonString)
            val watchArray = root.optJSONArray("marketwatch") ?: return list

            for (i in 0 until watchArray.length()) {
                val item = watchArray.getJSONObject(i)
                val lVal18AFC = item.optString("lVal18AFC", "").trim() // نماد (مانند فولاد، فملی)
                val lVal30 = item.optString("lVal30", "").trim() // نام کامل شرکت
                val pClosing = item.optDouble("pClosing", 0.0) // قیمت پایانی به ریال
                val pDrCotVal = item.optDouble("pDrCotVal", pClosing) // آخرین معامله

                val finalPriceRial = if (pClosing > 0.0) pClosing else pDrCotVal
                if (lVal18AFC.isNotBlank() && finalPriceRial > 0.0) {
                    list.add(
                        MarketPrice(
                            symbolOrName = lVal18AFC,
                            name = if (lVal30.isNotBlank()) lVal30 else lVal18AFC,
                            assetClass = AssetClass.STOCK,
                            price = finalPriceRial,
                            currency = CurrencyType.RIAL,
                            unit = "سهم",
                            priceType = "STOCK_EQUITY",
                            source = "بورس تهران (TSETMC)",
                            timestamp = System.currentTimeMillis(),
                            status = PriceStatus.FRESH
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // parsing exception
        }
        return list
    }
}
