package com.sbro.emucorec.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BundledPatchInstallerTest {
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
