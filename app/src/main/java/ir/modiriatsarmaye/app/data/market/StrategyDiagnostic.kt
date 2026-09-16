package ir.modiriatsarmaye.app.data.market

import ir.modiriatsarmaye.app.data.model.PriceStatus

/**
 * رکورد اطلاعات عیب‌یابی جامع برای هر راهبرد ارتباطی با سامانه‌های بازار (TGJU طلا، سهام، صندوق‌ها)
 */
data class StrategyDiagnostic(
    val testId: String = "", // e.g. "TEST A", "STOCK TGJU", "FUND TGJU"
    val testNameFa: String = "",
    val url: String = "",
    val httpMethod: String = "GET",
    val httpStatusCode: Int = 0,
    val contentType: String = "-",
    val contentLength: Long = -1L,
    val isRedirected: Boolean = false,
    val dnsException: String? = null,
    val tlsException: String? = null,
    val socketException: String? = null,
    val timeoutException: String? = null,
    val responseBodyPreview: String = "",
    val parserStrategy: String = "",
    val parserFailureReason: String? = null,
    val isSuccess: Boolean = false,
    val extractedPriceRial: Double? = null,
    val extractedUnit: String = "گرم",
    val extractedCurrency: String = "RIAL",
    val durationMs: Long = 0L,
    val providerName: String = "TGJU",
    val instrumentType: String = "GOLD",
    val symbol: String = "",
    val closingPriceRial: Double? = null,
    val latestPriceRial: Double? = null,
    val persistenceSuccess: Boolean = false,
    val readBackSuccess: Boolean = false,
    val portfolioSuccess: Boolean = false,
    val finalUiState: PriceStatus = PriceStatus.UNAVAILABLE
)
