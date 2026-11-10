package com.sbro.emucorec.ui.settings

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTabsUiContractTest {
    @Test
    fun settingsTabsExposeOnlyEmuCoreCAndLivePs3Capabilities() {
        val screen = sourceRoot().resolve("ui/settings/SettingsScreen.kt").readText()
        val expectedOrder = listOf("General", "Customization", "Graphics", "Overlay", "Audio", "Controls", "Storage", "Network", "Advanced", "Updates", "About")
        var cursor = -1
        expectedOrder.forEach { tab ->
            val next = screen.indexOf("    $tab(")
            assertTrue("Missing or out-of-order settings tab: $tab", next > cursor)
            cursor = next
        }
        assertFalse("Removed Pro tab must not return", "Pro(R.string" in screen)
        assertFalse("Raw all-core tab must not return", "Core(R.string" in screen)
        assertFalse("Billing must not return to settings", "billing" in screen.lowercase())
    }

    @Test
    fun coreSettingsAreDistributedAcrossNativeEmuCoreTabs() {
        val content = sourceRoot().resolve("ui/settings/SettingsTabContent.kt").readText()
        val panel = sourceRoot().resolve("ui/settings/Ps3CoreSettingsSection.kt").readText()
        Ps3CoreSettingsCategory.entries.forEach { category ->
            assertTrue("Missing routed core category: $category", "Ps3CoreSettingsCategory.$category" in content)
        }
        assertFalse("A separate raw core tab must not return", "SettingsTab.Core" in content)
        assertFalse("The old fixed-height nested list must not return", "listHeight" in panel)
        assertTrue("Enum values must use direct choice in SettingChoiceRow", "visibleVariants.forEach" in panel)
        assertTrue("Panel must enumerate the live core tree", "flattenCoreSettings(JSONObject(raw)" in panel)
        assertTrue(
            "Panel must use the same controls as the rest of EmuCoreC",
            listOf("SettingToggleRow(", "SettingChoiceRow(", "SettingSliderRow(", "CoreTextRow").all { it in panel }
        )
        assertTrue("Only settings with audited RPCS3 help may be shown", "coreHelpResourceName(setting.path)" in panel)
        assertFalse("A generic hardcoded help text must not return", "R.string.core_setting_description" in panel)
        assertTrue("Per-game settings need a reset action", "clearGameSetting(" in panel)
    }

    @Test
    fun gameManagerAndInGameMenuUseTheSameNativeSettingsPanel() {
        val manager = sourceRoot().resolve("ui/gamemanager/GameManagerScreen.kt").readText()
        val menu = sourceRoot().resolve("ui/emulation/EmulationMenu.kt").readText()
        assertTrue("Game manager must expose per-title core settings", "Ps3CoreSettingsScope.Game" in manager)
        assertTrue("In-game menu must expose per-title core settings", "Ps3CoreSettingsScope.Game" in menu)
        assertTrue("Game manager must keep Vulkan driver and controller settings", "GameManagerTab.Graphics" in manager && "GameManagerTab.Controls" in manager)
        assertTrue("Performance overlay needs its own manager tab", "GameManagerTab.Overlay -> GameCoreSettings(Ps3CoreSettingsCategory.Overlay" in manager)
        assertFalse("Graphics must not render overlay settings a second time", "additionalCategories = setOf(Ps3CoreSettingsCategory.Overlay)" in manager)
        assertFalse("Raw core tab must not return to game manager", "GameManagerTab.Core" in manager)
        assertFalse("Raw core tab must not return to in-game menu", "EmulationMenuTab.Core" in menu)
        assertFalse("Global network settings do not belong in the live game menu", "EmulationMenuTab.Network" in menu)
        // The in-game menu was trimmed on purpose: CPU/Advanced and the overlay debug sections stay
        // in Settings and the game manager, so they must not come back as their own in-game tabs.
        assertFalse("Advanced settings stay in Settings and the game manager", "EmulationMenuTab.Advanced" in menu)
        assertFalse("Overlay settings live in the game tab, not in a second tab", "EmulationMenuTab.Overlay" in menu)
        for (path in listOf("VFS@@$(EmulatorDir)", "Miscellaneous@@GDB Server")) {
            assertFalse("Filesystem and debug settings must stay outside the live menu: $path",
                isCoreSettingVisibleOnSurface(path, Ps3CoreSettingsSurface.InGame))
        }
        assertTrue("In-game core controls must use an explicit audited allowlist", "Ps3CoreSettingsSurface.InGame" in menu)
        assertFalse("Legacy Vita touch switch must not return", "touchSwitch" in manager || "Vita" in manager)
    }

    @Test
    fun selectedSettingsTabIsMadeVisibleAndCentered() {
        val source = sourceRoot().resolve("ui/settings/SettingsScreen.kt").readText()
        assertTrue("rememberLazyListState()" in source)
        assertTrue("LaunchedEffect(selectedTab)" in source)
        assertTrue("listState.scrollToItem(selectedIndex)" in source)
        assertTrue("listState.animateScrollBy(delta)" in source)
        assertTrue("state = listState" in source)
    }

    private fun sourceRoot(): Path {
        val workingDirectory = Path.of(System.getProperty("user.dir"))
        val appModule = sequenceOf(workingDirectory, workingDirectory.resolve("app"))
            .firstOrNull { Files.isDirectory(it.resolve("src/main")) }
            ?: error("Unable to locate Android app module from $workingDirectory")
        return appModule.resolve("src/main/java/com/sbro/emucorec")
    }
}
