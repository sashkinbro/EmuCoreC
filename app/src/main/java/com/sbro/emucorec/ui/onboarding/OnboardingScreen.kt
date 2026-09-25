@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.sbro.emucorec.ui.onboarding

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import com.sbro.emucorec.core.InstallStateBus
import com.sbro.emucorec.data.AppPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sbro.emucorec.R
import com.sbro.emucorec.core.FirmwareKind
import com.sbro.emucorec.core.Ps3StorageLocation
import com.sbro.emucorec.ui.common.rememberDebouncedClick
import com.sbro.emucorec.ui.theme.ScreenHorizontalPadding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.sbro.emucorec.ui.theme.neon.neonShape
import com.sbro.emucorec.ui.theme.neon.neonChipShape
import com.sbro.emucorec.ui.theme.neon.neonButtonShape

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    firmwareInstalled: Boolean,
    onInstallFirmware: () -> Unit,
    onInstallDownloadedFirmware: (String) -> Unit = {},
    firmwareDownloadViewModel: FirmwareDownloadViewModel = viewModel(),
    viewModel: OnboardingViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val downloadState by firmwareDownloadViewModel.state.collectAsState()
    val pagerState = rememberPagerState(pageCount = { uiState.totalPages })
    val scope = rememberCoroutineScope()
    var isCompleting by remember { mutableStateOf(false) }
    val folderPickerFailedMessage = stringResource(R.string.folder_picker_failed)

    val preferences = remember(context) { AppPreferences(context) }
    var selectedGamesFolder by remember { mutableStateOf(preferences.gameDirectories.firstOrNull()) }
    val gamesFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            preferences.addGameDirectory(uri.toString())
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            selectedGamesFolder = uri.toString()
            InstallStateBus.notifyCompleted()
        }
    }

    LaunchedEffect(uiState.currentPage) {
        if (pagerState.currentPage != uiState.currentPage) {
            pagerState.animateScrollToPage(uiState.currentPage)
        }
    }
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != uiState.currentPage) {
            viewModel.setCurrentPage(pagerState.currentPage)
        }
    }
    LaunchedEffect(downloadState.status, downloadState.resultFileUri) {
        val resultUri = downloadState.resultFileUri
        if (downloadState.status == FirmwareDownloadStatus.Completed && resultUri != null) {
            onInstallDownloadedFirmware(resultUri)
            firmwareDownloadViewModel.consumeResult()
        }
    }
    LaunchedEffect(uiState.storageErrorMessage) {
        if (uiState.storageErrorMessage != null) {
            Toast.makeText(context, folderPickerFailedMessage, Toast.LENGTH_SHORT).show()
            viewModel.consumeStorageError()
        }
    }

    val installFirmwareClick = rememberDebouncedClick(onClick = onInstallFirmware)
    val goToPage: (Int) -> Unit = { page ->
        val targetPage = page.coerceIn(0, uiState.totalPages - 1)
        scope.launch {
            pagerState.animateScrollToPage(targetPage)
            viewModel.setCurrentPage(targetPage)
        }
    }
    val backClick = { goToPage(pagerState.currentPage - 1) }
    val nextClick = { goToPage(pagerState.currentPage + 1) }
    val canComplete = uiState.canContinue &&
        firmwareInstalled &&
        !selectedGamesFolder.isNullOrBlank() &&
        !uiState.storageChangeInProgress
    val completeClick = rememberDebouncedClick {
        if (isCompleting || !canComplete) return@rememberDebouncedClick
        isCompleting = true
        scope.launch {
            delay(280)
            viewModel.completeOnboarding()
            onComplete()
        }
    }

    val contentAlpha by animateFloatAsState(
        targetValue = if (isCompleting) 0.34f else 1f,
        animationSpec = tween(280),
        label = "onboarding-content-alpha"
    )
    val contentOffset by animateFloatAsState(
        targetValue = if (isCompleting) -32f else 0f,
        animationSpec = tween(320),
        label = "onboarding-content-offset"
    )

    val backgroundMotion = rememberInfiniteTransition(label = "onboarding-background-motion")
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val orbOneOffsetX by backgroundMotion.animateFloat(
        initialValue = -18f,
        targetValue = 42f,
        animationSpec = infiniteRepeatable(animation = tween(5200), repeatMode = RepeatMode.Reverse),
        label = "orb-one-offset-x"
    )
    val orbOneOffsetY by backgroundMotion.animateFloat(
        initialValue = -12f,
        targetValue = 34f,
        animationSpec = infiniteRepeatable(animation = tween(6100), repeatMode = RepeatMode.Reverse),
        label = "orb-one-offset-y"
    )
    val orbTwoOffsetX by backgroundMotion.animateFloat(
        initialValue = 20f,
        targetValue = -56f,
        animationSpec = infiniteRepeatable(animation = tween(6800), repeatMode = RepeatMode.Reverse),
        label = "orb-two-offset-x"
    )
    val orbTwoOffsetY by backgroundMotion.animateFloat(
        initialValue = 0f,
        targetValue = 58f,
        animationSpec = infiniteRepeatable(animation = tween(5600), repeatMode = RepeatMode.Reverse),
        label = "orb-two-offset-y"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isDarkTheme) 0.58f else 0.72f),
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isDarkTheme) 0.70f else 0.88f)
                    )
                )
            )
    ) {
        Box(
            modifier = Modifier
                .padding(start = 28.dp)
                .size(180.dp)
                .graphicsLayer {
                    translationX = orbOneOffsetX
                    translationY = orbOneOffsetY
                }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isDarkTheme) 0.13f else 0.10f))
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 96.dp, end = 20.dp)
                .size(140.dp)
                .graphicsLayer {
                    translationX = orbTwoOffsetX
                    translationY = orbTwoOffsetY
                }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiary.copy(alpha = if (isDarkTheme) 0.12f else 0.10f))
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .graphicsLayer {
                    alpha = contentAlpha
                    translationY = contentOffset
                }
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val isSetupPage = page == uiState.totalPages - 1
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                        .navigationBarsPadding()
                        .padding(
                            start = ScreenHorizontalPadding,
                            end = ScreenHorizontalPadding,
                            top = if (isSetupPage) 12.dp else 48.dp,
                            bottom = if (isSetupPage) 144.dp else 160.dp
                        ),
                    verticalArrangement = if (isSetupPage) Arrangement.Top else Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (page) {
                        0 -> OnboardingHero(
                            icon = Icons.Rounded.Gamepad,
                            title = stringResource(R.string.onboarding_page_1_title),
                            subtitle = stringResource(R.string.onboarding_page_1_body)
                        )
                        1 -> OnboardingHero(
                            icon = Icons.Rounded.SmartDisplay,
                            title = stringResource(R.string.onboarding_page_2_title),
                            subtitle = stringResource(R.string.onboarding_page_2_body)
                        )
                        2 -> OnboardingHero(
                            icon = Icons.AutoMirrored.Rounded.LibraryBooks,
                            title = stringResource(R.string.onboarding_page_3_title),
                            subtitle = stringResource(R.string.onboarding_page_3_body)
                        )
                        else -> OnboardingSetupContent(
                            storagePath = uiState.storagePath,
                            storageLocations = uiState.storageLocations,
                            storageChangeInProgress = uiState.storageChangeInProgress,
                            selectStorageLocation = viewModel::selectStorageLocation,
                            firmwareInstalled = firmwareInstalled,
                            installFirmware = installFirmwareClick,
                            downloadState = downloadState,
                            startFirmwareDownload = firmwareDownloadViewModel::start,
                            cancelFirmwareDownload = firmwareDownloadViewModel::cancel,
                            selectedGamesFolder = selectedGamesFolder,
                            onPickGamesFolder = { gamesFolderPicker.launch(null) }
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = ScreenHorizontalPadding, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OnboardingIndicator(
                    currentPage = pagerState.currentPage,
                    totalPages = uiState.totalPages
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (pagerState.currentPage > 0) {
                            OutlinedButton(
                                onClick = backClick,
                                shape = neonShape(12.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.onboarding_back))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        if (pagerState.currentPage < uiState.totalPages - 1) {
                            Button(
                                onClick = nextClick,
                                shape = neonShape(12.dp)
                            ) {
                                Text(stringResource(R.string.onboarding_next))
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
                            }
                        } else {
                            Button(
                                onClick = completeClick,
                                enabled = canComplete,
                                shape = neonShape(12.dp)
                            ) {
                                Text(stringResource(R.string.onboarding_get_started))
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = isCompleting,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.96f, animationSpec = tween(220)),
            exit = fadeOut(tween(120)) + scaleOut(targetScale = 1.02f, animationSpec = tween(120))
        ) {
            Surface(
                shape = neonShape(28.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                tonalElevation = 12.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp
                    )
                    Text(
                        text = stringResource(R.string.onboarding_get_started),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingHero(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(112.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.82f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)),
            shadowElevation = 8.dp
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(36.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.displayLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.sp
            ),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
    }
}

@Composable
private fun OnboardingSetupContent(
    storagePath: String,
    storageLocations: List<Ps3StorageLocation>,
    storageChangeInProgress: Boolean,
    selectStorageLocation: (String) -> Unit,
    firmwareInstalled: Boolean,
    installFirmware: () -> Unit,
    downloadState: FirmwareDownloadState,
    startFirmwareDownload: (FirmwareKind) -> Unit,
    cancelFirmwareDownload: () -> Unit,
    selectedGamesFolder: String?,
    onPickGamesFolder: () -> Unit
) {
    val context = LocalContext.current
    val isDownloading = downloadState.status == FirmwareDownloadStatus.Running
    val downloadButton = stringResource(R.string.onboarding_firmware_download)
    val cancelDownloadButton = stringResource(R.string.onboarding_firmware_cancel_download)
    var firmwareInfoVisible by rememberSaveable { mutableStateOf(false) }
    var storagePickerVisible by rememberSaveable { mutableStateOf(false) }

    val baseDownloadStatus = firmwareDownloadStatusText(FirmwareKind.Base, downloadState)
    val baseDownloadProgress = downloadState.progress.takeIf {
        downloadState.kind == FirmwareKind.Base && downloadState.status == FirmwareDownloadStatus.Running
    }

    val displayFolderName = remember(selectedGamesFolder) {
        selectedGamesFolder?.let { raw ->
            com.sbro.emucorec.core.DocumentPathResolver.getDisplayName(context, raw)
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var hasStoragePermission by remember {
        mutableStateOf(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                android.os.Environment.isExternalStorageManager()
            } else {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasStoragePermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    android.os.Environment.isExternalStorageManager()
                } else {
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.READ_EXTERNAL_STORAGE
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val requestStoragePermission = {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            } else {
                val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            }
        }.onFailure {
            runCatching {
                val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            }
        }
    }

    LaunchedEffect(downloadState.status) {
        if (downloadState.status == FirmwareDownloadStatus.Completed) {
            firmwareInfoVisible = false
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.onboarding_page_4_title),
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.sp
            ),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_page_4_body),
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 23.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        Spacer(modifier = Modifier.height(18.dp))

        SetupCard(
            icon = Icons.Rounded.Memory,
            title = stringResource(R.string.onboarding_storage_title),
            description = storagePath,
            status = if (storageChangeInProgress) {
                stringResource(R.string.settings_storage_migrating)
            } else {
                stringResource(R.string.onboarding_status_ready)
            },
            statusColor = if (storageChangeInProgress) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
            onClick = { storagePickerVisible = true }
        )
        if (storageLocations.size > 1) {
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                storageLocations.forEachIndexed { index, location ->
                    OnboardingStorageChip(
                        location = location,
                        index = index,
                        enabled = !storageChangeInProgress,
                        onSelected = { selectStorageLocation(location.rootPath) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_storage_change_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(9.dp))

        SetupCard(
            icon = Icons.Rounded.Memory,
            title = stringResource(R.string.onboarding_firmware_title),
            description = stringResource(R.string.onboarding_firmware_desc),
            status = if (firmwareInstalled) {
                stringResource(R.string.onboarding_status_ready)
            } else {
                stringResource(R.string.onboarding_status_install_firmware)
            },
            statusColor = if (firmwareInstalled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            onClick = {
                firmwareInfoVisible = false
                installFirmware()
            },
            secondaryActionLabel = if (firmwareInstalled) null else if (baseDownloadProgress != null) cancelDownloadButton else downloadButton,
            secondaryActionEnabled = (!isDownloading || downloadState.kind == FirmwareKind.Base) && !storageChangeInProgress,
            onSecondaryAction = {
                if (baseDownloadProgress != null) cancelFirmwareDownload() else startFirmwareDownload(FirmwareKind.Base)
            },
            onInfoClick = { firmwareInfoVisible = true },
            downloadProgress = baseDownloadProgress,
            downloadStatus = baseDownloadStatus
        )

        Spacer(modifier = Modifier.height(9.dp))

        SetupCard(
            icon = Icons.Rounded.Storage,
            title = stringResource(R.string.onboarding_permission_storage_title),
            description = stringResource(R.string.onboarding_permission_storage_desc),
            status = if (hasStoragePermission) {
                stringResource(R.string.onboarding_permission_storage_granted)
            } else {
                stringResource(R.string.onboarding_permission_storage_grant)
            },
            statusColor = if (hasStoragePermission) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            onClick = {
                if (!hasStoragePermission) {
                    requestStoragePermission()
                }
            }
        )

        Spacer(modifier = Modifier.height(9.dp))

        SetupCard(
            icon = androidx.compose.material.icons.Icons.Rounded.Gamepad,
            title = stringResource(R.string.onboarding_games_folder_title),
            description = displayFolderName ?: stringResource(R.string.onboarding_games_folder_desc),
            status = if (displayFolderName != null) {
                stringResource(R.string.onboarding_status_ready)
            } else {
                stringResource(R.string.onboarding_status_choose_folder)
            },
            statusColor = if (displayFolderName != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            onClick = onPickGamesFolder
        )

        FirmwareDownloadInfoDialog(
            visible = firmwareInfoVisible,
            onDismiss = { firmwareInfoVisible = false }
        )
        OnboardingStorageDialog(
            visible = storagePickerVisible,
            storageLocations = storageLocations,
            enabled = !storageChangeInProgress,
            onSelected = { location ->
                if (!storageChangeInProgress) {
                    selectStorageLocation(location.rootPath)
                    storagePickerVisible = false
                }
            },
            onDismiss = { storagePickerVisible = false }
        )
    }
}

@Composable
private fun OnboardingStorageChip(
    location: Ps3StorageLocation,
    index: Int,
    enabled: Boolean = true,
    onSelected: () -> Unit
) {
    FilterChip(shape = neonChipShape(), 
        selected = location.selected,
        enabled = enabled,
        onClick = onSelected,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            labelColor = MaterialTheme.colorScheme.onSurface,
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
        ),
        label = { Text(storageLocationLabel(location, index)) }
    )
}

@Composable
private fun OnboardingStorageDialog(
    visible: Boolean,
    storageLocations: List<Ps3StorageLocation>,
    enabled: Boolean,
    onSelected: (Ps3StorageLocation) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .widthIn(max = 560.dp),
            shape = neonShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 10.dp
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.onboarding_storage_choose_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (storageLocations.size > 1) {
                        stringResource(R.string.settings_storage_change_note)
                    } else {
                        stringResource(R.string.onboarding_storage_only_default)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                storageLocations.forEachIndexed { index, location ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = neonShape(18.dp),
                        color = if (location.selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (location.selected) {
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.42f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)
                            }
                        ),
                        onClick = { if (enabled) onSelected(location) }
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = storageLocationLabel(location, index),
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = if (location.selected) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            Text(
                                text = location.ps3Path,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (location.selected) {
                                    MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.common_close))
                }
            }
        }
    }
}

@Composable
private fun storageLocationLabel(location: Ps3StorageLocation, index: Int): String = when {
    location.removable -> stringResource(R.string.settings_storage_location_sd)
    index == 0 -> stringResource(R.string.settings_storage_location_internal)
    else -> stringResource(R.string.settings_storage_location_external)
}

@Composable
private fun firmwareDownloadStatusText(
    kind: FirmwareKind,
    state: FirmwareDownloadState
): String? {
    if (state.kind != kind) return null
    return when (state.status) {
        FirmwareDownloadStatus.Running -> {
            val percent = (state.progress * 100f).toInt().coerceIn(0, 100)
            stringResource(R.string.onboarding_firmware_downloading, percent)
        }

        FirmwareDownloadStatus.Failed -> stringResource(R.string.onboarding_firmware_download_failed)
        else -> null
    }
}

@Composable
private fun SetupCard(
    icon: ImageVector,
    title: String,
    description: String,
    status: String,
    statusColor: Color,
    onClick: () -> Unit,
    secondaryActionLabel: String? = null,
    secondaryActionEnabled: Boolean = true,
    onSecondaryAction: (() -> Unit)? = null,
    onInfoClick: (() -> Unit)? = null,
    downloadProgress: Float? = null,
    downloadStatus: String? = null
) {
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        shape = neonShape(26.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        tonalElevation = 2.dp,
        onClick = onClick,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDarkTheme) 0.45f else 0.7f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(neonShape(16.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            if (secondaryActionLabel != null && onSecondaryAction != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusPill(
                        status = status,
                        statusColor = statusColor,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onInfoClick != null) {
                            FirmwareInfoButton(onClick = onInfoClick)
                        } else {
                            Spacer(modifier = Modifier.width(48.dp))
                        }
                        Button(shape = neonButtonShape(), 
                            onClick = onSecondaryAction,
                            enabled = secondaryActionEnabled,
                            modifier = Modifier.align(Alignment.CenterVertically),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(secondaryActionLabel)
                        }
                    }
                }
            } else {
                StatusPill(
                    status = status,
                    statusColor = statusColor
                )
            }

            if (downloadProgress != null) {
                Spacer(modifier = Modifier.height(8.dp))
                downloadStatus?.let { statusText ->
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (downloadStatus != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = downloadStatus,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun FirmwareInfoButton(onClick: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "firmware-info-pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "firmware-info-scale"
    )
    val alpha by pulse.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "firmware-info-alpha"
    )
    Surface(
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
        contentColor = MaterialTheme.colorScheme.primary,
        onClick = onClick
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null,
            modifier = Modifier
                .padding(12.dp)
                .size(20.dp)
        )
    }
}

@Composable
private fun FirmwareDownloadInfoDialog(
    visible: Boolean,
    onDismiss: () -> Unit
) {
    if (!visible) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            val scrollState = rememberScrollState()

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .heightIn(max = maxHeight),
                shape = neonShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    Text(
                        text = stringResource(R.string.onboarding_firmware_info_title),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.onboarding_firmware_info_body),
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.install_dialog_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(
    status: String,
    statusColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = statusColor.copy(alpha = 0.12f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Text(
                text = status,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = statusColor,
                maxLines = 3,
                overflow = TextOverflow.Clip
            )
        }
    }
}

@Composable
private fun OnboardingIndicator(
    currentPage: Int,
    totalPages: Int
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(totalPages) { index ->
            val selected = index == currentPage
            val width by animateFloatAsState(
                targetValue = if (selected) 28f else 8f,
                animationSpec = tween(250),
                label = "onboarding-indicator"
            )
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(width.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                    )
            )
        }
    }
}
