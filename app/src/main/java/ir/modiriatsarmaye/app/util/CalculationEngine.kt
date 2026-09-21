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
        val groupedByAsset = transactions.groupBy { it.assetName }

        val rawHoldings = mutableListOf<HoldingItem>()
        var sumPortfolioValue = 0.0
        var sumInvestedCost = 0.0

        for ((assetName, txList) in groupedByAsset) {
            val sampleTx = txList.first()
            val assetClass = sampleTx.assetClass
            val assetSymbol = sampleTx.assetSymbol
            val unit = sampleTx.unit

            var totalBuyQty = 0.0
            var totalSellQty = 0.0
            var totalBuyCostToman = 0.0
            var hasMissingBuyPrice = false

            for (tx in txList) {
                val txCostToman = when {
                    tx.totalAmount != null -> PersianUtils.convertCurrency(tx.totalAmount, tx.currency, CurrencyType.TOMAN)
                    tx.unitPrice != null -> PersianUtils.convertCurrency(tx.quantity * tx.unitPrice + tx.fees, tx.currency, CurrencyType.TOMAN)
                    else -> null
                }

                when (tx.action) {
                    TransactionAction.BUY -> {
                        totalBuyQty += tx.quantity
                        if (txCostToman != null) {
                            totalBuyCostToman += txCostToman
                        } else {
                            hasMissingBuyPrice = true
                        }
                    }
                    TransactionAction.SELL -> {
                        totalSellQty += tx.quantity
                    }
                    TransactionAction.DIVIDEND,
                    TransactionAction.DEPOSIT,
                    TransactionAction.WITHDRAWAL -> {
                        // handled separately
                    }
                }
            }

            val currentQty = (totalBuyQty - totalSellQty).coerceAtLeast(0.0)
            if (currentQty <= 0.0 && totalBuyQty == 0.0) continue

            // میانگین قیمت خرید
            val avgBuyPriceToman = if (totalBuyQty > 0 && !hasMissingBuyPrice) {
                totalBuyCostToman / totalBuyQty
            } else null

            val holdingCostToman = if (avgBuyPriceToman != null) {
                avgBuyPriceToman * currentQty
            } else {
                totalBuyCostToman
            }

            // دریافت قیمت روز با استفاده از نگاشت قطعی سازوکار استاندارد (Deterministic Instrument Mapping)
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

            val priceEntry = priceMap[assetName]
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
                // اگر قیمت روز نیست، از بهای تمام‌شده به عنوان ارزش تقریب موقت استفاده نمی‌کنیم مگر به عنوان پایه
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
                    isPurchasePriceMissing = hasMissingBuyPrice,
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
