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
 * Call [init] once in `Application.onCreate()`; every call, [init] included, is safe from any thread, and the others do
 * nothing before it.
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
     * The user signed in or updated their data: `identify("u-42", mapOf("email" to "anna@example.com"), userHash)`.
     * Pass a null user id to update traits of the current visitor.
     *
     * [userHash] is the signature of the user id: HMAC-SHA256 of [userId] with the project's signing key, in hex. Your
     * server computes it after sign-in and returns it to the app; never put the signing key into the app. When the
     * project requires the signature, a user id without it is ignored. The SDK keeps it with the user id and sends it
     * with every event and the installation; a later `identify` of the same user without it keeps the stored one.
     */
    @JvmStatic
    @JvmOverloads
    public fun identify(userId: String?, traits: Map<String, Any?> = emptyMap(), userHash: String? = null) {
        client?.identify(userId, traits, userHash)
    }

    /** Links the current anonymous visitor to a new user id; [userHash] is its signature, as in [identify]. */
    @JvmStatic
    @JvmOverloads
    public fun alias(userId: String, userHash: String? = null) {
        client?.alias(userId, userHash)
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

    /**
     * Loads the message feed: cards that stay in the app until they expire or the user removes them, pinned first, then
     * the newest, up to 50. [callback] is called on the main thread; before [init] with an empty list and `false`.
     */
    @JvmStatic
    public fun fetchFeed(callback: FeedCallback) {
        client?.feed?.fetch(callback) ?: Handler(Looper.getMainLooper()).post { callback.onFeed(emptyList(), false) }
    }

    /** The feed as last loaded, with the shows, clicks and removals since then; no request. */
    @JvmStatic
    public val feedCards: List<FeedCard> get() = client?.feed?.current ?: emptyList()

    /** Cards of the last loaded feed the user has not seen yet. */
    @JvmStatic
    public val feedUnreadCount: Int get() = client?.feed?.unread ?: 0

    /**
     * Calls [listener] on the main thread with the unread count now and every time it changes. While a listener is
     * added, the SDK loads the feed when the app opens and after it sends events (at most once a minute).
     */
    @JvmStatic
    public fun addFeedUnreadListener(listener: FeedUnreadListener) {
        client?.feed?.addUnreadListener(listener)
    }

    @JvmStatic
    public fun removeFeedUnreadListener(listener: FeedUnreadListener) {
        client?.feed?.removeUnreadListener(listener)
    }

    /**
     * For your own feed UI: the card became visible (at least half of it) in the open feed. Reported once per card; the
     * card becomes read and the message delivered and opened.
     */
    @JvmStatic
    public fun feedCardShown(card: FeedCard) {
        client?.feed?.shown(card.token)
    }

    /**
     * For your own feed UI: a tap on the button, or on the card when [FeedCard.opensOnTap]. Reports the click with the
     * link and opens it — through [setFeedLinkHandler], or as push links are opened (`openPushLinks`).
     *
     * @return false when the card has no link that may be opened (`javascript:`, `data:` and `file:` links are not).
     */
    @JvmStatic
    public fun feedCardClicked(context: Context, card: FeedCard): Boolean = client?.feed?.clicked(context, card) ?: false

    /** For your own feed UI: the user removed the card; it does not come back. */
    @JvmStatic
    public fun feedCardDismissed(card: FeedCard) {
        client?.feed?.dismissed(card.token)
    }

    /** Opens the links of feed cards in your own way; null returns to the default. */
    @JvmStatic
    public fun setFeedLinkHandler(handler: FeedLinkHandler?) {
        client?.feed?.linkHandler = handler
    }

    /** Opens the ready-made feed screen, [OneTraceFeedActivity]; [OneTraceFeedView] puts the feed into your own one. */
    @JvmStatic
    public fun openFeed(context: Context) {
        val intent = Intent(context, OneTraceFeedActivity::class.java)

        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)
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

    /**
     * For the Flutter and React Native wrappers, before [init]: report the wrapper as the library — `flutter` becomes
     * `onetrace-flutter` in the event context and `flutter 1.0.0` in the installation.
     */
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

    /** The feed of the initialized SDK, for [OneTraceFeedView]. */
    internal val feed: pro.onetrace.sdk.internal.Feed? get() = client?.feed

    /** Tests only: the instance to use. */
    @JvmStatic
    internal fun setClientForTests(client: Client?) {
        this.client = client
    }

    /** Tests only: forget the instance. */
    @JvmStatic
    internal fun shutdownForTests() {
        client = null
        inApp = null
    }
}
