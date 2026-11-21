package com.example.data.remote

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class AuthResponseDto(
    val token: String,
    val user: UserDto
)

@JsonClass(generateAdapter = true)
data class UserDto(
    val id: String,
    val email: String,
    val displayName: String? = null,
    val authProvider: String = "email",
    val isVerified: Boolean = false
)

@JsonClass(generateAdapter = true)
data class OAuthExchangeRequestDto(
    val code: String,
    val codeVerifier: String,
    val redirectUri: String
)

@JsonClass(generateAdapter = true)
data class OAuthTokenBundleDto(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtEpochMs: Long = 0L,
    val accountEmail: String = "",
    val accountId: String = "",
    val providerType: String = ""
)

@JsonClass(generateAdapter = true)
data class OAuthAuthorizeConfigDto(
    val authorizeUrl: String,
    val clientId: String
)

@JsonClass(generateAdapter = true)
data class CloudFileSyncDto(
    val id: String,
    val accountId: String,
    val providerType: String,
    val remoteFileId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256Hash: String,
    val category: String,
    val folderPath: String,
    val isFavorite: Boolean,
    val isTrashed: Boolean,
    val uploadedAt: Long
)

/**
 * HTTP client for the MyDrive Node.js (TypeScript) OAuth Broker & PostgreSQL Metadata Backend.
 * Keeps all OAuth Client Secrets exclusively on the backend server.
 */
class BackendApiClient(
    private val httpClient: OkHttpClient = defaultClient()
) {
    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun validateHttpsUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        require(trimmed.startsWith("https://") || trimmed.startsWith("http://10.0.2.2")) {
            "Backend URL must use HTTPS (or local emulator loopback http://10.0.2.2)."
        }
        return trimmed
    }

    suspend fun register(
        baseUrl: String,
        email: String,
        password: String,
        displayName: String
    ): Result<AuthResponseDto> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${validateHttpsUrl(baseUrl)}/api/auth/register"
            val payload = moshi.adapter(Map::class.java).toJson(
                mapOf("email" to email, "password" to password, "displayName" to displayName)
            )
            val request = Request.Builder()
                .url(url)
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("Registration failed (${response.code}): $body")
                moshi.adapter(AuthResponseDto::class.java).fromJson(body)
                    ?: error("Invalid registration response from server")
            }
        }
    }

    suspend fun login(
        baseUrl: String,
        email: String,
        password: String
    ): Result<AuthResponseDto> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${validateHttpsUrl(baseUrl)}/api/auth/login"
            val payload = moshi.adapter(Map::class.java).toJson(
                mapOf("email" to email, "password" to password)
            )
            val request = Request.Builder()
                .url(url)
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("Sign-in failed (${response.code}): $body")
                moshi.adapter(AuthResponseDto::class.java).fromJson(body)
                    ?: error("Invalid sign-in response from server")
            }
        }
    }

    suspend fun loginWithGoogleIdToken(
        baseUrl: String,
        idToken: String
    ): Result<AuthResponseDto> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${validateHttpsUrl(baseUrl)}/api/auth/google"
            val payload = moshi.adapter(Map::class.java).toJson(mapOf("idToken" to idToken))
            val request = Request.Builder()
                .url(url)
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("Google sign-in failed (${response.code}): $body")
                moshi.adapter(AuthResponseDto::class.java).fromJson(body)
                    ?: error("Invalid Google sign-in response")
            }
        }
    }

    suspend fun requestPasswordReset(baseUrl: String, email: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "${validateHttpsUrl(baseUrl)}/api/auth/forgot-password"
                val payload = moshi.adapter(Map::class.java).toJson(mapOf("email" to email))
                val request = Request.Builder()
                    .url(url)
                    .post(payload.toRequestBody(jsonMediaType))
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Reset request failed (${response.code})")
                }
            }
        }

    suspend fun getOAuthAuthorizeUrl(
        baseUrl: String,
        jwtToken: String,
        providerId: String,
        codeChallenge: String,
        state: String,
        redirectUri: String
    ): Result<OAuthAuthorizeConfigDto> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = validateHttpsUrl(baseUrl)
            val url = "$cleanBase/api/oauth/$providerId/authorize" +
                "?code_challenge=${java.net.URLEncoder.encode(codeChallenge, "UTF-8")}" +
                "&state=${java.net.URLEncoder.encode(state, "UTF-8")}" +
                "&redirect_uri=${java.net.URLEncoder.encode(redirectUri, "UTF-8")}"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $jwtToken")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("OAuth init failed (${response.code}): $body")
                moshi.adapter(OAuthAuthorizeConfigDto::class.java).fromJson(body)
                    ?: error("Invalid OAuth config from server")
            }
        }
    }

    suspend fun exchangeOAuthCode(
        baseUrl: String,
        jwtToken: String,
        providerId: String,
        code: String,
        codeVerifier: String,
        redirectUri: String
    ): Result<OAuthTokenBundleDto> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${validateHttpsUrl(baseUrl)}/api/oauth/$providerId/exchange"
            val dto = OAuthExchangeRequestDto(code, codeVerifier, redirectUri)
            val payload = moshi.adapter(OAuthExchangeRequestDto::class.java).toJson(dto)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $jwtToken")
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("OAuth exchange failed (${response.code}): $body")
                moshi.adapter(OAuthTokenBundleDto::class.java).fromJson(body)
                    ?: error("Invalid OAuth token response")
            }
        }
    }

    suspend fun refreshOAuthToken(
        baseUrl: String,
        jwtToken: String,
        providerId: String,
        accountId: String
    ): Result<OAuthTokenBundleDto> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${validateHttpsUrl(baseUrl)}/api/oauth/$providerId/refresh"
            val payload = moshi.adapter(Map::class.java).toJson(mapOf("accountId" to accountId))
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $jwtToken")
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("Token refresh failed (${response.code}): $body")
                moshi.adapter(OAuthTokenBundleDto::class.java).fromJson(body)
                    ?: error("Invalid refreshed token response")
            }
        }
    }

    suspend fun syncFileMetadata(
        baseUrl: String,
        jwtToken: String,
        fileDto: CloudFileSyncDto
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${validateHttpsUrl(baseUrl)}/api/files"
            val payload = moshi.adapter(CloudFileSyncDto::class.java).toJson(fileDto)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $jwtToken")
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Metadata sync failed (${response.code})")
            }
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
