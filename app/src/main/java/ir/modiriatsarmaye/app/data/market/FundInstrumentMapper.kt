package ir.modiriatsarmaye.app.data.market

import ir.modiriatsarmaye.app.util.PersianUtils

/**
 * نگاشت قطعی و پایدار نمادهای صندوق‌های سرمایه‌گذاری (Deterministic Fund Instrument Mapping)
 * اولویت مطلق: نماد واقعی صندوق وارد شده توسط کاربر (آگاس، کمند، لوتوس، عیار، افران و ...)
 * هرگز نماد یک صندوق را به صورت خودسر به صندوق دیگری نگاشت نمی‌کند.
 */
object FundInstrumentMapper {

    fun normalizeFundSymbol(raw: String): String {
        if (raw.isBlank()) return ""
        var cleaned = PersianUtils.toEnglishDigits(raw.trim())
            .replace("ي", "ی")
            .replace("ك", "ک")
            .replace("\u200C", "")
            .replace("\u00A0", "")
            .trim()

        val prefixes = listOf(
            "صندوق سرمایه گذاری ",
            "صندوق سرمایه‌گذاری ",
            "صندوق با درامد ثابت ",
            "صندوق با درآمد ثابت ",
            "صندوق سهامی ",
            "صندوق طلا ",
            "صندوق جسورانه ",
            "صندوق مختلط ",
            "صندوق "
        )
        for (prefix in prefixes) {
            if (cleaned.startsWith(prefix)) {
                cleaned = cleaned.removePrefix(prefix).trim()
            }
        }

        return cleaned
    }

    fun resolveFundSymbol(symbolOrKey: String, name: String = ""): String? {
        val s = normalizeFundSymbol(symbolOrKey)
        val n = normalizeFundSymbol(name)

        if (s.isNotBlank()) {
            return s
        }

        if (n.isNotBlank() && !n.contains(" ") && n.length in 2..12) {
            return n
        }

        return null
    }

    fun isValidFundSymbol(symbol: String): Boolean {
        val normalized = normalizeFundSymbol(symbol)
        return normalized.isNotBlank() && normalized.length in 2..15
    }
}
