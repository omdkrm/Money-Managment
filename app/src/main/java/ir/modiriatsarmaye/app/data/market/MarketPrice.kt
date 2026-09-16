package ir.modiriatsarmaye.app.data.market

import ir.modiriatsarmaye.app.data.model.AssetClass
import ir.modiriatsarmaye.app.data.model.CurrencyType
import ir.modiriatsarmaye.app.data.model.PriceStatus

/**
 * مدل داده قیمت بازار دریافتی از ارائه‌دهندگان نرخ آنلاین
 */
data class MarketPrice(
    val symbolOrName: String,
    val name: String,
    val assetClass: AssetClass,
    val price: Double,
    val currency: CurrencyType = CurrencyType.TOMAN,
    val unit: String,
    val priceType: String,
    val source: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: PriceStatus = PriceStatus.FRESH,
    val errorMessage: String? = null,
    val purity: String? = null,
    val originalPrice: Double? = null,
    val originalCurrency: CurrencyType? = null,
    val originalUnit: String? = null,
    val instrumentId: String = "",
    val latestPrice: Double? = null,
    val closingPrice: Double? = null
)
