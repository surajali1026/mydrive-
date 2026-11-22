package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
    fun appLaunchesDirectlyIntoPersonalDriveAndSettings() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("uploads_search_input").assertIsDisplayed()
                true
            }.getOrDefault(false)
        }

        // No account registration or sign-in is required in single-owner mode.
        composeRule.onNodeWithTag("uploads_search_input").assertIsDisplayed()

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
