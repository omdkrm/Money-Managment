package ir.modiriatsarmaye.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ir.modiriatsarmaye.app.data.market.MarketUpdateReport
import ir.modiriatsarmaye.app.data.model.*
import ir.modiriatsarmaye.app.data.repository.ValidationResult
import ir.modiriatsarmaye.app.data.repository.WealthRepository
import ir.modiriatsarmaye.app.security.PinMigrationResult
import ir.modiriatsarmaye.app.security.PinMutationResult
import ir.modiriatsarmaye.app.security.PinVerificationResult
import ir.modiriatsarmaye.app.util.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val isSecurityReady: Boolean = false,
    val pinErrorMessage: String? = null,
    val isKeystorePermanentlyUnavailable: Boolean = false,
    val pinRecoveryAwaitingConfirmation: Boolean = false,
    val isMarketUpdating: Boolean = false,
    val lastMarketUpdateReport: MarketUpdateReport? = null,
    val userErrorMessage: String? = null,
    val userSuccessMessage: String? = null
)

private data class SecurityUiStateHolder(
    val hasUnlockedInSession: Boolean = false,
    val isSecurityReady: Boolean = false,
    val isFailClosedSecurityError: Boolean = false,
    val pinErrorMessage: String? = null,
    val isKeystorePermanentlyUnavailable: Boolean = false,
    val isRecoveryDeviceAuthenticated: Boolean = false,
    val pinRecoveryAwaitingConfirmation: Boolean = false
)

class WealthViewModel(
    private val repository: WealthRepository
) : ViewModel() {

    private val _selectedAssetClassFilter = MutableStateFlow<AssetClass?>(null)
    private val _selectedActionFilter = MutableStateFlow<TransactionAction?>(null)
    private val _searchQuery = MutableStateFlow("")
    private val _securityState = MutableStateFlow(SecurityUiStateHolder())
    private val _isMarketUpdating = MutableStateFlow(false)
    private val _lastMarketUpdateReport = MutableStateFlow<MarketUpdateReport?>(null)
    private val _userErrorMessage = MutableStateFlow<String?>(null)
    private val _userSuccessMessage = MutableStateFlow<String?>(null)

    init {
        refreshSecurityAndMigrateLegacyPin()
    }

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
        _securityState,
        _isMarketUpdating,
        _lastMarketUpdateReport,
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
        val secState = args[9] as SecurityUiStateHolder
        val isUpdating = args[10] as Boolean
        val updateReport = args[11] as MarketUpdateReport?
        val errMsg = args[12] as String?
        val succMsg = args[13] as String?
        val isLocked = !secState.isSecurityReady ||
            secState.isFailClosedSecurityError ||
            (settings.isPinEnabled && !secState.hasUnlockedInSession)

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
            isSecurityReady = secState.isSecurityReady,
            pinErrorMessage = secState.pinErrorMessage,
            isKeystorePermanentlyUnavailable = secState.isKeystorePermanentlyUnavailable,
            pinRecoveryAwaitingConfirmation = secState.pinRecoveryAwaitingConfirmation,
            isMarketUpdating = isUpdating,
            lastMarketUpdateReport = updateReport,
            userErrorMessage = errMsg,
            userSuccessMessage = succMsg
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = WealthUiState(isAppLocked = true, isSecurityReady = false)
    )

    fun clearMessages() {
        _userErrorMessage.value = null
        _userSuccessMessage.value = null
    }

    fun reportUserError(message: String) {
        _userErrorMessage.value = message
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

    fun refreshSecurityAndMigrateLegacyPin() {
        viewModelScope.launch {
            try {
                val (migrationResult, afterSettings, hasSecurePin, keyHealth) = withContext(Dispatchers.IO) {
                    val mig = repository.migrateLegacyPinIfNeeded()
                    val settings = repository.getSettings()
                    val hasPin = repository.hasSecurePin()
                    val health = if (settings.isPinEnabled && hasPin) {
                        repository.checkKeystoreHealth()
                    } else {
                        ir.modiriatsarmaye.app.security.KeyHealthStatus.NOT_CREATED
                    }
                    listOf(mig, settings, hasPin, health)
                }
                val migRes = migrationResult as PinMigrationResult
                val settingsAfter = afterSettings as AppSettingsEntity
                val securePinExists = hasSecurePin as Boolean
                val healthStatus = keyHealth as ir.modiriatsarmaye.app.security.KeyHealthStatus

                when (migRes) {
                    is PinMigrationResult.FailedPreservedLegacy -> {
                        _securityState.update {
                            it.copy(
                                isSecurityReady = true,
                                isFailClosedSecurityError = true,
                                pinErrorMessage = migRes.message,
                                isKeystorePermanentlyUnavailable = migRes.isPermanentKeystoreFailure
                            )
                        }
                    }
                    PinMigrationResult.MigratedAndVerified,
                    PinMigrationResult.NotNeeded -> {
                        val permanentlyUnavailable = settingsAfter.isPinEnabled && (
                            !securePinExists ||
                                healthStatus == ir.modiriatsarmaye.app.security.KeyHealthStatus.PERMANENTLY_UNAVAILABLE ||
                                healthStatus == ir.modiriatsarmaye.app.security.KeyHealthStatus.NOT_CREATED
                            )
                        val temporarilyUnavailable = settingsAfter.isPinEnabled &&
                            securePinExists &&
                            healthStatus == ir.modiriatsarmaye.app.security.KeyHealthStatus.TEMPORARILY_UNAVAILABLE
                        _securityState.update {
                            it.copy(
                                isSecurityReady = true,
                                isFailClosedSecurityError = false,
                                isKeystorePermanentlyUnavailable = permanentlyUnavailable,
                                pinErrorMessage = when {
                                    permanentlyUnavailable ->
                                        "کلید امنیتی رمز ورود در دستگاه در دسترس نیست. برای دسترسی مجدد می‌توانید از بازیابی با احراز هویت دستگاه استفاده کنید."
                                    temporarilyUnavailable ->
                                        "سرویس امنیتی دستگاه موقتاً در دسترس نیست. لطفاً دوباره تلاش کنید."
                                    else -> null
                                }
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _securityState.update {
                    it.copy(
                        isSecurityReady = true,
                        isFailClosedSecurityError = true,
                        isKeystorePermanentlyUnavailable = true,
                        pinErrorMessage = "بررسی وضعیت امنیتی برنامه با خطا مواجه شد. برای محافظت از اطلاعات مالی، دسترسی قفل ماند."
                    )
                }
            }
        }
    }

    fun clearPinError() {
        _securityState.update { it.copy(pinErrorMessage = null) }
    }

    fun unlockWithPin(pin: String, onResult: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            when (val res = withContext(Dispatchers.IO) { repository.verifyPin(pin) }) {
                PinVerificationResult.Success -> {
                    _securityState.update {
                        it.copy(
                            hasUnlockedInSession = true,
                            isFailClosedSecurityError = false,
                            pinErrorMessage = null,
                            isKeystorePermanentlyUnavailable = false,
                            isRecoveryDeviceAuthenticated = false,
                            pinRecoveryAwaitingConfirmation = false
                        )
                    }
                    onResult?.invoke(true)
                }
                PinVerificationResult.InvalidPin -> {
                    _securityState.update {
                        it.copy(pinErrorMessage = "رمز ورود اشتباه است.")
                    }
                    onResult?.invoke(false)
                }
                PinVerificationResult.NoSecurePinConfigured -> {
                    _securityState.update {
                        it.copy(
                            isFailClosedSecurityError = true,
                            isKeystorePermanentlyUnavailable = true,
                            pinErrorMessage = "اطلاعات رمز امن در دستگاه یافت نشد. لطفاً از بازیابی با احراز هویت دستگاه استفاده کنید."
                        )
                    }
                    onResult?.invoke(false)
                }
                is PinVerificationResult.KeystoreError -> {
                    _securityState.update {
                        it.copy(
                            isKeystorePermanentlyUnavailable = res.isPermanent,
                            pinErrorMessage = res.message
                        )
                    }
                    onResult?.invoke(false)
                }
            }
        }
    }

    fun lockAppNow() {
        if (uiState.value.settings.isPinEnabled || _securityState.value.isFailClosedSecurityError) {
            _securityState.update {
                it.copy(
                    hasUnlockedInSession = false,
                    pinErrorMessage = null,
                    isRecoveryDeviceAuthenticated = false,
                    pinRecoveryAwaitingConfirmation = false
                )
            }
        }
    }

    fun setOrChangePin(
        currentPin: String?,
        newPin: String,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        viewModelScope.launch {
            when (val res = withContext(Dispatchers.IO) { repository.setOrChangePin(currentPin, newPin) }) {
                PinMutationResult.Success -> {
                    _securityState.update {
                        it.copy(
                            hasUnlockedInSession = true,
                            isFailClosedSecurityError = false,
                            pinErrorMessage = null,
                            isKeystorePermanentlyUnavailable = false
                        )
                    }
                    _userSuccessMessage.value = "رمز ورود (PIN) با موفقیت و به‌صورت رمزنگاری‌شده ذخیره شد."
                    onComplete?.invoke(true, null)
                }
                is PinMutationResult.ValidationError -> {
                    onComplete?.invoke(false, res.message)
                }
                is PinMutationResult.KeystoreError -> {
                    _securityState.update {
                        it.copy(isKeystorePermanentlyUnavailable = res.isPermanent)
                    }
                    onComplete?.invoke(false, res.message)
                }
            }
        }
    }

    fun disablePin(
        currentPin: String,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        viewModelScope.launch {
            when (val res = withContext(Dispatchers.IO) { repository.disablePin(currentPin) }) {
                PinMutationResult.Success -> {
                    _securityState.update {
                        it.copy(
                            hasUnlockedInSession = true,
                            isFailClosedSecurityError = false,
                            pinErrorMessage = null,
                            isKeystorePermanentlyUnavailable = false,
                            isRecoveryDeviceAuthenticated = false,
                            pinRecoveryAwaitingConfirmation = false
                        )
                    }
                    _userSuccessMessage.value = "قفل رمز ورود غیرفعال شد."
                    onComplete?.invoke(true, null)
                }
                is PinMutationResult.ValidationError -> {
                    onComplete?.invoke(false, res.message)
                }
                is PinMutationResult.KeystoreError -> {
                    _securityState.update {
                        it.copy(isKeystorePermanentlyUnavailable = res.isPermanent)
                    }
                    onComplete?.invoke(false, res.message)
                }
            }
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch {
            when (val res = repository.setBiometricEnabled(enabled)) {
                is ValidationResult.Success -> {
                    _userSuccessMessage.value = if (enabled) {
                        "ورود با بیومتریک دستگاه فعال شد."
                    } else {
                        "ورود با بیومتریک دستگاه غیرفعال شد."
                    }
                }
                is ValidationResult.Error -> {
                    _userErrorMessage.value = res.message
                }
            }
        }
    }

    fun onBiometricUnlockSucceeded() {
        val sec = _securityState.value
        if (sec.isKeystorePermanentlyUnavailable || sec.isFailClosedSecurityError || !sec.isSecurityReady) {
            return
        }
        _securityState.update {
            it.copy(
                hasUnlockedInSession = true,
                pinErrorMessage = null,
                isRecoveryDeviceAuthenticated = false,
                pinRecoveryAwaitingConfirmation = false
            )
        }
    }

    fun onBiometricAuthFailed(message: String) {
        _securityState.update { it.copy(pinErrorMessage = message) }
    }

    fun onRecoveryDeviceAuthSucceeded() {
        _securityState.update {
            it.copy(
                isRecoveryDeviceAuthenticated = true,
                pinRecoveryAwaitingConfirmation = true,
                pinErrorMessage = null
            )
        }
    }

    fun cancelPinRecovery() {
        _securityState.update {
            it.copy(
                isRecoveryDeviceAuthenticated = false,
                pinRecoveryAwaitingConfirmation = false
            )
        }
    }

    fun confirmResetPinGateAfterRecovery(onComplete: ((Boolean, String?) -> Unit)? = null) {
        viewModelScope.launch {
            val sec = _securityState.value
            val res = repository.resetPinGateAfterConfirmedRecovery(
                isDeviceAuthenticated = sec.isRecoveryDeviceAuthenticated,
                isUserConfirmed = sec.pinRecoveryAwaitingConfirmation
            )
            when (res) {
                is ValidationResult.Success -> {
                    _securityState.update {
                        it.copy(
                            hasUnlockedInSession = true,
                            isFailClosedSecurityError = false,
                            pinErrorMessage = null,
                            isKeystorePermanentlyUnavailable = false,
                            isRecoveryDeviceAuthenticated = false,
                            pinRecoveryAwaitingConfirmation = false
                        )
                    }
                    _userSuccessMessage.value = res.message
                    onComplete?.invoke(true, null)
                }
                is ValidationResult.Error -> {
                    _securityState.update { it.copy(pinErrorMessage = res.message) }
                    onComplete?.invoke(false, res.message)
                }
            }
        }
    }
}
