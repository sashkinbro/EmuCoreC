package com.sbro.emucorec.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledPatchInstallerTest {
    @Test
    fun journeyAndNaughtyBearCompatibilityFixesAreEnabledWithoutOptionalLbpCheats() {
        val enabled = mutableListOf<BundledPatch>()
        BundledPatchInstaller.enablePending({ false }, { enabled += it; true }, {})
        assertTrue(enabled.any { it.serial == "NPUB30828" && it.hash == "SPU-7d3b6a847736ef3198deee3620f00c648e5e9588" })
        assertTrue(enabled.any { it.serial == "BCES01740" && it.hash == "SPU-9001b44fd7278b5a6fa5385939fe928a0e549394" })
        assertFalse(enabled.any { it.hash == "PPU-5af0f0d7824157a0aace48f5d0b06f6efe02fe4e" })
    }

    @Test
    fun successfulPatchesAreNotReenabledAndFailedOnesAreRetried() {
        val completed = mutableSetOf<String>()
        val attempts = mutableListOf<String>()
        val failure = BundledPatchInstaller.defaults[1]

        BundledPatchInstaller.enablePending(completed::contains, {
            attempts += it.key
            it != failure
        }, { completed += it })
        assertFalse(completed.contains(failure.key))
        assertEquals(BundledPatchInstaller.defaults.size - 1, completed.size)

        attempts.clear()
        BundledPatchInstaller.enablePending(completed::contains, {
            attempts += it.key
            true
        }, { completed += it })
        assertEquals(listOf(failure.key), attempts)
        assertEquals(BundledPatchInstaller.defaults.size, completed.size)
    }
}
