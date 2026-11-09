package com.sbro.emucorec.core

import java.util.Collections
import java.util.IdentityHashMap

/** Process visibility, independent of emulator pause and of whether the core is loaded. */
internal class AppVisibilityState {
    private val started = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private var backgrounded = true
    private var sink: ((Boolean) -> Unit)? = null

    // Core loading runs off the main thread. Serialize attaching and updates so an older
    // snapshot cannot overwrite a newer activity transition in native code.
    @Synchronized
    fun attach(sink: (Boolean) -> Unit) {
        this.sink = sink
        sink(backgrounded)
    }

    @Synchronized
    fun started(activity: Any) {
        if (started.add(activity)) update(false)
    }

    @Synchronized
    fun stopped(activity: Any, changingConfigurations: Boolean) {
        if (!started.remove(activity)) return
        // Configuration replacement is still visible to the user. Its new activity
        // will start before the next genuine background transition.
        if (started.isEmpty() && !changingConfigurations) update(true)
    }

    private fun update(value: Boolean) {
        if (backgrounded == value) return
        backgrounded = value
        sink?.invoke(value)
    }
}
