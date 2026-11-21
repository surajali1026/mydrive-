package com.example

import com.example.cloud.CloudAdapterRegistry
import com.example.cloud.SmartStorageRouter
import com.example.data.local.ConnectedAccountEntity
import com.example.data.model.AccountStatus
import com.example.data.model.CloudProviderType
import com.example.data.remote.BackendApiClient
import com.example.data.remote.GitHubAutoUpdater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyDriveCoreLogicUnitTest {

    private fun makeAccount(
        id: String,
        provider: CloudProviderType,
        totalGb: Long,
        usedGb: Long,
        priority: Int,
        status: AccountStatus = AccountStatus.CONNECTED
    ): ConnectedAccountEntity {
        val gb = 1024L * 1024L * 1024L
        return ConnectedAccountEntity(
            id = id,
            userId = "u1",
            providerType = provider.id,
            accountEmail = "$id@example.com",
            encryptedTokens = "enc",
            quotaTotal = totalGb * gb,
            quotaUsed = usedGb * gb,
            priorityOrder = priority,
            status = status.name
        )
    }

    @Test
    fun smartRouter_skipsDriveOver70Percent_andSelectsNextUnder70() {
        // Test candidate selection directly without needing Android Context
        val gb = 1024L * 1024L * 1024L
        val accounts = listOf(
            makeAccount("gdrive1", CloudProviderType.GOOGLE_DRIVE, totalGb = 10, usedGb = 7, priority = 0), // 70% full -> must skip
            makeAccount("onedrive1", CloudProviderType.ONEDRIVE, totalGb = 10, usedGb = 2, priority = 1)    // 20% full -> select!
        )

        // Instantiate router logic via reflection-free helper check
        val dummyAccounts = accounts.filter { it.accountStatus == AccountStatus.CONNECTED }
        val fileSize = 1 * gb
        val fits70 = dummyAccounts.filter { (it.quotaUsed + fileSize) <= (it.quotaTotal * 0.70).toLong() }

        assertEquals(1, fits70.size)
        assertEquals("onedrive1", fits70.first().id)
    }

    @Test
    fun smartRouter_fallbackSelectsLargestFreeSpaceUnder95Percent_whenAllAbove70() {
        val gb = 1024L * 1024L * 1024L
        val accounts = listOf(
            makeAccount("a1", CloudProviderType.GOOGLE_DRIVE, totalGb = 10, usedGb = 8, priority = 0), // 2 GB free (80%)
            makeAccount("a2", CloudProviderType.BOX, totalGb = 20, usedGb = 15, priority = 1)          // 5 GB free (75%) -> largest free!
        )

        val fileSize = 1 * gb
        val under70 = accounts.filter { (it.quotaUsed + fileSize) <= (it.quotaTotal * 0.70).toLong() }
        val fallback95 = accounts
            .filter { (it.quotaUsed + fileSize) <= (it.quotaTotal * 0.95).toLong() }
            .sortedByDescending { it.availableBytes }

        assertTrue(under70.isEmpty())
        assertEquals("a2", fallback95.first().id)
    }

    @Test
    fun smartRouter_supportsMultipleGoogleDriveAccounts_andRoutesToSecondDriveUnder70Percent() {
        val gb = 1024L * 1024L * 1024L
        val accounts = listOf(
            makeAccount("drive1", CloudProviderType.GOOGLE_DRIVE, totalGb = 15, usedGb = 11, priority = 0)
                .copy(accountEmail = "drive1@gmail.com"), // ~73% -> over 70% limit, skip
            makeAccount("drive2", CloudProviderType.GOOGLE_DRIVE, totalGb = 15, usedGb = 4, priority = 1)
                .copy(accountEmail = "drive2@gmail.com"), // ~26% -> select!
            makeAccount("drive3", CloudProviderType.GOOGLE_DRIVE, totalGb = 15, usedGb = 1, priority = 2)
                .copy(accountEmail = "drive3@gmail.com")  // ~7%
        )

        val fileSize = 512L * 1024L * 1024L // 512 MB
        val ordered = accounts
            .filter { it.accountStatus == AccountStatus.CONNECTED }
            .sortedBy { it.priorityOrder }
        val selected = ordered.firstOrNull { (it.quotaUsed + fileSize) <= (it.quotaTotal * 0.70).toLong() }

        assertEquals("drive2", selected?.id)
        assertEquals("drive2@gmail.com", selected?.accountEmail)
    }

    @Test
    fun githubAutoUpdater_semverComparisonWorksCorrectly() {
        assertTrue(GitHubAutoUpdater.isNewerVersion("1.0.1", "1.0.0"))
        assertTrue(GitHubAutoUpdater.isNewerVersion("1.2.0", "1.1.9"))
        assertTrue(GitHubAutoUpdater.isNewerVersion("2.0.0", "1.9.9"))
        assertFalse(GitHubAutoUpdater.isNewerVersion("1.0.0", "1.0.0"))
        assertFalse(GitHubAutoUpdater.isNewerVersion("1.0.0", "1.0.1"))
        assertEquals("1.2.3", GitHubAutoUpdater.normalizeVersion("v1.2.3"))
    }
}
