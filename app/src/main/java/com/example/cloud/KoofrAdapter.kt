package com.example.cloud

import com.example.cloud.GoogleDriveAdapter.Companion.enc
import com.example.cloud.GoogleDriveAdapter.Companion.streamToFile
import com.example.data.model.CloudProviderType
import com.example.data.remote.BackendApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

/**
 * Official Koofr REST API v2 Adapter with OAuth 2.0.
 */
class KoofrAdapter(
    private val httpClient: OkHttpClient,
    private val backendApi: BackendApiClient
) : CloudStorageAdapter {

    override val providerType = CloudProviderType.KOOFR
    private val baseUrl = "https://app.koofr.net"

    override fun authenticate(
        clientId: String,
        codeChallenge: String,
        state: String,
        redirectUri: String
    ): CloudResult<String> {
        if (clientId.isBlank()) {
            return CloudResult.Failure("Configure Koofr OAuth Client ID on the MyDrive backend.")
        }
        val url = "$baseUrl/oauth2/auth" +
            "?client_id=${enc(clientId)}" +
            "&response_type=code" +
            "&redirect_uri=${enc(redirectUri)}" +
            "&scope=public" +
            "&state=${enc(state)}"
        return CloudResult.Success(url)
    }

    override suspend fun refreshToken(
        backendBaseUrl: String,
        jwtToken: String,
        accountId: String
    ): CloudResult<StoredOAuthTokens> =
        backendApi.refreshOAuthToken(backendBaseUrl, jwtToken, providerType.id, accountId).fold(
            onSuccess = { CloudResult.Success(StoredOAuthTokens(it.accessToken, it.refreshToken, it.expiresAtEpochMs)) },
            onFailure = { CloudResult.TokenExpired(it.message ?: "Koofr token expired") }
        )

    private fun resolvePrimaryMountId(accessToken: String): Triple<String, Long, Long>? {
        val req = Request.Builder()
            .url("$baseUrl/api/v2/mounts")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val mounts = JSONObject(resp.body?.string().orEmpty()).optJSONArray("mounts") ?: return null
            for (i in 0 until mounts.length()) {
                val m = mounts.getJSONObject(i)
                if (m.optBoolean("isPrimary", false) || i == 0) {
                    val id = m.optString("id")
                    val total = m.optLong("spaceTotal", -1L) * 1024L * 1024L // Koofr reports MiB
                    val used = m.optLong("spaceUsed", -1L) * 1024L * 1024L
                    return Triple(id, total, used)
                }
            }
        }
        return null
    }

    override suspend fun getQuota(accessToken: String): CloudResult<QuotaInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val mount = resolvePrimaryMountId(accessToken)
                ?: return@withContext CloudResult.Failure("Unable to fetch Koofr primary mount quota.")
            CloudResult.Success(QuotaInfo(totalBytes = mount.second, usedBytes = mount.third))
        }.getOrElse { CloudResult.Failure(it.message ?: "Koofr quota error") }
    }

    override suspend fun uploadFile(
        accessToken: String,
        localFile: File,
        fileName: String,
        mimeType: String,
        folderPath: String,
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit
    ): CloudResult<RemoteUploadResult> = withContext(Dispatchers.IO) {
        runCatching {
            val mount = resolvePrimaryMountId(accessToken)
                ?: return@withContext CloudResult.Failure("Koofr primary mount not found.")
            val mountId = mount.first
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    fileName,
                    ProgressFileRequestBody(localFile, mimeType.toMediaType(), onProgress)
                )
                .build()

            val req = Request.Builder()
                .url("$baseUrl/content/api/v2/mounts/${enc(mountId)}/files/put?path=/&filename=${enc(fileName)}&autorename=true")
                .header("Authorization", "Bearer $accessToken")
                .post(multipart)
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 401) return@withContext CloudResult.TokenExpired("Koofr token expired.")
                if (resp.code != 200 && resp.code != 201) {
                    return@withContext CloudResult.Failure("Koofr upload failed (${resp.code})", resp.code)
                }
                val remoteId = "$mountId:/$fileName"
                CloudResult.Success(RemoteUploadResult(remoteId, resp.code, fileName, localFile.length()))
            }
        }.getOrElse { CloudResult.Failure(it.message ?: "Koofr upload error") }
    }

    override suspend fun downloadFile(
        accessToken: String,
        remoteFileId: String,
        destinationFile: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): CloudResult<File> = withContext(Dispatchers.IO) {
        runCatching {
            val mountId = remoteFileId.substringBefore(":")
            val path = remoteFileId.substringAfter(":", "/").ifBlank { "/" }
            val req = Request.Builder()
                .url("$baseUrl/content/api/v2/mounts/${enc(mountId)}/files/get?path=${enc(path)}")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 401) return@withContext CloudResult.TokenExpired("Koofr token expired.")
                if (!resp.isSuccessful) return@withContext CloudResult.Failure("Koofr download failed (${resp.code})", resp.code)
                val body = resp.body ?: return@withContext CloudResult.Failure("Empty Koofr response")
                streamToFile(body.byteStream(), body.contentLength(), destinationFile, onProgress)
                CloudResult.Success(destinationFile)
            }
        }.getOrElse { CloudResult.Failure(it.message ?: "Koofr download error") }
    }

    override suspend fun listFiles(accessToken: String, pageToken: String?): CloudResult<List<RemoteFileMetadata>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val mount = resolvePrimaryMountId(accessToken)
                    ?: return@withContext CloudResult.Success(emptyList())
                val req = Request.Builder()
                    .url("$baseUrl/api/v2/mounts/${enc(mount.first)}/files/list?path=/")
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401) return@withContext CloudResult.TokenExpired("Koofr token expired.")
                    val files = JSONObject(resp.body?.string().orEmpty()).optJSONArray("files")
                    val list = mutableListOf<RemoteFileMetadata>()
                    if (files != null) {
                        for (i in 0 until files.length()) {
                            val obj = files.getJSONObject(i)
                            if (obj.optString("type") == "file") {
                                val name = obj.optString("name")
                                list.add(
                                    RemoteFileMetadata(
                                        remoteFileId = "${mount.first}:/$name",
                                        name = name,
                                        mimeType = obj.optString("contentType", "application/octet-stream"),
                                        sizeBytes = obj.optLong("size", 0L),
                                        modifiedAtEpochMs = obj.optLong("modified", System.currentTimeMillis())
                                    )
                                )
                            }
                        }
                    }
                    CloudResult.Success(list)
                }
            }.getOrElse { CloudResult.Failure(it.message ?: "Koofr list error") }
        }

    override suspend fun renameFile(accessToken: String, remoteFileId: String, newName: String): CloudResult<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val mountId = remoteFileId.substringBefore(":")
                val path = remoteFileId.substringAfter(":", "/")
                val payload = JSONObject().put("name", newName).toString()
                val req = Request.Builder()
                    .url("$baseUrl/api/v2/mounts/${enc(mountId)}/files/rename?path=${enc(path)}")
                    .header("Authorization", "Bearer $accessToken")
                    .put(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401) return@withContext CloudResult.TokenExpired("Koofr token expired.")
                    if (!resp.isSuccessful) return@withContext CloudResult.Failure("Koofr rename failed (${resp.code})", resp.code)
                    CloudResult.Success(Unit)
                }
            }.getOrElse { CloudResult.Failure(it.message ?: "Koofr rename error") }
        }

    override suspend fun moveFile(accessToken: String, remoteFileId: String, targetFolderPath: String): CloudResult<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val mountId = remoteFileId.substringBefore(":")
                val path = remoteFileId.substringAfter(":", "/")
                val fileName = path.substringAfterLast('/')
                val destPath = "/${targetFolderPath.trim('/')}/$fileName"
                val payload = JSONObject()
                    .put("toMountId", mountId)
                    .put("toPath", destPath)
                    .toString()
                val req = Request.Builder()
                    .url("$baseUrl/api/v2/mounts/${enc(mountId)}/files/move?path=${enc(path)}")
                    .header("Authorization", "Bearer $accessToken")
                    .put(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401) return@withContext CloudResult.TokenExpired("Koofr token expired.")
                    if (!resp.isSuccessful) return@withContext CloudResult.Failure("Koofr move failed (${resp.code})", resp.code)
                    CloudResult.Success(Unit)
                }
            }.getOrElse { CloudResult.Failure(it.message ?: "Koofr move error") }
        }

    override suspend fun deleteFile(accessToken: String, remoteFileId: String): CloudResult<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val mountId = remoteFileId.substringBefore(":")
                val path = remoteFileId.substringAfter(":", "/")
                val req = Request.Builder()
                    .url("$baseUrl/api/v2/mounts/${enc(mountId)}/files/remove?path=${enc(path)}")
                    .header("Authorization", "Bearer $accessToken")
                    .delete()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401) return@withContext CloudResult.TokenExpired("Koofr token expired.")
                    if (!resp.isSuccessful) return@withContext CloudResult.Failure("Koofr delete failed (${resp.code})", resp.code)
                    CloudResult.Success(Unit)
                }
            }.getOrElse { CloudResult.Failure(it.message ?: "Koofr delete error") }
        }
}
