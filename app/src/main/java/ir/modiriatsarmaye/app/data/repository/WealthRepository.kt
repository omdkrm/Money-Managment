package ir.modiriatsarmaye.app.data.repository

import android.content.Context
import ir.modiriatsarmaye.app.data.cloud.DriveBackupInfo
import ir.modiriatsarmaye.app.data.cloud.GoogleAccountInfo
import ir.modiriatsarmaye.app.data.cloud.GoogleDriveBackupManager
import ir.modiriatsarmaye.app.data.local.*
import ir.modiriatsarmaye.app.data.market.AssetInstrumentMapper
import ir.modiriatsarmaye.app.data.market.CompositeMarketDataProvider
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
    object Success : ValidationResult()
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
     * پشتیبان‌گیری استاندارد (Export Backup) به صورت JSON
     */
    suspend fun exportBackupJson(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("timestamp", System.currentTimeMillis())
        root.put("appName", "مدیریت سرمایه")

        // تراکنش‌ها
        val txList = transactionDao.getAllTransactions()
        val txArray = JSONArray()
        for (tx in txList) {
            val obj = JSONObject().apply {
                put("id", tx.id)
                put("datePersian", tx.datePersian)
                put("timestamp", tx.timestamp)
                put("assetClass", tx.assetClass.name)
                put("assetName", tx.assetName)
                put("assetSymbol", tx.assetSymbol)
                put("action", tx.action.name)
                put("quantity", tx.quantity)
                put("unit", tx.unit)
                if (tx.unitPrice != null) put("unitPrice", tx.unitPrice)
                put("currency", tx.currency.name)
                put("fees", tx.fees)
                put("commissionType", tx.commissionType.name)
                put("commissionRate", tx.commissionRate)
                if (tx.totalAmount != null) put("totalAmount", tx.totalAmount)
                put("brokerOrSource", tx.brokerOrSource)
                put("notes", tx.notes)
            }
            txArray.put(obj)
        }
        root.put("transactions", txArray)

        // قیمت‌ها
        val prices = currentPriceDao.getAllPrices()
        val priceArray = JSONArray()
        for (p in prices) {
            val obj = JSONObject().apply {
                put("assetSymbolOrName", p.assetSymbolOrName)
                put("assetName", p.assetName)
                put("assetClass", p.assetClass.name)
                put("price", p.price)
                put("currency", p.currency.name)
                put("source", p.source)
                put("lastUpdated", p.lastUpdated)
            }
            priceArray.put(obj)
        }
        root.put("prices", priceArray)

        // اهداف
        val goals = goalDao.getAllGoals()
        val goalArray = JSONArray()
        for (g in goals) {
            val obj = JSONObject().apply {
                put("title", g.title)
                put("targetAmountToman", g.targetAmountToman)
                put("targetDatePersian", g.targetDatePersian)
                put("category", g.category)
                put("notes", g.notes)
            }
            goalArray.put(obj)
        }
        root.put("goals", goalArray)

        // بدهی‌ها
        val liabilities = liabilityDao.getAllLiabilities()
        val liabArray = JSONArray()
        for (l in liabilities) {
            val obj = JSONObject().apply {
                put("title", l.title)
                put("totalAmountToman", l.totalAmountToman)
                put("monthlyPaymentToman", l.monthlyPaymentToman)
                put("dueDatePersian", l.dueDatePersian)
                put("notes", l.notes)
            }
            liabArray.put(obj)
        }
        root.put("liabilities", liabArray)

        return root.toString(2)
    }

    /**
     * بازیابی فایل پشتیبان (Restore Backup) با صحت‌سنجی یکپارچگی
     */
    suspend fun restoreBackupJson(jsonString: String): ValidationResult {
        return try {
            val root = JSONObject(jsonString)
            if (!root.has("transactions")) {
                return ValidationResult.Error("فایل پشتیبان نامعتبر است: ساختار تراکنش‌ها یافت نشد.")
            }

            val txArray = root.getJSONArray("transactions")
            val newTransactions = mutableListOf<TransactionEntity>()
            for (i in 0 until txArray.length()) {
                val obj = txArray.getJSONObject(i)
                newTransactions.add(
                    TransactionEntity(
                        id = 0, // شناسه جدید تخصیص داده می‌شود
                        datePersian = obj.getString("datePersian"),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        assetClass = AssetClass.valueOf(obj.getString("assetClass")),
                        assetName = obj.getString("assetName"),
                        assetSymbol = obj.optString("assetSymbol", ""),
                        action = TransactionAction.valueOf(obj.getString("action")),
                        quantity = obj.getDouble("quantity"),
                        unit = obj.getString("unit"),
                        unitPrice = if (obj.has("unitPrice")) obj.getDouble("unitPrice") else null,
                        currency = CurrencyType.valueOf(obj.optString("currency", "TOMAN")),
                        fees = obj.optDouble("fees", 0.0),
                        commissionType = if (obj.has("commissionType")) CommissionType.valueOf(obj.getString("commissionType")) else CommissionType.FIXED,
                        commissionRate = obj.optDouble("commissionRate", obj.optDouble("fees", 0.0)),
                        totalAmount = if (obj.has("totalAmount")) obj.getDouble("totalAmount") else null,
                        brokerOrSource = obj.optString("brokerOrSource", ""),
                        notes = obj.optString("notes", "")
                    )
                )
            }

            // قیمت‌ها
            val newPrices = mutableListOf<CurrentPriceEntity>()
            if (root.has("prices")) {
                val priceArray = root.getJSONArray("prices")
                for (i in 0 until priceArray.length()) {
                    val obj = priceArray.getJSONObject(i)
                    newPrices.add(
                        CurrentPriceEntity(
                            assetSymbolOrName = obj.getString("assetSymbolOrName"),
                            assetName = obj.getString("assetName"),
                            assetClass = AssetClass.valueOf(obj.getString("assetClass")),
                            price = obj.getDouble("price"),
                            currency = CurrencyType.valueOf(obj.optString("currency", "TOMAN")),
                            source = obj.optString("source", "بازیابی پشتیبان"),
                            lastUpdated = obj.optLong("lastUpdated", System.currentTimeMillis())
                        )
                    )
                }
            }

            // اعمال با تراکنش امن
            transactionDao.clearAllTransactions()
            transactionDao.insertTransactions(newTransactions)

            if (newPrices.isNotEmpty()) {
                currentPriceDao.clearAllPrices()
                currentPriceDao.insertPrices(newPrices)
            }

            ValidationResult.Success
        } catch (e: Exception) {
            ValidationResult.Error("خطا در بازیابی فایل پشتیبان: ${e.localizedMessage ?: "فرمت نامعتبر"}")
        }
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

        val stockReadBackEntity = if (!resolvedStockSymbol.isNullOrBlank()) {
            currentPriceDao.getPrice(resolvedStockSymbol)
                ?: currentPriceDao.getPriceByInstrumentId(resolvedStockSymbol)
                ?: currentPriceDao.getPrice(targetStockSymbol)
        } else null

        val stockProviderSuccess = targetStockDiag?.isSuccess == true || (targetStockDiag?.providerResult == true)
        val stockPersistenceSuccess = stockReadBackEntity != null && stockReadBackEntity.price > 0.0
        val stockReadBackSuccess = stockPersistenceSuccess

        val stockHolding = portfolio.holdings.find {
            it.assetClass == AssetClass.STOCK && (
                (resolvedStockSymbol != null && it.assetSymbol.equals(resolvedStockSymbol, ignoreCase = true)) ||
                it.assetSymbol.equals(targetStockSymbol, ignoreCase = true) ||
                it.assetName.equals(targetStockSymbol, ignoreCase = true)
            )
        }
        val stockPortfolioSuccess = stockHolding != null && stockHolding.currentPriceToman != null && stockHolding.currentPriceToman > 0.0

        val stockPipelineDiag = if (targetStockSymbol.isNotBlank() || stockDiags.isNotEmpty()) {
            SyncPipelineDiagnostic(
                providerResult = stockProviderSuccess,
                httpStatusCode = targetStockDiag?.httpStatusCode ?: 0,
                parserSuccess = targetStockDiag?.parsingSuccess ?: stockProviderSuccess,
                extractedPriceRial = targetStockDiag?.extractedPriceRial ?: stockReadBackEntity?.let { PersianUtils.tomanToRial(it.price) },
                assetMappingSuccess = resolvedStockSymbol != null,
                mappedAssetId = resolvedStockSymbol ?: StockInstrumentMapper.SYMBOL_REQUIRED_LABEL,
                mappedAssetName = targetStockSymbol,
                persistenceSuccess = stockPersistenceSuccess,
                readBackSuccess = stockReadBackSuccess,
                readBackPriceRial = stockReadBackEntity?.let { PersianUtils.tomanToRial(it.price) },
                portfolioCalculationSuccess = stockPortfolioSuccess,
                finalUiState = stockHolding?.currentPriceStatus ?: (if (stockPersistenceSuccess) PriceStatus.STALE else PriceStatus.UNAVAILABLE),
                finalPriceToman = stockHolding?.currentPriceToman ?: stockReadBackEntity?.price,
                priceGrowthPct = stockHolding?.priceGrowthPercent,
                failureReason = targetStockDiag?.errorMessage ?: targetStockDiag?.parserFailureReason
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
     */
    suspend fun syncAllStockPrices(forceRefresh: Boolean = true): MarketUpdateReport {
        val allTxs = transactionDao.getAllTransactions()
        val existingPrices = currentPriceDao.getAllPrices()
        val stockAssets = (allTxs.filter { it.assetClass == AssetClass.STOCK }.map { tx ->
            val key = if (tx.assetSymbol.isNotBlank()) tx.assetSymbol else tx.assetName
            Pair(key, AssetClass.STOCK)
        } + existingPrices.filter { it.assetClass == AssetClass.STOCK }.map {
            Pair(it.assetSymbolOrName, AssetClass.STOCK)
        }).distinct()

        if (stockAssets.isEmpty()) {
            return MarketUpdateReport(
                updatedCount = 0,
                failedCount = 0,
                totalCount = 0,
                messageFa = "هیچ دارایی سهامی برای بروزرسانی ثبت نشده است."
            )
        }

        val report = marketDataProvider.syncCurrentPrices(
            existingPrices = existingPrices,
            assetsToUpdate = stockAssets,
            forceRefresh = forceRefresh
        )

        if (report.updatedPrices.isNotEmpty()) {
            currentPriceDao.insertPrices(report.updatedPrices)
            val currentSettings = getSettings()
            saveSettings(currentSettings.copy(lastPriceUpdateTimestamp = System.currentTimeMillis()))
        }

        val updatedAllPrices = currentPriceDao.getAllPrices()
        val portfolio = CalculationEngine.calculatePortfolio(
            transactions = allTxs,
            prices = updatedAllPrices,
            settings = getSettings(),
            liabilities = liabilityDao.getAllLiabilities()
        )

        val stockDiags = report.stockDiagnostics
        val primaryStockDiag = stockDiags.firstOrNull { it.isSuccess } ?: stockDiags.firstOrNull()
        val targetSymbol = primaryStockDiag?.symbol?.ifBlank { primaryStockDiag.extractedSymbol }
            ?: stockAssets.firstOrNull()?.first ?: ""
        val resolvedSymbol = StockInstrumentMapper.resolveStockSymbol(targetSymbol)

        val readBackEntity = if (!resolvedSymbol.isNullOrBlank()) {
            currentPriceDao.getPrice(resolvedSymbol) ?: currentPriceDao.getPriceByInstrumentId(resolvedSymbol)
        } else null

        val providerSuccess = primaryStockDiag?.isSuccess == true || (primaryStockDiag?.providerResult == true)
        val persistenceSuccess = readBackEntity != null && readBackEntity.price > 0.0
        val readBackSuccess = persistenceSuccess
        val stockHolding = portfolio.holdings.find {
            it.assetClass == AssetClass.STOCK && (
                (resolvedSymbol != null && it.assetSymbol.equals(resolvedSymbol, ignoreCase = true)) ||
                it.assetSymbol.equals(targetSymbol, ignoreCase = true)
            )
        }
        val portfolioSuccess = stockHolding != null && stockHolding.currentPriceToman != null && stockHolding.currentPriceToman > 0.0

        val pipelineDiag = SyncPipelineDiagnostic(
            providerResult = providerSuccess,
            httpStatusCode = primaryStockDiag?.httpStatusCode ?: 0,
            parserSuccess = primaryStockDiag?.parsingSuccess ?: providerSuccess,
            extractedPriceRial = primaryStockDiag?.extractedPriceRial ?: readBackEntity?.let { PersianUtils.tomanToRial(it.price) },
            assetMappingSuccess = resolvedSymbol != null,
            mappedAssetId = resolvedSymbol ?: StockInstrumentMapper.SYMBOL_REQUIRED_LABEL,
            mappedAssetName = targetSymbol,
            persistenceSuccess = persistenceSuccess,
            readBackSuccess = readBackSuccess,
            readBackPriceRial = readBackEntity?.let { PersianUtils.tomanToRial(it.price) },
            portfolioCalculationSuccess = portfolioSuccess,
            finalUiState = stockHolding?.currentPriceStatus ?: (if (persistenceSuccess) PriceStatus.STALE else PriceStatus.UNAVAILABLE),
            finalPriceToman = stockHolding?.currentPriceToman ?: readBackEntity?.price,
            priceGrowthPct = stockHolding?.priceGrowthPercent,
            failureReason = primaryStockDiag?.errorMessage ?: primaryStockDiag?.parserFailureReason
        )

        return report.copy(
            stockPipelineDiagnostic = pipelineDiag,
            messageFa = if (providerSuccess) "قیمت سهام با موفقیت بروزرسانی شد." else report.messageFa
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
            (resolvedSymbol != null && it.assetSymbolOrName.equals(resolvedSymbol, ignoreCase = true))
        }

        if (resolvedSymbol == null) {
            val errDiag = StrategyDiagnostic(
                testId = "STOCK_SINGLE_VALIDATION",
                testNameFa = "اعتبارسنجی نماد بورس",
                symbol = symbol,
                parserStrategy = "Deterministic Symbol Resolver",
                parserFailureReason = StockInstrumentMapper.SYMBOL_REQUIRED_LABEL,
                errorMessage = StockInstrumentMapper.SYMBOL_REQUIRED_LABEL,
                isSuccess = false,
                instrumentType = "STOCK"
            )
            val pipelineDiag = SyncPipelineDiagnostic(
                providerResult = false,
                assetMappingSuccess = false,
                mappedAssetId = StockInstrumentMapper.SYMBOL_REQUIRED_LABEL,
                mappedAssetName = symbol,
                finalUiState = PriceStatus.UNAVAILABLE,
                failureReason = StockInstrumentMapper.SYMBOL_REQUIRED_LABEL
            )
            if (existing != null && existing.price > 0.0) {
                currentPriceDao.insertPrices(listOf(existing.copy(status = PriceStatus.STALE, errorMessage = StockInstrumentMapper.SYMBOL_REQUIRED_LABEL)))
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
            currentPriceDao.insertPrices(listOf(newEntity))
            val currentSettings = getSettings()
            saveSettings(currentSettings.copy(lastPriceUpdateTimestamp = System.currentTimeMillis()))

            val readBack = currentPriceDao.getPrice(resolvedSymbol) ?: currentPriceDao.getPriceByInstrumentId(resolvedSymbol)
            val persistenceSuccess = readBack != null && readBack.price > 0.0
            val isMatch = readBack != null && kotlin.math.abs(readBack.price - mp.price) < 0.01
            val readBackSuccess = persistenceSuccess && isMatch

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
                    it.assetName.equals(symbol, ignoreCase = true)
                )
            }
            val portfolioSuccess = holding != null && holding.currentPriceToman != null && holding.currentPriceToman > 0.0

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
                finalUiState = holding?.currentPriceStatus ?: PriceStatus.FRESH,
                finalPriceToman = holding?.currentPriceToman ?: readBack?.price,
                priceGrowthPct = holding?.priceGrowthPercent
            )

            return MarketUpdateReport(
                updatedCount = 1,
                failedCount = 0,
                totalCount = 1,
                messageFa = "قیمت نماد $resolvedSymbol (${PersianUtils.formatMoney(mp.price, CurrencyType.TOMAN)}) با موفقیت بروزرسانی و در سبد اعمال شد.",
                updatedPrices = listOf(newEntity),
                stockDiagnostics = diags,
                stockPipelineDiagnostic = pipelineDiag
            )
        } else {
            val failureReason = singleRes.exceptionOrNull()?.message
                ?: primaryDiag?.errorMessage
                ?: primaryDiag?.parserFailureReason
                ?: "عدم دریافت نرخ از سرور"

            if (existing != null && existing.price > 0.0) {
                currentPriceDao.insertPrices(listOf(existing.copy(status = PriceStatus.STALE, errorMessage = failureReason)))
            }

            val pipelineDiag = SyncPipelineDiagnostic(
                providerResult = false,
                httpStatusCode = primaryDiag?.httpStatusCode ?: 0,
                parserSuccess = false,
                assetMappingSuccess = true,
                mappedAssetId = resolvedSymbol,
                mappedAssetName = existing?.assetName ?: resolvedSymbol,
                persistenceSuccess = existing != null && existing.price > 0.0,
                readBackSuccess = false,
                readBackPriceRial = existing?.let { PersianUtils.tomanToRial(it.price) },
                portfolioCalculationSuccess = false,
                finalUiState = if (existing != null && existing.price > 0.0) PriceStatus.STALE else PriceStatus.UNAVAILABLE,
                finalPriceToman = existing?.price,
                failureReason = failureReason
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
}
