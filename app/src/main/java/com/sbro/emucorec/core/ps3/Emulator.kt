package com.sbro.emucorec.core.ps3

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import com.sbro.emucorec.R
import com.sbro.emucorec.core.BackupSessionGate
import com.sbro.emucorec.core.EmulatorStorage
import com.sbro.emucorec.core.PlayTimeRepository
import com.sbro.emucorec.core.Ps3CoreConfig
import com.sbro.emucorec.core.Ps3CoreConfigRepository
import com.sbro.emucorec.core.Ps3GameSettingsRepository
import com.sbro.emucorec.core.Ps3Runtime
import com.sbro.emucorec.core.input.InputDeviceClassifier
import com.sbro.emucorec.core.input.KeyboardMouseManager
import com.sbro.emucorec.core.ps3.overlay.InputOverlay
import com.sbro.emucorec.data.AppPreferences
import com.sbro.emucorec.data.InstalledGameRepository
import com.sbro.emucorec.data.ProfilePlayTimeSyncer
import com.sbro.emucorec.data.TrophyCloudRepository
import com.sbro.emucorec.data.drive.DriveBackupWork
import com.sbro.emucorec.discord.DiscordIntegration
import com.sbro.emucorec.ui.common.ImmersiveMode
import com.sbro.emucorec.ui.emulation.EmulationOverlayHost
import com.sbro.emucorec.ui.theme.EmuCoreCTheme
import com.sbro.emucorec.core.BootResult
import com.sbro.emucorec.core.EmulatorState
import com.sbro.emucorec.core.Ps3Core
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/** PS3 emulation activity: RPCS3 surface, RPCS3 lifecycle, and the existing EmuCoreC overlay. */
@android.annotation.SuppressLint("RestrictedApi")
class Emulator : AppCompatActivity(), InputManager.InputDeviceListener {
    var preparingGame by mutableStateOf(true)
        private set
    var currentGameTitle by mutableStateOf("")
        private set
    var currentGameIconPath by mutableStateOf<String?>(null)
        private set
    private var _currentGameId by mutableStateOf("")
    val currentGameId: String get() = _currentGameId

    private lateinit var surfaceView: EmuSurface
    private lateinit var inputOverlay: InputOverlay
    private var inputManager: InputManager? = null
    private val bootStarted = AtomicBoolean(false)
    private var bootThread: Thread? = null
    private var exiting = false
    @Volatile private var finishPending = false
    private val bootGate = Any()
    // Generations are guarded by bootGate. bootGeneration identifies the newest
    // requested launch; a boot thread may only start its title while its own
    // generation is still current. finishGeneration invalidates an in-flight
    // exit when a new launch supersedes it.
    private var bootGeneration = 0
    private var finishGeneration = 0
    private var menuPaused = false
    private var lifecyclePaused = false

    private var overlayBackHandler: (() -> Boolean)? = null
    private var overlayMenuButtonRevealHandler: (() -> Unit)? = null
    private var overlayPauseMenuOpenHandler: (() -> Unit)? = null

    private var playTimeSessionId: String? = null
    private var playTimeSessionTitleId = ""
    private var playTimeSessionStartedAt = 0L
    private var playTimeAccumulatedMs = 0L
    @Volatile
    private var bootSucceeded = false

    var hasPhysicalGamepad by mutableStateOf(false)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        super.onCreate(savedInstanceState)

        Ps3Runtime.stop()
        // No EmulatorStorage.prepareRuntime here: it does disk work (dirs, the
        // bundled resources) and used to run on this UI thread during launch.
        // Ps3Runtime.ensureInitialized performs it on the boot worker before the
        // core initializes, which is also the only place it is required.
        Ps3CoreConfigRepository(this).ensureDefaultsPersisted()
        _currentGameId = gameIdFromIntent(intent)
        inputOverlay = InputOverlay(this)

        val root = FrameLayout(this)
        surfaceView = EmuSurface(this)
        root.addView(surfaceView, matchParentLayoutParams())
        root.addView(createComposeOverlay(), matchParentLayoutParams())
        setContentView(root)

        KeyboardMouseManager.ensureInitialized(this)
        KeyboardMouseManager.attachSink(inputOverlay)
        KeyboardMouseManager.setPointerCaptureListener { enabled ->
            runOnUiThread { updatePointerCapture(enabled) }
        }
        window.decorView.setOnCapturedPointerListener { _, event ->
            KeyboardMouseManager.handleCapturedPointerEvent(event)
        }

        inputManager = getSystemService(InputManager::class.java)
        inputManager?.registerInputDeviceListener(this, null)
        refreshPhysicalGamepadState()
        refreshGamepadRuntimeInputSettings()
        refreshKeyboardMouseRuntimeSettings()
        val preferences = AppPreferences(this)
        if (preferences.keepScreenOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        hideSystemBars()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        finishPlayTimeSessionIfNeeded()
        setIntent(intent)
        synchronized(bootGate) {
            // A launch supersedes any pending exit: the exit coroutine watches
            // finishGeneration and must not tear the activity down under the
            // game the user just asked for. This is the singleInstance reuse
            // path -- tapping another game while this activity still exists.
            finishGeneration++
            finishPending = false
            bootGeneration++
        }
        exiting = false
        Ps3Runtime.stop()
        _currentGameId = gameIdFromIntent(intent)
        bootStarted.set(false)
        bootSucceeded = false
        preparingGame = true
        currentGameTitle = ""
        currentGameIconPath = null
        playTimeAccumulatedMs = 0L
        refreshGamepadRuntimeInputSettings()
        refreshKeyboardMouseRuntimeSettings()
        onEmulationSurfaceReady()
    }

    override fun onResume() {
        super.onResume()
        DiscordIntegration.setPaused(false)
        lifecyclePaused = false
        if (!menuPaused) Ps3Runtime.resume()
        refreshPhysicalGamepadState()
        refreshGamepadRuntimeInputSettings()
        refreshKeyboardMouseRuntimeSettings()
        KeyboardMouseManager.setEmulationInputEnabled(true)
        KeyboardMouseManager.refreshConnectionState()
        KeyboardMouseManager.refreshPointerCapture()
        hideSystemBars()
        onEmulationSurfaceReady()
        if (bootSucceeded) startPlayTimeSessionIfNeeded()
    }

    override fun onPause() {
        lifecyclePaused = true
        DiscordIntegration.setPaused(true)
        finishPlayTimeSessionIfNeeded(accumulate = true)
        Ps3Runtime.pause()
        KeyboardMouseManager.cancelBindingCapture()
        KeyboardMouseManager.releaseAll()
        KeyboardMouseManager.setEmulationInputEnabled(false)
        updatePointerCapture(false)
        super.onPause()
    }

    override fun onDestroy() {
        finishPlayTimeSessionIfNeeded(scheduleBackup = true)
        DiscordIntegration.clearGame()
        inputManager?.unregisterInputDeviceListener(this)
        inputManager = null
        KeyboardMouseManager.releaseAll()
        KeyboardMouseManager.setEmulationInputEnabled(false)
        KeyboardMouseManager.setPointerCaptureListener(null)
        KeyboardMouseManager.detachSink(inputOverlay)
        window.decorView.setOnCapturedPointerListener(null)
        inputOverlay.dispose()
        bootThread?.interrupt()
        if (isFinishing) Ps3Runtime.stop() else Ps3Runtime.detachSurface()
        super.onDestroy()
    }

    override fun finish() {
        if (isFinishing) return
        val token: Int
        synchronized(bootGate) {
            if (finishPending) return
            finishPending = true
            token = ++finishGeneration
        }
        if (!Ps3Core.initialized) {
            finishAfterStop()
            return
        }
        // Android abandons SurfaceView as soon as the activity finishes. Keep
        // its producer alive until a pending restore and VM shutdown settle.
        // Bounded: a teardown that never converges must not pin the activity
        // (and the library behind it) forever.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                synchronized(bootGate) { Ps3Runtime.requestStop() }
                var waitedMs = 0L
                while (Ps3Core.instance.saveStateBusy() || Ps3Runtime.state() != EmulatorState.Stopped) {
                    if (waitedMs >= STOP_WAIT_LIMIT_MS) {
                        Log.w(TAG, "Emulator did not reach stopped within ${STOP_WAIT_LIMIT_MS}ms; finishing anyway")
                        break
                    }
                    delay(25)
                    waitedMs += 25
                }
            }
            if (synchronized(bootGate) { finishPending && finishGeneration == token }) {
                finishAfterStop()
            }
        }
    }

    private fun finishAfterStop() = super.finish()

    internal fun emulationSurface() = surfaceView
    fun isSaveStateLaunch(): Boolean = gamePathFromIntent(intent).let {
        it.endsWith(".SAVESTAT") || it.endsWith(".SAVESTAT.zst") || it.endsWith(".SAVESTAT.gz")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            KeyboardMouseManager.refreshConnectionState()
            KeyboardMouseManager.refreshPointerCapture()
        } else {
            KeyboardMouseManager.releaseAll()
            updatePointerCapture(false)
        }
    }

    private fun updatePointerCapture(enabled: Boolean) {
        val decorView = window.decorView
        if (enabled && hasWindowFocus()) {
            decorView.isFocusable = true
            decorView.isFocusableInTouchMode = true
            if (!decorView.hasFocus()) decorView.requestFocus()
            if (!decorView.hasPointerCapture()) {
                decorView.post {
                    if (!isFinishing && hasWindowFocus() && !decorView.hasPointerCapture()) {
                        decorView.requestPointerCapture()
                    }
                }
            }
        } else {
            if (decorView.hasPointerCapture()) {
                decorView.releasePointerCapture()
            }
            decorView.isFocusable = false
            decorView.isFocusableInTouchMode = false
            decorView.clearFocus()
        }
    }

    fun onEmulationSurfaceReady() {
        if (finishPending || isFinishing) return
        if (!::surfaceView.isInitialized || !surfaceView.holder.surface.isValid) return
        val currentWidth = surfaceView.width
        val currentHeight = surfaceView.height
        if (currentWidth <= 1 || currentHeight <= 1) return
        if (!bootStarted.compareAndSet(false, true)) return
        // Bind this boot to its request. The singleInstance activity is reused
        // for the next game (onNewIntent), so two boot threads can overlap: the
        // generation makes sure only the newest one actually starts a title,
        // and the last requested launch always wins.
        val generation = synchronized(bootGate) { ++bootGeneration }
        val requestedIntent = Intent(intent)
        val requestedId = gameIdFromIntent(requestedIntent)

        bootThread = thread(name = "emucorec-ps3-boot") {
            if (!runCatching { Ps3Runtime.ensureInitialized(this) }.getOrDefault(false)) {
                showBootFailure(getString(R.string.ps3_core_settings_unavailable), generation)
                return@thread
            }
            val requestedPath = gamePathFromIntent(requestedIntent)
            val metadata = runCatching { InstalledGameRepository().findByTitleId(this, requestedId) }.getOrNull()
            surfaceView.post {
                if (!surfaceView.holder.surface.isValid || isFinishing || generation != bootGeneration) return@post
                currentGameTitle = metadata?.title.orEmpty().ifBlank { requestedId }
                currentGameIconPath = metadata?.iconPath
                val width = surfaceView.width.coerceAtLeast(1)
                val height = surfaceView.height.coerceAtLeast(1)
                Ps3Runtime.attachSurface(
                    surfaceView.holder.surface,
                    width,
                    height,
                    surfaceView.display?.refreshRate?.toDouble()
                        ?.takeIf { it.isFinite() && it >= 20.0 } ?: 60.0,
                )
                // Android preparation ends here. Native boot owns its PPU/SPU progress
                // and may keep its call active while the guest is already running.
                preparingGame = false
                bootThread = thread(name = "emucorec-ps3-game") {
                    val result = synchronized(bootGate) {
                        if (finishPending || isFinishing || generation != bootGeneration) {
                            // Superseded by a newer launch (or an exit) while
                            // this thread was starting: never boot a stale title.
                            return@thread
                        }
                        runCatching { Ps3Runtime.boot(this, requestedPath, requestedId) }
                            .onFailure { Log.e(TAG, "PS3 boot threw", it) }
                            .getOrDefault(BootResult.GenericError)
                    }
                    if (result != BootResult.NoErrors && result != BootResult.AlreadyAdded) {
                        showBootFailure(result.name, generation)
                    } else {
                        val nativeId = runCatching { Ps3Core.instance.getTitleId() }.getOrDefault("")
                        runOnUiThread {
                            if (generation != bootGeneration || finishPending || isFinishing) return@runOnUiThread
                            preparingGame = false
                            bootSucceeded = true
                            nativeId.takeIf(String::isNotBlank)?.let(::setCurrentGameId)
                            if (!lifecyclePaused) startPlayTimeSessionIfNeeded()
                        }
                    }
                }
            }
        }
    }

    private fun showBootFailure(reason: String, generation: Int) {
        Log.e(TAG, "PS3 boot failed: $reason")
        runOnUiThread {
            if (generation == bootGeneration && !finishPending && !isFinishing) {
                preparingGame = false
                Toast.makeText(this, "${getString(R.string.game_launch_failed)} ($reason)", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun createComposeOverlay() = ComposeView(this).apply {
        setBackgroundColor(Color.TRANSPARENT)
        elevation = EMULATION_OVERLAY_ELEVATION
        translationZ = EMULATION_OVERLAY_ELEVATION
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        val preferences = AppPreferences(this@Emulator)
        setContent {
            val customizationPreferences = remember { com.sbro.emucorec.data.CustomizationPreferences(this@Emulator) }
            val customization by customizationPreferences.settings.collectAsState()
            DisposableEffect(customizationPreferences) {
                onDispose { customizationPreferences.close() }
            }
            val themeMode by preferences.themeModeFlow.collectAsState(initial = preferences.themeMode)
            EmuCoreCTheme(themeMode = themeMode, customization = customization, enableCrtOverlay = false) {
                EmulationOverlayHost(activity = this@Emulator)
            }
        }
    }

    private fun matchParentLayoutParams() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    @Keep
    fun setCurrentGameId(gameId: String) {
        if (gameId.isBlank()) return
        _currentGameId = gameId
        refreshGamepadRuntimeInputSettings()
        refreshKeyboardMouseRuntimeSettings()
        if (bootSucceeded && !lifecyclePaused) startPlayTimeSessionIfNeeded()
    }

    @Keep
    fun getBaseStoragePath(): String = EmulatorStorage.runtimeRoot(this).absolutePath

    fun currentGameIdOrIntent(): String = currentGameId.ifBlank { gameIdFromIntent(intent) }

    fun getmOverlay(): InputOverlay = inputOverlay

    fun setOverlayBackHandler(handler: (() -> Boolean)?) { overlayBackHandler = handler }
    fun setOverlayMenuButtonRevealHandler(handler: (() -> Unit)?) { overlayMenuButtonRevealHandler = handler }
    fun setOverlayPauseMenuOpenHandler(handler: (() -> Unit)?) { overlayPauseMenuOpenHandler = handler }

    fun requestOverlayMenuButtonReveal() {
        runOnUiThread { overlayMenuButtonRevealHandler?.invoke() }
    }

    @Keep
    fun openPauseMenuFromController() {
        runOnUiThread { overlayPauseMenuOpenHandler?.invoke() }
    }

    @Keep
    fun setControllerOverlayState(overlayMask: Int, edit: Boolean, reset: Boolean) {
        inputOverlay.setState(overlayMask)
        inputOverlay.setIsInEditMode(edit)
        if (reset) inputOverlay.resetButtonPlacement()
    }

    @Keep
    fun setControllerOverlayScale(scale: Float) = inputOverlay.setScale(scale)

    @Keep
    fun setControllerOverlayOpacity(opacity: Int) = inputOverlay.setOpacity(opacity)

    /** Opens the native RPCS3 home menu exposed by the RPCS3 Android bridge. */
    fun openCoreHomeMenu(): Boolean = runCatching {
        if (!Ps3Core.initialized) return false
        Ps3Core.instance.openHomeMenu()
        true
    }.getOrDefault(false)

    fun getRunningGameTitle(): String {
        val id = currentGameIdOrIntent()
        return currentGameTitle.ifBlank { id }
    }

    fun setMenuPaused(paused: Boolean) {
        menuPaused = paused
        if (paused || lifecyclePaused) Ps3Runtime.pause() else Ps3Runtime.resume()
        if (!paused) hideSystemBars()
    }

    fun exitEmulation() {
        if (exiting) return
        exiting = true
        finishPlayTimeSessionIfNeeded()
        finish()
    }

    fun currentPlayTimeElapsedMs(): Long = playTimeAccumulatedMs +
        (playTimeSessionStartedAt
            .takeIf { it > 0L }
            ?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) }
            ?: 0L)

    fun updateGamepadRuntimeInputSettings(config: Ps3CoreConfig) {
        inputOverlay.synchronizeConfig(config)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (KeyboardMouseManager.handleKeyBindingCapture(event)) return true
        if (KeyboardMouseManager.handleKeyEvent(event)) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            if (overlayBackHandler?.invoke() == true) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (KeyboardMouseManager.handleMouseBindingCapture(event)) return true
        if (isMouseEvent(event) && KeyboardMouseManager.handleMouseButtonEvent(event)) {
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (KeyboardMouseManager.handleMouseBindingCapture(event)) return true
        if (isMouseEvent(event)) {
            val handled = KeyboardMouseManager.handleMouseButtonEvent(event) ||
                KeyboardMouseManager.handleMouseWheelEvent(event) ||
                KeyboardMouseManager.handleMouseMotionEvent(event)
            if (handled) return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun isMouseEvent(event: MotionEvent): Boolean =
        event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)

    // Physical gamepad input, delivered at the Activity level:
    // Activity-level onKeyDown/onKeyUp with a persistent pad state pushed on
    // every event. Not routed through the Compose tree, not gated on the touch
    // overlay, so a pad works regardless of overlay visibility or classifier
    // heuristics.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null || (event.source and GAMEPAD_SOURCES) == 0 || event.repeatCount != 0) {
            return super.onKeyDown(keyCode, event)
        }
        val control = keyCodeToControl(keyCode) ?: return super.onKeyDown(keyCode, event)
        inputOverlay.setPhysicalButton(control, true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null || (event.source and GAMEPAD_SOURCES) == 0) {
            return super.onKeyUp(keyCode, event)
        }
        val control = keyCodeToControl(keyCode) ?: return super.onKeyUp(keyCode, event)
        inputOverlay.setPhysicalButton(control, false)
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK ||
            event.action != MotionEvent.ACTION_MOVE
        ) return super.onGenericMotionEvent(event)

        inputOverlay.setPhysicalAxis(InputOverlay.ControlId.axis_left_x, event.axisAsShort(MotionEvent.AXIS_X))
        inputOverlay.setPhysicalAxis(InputOverlay.ControlId.axis_left_y, event.axisAsShort(MotionEvent.AXIS_Y))
        inputOverlay.setPhysicalAxis(InputOverlay.ControlId.axis_right_x, event.axisAsShort(MotionEvent.AXIS_Z))
        inputOverlay.setPhysicalAxis(InputOverlay.ControlId.axis_right_y, event.axisAsShort(MotionEvent.AXIS_RZ))
        inputOverlay.setPhysicalTrigger(InputOverlay.ControlId.l2, event.getAxisValue(MotionEvent.AXIS_LTRIGGER))
        inputOverlay.setPhysicalTrigger(InputOverlay.ControlId.r2, event.getAxisValue(MotionEvent.AXIS_RTRIGGER))

        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        inputOverlay.setPhysicalButton(InputOverlay.ControlId.dleft, hatX < -HAT_THRESHOLD)
        inputOverlay.setPhysicalButton(InputOverlay.ControlId.dright, hatX > HAT_THRESHOLD)
        inputOverlay.setPhysicalButton(InputOverlay.ControlId.dup, hatY < -HAT_THRESHOLD)
        inputOverlay.setPhysicalButton(InputOverlay.ControlId.ddown, hatY > HAT_THRESHOLD)
        return true
    }

    private fun MotionEvent.axisAsShort(axis: Int): Short =
        (getAxisValue(axis).coerceIn(-1f, 1f) * Short.MAX_VALUE)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()

    private fun keyCodeToControl(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> InputOverlay.ControlId.a
        KeyEvent.KEYCODE_BUTTON_B -> InputOverlay.ControlId.b
        KeyEvent.KEYCODE_BUTTON_X -> InputOverlay.ControlId.x
        KeyEvent.KEYCODE_BUTTON_Y -> InputOverlay.ControlId.y
        KeyEvent.KEYCODE_BUTTON_SELECT -> InputOverlay.ControlId.select
        KeyEvent.KEYCODE_BUTTON_START -> InputOverlay.ControlId.start
        KeyEvent.KEYCODE_BUTTON_MODE -> InputOverlay.ControlId.guide
        KeyEvent.KEYCODE_BUTTON_L1 -> InputOverlay.ControlId.l1
        KeyEvent.KEYCODE_BUTTON_R1 -> InputOverlay.ControlId.r1
        KeyEvent.KEYCODE_BUTTON_L2 -> InputOverlay.ControlId.l2
        KeyEvent.KEYCODE_BUTTON_R2 -> InputOverlay.ControlId.r2
        KeyEvent.KEYCODE_BUTTON_THUMBL -> InputOverlay.ControlId.l3
        KeyEvent.KEYCODE_BUTTON_THUMBR -> InputOverlay.ControlId.r3
        KeyEvent.KEYCODE_DPAD_UP -> InputOverlay.ControlId.dup
        KeyEvent.KEYCODE_DPAD_DOWN -> InputOverlay.ControlId.ddown
        KeyEvent.KEYCODE_DPAD_LEFT -> InputOverlay.ControlId.dleft
        KeyEvent.KEYCODE_DPAD_RIGHT -> InputOverlay.ControlId.dright
        else -> null
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        InputDeviceClassifier.invalidateDevice(deviceId)
        refreshPhysicalGamepadState()
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        InputDeviceClassifier.invalidateDevice(deviceId)
        refreshPhysicalGamepadState()
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        InputDeviceClassifier.invalidateDevice(deviceId)
        refreshPhysicalGamepadState()
    }

    private fun refreshPhysicalGamepadState() {
        hasPhysicalGamepad = InputDevice.getDeviceIds().any(InputDeviceClassifier::isPhysicalGameController)
        if (::inputOverlay.isInitialized) {
            inputOverlay.setPhysicalControllerPresent(hasPhysicalGamepad)
        }
    }

    private fun refreshGamepadRuntimeInputSettings() {
        val gameId = currentGameIdOrIntent()
        val config = if (gameId.isNotBlank()) {
            Ps3GameSettingsRepository(this).loadEffective(gameId)
        } else {
            Ps3CoreConfigRepository(this).load()
        }
        updateGamepadRuntimeInputSettings(config)
    }

    private fun refreshKeyboardMouseRuntimeSettings() {
        val gameId = currentGameIdOrIntent()
        val override = if (gameId.isNotBlank()) {
            Ps3GameSettingsRepository(this).loadProfile(gameId).keyboardMouse
        } else {
            null
        }
        KeyboardMouseManager.setPerGameOverride(override)
    }

    private fun startPlayTimeSessionIfNeeded() {
        val gameId = currentGameIdOrIntent().trim()
        if (gameId.isBlank()) return
        if (playTimeSessionId != null && playTimeSessionTitleId.equals(gameId, true)) return
        finishPlayTimeSessionIfNeeded()
        val title = currentGameTitle.ifBlank { gameId }
        val session = PlayTimeRepository(this).startSession(gameId, title) ?: return
        playTimeSessionId = session.id
        playTimeSessionTitleId = gameId
        playTimeSessionStartedAt = session.startedAt
        BackupSessionGate.gameStarted()
        DiscordIntegration.setPlaying(title, gameId)
    }

    private fun finishPlayTimeSessionIfNeeded(accumulate: Boolean = false, scheduleBackup: Boolean = false) {
        val endedAt = System.currentTimeMillis()
        val sessionId = playTimeSessionId
        if (sessionId != null) {
            PlayTimeRepository(this).finishSession(sessionId, endedAt)
            val durationMs = (endedAt - playTimeSessionStartedAt).coerceAtLeast(0L)
            val titleId = playTimeSessionTitleId
            if (titleId.isNotBlank() && durationMs > 0L) {
                val title = currentGameTitle.ifBlank { titleId }
                ProfilePlayTimeSyncer.recordAndSync(applicationContext, titleId, title, durationMs)
            }
        }
        if (accumulate && playTimeSessionStartedAt > 0L) {
            playTimeAccumulatedMs += (endedAt - playTimeSessionStartedAt).coerceAtLeast(0L)
        }
        playTimeSessionId = null
        playTimeSessionTitleId = ""
        playTimeSessionStartedAt = 0L
        if (scheduleBackup) {
            BackupSessionGate.stopped()
            DriveBackupWork.afterGame(applicationContext)
            TrophyCloudRepository.syncAsync(applicationContext)
        }
    }

    private fun hideSystemBars() = ImmersiveMode.apply(window)

    @Keep
    fun createShortcut(gameId: String, gameName: String): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) return false
        val iconFile = File(getExternalFilesDir(null), "cache/icons/$gameId.png")
        val icon: Bitmap = if (iconFile.isFile) {
            BitmapFactory.decodeFile(iconFile.path)
        } else {
            BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        }
        val launchIntent = Intent(this, Emulator::class.java).apply {
            putExtra(EXTRA_TITLE_ID, gameId)
            InstalledGameRepository().findByTitleId(this@Emulator, gameId)?.installPath
                ?.takeIf(String::isNotBlank)
                ?.let { putExtra(EXTRA_GAME_PATH, it) }
            action = "LAUNCH_$gameId"
        }
        val shortcut = ShortcutInfoCompat.Builder(this, gameId)
            .setShortLabel(gameName)
            .setLongLabel(gameName)
            .setIcon(IconCompat.createWithBitmap(icon))
            .setIntent(launchIntent)
            .build()
        return ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)
    }

    @Keep
    fun requestInstallUpdate() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/sashkinbro/EmuCoreC/releases")))
        }
    }

    private fun gameIdFromIntent(source: Intent?): String = source?.getStringExtra(EXTRA_TITLE_ID)
        ?.takeIf(String::isNotBlank)
        ?: source?.getStringExtra(EXTRA_GAME_ID)?.takeIf(String::isNotBlank)
        ?: source?.action?.takeIf { it.startsWith("LAUNCH_") }?.removePrefix("LAUNCH_")
        ?: ""

    private fun gamePathFromIntent(source: Intent?): String {
        source?.getStringExtra(EXTRA_GAME_PATH)
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        source?.data?.path
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        val titleId = gameIdFromIntent(source)
        return InstalledGameRepository().findByTitleId(this, titleId)?.installPath.orEmpty()
    }

    companion object {
        const val EXTRA_TITLE_ID = "titleId"
        const val EXTRA_GAME_ID = "gameId"
        const val EXTRA_GAME_PATH = "gamePath"
        private const val TAG = "EmuCoreCEmulator"
        private const val EMULATION_OVERLAY_ELEVATION = 64f
        private const val HAT_THRESHOLD = 0.25f
        private const val STOP_WAIT_LIMIT_MS = 20_000L
        private val GAMEPAD_SOURCES =
            InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD
    }
}
