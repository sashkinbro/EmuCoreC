package com.sbro.emucorec.core

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import net.rpcsx.NativeProgress
import net.rpcsx.ProgressRepository
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class NativeInstallProgress(
    val stage: String,
    val progress: Float,
    val current: Float,
    val total: Float,
    val detail: String?,
)

object Ps3InstallBridge {
    fun interface Listener { fun onProgress(progress: NativeInstallProgress) }

    @Volatile private var listener: Listener? = null
    private val installMutex = Mutex()

    suspend fun installFirmware(
        context: Context,
        firmwarePath: String,
        progressListener: Listener? = null,
    ): String? = runExclusive(progressListener) {
        emit("firmware", 0f, "Installing PS3 system software")
        val success = awaitNativeInstall("firmware", 0, 1, "Installing PS3 system software") { progressId ->
            Ps3Runtime.installFirmware(context, firmwarePath, progressId)
        }
        Ps3Runtime.stop()
        emit("firmware", if (success) 1f else 0f, if (success) "Installed" else "Installation failed")
        if (success) net.rpcsx.FirmwareRepository.version.value ?: "installed" else null
    }

    private suspend fun installPackage(context: Context, contentPath: String): Int {
        emit("content", 0f, "Installing PS3 PKG or disc image")
        val success = awaitNativeInstall("content", 0, 1, File(contentPath).name) { progressId ->
            Ps3Runtime.installPackage(context, contentPath, progressId)
        }
        if (success) NativeLib.refreshAppsList()
        Ps3Runtime.stop()
        emit("content", if (success) 1f else 0f, if (success) "Installed" else "Installation failed")
        return if (success) 1 else 0
    }

    suspend fun installLicense(
        context: Context,
        licensePath: String,
        progressListener: Listener? = null,
    ): Boolean = runExclusive(progressListener) {
        emit("license", 0f, "Installing PS3 RAP license")
        val file = File(licensePath)
        val success = if (file.extension.equals("rap", true)) {
            Ps3Runtime.installRap(context, licensePath)
        } else {
            awaitNativeInstall("license", 0, 1, file.name) { progressId ->
                Ps3Runtime.installLicense(context, licensePath, "", progressId)
            }
        }
        if (!success && file.extension.equals("rap", true)) {
            // A RAP is only usable under its content-id name; when it cannot be matched, say so
            // and name the file instead of a bare "Installation failed".
            val games = runCatching {
                com.sbro.emucorec.data.InstalledGameRepository().loadInstalledGames(context)
            }.getOrDefault(emptyList())
            val suggested = RapLicenseContentIdResolver.suggestedFileName(file, games)
            val detail = if (suggested != null) {
                "RAP could not be matched to an installed game. Rename it to $suggested"
            } else {
                "RAP could not be matched to an installed game. Rename it to <content-id>.rap"
            }
            emit("license", 0f, detail)
            return@runExclusive false
        }
        emit("license", if (success) 1f else 0f, if (success) "Installed" else "Installation failed")
        success
    }

    suspend fun installPkg(
        context: Context,
        pkgPath: String,
        progressListener: Listener? = null,
    ): Boolean = runExclusive(progressListener) { installPackage(context, pkgPath) > 0 }

    suspend fun installContent(
        context: Context,
        paths: List<String>,
        progressListener: Listener? = null,
    ): Boolean = runExclusive(progressListener) {
        val plan = InstallContentPlanner.create(paths)
        val licences = plan.licences
        val payloads = plan.payloads
        if (payloads.isEmpty() && licences.isEmpty()) return@runExclusive false

        val totalUnits = plan.totalUnits
        var unitIndex = 0
        var success = true
        if (plan.isSplitPackage) {
            emit("content", 0f, "Installing split PS3 package")
            success = awaitNativeInstall("content", unitIndex, totalUnits, "Installing split PS3 package") { progressId ->
                Ps3Runtime.installSplitPackages(context, payloads.map(File::getAbsolutePath), progressId)
            }
            unitIndex++
        } else {
            payloads.forEach { file ->
                if (!success) return@forEach
                success = awaitNativeInstall("content", unitIndex, totalUnits, file.name) { progressId ->
                    Ps3Runtime.installPackage(context, file.absolutePath, progressId)
                }
                unitIndex++
            }
        }
        licences.forEach { file ->
            if (!success) return@forEach
            success = if (file.extension.equals("rap", true)) {
                emitUnit("license", unitIndex, totalUnits, 0f, file.name)
                Ps3Runtime.installRap(context, file.absolutePath).also { installed ->
                    emitUnit("license", unitIndex, totalUnits, if (installed) 1f else 0f, file.name)
                }
            } else {
                awaitNativeInstall("license", unitIndex, totalUnits, file.name) { progressId ->
                    Ps3Runtime.installLicense(context, file.absolutePath, "", progressId)
                }
            }
            unitIndex++
        }
        if (success) NativeLib.refreshAppsList()
        Ps3Runtime.stop()
        emit("content", if (success) 1f else 0f, if (success) "Installed" else "Installation failed")
        success
    }

    private suspend fun awaitNativeInstall(
        stage: String,
        unitIndex: Int,
        totalUnits: Int,
        fallbackDetail: String,
        start: (Long) -> Boolean,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val settled = AtomicBoolean(false)
        val progressId = ProgressRepository.create { progress ->
            emitNative(stage, unitIndex, totalUnits, fallbackDetail, progress)
            if ((progress.failed || progress.completed) && settled.compareAndSet(false, true) && continuation.isActive) {
                continuation.resume(!progress.failed)
            }
        }
        continuation.invokeOnCancellation {
            if (settled.compareAndSet(false, true)) ProgressRepository.cancel(progressId)
        }
        val accepted = runCatching { start(progressId) }.getOrDefault(false)
        if (!settled.get()) {
            if (accepted) {
                ProgressRepository.onProgressEvent(progressId, 1, 1, null)
            } else {
                ProgressRepository.onProgressEvent(progressId, -1, 0, "Installation failed")
            }
        }
    }

    private fun emitNative(
        stage: String,
        unitIndex: Int,
        totalUnits: Int,
        fallbackDetail: String,
        progress: NativeProgress,
    ) {
        val unitProgress = if (progress.maximum > 0L) {
            progress.value.toFloat().div(progress.maximum.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        val overall = (unitIndex + unitProgress).div(totalUnits.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        listener?.onProgress(
            NativeInstallProgress(
                stage = stage,
                progress = overall,
                current = progress.value.toFloat(),
                total = progress.maximum.toFloat(),
                detail = progress.message?.takeIf(String::isNotBlank) ?: fallbackDetail,
            )
        )
    }

    private fun emitUnit(stage: String, unitIndex: Int, totalUnits: Int, value: Float, detail: String) {
        val overall = (unitIndex + value.coerceIn(0f, 1f)).div(totalUnits.coerceAtLeast(1).toFloat())
        listener?.onProgress(NativeInstallProgress(stage, overall, value, 1f, detail))
    }

    private fun emit(stage: String, progress: Float, detail: String) {
        listener?.onProgress(NativeInstallProgress(stage, progress, progress, 1f, detail))
    }

    private suspend fun <T> runExclusive(progressListener: Listener?, block: suspend () -> T): T =
        installMutex.withLock {
            listener = progressListener
            try {
                block()
            } finally {
                listener = null
            }
        }
}
