package pro.onetrace.sdk

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import pro.onetrace.sdk.internal.Client
import pro.onetrace.sdk.internal.DeviceContext
import pro.onetrace.sdk.internal.EventQueue
import pro.onetrace.sdk.internal.HttpResult
import pro.onetrace.sdk.internal.Storage
import pro.onetrace.sdk.internal.Transport
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

@RunWith(RobolectricTestRunner::class)
class ClientTest {
    private class Request(val path: String, val body: JSONObject)

    private class FakeTransport : Transport {
        val requests = mutableListOf<Request>()
        val gets = mutableListOf<String>()
        var status = 202
        var inApp = "{\"data\": []}"

        override fun get(path: String): HttpResult {
            gets.add(path)
            return HttpResult(200, inApp)
        }

        override fun post(path: String, body: JSONObject): HttpResult {
            requests.add(Request(path, JSONObject(body.toString())))
            return HttpResult(if (path == "/batch") status else 200, "{}")
        }

        fun batches() = requests.filter { it.path == "/batch" }.map { it.body.getJSONArray("batch") }

        fun events() = batches().flatMap { batch -> (0 until batch.length()).map { batch.getJSONObject(it) } }

        fun registrations() = requests.filter { it.path == "/devices" }.map { it.body }
    }

    private lateinit var app: Application
    private lateinit var executor: ScheduledExecutorService
    private lateinit var transport: FakeTransport
    private lateinit var storage: Storage
    private lateinit var queueFile: File
    private var notifications = true

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        executor = Executors.newSingleThreadScheduledExecutor()
        transport = FakeTransport()
        storage = Storage(app)
        queueFile = File(app.filesDir, "queue-test.jsonl").apply { delete() }
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    private fun client(flushAt: Int = 3, trackLifecycle: Boolean = false, openLinks: Boolean = true) = Client(
        app,
        OneTraceConfig("cdp_wk_test", "https://crm.example.com", flushAt = flushAt, flushIntervalSeconds = 3600, trackLifecycle = trackLifecycle, openPushLinks = openLinks),
        storage,
        EventQueue(queueFile, 50),
        transport,
        executor,
        DeviceContext(app, storage.installationId),
        clock = { 1_800_000_000_000 },
        notificationsEnabled = { notifications },
    )

    /** Waits until the SDK thread has processed everything submitted so far. */
    private fun drain() = executor.submit {}.get()

    @Test
    fun sendsEventsInBatchesWithTheAppContext() {
        val client = client()
        client.track("order_completed", mapOf("amount" to 1250.5, "items" to listOf("SKU-1")))
        client.screen("Cart", emptyMap())
        drain()
        assertTrue(transport.batches().isEmpty())

        client.track("checkout_started", emptyMap())
        drain()

        val events = transport.events()
        assertEquals(listOf("track", "screen", "track"), events.map { it.getString("type") })
        assertEquals(1250.5, events[0].getJSONObject("properties").getDouble("amount"), 0.0)
        assertEquals("Cart", events[1].getString("name"))
        assertEquals("2027-01-15T08:00:00.000Z", events[0].getString("timestamp"))
        assertEquals(storage.anonymousId, events[0].getString("anonymousId"))

        val context = events[0].getJSONObject("context")
        assertEquals(storage.installationId, context.getJSONObject("device").getString("id"))
        assertEquals("android", context.getJSONObject("os").getString("name"))
        assertEquals(app.packageName, context.getJSONObject("app").getString("namespace"))
        assertEquals("onetrace-android", context.getJSONObject("library").getString("name"))
        assertEquals(0, EventQueue(queueFile, 50).size())
    }

    @Test
    fun keepsEventsOnDiskWhileTheNetworkFailsAndDropsBatchesThePlatformRejects() {
        transport.status = 0
        val client = client(flushAt = 1)
        client.track("viewed", emptyMap())
        drain()
        assertEquals(1, EventQueue(queueFile, 50).size())

        transport.status = 422
        client.flush()
        drain()
        assertEquals(0, EventQueue(queueFile, 50).size())
    }

    @Test
    fun registersTheInstallationWithThePushTokenOnlyWhenSomethingChanged() {
        val client = client()
        client.start()
        client.setPushToken("fcm-token", "fcm")
        client.setPushToken("fcm-token", "fcm")
        drain()

        val registrations = transport.registrations()
        assertEquals(2, registrations.size)
        assertFalse(registrations[0].getJSONObject("push").getBoolean("enabled"))

        val push = registrations[1].getJSONObject("push")
        assertEquals("fcm", push.getString("provider"))
        assertEquals("fcm-token", push.getString("token"))
        assertTrue(push.getBoolean("enabled"))
        assertEquals(storage.installationId, registrations[1].getString("installationId"))
        assertEquals(app.packageName, registrations[1].getJSONObject("app").getString("id"))
        assertEquals("android", registrations[1].getJSONObject("sdk").getString("name"))

        notifications = false
        client.refreshRegistration()
        drain()
        assertFalse(transport.registrations().last().getJSONObject("push").getBoolean("enabled"))
    }

    @Test
    fun movesTheInstallationToTheNextUserOnIdentifyAndReset() {
        val client = client(flushAt = 1)
        val anonymous = storage.anonymousId
        client.identify("u-42", mapOf("email" to "anna@example.com"), null)
        drain()

        val identify = transport.events().single()
        assertEquals("u-42", identify.getString("userId"))
        assertEquals("anna@example.com", identify.getJSONObject("traits").getString("email"))
        assertEquals("u-42", transport.registrations().last().getString("userId"))

        client.reset()
        client.track("viewed", emptyMap())
        drain()

        assertNull(storage.userId)
        assertNotEquals(anonymous, storage.anonymousId)
        assertTrue(transport.registrations().last().isNull("userId"))
        assertEquals(storage.anonymousId, transport.registrations().last().getString("anonymousId"))
        assertFalse(transport.events().last().has("userId"))
    }

    @Test
    fun sendsTheUserIdSignatureWithTheUserIdUntilReset() {
        val client = client(flushAt = 1)
        client.identify("u-42", emptyMap(), "a1b2")
        client.identify("u-42", mapOf("plan" to "pro"), null)
        client.track("viewed", emptyMap())
        drain()

        assertEquals(listOf("a1b2", "a1b2", "a1b2"), transport.events().map { it.getString("userHash") })
        assertEquals("a1b2", transport.registrations().last().getString("userHash"))

        // A new signature of the same user re-registers the installation with it.
        client.identify("u-42", emptyMap(), "c3d4")
        drain()
        assertEquals("c3d4", transport.registrations().last().getString("userHash"))

        // Another user without a signature does not inherit the previous one.
        client.identify("u-7", emptyMap(), null)
        drain()
        assertFalse(transport.events().last().has("userHash"))
        assertFalse(transport.registrations().last().has("userHash"))

        client.alias("u-8", "e5f6")
        drain()
        assertEquals("e5f6", transport.events().last().getString("userHash"))
        assertEquals("e5f6", transport.registrations().last().getString("userHash"))

        client.reset()
        client.track("viewed", emptyMap())
        drain()
        assertNull(storage.userHash)
        assertFalse(transport.events().last().has("userHash"))
        assertFalse(transport.registrations().last().has("userHash"))
    }

    @Test
    fun tracksInstallsAndUpdates() {
        storage.appVersion = null
        client(flushAt = 1, trackLifecycle = true).appLaunched()
        drain()
        storage.appVersion = "0.9"
        client(flushAt = 1, trackLifecycle = true).appLaunched()
        drain()

        val events = transport.events().map { it.getString("event") }
        assertEquals(listOf("app_installed", "app_updated"), events)
        assertEquals("0.9", transport.events()[1].getJSONObject("properties").getString("previous_version"))
    }

    @Test
    fun reportsATappedNotificationAndOpensItsLink() {
        val intent = Intent(Intent.ACTION_MAIN).putExtra("cdp_token", "abc.def").putExtra("cdp_url", "shopapp://sale")

        assertTrue(client().handlePushIntent(intent, app))
        drain()

        val event = transport.requests.single { it.path == "/push/events" }.body
        assertEquals("opened", event.getString("event"))
        assertEquals("shopapp://sale", event.getString("url"))
        assertFalse(intent.hasExtra("cdp_token"))
        assertEquals("shopapp://sale", shadowOf(app).nextStartedActivity.data.toString())
        assertFalse(client().handlePushIntent(Intent(Intent.ACTION_MAIN), app))
    }

    @Test
    fun reportsATapOnceWhenTheIntentAndTheWrapperBothPassItOn() {
        val client = client(openLinks = false)
        val intent = Intent(Intent.ACTION_MAIN).putExtra("cdp_token", "abc.def")

        assertTrue(client.handlePushIntent(intent, app))
        assertTrue(client.notificationOpened(mapOf("cdp_token" to "abc.def"), app))
        assertTrue(client.notificationOpened(mapOf("cdp_token" to "ghi.jkl", "cdp_url" to ""), app))
        assertFalse(client.notificationOpened(mapOf("google.message_id" to "1"), app))
        drain()

        val opened = transport.requests.filter { it.path == "/push/events" }.map { it.body }
        assertEquals(listOf("abc.def", "ghi.jkl"), opened.map { it.getString("token") })
        assertFalse(opened[1].has("url"))
    }

    @Test
    fun keepsTheNewestEventsWhenTheQueueIsFull() {
        val queue = EventQueue(queueFile, 3)
        (1..5).forEach { queue.add(JSONObject().put("n", it)) }

        assertEquals(listOf(3, 4, 5), EventQueue(queueFile, 3).peek(10).map { it.getInt("n") })
    }

    @Test
    fun showsInAppMessagesAfterAFetchOrAfterTheirTriggerEvent() {
        val shown = mutableListOf<String>()
        val client = client(flushAt = 100)
        client.presenter = { shown.add(it.getString("title")) }
        transport.inApp = JSONObject().put("data", org.json.JSONArray()
            .put(JSONObject().put("token", "t1").put("title", "Welcome").put("trigger_event", JSONObject.NULL))
            .put(JSONObject().put("token", "t2").put("title", "Cart reminder").put("trigger_event", "add_to_cart"))).toString()

        client.fetchInApp()
        client.fetchInApp()
        drain()
        assertEquals(listOf("Welcome"), shown)
        assertTrue(transport.gets.first().startsWith("/in-app/messages?anonymousId=${storage.anonymousId}&appId=${app.packageName}&platform=android"))

        client.track("product_viewed", emptyMap())
        client.track("add_to_cart", emptyMap())
        client.track("add_to_cart", emptyMap())
        drain()
        assertEquals(listOf("Welcome", "Cart reminder"), shown)

        client.reportInApp("t2", "clicked", "shopapp://cart")
        drain()
        val event = transport.requests.single { it.path == "/in-app/events" }.body
        assertEquals("clicked", event.getString("event"))
        assertEquals("shopapp://cart", event.getString("url"))
    }

    @Test
    fun presentsAnInAppMessageOverTheActivityAndReportsTheButton() {
        val reports = mutableListOf<String>()
        val presenter = pro.onetrace.sdk.internal.InAppPresenter(openLinks = true) { _, event, url -> reports.add("$event ${url ?: ""}".trim()) }
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        presenter.resumed(activity)
        presenter.present(JSONObject().put("token", "t1").put("layout", "modal").put("title", "Sale").put("body", "-20%")
            .put("button_text", "Shop").put("button_url", "shopapp://sale").put("background_color", "#FFFFFF"))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        val dialog = presenter.current!!
        assertTrue(dialog.isShowing)
        assertEquals(listOf("shown"), reports)

        val button = findButton(dialog.window!!.decorView, "Shop")!!
        button.performClick()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        assertEquals(listOf("shown", "clicked shopapp://sale"), reports)
        assertFalse(dialog.isShowing)
        assertEquals("shopapp://sale", shadowOf(activity).nextStartedActivity.data.toString())
    }

    @Test
    fun showsABannerOverTheActivityContentAndTheNextMessageAfterIt() {
        val reports = mutableListOf<String>()
        val presenter = pro.onetrace.sdk.internal.InAppPresenter(openLinks = false) { token, event, _ -> reports.add("$token $event") }
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        presenter.resumed(activity)
        presenter.present(JSONObject().put("token", "b1").put("layout", "banner").put("position", "top").put("title", "Free delivery").put("close_text", "Close"))
        presenter.present(JSONObject().put("token", "m1").put("layout", "modal").put("title", "Sale"))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        val banner = presenter.currentBanner!!
        assertTrue(banner.parent === activity.window.decorView)
        assertEquals(null, presenter.current)

        findText(banner, "Close")!!.performClick()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        assertEquals(null, banner.parent)
        assertTrue(presenter.current!!.isShowing)
        assertEquals(listOf("b1 shown", "b1 dismissed", "m1 shown"), reports)
    }

    private fun findText(view: android.view.View, text: String): android.view.View? = when (view) {
        is android.widget.TextView -> view.takeIf { it.text.toString() == text }
        is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findText(view.getChildAt(it), text) }
        else -> null
    }

    private fun findButton(view: android.view.View, text: String): android.widget.Button? = when (view) {
        is android.widget.Button -> view.takeIf { it.text.toString() == text }
        is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findButton(view.getChildAt(it), text) }
        else -> null
    }
}
