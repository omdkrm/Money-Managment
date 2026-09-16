package ir.modiriatsarmaye.app

import ir.modiriatsarmaye.app.data.model.*
import ir.modiriatsarmaye.app.util.CalculationEngine
import ir.modiriatsarmaye.app.util.PersianUtils
import org.junit.Assert.*
import org.junit.Test

class WealthCalculationTest {

    @Test
    fun testCurrencyConversions() {
        val rial = 100_000_000.0
        val toman = PersianUtils.rialToToman(rial)
        assertEquals(10_000_000.0, toman, 0.001)

        val backToRial = PersianUtils.tomanToRial(toman)
        assertEquals(100_000_000.0, backToRial, 0.001)
    }

    @Test
    fun testPersianDigitConversion() {
        val english = "1234567890"
        val persian = PersianUtils.toPersianDigits(english)
        assertEquals("۱۲۳۴۵۶۷۸۹۰", persian)

        val backToEnglish = PersianUtils.toEnglishDigits(persian)
        assertEquals("1234567890", backToEnglish)

        // Test Arabic-indic digits and decimal separator conversion
        val persianDecimal = "۰٫۹۹"
        val normalizedDecimal = PersianUtils.toEnglishDigits(persianDecimal)
        assertEquals("0.99", normalizedDecimal)
        assertEquals(0.99, normalizedDecimal.toDouble(), 0.0001)

        val arabicIndic = "٠١٢٣٤٥٦٧٨٩"
        assertEquals("0123456789", PersianUtils.toEnglishDigits(arabicIndic))
    }

    @Test
    fun testFractionalGoldCalculations() {
        // Test 0.99 gram gold purchase
        val goldTx = TransactionEntity(
            id = 1,
            datePersian = "1403/01/10",
            timestamp = 1000L,
            assetClass = AssetClass.GOLD,
            assetName = "طلای فیزیکی (۱۸ عیار)",
            assetSymbol = "GOLD18",
            action = TransactionAction.BUY,
            quantity = 0.99,
            unit = "گرم",
            unitPrice = 3_000_000.0, // 3,000,000 Toman per gram
            currency = CurrencyType.TOMAN,
            fees = 0.0,
            commissionType = CommissionType.FIXED,
            commissionRate = 0.0,
            totalAmount = 0.99 * 3_000_000.0 // 2,970,000 Toman
        )

        val prices = listOf(
            CurrentPriceEntity(
                assetSymbolOrName = "GOLD18",
                assetName = "طلای فیزیکی (۱۸ عیار)",
                assetClass = AssetClass.GOLD,
                price = 3_500_000.0, // 3,500,000 Toman per gram
                currency = CurrencyType.TOMAN,
                source = "تست",
                lastUpdated = 2000L
            )
        )

        val settings = AppSettingsEntity()
        val summary = CalculationEngine.calculatePortfolio(listOf(goldTx), prices, settings, emptyList())

        // Holding: 0.99 grams
        val holding = summary.holdings.first { it.assetSymbol == "GOLD18" || it.assetName == "طلای فیزیکی (۱۸ عیار)" }
        assertEquals(0.99, holding.quantity, 0.0001)
        assertEquals(3_000_000.0, holding.averagePurchasePriceToman ?: 0.0, 0.01)
        assertEquals(2_970_000.0, holding.totalCostToman, 0.01)

        // Current Value: 0.99 * 3,500,000 = 3,465,000 Toman
        assertEquals(3_465_000.0, holding.currentValueToman ?: 0.0, 0.01)
        // Profit: 3,465,000 - 2,970,000 = 495,000 Toman (+16.666%)
        assertEquals(495_000.0, holding.profitLossToman ?: 0.0, 0.01)
        assertEquals(16.666, holding.returnPercent ?: 0.0, 0.01)

        // Number format checks
        assertEquals("۰٫۹۹", PersianUtils.formatNumber(0.99))
        assertEquals("۰٫۵", PersianUtils.formatNumber(0.50))
        assertEquals("۰٫۰۱", PersianUtils.formatNumber(0.01))
    }

    @Test
    fun testPercentageCommissionCalculations() {
        // Base amount = 10,000,000 Rial, Commission = 5% -> Commission amount = 500,000 Rial -> Total = 10,500,000 Rial
        val quantity = 1000.0
        val unitPriceRial = 10000.0 // 1000 * 10000 = 10,000,000 Rial
        val commissionRatePct = 5.0
        val baseAmountRial = quantity * unitPriceRial
        val calculatedFeeRial = baseAmountRial * (commissionRatePct / 100.0)
        val finalAmountRial = baseAmountRial + calculatedFeeRial

        assertEquals(10_000_000.0, baseAmountRial, 0.01)
        assertEquals(500_000.0, calculatedFeeRial, 0.01)
        assertEquals(10_500_000.0, finalAmountRial, 0.01)

        val tx = TransactionEntity(
            id = 10,
            datePersian = "1403/03/01",
            timestamp = 5000L,
            assetClass = AssetClass.EQUITY_FUND,
            assetName = "آگاس",
            assetSymbol = "AGAS",
            action = TransactionAction.BUY,
            quantity = quantity,
            unit = "واحد",
            unitPrice = unitPriceRial,
            currency = CurrencyType.RIAL,
            fees = calculatedFeeRial,
            commissionType = CommissionType.PERCENTAGE,
            commissionRate = commissionRatePct,
            totalAmount = finalAmountRial
        )

        // In portfolio calculation:
        val prices = listOf(
            CurrentPriceEntity(
                assetSymbolOrName = "AGAS",
                assetName = "آگاس",
                assetClass = AssetClass.EQUITY_FUND,
                price = 12000.0, // 12,000 Rial per unit
                currency = CurrencyType.RIAL,
                source = "تست",
                lastUpdated = 6000L
            )
        )

        val settings = AppSettingsEntity()
        val summary = CalculationEngine.calculatePortfolio(listOf(tx), prices, settings, emptyList())

        // 10,500,000 Rial = 1,050,000 Toman invested cost
        assertEquals(1_050_000.0, summary.totalInvestedCostToman, 0.01)
        // 1000 units * 12,000 Rial = 12,000,000 Rial = 1,200,000 Toman current value
        assertEquals(1_200_000.0, summary.totalPortfolioValueToman, 0.01)
        // Profit = 1,200,000 - 1,050,000 = 150,000 Toman (+14.285%)
        assertEquals(150_000.0, summary.totalProfitLossToman, 0.01)
    }

    @Test
    fun testFixedCommissionCalculations() {
        // Base amount = 10,000,000 Rial, Fixed commission = 500,000 Rial -> Total = 10,500,000 Rial
        val tx = TransactionEntity(
            id = 20,
            datePersian = "1403/04/01",
            timestamp = 7000L,
            assetClass = AssetClass.FIXED_INCOME_FUND,
            assetName = "کمند",
            assetSymbol = "KAMAND",
            action = TransactionAction.BUY,
            quantity = 1000.0,
            unit = "واحد",
            unitPrice = 10000.0,
            currency = CurrencyType.RIAL,
            fees = 500_000.0,
            commissionType = CommissionType.FIXED,
            commissionRate = 500_000.0,
            totalAmount = 10_500_000.0
        )

        val summary = CalculationEngine.calculatePortfolio(listOf(tx), emptyList(), AppSettingsEntity(), emptyList())
        assertEquals(1_050_000.0, summary.totalInvestedCostToman, 0.01)
    }

    @Test
    fun testPortfolioCalculations() {
        val transactions = listOf(
            TransactionEntity(
                id = 1,
                datePersian = "1403/01/10",
                timestamp = 1000L,
                assetClass = AssetClass.GOLD_FUND,
                assetName = "لوتوس",
                assetSymbol = "LOTUS",
                action = TransactionAction.BUY,
                quantity = 1000.0,
                unit = "واحد",
                unitPrice = 5000.0,
                currency = CurrencyType.TOMAN,
                fees = 0.0,
                totalAmount = 5_000_000.0
            ),
            TransactionEntity(
                id = 2,
                datePersian = "1403/02/10",
                timestamp = 2000L,
                assetClass = AssetClass.GOLD_FUND,
                assetName = "لوتوس",
                assetSymbol = "LOTUS",
                action = TransactionAction.BUY,
                quantity = 1000.0,
                unit = "واحد",
                unitPrice = 7000.0,
                currency = CurrencyType.TOMAN,
                fees = 0.0,
                totalAmount = 7_000_000.0
            )
        )

        val prices = listOf(
            CurrentPriceEntity(
                assetSymbolOrName = "LOTUS",
                assetName = "لوتوس",
                assetClass = AssetClass.GOLD_FUND,
                price = 10_000.0,
                currency = CurrencyType.TOMAN,
                source = "تست",
                lastUpdated = 3000L
            )
        )

        val settings = AppSettingsEntity()
        val summary = CalculationEngine.calculatePortfolio(transactions, prices, settings, emptyList())

        // 2000 units total bought, total cost = 12,000,000 Toman, avg price = 6,000 Toman
        // Current price = 10,000 -> Current Value = 20,000,000 Toman
        // Profit = 8,000,000 Toman (+66.67%)
        assertEquals(20_000_000.0, summary.totalPortfolioValueToman, 0.01)
        assertEquals(12_000_000.0, summary.totalInvestedCostToman, 0.01)
        assertEquals(8_000_000.0, summary.totalProfitLossToman, 0.01)
        assertEquals(66.666, summary.totalReturnPct, 0.01)
    }

    @Test
    fun testRetirementSimulationCompounds() {
        val result = CalculationEngine.simulateRetirement(
            initialCapitalToman = 100_000_000.0,
            monthlyInvestmentToman = 10_000_000.0,
            annualIncreasePct = 25.0,
            inflationRatePct = 35.0,
            years = 10,
            returnRatePct = 30.0,
            scenarioName = "سناریو تست"
        )

        assertNotNull(result)
        assertTrue(result.finalNominalValueToman > result.totalContributionsToman)
        assertTrue(result.yearlyProjections.size == 10)
    }

    @Test
    fun testPriceGrowthPercentAndStatusMetadata() {
        val tx = TransactionEntity(
            id = 20,
            datePersian = "1403/04/01",
            timestamp = 10000L,
            assetClass = AssetClass.STOCK,
            assetName = "فولاد",
            assetSymbol = "FOOLAD",
            action = TransactionAction.BUY,
            quantity = 1000.0,
            unit = "سهم",
            unitPrice = 5000.0,
            currency = CurrencyType.TOMAN,
            fees = 0.0,
            totalAmount = 5_000_000.0
        )

        val price = CurrentPriceEntity(
            assetSymbolOrName = "FOOLAD",
            assetName = "فولاد",
            assetClass = AssetClass.STOCK,
            price = 6000.0,
            currency = CurrencyType.TOMAN,
            source = "بورس تهران (TSETMC)",
            status = PriceStatus.FRESH,
            lastUpdated = System.currentTimeMillis()
        )

        val summary = CalculationEngine.calculatePortfolio(listOf(tx), listOf(price), AppSettingsEntity(), emptyList())
        val holding = summary.holdings.first { it.assetName == "فولاد" }

        // Average purchase price = 5000 Toman, Current price = 6000 Toman -> Price Growth = +20%
        assertNotNull(holding.priceGrowthPercent)
        assertEquals(20.0, holding.priceGrowthPercent ?: 0.0, 0.01)
        assertEquals(PriceStatus.FRESH, holding.currentPriceStatus)
        assertEquals("بورس تهران (TSETMC)", holding.currentPriceSource)

        // Total Return: Invested = 5,000,000, Value = 6,000,000 -> Profit = 1,000,000 (+20.0%)
        assertEquals(1_000_000.0, holding.profitLossToman ?: 0.0, 0.01)
        assertEquals(20.0, holding.returnPercent ?: 0.0, 0.01)
    }
}
