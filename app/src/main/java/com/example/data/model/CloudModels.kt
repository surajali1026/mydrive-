package com.example.data.model

import androidx.compose.ui.graphics.Color
import com.example.ui.theme.ProviderBox
import com.example.ui.theme.ProviderDropbox
import com.example.ui.theme.ProviderGoogleDrive
import com.example.ui.theme.ProviderKoofr
import com.example.ui.theme.ProviderMega
import com.example.ui.theme.ProviderOneDrive
import com.example.ui.theme.ProviderPCloud

/**
 * Official cloud providers supported by MyDrive.
 * Strictly excludes TeraBox and any unofficial/reverse-engineered integrations.
 */
enum class CloudProviderType(
    val id: String,
    val displayName: String,
    val shortBadge: String,
    val freeTierLabel: String,
    val accentColor: Color,
    val isIntegrationPending: Boolean = false,
    val apiSummary: String
) {
    GOOGLE_DRIVE(
        id = "google_drive",
        displayName = "Google Drive",
        shortBadge = "GDrive",
        freeTierLabel = "15 GB free",
        accentColor = ProviderGoogleDrive,
        apiSummary = "Google Drive REST API v3 • OAuth 2.0 (drive.file)"
    ),
    ONEDRIVE(
        id = "onedrive",
        displayName = "Microsoft OneDrive",
        shortBadge = "OneDrive",
        freeTierLabel = "5 GB free",
        accentColor = ProviderOneDrive,
        apiSummary = "Microsoft Graph API (/me/drive) • OAuth 2.0 PKCE"
    ),
    DROPBOX(
        id = "dropbox",
        displayName = "Dropbox",
        shortBadge = "Dropbox",
        freeTierLabel = "2 GB free",
        accentColor = ProviderDropbox,
        apiSummary = "Dropbox API v2 • PKCE OAuth 2.0"
    ),
    BOX(
        id = "box",
        displayName = "Box",
        shortBadge = "Box",
        freeTierLabel = "10 GB free",
        accentColor = ProviderBox,
        apiSummary = "Box Content API v2.0 • OAuth 2.0"
    ),
    PCLOUD(
        id = "pcloud",
        displayName = "pCloud",
        shortBadge = "pCloud",
        freeTierLabel = "10 GB free",
        accentColor = ProviderPCloud,
        apiSummary = "pCloud REST API • OAuth 2.0"
    ),
    KOOFR(
        id = "koofr",
        displayName = "Koofr",
        shortBadge = "Koofr",
        freeTierLabel = "10 GB free",
        accentColor = ProviderKoofr,
        apiSummary = "Koofr REST API v2 • OAuth 2.0"
    ),
    MEGA(
        id = "mega",
        displayName = "MEGA",
        shortBadge = "MEGA",
        freeTierLabel = "20 GB free",
        accentColor = ProviderMega,
        isIntegrationPending = true,
        apiSummary = "Integration Pending — Official MEGA SDK commercial registration required; password extraction disabled."
    );

    companion object {
        fun fromId(id: String): CloudProviderType =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: GOOGLE_DRIVE
    }
}

enum class AccountStatus(val label: String) {
    CONNECTED("Connected"),
    EXPIRED_RECONNECT("Expired - Reconnect"),
    INTEGRATION_PENDING("Integration Pending"),
    DISCONNECTED("Disconnected");

    companion object {
        fun fromName(name: String): AccountStatus =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DISCONNECTED
    }
}

enum class FileCategory(val label: String) {
    ALL("All"),
    PHOTOS("Photos"),
    VIDEOS("Videos"),
    AUDIO("Audio"),
    DOCUMENTS("Documents"),
    OTHER("Other"),
    RECENT("Recent"),
    FAVORITES("Favorites"),
    TRASH("Trash");

    companion object {
        fun fromMimeType(mimeType: String): FileCategory {
            val lower = mimeType.lowercase()
            return when {
                lower.startsWith("image/") -> PHOTOS
                lower.startsWith("video/") -> VIDEOS
                lower.startsWith("audio/") -> AUDIO
                lower.contains("pdf") ||
                    lower.contains("document") ||
                    lower.contains("msword") ||
                    lower.contains("spreadsheet") ||
                    lower.contains("presentation") ||
                    lower.startsWith("text/") -> DOCUMENTS
                else -> OTHER
            }
        }
    }
}

enum class DateFilter(val label: String, val windowMillis: Long?) {
    ALL_TIME("Any Date", null),
    TODAY("Today", 24L * 60L * 60L * 1000L),
    LAST_7_DAYS("Last 7 Days", 7L * 24L * 60L * 60L * 1000L),
    LAST_30_DAYS("Last 30 Days", 30L * 24L * 60L * 60L * 1000L)
}

enum class RoutingMode {
    SMART_AUTO_70_PERCENT,
    MANUAL_DRIVE
}

enum class TransferDirection {
    UPLOAD,
    DOWNLOAD
}

enum class TransferStatus(val label: String) {
    QUEUED("Queued"),
    IN_PROGRESS("In Progress"),
    PAUSED("Paused"),
    COMPLETED("Completed"),
    FAILED("Failed")
}
