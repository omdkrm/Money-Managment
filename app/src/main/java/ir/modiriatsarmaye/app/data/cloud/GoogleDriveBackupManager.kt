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
 * مدل متادیتای پشتیبان ابری Google Drive.
 * توجه: احراز هویت و ورود به حساب گوگل از برنامه حذف شده است؛ بنابراین پشتیبان‌گیری ابری
 * مستلزم احراز هویت گوگل غیرفعال بوده و پشتیبان‌گیری استاندارد JSON به صورت محلی انجام می‌شود.
 */
class GoogleDriveBackupManager(
    @Suppress("unused") private val context: Context
) {
    companion object {
        private const val TAG = "GoogleDriveBackup"

        const val DRIVE_FOLDER_NAME = "مدیریت سرمایه - Backup"
        const val DRIVE_API_FILES_URL = "https://www.googleapis.com/drive/v3/files"
        const val DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
        const val AUTH_REMOVED_MESSAGE = "احراز هویت حساب گوگل از برنامه حذف شده است؛ از پشتیبان‌گیری محلی JSON استفاده کنید."
    }

    fun isConfigured(): Boolean = false

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
    suspend fun uploadBackupToDrive(jsonContent: String, @Suppress("unused") accountEmail: String?): Result<DriveBackupInfo> = withContext(Dispatchers.IO) {
        Result.failure(Exception(AUTH_REMOVED_MESSAGE))
    }

    suspend fun downloadBackupFromDrive(@Suppress("unused") accountEmail: String?): Result<DriveBackupInfo> = withContext(Dispatchers.IO) {
        Result.failure(Exception(AUTH_REMOVED_MESSAGE))
    }

    suspend fun listAvailableBackups(): Result<List<Pair<String, String>>> = withContext(Dispatchers.IO) {
        Result.failure(Exception(AUTH_REMOVED_MESSAGE))
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
