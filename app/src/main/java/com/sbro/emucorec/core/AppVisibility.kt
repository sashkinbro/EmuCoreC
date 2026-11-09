package com.sbro.emucorec.core

import android.app.Activity
import android.app.Application
import android.os.Bundle

internal object AppVisibility : Application.ActivityLifecycleCallbacks {
    private val state = AppVisibilityState()

    // Called only once a caller has loaded the core for actual work. Registering
    // the lifecycle observer never initializes Ps3Core or its JNI library.
    fun coreLoaded(report: (Boolean) -> Unit) = state.attach(report)

    override fun onActivityStarted(activity: Activity) = state.started(activity)
    override fun onActivityStopped(activity: Activity) =
        state.stopped(activity, activity.isChangingConfigurations)

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
