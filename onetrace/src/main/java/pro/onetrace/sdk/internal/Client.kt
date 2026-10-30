package pro.onetrace.sdk.internal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import pro.onetrace.sdk.OneTraceConfig
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
) {
    /** Shows in-app messages; set by [pro.onetrace.sdk.OneTrace] (null in tests that do not need it). */
    @Volatile
    var presenter: ((JSONObject) -> Unit)? = null

    /** In-app messages received but not shown yet (SDK thread only). */
    private val inApp = mutableListOf<JSONObject>()
    private val seen = LinkedHashSet<String>()

    @Volatile
    var inAppPaused = false

    fun start() {
        executor.scheduleWithFixedDelay({ flushNow() }, config.flushIntervalSeconds, config.flushIntervalSeconds, TimeUnit.SECONDS)
        run { registerNow(force = true) }
    }

    fun track(event: String, properties: Map<String, Any?>) = run {
        enqueue("track", JSONObject().put("event", event).put("properties", Json.obj(properties)))
        presentInApp(event)
    }

    fun screen(name: String, properties: Map<String, Any?>) = run {
        enqueue("screen", JSONObject().put("name", name).put("properties", Json.obj(properties)))
    }

    fun identify(userId: String?, traits: Map<String, Any?>) = run {
        val changed = userId != null && userId != storage.userId

        if (userId != null) {
            storage.userId = userId
        }

        enqueue("identify", JSONObject().put("traits", Json.obj(traits)))

        // Another user on this installation: the platform moves it to them without merging the profiles.
        if (changed) {
            registerNow(force = false)
        }
    }

    fun alias(userId: String) = run {
        val previous = storage.userId ?: storage.anonymousId
        storage.userId = userId
        enqueue("alias", JSONObject().put("previousId", previous))
        registerNow(force = false)
    }

    fun reset() = run {
        flushNow()
        storage.userId = null
        storage.newAnonymousId()
        registerNow(force = false)
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
        val url = intent.getStringExtra(PUSH_URL)?.takeIf { it.isNotBlank() }
        intent.removeExtra(PUSH_TOKEN)
        run { pushEventNow(token, "opened", url) }

        if (config.openPushLinks && url != null) {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))

            if (launcher !is android.app.Activity) {
                view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                launcher.startActivity(view)
            } catch (e: ActivityNotFoundException) {
                log("No app can open $url")
            }
        }

        return true
    }

    /** The app received a OneTrace notification (FCM onMessageReceived): the message is delivered. */
    fun pushReceived(data: Map<String, String>) {
        val token = data[PUSH_TOKEN] ?: return
        run { pushEventNow(token, "received", null) }
    }

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

    private fun enqueue(type: String, fields: JSONObject) {
        fields.put("type", type)
            .put("messageId", UUID.randomUUID().toString())
            .put("anonymousId", storage.anonymousId)
            .put("timestamp", Json.iso(clock()))
            .put("context", device.build())
        storage.userId?.let { fields.put("userId", it) }
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
                    // The events may have started a journey with an in-app message: pick it up.
                    if (queue.size() == 0) {
                        fetchInAppNow()
                        presentInApp(null)
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
            .put("app", JSONObject().put("id", device.packageName).put("version", device.appVersion).put("build", device.appBuild))
            .put("os", JSONObject().put("name", "android").put("version", android.os.Build.VERSION.RELEASE ?: ""))
            .put("device", JSONObject().put("manufacturer", android.os.Build.MANUFACTURER).put("model", android.os.Build.MODEL))
            .put("locale", device.locale)
            .put("timezone", device.timezone)
            .put("push", if (token == null) JSONObject().put("enabled", false) else JSONObject().put("provider", storage.pushProvider ?: "fcm").put("token", token).put("enabled", notificationsEnabled()))
            .put("sdk", JSONObject().put("name", sdkName).put("version", sdkVersion))
    }

    private fun pushEventNow(token: String, event: String, url: String?) {
        val body = JSONObject().put("token", token).put("event", event)
        url?.let { body.put("url", it) }
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
        private const val BATCH_SIZE = 100
    }
}
