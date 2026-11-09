package com.sbro.emucorec.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import com.sbro.emucorec.data.readInstalledFolder
import org.junit.Test

class SystemMenuLaunchContractTest {
    @Test
    fun drawerLaunchesPs3WhileFirmwareInstallationStaysInOnboarding() {
        val shell = source("navigation/AdaptiveShell.kt")
        val navigation = source("navigation/AppNavigation.kt")
        val onboarding = source("ui/onboarding/OnboardingScreen.kt")
        val launcher = source("core/Ps3LaunchBridge.kt")

        assertFalse("Firmware installation is mandatory onboarding, not a drawer action", "shell_install_firmware" in shell)
        assertTrue("Onboarding must keep firmware installation", "onInstallFirmware" in onboarding)
        assertTrue("Drawer must expose VSH launch", "shell_launch_system_menu" in shell)
        assertTrue("Every app shell must receive the VSH action", "onLaunchSystemMenu = launchSystemMenu" in navigation)
        assertTrue("VSH must launch through an explicit executable path", "systemMenuExecutable(context)" in launcher)
    }

    @Test
    fun libraryRejectsServiceFoldersWithoutParamSfo() {
        val folder = Files.createTempDirectory("emucorec-service-folder").toFile()
        try {
            assertNull("Service folders without PARAM.SFO are not library games", readInstalledFolder(folder))
        } finally {
            folder.delete()
        }
    }

    private fun source(relative: String): String = appModule()
        .resolve("src/main/java/com/sbro/emucorec")
        .resolve(relative)
        .readText()

    private fun appModule(): Path {
        val workingDirectory = Path.of(System.getProperty("user.dir"))
        return sequenceOf(workingDirectory, workingDirectory.resolve("app"))
            .firstOrNull { Files.isDirectory(it.resolve("src/main")) }
            ?: error("Unable to locate Android app module from $workingDirectory")
    }
}
