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
        for (category in listOf("Graphics", "Overlay", "Audio", "Advanced")) {
            assertTrue("Missing $category in-game tab", "EmulationMenuTab.$category ->" in dispatch)
            assertEquals("Native category must be rendered once: $category", 1,
                Regex("Ps3CoreSettingsCategory\\.$category\\b").findAll(dispatch).count())
        }
        val section = source("ui/settings/Ps3CoreSettingsSection.kt")
        val filter = section.substringAfter("val filtered = remember(").substringBefore("val userFacingSettings")
        assertFalse("CPU settings belong in Advanced, not a second Graphics category", "startsWith(\"Core@@\")" in filter)
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
