package com.example.ui.screens

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.CloudFileEntity
import com.example.data.local.ConnectedAccountEntity
import com.example.data.model.AccountStatus
import com.example.data.model.CloudProviderType
import com.example.data.model.DateFilter
import com.example.data.model.FileCategory
import com.example.ui.components.FileThumbnailBox
import com.example.ui.components.ProviderBadge
import com.example.ui.components.formatBytes
import com.example.ui.components.formatShortDate
import com.example.ui.theme.AmberThreshold
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.viewmodel.GitHubUpdateUiState
import com.example.ui.viewmodel.MyDriveUiState

/**
 * Tab 1: My Uploads — Unified view of all files uploaded across every connected cloud drive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyUploadsScreen(
    uiState: MyDriveUiState,
    onSearchChange: (String) -> Unit,
    onCategorySelected: (FileCategory) -> Unit,
    onProviderFilterSelected: (CloudProviderType?) -> Unit,
    onDateFilterSelected: (DateFilter) -> Unit,
    onRequestFileUpload: (manualAccountId: String?) -> Unit,
    onDownloadFile: (CloudFileEntity) -> Unit,
    onRenameFile: (CloudFileEntity, String) -> Unit,
    onMoveFile: (CloudFileEntity, String) -> Unit,
    onToggleFavorite: (CloudFileEntity) -> Unit,
    onMoveToTrash: (CloudFileEntity) -> Unit,
    onRestoreFromTrash: (CloudFileEntity) -> Unit,
    onDeletePermanently: (CloudFileEntity) -> Unit,
    onOpenSettings: () -> Unit,
    onGoToCloudDrives: () -> Unit
) {
    var showUploadRoutingSheet by remember { mutableStateOf(false) }
    var previewFile by remember { mutableStateOf<CloudFileEntity?>(null) }
    var renameTarget by remember { mutableStateOf<CloudFileEntity?>(null) }
    var moveTarget by remember { mutableStateOf<CloudFileEntity?>(null) }
    var showProviderMenu by remember { mutableStateOf(false) }
    var showDateMenu by remember { mutableStateOf(false) }

    val hasUpdateBadge = uiState.updateState is GitHubUpdateUiState.Available ||
        uiState.updateState is GitHubUpdateUiState.ReadyToInstall
    val accountEmailById = remember(uiState.accounts) {
        uiState.accounts.associate { it.id to it.accountEmail }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "My Uploads",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = "${uiState.filteredFiles.size} file(s) across ${uiState.storageSummary.connectedAccountCount} connected drive(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag("uploads_open_settings_button")
                ) {
                    BadgedBox(
                        badge = {
                            if (hasUpdateBadge) {
                                Badge(containerColor = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings and GitHub Auto-Update"
                        )
                    }
                }
            }

            // Cross-Provider Search Bar
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("Search across all connected drives...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.searchQuery.isNotBlank()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .testTag("uploads_search_input")
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Category Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FileCategory.entries.forEach { category ->
                    FilterChip(
                        selected = uiState.selectedCategory == category,
                        onClick = { onCategorySelected(category) },
                        label = { Text(category.label) },
                        modifier = Modifier.testTag("category_chip_${category.name.lowercase()}")
                    )
                }
            }

            // Provider & Date Filter Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    FilterChip(
                        selected = uiState.selectedProviderFilter != null,
                        onClick = { showProviderMenu = true },
                        leadingIcon = {
                            Icon(Icons.Filled.FilterList, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = {
                            Text(uiState.selectedProviderFilter?.shortBadge ?: "All Drives")
                        },
                        modifier = Modifier.testTag("filter_provider_button")
                    )
                    DropdownMenu(
                        expanded = showProviderMenu,
                        onDismissRequest = { showProviderMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("All Connected Drives") },
                            onClick = {
                                onProviderFilterSelected(null)
                                showProviderMenu = false
                            }
                        )
                        CloudProviderType.entries.filter { !it.isIntegrationPending }.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text(provider.displayName) },
                                onClick = {
                                    onProviderFilterSelected(provider)
                                    showProviderMenu = false
                                }
                            )
                        }
                    }
                }

                Box {
                    FilterChip(
                        selected = uiState.selectedDateFilter != DateFilter.ALL_TIME,
                        onClick = { showDateMenu = true },
                        leadingIcon = {
                            Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text(uiState.selectedDateFilter.label) },
                        modifier = Modifier.testTag("filter_date_button")
                    )
                    DropdownMenu(
                        expanded = showDateMenu,
                        onDismissRequest = { showDateMenu = false }
                    ) {
                        DateFilter.entries.forEach { df ->
                            DropdownMenuItem(
                                text = { Text(df.label) },
                                onClick = {
                                    onDateFilterSelected(df)
                                    showDateMenu = false
                                }
                            )
                        }
                    }
                }
            }

            // File List or Empty State
            if (uiState.filteredFiles.isEmpty()) {
                EmptyUploadsState(
                    hasConnectedDrives = uiState.storageSummary.connectedAccountCount > 0,
                    isTrashTab = uiState.selectedCategory == FileCategory.TRASH,
                    onUploadClick = { showUploadRoutingSheet = true },
                    onConnectDriveClick = onGoToCloudDrives
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.filteredFiles, key = { it.id }) { file ->
                        CloudFileCard(
                            file = file,
                            accountEmail = accountEmailById[file.accountId],
                            onClick = { previewFile = file },
                            onDownload = { onDownloadFile(file) },
                            onRename = { renameTarget = file },
                            onMove = { moveTarget = file },
                            onToggleFavorite = { onToggleFavorite(file) },
                            onMoveToTrash = { onMoveToTrash(file) },
                            onRestoreFromTrash = { onRestoreFromTrash(file) },
                            onDeletePermanently = { onDeletePermanently(file) }
                        )
                    }
                }
            }
        }

        // Upload FAB (triggers Smart Storage Router or manual drive choice)
        ExtendedFloatingActionButton(
            onClick = { showUploadRoutingSheet = true },
            icon = { Icon(Icons.Filled.CloudUpload, contentDescription = "Upload file") },
            text = { Text("Upload") },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .testTag("uploads_fab_upload")
        )
    }

    // Upload Mode Bottom Sheet (Smart 70% Auto-Route vs Manual Drive Selection)
    if (showUploadRoutingSheet) {
        UploadRoutingModalSheet(
            connectedAccounts = uiState.accounts.filter {
                it.accountStatus == AccountStatus.CONNECTED && !it.provider.isIntegrationPending
            },
            onDismiss = { showUploadRoutingSheet = false },
            onSelectAndPickFile = { manualAccountId ->
                showUploadRoutingSheet = false
                onRequestFileUpload(manualAccountId)
            },
            onGoToConnectDrive = {
                showUploadRoutingSheet = false
                onGoToCloudDrives()
            }
        )
    }

    // In-App File Preview Modal
    previewFile?.let { file ->
        FilePreviewSheet(
            file = file,
            accountEmail = accountEmailById[file.accountId],
            onDismiss = { previewFile = null },
            onDownload = { onDownloadFile(file) },
            onRename = {
                previewFile = null
                renameTarget = file
            },
            onMove = {
                previewFile = null
                moveTarget = file
            },
            onDelete = {
                previewFile = null
                if (file.isTrashed) onDeletePermanently(file) else onMoveToTrash(file)
            }
        )
    }

    // Rename Dialog
    renameTarget?.let { file ->
        var newName by remember(file.id) { mutableStateOf(file.fileName) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename File") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New file name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    onRenameFile(file, newName)
                    renameTarget = null
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    // Move Folder Dialog
    moveTarget?.let { file ->
        var targetFolder by remember(file.id) { mutableStateOf(file.folderPath) }
        AlertDialog(
            onDismissRequest = { moveTarget = null },
            title = { Text("Move to Folder") },
            text = {
                OutlinedTextField(
                    value = targetFolder,
                    onValueChange = { targetFolder = it },
                    label = { Text("Destination folder path") },
                    placeholder = { Text("/MyDrive/Documents") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    onMoveFile(file, targetFolder)
                    moveTarget = null
                }) { Text("Move") }
            },
            dismissButton = {
                TextButton(onClick = { moveTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun CloudFileCard(
    file: CloudFileEntity,
    accountEmail: String?,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onToggleFavorite: () -> Unit,
    onMoveToTrash: () -> Unit,
    onRestoreFromTrash: () -> Unit,
    onDeletePermanently: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("file_card_${file.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FileThumbnailBox(
                category = file.fileCategory,
                thumbnailUri = file.localThumbnailUri,
                accentColor = file.provider.accentColor
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ProviderBadge(provider = file.provider)
                    Text(
                        text = formatBytes(file.sizeBytes),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                val metaSuffix = if (!accountEmail.isNullOrBlank()) {
                    "${formatShortDate(file.uploadedAt)} • $accountEmail"
                } else {
                    "${formatShortDate(file.uploadedAt)} • ${file.folderPath}"
                }
                Text(
                    text = metaSuffix,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (file.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (file.isFavorite) "Remove favorite" else "Add favorite",
                    tint = if (file.isFavorite) AmberThreshold else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "File options")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Download") },
                        leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                        onClick = { menuExpanded = false; onDownload() }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = { menuExpanded = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text("Move Folder") },
                        leadingIcon = { Icon(Icons.Filled.DriveFileMove, contentDescription = null) },
                        onClick = { menuExpanded = false; onMove() }
                    )
                    if (file.isTrashed) {
                        DropdownMenuItem(
                            text = { Text("Restore") },
                            leadingIcon = { Icon(Icons.Filled.RestoreFromTrash, contentDescription = null) },
                            onClick = { menuExpanded = false; onRestoreFromTrash() }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete Permanently") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { menuExpanded = false; onDeletePermanently() }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Move to Trash") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { menuExpanded = false; onMoveToTrash() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyUploadsState(
    hasConnectedDrives: Boolean,
    isTrashTab: Boolean,
    onUploadClick: () -> Unit,
    onConnectDriveClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_empty_uploads),
            contentDescription = "Empty cloud vault",
            modifier = Modifier
                .size(180.dp)
                .clip(RoundedCornerShape(24.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = if (isTrashTab) "Trash is Empty" else "No Uploaded Files Yet",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = when {
                isTrashTab -> "Files you move to trash appear here before permanent cloud deletion."
                hasConnectedDrives -> "Tap Upload to store any file with Smart 70% Auto-Routing across your connected drives."
                else -> "Connect your first cloud drive in Tab 2 to start uploading with automatic 70% capacity switching."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(18.dp))
        if (!isTrashTab) {
            if (hasConnectedDrives) {
                Button(
                    onClick = onUploadClick,
                    modifier = Modifier.testTag("empty_upload_cta")
                ) {
                    Icon(Icons.Filled.CloudUpload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Upload First File")
                }
            } else {
                Button(
                    onClick = onConnectDriveClick,
                    modifier = Modifier.testTag("empty_connect_drive_cta")
                ) {
                    Text("Connect a Cloud Drive")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UploadRoutingModalSheet(
    connectedAccounts: List<ConnectedAccountEntity>,
    onDismiss: () -> Unit,
    onSelectAndPickFile: (manualAccountId: String?) -> Unit,
    onGoToConnectDrive: () -> Unit
) {
    var selectedAccountId by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Choose Upload Destination", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Default SINGLE DRIVE mode uploads complete files without splitting and automatically switches drives at 70% capacity.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (connectedAccounts.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "No connected cloud drives yet.",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            "Connect Google Drive, OneDrive, Dropbox, Box, pCloud, or Koofr in Tab 2 to enable live cloud uploads.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(onClick = onGoToConnectDrive, modifier = Modifier.fillMaxWidth()) {
                            Text("Go to Cloud Drives")
                        }
                    }
                }
            } else {
                // Smart Auto-Route option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                        .clickable { selectedAccountId = null }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selectedAccountId == null, onClick = { selectedAccountId = null })
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Smart Auto-Route (70% Rule)", style = MaterialTheme.typography.titleSmall)
                        }
                        Text(
                            "Automatically selects the highest-priority drive under 70% capacity.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Manual drive options
                connectedAccounts.forEach { acc ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { selectedAccountId = acc.id }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selectedAccountId == acc.id, onClick = { selectedAccountId = acc.id })
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${acc.provider.displayName} • ${acc.accountEmail}", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${formatBytes(acc.quotaUsed)} / ${formatBytes(acc.quotaTotal)} (${(acc.usageRatio * 100).toInt()}%)",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Button(
                    onClick = { onSelectAndPickFile(selectedAccountId) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("confirm_pick_upload_file_button")
                ) {
                    Icon(Icons.Filled.CloudUpload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Pick File to Upload")
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilePreviewSheet(
    file: CloudFileEntity,
    accountEmail: String?,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = file.fileName,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                ProviderBadge(provider = file.provider)
            }

            if (file.fileCategory == FileCategory.PHOTOS && !file.localThumbnailUri.isNullOrBlank()) {
                AsyncImage(
                    model = Uri.parse(file.localThumbnailUri),
                    contentDescription = file.fileName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop
                )
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PreviewMetaRow("Cloud Drive", file.provider.displayName)
                    if (!accountEmail.isNullOrBlank()) {
                        PreviewMetaRow("Account", accountEmail)
                    }
                    PreviewMetaRow("Size", formatBytes(file.sizeBytes))
                    PreviewMetaRow("MIME Type", file.mimeType)
                    PreviewMetaRow("Folder", file.folderPath)
                    PreviewMetaRow("Remote ID", file.remoteFileId.take(28))
                    PreviewMetaRow("SHA-256", "${file.sha256Hash.take(18)}…")
                    PreviewMetaRow("Uploaded", formatShortDate(file.uploadedAt))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onDownload, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Download")
                }
                OutlinedButton(onClick = onRename, modifier = Modifier.weight(1f)) {
                    Text("Rename")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onMove, modifier = Modifier.weight(1f)) {
                    Text("Move")
                }
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                    Text(if (file.isTrashed) "Delete Forever" else "Trash", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PreviewMetaRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
