package pro.onetrace.sdk

import android.app.Activity
import android.content.Context
import androidx.annotation.RestrictTo
import pro.onetrace.sdk.internal.RichNotifications
import java.util.concurrent.CopyOnWriteArraySet

/**
 * For the other OneTrace modules (`onetrace-fcm`, `onetrace-location`) only: the platform API with the write key and the
 * hooks they need. Not part of the public API; it may change in any version.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object OneTraceModules {
    /** A response of the platform: status 0 without network, 304 when [etag] is still current. */
    public class Response(public val status: Int, public val body: String, public val etag: String?) {
        public val successful: Boolean get() = status in 200..299
    }

    /** Called on the main thread when an activity of the app resumes, for example after a permission prompt. */
    public fun interface ResumeListener {
        public fun onResumed(activity: Activity)
    }

    private val resumeListeners = CopyOnWriteArraySet<ResumeListener>()
    private val initListeners = CopyOnWriteArraySet<Runnable>()

    @JvmStatic
    public val isInitialized: Boolean get() = OneTrace.currentClient != null

    /** The app id the platform knows the installation by (the package name). */
    @JvmStatic
    public val appId: String? get() = OneTrace.currentClient?.device?.packageName

    /** A GET of the platform API on the calling thread (never the main one); null before `OneTrace.init`. */
    @JvmStatic
    public fun get(path: String, etag: String?): Response? {
        val result = OneTrace.currentClient?.apiGet(path, etag) ?: return null
        return Response(result.status, result.body, result.header("ETag"))
    }

    /**
     * An event that happened at [timestampMillis], for example a geofence transition recorded before `OneTrace.init`;
     * sent at once. Dropped before `OneTrace.init`.
     */
    @JvmStatic
    public fun track(event: String, properties: Map<String, Any?>, timestampMillis: Long) {
        OneTrace.currentClient?.let { client ->
            client.track(event, properties, timestampMillis)
            client.flush()
        }
    }

    /** Runs [listener] once `OneTrace.init` is called in this process (at once when it already was). */
    @JvmStatic
    public fun addInitListener(listener: Runnable) {
        if (isInitialized) {
            listener.run()
            return
        }

        initListeners.add(listener)

        // Init came between the check and the add.
        if (isInitialized && initListeners.remove(listener)) {
            listener.run()
        }
    }

    /** The app passes push data to the SDK: the installation reports the rich push capabilities. */
    @JvmStatic
    public fun richPushHandled() {
        OneTrace.currentClient?.richPushHandled()
    }

    /** The location permission may have changed: the installation reports it. */
    @JvmStatic
    public fun locationPermissionChanged() {
        OneTrace.currentClient?.refreshRegistration()
    }

    @JvmStatic
    public fun addResumeListener(listener: ResumeListener) {
        resumeListeners.add(listener)
    }

    @JvmStatic
    public fun removeResumeListener(listener: ResumeListener) {
        resumeListeners.remove(listener)
    }

    /** Draws a OneTrace notification from the data without reporting it; blocks while the images download. */
    @JvmStatic
    public fun showNotification(context: Context, data: Map<String, String>) {
        RichNotifications.show(context.applicationContext ?: context, data)
    }

    internal fun initialized() {
        initListeners.toList().forEach { listener ->
            if (initListeners.remove(listener)) {
                listener.run()
            }
        }
    }

    internal fun resumed(activity: Activity) {
        resumeListeners.forEach { it.onResumed(activity) }
    }
}
