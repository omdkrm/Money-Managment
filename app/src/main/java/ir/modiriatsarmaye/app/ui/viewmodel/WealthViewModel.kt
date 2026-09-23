package ir.modiriatsarmaye.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ir.modiriatsarmaye.app.data.cloud.DriveBackupInfo
import ir.modiriatsarmaye.app.data.market.MarketUpdateReport
import ir.modiriatsarmaye.app.data.model.*
import ir.modiriatsarmaye.app.data.repository.ValidationResult
import ir.modiriatsarmaye.app.data.repository.WealthRepository
import ir.modiriatsarmaye.app.util.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class WealthUiState(
    val transactions: List<TransactionEntity> = emptyList(),
    val filteredTransactions: List<TransactionEntity> = emptyList(),
    val prices: List<CurrentPriceEntity> = emptyList(),
    val goals: List<GoalEntity> = emptyList(),
    val liabilities: List<LiabilityEntity> = emptyList(),
    val dividends: List<DividendEntity> = emptyList(),
    val settings: AppSettingsEntity = AppSettingsEntity(),
    val portfolioSummary: PortfolioSummary = CalculationEngine.calculatePortfolio(emptyList(), emptyList(), AppSettingsEntity(), emptyList()),
    val cashFlowSummary: CashFlowSummary = CalculationEngine.calculateCashFlow(emptyList(), 10_000_000.0),
    val retirementScenarios: List<RetirementScenarioResult> = emptyList(),
    val selectedAssetClassFilter: AssetClass? = null,
    val selectedActionFilter: TransactionAction? = null,
    val searchQuery: String = "",
    val isAppLocked: Boolean = false,
    val isMarketUpdating: Boolean = false,
    val lastMarketUpdateReport: MarketUpdateReport? = null,
    val cloudBackupStatus: CloudBackupStatus = CloudBackupStatus.IDLE,
    val userErrorMessage: String? = null,
    val userSuccessMessage: String? = null
)

class WealthViewModel(
    private val repository: WealthRepository
) : ViewModel() {

    private val _selectedAssetClassFilter = MutableStateFlow<AssetClass?>(null)
    private val _selectedActionFilter = MutableStateFlow<TransactionAction?>(null)
    private val _searchQuery = MutableStateFlow("")
    private val _isAppLocked = MutableStateFlow(false)
    private val _isMarketUpdating = MutableStateFlow(false)
    private val _lastMarketUpdateReport = MutableStateFlow<MarketUpdateReport?>(null)
    private val _cloudBackupStatus = MutableStateFlow(CloudBackupStatus.IDLE)
    private val _userErrorMessage = MutableStateFlow<String?>(null)
    private val _userSuccessMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<WealthUiState> = combine(
        repository.allTransactionsFlow,
        repository.allPricesFlow,
        repository.allGoalsFlow,
        repository.allLiabilitiesFlow,
        repository.allDividendsFlow,
        repository.settingsFlow.map { it ?: AppSettingsEntity() },
        _selectedAssetClassFilter,
        _selectedActionFilter,
        _searchQuery,
        _isAppLocked,
        _isMarketUpdating,
        _lastMarketUpdateReport,
        _cloudBackupStatus,
        _userErrorMessage,
        _userSuccessMessage
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val transactions = args[0] as List<TransactionEntity>
        @Suppress("UNCHECKED_CAST")
        val prices = args[1] as List<CurrentPriceEntity>
        @Suppress("UNCHECKED_CAST")
        val goals = args[2] as List<GoalEntity>
        @Suppress("UNCHECKED_CAST")
        val liabilities = args[3] as List<LiabilityEntity>
        @Suppress("UNCHECKED_CAST")
        val dividends = args[4] as List<DividendEntity>
        val settings = args[5] as AppSettingsEntity
        val filterClass = args[6] as AssetClass?
        val filterAction = args[7] as TransactionAction?
        val query = args[8] as String
        val isLocked = args[9] as Boolean
        val isUpdating = args[10] as Boolean
        val updateReport = args[11] as MarketUpdateReport?
        val cloudStatus = args[12] as CloudBackupStatus
        val errMsg = args[13] as String?
        val succMsg = args[14] as String?

        // محاسبه وضعیت پرتفوی به صورت خودکار و واکنشی
        val portfolio = CalculationEngine.calculatePortfolio(transactions, prices, settings, liabilities)
        val cashFlow = CalculationEngine.calculateCashFlow(transactions, settings.monthlyInvestmentToman)

        // شبیه‌سازی‌های سه‌گانه بازنشستگی
        val initialCap = portfolio.totalPortfolioValueToman
        val simConservative = CalculationEngine.simulateRetirement(
            initialCapitalToman = initialCap,
            monthlyInvestmentToman = settings.monthlyInvestmentToman,
            annualIncreasePct = settings.annualInvestmentIncreasePct,
            inflationRatePct = settings.inflationRatePct,
            years = settings.retirementHorizonYears,
            returnRatePct = settings.scenarioConservativeReturnPct,
            scenarioName = "محافظه‌کارانه (${PersianUtils.toPersianDigits(settings.scenarioConservativeReturnPct.toInt().toString())}٪)"
        )
        val simBalanced = CalculationEngine.simulateRetirement(
            initialCapitalToman = initialCap,
            monthlyInvestmentToman = settings.monthlyInvestmentToman,
            annualIncreasePct = settings.annualInvestmentIncreasePct,
            inflationRatePct = settings.inflationRatePct,
            years = settings.retirementHorizonYears,
            returnRatePct = settings.scenarioBalancedReturnPct,
            scenarioName = "متعادل (${PersianUtils.toPersianDigits(settings.scenarioBalancedReturnPct.toInt().toString())}٪)"
        )
        val simOptimistic = CalculationEngine.simulateRetirement(
            initialCapitalToman = initialCap,
            monthlyInvestmentToman = settings.monthlyInvestmentToman,
            annualIncreasePct = settings.annualInvestmentIncreasePct,
            inflationRatePct = settings.inflationRatePct,
            years = settings.retirementHorizonYears,
            returnRatePct = settings.scenarioOptimisticReturnPct,
            scenarioName = "خوش‌بینانه (${PersianUtils.toPersianDigits(settings.scenarioOptimisticReturnPct.toInt().toString())}٪)"
        )

        // فیلتر کردن لیست تراکنش‌ها
        val filtered = transactions.filter { tx ->
            val matchClass = filterClass == null || tx.assetClass == filterClass
            val matchAction = filterAction == null || tx.action == filterAction
            val matchQuery = query.isBlank() ||
                    tx.assetName.contains(query, ignoreCase = true) ||
                    tx.assetSymbol.contains(query, ignoreCase = true) ||
                    tx.notes.contains(query, ignoreCase = true)
            matchClass && matchAction && matchQuery
        }

        WealthUiState(
            transactions = transactions,
            filteredTransactions = filtered,
            prices = prices,
            goals = goals,
            liabilities = liabilities,
            dividends = dividends,
            settings = settings,
            portfolioSummary = portfolio,
            cashFlowSummary = cashFlow,
            retirementScenarios = listOf(simConservative, simBalanced, simOptimistic),
            selectedAssetClassFilter = filterClass,
            selectedActionFilter = filterAction,
            searchQuery = query,
            isAppLocked = isLocked,
            isMarketUpdating = isUpdating,
            lastMarketUpdateReport = updateReport,
            cloudBackupStatus = cloudStatus,
            userErrorMessage = errMsg,
            userSuccessMessage = succMsg
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = WealthUiState()
    )

    fun clearMessages() {
        _userErrorMessage.value = null
        _userSuccessMessage.value = null
    }

    fun setFilterClass(assetClass: AssetClass?) {
        _selectedAssetClassFilter.value = assetClass
    }

    fun setFilterAction(action: TransactionAction?) {
        _selectedActionFilter.value = action
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun saveTransaction(
        transaction: TransactionEntity,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        viewModelScope.launch {
            when (val result = repository.saveTransaction(transaction)) {
                is ValidationResult.Success -> {
                    _userSuccessMessage.value = "تراکنش با موفقیت ثبت شد و محاسبات پرتفوی بروزرسانی گردید."
                    onComplete?.invoke(true, null)
                }
                is ValidationResult.Error -> {
                    _userErrorMessage.value = result.message
                    onComplete?.invoke(false, result.message)
                }
            }
        }
    }

    fun deleteTransaction(transaction: TransactionEntity) {
        viewModelScope.launch {
            repository.deleteTransaction(transaction)
            _userSuccessMessage.value = "تراکنش با موفقیت حذف شد."
        }
    }

    fun updateCurrentPrice(
        symbolOrName: String,
        name: String,
        assetClass: AssetClass,
        price: Double,
        currency: CurrencyType = CurrencyType.TOMAN,
        source: String = "ورود دستی"
    ) {
        viewModelScope.launch {
            repository.updateCurrentPrice(
                CurrentPriceEntity(
                    assetSymbolOrName = symbolOrName,
                    assetName = name,
                    assetClass = assetClass,
                    price = price,
                    currency = currency,
                    source = source,
                    lastUpdated = System.currentTimeMillis()
                )
            )
            _userSuccessMessage.value = "قیمت روز برای «$name» با موفقیت بروز شد."
        }
    }

    fun saveGoal(goal: GoalEntity) {
        viewModelScope.launch {
            repository.saveGoal(goal)
            _userSuccessMessage.value = "هدف مالی با موفقیت ذخیره شد."
        }
    }

    fun deleteGoal(goal: GoalEntity) {
        viewModelScope.launch {
            repository.deleteGoal(goal)
            _userSuccessMessage.value = "هدف مالی حذف شد."
        }
    }

    fun saveLiability(liability: LiabilityEntity) {
        viewModelScope.launch {
            repository.saveLiability(liability)
            _userSuccessMessage.value = "بدهی / تعهد با موفقیت ثبت شد."
        }
    }

    fun deleteLiability(liability: LiabilityEntity) {
        viewModelScope.launch {
            repository.deleteLiability(liability)
            _userSuccessMessage.value = "بدهی با موفقیت حذف شد."
        }
    }

    fun saveDividend(dividend: DividendEntity) {
        viewModelScope.launch {
            repository.saveDividend(dividend)
            _userSuccessMessage.value = "اطلاعات سود نقدی مجمع با موفقیت ثبت شد."
        }
    }

    fun deleteDividend(dividend: DividendEntity) {
        viewModelScope.launch {
            repository.deleteDividend(dividend)
            _userSuccessMessage.value = "اطلاعات سود مجمع حذف شد."
        }
    }

    fun saveSettings(
        settings: AppSettingsEntity,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        viewModelScope.launch {
            when (val res = repository.saveSettings(settings)) {
                is ValidationResult.Success -> {
                    _userSuccessMessage.value = "تنظیمات با موفقیت ذخیره شد."
                    onComplete?.invoke(true, null)
                }
                is ValidationResult.Error -> {
                    _userErrorMessage.value = res.message
                    onComplete?.invoke(false, res.message)
                }
            }
        }
    }

    suspend fun exportBackupJson(): String {
        return repository.exportBackupJson()
    }

    fun restoreBackupJson(
        jsonString: String,
        replaceExisting: Boolean = true,
        onComplete: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            when (val res = repository.restoreBackupJson(jsonString, replaceExisting = replaceExisting)) {
                is ValidationResult.Success -> {
                    _userSuccessMessage.value = res.message
                    onComplete(true, null)
                }
                is ValidationResult.Error -> {
                    _userErrorMessage.value = res.message
                    onComplete(false, res.message)
                }
            }
        }
    }

    fun resetToInitialData() {
        viewModelScope.launch {
            repository.resetToInitialData()
            _userSuccessMessage.value = "داده‌ها با موفقیت به حالت پیش‌فرض اولیه بازنشانی شدند."
        }
    }

    /**
     * همگام‌سازی و بروزرسانی قیمت‌های روز با سامانه آنلاین
     */
    fun syncMarketPrices(forceRefresh: Boolean = true) {
        viewModelScope.launch {
            _isMarketUpdating.value = true
            try {
                val report = repository.syncMarketPrices(forceRefresh)
                _lastMarketUpdateReport.value = report
                if (report.updatedCount > 0) {
                    _userSuccessMessage.value = report.messageFa
                } else if (report.failedCount > 0) {
                    _userErrorMessage.value = report.messageFa
                }
            } catch (e: Exception) {
                _userErrorMessage.value = "خطا در بروزرسانی قیمت‌ها: ${e.localizedMessage ?: "اتصال اینترنت در دسترس نیست"}"
            } finally {
                _isMarketUpdating.value = false
            }
        }
    }

    /**
     * همگام‌سازی و بروزرسانی کلیه سهام موجود (Update all stock prices)
     */
    fun syncAllStockPrices(forceRefresh: Boolean = true) {
        viewModelScope.launch {
            _isMarketUpdating.value = true
            try {
                val report = repository.syncAllStockPrices(forceRefresh)
                _lastMarketUpdateReport.value = report
                if (report.updatedCount > 0) {
                    _userSuccessMessage.value = report.messageFa
                } else {
                    _userErrorMessage.value = report.messageFa
                }
            } catch (e: Exception) {
                _userErrorMessage.value = "خطا در بروزرسانی سهام: ${e.localizedMessage ?: "اتصال اینترنت در دسترس نیست"}"
            } finally {
                _isMarketUpdating.value = false
            }
        }
    }

    /**
     * همگام‌سازی و استعلام قیمت یک سهم خاص (Update an individual stock price)
     */
    fun syncStockPrice(symbol: String, forceRefresh: Boolean = true) {
        viewModelScope.launch {
            _isMarketUpdating.value = true
            try {
                val report = repository.syncStockPrice(symbol, forceRefresh)
                _lastMarketUpdateReport.value = report
                if (report.updatedCount > 0) {
                    _userSuccessMessage.value = report.messageFa
                } else {
                    _userErrorMessage.value = report.messageFa
                }
            } catch (e: Exception) {
                _userErrorMessage.value = "خطا در استعلام نماد $symbol: ${e.localizedMessage ?: "اتصال اینترنت در دسترس نیست"}"
            } finally {
                _isMarketUpdating.value = false
            }
        }
    }

    /**
     * تصحیح نماد دارایی و بروزرسانی خودکار قیمت آن
     */
    fun correctAssetTicker(oldSymbolOrName: String, newTickerOrName: String) {
        viewModelScope.launch {
            _isMarketUpdating.value = true
            try {
                val res = repository.correctAssetTicker(oldSymbolOrName, newTickerOrName)
                if (res.isSuccess) {
                    _userSuccessMessage.value = "نماد دارایی با موفقیت به '${res.getOrNull()}' تغییر یافت و قیمت آن بروزرسانی شد."
                } else {
                    _userErrorMessage.value = res.exceptionOrNull()?.message ?: "خطا در تصحیح نماد"
                }
            } catch (e: Exception) {
                _userErrorMessage.value = "خطا در تغییر نماد: ${e.localizedMessage}"
            } finally {
                _isMarketUpdating.value = false
            }
        }
    }

    /**
     * اتصال حساب Google
     */
    fun connectGoogleAccount(email: String, displayName: String, accessToken: String? = null, onComplete: ((Boolean, String?) -> Unit)? = null) {
        viewModelScope.launch {
            when (val res = repository.connectGoogleAccount(email, displayName, accessToken)) {
                is ValidationResult.Success -> {
                    _cloudBackupStatus.value = CloudBackupStatus.IDLE
                    _userSuccessMessage.value = "حساب Google ($email) با موفقیت متصل گردید."
                    onComplete?.invoke(true, null)
                }
                is ValidationResult.Error -> {
                    _userErrorMessage.value = res.message
                    onComplete?.invoke(false, res.message)
                }
            }
        }
    }

    /**
     * قطع اتصال حساب Google
     */
    fun disconnectGoogleAccount() {
        viewModelScope.launch {
            repository.disconnectGoogleAccount()
            _cloudBackupStatus.value = CloudBackupStatus.DISCONNECTED
            _userSuccessMessage.value = "اتصال حساب Google با موفقیت قطع گردید."
        }
    }

    /**
     * پشتیبان‌گیری در Google Drive
     */
    fun performGoogleDriveBackup() {
        viewModelScope.launch {
            _cloudBackupStatus.value = CloudBackupStatus.SYNCING
            val result = repository.performGoogleDriveBackup()
            if (result.isSuccess) {
                _cloudBackupStatus.value = CloudBackupStatus.SUCCESS
                _userSuccessMessage.value = "نسخه پشتیبان با موفقیت در Google Drive ذخیره شد."
            } else {
                _cloudBackupStatus.value = CloudBackupStatus.ERROR
                _userErrorMessage.value = result.exceptionOrNull()?.message ?: "خطا در پشتیبان‌گیری در Google Drive"
            }
        }
    }

    /**
     * پیش‌نمایش فایل پشتیبان Google Drive قبل از اعمال
     */
    fun getGoogleDriveBackupPreview(onResult: (DriveBackupInfo?) -> Unit) {
        viewModelScope.launch {
            val result = repository.fetchGoogleDriveBackup()
            if (result.isSuccess) {
                onResult(result.getOrNull())
            } else {
                _userErrorMessage.value = result.exceptionOrNull()?.message ?: "امکان خواندن پشتیبان ابری وجود ندارد."
                onResult(null)
            }
        }
    }

    /**
     * بازیابی از Google Drive پس از تأیید کاربر با انتخاب جایگزینی یا ادغام
     */
    fun restoreFromGoogleDrive(replaceExisting: Boolean = true, onComplete: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            _cloudBackupStatus.value = CloudBackupStatus.RESTORING
            when (val res = repository.restoreFromGoogleDrive(replaceExisting = replaceExisting)) {
                is ValidationResult.Success -> {
                    _cloudBackupStatus.value = CloudBackupStatus.IDLE
                    _userSuccessMessage.value = res.message
                    onComplete(true, null)
                }
                is ValidationResult.Error -> {
                    _cloudBackupStatus.value = CloudBackupStatus.ERROR
                    _userErrorMessage.value = res.message
                    onComplete(false, res.message)
                }
            }
        }
    }

    fun unlockApp(pin: String): Boolean {
        val currentSettings = uiState.value.settings
        return if (!currentSettings.isPinEnabled || currentSettings.pinCode == pin) {
            _isAppLocked.value = false
            true
        } else {
            false
        }
    }
}
