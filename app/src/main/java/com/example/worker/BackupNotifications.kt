package com.example.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.example.R

object BackupNotifications {
    const val CHANNEL_BACKUP = "mydrive_gallery_backup"
    const val CHANNEL_UPDATES = "mydrive_app_updates"
    const val NOTIF_ID_PROGRESS = 1001
    const val NOTIF_ID_RESULT = 1002
    const val NOTIF_ID_UPDATE = 1003

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val backupChannel = NotificationChannel(
                CHANNEL_BACKUP,
                context.getString(R.string.backup_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.backup_channel_desc)
            }
            val updatesChannel = NotificationChannel(
                CHANNEL_UPDATES,
                context.getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.update_channel_desc)
            }
            manager.createNotificationChannel(backupChannel)
            manager.createNotificationChannel(updatesChannel)
        }
    }

    fun buildForegroundInfo(
        context: Context,
        title: String,
        subtitle: String,
        progress: Int,
        max: Int
    ): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_BACKUP)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(max, progress, max <= 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIF_ID_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIF_ID_PROGRESS, notification)
        }
    }

    fun postResultNotification(context: Context, title: String, message: String, isError: Boolean = false) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(context, CHANNEL_BACKUP)
            .setSmallIcon(
                if (isError) android.R.drawable.stat_notify_error
                else android.R.drawable.stat_sys_upload_done
            )
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { manager.notify(NOTIF_ID_RESULT, notif) }
    }
}
