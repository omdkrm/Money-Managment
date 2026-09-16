package ir.modiriatsarmaye.app.data.market

import ir.modiriatsarmaye.app.data.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class MarketUpdateReport(
    val updatedCount: Int,
    val failedCount: Int,
    val totalCount: Int,
    val messageFa: String,
    val timestamp: Long = System.currentTimeMillis(),
    val updatedPrices: List<CurrentPriceEntity> = emptyList(),
    val goldDiagnostics: List<StrategyDiagnostic> = emptyList(),
    val stockDiagnostics: List<StrategyDiagnostic> = emptyList(),
    val fundDiagnostics: List<StrategyDiagnostic> = emptyList(),
    val pipelineDiagnostic: SyncPipelineDiagnostic? = null,
    val allDiagnostics: List<StrategyDiagnostic> = emptyList()
)

/**
 * ارائه‌دهنده یکپارچه و ترکیبی قیمت‌های بازار (Composite Market Data Provider)
 * مدیریت هماهنگ منابع طلا (GoldPriceProvider)، سهام (StockMarketPriceProvider)، و صندوق‌ها (FundMarketPriceProvider)
 * با حفظ داده‌های قبلی، جلوگیری از جعل قیمت و پایداری کامل در حالت آفلاین
 */
class CompositeMarketDataProvider(
    private val goldProvider: GoldPriceProvider = GoldPriceProvider(),
    private val stockProvider: StockMarketPriceProvider = StockMarketPriceProvider(),
    private val fundProvider: FundMarketPriceProvider = FundMarketPriceProvider(),
    private val providers: List<MarketDataProvider> = listOf(goldProvider, stockProvider, fundProvider)
) {
    private var lastFetchTimestamp: Long = 0L
    private val cacheDurationMs: Long = 5 * 60 * 1000L // 5 minutes cache window
    private var cachedMarketPrices: List<MarketPrice> = emptyList()

    fun isCacheValid(): Boolean {
        return cachedMarketPrices.isNotEmpty() && (System.currentTimeMillis() - lastFetchTimestamp < cacheDurationMs)
    }

    fun getGoldDiagnostics(): List<StrategyDiagnostic> {
        return goldProvider.lastDiagnostics
    }

    fun getStockDiagnostics(): List<StrategyDiagnostic> {
        return stockProvider.lastDiagnostics
    }

    fun getFundDiagnostics(): List<StrategyDiagnostic> {
        return fundProvider.lastDiagnostics
    }

    fun getAllDiagnostics(): List<StrategyDiagnostic> {
        return getGoldDiagnostics() + getStockDiagnostics() + getFundDiagnostics()
    }

    /**
     * دریافت و همگام‌سازی کلیه قیمت‌های بازار از تمام ارائه‌دهنده‌ها
     */
    suspend fun fetchAllPrices(forceRefresh: Boolean = false): Result<List<MarketPrice>> = coroutineScope {
        if (!forceRefresh && isCacheValid()) {
            return@coroutineScope Result.success(cachedMarketPrices)
        }

        val deferredResults = providers.map { provider ->
            async { Pair(provider, provider.fetchPrices()) }
        }

        val allPrices = mutableListOf<MarketPrice>()
        val errors = mutableListOf<String>()

        deferredResults.forEach { deferred ->
            val (_, result) = deferred.await()
            if (result.isSuccess) {
                result.getOrNull()?.let { allPrices.addAll(it) }
            } else {
                result.exceptionOrNull()?.message?.let { errors.add(it) }
            }
        }

        if (allPrices.isNotEmpty()) {
            cachedMarketPrices = allPrices
            lastFetchTimestamp = System.currentTimeMillis()
            Result.success(allPrices)
        } else {
            val errorSummary = if (errors.isNotEmpty()) errors.first() else "منبع دریافت قیمت‌های بازار در دسترس نیست."
            Result.failure(Exception(errorSummary))
        }
    }

    /**
     * انطباق و بروزرسانی قیمت‌های روز با دارایی‌های موجود کاربر
     * بر اساس نوع دارایی به ارائه‌دهنده اختصاصی هدایت می‌شود:
     * - طلا -> GoldPriceProvider
     * - سهام -> StockMarketPriceProvider
     * - صندوق‌ها -> FundMarketPriceProvider
     */
    suspend fun syncCurrentPrices(
        existingPrices: List<CurrentPriceEntity>,
        assetsToUpdate: List<Pair<String, AssetClass>>, // list of (assetSymbolOrName, assetClass)
        forceRefresh: Boolean = false
    ): MarketUpdateReport {
        val fetchResult = fetchAllPrices(forceRefresh)
        val onlinePrices = fetchResult.getOrNull() ?: emptyList()
        val isNetworkSuccess = fetchResult.isSuccess && onlinePrices.isNotEmpty()
        val networkErrorMessage = fetchResult.exceptionOrNull()?.message

        val existingPriceMap = existingPrices.associateBy { it.assetSymbolOrName }
        val updatedList = mutableListOf<CurrentPriceEntity>()
        var successCount = 0
        var failCount = 0

        val fundClasses = setOf(AssetClass.EQUITY_FUND, AssetClass.FIXED_INCOME_FUND, AssetClass.GOLD_FUND)

        for ((assetKey, assetClass) in assetsToUpdate) {
            val existing = existingPriceMap[assetKey]

            var matched: MarketPrice? = null
            var targetInstrumentId: String? = null

            when {
                assetClass == AssetClass.GOLD -> {
                    targetInstrumentId = AssetInstrumentMapper.resolveInstrumentId(
                        symbolOrKey = assetKey,
                        name = existing?.assetName ?: assetKey,
                        assetClass = assetClass,
                        unit = existing?.unit ?: ""
                    )
                    if (isNetworkSuccess && targetInstrumentId != null) {
                        matched = onlinePrices.find { p ->
                            p.assetClass == AssetClass.GOLD && (
                                p.instrumentId.equals(targetInstrumentId, ignoreCase = true) ||
                                p.symbolOrName.equals(targetInstrumentId, ignoreCase = true) ||
                                AssetInstrumentMapper.resolveInstrumentId(p.symbolOrName, p.name, p.assetClass, p.unit) == targetInstrumentId
                            )
                        }
                    }
                }

                assetClass == AssetClass.STOCK -> {
                    val resolvedStockSymbol = StockInstrumentMapper.resolveStockSymbol(
                        symbolOrKey = assetKey,
                        name = existing?.assetName ?: ""
                    )
                    targetInstrumentId = resolvedStockSymbol
                    if (isNetworkSuccess && resolvedStockSymbol != null) {
                        matched = onlinePrices.find { p ->
                            p.assetClass == AssetClass.STOCK && (
                                StockInstrumentMapper.normalizeSymbol(p.symbolOrName) == resolvedStockSymbol ||
                                StockInstrumentMapper.normalizeSymbol(p.instrumentId) == resolvedStockSymbol
                            )
                        }
                    }
                    // در صورت عدم وجود در جدول کلی، استعلام مستقیم پروفایل اختصاصی سهم
                    if (matched == null && resolvedStockSymbol != null) {
                        val singleRes = stockProvider.fetchPriceForAsset(resolvedStockSymbol, AssetClass.STOCK)
                        if (singleRes.isSuccess && singleRes.getOrNull() != null) {
                            matched = singleRes.getOrNull()
                        }
                    }
                }

                assetClass in fundClasses -> {
                    val resolvedFundSymbol = FundInstrumentMapper.resolveFundSymbol(
                        symbolOrKey = assetKey,
                        name = existing?.assetName ?: ""
                    )
                    targetInstrumentId = resolvedFundSymbol
                    if (isNetworkSuccess && resolvedFundSymbol != null) {
                        matched = onlinePrices.find { p ->
                            p.assetClass in fundClasses && (
                                FundInstrumentMapper.normalizeFundSymbol(p.symbolOrName) == resolvedFundSymbol ||
                                FundInstrumentMapper.normalizeFundSymbol(p.instrumentId) == resolvedFundSymbol
                            )
                        }
                    }
                    // در صورت عدم وجود در جدول کلی، استعلام مستقیم پروفایل اختصاصی صندوق
                    if (matched == null && resolvedFundSymbol != null) {
                        val singleRes = fundProvider.fetchPriceForAsset(resolvedFundSymbol, assetClass)
                        if (singleRes.isSuccess && singleRes.getOrNull() != null) {
                            matched = singleRes.getOrNull()
                        }
                    }
                }

                else -> {
                    // سایر کلاس‌ها (مانند ارز و سپرده) در صورت وجود تطابق مستقیم
                    if (isNetworkSuccess) {
                        matched = onlinePrices.find { p ->
                            p.assetClass == assetClass && (
                                p.symbolOrName.equals(assetKey, ignoreCase = true) ||
                                p.name.equals(assetKey, ignoreCase = true)
                            )
                        }
                    }
                }
            }

            if (matched != null && matched.price > 0.0) {
                val effectiveInstrumentId = matched.instrumentId.ifBlank { targetInstrumentId ?: "" }
                updatedList.add(
                    CurrentPriceEntity(
                        assetSymbolOrName = assetKey,
                        assetName = if (existing != null && existing.assetName.isNotBlank()) existing.assetName else matched.name,
                        assetClass = assetClass,
                        price = matched.price,
                        currency = matched.currency,
                        source = matched.source,
                        lastUpdated = System.currentTimeMillis(),
                        unit = matched.unit,
                        priceType = matched.priceType,
                        isAutoUpdated = true,
                        status = PriceStatus.FRESH,
                        errorMessage = null,
                        instrumentId = effectiveInstrumentId
                    )
                )

                // در صورت وجود شناسه سازوکار برای طلای ۱۸ عیار، ثبت رکورد استاندارد
                if (assetClass == AssetClass.GOLD && effectiveInstrumentId == AssetInstrumentMapper.INSTRUMENT_GERAM18 &&
                    updatedList.none { it.assetSymbolOrName.equals(effectiveInstrumentId, ignoreCase = true) }
                ) {
                    updatedList.add(
                        CurrentPriceEntity(
                            assetSymbolOrName = effectiveInstrumentId,
                            assetName = AssetInstrumentMapper.CANONICAL_GOLD18_NAME,
                            assetClass = AssetClass.GOLD,
                            price = matched.price,
                            currency = matched.currency,
                            source = matched.source,
                            lastUpdated = System.currentTimeMillis(),
                            unit = matched.unit,
                            priceType = matched.priceType,
                            isAutoUpdated = true,
                            status = PriceStatus.FRESH,
                            errorMessage = null,
                            instrumentId = effectiveInstrumentId
                        )
                    )
                }
                successCount++
            } else {
                // اگر بروزرسانی آنلاین ناموفق بود: حفظ آخرین قیمت معتبر محلی با برچسب STALE
                if (existing != null && existing.price > 0.0) {
                    val newStatus = if (existing.source == "ورود دستی") PriceStatus.MANUAL else PriceStatus.STALE
                    val errorMsg = if (!isNetworkSuccess) {
                        networkErrorMessage ?: "اتصال به بازار برقرار نشد (حفظ آخرین نرخ معتبر)."
                    } else {
                        "نرخ نماد $assetKey در بازار یافت نشد؛ نیاز به تصحیح نماد یا ثبت دستی."
                    }
                    updatedList.add(
                        existing.copy(
                            status = newStatus,
                            errorMessage = errorMsg,
                            instrumentId = existing.instrumentId.ifBlank { targetInstrumentId ?: "" }
                        )
                    )
                } else {
                    // بدون ایجاد قیمت جعلی یا صفر
                    val errorMsg = if (!isNetworkSuccess) {
                        networkErrorMessage ?: "منبع دریافت قیمت‌های بازار در دسترس نیست."
                    } else {
                        "قیمت روز در دسترس نیست"
                    }
                    updatedList.add(
                        CurrentPriceEntity(
                            assetSymbolOrName = assetKey,
                            assetName = existing?.assetName ?: assetKey,
                            assetClass = assetClass,
                            price = 0.0,
                            currency = CurrencyType.TOMAN,
                            source = "نامشخص",
                            lastUpdated = 0L,
                            unit = assetClass.defaultUnit,
                            priceType = "",
                            isAutoUpdated = false,
                            status = PriceStatus.UNAVAILABLE,
                            errorMessage = errorMsg,
                            instrumentId = targetInstrumentId ?: ""
                        )
                    )
                }
                failCount++
            }
        }

        val message = when {
            successCount > 0 && failCount == 0 -> "قیمت $successCount دارایی با موفقیت از بازار بروزرسانی شد."
            successCount > 0 && failCount > 0 -> "قیمت $successCount دارایی بروز شد، $failCount دارایی از آخرین قیمت معتبر نگهداری شد."
            isNetworkSuccess -> "بررسی قیمت‌های بازار انجام شد."
            else -> networkErrorMessage ?: "منبع دریافت قیمت‌های بازار در دسترس نیست."
        }

        val goldDiags = getGoldDiagnostics()
        val stockDiags = getStockDiagnostics()
        val fundDiags = getFundDiagnostics()
        val allDiags = goldDiags + stockDiags + fundDiags

        return MarketUpdateReport(
            updatedCount = successCount,
            failedCount = failCount,
            totalCount = assetsToUpdate.size,
            messageFa = message,
            timestamp = System.currentTimeMillis(),
            updatedPrices = updatedList.filter { it.price > 0.0 },
            goldDiagnostics = goldDiags,
            stockDiagnostics = stockDiags,
            fundDiagnostics = fundDiags,
            allDiagnostics = allDiags
        )
    }
}
