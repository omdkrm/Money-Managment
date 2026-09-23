package ir.modiriatsarmaye.app.data.cloud

import android.content.Context
import android.util.Log
import ir.modiriatsarmaye.app.data.backup.SafeBackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class GoogleAccountInfo(
    val email: String,
    val displayName: String?,
    val connectedAt: Long = System.currentTimeMillis()
)

data class DriveBackupInfo(
    val fileId: String,
    val fileName: String,
    val sizeBytes: Long,
    val timestamp: Long,
    val transactionsCount: Int,
    val pricesCount: Int,
    val goalsCount: Int,
    val liabilitiesCount: Int,
    val rawJson: String
)

/**
 * مدیریت واقعی پشتیبان‌گیری ابری در Google Drive با استفاده از Google Drive REST API v3
 * 
 * اصول کلیدی:
 * ۱. هرگز موفقیت جعلی گزارش نمی‌شود؛ تنها در صورت آپلود قطعی و تأیید شناسه فایل در گوگل درایو موفقیت ثبت می‌گردد.
 * ۲. اگر دسترسی یا شناسه کلاینت تنظیم نشده باشد، صراحتاً پیام «پشتیبانگیری ابری هنوز تنظیم نشده است» ارائه می‌شود.
 * ۳. توکن‌ها و مشخصات هویتی درون فایل خروجی JSON ذخیره نمی‌شوند و در لاگ‌ها درج نمی‌گردند.
 */
class GoogleDriveBackupManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "GoogleDriveBackup"
        private const val PREFS_NAME = "google_drive_secure_prefs"
        private const val KEY_ACCESS_TOKEN = "oauth_access_token"
        private const val KEY_ACCOUNT_EMAIL = "oauth_account_email"
        private const val KEY_ACCOUNT_NAME = "oauth_account_name"
        private const val KEY_CONNECTED_AT = "oauth_connected_at"

        const val DRIVE_FOLDER_NAME = "مدیریت سرمایه - Backup"
        const val DRIVE_API_FILES_URL = "https://www.googleapis.com/drive/v3/files"
        const val DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
    }

    private val securePrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * ذخیره امن اعتبارنامه OAuth در SharedPreferences خصوصی
     * عدم ذخیره کلمه عبور و عدم درج توکن در لاگ‌ها
     */
    fun saveOAuthCredentials(email: String, displayName: String?, accessToken: String?) {
        securePrefs.edit()
            .putString(KEY_ACCOUNT_EMAIL, email.trim())
            .putString(KEY_ACCOUNT_NAME, displayName?.trim())
            .putString(KEY_ACCESS_TOKEN, accessToken?.trim())
            .putLong(KEY_CONNECTED_AT, System.currentTimeMillis())
            .apply()
    }

    fun getConnectedAccount(): GoogleAccountInfo? {
        val email = securePrefs.getString(KEY_ACCOUNT_EMAIL, null) ?: return null
        val name = securePrefs.getString(KEY_ACCOUNT_NAME, null)
        val connectedAt = securePrefs.getLong(KEY_CONNECTED_AT, 0L)
        return GoogleAccountInfo(email = email, displayName = name, connectedAt = connectedAt)
    }

    fun getAccessToken(): String? {
        return securePrefs.getString(KEY_ACCESS_TOKEN, null)
    }

    fun isConfigured(): Boolean {
        val token = getAccessToken()
        return !token.isNullOrBlank()
    }

    /**
     * قطع اتصال و پاک‌سازی کامل نشست و توکن‌ها
     */
    fun clearCloudSession() {
        securePrefs.edit().clear().apply()
    }

    /**
     * بررسی و اعتبارسنجی ساختار فایل پشتیبان
     */
    fun validateBackupJson(jsonString: String): Result<DriveBackupInfo> {
        val report = SafeBackupManager.validateAndParseBackup(jsonString)
        if (!report.isValid) {
            val err = report.errors.firstOrNull() ?: "ساختار فایل پشتیبان نامعتبر است."
            return Result.failure(Exception(err))
        }

        val ts = System.currentTimeMillis()
        return Result.success(
            DriveBackupInfo(
                fileId = "",
                fileName = "modiriat_sarmaye_backup_${SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date(ts))}.json",
                sizeBytes = jsonString.toByteArray(Charsets.UTF_8).size.toLong(),
                timestamp = ts,
                transactionsCount = report.validTransactions.size,
                pricesCount = report.validPrices.size,
                goalsCount = report.validGoals.size,
                liabilitiesCount = report.validLiabilities.size,
                rawJson = jsonString
            )
        )
    }

    /**
     * ارسال واقعی فایل پشتیبان به Google Drive از طریق REST API
     */
    suspend fun uploadBackupToDrive(jsonContent: String, accountEmail: String?): Result<DriveBackupInfo> = withContext(Dispatchers.IO) {
        if (accountEmail.isNullOrBlank()) {
            return@withContext Result.failure(Exception("برای پشتیبانگیری ابری ابتدا حساب Google را متصل کنید."))
        }

        val token = getAccessToken()
        if (token.isNullOrBlank()) {
            // محیط فاقد کلاینت OAuth فعال Google Drive است
            return@withContext Result.failure(Exception("پشتیبانگیری ابری هنوز تنظیم نشده است"))
        }

        val validation = validateBackupJson(jsonContent)
        if (validation.isFailure) {
            return@withContext validation
        }

        val parsed = validation.getOrThrow()

        try {
            // ۱. یافتن یا ایجاد پوشه پشتیبان در گوگل درایو
            val folderIdResult = getOrCreateBackupFolder(token)
            if (folderIdResult.isFailure) {
                return@withContext Result.failure(folderIdResult.exceptionOrNull() ?: Exception("خطا در ایجاد پوشه در Google Drive"))
            }
            val folderId = folderIdResult.getOrThrow()

            // ۲. آپلود فایل با متد multipart
            val uploadResult = performMultipartUpload(
                token = token,
                fileName = parsed.fileName,
                jsonContent = jsonContent,
                parentFolderId = folderId
            )
            if (uploadResult.isFailure) {
                return@withContext Result.failure(uploadResult.exceptionOrNull() ?: Exception("بارگذاری پشتیبان در Google Drive انجام نشد."))
            }

            val uploadedFileId = uploadResult.getOrThrow()

            // ۳. بررسی و راستی‌آزمایی وجود فایل و متادیتای آن در Google Drive
            val verifyResult = verifyFileInDrive(token, uploadedFileId)
            if (verifyResult.isFailure) {
                return@withContext Result.failure(verifyResult.exceptionOrNull() ?: Exception("تأیید بارگذاری فایل در Google Drive ناموفق بود."))
            }

            val verifiedInfo = parsed.copy(fileId = uploadedFileId)
            Result.success(verifiedInfo)
        } catch (e: Exception) {
            val friendlyMsg = mapExceptionToPersian(e)
            Result.failure(Exception(friendlyMsg))
        }
    }

    /**
     * دریافت لیست و آخرین فایل پشتیبان از Google Drive
     */
    suspend fun downloadBackupFromDrive(accountEmail: String?): Result<DriveBackupInfo> = withContext(Dispatchers.IO) {
        if (accountEmail.isNullOrBlank()) {
            return@withContext Result.failure(Exception("برای پشتیبانگیری ابری ابتدا حساب Google را متصل کنید."))
        }

        val token = getAccessToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(Exception("پشتیبانگیری ابری هنوز تنظیم نشده است"))
        }

        try {
            // ۱. جستجوی فایل‌های پشتیبان
            val filesResult = listDriveBackupFiles(token)
            if (filesResult.isFailure) {
                return@withContext Result.failure(filesResult.exceptionOrNull() ?: Exception("خطا در دریافت لیست پشتیبان‌ها از Google Drive"))
            }
            val files = filesResult.getOrThrow()
            if (files.isEmpty()) {
                return@withContext Result.failure(Exception("هیچ نسخه پشتیبانی در حساب Google Drive شما یافت نشد."))
            }

            val latestFile = files.first()
            val fileId = latestFile.first
            val fileName = latestFile.second

            // ۲. دانلود محتوای فایل
            val contentResult = downloadDriveFileContent(token, fileId)
            if (contentResult.isFailure) {
                return@withContext Result.failure(contentResult.exceptionOrNull() ?: Exception("خطا در دانلود فایل پشتیبان از Google Drive"))
            }
            val jsonContent = contentResult.getOrThrow()

            // ۳. اعتبارسنجی کامل JSON با SafeBackupManager
            val report = SafeBackupManager.validateAndParseBackup(jsonContent)
            if (!report.isValid) {
                return@withContext Result.failure(Exception("فایل پشتیبان دانلود شده معتبر نیست یا آسیب دیده است."))
            }

            val info = DriveBackupInfo(
                fileId = fileId,
                fileName = fileName,
                sizeBytes = jsonContent.toByteArray(Charsets.UTF_8).size.toLong(),
                timestamp = System.currentTimeMillis(),
                transactionsCount = report.validTransactions.size,
                pricesCount = report.validPrices.size,
                goalsCount = report.validGoals.size,
                liabilitiesCount = report.validLiabilities.size,
                rawJson = jsonContent
            )
            Result.success(info)
        } catch (e: Exception) {
            val friendlyMsg = mapExceptionToPersian(e)
            Result.failure(Exception(friendlyMsg))
        }
    }

    /**
     * لیست فایل‌های پشتیبان موجود در گوگل درایو کاربر
     */
    suspend fun listAvailableBackups(): Result<List<Pair<String, String>>> = withContext(Dispatchers.IO) {
        val token = getAccessToken()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(Exception("پشتیبانگیری ابری هنوز تنظیم نشده است"))
        }
        listDriveBackupFiles(token)
    }

    private fun getOrCreateBackupFolder(token: String): Result<String> {
        val query = "name = '$DRIVE_FOLDER_NAME' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$DRIVE_API_FILES_URL?q=$encodedQuery&spaces=drive&fields=files(id,name)"

        val conn = (URL(searchUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 15000
            readTimeout = 15000
        }

        val code = conn.responseCode
        if (code == 401) return Result.failure(Exception("دسترسی حساب Google منقضی شده است؛ دوباره وارد شوید."))
        if (code == 403) return Result.failure(Exception("اجازه دسترسی به Google Drive داده نشد."))
        if (code !in 200..299) return Result.failure(Exception("خطا در بررسی پوشه Google Drive (کد $code)"))

        val response = conn.inputStream.bufferedReader().use { it.readText() }
        val root = JSONObject(response)
        val files = root.optJSONArray("files")
        if (files != null && files.length() > 0) {
            val folderId = files.getJSONObject(0).getString("id")
            return Result.success(folderId)
        }

        // اگر پوشه وجود ندارد، ایجاد پوشه
        val createConn = (URL(DRIVE_API_FILES_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
        }

        val folderMeta = JSONObject().apply {
            put("name", DRIVE_FOLDER_NAME)
            put("mimeType", "application/vnd.google-apps.folder")
        }

        OutputStreamWriter(createConn.outputStream, "UTF-8").use { it.write(folderMeta.toString()) }

        val createCode = createConn.responseCode
        if (createCode !in 200..299) {
            return Result.failure(Exception("ایجاد پوشه پشتیبان در Google Drive ناموفق بود."))
        }

        val createResp = createConn.inputStream.bufferedReader().use { it.readText() }
        val createdId = JSONObject(createResp).getString("id")
        return Result.success(createdId)
    }

    private fun performMultipartUpload(
        token: String,
        fileName: String,
        jsonContent: String,
        parentFolderId: String
    ): Result<String> {
        val boundary = "====${System.currentTimeMillis()}===="
        val conn = (URL(DRIVE_UPLOAD_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            doOutput = true
            connectTimeout = 30000
            readTimeout = 30000
        }

        val metadata = JSONObject().apply {
            put("name", fileName)
            put("parents", JSONArray().put(parentFolderId))
            put("mimeType", "application/json")
        }

        conn.outputStream.use { os ->
            val writer = OutputStreamWriter(os, "UTF-8")
            // قسمت ۱: متادیتا
            writer.write("--$boundary\r\n")
            writer.write("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            writer.write(metadata.toString())
            writer.write("\r\n")

            // قسمت ۲: محتوای فایل
            writer.write("--$boundary\r\n")
            writer.write("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            writer.flush()

            os.write(jsonContent.toByteArray(Charsets.UTF_8))
            os.flush()

            writer.write("\r\n--$boundary--\r\n")
            writer.flush()
        }

        val code = conn.responseCode
        if (code == 401) return Result.failure(Exception("دسترسی حساب Google منقضی شده است؛ دوباره وارد شوید."))
        if (code == 403) return Result.failure(Exception("اجازه دسترسی به Google Drive داده نشد."))
        if (code !in 200..299) {
            return Result.failure(Exception("بارگذاری پشتیبان در Google Drive انجام نشد."))
        }

        val resp = conn.inputStream.bufferedReader().use { it.readText() }
        val fileId = JSONObject(resp).optString("id", "")
        if (fileId.isBlank()) {
            return Result.failure(Exception("بارگذاری پشتیبان در Google Drive انجام نشد (شناسه فایل بازگردانده نشد)."))
        }

        return Result.success(fileId)
    }

    private fun verifyFileInDrive(token: String, fileId: String): Result<Boolean> {
        val verifyUrl = "$DRIVE_API_FILES_URL/$fileId?fields=id,name,size,trashed"
        val conn = (URL(verifyUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 15000
            readTimeout = 15000
        }

        val code = conn.responseCode
        if (code !in 200..299) {
            return Result.failure(Exception("تأیید ذخیره‌سازی فایل در Google Drive ناموفق بود."))
        }

        val resp = conn.inputStream.bufferedReader().use { it.readText() }
        val obj = JSONObject(resp)
        val trashed = obj.optBoolean("trashed", false)
        val exists = obj.optString("id") == fileId && !trashed
        return if (exists) Result.success(true) else Result.failure(Exception("فایل در Google Drive یافت نشد."))
    }

    private fun listDriveBackupFiles(token: String): Result<List<Pair<String, String>>> {
        val query = "name contains 'modiriat_sarmaye_backup' and trashed = false"
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$DRIVE_API_FILES_URL?q=$encodedQuery&orderBy=createdTime desc&fields=files(id,name,size,createdTime)"

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 15000
            readTimeout = 15000
        }

        val code = conn.responseCode
        if (code == 401) return Result.failure(Exception("دسترسی حساب Google منقضی شده است؛ دوباره وارد شوید."))
        if (code == 403) return Result.failure(Exception("اجازه دسترسی به Google Drive داده نشد."))
        if (code !in 200..299) return Result.failure(Exception("خطا در خواندن فایل‌های Google Drive"))

        val resp = conn.inputStream.bufferedReader().use { it.readText() }
        val arr = JSONObject(resp).optJSONArray("files") ?: JSONArray()
        val list = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            list.add(Pair(item.getString("id"), item.getString("name")))
        }
        return Result.success(list)
    }

    private fun downloadDriveFileContent(token: String, fileId: String): Result<String> {
        val url = "$DRIVE_API_FILES_URL/$fileId?alt=media"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 30000
            readTimeout = 30000
        }

        val code = conn.responseCode
        if (code == 401) return Result.failure(Exception("دسترسی حساب Google منقضی شده است؛ دوباره وارد شوید."))
        if (code == 403) return Result.failure(Exception("اجازه دسترسی به Google Drive داده نشد."))
        if (code !in 200..299) return Result.failure(Exception("خطا در دریافت محتوای پشتیبان از Google Drive"))

        val content = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return Result.success(content)
    }

    private fun mapExceptionToPersian(e: Exception): String {
        val msg = e.message ?: ""
        return when {
            e is java.net.UnknownHostException || e is java.net.ConnectException -> "اتصال اینترنت برقرار نیست."
            e is java.net.SocketTimeoutException -> "مهلت اتصال به سرور Google به پایان رسید."
            msg.contains("401") || msg.contains("منقضی") -> "دسترسی حساب Google منقضی شده است؛ دوباره وارد شوید."
            msg.contains("403") || msg.contains("اجازه") -> "اجازه دسترسی به Google Drive داده نشد."
            msg.isNotBlank() -> msg
            else -> "بارگذاری پشتیبان در Google Drive انجام نشد."
        }
    }
}
