package ir.modiriatsarmaye.app.data.market

import ir.modiriatsarmaye.app.util.PersianUtils

/**
 * نگاشت قطعی و پایدار نمادهای سهام بورسی (Deterministic Stock Instrument Mapping)
 * اولویت مطلق: نماد وارد شده توسط کاربر (فملی، شپنا، خودرو، وبملت، فولاد و ...)
 * بدون حدس زدن‌های تصادفی یا مپ کردن نامشخص
 */
object StockInstrumentMapper {

    const val SYMBOL_REQUIRED_LABEL = "نیاز به تعیین نماد"

    // عناوین، اصطلاحات عمومی، برچسب‌های صفحات و کلمات غیرمجاز که هرگز نماد سهام نیستند
    private val INVALID_GENERIC_LABELS = setOf(
        "سهام", "سهم", "بازار سهام", "بازارسهام", "بورس", "بازار بورس", "بازاربورس",
        "قیمت سهام", "قیمتسهام", "نرخ سهام", "نرخسهام", "نماد", "نمادها", "نماد سهم",
        "شاخص", "شاخص کل", "شاخصکل", "فرابورس", "اوراق", "طلا", "ارز", "صندوق",
        "شرکت", "صنعتی", "سرمایه", "سرمایه گذاری", "سرمایه_گذاری", "سرمایه‌گذاری",
        "نامشخص", "null", "undefined", "stock", "stocks", "market", "tsetmc", "tgju",
        "بازار", "معاملات", "حجم", "ارزش", "آخرین قیمت", "قیمت پایانی", "تعداد معاملات"
    )

    /**
     * نرمال‌سازی متون فارسی، اصلاح حروف ی و ک عربی، حذف نیم‌فاصله، کاراکترهای پنهان و پیشوندهای مرسوم
     */
    fun normalizeSymbol(raw: String): String {
        if (raw.isBlank()) return ""
        var cleaned = PersianUtils.toEnglishDigits(raw.trim())
            .replace("ي", "ی")
            .replace("ك", "ک")
            .replace("\u200C", "") // zero-width non-joiner
            .replace("\u200B", "") // zero-width space
            .replace("\u200E", "") // LTR mark
            .replace("\u200F", "") // RTL mark
            .replace("\uFEFF", "") // BOM
            .replace("\u00A0", "") // non-breaking space
            .replace("&nbsp;", "")
            .replace("&zwnj;", "")
            .replace("&#160;", "")
            .replace("&#8204;", "")
            .replace("-", "")
            .replace("_", "")
            .trim()

        // حذف پیشوندهای متداول سهام مثل "سهام " یا "سهم " در صورت داشتن ادامه معتبر
        if (cleaned.startsWith("سهام ") && cleaned != "سهام") cleaned = cleaned.removePrefix("سهام ").trim()
        if (cleaned.startsWith("سهم ") && cleaned != "سهم") cleaned = cleaned.removePrefix("سهم ").trim()

        return cleaned
    }

    /**
     * استخراج نماد بورسی با استفاده دقیق از نماد ذخیره شده برای هر دارایی.
     * از حدس زدن نماد بر اساس نام‌های مبهم فارسی اکیداً خودداری می‌شود.
     * اگر دارایی فاقد نماد معتبر باشد null بازگردانده شده و در UI عبارت «نیاز به تعیین نماد» نمایش داده می‌شود.
     */
    fun resolveStockSymbol(symbolOrKey: String, name: String = ""): String? {
        val s = normalizeSymbol(symbolOrKey)
        if (isValidStockSymbol(s)) {
            return s
        }
        val n = normalizeSymbol(name)
        if (isValidStockSymbol(n)) {
            return n
        }
        // اگر نماد وارد نشده است، از حدس زدن نام‌های مبهم خودداری می‌کنیم
        return null
    }

    /**
     * بررسی معتبر بودن نماد بورسی:
     * - غیر خالی باشد
     * - فاصله نداشته باشد
     * - طول بین ۲ تا ۱۵ کاراکتر
     * - جزو عناوین و برچسب‌های عمومی مانند «سهام»، «بازار سهام»، «بورس»، «قیمت سهام» و ... نباشد
     * - شامل کلمات عمومی مثل «بازار»، «قیمت»، «شاخص»، «شرکت» نباشد
     */
    fun isValidStockSymbol(symbol: String): Boolean {
        val normalized = normalizeSymbol(symbol)
        if (normalized.isBlank() || normalized.contains(" ") || normalized.length !in 2..15) {
            return false
        }
        if (INVALID_GENERIC_LABELS.contains(normalized)) {
            return false
        }
        // رد برچسب‌های حاوی کلمات کلیدی صفحات وب و بازار
        if (normalized.contains("بازار") || 
            normalized.contains("قیمت") || 
            normalized.contains("شاخص") || 
            normalized.contains("شرکت") || 
            normalized.contains("صندوق") ||
            normalized.contains("سهام")
        ) {
            return false
        }
        return true
    }

    fun isValidSymbol(symbol: String): Boolean = isValidStockSymbol(symbol)
}
