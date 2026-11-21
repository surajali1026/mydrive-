package com.example.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.cloud.SmartUploadResult
import com.example.data.local.CloudFileEntity
import com.example.data.local.ConnectedAccountEntity
import com.example.data.local.MyDriveSettings
import com.example.data.local.TransferTaskEntity
import com.example.data.local.UserEntity
import com.example.data.model.AccountStatus
import com.example.data.model.CloudProviderType
import com.example.data.model.DateFilter
import com.example.data.model.FileCategory
import com.example.data.remote.AppUpdateInfo
import com.example.data.remote.UpdateCheckResult
import com.example.data.repository.MyDriveRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class AggregatedStorageSummary(
    val connectedAccountCount: Int = 0,
    val totalBytes: Long = 0L,
    val usedBytes: Long = 0L,
    val availableBytes: Long = 0L,
    val hasUnavailableQuotas: Boolean = false,
    val hasEstimatedQuotas: Boolean = false
) {
    val overallUsageRatio: Float
        get() = if (totalBytes > 0L) (usedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f) else 0f
}

sealed interface GitHubUpdateUiState {
    data object Idle : GitHubUpdateUiState
    data object Checking : GitHubUpdateUiState
    data class Available(val info: AppUpdateInfo) : GitHubUpdateUiState
    data class Downloading(val info: AppUpdateInfo, val progress: Float) : GitHubUpdateUiState
    data class ReadyToInstall(val info: AppUpdateInfo, val apkFile: File) : GitHubUpdateUiState
    data class UpToDate(val currentVersion: String, val latestTag: String) : GitHubUpdateUiState
    data class InfoMessage(val message: String) : GitHubUpdateUiState
}

data class MyDriveUiState(
    val isInitialized: Boolean = false,
    val settings: MyDriveSettings = MyDriveSettings(),
    val currentUser: UserEntity? = null,
    val accounts: List<ConnectedAccountEntity> = emptyList(),
    val allFiles: List<CloudFileEntity> = emptyList(),
    val filteredFiles: List<CloudFileEntity> = emptyList(),
    val transfers: List<TransferTaskEntity> = emptyList(),
    val storageSummary: AggregatedStorageSummary = AggregatedStorageSummary(),
    val searchQuery: String = "",
    val selectedCategory: FileCategory = FileCategory.ALL,
    val selectedProviderFilter: CloudProviderType? = null,
    val selectedDateFilter: DateFilter = DateFilter.ALL_TIME,
    val updateState: GitHubUpdateUiState = GitHubUpdateUiState.Idle,
    val isBusy: Boolean = false,
    val snackbarMessage: String? = null,
    val pendingOAuthUrl: String? = null
)

private data class FilterCriteria(
    val searchQuery: String = "",
    val category: FileCategory = FileCategory.ALL,
    val provider: CloudProviderType? = null,
    val dateFilter: DateFilter = DateFilter.ALL_TIME
)

private data class EphemeralUiState(
    val isBusy: Boolean = false,
    val snackbarMessage: String? = null,
    val pendingOAuthUrl: String? = null,
    val updateState: GitHubUpdateUiState = GitHubUpdateUiState.Idle
)

@OptIn(ExperimentalCoroutinesApi::class)
class MyDriveViewModel(
    val repository: MyDriveRepository
) : ViewModel() {

    private val filters = MutableStateFlow(FilterCriteria())
    private val ephemeral = MutableStateFlow(EphemeralUiState())

    private val activeUserFlow = repository.settingsFlow.flatMapLatest { settings ->
        repository.observeUser(settings.activeUserId)
    }

    private val accountsFlow = repository.settingsFlow.flatMapLatest { settings ->
        repository.observeAccounts(settings.activeUserId)
    }

    private val filesFlow = repository.settingsFlow.flatMapLatest { settings ->
        repository.observeFiles(settings.activeUserId)
    }

    private val transfersFlow = repository.settingsFlow.flatMapLatest { settings ->
        repository.observeTransfers(settings.activeUserId)
    }

    private val coreDataFlow = combine(
        repository.settingsFlow,
        activeUserFlow,
        accountsFlow,
        filesFlow,
        transfersFlow
    ) { settings, user, accounts, files, transfers ->
        CoreDataBundle(settings, user, accounts, files, transfers)
    }

    val uiState: StateFlow<MyDriveUiState> = combine(
        coreDataFlow,
        filters,
        ephemeral
    ) { core, filter, eph ->
        val summary = computeLiveStorageSummary(core.accounts)
        val filtered = applyFileFilters(core.files, filter)
        MyDriveUiState(
            isInitialized = true,
            settings = core.settings,
            currentUser = core.user,
            accounts = core.accounts,
            allFiles = core.files,
            filteredFiles = filtered,
            transfers = core.transfers,
            storageSummary = summary,
            searchQuery = filter.searchQuery,
            selectedCategory = filter.category,
            selectedProviderFilter = filter.provider,
            selectedDateFilter = filter.dateFilter,
            updateState = eph.updateState,
            isBusy = eph.isBusy,
            snackbarMessage = eph.snackbarMessage,
            pendingOAuthUrl = eph.pendingOAuthUrl
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MyDriveUiState()
    )

    init {
        // Auto-check GitHub Releases (`mydrive` repo) silently on launch if configured & enabled
        viewModelScope.launch {
            val settings = repository.settingsStore.current()
            if (settings.autoUpdateEnabled && settings.githubOwner.isNotBlank()) {
                checkGitHubUpdates(silent = true)
            }
        }
    }

    // ==================== FILTERING ====================

    fun updateSearchQuery(query: String) = filters.update { it.copy(searchQuery = query) }
    fun selectCategory(category: FileCategory) = filters.update { it.copy(category = category) }
    fun selectProviderFilter(provider: CloudProviderType?) = filters.update { it.copy(provider = provider) }
    fun selectDateFilter(dateFilter: DateFilter) = filters.update { it.copy(dateFilter = dateFilter) }

    fun clearSnackbar() = ephemeral.update { it.copy(snackbarMessage = null) }
    fun consumePendingOAuthUrl() = ephemeral.update { it.copy(pendingOAuthUrl = null) }

    private fun showMessage(msg: String) = ephemeral.update { it.copy(snackbarMessage = msg) }

    // ==================== AUTH ====================

    fun register(email: String, password: String, displayName: String) {
        viewModelScope.launch {
            ephemeral.update { it.copy(isBusy = true) }
            val result = repository.registerWithEmail(email, password, displayName)
            ephemeral.update { it.copy(isBusy = false) }
            result.fold(
                onSuccess = { showMessage("Welcome to MyDrive, ${it.displayName}!") },
                onFailure = { showMessage(it.message ?: "Registration failed.") }
            )
        }
    }

    fun login(email: String, password: String) {
        viewModelScope.launch {
            ephemeral.update { it.copy(isBusy = true) }
            val result = repository.loginWithEmail(email, password)
            ephemeral.update { it.copy(isBusy = false) }
            result.fold(
                onSuccess = { showMessage("Signed in as ${it.email}") },
                onFailure = { showMessage(it.message ?: "Sign-in failed.") }
            )
        }
    }

    fun completeGoogleSignIn(idToken: String, email: String, displayName: String) {
        viewModelScope.launch {
            ephemeral.update { it.copy(isBusy = true) }
            val result = repository.completeGoogleSignIn(idToken, email, displayName)
            ephemeral.update { it.copy(isBusy = false) }
            result.fold(
                onSuccess = { showMessage("Signed in with Google (${it.email})") },
                onFailure = { showMessage(it.message ?: "Google Sign-In failed.") }
            )
        }
    }

    fun verifyCurrentUserEmail() {
        val uid = uiState.value.currentUser?.id ?: return
        viewModelScope.launch {
            repository.verifyEmail(uid)
            showMessage("Email address verified.")
        }
    }

    fun resetPassword(email: String, newPassword: String?) {
        viewModelScope.launch {
            ephemeral.update { it.copy(isBusy = true) }
            val res = repository.resetPassword(email, newPassword)
            ephemeral.update { it.copy(isBusy = false) }
            res.fold(
                onSuccess = { showMessage(it) },
                onFailure = { showMessage(it.message ?: "Reset failed.") }
            )
        }
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            showMessage("Signed out securely.")
        }
    }

    // ==================== CLOUD PROVIDERS ====================

    fun startProviderOAuth(provider: CloudProviderType, loginHint: String? = null) {
        viewModelScope.launch {
            ephemeral.update { it.copy(isBusy = true) }
            val result = repository.buildProviderOAuthUrl(provider, loginHint)
            ephemeral.update { it.copy(isBusy = false) }
            result.fold(
                onSuccess = { url -> ephemeral.update { it.copy(pendingOAuthUrl = url) } },
                onFailure = { showMessage(it.message ?: "Unable to launch OAuth flow.") }
            )
        }
    }

    fun handleOAuthRedirect(uri: Uri) {
        viewModelScope.launch {
            ephemeral.update { it.copy(isBusy = true) }
            val result = repository.handleOAuthCallback(uri)
            ephemeral.update { it.copy(isBusy = false) }
            result.fold(
                onSuccess = { acc ->
                    showMessage("Connected ${acc.provider.displayName} (${acc.accountEmail})")
                },
                onFailure = { showMessage(it.message ?: "OAuth callback failed.") }
            )
        }
    }

    fun refreshQuotas() {
        val uid = uiState.value.currentUser?.id ?: return
        viewModelScope.launch {
            ephemeral.update { it.copy(isBusy = true) }
            val count = repository.refreshAllQuotas(uid)
            ephemeral.update { it.copy(isBusy = false) }
            showMessage(
                if (count > 0) "Synced live quotas across $count connected drive(s)."
                else "No active connected drives to refresh."
            )
        }
    }

    fun disconnectAccount(accountId: String) {
        viewModelScope.launch {
            repository.disconnectAccount(accountId)
            showMessage("Cloud drive disconnected.")
        }
    }

    fun moveAccountPriority(accountId: String, moveUp: Boolean) {
        val uid = uiState.value.currentUser?.id ?: return
        viewModelScope.launch {
            repository.moveAccountPriority(uid, accountId, moveUp)
        }
    }

    // ==================== FILE OPERATIONS ====================

    fun uploadFile(uri: Uri, manualAccountId: String? = null) {
        val uid = uiState.value.currentUser?.id ?: return
        viewModelScope.launch {
            ephemeral.update { it.copy(isBusy = true) }
            val res = repository.uploadLocalUri(uid, uri, manualAccountId)
            ephemeral.update { it.copy(isBusy = false) }
            when (res) {
                is SmartUploadResult.Success -> {
                    val warn = if (res.warningAddAnotherDrive) " (Above 70% — add another drive soon)" else ""
                    val accountLabel = res.usedAccount.accountEmail.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                    showMessage("Uploaded to ${res.usedAccount.provider.displayName}$accountLabel$warn")
                }
                is SmartUploadResult.NoConnectedDrives -> showMessage(res.message)
                is SmartUploadResult.StorageFull -> showMessage(res.message)
                is SmartUploadResult.Failed -> showMessage(res.message)
            }
        }
    }

    fun downloadFile(file: CloudFileEntity, onDownloaded: (File) -> Unit = {}) {
        viewModelScope.launch {
            ephemeral.update { it.copy(isBusy = true) }
            val res = repository.downloadCloudFile(file)
            ephemeral.update { it.copy(isBusy = false) }
            res.fold(
                onSuccess = { downloaded ->
                    showMessage("Downloaded ${file.fileName}")
                    onDownloaded(downloaded)
                },
                onFailure = { showMessage(it.message ?: "Download failed.") }
            )
        }
    }

    fun renameFile(file: CloudFileEntity, newName: String) {
        viewModelScope.launch {
            val res = repository.renameCloudFile(file, newName)
            res.fold(
                onSuccess = { showMessage("Renamed to $newName") },
                onFailure = { showMessage(it.message ?: "Rename failed.") }
            )
        }
    }

    fun moveFile(file: CloudFileEntity, targetFolder: String) {
        viewModelScope.launch {
            val res = repository.moveCloudFile(file, targetFolder)
            res.fold(
                onSuccess = { showMessage("Moved to $targetFolder") },
                onFailure = { showMessage(it.message ?: "Move failed.") }
            )
        }
    }

    fun toggleFavorite(file: CloudFileEntity) {
        viewModelScope.launch { repository.toggleFavorite(file) }
    }

    fun moveToTrash(file: CloudFileEntity) {
        viewModelScope.launch {
            repository.moveToTrash(file)
            showMessage("Moved ${file.fileName} to Trash.")
        }
    }

    fun restoreFromTrash(file: CloudFileEntity) {
        viewModelScope.launch {
            repository.restoreFromTrash(file)
            showMessage("Restored ${file.fileName}.")
        }
    }

    fun deletePermanently(file: CloudFileEntity) {
        viewModelScope.launch {
            val res = repository.deletePermanently(file)
            res.fold(
                onSuccess = { showMessage("Deleted ${file.fileName} from cloud.") },
                onFailure = { showMessage(it.message ?: "Delete failed.") }
            )
        }
    }

    fun togglePauseTransfer(task: TransferTaskEntity) {
        viewModelScope.launch { repository.togglePauseTransfer(task) }
    }

    fun retryTransfer(task: TransferTaskEntity) {
        val uri = runCatching { Uri.parse(task.localUri) }.getOrNull() ?: return
        uploadFile(uri, manualAccountId = task.accountId.ifBlank { null })
    }

    fun clearCompletedTransfers() {
        val uid = uiState.value.currentUser?.id ?: return
        viewModelScope.launch { repository.clearCompletedTransfers(uid) }
    }

    // ==================== BACKUP SETTINGS ====================

    fun updateBackupPreferences(
        autoGalleryEnabled: Boolean,
        dailyScheduleEnabled: Boolean,
        hour: Int,
        minute: Int,
        wifiOnly: Boolean
    ) {
        viewModelScope.launch {
            repository.updateBackupPreferences(
                autoGalleryEnabled = autoGalleryEnabled,
                dailyScheduleEnabled = dailyScheduleEnabled,
                hour = hour,
                minute = minute,
                wifiOnly = wifiOnly
            )
            if (autoGalleryEnabled && dailyScheduleEnabled) {
                showMessage("Daily WorkManager backup armed — runs automatically every 24h even when app is closed.")
            }
        }
    }

    fun runBackupNow() {
        viewModelScope.launch {
            repository.triggerBackupNow()
            showMessage("Gallery backup queued with SHA-256 deduplication.")
        }
    }

    // ==================== SETTINGS & GITHUB AUTO-UPDATE ====================

    fun saveBackendUrl(url: String) {
        viewModelScope.launch {
            repository.settingsStore.setBackendBaseUrl(url)
            showMessage("Backend URL saved.")
        }
    }

    fun saveAutoUpdateConfig(enabled: Boolean, owner: String, repo: String) {
        viewModelScope.launch {
            repository.settingsStore.setAutoUpdateConfig(enabled, owner, repo)
            showMessage("GitHub auto-update configuration saved.")
        }
    }

    fun checkGitHubUpdates(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) {
                ephemeral.update { it.copy(updateState = GitHubUpdateUiState.Checking) }
            }
            when (val res = repository.checkForGitHubUpdate()) {
                is UpdateCheckResult.Available -> {
                    ephemeral.update { it.copy(updateState = GitHubUpdateUiState.Available(res.info)) }
                    if (!silent) showMessage("MyDrive v${res.info.latestVersion} is ready to install!")
                }
                is UpdateCheckResult.UpToDate -> {
                    ephemeral.update {
                        it.copy(updateState = GitHubUpdateUiState.UpToDate(res.currentVersion, res.latestTag))
                    }
                    if (!silent) showMessage("MyDrive is up to date (v${res.currentVersion}).")
                }
                is UpdateCheckResult.MissingRepoConfig -> {
                    if (!silent) {
                        ephemeral.update { it.copy(updateState = GitHubUpdateUiState.InfoMessage(res.message)) }
                    }
                }
                is UpdateCheckResult.Error -> {
                    if (!silent) {
                        ephemeral.update { it.copy(updateState = GitHubUpdateUiState.InfoMessage(res.message)) }
                    }
                }
            }
        }
    }

    fun downloadAndPrepareUpdate(info: AppUpdateInfo) {
        viewModelScope.launch {
            ephemeral.update { it.copy(updateState = GitHubUpdateUiState.Downloading(info, 0f)) }
            val result = repository.downloadGitHubUpdate(info) { progress ->
                ephemeral.update { it.copy(updateState = GitHubUpdateUiState.Downloading(info, progress)) }
            }
            result.fold(
                onSuccess = { apkFile ->
                    ephemeral.update {
                        it.copy(updateState = GitHubUpdateUiState.ReadyToInstall(info, apkFile))
                    }
                    showMessage("Update APK verified and ready for in-place install.")
                },
                onFailure = { err ->
                    ephemeral.update {
                        it.copy(updateState = GitHubUpdateUiState.InfoMessage(err.message ?: "Download failed"))
                    }
                    showMessage(err.message ?: "Update download failed.")
                }
            )
        }
    }

    // ==================== PURE AGGREGATION & FILTER LOGIC ====================

    private fun computeLiveStorageSummary(accounts: List<ConnectedAccountEntity>): AggregatedStorageSummary {
        // Strictly count ONLY live connected accounts; never include disconnected or Integration Pending drives
        val connected = accounts.filter {
            it.accountStatus == AccountStatus.CONNECTED && !it.provider.isIntegrationPending
        }
        var total = 0L
        var used = 0L
        var hasUnavailable = false
        var hasEstimated = false
        for (acc in connected) {
            if (acc.hasValidQuota) {
                total += acc.quotaTotal
                used += acc.quotaUsed
                if (acc.quotaIsEstimated) hasEstimated = true
            } else {
                hasUnavailable = true
            }
        }
        return AggregatedStorageSummary(
            connectedAccountCount = connected.size,
            totalBytes = total,
            usedBytes = used,
            availableBytes = (total - used).coerceAtLeast(0L),
            hasUnavailableQuotas = hasUnavailable,
            hasEstimatedQuotas = hasEstimated
        )
    }

    private fun applyFileFilters(
        files: List<CloudFileEntity>,
        criteria: FilterCriteria
    ): List<CloudFileEntity> {
        val now = System.currentTimeMillis()
        return files.filter { file ->
            // Trash vs Active split
            if (criteria.category == FileCategory.TRASH) {
                if (!file.isTrashed) return@filter false
            } else {
                if (file.isTrashed) return@filter false
            }

            // Category filter
            val categoryMatch = when (criteria.category) {
                FileCategory.ALL, FileCategory.TRASH -> true
                FileCategory.FAVORITES -> file.isFavorite
                FileCategory.RECENT -> (now - file.uploadedAt) <= 7L * 24L * 60L * 60L * 1000L
                else -> file.fileCategory == criteria.category
            }
            if (!categoryMatch) return@filter false

            // Provider filter
            if (criteria.provider != null && file.provider != criteria.provider) return@filter false

            // Date filter
            val window = criteria.dateFilter.windowMillis
            if (window != null && (now - file.uploadedAt) > window) return@filter false

            // Search query
            if (criteria.searchQuery.isNotBlank()) {
                val q = criteria.searchQuery.trim().lowercase()
                val matches = file.fileName.lowercase().contains(q) ||
                    file.provider.displayName.lowercase().contains(q) ||
                    file.folderPath.lowercase().contains(q)
                if (!matches) return@filter false
            }

            true
        }
    }

    private data class CoreDataBundle(
        val settings: MyDriveSettings,
        val user: UserEntity?,
        val accounts: List<ConnectedAccountEntity>,
        val files: List<CloudFileEntity>,
        val transfers: List<TransferTaskEntity>
    )

    class Factory(private val repository: MyDriveRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MyDriveViewModel(repository) as T
        }
    }
}
