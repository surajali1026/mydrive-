package com.example.cloud

import com.example.cloud.GoogleDriveAdapter.Companion.enc
import com.example.cloud.GoogleDriveAdapter.Companion.streamToFile
import com.example.data.model.CloudProviderType
import com.example.data.remote.BackendApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * Official Microsoft OneDrive Adapter via Microsoft Graph API v1.0 (`/me/drive`) and OAuth 2.0 PKCE.
 */
class OneDriveAdapter(
    private val httpClient: OkHttpClient,
    private val backendApi: BackendApiClient
) : CloudStorageAdapter {

    override val providerType = CloudProviderType.ONEDRIVE

    override fun authenticate(
        clientId: String,
        codeChallenge: String,
        state: String,
        redirectUri: String
    ): CloudResult<String> {
        if (clientId.isBlank()) {
            return CloudResult.Failure("Configure OneDrive Azure App Client ID on the MyDrive backend.")
        }
        val url = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize" +
            "?client_id=${enc(clientId)}" +
            "&response_type=code" +
            "&redirect_uri=${enc(redirectUri)}" +
            "&scope=${enc("Files.ReadWrite offline_access User.Read")}" +
            "&code_challenge=${enc(codeChallenge)}" +
            "&code_challenge_method=S256" +
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
            onFailure = { CloudResult.TokenExpired(it.message ?: "OneDrive token expired") }
        )

    override suspend fun getQuota(accessToken: String): CloudResult<QuotaInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://graph.microsoft.com/v1.0/me/drive")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 401) return@withContext CloudResult.TokenExpired("OneDrive session expired.")
                if (!resp.isSuccessful) return@withContext CloudResult.Failure("OneDrive quota failed (${resp.code})", resp.code)
                val json = JSONObject(resp.body?.string().orEmpty())
                val quota = json.optJSONObject("quota")
                val total = quota?.optLong("total", -1L) ?: -1L
                val used = quota?.optLong("used", -1L) ?: -1L
                val email = json.optJSONObject("owner")?.optJSONObject("user")?.optString("email")
                CloudResult.Success(QuotaInfo(totalBytes = total, usedBytes = used, accountEmail = email))
            }
        }.getOrElse { CloudResult.Failure(it.message ?: "OneDrive quota error") }
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
            val cleanFolder = folderPath.trim('/').ifBlank { "MyDrive" }
            val totalBytes = localFile.length()
            val sessionPayload = JSONObject().put(
                "item",
                JSONObject().put("@microsoft.graph.conflictBehavior", "rename").put("name", fileName)
            ).toString()

            val sessionReq = Request.Builder()
                .url("https://graph.microsoft.com/v1.0/me/drive/root:/$cleanFolder/${enc(fileName)}:/createUploadSession")
                .header("Authorization", "Bearer $accessToken")
                .post(sessionPayload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val uploadUrl = httpClient.newCall(sessionReq).execute().use { resp ->
                if (resp.code == 401) return@withContext CloudResult.TokenExpired("OneDrive token expired.")
                if (!resp.isSuccessful) return@withContext CloudResult.Failure("OneDrive upload session failed (${resp.code})", resp.code)
                JSONObject(resp.body?.string().orEmpty()).optString("uploadUrl")
            }
            if (uploadUrl.isBlank()) return@withContext CloudResult.Failure("OneDrive returned empty uploadUrl.")

            // Upload in 4 MiB chunks (multiple of 320 KiB as required by Graph API)
            val chunkSize = 4 * 1024 * 1024
            var offset = 0L
            var finalId = ""
            var finalCode = 0
            RandomAccessFile(localFile, "r").use { raf ->
                while (offset < totalBytes || (totalBytes == 0L && offset == 0L)) {
                    val remaining = (totalBytes - offset).coerceAtLeast(0L)
                    val len = minOf(chunkSize.toLong(), remaining).toInt()
                    val chunk = ByteArray(len)
                    if (len > 0) raf.readFully(chunk)
                    val endByte = (offset + len - 1).coerceAtLeast(0L)
                    val rangeHeader = "bytes $offset-$endByte/$totalBytes"

                    val putReq = Request.Builder()
                        .url(uploadUrl)
                        .header("Content-Range", rangeHeader)
                        .put(chunk.toRequestBody(mimeType.toMediaType()))
                        .build()

                    httpClient.newCall(putReq).execute().use { resp ->
                        finalCode = resp.code
                        val raw = resp.body?.string().orEmpty()
                        if (resp.code == 200 || resp.code == 201) {
                            finalId = JSONObject(raw).optString("id")
                        } else if (resp.code != 202) {
                            return@withContext CloudResult.Failure("OneDrive chunk upload failed (${resp.code})", resp.code)
                        }
                    }
                    offset += len
                    onProgress(offset, totalBytes)
                    if (totalBytes == 0L) break
                }
            }
            if (finalId.isBlank()) return@withContext CloudResult.Failure("OneDrive upload missing file ID.")
            CloudResult.Success(RemoteUploadResult(finalId, finalCode, fileName, totalBytes))
        }.getOrElse { CloudResult.Failure(it.message ?: "OneDrive upload error") }
    }

    override suspend fun downloadFile(
        accessToken: String,
        remoteFileId: String,
        destinationFile: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): CloudResult<File> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://graph.microsoft.com/v1.0/me/drive/items/${enc(remoteFileId)}/content")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 401) return@withContext CloudResult.TokenExpired("OneDrive token expired.")
                if (!resp.isSuccessful) return@withContext CloudResult.Failure("OneDrive download failed (${resp.code})", resp.code)
                val body = resp.body ?: return@withContext CloudResult.Failure("Empty response")
                streamToFile(body.byteStream(), body.contentLength(), destinationFile, onProgress)
                CloudResult.Success(destinationFile)
            }
        }.getOrElse { CloudResult.Failure(it.message ?: "OneDrive download error") }
    }

    override suspend fun listFiles(accessToken: String, pageToken: String?): CloudResult<List<RemoteFileMetadata>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = pageToken ?: "https://graph.microsoft.com/v1.0/me/drive/root:/MyDrive:/children"
                val req = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").get().build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401) return@withContext CloudResult.TokenExpired("OneDrive token expired.")
                    if (resp.code == 404) return@withContext CloudResult.Success(emptyList())
                    if (!resp.isSuccessful) return@withContext CloudResult.Failure("OneDrive list failed (${resp.code})", resp.code)
                    val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("value")
                    val results = mutableListOf<RemoteFileMetadata>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            results.add(
                                RemoteFileMetadata(
                                    remoteFileId = obj.optString("id"),
                                    name = obj.optString("name"),
                                    mimeType = obj.optJSONObject("file")?.optString("mimeType") ?: "application/octet-stream",
                                    sizeBytes = obj.optLong("size", 0L),
                                    modifiedAtEpochMs = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                    CloudResult.Success(results)
                }
            }.getOrElse { CloudResult.Failure(it.message ?: "OneDrive list error") }
        }

    override suspend fun renameFile(accessToken: String, remoteFileId: String, newName: String): CloudResult<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = JSONObject().put("name", newName).toString()
                val req = Request.Builder()
                    .url("https://graph.microsoft.com/v1.0/me/drive/items/${enc(remoteFileId)}")
                    .header("Authorization", "Bearer $accessToken")
                    .patch(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401) return@withContext CloudResult.TokenExpired("OneDrive token expired.")
                    if (!resp.isSuccessful) return@withContext CloudResult.Failure("OneDrive rename failed (${resp.code})", resp.code)
                    CloudResult.Success(Unit)
                }
            }.getOrElse { CloudResult.Failure(it.message ?: "OneDrive rename error") }
        }

    override suspend fun moveFile(accessToken: String, remoteFileId: String, targetFolderPath: String): CloudResult<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val clean = targetFolderPath.trim('/').ifBlank { "MyDrive" }
                val payload = JSONObject().put(
                    "parentReference",
                    JSONObject().put("path", "/drive/root:/$clean")
                ).toString()
                val req = Request.Builder()
                    .url("https://graph.microsoft.com/v1.0/me/drive/items/${enc(remoteFileId)}")
                    .header("Authorization", "Bearer $accessToken")
                    .patch(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401) return@withContext CloudResult.TokenExpired("OneDrive token expired.")
                    if (!resp.isSuccessful) return@withContext CloudResult.Failure("OneDrive move failed (${resp.code})", resp.code)
                    CloudResult.Success(Unit)
                }
            }.getOrElse { CloudResult.Failure(it.message ?: "OneDrive move error") }
        }

    override suspend fun deleteFile(accessToken: String, remoteFileId: String): CloudResult<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("https://graph.microsoft.com/v1.0/me/drive/items/${enc(remoteFileId)}")
                    .header("Authorization", "Bearer $accessToken")
                    .delete()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401) return@withContext CloudResult.TokenExpired("OneDrive token expired.")
                    if (!resp.isSuccessful && resp.code != 404) {
                        return@withContext CloudResult.Failure("OneDrive delete failed (${resp.code})", resp.code)
                    }
                    CloudResult.Success(Unit)
                }
            }.getOrElse { CloudResult.Failure(it.message ?: "OneDrive delete error") }
        }
}
