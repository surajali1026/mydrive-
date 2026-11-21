package com.example.cloud

import com.example.data.local.CloudFileDao
import com.example.data.local.CloudFileEntity
import com.example.data.local.ConnectedAccountDao
import com.example.data.local.ConnectedAccountEntity
import com.example.data.model.AccountStatus
import com.example.data.model.FileCategory
import com.example.data.remote.BackendApiClient
import com.example.data.remote.CloudFileSyncDto
import com.example.security.TokenVault
import java.io.File
import java.util.UUID

data class RouteCandidate(
    val account: ConnectedAccountEntity,
    val isFallbackAbove70: Boolean
)

sealed interface SmartUploadResult {
    data class Success(
        val file: CloudFileEntity,
        val usedAccount: ConnectedAccountEntity,
        val warningAddAnotherDrive: Boolean
    ) : SmartUploadResult

    data class StorageFull(val message: String) : SmartUploadResult
    data class NoConnectedDrives(val message: String) : SmartUploadResult
    data class Failed(val message: String) : SmartUploadResult
}

/**
 * Smart Automatic Storage Engine (70% Threshold Rule + 95% Hard Safety Cap + Atomic Failover).
 *
 * Default mode (`SINGLE_DRIVE`): uploads every complete file to one eligible provider without splitting.
 * - Checks connected drives in `priorityOrder` ascending.
 * - Automatically skips any drive at or above 70% capacity (`usedBytes + fileSize > totalBytes * 0.70`).
 * - Falls back to the drive with largest remaining free space (up to 95% hard cap) if all drives >= 70%,
 *   flagging `warningAddAnotherDrive = true`.
 * - Verifies HTTP 200/201 + non-empty `remoteFileId` before saving metadata to Room & PostgreSQL.
 * - Fails over automatically to the next eligible drive on transient upload error without creating duplicates.
 */
class SmartStorageRouter(
    private val accountDao: ConnectedAccountDao,
    private val fileDao: CloudFileDao,
    private val adapterRegistry: CloudAdapterRegistry,
    private val tokenVault: TokenVault,
    private val backendApi: BackendApiClient
) {
    /**
     * Pure deterministic candidate ordering function implementing the 70% threshold & 95% fallback rule.
     */
    fun selectOrderedCandidates(
        accounts: List<ConnectedAccountEntity>,
        fileSizeBytes: Long,
        manualAccountId: String? = null
    ): List<RouteCandidate> {
        val healthy = accounts
            .filter { it.accountStatus == AccountStatus.CONNECTED && !it.provider.isIntegrationPending }
            .sortedBy { it.priorityOrder }

        if (healthy.isEmpty()) return emptyList()

        // Manual override mode: put chosen drive first if within 95% hard cap
        if (!manualAccountId.isNullOrBlank()) {
            val chosen = healthy.firstOrNull { it.id == manualAccountId }
            if (chosen != null && fitsWithinRatio(chosen, fileSizeBytes, HARD_SAFETY_CAP_RATIO)) {
                val above70 = !fitsWithinRatio(chosen, fileSizeBytes, AUTO_SWITCH_THRESHOLD_RATIO)
                return listOf(RouteCandidate(chosen, isFallbackAbove70 = above70))
            }
            return emptyList()
        }

        // Tier 1: All connected drives strictly under the 70% threshold in priority order
        val under70 = healthy
            .filter { fitsWithinRatio(it, fileSizeBytes, AUTO_SWITCH_THRESHOLD_RATIO) }
            .map { RouteCandidate(it, isFallbackAbove70 = false) }

        // Tier 2: Fallback drives >= 70% but <= 95% hard safety cap, sorted by largest remaining free space
        val fallbackUnder95 = healthy
            .filter {
                !fitsWithinRatio(it, fileSizeBytes, AUTO_SWITCH_THRESHOLD_RATIO) &&
                    fitsWithinRatio(it, fileSizeBytes, HARD_SAFETY_CAP_RATIO)
            }
            .sortedByDescending { it.availableBytes }
            .map { RouteCandidate(it, isFallbackAbove70 = true) }

        return under70 + fallbackUnder95
    }

    private fun fitsWithinRatio(
        account: ConnectedAccountEntity,
        fileSizeBytes: Long,
        maxRatio: Double
    ): Boolean {
        if (!account.hasValidQuota) return false
        val limitBytes = (account.quotaTotal.toDouble() * maxRatio).toLong()
        return (account.quotaUsed + fileSizeBytes) <= limitBytes
    }

    /**
     * Executes atomic upload with automatic failover across eligible drives.
     */
    suspend fun routeAndUpload(
        userId: String,
        localFile: File,
        fileName: String,
        mimeType: String,
        sha256Hash: String,
        folderPath: String = "/MyDrive",
        localThumbnailUri: String? = null,
        manualAccountId: String? = null,
        backendBaseUrl: String = "",
        onAccountSelected: (ConnectedAccountEntity) -> Unit = {},
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): SmartUploadResult {
        val allAccounts = accountDao.getAccountsSync(userId)
        val connected = allAccounts.filter {
            it.accountStatus == AccountStatus.CONNECTED && !it.provider.isIntegrationPending
        }
        if (connected.isEmpty()) {
            return SmartUploadResult.NoConnectedDrives(
                "No connected cloud drives found. Connect Google Drive, OneDrive, Dropbox, Box, pCloud, or Koofr in Tab 2."
            )
        }

        val fileSize = localFile.length()
        val candidates = selectOrderedCandidates(connected, fileSize, manualAccountId)
        if (candidates.isEmpty()) {
            return SmartUploadResult.StorageFull(
                "All connected drives have reached the 95% hard safety limit or lack free space for this file. Please connect another cloud drive."
            )
        }

        var lastError = "Upload failed across eligible drives."
        for (candidate in candidates) {
            val account = candidate.account
            onAccountSelected(account)

            val decryptedJson = tokenVault.decrypt(account.encryptedTokens)
            var tokens = decryptedJson?.let { StoredOAuthTokens.fromJson(it) }
            if (tokens == null || tokens.accessToken.isBlank()) {
                accountDao.updateStatus(account.id, AccountStatus.EXPIRED_RECONNECT.name)
                lastError = "${account.provider.displayName} (${account.accountEmail}) credentials expired."
                continue
            }

            val adapter = adapterRegistry.get(account.provider)

            // Refresh expired access token via backend broker before uploading
            if (tokens.isExpired() && backendBaseUrl.isNotBlank()) {
                val jwt = tokenVault.readJwtToken().orEmpty()
                when (val refreshed = adapter.refreshToken(backendBaseUrl, jwt, account.id)) {
                    is CloudResult.Success -> {
                        tokens = refreshed.data
                        val reEncrypted = tokenVault.encrypt(StoredOAuthTokens.toJson(tokens))
                        accountDao.updateAccount(account.copy(encryptedTokens = reEncrypted))
                    }
                    is CloudResult.TokenExpired, is CloudResult.Failure -> {
                        accountDao.updateStatus(account.id, AccountStatus.EXPIRED_RECONNECT.name)
                        lastError = "${account.provider.displayName} token expired — please reconnect."
                        continue
                    }
                    is CloudResult.IntegrationPending -> continue
                }
            }

            // Attempt atomic upload
            when (
                val uploadRes = adapter.uploadFile(
                    accessToken = tokens.accessToken,
                    localFile = localFile,
                    fileName = fileName,
                    mimeType = mimeType,
                    folderPath = folderPath,
                    onProgress = onProgress
                )
            ) {
                is CloudResult.Success -> {
                    val remote = uploadRes.data
                    // Atomic Verification: HTTP 200/201 + non-empty remoteFileId required!
                    if (!remote.isAtomicallyVerified) {
                        lastError = "${account.provider.displayName} returned unverified upload status (${remote.httpStatusCode})."
                        continue
                    }

                    val entity = CloudFileEntity(
                        id = UUID.randomUUID().toString(),
                        userId = userId,
                        accountId = account.id,
                        providerType = account.providerType,
                        remoteFileId = remote.remoteFileId,
                        fileName = remote.remoteName,
                        mimeType = mimeType,
                        sizeBytes = remote.sizeBytes,
                        sha256Hash = sha256Hash,
                        category = FileCategory.fromMimeType(mimeType).name,
                        folderPath = folderPath,
                        localThumbnailUri = localThumbnailUri,
                        isFavorite = false,
                        isTrashed = false,
                        uploadedAt = System.currentTimeMillis()
                    )

                    // Persist to Room
                    fileDao.upsertFile(entity)

                    // Update live quota on connected account
                    val updatedUsed = (account.quotaUsed + remote.sizeBytes).coerceAtMost(account.quotaTotal)
                    accountDao.updateQuota(account.id, updatedUsed, account.quotaTotal)

                    // Sync metadata to PostgreSQL backend if configured
                    val jwt = tokenVault.readJwtToken()
                    if (backendBaseUrl.isNotBlank() && !jwt.isNullOrBlank()) {
                        backendApi.syncFileMetadata(
                            baseUrl = backendBaseUrl,
                            jwtToken = jwt,
                            fileDto = CloudFileSyncDto(
                                id = entity.id,
                                accountId = entity.accountId,
                                providerType = entity.providerType,
                                remoteFileId = entity.remoteFileId,
                                fileName = entity.fileName,
                                mimeType = entity.mimeType,
                                sizeBytes = entity.sizeBytes,
                                sha256Hash = entity.sha256Hash,
                                category = entity.category,
                                folderPath = entity.folderPath,
                                isFavorite = entity.isFavorite,
                                isTrashed = entity.isTrashed,
                                uploadedAt = entity.uploadedAt
                            )
                        )
                    }

                    return SmartUploadResult.Success(
                        file = entity,
                        usedAccount = account.copy(quotaUsed = updatedUsed),
                        warningAddAnotherDrive = candidate.isFallbackAbove70
                    )
                }

                is CloudResult.TokenExpired -> {
                    accountDao.updateStatus(account.id, AccountStatus.EXPIRED_RECONNECT.name)
                    lastError = uploadRes.message
                    // Fail over automatically to next eligible drive without saving partial record
                }

                is CloudResult.Failure -> {
                    lastError = "${account.provider.displayName}: ${uploadRes.message}"
                    // Fail over automatically to next eligible drive
                }

                is CloudResult.IntegrationPending -> {
                    lastError = uploadRes.message
                }
            }
        }

        return SmartUploadResult.Failed(lastError)
    }

    companion object {
        const val AUTO_SWITCH_THRESHOLD_RATIO = 0.70
        const val HARD_SAFETY_CAP_RATIO = 0.95
    }
}
