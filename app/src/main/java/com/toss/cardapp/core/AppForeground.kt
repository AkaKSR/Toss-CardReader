package com.toss.cardapp.core

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tracks whether any of the app's screens is resumed. The background card watcher only holds the reader while
 * none is, so the reader screen (and the screens it opens) keep exactly the 1.0.0 behaviour. Main thread only.
 */
object AppForeground : Application.ActivityLifecycleCallbacks {
    private var resumed = 0
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    val visible: Boolean get() = resumed > 0

    fun install(app: Application) = app.registerActivityLifecycleCallbacks(this)

    fun addListener(l: (Boolean) -> Unit) = listeners.add(l)

    fun removeListener(l: (Boolean) -> Unit) = listeners.remove(l)

    // Dispatched from inside super.onResume()/super.onPause(), so the watcher lets go of the reader before
    // ReaderActivity arms its own checkCard, and takes it back only after the screen has cancelled its own.
    override fun onActivityResumed(activity: Activity) {
        if (++resumed == 1) listeners.forEach { it(true) }
    }

    override fun onActivityPaused(activity: Activity) {
        if (--resumed == 0) listeners.forEach { it(false) }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
