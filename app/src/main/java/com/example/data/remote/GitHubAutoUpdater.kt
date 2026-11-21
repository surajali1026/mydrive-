package com.example.data.remote

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.security.TokenVault
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class GitHubReleaseAssetDto(
    val name: String,
    @Json(name = "browser_download_url")
    val browserDownloadUrl: String,
    val size: Long = 0L,
    @Json(name = "content_type")
    val contentType: String? = null
)

@JsonClass(generateAdapter = true)
data class GitHubReleaseDto(
    @Json(name = "tag_name")
    val tagName: String,
    val name: String? = null,
    val body: String? = null,
    @Json(name = "html_url")
    val htmlUrl: String? = null,
    @Json(name = "published_at")
    val publishedAt: String? = null,
    val assets: List<GitHubReleaseAssetDto> = emptyList()
)

data class AppUpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
    val apkSizeBytes: Long,
    val sha256AssetUrl: String?,
    val htmlReleaseUrl: String?,
    val isUpdateAvailable: Boolean
)

sealed interface UpdateCheckResult {
    data class Available(val info: AppUpdateInfo) : UpdateCheckResult
    data class UpToDate(val currentVersion: String, val latestTag: String) : UpdateCheckResult
    data class MissingRepoConfig(val message: String) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

/**
 * GitHub Releases In-Place Auto-Updater for the `mydrive` repository.
 *
 * When updates are built with the same persistent release keystore and incremented versionCode
 * (see `.github/workflows/android-build.yml`), Android performs a seamless in-place package upgrade
 * without requiring the user to uninstall MyDrive or lose local Room/Keystore data.
 */
class GitHubAutoUpdater(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
) {
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    suspend fun checkLatestRelease(
        owner: String,
        repo: String = BuildConfig.DEFAULT_GITHUB_REPO_NAME
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        val cleanOwner = owner.trim()
        val cleanRepo = repo.trim().ifBlank { BuildConfig.DEFAULT_GITHUB_REPO_NAME }
        if (cleanOwner.isBlank()) {
            return@withContext UpdateCheckResult.MissingRepoConfig(
                "Set your GitHub username for the \"$cleanRepo\" repository to enable automatic updates."
            )
        }
        if (!OWNER_REPO_REGEX.matches(cleanOwner) || !OWNER_REPO_REGEX.matches(cleanRepo)) {
            return@withContext UpdateCheckResult.Error("Invalid GitHub repository owner or name format.")
        }

        val apiUrl = "https://api.github.com/repos/$cleanOwner/$cleanRepo/releases/latest"
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "MyDrive-Android/${BuildConfig.VERSION_NAME}")
            .get()
            .build()

        runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    return@withContext UpdateCheckResult.Error(
                        "No published GitHub Release found at github.com/$cleanOwner/$cleanRepo yet."
                    )
                }
                if (!response.isSuccessful) {
                    return@withContext UpdateCheckResult.Error(
                        "GitHub update check failed (HTTP ${response.code})."
                    )
                }
                val json = response.body?.string().orEmpty()
                val release = moshi.adapter(GitHubReleaseDto::class.java).fromJson(json)
                    ?: return@withContext UpdateCheckResult.Error("Could not parse GitHub release metadata.")

                val apkAsset = release.assets.firstOrNull {
                    it.name.endsWith(".apk", ignoreCase = true)
                } ?: return@withContext UpdateCheckResult.Error(
                    "Release ${release.tagName} does not include an attached APK asset."
                )

                val shaAsset = release.assets.firstOrNull {
                    it.name.endsWith(".sha256", ignoreCase = true)
                }

                val latestClean = normalizeVersion(release.tagName)
                val currentClean = normalizeVersion(BuildConfig.VERSION_NAME)
                val hasNewer = isNewerVersion(latestClean, currentClean)

                if (hasNewer) {
                    UpdateCheckResult.Available(
                        AppUpdateInfo(
                            currentVersion = BuildConfig.VERSION_NAME,
                            latestVersion = latestClean,
                            releaseTitle = release.name?.ifBlank { release.tagName } ?: release.tagName,
                            releaseNotes = release.body?.ifBlank { "Bug fixes and improvements." }
                                ?: "Bug fixes and improvements.",
                            apkDownloadUrl = apkAsset.browserDownloadUrl,
                            apkFileName = apkAsset.name,
                            apkSizeBytes = apkAsset.size,
                            sha256AssetUrl = shaAsset?.browserDownloadUrl,
                            htmlReleaseUrl = release.htmlUrl,
                            isUpdateAvailable = true
                        )
                    )
                } else {
                    UpdateCheckResult.UpToDate(
                        currentVersion = BuildConfig.VERSION_NAME,
                        latestTag = release.tagName
                    )
                }
            }
        }.getOrElse { error ->
            UpdateCheckResult.Error(error.message ?: "Unable to reach GitHub Releases.")
        }
    }

    /**
     * Downloads the release APK over HTTPS into cache/updates/ with progress callbacks,
     * and validates SHA-256 integrity whenever a `.sha256` checksum asset is published.
     */
    suspend fun downloadUpdateApk(
        info: AppUpdateInfo,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(info.apkDownloadUrl)
            require(uri.scheme == "https" && isAllowedGitHubHost(uri.host.orEmpty())) {
                "Blocked untrusted APK download host: ${uri.host}"
            }

            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            // Clean previous cached APKs to keep storage tidy
            updatesDir.listFiles()?.forEach { it.delete() }
            val safeName = "mydrive-${info.latestVersion.replace(Regex("[^a-zA-Z0-9._-]"), "_")}.apk"
            val outputFile = File(updatesDir, safeName)

            val request = Request.Builder()
                .url(info.apkDownloadUrl)
                .header("User-Agent", "MyDrive-Android/${BuildConfig.VERSION_NAME}")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("APK download failed (HTTP ${response.code})")
                val body = response.body ?: error("Empty APK response body")
                val totalBytes = body.contentLength().takeIf { it > 0 } ?: info.apkSizeBytes
                body.byteStream().use { input ->
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var bytesRead = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            bytesRead += read
                            if (totalBytes > 0L) {
                                onProgress((bytesRead.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }

            // Optional SHA-256 verification when `.sha256` asset is attached in GitHub Release
            if (!info.sha256AssetUrl.isNullOrBlank()) {
                val shaUri = Uri.parse(info.sha256AssetUrl)
                if (shaUri.scheme == "https" && isAllowedGitHubHost(shaUri.host.orEmpty())) {
                    val shaReq = Request.Builder().url(info.sha256AssetUrl).get().build()
                    httpClient.newCall(shaReq).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val expectedHash = resp.body?.string()
                                ?.trim()
                                ?.split(Regex("\\s+"))
                                ?.firstOrNull()
                                ?.lowercase()
                            if (!expectedHash.isNullOrBlank() && expectedHash.length == 64) {
                                val actualHash = FileInputStream(outputFile).use {
                                    TokenVault.computeSha256(it).lowercase()
                                }
                                require(actualHash == expectedHash) {
                                    "SHA-256 verification failed! Update aborted for your safety."
                                }
                            }
                        }
                    }
                }
            }

            onProgress(1f)
            outputFile
        }
    }

    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun createUnknownSourcesSettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Triggers Android's native PackageInstaller for an in-place upgrade over the existing app
     * without uninstalling or clearing local Room database / encrypted tokens.
     */
    fun createInstallApkIntent(apkFile: File): Intent {
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    companion object {
        private val OWNER_REPO_REGEX = Regex("^[A-Za-z0-9_.-]{1,100}$")

        private fun isAllowedGitHubHost(host: String): Boolean {
            val lower = host.lowercase()
            return lower == "github.com" ||
                lower == "objects.githubusercontent.com" ||
                lower == "release-assets.githubusercontent.com" ||
                lower.endsWith(".githubusercontent.com")
        }

        fun normalizeVersion(raw: String): String =
            raw.trim().removePrefix("v").removePrefix("V")

        fun isNewerVersion(candidate: String, current: String): Boolean {
            val cParts = candidate.split("-", "+").first().split(".").map { it.toIntOrNull() ?: 0 }
            val curParts = current.split("-", "+").first().split(".").map { it.toIntOrNull() ?: 0 }
            val maxLen = maxOf(cParts.size, curParts.size)
            for (i in 0 until maxLen) {
                val a = cParts.getOrElse(i) { 0 }
                val b = curParts.getOrElse(i) { 0 }
                if (a != b) return a > b
            }
            return false
        }
    }
}
