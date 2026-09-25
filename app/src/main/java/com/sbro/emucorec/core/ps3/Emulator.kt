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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.sbro.emucorec.R
import com.sbro.emucorec.core.EmulatorStorage
import com.sbro.emucorec.core.PlayTimeRepository
import com.sbro.emucorec.core.Ps3CoreConfig
import com.sbro.emucorec.core.Ps3CoreConfigRepository
import com.sbro.emucorec.core.Ps3GameSettingsRepository
import com.sbro.emucorec.core.Ps3Runtime
import com.sbro.emucorec.core.input.InputDeviceClassifier
import com.sbro.emucorec.core.ps3.overlay.InputOverlay
import com.sbro.emucorec.data.AppPreferences
import com.sbro.emucorec.data.InstalledGameRepository
import com.sbro.emucorec.discord.DiscordIntegration
import com.sbro.emucorec.ui.common.ImmersiveMode
import com.sbro.emucorec.ui.emulation.EmulationOverlayHost
import com.sbro.emucorec.ui.theme.EmuCoreCTheme
import net.rpcsx.BootResult
import net.rpcsx.RPCSX
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/** PS3 emulation activity: RPCS3 surface, RPCS3 lifecycle, and the existing EmuCoreC overlay. */
@android.annotation.SuppressLint("RestrictedApi")
class Emulator : AppCompatActivity(), InputManager.InputDeviceListener {
    private var _currentGameId by mutableStateOf("")
    val currentGameId: String get() = _currentGameId

    private lateinit var surfaceView: EmuSurface
    private lateinit var inputOverlay: InputOverlay
    private var inputManager: InputManager? = null
    private val bootStarted = AtomicBoolean(false)
    private var bootThread: Thread? = null
    private var exiting = false
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
        EmulatorStorage.prepareRuntime(this)
        Ps3CoreConfigRepository(this).ensureDefaultsPersisted()
        _currentGameId = gameIdFromIntent(intent)
        inputOverlay = InputOverlay(this)

        val root = FrameLayout(this)
        surfaceView = EmuSurface(this)
        root.addView(surfaceView, matchParentLayoutParams())
        root.addView(createComposeOverlay(), matchParentLayoutParams())
        setContentView(root)

        inputManager = getSystemService(InputManager::class.java)
        inputManager?.registerInputDeviceListener(this, null)
        refreshPhysicalGamepadState()
        refreshGamepadRuntimeInputSettings()
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
        Ps3Runtime.stop()
        _currentGameId = gameIdFromIntent(intent)
        bootStarted.set(false)
        bootSucceeded = false
        playTimeAccumulatedMs = 0L
        refreshGamepadRuntimeInputSettings()
        onEmulationSurfaceReady()
    }

    override fun onResume() {
        super.onResume()
        DiscordIntegration.setPaused(false)
        lifecyclePaused = false
        if (!menuPaused) Ps3Runtime.resume()
        refreshPhysicalGamepadState()
        hideSystemBars()
        onEmulationSurfaceReady()
        if (bootSucceeded) startPlayTimeSessionIfNeeded()
    }

    override fun onPause() {
        lifecyclePaused = true
        DiscordIntegration.setPaused(true)
        finishPlayTimeSessionIfNeeded(accumulate = true)
        Ps3Runtime.pause()
        super.onPause()
    }

    override fun onDestroy() {
        finishPlayTimeSessionIfNeeded()
        DiscordIntegration.clearGame()
        inputManager?.unregisterInputDeviceListener(this)
        inputManager = null
        inputOverlay.dispose()
        bootThread?.interrupt()
        if (isFinishing) Ps3Runtime.stop() else Ps3Runtime.detachSurface()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    fun onEmulationSurfaceReady() {
        if (!::surfaceView.isInitialized || !surfaceView.holder.surface.isValid) return
        val currentWidth = surfaceView.width
        val currentHeight = surfaceView.height
        if (currentWidth <= 1 || currentHeight <= 1) return
        if (!bootStarted.compareAndSet(false, true)) return

        val gamePath = gamePathFromIntent(intent)
        bootThread = thread(name = "emucorec-ps3-boot") {
            if (!Ps3Runtime.ensureInitialized(this)) {
                showBootFailure("RPCS3 core is missing or could not be initialized")
                return@thread
            }
            surfaceView.post {
                if (!surfaceView.holder.surface.isValid || isFinishing) return@post
                val width = surfaceView.width.coerceAtLeast(1)
                val height = surfaceView.height.coerceAtLeast(1)
                Ps3Runtime.attachSurface(
                    surfaceView.holder.surface,
                    width,
                    height,
                    surfaceView.display?.refreshRate?.toDouble()
                        ?.takeIf { it.isFinite() && it >= 20.0 } ?: 60.0,
                )
                bootThread = thread(name = "emucorec-ps3-game") {
                    val result = Ps3Runtime.boot(this, gamePath, currentGameIdOrIntent())
                    if (result != BootResult.NoErrors && result != BootResult.AlreadyAdded) {
                        showBootFailure(result.name)
                    } else {
                        bootSucceeded = true
                        runCatching { RPCSX.instance.getTitleId() }
                            .getOrDefault("")
                            .takeIf(String::isNotBlank)
                            ?.let(::setCurrentGameId)
                        runOnUiThread {
                            if (!lifecyclePaused && !isFinishing) startPlayTimeSessionIfNeeded()
                        }
                    }
                }
            }
        }
    }

    private fun showBootFailure(reason: String) {
        Log.e(TAG, "PS3 boot failed: $reason")
        runOnUiThread {
            if (!isFinishing) {
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
            val themeMode by preferences.themeModeFlow.collectAsState(initial = preferences.themeMode)
            EmuCoreCTheme(themeMode = themeMode, enableCrtOverlay = false) {
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
        if (!RPCSX.initialized) return false
        RPCSX.instance.openHomeMenu()
        true
    }.getOrDefault(false)

    fun getRunningGameTitle(): String {
        val id = currentGameIdOrIntent()
        return InstalledGameRepository().findByTitleId(this, id)?.title.orEmpty()
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
        Ps3Runtime.stop()
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
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            if (overlayBackHandler?.invoke() == true) return true
        }
        return super.dispatchKeyEvent(event)
    }

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

    private fun startPlayTimeSessionIfNeeded() {
        val gameId = currentGameIdOrIntent().trim()
        if (gameId.isBlank()) return
        if (playTimeSessionId != null && playTimeSessionTitleId.equals(gameId, true)) return
        finishPlayTimeSessionIfNeeded()
        val title = InstalledGameRepository().findByTitleId(this, gameId)?.title
            ?.takeIf(String::isNotBlank) ?: gameId
        val session = PlayTimeRepository(this).startSession(gameId, title) ?: return
        playTimeSessionId = session.id
        playTimeSessionTitleId = gameId
        playTimeSessionStartedAt = session.startedAt
        DiscordIntegration.setPlaying(title, gameId)
    }

    private fun finishPlayTimeSessionIfNeeded(accumulate: Boolean = false) {
        val endedAt = System.currentTimeMillis()
        playTimeSessionId?.let { PlayTimeRepository(this).finishSession(it, endedAt) }
        if (accumulate && playTimeSessionStartedAt > 0L) {
            playTimeAccumulatedMs += (endedAt - playTimeSessionStartedAt).coerceAtLeast(0L)
        }
        playTimeSessionId = null
        playTimeSessionTitleId = ""
        playTimeSessionStartedAt = 0L
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
        private val GAMEPAD_SOURCES =
            InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD
    }
}
