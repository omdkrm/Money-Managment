package ir.modiriatsarmaye.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/**
 * مدل داده‌ای تاریخ شمسی / جلالی (Jalali / Persian Date)
 * همراه با تبدیلات ریاضی قطعی به میلادی و مهرزمانی (Epoch Timestamp)
 * بدون هرگونه جابجایی یک‌روزه ناشی از منطقه زمانی (No Timezone Shift)
 */
data class JalaliDate(
    val year: Int,
    val month: Int,
    val day: Int
) : Comparable<JalaliDate> {

    init {
        require(year in 1000..3000) { "سال شمسی نامعتبر: $year" }
        require(month in 1..12) { "ماه شمسی نامعتبر: $month" }
        val maxDays = getDaysInMonth(year, month)
        require(day in 1..maxDays) { "روز شمسی نامعتبر: $day برای ماه $month سال $year (حداکثر $maxDays)" }
    }

    /**
     * نام فارسی ماه
     */
    val monthNameFa: String
        get() = PersianUtils.persianMonths.getOrElse(month - 1) { "" }

    /**
     * فرمت استاندارد انگلیسی با صفرهای پیشین: YYYY/MM/DD
     * به عنوان قالب یکنواخت برای ذخیره و فیلتر
     */
    val canonicalString: String
        get() = String.format(Locale.US, "%04d/%02d/%02d", year, month, day)

    /**
     * فرمت با ارقام فارسی برای نمایش در واسط کاربری: ۱۴۰۳/۰۵/۱۰
     */
    val displayString: String
        get() = PersianUtils.toPersianDigits(canonicalString)

    /**
     * فرمت تشریحی فارسی: ۱۰ مرداد ۱۴۰۳
     */
    val longDisplayString: String
        get() = "${PersianUtils.toPersianDigits(day.toString())} $monthNameFa ${PersianUtils.toPersianDigits(year.toString())}"

    /**
     * تبدیل به تاریخ میلادی معادل (بدون وابستگی به ساعت یا منطقه زمانی محلی)
     */
    fun toGregorian(): LocalDate {
        val (gy, gm, gd) = jalaliToGregorian(year, month, day)
        return LocalDate.of(gy, gm, gd)
    }

    /**
     * تبدیل به مهرزمانی کانونیکال (Epoch Milliseconds در ساعت ۱۲:۰۰ ظهر UTC)
     * ساعت ۱۲:۰۰ ظهر مانع از هرگونه لغزش تاریخی ناشی از تبدیل Timezone محلی در محدوده UTC-11 تا UTC+14 می‌شود.
     */
    fun toCanonicalEpochMillis(): Long {
        val g = toGregorian()
        return g.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    }

    /**
     * اندیس روز هفته بر اساس تقویم فارسی:
     * ۰: شنبه، ۱: یکشنبه، ۲: دوشنبه، ۳: سه‌شنبه، ۴: چهارشنبه، ۵: پنج‌شنبه، ۶: جمعه
     */
    val dayOfWeekIndex: Int
        get() {
            val gDate = toGregorian()
            // LocalDate.dayOfWeek: Monday = 1 ... Sunday = 7
            // Saturday in LocalDate = 6 -> Persian index 0
            // Sunday = 7 -> Persian index 1
            // Monday = 1 -> Persian index 2
            // Tuesday = 2 -> Persian index 3
            // Wednesday = 3 -> Persian index 4
            // Thursday = 4 -> Persian index 5
            // Friday = 5 -> Persian index 6
            return when (gDate.dayOfWeek.value) {
                6 -> 0 // شنبه
                7 -> 1 // یکشنبه
                1 -> 2 // دوشنبه
                2 -> 3 // سه‌شنبه
                3 -> 4 // چهارشنبه
                4 -> 5 // پنج‌شنبه
                5 -> 6 // جمعه
                else -> 0
            }
        }

    val dayOfWeekNameFa: String
        get() = PERSIAN_WEEK_DAYS.getOrElse(dayOfWeekIndex) { "" }

    /**
     * فرمت مناسب برای نام فایل پشتیبان: YYYY-MM-DD
     */
    fun formatForFilename(): String {
        return String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
    }

    override fun compareTo(other: JalaliDate): Int {
        if (year != other.year) return year.compareTo(other.year)
        if (month != other.month) return month.compareTo(other.month)
        return day.compareTo(other.day)
    }

    companion object {
        val PERSIAN_WEEK_DAYS = listOf("شنبه", "یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنج‌شنبه", "جمعه")
        val PERSIAN_WEEK_DAYS_SHORT = listOf("ش", "ی", "د", "س", "چ", "پ", "ج")

        /**
         * بررسی سال کبیسه شمسی (الگوریتم ۳۳ ساله خیامی/بیرشک)
         */
        fun isLeapYear(year: Int): Boolean {
            // چرخه ۳۳ ساله: باقیمانده‌های مشخص سال کبیسه هستند
            val rem = ((year + 38) * 31) % 128
            return rem < 31
        }

        /**
         * تعداد روزهای یک ماه مشخص شمسی
         */
        fun getDaysInMonth(year: Int, month: Int): Int {
            return when (month) {
                in 1..6 -> 31
                in 7..11 -> 30
                12 -> if (isLeapYear(year)) 30 else 29
                else -> 30
            }
        }

        /**
         * دریافت تاریخ شمسی امروز بر اساس زمان جاری
         */
        fun now(): JalaliDate {
            val localDate = LocalDate.now(ZoneOffset.UTC)
            return fromGregorian(localDate.year, localDate.monthValue, localDate.dayOfMonth)
        }

        /**
         * ساخت تاریخ شمسی از روی مهرزمانی UTC
         */
        fun fromEpochMillis(epochMillis: Long): JalaliDate {
            val instant = Instant.ofEpochMilli(epochMillis)
            val localDate = instant.atZone(ZoneOffset.UTC).toLocalDate()
            return fromGregorian(localDate.year, localDate.monthValue, localDate.dayOfMonth)
        }

        /**
         * تبدیل تاریخ میلادی به شمسی
         */
        fun fromGregorian(gy: Int, gm: Int, gd: Int): JalaliDate {
            return gregorianToJalali(gy, gm, gd)
        }

        /**
         * تجزیه رشته متنی به شیء JalaliDate
         * پشتیبانی از اعداد فارسی و انگلیسی: "1403/05/10", "۱۴۰۳/۰۵/۱۰", "1403/5/10", "1403-05-10"
         */
        fun parseOrNull(text: String?): JalaliDate? {
            if (text.isNullOrBlank()) return null
            val normalized = PersianUtils.toEnglishDigits(text.trim())
                .replace("-", "/")
                .replace(".", "/")
            val parts = normalized.split("/").mapNotNull { it.trim().toIntOrNull() }
            if (parts.size != 3) return null

            val y = parts[0]
            val m = parts[1]
            val d = parts[2]

            return try {
                if (y in 1000..3000 && m in 1..12 && d in 1..getDaysInMonth(y, m)) {
                    JalaliDate(y, m, d)
                } else null
            } catch (e: Exception) {
                null
            }
        }

        fun parseOrDefault(text: String?, default: JalaliDate = now()): JalaliDate {
            return parseOrNull(text) ?: default
        }

        // ==========================================
        // الگوریتم تبدیل استاندارد و قطعی تقویم‌ها
        // ==========================================

        private fun gregorianToJalali(gy: Int, gm: Int, gd: Int): JalaliDate {
            val gDaysInMonth = intArrayOf(0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
            val isLeapG = (gy % 4 == 0 && gy % 100 != 0) || (gy % 400 == 0)
            if (isLeapG) gDaysInMonth[2] = 29

            val gy2 = gy - 1600
            val gm2 = gm - 1
            val gd2 = gd - 1

            var gDayNo = 365L * gy2 + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400
            for (i in 1..gm2) {
                gDayNo += gDaysInMonth[i]
            }
            gDayNo += gd2

            var jDayNo = gDayNo - 79

            val jNp = jDayNo / 12053
            jDayNo %= 12053

            var jy = 979 + 33 * jNp + 4 * (jDayNo / 1461)
            jDayNo %= 1461

            if (jDayNo >= 366) {
                jy += (jDayNo - 1) / 365
                jDayNo = (jDayNo - 1) % 365
            }

            val jm: Int
            val jd: Int
            if (jDayNo < 186) {
                jm = 1 + (jDayNo / 31).toInt()
                jd = 1 + (jDayNo % 31).toInt()
            } else {
                jm = 7 + ((jDayNo - 186) / 30).toInt()
                jd = 1 + ((jDayNo - 186) % 30).toInt()
            }

            return JalaliDate(jy.toInt(), jm, jd)
        }

        private fun jalaliToGregorian(jy: Int, jm: Int, jd: Int): Triple<Int, Int, Int> {
            val jy2 = jy - 979
            val jm2 = jm - 1
            val jd2 = jd - 1

            var jDayNo = 365L * jy2 + (jy2 / 33) * 8 + ((jy2 % 33) + 3) / 4
            for (i in 0 until jm2) {
                jDayNo += if (i < 6) 31 else 30
            }
            jDayNo += jd2

            var gDayNo = jDayNo + 79

            var gy = 1600 + 400 * (gDayNo / 146097)
            gDayNo %= 146097

            var leap = true
            if (gDayNo >= 36525) {
                gDayNo--
                gy += 100 * (gDayNo / 36524)
                gDayNo %= 36524

                if (gDayNo >= 365) {
                    gDayNo++
                } else {
                    leap = false
                }
            }

            gy += 4 * (gDayNo / 1461)
            gDayNo %= 1461

            if (gDayNo >= 366) {
                leap = false
                gDayNo--
                gy += gDayNo / 365
                gDayNo %= 365
            }

            val gDaysInMonth = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
            var gm = 0
            while (gm < 12 && gDayNo >= gDaysInMonth[gm]) {
                gDayNo -= gDaysInMonth[gm]
                gm++
            }

            return Triple(gy.toInt(), gm + 1, (gDayNo + 1).toInt())
        }
    }
}
