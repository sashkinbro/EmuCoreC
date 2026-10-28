package com.sbro.emucorec.core

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import com.sbro.emucorec.data.InstalledGameRepository
import com.sbro.emucorec.BuildConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean

object Ps3Runtime {
    private const val TAG = "Ps3Runtime"
    private const val USER_ID = "00000001"
    // Core log channel levels (logs::level: always=0 .. trace=7). Warnings in
    // debug builds, errors only in release; the settings tree can raise a
    // specific channel on top of this.
    private const val LOG_FLOOR_DEBUG = 5
    private const val LOG_FLOOR_RELEASE = 2
    private const val SURFACE_CREATED = 0
    private const val SURFACE_CHANGED = 1
    private const val SURFACE_DESTROYED = 2

    @Volatile private var mainPumpThread: Thread? = null
    @Volatile private var compilePumpThread: Thread? = null
    @Volatile private var currentSurface: Surface? = null

    fun ensureInitialized(context: Context): Boolean = synchronized(this) {
        if (Ps3Core.initialized) {
            ensurePumpsRunning()
            return true
        }

        val appContext = context.applicationContext
        EmulatorStorage.prepareRuntime(appContext)
        // Per-title database configs must exist before the first boot so the
        // core can apply them (see ConfigDatabaseSeeder).
        ConfigDatabaseSeeder.ensureSeeded(appContext)
        val nativeDir = appContext.applicationInfo.nativeLibraryDir
        val core = File(nativeDir, "libemucorec-core.so")
        if (!core.isFile) {
            Log.e(TAG, "EmuCoreC core is missing: ${core.absolutePath}")
            return false
        }

        if (Ps3Core.activeLibrary.value == null && !Ps3Core.openLibrary(core.absolutePath)) {
            Log.e(TAG, "Could not load EmuCoreC core: ${core.absolutePath}")
            return false
        }

        // Feed the core the identity strings shown in the performance overlay header
        // (app version | build | core version, plus the device name).
        runCatching {
            Ps3Core.instance.setAppInfo(
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE.toString(),
                MobileSocNameMapper.currentDeviceName(),
            )
        }

        val root = EmulatorStorage.ps3Root(appContext).absolutePath.trimEnd('/') + "/"
        Ps3Core.rootDirectory = root
        File(root, ".nomedia").runCatching { parentFile?.mkdirs(); createNewFile() }

        // Exact SoC identity for the startup banner; must precede initialize().
        runCatching { Ps3Core.instance.setSocInfo(MobileSocNameMapper.currentDeviceName()) }

        // Keep the on-device log useful without letting periodic channels fill
        // flash: debug apps keep warnings and errors, release apps keep errors
        // only. Explicit per-channel levels from the settings tree still win.
        runCatching { Ps3Core.instance.setLogLevelFloor(if (BuildConfig.DEBUG) LOG_FLOOR_DEBUG else LOG_FLOOR_RELEASE) }

        if (!Ps3Core.instance.initialize(root, USER_ID)) {
            Log.e(TAG, "Core initialization failed for $root")
            return false
        }

        Ps3Core.initialized = true
        MicrophoneDevices.initialize(appContext)
        runCatching { FirmwareState.load() }
        ensurePumpsRunning()
        true
    }

    fun ensurePumpsRunning() {
        synchronized(this) {
            if (!Ps3Core.initialized) return
            if (mainPumpThread?.isAlive != true) {
                mainPumpThread = Thread({
                    runCatching { Ps3Core.instance.startMainThreadProcessor() }
                }, "ps3-main-pump").apply {
                    isDaemon = true
                    start()
                }
            }
            if (compilePumpThread?.isAlive != true) {
                compilePumpThread = Thread({
                    runCatching { Ps3Core.instance.processCompilationQueue() }
                }, "ps3-compile-pump").apply {
                    isDaemon = true
                    start()
                }
            }
        }
    }

    fun installFirmware(context: Context, uri: Uri): Boolean {
        if (!ensureInitialized(context)) return false
        val progressId = NativeProgressBus.create()
        return (context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            Ps3Core.instance.installFw(descriptor.fd, progressId)
        } ?: false).also { accepted -> rejectProgressIfNeeded(progressId, accepted) }
    }

    fun installFirmware(context: Context, source: String): Boolean {
        val progressId = NativeProgressBus.create()
        return installFirmware(context, source, progressId).also { accepted ->
            rejectProgressIfNeeded(progressId, accepted)
        }
    }

    fun installFirmware(context: Context, source: String, progressId: Long): Boolean =
        openDescriptor(context, source)?.use { descriptor ->
            if (!ensureInitialized(context)) return@use false
            Ps3Core.instance.installFw(descriptor.fd, progressId)
        } ?: false

    fun installPackage(context: Context, uri: Uri): Boolean {
        if (!ensureInitialized(context)) return false
        val progressId = NativeProgressBus.create()
        return (context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            Ps3Core.instance.install(descriptor.fd, progressId)
        } ?: false).also { accepted -> rejectProgressIfNeeded(progressId, accepted) }
    }

    fun installPackage(context: Context, source: String): Boolean {
        val progressId = NativeProgressBus.create()
        return installPackage(context, source, progressId).also { accepted ->
            rejectProgressIfNeeded(progressId, accepted)
        }
    }

    fun installPackage(context: Context, source: String, progressId: Long): Boolean =
        openDescriptor(context, source)?.use { descriptor ->
            if (!ensureInitialized(context)) return@use false
            Ps3Core.instance.install(descriptor.fd, progressId)
        } ?: false

    fun installSplitPackages(context: Context, sources: List<String>): Boolean {
        val progressId = NativeProgressBus.create()
        return installSplitPackages(context, sources, progressId).also { accepted ->
            rejectProgressIfNeeded(progressId, accepted)
        }
    }

    fun installSplitPackages(context: Context, sources: List<String>, progressId: Long): Boolean {
        if (sources.isEmpty() || !ensureInitialized(context)) return false
        val descriptors = ArrayList<ParcelFileDescriptor>(sources.size)
        return try {
            sources.forEach { source ->
                descriptors += openDescriptor(context, source) ?: return false
            }
            Ps3Core.instance.installSplitPkg(
                descriptors.map(ParcelFileDescriptor::getFd).toIntArray(),
                progressId,
            )
        } finally {
            descriptors.forEach { runCatching { it.close() } }
        }
    }

    fun installLicense(context: Context, source: String, gamePath: String): Boolean =
        if (File(source).extension.equals("rap", true) && gamePath.isBlank()) {
            installRap(context, source)
        } else {
            val progressId = NativeProgressBus.create()
            installLicense(context, source, gamePath, progressId).also { accepted ->
                rejectProgressIfNeeded(progressId, accepted)
            }
        }

    fun installLicense(context: Context, source: String, gamePath: String, progressId: Long): Boolean =
        openDescriptor(context, source)?.use { descriptor ->
            if (!ensureInitialized(context)) return@use false
            Ps3Core.instance.installKey(descriptor.fd, progressId, gamePath)
        } ?: false

    /** Installs a standalone RAP by its content-id filename, as RPCS3's exdata import does. */
    fun installRap(context: Context, source: String): Boolean = runCatching {
        if (!ensureInitialized(context)) return false
        val input = File(source)
        if (!input.isFile || input.length() != 0x10L) return false
        val contentId = RapLicenseContentIdResolver.resolve(
            input,
            InstalledGameRepository().loadInstalledGames(context),
        ) ?: return false

        val user = runCatching { Ps3Core.instance.getUser() }.getOrNull().orEmpty().ifBlank { USER_ID }
        val directory = File(Ps3Core.getHdd0Dir(), "home/$user/exdata").canonicalFile
        if (!directory.isDirectory && !directory.mkdirs()) return false
        val destination = File(directory, "$contentId.rap").canonicalFile
        if (destination.parentFile != directory) return false

        val temporary = File.createTempFile(".emucorec-rap-", ".tmp", directory)
        try {
            input.inputStream().use { sourceStream ->
                temporary.outputStream().use { destinationStream -> sourceStream.copyTo(destinationStream) }
            }
            runCatching {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.recoverCatching {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }.getOrThrow()
            true
        } finally {
            temporary.delete()
        }
    }.getOrDefault(false)

    private fun rejectProgressIfNeeded(progressId: Long, accepted: Boolean) {
        if (!accepted) NativeProgressBus.onEvent(progressId, -1, 0, "Installation failed", completed = false)
    }

    fun boot(context: Context, path: String, titleId: String? = null): BootResult {
        if (!ensureInitialized(context)) return BootResult.GenericError
        ensurePumpsRunning()
        Ps3CoreSettingOverrides.applyForGame(context, titleId)
        return Ps3Core.boot(path)
    }

    fun attachSurface(surface: Surface, width: Int, height: Int, refreshRate: Double) {
        if (!Ps3Core.initialized) return
        val event = if (currentSurface == null) SURFACE_CREATED else SURFACE_CHANGED
        currentSurface = surface
        Ps3Core.instance.surfaceSizeChanged(width, height)
        Ps3Core.instance.displayRefreshRateChanged(refreshRate)
        Ps3Core.instance.surfaceEvent(surface, event)
    }

    fun detachSurface() {
        currentSurface?.let { surface ->
            if (Ps3Core.initialized) Ps3Core.instance.surfaceEvent(surface, SURFACE_DESTROYED)
        }
        currentSurface = null
    }

    fun pause() {
        if (Ps3Core.initialized) runCatching { Ps3Core.instance.pause() }
    }

    fun resume() {
        if (Ps3Core.initialized) runCatching { Ps3Core.instance.resume() }
    }

    fun stop() {
        detachSurface()
        requestStop()
        // Both JNI processors are process-lifetime loops. Retain their handles
        // so initialization after an install or game stop cannot duplicate them.
    }

    fun requestStop() {
        if (Ps3Core.initialized) runCatching { Ps3Core.instance.kill() }
    }

    fun state(): EmulatorState =
        if (Ps3Core.initialized) runCatching { Ps3Core.getState() }.getOrDefault(EmulatorState.Stopped)
        else EmulatorState.Stopped

    private fun openDescriptor(context: Context, source: String): ParcelFileDescriptor? {
        val uri = Uri.parse(source)
        return if (uri.scheme.equals("content", ignoreCase = true)) {
            context.contentResolver.openFileDescriptor(uri, "r")
        } else {
            val path = if (uri.scheme.equals("file", ignoreCase = true)) uri.path else source
            path?.let(::File)?.takeIf(File::isFile)?.let { file ->
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
    }

    private inline fun File.runCatching(block: File.() -> Unit) {
        kotlin.runCatching { block() }
    }
}
