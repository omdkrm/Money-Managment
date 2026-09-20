package ir.modiriatsarmaye.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * انواع دسته‌بندی دارایی‌ها (Asset Classes)
 */
enum class AssetClass(val titleFa: String, val defaultUnit: String) {
    GOLD("طلا و سکه", "گرم"),
    GOLD_FUND("صندوق طلا", "واحد"),
    EQUITY_FUND("صندوق سهامی", "واحد"),
    FIXED_INCOME_FUND("صندوق درآمد ثابت", "واحد"),
    STOCK("سهام", "سهم"),
    USD("دلار آمریکا", "دلار"),
    EUR("یورو", "یورو"),
    AED("درهم امارات", "درهم"),
    CRYPTO("رمز ارز", "واحد"),
    DEPOSIT("سپرده بانکی", "تومان"),
    CASH("وجه نقد و جاری", "تومان"),
    OTHER("سایر دارایی‌ها", "عدد")
}

/**
 * انواع عملیات تراکنش (Transaction Actions)
 */
enum class TransactionAction(val titleFa: String) {
    BUY("خرید"),
    SELL("فروش"),
    DIVIDEND("دریافت سود نقدی (DPS)"),
    DEPOSIT("واریز نقدی"),
    WITHDRAWAL("برداشت نقدی")
}

/**
 * انواع ارز مورد استفاده (Currency Types)
 * قاعده تبدیل: ۱۰ ریال = ۱ تومان
 */
enum class CurrencyType(val titleFa: String, val symbol: String) {
    TOMAN("تومان", "تومان"),
    RIAL("ریال", "ریال")
}

/**
 * انواع کارمزد تراکنش (Commission Types)
 */
enum class CommissionType(val titleFa: String) {
    PERCENTAGE("درصدی"),
    FIXED("مبلغ ثابت")
}

/**
 * مدل اصلی و یگانه تراکنش‌های مالی (Canonical Transaction Entity)
 * همه بخش‌ها اطلاعات خود را از این جدول مشتق می‌کنند.
 */
@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val datePersian: String, // e.g. "1405/01/15" or "فروردین ۱۴۰۵"
    val timestamp: Long = System.currentTimeMillis(),
    val assetClass: AssetClass,
    val assetName: String, // e.g. "طلای ۱۸ عیار", "آگاس", "کمند", "لوتوس"
    val assetSymbol: String = "", // e.g. "AGAS", "KAMAND", "LOTUS", "GOLD"
    val action: TransactionAction,
    val quantity: Double,
    val unit: String, // e.g. "گرم", "واحد", "سهم", "دلار"
    val unitPrice: Double? = null, // قیمت واحد به ارز مشخص شده (می‌تواند خالی باشد تا کاربر بعداً وارد کند)
    val currency: CurrencyType = CurrencyType.TOMAN,
    val fees: Double = 0.0, // مبلغ محاسبه‌شده کارمزد به همان ارز تراکنش
    val commissionType: CommissionType = CommissionType.FIXED, // نوع کارمزد: درصدی یا ثابت
    val commissionRate: Double = 0.0, // مقدار درصد یا مبلغ ثابت ورودی
    val totalAmount: Double? = null, // مبلغ کل تراکنش
    val brokerOrSource: String = "", // کارگزاری یا منبع
    val notes: String = ""
)

/**
 * وضعیت دریافت قیمت روز دارایی
 */
enum class PriceStatus(val titleFa: String) {
    FRESH("بروز"),
    STALE("تاریخ‌گذشته"),
    UNAVAILABLE("در دسترس نیست"),
    MANUAL("دستی")
}

/**
 * زمان‌بندی و رفتار به‌روزرسانی قیمت‌های بازار
 */
enum class PriceUpdateFrequency(val titleFa: String) {
    MANUAL_ONLY("فقط دستی"),
    ON_CONNECTIVITY("هنگام اتصال به اینترنت"),
    DAILY("روزانه"),
    ON_APP_OPEN("هنگام باز کردن برنامه در صورت وجود اینترنت")
}

/**
 * وضعیت اتصال و پشتیبان‌گیری ابری Google Drive
 */
enum class CloudBackupStatus(val titleFa: String) {
    DISCONNECTED("غیرمتصل"),
    IDLE("آماده"),
    SYNCING("در حال پشتیبان‌گیری..."),
    RESTORING("در حال بازیابی..."),
    SUCCESS("پشتیبان‌گیری موفق"),
    ERROR("خطا در پشتیبان‌گیری")
}

/**
 * جدول قیمت‌های روز دارایی‌ها (Current Prices)
 * پشتیبانی از ورود دستی و انتزاع وب‌سرویس‌های آنلاین
 */
@Entity(tableName = "current_prices")
data class CurrentPriceEntity(
    @PrimaryKey val assetSymbolOrName: String,
    val assetName: String,
    val assetClass: AssetClass,
    val price: Double, // قیمت روز
    val currency: CurrencyType = CurrencyType.TOMAN,
    val source: String = "ورود دستی",
    val lastUpdated: Long = System.currentTimeMillis(),
    val unit: String = "", // واحد سنجش (گرم، واحد، سهم، ...)
    val priceType: String = "", // e.g. "GOLD_18K", "STOCK_EQUITY", "FUND_NAV"
    val isAutoUpdated: Boolean = false,
    val status: PriceStatus = PriceStatus.FRESH,
    val errorMessage: String? = null,
    val instrumentId: String = "" // شناسه استاندارد سازوکار مالی (مانند GERAM18)
)

/**
 * اطلاعات عیب‌یابی چندمرحله‌ای خط لوله همگام‌سازی (Pipeline Diagnostics)
 * تفکیک دقیق: Provider -> Persistence -> Read-back -> Portfolio -> UI
 */
data class SyncPipelineDiagnostic(
    val providerResult: Boolean = false,
    val httpStatusCode: Int = 0,
    val parserSuccess: Boolean = false,
    val extractedPriceRial: Double? = null,
    val assetMappingSuccess: Boolean = false,
    val mappedAssetId: String = "",
    val mappedAssetName: String = "",
    val persistenceSuccess: Boolean = false,
    val readBackSuccess: Boolean = false,
    val readBackPriceRial: Double? = null,
    val portfolioCalculationSuccess: Boolean = false,
    val finalUiState: PriceStatus = PriceStatus.UNAVAILABLE,
    val finalPriceToman: Double? = null,
    val priceGrowthPct: Double? = null,
    val failureReason: String? = null,
    val insCode: String? = null,
    val isin: String? = null,
    val providerName: String? = null
)

/**
 * جدول اهداف مالی (Financial Goals)
 */
@Entity(tableName = "financial_goals")
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val targetAmountToman: Double,
    val targetDatePersian: String = "",
    val category: String = "سرمایه‌گذاری",
    val notes: String = ""
)

/**
 * جدول بدهی‌ها و تعهدات (Liabilities for Net Worth calculation)
 */
@Entity(tableName = "liabilities")
data class LiabilityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val totalAmountToman: Double,
    val monthlyPaymentToman: Double = 0.0,
    val dueDatePersian: String = "",
    val notes: String = ""
)

/**
 * جدول پیگیری سود سهام و مجامع (Dividends Tracking)
 */
@Entity(tableName = "dividends")
data class DividendEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stockSymbol: String,
    val stockName: String,
    val sharesHeld: Double,
    val dpsToman: Double, // سود هر سهم به تومان
    val receivableAmountToman: Double, // مبلغ کل قابل دریافت
    val meetingDatePersian: String = "",
    val paymentDatePersian: String = "",
    val receivedAmountToman: Double = 0.0,
    val isPaid: Boolean = false,
    val notes: String = ""
)

/**
 * تنظیمات کلی برنامه (App Settings)
 */
@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val displayCurrency: CurrencyType = CurrencyType.TOMAN,
    val monthlyInvestmentToman: Double = 10_000_000.0,
    val annualInvestmentIncreasePct: Double = 15.0,
    val targetGoldPct: Double = 30.0,
    val targetEquityFundPct: Double = 30.0,
    val targetFixedIncomePct: Double = 20.0,
    val targetUsdPct: Double = 10.0,
    val targetCashPct: Double = 10.0,
    val rebalanceThresholdPct: Double = 5.0,
    val inflationRatePct: Double = 35.0,
    val scenarioConservativeReturnPct: Double = 20.0,
    val scenarioBalancedReturnPct: Double = 30.0,
    val scenarioOptimisticReturnPct: Double = 40.0,
    val retirementHorizonYears: Int = 10,
    val retirementTargetToman: Double = 10_000_000_000.0, // ۱۰ میلیارد تومان
    val pinCode: String = "",
    val isPinEnabled: Boolean = false,
    val isBiometricEnabled: Boolean = false,
    val hideSensitiveAmounts: Boolean = false,
    val notifyMonthlyInvestment: Boolean = true,
    val notifyPriceUpdate: Boolean = true,
    val notifyRebalance: Boolean = true,
    val notifyGoalProgress: Boolean = true,
    val notifyMonthlyReport: Boolean = true,
    val notifyPortfolioDecline: Boolean = true,
    val creatorName: String = "توسعه‌دهنده مدیریت سرمایه",
    val supportEmail: String = "support@modiriatsarmaye.ir",
    // تنظیمات دریافت قیمت‌های آنلاین بازار
    val priceUpdateFrequency: PriceUpdateFrequency = PriceUpdateFrequency.ON_CONNECTIVITY,
    val lastPriceUpdateTimestamp: Long = 0L,
    // تنظیمات پشتیبان‌گیری ابری حساب Google Drive
    val googleAccountEmail: String? = null,
    val googleAccountName: String? = null,
    val lastGoogleDriveBackupTimestamp: Long? = null,
    val lastGoogleDriveBackupSummary: String? = null
)
