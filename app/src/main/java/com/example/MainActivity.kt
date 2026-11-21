package com.example

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.CloudDrivesScreen
import com.example.ui.screens.MyUploadsScreen
import com.example.ui.screens.SettingsAndUpdateSheet
import com.example.ui.theme.MyDriveTheme
import com.example.ui.viewmodel.MyDriveViewModel
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch
import java.io.File

const val ROUTE_MY_UPLOADS = "my_uploads"
const val ROUTE_CLOUD_DRIVES = "cloud_drives"

class MainActivity : ComponentActivity() {

    private val viewModel: MyDriveViewModel by viewModels {
        MyDriveViewModel.Factory((application as MyDriveApplication).repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleOAuthIntent(intent)

        setContent {
            MyDriveTheme {
                MyDriveAppRoot(
                    viewModel = viewModel,
                    onLaunchCustomTab = { url -> launchCustomTab(url) },
                    onInstallUpdateApk = { apkFile -> installApkInPlace(apkFile) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuthIntent(intent)
    }

    private fun handleOAuthIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "mydrive" && data.host == "oauth") {
            viewModel.handleOAuthRedirect(data)
        }
    }

    private fun launchCustomTab(url: String) {
        runCatching {
            val uri = Uri.parse(url)
            if (uri.scheme == "https") {
                CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                    .launchUrl(this, uri)
            }
        }
    }

    private fun installApkInPlace(apkFile: File) {
        val updater = (application as MyDriveApplication).repository.autoUpdater
        if (!updater.canRequestPackageInstalls()) {
            startActivity(updater.createUnknownSourcesSettingsIntent())
            return
        }
        startActivity(updater.createInstallApkIntent(apkFile))
    }
}

@Composable
private fun MyDriveAppRoot(
    viewModel: MyDriveViewModel,
    onLaunchCustomTab: (String) -> Unit,
    onInstallUpdateApk: (File) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showSettingsSheet by remember { mutableStateOf(false) }
    var pendingManualUploadAccountId by remember { mutableStateOf<String?>(null) }

    // OpenDocument picker for universal file uploads (Photos, Videos, Audio, Documents, Other)
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.uploadFile(uri, manualAccountId = pendingManualUploadAccountId)
        }
        pendingManualUploadAccountId = null
    }

    // Minimum required runtime permissions for automatic gallery backup & notifications
    val backupPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val mediaGranted = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                results[Manifest.permission.READ_MEDIA_IMAGES] == true ||
                    results[Manifest.permission.READ_MEDIA_VIDEO] == true ||
                    results[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                results[Manifest.permission.READ_MEDIA_IMAGES] == true ||
                    results[Manifest.permission.READ_MEDIA_VIDEO] == true
            else ->
                results[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        }
        if (mediaGranted) {
            viewModel.updateBackupPreferences(
                autoGalleryEnabled = true,
                dailyScheduleEnabled = uiState.settings.dailyScheduleEnabled,
                hour = uiState.settings.scheduleHour,
                minute = uiState.settings.scheduleMinute,
                wifiOnly = uiState.settings.wifiOnlyBackup
            )
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("Gallery read permission is required to enable Automatic Gallery Backup.")
            }
        }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    LaunchedEffect(uiState.pendingOAuthUrl) {
        uiState.pendingOAuthUrl?.let { url ->
            onLaunchCustomTab(url)
            viewModel.consumePendingOAuthUrl()
        }
    }

    if (!uiState.isInitialized) {
        Box(modifier = Modifier.fillMaxSize())
        return
    }

    // Independent MyDrive Auth Gate
    if (uiState.currentUser == null) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            contentWindowInsets = WindowInsets.safeDrawing
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                AuthScreen(
                    backendUrl = uiState.settings.backendBaseUrl,
                    isBusy = uiState.isBusy,
                    onLogin = { email, pwd -> viewModel.login(email, pwd) },
                    onRegister = { email, pwd, name -> viewModel.register(email, pwd, name) },
                    onResetPassword = { email, newPwd -> viewModel.resetPassword(email, newPwd) },
                    onGoogleSignInClick = {
                        scope.launch {
                            val credentialManager = CredentialManager.create(context)
                            val googleIdOption = GetGoogleIdOption.Builder()
                                .setFilterByAuthorizedAccounts(false)
                                .setServerClientId("mydrive-google-client-id")
                                .build()
                            val request = GetCredentialRequest.Builder()
                                .addCredentialOption(googleIdOption)
                                .build()
                            try {
                                val result = credentialManager.getCredential(context, request)
                                val googleCred = GoogleIdTokenCredential.createFrom(result.credential.data)
                                viewModel.completeGoogleSignIn(
                                    idToken = googleCred.idToken,
                                    email = googleCred.id,
                                    displayName = googleCred.displayName ?: googleCred.id.substringBefore("@")
                                )
                            } catch (_: androidx.credentials.exceptions.NoCredentialException) {
                                snackbarHostState.showSnackbar(
                                    "No Google account credential available on this device. Use Email/Password or configure Google OAuth on your backend."
                                )
                            } catch (_: Exception) {
                                snackbarHostState.showSnackbar(
                                    "Google Sign-In requires a configured Google OAuth Web Client ID on your backend, or sign in with Email/Password above."
                                )
                            }
                        }
                    },
                    onSaveBackendUrl = { url -> viewModel.saveBackendUrl(url) }
                )
            }
        }
        return
    }

    // Main 2-Tab Navigation Architecture (Bottom NavigationBar on Compact, NavigationRail on Expanded)
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: ROUTE_MY_UPLOADS

    val navigateToTab: (String) -> Unit = { route ->
        if (route != currentRoute) {
            navController.navigate(route) {
                popUpTo(ROUTE_MY_UPLOADS) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                if (!useNavigationRail) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = currentRoute == ROUTE_MY_UPLOADS,
                            onClick = { navigateToTab(ROUTE_MY_UPLOADS) },
                            icon = {
                                Icon(
                                    imageVector = if (currentRoute == ROUTE_MY_UPLOADS) Icons.Filled.FolderCopy else Icons.Outlined.FolderCopy,
                                    contentDescription = stringResource(id = R.string.tab_my_uploads)
                                )
                            },
                            label = { Text(stringResource(id = R.string.tab_my_uploads)) },
                            modifier = Modifier.testTag("nav_tab_my_uploads")
                        )
                        NavigationBarItem(
                            selected = currentRoute == ROUTE_CLOUD_DRIVES,
                            onClick = { navigateToTab(ROUTE_CLOUD_DRIVES) },
                            icon = {
                                Icon(
                                    imageVector = if (currentRoute == ROUTE_CLOUD_DRIVES) Icons.Filled.CloudQueue else Icons.Outlined.CloudQueue,
                                    contentDescription = stringResource(id = R.string.tab_cloud_drives)
                                )
                            },
                            label = { Text(stringResource(id = R.string.tab_cloud_drives)) },
                            modifier = Modifier.testTag("nav_tab_cloud_drives")
                        )
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail) {
                    NavigationRail(modifier = Modifier.fillMaxHeight()) {
                        NavigationRailItem(
                            selected = currentRoute == ROUTE_MY_UPLOADS,
                            onClick = { navigateToTab(ROUTE_MY_UPLOADS) },
                            icon = {
                                Icon(
                                    imageVector = if (currentRoute == ROUTE_MY_UPLOADS) Icons.Filled.FolderCopy else Icons.Outlined.FolderCopy,
                                    contentDescription = stringResource(id = R.string.tab_my_uploads)
                                )
                            },
                            label = { Text("Uploads") },
                            modifier = Modifier.testTag("rail_tab_my_uploads")
                        )
                        NavigationRailItem(
                            selected = currentRoute == ROUTE_CLOUD_DRIVES,
                            onClick = { navigateToTab(ROUTE_CLOUD_DRIVES) },
                            icon = {
                                Icon(
                                    imageVector = if (currentRoute == ROUTE_CLOUD_DRIVES) Icons.Filled.CloudQueue else Icons.Outlined.CloudQueue,
                                    contentDescription = stringResource(id = R.string.tab_cloud_drives)
                                )
                            },
                            label = { Text("Drives") },
                            modifier = Modifier.testTag("rail_tab_cloud_drives")
                        )
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = ROUTE_MY_UPLOADS,
                    modifier = Modifier.weight(1f)
                ) {
                    composable(ROUTE_MY_UPLOADS) {
                        MyUploadsScreen(
                            uiState = uiState,
                            onSearchChange = viewModel::updateSearchQuery,
                            onCategorySelected = viewModel::selectCategory,
                            onProviderFilterSelected = viewModel::selectProviderFilter,
                            onDateFilterSelected = viewModel::selectDateFilter,
                            onRequestFileUpload = { manualAccountId ->
                                pendingManualUploadAccountId = manualAccountId
                                filePickerLauncher.launch(arrayOf("*/*"))
                            },
                            onDownloadFile = { file -> viewModel.downloadFile(file) },
                            onRenameFile = viewModel::renameFile,
                            onMoveFile = viewModel::moveFile,
                            onToggleFavorite = viewModel::toggleFavorite,
                            onMoveToTrash = viewModel::moveToTrash,
                            onRestoreFromTrash = viewModel::restoreFromTrash,
                            onDeletePermanently = viewModel::deletePermanently,
                            onOpenSettings = { showSettingsSheet = true },
                            onGoToCloudDrives = { navigateToTab(ROUTE_CLOUD_DRIVES) }
                        )
                    }

                    composable(ROUTE_CLOUD_DRIVES) {
                        CloudDrivesScreen(
                            uiState = uiState,
                            onConnectProvider = { provider, loginHint ->
                                viewModel.startProviderOAuth(provider, loginHint)
                            },
                            onRefreshQuotas = viewModel::refreshQuotas,
                            onDisconnectAccount = viewModel::disconnectAccount,
                            onMoveAccountPriority = viewModel::moveAccountPriority,
                            onToggleAutoBackup = { enable ->
                                if (enable) {
                                    val perms = buildList {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                            add(Manifest.permission.READ_MEDIA_IMAGES)
                                            add(Manifest.permission.READ_MEDIA_VIDEO)
                                            add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                                            add(Manifest.permission.POST_NOTIFICATIONS)
                                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            add(Manifest.permission.READ_MEDIA_IMAGES)
                                            add(Manifest.permission.READ_MEDIA_VIDEO)
                                            add(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            add(Manifest.permission.READ_EXTERNAL_STORAGE)
                                        }
                                    }.toTypedArray()
                                    backupPermissionsLauncher.launch(perms)
                                } else {
                                    viewModel.updateBackupPreferences(
                                        autoGalleryEnabled = false,
                                        dailyScheduleEnabled = false,
                                        hour = uiState.settings.scheduleHour,
                                        minute = uiState.settings.scheduleMinute,
                                        wifiOnly = uiState.settings.wifiOnlyBackup
                                    )
                                }
                            },
                            onToggleDailySchedule = { daily ->
                                viewModel.updateBackupPreferences(
                                    autoGalleryEnabled = uiState.settings.autoGalleryBackupEnabled,
                                    dailyScheduleEnabled = daily,
                                    hour = uiState.settings.scheduleHour,
                                    minute = uiState.settings.scheduleMinute,
                                    wifiOnly = uiState.settings.wifiOnlyBackup
                                )
                            },
                            onUpdateScheduleTime = { hour, minute ->
                                viewModel.updateBackupPreferences(
                                    autoGalleryEnabled = uiState.settings.autoGalleryBackupEnabled,
                                    dailyScheduleEnabled = uiState.settings.dailyScheduleEnabled,
                                    hour = hour,
                                    minute = minute,
                                    wifiOnly = uiState.settings.wifiOnlyBackup
                                )
                            },
                            onUpdateWifiOnly = { wifiOnly ->
                                viewModel.updateBackupPreferences(
                                    autoGalleryEnabled = uiState.settings.autoGalleryBackupEnabled,
                                    dailyScheduleEnabled = uiState.settings.dailyScheduleEnabled,
                                    hour = uiState.settings.scheduleHour,
                                    minute = uiState.settings.scheduleMinute,
                                    wifiOnly = wifiOnly
                                )
                            },
                            onRunBackupNow = viewModel::runBackupNow,
                            onTogglePauseTransfer = viewModel::togglePauseTransfer,
                            onRetryTransfer = viewModel::retryTransfer,
                            onClearCompletedTransfers = viewModel::clearCompletedTransfers,
                            onOpenSettings = { showSettingsSheet = true }
                        )
                    }
                }
            }
        }
    }

    if (showSettingsSheet) {
        SettingsAndUpdateSheet(
            uiState = uiState,
            onDismiss = { showSettingsSheet = false },
            onSaveAutoUpdateConfig = viewModel::saveAutoUpdateConfig,
            onCheckUpdatesNow = { viewModel.checkGitHubUpdates(silent = false) },
            onDownloadUpdate = viewModel::downloadAndPrepareUpdate,
            onInstallApkInPlace = onInstallUpdateApk,
            onSaveBackendUrl = viewModel::saveBackendUrl,
            onVerifyEmail = viewModel::verifyCurrentUserEmail,
            onLogout = viewModel::logout
        )
    }
}
