package com.sbro.emucorec.core

import android.view.Surface
import androidx.compose.runtime.mutableStateOf

enum class Digital1Flags(val bit: Int) {
    None(0),
    CELL_PAD_CTRL_SELECT(0x00000001),
    CELL_PAD_CTRL_L3(0x00000002),
    CELL_PAD_CTRL_R3(0x00000004),
    CELL_PAD_CTRL_START(0x00000008),
    CELL_PAD_CTRL_UP(0x00000010),
    CELL_PAD_CTRL_RIGHT(0x00000020),
    CELL_PAD_CTRL_DOWN(0x00000040),
    CELL_PAD_CTRL_LEFT(0x00000080),
    CELL_PAD_CTRL_PS(0x00000100),
}

enum class Digital2Flags(val bit: Int) {
    None(0),
    CELL_PAD_CTRL_L2(0x00000001),
    CELL_PAD_CTRL_R2(0x00000002),
    CELL_PAD_CTRL_L1(0x00000004),
    CELL_PAD_CTRL_R1(0x00000008),
    CELL_PAD_CTRL_TRIANGLE(0x00000010),
    CELL_PAD_CTRL_CIRCLE(0x00000020),
    CELL_PAD_CTRL_CROSS(0x00000040),
    CELL_PAD_CTRL_SQUARE(0x00000080),
}

enum class EmulatorState {
    Stopped,
    Loading,
    Stopping,
    Running,
    Paused,
    Frozen, // paused but cannot resume
    Ready,
    Starting;

    companion object {
        fun fromInt(value: Int) = EmulatorState.entries.getOrNull(value) ?: Stopped
    }
}

enum class BootResult {
    NoErrors,
    GenericError,
    NothingToBoot,
    WrongDiscLocation,
    InvalidFileOrFolder,
    InvalidBDvdFolder,
    InstallFailed,
    DecryptionError,
    FileCreationError,
    FirmwareMissing,
    FirmwareVersion,
    UnsupportedDiscType,
    DiscKeyMissing,
    DiscKeyInvalid,
    SavestateCorrupted,
    SavestateVersionUnsupported,
    StillRunning,
    AlreadyAdded,
    CurrentlyRestricted,
    DatabaseConfigMissing;

    companion object {
        fun fromInt(value: Int) = entries.getOrNull(value) ?: GenericError
    }
}

/**
 * The EmuCoreC bridge into the emulator core (libemucorec-core.so).
 *
 * The Kotlin surface is bound 1:1 to the JNI glue in emucorec-jni.cpp, which
 * dlopen()s the core by path and forwards every call to its emucorec_* entry
 * points. The core is a separate shared library on purpose: this class is the
 * only contract between the app and the emulator, and the session state below
 * lives exactly as long as the process that loaded the core.
 */
class Ps3Core {
    external fun openLibrary(path: String): Boolean
    external fun setSocInfo(socInfo: String)
    external fun setLogLevelFloor(level: Int)
    external fun initialize(rootDir: String, user: String): Boolean
    external fun installFw(fd: Int, progressId: Long): Boolean
    external fun install(fd: Int, progressId: Long): Boolean
    external fun installSplitPkg(fds: IntArray, progressId: Long): Boolean
    external fun installKey(fd: Int, requestId: Long, gamePath: String): Boolean
    external fun boot(path: String): Int
    external fun surfaceEvent(surface: Surface, event: Int): Boolean
    external fun surfaceSizeChanged(width: Int, height: Int)
    external fun displayRefreshRateChanged(refreshRate: Double)
    external fun processCompilationQueue(): Boolean
    external fun startMainThreadProcessor(): Boolean
    external fun overlayPadData(digital1: Int, digital2: Int, leftStickX: Int, leftStickY: Int, rightStickX: Int, rightStickY: Int): Boolean
    external fun settingsGet(path: String): String
    external fun settingsSet(path: String, value: String): Boolean
    external fun settingsSetTransient(path: String, value: String): Boolean
    external fun clearPendingGameSettings()
    external fun queueGameSetting(path: String, value: String): Boolean
    external fun getState(): Int
    external fun kill()
    external fun pause()
    external fun resume()
    external fun openHomeMenu()
    external fun getUser(): String
    external fun getTitleId(): String
    external fun getVersion(): String
    external fun probeDiscInfo(path: String, iconOut: String): String
    external fun saveStatesSupported(): Boolean
    external fun saveStateBusy(): Boolean
    external fun requestSaveState(slot: Int, progressId: Long): Boolean
    external fun requestLoadState(slot: Int, progressId: Long): Boolean
    external fun saveStateSlots(): String
    external fun setAppInfo(version: String, build: String, device: String)
    external fun patchEngineVersion(): String
    external fun patchesImport(content: String): Int
    external fun patchesList(serial: String): String
    external fun patchSetEnabled(hash: String, description: String, serial: String, appVersion: String, enabled: Boolean): Boolean

    companion object {
        var initialized = false
        val instance = Ps3Core()
        var rootDirectory = ""
        var activeLibrary = mutableStateOf<String?>(null)
        var state = mutableStateOf(EmulatorState.Stopped)

        fun boot(path: String): BootResult = BootResult.fromInt(instance.boot(path))

        fun updateState() {
            val newState = EmulatorState.fromInt(instance.getState())
            if (newState != state.value) {
                state.value = newState
            }
        }

        fun getState(): EmulatorState {
            updateState()
            return state.value
        }

        fun getHdd0Dir(): String = rootDirectory + "config/dev_hdd0/"

        fun openLibrary(path: String): Boolean {
            if (!instance.openLibrary(path)) {
                return false
            }

            activeLibrary.value = path
            return true
        }

        init {
            // This loads the JNI glue (app/src/main/cpp/emucorec-jni.cpp), not
            // the emulator core. The glue is what implements every external fun
            // here; it then dlopen()s libemucorec-core.so by path via
            // openLibrary(), and forwards each call to the core's emucorec_*
            // entry points.
            //
            // Two libraries, two load steps -- loading the core here instead
            // would resolve nothing, because the core exports no JNI symbols.
            System.loadLibrary("emucorec-jni")
        }
    }
}
