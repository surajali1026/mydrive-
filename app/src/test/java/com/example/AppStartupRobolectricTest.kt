package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppStartupRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunches_registerAndNavigateTwoTabsAndSettings() {
        composeRule.waitForIdle()

        // Switch to Create Account on AuthScreen
        composeRule.onNodeWithTag("auth_mode_register").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("auth_name_input").performScrollTo().performTextInput("Test User")
        composeRule.onNodeWithTag("auth_email_input").performScrollTo().performTextInput("test@mydrive.app")
        composeRule.onNodeWithTag("auth_password_input").performScrollTo().performTextInput("SecurePass123")
        composeRule.onNodeWithTag("auth_primary_submit_button").performScrollTo().performClick()

        // Wait for Room & DataStore async transition to main 2-tab UI
        try {
            composeRule.waitUntil(timeoutMillis = 8_000) {
                runCatching {
                    composeRule.onNodeWithTag("uploads_search_input").assertIsDisplayed()
                    true
                }.getOrDefault(false)
            }
        } catch (e: Throwable) {
            throw AssertionError("UI tree at timeout:\n" + composeRule.onRoot().printToString(), e)
        }

        // Navigate to Tab 2: Cloud Drives
        composeRule.onNodeWithTag("nav_tab_cloud_drives").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("total_storage_header_card").assertIsDisplayed()

        // Open Settings & GitHub Auto-Update sheet
        composeRule.onNodeWithTag("drives_open_settings_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("switch_github_auto_update").assertIsDisplayed()
    }
}
