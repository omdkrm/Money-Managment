package ir.modiriatsarmaye.app.data.market

import ir.modiriatsarmaye.app.data.model.AssetClass

/**
 * رابط ارائه‌دهنده قیمت‌های بازار (Pluggable Market Data Provider)
 * به برنامه‌نویس امکان اضافه یا جایگزین کردن منابع نرخ طلا، سهام، و صندوق‌ها را می‌دهد.
 */
interface MarketDataProvider {
    val providerName: String
    val supportedAssetClasses: Set<AssetClass>

    /**
     * دریافت لیست آخرین قیمت‌های موجود از منبع
     */
    suspend fun fetchPrices(): Result<List<MarketPrice>>

    /**
     * دریافت قیمت برای یک دارایی یا نماد خاص
     */
    suspend fun fetchPriceForAsset(symbolOrName: String, assetClass: AssetClass): Result<MarketPrice?>
}
