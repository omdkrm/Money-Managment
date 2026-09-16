package ir.modiriatsarmaye.app

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ApplicationProvider
import ir.modiriatsarmaye.app.data.model.AppSettingsEntity
import ir.modiriatsarmaye.app.ui.screens.DashboardScreen
import ir.modiriatsarmaye.app.ui.theme.ModiriatSarmayeTheme
import ir.modiriatsarmaye.app.ui.viewmodel.WealthUiState
import ir.modiriatsarmaye.app.util.CalculationEngine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WealthAppRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testAppStringResourcesAndPackage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("مدیریت سرمایه", appName)
        assertEquals("ir.modiriatsarmaye.app", context.packageName)
    }

    @Test
    fun testHomeScreenLoadsOffline() {
        val settings = AppSettingsEntity()
        val summary = CalculationEngine.calculatePortfolio(emptyList(), emptyList(), settings, emptyList())
        val uiState = WealthUiState(
            portfolioSummary = summary
        )

        composeTestRule.setContent {
            ModiriatSarmayeTheme {
                DashboardScreen(
                    uiState = uiState,
                    onAddTransactionClick = {},
                    onNavigateToPortfolio = {},
                    onNavigateToTransactions = {},
                    onNavigateToReports = {},
                    onTransactionClick = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("dashboard_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("dashboard_hero_card").assertIsDisplayed()
    }
}
