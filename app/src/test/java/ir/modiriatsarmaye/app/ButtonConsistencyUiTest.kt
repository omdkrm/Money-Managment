package ir.modiriatsarmaye.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import ir.modiriatsarmaye.app.data.market.StrategyDiagnostic
import ir.modiriatsarmaye.app.data.model.AppSettingsEntity
import ir.modiriatsarmaye.app.ui.components.AppActionPair
import ir.modiriatsarmaye.app.ui.components.AppIcons
import ir.modiriatsarmaye.app.ui.components.AppOutlinedButton
import ir.modiriatsarmaye.app.ui.components.AppPrimaryButton
import ir.modiriatsarmaye.app.ui.components.PersianDatePickerDialog
import ir.modiriatsarmaye.app.ui.components.StockDiagnosticCard
import ir.modiriatsarmaye.app.ui.screens.MoreScreen
import ir.modiriatsarmaye.app.ui.screens.TransactionsScreen
import ir.modiriatsarmaye.app.ui.theme.ModiriatSarmayeTheme
import ir.modiriatsarmaye.app.ui.viewmodel.WealthUiState
import ir.modiriatsarmaye.app.util.CalculationEngine
import ir.modiriatsarmaye.app.util.JalaliDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp")
class ButtonConsistencyUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testLocalBackupButtonsInMoreScreenHaveEqualDimensionsAndRtlOrder() {
        val settings = AppSettingsEntity()
        val uiState = WealthUiState(
            settings = settings,
            portfolioSummary = CalculationEngine.calculatePortfolio(emptyList(), emptyList(), settings, emptyList())
        )

        composeTestRule.setContent {
            ModiriatSarmayeTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    MoreScreen(
                        uiState = uiState,
                        onSaveSettings = {},
                        onExportBackup = { "{}" },
                        onRestoreBackup = { _, _ -> },
                        onResetDatabase = {},
                        onSyncMarketPrices = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("more_screen_list")
            .performScrollToNode(hasTestTag("local_backup_card"))
        composeTestRule.waitForIdle()

        val exportNode = composeTestRule.onNodeWithTag("export_file_backup_btn")
        val restoreNode = composeTestRule.onNodeWithTag("restore_file_backup_btn")

        exportNode.assertIsDisplayed()
        restoreNode.assertIsDisplayed()
        composeTestRule.onNodeWithText("گرفتن نسخه پشتیبان").assertIsDisplayed()
        composeTestRule.onNodeWithText("بازگردانی نسخه پشتیبان").assertIsDisplayed()

        val exportBounds = exportNode.fetchSemanticsNode().boundsInRoot
        val restoreBounds = restoreNode.fetchSemanticsNode().boundsInRoot
        val minTouchPx = with(composeTestRule.density) { 48.dp.toPx() }

        assertEquals(exportBounds.width, restoreBounds.width, 1f)
        assertEquals(exportBounds.height, restoreBounds.height, 1f)
        assertEquals(exportBounds.top, restoreBounds.top, 1f)
        assertEquals(exportBounds.bottom, restoreBounds.bottom, 1f)
        assertTrue("Export button height must be >= 48dp", exportBounds.height >= minTouchPx - 1f)
        assertTrue("Restore button height must be >= 48dp", restoreBounds.height >= minTouchPx - 1f)
        // RTL: first button ("گرفتن نسخه پشتیبان") is positioned to the right of second button
        assertTrue("First RTL button must be to the right of second button", exportBounds.left > restoreBounds.right)
    }

    @Test
    fun testActionPairStacksVerticallyWithEqualWidthOnNarrowScreens() {
        composeTestRule.setContent {
            ModiriatSarmayeTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(modifier = Modifier.width(240.dp)) {
                        AppActionPair(
                            firstButton = { itemModifier ->
                                AppPrimaryButton(
                                    text = "گرفتن نسخه پشتیبان",
                                    onClick = {},
                                    icon = AppIcons.FileDownload,
                                    modifier = itemModifier.testTag("narrow_export_btn")
                                )
                            },
                            secondButton = { itemModifier ->
                                AppOutlinedButton(
                                    text = "بازگردانی نسخه پشتیبان",
                                    onClick = {},
                                    icon = AppIcons.FileUpload,
                                    modifier = itemModifier.testTag("narrow_restore_btn")
                                )
                            }
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        val firstBounds = composeTestRule.onNodeWithTag("narrow_export_btn").fetchSemanticsNode().boundsInRoot
        val secondBounds = composeTestRule.onNodeWithTag("narrow_restore_btn").fetchSemanticsNode().boundsInRoot
        val minTouchPx = with(composeTestRule.density) { 48.dp.toPx() }

        assertEquals(firstBounds.width, secondBounds.width, 1f)
        assertTrue("Stacked buttons must span full narrow container width", firstBounds.width >= minTouchPx * 4)
        assertTrue("Second button must stack below first button", secondBounds.top >= firstBounds.bottom)
        assertTrue("First stacked button height must be >= 48dp", firstBounds.height >= minTouchPx - 1f)
        assertTrue("Second stacked button height must be >= 48dp", secondBounds.height >= minTouchPx - 1f)
    }

    @Test
    fun testStockDiagnosticCardButtonsHaveEqualDimensions() {
        val sampleDiag = StrategyDiagnostic(
            testId = "TEST_STOCK",
            testNameFa = "استعلام تستی",
            url = "https://tsetmc.com",
            httpStatusCode = 200,
            contentType = "application/json",
            isSuccess = true,
            symbol = "فولاد",
            instrumentType = "STOCK"
        )

        composeTestRule.setContent {
            ModiriatSarmayeTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    StockDiagnosticCard(
                        diagnostics = listOf(sampleDiag),
                        onUpdateAllStocks = {},
                        onUpdateIndividualStock = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        val allStocksBounds = composeTestRule.onNodeWithTag("update_all_stocks_btn").fetchSemanticsNode().boundsInRoot
        val singleStockBounds = composeTestRule.onNodeWithTag("update_single_stock_btn").fetchSemanticsNode().boundsInRoot
        val minTouchPx = with(composeTestRule.density) { 48.dp.toPx() }

        assertEquals(allStocksBounds.width, singleStockBounds.width, 1f)
        assertEquals(allStocksBounds.height, singleStockBounds.height, 1f)
        assertTrue("All stocks button height must be >= 48dp", allStocksBounds.height >= minTouchPx - 1f)
        assertTrue("Single stock button height must be >= 48dp", singleStockBounds.height >= minTouchPx - 1f)
    }

    @Test
    fun testDatePickerDialogButtonsHaveEqualDimensionsAndTouchTargets() {
        composeTestRule.setContent {
            ModiriatSarmayeTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    PersianDatePickerDialog(
                        initialDate = JalaliDate(1403, 1, 15),
                        onDismissRequest = {},
                        onDateSelected = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        val confirmBounds = composeTestRule.onNodeWithTag("date_picker_confirm_button").fetchSemanticsNode().boundsInRoot
        val cancelBounds = composeTestRule.onNodeWithTag("date_picker_cancel_button").fetchSemanticsNode().boundsInRoot
        val todayBounds = composeTestRule.onNodeWithTag("date_picker_today_button").fetchSemanticsNode().boundsInRoot
        val minTouchPx = with(composeTestRule.density) { 48.dp.toPx() }

        assertEquals(confirmBounds.width, cancelBounds.width, 1f)
        assertEquals(confirmBounds.height, cancelBounds.height, 1f)
        assertTrue(confirmBounds.height >= minTouchPx - 1f)
        assertTrue(cancelBounds.height >= minTouchPx - 1f)
        assertTrue(todayBounds.height >= minTouchPx - 1f)
    }

    @Test
    fun testTransactionsScreenTopButtonAndFilterChipsMeet48dpTouchTarget() {
        val settings = AppSettingsEntity()
        val uiState = WealthUiState(
            settings = settings,
            portfolioSummary = CalculationEngine.calculatePortfolio(emptyList(), emptyList(), settings, emptyList())
        )

        composeTestRule.setContent {
            ModiriatSarmayeTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    TransactionsScreen(
                        uiState = uiState,
                        onAddTransactionClick = {},
                        onEditTransaction = {},
                        onDeleteTransaction = {},
                        onSearchChange = {},
                        onSelectClassFilter = {},
                        onSelectActionFilter = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        val addBounds = composeTestRule.onNodeWithTag("add_transaction_top_btn").fetchSemanticsNode().boundsInRoot
        val allActionsBounds = composeTestRule.onNodeWithTag("filter_chip_action_all").fetchSemanticsNode().boundsInRoot
        val minTouchPx = with(composeTestRule.density) { 48.dp.toPx() }

        assertTrue("Top add transaction button height must be >= 48dp", addBounds.height >= minTouchPx - 1f)
        assertTrue("Action filter chip height must be >= 48dp", allActionsBounds.height >= minTouchPx - 1f)
    }
}
