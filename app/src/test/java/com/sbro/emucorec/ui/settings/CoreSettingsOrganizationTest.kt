package com.sbro.emucorec.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreSettingsOrganizationTest {
    @Test fun settingsWithDifferentNativeParentsShareTheirPlayerFacingGroup() {
        val expected = mapOf(
            "Video@@Renderer" to CoreSettingsGroup.DisplayScaling,
            "Video@@Resolution Scale" to CoreSettingsGroup.DisplayScaling,
            "Video@@MSAA" to CoreSettingsGroup.DisplayScaling,
            "Video@@Aspect ratio" to CoreSettingsGroup.DisplayScaling,
            "Video@@Output Scaling Mode" to CoreSettingsGroup.DisplayScaling,
            "Video@@VR Eye Spacing" to CoreSettingsGroup.DisplayScaling,
            "Video@@Shader Mode" to CoreSettingsGroup.ShadersCache,
            "Video@@Disable On-Disk Shader Cache" to CoreSettingsGroup.ShadersCache,
            "Video@@Write Color Buffers" to CoreSettingsGroup.RenderingCompatibility,
            "Video@@Vulkan@@VRAM allocation limit (MB)" to CoreSettingsGroup.Vulkan,
            "Audio@@Master Volume" to CoreSettingsGroup.AudioOutput,
            "Audio@@Enable Buffering" to CoreSettingsGroup.AudioBuffering,
            "Audio@@Enable Time Stretching" to CoreSettingsGroup.AudioBuffering,
            "Audio@@Microphone Devices" to CoreSettingsGroup.Microphone,
            "System@@Keyboard Type" to CoreSettingsGroup.InputDevices,
            "Input/Output@@Keep pads connected" to CoreSettingsGroup.InputDevices,
            "Core@@PPU Decoder" to CoreSettingsGroup.CpuCompilation,
            "Core@@Max LLVM Compile Threads" to CoreSettingsGroup.CpuCompilation,
            "Core@@SPU Cache" to CoreSettingsGroup.CpuCompilation,
            "Core@@Accurate SPU Reservations" to CoreSettingsGroup.CpuCompatibility,
            "Video@@Debug overlay@@Enabled" to CoreSettingsGroup.Diagnostics,
            "Miscellaneous@@GDB Server" to CoreSettingsGroup.Diagnostics,
            "Log@@TTY" to CoreSettingsGroup.Diagnostics,
        )
        expected.forEach { (path, group) -> assertEquals(path, group, coreSettingsGroup(path)) }
    }

    @Test fun mainControlsComeBeforeTheirFineTuning() {
        assertTrue(coreSettingOrder("Video@@Renderer") < coreSettingOrder("Video@@Resolution Scale"))
        assertTrue(coreSettingOrder("Audio@@Enable Buffering") < coreSettingOrder("Audio@@Desired Audio Buffer Duration"))
        assertTrue(coreSettingOrder("Audio@@Enable Time Stretching") < coreSettingOrder("Audio@@Time Stretching Threshold"))
        assertTrue(coreSettingOrder("Core@@PPU Decoder") < coreSettingOrder("Core@@Max LLVM Compile Threads"))
    }

    @Test fun desktopHostOptionsAreHiddenOnEveryAndroidSurface() {
        val unsupported = listOf(
            "Miscellaneous@@Prevent display sleep while running games",
            "Miscellaneous@@Start games in fullscreen mode",
            "Miscellaneous@@Window Title Format",
            "Miscellaneous@@Enable GameMode",
            "Video@@Vulkan@@Exclusive Fullscreen Mode",
            "Input/Output@@Keyboard", "Input/Output@@Mouse",
            "Input/Output@@Camera ID", "Audio@@Music Handler",
        )
        Ps3CoreSettingsSurface.entries.forEach { surface ->
            unsupported.forEach { path -> assertFalse("$surface: $path", isCoreSettingVisibleOnSurface(path, surface)) }
        }
        // These controls have Android implementations and must not disappear with desktop fields.
        assertTrue(isCoreSettingVisibleOnSurface("Video@@Vulkan@@Use Re-BAR for GPU uploads", Ps3CoreSettingsSurface.FullSettings))
        assertTrue(isCoreSettingVisibleOnSurface("Audio@@Microphone Devices", Ps3CoreSettingsSurface.FullSettings))
        assertTrue(isCoreSettingVisibleOnSurface("Input/Output@@Camera", Ps3CoreSettingsSurface.GameProfile))
    }

    @Test fun cameraChoicesMatchTheImplementedAndroidDevice() {
        assertEquals(listOf("Null", "Fake"), userFacingCoreVariants("Input/Output@@Camera", listOf("Null", "Fake", "Qt", "SDL")))
    }

    @Test fun performanceAndNotificationsHaveOneOverlayOwnerWhileDiagnosticsAreAdvanced() {
        listOf("Video@@Performance Overlay@@Enabled", "Miscellaneous@@Show PPU compilation hint",
            "Miscellaneous@@Show trophy popups", "Video@@Shader Loading Dialog@@Blur effect strength").forEach {
            assertEquals(it, Ps3CoreSettingsCategory.Overlay, coreSettingsCategory(it))
        }
        listOf("Video@@Debug overlay", "Video@@Disable Video Output", "Audio@@Dump to file", "Input/Output@@IO Debug overlay",
            "Miscellaneous@@GDB Server").forEach {
            assertEquals(it, Ps3CoreSettingsCategory.Advanced, coreSettingsCategory(it))
        }
    }
}
