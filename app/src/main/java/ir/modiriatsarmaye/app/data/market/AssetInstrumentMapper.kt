package ir.modiriatsarmaye.app.data.market

import ir.modiriatsarmaye.app.data.model.AssetClass
import ir.modiriatsarmaye.app.data.model.CurrencyType
import ir.modiriatsarmaye.app.util.PersianUtils

/**
 * نگاشت قطعی و پایدار شناسه سازوکارهای مالی (Deterministic Instrument Mapping)
 * پیونددهنده شناسه ارائه‌دهنده آنلاین (GERAM18) به دارایی‌های طلای فیزیکی موجود در سبد کاربر
 * بدون وابستگی به تطابق صرفاً لغوی نام‌های محلی
 */
object AssetInstrumentMapper {

    const val INSTRUMENT_GERAM18 = "GERAM18"
    const val CANONICAL_GOLD18_NAME = "طلای ۱۸ عیار / ۷۵۰"
    const val CANONICAL_GOLD18_UNIT = "گرم"
    const val CANONICAL_GOLD18_PURITY = "18K / 750"
    val CANONICAL_GOLD18_CURRENCY = CurrencyType.RIAL

    /**
     * تشخیص و نگاشت قطعی نماد یا نام دارایی به شناسه استاندارد سازوکار مالی
     * پشتیبانی کامل از «طلا»، «طلای فیزیکی»، «طلای 18 عیار»، «طلای ۱۸ عیار»، «GOLD18»، «geram18»
     */
    fun resolveInstrumentId(
        symbolOrKey: String,
        name: String = "",
        assetClass: AssetClass? = null,
        unit: String = ""
    ): String? {
        val s = normalize(symbolOrKey)
        val n = normalize(name)
        val u = normalize(unit)

        // ۱. تطابق با شناسه مستقیم سازوکار TGJU
        if (s == "geram18" || n == "geram18" || s.contains("geram18") || n.contains("geram18")) {
            return INSTRUMENT_GERAM18
        }

        // ۲. نمادهای استاندارد طلای ۱۸ عیار
        val gold18Symbols = listOf("gold18", "gold18k", "tala18", "gold", "18k", "750")
        if (gold18Symbols.any { s.contains(it) || n.contains(it) }) {
            return INSTRUMENT_GERAM18
        }

        // ۳. بررسی کلاس دارایی طلا
        val isGoldClass = assetClass == AssetClass.GOLD || s.contains("gold") || n.contains("gold")
        if (isGoldClass) {
            // تفکیک سکه و صندوق‌های قابل معامله بورسی
            val isCoin = isCoinInstrument(s, n)
            val isEtfFund = isEtfFundInstrument(s, n, u)

            if (!isCoin && !isEtfFund) {
                // تمام الگوهای طلای فیزیکی و مظنه ۱۸ عیار
                val isPhysicalGold = n.contains("طلا") || s.contains("طلا") ||
                        n.contains("فیزیکی") || s.contains("فیزیکی") ||
                        n.contains("ابشده") || n.contains("خام") || n.contains("مستعمل") ||
                        n.contains("زینتی") || n.contains("دستدوم") ||
                        u == "گرم" || u == "gram" || u.isEmpty()

                if (isPhysicalGold) {
                    return INSTRUMENT_GERAM18
                }
            }
        }

        // ۴. الگوهای ترکیبی نام طلا در صورت عدم تعیین کلاس دارایی
        if ((n.contains("طلایفیزیکی") || n.contains("طلای18") || n.contains("طلای۱۸") ||
             s.contains("طلایفیزیکی") || s.contains("طلای18") || s.contains("طلای۱۸") ||
             n == "طلا" || s == "طلا") &&
            !isCoinInstrument(s, n) && !isEtfFundInstrument(s, n, u)) {
            return INSTRUMENT_GERAM18
        }

        return null
    }

    /**
     * بررسی اینکه آیا دارایی داده شده مربوط به طلای ۱۸ عیار / ۷۵۰ است یا خیر
     */
    fun isGold18kInstrument(
        symbolOrKey: String,
        name: String = "",
        assetClass: AssetClass? = null,
        unit: String = ""
    ): Boolean {
        return resolveInstrumentId(symbolOrKey, name, assetClass, unit) == INSTRUMENT_GERAM18
    }

    private fun isCoinInstrument(s: String, n: String): Boolean {
        val coinKeywords = listOf("سکه", "امامی", "بهارازادی", "نیمسکه", "ربscale", "گرمی", "coin")
        return coinKeywords.any { s.contains(it) || n.contains(it) }
    }

    private fun isEtfFundInstrument(s: String, n: String, u: String): Boolean {
        val fundKeywords = listOf("صندوق", "etf", "لوتوس", "کهربا", "زرفام", "گوهر", "عیار", "زر", "ناب", "تابش")
        return (u == "واحد" || u == "unit") && fundKeywords.any { s.contains(it) || n.contains(it) }
    }

    fun normalize(text: String): String {
        if (text.isBlank()) return ""
        var res = PersianUtils.toEnglishDigits(text.trim().lowercase())
        res = res.replace("ي", "ی")
            .replace("ك", "ک")
            .replace("آ", "ا")
            .replace("أ", "ا")
            .replace("إ", "ا")
            .replace("\u200C", "") // ZWNJ
            .replace("\u200B", "") // ZWSP
            .replace("\u200E", "")
            .replace("\u200F", "")
            .replace("-", "")
            .replace("_", "")
            .replace("(", "")
            .replace(")", "")
            .replace("/", "")
            .replace("\\", "")
            .replace(" ", "")
        return res
    }
}
