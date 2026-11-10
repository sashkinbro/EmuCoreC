package com.sbro.emucorec.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreSettingsTreeCacheTest {
    @After fun clear() = CoreSettingsTreeCache.invalidateTree()

    @Test fun anOldFetchCannotOverwriteSettingsPublishedAfterInvalidation() {
        CoreSettingsTreeCache.invalidateTree()
        val fetching = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val old = worker.submit<String> {
                CoreSettingsTreeCache.tree {
                    fetching.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    "previous game"
                }
            }
            assertTrue(fetching.await(5, TimeUnit.SECONDS))
            CoreSettingsTreeCache.invalidateTree()
            assertEquals("current game", CoreSettingsTreeCache.tree { "current game" })
            release.countDown()
            assertEquals("current game", old.get(5, TimeUnit.SECONDS))
            assertEquals("current game", CoreSettingsTreeCache.tree { error("Must reuse the current snapshot") })
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }
}
