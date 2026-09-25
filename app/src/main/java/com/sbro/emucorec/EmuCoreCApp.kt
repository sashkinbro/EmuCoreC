package com.sbro.emucorec

import android.app.Application
import com.sbro.emucorec.core.EmulatorStorage
import com.sbro.emucorec.core.NativeLibraryLoader
import com.sbro.emucorec.core.PlayTimeRepository
import com.sbro.emucorec.data.AppPreferences
import com.sbro.emucorec.discord.DiscordIntegration

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
        if (AppPreferences(this).onboardingCompleted) {
            NativeLibraryLoader.ensureLoaded(this)
        }
    }

    private companion object {
        const val DISCORD_PROCESS_SUFFIX = ":discord"
    }
}
