package com.sbro.emucorec

import android.app.Application
import com.sbro.emucorec.core.AppVisibility
import com.sbro.emucorec.core.BackupSessionGate
import com.sbro.emucorec.core.DeviceGpuInfoProvider
import com.sbro.emucorec.core.EmulatorStorage
import com.sbro.emucorec.core.NativeLibraryLoader
import com.sbro.emucorec.core.PlayTimeRepository
import com.sbro.emucorec.data.AppPreferences
import com.sbro.emucorec.data.ProfilePlayTimeSyncer
import com.sbro.emucorec.data.drive.DriveBackupArchive
import com.sbro.emucorec.data.drive.DriveBackupWork
import com.sbro.emucorec.discord.DiscordIntegration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class EmuCoreCApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The Discord SDK runs in its own isolated helper process, which must not
        // build the emulator-side graph.
        if (Application.getProcessName().endsWith(DISCORD_PROCESS_SUFFIX)) return
        if (Application.getProcessName() == packageName) {
            registerActivityLifecycleCallbacks(AppVisibility)
        }
        // Apply the persisted locale before any activity is created so
        // AppCompat can attach the correctly localized resource context.
        AppPreferences(this).applyAppLanguage()
        // First-run work (creating the runtime tree and extracting the Icons
        // assets) is disk I/O that can take seconds on a fresh install, and it
        // used to run on the main thread right here: the launcher then hit the
        // input-dispatch timeout and the app got its ANR before it even drew.
        // prepareRuntime is synchronized, so this thread and the emulator boot
        // thread can race it safely.
        Thread({
            runCatching {
                EmulatorStorage.prepareRuntime(this)
                if (AppPreferences(this).onboardingCompleted) {
                    NativeLibraryLoader.ensureLoaded(this)
                    com.sbro.emucorec.ui.settings.preloadCoreSettings(this)
                }
            }
        }, "EmuCoreC-PrepareRuntime").apply { isDaemon = true; start() }
        Thread({
            runCatching { runBlocking { DeviceGpuInfoProvider.preload(this@EmuCoreCApp) } }
        }, "EmuCoreC-GpuProfile").apply { isDaemon = true; start() }
        runCatching { PlayTimeRepository(this).finishOpenSessions() }
        DiscordIntegration.initialize(this)
        ProfilePlayTimeSyncer.syncPendingAsync(this)
        recoverPendingDriveRestore()
        DriveBackupWork.resumePending(this)
    }

    private fun recoverPendingDriveRestore() {
        if (!DriveBackupArchive.hasPendingRecovery(this)) return
        Thread({
            runBlocking {
                try {
                    BackupSessionGate.whileStopped { DriveBackupArchive(this@EmuCoreCApp).recoverPending() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The next create/restore call retries the same recovery under the gate.
                }
            }
        }, "EmuCoreC-DriveRecovery").apply { isDaemon = true; start() }
    }

    private companion object {
        const val DISCORD_PROCESS_SUFFIX = ":discord"
    }
}
