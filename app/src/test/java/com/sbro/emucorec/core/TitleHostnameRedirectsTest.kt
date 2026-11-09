package com.sbro.emucorec.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleHostnameRedirectsTest {
    @Test
    fun demonsSoulsRoutesToCommunityServerWithoutReplacingUserHosts() {
        val result = withTitleHostnameRedirects("C.DEMONS-SOULS.COM=192.0.2.5&&example.net=192.0.2.9", "blus30443")
        assertTrue(result.startsWith("C.DEMONS-SOULS.COM=192.0.2.5&&example.net=192.0.2.9&&"))
        assertTrue(result.contains("ds-eu-c.scej-online.jp=206.189.232.242"))
        assertEquals(1, result.split("&&").count { it.substringBefore('=').equals("c.demons-souls.com", true) })
        assertEquals(7, result.split("&&").size)
    }

    @Test
    fun redirectDefaultsDoNotAffectUnrelatedGamesOrUnknownTitles() {
        val custom = "  example.net=192.0.2.9  "
        assertEquals(custom, withTitleHostnameRedirects(custom, "BLUS00000"))
        assertEquals(custom, withTitleHostnameRedirects(custom, null))
    }
}
