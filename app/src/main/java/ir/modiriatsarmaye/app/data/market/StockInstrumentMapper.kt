package ir.modiriatsarmaye.app.data.market

import ir.modiriatsarmaye.app.util.PersianUtils

/**
 * مدل داده‌ای مشخصات استاندارد سازوکار مالی سهام (Stock Instrument Details)
 */
data class StockInstrument(
    val symbol: String,               // نماد رسمی بورسی (مانند: فولاد)
    val name: String,                 // نام رسمی شرکت (مانند: فولاد مبارکه اصفهان)
    val insCode: String? = null,      // شناسه اختصاصی TSETMC InsCode
    val isin: String? = null,         // کد بین‌المللی ISIN
    val instrumentType: String = "STOCK"
)

/**
 * نگاشت قطعی و پایدار نمادهای سهام بورسی (Deterministic Stock Instrument Mapping)
 * اولویت مطلق: استخراج و تطبیق نماد رسمی و معتبر بورسی (فملی، شپنا، خودرو، وبملت، فولاد و ...)
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
     * کاتالوگ جامع نمادهای شاخص بازار بورس و فرابورس ایران به همراه InsCode و ISIN
     */
    val STOCK_CATALOG: Map<String, StockInstrument> = mapOf(
        "فولاد" to StockInstrument("فولاد", "فولاد مبارکه اصفهان", "35367687258336381", "IRO1FOLD0001"),
        "فملی" to StockInstrument("فملی", "ملی صنایع مس ایران", "35425587644337450", "IRO1MSMI0001"),
        "شپنا" to StockInstrument("شپنا", "پالایش نفت اصفهان", "33887010427329587", "IRO1PNES0001"),
        "شتران" to StockInstrument("شتران", "پالایش نفت تهران", "31182376664974955", "IRO1PNTN0001"),
        "شبندر" to StockInstrument("شبندر", "پالایش نفت بندرعباس", "63866299388512141", "IRO1PNBA0001"),
        "خودرو" to StockInstrument("خودرو", "ایران خودرو", "65883838195688438", "IRO1IKCO0001"),
        "خساپا" to StockInstrument("خساپا", "سایپا", "49977801302820369", "IRO1SIPA0001"),
        "وبملت" to StockInstrument("وبملت", "بانک ملت", "70088051524347058", "IRO1BMLT0001"),
        "وتجارت" to StockInstrument("وتجارت", "بانک تجارت", "64724818780746979", "IRO1BTEJ0001"),
        "وبصادر" to StockInstrument("وبصادر", "بانک صادرات ایران", "38452406661364852", "IRO1BSDR0001"),
        "شستا" to StockInstrument("شستا", "سرمایه گذاری تامین اجتماعی", "2400322364771558", "IRO1SSAT0001"),
        "فارس" to StockInstrument("فارس", "صنایع پتروشیمی خلیج فارس", "44891482026867833", "IRO1PKLF0001"),
        "تاپیکو" to StockInstrument("تاپیکو", "سرمایه گذاری نفت و گاز و پتروشیمی تامین", "28860444650570827", "IRO1TAPK0001"),
        "وغدیر" to StockInstrument("وغدیر", "سرمایه گذاری غدیر", "43815545229606821", "IRO1GDIR0001"),
        "کگل" to StockInstrument("کگل", "معدنی و صنعتی گل گهر", "25091722394017770", "IRO1GOLG0001"),
        "کچاد" to StockInstrument("کچاد", "معدنی و صنعتی چادرملو", "26487679860714777", "IRO1CHAD0001"),
        "خگستر" to StockInstrument("خگستر", "گسترش سرمایه گذاری ایران خودرو", "17917996026405381", "IRO1IKGS0001"),
        "زاگرس" to StockInstrument("زاگرس", "پتروشیمی زاگرس", "11417036662453676", "IRO3ZGPZ0001"),
        "نوری" to StockInstrument("نوری", "پتروشیمی نوری", "17655075677847424", "IRO1PNUR0001"),
        "بوعلی" to StockInstrument("بوعلی", "پتروشیمی بوعلی سینا", "43763784110300958", "IRO1BOAL0001"),
        "آریا" to StockInstrument("آریا", "پلیمر آریا ساسول", "71933092289650041", "IRO3PASZ0001"),
        "اخابر" to StockInstrument("اخابر", "مخابرات ایران", "44728562140411883", "IRO1MKBT0001"),
        "همراه" to StockInstrument("همراه", "ارتباطات سیار ایران", "63032968128015881", "IRO1HMRA0001"),
        "فسبزوار" to StockInstrument("فسبزوار", "پارس فولاد سبزوار", "30623694086660655", "IRO1SABZ0001"),
        "فخوز" to StockInstrument("فخوز", "فولاد خوزستان", "46348619115042460", "IRO1FKHZ0001"),
        "ذوب" to StockInstrument("ذوب", "سهامی ذوب آهن اصفهان", "43029199346618776", "IRO3ZOBZ0001")
    )

    private val COMPANY_NAME_TO_TICKER: Map<String, String> = mapOf(
        "فولاد مبارکه اصفهان" to "فولاد",
        "فولاد مبارکه" to "فولاد",
        "ملی صنایع مس ایران" to "فملی",
        "صنایع مس ایران" to "فملی",
        "مس ایران" to "فملی",
        "پالایش نفت اصفهان" to "شپنا",
        "نفت اصفهان" to "شپنا",
        "پالایش نفت تهران" to "شتران",
        "نفت تهران" to "شتران",
        "پالایش نفت بندرعباس" to "شبندر",
        "نفت بندرعباس" to "شبندر",
        "ایران خودرو" to "خودرو",
        "سایپا" to "خساپا",
        "بانک ملت" to "وبملت",
        "بانک تجارت" to "وتجارت",
        "بانک صادرات ایران" to "وبصادر",
        "بانک صادرات" to "وبصادر",
        "سرمایه گذاری تامین اجتماعی" to "شستا",
        "تامین اجتماعی" to "شستا",
        "صنایع پتروشیمی خلیج فارس" to "فارس",
        "پتروشیمی خلیج فارس" to "فارس",
        "سرمایه گذاری نفت و گاز و پتروشیمی تامین" to "تاپیکو",
        "سرمایه گذاری غدیر" to "وغدیر",
        "معدنی و صنعتی گل گهر" to "کگل",
        "گل گهر" to "کگل",
        "معدنی و صنعتی چادرملو" to "کچاد",
        "چادرملو" to "کچاد",
        "گسترش سرمایه گذاری ایران خودرو" to "خگستر",
        "پتروشیمی زاگرس" to "زاگرس",
        "پتروشیمی نوری" to "نوری",
        "پتروشیمی بوعلی سینا" to "بوعلی",
        "پلیمر آریا ساسول" to "آریا",
        "مخابرات ایران" to "اخابر",
        "ارتباطات سیار ایران" to "همراه",
        "پارس فولاد سبزوار" to "فسبزوار",
        "فولاد خوزستان" to "فخوز",
        "سهامی ذوب آهن اصفهان" to "ذوب",
        "ذوب آهن اصفهان" to "ذوب"
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
     * استعلام کامل مشخصات سازوکار مالی (Instrument Resolution):
     * - اگر نماد ورودی در کاتالوگ باشد، مشخصات کامل رسمی (InsCode, ISIN, Name) برگردانده می‌شود.
     * - اگر کاربر نام شرکت را وارد کرده باشد، نماد رسمی متناظر آن استخراج می‌گردد.
     * - اگر هر دو ارائه شده باشند، سازگاری آن‌ها بررسی می‌گردد.
     * - هرگز کلمات عمومی (سهام، سهم، بورس و ...) پذیرفته نمی‌شوند.
     */
    fun resolveInstrument(symbolOrKey: String, name: String = ""): StockInstrument? {
        val normSymbol = normalizeSymbol(symbolOrKey)
        val normName = normalizeSymbol(name)

        // ۱. رد کلمات و برچسب‌های نامعتبر عمومی
        if (isGenericLabel(normSymbol) || isGenericLabel(normName)) {
            if (normSymbol.isNotBlank() && !isValidStockSymbol(normSymbol)) return null
            if (normName.isNotBlank() && !isValidStockSymbol(normName) && COMPANY_NAME_TO_TICKER[normName] == null) return null
        }

        // ۲. تطبیق مستقیم نماد در کاتالوگ
        val fromCatalog = STOCK_CATALOG[normSymbol]
        if (fromCatalog != null) {
            // در صورت وجود نام، بررسی سازگاری
            if (normName.isNotBlank()) {
                val expectedTicker = COMPANY_NAME_TO_TICKER[normName]
                if (expectedTicker != null && expectedTicker != normSymbol) {
                    // ناسازگاری نماد و نام دو شرکت متفاوت
                    return null
                }
            }
            return fromCatalog
        }

        // ۳. تطبیق نام شرکت برای استخراج نماد رسمی
        val tickerFromName = COMPANY_NAME_TO_TICKER[normName] ?: COMPANY_NAME_TO_TICKER[normSymbol]
        if (tickerFromName != null) {
            val instrument = STOCK_CATALOG[tickerFromName]
            if (instrument != null) {
                return instrument
            }
        }

        // ۴. جستجوی منعطف بر اساس نام‌های مشابه در کاتالوگ (مثلا بدون پسوند یا پیشوند)
        val candidateFromCatalog = STOCK_CATALOG.values.firstOrNull { 
            (normName.isNotBlank() && it.name.contains(normName)) || 
            (normSymbol.isNotBlank() && it.name.contains(normSymbol)) 
        }
        if (candidateFromCatalog != null && !isGenericLabel(normSymbol) && !isGenericLabel(normName)) {
            return candidateFromCatalog
        }

        // ۵. در صورتی که در کاتالوگ از پیش تعریف شده نباشد، ولی نماد معتبر بورسی باشد
        if (isValidStockSymbol(normSymbol)) {
            return StockInstrument(
                symbol = normSymbol,
                name = if (normName.isNotBlank()) normName else normSymbol
            )
        }

        if (isValidStockSymbol(normName)) {
            return StockInstrument(
                symbol = normName,
                name = normName
            )
        }

        return null
    }

    private fun isGenericLabel(text: String): Boolean {
        if (text.isBlank()) return false
        if (INVALID_GENERIC_LABELS.contains(text)) return true
        if (text == "سهام" || text == "سهم" || text == "بورس" || text == "بازار سهام" || text == "قیمت سهام") return true
        return false
    }
}
