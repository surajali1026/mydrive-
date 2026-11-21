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
 * Official Dropbox API v2 Adapter with PKCE OAuth 2.0 and chunked upload sessions.
 */
class DropboxAdapter(
    private val httpClient: OkHttpClient,
    private val backendApi: BackendApiClient
) : CloudStorageAdapter {

    override val providerType = CloudProviderType.DROPBOX

    override fun authenticate(
        clientId: String,
        codeChallenge: String,
        state: String,
        redirectUri: String
    ): CloudResult<String> {
        if (clientId.isBlank()) {
            return CloudResult.Failure("Configure Dropbox App Key on the MyDrive backend.")
        }
        val url = "https://www.dropbox.com/oauth2/authorize" +
            "?client_id=${enc(clientId)}" +
            "&response_type=code" +
            "&token_access_type=offline" +
            "&redirect_uri=${enc(redirectUri)}" +
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
            onFailure = { CloudResult.TokenExpired(it.message ?: "Dropbox token expired") }
        )

    override suspend fun getQuota(accessToken: String): CloudResult<QuotaInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://api.dropboxapi.com/2/users/get_space_usage")
                .header("Authorization", "Bearer $accessToken")
                .post("null".toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 401) return@withContext CloudResult.TokenExpired("Dropbox token expired.")
                if (!resp.isSuccessful) return@withContext CloudResult.Failure("Dropbox quota failed (${resp.code})", resp.code)
                val json = JSONObject(resp.body?.string().orEmpty())
                val used = json.optLong("used", -1L)
                val allocated = json.optJSONObject("allocation")?.optLong("allocated", -1L) ?: -1L
                CloudResult.Success(QuotaInfo(totalBytes = allocated, usedBytes = used))
            }
        }.getOrElse { CloudResult.Failure(it.message ?: "Dropbox quota error") }
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
            val totalBytes = localFile.length()
            val cleanFolder = folderPath.trim('/').ifBlank { "MyDrive" }
            val destPath = "/$cleanFolder/$fileName"
            val chunkSize = 4 * 1024 * 1024

            // Start chunked upload session
            val startReq = Request.Builder()
                .url("https://content.dropboxapi.com/2/files/upload_session/start")
                .header("Authorization", "Bearer $accessToken")
                .header("Dropbox-API-Arg", JSONObject().put("close", false).toString())
                .post(ByteArray(0).toRequestBody("application/octet-stream".toMediaType()))
                .build()

            val sessionId = httpClient.newCall(startReq).execute().use { resp ->
                if (resp.code == 401) return@withContext CloudResult.TokenExpired("Dropbox token expired.")
                if (!resp.isSuccessful) return@withContext CloudResult.Failure("Dropbox session start failed (${resp.code})", resp.code)
                JSONObject(resp.body?.string().orEmpty()).optString("session_id")
            }

            var offset = 0L
            RandomAccessFile(localFile, "r").use { raf ->
                while (offset < totalBytes) {
                    val remaining = totalBytes - offset
                    val len = minOf(chunkSize.toLong(), remaining).toInt()
                    val chunk = ByteArray(len)
                    raf.readFully(chunk)
                    val isLast = (offset + len) >= totalBytes
                    if (!isLast) {
                        val cursor = JSONObject().put("session_id", sessionId).put("offset", offset)
                        val arg = JSONObject().put("cursor", cursor).put("close", false).toString()
                        val appendReq = Request.Builder()
                            .url("https://content.dropboxapi.com/2/files/upload_session/append_v2")
                            .header("Authorization", "Bearer $accessToken")
                            .header("Dropbox-API-Arg", arg)
                            .post(chunk.toRequestBody("application/octet-stream".toMediaType()))
                            .build()
                        httpClient.newCall(appendReq).execute().use { resp ->
                            if (!resp.isSuccessful) {
                                return@withContext CloudResult.Failure("Dropbox append failed (${resp.code})", resp.code)
                            }
                        }
                        offset += len
                        onProgress(offset, totalBytes)
                    } else {
                        val cursor = JSONObject().put("session_id", sessionId).put("offset", offset)
                        val commit = JSONObject()
                            .put("path", destPath)
                            .put("mode", "add")
                            .put("autorename", true)
                        val arg = JSONObject().put("cursor", cursor).put("commit", commit).toString()
                        val finishReq = Request.Builder()
                            .url("https://content.dropboxapi.com/2/files/upload_session/finish")
                            .header("Authorization", "Bearer $accessToken")
                            .header("Dropbox-API-Arg", arg)
                            .post(chunk.toRequestBody("application/octet-stream".toMediaType()))
                            .build()
                        httpClient.newCall(finishReq).execute().use { resp ->
                            val raw = resp.body?.string().orEmpty()
                            if (resp.code != 200 && resp.code != 201) {
                                return@withContext CloudResult.Failure("Dropbox finish failed (${resp.code})", resp.code)
                            }
                            val json = JSONObject(raw)
                            val remoteId = json.optString("id").ifBlank { json.optString("path_lower") }
                            onProgress(totalBytes, totalBytes)
                            return@withContext CloudResult.Success(
                                RemoteUploadResult(remoteId, resp.code, json.optString("name", fileName), totalBytes)
                            )
                        }
                    }
                }
            }
            CloudResult.Failure("Dropbox upload did not finalize.")
        }.getOrElse { CloudResult.Failure(it.message ?: "Dropbox upload error") }
    }

    override suspend fun downloadFile(
        accessToken: String,
        remoteFileId: String,
        destinationFile: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): CloudResult<File> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://content.dropboxapi.com/2/files/download")
                .header("Authorization", "Bearer $accessToken")
                .header("Dropbox-API-Arg", JSONObject().put("path", remoteFileId).toString())
                .post(ByteArray(0).toRequestBody("application/octet-stream".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 401) return@withContext CloudResult.TokenExpired("Dropbox token expired.")
                if (!resp.isSuccessful) return@withContext CloudResult.Failure("Dropbox download failed (${resp.code})", resp.code)
                val body = resp.body ?: return@withContext CloudResult.Failure("Empty Dropbox download body")
                streamToFile(body.byteStream(), body.contentLength(), destinationFile, onProgress)
                CloudResult.Success(destinationFile)
            }
        }.getOrElse { CloudResult.Failure(it.message ?: "Dropbox download error") }
    }

    override suspend fun listFiles(accessToken: String, pageToken: String?): CloudResult<List<RemoteFileMetadata>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = JSONObject().put("path", "/MyDrive").put("recursive", true).toString()
                val req = Request.Builder()
                    .url("https://api.dropboxapi.com/2/files/list_folder")
                    .header("Authorization", "Bearer $accessToken")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401) return@withContext CloudResult.TokenExpired("Dropbox token expired.")
                    if (resp.code == 409) return@withContext CloudResult.Success(emptyList())
                    if (!resp.isSuccessful) return@withContext CloudResult.Failure("Dropbox list failed (${resp.code})", resp.code)
                    val entries = JSONObject(resp.body?.string().orEmpty()).optJSONArray("entries")
                    val list = mutableListOf<RemoteFileMetadata>()
                    if (entries != null) {
                        for (i in 0 until entries.length()) {
                            val obj = entries.getJSONObject(i)
                            if (obj.optString(".tag") == "file") {
                                list.add(
                                    RemoteFileMetadata(
                                        remoteFileId = obj.optString("id"),
                                        name = obj.optString("name"),
                                        mimeType = "application/octet-stream",
                                        sizeBytes = obj.optLong("size", 0L),
                                        modifiedAtEpochMs = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    }
                    CloudResult.Success(list)
                }
            }.getOrElse { CloudResult.Failure(it.message ?: "Dropbox list error") }
        }

    override suspend fun renameFile(accessToken: String, remoteFileId: String, newName: String): CloudResult<Unit> =
        moveDropboxPath(accessToken, remoteFileId, "/MyDrive/$newName")

    override suspend fun moveFile(accessToken: String, remoteFileId: String, targetFolderPath: String): CloudResult<Unit> {
        val clean = targetFolderPath.trim('/').ifBlank { "MyDrive" }
        return moveDropboxPath(accessToken, remoteFileId, "/$clean/${remoteFileId.substringAfterLast('/')}")
    }

    private suspend fun moveDropboxPath(accessToken: String, fromPath: String, toPath: String): CloudResult<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = JSONObject()
                    .put("from_path", fromPath)
                    .put("to_path", toPath)
                    .put("autorename", true)
                    .toString()
                val req = Request.Builder()
                    .url("https://api.dropboxapi.com/2/files/move_v2")
                    .header("Authorization", "Bearer $accessToken")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401) return@withContext CloudResult.TokenExpired("Dropbox token expired.")
                    if (!resp.isSuccessful) return@withContext CloudResult.Failure("Dropbox move/rename failed (${resp.code})", resp.code)
                    CloudResult.Success(Unit)
                }
            }.getOrElse { CloudResult.Failure(it.message ?: "Dropbox move error") }
        }

    override suspend fun deleteFile(accessToken: String, remoteFileId: String): CloudResult<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = JSONObject().put("path", remoteFileId).toString()
                val req = Request.Builder()
                    .url("https://api.dropboxapi.com/2/files/delete_v2")
                    .header("Authorization", "Bearer $accessToken")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401) return@withContext CloudResult.TokenExpired("Dropbox token expired.")
                    if (!resp.isSuccessful && resp.code != 409) {
                        return@withContext CloudResult.Failure("Dropbox delete failed (${resp.code})", resp.code)
                    }
                    CloudResult.Success(Unit)
                }
            }.getOrElse { CloudResult.Failure(it.message ?: "Dropbox delete error") }
        }
}
