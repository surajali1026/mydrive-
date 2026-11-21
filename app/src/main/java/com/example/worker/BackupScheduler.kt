package com.example.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

object BackupScheduler {
    const val UNIQUE_DAILY_BACKUP_WORK = "mydrive_daily_gallery_backup"
    const val UNIQUE_IMMEDIATE_BACKUP_WORK = "mydrive_immediate_gallery_backup"

    /**
     * Schedules a 24-hour PeriodicWorkRequest that executes even when MyDrive is closed/killed.
     */
    fun scheduleDailyBackup(
        context: Context,
        hour: Int,
        minute: Int,
        wifiOnly: Boolean,
        replaceExisting: Boolean = false
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val initialDelayMs = computeInitialDelayMillis(hour, minute)

        val request = PeriodicWorkRequestBuilder<DailyGalleryBackupWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .addTag(UNIQUE_DAILY_BACKUP_WORK)
            .build()

        val policy = if (replaceExisting) {
            ExistingPeriodicWorkPolicy.UPDATE
        } else {
            ExistingPeriodicWorkPolicy.KEEP
        }

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_DAILY_BACKUP_WORK,
            policy,
            request
        )
    }

    fun cancelDailyBackup(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_DAILY_BACKUP_WORK)
    }

    fun runImmediateBackup(context: Context, wifiOnly: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<DailyGalleryBackupWorker>()
            .setConstraints(constraints)
            .addTag(UNIQUE_IMMEDIATE_BACKUP_WORK)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_IMMEDIATE_BACKUP_WORK,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun computeInitialDelayMillis(targetHour: Int, targetMinute: Int): Long {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, targetHour.coerceIn(0, 23))
            set(Calendar.MINUTE, targetMinute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return (next.timeInMillis - now.timeInMillis).coerceAtLeast(60_000L)
    }
}
