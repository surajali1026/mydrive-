package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        UserEntity::class,
        ConnectedAccountEntity::class,
        CloudFileEntity::class,
        TransferTaskEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class MyDriveDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun connectedAccountDao(): ConnectedAccountDao
    abstract fun cloudFileDao(): CloudFileDao
    abstract fun transferTaskDao(): TransferTaskDao

    companion object {
        @Volatile
        private var INSTANCE: MyDriveDatabase? = null

        fun getInstance(context: Context): MyDriveDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MyDriveDatabase::class.java,
                    "mydrive_vault.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
