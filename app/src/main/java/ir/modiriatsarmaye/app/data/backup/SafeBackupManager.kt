package ir.modiriatsarmaye.app.data.backup

import android.util.Log
import androidx.room.withTransaction
import ir.modiriatsarmaye.app.data.local.AppDatabase
import ir.modiriatsarmaye.app.data.market.AssetInstrumentMapper
import ir.modiriatsarmaye.app.data.market.StockInstrumentMapper
import ir.modiriatsarmaye.app.data.model.*
import ir.modiriatsarmaye.app.data.repository.ValidationResult
import ir.modiriatsarmaye.app.util.PersianUtils
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
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val summaryFa: String = ""
)

/**
 * سامانه امن و سازگار با گذشته جهت استخراج و بازیابی فایل‌های پشتیبان (Safe & Backward-Compatible Backup Manager)
 */
object SafeBackupManager {

    private const val TAG = "SafeBackupManager"
    const val CURRENT_SCHEMA_VERSION = 2

    /**
     * استخراج منعطف و بدون خطای مقدار قیمت از فیلد JSON
     * پشتیبانی کامل از:
     * - رشته (String) مانند "123456", "123,456", "۱۲۳۴۵۶"
     * - عدد (Number) اعم از صحیح و اعشاری
     * - شیء JSON به صورت {"amount": 123456, "currency": "IRR"}
     * - مقدار null
     * - عدم وجود فیلد
     * هرگز مستقیماً getJSONObject("price") صدا زده نمی‌شود.
     */
    fun parseFlexiblePrice(
        parent: JSONObject,
        priceKey: String,
        defaultCurrency: CurrencyType = CurrencyType.TOMAN,
        schemaVersion: Int = 1,
        recordContext: String = ""
    ): SafePriceParsed {
        // ۱. بررسی عدم وجود فیلد یا مقدار null
        if (!parent.has(priceKey) || parent.isNull(priceKey)) {
            return SafePriceParsed(
                amount = null,
                currency = defaultCurrency,
                isLegacyFormat = false,
                isAmbiguousCurrency = false,
                warning = null
            )
        }

        // ۲. استفاده از opt برای استخراج بدون پرتاب Exception
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

        // ۳. بررسی حالت JSONObject
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

        // ۴. بررسی حالت Number (صحیح یا اعشاری)
        if (rawValue is Number) {
            val (resolvedCurrency, isAmbiguous) = resolveCurrency("", defaultCurrency, schemaVersion)
            val warning = if (isAmbiguous) {
                "واحد پول برای «$recordContext» مشخص نبود؛ بر اساس نسخه $schemaVersion، ${resolvedCurrency.titleFa} لحاظ شد."
            } else null

            return SafePriceParsed(
                amount = rawValue.toDouble(),
                currency = resolvedCurrency,
                isLegacyFormat = schemaVersion <= 1,
                isAmbiguousCurrency = isAmbiguous,
                warning = warning
            )
        }

        // ۵. بررسی حالت String (رشته‌های عددی، کامادار، فارسی، یا همراه با پسوند ریال/تومان)
        if (rawValue is String) {
            val str = rawValue.trim()
            if (str.isBlank() || str.equals("null", ignoreCase = true)) {
                return SafePriceParsed(amount = null, currency = defaultCurrency)
            }

            // تشخیص واحد پول احتمالی داخل متن قیمت
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

            val amount = PersianUtils.parsePersianDouble(cleanNumStr)
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

        // ۶. انواع داده‌های غیرمنتظره دیگر بدون سقوط برنامه (No crash on unexpected type)
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
     * تبدیل ایمن هر آبجکت به مقدار Double معتبر
     */
    fun parseNumericAmount(value: Any?): Double? {
        if (value == null || value == JSONObject.NULL) return null
        return when (value) {
            is Number -> value.toDouble()
            is String -> PersianUtils.parsePersianDouble(value)
            else -> null
        }
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
            // عدم حدس خاموش: طبق قرارداد مستند نسخه ۱ و ۲ پیش‌فرض تومان است، اما وضعیت ابهام ثبت می‌گردد
            val isAmbiguous = (schemaVersion > 1) // در نسخه ۲ واحد باید صریح باشد
            return Pair(fallbackDefault, isAmbiguous)
        }

        val clean = currencyString.trim().uppercase(Locale.ROOT)
        return when {
            clean == "IRR" || clean == "RIAL" || clean == "RIALS" || clean == "ریال" -> Pair(CurrencyType.RIAL, false)
            clean == "TOMAN" || clean == "TMN" || clean == "TOMANS" || clean == "تومان" -> Pair(CurrencyType.TOMAN, false)
            else -> Pair(fallbackDefault, true)
        }
    }

    /**
     * اعتبارسنجی کامل فایل پشتیبان پیش از هرگونه تغییر در پایگاه داده
     */
    fun validateAndParseBackup(jsonString: String): BackupValidationReport {
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()

        if (jsonString.isBlank()) {
            return BackupValidationReport(
                isValid = false,
                schemaVersion = 1,
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
                errors = listOf("فایل پشتیبان خالی است."),
                summaryFa = "فایل پشتیبان خالی است."
            )
        }

        val root: JSONObject
        try {
            root = JSONObject(jsonString)
        } catch (e: JSONException) {
            val userFriendlyError = "فرمت فایل پشتیبان نامعتبر است: متن ورودی ساختار صحیح JSON ندارد."
            return BackupValidationReport(
                isValid = false,
                schemaVersion = 1,
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
                errors = listOf(userFriendlyError),
                summaryFa = userFriendlyError
            )
        }

        val schemaVersion = root.optInt("schemaVersion", root.optInt("version", 1))

        if (!root.has("transactions")) {
            val err = "فایل پشتیبان نامعتبر است: فهرست تراکنش‌ها (transactions) یافت نشد."
            return BackupValidationReport(
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
                errors = listOf(err),
                summaryFa = err
            )
        }

        // ۱. اعتبارسنجی تراکنش‌ها
        val txArray = root.optJSONArray("transactions")
        val validTransactions = mutableListOf<TransactionEntity>()
        var skippedTxCount = 0
        val totalTxCount = txArray?.length() ?: 0

        if (txArray != null) {
            for (i in 0 until txArray.length()) {
                val obj = txArray.optJSONObject(i)
                if (obj == null) {
                    skippedTxCount++
                    warnings.add("تراکنش شماره ${i + 1} ساختار JSON معتبر نداشت و نادیده گرفته شد.")
                    continue
                }

                val assetName = obj.optString("assetName", "").trim()
                if (assetName.isBlank()) {
                    skippedTxCount++
                    warnings.add("تراکنش شماره ${i + 1} به دلیل نام دارایی خالی نادیده گرفته شد.")
                    continue
                }

                val qtyRaw = obj.opt("quantity")
                val quantity = parseNumericAmount(qtyRaw)
                if (quantity == null || quantity <= 0.0) {
                    skippedTxCount++
                    warnings.add("تراکنش «$assetName» به دلیل مقدار/تعداد نامعتبر ($qtyRaw) نادیده گرفته شد.")
                    continue
                }

                val actionStr = obj.optString("action", "BUY").trim().uppercase(Locale.ROOT)
                val action = TransactionAction.values().firstOrNull {
                    it.name.equals(actionStr, ignoreCase = true) || it.titleFa == actionStr
                } ?: TransactionAction.BUY

                val assetClassStr = obj.optString("assetClass", "GOLD").trim().uppercase(Locale.ROOT)
                val assetClass = AssetClass.values().firstOrNull {
                    it.name.equals(assetClassStr, ignoreCase = true) || it.titleFa == assetClassStr
                } ?: AssetClass.OTHER

                val unit = obj.optString("unit", assetClass.defaultUnit).ifBlank { assetClass.defaultUnit }

                // واحد پول سطح تراکنش
                val (txCurrency, txCurrAmbiguous) = resolveCurrency(
                    obj.optString("currency", ""),
                    CurrencyType.TOMAN,
                    schemaVersion
                )
                if (txCurrAmbiguous) {
                    warnings.add("واحد پول تراکنش «$assetName» مشخص نبود؛ تومان منظور گردید.")
                }

                // استخراج ایمن قیمت واحد (unitPrice یا price قدیمی)
                val unitPriceKey = if (obj.has("unitPrice")) "unitPrice" else "price"
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

                // در صورت تفاوت واحد پول قیمت واحد با تراکنش، تبدیل امن به واحد پول تراکنش
                val finalUnitPrice = parsedUnitPrice.amount?.let { amt ->
                    if (parsedUnitPrice.currency != txCurrency) {
                        PersianUtils.convertCurrency(amt, parsedUnitPrice.currency, txCurrency)
                    } else amt
                }

                // استخراج ایمن مبلغ کل
                val parsedTotal = parseFlexiblePrice(
                    parent = obj,
                    priceKey = "totalAmount",
                    defaultCurrency = txCurrency,
                    schemaVersion = schemaVersion,
                    recordContext = "$assetName (مبلغ کل)"
                )
                val finalTotalAmount = parsedTotal.amount?.let { amt ->
                    if (parsedTotal.currency != txCurrency) {
                        PersianUtils.convertCurrency(amt, parsedTotal.currency, txCurrency)
                    } else amt
                } ?: (if (finalUnitPrice != null) finalUnitPrice * quantity else null)

                // استخراج کارمزد
                val parsedFees = parseFlexiblePrice(
                    parent = obj,
                    priceKey = "fees",
                    defaultCurrency = txCurrency,
                    schemaVersion = schemaVersion,
                    recordContext = "$assetName (کارمزد)"
                )
                val fees = parsedFees.amount ?: 0.0

                val commTypeStr = obj.optString("commissionType", "FIXED").trim().uppercase(Locale.ROOT)
                val commType = CommissionType.values().firstOrNull {
                    it.name.equals(commTypeStr, ignoreCase = true) || it.titleFa == commTypeStr
                } ?: CommissionType.FIXED

                val commRateRaw = obj.opt("commissionRate")
                val commissionRate = parseNumericAmount(commRateRaw) ?: fees

                val datePersian = obj.optString("datePersian", PersianUtils.getCurrentPersianDate()).ifBlank {
                    PersianUtils.getCurrentPersianDate()
                }
                val timestamp = obj.optLong("timestamp", System.currentTimeMillis())

                validTransactions.add(
                    TransactionEntity(
                        id = 0, // تخصیص شناسه جدید و پاک
                        datePersian = datePersian,
                        timestamp = timestamp,
                        assetClass = assetClass,
                        assetName = assetName,
                        assetSymbol = obj.optString("assetSymbol", ""),
                        action = action,
                        quantity = quantity,
                        unit = unit,
                        unitPrice = finalUnitPrice,
                        currency = txCurrency,
                        fees = fees,
                        commissionType = commType,
                        commissionRate = commissionRate,
                        totalAmount = finalTotalAmount,
                        brokerOrSource = obj.optString("brokerOrSource", ""),
                        notes = obj.optString("notes", "")
                    )
                )
            }
        }

        // ۲. اعتبارسنجی قیمت‌ها (Current Prices)
        val priceArray = root.optJSONArray("prices")
        val validPrices = mutableListOf<CurrentPriceEntity>()
        var skippedPriceCount = 0
        val totalPricesCount = priceArray?.length() ?: 0

        if (priceArray != null) {
            for (i in 0 until priceArray.length()) {
                val obj = priceArray.optJSONObject(i)
                if (obj == null) {
                    skippedPriceCount++
                    continue
                }

                val assetSymbolOrName = obj.optString("assetSymbolOrName", "").trim()
                val assetName = obj.optString("assetName", assetSymbolOrName).trim().ifBlank { assetSymbolOrName }
                if (assetSymbolOrName.isBlank()) {
                    skippedPriceCount++
                    continue
                }

                val (defaultPriceCurr, isCurrAmbiguous) = resolveCurrency(
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
                // جلوگیری از ثبت قیمت صفر یا ساختگی
                if (priceVal == null || priceVal <= 0.0) {
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
                        lastUpdated = obj.optLong("lastUpdated", System.currentTimeMillis()),
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
        val goalArray = root.optJSONArray("goals")
        val validGoals = mutableListOf<GoalEntity>()
        val totalGoalsCount = goalArray?.length() ?: 0
        if (goalArray != null) {
            for (i in 0 until goalArray.length()) {
                val obj = goalArray.optJSONObject(i) ?: continue
                val title = obj.optString("title", "").trim()
                val targetAmtRaw = obj.opt("targetAmountToman")
                val targetAmt = parseNumericAmount(targetAmtRaw) ?: 0.0
                if (title.isNotBlank() && targetAmt > 0.0) {
                    validGoals.add(
                        GoalEntity(
                            id = 0,
                            title = title,
                            targetAmountToman = targetAmt,
                            targetDatePersian = obj.optString("targetDatePersian", ""),
                            category = obj.optString("category", "عمومی"),
                            notes = obj.optString("notes", "")
                        )
                    )
                }
            }
        }

        // ۴. بدهی‌ها (Liabilities)
        val liabArray = root.optJSONArray("liabilities")
        val validLiabilities = mutableListOf<LiabilityEntity>()
        val totalLiabilitiesCount = liabArray?.length() ?: 0
        if (liabArray != null) {
            for (i in 0 until liabArray.length()) {
                val obj = liabArray.optJSONObject(i) ?: continue
                val title = obj.optString("title", "").trim()
                val totalAmtRaw = obj.opt("totalAmountToman")
                val totalAmt = parseNumericAmount(totalAmtRaw) ?: 0.0
                if (title.isNotBlank() && totalAmt > 0.0) {
                    validLiabilities.add(
                        LiabilityEntity(
                            id = 0,
                            title = title,
                            totalAmountToman = totalAmt,
                            monthlyPaymentToman = parseNumericAmount(obj.opt("monthlyPaymentToman")) ?: 0.0,
                            dueDatePersian = obj.optString("dueDatePersian", ""),
                            notes = obj.optString("notes", "")
                        )
                    )
                }
            }
        }

        val isValid = validTransactions.isNotEmpty() || (totalTxCount == 0 && validPrices.isNotEmpty())
        val summaryFa = if (isValid) {
            "فایل پشتیبان با موفقیت اعتبارسنجی شد (${validTransactions.size} تراکنش معتبر" +
                    (if (skippedTxCount > 0) "، $skippedTxCount رکورد نادیده گرفته شد" else "") +
                    (if (validPrices.isNotEmpty()) "، ${validPrices.size} نرخ دارایی" else "") +
                    ")."
        } else {
            "هیچ تراکنش معتبری در فایل پشتیبان یافت نشد."
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
            warnings = warnings,
            errors = errors,
            summaryFa = summaryFa
        )
    }

    /**
     * صدور استاندارد و یکنواخت نسخه پشتیبان مطابق طرح‌واره مستند (Documented Schema v2)
     */
    fun exportBackupJson(
        transactions: List<TransactionEntity>,
        prices: List<CurrentPriceEntity>,
        goals: List<GoalEntity>,
        liabilities: List<LiabilityEntity>
    ): String {
        val root = JSONObject()
        root.put("schemaVersion", CURRENT_SCHEMA_VERSION)
        root.put("version", CURRENT_SCHEMA_VERSION) // جهت سازگاری با سیستم‌های قدیمی
        root.put("timestamp", System.currentTimeMillis())
        root.put("appName", "مدیریت سرمایه")

        // تراکنش‌ها با خروجی استاندارد و یکپارچه
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
                put("quantity", tx.quantity)
                put("unit", tx.unit)
                if (tx.unitPrice != null) {
                    val unitPriceObj = JSONObject().apply {
                        put("amount", tx.unitPrice)
                        put("currency", tx.currency.name)
                    }
                    put("unitPrice", unitPriceObj)
                }
                put("currency", tx.currency.name)
                put("fees", tx.fees)
                put("commissionType", tx.commissionType.name)
                put("commissionRate", tx.commissionRate)
                if (tx.totalAmount != null) {
                    val totalObj = JSONObject().apply {
                        put("amount", tx.totalAmount)
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

        // قیمت‌ها به شکل یکنواخت با شیء price استاندارد
        val priceArray = JSONArray()
        for (p in prices) {
            val obj = JSONObject().apply {
                put("assetSymbolOrName", p.assetSymbolOrName)
                put("assetName", p.assetName)
                put("assetClass", p.assetClass.name)
                val priceObj = JSONObject().apply {
                    put("amount", p.price)
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

        // اهداف مالی
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
     * اجرای ایمن بازیابی درون تراکنش Room با امکان بازگشت خودکار در صورت بروز خطای بحرانی
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

                if (replaceExisting) {
                    txDao.clearAllTransactions()
                    txDao.insertTransactions(report.validTransactions)

                    if (report.validPrices.isNotEmpty()) {
                        priceDao.clearAllPrices()
                        priceDao.insertPrices(report.validPrices)
                    }

                    if (report.validGoals.isNotEmpty()) {
                        goalDao.clearAllGoals()
                        goalDao.insertGoals(report.validGoals)
                    }

                    if (report.validLiabilities.isNotEmpty()) {
                        liabDao.clearAllLiabilities()
                        for (l in report.validLiabilities) {
                            liabDao.insertLiability(l)
                        }
                    }
                } else {
                    // حالت ادغام بدون ایجاد تراکنش‌های تکراری
                    val existing = txDao.getAllTransactions()
                    val toInsert = report.validTransactions.filter { newTx ->
                        existing.none { oldTx ->
                            oldTx.datePersian == newTx.datePersian &&
                                    oldTx.assetName == newTx.assetName &&
                                    oldTx.quantity == newTx.quantity &&
                                    oldTx.action == newTx.action &&
                                    oldTx.timestamp == newTx.timestamp
                        }
                    }
                    if (toInsert.isNotEmpty()) {
                        txDao.insertTransactions(toInsert)
                    }

                    if (report.validPrices.isNotEmpty()) {
                        priceDao.insertPrices(report.validPrices)
                    }
                }
            }

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
        } catch (e: Throwable) {
            Log.e(TAG, "Critical transaction failure during restore; Room rolled back completely", e)
            ValidationResult.Error("خطا در ثبت اطلاعات در پایگاه داده؛ عملیات بازگردانی شد و داده‌های فعلی بدون تغییر باقی ماندند: ${e.localizedMessage}")
        }
    }
}
