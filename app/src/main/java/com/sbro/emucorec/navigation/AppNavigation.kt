package com.sbro.emucorec.navigation

import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sbro.emucorec.R
import com.sbro.emucorec.core.DocumentPathResolver
import com.sbro.emucorec.core.ArchiveContentInstaller
import com.sbro.emucorec.core.EmulatorStorage
import com.sbro.emucorec.core.InstallStateBus
import com.sbro.emucorec.core.Ps3LaunchBridge
import com.sbro.emucorec.ui.achievements.AchievementsScreen
import com.sbro.emucorec.data.AppPreferences
import com.sbro.emucorec.ui.catalog.CatalogScreen
import com.sbro.emucorec.ui.detail.GameDetailScreen
import com.sbro.emucorec.ui.feedback.FeedbackScreen
import com.sbro.emucorec.ui.gamemanager.GameManagerScreen
import com.sbro.emucorec.ui.library.LibraryScreen
import com.sbro.emucorec.ui.onboarding.OnboardingScreen
import com.sbro.emucorec.ui.patches.PatchesScreen
import com.sbro.emucorec.ui.playtime.PlayTimeScreen
import com.sbro.emucorec.ui.profile.ProfileScreen
import com.sbro.emucorec.ui.saves.SaveDataScreen
import com.sbro.emucorec.ui.settings.AppLanguageScreen
import com.sbro.emucorec.ui.settings.GpuDriverScreen
import com.sbro.emucorec.ui.settings.SettingsScreen
import com.sbro.emucorec.ui.settings.SettingsTab
import com.sbro.emucorec.ui.settings.SettingsViewModel
import com.sbro.emucorec.ui.settings.TouchControlsEditorScreen
import com.sbro.emucorec.ui.settings.settingsTabFromRoute
import com.sbro.emucorec.ui.discord.DiscordScreen
import com.sbro.emucorec.ui.setup.InstallGameChoiceDialog
import com.sbro.emucorec.ui.setup.SetupInstallDialog
import com.sbro.emucorec.ui.setup.SetupInstallViewModel
import com.sbro.emucorec.ui.setup.SetupScreen

private const val ROUTE_ONBOARDING = "onboarding"
private const val ROUTE_SETUP = "setup"
private const val ROUTE_LIBRARY = "library"
private const val ROUTE_CATALOG = "catalog"
private const val ROUTE_GAME_MANAGER = "game-manager"
private const val ROUTE_GAME_MANAGER_WITH_TITLE = "game-manager/{titleId}"
private const val ROUTE_PATCHES = "patches"
private const val ROUTE_PATCHES_WITH_TITLE = "patches/{titleId}"
private const val ROUTE_PLAY_TIME = "play-time"
private const val ROUTE_PLAY_TIME_WITH_TITLE = "play-time/{titleId}"
private const val ROUTE_ACHIEVEMENTS = "achievements"
private const val ROUTE_ACHIEVEMENTS_WITH_TITLE = "achievements/{titleId}"
private const val ROUTE_SAVE_MANAGER = "save-manager"
private const val ROUTE_SAVE_MANAGER_WITH_TITLE = "save-manager/{titleId}"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_PROFILE = "profile"
private const val ROUTE_DISCORD = "discord"
private const val ROUTE_FEEDBACK = "feedback"
private const val ROUTE_SETTINGS_WITH_TAB = "settings/{tab}"
private const val ROUTE_APP_LANGUAGE = "app-language"
private const val ROUTE_GPU_DRIVER = "gpu-driver"
private const val ROUTE_TOUCH_CONTROLS_EDITOR = "touch-controls-editor"
private const val ROUTE_TOUCH_CONTROLS_EDITOR_WITH_TITLE = "touch-controls-editor/{titleId}"
private const val ROUTE_GPU_DRIVER_WITH_TITLE = "gpu-driver/{titleId}"
private const val ROUTE_DETAIL_PREFIX = "detail"
private const val ROUTE_CATALOG_DETAIL_PREFIX = "catalog-detail"

private fun settingsRoute(tab: SettingsTab = SettingsTab.General): String = "$ROUTE_SETTINGS/${tab.name.lowercase()}"
private fun saveManagerRoute(titleId: String? = null): String =
    titleId?.takeIf(String::isNotBlank)?.let { "$ROUTE_SAVE_MANAGER/$it" } ?: ROUTE_SAVE_MANAGER
private fun gameManagerRoute(titleId: String? = null): String =
    titleId?.takeIf(String::isNotBlank)?.let { "$ROUTE_GAME_MANAGER/$it" } ?: ROUTE_GAME_MANAGER
private fun patchesRoute(titleId: String? = null): String =
    titleId?.takeIf(String::isNotBlank)?.let { "$ROUTE_PATCHES/$it" } ?: ROUTE_PATCHES
private fun playTimeRoute(titleId: String? = null): String =
    titleId?.takeIf(String::isNotBlank)?.let { "$ROUTE_PLAY_TIME/$it" } ?: ROUTE_PLAY_TIME
private fun achievementsRoute(titleId: String? = null): String =
    titleId?.takeIf(String::isNotBlank)?.let { "$ROUTE_ACHIEVEMENTS/${Uri.encode(it)}" } ?: ROUTE_ACHIEVEMENTS
private fun gpuDriverRoute(titleId: String? = null): String =
    titleId?.takeIf(String::isNotBlank)?.let { "$ROUTE_GPU_DRIVER/${Uri.encode(it)}" } ?: ROUTE_GPU_DRIVER

@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    val preferences = remember(context) { AppPreferences(context) }
    val installViewModel: SetupInstallViewModel = viewModel()
    val settingsViewModel: SettingsViewModel = viewModel()
    val installUiState by installViewModel.uiState.collectAsState()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    var firmwareInstalled by remember(context) { mutableStateOf(EmulatorStorage.hasInstalledFirmware(context)) }
    val startDestination = if (preferences.onboardingCompleted) ROUTE_LIBRARY else ROUTE_ONBOARDING
    stringResource(R.string.core_install_firmware_failed)
    val unsupportedFirmware = stringResource(R.string.core_install_firmware_unsupported)
    stringResource(R.string.core_install_pkg_failed)
    val unsupportedContent = stringResource(R.string.core_install_pkg_unsupported)
    val gameLaunchFailed = stringResource(R.string.game_launch_failed)
    val launchRequiresFirmwareMessage = stringResource(R.string.game_launch_requires_firmware)
    val systemMenuLaunchFailed = stringResource(R.string.system_menu_launch_failed)
    var showInstallChoiceDialog by rememberSaveable { mutableStateOf(false) }

    val firmwarePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val fileName = DocumentPathResolver.getDisplayName(context, uri.toString())
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension != "pup") {
            Toast.makeText(context, unsupportedFirmware, Toast.LENGTH_SHORT).show()
        } else {
            installViewModel.installFirmware(uri.toString())
        }
    }

    val licensePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val fileName = DocumentPathResolver.getDisplayName(context, uri.toString())
        if (!fileName.endsWith(".rap", ignoreCase = true)) {
            Toast.makeText(context, R.string.core_install_license_unsupported, Toast.LENGTH_SHORT).show()
        } else {
            installViewModel.installLicense(uri.toString())
        }
    }

    val pkgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val unsupported = uris.firstOrNull { uri ->
            !ArchiveContentInstaller.isSupportedSelectionName(
                DocumentPathResolver.getDisplayName(context, uri.toString())
            )
        }
        if (unsupported != null) {
            Toast.makeText(context, unsupportedContent, Toast.LENGTH_SHORT).show()
        } else {
            installViewModel.installContent(uris.map(Uri::toString))
        }
    }

    val openFirmwareInstall = { firmwarePicker.launch(arrayOf("*/*")) }
    val openLicenseInstall = { licensePicker.launch(arrayOf("*/*")) }
    val openPkgInstall = { pkgPicker.launch(arrayOf("*/*")) }
    val openInstallChoiceDialog = {
        showInstallChoiceDialog = true
    }

    LaunchedEffect(context) {
        InstallStateBus.events.collect {
            firmwareInstalled = EmulatorStorage.hasInstalledFirmware(context)
        }
    }


    val launchInstalledGame: (String) -> Unit = { titleId ->
        when (Ps3LaunchBridge.launchInstalledTitle(context, titleId)) {
            Ps3LaunchBridge.LaunchResult.Success -> Unit
            Ps3LaunchBridge.LaunchResult.MissingFirmware -> Toast.makeText(context, launchRequiresFirmwareMessage, Toast.LENGTH_SHORT).show()
            Ps3LaunchBridge.LaunchResult.Failure -> Toast.makeText(context, gameLaunchFailed, Toast.LENGTH_SHORT).show()
        }
    }
    val launchSystemMenu = {
        when (Ps3LaunchBridge.launchSystemMenu(context)) {
            Ps3LaunchBridge.LaunchResult.Success -> Unit
            Ps3LaunchBridge.LaunchResult.MissingFirmware -> Toast.makeText(context, launchRequiresFirmwareMessage, Toast.LENGTH_SHORT).show()
            Ps3LaunchBridge.LaunchResult.Failure -> Toast.makeText(context, systemMenuLaunchFailed, Toast.LENGTH_SHORT).show()
        }
    }
    val navigateGameManager: (String?) -> Unit = { titleId ->
        navController.navigate(gameManagerRoute(titleId)) { launchSingleTop = true }
    }
    val navigatePatches: (String?) -> Unit = { titleId ->
        navController.navigate(patchesRoute(titleId)) { launchSingleTop = true }
    }
    val navigatePlayTime: (String?) -> Unit = { titleId ->
        navController.navigate(playTimeRoute(titleId)) { launchSingleTop = true }
    }
    val navigateAchievements: (String?) -> Unit = { titleId ->
        navController.navigate(achievementsRoute(titleId)) { launchSingleTop = true }
    }
    val navigateAchievementsRoot = { navigateAchievements(null) }
    val navigateProfile = {
        navController.navigate(ROUTE_PROFILE) { launchSingleTop = true }
    }
    val navigateDiscord = {
        navController.navigate(ROUTE_DISCORD) { launchSingleTop = true }
    }
    val navigateFeedback = {
        navController.navigate(ROUTE_FEEDBACK) { launchSingleTop = true }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            enterTransition = { appScreenEnterTransition() },
            exitTransition = { appScreenExitTransition() },
            popEnterTransition = { appScreenPopEnterTransition() },
            popExitTransition = { appScreenPopExitTransition() },
            sizeTransform = { SizeTransform(clip = false) }
        ) {
            composable(
                ROUTE_ONBOARDING
            ) {
                OnboardingScreen(
                    firmwareInstalled = firmwareInstalled,
                    onInstallFirmware = openFirmwareInstall,
                    onInstallDownloadedFirmware = installViewModel::installFirmware,
                    onComplete = {
                        navController.navigate(ROUTE_LIBRARY) {
                            popUpTo(ROUTE_ONBOARDING) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(
                ROUTE_SETUP
            ) {
                val navigateHome = {
                    navController.navigate(ROUTE_LIBRARY) {
                        launchSingleTop = true
                        popUpTo(ROUTE_LIBRARY) { inclusive = false }
                    }
                }
                AdaptiveShell(
                    selected = PrimaryDestination.Setup,
                    onNavigateSetup = { },
                    onNavigateLibrary = {
                        navController.navigate(ROUTE_LIBRARY) { launchSingleTop = true }
                    },
                    onNavigateGameManager = { navigateGameManager(null) },
                    onNavigatePatches = { navigatePatches(null) },
                    onNavigatePlayTime = { navigatePlayTime(null) },
                    onNavigateAchievements = navigateAchievementsRoot,
                    onNavigateSaveData = {
                        navController.navigate(saveManagerRoute()) { launchSingleTop = true }
                    },
                    onNavigateSearch = {
                        navController.navigate(ROUTE_CATALOG) { launchSingleTop = true }
                    },
                    onNavigateSettings = {
                        navController.navigate(settingsRoute()) { launchSingleTop = true }
                    },
                    onNavigateProfile = navigateProfile,
                    onNavigateDiscord = navigateDiscord,
                    onNavigateFeedback = navigateFeedback,
                    onBackClick = navigateHome,
                    onLaunchSystemMenu = launchSystemMenu,
                    onInstallContent = openInstallChoiceDialog
                ) {
                    SetupScreen(
                        ps3RootPath = EmulatorStorage.ps3Root(context).absolutePath,
                        onBackClick = navigateHome,
                        onInstallLicense = openLicenseInstall,
                        onInstallPkg = openPkgInstall
                    )
                }
            }
            composable(
                ROUTE_LIBRARY
            ) {
                AdaptiveShell(
                    selected = PrimaryDestination.Library,
                    onNavigateSetup = {
                        navController.navigate(ROUTE_SETUP) { launchSingleTop = true }
                    },
                    onNavigateLibrary = { },
                    onNavigateGameManager = { navigateGameManager(null) },
                    onNavigatePatches = { navigatePatches(null) },
                    onNavigatePlayTime = { navigatePlayTime(null) },
                    onNavigateAchievements = navigateAchievementsRoot,
                    onNavigateSaveData = {
                        navController.navigate(saveManagerRoute()) { launchSingleTop = true }
                    },
                    onNavigateSearch = {
                        navController.navigate(ROUTE_CATALOG) { launchSingleTop = true }
                    },
                    onNavigateSettings = {
                        navController.navigate(settingsRoute()) { launchSingleTop = true }
                    },
                    onNavigateProfile = navigateProfile,
                    onNavigateDiscord = navigateDiscord,
                    onNavigateFeedback = navigateFeedback,
                    onLaunchSystemMenu = launchSystemMenu,
                    onInstallContent = openInstallChoiceDialog
                ) { openDrawer ->
                    LibraryScreen(
                        onLaunchGame = launchInstalledGame,
                        onOpenSaveManager = { titleId ->
                            navController.navigate(saveManagerRoute(titleId)) { launchSingleTop = true }
                        },
                        onOpenGameManager = { titleId -> navigateGameManager(titleId) },
                        onOpenPatches = { titleId -> navigatePatches(titleId) },
                        onOpenPlayTime = { titleId -> navigatePlayTime(titleId) },
                        onOpenAchievements = { titleId -> navigateAchievements(titleId) },
                        onOpenCatalogEntry = { igdbId ->
                            navController.navigate("$ROUTE_CATALOG_DETAIL_PREFIX/$igdbId") { launchSingleTop = true }
                        },
                        onMenuClick = openDrawer
                    )
                }
            }
            composable(
                ROUTE_CATALOG
            ) {
                val navigateHome = {
                    navController.navigate(ROUTE_LIBRARY) {
                        launchSingleTop = true
                        popUpTo(ROUTE_LIBRARY) { inclusive = false }
                    }
                }
                AdaptiveShell(
                    selected = PrimaryDestination.Search,
                    onNavigateSetup = {
                        navController.navigate(ROUTE_SETUP) { launchSingleTop = true }
                    },
                    onNavigateLibrary = {
                        navController.navigate(ROUTE_LIBRARY) { launchSingleTop = true }
                    },
                    onNavigateGameManager = { navigateGameManager(null) },
                    onNavigatePatches = { navigatePatches(null) },
                    onNavigatePlayTime = { navigatePlayTime(null) },
                    onNavigateAchievements = navigateAchievementsRoot,
                    onNavigateSaveData = {
                        navController.navigate(saveManagerRoute()) { launchSingleTop = true }
                    },
                    onNavigateSearch = { },
                    onNavigateSettings = {
                        navController.navigate(settingsRoute()) { launchSingleTop = true }
                    },
                    onNavigateProfile = navigateProfile,
                    onNavigateDiscord = navigateDiscord,
                    onNavigateFeedback = navigateFeedback,
                    onBackClick = navigateHome,
                    onLaunchSystemMenu = launchSystemMenu,
                    onInstallContent = openInstallChoiceDialog
                ) {
                    CatalogScreen(
                        onGameClick = { igdbId -> navController.navigate("$ROUTE_CATALOG_DETAIL_PREFIX/$igdbId") },
                        onBackClick = navigateHome
                    )
                }
            }
            composable(ROUTE_SETTINGS) {
                val navigateHome = {
                    navController.navigate(ROUTE_LIBRARY) {
                        launchSingleTop = true
                        popUpTo(ROUTE_LIBRARY) { inclusive = false }
                    }
                }
                AdaptiveShell(
                    selected = PrimaryDestination.Settings,
                    onNavigateSetup = {
                        navController.navigate(ROUTE_SETUP) { launchSingleTop = true }
                    },
                    onNavigateLibrary = {
                        navController.navigate(ROUTE_LIBRARY) { launchSingleTop = true }
                    },
                    onNavigateGameManager = { navigateGameManager(null) },
                    onNavigatePatches = { navigatePatches(null) },
                    onNavigatePlayTime = { navigatePlayTime(null) },
                    onNavigateAchievements = navigateAchievementsRoot,
                    onNavigateSaveData = {
                        navController.navigate(saveManagerRoute()) { launchSingleTop = true }
                    },
                    onNavigateSearch = {
                        navController.navigate(ROUTE_CATALOG) { launchSingleTop = true }
                    },
                    onNavigateSettings = { },
                    onNavigateProfile = navigateProfile,
                    onNavigateDiscord = navigateDiscord,
                    onNavigateFeedback = navigateFeedback,
                    onBackClick = navigateHome,
                    onLaunchSystemMenu = launchSystemMenu,
                    onInstallContent = openInstallChoiceDialog
                ) {
                    SettingsScreen(
                        initialTab = SettingsTab.General,
                        onBackClick = navigateHome,
                        onOpenLanguageSettings = {
                            navController.navigate(ROUTE_APP_LANGUAGE) { launchSingleTop = true }
                        },
                        onOpenGpuDriverSettings = {
                            navController.navigate(ROUTE_GPU_DRIVER) { launchSingleTop = true }
                        },
                        onOpenTouchControlsEditor = {
                            navController.navigate(ROUTE_TOUCH_CONTROLS_EDITOR) { launchSingleTop = true }
                        },
                        viewModel = settingsViewModel
                    )
                }
            }
            composable(ROUTE_SETTINGS_WITH_TAB) { entry ->
                val navigateHome = {
                    navController.navigate(ROUTE_LIBRARY) {
                        launchSingleTop = true
                        popUpTo(ROUTE_LIBRARY) { inclusive = false }
                    }
                }
                AdaptiveShell(
                    selected = PrimaryDestination.Settings,
                    onNavigateSetup = {
                        navController.navigate(ROUTE_SETUP) { launchSingleTop = true }
                    },
                    onNavigateLibrary = {
                        navController.navigate(ROUTE_LIBRARY) { launchSingleTop = true }
                    },
                    onNavigateGameManager = { navigateGameManager(null) },
                    onNavigatePatches = { navigatePatches(null) },
                    onNavigatePlayTime = { navigatePlayTime(null) },
                    onNavigateAchievements = navigateAchievementsRoot,
                    onNavigateSaveData = {
                        navController.navigate(saveManagerRoute()) { launchSingleTop = true }
                    },
                    onNavigateSearch = {
                        navController.navigate(ROUTE_CATALOG) { launchSingleTop = true }
                    },
                    onNavigateSettings = { },
                    onNavigateProfile = navigateProfile,
                    onNavigateDiscord = navigateDiscord,
                    onNavigateFeedback = navigateFeedback,
                    onBackClick = navigateHome,
                    onLaunchSystemMenu = launchSystemMenu,
                    onInstallContent = openInstallChoiceDialog
                ) {
                    SettingsScreen(
                        initialTab = settingsTabFromRoute(entry.arguments?.getString("tab")),
                        onBackClick = navigateHome,
                        onOpenLanguageSettings = {
                            navController.navigate(ROUTE_APP_LANGUAGE) { launchSingleTop = true }
                        },
                        onOpenGpuDriverSettings = {
                            navController.navigate(ROUTE_GPU_DRIVER) { launchSingleTop = true }
                        },
                        onOpenTouchControlsEditor = {
                            navController.navigate(ROUTE_TOUCH_CONTROLS_EDITOR) { launchSingleTop = true }
                        },
                        viewModel = settingsViewModel
                    )
                }
            }
            composable(ROUTE_APP_LANGUAGE) {
                AppLanguageScreen(
                    onBackClick = { navController.popBackStack() },
                    viewModel = settingsViewModel
                )
            }
            composable(ROUTE_GPU_DRIVER) {
                GpuDriverScreen(
                    onBackClick = { navController.popBackStack() },
                    viewModel = settingsViewModel
                )
            }
            composable(ROUTE_TOUCH_CONTROLS_EDITOR) {
                TouchControlsEditorScreen(
                    onExit = { navController.popBackStack() }
                )
            }
            composable(ROUTE_TOUCH_CONTROLS_EDITOR_WITH_TITLE) { entry ->
                TouchControlsEditorScreen(
                    onExit = { navController.popBackStack() },
                    titleId = entry.arguments?.getString("titleId")
                )
            }
            composable(ROUTE_GPU_DRIVER_WITH_TITLE) { entry ->
                GpuDriverScreen(
                    onBackClick = { navController.popBackStack() },
                    targetTitleId = entry.arguments?.getString("titleId"),
                    viewModel = settingsViewModel
                )
            }
            composable(ROUTE_SAVE_MANAGER) {
                val navigateHome = {
                    navController.navigate(ROUTE_LIBRARY) {
                        launchSingleTop = true
                        popUpTo(ROUTE_LIBRARY) { inclusive = false }
                    }
                }
                AdaptiveShell(
                    selected = PrimaryDestination.SaveData,
                    onNavigateSetup = {
                        navController.navigate(ROUTE_SETUP) { launchSingleTop = true }
                    },
                    onNavigateLibrary = {
                        navController.navigate(ROUTE_LIBRARY) { launchSingleTop = true }
                    },
                    onNavigateGameManager = { navigateGameManager(null) },
                    onNavigatePatches = { navigatePatches(null) },
                    onNavigatePlayTime = { navigatePlayTime(null) },
                    onNavigateAchievements = navigateAchievementsRoot,
                    onNavigateSaveData = { },
                    onNavigateSearch = {
                        navController.navigate(ROUTE_CATALOG) { launchSingleTop = true }
                    },
                    onNavigateSettings = {
                        navController.navigate(settingsRoute()) { launchSingleTop = true }
                    },
                    onNavigateProfile = navigateProfile,
                    onNavigateDiscord = navigateDiscord,
                    onNavigateFeedback = navigateFeedback,
                    onBackClick = navigateHome,
                    onLaunchSystemMenu = launchSystemMenu,
                    onInstallContent = openInstallChoiceDialog
                ) {
                    SaveDataScreen(
                        focusTitleId = null,
                        onBackClick = navigateHome
                    )
                }
            }
            composable(ROUTE_SAVE_MANAGER_WITH_TITLE) { entry ->
                SaveDataScreen(
                    focusTitleId = entry.arguments?.getString("titleId"),
                    onBackClick = { navController.popBackStack() }
                )
            }
            composable(ROUTE_PLAY_TIME) {
                val navigateHome = {
                    navController.navigate(ROUTE_LIBRARY) {
                        launchSingleTop = true
                        popUpTo(ROUTE_LIBRARY) { inclusive = false }
                    }
                }
                AdaptiveShell(
                    selected = PrimaryDestination.PlayTime,
                    onNavigateSetup = {
                        navController.navigate(ROUTE_SETUP) { launchSingleTop = true }
                    },
                    onNavigateLibrary = {
                        navController.navigate(ROUTE_LIBRARY) { launchSingleTop = true }
                    },
                    onNavigateGameManager = { navigateGameManager(null) },
                    onNavigatePatches = { navigatePatches(null) },
                    onNavigatePlayTime = { },
                    onNavigateAchievements = navigateAchievementsRoot,
                    onNavigateSaveData = {
                        navController.navigate(saveManagerRoute()) { launchSingleTop = true }
                    },
                    onNavigateSearch = {
                        navController.navigate(ROUTE_CATALOG) { launchSingleTop = true }
                    },
                    onNavigateSettings = {
                        navController.navigate(settingsRoute()) { launchSingleTop = true }
                    },
                    onNavigateProfile = navigateProfile,
                    onNavigateDiscord = navigateDiscord,
                    onNavigateFeedback = navigateFeedback,
                    onBackClick = navigateHome,
                    onLaunchSystemMenu = launchSystemMenu,
                    onInstallContent = openInstallChoiceDialog
                ) { openDrawer ->
                    PlayTimeScreen(
                        onMenuClick = openDrawer,
                        onBackClick = navigateHome
                    )
                }
            }
            composable(ROUTE_ACHIEVEMENTS) {
                val navigateHome = {
                    navController.navigate(ROUTE_LIBRARY) {
                        launchSingleTop = true
                        popUpTo(ROUTE_LIBRARY) { inclusive = false }
                    }
                }
                AdaptiveShell(
                    selected = PrimaryDestination.Achievements,
                    onNavigateSetup = {
                        navController.navigate(ROUTE_SETUP) { launchSingleTop = true }
                    },
                    onNavigateLibrary = {
                        navController.navigate(ROUTE_LIBRARY) { launchSingleTop = true }
                    },
                    onNavigateGameManager = { navigateGameManager(null) },
                    onNavigatePatches = { navigatePatches(null) },
                    onNavigatePlayTime = { navigatePlayTime(null) },
                    onNavigateAchievements = { },
                    onNavigateSaveData = {
                        navController.navigate(saveManagerRoute()) { launchSingleTop = true }
                    },
                    onNavigateSearch = {
                        navController.navigate(ROUTE_CATALOG) { launchSingleTop = true }
                    },
                    onNavigateSettings = {
                        navController.navigate(settingsRoute()) { launchSingleTop = true }
                    },
                    onNavigateProfile = navigateProfile,
                    onNavigateDiscord = navigateDiscord,
                    onNavigateFeedback = navigateFeedback,
                    onBackClick = navigateHome,
                    onLaunchSystemMenu = launchSystemMenu,
                    onInstallContent = openInstallChoiceDialog
                ) { openDrawer ->
                    AchievementsScreen(
                        onMenuClick = openDrawer,
                        onBackClick = navigateHome
                    )
                }
            }
            composable(ROUTE_ACHIEVEMENTS_WITH_TITLE) { entry ->
                AchievementsScreen(
                    focusTitleId = entry.arguments?.getString("titleId"),
                    onMenuClick = null,
                    onBackClick = { navController.popBackStack() }
                )
            }
            composable(ROUTE_PLAY_TIME_WITH_TITLE) { entry ->
                PlayTimeScreen(
                    focusTitleId = entry.arguments?.getString("titleId"),
                    onMenuClick = null,
                    onBackClick = { navController.popBackStack() }
                )
            }
            composable(ROUTE_PROFILE) {
                val navigateHome = {
                    navController.navigate(ROUTE_LIBRARY) {
                        launchSingleTop = true
                        popUpTo(ROUTE_LIBRARY) { inclusive = false }
                    }
                }
                AdaptiveShell(
                    selected = PrimaryDestination.Profile,
                    onNavigateSetup = {
                        navController.navigate(ROUTE_SETUP) { launchSingleTop = true }
                    },
                    onNavigateLibrary = {
                        navController.navigate(ROUTE_LIBRARY) { launchSingleTop = true }
                    },
                    onNavigateGameManager = { navigateGameManager(null) },
                    onNavigatePatches = { navigatePatches(null) },
                    onNavigatePlayTime = { navigatePlayTime(null) },
                    onNavigateAchievements = navigateAchievementsRoot,
                    onNavigateSaveData = {
                        navController.navigate(saveManagerRoute()) { launchSingleTop = true }
                    },
                    onNavigateSearch = {
                        navController.navigate(ROUTE_CATALOG) { launchSingleTop = true }
                    },
                    onNavigateSettings = {
                        navController.navigate(settingsRoute()) { launchSingleTop = true }
                    },
                    onNavigateProfile = { },
                    onNavigateDiscord = navigateDiscord,
                    onNavigateFeedback = navigateFeedback,
                    onBackClick = navigateHome,
                    onLaunchSystemMenu = launchSystemMenu,
                    onInstallContent = openInstallChoiceDialog
                ) { openDrawer ->
                    ProfileScreen(
                        onBackClick = navigateHome,
                        onMenuClick = openDrawer,
                        onGameClick = { igdbId -> navController.navigate("$ROUTE_CATALOG_DETAIL_PREFIX/$igdbId") }
                    )
                }
            }
            composable(ROUTE_DISCORD) {
                AdaptiveShell(
                    selected = PrimaryDestination.Discord,
                    onNavigateSetup = {
                        navController.navigate(ROUTE_SETUP) { launchSingleTop = true }
                    },
                    onNavigateLibrary = {
                        navController.navigate(ROUTE_LIBRARY) { launchSingleTop = true }
                    },
                    onNavigateGameManager = { navigateGameManager(null) },
                    onNavigatePatches = { navigatePatches(null) },
                    onNavigatePlayTime = { navigatePlayTime(null) },
                    onNavigateAchievements = navigateAchievementsRoot,
                    onNavigateSaveData = {
                        navController.navigate(saveManagerRoute()) { launchSingleTop = true }
                    },
                    onNavigateSearch = {
                        navController.navigate(ROUTE_CATALOG) { launchSingleTop = true }
                    },
                    onNavigateSettings = {
                        navController.navigate(settingsRoute()) { launchSingleTop = true }
                    },
                    onNavigateProfile = navigateProfile,
                    onNavigateDiscord = { },
                    onNavigateFeedback = navigateFeedback,
                    onBackClick = { navController.popBackStack() },
                    onLaunchSystemMenu = null,
                    onInstallContent = null
                ) {
                    DiscordScreen(onBackClick = { navController.popBackStack() })
                }
            }
            composable(ROUTE_FEEDBACK) {
                AdaptiveShell(
                    selected = PrimaryDestination.Feedback,
                    onNavigateSetup = {
                        navController.navigate(ROUTE_SETUP) { launchSingleTop = true }
                    },
                    onNavigateLibrary = {
                        navController.navigate(ROUTE_LIBRARY) { launchSingleTop = true }
                    },
                    onNavigateGameManager = { navigateGameManager(null) },
                    onNavigatePatches = { navigatePatches(null) },
                    onNavigatePlayTime = { navigatePlayTime(null) },
                    onNavigateAchievements = navigateAchievementsRoot,
                    onNavigateSaveData = {
                        navController.navigate(saveManagerRoute()) { launchSingleTop = true }
                    },
                    onNavigateSearch = {
                        navController.navigate(ROUTE_CATALOG) { launchSingleTop = true }
                    },
                    onNavigateSettings = {
                        navController.navigate(settingsRoute()) { launchSingleTop = true }
                    },
                    onNavigateProfile = navigateProfile,
                    onNavigateDiscord = navigateDiscord,
                    onNavigateFeedback = {},
                    onBackClick = { navController.popBackStack() },
                    onLaunchSystemMenu = launchSystemMenu,
                    onInstallContent = null
                ) {
                    FeedbackScreen(onBackClick = { navController.popBackStack() })
                }
            }
            composable(ROUTE_GAME_MANAGER) {
                val navigateHome = {
                    navController.navigate(ROUTE_LIBRARY) {
                        launchSingleTop = true
                        popUpTo(ROUTE_LIBRARY) { inclusive = false }
                    }
                }
                AdaptiveShell(
                    selected = PrimaryDestination.GameManager,
                    onNavigateSetup = {
                        navController.navigate(ROUTE_SETUP) { launchSingleTop = true }
                    },
                    onNavigateLibrary = {
                        navController.navigate(ROUTE_LIBRARY) { launchSingleTop = true }
                    },
                    onNavigateGameManager = { },
                    onNavigatePatches = { navigatePatches(null) },
                    onNavigatePlayTime = { navigatePlayTime(null) },
                    onNavigateAchievements = navigateAchievementsRoot,
                    onNavigateSaveData = {
                        navController.navigate(saveManagerRoute()) { launchSingleTop = true }
                    },
                    onNavigateSearch = {
                        navController.navigate(ROUTE_CATALOG) { launchSingleTop = true }
                    },
                    onNavigateSettings = {
                        navController.navigate(settingsRoute()) { launchSingleTop = true }
                    },
                    onNavigateProfile = navigateProfile,
                    onNavigateDiscord = navigateDiscord,
                    onNavigateFeedback = navigateFeedback,
                    onBackClick = navigateHome,
                    onLaunchSystemMenu = launchSystemMenu,
                    onInstallContent = openInstallChoiceDialog
                ) { openDrawer ->
                    GameManagerScreen(
                        onMenuClick = openDrawer,
                        onBackClick = navigateHome,
                        onOpenGpuDriverManager = {
                            navController.navigate(gpuDriverRoute(it)) { launchSingleTop = true }
                        },
                        onOpenTouchControlsEditor = { titleId ->
                            navController.navigate(
                                if (titleId.isNullOrBlank()) ROUTE_TOUCH_CONTROLS_EDITOR
                                else "$ROUTE_TOUCH_CONTROLS_EDITOR/${Uri.encode(titleId)}"
                            ) { launchSingleTop = true }
                        }
                    )
                }
            }
            composable(ROUTE_GAME_MANAGER_WITH_TITLE) { entry ->
                GameManagerScreen(
                    initialTitleId = entry.arguments?.getString("titleId"),
                    onMenuClick = null,
                    onBackClick = { navController.popBackStack() },
                    onOpenGpuDriverManager = {
                        navController.navigate(gpuDriverRoute(it)) { launchSingleTop = true }
                    },
                    onOpenTouchControlsEditor = { titleId ->
                        navController.navigate(
                            if (titleId.isNullOrBlank()) ROUTE_TOUCH_CONTROLS_EDITOR
                            else "$ROUTE_TOUCH_CONTROLS_EDITOR/${Uri.encode(titleId)}"
                        ) { launchSingleTop = true }
                    }
                )
            }
            composable(ROUTE_PATCHES) {
                val navigateHome = {
                    navController.navigate(ROUTE_LIBRARY) {
                        launchSingleTop = true
                        popUpTo(ROUTE_LIBRARY) { inclusive = false }
                    }
                }
                AdaptiveShell(
                    selected = PrimaryDestination.Patches,
                    onNavigateSetup = {
                        navController.navigate(ROUTE_SETUP) { launchSingleTop = true }
                    },
                    onNavigateLibrary = {
                        navController.navigate(ROUTE_LIBRARY) { launchSingleTop = true }
                    },
                    onNavigateGameManager = { navigateGameManager(null) },
                    onNavigatePatches = { },
                    onNavigatePlayTime = { navigatePlayTime(null) },
                    onNavigateAchievements = navigateAchievementsRoot,
                    onNavigateSaveData = {
                        navController.navigate(saveManagerRoute()) { launchSingleTop = true }
                    },
                    onNavigateSearch = {
                        navController.navigate(ROUTE_CATALOG) { launchSingleTop = true }
                    },
                    onNavigateSettings = {
                        navController.navigate(settingsRoute()) { launchSingleTop = true }
                    },
                    onNavigateProfile = navigateProfile,
                    onNavigateDiscord = navigateDiscord,
                    onNavigateFeedback = navigateFeedback,
                    onBackClick = navigateHome,
                    onLaunchSystemMenu = launchSystemMenu,
                    onInstallContent = openInstallChoiceDialog
                ) { openDrawer ->
                    PatchesScreen(
                        onMenuClick = openDrawer,
                        onBackClick = navigateHome
                    )
                }
            }
            composable(ROUTE_PATCHES_WITH_TITLE) { entry ->
                PatchesScreen(
                    initialTitleId = entry.arguments?.getString("titleId"),
                    onMenuClick = null,
                    onBackClick = { navController.popBackStack() }
                )
            }
            composable(
                "$ROUTE_DETAIL_PREFIX/{titleId}"
            ) { entry ->
                GameDetailScreen(
                    titleId = entry.arguments?.getString("titleId"),
                    igdbId = null,
                    onBack = { navController.popBackStack() },
                    onOpenSaveManager = { titleId ->
                        navController.navigate(saveManagerRoute(titleId)) { launchSingleTop = true }
                    }
                )
            }
            composable(
                "$ROUTE_CATALOG_DETAIL_PREFIX/{igdbId}"
            ) { entry ->
                GameDetailScreen(
                    titleId = null,
                    igdbId = entry.arguments?.getString("igdbId")?.toLongOrNull(),
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }

    if (showInstallChoiceDialog) {
        InstallGameChoiceDialog(
            onDismiss = {
                showInstallChoiceDialog = false
            },
            onInstallLicense = {
                showInstallChoiceDialog = false
                openLicenseInstall()
            },
            onInstallPkg = {
                showInstallChoiceDialog = false
                openPkgInstall()
            }
        )
    }

    SetupInstallDialog(
        uiState = installUiState,
        onDismiss = installViewModel::dismissDialog
    )

}

private fun appScreenEnterTransition(): EnterTransition {
    return appScreenPopEnterTransition()
}

private fun appScreenExitTransition(): ExitTransition {
    return ExitTransition.None
}

private fun appScreenPopEnterTransition(): EnterTransition {
    return fadeIn(animationSpec = tween(durationMillis = 260, delayMillis = 70, easing = EaseOut)) +
        scaleIn(initialScale = 0.96f, animationSpec = tween(260, delayMillis = 70, easing = EaseOut))
}

private fun appScreenPopExitTransition(): ExitTransition {
    return fadeOut(animationSpec = tween(durationMillis = 110, easing = EaseIn)) +
        scaleOut(targetScale = 1.0f, animationSpec = tween(110, easing = EaseIn))
}
