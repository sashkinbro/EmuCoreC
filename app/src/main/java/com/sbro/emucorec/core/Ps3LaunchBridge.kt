package com.sbro.emucorec.core

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.sbro.emucorec.core.ps3.Emulator
import com.sbro.emucorec.data.InstalledGameRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Ps3LaunchBridge {
    enum class LaunchResult { Success, MissingFirmware, Failure }

    /** Recreate the game surface/menu, then use the normal boot path with the same title profile. */
    suspend fun restartRunningGame(context: Context): Boolean {
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Emulator>().firstOrNull() ?: return false
        if (Ps3Core.instance.saveStateBusy()) return false
        val launch = Ps3Runtime.currentLaunch ?: return false
        val titleId = activity.currentGameIdOrIntent().ifBlank { launch.second.orEmpty() }
        val path = withContext(Dispatchers.IO) {
            // Restart a state-restored game from its executable, not from the old boot snapshot.
            if (launch.first.contains(".SAVESTAT", ignoreCase = true)) {
                InstalledGameRepository().findByTitleId(context, titleId)?.installPath
            } else launch.first
        }?.takeIf(String::isNotBlank) ?: return false
        return withContext(Dispatchers.Main) {
            if (activity.isFinishing || activity.isDestroyed || Ps3Core.instance.saveStateBusy()) false
            else {
                activity.intent = Intent(activity.intent).apply {
                    putExtra(Emulator.EXTRA_TITLE_ID, titleId)
                    putExtra(Emulator.EXTRA_GAME_PATH, path)
                }
                CoreSettingsTreeCache.invalidateTree()
                activity.recreate()
                true
            }
        }
    }

    fun launchSaveState(context: Context, titleId: String, slot: Int): LaunchResult {
        if (!EmulatorStorage.hasInstalledFirmware(context)) return LaunchResult.MissingFirmware
        return runCatching {
            val file = SaveStateRepository(context).stateFile(titleId, slot) ?: return LaunchResult.Failure
            context.startActivity(Intent(context, Emulator::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Emulator.EXTRA_TITLE_ID, titleId)
                putExtra(Emulator.EXTRA_GAME_PATH, file.absolutePath)
                action = ACTION_LAUNCH
            })
            LaunchResult.Success
        }.getOrDefault(LaunchResult.Failure)
    }

    /**
     * Launches an installed title.
     *
     * The caller usually already holds the [installPath] from the library item
     * it is rendering; passing it skips a full library rescan. That rescan
     * walks every game directory and probes disc images through the core, which
     * is far too much work for a UI click -- it ran on the main thread here and
     * was part of the launch ANR. The lookup remains only as a fallback.
     */
    fun launchInstalledTitle(context: Context, titleId: String, installPath: String? = null): LaunchResult {
        if (!EmulatorStorage.hasInstalledFirmware(context)) return LaunchResult.MissingFirmware
        val resolvedPath = installPath?.takeIf(String::isNotBlank)
            ?: InstalledGameRepository().findByTitleId(context, titleId)?.installPath
            ?: return LaunchResult.Failure
        return runCatching {
            context.startActivity(
                Intent(context, Emulator::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(Emulator.EXTRA_TITLE_ID, titleId)
                    putExtra(Emulator.EXTRA_GAME_PATH, resolvedPath)
                    action = ACTION_LAUNCH
                }
            )
        }.fold({ LaunchResult.Success }, { LaunchResult.Failure })
    }

    fun launchSystemMenu(context: Context): LaunchResult {
        val vsh = EmulatorStorage.systemMenuExecutable(context)
        if (!vsh.isFile) return LaunchResult.MissingFirmware
        return runCatching {
            context.startActivity(
                Intent(context, Emulator::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(Emulator.EXTRA_GAME_PATH, vsh.absolutePath)
                    action = ACTION_LAUNCH_SYSTEM_MENU
                }
            )
        }.fold({ LaunchResult.Success }, { LaunchResult.Failure })
    }

    fun installFirmware(context: Context, firmwarePath: String): Boolean =
        Ps3Runtime.installFirmware(context, firmwarePath)

    fun installPkg(context: Context, pkgPath: String): Boolean =
        Ps3Runtime.installPackage(context, pkgPath)

    private const val ACTION_LAUNCH = "com.sbro.emucorec.action.LAUNCH"
    private const val ACTION_LAUNCH_SYSTEM_MENU = "com.sbro.emucorec.action.LAUNCH_SYSTEM_MENU"
}
