package com.sbro.emucorec.ui.common

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTopBarContractTest {
    @Test
    fun sharedTopBarMatchesTheEmuCoreCContainerContract() {
        val source = sourceRoot().resolve("ui/common/ScreenTopBar.kt").readText()

        assertTrue("Top bar must use the theme-aware 24 dp shape", "neonShape(24.dp)" in source)
        assertTrue("Top bar must remain translucent", "surface.copy(alpha = 0.78f)" in source)
        assertTrue("Top bar must use subtle tonal elevation", "tonalElevation = 1.dp" in source)
        assertTrue("Top bar must not cast a floating shadow", "shadowElevation = 0.dp" in source)
        assertTrue("Top bar border must remain subtle", "copy(alpha = 0.62f)" in source)
        assertTrue("Neon top bar must use the tricolor divider", "NeonTricolorDivider" in source)
        assertTrue("Neon top bar must use the yellow accent border", "NeonYellow.copy(alpha = 0.35f)" in source)
        assertTrue(
            "Top bar must keep the compact internal padding",
            "start = 10.dp + if (LocalNeonTheme.current && !neonDecorated) 8.dp else 0.dp" in source &&
                "top = 8.dp" in source &&
                "bottom = 8.dp" in source
        )
    }

    @Test
    fun primaryScreensUseAContainedTopBar() {
        val sourceRoot = sourceRoot()
        val requiredScreens = listOf(
            "ui/library/LibraryScreen.kt",
            "ui/catalog/CatalogScreen.kt",
            "ui/setup/SetupScreen.kt",
            "ui/settings/AppLanguageScreen.kt",
            "ui/settings/GpuDriverScreen.kt",
            "ui/saves/SaveDataScreen.kt",
            "ui/playtime/PlayTimeScreen.kt",
            "ui/achievements/AchievementsScreen.kt",
            "ui/profile/ProfileScreen.kt",
            "ui/gamemanager/GameManagerScreen.kt"
        )

        val offenders = requiredScreens.filter { relativePath ->
            val source = sourceRoot.resolve(relativePath).readText()
            "ScreenTopBar(" !in source && "ScreenTopBarSurface" !in source
        }

        assertTrue("Primary screens missing the shared top bar: $offenders", offenders.isEmpty())
    }

    @Test
    fun navigationButtonsMatchTheContainedHeaderStyle() {
        val sourceRoot = sourceRoot()
        listOf("NavigationBackButton.kt", "NavigationMenuButton.kt").forEach { fileName ->
            val source = sourceRoot.resolve("ui/common/$fileName").readText()
            assertTrue("$fileName must be 44 dp", ".size(44.dp)" in source)
            assertTrue("$fileName must use the theme-aware 14 dp shape", "neonShape(14.dp)" in source)
            assertTrue("$fileName must not float", "shadowElevation: Dp = 0.dp" in source)
            assertTrue("$fileName must use the subtle border", "copy(alpha = 0.50f)" in source)
        }
    }

    private fun sourceRoot(): Path {
        val workingDirectory = Path.of(System.getProperty("user.dir"))
        val appModule = sequenceOf(
            workingDirectory,
            workingDirectory.resolve("app")
        ).firstOrNull { Files.isDirectory(it.resolve("src/main")) }
            ?: error("Unable to locate Android app module from $workingDirectory")
        return appModule.resolve("src/main/java/com/sbro/emucorec")
    }
}
