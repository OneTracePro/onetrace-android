package pro.onetrace.sdk.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * App foreground and background from the process lifecycle; activities for automatic screens and for taps on push
 * notifications, which open the launcher activity with the notification data in the intent extras.
 */
internal class AppLifecycle(private val client: Client, private val trackScreens: Boolean, private val inApp: InAppPresenter) : DefaultLifecycleObserver, Application.ActivityLifecycleCallbacks {
    private var started = false

    /** Any thread: React Native calls the SDK from its native modules thread, the process lifecycle wants the main one. */
    fun attach(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
        val observe = { ProcessLifecycleOwner.get().lifecycle.addObserver(this) }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            observe()
        } else {
            Handler(Looper.getMainLooper()).post(observe)
        }
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

    override fun onActivityResumed(activity: Activity) = inApp.resumed(activity)

    override fun onActivityPaused(activity: Activity) = inApp.paused(activity)

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
