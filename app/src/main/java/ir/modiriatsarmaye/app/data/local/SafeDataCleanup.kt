package ir.modiriatsarmaye.app.data.local

import ir.modiriatsarmaye.app.data.model.AppSettingsEntity
import ir.modiriatsarmaye.app.data.model.CurrencyType
import ir.modiriatsarmaye.app.data.model.GoalEntity
import ir.modiriatsarmaye.app.data.model.TransactionEntity

/**
 * پاکسازی ایمن و هدفمند داده‌های آزمایشی اولیه بدون آسیب به داده‌های واقعی کاربر
 * (Safe Cleanup of Known Sample / Demo Data)
 */
object SafeDataCleanup {

    suspend fun cleanupKnownSampleData(database: AppDatabase) {
        val txDao = database.transactionDao()
        val priceDao = database.currentPriceDao()
        val goalDao = database.goalDao()
        val settingsDao = database.settingsDao()

        // ۱. پاکسازی تراکنش‌های نمونه و آزمایشی
        val allTransactions = txDao.getAllTransactions()
        allTransactions.filter { isKnownSampleTransaction(it) }.forEach { tx ->
            txDao.deleteTransaction(tx)
        }

        // ۲. پاکسازی قیمت‌های روز آزمایشی قدیمی
        val allPrices = priceDao.getAllPrices()
        allPrices.filter { isKnownSamplePrice(it) }.forEach { p ->
            priceDao.deletePrice(p)
        }

        // ۳. پاکسازی اهداف مالی آزمایشی
        val allGoals = goalDao.getAllGoals()
        allGoals.filter { isKnownSampleGoal(it) }.forEach { g ->
            goalDao.deleteGoal(g)
        }

        // ۴. پاکسازی مقادیر نمونه تنظیمات (در صورت وجود مقادیر اولیه آزمایشی)
        val currentSettings = settingsDao.getSettingsDirect()
        if (currentSettings == null) {
            settingsDao.saveSettings(
                AppSettingsEntity(
                    id = 1,
                    displayCurrency = CurrencyType.TOMAN,
                    monthlyInvestmentToman = 0.0,
                    annualInvestmentIncreasePct = 0.0,
                    targetGoldPct = 0.0,
                    targetEquityFundPct = 0.0,
                    targetFixedIncomePct = 0.0,
                    targetUsdPct = 0.0,
                    targetCashPct = 0.0,
                    rebalanceThresholdPct = 5.0,
                    inflationRatePct = 35.0,
                    scenarioConservativeReturnPct = 20.0,
                    scenarioBalancedReturnPct = 30.0,
                    scenarioOptimisticReturnPct = 40.0,
                    retirementHorizonYears = 10,
                    retirementTargetToman = 0.0,
                    creatorName = "مدیریت سرمایه",
                    supportEmail = "support@modiriatsarmaye.ir"
                )
            )
        } else if (
            currentSettings.monthlyInvestmentToman == 10_000_000.0 &&
            currentSettings.targetGoldPct == 30.0 &&
            currentSettings.targetEquityFundPct == 30.0 &&
            currentSettings.retirementTargetToman == 10_000_000_000.0
        ) {
            settingsDao.saveSettings(
                currentSettings.copy(
                    monthlyInvestmentToman = 0.0,
                    annualInvestmentIncreasePct = 0.0,
                    targetGoldPct = 0.0,
                    targetEquityFundPct = 0.0,
                    targetFixedIncomePct = 0.0,
                    targetUsdPct = 0.0,
                    targetCashPct = 0.0,
                    retirementTargetToman = 0.0
                )
            )
        }
    }

    fun isKnownSampleTransaction(tx: TransactionEntity): Boolean {
        val sampleNotes = listOf(
            "خرید ماهانه طلا - قیمت خرید ثبت نشده است",
            "صندوق سهامی هستی‌بخش آگاه",
            "صندوق با درآمد ثابت کمند",
            "صندوق طلای لوتوس پارسیان"
        )
        if (sampleNotes.any { tx.notes.contains(it) }) return true

        val sampleDates = listOf("فروردین ۱۴۰۵", "اردیبهشت ۱۴۰۵", "خرداد ۱۴۰۵", "تیر ۱۴۰۵")
        if (tx.assetSymbol == "GOLD18" && tx.unitPrice == null && sampleDates.contains(tx.datePersian)) return true

        if (tx.assetSymbol == "AGAS" && tx.quantity == 100.0 && tx.unitPrice == 3732.0) return true
        if (tx.assetSymbol == "KAMAND" && tx.quantity == 5000.0 && tx.unitPrice == 10194.0) return true
        if (tx.assetSymbol == "LOTUS" && tx.quantity == 3000.0 && tx.unitPrice == 3040.0) return true

        return false
    }

    fun isKnownSamplePrice(p: ir.modiriatsarmaye.app.data.model.CurrentPriceEntity): Boolean {
        if (p.source == "ورود اولیه" || p.source.contains("اولیه")) return true
        if (p.assetSymbolOrName in listOf("آگاس", "کمند", "لوتوس", "AGAS", "KAMAND", "LOTUS") && p.source.contains("اولیه")) return true
        return false
    }

    fun isKnownSampleGoal(g: GoalEntity): Boolean {
        val sampleTitles = listOf("۱ میلیارد تومان", "۲ میلیارد تومان", "۵ میلیارد تومان", "هدف بازنشستگی")
        val sampleCategories = listOf("رسیدن به اولین سرمایه بزرگ", "توسعه سبد دارایی", "استقلال مالی میان‌مدت", "آسایش دوران بازنشستگی")
        return sampleTitles.contains(g.title) && sampleCategories.contains(g.category)
    }
}
