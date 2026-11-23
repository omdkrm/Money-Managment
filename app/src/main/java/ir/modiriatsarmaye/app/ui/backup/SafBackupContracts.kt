package ir.modiriatsarmaye.app.ui.backup

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

private const val SAF_PICKER_TAG = "SafBackupPicker"

data class BackupPickerUriEvent(
    val eventId: Long,
    val uri: Uri?
)

sealed class BackupPickerLaunchOutcome {
    data object Launched : BackupPickerLaunchOutcome()
    data class Failed(val userMessage: String) : BackupPickerLaunchOutcome()
}

/**
 * قرارداد ایجاد فایل پشتیبان با Category استاندارد OPENABLE و پیاده‌سازی equals/hashCode
 * برای جلوگیری از ثبت مجدد لانچر در هنگام Recomposition.
 */
class CreateBackupDocumentContract(
    private val mimeType: String,
    private val includeOpenableCategory: Boolean = true
) : ActivityResultContracts.CreateDocument(mimeType) {

    override fun createIntent(context: Context, input: String): Intent {
        return super.createIntent(context, input).apply {
            if (includeOpenableCategory) {
                addCategory(Intent.CATEGORY_OPENABLE)
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CreateBackupDocumentContract) return false
        return mimeType == other.mimeType && includeOpenableCategory == other.includeOpenableCategory
    }

    override fun hashCode(): Int = 31 * mimeType.hashCode() + includeOpenableCategory.hashCode()
}

/**
 * قرارداد باز کردن فایل پشتیبان از طریق SAF با پشتیبانی ایمن از فیلتر MIME
 * و اجتناب از قرار دادن نوع کلی wildcard درون EXTRA_MIME_TYPES که در برخی رام‌های اندروید خطا می‌دهد.
 */
class OpenBackupDocumentContract(
    private val includeOpenableCategory: Boolean = true
) : ActivityResultContracts.OpenDocument() {

    override fun createIntent(context: Context, input: Array<String>): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            if (includeOpenableCategory) {
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            type = "*/*"
            if (input.isNotEmpty() && !(input.size == 1 && input[0] == "*/*")) {
                putExtra(Intent.EXTRA_MIME_TYPES, input)
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OpenBackupDocumentContract) return false
        return includeOpenableCategory == other.includeOpenableCategory
    }

    override fun hashCode(): Int = includeOpenableCategory.hashCode()
}

/**
 * قرارداد پشتیبان GET_CONTENT برای دستگاه‌هایی که DocumentsUI روی آن‌ها غیرفعال یا سفارشی است.
 */
class GetBackupContentContract(
    private val includeOpenableCategory: Boolean = true
) : ActivityResultContracts.GetContent() {

    override fun createIntent(context: Context, input: String): Intent {
        return super.createIntent(context, input).apply {
            if (includeOpenableCategory) {
                addCategory(Intent.CATEGORY_OPENABLE)
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GetBackupContentContract) return false
        return includeOpenableCategory == other.includeOpenableCategory
    }

    override fun hashCode(): Int = includeOpenableCategory.hashCode()
}

fun launchBackupExportWithFallbacks(
    suggestedFileName: String,
    primaryLauncher: ActivityResultLauncher<String>,
    fallbackLauncher: ActivityResultLauncher<String>
): BackupPickerLaunchOutcome {
    return try {
        primaryLauncher.launch(suggestedFileName)
        BackupPickerLaunchOutcome.Launched
    } catch (e: ActivityNotFoundException) {
        Log.w(SAF_PICKER_TAG, "Primary CreateDocument picker unavailable; trying wildcard fallback", e)
        tryWildcardExportFallback(suggestedFileName, fallbackLauncher)
    } catch (e: SecurityException) {
        Log.e(SAF_PICKER_TAG, "SecurityException launching backup export picker", e)
        BackupPickerLaunchOutcome.Failed("دسترسی سیستم به انتخابگر فایل محدود شده است.")
    } catch (e: IllegalStateException) {
        Log.w(SAF_PICKER_TAG, "Primary CreateDocument launcher illegal state; trying fallback", e)
        tryWildcardExportFallback(suggestedFileName, fallbackLauncher)
    } catch (e: Throwable) {
        Log.w(SAF_PICKER_TAG, "Unexpected error on primary CreateDocument; trying fallback", e)
        tryWildcardExportFallback(suggestedFileName, fallbackLauncher)
    }
}

private fun tryWildcardExportFallback(
    suggestedFileName: String,
    fallbackLauncher: ActivityResultLauncher<String>
): BackupPickerLaunchOutcome {
    return try {
        fallbackLauncher.launch(suggestedFileName)
        BackupPickerLaunchOutcome.Launched
    } catch (fallbackEx: ActivityNotFoundException) {
        Log.e(SAF_PICKER_TAG, "No SAF CreateDocument handler available on device", fallbackEx)
        BackupPickerLaunchOutcome.Failed("برنامه مدیریت فایل دستگاه برای ذخیره پشتیبان در دسترس نیست.")
    } catch (fallbackEx: SecurityException) {
        Log.e(SAF_PICKER_TAG, "SecurityException launching fallback CreateDocument picker", fallbackEx)
        BackupPickerLaunchOutcome.Failed("دسترسی سیستم به انتخابگر فایل محدود شده است.")
    } catch (fallbackEx: IllegalStateException) {
        Log.e(SAF_PICKER_TAG, "IllegalStateException launching fallback CreateDocument picker", fallbackEx)
        BackupPickerLaunchOutcome.Failed("امکان باز کردن صفحه ذخیره فایل پشتیبان وجود ندارد (خطای ثبت لانچر).")
    } catch (fallbackErr: Throwable) {
        Log.e(SAF_PICKER_TAG, "Fallback CreateDocument launch failed", fallbackErr)
        BackupPickerLaunchOutcome.Failed("امکان باز کردن صفحه ذخیره فایل پشتیبان وجود ندارد.")
    }
}

fun launchBackupRestoreWithFallbacks(
    openDocumentLauncher: ActivityResultLauncher<Array<String>>,
    getContentLauncher: ActivityResultLauncher<String>
): BackupPickerLaunchOutcome {
    val preferredMimeTypes = arrayOf("application/json", "text/plain", "application/octet-stream")
    return try {
        openDocumentLauncher.launch(preferredMimeTypes)
        BackupPickerLaunchOutcome.Launched
    } catch (e: ActivityNotFoundException) {
        Log.w(SAF_PICKER_TAG, "Typed OpenDocument picker unavailable; trying */* OpenDocument & GetContent fallbacks", e)
        tryWildcardAndGetContentRestoreFallback(openDocumentLauncher, getContentLauncher)
    } catch (e: SecurityException) {
        Log.e(SAF_PICKER_TAG, "SecurityException launching typed OpenDocument picker", e)
        BackupPickerLaunchOutcome.Failed("دسترسی سیستم به انتخابگر فایل محدود شده است.")
    } catch (e: IllegalStateException) {
        Log.w(SAF_PICKER_TAG, "Typed OpenDocument launcher illegal state; trying fallbacks", e)
        tryWildcardAndGetContentRestoreFallback(openDocumentLauncher, getContentLauncher)
    } catch (e: Throwable) {
        Log.w(SAF_PICKER_TAG, "Unexpected error launching typed OpenDocument; trying fallbacks", e)
        tryWildcardAndGetContentRestoreFallback(openDocumentLauncher, getContentLauncher)
    }
}

private fun tryWildcardAndGetContentRestoreFallback(
    openDocumentLauncher: ActivityResultLauncher<Array<String>>,
    getContentLauncher: ActivityResultLauncher<String>
): BackupPickerLaunchOutcome {
    return try {
        openDocumentLauncher.launch(arrayOf("*/*"))
        BackupPickerLaunchOutcome.Launched
    } catch (openAllEx: ActivityNotFoundException) {
        Log.w(SAF_PICKER_TAG, "Wildcard OpenDocument unavailable; trying GetContent fallback", openAllEx)
        tryGetContentFallback(getContentLauncher)
    } catch (openAllEx: SecurityException) {
        Log.e(SAF_PICKER_TAG, "SecurityException launching wildcard OpenDocument picker", openAllEx)
        BackupPickerLaunchOutcome.Failed("دسترسی سیستم به انتخابگر فایل محدود شده است.")
    } catch (openAllEx: Throwable) {
        Log.w(SAF_PICKER_TAG, "Wildcard OpenDocument failed; trying GetContent fallback", openAllEx)
        tryGetContentFallback(getContentLauncher)
    }
}

private fun tryGetContentFallback(
    getContentLauncher: ActivityResultLauncher<String>
): BackupPickerLaunchOutcome {
    return try {
        getContentLauncher.launch("*/*")
        BackupPickerLaunchOutcome.Launched
    } catch (getContentEx: ActivityNotFoundException) {
        Log.e(SAF_PICKER_TAG, "No SAF OpenDocument or GetContent handler available on device", getContentEx)
        BackupPickerLaunchOutcome.Failed("برنامه مدیریت فایل دستگاه برای انتخاب نسخه پشتیبان در دسترس نیست.")
    } catch (getContentEx: SecurityException) {
        Log.e(SAF_PICKER_TAG, "SecurityException launching GetContent picker", getContentEx)
        BackupPickerLaunchOutcome.Failed("دسترسی سیستم به انتخابگر فایل محدود شده است.")
    } catch (getContentEx: IllegalStateException) {
        Log.e(SAF_PICKER_TAG, "IllegalStateException launching GetContent picker", getContentEx)
        BackupPickerLaunchOutcome.Failed("امکان باز کردن انتخابگر فایل پشتیبان وجود ندارد (خطای ثبت لانچر).")
    } catch (getContentErr: Throwable) {
        Log.e(SAF_PICKER_TAG, "GetContent fallback launch failed", getContentErr)
        BackupPickerLaunchOutcome.Failed("امکان باز کردن انتخابگر فایل پشتیبان وجود ندارد.")
    }
}
