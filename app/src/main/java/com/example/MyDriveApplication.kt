package com.example

import android.app.Application
import com.example.cloud.CloudAdapterRegistry
import com.example.cloud.SmartStorageRouter
import com.example.data.local.MyDriveDatabase
import com.example.data.local.SettingsStore
import com.example.data.remote.BackendApiClient
import com.example.data.remote.GitHubAutoUpdater
import com.example.data.repository.MyDriveRepository
import com.example.security.TokenVault
import com.example.worker.BackupNotifications

class MyDriveApplication : Application() {

    lateinit var repository: MyDriveRepository
        private set

    override fun onCreate() {
        super.onCreate()
        BackupNotifications.createChannels(this)

        val db = MyDriveDatabase.getInstance(this)
        val settingsStore = SettingsStore(this)
        val tokenVault = TokenVault(this)
        val httpClient = BackendApiClient.defaultClient()
        val backendApi = BackendApiClient(httpClient)
        val adapterRegistry = CloudAdapterRegistry(httpClient, backendApi)
        val smartRouter = SmartStorageRouter(
            accountDao = db.connectedAccountDao(),
            fileDao = db.cloudFileDao(),
            adapterRegistry = adapterRegistry,
            tokenVault = tokenVault,
            backendApi = backendApi
        )
        val autoUpdater = GitHubAutoUpdater(this, httpClient)

        repository = MyDriveRepository(
            context = this,
            db = db,
            settingsStore = settingsStore,
            tokenVault = tokenVault,
            backendApi = backendApi,
            adapterRegistry = adapterRegistry,
            smartRouter = smartRouter,
            autoUpdater = autoUpdater
        )
    }
}
