package com.sbro.emucorec.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CardboardPresentationTest {
    @Test
    fun perTitlePresentationValuesOverrideGlobalValuesAndClampImportedOffsets() {
        val global = mapOf("Video@@Cardboard VR" to "true", "Video@@VR Picture Size" to "80", "Video@@VR Eye Spacing" to "20")
        val game = mapOf("Video@@VR Picture Size" to "45", "Video@@VR Height" to "999")
        assertEquals(CardboardPresentation(true, 45, 20, 100), resolveCardboardPresentation(global + game))
    }

    @Test
    fun invalidBackupValuesFallBackToAnOrdinarySinglePicture() {
        val result = resolveCardboardPresentation(mapOf("Video@@Cardboard VR" to "unexpected", "Video@@VR Picture Size" to "NaN"))
        assertEquals(CardboardPresentation(false, 80, 0, 0), result)
        assertFalse(Ps3CoreSettingOverrides.validPresentationEdit("Video@@VR Picture Size", "NaN"))
        assertFalse(Ps3CoreSettingOverrides.validPresentationEdit("Video@@VR Height", "101"))
    }
}
