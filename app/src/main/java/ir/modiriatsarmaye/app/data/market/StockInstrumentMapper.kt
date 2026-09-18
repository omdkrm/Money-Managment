package ir.modiriatsarmaye.app.data.market

import ir.modiriatsarmaye.app.util.PersianUtils

/**
 * نگاشت قطعی و پایدار نمادهای سهام بورسی (Deterministic Stock Instrument Mapping)
 * اولویت مطلق: نماد وارد شده توسط کاربر (فملی، شپنا، خودرو، وبملت، فولاد و ...)
 * بدون حدس زدن‌های تصادفی یا مپ کردن نامشخص
 */
object StockInstrumentMapper {

    const val SYMBOL_REQUIRED_LABEL = "نیاز به تعیین نماد"

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

        // حذف پیشوندهای متداول سهام مثل "سهام " یا "سهم "
        if (cleaned.startsWith("سهام ")) cleaned = cleaned.removePrefix("سهام ").trim()
        if (cleaned.startsWith("سهم ")) cleaned = cleaned.removePrefix("سهم ").trim()

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
        // اگر نماد وارد نشده است، از حدس زدن نام‌های مبهم خودداری می‌کنیم
        return null
    }

    /**
     * بررسی معتبر بودن نماد بورسی
     */
    fun isValidStockSymbol(symbol: String): Boolean {
        val normalized = normalizeSymbol(symbol)
        return normalized.isNotBlank() && !normalized.contains(" ") && normalized.length in 2..15
    }

    fun isValidSymbol(symbol: String): Boolean = isValidStockSymbol(symbol)
}
