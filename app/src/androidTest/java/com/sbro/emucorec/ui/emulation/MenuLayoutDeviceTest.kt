package com.sbro.emucorec.ui.emulation

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sbro.emucorec.core.Ps3CoreConfig
import com.sbro.emucorec.data.GameMenuLayoutStyle
import com.sbro.emucorec.ui.theme.EmuCoreCTheme
import com.sbro.emucorec.ui.theme.ThemeMode
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MenuLayoutDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val callbacks = EmulationMenuCallbacks(
        onPauseToggle = {},
        onExit = {},
        onEditControls = {},
        onControlsVisibility = {},
        onResetOverlay = {},
        onOverlayScale = {},
        onOverlayOpacity = {},
        onTouchHaptics = {},
        onTouchHapticsPreset = {},
        onTouchHapticsStrength = {},
        onGyroMode = {},
        onGyroSensitivity = {},
        onGyroSmoothing = {},
        onGyroInvertX = {},
        onGyroInvertY = {},
        onGamepadDeadzone = {},
        onGamepadAnalogMultiplier = {},
        onGamepadTriggerThreshold = {},
        onGamepadButtonProfile = {},
        onGamepadVibration = {},
        onGamepadVibrationStrength = {},
        onDeviceVibrationFallback = {},
        onGamepadSwapSticks = {},
        onGamepadInvertLeftX = {},
        onGamepadInvertLeftY = {},
        onGamepadInvertRightX = {},
        onGamepadInvertRightY = {},
        onOpenKeyboardMouseSettings = {},
        onSaveStateSlotSelected = {},
        onSaveStateSave = {},
        onSaveStateLoad = {},
        onSaveStateDelete = {}
    )

    @Test fun everyLayoutKeepsTheSharedHeaderAndSelectedTabInBothThemes() {
        val style = mutableStateOf(GameMenuLayoutStyle.SIDEBAR)
        val theme = mutableStateOf(ThemeMode.DARK)
        compose.setContent {
            EmuCoreCTheme(themeMode = theme.value, enableCrtOverlay = false) {
                EmulationGameMenu(
                    gameTitle = "Menu preview", gameId = "TEST12345", config = Ps3CoreConfig(),
                    paused = true, sessionElapsedMs = 45_000, expandHorizontally = true,
                    layoutStyle = style.value, physicalGamepadConnected = false,
                    callbacks = callbacks, selectedTab = EmulationMenuTab.Controls,
                    modifier = Modifier
                )
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = File(context.getExternalFilesDir(null), "validation/layouts").apply { mkdirs() }
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            for (layout in GameMenuLayoutStyle.entries) {
                compose.runOnIdle { style.value = layout; theme.value = mode }
                compose.waitForIdle()
                compose.onNodeWithText("Menu preview").assertExists()
                compose.onNodeWithTag("game-menu-tab-Controls").assertIsSelected()
                compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                    File(output, "${mode.name}-${layout.name}.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
        }
    }
}
