package com.sbro.emucorec.ui.emulation

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulationQuickBarContractTest {
    @Test
    fun quickBarRequiresAnExplicitPersistedOptIn() {
        val preferences = source("data/AppPreferences.kt")
        val overlay = source("ui/emulation/EmulationOverlay.kt")
        val settings = source("ui/settings/SettingsTabContent.kt")
        val viewModel = source("ui/settings/SettingsViewModel.kt")

        assertTrue("Existing installs must default to hidden", "prefs.getBoolean(KEY_SHOW_EMULATION_QUICK_BAR, false)" in preferences)
        assertTrue("Opt-in must survive restart", "putBoolean(KEY_SHOW_EMULATION_QUICK_BAR, value)" in preferences)
        assertTrue("Overlay must observe preference changes",
            "showEmulationQuickBarFlow" in overlay && ".collectAsState(" in overlay)
        assertTrue("Reveal gestures must respect the opt-in", "visible = showQuickBar && !controlsEditMode && menuButtonVisible" in overlay)
        assertTrue("Setting must persist through the view model", "preferences.showEmulationQuickBar = enabled" in viewModel)
        assertTrue("Settings must load the persisted value", "showEmulationQuickBar = preferences.showEmulationQuickBar" in viewModel)
        assertTrue("Setting must offer a reset to hidden", "viewModel.updateShowEmulationQuickBar(false)" in settings)
    }

    @Test
    fun hidingQuickBarPreservesIndependentPauseMenuAndSaveStateAccess() {
        val overlay = source("ui/emulation/EmulationOverlay.kt")
        val backHandler = overlay.substringAfter("activity.setOverlayBackHandler {")
            .substringBefore("activity.setOverlayMenuButtonRevealHandler")
        val pauseHandler = overlay.substringAfter("activity.setOverlayPauseMenuOpenHandler {")
            .substringBefore("onDispose {")

        assertTrue("Back must still open the game menu", "menuOpen = !menuOpen" in backHandler)
        assertTrue("Pause hotkey must still open the game menu", "menuOpen = true" in pauseHandler)
        assertFalse("Back must work while quick bar is disabled", "showQuickBar" in backHandler)
        assertFalse("Pause hotkey must work while quick bar is disabled", "showQuickBar" in pauseHandler)
        assertTrue("Game menu must retain save state actions", "onSaveStateSave = { requestSaveAction" in overlay)
        assertTrue("Game menu must retain load state actions", "onSaveStateLoad = { requestSaveAction" in overlay)
    }

    @Test
    fun everySupportedLocaleExplainsTheQuickBarSetting() {
        val resourceRoot = appModule().resolve("src/main/res")
        val languageTags = Regex("""\w+\(\d+, "([a-z]+)"\)""")
            .findAll(source("data/AppPreferences.kt").substringBefore("class AppPreferences"))
            .map { it.groupValues[1] }
            .toSet()
        val directories = setOf("values") + languageTags.filter { it != "en" }
            .map { "values-${if (it == "id") "in" else it}" }

        directories.forEach { directory ->
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(resourceRoot.resolve("$directory/strings.xml").toFile())
            val strings = document.getElementsByTagName("string")
            val textByKey = (0 until strings.length).associate { index ->
                val node = strings.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }
            for (key in listOf("settings_show_quick_bar", "settings_help_show_quick_bar")) {
                assertTrue("$directory is missing $key", !textByKey[key].isNullOrBlank())
            }
        }
    }

    private fun source(relative: String): String = appModule()
        .resolve("src/main/java/com/sbro/emucorec/$relative").readText()

    private fun appModule(): Path {
        val workingDirectory = Path.of(System.getProperty("user.dir"))
        return sequenceOf(workingDirectory, workingDirectory.resolve("app"))
            .firstOrNull { Files.isDirectory(it.resolve("src/main")) }
            ?: error("Unable to locate app module from $workingDirectory")
    }
}
