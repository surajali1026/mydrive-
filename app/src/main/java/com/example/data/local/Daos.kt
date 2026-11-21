package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE id = :userId LIMIT 1")
    fun observeUser(userId: String): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE id = :userId LIMIT 1")
    suspend fun getUserById(userId: String): UserEntity?

    @Query("SELECT * FROM users WHERE LOWER(email) = LOWER(:email) LIMIT 1")
    suspend fun getUserByEmail(email: String): UserEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUser(user: UserEntity)

    @Query("UPDATE users SET is_verified = :verified WHERE id = :userId")
    suspend fun setVerified(userId: String, verified: Boolean)

    @Query("UPDATE users SET password_hash = :passwordHash WHERE LOWER(email) = LOWER(:email)")
    suspend fun updatePasswordHash(email: String, passwordHash: String)
}

@Dao
interface ConnectedAccountDao {
    @Query("SELECT * FROM connected_accounts WHERE user_id = :userId ORDER BY priority_order ASC, last_synced_at DESC")
    fun observeAccounts(userId: String): Flow<List<ConnectedAccountEntity>>

    @Query("SELECT * FROM connected_accounts WHERE user_id = :userId ORDER BY priority_order ASC")
    suspend fun getAccountsSync(userId: String): List<ConnectedAccountEntity>

    @Query("SELECT * FROM connected_accounts WHERE id = :accountId LIMIT 1")
    suspend fun getAccountById(accountId: String): ConnectedAccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccount(account: ConnectedAccountEntity)

    @Update
    suspend fun updateAccount(account: ConnectedAccountEntity)

    @Query("UPDATE connected_accounts SET quota_used = :usedBytes, quota_total = :totalBytes, last_synced_at = :timestamp WHERE id = :accountId")
    suspend fun updateQuota(accountId: String, usedBytes: Long, totalBytes: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE connected_accounts SET status = :status WHERE id = :accountId")
    suspend fun updateStatus(accountId: String, status: String)

    @Query("UPDATE connected_accounts SET priority_order = :priority WHERE id = :accountId")
    suspend fun updatePriority(accountId: String, priority: Int)

    @Query("DELETE FROM connected_accounts WHERE id = :accountId")
    suspend fun deleteAccount(accountId: String)
}

@Dao
interface CloudFileDao {
    @Query("SELECT * FROM cloud_files WHERE user_id = :userId ORDER BY uploaded_at DESC")
    fun observeFiles(userId: String): Flow<List<CloudFileEntity>>

    @Query("SELECT * FROM cloud_files WHERE user_id = :userId AND is_trashed = 0 ORDER BY uploaded_at DESC")
    suspend fun getActiveFilesSync(userId: String): List<CloudFileEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM cloud_files WHERE user_id = :userId AND sha256_hash = :sha256Hash AND is_trashed = 0)")
    suspend fun existsBySha256(userId: String, sha256Hash: String): Boolean

    @Query("SELECT * FROM cloud_files WHERE id = :fileId LIMIT 1")
    suspend fun getFileById(fileId: String): CloudFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFile(file: CloudFileEntity)

    @Query("UPDATE cloud_files SET file_name = :newName WHERE id = :fileId")
    suspend fun renameFile(fileId: String, newName: String)

    @Query("UPDATE cloud_files SET folder_path = :newFolderPath WHERE id = :fileId")
    suspend fun updateFolderPath(fileId: String, newFolderPath: String)

    @Query("UPDATE cloud_files SET is_favorite = :isFavorite WHERE id = :fileId")
    suspend fun setFavorite(fileId: String, isFavorite: Boolean)

    @Query("UPDATE cloud_files SET is_trashed = :isTrashed WHERE id = :fileId")
    suspend fun setTrashed(fileId: String, isTrashed: Boolean)

    @Query("DELETE FROM cloud_files WHERE id = :fileId")
    suspend fun deleteFilePermanently(fileId: String)

    @Query("DELETE FROM cloud_files WHERE account_id = :accountId")
    suspend fun deleteFilesForAccount(accountId: String)
}

@Dao
interface TransferTaskDao {
    @Query("SELECT * FROM transfer_tasks WHERE user_id = :userId ORDER BY updated_at DESC LIMIT 50")
    fun observeTasks(userId: String): Flow<List<TransferTaskEntity>>

    @Query("SELECT * FROM transfer_tasks WHERE id = :taskId LIMIT 1")
    suspend fun getTaskById(taskId: String): TransferTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: TransferTaskEntity)

    @Query("UPDATE transfer_tasks SET transferred_bytes = :transferred, status = :status, error_message = :error, updated_at = :updatedAt WHERE id = :taskId")
    suspend fun updateProgress(
        taskId: String,
        transferred: Long,
        status: String,
        error: String? = null,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM transfer_tasks WHERE user_id = :userId AND status = 'COMPLETED'")
    suspend fun clearCompleted(userId: String)
}
