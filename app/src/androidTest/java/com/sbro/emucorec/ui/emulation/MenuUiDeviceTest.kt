package com.sbro.emucorec.ui.emulation

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sbro.emucorec.core.SaveStateSlot
import com.sbro.emucorec.ui.settings.SettingsScreen
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MenuUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingsHeaderScrollsAwayWhileOnlyTabsRemainPinned() {
        compose.setContent { MaterialTheme { SettingsScreen(onBackClick = {}, onOpenLanguageSettings = {}) } }
        val initialTop = compose.onNodeWithTag("settings-tabs").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("settings-scroll").performScrollToIndex(2)
        compose.waitForIdle()
        val pinnedTop = compose.onNodeWithTag("settings-tabs").fetchSemanticsNode().boundsInRoot.top
        assertTrue("The screen header must scroll away", pinnedTop < initialTop - 20f)
        compose.onNodeWithTag("settings-scroll").performScrollToIndex(2)
        assertEquals(pinnedTop, compose.onNodeWithTag("settings-tabs").fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test fun overwritingTheSameSaveSlotRefreshesItsScreenshot() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("save-preview-", ".png", context.cacheDir)
        fun write(color: Int) {
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val slot = mutableStateOf(SaveStateSlot(0, 1, 100, thumbnailPath = file.path, thumbnailRevision = 1))
        fun center(): Color {
            val pixels = compose.onNodeWithTag("save-state-preview").captureToImage().toPixelMap()
            return pixels[pixels.width / 2, pixels.height / 2]
        }
        try {
            write(android.graphics.Color.RED)
            compose.setContent { MaterialTheme { SaveStatePreview(slot.value, Modifier.size(160.dp)) } }
            compose.waitUntil(5000) { center() == Color.Red }
            write(android.graphics.Color.BLUE)
            compose.runOnIdle { slot.value = slot.value.copy(thumbnailRevision = 2) }
            compose.waitUntil(5000) { center() == Color.Blue }
        } finally { file.delete() }
    }
}
