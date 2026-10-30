package pro.onetrace.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import pro.onetrace.sdk.internal.AppLifecycle
import pro.onetrace.sdk.internal.Client
import pro.onetrace.sdk.internal.DeviceContext
import pro.onetrace.sdk.internal.EventQueue
import pro.onetrace.sdk.internal.HttpTransport
import pro.onetrace.sdk.internal.InAppPresenter
import pro.onetrace.sdk.internal.Storage
import java.io.File
import java.util.concurrent.Executors

/**
 * OneTrace.pro for Android: events, screens and identities of app users, installations for mobile push.
 *
 * Call [init] once in `Application.onCreate()`; every other call is safe from any thread and does nothing before it.
 */
public object OneTrace {
    @Volatile
    private var client: Client? = null

    @Volatile
    private var inApp: InAppPresenter? = null

    @Volatile
    private var wrapper: Pair<String, String>? = null

    /** Push provider of the token passed to [setPushToken]. */
    public enum class PushProvider(internal val key: String) { FCM("fcm"), APNS("apns") }

    @JvmStatic
    @Synchronized
    public fun init(context: Context, config: OneTraceConfig) {
        if (client != null) {
            return
        }

        val app = context.applicationContext
        val storage = Storage(app)
        val device = DeviceContext(app, storage.installationId).also { it.wrapper = wrapper }
        val executor = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "onetrace").apply { isDaemon = true } }
        val transport = HttpTransport(config.apiUrl, config.writeKey, "onetrace-android/${BuildConfig.SDK_VERSION} (${app.packageName})")
        val queue = EventQueue(File(app.filesDir, "onetrace-queue.jsonl"), config.maxQueueSize)
        val created = Client(app, config, storage, queue, transport, executor, device)
        val inApp = InAppPresenter(config.openPushLinks) { token, event, url -> created.reportInApp(token, event, url) }
        created.presenter = inApp::present
        client = created
        this.inApp = inApp

        created.appLaunched()
        created.start()
        (app as? Application)?.let { AppLifecycle(created, config.trackActivityScreens, inApp).attach(it) }
            ?: Log.w(Client.TAG, "OneTrace.init() needs the application context: lifecycle and push taps are not tracked")
    }

    /** Your own event, for example `track("order_completed", mapOf("order_id" to "1001", "amount" to 1250.5))`. */
    @JvmStatic
    @JvmOverloads
    public fun track(event: String, properties: Map<String, Any?> = emptyMap()) {
        client?.track(event, properties)
    }

    /** A screen of the app: the `$screen` event with the screen name. */
    @JvmStatic
    @JvmOverloads
    public fun screen(name: String, properties: Map<String, Any?> = emptyMap()) {
        client?.screen(name, properties)
    }

    /**
     * The user signed in or updated their data: `identify("u-42", mapOf("email" to "anna@example.com"))`. Pass a null
     * user id to update traits of the current visitor.
     */
    @JvmStatic
    @JvmOverloads
    public fun identify(userId: String?, traits: Map<String, Any?> = emptyMap()) {
        client?.identify(userId, traits)
    }

    /** Links the current anonymous visitor to a new user id. */
    @JvmStatic
    public fun alias(userId: String) {
        client?.alias(userId)
    }

    /** The user signed out: a new anonymous id, and the installation leaves the previous user's profile. */
    @JvmStatic
    public fun reset() {
        client?.reset()
    }

    /** Sends queued events now instead of waiting for the next batch. */
    @JvmStatic
    public fun flush() {
        client?.flush()
    }

    /**
     * The push token of this installation: from `FirebaseMessagingService.onNewToken()` (the `onetrace-fcm` module does
     * it for you) or your own push setup. Null removes it.
     */
    @JvmStatic
    @JvmOverloads
    public fun setPushToken(token: String?, provider: PushProvider = PushProvider.FCM) {
        client?.setPushToken(token, provider.key)
    }

    /**
     * Holds in-app messages back while true, for example during checkout; they are shown after it is set back to false
     * and the app opens again or tracks their trigger event.
     */
    @JvmStatic
    public fun pauseInAppMessages(paused: Boolean) {
        client?.inAppPaused = paused
    }

    /** Asks OneTrace for in-app messages now; the SDK also does it when the app opens and after sending events. */
    @JvmStatic
    public fun fetchInAppMessages() {
        client?.fetchInApp()
    }

    /** Call after the user answered the notification permission prompt: the platform learns whether push can be sent. */
    @JvmStatic
    public fun notificationPermissionChanged() {
        client?.refreshRegistration()
    }

    /**
     * Reports a tap on a OneTrace notification and opens its link. Activities are checked automatically when they are
     * created; call it from `onNewIntent()` of a `singleTop` or `singleTask` activity.
     *
     * @return true when the intent came from a OneTrace notification.
     */
    @JvmStatic
    public fun handleIntent(context: Context, intent: Intent?): Boolean = client?.handlePushIntent(intent, context) ?: false

    /**
     * A tap on a OneTrace notification that your own code showed or received, for example `onMessageOpenedApp` of
     * firebase_messaging: reports the open once and opens its link.
     *
     * @return true when the data came from a OneTrace notification.
     */
    @JvmStatic
    public fun notificationOpened(context: Context, data: Map<String, String>): Boolean = client?.notificationOpened(data, context) ?: false

    /** A OneTrace notification reached the app (`onMessageReceived`): the message becomes delivered. */
    @JvmStatic
    public fun notificationReceived(data: Map<String, String>) {
        client?.pushReceived(data)
    }

    /** True when the data came from a OneTrace notification. */
    @JvmStatic
    public fun isOneTraceNotification(data: Map<String, String>): Boolean = data.containsKey(Client.PUSH_TOKEN)

    @JvmStatic
    public val anonymousId: String? get() = client?.anonymousId

    @JvmStatic
    public val userId: String? get() = client?.userId

    /** The installation id: the `mobile_push` identity of the profile while push can be sent. */
    @JvmStatic
    public val installationId: String? get() = client?.installationId

    /** For the Flutter and React Native wrappers, before [init]: report the wrapper as the library. */
    @JvmStatic
    public fun setWrapper(name: String, version: String) {
        wrapper = name to version
        client?.device?.wrapper = wrapper
    }

    /**
     * For the Flutter and React Native wrappers, which start the SDK after their activity was created: checks the
     * activity intent for a notification tap and shows in-app messages over the activity.
     */
    @JvmStatic
    public fun attachActivity(activity: Activity) {
        val current = client ?: return
        current.handlePushIntent(activity.intent, activity)
        val resumed = (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true

        if (resumed) {
            Handler(Looper.getMainLooper()).post { inApp?.resumed(activity) }
        }
    }

    /** Tests only: forget the instance. */
    @JvmStatic
    internal fun shutdownForTests() {
        client = null
        inApp = null
    }
}
