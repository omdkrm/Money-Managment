package ir.modiriatsarmaye.app.data.local

import ir.modiriatsarmaye.app.data.model.AppSettingsEntity
import ir.modiriatsarmaye.app.data.model.CurrencyType

/**
 * مقداردهی اولیه پایگاه داده برای استفاده واقعی
 * بدون هیچ‌گونه تراکنش، نرخ یا هدف ساختگی یا آزمایشی
 */
object InitialData {

    suspend fun populateDatabase(database: AppDatabase) {
        val settingsDao = database.settingsDao()

        // فقط در صورت عدم وجود تنظیمات، تنظیمات پیش‌فرض و تمیز ایجاد می‌گردد
        if (settingsDao.getSettingsDirect() == null) {
            val defaultSettings = AppSettingsEntity(
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
            settingsDao.saveSettings(defaultSettings)
        }
    }
}
