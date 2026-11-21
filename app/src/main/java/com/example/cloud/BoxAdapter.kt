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
 * Official Box Content API v2.0 Adapter with OAuth 2.0.
 */
class BoxAdapter(
    private val httpClient: OkHttpClient,
    private val backendApi: BackendApiClient
) : CloudStorageAdapter {

    override val providerType = CloudProviderType.BOX

    override fun authenticate(
        clientId: String,
        codeChallenge: String,
        state: String,
        redirectUri: String
    ): CloudResult<String> {
        if (clientId.isBlank()) {
            return CloudResult.Failure("Configure Box OAuth Client ID on the MyDrive backend.")
        }
        val url = "https://account.box.com/api/oauth2/authorize" +
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
            onFailure = { CloudResult.TokenExpired(it.message ?: "Box token expired") }
        )

    override suspend fun getQuota(accessToken: String): CloudResult<QuotaInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://api.box.com/2.0/users/me?fields=space_amount,space_used,login")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 401) return@withContext CloudResult.TokenExpired("Box token expired.")
                if (!resp.isSuccessful) return@withContext CloudResult.Failure("Box quota failed (${resp.code})", resp.code)
                val json = JSONObject(resp.body?.string().orEmpty())
                CloudResult.Success(
                    QuotaInfo(
                        totalBytes = json.optLong("space_amount", -1L),
                        usedBytes = json.optLong("space_used", -1L),
                        accountEmail = json.optString("login")
                    )
                )
            }
        }.getOrElse { CloudResult.Failure(it.message ?: "Box quota error") }
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
            val attributes = JSONObject()
                .put("name", fileName)
                .put("parent", JSONObject().put("id", "0"))
                .toString()

            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("attributes", attributes)
                .addFormDataPart(
                    "file",
                    fileName,
                    ProgressFileRequestBody(localFile, mimeType.toMediaType(), onProgress)
                )
                .build()

            val req = Request.Builder()
                .url("https://upload.box.com/api/2.0/files/content")
                .header("Authorization", "Bearer $accessToken")
                .post(multipart)
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (resp.code == 401) return@withContext CloudResult.TokenExpired("Box token expired.")
                if (resp.code != 200 && resp.code != 201) {
                    return@withContext CloudResult.Failure("Box upload failed (${resp.code})", resp.code)
                }
                val entries = JSONObject(raw).optJSONArray("entries")
                val entry = entries?.optJSONObject(0)
                val remoteId = entry?.optString("id").orEmpty()
                if (remoteId.isBlank()) return@withContext CloudResult.Failure("Box returned empty file ID.")
                CloudResult.Success(RemoteUploadResult(remoteId, resp.code, fileName, localFile.length()))
            }
        }.getOrElse { CloudResult.Failure(it.message ?: "Box upload error") }
    }

    override suspend fun downloadFile(
        accessToken: String,
        remoteFileId: String,
        destinationFile: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): CloudResult<File> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://api.box.com/2.0/files/${enc(remoteFileId)}/content")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 401) return@withContext CloudResult.TokenExpired("Box token expired.")
                if (!resp.isSuccessful) return@withContext CloudResult.Failure("Box download failed (${resp.code})", resp.code)
                val body = resp.body ?: return@withContext CloudResult.Failure("Empty Box response")
                streamToFile(body.byteStream(), body.contentLength(), destinationFile, onProgress)
                CloudResult.Success(destinationFile)
            }
        }.getOrElse { CloudResult.Failure(it.message ?: "Box download error") }
    }

    override suspend fun listFiles(accessToken: String, pageToken: String?): CloudResult<List<RemoteFileMetadata>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("https://api.box.com/2.0/folders/0/items?fields=id,name,size,modified_at&limit=50")
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401) return@withContext CloudResult.TokenExpired("Box token expired.")
                    if (!resp.isSuccessful) return@withContext CloudResult.Failure("Box list failed (${resp.code})", resp.code)
                    val entries = JSONObject(resp.body?.string().orEmpty()).optJSONArray("entries")
                    val list = mutableListOf<RemoteFileMetadata>()
                    if (entries != null) {
                        for (i in 0 until entries.length()) {
                            val obj = entries.getJSONObject(i)
                            if (obj.optString("type") == "file") {
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
            }.getOrElse { CloudResult.Failure(it.message ?: "Box list error") }
        }

    override suspend fun renameFile(accessToken: String, remoteFileId: String, newName: String): CloudResult<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = JSONObject().put("name", newName).toString()
                val req = Request.Builder()
                    .url("https://api.box.com/2.0/files/${enc(remoteFileId)}")
                    .header("Authorization", "Bearer $accessToken")
                    .put(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401) return@withContext CloudResult.TokenExpired("Box token expired.")
                    if (!resp.isSuccessful) return@withContext CloudResult.Failure("Box rename failed (${resp.code})", resp.code)
                    CloudResult.Success(Unit)
                }
            }.getOrElse { CloudResult.Failure(it.message ?: "Box rename error") }
        }

    override suspend fun moveFile(accessToken: String, remoteFileId: String, targetFolderPath: String): CloudResult<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = JSONObject().put("description", "folder:$targetFolderPath").toString()
                val req = Request.Builder()
                    .url("https://api.box.com/2.0/files/${enc(remoteFileId)}")
                    .header("Authorization", "Bearer $accessToken")
                    .put(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401) return@withContext CloudResult.TokenExpired("Box token expired.")
                    if (!resp.isSuccessful) return@withContext CloudResult.Failure("Box move failed (${resp.code})", resp.code)
                    CloudResult.Success(Unit)
                }
            }.getOrElse { CloudResult.Failure(it.message ?: "Box move error") }
        }

    override suspend fun deleteFile(accessToken: String, remoteFileId: String): CloudResult<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("https://api.box.com/2.0/files/${enc(remoteFileId)}")
                    .header("Authorization", "Bearer $accessToken")
                    .delete()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401) return@withContext CloudResult.TokenExpired("Box token expired.")
                    if (!resp.isSuccessful && resp.code != 404) {
                        return@withContext CloudResult.Failure("Box delete failed (${resp.code})", resp.code)
                    }
                    CloudResult.Success(Unit)
                }
            }.getOrElse { CloudResult.Failure(it.message ?: "Box delete error") }
        }
}
