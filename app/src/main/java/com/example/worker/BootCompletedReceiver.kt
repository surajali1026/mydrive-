package com.example.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.local.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-arms the daily 24-hour WorkManager gallery backup schedule after phone reboot.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsStore(context).current()
                if (settings.autoGalleryBackupEnabled && settings.dailyScheduleEnabled) {
                    BackupScheduler.scheduleDailyBackup(
                        context = context,
                        hour = settings.scheduleHour,
                        minute = settings.scheduleMinute,
                        wifiOnly = settings.wifiOnlyBackup,
                        replaceExisting = false
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
