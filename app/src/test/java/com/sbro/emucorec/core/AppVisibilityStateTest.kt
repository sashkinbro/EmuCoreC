package com.sbro.emucorec.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AppVisibilityStateTest {
    @Test
    fun deferredCoreReceivesCurrentVisibilityWithoutLoadingItAtStartup() {
        val state = AppVisibilityState()
        val values = mutableListOf<Boolean>()
        val activity = Any()
        state.started(activity)
        state.stopped(activity, changingConfigurations = false)
        state.attach(values::add)
        assertEquals(listOf(true), values)
        state.started(activity)
        assertEquals(listOf(true, false), values)
    }

    @Test
    fun overlapAndDuplicateCallbacksDoNotBackgroundAnotherVisibleActivity() {
        val state = AppVisibilityState()
        val values = mutableListOf<Boolean>()
        state.attach(values::add)
        val library = Any()
        val emulator = Any()
        state.started(library)
        state.started(library)
        state.started(emulator)
        state.stopped(library, false)
        state.stopped(library, false)
        assertEquals(listOf(true, false), values)
        state.stopped(emulator, false)
        assertEquals(listOf(true, false, true), values)
    }

    @Test
    fun activityRecreationStaysForegroundAndLaterHomeBackgrounds() {
        val state = AppVisibilityState()
        val values = mutableListOf<Boolean>()
        val old = Any()
        val replacement = Any()
        state.started(old)
        state.attach(values::add)
        state.stopped(old, changingConfigurations = true)
        state.started(replacement)
        assertEquals(listOf(false), values)
        state.stopped(replacement, changingConfigurations = false)
        assertEquals(listOf(false, true), values)
        state.started(replacement)
        assertEquals(listOf(false, true, false), values)
    }

    @Test
    fun lateCoreAttachmentWhileVisibleReceivesForegroundImmediately() {
        val state = AppVisibilityState()
        val values = mutableListOf<Boolean>()
        state.started(Any())
        state.attach(values::add)
        assertEquals(listOf(false), values)
    }
}
