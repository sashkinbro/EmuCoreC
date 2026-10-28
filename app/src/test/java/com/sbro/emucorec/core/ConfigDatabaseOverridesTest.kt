package com.sbro.emucorec.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigDatabaseOverridesTest {
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
