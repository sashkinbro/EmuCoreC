package com.sbro.emucorec

import android.content.Intent
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucorec.core.EmulatorState
import com.sbro.emucorec.core.Ps3Core
import com.sbro.emucorec.core.Ps3IsoParser
import com.sbro.emucorec.core.Ps3Runtime
import com.sbro.emucorec.core.ps3.Emulator
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real-device regression: -e testGamePath <a PS3 disc image>. */
@RunWith(AndroidJUnit4::class)
class GameLaunchDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test(timeout = 600_000)
    fun gameBootKeepsUiResponsiveAndMenuRestoresItsTabAndScroll() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val path = InstrumentationRegistry.getArguments().getString("testGamePath")
        assumeNotNull(path)
        val context = instrumentation.targetContext
        assertTrue(Ps3Runtime.ensureInitialized(context))
        val game = Ps3IsoParser.parse(context, File(requireNotNull(path)))
        assertNotNull("Test game metadata must be readable", game)
        val title = requireNotNull(game)
        val started = SystemClock.elapsedRealtime()
        val activity = instrumentation.startActivitySync(Intent(context, Emulator::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(Emulator.EXTRA_GAME_PATH, path)
            .putExtra(Emulator.EXTRA_TITLE_ID, title.titleId)) as Emulator
        val activityReadyMs = SystemClock.elapsedRealtime() - started
        var maxResponseMs = 0L
        var peakPssKb = 0L
        val log = File(Ps3Core.rootDirectory, "cache/emucorec.log")
        val output = File(context.getExternalFilesDir(null), "validation").apply { mkdirs() }
        try {
            val deadline = started + 540_000
            while (!startupReady(log)) {
                assertTrue("Boot never completed; see native log", SystemClock.elapsedRealtime() < deadline)
                val responded = CountDownLatch(1)
                val posted = SystemClock.elapsedRealtime()
                Handler(Looper.getMainLooper()).post { responded.countDown() }
                assertTrue("Main thread stalled during boot", responded.await(2, TimeUnit.SECONDS))
                maxResponseMs = maxOf(maxResponseMs, SystemClock.elapsedRealtime() - posted)
                peakPssKb = maxOf(peakPssKb, Debug.getPss())
                SystemClock.sleep(250)
            }
            val bootReadyMs = SystemClock.elapsedRealtime() - started
            File(output, "launch-${title.titleId}.txt").writeText(
                "title=${title.titleId}\nactivity_ms=$activityReadyMs\nstartup_ms=$bootReadyMs\n" +
                    "max_main_response_ms=$maxResponseMs\npeak_pss_kb=$peakPssKb\n")
            assertTrue("The activity should appear before a first-launch ANR", activityReadyMs < 3000)
            assertEquals(EmulatorState.Running, Ps3Runtime.state())
            compose.onNodeWithTag("launch_state_loading_overlay").assertDoesNotExist()
            instrumentation.runOnMainSync { activity.openPauseMenuFromController() }
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("game-menu-tab-Controls").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("game-menu-tab-Controls").performScrollTo().performClick()
            compose.onNodeWithTag("game-menu-tab-Controls").assertIsSelected()
            compose.onNodeWithTag("game-menu-content").performTouchInput { swipeUp() }
            compose.waitForIdle()
            fun offset() = compose.onNodeWithTag("game-menu-content").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value()
            val before = offset()
            assertTrue("The test must actually scroll the controls tab", before > 0f)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("game-menu-tab-Controls").fetchSemanticsNodes().isEmpty()
            }
            instrumentation.runOnMainSync { activity.openPauseMenuFromController() }
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("game-menu-tab-Controls").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("game-menu-tab-Controls").assertIsSelected()
            assertEquals("Reopening must retain the exact content position", before, offset(), 2f)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(output, "menu-${title.titleId}.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            val stopDeadline = SystemClock.elapsedRealtime() + 20_000
            while (Ps3Runtime.state() != EmulatorState.Stopped && SystemClock.elapsedRealtime() < stopDeadline) {
                SystemClock.sleep(100)
            }
            assertEquals("The device must be left with emulation stopped", EmulatorState.Stopped, Ps3Runtime.state())
        }
    }

    private fun startupReady(log: File): Boolean = runCatching {
        RandomAccessFile(log, "r").use { input ->
            input.seek((input.length() - 4_194_304).coerceAtLeast(0))
            generateSequence { input.readLine() }.any { "Startup PPU/SPU modules ready" in it }
        }
    }.getOrDefault(false)
}
