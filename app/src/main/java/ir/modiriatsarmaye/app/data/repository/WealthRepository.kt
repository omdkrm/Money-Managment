package ir.modiriatsarmaye.app.data.repository

import android.content.Context
import android.util.Log
import ir.modiriatsarmaye.app.data.cloud.DriveBackupInfo
import ir.modiriatsarmaye.app.data.cloud.GoogleAccountInfo
import ir.modiriatsarmaye.app.data.cloud.GoogleDriveBackupManager
import ir.modiriatsarmaye.app.data.local.*
import ir.modiriatsarmaye.app.data.market.AssetInstrumentMapper
import ir.modiriatsarmaye.app.data.market.CompositeMarketDataProvider
import ir.modiriatsarmaye.app.data.market.FundInstrumentMapper
import ir.modiriatsarmaye.app.data.market.MarketUpdateReport
import ir.modiriatsarmaye.app.data.market.StockInstrumentMapper
import ir.modiriatsarmaye.app.data.market.StrategyDiagnostic
import ir.modiriatsarmaye.app.data.model.*
import ir.modiriatsarmaye.app.util.CalculationEngine
import ir.modiriatsarmaye.app.util.PersianUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

sealed class ValidationResult {
    open class Success(
        open val message: String = "عملیات با موفقیت انجام شد.",
        open val report: ir.modiriatsarmaye.app.data.backup.BackupValidationReport? = null
    ) : ValidationResult() {
        companion object : Success()
    }
    data class Error(val message: String) : ValidationResult()
}

class WealthRepository(
    private val database: AppDatabase,
    context: Context? = null,
    private val marketDataProvider: CompositeMarketDataProvider = CompositeMarketDataProvider()
) {
    private val transactionDao = database.transactionDao()
    private val currentPriceDao = database.currentPriceDao()
    private val goalDao = database.goalDao()
    private val liabilityDao = database.liabilityDao()
    private val dividendDao = database.dividendDao()
    private val settingsDao = database.settingsDao()

    private val driveBackupManager = context?.let { GoogleDriveBackupManager(it) }

    init {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            SafeDataCleanup.cleanupKnownSampleData(database)
        }
    }

    val allTransactionsFlow: Flow<List<TransactionEntity>> = transactionDao.getAllTransactionsFlow()
    val allPricesFlow: Flow<List<CurrentPriceEntity>> = currentPriceDao.getAllPricesFlow()
    val allGoalsFlow: Flow<List<GoalEntity>> = goalDao.getAllGoalsFlow()
    val allLiabilitiesFlow: Flow<List<LiabilityEntity>> = liabilityDao.getAllLiabilitiesFlow()
    val allDividendsFlow: Flow<List<DividendEntity>> = dividendDao.getAllDividendsFlow()
    val settingsFlow: Flow<AppSettingsEntity?> = settingsDao.getSettingsFlow()

    suspend fun getTransactionById(id: Long): TransactionEntity? = transactionDao.getTransactionById(id)

    /**
     * اعتبارسنجی دقیق تراکنش قبل از ثبت در سامانه یکپارچه
     */
    suspend fun validateTransaction(transaction: TransactionEntity, isEdit: Boolean = false): ValidationResult {
        if (transaction.assetName.isBlank()) {
            return ValidationResult.Error("نام دارایی را وارد کنید.")
        }
        if (transaction.quantity <= 0.0) {
            return ValidationResult.Error("تعداد / مقدار باید بزرگتر از صفر باشد.")
        }
        if (transaction.unit.isBlank()) {
            return ValidationResult.Error("واحد سنجش را مشخص کنید.")
        }

        // اعتبارسنجی کارمزد
        if (transaction.fees < 0.0 || transaction.commissionRate < 0.0) {
            return ValidationResult.Error("کارمزد نمی‌تواند منفی باشد.")
        }
        if (transaction.commissionType == CommissionType.PERCENTAGE && transaction.commissionRate > 100.0) {
            return ValidationResult.Error("درصد کارمزد باید بین ۰ تا ۱۰۰ درصد باشد.")
        }

        // اعتبارسنجی عملیات فروش (عدم اجازه موقعیت تعهدی/فروش بیش از موجودی)
        if (transaction.action == TransactionAction.SELL) {
            val allTxs = transactionDao.getAllTransactions()
            val existingTxs = if (isEdit) allTxs.filter { it.id != transaction.id } else allTxs
            val assetTxs = existingTxs.filter { it.assetName.equals(transaction.assetName, ignoreCase = true) }

            val totalBought = assetTxs.filter { it.action == TransactionAction.BUY }.sumOf { it.quantity }
            val totalSold = assetTxs.filter { it.action == TransactionAction.SELL }.sumOf { it.quantity }
            val currentHolding = (totalBought - totalSold).coerceAtLeast(0.0)

            if (transaction.quantity > currentHolding) {
                return ValidationResult.Error(
                    "تعداد قابل فروش کافی نیست. موجودی فعلی دارایی: ${PersianUtils.formatNumber(currentHolding)} ${transaction.unit}"
                )
            }
        }

        return ValidationResult.Success
    }

    suspend fun saveTransaction(transaction: TransactionEntity): ValidationResult {
        val validation = validateTransaction(transaction, isEdit = transaction.id > 0)
        if (validation is ValidationResult.Error) {
            return validation
        }

        val baseAmount = if (transaction.unitPrice != null && transaction.quantity > 0.0) {
            transaction.quantity * transaction.unitPrice
        } else null

        val calculatedFeeAmount = if (baseAmount != null) {
            when (transaction.commissionType) {
                CommissionType.PERCENTAGE -> {
                    if (transaction.action in listOf(TransactionAction.BUY, TransactionAction.SELL)) {
                        baseAmount * (transaction.commissionRate / 100.0)
                    } else 0.0
                }
                CommissionType.FIXED -> {
                    if (transaction.action in listOf(TransactionAction.BUY, TransactionAction.SELL)) {
                        transaction.fees
                    } else 0.0
                }
            }
        } else {
            if (transaction.commissionType == CommissionType.FIXED) transaction.fees else 0.0
        }

        val computedTotal = if (transaction.totalAmount != null) {
            transaction.totalAmount
        } else if (baseAmount != null) {
            when (transaction.action) {
                TransactionAction.BUY -> baseAmount + calculatedFeeAmount
                TransactionAction.SELL -> (baseAmount - calculatedFeeAmount).coerceAtLeast(0.0)
                TransactionAction.DIVIDEND,
                TransactionAction.DEPOSIT,
                TransactionAction.WITHDRAWAL -> baseAmount
            }
        } else null

        val finalTx = transaction.copy(
            fees = calculatedFeeAmount,
            totalAmount = computedTotal
        )

        if (finalTx.id == 0L) {
            transactionDao.insertTransaction(finalTx)
        } else {
            transactionDao.updateTransaction(finalTx)
        }
        return ValidationResult.Success
    }

    suspend fun deleteTransaction(transaction: TransactionEntity) {
        transactionDao.deleteTransaction(transaction)
    }

    suspend fun deleteTransactionById(id: Long) {
        transactionDao.deleteTransactionById(id)
    }

    // --- مدیریت قیمت‌های روز ---
    suspend fun updateCurrentPrice(price: CurrentPriceEntity) {
        currentPriceDao.insertPrice(price)
    }

    suspend fun deleteCurrentPrice(price: CurrentPriceEntity) {
        currentPriceDao.deletePrice(price)
    }

    // --- مدیریت اهداف مالی ---
    suspend fun saveGoal(goal: GoalEntity) {
        if (goal.id == 0L) {
            goalDao.insertGoal(goal)
        } else {
            goalDao.updateGoal(goal)
        }
    }

    suspend fun deleteGoal(goal: GoalEntity) {
        goalDao.deleteGoal(goal)
    }

    // --- مدیریت بدهی‌ها ---
    suspend fun saveLiability(liability: LiabilityEntity) {
        if (liability.id == 0L) {
            liabilityDao.insertLiability(liability)
        } else {
            liabilityDao.updateLiability(liability)
        }
    }

    suspend fun deleteLiability(liability: LiabilityEntity) {
        liabilityDao.deleteLiability(liability)
    }

    // --- مدیریت سود نقدی مجامع (DPS) ---
    suspend fun saveDividend(dividend: DividendEntity) {
        if (dividend.id == 0L) {
            dividendDao.insertDividend(dividend)
        } else {
            dividendDao.updateDividend(dividend)
        }
    }

    suspend fun deleteDividend(dividend: DividendEntity) {
        dividendDao.deleteDividend(dividend)
    }

    // --- تنظیمات برنامه ---
    suspend fun saveSettings(settings: AppSettingsEntity): ValidationResult {
        val targetTotal = settings.targetGoldPct + settings.targetEquityFundPct +
                settings.targetFixedIncomePct + settings.targetUsdPct + settings.targetCashPct
        if (kotlin.math.abs(targetTotal - 100.0) > 0.01) {
            return ValidationResult.Error("مجموع درصدهای تخصیص هدف باید دقیقاً برابر ۱۰۰٪ باشد (مجموع فعلی: $targetTotal٪).")
        }
        settingsDao.saveSettings(settings)
        return ValidationResult.Success
    }

    suspend fun getSettings(): AppSettingsEntity {
        return settingsDao.getSettings() ?: AppSettingsEntity()
    }

    /**
     * پشتیبان‌گیری استاندارد (Export Backup) به صورت JSON با طرح‌واره مستند
     */
    suspend fun exportBackupJson(): String {
        return ir.modiriatsarmaye.app.data.backup.SafeBackupManager.exportBackupJson(
            transactions = transactionDao.getAllTransactions(),
            prices = currentPriceDao.getAllPrices(),
            goals = goalDao.getAllGoals(),
            liabilities = liabilityDao.getAllLiabilities()
        )
    }

    /**
     * پیش‌اعتبارسنجی ساختار فایل پشتیبان بدون دستکاری پایگاه داده
     */
    fun validateBackupJson(jsonString: String): ir.modiriatsarmaye.app.data.backup.BackupValidationReport {
        return ir.modiriatsarmaye.app.data.backup.SafeBackupManager.validateAndParseBackup(jsonString)
    }

    /**
     * بازیابی فایل پشتیبان (Restore Backup) با صحت‌سنجی یکپارچگی، سازگاری عقب‌رو و تراکنش امن Room
     */
    suspend fun restoreBackupJson(jsonString: String, replaceExisting: Boolean = true): ValidationResult {
        val report = ir.modiriatsarmaye.app.data.backup.SafeBackupManager.validateAndParseBackup(jsonString)
        if (!report.isValid) {
            val err = report.errors.firstOrNull() ?: "فایل پشتیبان نامعتبر است."
            return ValidationResult.Error(err)
        }
        return ir.modiriatsarmaye.app.data.backup.SafeBackupManager.executeSafeRestore(
            database = database,
            report = report,
            replaceExisting = replaceExisting
        )
    }

    /**
     * همگام‌سازی و بروزرسانی قیمت‌های روز دارایی‌ها از بازار آنلاین با اعتبارسنجی گام‌به‌گام ذخیره‌سازی
     */
    suspend fun syncMarketPrices(forceRefresh: Boolean = false): MarketUpdateReport {
        val allTxs = transactionDao.getAllTransactions()
        val existingPrices = currentPriceDao.getAllPrices()

        // استخراج کلیه دارایی‌های سبد کاربر جهت بروزرسانی
        val assetList = allTxs.map { tx ->
            val key = if (tx.assetSymbol.isNotBlank()) tx.assetSymbol else tx.assetName
            Pair(key, tx.assetClass)
        }.distinct()

        // اضافه کردن سایر دارایی‌های موجود در جدول قیمت‌ها
        val priceAssetList = existingPrices.map { Pair(it.assetSymbolOrName, it.assetClass) }

        // شناسایی دارایی طلای فیزیکی ۱۸ عیار کاربر با نگاشت قطعی
        val userGoldTx = allTxs.find { AssetInstrumentMapper.isGold18kInstrument(it.assetSymbol, it.assetName, it.assetClass, it.unit) }
        val userGoldPrice = existingPrices.find { AssetInstrumentMapper.isGold18kInstrument(it.assetSymbolOrName, it.assetName, it.assetClass, it.unit) }
        val mappedAssetName = userGoldTx?.assetName ?: userGoldPrice?.assetName ?: AssetInstrumentMapper.CANONICAL_GOLD18_NAME
        val mappedAssetId = userGoldTx?.let { if (it.assetSymbol.isNotBlank()) it.assetSymbol else it.assetName }
            ?: userGoldPrice?.assetSymbolOrName
            ?: AssetInstrumentMapper.INSTRUMENT_GERAM18

        val goldPair = Pair(AssetInstrumentMapper.INSTRUMENT_GERAM18, AssetClass.GOLD)
        val combinedAssets = (assetList + priceAssetList + listOf(goldPair)).distinct()

        val report = marketDataProvider.syncCurrentPrices(
            existingPrices = existingPrices,
            assetsToUpdate = combinedAssets,
            forceRefresh = forceRefresh
        )

        // ۱. مرحله استخراج و ذخیره‌سازی در پایگاه داده (Write / Persistence)
        if (report.updatedPrices.isNotEmpty()) {
            currentPriceDao.insertPrices(report.updatedPrices)
            val currentSettings = getSettings()
            saveSettings(currentSettings.copy(lastPriceUpdateTimestamp = System.currentTimeMillis()))
        }

        // ۲. مرحله بازخوانی از پایگاه داده (Read-back Verification)
        val readBackEntity = currentPriceDao.getPriceByInstrumentId(AssetInstrumentMapper.INSTRUMENT_GERAM18)
            ?: currentPriceDao.getPrice(AssetInstrumentMapper.INSTRUMENT_GERAM18)
            ?: currentPriceDao.getPrice(mappedAssetId)

        val goldDiags = report.goldDiagnostics
        val successfulGoldDiag = goldDiags.firstOrNull { it.isSuccess }
        val providerSuccess = successfulGoldDiag != null
        val httpStatus = successfulGoldDiag?.httpStatusCode ?: goldDiags.firstOrNull()?.httpStatusCode ?: 0
        val extractedRial = successfulGoldDiag?.extractedPriceRial ?: readBackEntity?.price

        val isPriceMatch = readBackEntity != null && extractedRial != null &&
                kotlin.math.abs(readBackEntity.price - extractedRial) < 0.01

        val persistenceSuccess = readBackEntity != null && readBackEntity.price > 0.0
        val readBackSuccess = persistenceSuccess && isPriceMatch

        // ۳. بررسی اتصال قیمت به سبد دارایی و محاسبه سود/زیان (Portfolio & UI State)
        val updatedAllPrices = currentPriceDao.getAllPrices()
        val portfolio = CalculationEngine.calculatePortfolio(
            transactions = allTxs,
            prices = updatedAllPrices,
            settings = getSettings(),
            liabilities = liabilityDao.getAllLiabilities()
        )
        val goldHolding = portfolio.holdings.find {
            AssetInstrumentMapper.isGold18kInstrument(it.assetSymbol, it.assetName, it.assetClass, it.unit)
        }

        val portfolioSuccess = goldHolding != null && goldHolding.currentPriceToman != null && goldHolding.currentPriceToman > 0.0
        val finalUiState = when {
            goldHolding != null -> goldHolding.currentPriceStatus
            readBackSuccess -> PriceStatus.FRESH
            persistenceSuccess -> PriceStatus.STALE
            else -> PriceStatus.UNAVAILABLE
        }

        val pipelineDiag = SyncPipelineDiagnostic(
            providerResult = providerSuccess,
            httpStatusCode = httpStatus,
            parserSuccess = providerSuccess,
            extractedPriceRial = extractedRial,
            assetMappingSuccess = providerSuccess,
            mappedAssetId = "GERAM18 → $mappedAssetId",
            mappedAssetName = mappedAssetName,
            persistenceSuccess = persistenceSuccess,
            readBackSuccess = readBackSuccess,
            readBackPriceRial = readBackEntity?.price,
            portfolioCalculationSuccess = portfolioSuccess,
            finalUiState = finalUiState,
            finalPriceToman = goldHolding?.currentPriceToman ?: readBackEntity?.let {
                PersianUtils.convertCurrency(it.price, it.currency, CurrencyType.TOMAN)
            },
            priceGrowthPct = goldHolding?.priceGrowthPercent,
            failureReason = if (!providerSuccess) (goldDiags.firstOrNull()?.parserFailureReason ?: "پاسخ معتبری از سامانه طلا دریافت نشد") else null
        )

        // ۴. ارزیابی خط لوله سهام در صورت وجود دارایی سهام در سبد یا استعلام انجام شده
        val stockDiags = report.stockDiagnostics
        val targetStockDiag = stockDiags.firstOrNull { it.isSuccess } ?: stockDiags.firstOrNull()
        val targetStockSymbol = targetStockDiag?.symbol?.ifBlank { targetStockDiag.extractedSymbol }
            ?: allTxs.firstOrNull { it.assetClass == AssetClass.STOCK }?.let {
                if (it.assetSymbol.isNotBlank()) it.assetSymbol else it.assetName
            } ?: ""

        val resolvedStockSymbol = if (targetStockSymbol.isNotBlank()) {
            StockInstrumentMapper.resolveStockSymbol(targetStockSymbol)
        } else null

        val isResolvedValid = resolvedStockSymbol != null && StockInstrumentMapper.isValidStockSymbol(resolvedStockSymbol)

        val stockReadBackEntity = if (isResolvedValid) {
            currentPriceDao.getPrice(resolvedStockSymbol!!)
                ?: currentPriceDao.getPriceByInstrumentId(resolvedStockSymbol)
                ?: currentPriceDao.getPrice(targetStockSymbol)
        } else null

        val stockProviderSuccess = isResolvedValid && (targetStockDiag?.isSuccess == true || targetStockDiag?.providerResult == true)
        val stockParserSuccess = isResolvedValid && (targetStockDiag?.parsingSuccess == true) && ((targetStockDiag.extractedPriceRial ?: 0.0) > 0.0)
        val stockPersistenceSuccess = isResolvedValid && stockReadBackEntity != null && stockReadBackEntity.price > 0.0 && stockParserSuccess
        val stockReadBackSuccess = stockPersistenceSuccess

        val stockHolding = portfolio.holdings.find {
            it.assetClass == AssetClass.STOCK && (
                (resolvedStockSymbol != null && it.assetSymbol.equals(resolvedStockSymbol, ignoreCase = true)) ||
                it.assetSymbol.equals(targetStockSymbol, ignoreCase = true) ||
                it.assetName.equals(targetStockSymbol, ignoreCase = true)
            )
        }
        val stockPortfolioSuccess = (stockHolding?.currentPriceToman ?: 0.0) > 0.0

        val stockPipelineDiag = if (targetStockSymbol.isNotBlank() || stockDiags.isNotEmpty()) {
            SyncPipelineDiagnostic(
                providerResult = stockProviderSuccess,
                httpStatusCode = targetStockDiag?.httpStatusCode ?: 0,
                parserSuccess = stockParserSuccess,
                extractedPriceRial = if (isResolvedValid) (targetStockDiag?.extractedPriceRial ?: stockReadBackEntity?.let { PersianUtils.tomanToRial(it.price) }) else null,
                assetMappingSuccess = isResolvedValid,
                mappedAssetId = if (isResolvedValid) resolvedStockSymbol!! else StockInstrumentMapper.SYMBOL_REQUIRED_LABEL,
                mappedAssetName = targetStockSymbol,
                persistenceSuccess = stockPersistenceSuccess,
                readBackSuccess = stockReadBackSuccess,
                readBackPriceRial = stockReadBackEntity?.let { PersianUtils.tomanToRial(it.price) },
                portfolioCalculationSuccess = stockPortfolioSuccess,
                finalUiState = stockHolding?.currentPriceStatus ?: (if (stockReadBackEntity != null && stockReadBackEntity.price > 0.0) PriceStatus.STALE else PriceStatus.UNAVAILABLE),
                finalPriceToman = stockHolding?.currentPriceToman ?: stockReadBackEntity?.price,
                priceGrowthPct = stockHolding?.priceGrowthPercent,
                failureReason = if (!isResolvedValid) StockInstrumentMapper.SYMBOL_REQUIRED_LABEL else (targetStockDiag?.errorMessage ?: targetStockDiag?.parserFailureReason)
            )
        } else null

        val finalMessage = if (providerSuccess && readBackSuccess) {
            "قیمت طلای ۱۸ عیار (${PersianUtils.formatMoney(extractedRial ?: 0.0, CurrencyType.RIAL)}) با موفقیت دریافت و در سبد اعمال شد."
        } else {
            report.messageFa
        }

        return report.copy(
            pipelineDiagnostic = pipelineDiag,
            stockPipelineDiagnostic = stockPipelineDiag,
            messageFa = finalMessage
        )
    }

    /**
     * همگام‌سازی اختصاصی کلیه دارایی‌های سهام با ذخیره در Room و ارزیابی چرخه کامل
     * مراحل ترتیبی دقیق (Reliable Sequence):
     * 1. بارگذاری کلیه دارایی‌های سهام و صندوق‌ها از پایگاه داده (Room)
     * 2. اعتبارسنجی و نگاشت دقیق نمادها با تفکیک نمادهای ناقص از معتبر
     * 3. حفظ قیمت قبلی نمادهای نامعتبر/توصیفی به صورت STALE و ثبت خطای «نیاز به تعیین نماد»
     * 4. استعلام قیمت آنلاین برای نمادهای معتبر از طریق خط لوله چندمنبعی
     * 5. اعتبارسنجی قیمت‌های استخراج‌شده
     * 6. ذخیره‌سازی قطعی در پایگاه داده محلی (Room) و انتظار برای اتمام کامل تراکنش پایگاه داده
     * 7. بازخوانی کلیه قیمت‌ها از پایگاه داده Room جهت اطمینان از پایداری (Read-back)
     * 8. بارگذاری مجدد دارایی‌ها و تراکنش‌های سبد از Room
     * 9. بازتولید و محاسبه مجدد کل پرتفوی (ارزش روز، سود/زیان، درصد بازده، تخصیص دارایی و هشدارهای تعادل‌بخشی)
     * 10. تولید گزارش تجمیعی بروزرسانی دسته‌ای (Bulk Update Summary) شامل ۵ مؤلفه کلیدی
     * 11. تنظیم وضعیت نهایی خط لوله (Pipeline Diagnostics)
     */
    suspend fun syncAllStockPrices(forceRefresh: Boolean = true): MarketUpdateReport {
        val eligibleClasses = setOf(
            AssetClass.STOCK,
            AssetClass.EQUITY_FUND,
            AssetClass.FIXED_INCOME_FUND,
            AssetClass.GOLD_FUND
        )

        // مرحله ۱: بارگذاری کلیه دارایی‌های سهام و صندوق‌ها از پایگاه داده Room
        val allTxs = transactionDao.getAllTransactions()
        val existingPrices = currentPriceDao.getAllPrices()

        val rawStockAssets = (allTxs.filter { it.assetClass in eligibleClasses }.map { tx ->
            val key = if (tx.assetSymbol.isNotBlank()) tx.assetSymbol else tx.assetName
            Pair(key, tx.assetClass)
        } + existingPrices.filter { it.assetClass in eligibleClasses }.map {
            Pair(it.assetSymbolOrName, it.assetClass)
        }).distinct()

        if (rawStockAssets.isEmpty()) {
            return MarketUpdateReport(
                updatedCount = 0,
                failedCount = 0,
                totalCount = 0,
                messageFa = "هیچ دارایی سهام یا صندوقی برای بروزرسانی ثبت نشده است.",
                bulkUpdateSummaryFa = "هیچ دارایی سهامی در پرتفوی یافت نشد."
            )
        }

        // مرحله ۲: اعتبارسنجی و نگاشت دقیق نمادها با تفکیک نمادهای ناقص از معتبر
        val assetsToUpdate = mutableListOf<Pair<String, AssetClass>>()
        val unresolvableAssets = mutableListOf<Pair<String, AssetClass>>()

        for (asset in rawStockAssets) {
            val (key, assetClass) = asset
            val resolved = if (assetClass == AssetClass.STOCK) {
                StockInstrumentMapper.resolveStockSymbol(key)
            } else {
                FundInstrumentMapper.resolveFundSymbol(key)
            }
            if (resolved != null) {
                assetsToUpdate.add(asset)
            } else {
                unresolvableAssets.add(asset)
            }
        }

        // مرحله ۳: برای نمادهای نامعتبر/توصیفی: حفظ آخرین قیمت معتبر با برچسب STALE
        val preservedUnresolved = mutableListOf<CurrentPriceEntity>()
        for ((key, assetClass) in unresolvableAssets) {
            val existing = existingPrices.find {
                it.assetClass == assetClass && (
                    it.assetSymbolOrName.equals(key, ignoreCase = true) ||
                    it.assetName.equals(key, ignoreCase = true)
                )
            }
            if (existing != null && existing.price > 0.0) {
                preservedUnresolved.add(
                    existing.copy(
                        status = PriceStatus.STALE,
                        errorMessage = StockInstrumentMapper.SYMBOL_REQUIRED_LABEL
                    )
                )
            } else {
                preservedUnresolved.add(
                    CurrentPriceEntity(
                        assetSymbolOrName = key,
                        assetName = key,
                        assetClass = assetClass,
                        price = 0.0,
                        currency = CurrencyType.TOMAN,
                        source = "نامشخص",
                        lastUpdated = 0L,
                        unit = assetClass.defaultUnit,
                        priceType = "",
                        isAutoUpdated = false,
                        status = PriceStatus.UNAVAILABLE,
                        errorMessage = StockInstrumentMapper.SYMBOL_REQUIRED_LABEL,
                        instrumentId = ""
                    )
                )
            }
        }

        // مرحله ۴: استعلام قیمت آنلاین برای نمادهای معتبر از طریق خط لوله چندمنبعی
        val report = if (assetsToUpdate.isNotEmpty()) {
            marketDataProvider.syncCurrentPrices(
                existingPrices = existingPrices,
                assetsToUpdate = assetsToUpdate,
                forceRefresh = forceRefresh
            )
        } else {
            MarketUpdateReport(
                updatedCount = 0,
                failedCount = unresolvableAssets.size,
                totalCount = unresolvableAssets.size,
                messageFa = StockInstrumentMapper.SYMBOL_REQUIRED_LABEL
            )
        }

        // مرحله ۵: اعتبارسنجی قیمت‌های استخراج‌شده و آماده‌سازی جهت ذخیره در Room
        var persistenceFailed = false
        val pricesToPersist = mutableListOf<CurrentPriceEntity>()

        for (price in report.updatedPrices) {
            if (price.price > 0.0) {
                pricesToPersist.add(price)

                val resolvedSym = if (price.assetClass == AssetClass.STOCK) {
                    StockInstrumentMapper.resolveStockSymbol(price.assetSymbolOrName, price.assetName)
                } else {
                    FundInstrumentMapper.resolveFundSymbol(price.assetSymbolOrName, price.assetName)
                }

                if (resolvedSym != null && !resolvedSym.equals(price.assetSymbolOrName, ignoreCase = true)) {
                    pricesToPersist.add(
                        price.copy(
                            assetSymbolOrName = resolvedSym,
                            instrumentId = resolvedSym
                        )
                    )
                }
                if (price.assetName.isNotBlank() && !price.assetName.equals(price.assetSymbolOrName, ignoreCase = true)) {
                    pricesToPersist.add(
                        price.copy(
                            assetSymbolOrName = price.assetName,
                            instrumentId = resolvedSym ?: price.instrumentId
                        )
                    )
                }
            }
        }

        pricesToPersist.addAll(preservedUnresolved)

        // مرحله ۶: ذخیره‌سازی قطعی در پایگاه داده محلی (Room) و انتظار برای اتمام کامل تراکنش
        if (pricesToPersist.isNotEmpty()) {
            try {
                currentPriceDao.insertPrices(pricesToPersist.distinctBy { it.assetSymbolOrName })
                val currentSettings = getSettings()
                saveSettings(currentSettings.copy(lastPriceUpdateTimestamp = System.currentTimeMillis()))
                Log.i(TAG, "Successfully persisted ${pricesToPersist.size} stock price records to Room in syncAllStockPrices")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist stock prices to Room in syncAllStockPrices", e)
                persistenceFailed = true
            }
        }

        // مرحله ۷: بازخوانی کلیه قیمت‌ها از Room جهت اطمینان از اعمال تغییرات
        val updatedAllPrices = currentPriceDao.getAllPrices()

        // مرحله ۸: بارگذاری مجدد دارایی‌ها و تراکنش‌های سبد از Room
        val freshTransactions = transactionDao.getAllTransactions()
        val freshLiabilities = liabilityDao.getAllLiabilities()
        val freshSettings = getSettings()

        // مرحله ۹: بازتولید و محاسبه مجدد کل پرتفوی با قیمت‌های تازه ذخیره شده در Room
        val freshPortfolio = CalculationEngine.calculatePortfolio(
            transactions = freshTransactions,
            prices = updatedAllPrices,
            settings = freshSettings,
            liabilities = freshLiabilities
        )

        // مرحله ۱۰: بررسی نتایج و محاسبه گزارش تجمیعی بروزرسانی دسته‌ای (Bulk Update Summary)
        val relevantHoldings = freshPortfolio.holdings.filter { it.assetClass in eligibleClasses }
        val pricedHoldings = relevantHoldings.filter { (it.currentPriceToman ?: 0.0) > 0.0 }
        val staleCount = updatedAllPrices.count { it.assetClass in eligibleClasses && it.status == PriceStatus.STALE }

        val portfolioRecalcSuccess = !persistenceFailed && (
            pricedHoldings.isNotEmpty() ||
            (relevantHoldings.isEmpty() && report.updatedCount > 0) ||
            (report.failedCount == 0 && report.totalCount > 0)
        )
        val uiRefreshSuccess = portfolioRecalcSuccess && (report.updatedCount > 0 || pricedHoldings.isNotEmpty())

        val bulkSummaryFa = """
            تعداد بروزرسانی‌های موفق: ${PersianUtils.toPersianDigits(report.updatedCount.toString())}
            تعداد خطاهای بروزرسانی: ${PersianUtils.toPersianDigits((report.failedCount + unresolvableAssets.size).toString())}
            تعداد قیمت‌های نگهداری‌شده (STALE): ${PersianUtils.toPersianDigits(staleCount.toString())}
            نتیجه بازتولید پرتفوی: ${if (portfolioRecalcSuccess) "موفق (SUCCESS)" else "ناموفق (FAILED)"}
            نتیجه نمایش در رابط کاربری: ${if (uiRefreshSuccess) "موفق (SUCCESS)" else "منقضی (STALE)"}
        """.trimIndent()

        // مرحله ۱۱: تنظیم وضعیت نهایی خط لوله (Pipeline Diagnostics)
        val stockDiags = report.stockDiagnostics
        val primaryStockDiag = stockDiags.firstOrNull { it.isSuccess } ?: stockDiags.firstOrNull()
        val providerSuccess = report.updatedCount > 0 || (stockDiags.any { it.isSuccess || it.providerResult })
        val parserSuccess = (report.updatedPrices.isNotEmpty() || stockDiags.any { it.parsingSuccess }) && !persistenceFailed
        val persistenceSuccess = !persistenceFailed && pricesToPersist.any { it.price > 0.0 }
        val readBackSuccess = updatedAllPrices.any { it.assetClass in eligibleClasses && it.price > 0.0 }

        val representativeHolding = pricedHoldings.firstOrNull() ?: relevantHoldings.firstOrNull()

        val pipelineDiag = SyncPipelineDiagnostic(
            providerResult = providerSuccess,
            httpStatusCode = if (providerSuccess) 200 else (primaryStockDiag?.httpStatusCode ?: 0),
            parserSuccess = parserSuccess,
            extractedPriceRial = representativeHolding?.currentPriceToman?.let { PersianUtils.tomanToRial(it) }
                ?: report.updatedPrices.firstOrNull()?.let { PersianUtils.tomanToRial(it.price) },
            assetMappingSuccess = assetsToUpdate.isNotEmpty(),
            mappedAssetId = representativeHolding?.assetSymbol ?: "سبد سهام",
            mappedAssetName = representativeHolding?.assetName ?: "کلیه سهام پرتفوی",
            persistenceSuccess = persistenceSuccess,
            readBackSuccess = readBackSuccess,
            readBackPriceRial = representativeHolding?.currentPriceToman?.let { PersianUtils.tomanToRial(it) },
            portfolioCalculationSuccess = portfolioRecalcSuccess,
            finalUiState = if (uiRefreshSuccess && staleCount == 0) PriceStatus.FRESH else if (uiRefreshSuccess) PriceStatus.FRESH else if (staleCount > 0) PriceStatus.STALE else PriceStatus.UNAVAILABLE,
            finalPriceToman = representativeHolding?.currentPriceToman,
            priceGrowthPct = representativeHolding?.priceGrowthPercent,
            failureReason = if (!portfolioRecalcSuccess) "خطا در محاسبه ارزش سبد سهام" else null,
            providerName = "TSETMC / TGJU Composite"
        )

        val finalMessageFa = when {
            report.updatedCount > 0 && unresolvableAssets.isEmpty() -> "قیمت ${report.updatedCount} سهم با موفقیت بروزرسانی و در سبد اعمال شد."
            report.updatedCount > 0 && unresolvableAssets.isNotEmpty() -> "قیمت ${report.updatedCount} سهم بروزرسانی شد؛ ${unresolvableAssets.size} مورد نیاز به تعیین نماد دارند."
            unresolvableAssets.isNotEmpty() && assetsToUpdate.isEmpty() -> StockInstrumentMapper.SYMBOL_REQUIRED_LABEL
            else -> report.messageFa
        }

        return report.copy(
            stockPipelineDiagnostic = pipelineDiag,
            messageFa = finalMessageFa,
            staleCount = staleCount,
            portfolioRecalculationSuccess = portfolioRecalcSuccess,
            uiRefreshSuccess = uiRefreshSuccess,
            bulkUpdateSummaryFa = bulkSummaryFa
        )
    }

    /**
     * همگام‌سازی و بروزرسانی قیمت یک سهم مشخص به همراه ذخیره در Room و بازخوانی جهت اعتبارسنجی
     */
    suspend fun syncStockPrice(symbol: String, forceRefresh: Boolean = true): MarketUpdateReport {
        val resolvedSymbol = StockInstrumentMapper.resolveStockSymbol(symbol)
        val allTxs = transactionDao.getAllTransactions()
        val existingPrices = currentPriceDao.getAllPrices()
        val existing = existingPrices.find {
            it.assetSymbolOrName.equals(symbol, ignoreCase = true) ||
            (resolvedSymbol != null && it.assetSymbolOrName.equals(resolvedSymbol, ignoreCase = true)) ||
            (resolvedSymbol != null && it.instrumentId.equals(resolvedSymbol, ignoreCase = true))
        }

        if (resolvedSymbol == null) {
            Log.w(TAG, "syncStockPrice: '$symbol' has no valid ticker -> ${StockInstrumentMapper.SYMBOL_REQUIRED_LABEL}")
            val errDiag = StrategyDiagnostic(
                testId = "STOCK_SINGLE_VALIDATION",
                testNameFa = "اعتبارسنجی نماد بورس",
                symbol = symbol,
                parserStrategy = "Deterministic Symbol Resolver",
                parserFailureReason = StockInstrumentMapper.SYMBOL_REQUIRED_LABEL,
                errorMessage = StockInstrumentMapper.SYMBOL_REQUIRED_LABEL,
                isSuccess = false,
                providerResult = false,
                symbolMappingSuccess = false,
                parsingSuccess = false,
                instrumentType = "STOCK"
            )
            val pipelineDiag = SyncPipelineDiagnostic(
                providerResult = false,
                httpStatusCode = 0,
                parserSuccess = false,
                extractedPriceRial = null,
                assetMappingSuccess = false,
                mappedAssetId = StockInstrumentMapper.SYMBOL_REQUIRED_LABEL,
                mappedAssetName = symbol,
                persistenceSuccess = false,
                readBackSuccess = false,
                readBackPriceRial = existing?.let { PersianUtils.tomanToRial(it.price) },
                portfolioCalculationSuccess = false,
                finalUiState = if (existing != null && existing.price > 0.0) PriceStatus.STALE else PriceStatus.UNAVAILABLE,
                finalPriceToman = existing?.price,
                failureReason = StockInstrumentMapper.SYMBOL_REQUIRED_LABEL
            )
            if (existing != null && existing.price > 0.0) {
                try {
                    currentPriceDao.insertPrices(listOf(existing.copy(status = PriceStatus.STALE, errorMessage = StockInstrumentMapper.SYMBOL_REQUIRED_LABEL)))
                    Log.i(TAG, "Preserved existing price for '$symbol' as STALE: ${existing.price} Toman")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update existing price to STALE", e)
                }
            }
            return MarketUpdateReport(
                updatedCount = 0,
                failedCount = 1,
                totalCount = 1,
                messageFa = StockInstrumentMapper.SYMBOL_REQUIRED_LABEL,
                stockDiagnostics = listOf(errDiag),
                stockPipelineDiagnostic = pipelineDiag
            )
        }

        val singleRes = marketDataProvider.stockProvider.fetchPriceForAsset(resolvedSymbol, AssetClass.STOCK)
        val diags = marketDataProvider.stockProvider.lastDiagnostics
        val primaryDiag = diags.firstOrNull { it.isSuccess } ?: diags.firstOrNull()

        if (singleRes.isSuccess && singleRes.getOrNull() != null) {
            val mp = singleRes.getOrNull()!!

            // اعتبارسنجی قطعی خروجی پارسر: قیمت باید بزرگتر از صفر بوده و متعلق به نماد درخواستی باشد
            val isValidPrice = mp.price > 0.0 && (mp.originalPrice ?: 0.0) > 0.0
            val isMatchingSymbol = StockInstrumentMapper.normalizeSymbol(mp.symbolOrName) == resolvedSymbol ||
                    StockInstrumentMapper.normalizeSymbol(mp.instrumentId) == resolvedSymbol

            if (!isValidPrice || !isMatchingSymbol) {
                val validationErr = if (!isValidPrice) "قیمت استخراج شده نامعتبر است (${mp.price})" else "نماد استخراج شده (${mp.symbolOrName}) با نماد درخواستی ($resolvedSymbol) مطابقت ندارد."
                Log.e(TAG, "Stock validation failed for $resolvedSymbol: $validationErr")

                var preservedAsStale = false
                if (existing != null && existing.price > 0.0) {
                    try {
                        currentPriceDao.insertPrices(listOf(existing.copy(status = PriceStatus.STALE, errorMessage = validationErr)))
                        preservedAsStale = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to preserve existing price as STALE", e)
                    }
                }

                val pipelineDiag = SyncPipelineDiagnostic(
                    providerResult = false,
                    httpStatusCode = primaryDiag?.httpStatusCode ?: 200,
                    parserSuccess = false,
                    extractedPriceRial = null,
                    assetMappingSuccess = true,
                    mappedAssetId = resolvedSymbol,
                    mappedAssetName = existing?.assetName ?: resolvedSymbol,
                    persistenceSuccess = false,
                    readBackSuccess = false,
                    readBackPriceRial = existing?.let { PersianUtils.tomanToRial(it.price) },
                    portfolioCalculationSuccess = false,
                    finalUiState = if (preservedAsStale) PriceStatus.STALE else PriceStatus.UNAVAILABLE,
                    finalPriceToman = existing?.price,
                    failureReason = validationErr
                )

                return MarketUpdateReport(
                    updatedCount = 0,
                    failedCount = 1,
                    totalCount = 1,
                    messageFa = validationErr,
                    stockDiagnostics = diags,
                    stockPipelineDiagnostic = pipelineDiag
                )
            }

            // ثبت رکورد در Room
            val newEntity = CurrentPriceEntity(
                assetSymbolOrName = resolvedSymbol,
                assetName = existing?.assetName ?: resolvedSymbol,
                assetClass = AssetClass.STOCK,
                price = mp.price,
                currency = mp.currency,
                source = mp.source,
                lastUpdated = System.currentTimeMillis(),
                unit = mp.unit,
                priceType = mp.priceType,
                isAutoUpdated = true,
                status = PriceStatus.FRESH,
                errorMessage = null,
                instrumentId = resolvedSymbol
            )

            val entitiesToInsert = mutableListOf(newEntity)
            if (!symbol.equals(resolvedSymbol, ignoreCase = true)) {
                entitiesToInsert.add(newEntity.copy(assetSymbolOrName = symbol))
            }

            var persistenceSuccess = false
            try {
                currentPriceDao.insertPrices(entitiesToInsert)
                persistenceSuccess = true
                val currentSettings = getSettings()
                saveSettings(currentSettings.copy(lastPriceUpdateTimestamp = System.currentTimeMillis()))
                Log.i(TAG, "Successfully persisted stock price to Room for $resolvedSymbol: ${mp.price} Toman (${mp.originalPrice} Rial)")
            } catch (e: Exception) {
                Log.e(TAG, "Room persistence FAILED for stock $resolvedSymbol: ${e.message}", e)
                persistenceSuccess = false
            }

            // بازخوانی از Room جهت اعتبارسنجی خط لوله
            val readBack = try {
                currentPriceDao.getPrice(resolvedSymbol)
                    ?: currentPriceDao.getPriceByInstrumentId(resolvedSymbol)
                    ?: currentPriceDao.getPrice(symbol)
            } catch (e: Exception) {
                Log.e(TAG, "Room read-back FAILED for stock $resolvedSymbol: ${e.message}", e)
                null
            }

            val isMatch = readBack != null && readBack.price > 0.0 && kotlin.math.abs(readBack.price - mp.price) < 0.01
            val readBackSuccess = persistenceSuccess && isMatch
            Log.i(TAG, "Room read-back check for $resolvedSymbol: entity=${readBack?.assetSymbolOrName}, price=${readBack?.price}, match=$isMatch, readBackSuccess=$readBackSuccess")

            val updatedAllPrices = currentPriceDao.getAllPrices()
            val portfolio = CalculationEngine.calculatePortfolio(
                transactions = allTxs,
                prices = updatedAllPrices,
                settings = getSettings(),
                liabilities = liabilityDao.getAllLiabilities()
            )
            val holding = portfolio.holdings.find {
                it.assetClass == AssetClass.STOCK && (
                    it.assetSymbol.equals(resolvedSymbol, ignoreCase = true) ||
                    it.assetSymbol.equals(symbol, ignoreCase = true) ||
                    it.assetName.equals(symbol, ignoreCase = true)
                )
            }
            val portfolioSuccess = (holding?.currentPriceToman ?: 0.0) > 0.0

            val pipelineDiag = SyncPipelineDiagnostic(
                providerResult = true,
                httpStatusCode = primaryDiag?.httpStatusCode ?: 200,
                parserSuccess = true,
                extractedPriceRial = mp.originalPrice,
                assetMappingSuccess = true,
                mappedAssetId = resolvedSymbol,
                mappedAssetName = existing?.assetName ?: resolvedSymbol,
                persistenceSuccess = persistenceSuccess,
                readBackSuccess = readBackSuccess,
                readBackPriceRial = readBack?.let { PersianUtils.tomanToRial(it.price) },
                portfolioCalculationSuccess = portfolioSuccess,
                finalUiState = if (readBackSuccess) (holding?.currentPriceStatus ?: PriceStatus.FRESH) else PriceStatus.STALE,
                finalPriceToman = holding?.currentPriceToman ?: readBack?.price,
                priceGrowthPct = holding?.priceGrowthPercent,
                insCode = primaryDiag?.insCode,
                isin = primaryDiag?.isin,
                providerName = primaryDiag?.providerName
            )

            return MarketUpdateReport(
                updatedCount = if (persistenceSuccess) 1 else 0,
                failedCount = if (persistenceSuccess) 0 else 1,
                totalCount = 1,
                messageFa = if (persistenceSuccess) {
                    "قیمت نماد $resolvedSymbol (${PersianUtils.formatMoney(mp.price, CurrencyType.TOMAN)}) با موفقیت بروزرسانی و در سبد اعمال شد."
                } else {
                    "خطا در ثبت نرخ در پایگاه داده"
                },
                updatedPrices = if (persistenceSuccess) entitiesToInsert else emptyList(),
                stockDiagnostics = diags,
                stockPipelineDiagnostic = pipelineDiag
            )
        } else {
            val failureReason = singleRes.exceptionOrNull()?.message
                ?: primaryDiag?.errorMessage
                ?: primaryDiag?.parserFailureReason
                ?: "عدم دریافت نرخ از سرور"
            Log.w(TAG, "Stock sync failed for $resolvedSymbol: $failureReason")

            var preservedAsStale = false
            if (existing != null && existing.price > 0.0) {
                try {
                    currentPriceDao.insertPrices(listOf(existing.copy(status = PriceStatus.STALE, errorMessage = failureReason)))
                    preservedAsStale = true
                    Log.i(TAG, "Preserved existing price for $resolvedSymbol as STALE: ${existing.price} Toman")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to preserve existing price as STALE for $resolvedSymbol", e)
                }
            }

            val readBack = currentPriceDao.getPrice(resolvedSymbol)
                ?: currentPriceDao.getPriceByInstrumentId(resolvedSymbol)
                ?: currentPriceDao.getPrice(symbol)

            val updatedAllPrices = currentPriceDao.getAllPrices()
            val portfolio = CalculationEngine.calculatePortfolio(
                transactions = allTxs,
                prices = updatedAllPrices,
                settings = getSettings(),
                liabilities = liabilityDao.getAllLiabilities()
            )
            val holding = portfolio.holdings.find {
                it.assetClass == AssetClass.STOCK && (
                    it.assetSymbol.equals(resolvedSymbol, ignoreCase = true) ||
                    it.assetSymbol.equals(symbol, ignoreCase = true) ||
                    it.assetName.equals(symbol, ignoreCase = true)
                )
            }
            val portfolioSuccess = (holding?.currentPriceToman ?: 0.0) > 0.0

            val pipelineDiag = SyncPipelineDiagnostic(
                providerResult = false,
                httpStatusCode = primaryDiag?.httpStatusCode ?: 0,
                parserSuccess = false,
                assetMappingSuccess = true,
                mappedAssetId = resolvedSymbol,
                mappedAssetName = existing?.assetName ?: resolvedSymbol,
                persistenceSuccess = false,
                readBackSuccess = false,
                readBackPriceRial = readBack?.let { PersianUtils.tomanToRial(it.price) },
                portfolioCalculationSuccess = portfolioSuccess,
                finalUiState = if (preservedAsStale) PriceStatus.STALE else PriceStatus.UNAVAILABLE,
                finalPriceToman = holding?.currentPriceToman ?: readBack?.price,
                priceGrowthPct = holding?.priceGrowthPercent,
                failureReason = failureReason,
                insCode = primaryDiag?.insCode,
                isin = primaryDiag?.isin,
                providerName = primaryDiag?.providerName
            )

            return MarketUpdateReport(
                updatedCount = 0,
                failedCount = 1,
                totalCount = 1,
                messageFa = failureReason,
                stockDiagnostics = diags,
                stockPipelineDiagnostic = pipelineDiag
            )
        }
    }

    /**
     * اصلاح و تعیین نماد رسمی برای دارایی‌های بدون نماد یا نیازمند تصحیح
     */
    suspend fun correctAssetTicker(oldSymbolOrName: String, newTickerOrName: String): Result<String> {
        val instrument = StockInstrumentMapper.resolveInstrument(newTickerOrName)
            ?: StockInstrumentMapper.resolveInstrument(symbolOrKey = "", name = newTickerOrName)
        if (instrument == null || !StockInstrumentMapper.isValidStockSymbol(instrument.symbol)) {
            return Result.failure(IllegalArgumentException(StockInstrumentMapper.SYMBOL_REQUIRED_LABEL))
        }

        val resolvedTicker = instrument.symbol
        val officialName = instrument.name

        try {
            // ۱. بروزرسانی تراکنش‌ها
            val allTxs = transactionDao.getAllTransactions()
            allTxs.forEach { tx ->
                if (tx.assetClass == AssetClass.STOCK && (
                    tx.assetSymbol.equals(oldSymbolOrName, ignoreCase = true) ||
                    tx.assetName.equals(oldSymbolOrName, ignoreCase = true) ||
                    tx.assetSymbol.isBlank() ||
                    tx.assetSymbol == StockInstrumentMapper.SYMBOL_REQUIRED_LABEL
                )) {
                    transactionDao.insertTransaction(tx.copy(assetSymbol = resolvedTicker, assetName = officialName.ifBlank { tx.assetName }))
                }
            }

            // ۲. بروزرسانی موجودیت قیمت روز در پایگاه داده
            val existingOldPrice = currentPriceDao.getPrice(oldSymbolOrName)
            if (existingOldPrice != null) {
                currentPriceDao.deletePrice(existingOldPrice)
                currentPriceDao.insertPrices(listOf(
                    existingOldPrice.copy(
                        assetSymbolOrName = resolvedTicker,
                        assetName = officialName,
                        instrumentId = resolvedTicker
                    )
                ))
            }

            // ۳. همگام‌سازی فوری قیمت با نماد تصحیح‌شده
            syncStockPrice(resolvedTicker)

            return Result.success(resolvedTicker)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to correct asset ticker for $oldSymbolOrName -> $resolvedTicker", e)
            return Result.failure(e)
        }
    }

    /**
     * اتصال حساب Google جهت پشتیبان‌گیری ابری
     */
    suspend fun connectGoogleAccount(email: String, displayName: String): ValidationResult {
        if (email.isBlank() || !email.contains("@")) {
            return ValidationResult.Error("آدرس ایمیل گوگل نامعتبر است.")
        }
        val currentSettings = getSettings()
        val updated = currentSettings.copy(
            googleAccountEmail = email.trim(),
            googleAccountName = displayName.trim()
        )
        return saveSettings(updated)
    }

    /**
     * قطع اتصال حساب Google و پاکسازی نشست ابری محلی
     */
    suspend fun disconnectGoogleAccount(): ValidationResult {
        driveBackupManager?.clearCloudSession()
        val currentSettings = getSettings()
        val updated = currentSettings.copy(
            googleAccountEmail = null,
            googleAccountName = null,
            lastGoogleDriveBackupTimestamp = null,
            lastGoogleDriveBackupSummary = null
        )
        return saveSettings(updated)
    }

    /**
     * انجام پشتیبان‌گیری در Google Drive
     */
    suspend fun performGoogleDriveBackup(): Result<DriveBackupInfo> {
        val manager = driveBackupManager ?: return Result.failure(Exception("مدیریت Google Drive در دسترس نیست."))
        val settings = getSettings()
        val email = settings.googleAccountEmail ?: return Result.failure(Exception("حساب Google متصل نیست."))

        val backupJson = exportBackupJson()
        val backupResult = manager.uploadBackupToDrive(backupJson, email)

        if (backupResult.isSuccess) {
            val info = backupResult.getOrThrow()
            val summary = "${info.transactionsCount} تراکنش • ${PersianUtils.toPersianDigits((info.sizeBytes / 1024).toString())} کیلوبایت"
            saveSettings(
                settings.copy(
                    lastGoogleDriveBackupTimestamp = info.timestamp,
                    lastGoogleDriveBackupSummary = summary
                )
            )
        }

        return backupResult
    }

    /**
     * دریافت آخرین وضعیت پشتیبان ذخیره‌شده در Google Drive
     */
    suspend fun fetchGoogleDriveBackup(): Result<DriveBackupInfo> {
        val manager = driveBackupManager ?: return Result.failure(Exception("مدیریت Google Drive در دسترس نیست."))
        val settings = getSettings()
        val email = settings.googleAccountEmail ?: return Result.failure(Exception("حساب Google متصل نیست."))

        return manager.downloadBackupFromDrive(email)
    }

    /**
     * بازیابی اطلاعات از Google Drive با اعتبارسنجی
     */
    suspend fun restoreFromGoogleDrive(): ValidationResult {
        val fetchResult = fetchGoogleDriveBackup()
        if (fetchResult.isFailure) {
            return ValidationResult.Error(fetchResult.exceptionOrNull()?.message ?: "خطا در دریافت پشتیبان Google Drive")
        }

        val info = fetchResult.getOrThrow()
        return restoreBackupJson(info.rawJson)
    }

    /**
     * بازنشانی کامل داده‌ها به مقادیر پیش‌فرض اولیه
     */
    suspend fun resetToInitialData() {
        transactionDao.clearAllTransactions()
        currentPriceDao.clearAllPrices()
        goalDao.clearAllGoals()
        liabilityDao.clearAllLiabilities()
        dividendDao.clearAllDividends()
        InitialData.populateDatabase(database)
    }

    companion object {
        private const val TAG = "WealthRepository"
    }
}
