package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.ConnectedAccountEntity
import com.example.data.local.TransferTaskEntity
import com.example.data.model.AccountStatus
import com.example.data.model.CloudProviderType
import com.example.data.model.TransferStatus
import com.example.ui.components.AccountStatusPill
import com.example.ui.components.ProviderBadge
import com.example.ui.components.ThresholdProgressBar
import com.example.ui.components.formatBytes
import com.example.ui.components.formatClockTime
import com.example.ui.components.formatShortDate
import com.example.ui.theme.AmberThreshold
import com.example.ui.theme.CobaltPrimary
import com.example.ui.theme.CoralCritical
import com.example.ui.theme.CyanSecondary
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.viewmodel.AggregatedStorageSummary
import com.example.ui.viewmodel.GitHubUpdateUiState
import com.example.ui.viewmodel.MyDriveUiState

/**
 * Tab 2: Cloud Drives & Auto-Backup.
 * - Live Aggregated Storage Header (strictly live connected quotas, zero fake numbers)
 * - Connected Drives list with 70% Threshold Progress Bar & multi-account support
 * - All 6 official OAuth 2.0 providers + MEGA (`Integration Pending`); strictly excludes TeraBox
 * - Automatic Gallery Backup & Daily 24h WorkManager Schedule (runs even when closed)
 * - Live Upload/Download Queue with Pause/Resume & Failed alerts
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudDrivesScreen(
    uiState: MyDriveUiState,
    onConnectProvider: (CloudProviderType) -> Unit,
    onRefreshQuotas: () -> Unit,
    onDisconnectAccount: (String) -> Unit,
    onMoveAccountPriority: (String, Boolean) -> Unit,
    onToggleAutoBackup: (Boolean) -> Unit,
    onToggleDailySchedule: (Boolean) -> Unit,
    onUpdateScheduleTime: (Int, Int) -> Unit,
    onUpdateWifiOnly: (Boolean) -> Unit,
    onRunBackupNow: () -> Unit,
    onTogglePauseTransfer: (TransferTaskEntity) -> Unit,
    onRetryTransfer: (TransferTaskEntity) -> Unit,
    onClearCompletedTransfers: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var showTimePicker by remember { mutableStateOf(false) }
    var showMegaInfoDialog by remember { mutableStateOf(false) }
    val hasUpdateBadge = uiState.updateState is GitHubUpdateUiState.Available ||
        uiState.updateState is GitHubUpdateUiState.ReadyToInstall

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Screen Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Cloud Drives", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = "Smart 70% routing • Live quotas • Daily WorkManager backup",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row {
                    IconButton(
                        onClick = onRefreshQuotas,
                        modifier = Modifier.testTag("drives_refresh_quotas_button")
                    ) {
                        Icon(Icons.Filled.Sync, contentDescription = "Refresh live quotas")
                    }
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag("drives_open_settings_button")
                    ) {
                        BadgedBox(badge = { if (hasUpdateBadge) Badge(containerColor = MaterialTheme.colorScheme.secondary) }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings and Auto-Update")
                        }
                    }
                }
            }
        }

        // 1. Live Total Storage Aggregator Header
        item {
            TotalStorageHeaderCard(summary = uiState.storageSummary)
        }

        // 2. Connected Accounts Section
        item {
            Text(
                text = "Connected Accounts (${uiState.accounts.size})",
                style = MaterialTheme.typography.titleLarge
            )
        }

        if (uiState.accounts.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("No Live Drives Connected", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Connect one or more providers below via OAuth 2.0 PKCE. Multiple accounts per provider are supported.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        } else {
            itemsIndexed(uiState.accounts, key = { _, acc -> acc.id }) { index, account ->
                ConnectedDriveCard(
                    account = account,
                    priorityRank = index + 1,
                    canMoveUp = index > 0,
                    canMoveDown = index < uiState.accounts.lastIndex,
                    onMoveUp = { onMoveAccountPriority(account.id, true) },
                    onMoveDown = { onMoveAccountPriority(account.id, false) },
                    onReconnect = { onConnectProvider(account.provider) },
                    onDisconnect = { onDisconnectAccount(account.id) }
                )
            }
        }

        // 3. Official Cloud Providers Directory (Multiple Accounts Supported + MEGA Integration Pending)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Supported Official Providers", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "Connect multiple accounts per provider via official OAuth 2.0 PKCE. Client secrets remain strictly on your backend.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CloudProviderType.entries.forEach { provider ->
                    val connectedCount = uiState.accounts.count {
                        it.provider == provider && it.accountStatus == AccountStatus.CONNECTED
                    }
                    ProviderDirectoryCard(
                        provider = provider,
                        connectedCount = connectedCount,
                        onConnect = {
                            if (provider.isIntegrationPending) {
                                showMegaInfoDialog = true
                            } else {
                                onConnectProvider(provider)
                            }
                        }
                    )
                }
            }
        }

        // 4. Auto-Backup & Daily 24h WorkManager Schedule Controls
        item {
            AutoBackupScheduleCard(
                autoGalleryEnabled = uiState.settings.autoGalleryBackupEnabled,
                dailyScheduleEnabled = uiState.settings.dailyScheduleEnabled,
                scheduleHour = uiState.settings.scheduleHour,
                scheduleMinute = uiState.settings.scheduleMinute,
                wifiOnly = uiState.settings.wifiOnlyBackup,
                lastBackupTs = uiState.settings.lastBackupTimestamp,
                onToggleAutoBackup = onToggleAutoBackup,
                onToggleDailySchedule = onToggleDailySchedule,
                onOpenTimePicker = { showTimePicker = true },
                onUpdateWifiOnly = onUpdateWifiOnly,
                onRunBackupNow = onRunBackupNow
            )
        }

        // 5. Live Upload / Download Queue & History
        item {
            TransferQueueCard(
                transfers = uiState.transfers,
                onTogglePause = onTogglePauseTransfer,
                onRetry = onRetryTransfer,
                onClearCompleted = onClearCompletedTransfers
            )
        }
    }

    // Material 3 Schedule Time Picker Dialog
    if (showTimePicker) {
        val pickerState = rememberTimePickerState(
            initialHour = uiState.settings.scheduleHour,
            initialMinute = uiState.settings.scheduleMinute
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Daily Backup Time") },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                Button(
                    onClick = {
                        onUpdateScheduleTime(pickerState.hour, pickerState.minute)
                        showTimePicker = false
                    },
                    modifier = Modifier.testTag("confirm_schedule_time_button")
                ) { Text("Save Schedule") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            }
        )
    }

    if (showMegaInfoDialog) {
        AlertDialog(
            onDismissRequest = { showMegaInfoDialog = false },
            title = { Text("MEGA — Integration Pending") },
            text = {
                Text(
                    "MEGA requires official commercial MEGA SDK app registration and does not provide a standard public OAuth 2.0 REST authorization flow without password extraction. " +
                        "MyDrive enforces strict security compliance and never uses reverse-engineered APIs or stores cloud account passwords."
                )
            },
            confirmButton = {
                Button(onClick = { showMegaInfoDialog = false }) { Text("Understood") }
            }
        )
    }
}

@Composable
private fun TotalStorageHeaderCard(summary: AggregatedStorageSummary) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF0F244A),
                        CobaltPrimary.copy(alpha = 0.85f),
                        CyanSecondary.copy(alpha = 0.78f)
                    )
                )
            )
            .padding(20.dp)
            .testTag("total_storage_header_card")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "UNIFIED CLOUD POOL",
                        style = MaterialTheme.typography.labelMedium.copy(
                            letterSpacing = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Sp),
                            color = Color(0xFFBAE6FD)
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatBytes(summary.availableBytes) + " free",
                        style = MaterialTheme.typography.displaySmall.copy(color = Color.White)
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.16f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${summary.connectedAccountCount}",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                color = Color.White
                            )
                        )
                        Text(
                            text = "Connected",
                            style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFFE0F2FE))
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StorageStatColumn("Total Storage", formatBytes(summary.totalBytes))
                StorageStatColumn("Used Storage", formatBytes(summary.usedBytes))
                StorageStatColumn("Available", formatBytes(summary.availableBytes))
            }

            ThresholdProgressBar(
                usageRatio = summary.overallUsageRatio,
                trackColor = Color.White.copy(alpha = 0.22f),
                showMarkerLabel = false
            )

            val caption = when {
                summary.connectedAccountCount == 0 ->
                    "Live quota sum: 0 connected accounts. Disconnected & pending drives are excluded."
                summary.hasUnavailableQuotas ->
                    "Live sum from connected drives • Some accounts reported unavailable quotas."
                summary.hasEstimatedQuotas ->
                    "Live sum from connected drives • Includes estimated quota values."
                else ->
                    "Live sum strictly from connected provider APIs • Auto-switches drives at 70%."
            }
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFE0F2FE))
            )
        }
    }
}

@Composable
private fun StorageStatColumn(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFFBAE6FD))
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.copy(
                fontFamily = JetBrainsMonoFontFamily,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

@Composable
private fun ConnectedDriveCard(
    account: ConnectedAccountEntity,
    priorityRank: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("connected_account_card_${account.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "#$priorityRank",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = MaterialTheme.colorScheme.primary
                    )
                    ProviderBadge(provider = account.provider)
                }
                AccountStatusPill(status = account.accountStatus)
            }

            Text(
                text = account.accountEmail,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (account.hasValidQuota) {
                val quotaLabel = buildString {
                    append("${formatBytes(account.quotaUsed)} / ${formatBytes(account.quotaTotal)}")
                    if (account.quotaIsEstimated) append(" (Est.)")
                }
                Text(
                    text = quotaLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ThresholdProgressBar(usageRatio = account.usageRatio)
            } else {
                Text(
                    text = "Quota unavailable from provider API",
                    style = MaterialTheme.typography.bodySmall,
                    color = AmberThreshold
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row {
                    IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = "Increase priority")
                    }
                    IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                        Icon(Icons.Filled.ArrowDownward, contentDescription = "Decrease priority")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (account.accountStatus == AccountStatus.EXPIRED_RECONNECT) {
                        Button(onClick = onReconnect) { Text("Reconnect") }
                    }
                    TextButton(onClick = onDisconnect) {
                        Text("Disconnect", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderDirectoryCard(
    provider: CloudProviderType,
    connectedCount: Int,
    onConnect: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(18.dp)
            )
            .testTag("provider_card_${provider.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ProviderBadge(provider = provider)
                    Text(
                        text = provider.freeTierLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (connectedCount > 0) {
                        Text(
                            text = "• $connectedCount linked",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                Text(
                    text = provider.apiSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            if (provider.isIntegrationPending) {
                OutlinedButton(
                    onClick = onConnect,
                    modifier = Modifier.testTag("mega_pending_info_button")
                ) {
                    Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Pending")
                }
            } else {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.testTag("connect_provider_${provider.id}")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (connectedCount > 0) "Add" else "Connect")
                }
            }
        }
    }
}

@Composable
private fun AutoBackupScheduleCard(
    autoGalleryEnabled: Boolean,
    dailyScheduleEnabled: Boolean,
    scheduleHour: Int,
    scheduleMinute: Int,
    wifiOnly: Boolean,
    lastBackupTs: Long,
    onToggleAutoBackup: (Boolean) -> Unit,
    onToggleDailySchedule: (Boolean) -> Unit,
    onOpenTimePicker: () -> Unit,
    onUpdateWifiOnly: (Boolean) -> Unit,
    onRunBackupNow: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Backup,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("Auto-Backup & Daily Schedule", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "Non-destructive MediaStore scan • SHA-256 deduplication",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Automatic Gallery Backup", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Photos & Videos backed up via Smart 70% Storage Router.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoGalleryEnabled,
                    onCheckedChange = onToggleAutoBackup,
                    modifier = Modifier.testTag("switch_auto_gallery_backup")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Daily 24h Background Backup", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Runs via Android WorkManager even when app is closed/killed or after reboot.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = dailyScheduleEnabled,
                    onCheckedChange = onToggleDailySchedule,
                    enabled = autoGalleryEnabled,
                    modifier = Modifier.testTag("switch_daily_schedule_backup")
                )
            }

            // Schedule Time Picker Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("Daily Schedule Time", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = formatClockTime(scheduleHour, scheduleMinute),
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFontFamily)
                        )
                    }
                }
                OutlinedButton(
                    onClick = onOpenTimePicker,
                    modifier = Modifier.testTag("open_schedule_time_picker")
                ) {
                    Text("Change Time")
                }
            }

            // Network Preference
            Text("Network Preference", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = wifiOnly,
                    onClick = { onUpdateWifiOnly(true) },
                    leadingIcon = { Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("Wi-Fi Only (Default)") },
                    modifier = Modifier.testTag("network_pref_wifi_only")
                )
                FilterChip(
                    selected = !wifiOnly,
                    onClick = { onUpdateWifiOnly(false) },
                    leadingIcon = { Icon(Icons.Filled.CellTower, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("Allow Mobile Data") },
                    modifier = Modifier.testTag("network_pref_mobile_data")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Last run: ${formatShortDate(lastBackupTs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onRunBackupNow,
                    modifier = Modifier.testTag("run_backup_now_button")
                ) {
                    Text("Back Up Now")
                }
            }
        }
    }
}

@Composable
private fun TransferQueueCard(
    transfers: List<TransferTaskEntity>,
    onTogglePause: (TransferTaskEntity) -> Unit,
    onRetry: (TransferTaskEntity) -> Unit,
    onClearCompleted: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Live Queue & History (${transfers.size})", style = MaterialTheme.typography.titleLarge)
                if (transfers.any { it.transferStatus == TransferStatus.COMPLETED }) {
                    TextButton(onClick = onClearCompleted) { Text("Clear Done") }
                }
            }

            if (transfers.isEmpty()) {
                Text(
                    text = "No active uploads or downloads. Manual uploads and daily WorkManager gallery backups appear here with live progress and retry alerts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                transfers.take(10).forEach { task ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = task.fileName,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${task.transferDirection.name} • ${task.transferStatus.label} • ${formatBytes(task.totalBytes)}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                    color = if (task.transferStatus == TransferStatus.FAILED) CoralCritical
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            when (task.transferStatus) {
                                TransferStatus.IN_PROGRESS, TransferStatus.QUEUED, TransferStatus.PAUSED -> {
                                    IconButton(onClick = { onTogglePause(task) }) {
                                        Icon(
                                            imageVector = if (task.transferStatus == TransferStatus.PAUSED) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                            contentDescription = "Pause or resume transfer"
                                        )
                                    }
                                }
                                TransferStatus.FAILED -> {
                                    IconButton(onClick = { onRetry(task) }) {
                                        Icon(
                                            imageVector = Icons.Filled.Refresh,
                                            contentDescription = "Retry failed transfer",
                                            tint = CoralCritical
                                        )
                                    }
                                }
                                else -> Unit
                            }
                        }

                        if (task.transferStatus == TransferStatus.IN_PROGRESS || task.transferStatus == TransferStatus.PAUSED) {
                            LinearProgressIndicator(
                                progress = { task.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (!task.errorMessage.isNullOrBlank() && task.transferStatus == TransferStatus.FAILED) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.ErrorOutline,
                                    contentDescription = null,
                                    tint = CoralCritical,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = task.errorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CoralCritical
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
