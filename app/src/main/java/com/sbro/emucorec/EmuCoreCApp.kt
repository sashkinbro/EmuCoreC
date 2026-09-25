package com.sbro.emucorec

import android.app.Application
import com.sbro.emucorec.core.BackupSessionGate
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
        // Apply the persisted locale before any activity is created so
        // AppCompat can attach the correctly localized resource context.
        AppPreferences(this).applyAppLanguage()
        runCatching { EmulatorStorage.prepareRuntime(this) }
        runCatching { PlayTimeRepository(this).finishOpenSessions() }
        DiscordIntegration.initialize(this)
        ProfilePlayTimeSyncer.syncPendingAsync(this)
        recoverPendingDriveRestore()
        DriveBackupWork.resumePending(this)
        if (AppPreferences(this).onboardingCompleted) {
            NativeLibraryLoader.ensureLoaded(this)
        }
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
