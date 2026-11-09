package com.sbro.emucorec.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreSettingsSurfaceTest {
    @Test
    fun inGameSurfaceKeepsDeviceAndSystemSettingsSeparate() {
        assertTrue(isCoreSettingVisibleOnSurface("Video@@Resolution Scale", Ps3CoreSettingsSurface.InGame))
        assertTrue(isCoreSettingVisibleOnSurface("Audio@@Master Volume", Ps3CoreSettingsSurface.InGame))
        assertFalse(isCoreSettingVisibleOnSurface("System@@Language", Ps3CoreSettingsSurface.InGame))
        assertFalse(isCoreSettingVisibleOnSurface("System@@Keyboard Type", Ps3CoreSettingsSurface.InGame))
        assertFalse(isCoreSettingVisibleOnSurface("Net@@Network Status", Ps3CoreSettingsSurface.InGame))
        assertFalse(isCoreSettingVisibleOnSurface("VFS@@$(EmulatorDir)", Ps3CoreSettingsSurface.InGame))
    }

    @Test
    fun inGameSurfaceOffersBootSettingsThroughTheDeferredApplyFlow() {
        assertTrue(isCoreSettingVisibleOnSurface("Core@@PPU Decoder", Ps3CoreSettingsSurface.InGame))
        assertTrue(isCoreSettingVisibleOnSurface("Video@@Renderer", Ps3CoreSettingsSurface.InGame))
        assertTrue(isCoreSettingVisibleOnSurface("Video@@Write Color Buffers", Ps3CoreSettingsSurface.InGame))
        assertFalse(isCoreSettingVisibleOnSurface("Video@@Vulkan@@Adapter", Ps3CoreSettingsSurface.InGame))
    }

    @Test
    fun inGameSurfaceOffersAudioBackendsAndCompilationNotices() {
        assertTrue(isCoreSettingVisibleOnSurface("Audio@@Recording Compatible", Ps3CoreSettingsSurface.InGame))
        assertTrue(isCoreSettingVisibleOnSurface("Audio@@Cubeb Backend", Ps3CoreSettingsSurface.InGame))
        assertTrue(isCoreSettingVisibleOnSurface("Miscellaneous@@Show SPU compilation hint", Ps3CoreSettingsSurface.InGame))
        assertEquals(Ps3CoreSettingsCategory.Overlay, coreSettingsCategory("Miscellaneous@@Show SPU compilation hint"))
    }

    @Test
    fun desiredPerTitleBootValueWinsOverGlobalAndKeepsRestartPending() {
        val state = resolveCoreSettingEditState(
            "Core@@PPU Decoder", "Interpreter (static)", false,
            mapOf("Core@@PPU Decoder" to "\"Interpreter (static)\""),
            mapOf("Core@@PPU Decoder" to "\"Recompiler (LLVM)\""),
            setOf("Core@@PPU Decoder"),
        )
        assertEquals("Recompiler (LLVM)", state.value)
        assertTrue(state.overridden)
        assertTrue(state.pendingRestart)
    }

    @Test
    fun liveChangesAndRecommendedDatabaseDifferencesDoNotRequestRestart() {
        val live = resolveCoreSettingEditState(
            "Audio@@Master Volume", "100", true,
            emptyMap(), mapOf("Audio@@Master Volume" to "50"), emptySet(),
        )
        assertEquals("50", live.value)
        assertFalse(live.pendingRestart)
        val database = resolveCoreSettingEditState(
            "Video@@Write Color Buffers", "false", false,
            mapOf("Video@@Write Color Buffers" to "true"), emptyMap(), emptySet(),
        )
        assertFalse(database.pendingRestart)
        val reverted = resolveCoreSettingEditState(
            "Core@@SPU Block Size", "Safe", false,
            emptyMap(), mapOf("Core@@SPU Block Size" to "\"Safe\""), emptySet(),
        )
        assertFalse(reverted.pendingRestart)
    }

    @Test
    fun gameProfilesExcludeGlobalDevicesAndStorage() {
        assertTrue(isCoreSettingVisibleOnSurface("System@@Language", Ps3CoreSettingsSurface.GameProfile))
        assertTrue(isCoreSettingVisibleOnSurface("Video@@Write Color Buffers", Ps3CoreSettingsSurface.GameProfile))
        assertFalse(isCoreSettingVisibleOnSurface("Video@@Vulkan@@Adapter", Ps3CoreSettingsSurface.GameProfile))
        assertFalse(isCoreSettingVisibleOnSurface("Audio@@Audio Device", Ps3CoreSettingsSurface.GameProfile))
        assertFalse(isCoreSettingVisibleOnSurface("VFS@@$(EmulatorDir)", Ps3CoreSettingsSurface.GameProfile))
        assertTrue(isCoreSettingVisibleOnSurface("Net@@PSN status", Ps3CoreSettingsSurface.GameProfile))
        assertFalse(isCoreSettingVisibleOnSurface("Net@@DNS address", Ps3CoreSettingsSurface.GameProfile))
    }

    @Test
    fun compiledRenderersAreOfferedAndNullIsHidden() {
        assertTrue(
            userFacingCoreVariants("Video@@Renderer", listOf("Null", "OpenGL", "Vulkan")) ==
                listOf("OpenGL", "Vulkan")
        )
    }
}
