package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.model.AccountStatus
import com.example.data.model.CloudProviderType
import com.example.data.model.FileCategory
import com.example.data.model.TransferDirection
import com.example.data.model.TransferStatus

@Entity(
    tableName = "users",
    indices = [Index(value = ["email"], unique = true)]
)
data class UserEntity(
    @PrimaryKey
    val id: String,
    val email: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "auth_provider")
    val authProvider: String, // "email" or "google"
    @ColumnInfo(name = "password_hash")
    val passwordHash: String? = null,
    @ColumnInfo(name = "is_verified")
    val isVerified: Boolean = false,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "connected_accounts",
    indices = [Index(value = ["user_id", "provider_type"])]
)
data class ConnectedAccountEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "provider_type")
    val providerType: String, // CloudProviderType.id
    @ColumnInfo(name = "account_email")
    val accountEmail: String,
    @ColumnInfo(name = "encrypted_tokens")
    val encryptedTokens: String, // AES-256-GCM encrypted JSON payload via TokenVault
    @ColumnInfo(name = "quota_total")
    val quotaTotal: Long, // -1 if unavailable
    @ColumnInfo(name = "quota_used")
    val quotaUsed: Long, // -1 if unavailable
    @ColumnInfo(name = "quota_is_estimated")
    val quotaIsEstimated: Boolean = false,
    @ColumnInfo(name = "priority_order")
    val priorityOrder: Int = 0,
    val status: String = AccountStatus.CONNECTED.name,
    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long = System.currentTimeMillis()
) {
    val provider: CloudProviderType
        get() = CloudProviderType.fromId(providerType)

    val accountStatus: AccountStatus
        get() = AccountStatus.fromName(status)

    val hasValidQuota: Boolean
        get() = quotaTotal > 0L && quotaUsed >= 0L

    val usageRatio: Float
        get() = if (hasValidQuota) (quotaUsed.toDouble() / quotaTotal.toDouble()).toFloat().coerceIn(0f, 1f) else 0f

    val availableBytes: Long
        get() = if (hasValidQuota) (quotaTotal - quotaUsed).coerceAtLeast(0L) else 0L
}

@Entity(
    tableName = "cloud_files",
    indices = [
        Index(value = ["user_id"]),
        Index(value = ["account_id"]),
        Index(value = ["sha256_hash"]),
        Index(value = ["provider_type"])
    ]
)
data class CloudFileEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "account_id")
    val accountId: String,
    @ColumnInfo(name = "provider_type")
    val providerType: String,
    @ColumnInfo(name = "remote_file_id")
    val remoteFileId: String,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "sha256_hash")
    val sha256Hash: String,
    val category: String, // FileCategory.name
    @ColumnInfo(name = "folder_path")
    val folderPath: String = "/MyDrive",
    @ColumnInfo(name = "local_thumbnail_uri")
    val localThumbnailUri: String? = null,
    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean = false,
    @ColumnInfo(name = "is_trashed")
    val isTrashed: Boolean = false,
    @ColumnInfo(name = "uploaded_at")
    val uploadedAt: Long = System.currentTimeMillis()
) {
    val provider: CloudProviderType
        get() = CloudProviderType.fromId(providerType)

    val fileCategory: FileCategory
        get() = FileCategory.entries.firstOrNull { it.name == category } ?: FileCategory.OTHER
}

@Entity(
    tableName = "transfer_tasks",
    indices = [Index(value = ["user_id"]), Index(value = ["status"])]
)
data class TransferTaskEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "local_uri")
    val localUri: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "total_bytes")
    val totalBytes: Long,
    @ColumnInfo(name = "transferred_bytes")
    val transferredBytes: Long = 0L,
    @ColumnInfo(name = "provider_type")
    val providerType: String = "",
    @ColumnInfo(name = "account_id")
    val accountId: String = "",
    val direction: String = TransferDirection.UPLOAD.name,
    val status: String = TransferStatus.QUEUED.name,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    val progress: Float
        get() = if (totalBytes > 0L) (transferredBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f) else 0f

    val transferStatus: TransferStatus
        get() = TransferStatus.entries.firstOrNull { it.name == status } ?: TransferStatus.QUEUED

    val transferDirection: TransferDirection
        get() = TransferDirection.entries.firstOrNull { it.name == direction } ?: TransferDirection.UPLOAD
}
