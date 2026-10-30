package pro.onetrace.sdk.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * App foreground and background from the process lifecycle; activities for automatic screens and for taps on push
 * notifications, which open the launcher activity with the notification data in the intent extras.
 */
internal class AppLifecycle(private val client: Client, private val trackScreens: Boolean) : DefaultLifecycleObserver, Application.ActivityLifecycleCallbacks {
    private var started = false

    fun attach(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        client.appForeground(fromBackground = started)
        started = true
    }

    override fun onStop(owner: LifecycleOwner) {
        client.appBackground()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        client.handlePushIntent(activity.intent, activity)
    }

    override fun onActivityStarted(activity: Activity) {
        if (trackScreens) {
            client.screen(activity.title?.toString()?.takeIf { it.isNotBlank() } ?: activity.javaClass.simpleName, mapOf("activity" to activity.javaClass.name))
        }
    }

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
