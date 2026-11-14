package ir.modiriatsarmaye.app.util

import ir.modiriatsarmaye.app.data.market.AssetInstrumentMapper
import ir.modiriatsarmaye.app.data.market.FundInstrumentMapper
import ir.modiriatsarmaye.app.data.market.StockInstrumentMapper
import ir.modiriatsarmaye.app.data.model.*
import kotlin.math.pow

data class HoldingItem(
    val assetName: String,
    val assetSymbol: String,
    val assetClass: AssetClass,
    val quantity: Double,
    val unit: String,
    val totalCostToman: Double,
    val averagePurchasePriceToman: Double?,
    val currentPriceToman: Double?,
    val currentPriceOriginal: Double?,
    val currentPriceCurrency: CurrencyType,
    val currentValueToman: Double?,
    val profitLossToman: Double?,
    val returnPercent: Double?,
    val priceGrowthPercent: Double? = null,
    val portfolioPercent: Double,
    val isPurchasePriceMissing: Boolean,
    val isCurrentPriceMissing: Boolean,
    val currentPriceSource: String = "ورود دستی",
    val currentPriceLastUpdated: Long = 0L,
    val currentPriceStatus: PriceStatus = PriceStatus.FRESH,
    val currentPriceType: String = "",
    val currentPriceErrorMessage: String? = null
)

data class RebalanceItem(
    val assetClass: AssetClass,
    val targetPct: Double,
    val currentPct: Double,
    val diffPct: Double,
    val isAlert: Boolean,
    val recommendedAction: String, // "خرید", "فروش", "متعادل"
    val recommendedAmountToman: Double
)

data class PortfolioSummary(
    val totalPortfolioValueToman: Double,
    val totalInvestedCostToman: Double,
    val totalProfitLossToman: Double,
    val totalReturnPct: Double,
    val holdings: List<HoldingItem>,
    val allocationByClass: Map<AssetClass, Double>,
    val rebalanceItems: List<RebalanceItem>,
    val hasRebalanceAlert: Boolean,
    val totalLiabilitiesToman: Double,
    val netWorthToman: Double
)

data class RetirementYearProjection(
    val year: Int,
    val nominalValueToman: Double,
    val realValueToman: Double,
    val totalContributionsToman: Double
)

data class RetirementScenarioResult(
    val nameFa: String,
    val annualReturnRatePct: Double,
    val finalNominalValueToman: Double,
    val finalRealValueToman: Double,
    val totalContributionsToman: Double,
    val investmentProfitToman: Double,
    val yearlyProjections: List<RetirementYearProjection>
)

data class CashFlowSummary(
    val totalInflowsToman: Double,
    val totalOutflowsToman: Double,
    val netSavingsToman: Double,
    val monthlyInvestmentToman: Double,
    val remainingCashToman: Double
)

object CalculationEngine {

    const val ZERO_QUANTITY_EPSILON = 1e-8

    data class AssetPositionMetrics(
        val assetName: String,
        val assetSymbol: String,
        val assetClass: AssetClass,
        val unit: String,
        val quantity: Double,
        val remainingCostToman: Double,
        val averagePurchasePriceToman: Double?,
        val hasMissingBuyPrice: Boolean,
        val totalBoughtQuantity: Double,
        val totalSoldQuantity: Double,
        val totalDividendsToman: Double
    )

    data class AssetDetailSummary(
        val assetName: String,
        val assetSymbol: String,
        val assetClass: AssetClass,
        val unit: String,
        val currentQuantity: Double,
        val isSoldOut: Boolean,
        val currentPriceToman: Double?,
        val currentPriceOriginal: Double?,
        val currentPriceCurrency: CurrencyType,
        val currentPriceSource: String,
        val currentPriceLastUpdated: Long,
        val currentPriceStatus: PriceStatus,
        val currentPriceErrorMessage: String?,
        val currentValueToman: Double?,
        val weightedAveragePurchasePriceToman: Double?,
        val isPurchasePriceMissing: Boolean,
        val remainingCostBasisToman: Double,
        val unrealizedProfitLossToman: Double?,
        val unrealizedReturnPercent: Double?,
        val priceGrowthPercent: Double?,
        val totalBoughtQuantity: Double,
        val totalSoldQuantity: Double,
        val totalDividendsToman: Double,
        val transactions: List<TransactionEntity>
    )

    /**
     * محاسبه دقیق مانده دارایی و بهای تمام‌شده میانگین موزون به ترتیب زمانی (Sequential Weighted Average Cost Basis)
     */
    fun calculateAssetPositionMetrics(
        assetName: String,
        txList: List<TransactionEntity>
    ): AssetPositionMetrics {
        val sampleTx = txList.first()
        val assetClass = sampleTx.assetClass
        val assetSymbol = txList.firstOrNull { it.assetSymbol.isNotBlank() }?.assetSymbol ?: sampleTx.assetSymbol
        val unit = txList.firstOrNull { it.unit.isNotBlank() }?.unit ?: sampleTx.unit

        val orderedTxs = txList.sortedWith(compareBy<TransactionEntity>({ it.timestamp }, { it.id }))

        var remainingQty = 0.0
        var remainingCostToman = 0.0
        var hasMissingBuyPrice = false
        var totalBoughtQty = 0.0
        var totalSoldQty = 0.0
        var totalDividendsToman = 0.0

        for (tx in orderedTxs) {
            val txCostToman = when {
                tx.totalAmount != null -> PersianUtils.convertCurrency(tx.totalAmount, tx.currency, CurrencyType.TOMAN)
                tx.unitPrice != null -> PersianUtils.convertCurrency(tx.quantity * tx.unitPrice + tx.fees, tx.currency, CurrencyType.TOMAN)
                else -> null
            }

            when (tx.action) {
                TransactionAction.BUY -> {
                    if (tx.quantity > 0.0) {
                        remainingQty += tx.quantity
                        totalBoughtQty += tx.quantity
                        if (txCostToman != null) {
                            remainingCostToman += txCostToman
                        } else {
                            hasMissingBuyPrice = true
                        }
                    }
                }
                TransactionAction.SELL -> {
                    if (tx.quantity > 0.0) {
                        totalSoldQty += tx.quantity
                        val effectiveSellQty = tx.quantity.coerceAtMost(remainingQty)
                        if (effectiveSellQty > 0.0 && remainingQty > ZERO_QUANTITY_EPSILON) {
                            val avgCostPerUnit = remainingCostToman / remainingQty
                            val allocatedSoldCost = avgCostPerUnit * effectiveSellQty
                            remainingCostToman = (remainingCostToman - allocatedSoldCost).coerceAtLeast(0.0)
                            remainingQty = (remainingQty - effectiveSellQty).coerceAtLeast(0.0)
                        }
                        if (remainingQty <= ZERO_QUANTITY_EPSILON) {
                            remainingQty = 0.0
                            remainingCostToman = 0.0
                            hasMissingBuyPrice = false
                        }
                    }
                }
                TransactionAction.DIVIDEND -> {
                    val divAmountToman = when {
                        tx.totalAmount != null -> PersianUtils.convertCurrency(tx.totalAmount, tx.currency, CurrencyType.TOMAN)
                        tx.unitPrice != null -> PersianUtils.convertCurrency(tx.quantity * tx.unitPrice, tx.currency, CurrencyType.TOMAN)
                        else -> 0.0
                    }
                    totalDividendsToman += divAmountToman.coerceAtLeast(0.0)
                }
                TransactionAction.DEPOSIT,
                TransactionAction.WITHDRAWAL -> {
                    // عملیات نقدی تغییری در تعداد و بهای تمام‌شده واحد دارایی ایجاد نمی‌کند
                }
            }
        }

        val normalizedQty = if (remainingQty <= ZERO_QUANTITY_EPSILON) 0.0 else remainingQty
        val normalizedCost = if (normalizedQty == 0.0) 0.0 else remainingCostToman
        val avgBuyPriceToman = if (normalizedQty > ZERO_QUANTITY_EPSILON && !hasMissingBuyPrice) {
            normalizedCost / normalizedQty
        } else null

        return AssetPositionMetrics(
            assetName = assetName,
            assetSymbol = assetSymbol,
            assetClass = assetClass,
            unit = unit,
            quantity = normalizedQty,
            remainingCostToman = normalizedCost,
            averagePurchasePriceToman = avgBuyPriceToman,
            hasMissingBuyPrice = hasMissingBuyPrice,
            totalBoughtQuantity = totalBoughtQty,
            totalSoldQuantity = totalSoldQty,
            totalDividendsToman = totalDividendsToman
        )
    }

    private fun resolvePriceEntry(
        assetName: String,
        assetSymbol: String,
        assetClass: AssetClass,
        unit: String,
        prices: List<CurrentPriceEntity>,
        priceMap: Map<String, CurrentPriceEntity>
    ): CurrentPriceEntity? {
        val holdingInstrumentId = AssetInstrumentMapper.resolveInstrumentId(
            symbolOrKey = assetSymbol,
            name = assetName,
            assetClass = assetClass,
            unit = unit
        )

        val stockTicker = if (assetClass == AssetClass.STOCK) {
            StockInstrumentMapper.resolveStockSymbol(assetSymbol, assetName)
        } else null
        val fundTicker = if (assetClass in setOf(AssetClass.EQUITY_FUND, AssetClass.FIXED_INCOME_FUND, AssetClass.GOLD_FUND)) {
            FundInstrumentMapper.resolveFundSymbol(assetSymbol, assetName)
        } else null

        return priceMap[assetName]
            ?: priceMap[assetSymbol]
            ?: (if (stockTicker != null) priceMap[stockTicker] else null)
            ?: (if (fundTicker != null) priceMap[fundTicker] else null)
            ?: (if (holdingInstrumentId != null) priceMap[holdingInstrumentId] else null)
            ?: prices.firstOrNull { p ->
                (stockTicker != null && (
                    p.assetSymbolOrName.equals(stockTicker, ignoreCase = true) ||
                    p.instrumentId.equals(stockTicker, ignoreCase = true)
                )) ||
                (fundTicker != null && (
                    p.assetSymbolOrName.equals(fundTicker, ignoreCase = true) ||
                    p.instrumentId.equals(fundTicker, ignoreCase = true)
                )) ||
                (holdingInstrumentId != null && p.instrumentId.isNotBlank() && p.instrumentId.equals(holdingInstrumentId, ignoreCase = true)) ||
                (holdingInstrumentId != null && AssetInstrumentMapper.resolveInstrumentId(p.assetSymbolOrName, p.assetName, p.assetClass, p.unit) == holdingInstrumentId) ||
                (p.assetClass == assetClass && (
                    p.assetSymbolOrName.equals(assetName, ignoreCase = true) ||
                    p.assetSymbolOrName.equals(assetSymbol, ignoreCase = true) ||
                    p.assetName.equals(assetName, ignoreCase = true)
                ))
            }
    }

    /**
     * محاسبه اطلاعات جامع یک دارایی برای صفحه جزئیات دارایی و تاریخچه تراکنش‌ها
     * (پشتیبانی کامل از دارایی‌های فعال و دارایی‌های کاملاً فروخته‌شده)
     */
    fun calculateAssetDetail(
        assetName: String,
        assetSymbol: String,
        assetClass: AssetClass,
        unit: String,
        transactions: List<TransactionEntity>,
        prices: List<CurrentPriceEntity>
    ): AssetDetailSummary {
        val normalizedTargetName = assetName.trim()
        val normalizedTargetSymbol = assetSymbol.trim()

        val matchingTxs = transactions.filter { tx ->
            val txName = tx.assetName.trim()
            val txSymbol = tx.assetSymbol.trim()
            val nameMatches = txName.equals(normalizedTargetName, ignoreCase = true) && tx.assetClass == assetClass
            val symbolMatches = normalizedTargetSymbol.isNotBlank() &&
                txSymbol.isNotBlank() &&
                tx.assetClass == assetClass &&
                txSymbol.equals(normalizedTargetSymbol, ignoreCase = true)
            nameMatches || symbolMatches
        }

        val relevantHistory = matchingTxs
            .filter {
                it.action == TransactionAction.BUY ||
                    it.action == TransactionAction.SELL ||
                    it.action == TransactionAction.DIVIDEND
            }
            .sortedWith(compareByDescending<TransactionEntity> { it.timestamp }.thenByDescending { it.id })

        val metrics = if (matchingTxs.isNotEmpty()) {
            calculateAssetPositionMetrics(normalizedTargetName, matchingTxs)
        } else {
            AssetPositionMetrics(
                assetName = normalizedTargetName,
                assetSymbol = normalizedTargetSymbol,
                assetClass = assetClass,
                unit = unit.ifBlank { assetClass.defaultUnit },
                quantity = 0.0,
                remainingCostToman = 0.0,
                averagePurchasePriceToman = null,
                hasMissingBuyPrice = false,
                totalBoughtQuantity = 0.0,
                totalSoldQuantity = 0.0,
                totalDividendsToman = 0.0
            )
        }

        val effectiveSymbol = metrics.assetSymbol.ifBlank { normalizedTargetSymbol }
        val effectiveUnit = metrics.unit.ifBlank { unit.ifBlank { assetClass.defaultUnit } }
        val priceMap = prices.associateBy { it.assetSymbolOrName }
        val priceEntry = resolvePriceEntry(
            assetName = normalizedTargetName,
            assetSymbol = effectiveSymbol,
            assetClass = assetClass,
            unit = effectiveUnit,
            prices = prices,
            priceMap = priceMap
        )

        val currentPriceToman = priceEntry?.let {
            if (it.price > 0.0) PersianUtils.convertCurrency(it.price, it.currency, CurrencyType.TOMAN) else null
        }

        val isSoldOut = metrics.quantity <= ZERO_QUANTITY_EPSILON
        val currentValueToman = when {
            isSoldOut -> 0.0
            currentPriceToman != null -> currentPriceToman * metrics.quantity
            else -> null
        }

        val unrealizedPnLToman = when {
            isSoldOut -> 0.0
            currentValueToman != null && !metrics.hasMissingBuyPrice -> currentValueToman - metrics.remainingCostToman
            else -> null
        }

        val unrealizedReturnPct = when {
            isSoldOut -> 0.0
            unrealizedPnLToman != null && metrics.remainingCostToman > 0.0 -> (unrealizedPnLToman / metrics.remainingCostToman) * 100.0
            else -> null
        }

        val avgBuyPrice = metrics.averagePurchasePriceToman
        val priceGrowthPct = if (!isSoldOut && currentPriceToman != null && avgBuyPrice != null && avgBuyPrice > 0.0) {
            ((currentPriceToman - avgBuyPrice) / avgBuyPrice) * 100.0
        } else null

        val isCurrentPriceMissing = currentPriceToman == null

        return AssetDetailSummary(
            assetName = normalizedTargetName,
            assetSymbol = effectiveSymbol,
            assetClass = assetClass,
            unit = effectiveUnit,
            currentQuantity = metrics.quantity,
            isSoldOut = isSoldOut,
            currentPriceToman = currentPriceToman,
            currentPriceOriginal = priceEntry?.price,
            currentPriceCurrency = priceEntry?.currency ?: CurrencyType.TOMAN,
            currentPriceSource = priceEntry?.source ?: "ورود دستی",
            currentPriceLastUpdated = priceEntry?.lastUpdated ?: 0L,
            currentPriceStatus = priceEntry?.status ?: (if (isCurrentPriceMissing) PriceStatus.UNAVAILABLE else PriceStatus.MANUAL),
            currentPriceErrorMessage = priceEntry?.errorMessage,
            currentValueToman = currentValueToman,
            weightedAveragePurchasePriceToman = avgBuyPrice,
            isPurchasePriceMissing = metrics.hasMissingBuyPrice,
            remainingCostBasisToman = metrics.remainingCostToman,
            unrealizedProfitLossToman = unrealizedPnLToman,
            unrealizedReturnPercent = unrealizedReturnPct,
            priceGrowthPercent = priceGrowthPct,
            totalBoughtQuantity = metrics.totalBoughtQuantity,
            totalSoldQuantity = metrics.totalSoldQuantity,
            totalDividendsToman = metrics.totalDividendsToman,
            transactions = relevantHistory
        )
    }

    /**
     * محاسبه کامل پرتفوی، دارایی‌ها، سود و زیان و بازتنظیم بر اساس تراکنش‌ها
     */
    fun calculatePortfolio(
        transactions: List<TransactionEntity>,
        prices: List<CurrentPriceEntity>,
        settings: AppSettingsEntity,
        liabilities: List<LiabilityEntity> = emptyList()
    ): PortfolioSummary {
        val priceMap = prices.associateBy { it.assetSymbolOrName }

        // گروه بندی بر اساس دارایی (بر اساس نام دارایی)
        val groupedByAsset = transactions
            .filter { it.assetName.isNotBlank() }
            .groupBy { it.assetName.trim() }

        val rawHoldings = mutableListOf<HoldingItem>()
        var sumPortfolioValue = 0.0
        var sumInvestedCost = 0.0

        for ((assetName, txList) in groupedByAsset) {
            val metrics = calculateAssetPositionMetrics(assetName, txList)

            // دارایی‌هایی که تمام واحدهای آن‌ها فروخته شده است (مانده صفر در تلرانس دقت) از پرتفوی فعال حذف می‌شوند
            if (metrics.quantity <= ZERO_QUANTITY_EPSILON) continue

            val assetClass = metrics.assetClass
            val assetSymbol = metrics.assetSymbol
            val unit = metrics.unit
            val currentQty = metrics.quantity
            val avgBuyPriceToman = metrics.averagePurchasePriceToman
            val holdingCostToman = metrics.remainingCostToman

            val priceEntry = resolvePriceEntry(
                assetName = assetName,
                assetSymbol = assetSymbol,
                assetClass = assetClass,
                unit = unit,
                prices = prices,
                priceMap = priceMap
            )
            val currentPriceToman = priceEntry?.let {
                if (it.price > 0.0) PersianUtils.convertCurrency(it.price, it.currency, CurrencyType.TOMAN) else null
            }
            val isCurrentPriceMissing = currentPriceToman == null

            val currentValueToman = if (currentPriceToman != null) {
                currentPriceToman * currentQty
            } else null

            val profitLossToman = if (currentValueToman != null && avgBuyPriceToman != null) {
                currentValueToman - holdingCostToman
            } else null

            val returnPct = if (profitLossToman != null && holdingCostToman > 0) {
                (profitLossToman / holdingCostToman) * 100.0
            } else null

            // محاسبه رشد قیمت روز دارایی نسبت به میانگین قیمت خرید
            val priceGrowthPct = if (currentPriceToman != null && avgBuyPriceToman != null && avgBuyPriceToman > 0) {
                ((currentPriceToman - avgBuyPriceToman) / avgBuyPriceToman) * 100.0
            } else null

            if (currentValueToman != null) {
                sumPortfolioValue += currentValueToman
            } else if (holdingCostToman > 0) {
                sumPortfolioValue += holdingCostToman
            }
            sumInvestedCost += holdingCostToman

            rawHoldings.add(
                HoldingItem(
                    assetName = assetName,
                    assetSymbol = assetSymbol,
                    assetClass = assetClass,
                    quantity = currentQty,
                    unit = unit,
                    totalCostToman = holdingCostToman,
                    averagePurchasePriceToman = avgBuyPriceToman,
                    currentPriceToman = currentPriceToman,
                    currentPriceOriginal = priceEntry?.price,
                    currentPriceCurrency = priceEntry?.currency ?: CurrencyType.TOMAN,
                    currentValueToman = currentValueToman,
                    profitLossToman = profitLossToman,
                    returnPercent = returnPct,
                    priceGrowthPercent = priceGrowthPct,
                    portfolioPercent = 0.0, // calculated below
                    isPurchasePriceMissing = metrics.hasMissingBuyPrice,
                    isCurrentPriceMissing = isCurrentPriceMissing,
                    currentPriceSource = priceEntry?.source ?: "ورود دستی",
                    currentPriceLastUpdated = priceEntry?.lastUpdated ?: 0L,
                    currentPriceStatus = priceEntry?.status ?: (if (isCurrentPriceMissing) PriceStatus.UNAVAILABLE else PriceStatus.MANUAL),
                    currentPriceType = priceEntry?.priceType ?: "",
                    currentPriceErrorMessage = priceEntry?.errorMessage
                )
            )
        }

        // محاسبه درصد از کل پرتفوی
        val holdings = rawHoldings.map { h ->
            val value = h.currentValueToman ?: h.totalCostToman
            val pct = if (sumPortfolioValue > 0) (value / sumPortfolioValue) * 100.0 else 0.0
            h.copy(portfolioPercent = pct)
        }

        val totalProfitLossToman = sumPortfolioValue - sumInvestedCost
        val totalReturnPct = if (sumInvestedCost > 0) (totalProfitLossToman / sumInvestedCost) * 100.0 else 0.0

        // محاسبه تخصیص دارایی به تفکیک دسته (Asset Allocation)
        val allocationMap = mutableMapOf<AssetClass, Double>()
        for (h in holdings) {
            val valToman = h.currentValueToman ?: h.totalCostToman
            val current = allocationMap.getOrDefault(h.assetClass, 0.0)
            allocationMap[h.assetClass] = current + valToman
        }
        val allocationPctByClass = allocationMap.mapValues { (_, v) ->
            if (sumPortfolioValue > 0) (v / sumPortfolioValue) * 100.0 else 0.0
        }

        // محاسبه بازتنظیم (Rebalancing) بر اساس اهداف تنظیمی
        val targets = mapOf(
            AssetClass.GOLD to (settings.targetGoldPct),
            AssetClass.GOLD_FUND to (settings.targetGoldPct), // می‌توانند با هم یا مجزا سنجیده شوند
            AssetClass.EQUITY_FUND to (settings.targetEquityFundPct),
            AssetClass.FIXED_INCOME_FUND to (settings.targetFixedIncomePct),
            AssetClass.USD to (settings.targetUsdPct),
            AssetClass.CASH to (settings.targetCashPct)
        )

        // دسته‌بندی تجمیعی برای بازتنظیم: طلا (فیزیکی + صندوق طلا)، صندوق سهامی، درآمد ثابت، ارز، نقد
        val goldTotalPct = (allocationPctByClass[AssetClass.GOLD] ?: 0.0) + (allocationPctByClass[AssetClass.GOLD_FUND] ?: 0.0)
        val equityTotalPct = (allocationPctByClass[AssetClass.EQUITY_FUND] ?: 0.0) + (allocationPctByClass[AssetClass.STOCK] ?: 0.0)
        val fixedIncomeTotalPct = allocationPctByClass[AssetClass.FIXED_INCOME_FUND] ?: 0.0
        val usdTotalPct = (allocationPctByClass[AssetClass.USD] ?: 0.0) + (allocationPctByClass[AssetClass.EUR] ?: 0.0) + (allocationPctByClass[AssetClass.AED] ?: 0.0)
        val cashTotalPct = (allocationPctByClass[AssetClass.CASH] ?: 0.0) + (allocationPctByClass[AssetClass.DEPOSIT] ?: 0.0)

        val rebalanceCategories = listOf(
            Triple(AssetClass.GOLD, settings.targetGoldPct, goldTotalPct),
            Triple(AssetClass.EQUITY_FUND, settings.targetEquityFundPct, equityTotalPct),
            Triple(AssetClass.FIXED_INCOME_FUND, settings.targetFixedIncomePct, fixedIncomeTotalPct),
            Triple(AssetClass.USD, settings.targetUsdPct, usdTotalPct),
            Triple(AssetClass.CASH, settings.targetCashPct, cashTotalPct)
        )

        var hasAlert = false
        val threshold = settings.rebalanceThresholdPct

        val rebalanceItems = rebalanceCategories.map { (assetClass, targetPct, currentPct) ->
            val diff = currentPct - targetPct
            val isAlert = kotlin.math.abs(diff) > threshold
            if (isAlert) hasAlert = true

            val targetValueToman = (targetPct / 100.0) * sumPortfolioValue
            val currentValueForClass = (currentPct / 100.0) * sumPortfolioValue
            val amountDiffToman = kotlin.math.abs(targetValueToman - currentValueForClass)

            val action = when {
                diff > threshold -> "فروش / کاهش سهم"
                diff < -threshold -> "خرید / افزایش سهم"
                else -> "متعادل"
            }

            RebalanceItem(
                assetClass = assetClass,
                targetPct = targetPct,
                currentPct = currentPct,
                diffPct = diff,
                isAlert = isAlert,
                recommendedAction = action,
                recommendedAmountToman = amountDiffToman
            )
        }

        val totalLiabilitiesToman = liabilities.sumOf { it.totalAmountToman }
        val netWorthToman = sumPortfolioValue - totalLiabilitiesToman

        return PortfolioSummary(
            totalPortfolioValueToman = sumPortfolioValue,
            totalInvestedCostToman = sumInvestedCost,
            totalProfitLossToman = totalProfitLossToman,
            totalReturnPct = totalReturnPct,
            holdings = holdings,
            allocationByClass = allocationPctByClass,
            rebalanceItems = rebalanceItems,
            hasRebalanceAlert = hasAlert,
            totalLiabilitiesToman = totalLiabilitiesToman,
            netWorthToman = netWorthToman
        )
    }

    /**
     * شبیه‌سازی دقیق و مرکب ثروت بازنشستگی در سناریوهای سه‌گانه
     */
    fun simulateRetirement(
        initialCapitalToman: Double,
        monthlyInvestmentToman: Double,
        annualIncreasePct: Double,
        inflationRatePct: Double,
        years: Int,
        returnRatePct: Double,
        scenarioName: String
    ): RetirementScenarioResult {
        var currentNominal = initialCapitalToman
        var totalContributions = initialCapitalToman
        var currentMonthlyContribution = monthlyInvestmentToman
        val projections = mutableListOf<RetirementYearProjection>()

        for (year in 1..years) {
            // ۱۲ ماه سال
            for (m in 1..12) {
                // رشد ماهانه با نرخ معادل مرکب ماهانه
                val monthlyRate = (1.0 + returnRatePct / 100.0).pow(1.0 / 12.0) - 1.0
                currentNominal = (currentNominal + currentMonthlyContribution) * (1.0 + monthlyRate)
                totalContributions += currentMonthlyContribution
            }
            // افزایش سالانه پس‌انداز ماهانه بر اساس نرخ افزایش
            currentMonthlyContribution *= (1.0 + annualIncreasePct / 100.0)

            // ارزش واقعی تعدیل‌شده با تورم
            val inflationDiscount = (1.0 + inflationRatePct / 100.0).pow(year.toDouble())
            val realValue = currentNominal / inflationDiscount

            projections.add(
                RetirementYearProjection(
                    year = year,
                    nominalValueToman = currentNominal,
                    realValueToman = realValue,
                    totalContributionsToman = totalContributions
                )
            )
        }

        val finalNominal = projections.lastOrNull()?.nominalValueToman ?: initialCapitalToman
        val finalReal = projections.lastOrNull()?.realValueToman ?: initialCapitalToman
        val profit = finalNominal - totalContributions

        return RetirementScenarioResult(
            nameFa = scenarioName,
            annualReturnRatePct = returnRatePct,
            finalNominalValueToman = finalNominal,
            finalRealValueToman = finalReal,
            totalContributionsToman = totalContributions,
            investmentProfitToman = profit,
            yearlyProjections = projections
        )
    }

    /**
     * محاسبه جریان نقدی
     */
    fun calculateCashFlow(
        transactions: List<TransactionEntity>,
        monthlyInvestmentToman: Double
    ): CashFlowSummary {
        var totalInflows = 0.0
        var totalOutflows = 0.0

        for (tx in transactions) {
            val amountToman = when {
                tx.totalAmount != null -> PersianUtils.convertCurrency(tx.totalAmount, tx.currency, CurrencyType.TOMAN)
                tx.unitPrice != null -> PersianUtils.convertCurrency(tx.quantity * tx.unitPrice, tx.currency, CurrencyType.TOMAN)
                else -> 0.0
            }

            when (tx.action) {
                TransactionAction.DEPOSIT, TransactionAction.DIVIDEND -> totalInflows += amountToman
                TransactionAction.WITHDRAWAL -> totalOutflows += amountToman
                else -> {}
            }
        }

        val netSavings = totalInflows - totalOutflows
        val remainingCash = netSavings - monthlyInvestmentToman

        return CashFlowSummary(
            totalInflowsToman = totalInflows,
            totalOutflowsToman = totalOutflows,
            netSavingsToman = netSavings,
            monthlyInvestmentToman = monthlyInvestmentToman,
            remainingCashToman = remainingCash
        )
    }
}
