package ir.modiriatsarmaye.app.ui.screens

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
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
import ir.modiriatsarmaye.app.data.model.*
import ir.modiriatsarmaye.app.ui.backup.BackupPickerLaunchOutcome
import ir.modiriatsarmaye.app.ui.backup.BackupPickerUriEvent
import ir.modiriatsarmaye.app.ui.backup.CreateBackupDocumentContract
import ir.modiriatsarmaye.app.ui.backup.GetBackupContentContract
import ir.modiriatsarmaye.app.ui.backup.OpenBackupDocumentContract
import ir.modiriatsarmaye.app.ui.backup.launchBackupExportWithFallbacks
import ir.modiriatsarmaye.app.ui.backup.launchBackupRestoreWithFallbacks
import ir.modiriatsarmaye.app.ui.components.*
import ir.modiriatsarmaye.app.ui.theme.*
import ir.modiriatsarmaye.app.ui.viewmodel.WealthUiState
import ir.modiriatsarmaye.app.util.PersianUtils
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val BACKUP_UI_TAG = "MoreScreenBackup"
private const val MAX_BACKUP_FILE_BYTES = 15 * 1024 * 1024 // ۱۵ مگابایت سقف ایمن خواندن فایل پشتیبان

private fun readBackupUriUtf8Bounded(context: Context, uri: Uri): String {
    val resolver = context.contentResolver
    try {
        resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
            if (afd.length > MAX_BACKUP_FILE_BYTES) {
                throw IOException("حجم فایل انتخاب‌شده بسیار زیاد است (حداکثر ۱۵ مگابایت پشتیبانی می‌شود).")
            }
        }
    } catch (e: IOException) {
        throw e
    } catch (_: Exception) {
        // برخی ارائه‌دهنده‌های SAF طول فایل را گزارش نمی‌دهند؛ خواندن محدود در ادامه کنترل می‌شود
    }

    return resolver.openInputStream(uri)?.use { inputStream ->
        val buffer = ByteArray(8192)
        val out = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = inputStream.read(buffer)
            if (read == -1) break
            total += read
            if (total > MAX_BACKUP_FILE_BYTES) {
                throw IOException("حجم فایل انتخاب‌شده بیش از حد مجاز (۱۵ مگابایت) است.")
            }
            out.write(buffer, 0, read)
        }
        out.toString(Charsets.UTF_8.name())
    } ?: throw IOException("امکان خواندن فایل انتخاب شده وجود ندارد.")
}

private fun writeBackupUriUtf8(context: Context, uri: Uri, jsonContent: String) {
    val resolver = context.contentResolver
    val stream = try {
        resolver.openOutputStream(uri, "wt")
    } catch (_: Exception) {
        null
    } ?: resolver.openOutputStream(uri)
        ?: throw IOException("عدم دسترسی به مسیر فایل انتخابی جهت ذخیره‌سازی")

    stream.use { os ->
        os.write(jsonContent.toByteArray(Charsets.UTF_8))
        os.flush()
    }
}

@Composable
fun MoreScreen(
    uiState: WealthUiState,
    onSaveSettings: (AppSettingsEntity) -> Unit,
    onExportBackup: suspend () -> String,
    onRestoreBackup: (String, Boolean) -> Unit,
    @Suppress("UNUSED_PARAMETER") onResetDatabase: () -> Unit = {},
    onSyncMarketPrices: () -> Unit,
    onSetOrChangePin: (currentPin: String?, newPin: String, onComplete: (Boolean, String?) -> Unit) -> Unit = { _, _, cb -> cb(false, null) },
    onDisablePin: (currentPin: String, onComplete: (Boolean, String?) -> Unit) -> Unit = { _, cb -> cb(false, null) },
    onToggleBiometric: (Boolean) -> Unit = {},
    onLockAppNow: () -> Unit = {},
    onRestoreBackupWithResult: ((String, Boolean, (Boolean, String?) -> Unit) -> Unit)? = null,
    externalExportPickerEvent: BackupPickerUriEvent? = null,
    onConsumeExternalExportPickerEvent: (Long) -> Unit = {},
    externalRestorePickerEvent: BackupPickerUriEvent? = null,
    onConsumeExternalRestorePickerEvent: (Long) -> Unit = {},
    onLaunchExternalBackupExportPicker: ((suggestedFileName: String) -> BackupPickerLaunchOutcome)? = null,
    onLaunchExternalBackupRestorePicker: (() -> BackupPickerLaunchOutcome)? = null
) {
    val context = LocalContext.current
    val settings = uiState.settings
    val scope = rememberCoroutineScope()

    var showSettingsEditorDialog by remember { mutableStateOf(false) }

    // بازخورد و وضعیت اجرای عملیات پشتیبان‌گیری فایلی
    var fileOpSuccessMessage by remember { mutableStateOf<String?>(null) }
    var fileOpErrorMessage by remember { mutableStateOf<String?>(null) }
    var isBackupInProgress by remember { mutableStateOf(false) }
    var isRestoreInProgress by remember { mutableStateOf(false) }

    // وضعیت بازیابی فایل محلی SAF
    var pendingFileRestoreJson by remember { mutableStateOf<String?>(null) }
    var pendingFileRestoreReport by remember { mutableStateOf<BackupValidationReport?>(null) }
    var showFileRestoreConfirmDialog by remember { mutableStateOf(false) }

    fun handleCreateDocumentResult(uri: Uri?) {
        if (uri == null) {
            Log.i(BACKUP_UI_TAG, "Backup export picker canceled by user")
            isBackupInProgress = false
            return
        }
        isBackupInProgress = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val jsonContent = onExportBackup()
                    writeBackupUriUtf8(context, uri, jsonContent)
                }
                Log.i(BACKUP_UI_TAG, "Backup export completed successfully")
                fileOpSuccessMessage = "پشتیبان با موفقیت در فایل ذخیره شد."
                fileOpErrorMessage = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                Log.e(BACKUP_UI_TAG, "OutOfMemoryError during backup export", e)
                fileOpErrorMessage = "حافظه کافی برای ذخیره فایل پشتیبان در دسترس نبود."
                fileOpSuccessMessage = null
            } catch (e: SecurityException) {
                Log.e(BACKUP_UI_TAG, "SecurityException during backup export", e)
                fileOpErrorMessage = "مجوز دسترسی به مسیر ذخیره‌سازی فایل داده نشد."
                fileOpSuccessMessage = null
            } catch (e: Throwable) {
                Log.e(BACKUP_UI_TAG, "Backup export failed: ${e.javaClass.simpleName}", e)
                fileOpErrorMessage = "ذخیره فایل پشتیبان انجام نشد: ${e.localizedMessage ?: "خطای دسترسی به فایل"}"
                fileOpSuccessMessage = null
            } finally {
                isBackupInProgress = false
            }
        }
    }

    fun handleOpenDocumentResult(uri: Uri?) {
        if (uri == null) {
            Log.i(BACKUP_UI_TAG, "Backup restore picker canceled by user")
            isRestoreInProgress = false
            return
        }
        isRestoreInProgress = true
        scope.launch {
            try {
                val (jsonContent, report) = withContext(Dispatchers.IO) {
                    val content = readBackupUriUtf8Bounded(context, uri)
                    val validation = SafeBackupManager.validateAndParseBackup(content)
                    Pair(content, validation)
                }
                if (!report.isValid) {
                    Log.w(BACKUP_UI_TAG, "Selected backup failed validation")
                    fileOpErrorMessage = report.errors.firstOrNull() ?: "فایل پشتیبان نامعتبر است."
                    fileOpSuccessMessage = null
                } else {
                    Log.i(BACKUP_UI_TAG, "Backup file parsed and validated cleanly; opening confirmation dialog")
                    pendingFileRestoreJson = jsonContent
                    pendingFileRestoreReport = report
                    fileOpErrorMessage = null
                    showFileRestoreConfirmDialog = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                Log.e(BACKUP_UI_TAG, "OutOfMemoryError while reading backup file", e)
                fileOpErrorMessage = "حجم فایل انتخاب‌شده بیش از توان حافظه دستگاه است."
                fileOpSuccessMessage = null
            } catch (e: SecurityException) {
                Log.e(BACKUP_UI_TAG, "SecurityException while opening backup file", e)
                fileOpErrorMessage = "اجازه دسترسی به فایل انتخاب‌شده صادر نشد."
                fileOpSuccessMessage = null
            } catch (e: Throwable) {
                Log.e(BACKUP_UI_TAG, "Backup read failed: ${e.javaClass.simpleName}", e)
                fileOpErrorMessage = "خواندن فایل پشتیبان انجام نشد: ${e.localizedMessage ?: "خطای خواندن فایل"}"
                fileOpSuccessMessage = null
            } finally {
                isRestoreInProgress = false
            }
        }
    }

    LaunchedEffect(externalExportPickerEvent) {
        val event = externalExportPickerEvent ?: return@LaunchedEffect
        onConsumeExternalExportPickerEvent(event.eventId)
        handleCreateDocumentResult(event.uri)
    }

    LaunchedEffect(externalRestorePickerEvent) {
        val event = externalRestorePickerEvent ?: return@LaunchedEffect
        onConsumeExternalRestorePickerEvent(event.eventId)
        handleOpenDocumentResult(event.uri)
    }

    // ثبت ایمن لانچرهای محلی تنها در زمانی که میزبان Activity لانچرهای مستقل را ارسال نکرده باشد
    // استفاده از remember روی قراردادها از Unregister/Re-register شدن هنگام Recomposition جلوگیری می‌کند
    val hasRegistryOwner = LocalActivityResultRegistryOwner.current != null
    val shouldRegisterLocalLaunchers =
        onLaunchExternalBackupExportPicker == null && onLaunchExternalBackupRestorePicker == null && hasRegistryOwner

    val createJsonContract = remember { CreateBackupDocumentContract("application/json", includeOpenableCategory = true) }
    val createWildcardContract = remember { CreateBackupDocumentContract("*/*", includeOpenableCategory = false) }
    val openDocumentContract = remember { OpenBackupDocumentContract(includeOpenableCategory = true) }
    val getContentContract = remember { GetBackupContentContract(includeOpenableCategory = true) }

    val localCreateDocumentLauncher = if (shouldRegisterLocalLaunchers) {
        rememberLauncherForActivityResult(contract = createJsonContract) { uri ->
            handleCreateDocumentResult(uri)
        }
    } else null

    val localCreateDocumentFallbackLauncher = if (shouldRegisterLocalLaunchers) {
        rememberLauncherForActivityResult(contract = createWildcardContract) { uri ->
            handleCreateDocumentResult(uri)
        }
    } else null

    val localOpenDocumentLauncher = if (shouldRegisterLocalLaunchers) {
        rememberLauncherForActivityResult(contract = openDocumentContract) { uri ->
            handleOpenDocumentResult(uri)
        }
    } else null

    val localGetContentFallbackLauncher = if (shouldRegisterLocalLaunchers) {
        rememberLauncherForActivityResult(contract = getContentContract) { uri ->
            handleOpenDocumentResult(uri)
        }
    } else null

    fun launchBackupExportPicker() {
        if (isBackupInProgress || isRestoreInProgress) return
        val timeFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)
        val filename = "modiriat_sarmaye_backup_${timeFormat.format(Date())}.json"
        fileOpSuccessMessage = null
        fileOpErrorMessage = null
        isBackupInProgress = true

        val outcome = when {
            onLaunchExternalBackupExportPicker != null -> {
                onLaunchExternalBackupExportPicker(filename)
            }
            localCreateDocumentLauncher != null && localCreateDocumentFallbackLauncher != null -> {
                launchBackupExportWithFallbacks(
                    suggestedFileName = filename,
                    primaryLauncher = localCreateDocumentLauncher,
                    fallbackLauncher = localCreateDocumentFallbackLauncher
                )
            }
            else -> {
                Log.e(BACKUP_UI_TAG, "No ActivityResultRegistryOwner or host export launcher available")
                BackupPickerLaunchOutcome.Failed("امکان باز کردن صفحه ذخیره فایل پشتیبان وجود ندارد.")
            }
        }

        if (outcome is BackupPickerLaunchOutcome.Failed) {
            isBackupInProgress = false
            fileOpErrorMessage = outcome.userMessage
        }
    }

    fun launchBackupRestorePicker() {
        if (isBackupInProgress || isRestoreInProgress) return
        fileOpSuccessMessage = null
        fileOpErrorMessage = null
        isRestoreInProgress = true

        val outcome = when {
            onLaunchExternalBackupRestorePicker != null -> {
                onLaunchExternalBackupRestorePicker()
            }
            localOpenDocumentLauncher != null && localGetContentFallbackLauncher != null -> {
                launchBackupRestoreWithFallbacks(
                    openDocumentLauncher = localOpenDocumentLauncher,
                    getContentLauncher = localGetContentFallbackLauncher
                )
            }
            else -> {
                Log.e(BACKUP_UI_TAG, "No ActivityResultRegistryOwner or host restore launcher available")
                BackupPickerLaunchOutcome.Failed("امکان باز کردن انتخابگر فایل پشتیبان وجود ندارد.")
            }
        }

        if (outcome is BackupPickerLaunchOutcome.Failed) {
            isRestoreInProgress = false
            fileOpErrorMessage = outcome.userMessage
        }
    }

    fun applyValidatedRestore(json: String, replaceExisting: Boolean) {
        if (isRestoreInProgress) return
        if (onRestoreBackupWithResult != null) {
            isRestoreInProgress = true
            onRestoreBackupWithResult(json, replaceExisting) { success, errMsg ->
                isRestoreInProgress = false
                showFileRestoreConfirmDialog = false
                pendingFileRestoreJson = null
                pendingFileRestoreReport = null
                if (success) {
                    fileOpSuccessMessage = if (replaceExisting) {
                        "داده‌ها با موفقیت جایگزین شدند."
                    } else {
                        "پشتیبان با موفقیت با داده‌های موجود ادغام گردید."
                    }
                    fileOpErrorMessage = null
                } else {
                    fileOpErrorMessage = errMsg ?: "بازیابی نسخه پشتیبان با خطا مواجه شد؛ داده‌های فعلی دست‌نخورده باقی ماندند."
                    fileOpSuccessMessage = null
                }
            }
        } else {
            onRestoreBackup(json, replaceExisting)
            showFileRestoreConfirmDialog = false
            pendingFileRestoreJson = null
            pendingFileRestoreReport = null
            fileOpSuccessMessage = if (replaceExisting) {
                "داده‌ها با موفقیت جایگزین شدند."
            } else {
                "پشتیبان با موفقیت با داده‌های موجود ادغام گردید."
            }
            fileOpErrorMessage = null
        }
    }

    // دیالوگ تأیید بازیابی از فایل با نمایش مشخصات نسخه پشتیبان و انتخاب نحوه ادغام / جایگزینی
    if (showFileRestoreConfirmDialog && pendingFileRestoreJson != null && pendingFileRestoreReport != null) {
        val rep = pendingFileRestoreReport!!
        AlertDialog(
            onDismissRequest = {
                if (!isRestoreInProgress) {
                    showFileRestoreConfirmDialog = false
                }
            },
            title = {
                Text("تأیید بازیابی از فایل پشتیبان", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "مشخصات نسخه پشتیبان خوانده‌شده از فایل JSON (نسخه ${PersianUtils.toPersianDigits(rep.schemaVersion.toString())}):",
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
                            if (rep.hasDividendsSection || rep.validDividends.isNotEmpty()) {
                                Text("• سود نقدی مجامع: ${PersianUtils.formatNumber(rep.validDividends.size.toDouble())} مورد", style = MaterialTheme.typography.bodySmall)
                            }
                            if (rep.skippedTransactionsCount > 0) {
                                Text("• رکوردهای نامعتبر نادیده گرفته‌شده: ${PersianUtils.toPersianDigits(rep.skippedTransactionsCount.toString())}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    if (rep.isEmptyBackup) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "هشدار: این فایل پشتیبان خالی است. در صورت انتخاب «جایگزینی کامل»، تمام سوابق مالی فعلی پاک می‌شوند.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "نحوه اعمال نسخه پشتیبان بر روی داده‌های فعلی را انتخاب نمایید (تنظیمات رمز PIN دستگاه دست‌نخورده می‌ماند):",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppActionPair(
                        firstButton = { itemModifier ->
                            AppOutlinedButton(
                                text = "ادغام با داده‌های موجود",
                                onClick = {
                                    pendingFileRestoreJson?.let { json ->
                                        applyValidatedRestore(json, replaceExisting = false)
                                    }
                                },
                                isLoading = isRestoreInProgress,
                                enabled = !isRestoreInProgress,
                                contentPadding = AppButtonDefaults.CompactContentPadding,
                                modifier = itemModifier.testTag("confirm_restore_merge_btn")
                            )
                        },
                        secondButton = { itemModifier ->
                            AppDestructiveButton(
                                text = "جایگزینی کامل",
                                onClick = {
                                    pendingFileRestoreJson?.let { json ->
                                        applyValidatedRestore(json, replaceExisting = true)
                                    }
                                },
                                enabled = !isRestoreInProgress,
                                contentPadding = AppButtonDefaults.CompactContentPadding,
                                modifier = itemModifier.testTag("confirm_restore_replace_btn")
                            )
                        }
                    )
                    AppTextButton(
                        text = "انصراف",
                        enabled = !isRestoreInProgress,
                        onClick = { showFileRestoreConfirmDialog = false },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
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
            .padding(horizontal = 16.dp)
            .testTag("more_screen_list"),
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
                    text = "پیکربندی استراتژی سرمایه‌گذاری، پشتیبان‌گیری محلی JSON و نرخ‌های بازار",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // بخش ۱: تنظیمات و بروزرسانی قیمت‌های بازار
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
                            Icon(imageVector = AppIcons.QueryStats, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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

                    AppPrimaryButton(
                        text = "بروزرسانی آنلاین قیمت‌ها هم‌اکنون",
                        onClick = onSyncMarketPrices,
                        icon = Icons.Default.Refresh,
                        isLoading = uiState.isMarketUpdating,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sync_market_prices_btn")
                    )
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
                            Icon(imageVector = AppIcons.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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

        // بخش امنیت و رمز ورود (PIN)
        item {
            PinSecuritySection(
                settings = settings,
                onSetOrChangePin = onSetOrChangePin,
                onDisablePin = onDisablePin,
                onToggleBiometric = onToggleBiometric,
                onLockAppNow = onLockAppNow
            )
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
                        Icon(imageVector = AppIcons.FolderZip, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "پشتیبان‌گیری محلی در فایل (آفلاین)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "ذخیره یا بازخوانی فایل پشتیبان استاندارد JSON با سازگاری عقب‌رو مستقیماً در حافظه دستگاه بدون نیاز به حساب کاربری یا ورود با گوگل. (همگام‌سازی ابری Google Drive به دلیل حذف احراز هویت گوگل غیرفعال است.)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (fileOpSuccessMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = ProfitGreen.copy(alpha = 0.15f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("backup_op_success_banner")
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("backup_op_error_banner")
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

                    AppActionPair(
                        firstButton = { itemModifier ->
                            AppPrimaryButton(
                                text = "گرفتن نسخه پشتیبان",
                                onClick = { launchBackupExportPicker() },
                                icon = AppIcons.FileDownload,
                                isLoading = isBackupInProgress,
                                enabled = !isBackupInProgress && !isRestoreInProgress,
                                modifier = itemModifier.testTag("export_file_backup_btn")
                            )
                        },
                        secondButton = { itemModifier ->
                            AppOutlinedButton(
                                text = "بازگردانی نسخه پشتیبان",
                                onClick = { launchBackupRestorePicker() },
                                icon = AppIcons.FileUpload,
                                isLoading = isRestoreInProgress,
                                enabled = !isBackupInProgress && !isRestoreInProgress,
                                modifier = itemModifier.testTag("restore_file_backup_btn")
                            )
                        }
                    )
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
                            Icon(imageVector = AppIcons.Analytics, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
                            Text(
                                text = "• امنیت و ذخیره‌سازی محلی: کاملاً آفلاین و خصوصی بدون نیاز به حساب Google",
                                style = MaterialTheme.typography.bodySmall,
                                color = ProfitGreen
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
                        text = "نسخه ۱.۰.۰ • معماری Local-first بادوام ۱۰ ساله برای محیط اقتصادی ایران • بدون نیاز به حساب کاربری، با پشتیبان‌گیری محلی JSON و بروزرسانی آنلاین قیمت‌های بازار بورس و طلا.",
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
                Icon(imageVector = AppIcons.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
                                imageVector = if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
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
            AppDialogActionPair(
                confirmText = "ذخیره تنظیمات",
                onConfirm = {
                    val g = goldPct.toDoubleOrNull() ?: 0.0
                    val e = equityPct.toDoubleOrNull() ?: 0.0
                    val f = fixedPct.toDoubleOrNull() ?: 0.0
                    val u = usdPct.toDoubleOrNull() ?: 0.0
                    val c = cashPct.toDoubleOrNull() ?: 0.0
                    val sum = g + e + f + u + c

                    if (kotlin.math.abs(sum - 100.0) > 0.01) {
                        errorMessage = "مجموع درصدها باید دقیقاً ۱۰۰ باشد (فعلی: $sum)."
                        return@AppDialogActionPair
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
                },
                dismissText = "انصراف",
                onDismiss = onDismiss
            )
        }
    )
}
