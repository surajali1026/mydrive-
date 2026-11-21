package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.myDriveDataStore: DataStore<Preferences> by preferencesDataStore(name = "mydrive_settings")

data class MyDriveSettings(
    val activeUserId: String? = null,
    val autoGalleryBackupEnabled: Boolean = false,
    val dailyScheduleEnabled: Boolean = false,
    val scheduleHour: Int = 2,
    val scheduleMinute: Int = 0,
    val wifiOnlyBackup: Boolean = true,
    val lastBackupTimestamp: Long = 0L,
    val autoUpdateEnabled: Boolean = true,
    val githubOwner: String = "",
    val githubRepo: String = BuildConfig.DEFAULT_GITHUB_REPO_NAME,
    val backendBaseUrl: String = BuildConfig.DEFAULT_BACKEND_URL,
    val darkThemeOverride: Boolean? = null
)

class SettingsStore(private val context: Context) {

    val settingsFlow: Flow<MyDriveSettings> = context.myDriveDataStore.data.map { prefs ->
        MyDriveSettings(
            activeUserId = prefs[KEY_ACTIVE_USER_ID],
            autoGalleryBackupEnabled = prefs[KEY_AUTO_GALLERY_BACKUP] ?: false,
            dailyScheduleEnabled = prefs[KEY_DAILY_SCHEDULE_ENABLED] ?: false,
            scheduleHour = prefs[KEY_SCHEDULE_HOUR] ?: 2,
            scheduleMinute = prefs[KEY_SCHEDULE_MINUTE] ?: 0,
            wifiOnlyBackup = prefs[KEY_WIFI_ONLY_BACKUP] ?: true,
            lastBackupTimestamp = prefs[KEY_LAST_BACKUP_TS] ?: 0L,
            autoUpdateEnabled = prefs[KEY_AUTO_UPDATE_ENABLED] ?: true,
            githubOwner = prefs[KEY_GITHUB_OWNER] ?: "",
            githubRepo = prefs[KEY_GITHUB_REPO]?.ifBlank { BuildConfig.DEFAULT_GITHUB_REPO_NAME }
                ?: BuildConfig.DEFAULT_GITHUB_REPO_NAME,
            backendBaseUrl = prefs[KEY_BACKEND_URL] ?: BuildConfig.DEFAULT_BACKEND_URL,
            darkThemeOverride = prefs[KEY_DARK_THEME_OVERRIDE]
        )
    }

    suspend fun current(): MyDriveSettings = settingsFlow.first()

    suspend fun setActiveUserId(userId: String?) {
        context.myDriveDataStore.edit { prefs ->
            if (userId == null) prefs.remove(KEY_ACTIVE_USER_ID)
            else prefs[KEY_ACTIVE_USER_ID] = userId
        }
    }

    suspend fun setAutoGalleryBackup(enabled: Boolean) {
        context.myDriveDataStore.edit { it[KEY_AUTO_GALLERY_BACKUP] = enabled }
    }

    suspend fun setDailySchedule(enabled: Boolean, hour: Int, minute: Int) {
        context.myDriveDataStore.edit { prefs ->
            prefs[KEY_DAILY_SCHEDULE_ENABLED] = enabled
            prefs[KEY_SCHEDULE_HOUR] = hour.coerceIn(0, 23)
            prefs[KEY_SCHEDULE_MINUTE] = minute.coerceIn(0, 59)
        }
    }

    suspend fun setWifiOnlyBackup(wifiOnly: Boolean) {
        context.myDriveDataStore.edit { it[KEY_WIFI_ONLY_BACKUP] = wifiOnly }
    }

    suspend fun setLastBackupTimestamp(timestamp: Long) {
        context.myDriveDataStore.edit { it[KEY_LAST_BACKUP_TS] = timestamp }
    }

    suspend fun setAutoUpdateConfig(enabled: Boolean, owner: String, repo: String) {
        context.myDriveDataStore.edit { prefs ->
            prefs[KEY_AUTO_UPDATE_ENABLED] = enabled
            prefs[KEY_GITHUB_OWNER] = owner.trim()
            prefs[KEY_GITHUB_REPO] = repo.trim().ifBlank { BuildConfig.DEFAULT_GITHUB_REPO_NAME }
        }
    }

    suspend fun setBackendBaseUrl(url: String) {
        context.myDriveDataStore.edit { it[KEY_BACKEND_URL] = url.trim().trimEnd('/') }
    }

    companion object {
        private val KEY_ACTIVE_USER_ID = stringPreferencesKey("active_user_id")
        private val KEY_AUTO_GALLERY_BACKUP = booleanPreferencesKey("auto_gallery_backup")
        private val KEY_DAILY_SCHEDULE_ENABLED = booleanPreferencesKey("daily_schedule_enabled")
        private val KEY_SCHEDULE_HOUR = intPreferencesKey("schedule_hour")
        private val KEY_SCHEDULE_MINUTE = intPreferencesKey("schedule_minute")
        private val KEY_WIFI_ONLY_BACKUP = booleanPreferencesKey("wifi_only_backup")
        private val KEY_LAST_BACKUP_TS = longPreferencesKey("last_backup_ts")
        private val KEY_AUTO_UPDATE_ENABLED = booleanPreferencesKey("auto_update_enabled")
        private val KEY_GITHUB_OWNER = stringPreferencesKey("github_owner")
        private val KEY_GITHUB_REPO = stringPreferencesKey("github_repo")
        private val KEY_BACKEND_URL = stringPreferencesKey("backend_base_url")
        private val KEY_DARK_THEME_OVERRIDE = booleanPreferencesKey("dark_theme_override")
    }
}
