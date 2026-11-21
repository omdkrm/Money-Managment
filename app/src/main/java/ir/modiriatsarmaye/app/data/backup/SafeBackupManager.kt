package ir.modiriatsarmaye.app.data.backup

import android.util.Log
import androidx.room.withTransaction
import ir.modiriatsarmaye.app.data.local.AppDatabase
import ir.modiriatsarmaye.app.data.market.AssetInstrumentMapper
import ir.modiriatsarmaye.app.data.model.*
import ir.modiriatsarmaye.app.data.repository.ValidationResult
import ir.modiriatsarmaye.app.util.PersianUtils
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/**
 * ساختار اطلاعاتی نتیجه استخراج منعطف قیمت
 */
data class SafePriceParsed(
    val amount: Double?,
    val currency: CurrencyType,
    val isLegacyFormat: Boolean = false,
    val isAmbiguousCurrency: Boolean = false,
    val warning: String? = null
)

/**
 * گزارش جامع اعتبارسنجی فایل پشتیبان پیش از اعمال در دیتابیس
 */
data class BackupValidationReport(
    val isValid: Boolean,
    val schemaVersion: Int,
    val totalTransactionsCount: Int,
    val validTransactions: List<TransactionEntity>,
    val skippedTransactionsCount: Int,
    val totalPricesCount: Int,
    val validPrices: List<CurrentPriceEntity>,
    val skippedPricesCount: Int,
    val totalGoalsCount: Int,
    val validGoals: List<GoalEntity>,
    val totalLiabilitiesCount: Int,
    val validLiabilities: List<LiabilityEntity>,
    val totalDividendsCount: Int = 0,
    val validDividends: List<DividendEntity> = emptyList(),
    val hasPricesSection: Boolean = false,
    val hasGoalsSection: Boolean = false,
    val hasLiabilitiesSection: Boolean = false,
    val hasDividendsSection: Boolean = false,
    val isEmptyBackup: Boolean = false,
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val summaryFa: String = ""
)

/**
 * سامانه امن و سازگار با گذشته جهت استخراج و بازیابی فایل‌های پشتیبان (Safe & Backward-Compatible Backup Manager)
 */
object SafeBackupManager {

    private const val TAG = "SafeBackupManager"
    const val MIN_SUPPORTED_SCHEMA_VERSION = 1
    const val CURRENT_SCHEMA_VERSION = 2

    private fun finiteOrNull(value: Double?): Double? = value?.takeIf { it.isFinite() }

    private fun finiteOrZero(value: Double): Double = if (value.isFinite()) value else 0.0

    private fun optFiniteLong(obj: JSONObject, key: String, fallback: Long): Long {
        if (!obj.has(key) || obj.isNull(key)) return fallback
        val raw = obj.opt(key)
        return when (raw) {
            is Number -> {
                val d = raw.toDouble()
                if (d.isFinite()) raw.toLong() else fallback
            }
            is String -> raw.trim().toLongOrNull() ?: fallback
            else -> fallback
        }
    }

    private fun optNonBlankString(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            if (obj.has(key) && !obj.isNull(key)) {
                val raw = obj.opt(key)
                if (raw is String && raw.isNotBlank() && !raw.equals("null", ignoreCase = true)) {
                    return raw.trim()
                }
            }
        }
        return ""
    }

    /**
     * استخراج منعطف و بدون خطای مقدار قیمت از فیلد JSON
     */
    fun parseFlexiblePrice(
        parent: JSONObject,
        priceKey: String,
        defaultCurrency: CurrencyType = CurrencyType.TOMAN,
        schemaVersion: Int = 1,
        recordContext: String = ""
    ): SafePriceParsed {
        if (!parent.has(priceKey) || parent.isNull(priceKey)) {
            return SafePriceParsed(
                amount = null,
                currency = defaultCurrency,
                isLegacyFormat = false,
                isAmbiguousCurrency = false,
                warning = null
            )
        }

        val rawValue = parent.opt(priceKey)
        if (rawValue == null || rawValue == JSONObject.NULL) {
            return SafePriceParsed(
                amount = null,
                currency = defaultCurrency,
                isLegacyFormat = false,
                isAmbiguousCurrency = false,
                warning = null
            )
        }

        if (rawValue is JSONObject) {
            val amountRaw = rawValue.opt("amount")
                ?: rawValue.opt("value")
                ?: rawValue.opt("price")
                ?: rawValue.opt("val")

            val amount = parseNumericAmount(amountRaw)
            val currencyStr = rawValue.optString("currency", rawValue.optString("unit", ""))
            val (resolvedCurrency, isAmbiguous) = resolveCurrency(currencyStr, defaultCurrency, schemaVersion)

            val warning = if (isAmbiguous) {
                "واحد پول برای «$recordContext» در فیلد قیمت تعیین نشده بود؛ پیش‌فرض ${resolvedCurrency.titleFa} اعمال شد."
            } else null

            return SafePriceParsed(
                amount = amount,
                currency = resolvedCurrency,
                isLegacyFormat = false,
                isAmbiguousCurrency = isAmbiguous,
                warning = warning
            )
        }

        if (rawValue is Number) {
            val parsedDouble = rawValue.toDouble().takeIf { it.isFinite() }
            val (resolvedCurrency, isAmbiguous) = resolveCurrency("", defaultCurrency, schemaVersion)
            val warning = if (isAmbiguous) {
                "واحد پول برای «$recordContext» مشخص نبود؛ بر اساس نسخه $schemaVersion، ${resolvedCurrency.titleFa} لحاظ شد."
            } else null

            return SafePriceParsed(
                amount = parsedDouble,
                currency = resolvedCurrency,
                isLegacyFormat = schemaVersion <= 1,
                isAmbiguousCurrency = isAmbiguous,
                warning = warning
            )
        }

        if (rawValue is String) {
            val str = rawValue.trim()
            if (str.isBlank() || str.equals("null", ignoreCase = true)) {
                return SafePriceParsed(amount = null, currency = defaultCurrency)
            }

            var embeddedCurrency: CurrencyType? = null
            if (str.contains("ریال") || str.contains("IRR", ignoreCase = true) || str.contains("Rial", ignoreCase = true)) {
                embeddedCurrency = CurrencyType.RIAL
            } else if (str.contains("تومان") || str.contains("TMN", ignoreCase = true) || str.contains("Toman", ignoreCase = true)) {
                embeddedCurrency = CurrencyType.TOMAN
            }

            val cleanNumStr = str
                .replace("ریال", "")
                .replace("تومان", "")
                .replace("IRR", "", ignoreCase = true)
                .replace("RIAL", "", ignoreCase = true)
                .replace("TOMAN", "", ignoreCase = true)
                .replace("TMN", "", ignoreCase = true)
                .replace("Rial", "", ignoreCase = true)
                .replace("Toman", "", ignoreCase = true)
                .trim()

            val amount = PersianUtils.parsePersianDouble(cleanNumStr)?.takeIf { it.isFinite() }
            val (resolvedCurrency, isAmbiguous) = if (embeddedCurrency != null) {
                Pair(embeddedCurrency, false)
            } else {
                resolveCurrency("", defaultCurrency, schemaVersion)
            }

            val warning = if (amount != null) {
                "فرمت قیمت در نسخه پشتیبان قدیمی است؛ در حال تبدیل امن اطلاعات..."
            } else {
                "مقدار قیمت رشته‌ای نامعتبر است: '$str'"
            }

            return SafePriceParsed(
                amount = amount,
                currency = resolvedCurrency,
                isLegacyFormat = true,
                isAmbiguousCurrency = isAmbiguous,
                warning = warning
            )
        }

        Log.w(TAG, "Unexpected JSON type for key '$priceKey': ${rawValue.javaClass.simpleName}")
        return SafePriceParsed(
            amount = null,
            currency = defaultCurrency,
            isLegacyFormat = true,
            isAmbiguousCurrency = false,
            warning = "نوع داده نامعتبر برای فیلد قیمت: ${rawValue.javaClass.simpleName}"
        )
    }

    /**
     * تبدیل ایمن هر آبجکت به مقدار Double متناهی و معتبر
     */
    fun parseNumericAmount(value: Any?): Double? {
        if (value == null || value == JSONObject.NULL) return null
        val parsed = when (value) {
            is Number -> value.toDouble()
            is String -> PersianUtils.parsePersianDouble(value)
            else -> null
        }
        return parsed?.takeIf { it.isFinite() }
    }

    /**
     * تطبیق واحد پول بر اساس رشته، نسخه پشتیبان، و قواعد شفاف بدون حدس‌زدن خاموش
     */
    fun resolveCurrency(
        currencyString: String?,
        fallbackDefault: CurrencyType,
        schemaVersion: Int
    ): Pair<CurrencyType, Boolean> {
        if (currencyString.isNullOrBlank()) {
            val isAmbiguous = (schemaVersion > 1)
            return Pair(fallbackDefault, isAmbiguous)
        }

        val clean = currencyString.trim().uppercase(Locale.ROOT)
        return when {
            clean == "IRR" || clean == "RIAL" || clean == "RIALS" || clean == "ریال" -> Pair(CurrencyType.RIAL, false)
            clean == "TOMAN" || clean == "TMN" || clean == "TOMANS" || clean == "تومان" -> Pair(CurrencyType.TOMAN, false)
            else -> Pair(fallbackDefault, true)
        }
    }

    private fun invalidReport(
        schemaVersion: Int,
        error: String
    ): BackupValidationReport = BackupValidationReport(
        isValid = false,
        schemaVersion = schemaVersion,
        totalTransactionsCount = 0,
        validTransactions = emptyList(),
        skippedTransactionsCount = 0,
        totalPricesCount = 0,
        validPrices = emptyList(),
        skippedPricesCount = 0,
        totalGoalsCount = 0,
        validGoals = emptyList(),
        totalLiabilitiesCount = 0,
        validLiabilities = emptyList(),
        totalDividendsCount = 0,
        validDividends = emptyList(),
        errors = listOf(error),
        summaryFa = error
    )

    /**
     * اعتبارسنجی کامل فایل پشتیبان پیش از هرگونه تغییر در پایگاه داده
     */
    fun validateAndParseBackup(jsonString: String): BackupValidationReport {
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()

        if (jsonString.isBlank()) {
            return invalidReport(schemaVersion = 1, error = "فایل پشتیبان خالی است.")
        }

        val root: JSONObject
        try {
            root = JSONObject(jsonString)
        } catch (e: JSONException) {
            Log.w(TAG, "Backup JSON parse failed: ${e.javaClass.simpleName}")
            return invalidReport(
                schemaVersion = 1,
                error = "فرمت فایل پشتیبان نامعتبر است: متن ورودی ساختار صحیح JSON ندارد."
            )
        }

        val schemaVersion = when {
            root.has("schemaVersion") && !root.isNull("schemaVersion") -> root.optInt("schemaVersion", -1)
            root.has("version") && !root.isNull("version") -> root.optInt("version", -1)
            else -> 1
        }
        if (schemaVersion < MIN_SUPPORTED_SCHEMA_VERSION || schemaVersion > CURRENT_SCHEMA_VERSION) {
            val err = "نسخه ساختار فایل پشتیبان ($schemaVersion) پشتیبانی نمی‌شود. لطفاً از نسخه جدیدتر برنامه استفاده کنید."
            Log.w(TAG, "Unsupported backup schemaVersion: $schemaVersion")
            return invalidReport(schemaVersion = schemaVersion, error = err)
        }

        val txArray = root.optJSONArray("transactions")
        if (!root.has("transactions") || txArray == null) {
            return invalidReport(
                schemaVersion = schemaVersion,
                error = "فایل پشتیبان نامعتبر است: فهرست تراکنش‌ها (transactions) یافت نشد."
            )
        }

        // ۱. اعتبارسنجی تراکنش‌ها
        val validTransactions = mutableListOf<TransactionEntity>()
        var skippedTxCount = 0
        val totalTxCount = txArray.length()

        for (i in 0 until totalTxCount) {
            val obj = txArray.optJSONObject(i)
            if (obj == null) {
                skippedTxCount++
                warnings.add("تراکنش شماره ${i + 1} ساختار JSON معتبر نداشت و نادیده گرفته شد.")
                continue
            }

            val assetName = optNonBlankString(obj, "assetName", "name", "asset_name")
            if (assetName.isBlank()) {
                skippedTxCount++
                warnings.add("تراکنش شماره ${i + 1} به دلیل نام دارایی خالی نادیده گرفته شد.")
                continue
            }

            val qtyRaw = obj.opt("quantity")
            val quantity = parseNumericAmount(qtyRaw)
            if (quantity == null || !quantity.isFinite() || quantity <= 0.0) {
                skippedTxCount++
                warnings.add("تراکنش «$assetName» به دلیل مقدار/تعداد نامعتبر نادیده گرفته شد.")
                continue
            }

            val rawAction = optNonBlankString(obj, "action", "type").ifBlank { "BUY" }.uppercase(Locale.ROOT)
            val action = TransactionAction.values().firstOrNull {
                it.name.equals(rawAction, ignoreCase = true) || it.titleFa == rawAction
            } ?: TransactionAction.BUY

            val rawAssetClass = optNonBlankString(obj, "assetClass", "category", "asset_class").ifBlank { "GOLD" }.uppercase(Locale.ROOT)
            val assetClass = AssetClass.values().firstOrNull {
                it.name.equals(rawAssetClass, ignoreCase = true) || it.titleFa == rawAssetClass
            } ?: AssetClass.OTHER

            val unit = optNonBlankString(obj, "unit").ifBlank { assetClass.defaultUnit }

            val (txCurrency, txCurrAmbiguous) = resolveCurrency(
                optNonBlankString(obj, "currency"),
                CurrencyType.TOMAN,
                schemaVersion
            )
            if (txCurrAmbiguous) {
                warnings.add("واحد پول تراکنش «$assetName» مشخص نبود؛ تومان منظور گردید.")
            }

            val unitPriceKey = when {
                obj.has("unitPrice") && !obj.isNull("unitPrice") -> "unitPrice"
                obj.has("unit_price") && !obj.isNull("unit_price") -> "unit_price"
                else -> "price"
            }
            val parsedUnitPrice = parseFlexiblePrice(
                parent = obj,
                priceKey = unitPriceKey,
                defaultCurrency = txCurrency,
                schemaVersion = schemaVersion,
                recordContext = "$assetName (قیمت واحد)"
            )
            if (parsedUnitPrice.warning != null && !warnings.contains(parsedUnitPrice.warning)) {
                warnings.add(parsedUnitPrice.warning)
            }

            val finalUnitPrice = parsedUnitPrice.amount?.let { amt ->
                if (parsedUnitPrice.currency != txCurrency) {
                    PersianUtils.convertCurrency(amt, parsedUnitPrice.currency, txCurrency)
                } else amt
            }?.takeIf { it.isFinite() }

            val totalAmountKey = when {
                obj.has("totalAmount") && !obj.isNull("totalAmount") -> "totalAmount"
                obj.has("total_amount") && !obj.isNull("total_amount") -> "total_amount"
                else -> "totalAmount"
            }
            val parsedTotal = parseFlexiblePrice(
                parent = obj,
                priceKey = totalAmountKey,
                defaultCurrency = txCurrency,
                schemaVersion = schemaVersion,
                recordContext = "$assetName (مبلغ کل)"
            )
            val finalTotalAmount = (parsedTotal.amount?.let { amt ->
                if (parsedTotal.currency != txCurrency) {
                    PersianUtils.convertCurrency(amt, parsedTotal.currency, txCurrency)
                } else amt
            } ?: (if (finalUnitPrice != null) finalUnitPrice * quantity else null))?.takeIf { it.isFinite() }

            val parsedFees = parseFlexiblePrice(
                parent = obj,
                priceKey = "fees",
                defaultCurrency = txCurrency,
                schemaVersion = schemaVersion,
                recordContext = "$assetName (کارمزد)"
            )
            val fees = finiteOrZero(parsedFees.amount ?: 0.0)

            val commTypeStr = optNonBlankString(obj, "commissionType", "commission_type").ifBlank { "FIXED" }.uppercase(Locale.ROOT)
            val commType = CommissionType.values().firstOrNull {
                it.name.equals(commTypeStr, ignoreCase = true) || it.titleFa == commTypeStr
            } ?: CommissionType.FIXED

            val commRateRaw = obj.opt("commissionRate") ?: obj.opt("commission_rate")
            val commissionRate = finiteOrZero(parseNumericAmount(commRateRaw) ?: fees)

            val datePersian = optNonBlankString(obj, "datePersian", "date", "date_persian")
                .ifBlank { PersianUtils.getCurrentPersianDate() }
            val timestamp = optFiniteLong(obj, "timestamp", System.currentTimeMillis())

            validTransactions.add(
                TransactionEntity(
                    id = 0,
                    datePersian = datePersian,
                    timestamp = timestamp,
                    assetClass = assetClass,
                    assetName = assetName,
                    assetSymbol = optNonBlankString(obj, "assetSymbol", "symbol", "ticker", "asset_symbol"),
                    action = action,
                    quantity = quantity,
                    unit = unit,
                    unitPrice = finalUnitPrice,
                    currency = txCurrency,
                    fees = fees,
                    commissionType = commType,
                    commissionRate = commissionRate,
                    totalAmount = finalTotalAmount,
                    brokerOrSource = optNonBlankString(obj, "brokerOrSource", "broker_or_source"),
                    notes = optNonBlankString(obj, "notes")
                )
            )
        }

        // ۲. اعتبارسنجی قیمت‌ها (Current Prices)
        val hasPricesSection = root.has("prices") && root.optJSONArray("prices") != null
        val priceArray = root.optJSONArray("prices")
        val validPrices = mutableListOf<CurrentPriceEntity>()
        var skippedPriceCount = 0
        val totalPricesCount = priceArray?.length() ?: 0

        if (priceArray != null) {
            for (i in 0 until totalPricesCount) {
                val obj = priceArray.optJSONObject(i)
                if (obj == null) {
                    skippedPriceCount++
                    continue
                }

                val assetSymbolOrName = optNonBlankString(
                    obj,
                    "assetSymbolOrName",
                    "symbol",
                    "name",
                    "assetName",
                    "asset_name"
                )
                val assetName = optNonBlankString(
                    obj,
                    "assetName",
                    "name",
                    "asset_name"
                ).ifBlank { assetSymbolOrName }
                if (assetSymbolOrName.isBlank()) {
                    skippedPriceCount++
                    continue
                }

                val (defaultPriceCurr, _) = resolveCurrency(
                    obj.optString("currency", ""),
                    CurrencyType.TOMAN,
                    schemaVersion
                )

                val parsedPrice = parseFlexiblePrice(
                    parent = obj,
                    priceKey = "price",
                    defaultCurrency = defaultPriceCurr,
                    schemaVersion = schemaVersion,
                    recordContext = assetSymbolOrName
                )
                if (parsedPrice.warning != null && !warnings.contains(parsedPrice.warning)) {
                    warnings.add(parsedPrice.warning)
                }

                val priceVal = parsedPrice.amount
                if (priceVal == null || !priceVal.isFinite() || priceVal <= 0.0) {
                    skippedPriceCount++
                    warnings.add("قیمت برای «$assetSymbolOrName» به دلیل مقدار نامعتبر یا صفر نادیده گرفته شد.")
                    continue
                }

                val assetClassStr = obj.optString("assetClass", "GOLD").trim().uppercase(Locale.ROOT)
                val assetClass = AssetClass.values().firstOrNull {
                    it.name.equals(assetClassStr, ignoreCase = true) || it.titleFa == assetClassStr
                } ?: AssetClass.OTHER

                val unit = obj.optString("unit", assetClass.defaultUnit).ifBlank { assetClass.defaultUnit }
                val instrumentId = obj.optString("instrumentId", "").ifBlank {
                    AssetInstrumentMapper.resolveInstrumentId(assetSymbolOrName, assetName, assetClass, unit) ?: ""
                }

                val statusStr = obj.optString("status", "FRESH").trim().uppercase(Locale.ROOT)
                val status = PriceStatus.values().firstOrNull {
                    it.name.equals(statusStr, ignoreCase = true) || it.titleFa == statusStr
                } ?: PriceStatus.FRESH

                validPrices.add(
                    CurrentPriceEntity(
                        assetSymbolOrName = assetSymbolOrName,
                        assetName = assetName,
                        assetClass = assetClass,
                        price = priceVal,
                        currency = parsedPrice.currency,
                        source = obj.optString("source", "بازیابی پشتیبان"),
                        lastUpdated = optFiniteLong(obj, "lastUpdated", System.currentTimeMillis()),
                        unit = unit,
                        priceType = obj.optString("priceType", ""),
                        isAutoUpdated = obj.optBoolean("isAutoUpdated", false),
                        status = status,
                        errorMessage = obj.optString("errorMessage", "").ifBlank { null },
                        instrumentId = instrumentId
                    )
                )
            }
        }

        // ۳. اهداف مالی (Goals)
        val hasGoalsSection = root.has("goals") && root.optJSONArray("goals") != null
        val goalArray = root.optJSONArray("goals")
        val validGoals = mutableListOf<GoalEntity>()
        val totalGoalsCount = goalArray?.length() ?: 0
        if (goalArray != null) {
            for (i in 0 until totalGoalsCount) {
                val obj = goalArray.optJSONObject(i) ?: continue
                val title = optNonBlankString(obj, "title", "name")
                val targetAmt = parseNumericAmount(obj.opt("targetAmountToman")) ?: 0.0
                if (title.isNotBlank() && targetAmt.isFinite() && targetAmt > 0.0) {
                    validGoals.add(
                        GoalEntity(
                            id = 0,
                            title = title,
                            targetAmountToman = targetAmt,
                            targetDatePersian = obj.optString("targetDatePersian", ""),
                            category = obj.optString("category", "عمومی").ifBlank { "عمومی" },
                            notes = obj.optString("notes", "")
                        )
                    )
                }
            }
        }

        // ۴. بدهی‌ها (Liabilities)
        val hasLiabilitiesSection = root.has("liabilities") && root.optJSONArray("liabilities") != null
        val liabArray = root.optJSONArray("liabilities")
        val validLiabilities = mutableListOf<LiabilityEntity>()
        val totalLiabilitiesCount = liabArray?.length() ?: 0
        if (liabArray != null) {
            for (i in 0 until totalLiabilitiesCount) {
                val obj = liabArray.optJSONObject(i) ?: continue
                val title = optNonBlankString(obj, "title", "name")
                val totalAmt = parseNumericAmount(obj.opt("totalAmountToman")) ?: 0.0
                if (title.isNotBlank() && totalAmt.isFinite() && totalAmt > 0.0) {
                    validLiabilities.add(
                        LiabilityEntity(
                            id = 0,
                            title = title,
                            totalAmountToman = totalAmt,
                            monthlyPaymentToman = finiteOrZero(parseNumericAmount(obj.opt("monthlyPaymentToman")) ?: 0.0),
                            dueDatePersian = obj.optString("dueDatePersian", ""),
                            notes = obj.optString("notes", "")
                        )
                    )
                }
            }
        }

        // ۵. سودهای نقدی مجامع (Dividends - اختیاری در نسخه‌های قدیمی)
        val hasDividendsSection = root.has("dividends") && root.optJSONArray("dividends") != null
        val divArray = root.optJSONArray("dividends")
        val validDividends = mutableListOf<DividendEntity>()
        val totalDividendsCount = divArray?.length() ?: 0
        if (divArray != null) {
            for (i in 0 until totalDividendsCount) {
                val obj = divArray.optJSONObject(i) ?: continue
                val stockSymbol = optNonBlankString(obj, "stockSymbol", "symbol")
                val stockName = optNonBlankString(obj, "stockName", "name").ifBlank { stockSymbol }
                if (stockSymbol.isBlank() && stockName.isBlank()) continue
                val sharesHeld = finiteOrZero(parseNumericAmount(obj.opt("sharesHeld")) ?: 0.0)
                val dpsToman = finiteOrZero(parseNumericAmount(obj.opt("dpsToman")) ?: 0.0)
                val receivableAmount = finiteOrZero(parseNumericAmount(obj.opt("receivableAmountToman")) ?: (sharesHeld * dpsToman))
                val receivedAmount = finiteOrZero(parseNumericAmount(obj.opt("receivedAmountToman")) ?: 0.0)
                validDividends.add(
                    DividendEntity(
                        id = 0,
                        stockSymbol = stockSymbol,
                        stockName = stockName,
                        sharesHeld = sharesHeld,
                        dpsToman = dpsToman,
                        receivableAmountToman = receivableAmount,
                        meetingDatePersian = obj.optString("meetingDatePersian", ""),
                        paymentDatePersian = obj.optString("paymentDatePersian", ""),
                        receivedAmountToman = receivedAmount,
                        isPaid = obj.optBoolean("isPaid", false),
                        notes = obj.optString("notes", "")
                    )
                )
            }
        }

        val hasAnyValidRecords = validTransactions.isNotEmpty() ||
            validPrices.isNotEmpty() ||
            validGoals.isNotEmpty() ||
            validLiabilities.isNotEmpty() ||
            validDividends.isNotEmpty()

        val allProvidedRecordsInvalid = (totalTxCount > 0 && validTransactions.isEmpty()) ||
            (!hasAnyValidRecords && (totalPricesCount > 0 || totalGoalsCount > 0 || totalLiabilitiesCount > 0 || totalDividendsCount > 0))

        val isEmptyBackup = !hasAnyValidRecords && !allProvidedRecordsInvalid
        val isValid = !allProvidedRecordsInvalid

        if (!isValid) {
            errors.add("هیچ تراکنش یا رکورد معتبری در فایل پشتیبان یافت نشد.")
        }
        if (isEmptyBackup) {
            warnings.add("این فایل پشتیبان حاوی هیچ تراکنش یا رکورد مالی نیست (پشتیبان خالی).")
        }

        val summaryFa = when {
            !isValid -> errors.first()
            isEmptyBackup -> "فایل پشتیبان معتبر است اما هیچ رکورد مالی ثبت‌شده‌ای در آن وجود ندارد."
            else -> "فایل پشتیبان با موفقیت اعتبارسنجی شد (${validTransactions.size} تراکنش معتبر" +
                (if (skippedTxCount > 0) "، $skippedTxCount رکورد نادیده گرفته شد" else "") +
                (if (validPrices.isNotEmpty()) "، ${validPrices.size} نرخ دارایی" else "") +
                ")."
        }

        return BackupValidationReport(
            isValid = isValid,
            schemaVersion = schemaVersion,
            totalTransactionsCount = totalTxCount,
            validTransactions = validTransactions,
            skippedTransactionsCount = skippedTxCount,
            totalPricesCount = totalPricesCount,
            validPrices = validPrices,
            skippedPricesCount = skippedPriceCount,
            totalGoalsCount = totalGoalsCount,
            validGoals = validGoals,
            totalLiabilitiesCount = totalLiabilitiesCount,
            validLiabilities = validLiabilities,
            totalDividendsCount = totalDividendsCount,
            validDividends = validDividends,
            hasPricesSection = hasPricesSection,
            hasGoalsSection = hasGoalsSection,
            hasLiabilitiesSection = hasLiabilitiesSection,
            hasDividendsSection = hasDividendsSection,
            isEmptyBackup = isEmptyBackup,
            warnings = warnings,
            errors = errors,
            summaryFa = summaryFa
        )
    }

    /**
     * صدور استاندارد و یکنواخت نسخه پشتیبان مطابق طرح‌واره مستند (Documented Schema v2)
     * هیچ رمز ورود (PIN) یا تنظیم امنیتی دستگاه در خروجی قرار نمی‌گیرد.
     */
    fun exportBackupJson(
        transactions: List<TransactionEntity>,
        prices: List<CurrentPriceEntity>,
        goals: List<GoalEntity>,
        liabilities: List<LiabilityEntity>,
        dividends: List<DividendEntity> = emptyList()
    ): String {
        val root = JSONObject()
        root.put("schemaVersion", CURRENT_SCHEMA_VERSION)
        root.put("version", CURRENT_SCHEMA_VERSION)
        root.put("timestamp", System.currentTimeMillis())
        root.put("appName", "مدیریت سرمایه")

        val txArray = JSONArray()
        for (tx in transactions) {
            val obj = JSONObject().apply {
                put("id", tx.id)
                put("datePersian", tx.datePersian)
                put("timestamp", tx.timestamp)
                put("assetClass", tx.assetClass.name)
                put("assetName", tx.assetName)
                put("assetSymbol", tx.assetSymbol)
                put("action", tx.action.name)
                put("quantity", finiteOrZero(tx.quantity))
                put("unit", tx.unit)
                finiteOrNull(tx.unitPrice)?.let { safeUnitPrice ->
                    val unitPriceObj = JSONObject().apply {
                        put("amount", safeUnitPrice)
                        put("currency", tx.currency.name)
                    }
                    put("unitPrice", unitPriceObj)
                }
                put("currency", tx.currency.name)
                put("fees", finiteOrZero(tx.fees))
                put("commissionType", tx.commissionType.name)
                put("commissionRate", finiteOrZero(tx.commissionRate))
                finiteOrNull(tx.totalAmount)?.let { safeTotal ->
                    val totalObj = JSONObject().apply {
                        put("amount", safeTotal)
                        put("currency", tx.currency.name)
                    }
                    put("totalAmount", totalObj)
                }
                put("brokerOrSource", tx.brokerOrSource)
                put("notes", tx.notes)
            }
            txArray.put(obj)
        }
        root.put("transactions", txArray)

        val priceArray = JSONArray()
        for (p in prices) {
            val safePrice = finiteOrNull(p.price) ?: continue
            val obj = JSONObject().apply {
                put("assetSymbolOrName", p.assetSymbolOrName)
                put("assetName", p.assetName)
                put("assetClass", p.assetClass.name)
                val priceObj = JSONObject().apply {
                    put("amount", safePrice)
                    put("currency", p.currency.name)
                }
                put("price", priceObj)
                put("currency", p.currency.name)
                put("unit", p.unit)
                put("priceType", p.priceType)
                put("source", p.source)
                put("status", p.status.name)
                put("lastUpdated", p.lastUpdated)
                put("instrumentId", p.instrumentId)
            }
            priceArray.put(obj)
        }
        root.put("prices", priceArray)

        val goalArray = JSONArray()
        for (g in goals) {
            val safeTarget = finiteOrZero(g.targetAmountToman)
            val obj = JSONObject().apply {
                put("title", g.title)
                put("targetAmountToman", safeTarget)
                put("targetDatePersian", g.targetDatePersian)
                put("category", g.category)
                put("notes", g.notes)
            }
            goalArray.put(obj)
        }
        root.put("goals", goalArray)

        val liabArray = JSONArray()
        for (l in liabilities) {
            val obj = JSONObject().apply {
                put("title", l.title)
                put("totalAmountToman", finiteOrZero(l.totalAmountToman))
                put("monthlyPaymentToman", finiteOrZero(l.monthlyPaymentToman))
                put("dueDatePersian", l.dueDatePersian)
                put("notes", l.notes)
            }
            liabArray.put(obj)
        }
        root.put("liabilities", liabArray)

        val dividendArray = JSONArray()
        for (d in dividends) {
            val obj = JSONObject().apply {
                put("stockSymbol", d.stockSymbol)
                put("stockName", d.stockName)
                put("sharesHeld", finiteOrZero(d.sharesHeld))
                put("dpsToman", finiteOrZero(d.dpsToman))
                put("receivableAmountToman", finiteOrZero(d.receivableAmountToman))
                put("meetingDatePersian", d.meetingDatePersian)
                put("paymentDatePersian", d.paymentDatePersian)
                put("receivedAmountToman", finiteOrZero(d.receivedAmountToman))
                put("isPaid", d.isPaid)
                put("notes", d.notes)
            }
            dividendArray.put(obj)
        }
        root.put("dividends", dividendArray)

        return root.toString(2)
    }

    /**
     * اجرای ایمن بازیابی درون تراکنش Room با امکان بازگشت خودکار در صورت بروز خطای بحرانی.
     * تنظیمات امنیتی دستگاه (PIN / بیومتریک) هرگز در جریان بازیابی تغییر یا پاک نمی‌شوند.
     */
    suspend fun executeSafeRestore(
        database: AppDatabase,
        report: BackupValidationReport,
        replaceExisting: Boolean = true
    ): ValidationResult {
        if (!report.isValid) {
            val errMsg = report.errors.firstOrNull() ?: "فایل پشتیبان نامعتبر است."
            return ValidationResult.Error(errMsg)
        }

        return try {
            database.withTransaction {
                val txDao = database.transactionDao()
                val priceDao = database.currentPriceDao()
                val goalDao = database.goalDao()
                val liabDao = database.liabilityDao()
                val divDao = database.dividendDao()

                if (replaceExisting) {
                    txDao.clearAllTransactions()
                    if (report.validTransactions.isNotEmpty()) {
                        txDao.insertTransactions(report.validTransactions)
                    }

                    // تفکیک بخش صریح موجود از بخش غایب در نسخه‌های قدیمی:
                    // تنها در صورت حضور کلید بخش در فایل پشتیبان (یا وجود داده معتبر) جایگزینی انجام می‌شود.
                    if (report.hasPricesSection || report.validPrices.isNotEmpty()) {
                        priceDao.clearAllPrices()
                        if (report.validPrices.isNotEmpty()) {
                            priceDao.insertPrices(report.validPrices)
                        }
                    }

                    if (report.hasGoalsSection || report.validGoals.isNotEmpty()) {
                        goalDao.clearAllGoals()
                        if (report.validGoals.isNotEmpty()) {
                            goalDao.insertGoals(report.validGoals)
                        }
                    }

                    if (report.hasLiabilitiesSection || report.validLiabilities.isNotEmpty()) {
                        liabDao.clearAllLiabilities()
                        for (l in report.validLiabilities) {
                            liabDao.insertLiability(l)
                        }
                    }

                    if (report.hasDividendsSection || report.validDividends.isNotEmpty()) {
                        divDao.clearAllDividends()
                        for (d in report.validDividends) {
                            divDao.insertDividend(d)
                        }
                    }
                } else {
                    // حالت ادغام بدون ایجاد رکوردهای تکراری
                    val existingTxs = txDao.getAllTransactions()
                    val txsToInsert = report.validTransactions.filter { newTx ->
                        existingTxs.none { oldTx ->
                            oldTx.datePersian == newTx.datePersian &&
                                oldTx.assetName == newTx.assetName &&
                                oldTx.assetSymbol == newTx.assetSymbol &&
                                oldTx.quantity == newTx.quantity &&
                                oldTx.unitPrice == newTx.unitPrice &&
                                oldTx.action == newTx.action &&
                                oldTx.timestamp == newTx.timestamp
                        }
                    }
                    if (txsToInsert.isNotEmpty()) {
                        txDao.insertTransactions(txsToInsert)
                    }

                    if (report.validPrices.isNotEmpty()) {
                        priceDao.insertPrices(report.validPrices)
                    }

                    if (report.validGoals.isNotEmpty()) {
                        val existingGoals = goalDao.getAllGoals()
                        val goalsToInsert = report.validGoals.filter { newGoal ->
                            existingGoals.none { oldGoal ->
                                oldGoal.title.equals(newGoal.title, ignoreCase = true) &&
                                    oldGoal.targetAmountToman == newGoal.targetAmountToman &&
                                    oldGoal.targetDatePersian == newGoal.targetDatePersian
                            }
                        }
                        if (goalsToInsert.isNotEmpty()) {
                            goalDao.insertGoals(goalsToInsert)
                        }
                    }

                    if (report.validLiabilities.isNotEmpty()) {
                        val existingLiabs = liabDao.getAllLiabilities()
                        val liabsToInsert = report.validLiabilities.filter { newLiab ->
                            existingLiabs.none { oldLiab ->
                                oldLiab.title.equals(newLiab.title, ignoreCase = true) &&
                                    oldLiab.totalAmountToman == newLiab.totalAmountToman &&
                                    oldLiab.dueDatePersian == newLiab.dueDatePersian
                            }
                        }
                        for (l in liabsToInsert) {
                            liabDao.insertLiability(l)
                        }
                    }

                    if (report.validDividends.isNotEmpty()) {
                        val existingDivs = divDao.getAllDividends()
                        val divsToInsert = report.validDividends.filter { newDiv ->
                            existingDivs.none { oldDiv ->
                                oldDiv.stockSymbol.equals(newDiv.stockSymbol, ignoreCase = true) &&
                                    oldDiv.stockName.equals(newDiv.stockName, ignoreCase = true) &&
                                    oldDiv.sharesHeld == newDiv.sharesHeld &&
                                    oldDiv.dpsToman == newDiv.dpsToman &&
                                    oldDiv.meetingDatePersian == newDiv.meetingDatePersian
                            }
                        }
                        for (d in divsToInsert) {
                            divDao.insertDividend(d)
                        }
                    }
                }
            }

            Log.i(
                TAG,
                "Backup restore succeeded (replace=$replaceExisting, tx=${report.validTransactions.size}, prices=${report.validPrices.size}, goals=${report.validGoals.size}, liabilities=${report.validLiabilities.size}, dividends=${report.validDividends.size})"
            )

            val msg = buildString {
                append("بازیابی اطلاعات با موفقیت انجام شد: ")
                append("${report.validTransactions.size} تراکنش")
                if (report.skippedTransactionsCount > 0) {
                    append(" (تعداد ${report.skippedTransactionsCount} رکورد نامعتبر نادیده گرفته شد)")
                }
                if (report.validPrices.isNotEmpty()) {
                    append(" و ${report.validPrices.size} قیمت روز")
                }
                append(" اعمال گردید.")
            }

            ValidationResult.Success(message = msg, report = report)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Critical transaction failure during restore (${e.javaClass.simpleName}); Room rolled back completely", e)
            ValidationResult.Error("خطا در ثبت اطلاعات در پایگاه داده؛ عملیات بازگردانی شد و داده‌های فعلی بدون تغییر باقی ماندند.")
        }
    }
}

