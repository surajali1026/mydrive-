package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.data.remote.AppUpdateInfo
import com.example.ui.components.formatBytes
import com.example.ui.theme.EmeraldHealthy
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.viewmodel.GitHubUpdateUiState
import com.example.ui.viewmodel.MyDriveUiState
import java.io.File

/**
 * Profile, Security, Backend Broker URL, and GitHub In-Place Auto-Update Manager (`mydrive` repo).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAndUpdateSheet(
    uiState: MyDriveUiState,
    onDismiss: () -> Unit,
    onSaveAutoUpdateConfig: (enabled: Boolean, owner: String, repo: String) -> Unit,
    onCheckUpdatesNow: () -> Unit,
    onDownloadUpdate: (AppUpdateInfo) -> Unit,
    onInstallApkInPlace: (File) -> Unit,
    onSaveBackendUrl: (String) -> Unit,
    onVerifyEmail: () -> Unit,
    onLogout: () -> Unit
) {
    var autoUpdateEnabled by remember(uiState.settings.autoUpdateEnabled) {
        mutableStateOf(uiState.settings.autoUpdateEnabled)
    }
    var githubOwner by remember(uiState.settings.githubOwner) {
        mutableStateOf(uiState.settings.githubOwner)
    }
    var githubRepo by remember(uiState.settings.githubRepo) {
        mutableStateOf(uiState.settings.githubRepo.ifBlank { BuildConfig.DEFAULT_GITHUB_REPO_NAME })
    }
    var backendUrl by remember(uiState.settings.backendBaseUrl) {
        mutableStateOf(uiState.settings.backendBaseUrl)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Settings & Auto-Update", style = MaterialTheme.typography.headlineSmall)

            // 1. GitHub Auto-Update Card (In-Place Upgrade without Uninstalling)
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.SystemUpdate,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("GitHub Auto-Update", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "Installed: v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = autoUpdateEnabled,
                            onCheckedChange = {
                                autoUpdateEnabled = it
                                onSaveAutoUpdateConfig(it, githubOwner, githubRepo)
                            },
                            modifier = Modifier.testTag("switch_github_auto_update")
                        )
                    }

                    Text(
                        text = "Updates MyDrive directly from GitHub Releases in-place without uninstalling or losing your Room database & connected drives.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = githubOwner,
                            onValueChange = { githubOwner = it },
                            label = { Text("GitHub Username") },
                            placeholder = { Text("your-username") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1.2f)
                                .testTag("github_owner_input")
                        )
                        OutlinedTextField(
                            value = githubRepo,
                            onValueChange = { githubRepo = it },
                            label = { Text("Repo") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(0.8f)
                                .testTag("github_repo_input")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onSaveAutoUpdateConfig(autoUpdateEnabled, githubOwner, githubRepo) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("save_github_repo_button")
                        ) { Text("Save Repo") }
                        Button(
                            onClick = {
                                onSaveAutoUpdateConfig(autoUpdateEnabled, githubOwner, githubRepo)
                                onCheckUpdatesNow()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("check_github_updates_button")
                        ) { Text("Check Now") }
                    }

                    // Dynamic Update State Panel
                    when (val state = uiState.updateState) {
                        is GitHubUpdateUiState.Checking -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Checking github.com/$githubOwner/$githubRepo/releases...", style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        is GitHubUpdateUiState.Available -> {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "New Version Available: v${state.info.latestVersion}",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = EmeraldHealthy
                                    )
                                    Text(
                                        text = state.info.releaseNotes,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${state.info.apkFileName} • ${formatBytes(state.info.apkSizeBytes)}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily)
                                    )
                                    Button(
                                        onClick = { onDownloadUpdate(state.info) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("download_github_update_button")
                                    ) {
                                        Text("Download Update (In-Place)")
                                    }
                                }
                            }
                        }

                        is GitHubUpdateUiState.Downloading -> {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "Downloading v${state.info.latestVersion}... ${(state.progress * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                LinearProgressIndicator(
                                    progress = { state.progress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        is GitHubUpdateUiState.ReadyToInstall -> {
                            Button(
                                onClick = { onInstallApkInPlace(state.apkFile) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("install_github_update_button")
                            ) {
                                Text("Install v${state.info.latestVersion} Now (No Uninstall)")
                            }
                        }

                        is GitHubUpdateUiState.UpToDate -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = EmeraldHealthy, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "You are running the latest MyDrive release (v${state.currentVersion}).",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        is GitHubUpdateUiState.InfoMessage -> {
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        GitHubUpdateUiState.Idle -> Unit
                    }
                }
            }

            // 2. Node.js OAuth Broker & PostgreSQL Backend URL
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("OAuth Broker & PostgreSQL Backend", style = MaterialTheme.typography.titleSmall)
                    }
                    OutlinedTextField(
                        value = backendUrl,
                        onValueChange = { backendUrl = it },
                        label = { Text("Backend HTTPS URL") },
                        placeholder = { Text("https://mydrive-api.yourdomain.com") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_backend_url_input")
                    )
                    Button(
                        onClick = { onSaveBackendUrl(backendUrl) },
                        modifier = Modifier
                            .align(Alignment.End)
                            .testTag("settings_save_backend_button")
                    ) { Text("Save URL") }
                }
            }

            // 3. Account Profile, Verification & Security
            uiState.currentUser?.let { user ->
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(user.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(user.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        if (!user.isVerified) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Email not yet verified", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = onVerifyEmail) { Text("Verify Now") }
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Security, contentDescription = null, tint = EmeraldHealthy, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "Android Keystore AES-256-GCM Token Vault Active",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = EmeraldHealthy
                                )
                            }
                        }

                        HorizontalDivider()

                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                onLogout()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_logout_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sign Out", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
