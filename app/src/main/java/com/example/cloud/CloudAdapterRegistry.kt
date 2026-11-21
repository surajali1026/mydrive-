package com.example.cloud

import com.example.data.model.CloudProviderType
import com.example.data.remote.BackendApiClient
import okhttp3.OkHttpClient

class CloudAdapterRegistry(
    httpClient: OkHttpClient,
    backendApi: BackendApiClient
) {
    private val adapters: Map<CloudProviderType, CloudStorageAdapter> = mapOf(
        CloudProviderType.GOOGLE_DRIVE to GoogleDriveAdapter(httpClient, backendApi),
        CloudProviderType.ONEDRIVE to OneDriveAdapter(httpClient, backendApi),
        CloudProviderType.DROPBOX to DropboxAdapter(httpClient, backendApi),
        CloudProviderType.BOX to BoxAdapter(httpClient, backendApi),
        CloudProviderType.PCLOUD to PCloudAdapter(httpClient, backendApi),
        CloudProviderType.KOOFR to KoofrAdapter(httpClient, backendApi),
        CloudProviderType.MEGA to MegaPendingAdapter()
    )

    fun get(provider: CloudProviderType): CloudStorageAdapter =
        adapters[provider] ?: error("Unsupported provider: ${provider.displayName}")
}
