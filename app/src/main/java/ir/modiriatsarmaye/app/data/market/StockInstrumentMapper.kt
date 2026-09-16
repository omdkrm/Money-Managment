package ir.modiriatsarmaye.app.data.market

import ir.modiriatsarmaye.app.util.PersianUtils

/**
 * نگاشت قطعی و پایدار نمادهای سهام بورسی (Deterministic Stock Instrument Mapping)
 * اولویت مطلق: نماد وارد شده توسط کاربر (فملی، شپنا، خودرو، وبملت، فولاد و ...)
 * بدون حدس زدن‌های تصادفی یا مپ کردن نامشخص
 */
object StockInstrumentMapper {

    /**
     * نرمال‌سازی متون فارسی، اصلاح حروف ی و ک عربی، حذف نیم‌فاصله و پیشوندهای مرسوم
     */
    fun normalizeSymbol(raw: String): String {
        if (raw.isBlank()) return ""
        var cleaned = PersianUtils.toEnglishDigits(raw.trim())
            .replace("ي", "ی")
            .replace("ك", "ک")
            .replace("\u200C", "") // zero-width non-joiner
            .replace("\u00A0", "") // non-breaking space
            .replace("-", "")
            .replace("_", "")
            .trim()

        // حذف پیشوندهای متداول سهام مثل "سهام " یا "سهم "
        if (cleaned.startsWith("سهام ")) cleaned = cleaned.removePrefix("سهام ").trim()
        if (cleaned.startsWith("سهم ")) cleaned = cleaned.removePrefix("سهم ").trim()

        return cleaned
    }

    /**
     * استخراج و تطابق قطعی نماد بورسی.
     * اولویت با نماد صریح وارد شده (symbolOrKey) است.
     * اگر نماد صریح وارد نشده باشد و نام شرکت یک کلمه بدون فاصله کوتاه باشد، از آن استفاده می‌شود.
     */
    fun resolveStockSymbol(symbolOrKey: String, name: String = ""): String? {
        val s = normalizeSymbol(symbolOrKey)
        val n = normalizeSymbol(name)

        if (s.isNotBlank()) {
            return s
        }

        // اگر نماد خالی بود و نام سهم کوتاه و مشخص بود:
        if (n.isNotBlank() && !n.contains(" ") && n.length in 2..10) {
            return n
        }

        return null
    }

    /**
     * بررسی معتبر بودن نماد بورسی
     */
    fun isValidStockSymbol(symbol: String): Boolean {
        val normalized = normalizeSymbol(symbol)
        return normalized.isNotBlank() && normalized.length in 2..15
    }
}
