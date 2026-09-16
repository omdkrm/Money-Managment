package ir.modiriatsarmaye.app.util

import ir.modiriatsarmaye.app.data.model.CurrencyType
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object PersianUtils {

    private val persianDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
    private val arabicIndicDigits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')

    fun toPersianDigits(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            if (ch in '0'..'9') {
                sb.append(persianDigits[ch - '0'])
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun toEnglishDigits(text: String): String {
        var result = text
        for (i in 0..9) {
            result = result.replace(persianDigits[i], (i + '0'.code).toChar())
            result = result.replace(arabicIndicDigits[i], (i + '0'.code).toChar())
        }
        // تبدیل ممیز فارسی، عربی، ویرگول و اسلش به نقطه اعشار استاندارد
        result = result.replace('٫', '.') // Persian decimal separator U+066B
        result = result.replace('،', '.') // Arabic comma U+060C
        result = result.replace('/', '.') // Slash
        return result
    }

    fun formatNumber(number: Double, decimals: Int = 0): String {
        val pattern = when {
            decimals > 0 -> "#,##0." + "0".repeat(decimals)
            number % 1.0 != 0.0 -> "#,##0.######"
            else -> "#,##0"
        }
        val symbols = DecimalFormatSymbols(Locale.US).apply {
            groupingSeparator = '٬'
            decimalSeparator = '٫'
        }
        val df = DecimalFormat(pattern, symbols)
        return toPersianDigits(df.format(number))
    }

    fun formatNumberRaw(number: Long): String {
        val symbols = DecimalFormatSymbols(Locale.US).apply {
            groupingSeparator = '٬'
        }
        val df = DecimalFormat("#,##0", symbols)
        return toPersianDigits(df.format(number))
    }

    /**
     * فرمت‌بندی استاندارد مبالغ پولی با درج واحد تومان یا ریال
     */
    fun formatMoney(
        amount: Double?,
        targetCurrency: CurrencyType = CurrencyType.TOMAN,
        includeUnit: Boolean = true
    ): String {
        if (amount == null) return "ثبت نشده"
        val formatted = formatNumber(amount, if (amount % 1.0 == 0.0) 0 else 2)
        return if (includeUnit) {
            "$formatted ${targetCurrency.titleFa}"
        } else {
            formatted
        }
    }

    /**
     * تبدیل ریال به تومان: ۱۰ ریال = ۱ تومان
     */
    fun rialToToman(rial: Double): Double = rial / 10.0

    /**
     * تبدیل تومان به ریال: ۱ تومان = ۱۰ ریال
     */
    fun tomanToRial(toman: Double): Double = toman * 10.0

    /**
     * تبدیل مبلغ به ارز مقصد مشخص‌شده
     */
    fun convertCurrency(amount: Double, from: CurrencyType, to: CurrencyType): Double {
        return when {
            from == to -> amount
            from == CurrencyType.RIAL && to == CurrencyType.TOMAN -> rialToToman(amount)
            from == CurrencyType.TOMAN && to == CurrencyType.RIAL -> tomanToRial(amount)
            else -> amount
        }
    }

    val persianMonths = listOf(
        "فروردین", "اردیبهشت", "خرداد",
        "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر",
        "دی", "بهمن", "اسفند"
    )

    /**
     * فرمت‌بندی زمان میلی‌ثانیه به تاریخ شمسی/ساعت خوانا
     */
    fun formatTimestampToPersian(timestamp: Long?): String {
        if (timestamp == null || timestamp <= 0L) return "نامشخص"
        val sdf = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        val formatted = sdf.format(java.util.Date(timestamp))
        return toPersianDigits(formatted)
    }

    /**
     * تاریخ شمسی فعلی تقریبی برای ایجاد پیش‌فرض فرم‌ها
     */
    fun getCurrentPersianDate(): String {
        return "۱۴۰۵/۰۱/۰۱"
    }

    fun getPersianYearMonths(year: Int = 1405): List<String> {
        return persianMonths.map { "$it $year" }
    }
}
