package com.sbro.emucorec.ui.emulation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulationSettingsContractTest {
    @Test
    fun nativeSettingsHaveOneMatchingInGameTab() {
        val menu = source("ui/emulation/EmulationMenu.kt")
        val dispatch = menu.substringAfter("when (selectedTab) {").substringBefore("Spacer(modifier")
        for (category in listOf("Graphics", "Audio")) {
            assertTrue("Missing $category in-game tab", "EmulationMenuTab.$category ->" in dispatch)
        }
        // Overlay and Advanced are deliberately not part of the in-game menu any more: they stay in
        // Settings and the game manager.
        for (category in listOf("Overlay", "Advanced")) {
            assertFalse("$category must not be rendered in-game", "EmulationMenuTab.$category ->" in dispatch)
            assertFalse("$category must not be dispatched in-game", "EmulationMenuTab.$category," in dispatch)
        }
        val section = source("ui/settings/Ps3CoreSettingsSection.kt")
        val filter = section.substringAfter("val filtered = remember(").substringBefore("val userFacingSettings")
        assertFalse("CPU settings belong in Advanced, not a second Graphics category", "startsWith(\"Core@@\")" in filter)
    }

    @Test
    fun everyNativeSettingGroupIsRenderedAtMostOnceInGame() {
        val menu = source("ui/emulation/EmulationMenu.kt")
        val inGame = menu.substringAfter("fun EmulationGameMenu(").substringBefore("private enum class MenuBadge")
        // Each native group has exactly one in-game owner, so nothing is shown twice.
        for (category in listOf("Graphics", "Audio", "Overlay")) {
            assertEquals("$category must be rendered exactly once in-game", 1,
                Regex("Ps3CoreSettingsCategory\\.$category\\b").findAll(inGame).count())
        }
        for (category in listOf("Advanced", "Controls", "Storage", "Network", "General")) {
            assertFalse("$category must stay out of the in-game menu",
                Regex("Ps3CoreSettingsCategory\\.$category\\b").containsMatchIn(inGame))
        }
        assertFalse("The Overlay tab is gone, the overlay settings live in the game tab",
            "EmulationMenuTab.Overlay" in menu)
        assertFalse("The Advanced tab is gone", "EmulationMenuTab.Advanced" in menu)
    }

    @Test
    fun androidInputEditsRemainPerGameAndApplyImmediately() {
        val overlay = source("ui/emulation/EmulationOverlay.kt")
        val persist = overlay.substringAfter("fun persistConfig(").substringBefore("fun ")
        assertTrue("Android controls must save the current title profile", "repository.savePreservingDriverOverride(gameId" in persist)
        assertTrue("Android controls must apply to the active input overlay", "activity.updateGamepadRuntimeInputSettings(config)" in persist)
        val native = source("ui/settings/Ps3CoreSettingsSection.kt")
        assertTrue("Native per-game edits must avoid overwriting global core config", "settingsSetTransient(setting.path, encoded)" in native)
        assertTrue("Native per-game edits must persist", "Ps3CoreSettingOverrides.recordGame(" in native)
        assertTrue("Boot-only edits must retain the restart flow", "Ps3LaunchBridge.restartRunningGame(context)" in native)
    }

    @Test
    fun customMenuSurfacesProvideReadableInheritedTextForPortalSlots() {
        val menu = source("ui/emulation/EmulationMenu.kt")
        val menuBody = menu.substringAfter("fun EmulationGameMenu(").substringBefore("private fun MenuScrollableContent(")
        assertTrue("Custom menu surfaces must provide their foreground instead of inheriting black",
            Regex("contentColor = palette.textPrimary").findAll(menuBody).count() >= 3)
        val portal = source("ui/emulation/ToyPortalPanel.kt")
        assertTrue("Portal slots must explicitly use readable surface text", "color = MaterialTheme.colorScheme.onSurface" in portal)
        assertTrue("Portal panel must explain the figure workflow", "R.string.emulation_portal_description" in portal)
    }

    @Test
    fun nativeOptionErrorsAndAutomaticValuesUseLocalizedPresentation() {
        val native = source("ui/settings/Ps3CoreSettingsSection.kt")
        assertFalse("Native errors must not interpolate the English metadata name",
            "R.string.ps3_core_setting_rejected, setting.name" in native)
        assertFalse("Unknown metadata must not fall back to raw English", "return raw.replaceFirstChar" in native)
        assertTrue("Known native default sentinels need localized presentation", "localizedCoreTextValue(setting.path, text)" in native)
        assertTrue("Default-value hints need the same localized presentation", "localizedCoreTextValue(setting.path, setting.default)" in native)
    }

    private fun source(relative: String): String {
        val workingDirectory = Path.of(System.getProperty("user.dir"))
        val module = sequenceOf(workingDirectory, workingDirectory.resolve("app"))
            .first { Files.isDirectory(it.resolve("src/main")) }
        return module.resolve("src/main/java/com/sbro/emucorec/$relative").readText()
    }
}
