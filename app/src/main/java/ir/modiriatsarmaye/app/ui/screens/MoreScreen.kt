package ir.modiriatsarmaye.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.modiriatsarmaye.app.data.backup.BackupValidationReport
import ir.modiriatsarmaye.app.data.backup.SafeBackupManager
import ir.modiriatsarmaye.app.data.cloud.DriveBackupInfo
import ir.modiriatsarmaye.app.data.model.*
import ir.modiriatsarmaye.app.ui.components.ConfirmationDialog
import ir.modiriatsarmaye.app.ui.components.StatusBadge
import ir.modiriatsarmaye.app.ui.theme.*
import ir.modiriatsarmaye.app.ui.viewmodel.WealthUiState
import ir.modiriatsarmaye.app.util.PersianUtils
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MoreScreen(
    uiState: WealthUiState,
    onSaveSettings: (AppSettingsEntity) -> Unit,
    onExportBackup: suspend () -> String,
    onRestoreBackup: (String, Boolean) -> Unit,
    onResetDatabase: () -> Unit,
    onSyncMarketPrices: () -> Unit,
    onConnectGoogleAccount: (String, String) -> Unit,
    onDisconnectGoogleAccount: () -> Unit,
    onPerformGoogleDriveBackup: () -> Unit,
    onGetGoogleDriveBackupPreview: ((DriveBackupInfo?) -> Unit) -> Unit,
    onRestoreFromGoogleDrive: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val settings = uiState.settings
    val scope = rememberCoroutineScope()

    var showResetConfirmDialog by remember { mutableStateOf(false) }
    var showSettingsEditorDialog by remember { mutableStateOf(false) }

    // بازخورد عملیات پشتیبان‌گیری فایلی
    var fileOpSuccessMessage by remember { mutableStateOf<String?>(null) }
    var fileOpErrorMessage by remember { mutableStateOf<String?>(null) }

    // وضعیت بازیابی فایل محلی SAF
    var pendingFileRestoreJson by remember { mutableStateOf<String?>(null) }
    var pendingFileRestoreReport by remember { mutableStateOf<BackupValidationReport?>(null) }
    var showFileRestoreConfirmDialog by remember { mutableStateOf(false) }

    // Google Drive Dialogs
    var showGoogleConnectDialog by remember { mutableStateOf(false) }
    var googleEmailInput by remember { mutableStateOf("") }
    var googleNameInput by remember { mutableStateOf("") }
    var showDisconnectGoogleConfirm by remember { mutableStateOf(false) }
    var showCloudRestoreConfirmDialog by remember { mutableStateOf(false) }
    var cloudBackupPreviewInfo by remember { mutableStateOf<DriveBackupInfo?>(null) }
    var isCheckingCloudBackup by remember { mutableStateOf(false) }

    // ثبت لانچر ذخیره فایل در Storage Access Framework با نوع MIME application/json
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val jsonContent = onExportBackup()
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(jsonContent.toByteArray(Charsets.UTF_8))
                    } ?: throw IOException("عدم دسترسی به مسیر فایل انتخابی جهت ذخیره‌سازی")
                    withContext(Dispatchers.Main) {
                        fileOpSuccessMessage = "پشتیبان با موفقیت در فایل ذخیره شد."
                        fileOpErrorMessage = null
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        fileOpErrorMessage = "ذخیره فایل پشتیبان انجام نشد: ${e.localizedMessage ?: "خطای ناشناخته"}"
                        fileOpSuccessMessage = null
                    }
                }
            }
        }
    }

    // ثبت لانچر انتخاب و بازخوانی فایل پشتیبان JSON از حافظه دستگاه
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val jsonContent = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        inputStream.bufferedReader(Charsets.UTF_8).readText()
                    } ?: throw IOException("امکان خواندن فایل انتخاب شده وجود ندارد.")

                    val report = SafeBackupManager.validateAndParseBackup(jsonContent)
                    withContext(Dispatchers.Main) {
                        if (!report.isValid) {
                            fileOpErrorMessage = report.errors.firstOrNull() ?: "فایل پشتیبان نامعتبر است."
                            fileOpSuccessMessage = null
                        } else {
                            pendingFileRestoreJson = jsonContent
                            pendingFileRestoreReport = report
                            showFileRestoreConfirmDialog = true
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        fileOpErrorMessage = "خواندن فایل پشتیبان انجام نشد: ${e.localizedMessage ?: "خطای ناشناخته"}"
                        fileOpSuccessMessage = null
                    }
                }
            }
        }
    }

    // دیالوگ اتصال حساب Google
    if (showGoogleConnectDialog) {
        AlertDialog(
            onDismissRequest = { showGoogleConnectDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.CloudSync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("اتصال حساب Google جهت پشتیبان‌گیری ابری", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "با اتصال حساب Google، نسخه پشتیبان رمزنگاری‌شده از پرتفوی شما در فضای ابری خصوصی Google Drive ذخیره شده و از هر دستگاهی قابل بازیابی است.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = googleEmailInput,
                        onValueChange = { googleEmailInput = it },
                        label = { Text("آدرس ایمیل گوگل (Gmail)") },
                        placeholder = { Text("example@gmail.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = googleNameInput,
                        onValueChange = { googleNameInput = it },
                        label = { Text("نام نمایشی (اختیاری)") },
                        placeholder = { Text("نام شما") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (googleEmailInput.isNotBlank()) {
                            onConnectGoogleAccount(
                                googleEmailInput.trim(),
                                if (googleNameInput.isNotBlank()) googleNameInput.trim() else "کاربر مدیریت سرمایه"
                            )
                            showGoogleConnectDialog = false
                        }
                    },
                    enabled = googleEmailInput.contains("@")
                ) {
                    Text("اتصال و ذخیره")
                }
            },
            dismissButton = {
                TextButton(onClick = { showGoogleConnectDialog = false }) { Text("انصراف") }
            }
        )
    }

    // دیالوگ تأیید بازیابی از فایل با نمایش مشخصات نسخه پشتیبان و انتخاب نحوه ادغام / جایگزینی
    if (showFileRestoreConfirmDialog && pendingFileRestoreJson != null && pendingFileRestoreReport != null) {
        val rep = pendingFileRestoreReport!!
        AlertDialog(
            onDismissRequest = { showFileRestoreConfirmDialog = false },
            title = {
                Text("تأیید بازیابی از فایل پشتیبان", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "مشخصات نسخه پشتیبان خوانده‌شده از فایل JSON:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("• تعداد تراکنش‌های معتبر: ${PersianUtils.formatNumber(rep.validTransactions.size.toDouble())} مورد", style = MaterialTheme.typography.bodySmall)
                            Text("• تعداد قیمت‌های روز: ${PersianUtils.formatNumber(rep.validPrices.size.toDouble())} مورد", style = MaterialTheme.typography.bodySmall)
                            Text("• اهداف مالی: ${PersianUtils.formatNumber(rep.validGoals.size.toDouble())} مورد", style = MaterialTheme.typography.bodySmall)
                            Text("• بدهی‌ها و تعهدات: ${PersianUtils.formatNumber(rep.validLiabilities.size.toDouble())} مورد", style = MaterialTheme.typography.bodySmall)
                            if (rep.skippedTransactionsCount > 0) {
                                Text("• رکوردهای نامعتبر نادیده گرفته‌شده: ${PersianUtils.toPersianDigits(rep.skippedTransactionsCount.toString())}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "نحوه اعمال نسخه پشتیبان بر روی داده‌های فعلی را انتخاب نمایید:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val json = pendingFileRestoreJson!!
                            onRestoreBackup(json, false) // Merge
                            showFileRestoreConfirmDialog = false
                            fileOpSuccessMessage = "پشتیبان با موفقیت با داده‌های موجود ادغام گردید."
                        },
                        modifier = Modifier.testTag("confirm_restore_merge_btn")
                    ) {
                        Text("ادغام با داده‌های موجود")
                    }
                    Button(
                        onClick = {
                            val json = pendingFileRestoreJson!!
                            onRestoreBackup(json, true) // Replace
                            showFileRestoreConfirmDialog = false
                            fileOpSuccessMessage = "داده‌ها با موفقیت جایگزین شدند."
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.testTag("confirm_restore_replace_btn")
                    ) {
                        Text("جایگزینی کامل")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showFileRestoreConfirmDialog = false }) { Text("انصراف") }
            }
        )
    }

    // دیالوگ تأیید بازیابی از Google Drive با نمایش مشخصات نسخه پشتیبان و انتخاب جایگزینی یا ادغام
    if (showCloudRestoreConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showCloudRestoreConfirmDialog = false },
            title = {
                Text("تأیید بازیابی از Google Drive", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "اطلاعات نسخه پشتیبان موجود در Google Drive شما به شرح زیر است:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    if (cloudBackupPreviewInfo != null) {
                        val info = cloudBackupPreviewInfo!!
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("• نام فایل: ${info.fileName}", style = MaterialTheme.typography.bodySmall)
                                Text("• تعداد تراکنش‌ها: ${PersianUtils.formatNumber(info.transactionsCount.toDouble())} مورد", style = MaterialTheme.typography.bodySmall)
                                Text("• تعداد اهداف مالی: ${PersianUtils.formatNumber(info.goalsCount.toDouble())} مورد", style = MaterialTheme.typography.bodySmall)
                                Text("• تعداد بدهی‌ها: ${PersianUtils.formatNumber(info.liabilitiesCount.toDouble())} مورد", style = MaterialTheme.typography.bodySmall)
                                Text("• حجم فایل: ${PersianUtils.toPersianDigits((info.sizeBytes / 1024).toString())} کیلوبایت", style = MaterialTheme.typography.bodySmall)
                                Text("• تاریخ فایل: ${PersianUtils.formatTimestampToPersian(info.timestamp)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    } else {
                        Text("در حال بررسی فایل پشتیبان...", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "نحوه اعمال نسخه ابری بر روی داده‌های فعلی را انتخاب فرمایید:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            onRestoreFromGoogleDrive(false) // Merge
                            showCloudRestoreConfirmDialog = false
                        },
                        modifier = Modifier.testTag("cloud_restore_merge_btn")
                    ) {
                        Text("ادغام با داده‌های فعلی")
                    }
                    Button(
                        onClick = {
                            onRestoreFromGoogleDrive(true) // Replace
                            showCloudRestoreConfirmDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.testTag("cloud_restore_replace_btn")
                    ) {
                        Text("جایگزینی کامل")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloudRestoreConfirmDialog = false }) { Text("انصراف") }
            }
        )
    }

    // دیالوگ تأیید قطع اتصال Google
    if (showDisconnectGoogleConfirm) {
        ConfirmationDialog(
            title = "قطع اتصال حساب Google",
            message = "آیا از قطع اتصال حساب Google (${settings.googleAccountEmail}) اطمینان دارید؟ داده‌های محلی دستگاه شما بدون تغییر باقی می‌مانند.",
            confirmButtonText = "قطع اتصال",
            isDestructive = true,
            onConfirm = {
                onDisconnectGoogleAccount()
                showDisconnectGoogleConfirm = false
            },
            onDismiss = { showDisconnectGoogleConfirm = false }
        )
    }

    // دیالوگ بازنشانی به مقادیر اولیه
    if (showResetConfirmDialog) {
        ConfirmationDialog(
            title = "بازنشانی داده‌ها به حالت اولیه",
            message = "آیا از بازنشانی کلیه داده‌ها و تراکنش‌ها به نمونه‌های پیش‌فرض اولیه اطمینان دارید؟ کلیه تراکنش‌های دستی جدید حذف خواهند شد.",
            confirmButtonText = "بازنشانی داده‌ها",
            isDestructive = true,
            onConfirm = {
                onResetDatabase()
                showResetConfirmDialog = false
            },
            onDismiss = { showResetConfirmDialog = false }
        )
    }

    // دیالوگ ویرایش تنظیمات مالی
    if (showSettingsEditorDialog) {
        SettingsEditorDialog(
            initialSettings = settings,
            onDismiss = { showSettingsEditorDialog = false },
            onSave = {
                onSaveSettings(it)
                showSettingsEditorDialog = false
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                Text(
                    text = "تنظیمات، پشتیبان و راهنما",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "پیکربندی استراتژی سرمایه‌گذاری، پشتیبان‌گیری ابری و نرخ‌های بازار",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // بخش ۱: پشتیبان‌گیری ابری Google Drive (Cloud Backup)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("google_drive_backup_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CloudSync,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "پشتیبان‌گیری ابری Google Drive",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (!settings.googleAccountEmail.isNullOrBlank()) {
                            StatusBadge(text = "متصل", color = ProfitGreen)
                        } else {
                            StatusBadge(text = "غیرمتصل", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (settings.googleAccountEmail.isNullOrBlank()) {
                        Text(
                            text = "با اتصال حساب Google خود، می‌توانید از پرتفوی خود در فضای ابری خصوصی Google Drive پشتیبان تهیه کنید و به راحتی اطلاعات را بازیابی نمایید. برنامه همواره در حالت آفلاین نیز ۱۰۰٪ کارآمد باقی می‌ماند.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = {
                                googleEmailInput = ""
                                googleNameInput = ""
                                showGoogleConnectDialog = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("connect_google_account_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("اتصال حساب Google")
                        }
                    } else {
                        // اطلاعات حساب متصل
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "حساب کاربری: ${settings.googleAccountName ?: ""} (${settings.googleAccountEmail})",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (settings.lastGoogleDriveBackupTimestamp != null) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "آخرین پشتیبان ابری: ${PersianUtils.formatTimestampToPersian(settings.lastGoogleDriveBackupTimestamp)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (settings.lastGoogleDriveBackupSummary != null) {
                                        Text(
                                            text = "مشخصات فایل: ${settings.lastGoogleDriveBackupSummary}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                } else {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "هنوز نسخه‌ای در Google Drive ذخیره نشده است.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onPerformGoogleDriveBackup,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("backup_to_drive_btn"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                if (uiState.cloudBackupStatus == CloudBackupStatus.SYNCING) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                                } else {
                                    Icon(imageVector = Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("پشتیبان‌گیری ابری")
                            }

                            OutlinedButton(
                                onClick = {
                                    isCheckingCloudBackup = true
                                    onGetGoogleDriveBackupPreview { info ->
                                        isCheckingCloudBackup = false
                                        cloudBackupPreviewInfo = info
                                        if (info != null) {
                                            showCloudRestoreConfirmDialog = true
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("restore_from_drive_btn"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                if (isCheckingCloudBackup || uiState.cloudBackupStatus == CloudBackupStatus.RESTORING) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(imageVector = Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("بازیابی از Drive")
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        TextButton(
                            onClick = { showDisconnectGoogleConfirm = true },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text("قطع اتصال حساب Google", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // بخش ۲: تنظیمات و بروزرسانی قیمت‌های بازار
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("market_price_settings_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.QueryStats, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "بروزرسانی آنلاین نرخ‌های بازار",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "پوشش نرخ رسمی طلای ۱۸ عیار، سکه، سهام بورس تهران و صندوق‌های طلا/سهامی/درآمد ثابت (TSETMC). در صورت قطعی اینترنت، آخرین قیمت معتبر حفظ می‌شود.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (settings.lastPriceUpdateTimestamp > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "آخرین بروزرسانی بازار: ${PersianUtils.formatTimestampToPersian(settings.lastPriceUpdateTimestamp)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // زمان‌بندی بروزرسانی
                    Text(text = "رفتار به‌روزرسانی قیمت‌ها:", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PriceUpdateFrequency.values().forEach { freq ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        onSaveSettings(settings.copy(priceUpdateFrequency = freq))
                                    }
                                    .padding(vertical = 4.dp, horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = settings.priceUpdateFrequency == freq,
                                    onClick = { onSaveSettings(settings.copy(priceUpdateFrequency = freq)) }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = freq.titleFa, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = onSyncMarketPrices,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sync_market_prices_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (uiState.isMarketUpdating) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        } else {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("بروزرسانی آنلاین قیمت‌ها هم‌اکنون")
                    }
                }
            }
        }

        // بخش ۳: تنظیمات استراتژی و تخصیص هدف
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showSettingsEditorDialog = true }
                    .testTag("financial_settings_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "تنظیمات استراتژی و تخصیص هدف",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "• طلا و سکه: ${PersianUtils.toPersianDigits(settings.targetGoldPct.toInt().toString())}٪  • صندوق سهامی: ${PersianUtils.toPersianDigits(settings.targetEquityFundPct.toInt().toString())}٪  • درآمد ثابت: ${PersianUtils.toPersianDigits(settings.targetFixedIncomePct.toInt().toString())}٪  • ارز: ${PersianUtils.toPersianDigits(settings.targetUsdPct.toInt().toString())}٪  • نقد: ${PersianUtils.toPersianDigits(settings.targetCashPct.toInt().toString())}٪",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• سرمایه‌گذاری ماهانه: ${PersianUtils.formatMoney(settings.monthlyInvestmentToman, settings.displayCurrency)} • رشد سالانه: ${PersianUtils.toPersianDigits(settings.annualInvestmentIncreasePct.toInt().toString())}٪",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // بخش ۴: پشتیبان‌گیری محلی در فایل (آفلاین با استاندارد SAF)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("local_backup_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.FolderZip, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "پشتیبان‌گیری محلی در فایل (آفلاین)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "ذخیره یا بازخوانی فایل پشتیبان استاندارد JSON با سازگاری عقب‌رو مستقیماً در حافظه دستگاه بدون ارسال به سرور خارجی.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (fileOpSuccessMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = ProfitGreen.copy(alpha = 0.15f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = fileOpSuccessMessage!!,
                                color = ProfitGreen,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    if (fileOpErrorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = fileOpErrorMessage!!,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val timeFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)
                                val filename = "modiriat_sarmaye_backup_${timeFormat.format(Date())}.json"
                                createDocumentLauncher.launch(filename)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("export_file_backup_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("ذخیره فایل پشتیبان")
                        }

                        OutlinedButton(
                            onClick = {
                                openDocumentLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("restore_file_backup_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("بازیابی از فایل")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        onClick = { showResetConfirmDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("بازنشانی پایگاه داده به مقادیر پیش‌فرض اولیه", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // بخش ۵: وضعیت عملکرد، پایگاه داده و اتصالات (Diagnostics & Health)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("diagnostics_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Analytics, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "وضعیت عملکرد و پایش سیستم",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        StatusBadge(text = "پایدار", color = ProfitGreen)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "• پایگاه داده محلی (Room): ${PersianUtils.formatNumber(uiState.transactions.size.toDouble())} تراکنش • ${PersianUtils.formatNumber(uiState.portfolioSummary.holdings.size.toDouble())} دارایی فعال",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "• اهداف و تعهدات: ${PersianUtils.formatNumber(uiState.goals.size.toDouble())} هدف مالی • ${PersianUtils.formatNumber(uiState.liabilities.size.toDouble())} تعهد بدهی",
                                style = MaterialTheme.typography.bodySmall
                            )
                            val cacheTimeStr = if (settings.lastPriceUpdateTimestamp > 0) PersianUtils.formatTimestampToPersian(settings.lastPriceUpdateTimestamp) else "ثبت نشده"
                            Text(
                                text = "• کش نرخ‌های بازار: بروزرسانی در $cacheTimeStr",
                                style = MaterialTheme.typography.bodySmall
                            )
                            val cloudStateStr = if (!settings.googleAccountEmail.isNullOrBlank()) "متصل (${settings.googleAccountEmail}) - بدون افشای کلیدها" else "غیرمتصل (امن و خصوصی)"
                            Text(
                                text = "• امنیت و پشتیبان ابری: $cloudStateStr",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (!settings.googleAccountEmail.isNullOrBlank()) ProfitGreen else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "• بهینه‌سازی مصرف انرژی: فرکانس ${settings.priceUpdateFrequency.titleFa} (بدون پولینگ مداوم پس‌زمینه)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        // بخش ۵: راهنمای جامع ۱۰ مرحله‌ای سرمایه‌گذاری در ایران
        item {
            ComprehensivePersianGuideSection()
        }

        // بخش ۶: درباره برنامه
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "درباره «مدیریت سرمایه»",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "نسخه ۱.۰.۰ • معماری Local-first بادوام ۱۰ ساله برای محیط اقتصادی ایران • با پشتیبانی اختیاری از Google Drive و بروزرسانی آنلاین قیمت‌های بازار بورس و طلا.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun ComprehensivePersianGuideSection() {
    val steps = listOf(
        Pair("۱. تعریف جریان نقدی و انضباط مالی", "پیش از هر چیز، درآمدها و هزینه‌ها را تفکیک کنید. حداقل ۲۰ الی ۳۰ درصد از درآمد ماهانه را در ابتدای هر ماه به سرمایه‌گذاری منظم تخصیص دهید."),
        Pair("۲. صندوق اضطراری نقدشونده", "معادل ۳ الی ۶ ماه هزینه‌های ضروری زندگی را در دارایی‌های با نقدشوندگی بسیار بالا (مانند صندوق‌های درآمد ثابت بدون کارمزد) نگهداری کنید."),
        Pair("۳. نقش طلا و سکه در حفظ قدرت خرید", "طلا سپر سنتی در برابر تورم و نوسانات نرخ ارز است. نگهداری ترکیبی از طلای فیزیکی و صندوق‌های طلای بورس (ETF) به تعادل پرتفوی کمک می‌کند."),
        Pair("۴. صندوق‌های سرمایه‌گذاری سهامی (ETF)", "به جای خرید هیجانی سهام تکی، از صندوق‌های سهامی با مدیریت حرفه‌ای برای کسب بازدهی بلندمدت از رشد تولید و اقتصاد استفاده کنید."),
        Pair("۵. اصل تنوع‌بخشی (عدم قرار دادن تمام تخم‌مرغ‌ها در یک سبد)", "هیچ کلاسی از دارایی‌ها نباید بیش از ۴۰ درصد از کل پرتفوی شما را تشکیل دهد تا ریسک سقوط ناگهانی یک بازار مهار شود."),
        Pair("۶. بهره‌گیری از قدرت اعجاب‌انگیز سود مرکب", "سودهای دریافتی، سود مجامع (DPS) و عایدی صندوق‌ها را مجدداً سرمایه‌گذاری کنید تا در افق ۱۰ ساله ثروت شما تصاعدی رشد کند."),
        Pair("۷. بازتنظیم منظم سبد دارایی‌ها (Rebalancing)", "حداقل سالی ۲ بار یا در صورت انحراف بیش از ۵ درصد، با فروش بخشی از دارایی‌های رشدکرده و خرید دارایی‌های کمتر رشدکرده، تعادل را برقرار کنید."),
        Pair("۸. واقع‌گرایی نسبت به تورم و بازده واقعی", "همواره بازدهی اسمی را منهای نرخ تورم کنید. هدف اصلی هر سرمایه‌گذار در ایران، دستیابی به بازدهی فراتر از نرخ تورم ساختاری است."),
        Pair("۹. پایبندی به برنامه و پرهیز از تصمیمات هیجانی", "در زمان اصلاح‌های سنگین بازار یا رشدهای حبابی، به برنامه بلندمدت و استراتژی تخصیص هدف خود وفادار بمانید."),
        Pair("۱۰. ثبت دقیق و مداوم همه تراکنش‌ها در سامانه", "داشتن دفتر تراکنش دقیق و شفاف، دید جامع و بدون توهمی از عملکرد سرمایه‌گذاری به شما ارائه می‌دهد.")
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Filled.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "راهنمای جامع ۱۰ مرحله‌ای سرمایه‌گذاری",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            steps.forEach { (title, description) ->
                var expanded by remember { mutableStateOf(false) }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { expanded = !expanded },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Icon(
                                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        if (expanded) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsEditorDialog(
    initialSettings: AppSettingsEntity,
    onDismiss: () -> Unit,
    onSave: (AppSettingsEntity) -> Unit
) {
    var goldPct by remember { mutableStateOf(initialSettings.targetGoldPct.toInt().toString()) }
    var equityPct by remember { mutableStateOf(initialSettings.targetEquityFundPct.toInt().toString()) }
    var fixedPct by remember { mutableStateOf(initialSettings.targetFixedIncomePct.toInt().toString()) }
    var usdPct by remember { mutableStateOf(initialSettings.targetUsdPct.toInt().toString()) }
    var cashPct by remember { mutableStateOf(initialSettings.targetCashPct.toInt().toString()) }
    var monthlyInvest by remember { mutableStateOf(initialSettings.monthlyInvestmentToman.toLong().toString()) }
    var inflationRate by remember { mutableStateOf(initialSettings.inflationRatePct.toInt().toString()) }
    var annualIncrease by remember { mutableStateOf(initialSettings.annualInvestmentIncreasePct.toInt().toString()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ویرایش اهداف تخصیص و تنظیمات مالی", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                if (errorMessage != null) {
                    Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Text("تخصیص هدف دارایی‌ها (مجموع باید ۱۰۰٪ باشد):", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(6.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = goldPct, onValueChange = { goldPct = PersianUtils.toEnglishDigits(it) }, label = { Text("طلا ٪") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = equityPct, onValueChange = { equityPct = PersianUtils.toEnglishDigits(it) }, label = { Text("سهام ٪") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = fixedPct, onValueChange = { fixedPct = PersianUtils.toEnglishDigits(it) }, label = { Text("درآمد ثابت ٪") }, modifier = Modifier.weight(1f), singleLine = true)
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = usdPct, onValueChange = { usdPct = PersianUtils.toEnglishDigits(it) }, label = { Text("ارز ٪") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = cashPct, onValueChange = { cashPct = PersianUtils.toEnglishDigits(it) }, label = { Text("نقد ٪") }, modifier = Modifier.weight(1f), singleLine = true)
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = monthlyInvest,
                    onValueChange = { monthlyInvest = PersianUtils.toEnglishDigits(it) },
                    label = { Text("سرمایه‌گذاری ماهانه (تومان)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = annualIncrease, onValueChange = { annualIncrease = PersianUtils.toEnglishDigits(it) }, label = { Text("رشد سالانه پس‌انداز ٪") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = inflationRate, onValueChange = { inflationRate = PersianUtils.toEnglishDigits(it) }, label = { Text("نرخ تورم فرضی ٪") }, modifier = Modifier.weight(1f), singleLine = true)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val g = goldPct.toDoubleOrNull() ?: 0.0
                    val e = equityPct.toDoubleOrNull() ?: 0.0
                    val f = fixedPct.toDoubleOrNull() ?: 0.0
                    val u = usdPct.toDoubleOrNull() ?: 0.0
                    val c = cashPct.toDoubleOrNull() ?: 0.0
                    val sum = g + e + f + u + c

                    if (kotlin.math.abs(sum - 100.0) > 0.01) {
                        errorMessage = "مجموع درصدها باید دقیقاً ۱۰۰ باشد (فعلی: $sum)."
                        return@Button
                    }

                    val updated = initialSettings.copy(
                        targetGoldPct = g,
                        targetEquityFundPct = e,
                        targetFixedIncomePct = f,
                        targetUsdPct = u,
                        targetCashPct = c,
                        monthlyInvestmentToman = monthlyInvest.toDoubleOrNull() ?: initialSettings.monthlyInvestmentToman,
                        annualInvestmentIncreasePct = annualIncrease.toDoubleOrNull() ?: initialSettings.annualInvestmentIncreasePct,
                        inflationRatePct = inflationRate.toDoubleOrNull() ?: initialSettings.inflationRatePct
                    )
                    onSave(updated)
                }
            ) {
                Text("ذخیره تنظیمات")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف") }
        }
    )
}
