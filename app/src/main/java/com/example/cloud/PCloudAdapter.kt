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
import org.json.JSONObject
import java.io.File

/**
 * Official pCloud REST API Adapter with OAuth 2.0.
 */
class PCloudAdapter(
    private val httpClient: OkHttpClient,
    private val backendApi: BackendApiClient
) : CloudStorageAdapter {

    override val providerType = CloudProviderType.PCLOUD
    private val apiHost = "https://api.pcloud.com"

    override fun authenticate(
        clientId: String,
        codeChallenge: String,
        state: String,
        redirectUri: String
    ): CloudResult<String> {
        if (clientId.isBlank()) {
            return CloudResult.Failure("Configure pCloud OAuth Client ID on the MyDrive backend.")
        }
        val url = "https://my.pcloud.com/oauth2/authorize" +
            "?client_id=${enc(clientId)}" +
            "&response_type=code" +
            "&redirect_uri=${enc(redirectUri)}" +
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
            onFailure = { CloudResult.TokenExpired(it.message ?: "pCloud token expired") }
        )

    override suspend fun getQuota(accessToken: String): CloudResult<QuotaInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$apiHost/userinfo")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 401) return@withContext CloudResult.TokenExpired("pCloud session expired.")
                if (!resp.isSuccessful) return@withContext CloudResult.Failure("pCloud quota failed (${resp.code})", resp.code)
                val json = JSONObject(resp.body?.string().orEmpty())
                if (json.optInt("result", 0) != 0) {
                    return@withContext CloudResult.Failure(json.optString("error", "pCloud API error"))
                }
                CloudResult.Success(
                    QuotaInfo(
                        totalBytes = json.optLong("quota", -1L),
                        usedBytes = json.optLong("usedquota", -1L),
                        accountEmail = json.optString("email")
                    )
                )
            }
        }.getOrElse { CloudResult.Failure(it.message ?: "pCloud quota error") }
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
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    fileName,
                    ProgressFileRequestBody(localFile, mimeType.toMediaType(), onProgress)
                )
                .build()

            val req = Request.Builder()
                .url("$apiHost/uploadfile?folderid=0&filename=${enc(fileName)}&renameifexists=1")
                .header("Authorization", "Bearer $accessToken")
                .post(multipart)
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (resp.code == 401) return@withContext CloudResult.TokenExpired("pCloud token expired.")
                if (resp.code != 200 && resp.code != 201) {
                    return@withContext CloudResult.Failure("pCloud upload failed (${resp.code})", resp.code)
                }
                val json = JSONObject(raw)
                if (json.optInt("result", 0) != 0) {
                    return@withContext CloudResult.Failure(json.optString("error", "pCloud upload rejected"))
                }
                val metadata = json.optJSONArray("metadata")?.optJSONObject(0)
                val fileId = metadata?.optLong("fileid", 0L)?.takeIf { it > 0L }?.toString().orEmpty()
                if (fileId.isBlank()) return@withContext CloudResult.Failure("pCloud returned empty fileid.")
                CloudResult.Success(RemoteUploadResult(fileId, resp.code, fileName, localFile.length()))
            }
        }.getOrElse { CloudResult.Failure(it.message ?: "pCloud upload error") }
    }

    override suspend fun downloadFile(
        accessToken: String,
        remoteFileId: String,
        destinationFile: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): CloudResult<File> = withContext(Dispatchers.IO) {
        runCatching {
            val linkReq = Request.Builder()
                .url("$apiHost/getfilelink?fileid=${enc(remoteFileId)}")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            val downloadUrl = httpClient.newCall(linkReq).execute().use { resp ->
                if (resp.code == 401) return@withContext CloudResult.TokenExpired("pCloud token expired.")
                val json = JSONObject(resp.body?.string().orEmpty())
                val host = json.optJSONArray("hosts")?.optString(0).orEmpty()
                val path = json.optString("path")
                if (host.isBlank() || path.isBlank()) return@withContext CloudResult.Failure("pCloud download link unavailable.")
                "https://$host$path"
            }
            val fileReq = Request.Builder().url(downloadUrl).get().build()
            httpClient.newCall(fileReq).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext CloudResult.Failure("pCloud download failed (${resp.code})", resp.code)
                val body = resp.body ?: return@withContext CloudResult.Failure("Empty pCloud file stream")
                streamToFile(body.byteStream(), body.contentLength(), destinationFile, onProgress)
                CloudResult.Success(destinationFile)
            }
        }.getOrElse { CloudResult.Failure(it.message ?: "pCloud download error") }
    }

    override suspend fun listFiles(accessToken: String, pageToken: String?): CloudResult<List<RemoteFileMetadata>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$apiHost/listfolder?folderid=0")
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401) return@withContext CloudResult.TokenExpired("pCloud token expired.")
                    val contents = JSONObject(resp.body?.string().orEmpty())
                        .optJSONObject("metadata")
                        ?.optJSONArray("contents")
                    val list = mutableListOf<RemoteFileMetadata>()
                    if (contents != null) {
                        for (i in 0 until contents.length()) {
                            val item = contents.getJSONObject(i)
                            if (!item.optBoolean("isfolder", true)) {
                                list.add(
                                    RemoteFileMetadata(
                                        remoteFileId = item.optLong("fileid").toString(),
                                        name = item.optString("name"),
                                        mimeType = item.optString("contenttype", "application/octet-stream"),
                                        sizeBytes = item.optLong("size", 0L),
                                        modifiedAtEpochMs = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    }
                    CloudResult.Success(list)
                }
            }.getOrElse { CloudResult.Failure(it.message ?: "pCloud list error") }
        }

    override suspend fun renameFile(accessToken: String, remoteFileId: String, newName: String): CloudResult<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$apiHost/renamefile?fileid=${enc(remoteFileId)}&toname=${enc(newName)}")
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401) return@withContext CloudResult.TokenExpired("pCloud token expired.")
                    if (!resp.isSuccessful) return@withContext CloudResult.Failure("pCloud rename failed (${resp.code})", resp.code)
                    CloudResult.Success(Unit)
                }
            }.getOrElse { CloudResult.Failure(it.message ?: "pCloud rename error") }
        }

    override suspend fun moveFile(accessToken: String, remoteFileId: String, targetFolderPath: String): CloudResult<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$apiHost/renamefile?fileid=${enc(remoteFileId)}&topath=${enc(targetFolderPath)}")
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401) return@withContext CloudResult.TokenExpired("pCloud token expired.")
                    if (!resp.isSuccessful) return@withContext CloudResult.Failure("pCloud move failed (${resp.code})", resp.code)
                    CloudResult.Success(Unit)
                }
            }.getOrElse { CloudResult.Failure(it.message ?: "pCloud move error") }
        }

    override suspend fun deleteFile(accessToken: String, remoteFileId: String): CloudResult<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$apiHost/deletefile?fileid=${enc(remoteFileId)}")
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401) return@withContext CloudResult.TokenExpired("pCloud token expired.")
                    if (!resp.isSuccessful) return@withContext CloudResult.Failure("pCloud delete failed (${resp.code})", resp.code)
                    CloudResult.Success(Unit)
                }
            }.getOrElse { CloudResult.Failure(it.message ?: "pCloud delete error") }
        }
}
