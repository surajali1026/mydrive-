package com.example.worker

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.cloud.CloudAdapterRegistry
import com.example.cloud.SmartStorageRouter
import com.example.cloud.SmartUploadResult
import com.example.data.local.MyDriveDatabase
import com.example.data.local.SettingsStore
import com.example.data.local.TransferTaskEntity
import com.example.data.model.TransferDirection
import com.example.data.model.TransferStatus
import com.example.data.remote.BackendApiClient
import com.example.security.TokenVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

private data class GalleryMediaItem(
    val contentUri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long
)

/**
 * Daily Background Gallery Backup Worker (Android WorkManager).
 *
 * - Runs automatically every 24 hours even when MyDrive is completely closed/killed.
 * - Scans MediaStore Images & Videos strictly read-only (NON-DESTRUCTIVE: never alters or deletes local gallery files).
 * - Computes local SHA-256 hash and deduplicates against Room `cloud_files` table.
 * - Routes uploads through `SmartStorageRouter` (70% capacity threshold auto-switch + failover).
 * - Displays foreground progress notification and final completion/failure alerts.
 */
class DailyGalleryBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        BackupNotifications.createChannels(context)

        val settingsStore = SettingsStore(context)
        val settings = settingsStore.current()
        val userId = settings.activeUserId

        if (userId.isNullOrBlank()) {
            return@withContext Result.success()
        }

        val db = MyDriveDatabase.getInstance(context)
        val tokenVault = TokenVault(context)
        val httpClient = BackendApiClient.defaultClient()
        val backendApi = BackendApiClient(httpClient)
        val registry = CloudAdapterRegistry(httpClient, backendApi)
        val router = SmartStorageRouter(
            accountDao = db.connectedAccountDao(),
            fileDao = db.cloudFileDao(),
            adapterRegistry = registry,
            tokenVault = tokenVault,
            backendApi = backendApi
        )

        runCatching {
            setForeground(
                BackupNotifications.buildForegroundInfo(
                    context = context,
                    title = "MyDrive Gallery Backup",
                    subtitle = "Scanning gallery for new photos & videos...",
                    progress = 0,
                    max = 0
                )
            )
        }

        val mediaItems = scanGalleryReadOnly(context)
        if (mediaItems.isEmpty()) {
            settingsStore.setLastBackupTimestamp(System.currentTimeMillis())
            BackupNotifications.postResultNotification(
                context,
                "MyDrive Backup Complete",
                "No new photos or videos found to back up."
            )
            return@withContext Result.success()
        }

        var uploadedCount = 0
        var skippedDuplicates = 0
        var failedCount = 0
        var warnAddDrive = false
        var lastError: String? = null

        val tempDir = File(context.cacheDir, "backup_staging").apply { mkdirs() }

        for ((index, item) in mediaItems.withIndex()) {
            if (isStopped) return@withContext Result.retry()

            runCatching {
                setForeground(
                    BackupNotifications.buildForegroundInfo(
                        context = context,
                        title = "Backing up ${index + 1} of ${mediaItems.size}",
                        subtitle = item.displayName,
                        progress = index,
                        max = mediaItems.size
                    )
                )
            }

            // Compute SHA-256 hash directly from read-only ContentResolver stream
            val sha256 = runCatching {
                context.contentResolver.openInputStream(item.contentUri)?.use { stream ->
                    TokenVault.computeSha256(stream)
                }
            }.getOrNull()

            if (sha256.isNullOrBlank()) {
                failedCount++
                lastError = "Unable to read ${item.displayName}"
                continue
            }

            // SHA-256 Deduplication against Room `cloud_files`
            if (db.cloudFileDao().existsBySha256(userId, sha256)) {
                skippedDuplicates++
                continue
            }

            // Stage temporary read-only copy for chunked resumable upload (never touches original MediaStore file)
            val stagedFile = File(tempDir, "${UUID.randomUUID()}_${item.displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
            val taskId = UUID.randomUUID().toString()
            try {
                context.contentResolver.openInputStream(item.contentUri)?.use { input ->
                    FileOutputStream(stagedFile).use { output ->
                        input.copyTo(output, bufferSize = 16 * 1024)
                    }
                } ?: error("Cannot open gallery item")

                db.transferTaskDao().upsertTask(
                    TransferTaskEntity(
                        id = taskId,
                        userId = userId,
                        fileName = item.displayName,
                        localUri = item.contentUri.toString(),
                        mimeType = item.mimeType,
                        totalBytes = stagedFile.length(),
                        transferredBytes = 0L,
                        direction = TransferDirection.UPLOAD.name,
                        status = TransferStatus.IN_PROGRESS.name
                    )
                )

                val result = router.routeAndUpload(
                    userId = userId,
                    localFile = stagedFile,
                    fileName = item.displayName,
                    mimeType = item.mimeType,
                    sha256Hash = sha256,
                    folderPath = "/MyDrive/GalleryBackup",
                    localThumbnailUri = item.contentUri.toString(),
                    backendBaseUrl = settings.backendBaseUrl,
                    onProgress = { sent, _ ->
                        // Best-effort lightweight progress updates
                        runCatching {
                            // Handled asynchronously if needed
                        }
                    }
                )

                when (result) {
                    is SmartUploadResult.Success -> {
                        uploadedCount++
                        if (result.warningAddAnotherDrive) warnAddDrive = true
                        db.transferTaskDao().upsertTask(
                            TransferTaskEntity(
                                id = taskId,
                                userId = userId,
                                fileName = item.displayName,
                                localUri = item.contentUri.toString(),
                                mimeType = item.mimeType,
                                totalBytes = stagedFile.length(),
                                transferredBytes = stagedFile.length(),
                                providerType = result.usedAccount.providerType,
                                accountId = result.usedAccount.id,
                                direction = TransferDirection.UPLOAD.name,
                                status = TransferStatus.COMPLETED.name
                            )
                        )
                    }

                    is SmartUploadResult.NoConnectedDrives -> {
                        failedCount++
                        lastError = result.message
                        db.transferTaskDao().updateProgress(
                            taskId = taskId,
                            transferred = 0L,
                            status = TransferStatus.FAILED.name,
                            error = result.message
                        )
                        // Stop iterating further when no drives are connected
                        break
                    }

                    is SmartUploadResult.StorageFull -> {
                        failedCount++
                        lastError = result.message
                        db.transferTaskDao().updateProgress(
                            taskId = taskId,
                            transferred = 0L,
                            status = TransferStatus.FAILED.name,
                            error = result.message
                        )
                        break
                    }

                    is SmartUploadResult.Failed -> {
                        failedCount++
                        lastError = result.message
                        db.transferTaskDao().updateProgress(
                            taskId = taskId,
                            transferred = 0L,
                            status = TransferStatus.FAILED.name,
                            error = result.message
                        )
                    }
                }
            } catch (e: Exception) {
                failedCount++
                lastError = e.message ?: "Backup error"
                db.transferTaskDao().updateProgress(
                    taskId = taskId,
                    transferred = 0L,
                    status = TransferStatus.FAILED.name,
                    error = lastError
                )
            } finally {
                // Only deletes temporary staging cache copy; NEVER alters or deletes original gallery files
                stagedFile.delete()
            }
        }

        settingsStore.setLastBackupTimestamp(System.currentTimeMillis())

        val summary = buildString {
            append("Uploaded $uploadedCount new item(s)")
            if (skippedDuplicates > 0) append(", skipped $skippedDuplicates duplicate(s)")
            if (failedCount > 0) append(", $failedCount failed")
            if (warnAddDrive) append(". Drives above 70% — connect another drive soon.")
            if (failedCount > 0 && !lastError.isNullOrBlank()) append("\nReason: $lastError")
        }

        BackupNotifications.postResultNotification(
            context = context,
            title = if (failedCount > 0 && uploadedCount == 0) "MyDrive Backup Alert" else "MyDrive Backup Complete",
            message = summary,
            isError = failedCount > 0 && uploadedCount == 0
        )

        if (failedCount > 0 && uploadedCount == 0 && runAttemptCount < 2) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    private fun scanGalleryReadOnly(context: Context): List<GalleryMediaItem> {
        val items = mutableListOf<GalleryMediaItem>()
        val queries = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "image/jpeg",
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "video/mp4"
        )
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE
        )

        for ((collectionUri, fallbackMime) in queries) {
            runCatching {
                context.contentResolver.query(
                    collectionUri,
                    projection,
                    null,
                    null,
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    var count = 0
                    while (cursor.moveToNext() && count < MAX_SCAN_PER_RUN) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "media_$id"
                        val mime = cursor.getString(mimeCol) ?: fallbackMime
                        val size = cursor.getLong(sizeCol)
                        if (size > 0L) {
                            items.add(
                                GalleryMediaItem(
                                    contentUri = ContentUris.withAppendedId(collectionUri, id),
                                    displayName = name,
                                    mimeType = mime,
                                    sizeBytes = size
                                )
                            )
                            count++
                        }
                    }
                }
            }
        }
        return items
    }

    companion object {
        private const val MAX_SCAN_PER_RUN = 100
    }
}
