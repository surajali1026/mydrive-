package com.example.cloud

import com.example.data.model.CloudProviderType
import com.example.data.remote.BackendApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLEncoder

/**
 * Official Google Drive REST API v3 Adapter.
 * Uses Google Identity OAuth 2.0 (`drive.file` scope) with resumable uploads and live quota reads.
 */
class GoogleDriveAdapter(
    private val httpClient: OkHttpClient,
    private val backendApi: BackendApiClient
) : CloudStorageAdapter {

    override val providerType = CloudProviderType.GOOGLE_DRIVE

    override fun authenticate(
        clientId: String,
        codeChallenge: String,
        state: String,
        redirectUri: String
    ): CloudResult<String> {
        if (clientId.isBlank()) {
            return CloudResult.Failure("Configure Google OAuth Client ID on the MyDrive Node.js backend.")
        }
        val url = "https://accounts.google.com/o/oauth2/v2/auth" +
            "?client_id=${enc(clientId)}" +
            "&redirect_uri=${enc(redirectUri)}" +
            "&response_type=code" +
            "&scope=${enc("https://www.googleapis.com/auth/drive.file openid email profile")}" +
            "&access_type=offline" +
            "&prompt=${enc("select_account consent")}" +
            "&code_challenge=${enc(codeChallenge)}" +
            "&code_challenge_method=S256" +
            "&state=${enc(state)}"
        return CloudResult.Success(url)
    }

    override suspend fun refreshToken(
        backendBaseUrl: String,
        jwtToken: String,
        accountId: String
    ): CloudResult<StoredOAuthTokens> {
        return backendApi.refreshOAuthToken(backendBaseUrl, jwtToken, providerType.id, accountId)
            .fold(
                onSuccess = {
                    CloudResult.Success(
                        StoredOAuthTokens(
                            accessToken = it.accessToken,
                            refreshToken = it.refreshToken,
                            expiresAtEpochMs = it.expiresAtEpochMs
                        )
                    )
                },
                onFailure = { CloudResult.TokenExpired(it.message ?: "Google Drive session expired") }
            )
    }

    override suspend fun getQuota(accessToken: String): CloudResult<QuotaInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/about?fields=storageQuota,user(emailAddress)")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 401) return@withContext CloudResult.TokenExpired("Google Drive token expired.")
                if (!resp.isSuccessful) return@withContext CloudResult.Failure("Drive quota failed (${resp.code})", resp.code)
                val json = JSONObject(resp.body?.string().orEmpty())
                val quota = json.optJSONObject("storageQuota")
                val limit = quota?.optString("limit", "-1")?.toLongOrNull() ?: -1L
                val usage = quota?.optString("usage", "-1")?.toLongOrNull() ?: -1L
                val email = json.optJSONObject("user")?.optString("emailAddress")
                CloudResult.Success(QuotaInfo(totalBytes = limit, usedBytes = usage, accountEmail = email))
            }
        }.getOrElse { CloudResult.Failure(it.message ?: "Google Drive quota error") }
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
            // Step 1: Initiate official resumable upload session
            val metaJson = JSONObject().apply {
                put("name", fileName)
                put("mimeType", mimeType)
            }.toString()

            val initReq = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,name,size")
                .header("Authorization", "Bearer $accessToken")
                .header("X-Upload-Content-Type", mimeType)
                .header("X-Upload-Content-Length", totalBytes.toString())
                .post(metaJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val sessionUri = httpClient.newCall(initReq).execute().use { initResp ->
                if (initResp.code == 401) return@withContext CloudResult.TokenExpired("Google Drive token expired.")
                if (!initResp.isSuccessful) {
                    return@withContext CloudResult.Failure("Failed to start Google Drive resumable session (${initResp.code})", initResp.code)
                }
                initResp.header("Location")
                    ?: return@withContext CloudResult.Failure("Missing resumable upload Location header.")
            }

            // Step 2: Stream file bytes to resumable session URL
            val body = ProgressFileRequestBody(localFile, mimeType.toMediaType(), onProgress)
            val uploadReq = Request.Builder()
                .url(sessionUri)
                .put(body)
                .build()

            httpClient.newCall(uploadReq).execute().use { uploadResp ->
                val raw = uploadResp.body?.string().orEmpty()
                if (uploadResp.code == 401) return@withContext CloudResult.TokenExpired("Google Drive token expired.")
                if (uploadResp.code != 200 && uploadResp.code != 201) {
                    return@withContext CloudResult.Failure("Google Drive upload failed (${uploadResp.code})", uploadResp.code)
                }
                val json = JSONObject(raw)
                val remoteId = json.optString("id")
                if (remoteId.isBlank()) {
                    return@withContext CloudResult.Failure("Google Drive returned empty remote file ID.")
                }
                CloudResult.Success(
                    RemoteUploadResult(
                        remoteFileId = remoteId,
                        httpStatusCode = uploadResp.code,
                        remoteName = json.optString("name", fileName),
                        sizeBytes = totalBytes
                    )
                )
            }
        }.getOrElse { CloudResult.Failure(it.message ?: "Google Drive upload error") }
    }

    override suspend fun downloadFile(
        accessToken: String,
        remoteFileId: String,
        destinationFile: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): CloudResult<File> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/${enc(remoteFileId)}?alt=media")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 401) return@withContext CloudResult.TokenExpired("Google Drive token expired.")
                if (!resp.isSuccessful) return@withContext CloudResult.Failure("Download failed (${resp.code})", resp.code)
                val body = resp.body ?: return@withContext CloudResult.Failure("Empty download response")
                streamToFile(body.byteStream(), body.contentLength(), destinationFile, onProgress)
                CloudResult.Success(destinationFile)
            }
        }.getOrElse { CloudResult.Failure(it.message ?: "Google Drive download error") }
    }

    override suspend fun listFiles(accessToken: String, pageToken: String?): CloudResult<List<RemoteFileMetadata>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val pageQuery = pageToken?.let { "&pageToken=${enc(it)}" }.orEmpty()
                val req = Request.Builder()
                    .url("https://www.googleapis.com/drive/v3/files?fields=files(id,name,mimeType,size,modifiedTime)&pageSize=50$pageQuery")
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401) return@withContext CloudResult.TokenExpired("Google Drive token expired.")
                    if (!resp.isSuccessful) return@withContext CloudResult.Failure("List files failed (${resp.code})", resp.code)
                    val filesArr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("files")
                    val list = mutableListOf<RemoteFileMetadata>()
                    if (filesArr != null) {
                        for (i in 0 until filesArr.length()) {
                            val item = filesArr.getJSONObject(i)
                            list.add(
                                RemoteFileMetadata(
                                    remoteFileId = item.optString("id"),
                                    name = item.optString("name"),
                                    mimeType = item.optString("mimeType", "application/octet-stream"),
                                    sizeBytes = item.optString("size", "0").toLongOrNull() ?: 0L,
                                    modifiedAtEpochMs = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                    CloudResult.Success(list)
                }
            }.getOrElse { CloudResult.Failure(it.message ?: "Google Drive list error") }
        }

    override suspend fun renameFile(accessToken: String, remoteFileId: String, newName: String): CloudResult<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = JSONObject().put("name", newName).toString()
                val req = Request.Builder()
                    .url("https://www.googleapis.com/drive/v3/files/${enc(remoteFileId)}")
                    .header("Authorization", "Bearer $accessToken")
                    .patch(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401) return@withContext CloudResult.TokenExpired("Google Drive token expired.")
                    if (!resp.isSuccessful) return@withContext CloudResult.Failure("Rename failed (${resp.code})", resp.code)
                    CloudResult.Success(Unit)
                }
            }.getOrElse { CloudResult.Failure(it.message ?: "Rename error") }
        }

    override suspend fun moveFile(accessToken: String, remoteFileId: String, targetFolderPath: String): CloudResult<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = JSONObject().put("description", "folder:$targetFolderPath").toString()
                val req = Request.Builder()
                    .url("https://www.googleapis.com/drive/v3/files/${enc(remoteFileId)}")
                    .header("Authorization", "Bearer $accessToken")
                    .patch(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401) return@withContext CloudResult.TokenExpired("Google Drive token expired.")
                    if (!resp.isSuccessful) return@withContext CloudResult.Failure("Move failed (${resp.code})", resp.code)
                    CloudResult.Success(Unit)
                }
            }.getOrElse { CloudResult.Failure(it.message ?: "Move error") }
        }

    override suspend fun deleteFile(accessToken: String, remoteFileId: String): CloudResult<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("https://www.googleapis.com/drive/v3/files/${enc(remoteFileId)}")
                    .header("Authorization", "Bearer $accessToken")
                    .delete()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401) return@withContext CloudResult.TokenExpired("Google Drive token expired.")
                    if (!resp.isSuccessful && resp.code != 404) {
                        return@withContext CloudResult.Failure("Delete failed (${resp.code})", resp.code)
                    }
                    CloudResult.Success(Unit)
                }
            }.getOrElse { CloudResult.Failure(it.message ?: "Delete error") }
        }

    companion object {
        internal fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

        internal fun streamToFile(
            inputStream: java.io.InputStream,
            totalBytes: Long,
            destinationFile: File,
            onProgress: (Long, Long) -> Unit
        ) {
            destinationFile.parentFile?.mkdirs()
            inputStream.use { input ->
                FileOutputStream(destinationFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, totalBytes)
                    }
                }
            }
        }
    }
}

internal class ProgressFileRequestBody(
    private val file: File,
    private val contentType: okhttp3.MediaType,
    private val onProgress: (Long, Long) -> Unit
) : RequestBody() {
    override fun contentType() = contentType
    override fun contentLength() = file.length()
    override fun writeTo(sink: BufferedSink) {
        val total = file.length()
        val buffer = ByteArray(16 * 1024)
        var uploaded = 0L
        FileInputStream(file).use { input ->
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                sink.write(buffer, 0, read)
                uploaded += read
                onProgress(uploaded, total)
            }
        }
    }
}
