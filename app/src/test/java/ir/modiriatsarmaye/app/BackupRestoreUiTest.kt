package ir.modiriatsarmaye.app

import android.content.ActivityNotFoundException
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import ir.modiriatsarmaye.app.data.model.AppSettingsEntity
import ir.modiriatsarmaye.app.ui.backup.BackupPickerLaunchOutcome
import ir.modiriatsarmaye.app.ui.backup.BackupPickerUriEvent
import ir.modiriatsarmaye.app.ui.backup.CreateBackupDocumentContract
import ir.modiriatsarmaye.app.ui.backup.GetBackupContentContract
import ir.modiriatsarmaye.app.ui.backup.OpenBackupDocumentContract
import ir.modiriatsarmaye.app.ui.screens.MoreScreen
import ir.modiriatsarmaye.app.ui.theme.ModiriatSarmayeTheme
import ir.modiriatsarmaye.app.ui.viewmodel.WealthUiState
import ir.modiriatsarmaye.app.util.CalculationEngine
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp")
class BackupRestoreUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class FakeActivityResultRegistryOwner(
        private val handler: (requestCode: Int, contract: ActivityResultContract<*, *>, input: Any?, registry: ActivityResultRegistry) -> Unit
    ) : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?
            ) {
                handler(requestCode, contract, input, this)
            }
        }
    }

    private class SecurityErrorContentProvider : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = "application/json"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            throw SecurityException("Permission denial reading or writing test URI")
        }
    }

    private fun baseUiState(): WealthUiState {
        val settings = AppSettingsEntity()
        return WealthUiState(
            settings = settings,
            portfolioSummary = CalculationEngine.calculatePortfolio(emptyList(), emptyList(), settings, emptyList())
        )
    }

    private fun mountMoreScreen(
        registryOwner: ActivityResultRegistryOwner,
        onExportBackup: suspend () -> String = { """{"schemaVersion":2,"transactions":[],"prices":[],"goals":[],"liabilities":[],"dividends":[]}""" },
        onRestoreBackupWithResult: ((String, Boolean, (Boolean, String?) -> Unit) -> Unit)? = null
    ) {
        composeTestRule.setContent {
            ModiriatSarmayeTheme {
                CompositionLocalProvider(
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                    LocalActivityResultRegistryOwner provides registryOwner
                ) {
                    MoreScreen(
                        uiState = baseUiState(),
                        onSaveSettings = {},
                        onExportBackup = onExportBackup,
                        onRestoreBackup = { _, _ -> },
                        onSyncMarketPrices = {},
                        onRestoreBackupWithResult = onRestoreBackupWithResult
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("more_screen_list")
            .performScrollToNode(hasTestTag("local_backup_card"))
        composeTestRule.waitForIdle()
    }

    @Test
    fun backupAndRestoreButtons_handleActivityNotFoundExceptionWithoutCrashing() {
        val owner = FakeActivityResultRegistryOwner { _, _, _, _ ->
            throw ActivityNotFoundException("No DocumentsUI installed")
        }
        mountMoreScreen(owner)

        composeTestRule.onNodeWithTag("export_file_backup_btn").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("backup_op_error_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("export_file_backup_btn").assertIsEnabled()

        composeTestRule.onNodeWithTag("restore_file_backup_btn").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("backup_op_error_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("restore_file_backup_btn").assertIsEnabled()
    }

    @Test
    fun backupAndRestorePickers_handleUserCancellationCleanly() {
        val owner = FakeActivityResultRegistryOwner { requestCode, _, _, registry ->
            registry.dispatchResult(requestCode, null as Uri?)
        }
        mountMoreScreen(owner)

        composeTestRule.onNodeWithTag("export_file_backup_btn").performClick()
        composeTestRule.waitForIdle()
        assertEquals(0, composeTestRule.onAllNodesWithTag("backup_op_error_banner").fetchSemanticsNodes().size)
        assertEquals(0, composeTestRule.onAllNodesWithTag("backup_op_success_banner").fetchSemanticsNodes().size)
        composeTestRule.onNodeWithTag("export_file_backup_btn").assertIsEnabled()

        composeTestRule.onNodeWithTag("restore_file_backup_btn").performClick()
        composeTestRule.waitForIdle()
        assertEquals(0, composeTestRule.onAllNodesWithTag("backup_op_error_banner").fetchSemanticsNodes().size)
        composeTestRule.onNodeWithTag("restore_file_backup_btn").assertIsEnabled()
    }

    @Test
    fun backupExport_writesJsonToSelectedUriAndShowsSuccess() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val outFile = File(context.cacheDir, "ui_backup_export_test.json").apply { delete() }
        val targetUri = Uri.fromFile(outFile)
        val sampleJson = """{"schemaVersion":2,"transactions":[],"prices":[],"goals":[],"liabilities":[],"dividends":[]}"""

        val owner = FakeActivityResultRegistryOwner { requestCode, _, _, registry ->
            registry.dispatchResult(requestCode, targetUri)
        }
        mountMoreScreen(owner, onExportBackup = { sampleJson })

        composeTestRule.onNodeWithTag("export_file_backup_btn").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("backup_op_success_banner").fetchSemanticsNodes().isNotEmpty()
        }

        assertTrue(outFile.exists())
        assertEquals(sampleJson, outFile.readText(Charsets.UTF_8))
        composeTestRule.onNodeWithTag("backup_op_success_banner").assertIsDisplayed()
        outFile.delete()
    }

    @Test
    fun backupAndRestore_handleSecurityExceptionDuringStreamAccessWithoutCrashing() {
        val authority = "ir.modiriatsarmaye.app.test.securityerr"
        ShadowContentResolver.registerProviderInternal(authority, SecurityErrorContentProvider())
        val errUri = Uri.parse("content://$authority/backup.json")

        val owner = FakeActivityResultRegistryOwner { requestCode, _, _, registry ->
            registry.dispatchResult(requestCode, errUri)
        }
        mountMoreScreen(owner)

        composeTestRule.onNodeWithTag("export_file_backup_btn").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("backup_op_error_banner").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("backup_op_error_banner").assertIsDisplayed()

        composeTestRule.onNodeWithTag("restore_file_backup_btn").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("backup_op_error_banner").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("backup_op_error_banner").assertIsDisplayed()
    }

    @Test
    fun backupRestore_validFileOpensDialogAndReflectsRealAsyncSuccessAndFailure() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inFile = File(context.cacheDir, "ui_backup_restore_test.json")
        inFile.writeText(
            """
            {
                "schemaVersion": 2,
                "transactions": [
                    {
                        "datePersian": "1403/02/01",
                        "assetClass": "GOLD",
                        "assetName": "طلای ۱۸ عیار",
                        "action": "BUY",
                        "quantity": 2.0,
                        "unit": "گرم"
                    }
                ],
                "prices": [],
                "goals": [],
                "liabilities": [],
                "dividends": []
            }
            """.trimIndent(),
            Charsets.UTF_8
        )
        val targetUri = Uri.fromFile(inFile)

        var shouldFailRestore = true
        val owner = FakeActivityResultRegistryOwner { requestCode, _, _, registry ->
            registry.dispatchResult(requestCode, targetUri)
        }
        mountMoreScreen(
            owner,
            onRestoreBackupWithResult = { _, _, onComplete ->
                if (shouldFailRestore) {
                    onComplete(false, "خطای شبیه‌سازی‌شده پایگاه داده")
                } else {
                    onComplete(true, null)
                }
            }
        )

        // ۱. انتخاب فایل معتبر -> باز شدن دیالوگ تأیید و شکست واقعی در Replace -> نمایش خطا بدون موفقیت کاذب
        composeTestRule.onNodeWithTag("restore_file_backup_btn").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("confirm_restore_replace_btn").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("confirm_restore_replace_btn").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("backup_op_error_banner").assertIsDisplayed()
        assertEquals(0, composeTestRule.onAllNodesWithTag("backup_op_success_banner").fetchSemanticsNodes().size)

        // ۲. تکرار با نتیجه موفق در Merge -> نمایش پیام موفقیت واقعی
        shouldFailRestore = false
        composeTestRule.onNodeWithTag("restore_file_backup_btn").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("confirm_restore_merge_btn").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("confirm_restore_merge_btn").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("backup_op_success_banner").assertIsDisplayed()

        inFile.delete()
    }

    @Test
    fun safContracts_buildValidIntentsAndPreserveStructuralEqualityAcrossRecompositions() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val create1 = CreateBackupDocumentContract("application/json", includeOpenableCategory = true)
        val create2 = CreateBackupDocumentContract("application/json", includeOpenableCategory = true)
        assertEquals(create1, create2)
        assertEquals(create1.hashCode(), create2.hashCode())

        val createIntent = create1.createIntent(context, "backup.json")
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, createIntent.action)
        assertEquals("application/json", createIntent.type)
        assertEquals("backup.json", createIntent.getStringExtra(Intent.EXTRA_TITLE))
        assertTrue(createIntent.categories?.contains(Intent.CATEGORY_OPENABLE) == true)

        val open1 = OpenBackupDocumentContract(includeOpenableCategory = true)
        val open2 = OpenBackupDocumentContract(includeOpenableCategory = true)
        assertEquals(open1, open2)
        assertEquals(open1.hashCode(), open2.hashCode())

        val openTypedIntent = open1.createIntent(context, arrayOf("application/json", "text/plain"))
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, openTypedIntent.action)
        assertEquals("*/*", openTypedIntent.type)
        assertTrue(openTypedIntent.categories?.contains(Intent.CATEGORY_OPENABLE) == true)
        assertEquals(listOf("application/json", "text/plain"), openTypedIntent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())

        val openWildcardIntent = open1.createIntent(context, arrayOf("*/*"))
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, openWildcardIntent.action)
        assertEquals("*/*", openWildcardIntent.type)
        assertNull(openWildcardIntent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))

        val get1 = GetBackupContentContract(includeOpenableCategory = true)
        val get2 = GetBackupContentContract(includeOpenableCategory = true)
        assertEquals(get1, get2)
        val getIntent = get1.createIntent(context, "*/*")
        assertEquals(Intent.ACTION_GET_CONTENT, getIntent.action)
        assertEquals("*/*", getIntent.type)
        assertTrue(getIntent.categories?.contains(Intent.CATEGORY_OPENABLE) == true)
    }

    @Test
    fun backupAndRestore_recoverViaWildcardAndGetContentFallbacksWhenPrimaryFailsWithIllegalState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val outFile = File(context.cacheDir, "ui_fallback_export_test.json").apply { delete() }
        val inFile = File(context.cacheDir, "ui_fallback_restore_test.json")
        val sampleJson = """{"schemaVersion":2,"transactions":[],"prices":[],"goals":[],"liabilities":[],"dividends":[]}"""
        inFile.writeText(sampleJson, Charsets.UTF_8)

        val launchedContracts = mutableListOf<String>()
        val owner = FakeActivityResultRegistryOwner { requestCode, contract, input, registry ->
            launchedContracts.add(contract.javaClass.simpleName)
            when (contract) {
                is CreateBackupDocumentContract -> {
                    val intent = contract.createIntent(context, input as String)
                    if (intent.type == "application/json") {
                        throw IllegalStateException("Simulated Compose/OEM primary CreateDocument error")
                    }
                    registry.dispatchResult(requestCode, Uri.fromFile(outFile))
                }
                is OpenBackupDocumentContract -> {
                    throw ActivityNotFoundException("No DocumentsUI OpenDocument handler")
                }
                is GetBackupContentContract -> {
                    registry.dispatchResult(requestCode, Uri.fromFile(inFile))
                }
            }
        }
        mountMoreScreen(owner, onExportBackup = { sampleJson })

        composeTestRule.onNodeWithTag("export_file_backup_btn").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("backup_op_success_banner").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(outFile.exists())
        assertEquals(sampleJson, outFile.readText(Charsets.UTF_8))

        composeTestRule.onNodeWithTag("restore_file_backup_btn").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("confirm_restore_merge_btn").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(launchedContracts.contains("GetBackupContentContract"))

        outFile.delete()
        inFile.delete()
    }

    @Test
    fun backupAndRestore_handleUnrecoverableIllegalStateExceptionWithoutCrashing() {
        val owner = FakeActivityResultRegistryOwner { _, _, _, _ ->
            throw IllegalStateException("Launcher not registered")
        }
        mountMoreScreen(owner)

        composeTestRule.onNodeWithTag("export_file_backup_btn").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("backup_op_error_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("export_file_backup_btn").assertIsEnabled()

        composeTestRule.onNodeWithTag("restore_file_backup_btn").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("backup_op_error_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("restore_file_backup_btn").assertIsEnabled()
    }

    @Test
    fun moreScreen_hostPickerBridgeDeliversExportUriAndHandlesMainActivitySetupCleanly() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertFalse(controller.get().isFinishing)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val outFile = File(context.cacheDir, "ui_host_bridge_export.json").apply { delete() }
        val sampleJson = """{"schemaVersion":2,"transactions":[],"prices":[],"goals":[],"liabilities":[],"dividends":[]}"""
        var exportEvent by mutableStateOf<BackupPickerUriEvent?>(null)
        var restoreEvent by mutableStateOf<BackupPickerUriEvent?>(null)
        var consumedExportId: Long? = null

        composeTestRule.setContent {
            ModiriatSarmayeTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    MoreScreen(
                        uiState = baseUiState(),
                        onSaveSettings = {},
                        onExportBackup = { sampleJson },
                        onRestoreBackup = { _, _ -> },
                        onSyncMarketPrices = {},
                        externalExportPickerEvent = exportEvent,
                        onConsumeExternalExportPickerEvent = { id ->
                            consumedExportId = id
                            exportEvent = null
                        },
                        externalRestorePickerEvent = restoreEvent,
                        onConsumeExternalRestorePickerEvent = { restoreEvent = null },
                        onLaunchExternalBackupExportPicker = {
                            exportEvent = BackupPickerUriEvent(eventId = 42L, uri = Uri.fromFile(outFile))
                            BackupPickerLaunchOutcome.Launched
                        },
                        onLaunchExternalBackupRestorePicker = {
                            BackupPickerLaunchOutcome.Failed("امکان باز کردن انتخابگر فایل پشتیبان وجود ندارد.")
                        }
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("more_screen_list")
            .performScrollToNode(hasTestTag("local_backup_card"))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("export_file_backup_btn").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("backup_op_success_banner").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(42L, consumedExportId)
        assertEquals(sampleJson, outFile.readText(Charsets.UTF_8))

        composeTestRule.onNodeWithTag("restore_file_backup_btn").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("backup_op_error_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("restore_file_backup_btn").assertIsEnabled()

        outFile.delete()
        controller.pause().stop().destroy()
    }
}
