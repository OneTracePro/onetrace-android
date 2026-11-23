package pro.onetrace.sdk.internal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import pro.onetrace.sdk.OneTraceConfig
import pro.onetrace.sdk.OneTraceRemoteConfig
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The SDK engine. Every call goes through one background thread in call order: events are stamped with the ids that
 * were current when they were made, disk and network never touch the main thread.
 */
internal class Client(
    private val context: Context,
    private val config: OneTraceConfig,
    private val storage: Storage,
    private val queue: EventQueue,
    private val transport: Transport,
    private val executor: ScheduledExecutorService,
    val device: DeviceContext,
    private val clock: () -> Long = System::currentTimeMillis,
    private val notificationsEnabled: () -> Boolean = { NotificationManagerCompat.from(context).areNotificationsEnabled() },
    val remoteConfig: OneTraceRemoteConfig = OneTraceRemoteConfig(),
    private val locationPermission: () -> String = { LocationPermission.of(context) },
) {
    /** Shows in-app messages; set by [pro.onetrace.sdk.OneTrace] (null in tests that do not need it). */
    @Volatile
    var presenter: ((JSONObject) -> Unit)? = null

    /** In-app messages received but not shown yet (SDK thread only). */
    private val inApp = mutableListOf<JSONObject>()
    private val seen = LinkedHashSet<String>()
    private val openedTokens = LinkedHashSet<String>()

    @Volatile
    var inAppPaused = false

    /** The app passes push data to the SDK (the config flag, or the `onetrace-fcm` module): rich push can be sent. */
    @Volatile
    var richPush = config.richPush || storage.richPush || fcmServiceDeclared()
        private set

    /** The `onetrace-fcm` module or the app's own service passes push data to the SDK. */
    fun richPushHandled() {
        if (!richPush) {
            richPush = true
            storage.richPush = true
            refreshRegistration()
        }
    }

    /** The bundled service of `onetrace-fcm` is in the app manifest: it passes every OneTrace message to the SDK. */
    private fun fcmServiceDeclared(): Boolean = try {
        context.packageManager.getServiceInfo(android.content.ComponentName(context, "pro.onetrace.sdk.fcm.OneTraceMessagingService"), 0)
        true
    } catch (e: Exception) {
        false
    }

    /** The message feed of the current anonymous id. */
    val feed = Feed(transport, executor, { storage.anonymousId }, config.openPushLinks, ::log, clock)

    private val configStore = RemoteConfigStore(context, transport, config.remoteConfigMinFetchIntervalSeconds * 1000, clock).also {
        remoteConfig.store = it
        remoteConfig.fetcher = { force, done -> run { done(fetchConfigNow(force)) } }
    }

    fun start() {
        executor.scheduleWithFixedDelay({ flushNow() }, config.flushIntervalSeconds, config.flushIntervalSeconds, TimeUnit.SECONDS)
        run {
            registerNow(force = true)
            fetchConfigNow(force = false)
        }
    }

    /** [timestamp]: when it happened, for an event recorded before the SDK started (a geofence in the background). */
    fun track(event: String, properties: Map<String, Any?>, timestamp: Long? = null) = run {
        enqueue("track", JSONObject().put("event", event).put("properties", Json.obj(properties)), timestamp)
        presentInApp(event)
    }

    fun screen(name: String, properties: Map<String, Any?>) = run {
        enqueue("screen", JSONObject().put("name", name).put("properties", Json.obj(properties)))
    }

    fun identify(userId: String?, traits: Map<String, Any?>, userHash: String?) = run {
        val changed = userId != null && userId != storage.userId
        // A signature belongs to its user id: a new user drops the previous one, the same user keeps it until a new one.
        val signed = userId != null && userHash != null && userHash != storage.userHash

        if (userId != null) {
            storage.userId = userId
        }

        if (changed || signed) {
            storage.userHash = userHash
        }

        enqueue("identify", JSONObject().put("traits", Json.obj(traits)))

        // Another user on this installation: the platform moves it to them without merging the profiles. A new
        // signature re-registers too: with a required signature an unsigned user id was not accepted.
        if (changed || signed) {
            registerNow(force = false)
            // The app settings depend on the profile: the platform gets the identify first.
            flushNow()
            fetchConfigNow(force = true)
        }
    }

    fun alias(userId: String, userHash: String?) = run {
        val previous = storage.userId ?: storage.anonymousId
        storage.userId = userId
        storage.userHash = userHash
        enqueue("alias", JSONObject().put("previousId", previous))
        registerNow(force = false)
    }

    fun reset() = run {
        flushNow()
        storage.userId = null
        storage.userHash = null
        storage.newAnonymousId()
        registerNow(force = false)
        feed.reset()
        fetchConfigNow(force = true)
    }

    fun flush() = run { flushNow() }

    fun setPushToken(token: String?, provider: String) = run {
        storage.pushToken = token
        storage.pushProvider = if (token == null) null else provider
        registerNow(force = false)
    }

    /** Notification permission may have changed: the user answered the prompt or changed it in the settings. */
    fun refreshRegistration() = run { registerNow(force = false) }

    /** Lifecycle hooks: launch and update detection, foreground and background. */
    fun appLaunched() = run {
        val version = device.appVersion
        val build = device.appBuild
        val previous = storage.appVersion

        if (config.trackLifecycle) {
            when {
                previous == null -> enqueue("track", JSONObject().put("event", "app_installed").put("properties", JSONObject().put("version", version).put("build", build)))
                previous != version || storage.appBuild != build -> enqueue("track", JSONObject().put("event", "app_updated").put("properties", JSONObject()
                    .put("version", version).put("build", build).put("previous_version", previous).put("previous_build", storage.appBuild ?: "")))
            }
        }

        storage.appVersion = version
        storage.appBuild = build
    }

    fun appForeground(fromBackground: Boolean) = run {
        if (config.trackLifecycle) {
            enqueue("track", JSONObject().put("event", "app_opened").put("properties", JSONObject().put("from_background", fromBackground).put("version", device.appVersion)))
        }

        if (fromBackground) {
            registerNow(force = false)
        }

        fetchInAppNow()
        presentInApp(null)
        feed.refreshIfWatched(force = false)
        fetchConfigNow(force = false)
    }

    fun appBackground() = run {
        if (config.trackLifecycle) {
            enqueue("track", JSONObject().put("event", "app_backgrounded").put("properties", JSONObject()))
        }

        flushNow()
    }

    /**
     * A push notification was tapped and opened the app: report the open (and the click on its link) and open the link.
     * Returns true when the intent came from a OneTrace notification.
     */
    fun handlePushIntent(intent: Intent?, launcher: Context): Boolean {
        val token = intent?.getStringExtra(PUSH_TOKEN) ?: return false
        val url = intent.getStringExtra(PUSH_URL)
        val action = intent.getStringExtra(PUSH_ACTION)
        intent.removeExtra(PUSH_TOKEN)
        intent.removeExtra(PUSH_ACTION)
        // A notification action or a carousel card does not close the notification by itself.
        if (intent.hasExtra(RichNotifications.EXTRA_NOTIFICATION_ID)) {
            NotificationManagerCompat.from(context).cancel(intent.getIntExtra(RichNotifications.EXTRA_NOTIFICATION_ID, 0))
            intent.removeExtra(RichNotifications.EXTRA_NOTIFICATION_ID)
        }

        opened(token, url, launcher, action)

        return true
    }

    /** A tap on a OneTrace notification shown by the app's own code; false when the data is not from OneTrace. */
    fun notificationOpened(data: Map<String, String>, launcher: Context, actionId: String? = null): Boolean {
        val token = data[PUSH_TOKEN] ?: return false

        if (actionId == null) {
            opened(token, data[PUSH_URL], launcher, null)
            return true
        }

        val button = PushButton.list(data[RichNotifications.BUTTONS]).firstOrNull { it.id == actionId }

        when (button?.action) {
            PushButton.DISMISS -> Unit
            PushButton.SNOOZE -> {
                RichNotifications.snooze(context, data, button)
                actionTapped(token, actionId)
            }
            PushButton.OPEN_APP -> opened(token, null, launcher, actionId)
            PushButton.OPEN_URL -> opened(token, button.url, launcher, actionId)
            else -> opened(token, data[PUSH_URL], launcher, actionId)
        }

        return true
    }

    /** A button that neither opens the app nor a link («Remind me later»): the tap is reported once. */
    fun actionTapped(token: String, actionId: String) {
        if (remember(token, actionId)) {
            run { pushEventNow(token, "opened", null, actionId) }
        }
    }

    /** Reports the tap once: the launch intent and a wrapper (firebase_messaging and the like) may both pass it on. */
    private fun opened(token: String, link: String?, launcher: Context, action: String?) {
        if (!remember(token, action)) {
            return
        }

        val url = link?.takeIf { it.isNotBlank() }
        run { pushEventNow(token, "opened", url, action) }

        if (config.openPushLinks && url != null && !Links.open(launcher, url)) {
            log("No app can open $url")
        }
    }

    /** The last 50 taps, a button counted apart from the notification itself; false for a repeated one. */
    private fun remember(token: String, action: String?): Boolean = synchronized(openedTokens) {
        if (!openedTokens.add(if (action == null) token else "$token#$action")) {
            return false
        }

        if (openedTokens.size > 50) {
            openedTokens.remove(openedTokens.first())
        }

        true
    }

    /** A Live Update was shown (`started`) or swiped away (`dismissed`) on this installation. */
    fun liveUpdateEvent(type: String, key: String, event: String) = run {
        val body = JSONObject().put("installationId", storage.installationId).put("type", type).put("key", key).put("event", event)
        val result = transport.post("/live-activities", body)
        log("Live Update $event reported: HTTP ${result.status}")
    }

    /** The app received a OneTrace notification (FCM onMessageReceived): the message is delivered. */
    fun pushReceived(data: Map<String, String>) {
        val token = data[PUSH_TOKEN] ?: return
        run { pushEventNow(token, "received", null) }
    }

    /** A GET for the other OneTrace modules, on their thread; with the ETag of what they fetched before. */
    fun apiGet(path: String, etag: String?): HttpResult = transport.get(path, etag?.let { mapOf("If-None-Match" to it) } ?: emptyMap())

    /** Asks the platform for in-app messages now. */
    fun fetchInApp() = run {
        fetchInAppNow()
        presentInApp(null)
    }

    fun reportInApp(token: String, event: String, url: String?) = run {
        val body = JSONObject().put("token", token).put("event", event)
        url?.let { body.put("url", it) }
        val result = transport.post("/in-app/events", body)
        log("In-app $event reported: HTTP ${result.status}")
    }

    val anonymousId: String get() = storage.anonymousId

    val userId: String? get() = storage.userId

    val installationId: String get() = storage.installationId

    private fun run(block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (e: Exception) {
                log("Unexpected error: ${e.message}")
            }
        }
    }

    private fun enqueue(type: String, fields: JSONObject, timestamp: Long? = null) {
        fields.put("type", type)
            .put("messageId", UUID.randomUUID().toString())
            .put("anonymousId", storage.anonymousId)
            .put("timestamp", Json.iso(timestamp ?: clock()))
            .put("context", device.build())
        storage.userId?.let { userId ->
            fields.put("userId", userId)
            storage.userHash?.let { fields.put("userHash", it) }
        }
        queue.add(fields)
        log("Queued $type ${fields.optString("event", fields.optString("name"))}")

        if (queue.size() >= config.flushAt) {
            flushNow()
        }
    }

    private fun flushNow() {
        while (queue.size() > 0) {
            val batch = queue.peek(BATCH_SIZE)
            val result = transport.post("/batch", JSONObject().put("batch", JSONArray(batch)))

            when {
                result.successful -> {
                    queue.remove(batch.size)
                    log("Sent ${batch.size} events")
                    // The events may have started a journey with an in-app message or a feed card: pick it up.
                    if (queue.size() == 0) {
                        fetchInAppNow()
                        presentInApp(null)
                        feed.refreshIfWatched(force = false)
                    }
                }
                // A wrong key: the events wait until a new app version fixes it.
                result.status == 401 || result.status == 403 -> {
                    log("The write key was rejected (HTTP ${result.status})")
                    return
                }
                result.retryable -> {
                    log("Sending later: HTTP ${result.status} ${result.body.take(200)}")
                    return
                }
                // The platform would reject this batch again: drop it so the queue keeps moving.
                else -> {
                    queue.remove(batch.size)
                    log("Dropped ${batch.size} events: HTTP ${result.status} ${result.body.take(500)}")
                }
            }
        }
    }

    private fun fetchInAppNow() {
        val query = "anonymousId=${Uri.encode(storage.anonymousId)}&appId=${Uri.encode(device.packageName)}&platform=android"
        val result = transport.get("/in-app/messages?$query")

        if (!result.successful) {
            log("In-app messages: HTTP ${result.status}")
            return
        }

        val data = runCatching { JSONObject(result.body).optJSONArray("data") }.getOrNull() ?: return

        for (i in 0 until data.length()) {
            val message = data.optJSONObject(i) ?: continue
            val token = message.optString("token")

            if (token.isNotEmpty() && seen.add(token)) {
                inApp.add(message)
            }
        }

        while (seen.size > 200) {
            seen.remove(seen.first())
        }
    }

    /** Shows the next message for this moment: without a trigger after a fetch, or the one triggered by [event]. */
    private fun presentInApp(event: String?) {
        val show = presenter ?: return

        if (inAppPaused) {
            return
        }

        val matching = inApp.filter { (it.optString("trigger_event").takeIf { t -> t.isNotEmpty() && t != "null" }) == event }
        // After a fetch every message without a trigger is shown, one after another; an event shows one message.
        val shown = if (event == null) matching else matching.take(1)
        inApp.removeAll(shown)
        shown.forEach(show)
    }

    /** SDK thread: fetches the app settings and tells the listeners what changed; false when they could not be fetched. */
    private fun fetchConfigNow(force: Boolean): Boolean {
        val fetched = configStore.fetch(force, storage.anonymousId, device.packageName, device.appVersion)
        val changed = fetched.changed ?: return false.also { log("App settings were not fetched") }
        remoteConfig.changed(changed)

        return true
    }

    private fun registerNow(force: Boolean) {
        val payload = registration()
        val fingerprint = payload.toString()

        if (!force && fingerprint == storage.registration) {
            return
        }

        val result = transport.post("/devices", payload)

        if (result.successful) {
            storage.registration = fingerprint
            log("Registered the installation: ${result.body}")
        } else {
            storage.registration = null
            log("Device registration failed: HTTP ${result.status} ${result.body.take(300)}")
        }
    }

    private fun registration(): JSONObject {
        val (sdkName, sdkVersion) = device.sdk
        val token = storage.pushToken

        return JSONObject()
            .put("installationId", storage.installationId)
            .put("anonymousId", storage.anonymousId)
            .put("userId", storage.userId ?: JSONObject.NULL)
            .put("userHash", storage.userId?.let { storage.userHash })
            .put("app", JSONObject().put("id", device.packageName).put("version", device.appVersion).put("build", device.appBuild))
            .put("os", JSONObject().put("name", "android").put("version", android.os.Build.VERSION.RELEASE ?: ""))
            .put("device", JSONObject().put("manufacturer", android.os.Build.MANUFACTURER).put("model", android.os.Build.MODEL))
            .put("locale", device.locale)
            .put("timezone", device.timezone)
            .put("push", (if (token == null) JSONObject().put("enabled", false) else JSONObject().put("provider", storage.pushProvider ?: "fcm").put("token", token).put("enabled", notificationsEnabled()))
                .put("capabilities", JSONArray(if (richPush) PUSH_CAPABILITIES else emptyList())))
            .put("location", JSONObject().put("permission", locationPermission()))
            .put("sdk", JSONObject().put("name", sdkName).put("version", sdkVersion))
    }

    private fun pushEventNow(token: String, event: String, url: String?, action: String? = null) {
        val body = JSONObject().put("token", token).put("event", event)
        url?.let { body.put("url", it) }
        action?.let { body.put("action", it) }
        val result = transport.post("/push/events", body)
        log("Push $event reported: HTTP ${result.status}")
    }

    private fun log(message: String) {
        if (config.debug) {
            Log.d(TAG, message)
        }
    }

    companion object {
        const val TAG = "OneTrace"
        const val PUSH_TOKEN = "cdp_token"
        const val PUSH_URL = "cdp_url"
        const val PUSH_IMAGE = "cdp_image"
        const val PUSH_KIND = "cdp_kind"
        const val PUSH_LIVE = "cdp_live"

        /** The button of a notification action in the launch intent (not part of the push data). */
        const val PUSH_ACTION = "cdp_action"

        /** What this SDK draws itself once the app passes push data to it. */
        val PUSH_CAPABILITIES = listOf("rich", "live_updates", "silent")
        private const val BATCH_SIZE = 100
    }
}
