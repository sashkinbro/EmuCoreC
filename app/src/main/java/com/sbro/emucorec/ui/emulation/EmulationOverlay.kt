@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.sbro.emucorec.ui.emulation

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import com.sbro.emucorec.ui.common.AppAlertDialog as AlertDialog
import com.sbro.emucorec.ui.common.ImmersiveDialogWindow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sbro.emucorec.R
import com.sbro.emucorec.core.AndroidGyroscopeInput
import com.sbro.emucorec.core.AndroidTouchHaptics
import com.sbro.emucorec.core.AndroidTouchHaptics.ButtonPhase
import com.sbro.emucorec.core.Ps3CoreConfig
import com.sbro.emucorec.core.Ps3GameSettingsRepository
import com.sbro.emucorec.core.input.KeyboardMouseBindings
import com.sbro.emucorec.core.input.KeyboardMouseManager
import com.sbro.emucorec.core.NativeSaveStateBackend
import com.sbro.emucorec.core.SaveStateController
import com.sbro.emucorec.core.SaveStateRepository
import com.sbro.emucorec.core.SaveStateOutcome
import com.sbro.emucorec.core.Ps3Runtime
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import com.sbro.emucorec.core.EmulatorState
import com.sbro.emucorec.core.Ps3Core
import com.sbro.emucorec.core.ps3.Emulator
import com.sbro.emucorec.core.ps3.overlay.InputOverlay
import com.sbro.emucorec.data.AppPreferences
import com.sbro.emucorec.data.InstalledGameRepository
import com.sbro.emucorec.data.CustomizationPreferences
import com.sbro.emucorec.data.KeyboardMouseSettings
import com.sbro.emucorec.data.TouchControlPressEffect
import com.sbro.emucorec.data.TouchControlVisualStyle
import com.sbro.emucorec.ui.common.VectorAnalogStick
import com.sbro.emucorec.ui.common.VectorOverlayButton
import com.sbro.emucorec.ui.settings.KeyboardMouseSettingsDialog
import kotlin.math.abs
import kotlin.math.roundToInt
import com.sbro.emucorec.ui.theme.neon.LocalNeonTheme
import com.sbro.emucorec.ui.theme.neon.neonShape

private enum class SaveAction { Save, Load, Delete }
private data class SaveRequest(val action: SaveAction, val slot: Int)

@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
fun EmulationOverlayHost(
    activity: Emulator,
    modifier: Modifier = Modifier
) {
    // Read currentGameId as Compose State — recomposes automatically when the
    // native core calls setCurrentGameId() after the SDL surface is ready.
    // Fall back to the intent-carried ID until the native callback fires.
    val nativeGameId = activity.currentGameId
    val gameId = nativeGameId.ifBlank { activity.currentGameIdOrIntent() }
    val repository = remember(activity) { Ps3GameSettingsRepository(activity) }
    val controlLayoutRepository = remember(activity) { TouchControlLayoutRepository(activity) }
    val customizationPreferences = remember(activity) { CustomizationPreferences(activity) }
    val appPreferences = remember(activity) { AppPreferences(activity) }
    val showQuickBar by remember(appPreferences) { appPreferences.showEmulationQuickBarFlow }.collectAsState(
        initial = appPreferences.showEmulationQuickBar
    )
    val customization by customizationPreferences.settings.collectAsState()
    val overlayBridge = remember(activity) { activity.getmOverlay() }
    var config by remember(activity, gameId) { mutableStateOf(repository.loadEffective(gameId)) }
    var controlLayout by remember(activity, gameId) { mutableStateOf(controlLayoutRepository.load(gameId)) }
    var controlsEditMode by remember { mutableStateOf(false) }
    var editorProfileRevision by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    // Kept outside the menu composable so closing the menu and opening it again lands on the same
    // tab with the same scroll offset, like the EmuCoreX game menu does.
    var menuTabName by rememberSaveable(gameId) { mutableStateOf(EmulationMenuTab.Game.name) }
    val menuScrollStates = remember(gameId) { mutableMapOf<String, ScrollState>() }
    var menuButtonVisible by remember { mutableStateOf(false) }
    var userPaused by remember { mutableStateOf(false) }
    var exitDialogVisible by remember { mutableStateOf(false) }
    var pendingSaveRequest by remember { mutableStateOf<SaveRequest?>(null) }
    var saveRequesting by remember { mutableStateOf(false) }
    var saveRequestSaving by remember { mutableStateOf(false) }
    var saveRequestDeleting by remember { mutableStateOf(false) }
    var saveSlotsLoaded by remember(gameId) { mutableStateOf(false) }
    var launchingSaveState by remember(activity) { mutableStateOf(activity.isSaveStateLaunch()) }
    var selectedSaveSlot by remember(gameId) { mutableIntStateOf(0) }
    var showKeyboardMouseSettings by remember { mutableStateOf(false) }
    var keyboardMouseOverride by remember(activity, gameId) {
        mutableStateOf(
            repository.loadProfile(gameId).keyboardMouse
        )
    }
    val globalKeyboardMouseSettings by KeyboardMouseManager.globalSettings.collectAsState()
    val keyboardMousePreferences = remember(activity) { AppPreferences(activity) }
    val saveStateScope = rememberCoroutineScope()
    val saveRepository = remember(activity) { SaveStateRepository(activity) }
    val saveStates = remember(activity, gameId) {
        SaveStateController(NativeSaveStateBackend { saveRepository.listSlots(gameId) })
    }
    val saveStateBusy by saveStates.busy.collectAsState()
    val saveSlots by saveStates.slots.collectAsState()
    var sessionElapsedMs by remember(activity) { mutableLongStateOf(activity.currentPlayTimeElapsedMs()) }
    val gameTitle = activity.currentGameTitle.ifBlank { gameId }
    val gameIconPath = activity.currentGameIconPath
    val gameCoverUrl = activity.currentGameCoverUrl
    val hasPhysicalGamepad = activity.hasPhysicalGamepad
    val touchControlsActive = controlsEditMode || config.enableGamepadOverlay
    val showTouchControls = !menuOpen &&
        (
            controlsEditMode ||
                (
                    touchControlsActive &&
                        overlayBridge.effectiveOverlayMask != 0
                )
            )
    val effectivePaused = userPaused || menuOpen || controlsEditMode
    val lifecycleOwner = LocalLifecycleOwner.current
    val gyroController = remember(activity, overlayBridge) {
        AndroidGyroscopeInput(activity) { emittedMode, x, y ->
            val rightStick = emittedMode == Ps3CoreConfig.GYRO_MODE_AIM
            val axisX = if (rightStick) {
                InputOverlay.ControlId.axis_right_x
            } else {
                InputOverlay.ControlId.axis_left_x
            }
            val axisY = if (rightStick) {
                InputOverlay.ControlId.axis_right_y
            } else {
                InputOverlay.ControlId.axis_left_y
            }
            fun quantize(value: Float): Short = (value * Short.MAX_VALUE)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            overlayBridge.setAxis(axisX, quantize(x))
            overlayBridge.setAxis(axisY, quantize(y))
        }
    }

    fun persistConfig(transform: (Ps3CoreConfig) -> Ps3CoreConfig) {
        config = transform(config)
        // Only persist when we have a real game ID — if still blank the native
        // core hasn't reported the title yet and the save would be a no-op
        // (savePreservingDriverOverride guards against blank IDs internally).
        repository.savePreservingDriverOverride(gameId, config)
        activity.updateGamepadRuntimeInputSettings(config)
    }

    fun persistKeyboardMouse(settings: KeyboardMouseSettings?) {
        if (gameId.isBlank()) {
            if (settings != null) {
                keyboardMousePreferences.setKeyboardMouseSettings(settings)
            }
            keyboardMouseOverride = null
            KeyboardMouseManager.setPerGameOverride(null)
            return
        }
        if (settings == null) {
            repository.clearKeyboardMouse(gameId)
        } else {
            repository.saveKeyboardMouse(gameId, settings)
        }
        keyboardMouseOverride = settings
        KeyboardMouseManager.setPerGameOverride(settings)
    }

    LaunchedEffect(keyboardMouseOverride) {
        KeyboardMouseManager.setPerGameOverride(keyboardMouseOverride)
    }

    LaunchedEffect(menuOpen, controlsEditMode) {
        KeyboardMouseManager.setPointerCaptureSuppressed(menuOpen || controlsEditMode)
    }

    DisposableEffect(activity) {
        onDispose {
            KeyboardMouseManager.setPointerCaptureSuppressed(false)
        }
    }

    DisposableEffect(config) {
        overlayBridge.synchronizeConfig(config)
        onDispose {}
    }

    DisposableEffect(
        lifecycleOwner,
        effectivePaused,
        config.gyroMode,
        config.gyroSensitivity,
        config.gyroSmoothing,
        config.gyroInvertX,
        config.gyroInvertY
    ) {
        fun startGyroscope() {
            if (!effectivePaused && config.gyroMode != Ps3CoreConfig.GYRO_MODE_OFF) {
                gyroController.start(
                    mode = config.gyroMode,
                    sensitivityPercent = config.gyroSensitivity,
                    smoothingPercent = config.gyroSmoothing,
                    invertX = config.gyroInvertX,
                    invertY = config.gyroInvertY
                )
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> startGyroscope()
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> gyroController.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            startGyroscope()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            gyroController.stop()
        }
    }

    LaunchedEffect(effectivePaused, saveStateBusy, saveRequesting, launchingSaveState) {
        if (!saveStateBusy && !saveRequesting && !launchingSaveState) activity.setMenuPaused(effectivePaused)
    }

    LaunchedEffect(activity, launchingSaveState) {
        if (launchingSaveState) {
            while (true) {
                when (Ps3Runtime.state()) {
                    EmulatorState.Running -> break
                    EmulatorState.Paused -> { userPaused = true; break }
                    else -> kotlinx.coroutines.delay(50)
                }
            }
            launchingSaveState = false
        }
    }

    LaunchedEffect(menuOpen, gameId) {
        if (menuOpen) withContext(Dispatchers.IO) {
            runCatching { com.sbro.emucorec.ui.settings.preloadCoreSettings(activity) }
            runCatching { saveStates.refresh() }
        }
    }

    LaunchedEffect(activity, gameId) {
        while (Ps3Runtime.state() !in listOf(EmulatorState.Running, EmulatorState.Paused)) kotlinx.coroutines.delay(50)
        saveSlotsLoaded = withContext(Dispatchers.IO) { runCatching { saveStates.refresh() }.isSuccess }
    }

    fun performSaveRequest(request: SaveRequest) {
        if (saveStateBusy || saveRequesting) return
        saveRequesting = true
        saveRequestSaving = request.action == SaveAction.Save
        saveRequestDeleting = request.action == SaveAction.Delete
        saveStateScope.launch {
            var thumbnail: File? = null
            try {
                if (request.action == SaveAction.Save) {
                    thumbnail = try {
                        withTimeoutOrNull(2000) {
                            captureSaveStateThumbnail(activity, saveRepository.thumbnailFile(gameId, request.slot))
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                }
                val outcome = try {
                    withTimeout(120_000) {
                        withContext(Dispatchers.IO) {
                            when (request.action) {
                                SaveAction.Save -> saveStates.save(request.slot)
                                SaveAction.Load -> saveStates.load(request.slot)
                                SaveAction.Delete -> if (saveRepository.delete(gameId, request.slot)) SaveStateOutcome.Success else SaveStateOutcome.Failed
                            }
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    withContext(Dispatchers.IO) { Ps3Runtime.requestStop() }
                    SaveStateOutcome.Failed
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { SaveStateOutcome.Failed }
                if (outcome == SaveStateOutcome.Success && thumbnail != null) {
                    withContext(Dispatchers.IO) {
                        runCatching { commitSaveStateThumbnail(requireNotNull(thumbnail), saveRepository.thumbnailFile(gameId, request.slot)) }
                    }
                }
                withContext(Dispatchers.IO) { runCatching { saveStates.refresh() } }
                if (outcome == SaveStateOutcome.Success && request.action == SaveAction.Load) {
                    menuOpen = false
                    userPaused = false
                }
                val message = if (outcome == SaveStateOutcome.Empty) R.string.emulation_savestate_empty
                else if (outcome != SaveStateOutcome.Success) R.string.emulation_savestate_failed_toast else when (request.action) {
                    SaveAction.Save -> R.string.emulation_savestate_saved_toast
                    SaveAction.Load -> R.string.emulation_savestate_loaded_toast
                    SaveAction.Delete -> R.string.emulation_savestate_deleted_toast
                }
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            } finally {
                thumbnail?.delete()
                saveRequesting = false
            }
        }
    }

    fun requestSaveAction(action: SaveAction, slot: Int) {
        if (saveStateBusy || saveRequesting || !saveSlotsLoaded) return
        val request = SaveRequest(action, slot)
        if (action != SaveAction.Save && saveSlots.none { it.index == slot }) {
            Toast.makeText(activity, R.string.emulation_savestate_empty, Toast.LENGTH_SHORT).show()
            return
        }
        if (action == SaveAction.Save && saveSlots.none { it.index == slot }) performSaveRequest(request)
        else pendingSaveRequest = request
    }

    val quickActionHandler by rememberUpdatedState<(String) -> Unit> { actionId ->
        when (actionId) {
            KeyboardMouseBindings.ACTION_QUICK_SAVE ->
                requestSaveAction(SaveAction.Save, SaveStateRepository.QUICK_SLOT)
            KeyboardMouseBindings.ACTION_QUICK_LOAD ->
                requestSaveAction(SaveAction.Load, SaveStateRepository.QUICK_SLOT)
        }
    }

    LaunchedEffect(KeyboardMouseManager) {
        KeyboardMouseManager.quickActions.collect { actionId ->
            quickActionHandler(actionId)
        }
    }

    LaunchedEffect(effectivePaused) {
        KeyboardMouseManager.setEmulationInputEnabled(!effectivePaused)
    }

    LaunchedEffect(touchControlsActive) {
        overlayBridge.setTouchControlsActive(touchControlsActive)
    }

    LaunchedEffect(showTouchControls) {
        if (showTouchControls) {
            while (!overlayBridge.ensureControllerAttached()) {
                kotlinx.coroutines.delay(350)
            }
        }
    }

    LaunchedEffect(menuOpen, controlsEditMode) {
        if (menuOpen || controlsEditMode) {
            menuButtonVisible = true
        }
    }

    LaunchedEffect(activity, menuOpen) {
        while (menuOpen) {
            sessionElapsedMs = activity.currentPlayTimeElapsedMs()
            kotlinx.coroutines.delay(1_000)
        }
    }

    LaunchedEffect(menuOpen, menuButtonVisible) {
        if (!menuOpen && menuButtonVisible) {
            kotlinx.coroutines.delay(5_000)
            if (!menuOpen) {
                menuButtonVisible = false
            }
        }
    }

    DisposableEffect(activity) {
        activity.setOverlayBackHandler {
            if (saveStateBusy || saveRequesting || launchingSaveState) {
                true
            } else if (pendingSaveRequest != null) {
                pendingSaveRequest = null
                true
            } else if (exitDialogVisible) {
                exitDialogVisible = false
                true
            } else if (showKeyboardMouseSettings) {
                showKeyboardMouseSettings = false
                true
            } else if (controlsEditMode) {
                controlsEditMode = false
                overlayBridge.setIsInEditMode(false)
                true
            } else {
                menuOpen = !menuOpen
                menuButtonVisible = true
                true
            }
        }
        activity.setOverlayMenuButtonRevealHandler {
            menuButtonVisible = true
        }
        activity.setOverlayPauseMenuOpenHandler {
            menuOpen = true
            menuButtonVisible = true
        }
        onDispose {
            activity.setOverlayBackHandler(null)
            activity.setOverlayMenuButtonRevealHandler(null)
            activity.setOverlayPauseMenuOpenHandler(null)
            customizationPreferences.close()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (showTouchControls) {
            OnScreenControls(
                modifier = Modifier.fillMaxSize(),
                overlayScale = config.overlayScale,
                overlayOpacity = config.overlayOpacity,
                visualStyle = customization.touchControlVisualStyle,
                pressEffect = customization.touchControlPressEffect,
                touchHaptics = config.touchHaptics,
                touchHapticsPreset = config.touchHapticsPreset,
                touchHapticsStrength = config.touchHapticsStrength,
                editMode = controlsEditMode,
                editorProfileRevision = editorProfileRevision,
                savedLayout = controlLayout,
                onLayoutChange = { updated ->
                    controlLayout = updated
                    controlLayoutRepository.save(gameId, updated)
                },
                onEditDone = {
                    controlsEditMode = false
                    overlayBridge.setIsInEditMode(false)
                },
                onEditReset = {
                    controlLayoutRepository.reset(gameId)
                    controlLayout = controlLayoutRepository.load(gameId)
                    editorProfileRevision++
                    persistConfig { it.copy(overlayScale = 0.9f, overlayOpacity = 100) }
                },
                onButtonChange = { button, pressed -> overlayBridge.setButton(button, pressed) },
                onAxisChange = { axis, value -> overlayBridge.setAxis(axis, value) }
            )
        }

        val configuration = LocalConfiguration.current
        val useSidePanel = configuration.screenWidthDp > configuration.screenHeightDp

        val menuCallbacks = EmulationMenuCallbacks(
            onSaveStateSlotSelected = { selectedSaveSlot = it },
            onSaveStateSave = { requestSaveAction(SaveAction.Save, it) },
            onSaveStateLoad = { requestSaveAction(SaveAction.Load, it) },
            onSaveStateDelete = { requestSaveAction(SaveAction.Delete, it) },
            onPauseToggle = {
                if (effectivePaused) {
                    userPaused = false
                    menuOpen = false
                    if (controlsEditMode) {
                        controlsEditMode = false
                        overlayBridge.setIsInEditMode(false)
                    }
                } else {
                    userPaused = true
                    menuButtonVisible = true
                }
            },
            onExit = { exitDialogVisible = true },
            onClose = { menuOpen = false },
            onEditControls = {
                persistConfig { it.copy(enableGamepadOverlay = true) }
                menuOpen = false
                menuButtonVisible = true
                controlsEditMode = true
                overlayBridge.setIsInEditMode(true)
            },
            onControlsVisibility = {
                persistConfig { it.copy(enableGamepadOverlay = !it.enableGamepadOverlay) }
            },
            onResetOverlay = {
                controlLayoutRepository.reset(gameId)
                controlLayout = controlLayoutRepository.load(gameId)
                editorProfileRevision++
                persistConfig {
                    it.copy(
                        enableGamepadOverlay = true,
                        overlayScale = 0.9f,
                        overlayOpacity = 100
                    )
                }
            },
            onOverlayScale = { value -> persistConfig { it.copy(overlayScale = value) } },
            onOverlayOpacity = { value -> persistConfig { it.copy(overlayOpacity = value) } },
            onTouchHaptics = { enabled -> persistConfig { it.copy(touchHaptics = enabled) } },
            onTouchHapticsPreset = { value -> persistConfig { it.copy(touchHapticsPreset = value) } },
            onTouchHapticsStrength = { value -> persistConfig { it.copy(touchHapticsStrength = value) } },
            onGyroMode = { value -> persistConfig { it.copy(gyroMode = value) } },
            onGyroSensitivity = { value -> persistConfig { it.copy(gyroSensitivity = value) } },
            onGyroSmoothing = { value -> persistConfig { it.copy(gyroSmoothing = value) } },
            onGyroInvertX = { enabled -> persistConfig { it.copy(gyroInvertX = enabled) } },
            onGyroInvertY = { enabled -> persistConfig { it.copy(gyroInvertY = enabled) } },
            onGamepadDeadzone = { value -> persistConfig { it.copy(gamepadDeadzone = value) } },
            onGamepadAnalogMultiplier = { value -> persistConfig { it.copy(analogMultiplier = value) } },
            onGamepadTriggerThreshold = { value -> persistConfig { it.copy(gamepadTriggerThreshold = value) } },
            onGamepadButtonProfile = { value -> persistConfig { it.copy(gamepadButtonProfile = value) } },
            onGamepadVibration = { enabled -> persistConfig { it.copy(gamepadVibration = enabled) } },
            onGamepadVibrationStrength = { value -> persistConfig { it.copy(gamepadVibrationStrength = value) } },
            onDeviceVibrationFallback = { enabled -> persistConfig { it.copy(deviceVibrationFallback = enabled) } },
            onGamepadSwapSticks = { enabled -> persistConfig { it.copy(gamepadSwapSticks = enabled) } },
            onGamepadInvertLeftX = { enabled -> persistConfig { it.copy(gamepadInvertLeftX = enabled) } },
            onGamepadInvertLeftY = { enabled -> persistConfig { it.copy(gamepadInvertLeftY = enabled) } },
            onGamepadInvertRightX = { enabled -> persistConfig { it.copy(gamepadInvertRightX = enabled) } },
            onGamepadInvertRightY = { enabled -> persistConfig { it.copy(gamepadInvertRightY = enabled) } },
            onOpenKeyboardMouseSettings = { showKeyboardMouseSettings = true }
        )

        AnimatedVisibility(visible = menuOpen, enter = fadeIn(tween(220)), exit = fadeOut(tween(180))) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        menuOpen = false
                    }
            )
        }

        AnimatedVisibility(
            visible = menuOpen,
            enter = if (useSidePanel) {
                slideInHorizontally(initialOffsetX = { it }) + fadeIn(tween(300))
            } else {
                androidx.compose.animation.slideInVertically(initialOffsetY = { it }) + fadeIn(tween(220))
            },
            exit = if (useSidePanel) {
                slideOutHorizontally(targetOffsetX = { it }) + fadeOut(tween(250))
            } else {
                androidx.compose.animation.slideOutVertically(targetOffsetY = { it }) + fadeOut(tween(180))
            },
            modifier = Modifier.fillMaxSize()
        ) {
            EmulationGameMenu(
                gameTitle = gameTitle,
                gameIconPath = gameIconPath,
                gameCoverUrl = gameCoverUrl,
                gameId = gameId,
                config = config,
                paused = effectivePaused,
                sessionElapsedMs = sessionElapsedMs,
                expandHorizontally = useSidePanel,
                layoutStyle = customization.gameMenuLayoutStyle,
                physicalGamepadConnected = hasPhysicalGamepad,
                saveStates = SaveStateMenuState(saveSlots, selectedSaveSlot,
                    saveStateBusy || saveRequesting, saveRequestSaving,
                    saveSlotsLoaded && Ps3Core.initialized && Ps3Core.instance.saveStatesSupported() && !launchingSaveState,
                    busyDeleting = saveRequestDeleting),
                selectedTab = EmulationMenuTab.values().firstOrNull { it.name == menuTabName }
                    ?: EmulationMenuTab.Game,
                onSelectedTab = { menuTabName = it.name },
                scrollState = menuScrollStates.getOrPut(menuTabName) { ScrollState(0) },
                callbacks = menuCallbacks
            )
        }

        AnimatedVisibility(
            visible = showQuickBar && !controlsEditMode && menuButtonVisible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(140)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            EmulationQuickBar(
                paused = effectivePaused,
                quickActionsEnabled = saveSlotsLoaded && !saveStateBusy && !saveRequesting && !launchingSaveState,
                onPauseToggle = menuCallbacks.onPauseToggle,
                onQuickSave = { requestSaveAction(SaveAction.Save, SaveStateRepository.QUICK_SLOT) },
                onQuickLoad = { requestSaveAction(SaveAction.Load, SaveStateRepository.QUICK_SLOT) },
                onOpenCoreHomeMenu = { activity.openCoreHomeMenu() },
                onOpenMenu = {
                    menuOpen = !menuOpen
                    menuButtonVisible = true
                }
            )
        }

        pendingSaveRequest?.let { request ->
            val title = when (request.action) {
                SaveAction.Save -> R.string.emulation_savestate_confirm_save_title
                SaveAction.Load -> R.string.emulation_savestate_confirm_load_title
                SaveAction.Delete -> R.string.emulation_savestate_confirm_delete_title
            }
            val body = when (request.action) {
                SaveAction.Save -> R.string.emulation_savestate_confirm_save_body
                SaveAction.Load -> R.string.emulation_savestate_confirm_load_body
                SaveAction.Delete -> R.string.emulation_savestate_confirm_delete_body
            }
            val label = if (request.slot == SaveStateRepository.QUICK_SLOT) stringResource(R.string.emulation_savestate_quick_label)
                else stringResource(R.string.emulation_savestate_slot_label, request.slot)
            AlertDialog(onDismissRequest = { pendingSaveRequest = null },
                title = { Text(stringResource(title)) },
                text = {
                    // The dialog owns its own window; keep the game's system bars hidden.
                    ImmersiveDialogWindow()
                    Text(stringResource(body, label))
                },
                confirmButton = { TextButton(onClick = {
                    pendingSaveRequest = null
                    performSaveRequest(request)
                }) { Text(stringResource(when (request.action) {
                    SaveAction.Save -> R.string.emulation_savestate_save_action
                    SaveAction.Load -> R.string.emulation_savestate_load_action
                    SaveAction.Delete -> R.string.emulation_savestate_delete_action
                })) } },
                dismissButton = { TextButton(onClick = { pendingSaveRequest = null }) { Text(stringResource(R.string.common_cancel)) } })
        }

        // Saving stops and resumes the running game in the core, but the screen
        // never leaves the game the user is playing -- a full-screen progress
        // overlay for a save just flashes in and out. It stays for loads and
        // savestate launches, which genuinely restart emulation and take time.
        LaunchStateLoadingOverlay(
            state = LaunchStateLoadingState(
                launchingSaveState ||
                    (!saveRequestSaving && !saveRequestDeleting && (saveRequesting || saveStateBusy))
            ),
            indeterminate = true,
            scrimAlpha = if (launchingSaveState) 1f else 0.78f,
            messageRes = R.string.emulation_savestate_startup_loading,
        )

        LaunchStateLoadingOverlay(
            state = LaunchStateLoadingState(activity.preparingGame),
            indeterminate = true,
            messageRes = R.string.emulation_game_preparing,
        )

        if (showKeyboardMouseSettings) {
            KeyboardMouseSettingsDialog(
                settings = keyboardMouseOverride ?: globalKeyboardMouseSettings,
                onSettingsChange = { settings -> persistKeyboardMouse(settings) },
                onUseGlobal = if (keyboardMouseOverride != null) {
                    { persistKeyboardMouse(null) }
                } else {
                    null
                },
                onDismiss = { showKeyboardMouseSettings = false }
            )
        }

        if (exitDialogVisible) {
            AlertDialog(
                onDismissRequest = { exitDialogVisible = false },
                title = {
                    Text(text = stringResource(R.string.emulation_exit_confirm_title))
                },
                text = {
                    // The dialog owns its own window; keep the game's system bars hidden.
                    ImmersiveDialogWindow()
                    Text(text = stringResource(R.string.emulation_exit_confirm_body))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            exitDialogVisible = false
                            activity.exitEmulation()
                        }
                    ) {
                        Text(text = stringResource(R.string.emulation_exit_confirm_action))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { exitDialogVisible = false }) {
                        Text(text = stringResource(R.string.emulation_exit_cancel_action))
                    }
                }
            )
        }
    }

}

@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
internal fun OnScreenControls(
    overlayScale: Float,
    overlayOpacity: Int,
    visualStyle: TouchControlVisualStyle,
    pressEffect: TouchControlPressEffect,
    touchHaptics: Boolean,
    touchHapticsPreset: Int,
    touchHapticsStrength: Int,
    editMode: Boolean,
    savedLayout: List<TouchControlElement>?,
    onLayoutChange: (List<TouchControlElement>) -> Unit,
    onEditDone: () -> Unit,
    onEditReset: () -> Unit,
    onButtonChange: (Int, Boolean) -> Unit,
    onAxisChange: (Int, Short) -> Unit,
    modifier: Modifier = Modifier,
    editorProfileRevision: Int = 0
) {
    val context = LocalContext.current
    val hapticView = LocalView.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val cutoutInsets = WindowInsets.displayCutout.asPaddingValues()
    val navInsets = WindowInsets.navigationBars.asPaddingValues()
    val topInset = maxOf(
        cutoutInsets.calculateTopPadding(),
        WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding(),
    )
    val bottomInset = navInsets.calculateBottomPadding()
    val alpha = overlayOpacity / 100f

    val editorLayoutDirection = LocalLayoutDirection.current
    // Controller positions and pointer coordinates use physical screen axes,
    // independently of the app language. Editor text keeps the app direction.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (editMode) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.34f)))
        }
        val density = LocalDensity.current
        val canvasWidth = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val canvasHeight = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val safeAreaPadding = with(density) {
            touchLayoutSafeAreaPadding(
                isLandscape = isLandscape,
                density = density.density,
                leftInsetPx = cutoutInsets.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr).toPx(),
                rightInsetPx = cutoutInsets.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr).toPx(),
                topInsetPx = topInset.toPx(),
                bottomInsetPx = bottomInset.toPx()
            )
        }
        val defaultLayout = remember(
            canvasWidth,
            canvasHeight,
            isLandscape,
            overlayScale,
            safeAreaPadding
        ) {
            buildDefaultTouchLayout(
                canvasWidth = canvasWidth,
                canvasHeight = canvasHeight,
                isLandscape = isLandscape,
                overlayScale = overlayScale,
                density = density.density,
                sidePaddingPx = safeAreaPadding.sidePx,
                bottomPaddingPx = safeAreaPadding.bottomPx,
                shoulderTopPaddingPx = safeAreaPadding.shoulderTopPx
            )
        }
        val mergedLayout = remember(defaultLayout, savedLayout) { mergeTouchLayout(defaultLayout, savedLayout) }
        var controls by remember(defaultLayout, editorProfileRevision) { mutableStateOf(mergedLayout) }
        var selectedId by remember(editMode, editorProfileRevision) { mutableStateOf<String?>(null) }
        var selectedGroupIds by remember(editMode, editorProfileRevision) { mutableStateOf<Set<String>?>(null) }
        var groupScalePercent by remember(editMode, editorProfileRevision) { mutableIntStateOf(100) }
        var pressedGroupControlIds by remember { mutableStateOf(emptySet<Int>()) }
        var hapticPressedControlIds by remember { mutableStateOf(emptySet<Int>()) }
        LaunchedEffect(mergedLayout, editorProfileRevision) {
            controls = mergedLayout
        }
        val selected = controls.firstOrNull { it.id == selectedId } ?: controls.firstOrNull()
        var showGrid by rememberSaveable(editMode, editorProfileRevision) { mutableStateOf(false) }
        var snapToGrid by rememberSaveable(editMode, editorProfileRevision) { mutableStateOf(false) }
        var comboEditorOpen by remember(editMode, editorProfileRevision) { mutableStateOf(false) }
        var createCombo by remember(editMode, editorProfileRevision) { mutableStateOf(false) }
        val dragResiduals = remember(editorProfileRevision) { mutableMapOf<String, Pair<Float, Float>>() }
        val gridStep = with(density) { 24.dp.toPx() }
        val selectedDescriptor = selected?.actionId?.let(::touchControlDescriptor)
        val defaultSelected = selected?.let { element -> defaultLayout.firstOrNull { it.id == element.actionId } }
        val selectedIsAnalog = selectedDescriptor?.type == TouchControlType.Analog
        val selectedAnalogMode = selected?.analogMode ?: TouchAnalogMode.Stick
        val selectedScalePercent = if (selected != null && defaultSelected != null) {
            val currentSize = maxOf(selected.width * canvasWidth, selected.height * canvasHeight)
            val defaultSize = maxOf(defaultSelected.width * canvasWidth, defaultSelected.height * canvasHeight).coerceAtLeast(1f)
            ((currentSize / defaultSize) * 100f).roundToInt().coerceIn(25, 300)
        } else {
            100
        }
        val selectedWidthPercent = if (selected != null && defaultSelected != null) {
            ((selected.width / defaultSelected.width.coerceAtLeast(0.001f)) * 100f).roundToInt().coerceIn(25, 300)
        } else {
            100
        }
        val selectedHeightPercent = if (selected != null && defaultSelected != null) {
            ((selected.height / defaultSelected.height.coerceAtLeast(0.001f)) * 100f).roundToInt().coerceIn(25, 300)
        } else {
            100
        }

        fun commitLayoutChange(transform: (List<TouchControlElement>) -> List<TouchControlElement>) {
            val updated = transform(controls).map { it.coerceToCanvas() }
            controls = updated
            onLayoutChange(updated)
        }

        fun snapDrag(id: String, x: Float, y: Float, delta: Offset): Offset {
            val snapped = snapTouchDrag(dragResiduals, id, x * canvasWidth, y * canvasHeight,
                delta.x to delta.y, gridStep, snapToGrid)
            return Offset(snapped.first, snapped.second)
        }

        fun duplicateSelected() {
            val source = selected ?: return
            if (selectedDescriptor?.type != TouchControlType.Button || controls.count { it.id.startsWith("custom_") } >= 32) return
            val copy = source.copy(id = "custom_" + java.util.UUID.randomUUID(),
                x = source.x + 0.04f, y = source.y + 0.04f).coerceToCanvas()
            commitLayoutChange { it + copy }
            selectedId = copy.id
        }

        fun resizeAroundCenter(element: TouchControlElement, nextWidth: Float, nextHeight: Float): TouchControlElement {
            val centerX = element.x + element.width / 2f
            val centerY = element.y + element.height / 2f
            val safeWidth = normalizedTouchControlDimension(nextWidth)
            val safeHeight = normalizedTouchControlDimension(nextHeight)
            return element.copy(
                width = safeWidth,
                height = safeHeight,
                x = (centerX - safeWidth / 2f).coerceIn(0f, 1f - safeWidth),
                y = (centerY - safeHeight / 2f).coerceIn(0f, 1f - safeHeight)
            )
        }

        fun updateSelectedSize(percentDelta: Int) {
            val selectedElement = selected ?: return
            val target = controls.firstOrNull { it.id == selectedElement.id } ?: selectedElement
            val baseline = defaultLayout.firstOrNull { it.id == target.actionId } ?: target
            val currentSize = maxOf(target.width * canvasWidth, target.height * canvasHeight)
            val defaultSize = maxOf(baseline.width * canvasWidth, baseline.height * canvasHeight).coerceAtLeast(1f)
            val currentPercent = ((currentSize / defaultSize) * 100f).roundToInt().coerceIn(25, 300)
            val nextPercent = (currentPercent + percentDelta).coerceIn(35, 250) / 100f
            val nextWidth = baseline.width * nextPercent
            val nextHeight = baseline.height * nextPercent
            commitLayoutChange { currentControls ->
                currentControls.replaceElement(resizeAroundCenter(target, nextWidth, nextHeight))
            }
        }

        fun updateSelectedWidth(percentDelta: Int) {
            val selectedElement = selected ?: return
            val target = controls.firstOrNull { it.id == selectedElement.id } ?: selectedElement
            val baseline = defaultLayout.firstOrNull { it.id == target.actionId } ?: target
            val currentPercent = ((target.width / baseline.width.coerceAtLeast(0.001f)) * 100f).roundToInt().coerceIn(25, 300)
            val nextPercent = (currentPercent + percentDelta).coerceIn(50, 300) / 100f
            commitLayoutChange { currentControls ->
                currentControls.replaceElement(resizeAroundCenter(target, baseline.width * nextPercent, target.height))
            }
        }

        fun updateSelectedHeight(percentDelta: Int) {
            val selectedElement = selected ?: return
            val target = controls.firstOrNull { it.id == selectedElement.id } ?: selectedElement
            val baseline = defaultLayout.firstOrNull { it.id == target.actionId } ?: target
            val currentPercent = ((target.height / baseline.height.coerceAtLeast(0.001f)) * 100f).roundToInt().coerceIn(25, 300)
            val nextPercent = (currentPercent + percentDelta).coerceIn(50, 300) / 100f
            commitLayoutChange { currentControls ->
                currentControls.replaceElement(resizeAroundCenter(target, target.width, baseline.height * nextPercent))
            }
        }

        fun updateSelectedOpacity(percentDelta: Int) {
            val selectedElement = selected ?: return
            val target = controls.firstOrNull { it.id == selectedElement.id } ?: selectedElement
            val nextOpacity = (target.opacity + percentDelta)
                .coerceIn(CONTROL_OPACITY_MIN, CONTROL_OPACITY_MAX)
            if (nextOpacity == target.opacity) return
            commitLayoutChange { currentControls ->
                currentControls.replaceElement(target.copy(opacity = nextOpacity))
            }
        }

        fun selectElementControl(id: String) {
            selectedId = id
            selectedGroupIds = null
            groupScalePercent = 100
            dragResiduals.clear()
        }

        fun selectControlGroup(ids: Set<String>) {
            if (selectedGroupIds != ids) {
                groupScalePercent = 100
            }
            selectedId = null
            selectedGroupIds = ids
            dragResiduals.clear()
        }

        fun updateGroupScale(percentDelta: Int) {
            val groupIds = selectedGroupIds ?: return
            val nextPercent = (groupScalePercent + percentDelta)
                .coerceIn(GROUP_SCALE_MIN_PERCENT, GROUP_SCALE_MAX_PERCENT)
            if (nextPercent == groupScalePercent) return
            val members = controls.filter { it.id in groupIds }
            if (members.size != groupIds.size) return
            val factor = nextPercent.toFloat() / groupScalePercent.toFloat()
            val scaledMembers = members.scaleGroupAroundCenter(factor)
            commitLayoutChange { currentControls ->
                currentControls.replaceElements(scaledMembers)
            }
            groupScalePercent = (groupScalePercent * scaledMembers.first().width / members.first().width)
                .roundToInt().coerceIn(GROUP_SCALE_MIN_PERCENT, GROUP_SCALE_MAX_PERCENT)
        }

        fun resetSelectedElementOrGroup() {
            val groupIds = selectedGroupIds
            if (groupIds != null) {
                val defaults = defaultLayout.filter { it.id in groupIds }
                if (defaults.size == groupIds.size) {
                    commitLayoutChange { currentControls -> currentControls.replaceElements(defaults) }
                }
                groupScalePercent = 100
                return
            }
            val currentSelected = selected ?: return
            defaultSelected?.let { baseline ->
                val reset = if (currentSelected.id.startsWith("custom_")) {
                    baseline.copy(id = currentSelected.id, x = 0.45f, y = 0.45f,
                        actionId = currentSelected.actionId, secondaryActionId = currentSelected.secondaryActionId,
                        visible = currentSelected.visible)
                } else baseline
                commitLayoutChange { it.replaceElement(reset) }
            }
        }

        fun toggleSelectedAnalogMode() {
            val selectedElement = selected ?: return
            val target = controls.firstOrNull { it.id == selectedElement.id } ?: selectedElement
            val baseline = defaultLayout.firstOrNull { it.id == target.actionId } ?: target
            val nextMode = if (target.analogMode == TouchAnalogMode.TouchArea) {
                TouchAnalogMode.Stick
            } else {
                TouchAnalogMode.TouchArea
            }
            val resized = if (nextMode == TouchAnalogMode.TouchArea) {
                resizeAroundCenter(
                    element = target,
                    nextWidth = maxOf(target.width, baseline.width * 1.8f),
                    nextHeight = maxOf(target.height, baseline.height * 1.1f)
                )
            } else {
                resizeAroundCenter(target, baseline.width, baseline.height)
            }
            commitLayoutChange { currentControls ->
                currentControls.replaceElement(resized.copy(analogMode = nextMode))
            }
        }

        if (editMode && showGrid) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                var x = 0f
                while (x <= size.width) {
                    drawLine(Color.White.copy(alpha = 0.16f), Offset(x, 0f), Offset(x, size.height))
                    x += gridStep
                }
                var y = 0f
                while (y <= size.height) {
                    drawLine(Color.White.copy(alpha = 0.16f), Offset(0f, y), Offset(size.width, y))
                    y += gridStep
                }
            }
        }

        if (editMode) {
            touchControlGroups.forEach { group ->
                val groupElements = controls.filter { it.id in group.ids }
                if (groupElements.size == group.ids.size) {
                    TouchControlGroupFrame(
                        group = group,
                        elements = groupElements,
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasHeight,
                        selected = selectedGroupIds == group.ids,
                        onSelected = { selectControlGroup(group.ids) },
                        onDragStart = { selectControlGroup(group.ids) },
                        snapDrag = ::snapDrag,
                        onGroupChange = { updatedElements ->
                            commitLayoutChange { currentControls ->
                                currentControls.replaceElements(updatedElements)
                            }
                        }
                    )
                }
            }
        }

        val activeInputGroups = touchControlGroups.filter { group ->
            group.ids.all { id -> controls.any { it.id == id && it.visible } }
        }
        val groupHandledControlIds = activeInputGroups.flatMap { it.ids }.toSet()
        fun performTouchHaptic(phase: ButtonPhase) {
            if (touchHaptics) {
                AndroidTouchHaptics.playButton(
                    context = context,
                    view = hapticView,
                    strengthPercent = touchHapticsStrength,
                    preset = touchHapticsPreset,
                    phase = phase
                )
            }
        }

        fun dispatchButtonChange(controlId: Int, pressed: Boolean) {
            // Send the press to the core first. Haptics and Compose state are
            // presentation concerns; doing them before this adds their cost
            // straight onto input latency.
            onButtonChange(controlId, pressed)

            val wasPressed = controlId in hapticPressedControlIds
            if (pressed != wasPressed) {
                hapticPressedControlIds = if (pressed) {
                    hapticPressedControlIds + controlId
                } else {
                    hapticPressedControlIds - controlId
                }
                performTouchHaptic(if (pressed) ButtonPhase.PRESS else ButtonPhase.RELEASE)
            }
        }

        val currentDispatch by rememberUpdatedState<(Int, Boolean) -> Unit>(::dispatchButtonChange)
        val actionTracker = remember { TouchActionTracker { action, pressed -> currentDispatch(action, pressed) } }
        DisposableEffect(actionTracker, editMode) {
            onDispose { actionTracker.cancel() }
        }
        fun dispatchControlChange(id: String, pressed: Boolean) {
            if (!pressed) {
                actionTracker.release(id)
                return
            }
            val element = controls.firstOrNull { it.id == id } ?: return
            val actions = listOfNotNull(element.actionId, element.secondaryActionId)
                .mapNotNull { touchControlDescriptor(it)?.controlId }.toSet()
            actionTracker.press(id, actions)
        }

        fun handleGroupButtonChange(id: String, pressed: Boolean) {
            dispatchControlChange(id, pressed)
            val controlId = controls.firstOrNull { it.id == id }?.actionId?.let(::touchControlDescriptor)?.controlId ?: return
            pressedGroupControlIds = if (pressed) {
                pressedGroupControlIds + controlId
            } else {
                pressedGroupControlIds - controlId
            }
        }

        if (!editMode) {
            activeInputGroups.forEach { group ->
                val groupElements = controls.filter { it.id in group.ids && it.visible }
                if (groupElements.size == group.ids.size) {
                    TouchControlGroupInputCapture(
                        groupElements = groupElements,
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasHeight,
                        onButtonChange = ::handleGroupButtonChange
                    )
                }
            }
        }

        controls.forEach { element ->
            val descriptor = touchControlDescriptor(element.actionId) ?: return@forEach
            if (!editMode && !element.visible) {
                return@forEach
            }
            key(element.id) {
              TouchControlCanvasItem(
                element = element,
                descriptor = descriptor,
                canvasWidth = canvasWidth,
                canvasHeight = canvasHeight,
                alpha = if (editMode && !element.visible) 0.28f else alpha * (element.opacity / 100f),
                selected = editMode && selectedGroupIds == null && selected?.id == element.id,
                editMode = editMode,
                inputHandledByGroup = !editMode && element.id in groupHandledControlIds,
                externallyPressed = descriptor.controlId?.let { it in pressedGroupControlIds } == true,
                visualStyle = visualStyle,
                pressEffect = pressEffect,
                onSelected = { selectElementControl(element.id) },
                snapDrag = ::snapDrag,
                onElementChange = { updated -> commitLayoutChange { currentControls -> currentControls.replaceElement(updated) } },
                onButtonChange = { _, pressed -> dispatchControlChange(element.id, pressed) },
                onAxisChange = onAxisChange
              )
            }
        }

        if (editMode && selected != null && selectedDescriptor != null) {
            val groupActive = selectedGroupIds != null
            key(editorProfileRevision) {
              CompositionLocalProvider(LocalLayoutDirection provides editorLayoutDirection) {
                TouchControlEditorChrome(
                selectedLabel = if (groupActive) {
                    if (TouchControlIds.DPAD_UP in selectedGroupIds.orEmpty()) {
                        stringResource(R.string.controls_editor_group_dpad)
                    } else {
                        stringResource(R.string.controls_editor_group_buttons)
                    }
                } else {
                    listOfNotNull(
                        stringResource(selectedDescriptor.labelRes),
                        selected.secondaryActionId?.let(::touchControlDescriptor)?.let { stringResource(it.labelRes) }
                    ).joinToString(" + ")
                },
                selectedVisible = selected.visible,
                selectedScalePercent = selectedScalePercent,
                onReset = onEditReset,
                onVisibilityToggle = {
                    val currentSelected = controls.firstOrNull { it.id == selected.id } ?: selected
                    commitLayoutChange { currentControls ->
                        currentControls.replaceElement(currentSelected.copy(visible = !currentSelected.visible))
                    }
                },
                onSizeDecrease = { updateSelectedSize(-10) },
                onSizeIncrease = { updateSelectedSize(10) },
                selectedOpacityPercent = selected.opacity,
                onOpacityDecrease = { updateSelectedOpacity(-CONTROL_OPACITY_STEP) },
                onOpacityIncrease = { updateSelectedOpacity(CONTROL_OPACITY_STEP) },
                analogMode = if (!groupActive && selectedIsAnalog) selectedAnalogMode else null,
                touchAreaWidthPercent = selectedWidthPercent,
                touchAreaHeightPercent = selectedHeightPercent,
                onAnalogModeToggle = ::toggleSelectedAnalogMode,
                onTouchAreaWidthDecrease = { updateSelectedWidth(-10) },
                onTouchAreaWidthIncrease = { updateSelectedWidth(10) },
                onTouchAreaHeightDecrease = { updateSelectedHeight(-10) },
                onTouchAreaHeightIncrease = { updateSelectedHeight(10) },
                onDone = onEditDone,
                showDimensions = !groupActive && (!selectedIsAnalog || selectedAnalogMode == TouchAnalogMode.TouchArea),
                canDuplicate = !groupActive && selectedDescriptor.type == TouchControlType.Button && controls.count { it.id.startsWith("custom_") } < 32,
                canDelete = !groupActive && selected.id.startsWith("custom_"),
                canCombo = !groupActive && selectedDescriptor.type == TouchControlType.Button,
                canCreate = controls.count { it.id.startsWith("custom_") } < 32,
                showGrid = showGrid,
                snapToGrid = snapToGrid,
                onDuplicate = ::duplicateSelected,
                onDelete = {
                    commitLayoutChange { it.filterNot { element -> element.id == selected.id } }
                    selectedId = null
                    selectedGroupIds = null
                },
                onCombo = { createCombo = false; comboEditorOpen = true },
                onCreate = { createCombo = true; comboEditorOpen = true },
                onResetSelected = { resetSelectedElementOrGroup() },
                onGridToggle = { showGrid = !showGrid },
                onSnapToggle = { snapToGrid = !snapToGrid; dragResiduals.clear() },
                groupSelected = groupActive,
                groupScalePercent = groupScalePercent,
                onGroupScaleDecrease = { updateGroupScale(-GROUP_SCALE_STEP_PERCENT) },
                onGroupScaleIncrease = { updateGroupScale(GROUP_SCALE_STEP_PERCENT) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .heightIn(max = maxHeight * 0.62f)
                )
              }
            }
        }

        if (editMode && comboEditorOpen) {
          CompositionLocalProvider(LocalLayoutDirection provides editorLayoutDirection) {
            TouchComboEditorDialog(
                actions = listOf(TouchControlIds.DPAD_UP, TouchControlIds.DPAD_DOWN, TouchControlIds.DPAD_LEFT,
                    TouchControlIds.DPAD_RIGHT, TouchControlIds.TRIANGLE, TouchControlIds.CROSS, TouchControlIds.SQUARE,
                    TouchControlIds.CIRCLE, TouchControlIds.L1, TouchControlIds.R1, TouchControlIds.L2, TouchControlIds.R2,
                    TouchControlIds.SELECT, TouchControlIds.START, TouchControlIds.L3, TouchControlIds.R3).mapNotNull { id -> touchControlDescriptor(id)?.let { id to stringResource(it.labelRes) } },
                primary = if (createCombo) TouchControlIds.CROSS else selected?.actionId ?: TouchControlIds.CROSS,
                secondary = if (createCombo) TouchControlIds.L1 else selected?.secondaryActionId,
                primaryEditable = createCombo || selected?.id?.startsWith("custom_") == true,
                onDismiss = { comboEditorOpen = false },
                onConfirm = { primary, secondary ->
                    if (createCombo) {
                        val baseline = defaultLayout.first { it.id == primary }
                        val created = baseline.copy(id = "custom_" + java.util.UUID.randomUUID(),
                            x = 0.45f, y = 0.45f, visible = true, secondaryActionId = secondary)
                        commitLayoutChange { it + created }
                        selectedId = created.id
                    } else if (selected != null) {
                        commitLayoutChange { it.replaceElement(selected.copy(actionId = primary, secondaryActionId = secondary)) }
                    }
                    comboEditorOpen = false
                }
            )
          }
        }
    }
    }
}

private enum class TouchControlType {
    Button,
    Analog
}

private data class TouchControlDescriptor(
    val id: String,
    val labelRes: Int,
    val drawableRes: Int,
    val shape: Shape,
    val type: TouchControlType,
    val controlId: Int? = null,
    val axisX: Int? = null,
    val axisY: Int? = null
)

private data class TouchControlGroup(
    val ids: Set<String>
)

private data class TouchControlGroupBounds(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

private val TouchGroupDragCapturePadding = 28.dp

private val touchControlGroups = listOf(
    TouchControlGroup(
        setOf(
            TouchControlIds.DPAD_UP,
            TouchControlIds.DPAD_DOWN,
            TouchControlIds.DPAD_LEFT,
            TouchControlIds.DPAD_RIGHT
        )
    ),
    TouchControlGroup(
        setOf(
            TouchControlIds.TRIANGLE,
            TouchControlIds.CROSS,
            TouchControlIds.SQUARE,
            TouchControlIds.CIRCLE
        )
    )
)

private fun touchControlDescriptor(id: String): TouchControlDescriptor? = when (id) {
    TouchControlIds.L2 -> TouchControlDescriptor(id, R.string.controls_button_l2, R.drawable.ic_controller_l2_button, RoundedCornerShape(10.dp), TouchControlType.Button, InputOverlay.ControlId.l2)
    TouchControlIds.L1 -> TouchControlDescriptor(id, R.string.controls_button_l1, R.drawable.ic_controller_l1_button, RoundedCornerShape(10.dp), TouchControlType.Button, InputOverlay.ControlId.l1)
    TouchControlIds.R2 -> TouchControlDescriptor(id, R.string.controls_button_r2, R.drawable.ic_controller_r2_button, RoundedCornerShape(10.dp), TouchControlType.Button, InputOverlay.ControlId.r2)
    TouchControlIds.R1 -> TouchControlDescriptor(id, R.string.controls_button_r1, R.drawable.ic_controller_r1_button, RoundedCornerShape(10.dp), TouchControlType.Button, InputOverlay.ControlId.r1)
    TouchControlIds.DPAD_UP -> TouchControlDescriptor(id, R.string.controls_button_up, R.drawable.ic_controller_up_button, RoundedCornerShape(8.dp), TouchControlType.Button, InputOverlay.ControlId.dup)
    TouchControlIds.DPAD_DOWN -> TouchControlDescriptor(id, R.string.controls_button_down, R.drawable.ic_controller_down_button, RoundedCornerShape(8.dp), TouchControlType.Button, InputOverlay.ControlId.ddown)
    TouchControlIds.DPAD_LEFT -> TouchControlDescriptor(id, R.string.controls_button_left, R.drawable.ic_controller_left_button, RoundedCornerShape(8.dp), TouchControlType.Button, InputOverlay.ControlId.dleft)
    TouchControlIds.DPAD_RIGHT -> TouchControlDescriptor(id, R.string.controls_button_right, R.drawable.ic_controller_right_button, RoundedCornerShape(8.dp), TouchControlType.Button, InputOverlay.ControlId.dright)
    TouchControlIds.LEFT_STICK -> TouchControlDescriptor(id, R.string.controls_button_left_stick, R.drawable.ic_controller_analog_base, CircleShape, TouchControlType.Analog, axisX = InputOverlay.ControlId.axis_left_x, axisY = InputOverlay.ControlId.axis_left_y)
    TouchControlIds.RIGHT_STICK -> TouchControlDescriptor(id, R.string.controls_button_right_stick, R.drawable.ic_controller_analog_base, CircleShape, TouchControlType.Analog, axisX = InputOverlay.ControlId.axis_right_x, axisY = InputOverlay.ControlId.axis_right_y)
    TouchControlIds.TRIANGLE -> TouchControlDescriptor(id, R.string.controls_button_triangle, R.drawable.ic_controller_triangle_button, CircleShape, TouchControlType.Button, InputOverlay.ControlId.y)
    TouchControlIds.CROSS -> TouchControlDescriptor(id, R.string.controls_button_cross, R.drawable.ic_controller_cross_button, CircleShape, TouchControlType.Button, InputOverlay.ControlId.a)
    TouchControlIds.SQUARE -> TouchControlDescriptor(id, R.string.controls_button_square, R.drawable.ic_controller_square_button, CircleShape, TouchControlType.Button, InputOverlay.ControlId.x)
    TouchControlIds.CIRCLE -> TouchControlDescriptor(id, R.string.controls_button_circle, R.drawable.ic_controller_circle_button, CircleShape, TouchControlType.Button, InputOverlay.ControlId.b)
    TouchControlIds.L3 -> TouchControlDescriptor(id, R.string.controls_button_l3, R.drawable.ic_controller_l3_button, CircleShape, TouchControlType.Button, InputOverlay.ControlId.l3)
    TouchControlIds.R3 -> TouchControlDescriptor(id, R.string.controls_button_r3, R.drawable.ic_controller_r3_button, CircleShape, TouchControlType.Button, InputOverlay.ControlId.r3)
    TouchControlIds.SELECT -> TouchControlDescriptor(id, R.string.controls_button_select, R.drawable.ic_controller_select_button, RoundedCornerShape(8.dp), TouchControlType.Button, InputOverlay.ControlId.select)
    TouchControlIds.START -> TouchControlDescriptor(id, R.string.controls_button_start, R.drawable.ic_controller_start_button, RoundedCornerShape(8.dp), TouchControlType.Button, InputOverlay.ControlId.start)
    else -> null
}

@Composable
private fun TouchControlGroupFrame(
    group: TouchControlGroup,
    elements: List<TouchControlElement>,
    canvasWidth: Float,
    canvasHeight: Float,
    selected: Boolean,
    onSelected: () -> Unit,
    onDragStart: () -> Unit,
    snapDrag: (String, Float, Float, Offset) -> Offset,
    onGroupChange: (List<TouchControlElement>) -> Unit
) {
    val density = LocalDensity.current
    val latestElements by rememberUpdatedState(elements)
    val currentOnSelected by rememberUpdatedState(onSelected)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentSnapDrag by rememberUpdatedState(snapDrag)
    val currentOnGroupChange by rememberUpdatedState(onGroupChange)
    val bounds = elements.groupBounds()
    val paddingPx = with(density) { 14.dp.toPx() }
    val paddedX = (bounds.x * canvasWidth - paddingPx).coerceAtLeast(0f)
    val paddedY = (bounds.y * canvasHeight - paddingPx).coerceAtLeast(0f)
    val paddedRight = ((bounds.x + bounds.width) * canvasWidth + paddingPx).coerceAtMost(canvasWidth)
    val paddedBottom = ((bounds.y + bounds.height) * canvasHeight + paddingPx).coerceAtMost(canvasHeight)
    val widthPx = (paddedRight - paddedX).coerceAtLeast(1f)
    val heightPx = (paddedBottom - paddedY).coerceAtLeast(1f)

    Box(
        modifier = Modifier
            .offset { IntOffset(paddedX.roundToInt(), paddedY.roundToInt()) }
            .size(
                width = with(density) { widthPx.toDp() },
                height = with(density) { heightPx.toDp() }
            )
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (selected) 0.16f else 0.08f))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = if (selected) 0.75f else 0.42f),
                shape = RoundedCornerShape(18.dp)
            )
            .pointerInput(group.ids, selected) {
                detectTapGestures { currentOnSelected() }
            }
            .pointerInput(group.ids, canvasWidth, canvasHeight) {
                var draggedElements = latestElements
                detectDragGestures(
                    onDragStart = {
                        draggedElements = latestElements
                        currentOnDragStart()
                    }
                ) { change, dragAmount ->
                    change.consume()
                    val bounds = draggedElements.groupBounds()
                    val delta = currentSnapDrag("group:" + group.ids.joinToString(), bounds.x, bounds.y, dragAmount)
                    draggedElements = draggedElements.moveGroupBy(
                        dx = delta.x / canvasWidth,
                        dy = delta.y / canvasHeight
                    )
                    currentOnGroupChange(draggedElements)
                }
            }
    )
}

@Composable
private fun TouchControlGroupInputCapture(
    groupElements: List<TouchControlElement>,
    canvasWidth: Float,
    canvasHeight: Float,
    onButtonChange: (String, Boolean) -> Unit
) {
    val density = LocalDensity.current
    val currentOnButtonChange by rememberUpdatedState(onButtonChange)
    val buttonTracker = remember(groupElements, canvasWidth, canvasHeight) { TouchButtonTracker() }
    val bounds = groupElements.groupBounds()
    val paddingPx = with(density) { TouchGroupDragCapturePadding.toPx() }
    val paddedX = (bounds.x * canvasWidth - paddingPx).coerceAtLeast(0f)
    val paddedY = (bounds.y * canvasHeight - paddingPx).coerceAtLeast(0f)
    val paddedRight = ((bounds.x + bounds.width) * canvasWidth + paddingPx).coerceAtMost(canvasWidth)
    val paddedBottom = ((bounds.y + bounds.height) * canvasHeight + paddingPx).coerceAtMost(canvasHeight)
    val widthPx = (paddedRight - paddedX).coerceAtLeast(1f)
    val heightPx = (paddedBottom - paddedY).coerceAtLeast(1f)

    fun releasePointer(pointerId: Int) {
        buttonTracker.release(pointerId) { index, pressed -> currentOnButtonChange(groupElements[index].id, pressed) }
    }

    fun releaseAll() {
        buttonTracker.cancel { index, pressed -> currentOnButtonChange(groupElements[index].id, pressed) }
    }

    fun controlAt(localX: Float, localY: Float): Int? {
        val absoluteX = paddedX + localX
        val absoluteY = paddedY + localY
        return groupElements.indices.firstOrNull { index ->
            val element = groupElements[index]
            val left = element.x * canvasWidth
            val top = element.y * canvasHeight
            val right = left + element.width * canvasWidth
            val bottom = top + element.height * canvasHeight
            absoluteX in left..right && absoluteY in top..bottom
        }
    }

    fun updatePointer(event: MotionEvent, pointerIndex: Int) {
        val pointerId = event.getPointerId(pointerIndex)
        val nextControl = controlAt(event.getX(pointerIndex), event.getY(pointerIndex))
        buttonTracker.update(pointerId, nextControl) { index, pressed -> currentOnButtonChange(groupElements[index].id, pressed) }
    }

    DisposableEffect(buttonTracker) {
        onDispose { releaseAll() }
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(paddedX.roundToInt(), paddedY.roundToInt()) }
            .size(
                width = with(density) { widthPx.toDp() },
                height = with(density) { heightPx.toDp() }
            )
            .pointerInteropFilter { event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        updatePointer(event, event.actionIndex)
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        for (index in 0 until event.pointerCount) {
                            updatePointer(event, index)
                        }
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                        releasePointer(event.getPointerId(event.actionIndex))
                        true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        releaseAll()
                        true
                    }

                    else -> true
                }
            }
    )
}

@Composable
private fun TouchControlCanvasItem(
    element: TouchControlElement,
    descriptor: TouchControlDescriptor,
    canvasWidth: Float,
    canvasHeight: Float,
    alpha: Float,
    selected: Boolean,
    editMode: Boolean,
    inputHandledByGroup: Boolean,
    externallyPressed: Boolean,
    visualStyle: TouchControlVisualStyle,
    pressEffect: TouchControlPressEffect,
    onSelected: () -> Unit,
    snapDrag: (String, Float, Float, Offset) -> Offset,
    onElementChange: (TouchControlElement) -> Unit,
    onButtonChange: (Int, Boolean) -> Unit,
    onAxisChange: (Int, Short) -> Unit
) {
    val density = LocalDensity.current
    val latestElement by rememberUpdatedState(element)
    val currentOnButtonChange by rememberUpdatedState(onButtonChange)
    val currentOnSelected by rememberUpdatedState(onSelected)
    val currentSnapDrag by rememberUpdatedState(snapDrag)
    val currentOnElementChange by rememberUpdatedState(onElementChange)
    val xPx = element.x * canvasWidth
    val yPx = element.y * canvasHeight
    val widthPx = element.width * canvasWidth
    val heightPx = element.height * canvasHeight
    var pressed by remember(element.id, editMode) { mutableStateOf(false) }
    val pointerOwner = remember(element.id, editMode) { TouchPointerOwner() }
    val itemShape = if (descriptor.type == TouchControlType.Analog && element.analogMode == TouchAnalogMode.TouchArea) {
        RoundedCornerShape(18.dp)
    } else {
        descriptor.shape
    }
    val sizeModifier = Modifier
        .offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) }
        .size(width = with(density) { widthPx.toDp() }, height = with(density) { heightPx.toDp() })

    DisposableEffect(element.id, editMode, inputHandledByGroup, descriptor.type, descriptor.controlId) {
        onDispose {
            if (pointerOwner.cancel() && descriptor.type == TouchControlType.Button) {
                descriptor.controlId?.let { currentOnButtonChange(it, false) }
            }
        }
    }

    val inputModifier = if (editMode) {
        Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelected
            )
            .pointerInput(element.id, canvasWidth, canvasHeight) {
                var draggedElement = latestElement
                detectDragGestures(
                    onDragStart = {
                        draggedElement = latestElement
                        currentOnSelected()
                    }
                ) { change, dragAmount ->
                    change.consume()
                    val delta = currentSnapDrag(element.id, draggedElement.x, draggedElement.y, dragAmount)
                    draggedElement = draggedElement.copy(
                        x = (draggedElement.x + delta.x / canvasWidth).coerceIn(0f, 1f - draggedElement.width),
                        y = (draggedElement.y + delta.y / canvasHeight).coerceIn(0f, 1f - draggedElement.height)
                    )
                    currentOnElementChange(draggedElement)
                }
            }
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.34f),
                shape = itemShape
            )
    } else if (inputHandledByGroup) {
        Modifier
    } else {
        when (descriptor.type) {
            TouchControlType.Button -> Modifier.pointerInteropFilter { event ->
                val controlId = descriptor.controlId ?: return@pointerInteropFilter false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        val pointerId = event.getPointerId(event.actionIndex)
                        if (pointerOwner.acquire(pointerId) && !pressed) {
                            pressed = true
                            currentOnButtonChange(controlId, true)
                        }
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                        val pointerId = event.getPointerId(event.actionIndex)
                        if (pointerOwner.release(pointerId) && pressed) {
                            pressed = false
                            currentOnButtonChange(controlId, false)
                        }
                        true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        if (pointerOwner.cancel() && pressed) {
                            pressed = false
                            currentOnButtonChange(controlId, false)
                        }
                        true
                    }

                    else -> true
                }
            }

            TouchControlType.Analog -> Modifier
        }
    }

    Box(modifier = sizeModifier.testTag("touch_control_${element.id}").then(inputModifier), contentAlignment = Alignment.Center) {
        when (descriptor.type) {
            TouchControlType.Analog -> {
                if (editMode) {
                    if (element.analogMode == TouchAnalogMode.TouchArea) {
                        StaticAnalogTouchArea(alpha = alpha, visualStyle = visualStyle)
                    } else {
                        StaticAnalogStick(alpha = alpha, visualStyle = visualStyle)
                    }
                } else if (element.analogMode == TouchAnalogMode.TouchArea) {
                    AnalogTouchArea(
                        alpha = alpha,
                        visualStyle = visualStyle,
                        pressEffect = pressEffect,
                        onAxisChange = { x, y ->
                            descriptor.axisX?.let { onAxisChange(it, x) }
                            descriptor.axisY?.let { onAxisChange(it, y) }
                        }
                    )
                } else {
                    AnalogStick(
                        analogSize = with(density) { minOf(widthPx, heightPx).toDp() },
                        alpha = alpha,
                        visualStyle = visualStyle,
                        pressEffect = pressEffect,
                        onAxisChange = { x, y ->
                            descriptor.axisX?.let { onAxisChange(it, x) }
                            descriptor.axisY?.let { onAxisChange(it, y) }
                        }
                    )
                }
            }

            TouchControlType.Button -> {
                AssetButton(
                    drawableRes = descriptor.drawableRes,
                    width = with(density) { widthPx.toDp() },
                    height = with(density) { heightPx.toDp() },
                    alpha = alpha,
                    shape = descriptor.shape,
                    pressed = !editMode && (pressed || externallyPressed),
                    visualStyle = visualStyle,
                    pressEffect = pressEffect
                )
                element.secondaryActionId?.let(::touchControlDescriptor)?.let { secondary ->
                    Text(
                        text = "+ " + stringResource(secondary.labelRes),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 3.dp)
                    )
                }

            }
        }
    }
}

@Composable
private fun StaticAnalogTouchArea(alpha: Float, visualStyle: TouchControlVisualStyle) {
    val palette = touchVisualPalette(visualStyle)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(alpha = alpha)
            .background(palette.fill, RoundedCornerShape(18.dp))
            .border(palette.borderWidth, palette.accent.copy(alpha = 0.70f), RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.emulation_controls_editor_touch_area_mode),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = palette.accent
        )
    }
}

@Composable
private fun StaticAnalogStick(alpha: Float, visualStyle: TouchControlVisualStyle) {
    val palette = touchVisualPalette(visualStyle)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(alpha = alpha)
            .background(palette.fill, CircleShape)
            .border(palette.borderWidth, palette.accent.copy(alpha = 0.64f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_controller_analog_base),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            colorFilter = palette.colorFilter
        )
        Image(
            painter = painterResource(R.drawable.ic_controller_analog_stick),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(0.56f),
            contentScale = ContentScale.Fit,
            colorFilter = palette.colorFilter
        )
    }
}

internal fun mergeTouchLayout(
    defaults: List<TouchControlElement>,
    saved: List<TouchControlElement>?
): List<TouchControlElement> {
    val savedById = saved.orEmpty().associateBy { it.id }
    val standard = defaults.map { default ->
        val element = savedById[default.id]
        val descriptor = element?.actionId?.let(::touchControlDescriptor)
        if (element != null && descriptor?.type == touchControlDescriptor(default.id)?.type)
            element.coerceToCanvas()
        else default
    }
    val custom = saved.orEmpty().filter {
        it.id.startsWith("custom_") && touchControlDescriptor(it.actionId)?.type == TouchControlType.Button
    }.distinctBy { it.id }.take(32).map { it.coerceToCanvas() }
    return standard + custom
}

private fun List<TouchControlElement>.replaceElement(updated: TouchControlElement): List<TouchControlElement> {
    return map { element -> if (element.id == updated.id) updated.coerceToCanvas() else element }
}

private fun List<TouchControlElement>.replaceElements(updated: List<TouchControlElement>): List<TouchControlElement> {
    val updatedById = updated.associateBy { it.id }
    return map { element -> updatedById[element.id]?.coerceToCanvas() ?: element }
}

private fun List<TouchControlElement>.groupBounds(): TouchControlGroupBounds {
    val left = minOf { it.x }
    val top = minOf { it.y }
    val right = maxOf { it.x + it.width }
    val bottom = maxOf { it.y + it.height }
    return TouchControlGroupBounds(
        x = left,
        y = top,
        width = right - left,
        height = bottom - top
    )
}

private fun List<TouchControlElement>.moveGroupBy(dx: Float, dy: Float): List<TouchControlElement> {
    if (isEmpty()) return this
    val bounds = groupBounds()
    val clampedDx = dx.coerceIn(-bounds.x, 1f - (bounds.x + bounds.width))
    val clampedDy = dy.coerceIn(-bounds.y, 1f - (bounds.y + bounds.height))
    if (clampedDx == 0f && clampedDy == 0f) return this
    return map { element ->
        element.copy(
            x = element.x + clampedDx,
            y = element.y + clampedDy
        ).coerceToCanvas()
    }
}

private fun TouchControlElement.coerceToCanvas(): TouchControlElement {
    return normalized()
}

@Composable
private fun AssetButton(
    drawableRes: Int,
    width: Dp,
    height: Dp,
    alpha: Float,
    shape: Shape,
    pressed: Boolean,
    visualStyle: TouchControlVisualStyle,
    pressEffect: TouchControlPressEffect,
    modifier: Modifier = Modifier,
    rotation: Float = 0f
) {
    val effectiveVisualStyle = if (
        drawableRes == R.drawable.button_touch_f ||
        drawableRes == R.drawable.button_touch_b
    ) {
        TouchControlVisualStyle.CLASSIC
    } else {
        visualStyle
    }
    VectorOverlayButton(
        drawableRes = drawableRes,
        width = width,
        height = height,
        modifier = modifier.graphicsLayer(rotationZ = rotation),
        shape = shape,
        alpha = alpha,
        pressed = pressed,
        interactive = false,
        visualStyle = effectiveVisualStyle,
        pressEffect = pressEffect
    )
}

@Composable
private fun AnalogTouchArea(
    alpha: Float,
    visualStyle: TouchControlVisualStyle,
    pressEffect: TouchControlPressEffect,
    onAxisChange: (Short, Short) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnAxisChange by rememberUpdatedState(onAxisChange)
    var sizePx by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    var startOffset by remember { mutableStateOf(Offset.Zero) }
    var pressed by remember { mutableStateOf(false) }
    var lastX by remember { mutableIntStateOf(0) }
    var lastY by remember { mutableIntStateOf(0) }
    var activePointerId by remember { mutableIntStateOf(MotionEvent.INVALID_POINTER_ID) }

    fun sendAxis(x: Float, y: Float) {
        val quantizedX = (x * Short.MAX_VALUE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        val quantizedY = (y * Short.MAX_VALUE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        if (quantizedX == lastX && quantizedY == lastY) return
        lastX = quantizedX
        lastY = quantizedY
        currentOnAxisChange(quantizedX.toShort(), quantizedY.toShort())
    }

    fun resetArea() {
        pressed = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        sendAxis(0f, 0f)
    }

    fun updateArea(position: Offset) {
        if (sizePx.width == 0f || sizePx.height == 0f) return
        val maxDistance = (minOf(sizePx.width, sizePx.height) * 0.38f).coerceAtLeast(1f)
        val raw = position - startOffset
        val distance = raw.getDistance()
        val clamped = if (distance > maxDistance && distance > 0f) raw * (maxDistance / distance) else raw
        val nx = (clamped.x / maxDistance).coerceIn(-1f, 1f).let { if (abs(it) < 0.08f) 0f else it }
        val ny = (clamped.y / maxDistance).coerceIn(-1f, 1f).let { if (abs(it) < 0.08f) 0f else it }
        sendAxis(nx, ny)
    }

    DisposableEffect(Unit) {
        onDispose {
            if (lastX != 0 || lastY != 0) {
                currentOnAxisChange(0.toShort(), 0.toShort())
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { sizePx = androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInteropFilter { event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        if (activePointerId == MotionEvent.INVALID_POINTER_ID) {
                            val index = event.actionIndex
                            activePointerId = event.getPointerId(index)
                            pressed = true
                            startOffset = Offset(event.getX(index), event.getY(index))
                            sendAxis(0f, 0f)
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val index = event.findPointerIndex(activePointerId)
                        if (index >= 0) {
                            updateArea(Offset(event.getX(index), event.getY(index)))
                        }
                        true
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_POINTER_UP -> {
                        if (event.getPointerId(event.actionIndex) == activePointerId) resetArea()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        resetArea()
                        true
                    }
                    else -> true
                }
            }
    ) {
        TouchControlVisualLayer(
            alpha = alpha,
            pressed = pressed,
            visualStyle = visualStyle,
            pressEffect = pressEffect,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun AnalogStick(
    analogSize: Dp,
    alpha: Float,
    visualStyle: TouchControlVisualStyle,
    pressEffect: TouchControlPressEffect,
    onAxisChange: (Short, Short) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnAxisChange by rememberUpdatedState(onAxisChange)
    var sizePx by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    var thumbOffset by remember { mutableStateOf(Offset.Zero) }
    var pressed by remember { mutableStateOf(false) }
    var lastX by remember { mutableIntStateOf(0) }
    var lastY by remember { mutableIntStateOf(0) }
    var activePointerId by remember { mutableIntStateOf(MotionEvent.INVALID_POINTER_ID) }

    fun sendAxis(x: Float, y: Float) {
        val quantizedX = (x * Short.MAX_VALUE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        val quantizedY = (y * Short.MAX_VALUE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        if (quantizedX == lastX && quantizedY == lastY) return
        lastX = quantizedX
        lastY = quantizedY
        currentOnAxisChange(quantizedX.toShort(), quantizedY.toShort())
    }

    fun resetStick() {
        pressed = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        thumbOffset = Offset.Zero
        sendAxis(0f, 0f)
    }

    fun updateStick(position: Offset) {
        if (sizePx.width == 0f || sizePx.height == 0f) return
        val center = Offset(sizePx.width / 2f, sizePx.height / 2f)
        val maxDistance = minOf(sizePx.width, sizePx.height) * 0.48f
        val raw = position - center
        val distance = raw.getDistance()
        val clamped = if (distance > maxDistance && distance > 0f) raw * (maxDistance / distance) else raw
        thumbOffset = clamped
        val nx = (clamped.x / maxDistance).coerceIn(-1f, 1f).let { if (abs(it) < 0.12f) 0f else it }
        val ny = (clamped.y / maxDistance).coerceIn(-1f, 1f).let { if (abs(it) < 0.12f) 0f else it }
        sendAxis(nx, ny)
    }

    DisposableEffect(Unit) {
        onDispose {
            if (lastX != 0 || lastY != 0) {
                currentOnAxisChange(0.toShort(), 0.toShort())
            }
        }
    }

    Box(
        modifier = modifier
            .size(analogSize)
            .onSizeChanged { sizePx = androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInteropFilter { event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        if (activePointerId == MotionEvent.INVALID_POINTER_ID) {
                            val index = event.actionIndex
                            activePointerId = event.getPointerId(index)
                            pressed = true
                            updateStick(Offset(event.getX(index), event.getY(index)))
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val index = event.findPointerIndex(activePointerId)
                        if (index >= 0) {
                            updateStick(Offset(event.getX(index), event.getY(index)))
                        }
                        true
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_POINTER_UP -> {
                        if (event.getPointerId(event.actionIndex) == activePointerId) resetStick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        resetStick()
                        true
                    }
                    else -> true
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val maxDistance = minOf(sizePx.width, sizePx.height) * 0.48f
        VectorAnalogStick(
            analogSize = analogSize,
            analogWidth = analogSize,
            analogHeight = analogSize,
            alpha = alpha,
            visualX = if (maxDistance > 0f) thumbOffset.x / maxDistance else 0f,
            visualY = if (maxDistance > 0f) thumbOffset.y / maxDistance else 0f,
            interactive = false,
            visualStyle = visualStyle,
            pressEffect = pressEffect,
            pressed = pressed
        )
    }
}

private data class TouchVisualPalette(
    val accent: Color,
    val fill: Color,
    val borderWidth: Dp,
    val colorFilter: ColorFilter?
)

@Composable
private fun touchVisualPalette(style: TouchControlVisualStyle): TouchVisualPalette = when (style) {
    TouchControlVisualStyle.CLASSIC -> TouchVisualPalette(
        accent = Color.White,
        fill = Color.Transparent,
        borderWidth = 0.dp,
        colorFilter = null
    )
    TouchControlVisualStyle.LEGACY -> TouchVisualPalette(
        accent = Color.White,
        fill = Color.White.copy(alpha = 0.09f),
        borderWidth = 1.dp,
        colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.94f))
    )
    TouchControlVisualStyle.MODERN -> TouchVisualPalette(
        accent = MaterialTheme.colorScheme.primary,
        fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
        borderWidth = 2.dp,
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
    )
    TouchControlVisualStyle.ARCADE -> TouchVisualPalette(
        accent = Color(0xFFFFD166),
        fill = Color(0xFF653754).copy(alpha = 0.72f),
        borderWidth = 2.dp,
        colorFilter = ColorFilter.tint(Color(0xFFFFD166))
    )
    TouchControlVisualStyle.MINIMAL -> TouchVisualPalette(
        accent = Color.White.copy(alpha = 0.74f),
        fill = Color.Transparent,
        borderWidth = 1.dp,
        colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.74f))
    )
}

@Composable
private fun TouchControlVisualLayer(
    alpha: Float,
    pressed: Boolean,
    visualStyle: TouchControlVisualStyle,
    pressEffect: TouchControlPressEffect,
    shape: Shape,
    modifier: Modifier = Modifier
) {
    val palette = touchVisualPalette(visualStyle)
    Box(
        modifier = modifier
            .graphicsLayer(alpha = alpha)
            .background(
                if (pressed && pressEffect == TouchControlPressEffect.GLOW) {
                    palette.accent.copy(alpha = 0.30f)
                } else {
                    palette.fill
                },
                shape
            )
            .border(
                if (pressed && pressEffect == TouchControlPressEffect.GLOW) 3.dp else palette.borderWidth,
                palette.accent.copy(
                    alpha = if (pressed && pressEffect == TouchControlPressEffect.GLOW) 0.95f else 0.66f
                ),
                shape
            )
    )
}
