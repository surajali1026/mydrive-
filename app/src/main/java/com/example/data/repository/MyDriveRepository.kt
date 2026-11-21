package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.cloud.CloudAdapterRegistry
import com.example.cloud.CloudResult
import com.example.cloud.SmartStorageRouter
import com.example.cloud.SmartUploadResult
import com.example.cloud.StoredOAuthTokens
import com.example.data.local.CloudFileEntity
import com.example.data.local.ConnectedAccountEntity
import com.example.data.local.MyDriveDatabase
import com.example.data.local.MyDriveSettings
import com.example.data.local.SettingsStore
import com.example.data.local.TransferTaskEntity
import com.example.data.local.UserEntity
import com.example.data.model.AccountStatus
import com.example.data.model.CloudProviderType
import com.example.data.model.TransferDirection
import com.example.data.model.TransferStatus
import com.example.data.remote.AppUpdateInfo
import com.example.data.remote.BackendApiClient
import com.example.data.remote.GitHubAutoUpdater
import com.example.data.remote.UpdateCheckResult
import com.example.security.PkceHelper
import com.example.security.TokenVault
import com.example.worker.BackupScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class MyDriveRepository(
    private val context: Context,
    private val db: MyDriveDatabase,
    val settingsStore: SettingsStore,
    val tokenVault: TokenVault,
    val backendApi: BackendApiClient,
    val adapterRegistry: CloudAdapterRegistry,
    val smartRouter: SmartStorageRouter,
    val autoUpdater: GitHubAutoUpdater
) {
    val settingsFlow: Flow<MyDriveSettings> = settingsStore.settingsFlow

    fun observeUser(userId: String?): Flow<UserEntity?> =
        if (userId.isNullOrBlank()) flowOf(null) else db.userDao().observeUser(userId)

    fun observeAccounts(userId: String?): Flow<List<ConnectedAccountEntity>> =
        if (userId.isNullOrBlank()) flowOf(emptyList()) else db.connectedAccountDao().observeAccounts(userId)

    fun observeFiles(userId: String?): Flow<List<CloudFileEntity>> =
        if (userId.isNullOrBlank()) flowOf(emptyList()) else db.cloudFileDao().observeFiles(userId)

    fun observeTransfers(userId: String?): Flow<List<TransferTaskEntity>> =
        if (userId.isNullOrBlank()) flowOf(emptyList()) else db.transferTaskDao().observeTasks(userId)

    // ==================== AUTHENTICATION ====================

    suspend fun registerWithEmail(
        email: String,
        password: String,
        displayName: String
    ): Result<UserEntity> = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim().lowercase()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
            return@withContext Result.failure(IllegalArgumentException("Enter a valid email address."))
        }
        if (password.length < 8) {
            return@withContext Result.failure(IllegalArgumentException("Password must be at least 8 characters."))
        }

        val settings = settingsStore.current()
        if (settings.backendBaseUrl.isNotBlank()) {
            val remote = backendApi.register(settings.backendBaseUrl, cleanEmail, password, displayName.trim())
            if (remote.isSuccess) {
                val dto = remote.getOrThrow()
                tokenVault.saveJwtToken(dto.token)
                val user = UserEntity(
                    id = dto.user.id,
                    email = dto.user.email,
                    displayName = dto.user.displayName ?: displayName.ifBlank { cleanEmail.substringBefore("@") },
                    authProvider = "email",
                    isVerified = dto.user.isVerified
                )
                db.userDao().upsertUser(user)
                settingsStore.setActiveUserId(user.id)
                return@withContext Result.success(user)
            }
            return@withContext Result.failure(remote.exceptionOrNull() ?: Exception("Registration failed"))
        }

        // Local encrypted Room account creation when Node.js backend is not yet linked
        val existing = db.userDao().getUserByEmail(cleanEmail)
        if (existing != null) {
            return@withContext Result.failure(IllegalStateException("An account with this email already exists."))
        }
        val user = UserEntity(
            id = UUID.randomUUID().toString(),
            email = cleanEmail,
            displayName = displayName.trim().ifBlank { cleanEmail.substringBefore("@") },
            authProvider = "email",
            passwordHash = TokenVault.hashPassword(password),
            isVerified = false
        )
        db.userDao().upsertUser(user)
        settingsStore.setActiveUserId(user.id)
        Result.success(user)
    }

    suspend fun loginWithEmail(email: String, password: String): Result<UserEntity> = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim().lowercase()
        val settings = settingsStore.current()
        if (settings.backendBaseUrl.isNotBlank()) {
            val remote = backendApi.login(settings.backendBaseUrl, cleanEmail, password)
            if (remote.isSuccess) {
                val dto = remote.getOrThrow()
                tokenVault.saveJwtToken(dto.token)
                val user = UserEntity(
                    id = dto.user.id,
                    email = dto.user.email,
                    displayName = dto.user.displayName ?: cleanEmail.substringBefore("@"),
                    authProvider = dto.user.authProvider,
                    isVerified = dto.user.isVerified
                )
                db.userDao().upsertUser(user)
                settingsStore.setActiveUserId(user.id)
                return@withContext Result.success(user)
            }
            return@withContext Result.failure(remote.exceptionOrNull() ?: Exception("Sign-in failed"))
        }

        val user = db.userDao().getUserByEmail(cleanEmail)
            ?: return@withContext Result.failure(IllegalArgumentException("No local account found for $cleanEmail. Tap Create Account first."))
        val storedHash = user.passwordHash
            ?: return@withContext Result.failure(IllegalArgumentException("Use Google Sign-In for this account."))
        if (!TokenVault.verifyPassword(password, storedHash)) {
            return@withContext Result.failure(IllegalArgumentException("Incorrect password."))
        }
        settingsStore.setActiveUserId(user.id)
        Result.success(user)
    }

    suspend fun completeGoogleSignIn(idToken: String, email: String, displayName: String): Result<UserEntity> =
        withContext(Dispatchers.IO) {
            val settings = settingsStore.current()
            if (settings.backendBaseUrl.isNotBlank() && idToken.isNotBlank()) {
                val remote = backendApi.loginWithGoogleIdToken(settings.backendBaseUrl, idToken)
                if (remote.isSuccess) {
                    val dto = remote.getOrThrow()
                    tokenVault.saveJwtToken(dto.token)
                    val user = UserEntity(
                        id = dto.user.id,
                        email = dto.user.email,
                        displayName = dto.user.displayName ?: displayName,
                        authProvider = "google",
                        isVerified = true
                    )
                    db.userDao().upsertUser(user)
                    settingsStore.setActiveUserId(user.id)
                    return@withContext Result.success(user)
                }
            }
            val existing = db.userDao().getUserByEmail(email)
            val user = existing ?: UserEntity(
                id = UUID.randomUUID().toString(),
                email = email.trim().lowercase(),
                displayName = displayName.ifBlank { email.substringBefore("@") },
                authProvider = "google",
                isVerified = true
            )
            db.userDao().upsertUser(user)
            settingsStore.setActiveUserId(user.id)
            Result.success(user)
        }

    suspend fun verifyEmail(userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        db.userDao().setVerified(userId, true)
        Result.success(Unit)
    }

    suspend fun resetPassword(email: String, newPassword: String?): Result<String> = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim().lowercase()
        val settings = settingsStore.current()
        if (settings.backendBaseUrl.isNotBlank()) {
            return@withContext backendApi.requestPasswordReset(settings.backendBaseUrl, cleanEmail)
                .map { "Password reset instructions sent to $cleanEmail." }
        }
        val existing = db.userDao().getUserByEmail(cleanEmail)
            ?: return@withContext Result.failure(IllegalArgumentException("Account not found for $cleanEmail."))
        if (newPassword.isNullOrBlank() || newPassword.length < 8) {
            return@withContext Result.failure(IllegalArgumentException("Enter a new password of at least 8 characters."))
        }
        db.userDao().updatePasswordHash(existing.email, TokenVault.hashPassword(newPassword))
        Result.success("Password updated securely for $cleanEmail.")
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        tokenVault.clearAllSessionSecrets()
        settingsStore.setActiveUserId(null)
    }

    // ==================== CLOUD PROVIDER OAUTH & QUOTAS ====================

    suspend fun buildProviderOAuthUrl(provider: CloudProviderType): Result<String> = withContext(Dispatchers.IO) {
        if (provider.isIntegrationPending) {
            return@withContext Result.failure(
                IllegalStateException("${provider.displayName}: Integration Pending — official commercial SDK registration required.")
            )
        }
        val settings = settingsStore.current()
        if (settings.backendBaseUrl.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException(
                    "Set your MyDrive Node.js OAuth Backend URL in Settings before starting ${provider.displayName} OAuth 2.0 PKCE."
                )
            )
        }
        val jwt = tokenVault.readJwtToken().orEmpty()
        val pkce = PkceHelper.generate()
        tokenVault.savePendingOAuth(provider.id, pkce.state, pkce.codeVerifier)

        backendApi.getOAuthAuthorizeUrl(
            baseUrl = settings.backendBaseUrl,
            jwtToken = jwt,
            providerId = provider.id,
            codeChallenge = pkce.codeChallenge,
            state = pkce.state,
            redirectUri = PkceHelper.REDIRECT_URI
        ).map { it.authorizeUrl }
    }

    suspend fun handleOAuthCallback(callbackUri: Uri): Result<ConnectedAccountEntity> = withContext(Dispatchers.IO) {
        val code = callbackUri.getQueryParameter("code")
        val returnedState = callbackUri.getQueryParameter("state")
        val oauthError = callbackUri.getQueryParameter("error")

        if (!oauthError.isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("Provider authorization denied: $oauthError"))
        }
        if (code.isNullOrBlank() || returnedState.isNullOrBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Missing OAuth authorization code or state."))
        }

        val pending = tokenVault.consumePendingOAuth()
            ?: return@withContext Result.failure(IllegalStateException("No pending OAuth session found."))
        val (providerId, expectedState, codeVerifier) = pending
        if (returnedState != expectedState) {
            return@withContext Result.failure(SecurityException("OAuth state mismatch — request rejected."))
        }

        val settings = settingsStore.current()
        val userId = settings.activeUserId
            ?: return@withContext Result.failure(IllegalStateException("Sign in to MyDrive first."))
        val jwt = tokenVault.readJwtToken().orEmpty()

        val exchange = backendApi.exchangeOAuthCode(
            baseUrl = settings.backendBaseUrl,
            jwtToken = jwt,
            providerId = providerId,
            code = code,
            codeVerifier = codeVerifier,
            redirectUri = PkceHelper.REDIRECT_URI
        )
        if (exchange.isFailure) {
            return@withContext Result.failure(exchange.exceptionOrNull() ?: Exception("OAuth token exchange failed"))
        }

        val bundle = exchange.getOrThrow()
        val provider = CloudProviderType.fromId(providerId)
        val adapter = adapterRegistry.get(provider)
        val quotaResult = adapter.getQuota(bundle.accessToken)
        val quota = (quotaResult as? CloudResult.Success)?.data

        val existingAccounts = db.connectedAccountDao().getAccountsSync(userId)
        val encrypted = tokenVault.encrypt(
            StoredOAuthTokens.toJson(
                StoredOAuthTokens(
                    accessToken = bundle.accessToken,
                    refreshToken = bundle.refreshToken,
                    expiresAtEpochMs = bundle.expiresAtEpochMs
                )
            )
        )
        val entity = ConnectedAccountEntity(
            id = bundle.accountId.ifBlank { UUID.randomUUID().toString() },
            userId = userId,
            providerType = provider.id,
            accountEmail = quota?.accountEmail?.ifBlank { bundle.accountEmail } ?: bundle.accountEmail.ifBlank { "Connected Account" },
            encryptedTokens = encrypted,
            quotaTotal = quota?.totalBytes ?: -1L,
            quotaUsed = quota?.usedBytes ?: -1L,
            quotaIsEstimated = quota?.isEstimated ?: false,
            priorityOrder = existingAccounts.size,
            status = AccountStatus.CONNECTED.name,
            lastSyncedAt = System.currentTimeMillis()
        )
        db.connectedAccountDao().upsertAccount(entity)
        Result.success(entity)
    }

    suspend fun refreshAllQuotas(userId: String): Int = withContext(Dispatchers.IO) {
        val accounts = db.connectedAccountDao().getAccountsSync(userId)
        val settings = settingsStore.current()
        var refreshedCount = 0
        for (account in accounts) {
            if (account.provider.isIntegrationPending || account.accountStatus == AccountStatus.DISCONNECTED) continue
            val tokenStr = tokenVault.decrypt(account.encryptedTokens) ?: continue
            var tokens = StoredOAuthTokens.fromJson(tokenStr) ?: continue
            val adapter = adapterRegistry.get(account.provider)

            if (tokens.isExpired() && settings.backendBaseUrl.isNotBlank()) {
                val jwt = tokenVault.readJwtToken().orEmpty()
                when (val ref = adapter.refreshToken(settings.backendBaseUrl, jwt, account.id)) {
                    is CloudResult.Success -> {
                        tokens = ref.data
                        db.connectedAccountDao().updateAccount(
                            account.copy(encryptedTokens = tokenVault.encrypt(StoredOAuthTokens.toJson(tokens)))
                        )
                    }
                    is CloudResult.TokenExpired, is CloudResult.Failure -> {
                        db.connectedAccountDao().updateStatus(account.id, AccountStatus.EXPIRED_RECONNECT.name)
                        continue
                    }
                    is CloudResult.IntegrationPending -> continue
                }
            }

            when (val quotaRes = adapter.getQuota(tokens.accessToken)) {
                is CloudResult.Success -> {
                    db.connectedAccountDao().updateQuota(account.id, quotaRes.data.usedBytes, quotaRes.data.totalBytes)
                    db.connectedAccountDao().updateStatus(account.id, AccountStatus.CONNECTED.name)
                    refreshedCount++
                }
                is CloudResult.TokenExpired -> {
                    db.connectedAccountDao().updateStatus(account.id, AccountStatus.EXPIRED_RECONNECT.name)
                }
                else -> Unit
            }
        }
        refreshedCount
    }

    suspend fun disconnectAccount(accountId: String) = withContext(Dispatchers.IO) {
        db.cloudFileDao().deleteFilesForAccount(accountId)
        db.connectedAccountDao().deleteAccount(accountId)
    }

    suspend fun moveAccountPriority(userId: String, accountId: String, moveUp: Boolean) = withContext(Dispatchers.IO) {
        val list = db.connectedAccountDao().getAccountsSync(userId).sortedBy { it.priorityOrder }.toMutableList()
        val idx = list.indexOfFirst { it.id == accountId }
        if (idx == -1) return@withContext
        val target = if (moveUp) idx - 1 else idx + 1
        if (target !in list.indices) return@withContext
        val swapped = list.removeAt(idx)
        list.add(target, swapped)
        list.forEachIndexed { order, acc ->
            db.connectedAccountDao().updatePriority(acc.id, order)
        }
    }

    // ==================== UNIFIED FILE OPERATIONS ====================

    suspend fun uploadLocalUri(
        userId: String,
        uri: Uri,
        manualAccountId: String? = null
    ): SmartUploadResult = withContext(Dispatchers.IO) {
        val (displayName, sizeBytes) = resolveUriMeta(uri)
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val taskId = UUID.randomUUID().toString()

        val stagingDir = File(context.cacheDir, "upload_staging").apply { mkdirs() }
        val stagedFile = File(stagingDir, "${UUID.randomUUID()}_${displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")}")

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(stagedFile).use { output -> input.copyTo(output, 16 * 1024) }
            } ?: return@withContext SmartUploadResult.Failed("Could not open selected file.")

            val sha256 = stagedFile.inputStream().use { TokenVault.computeSha256(it) }
            val actualSize = if (stagedFile.length() > 0L) stagedFile.length() else sizeBytes

            db.transferTaskDao().upsertTask(
                TransferTaskEntity(
                    id = taskId,
                    userId = userId,
                    fileName = displayName,
                    localUri = uri.toString(),
                    mimeType = mimeType,
                    totalBytes = actualSize,
                    transferredBytes = 0L,
                    direction = TransferDirection.UPLOAD.name,
                    status = TransferStatus.IN_PROGRESS.name
                )
            )

            val settings = settingsStore.current()
            val result = smartRouter.routeAndUpload(
                userId = userId,
                localFile = stagedFile,
                fileName = displayName,
                mimeType = mimeType,
                sha256Hash = sha256,
                localThumbnailUri = uri.toString(),
                manualAccountId = manualAccountId,
                backendBaseUrl = settings.backendBaseUrl
            )

            when (result) {
                is SmartUploadResult.Success -> {
                    db.transferTaskDao().upsertTask(
                        TransferTaskEntity(
                            id = taskId,
                            userId = userId,
                            fileName = displayName,
                            localUri = uri.toString(),
                            mimeType = mimeType,
                            totalBytes = actualSize,
                            transferredBytes = actualSize,
                            providerType = result.usedAccount.providerType,
                            accountId = result.usedAccount.id,
                            direction = TransferDirection.UPLOAD.name,
                            status = TransferStatus.COMPLETED.name
                        )
                    )
                }
                is SmartUploadResult.NoConnectedDrives -> {
                    db.transferTaskDao().updateProgress(taskId, 0L, TransferStatus.FAILED.name, result.message)
                }
                is SmartUploadResult.StorageFull -> {
                    db.transferTaskDao().updateProgress(taskId, 0L, TransferStatus.FAILED.name, result.message)
                }
                is SmartUploadResult.Failed -> {
                    db.transferTaskDao().updateProgress(taskId, 0L, TransferStatus.FAILED.name, result.message)
                }
            }
            result
        } catch (e: Exception) {
            db.transferTaskDao().updateProgress(taskId, 0L, TransferStatus.FAILED.name, e.message)
            SmartUploadResult.Failed(e.message ?: "Upload failed")
        } finally {
            stagedFile.delete()
        }
    }

    suspend fun downloadCloudFile(file: CloudFileEntity): Result<File> = withContext(Dispatchers.IO) {
        val account = db.connectedAccountDao().getAccountById(file.accountId)
            ?: return@withContext Result.failure(IllegalStateException("Source drive account is disconnected."))
        val tokens = tokenVault.decrypt(account.encryptedTokens)?.let { StoredOAuthTokens.fromJson(it) }
            ?: return@withContext Result.failure(IllegalStateException("Account token expired — reconnect in Tab 2."))
        val adapter = adapterRegistry.get(file.provider)

        val destDir = File(context.filesDir, "downloads").apply { mkdirs() }
        val destFile = File(destDir, file.fileName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        val taskId = UUID.randomUUID().toString()
        db.transferTaskDao().upsertTask(
            TransferTaskEntity(
                id = taskId,
                userId = file.userId,
                fileName = file.fileName,
                localUri = destFile.absolutePath,
                mimeType = file.mimeType,
                totalBytes = file.sizeBytes,
                transferredBytes = 0L,
                providerType = file.providerType,
                accountId = file.accountId,
                direction = TransferDirection.DOWNLOAD.name,
                status = TransferStatus.IN_PROGRESS.name
            )
        )

        when (val res = adapter.downloadFile(tokens.accessToken, file.remoteFileId, destFile)) {
            is CloudResult.Success -> {
                db.transferTaskDao().updateProgress(taskId, file.sizeBytes, TransferStatus.COMPLETED.name)
                Result.success(res.data)
            }
            is CloudResult.TokenExpired -> {
                db.connectedAccountDao().updateStatus(account.id, AccountStatus.EXPIRED_RECONNECT.name)
                db.transferTaskDao().updateProgress(taskId, 0L, TransferStatus.FAILED.name, res.message)
                Result.failure(IllegalStateException(res.message))
            }
            is CloudResult.Failure -> {
                db.transferTaskDao().updateProgress(taskId, 0L, TransferStatus.FAILED.name, res.message)
                Result.failure(IllegalStateException(res.message))
            }
            is CloudResult.IntegrationPending -> {
                db.transferTaskDao().updateProgress(taskId, 0L, TransferStatus.FAILED.name, res.message)
                Result.failure(IllegalStateException(res.message))
            }
        }
    }

    suspend fun renameCloudFile(file: CloudFileEntity, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanName = newName.trim()
        if (cleanName.isBlank()) return@withContext Result.failure(IllegalArgumentException("File name cannot be empty."))
        val account = db.connectedAccountDao().getAccountById(file.accountId)
            ?: return@withContext Result.failure(IllegalStateException("Account not found."))
        val tokens = tokenVault.decrypt(account.encryptedTokens)?.let { StoredOAuthTokens.fromJson(it) }
            ?: return@withContext Result.failure(IllegalStateException("Token expired — reconnect account."))
        val adapter = adapterRegistry.get(file.provider)
        when (val res = adapter.renameFile(tokens.accessToken, file.remoteFileId, cleanName)) {
            is CloudResult.Success -> {
                db.cloudFileDao().renameFile(file.id, cleanName)
                Result.success(Unit)
            }
            is CloudResult.TokenExpired -> {
                db.connectedAccountDao().updateStatus(account.id, AccountStatus.EXPIRED_RECONNECT.name)
                Result.failure(IllegalStateException(res.message))
            }
            is CloudResult.Failure -> Result.failure(IllegalStateException(res.message))
            is CloudResult.IntegrationPending -> Result.failure(IllegalStateException(res.message))
        }
    }

    suspend fun moveCloudFile(file: CloudFileEntity, newFolderPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        val normalized = "/" + newFolderPath.trim().trim('/').ifBlank { "MyDrive" }
        val account = db.connectedAccountDao().getAccountById(file.accountId)
            ?: return@withContext Result.failure(IllegalStateException("Account not found."))
        val tokens = tokenVault.decrypt(account.encryptedTokens)?.let { StoredOAuthTokens.fromJson(it) }
            ?: return@withContext Result.failure(IllegalStateException("Token expired — reconnect account."))
        val adapter = adapterRegistry.get(file.provider)
        when (val res = adapter.moveFile(tokens.accessToken, file.remoteFileId, normalized)) {
            is CloudResult.Success -> {
                db.cloudFileDao().updateFolderPath(file.id, normalized)
                Result.success(Unit)
            }
            is CloudResult.TokenExpired -> {
                db.connectedAccountDao().updateStatus(account.id, AccountStatus.EXPIRED_RECONNECT.name)
                Result.failure(IllegalStateException(res.message))
            }
            is CloudResult.Failure -> Result.failure(IllegalStateException(res.message))
            is CloudResult.IntegrationPending -> Result.failure(IllegalStateException(res.message))
        }
    }

    suspend fun toggleFavorite(file: CloudFileEntity) = withContext(Dispatchers.IO) {
        db.cloudFileDao().setFavorite(file.id, !file.isFavorite)
    }

    suspend fun moveToTrash(file: CloudFileEntity) = withContext(Dispatchers.IO) {
        db.cloudFileDao().setTrashed(file.id, true)
    }

    suspend fun restoreFromTrash(file: CloudFileEntity) = withContext(Dispatchers.IO) {
        db.cloudFileDao().setTrashed(file.id, false)
    }

    suspend fun deletePermanently(file: CloudFileEntity): Result<Unit> = withContext(Dispatchers.IO) {
        val account = db.connectedAccountDao().getAccountById(file.accountId)
        val tokens = account?.let { tokenVault.decrypt(it.encryptedTokens) }?.let { StoredOAuthTokens.fromJson(it) }
        if (account != null && tokens != null) {
            when (val res = adapterRegistry.get(file.provider).deleteFile(tokens.accessToken, file.remoteFileId)) {
                is CloudResult.Failure -> return@withContext Result.failure(IllegalStateException(res.message))
                is CloudResult.TokenExpired -> {
                    db.connectedAccountDao().updateStatus(account.id, AccountStatus.EXPIRED_RECONNECT.name)
                    return@withContext Result.failure(IllegalStateException(res.message))
                }
                else -> Unit
            }
        }
        db.cloudFileDao().deleteFilePermanently(file.id)
        Result.success(Unit)
    }

    suspend fun togglePauseTransfer(task: TransferTaskEntity) = withContext(Dispatchers.IO) {
        val next = when (task.transferStatus) {
            TransferStatus.IN_PROGRESS, TransferStatus.QUEUED -> TransferStatus.PAUSED
            TransferStatus.PAUSED -> TransferStatus.QUEUED
            else -> task.transferStatus
        }
        db.transferTaskDao().updateProgress(task.id, task.transferredBytes, next.name, task.errorMessage)
    }

    suspend fun clearCompletedTransfers(userId: String) = withContext(Dispatchers.IO) {
        db.transferTaskDao().clearCompleted(userId)
    }

    // ==================== BACKUP SCHEDULING ====================

    suspend fun updateBackupPreferences(
        autoGalleryEnabled: Boolean,
        dailyScheduleEnabled: Boolean,
        hour: Int,
        minute: Int,
        wifiOnly: Boolean
    ) {
        settingsStore.setAutoGalleryBackup(autoGalleryEnabled)
        settingsStore.setDailySchedule(dailyScheduleEnabled, hour, minute)
        settingsStore.setWifiOnlyBackup(wifiOnly)

        if (autoGalleryEnabled && dailyScheduleEnabled) {
            BackupScheduler.scheduleDailyBackup(
                context = context,
                hour = hour,
                minute = minute,
                wifiOnly = wifiOnly,
                replaceExisting = true
            )
        } else {
            BackupScheduler.cancelDailyBackup(context)
        }
    }

    suspend fun triggerBackupNow() {
        val settings = settingsStore.current()
        BackupScheduler.runImmediateBackup(context, settings.wifiOnlyBackup)
    }

    // ==================== GITHUB AUTO-UPDATE ====================

    suspend fun checkForGitHubUpdate(): UpdateCheckResult {
        val settings = settingsStore.current()
        return autoUpdater.checkLatestRelease(settings.githubOwner, settings.githubRepo)
    }

    suspend fun downloadGitHubUpdate(
        info: AppUpdateInfo,
        onProgress: (Float) -> Unit
    ): Result<File> = autoUpdater.downloadUpdateApk(info, onProgress)

    private fun resolveUriMeta(uri: Uri): Pair<String, Long> {
        var name = "upload_${System.currentTimeMillis()}"
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) cursor.getString(nameIdx)?.let { if (it.isNotBlank()) name = it }
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
        }
        return name to size
    }
}
