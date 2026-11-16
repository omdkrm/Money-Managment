package ir.modiriatsarmaye.app.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import ir.modiriatsarmaye.app.data.model.AppSettingsEntity
import ir.modiriatsarmaye.app.data.model.CurrencyType

/**
 * مقداردهی اولیه پایگاه داده برای استفاده واقعی
 * بدون هیچ‌گونه تراکنش، نرخ یا هدف ساختگی یا آزمایشی
 */
object InitialData {

    fun defaultSettings(): AppSettingsEntity = AppSettingsEntity(
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

    /**
     * درج قطعی و هم‌پایه (idempotent) تنظیمات اولیه در زمان ایجاد پایگاه‌داده تازه (onCreate).
     * هرگز داده‌های موجود را بازنویسی نمی‌کند و در ارتقای نسخه یا بازگشایی مجدد اجرا نمی‌شود.
     */
    fun seedDefaultSettingsIfMissing(db: SupportSQLiteDatabase) {
        val defaults = defaultSettings()
        val values = ContentValues().apply {
            put("id", defaults.id)
            put("displayCurrency", defaults.displayCurrency.name)
            put("monthlyInvestmentToman", defaults.monthlyInvestmentToman)
            put("annualInvestmentIncreasePct", defaults.annualInvestmentIncreasePct)
            put("targetGoldPct", defaults.targetGoldPct)
            put("targetEquityFundPct", defaults.targetEquityFundPct)
            put("targetFixedIncomePct", defaults.targetFixedIncomePct)
            put("targetUsdPct", defaults.targetUsdPct)
            put("targetCashPct", defaults.targetCashPct)
            put("rebalanceThresholdPct", defaults.rebalanceThresholdPct)
            put("inflationRatePct", defaults.inflationRatePct)
            put("scenarioConservativeReturnPct", defaults.scenarioConservativeReturnPct)
            put("scenarioBalancedReturnPct", defaults.scenarioBalancedReturnPct)
            put("scenarioOptimisticReturnPct", defaults.scenarioOptimisticReturnPct)
            put("retirementHorizonYears", defaults.retirementHorizonYears)
            put("retirementTargetToman", defaults.retirementTargetToman)
            put("pinCode", defaults.pinCode)
            put("isPinEnabled", if (defaults.isPinEnabled) 1 else 0)
            put("isBiometricEnabled", if (defaults.isBiometricEnabled) 1 else 0)
            put("hideSensitiveAmounts", if (defaults.hideSensitiveAmounts) 1 else 0)
            put("notifyMonthlyInvestment", if (defaults.notifyMonthlyInvestment) 1 else 0)
            put("notifyPriceUpdate", if (defaults.notifyPriceUpdate) 1 else 0)
            put("notifyRebalance", if (defaults.notifyRebalance) 1 else 0)
            put("notifyGoalProgress", if (defaults.notifyGoalProgress) 1 else 0)
            put("notifyMonthlyReport", if (defaults.notifyMonthlyReport) 1 else 0)
            put("notifyPortfolioDecline", if (defaults.notifyPortfolioDecline) 1 else 0)
            put("creatorName", defaults.creatorName)
            put("supportEmail", defaults.supportEmail)
            put("priceUpdateFrequency", defaults.priceUpdateFrequency.name)
            put("lastPriceUpdateTimestamp", defaults.lastPriceUpdateTimestamp)
            putNull("googleAccountEmail")
            putNull("googleAccountName")
            putNull("lastGoogleDriveBackupTimestamp")
            putNull("lastGoogleDriveBackupSummary")
        }
        db.insert("app_settings", SQLiteDatabase.CONFLICT_IGNORE, values)
    }

    suspend fun populateDatabase(database: AppDatabase) {
        val settingsDao = database.settingsDao()

        // فقط در صورت عدم وجود تنظیمات، تنظیمات پیش‌فرض و تمیز ایجاد می‌گردد
        if (settingsDao.getSettingsDirect() == null) {
            settingsDao.saveSettings(defaultSettings())
        }
    }
}
