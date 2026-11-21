package com.example.cloud

import com.example.data.model.CloudProviderType
import java.io.File

/**
 * Compliance-first MEGA handler.
 *
 * MEGA does not offer a public OAuth 2.0 REST authorization flow without extracting user
 * credentials or registering a commercial MEGA SDK app key. Per MyDrive security policy,
 * MEGA is surfaced in Tab 2 with status `Integration Pending` rather than using unofficial
 * endpoints or storing cloud passwords.
 */
class MegaPendingAdapter : CloudStorageAdapter {
    override val providerType = CloudProviderType.MEGA

    private val pendingMessage =
        "MEGA is marked Integration Pending: official MEGA SDK commercial app registration is required. " +
            "MyDrive never uses unofficial endpoints or cloud password extraction."

    override fun authenticate(
        clientId: String,
        codeChallenge: String,
        state: String,
        redirectUri: String
    ): CloudResult<String> = CloudResult.IntegrationPending(pendingMessage)

    override suspend fun refreshToken(
        backendBaseUrl: String,
        jwtToken: String,
        accountId: String
    ): CloudResult<StoredOAuthTokens> = CloudResult.IntegrationPending(pendingMessage)

    override suspend fun getQuota(accessToken: String): CloudResult<QuotaInfo> =
        CloudResult.IntegrationPending(pendingMessage)

    override suspend fun uploadFile(
        accessToken: String,
        localFile: File,
        fileName: String,
        mimeType: String,
        folderPath: String,
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit
    ): CloudResult<RemoteUploadResult> = CloudResult.IntegrationPending(pendingMessage)

    override suspend fun downloadFile(
        accessToken: String,
        remoteFileId: String,
        destinationFile: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): CloudResult<File> = CloudResult.IntegrationPending(pendingMessage)

    override suspend fun listFiles(accessToken: String, pageToken: String?): CloudResult<List<RemoteFileMetadata>> =
        CloudResult.IntegrationPending(pendingMessage)

    override suspend fun renameFile(accessToken: String, remoteFileId: String, newName: String): CloudResult<Unit> =
        CloudResult.IntegrationPending(pendingMessage)

    override suspend fun moveFile(accessToken: String, remoteFileId: String, targetFolderPath: String): CloudResult<Unit> =
        CloudResult.IntegrationPending(pendingMessage)

    override suspend fun deleteFile(accessToken: String, remoteFileId: String): CloudResult<Unit> =
        CloudResult.IntegrationPending(pendingMessage)
}
