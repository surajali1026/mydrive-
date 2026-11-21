package com.example.cloud

import com.example.data.model.CloudProviderType
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

@JsonClass(generateAdapter = true)
data class StoredOAuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtEpochMs: Long = 0L,
    val extraEndpoint: String? = null
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        expiresAtEpochMs > 0L && nowMs >= (expiresAtEpochMs - 60_000L)

    companion object {
        private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        private val adapter = moshi.adapter(StoredOAuthTokens::class.java)

        fun toJson(tokens: StoredOAuthTokens): String = adapter.toJson(tokens)
        fun fromJson(json: String): StoredOAuthTokens? = runCatching { adapter.fromJson(json) }.getOrNull()
    }
}

data class QuotaInfo(
    val totalBytes: Long,
    val usedBytes: Long,
    val isEstimated: Boolean = false,
    val accountEmail: String? = null
) {
    val availableBytes: Long
        get() = if (totalBytes > 0L && usedBytes >= 0L) (totalBytes - usedBytes).coerceAtLeast(0L) else 0L
}

data class RemoteUploadResult(
    val remoteFileId: String,
    val httpStatusCode: Int,
    val remoteName: String,
    val sizeBytes: Long
) {
    val isAtomicallyVerified: Boolean
        get() = (httpStatusCode == 200 || httpStatusCode == 201) && remoteFileId.isNotBlank()
}

data class RemoteFileMetadata(
    val remoteFileId: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedAtEpochMs: Long
)

sealed interface CloudResult<out T> {
    data class Success<T>(val data: T) : CloudResult<T>
    data class TokenExpired(val message: String) : CloudResult<Nothing>
    data class IntegrationPending(val message: String) : CloudResult<Nothing>
    data class Failure(val message: String, val httpCode: Int? = null) : CloudResult<Nothing>
}

/**
 * Unified official Cloud Storage Adapter contract implemented by every supported provider.
 */
interface CloudStorageAdapter {
    val providerType: CloudProviderType

    /**
     * Constructs the official OAuth 2.0 PKCE authorization URL.
     * Client secrets are NEVER embedded in the Android APK; authorization codes are exchanged on the Node.js backend.
     */
    fun authenticate(
        clientId: String,
        codeChallenge: String,
        state: String,
        redirectUri: String
    ): CloudResult<String>

    suspend fun refreshToken(
        backendBaseUrl: String,
        jwtToken: String,
        accountId: String
    ): CloudResult<StoredOAuthTokens>

    suspend fun getQuota(accessToken: String): CloudResult<QuotaInfo>

    suspend fun uploadFile(
        accessToken: String,
        localFile: File,
        fileName: String,
        mimeType: String,
        folderPath: String = "/MyDrive",
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): CloudResult<RemoteUploadResult>

    suspend fun downloadFile(
        accessToken: String,
        remoteFileId: String,
        destinationFile: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): CloudResult<File>

    suspend fun listFiles(
        accessToken: String,
        pageToken: String? = null
    ): CloudResult<List<RemoteFileMetadata>>

    suspend fun renameFile(
        accessToken: String,
        remoteFileId: String,
        newName: String
    ): CloudResult<Unit>

    suspend fun moveFile(
        accessToken: String,
        remoteFileId: String,
        targetFolderPath: String
    ): CloudResult<Unit>

    suspend fun deleteFile(
        accessToken: String,
        remoteFileId: String
    ): CloudResult<Unit>
}
