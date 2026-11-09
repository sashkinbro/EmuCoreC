package com.sbro.emucorec.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigDatabaseOverridesTest {
    @Test
    fun desktopLimitsAreFilteredWithoutDroppingConsolePacingOrRsxOffload() {
        val source = "Core:\n  Max SPURS Threads: 3\nVideo:\n  Frame limit: Off\n  Multithreaded RSX: true\n"
        val result = ConfigDatabaseSeeder.prepareConfig("BLUS00000", source)
        assertEquals("Core:\nVideo:\n  Multithreaded RSX: true\n", result)
        assertEquals(
            "Video:\n  Frame limit: PS3 Native\n",
            ConfigDatabaseSeeder.prepareConfig("BLUS00000", "Video:\n  Frame limit: PS3 Native\n"),
        )
    }

    @Test
    fun finalFantasyDefaultsReplaceDesktopValuesAndPreserveLibraries() {
        val result = ConfigDatabaseSeeder.prepareConfig(
            "MRTC00003",
            "Core:\n  SPU Block Size: Safe\n  Stub PPU Traps: 0\n  Libraries Control: Load liblv2.sprx only\nVideo:\n  Resolution: 720p\n",
        )
        assertTrue(result.contains("  SPU Block Size: Mega\n"))
        assertTrue(result.contains("  Stub PPU Traps: 20\n"))
        assertTrue(result.contains("  Libraries Control: Load liblv2.sprx only\n"))
        assertTrue(result.contains("  Resolution: 720p\n"))
    }

    @Test
    fun ratchetDefaultsMergeCpuAndVulkanWithoutReplacingOtherVideoOptions() {
        val result = ConfigDatabaseSeeder.prepareConfig(
            "BCES00052",
            "Core:\n  Thread Scheduler Mode: Operating System\nVideo:\n  Vulkan:\n    Adapter: Default\n    Early Submit Draw Threshold: 0\n  Write Color Buffers: true\n",
        )
        assertTrue(result.contains("  Thread Scheduler Mode: RPCS3 Scheduler\n"))
        assertTrue(result.contains("  SPU GETLLAR Busy Waiting Percentage: 0\n"))
        assertTrue(result.contains("    Vertex Fetch Specialization: true\n"))
        assertTrue(result.contains("    Early Submit Draw Threshold: 1024\n"))
        assertTrue(result.contains("    Adapter: Default\n"))
        assertTrue(result.contains("  Write Color Buffers: true\n"))
    }

    @Test
    fun gameDefaultsDoNotCarryIntoAnotherTitle() {
        assertTrue(ConfigDatabaseSeeder.prepareConfig("BCUS98232", "").contains("  Stub PPU Traps: 1\n"))
        assertTrue(ConfigDatabaseSeeder.prepareConfig("NPUB30828", "").contains("  SPU Block Size: Mega\n"))
        assertTrue(ConfigDatabaseSeeder.prepareConfig("BCUS98111", "").contains("  SPU XFloat Accuracy: Relaxed\n"))
        val unrelated = "Core:\n  Stub PPU Traps: 0\nVideo:\n  Shader Precision: High\n"
        assertEquals(unrelated, ConfigDatabaseSeeder.prepareConfig("BLUS00000", unrelated))
    }

    @Test
    fun overridesReplaceExistingCoreValuesWithoutTouchingOtherSections() {
        val source = "Core:\n  PPU Event Wake-Up Delay: 25\n  SPU Decoder: LLVM\nVideo:\n  PPU Event Wake-Up Delay: 4"
        assertEquals(
            "Core:\n  PPU Event Wake-Up Delay: 35\n  SPU Decoder: LLVM\nVideo:\n  PPU Event Wake-Up Delay: 4\n",
            applyCoreOverrides(source, mapOf("PPU Event Wake-Up Delay" to "35")),
        )
    }

    @Test
    fun overridesAddMissingCoreSectionAndKeepExistingVideoSettings() {
        assertEquals(
            "Video:\n  Write Color Buffers: true\nCore:\n  MFC Commands Shuffling Limit: 1\n",
            applyCoreOverrides("Video:\n  Write Color Buffers: true", mapOf("MFC Commands Shuffling Limit" to "1")),
        )
    }

    @Test
    fun vulkanOverridesPreserveOtherVideoAndCoreSettings() {
        val source = "Video:\n  Vulkan:\n    VRAM allocation limit (MB): 2048\n    Adapter: Default\n  Frame limit: 60\nCore:\n  SPU Cache: false"
        assertEquals(
            "Video:\n  Vulkan:\n    VRAM allocation limit (MB): 3072\n    Adapter: Default\n  Frame limit: 60\nCore:\n  SPU Cache: false\n",
            applyVulkanOverrides(source, mapOf("VRAM allocation limit (MB)" to "3072")),
        )
    }
}
