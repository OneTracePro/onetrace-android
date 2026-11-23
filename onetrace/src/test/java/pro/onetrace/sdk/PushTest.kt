package pro.onetrace.sdk

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import pro.onetrace.sdk.internal.Client
import pro.onetrace.sdk.internal.DeviceContext
import pro.onetrace.sdk.internal.EventQueue
import pro.onetrace.sdk.internal.HttpResult
import pro.onetrace.sdk.internal.PushActionReceiver
import pro.onetrace.sdk.internal.Storage
import pro.onetrace.sdk.internal.Transport
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

@RunWith(RobolectricTestRunner::class)
class PushTest {
    private class Request(val path: String, val body: JSONObject)

    private class FakeTransport : Transport {
        val requests = mutableListOf<Request>()

        override fun get(path: String): HttpResult = HttpResult(200, "{\"data\": []}")

        override fun post(path: String, body: JSONObject): HttpResult {
            synchronized(requests) { requests.add(Request(path, JSONObject(body.toString()))) }
            return HttpResult(if (path == "/batch") 202 else 200, "{}")
        }

        fun sent(path: String) = synchronized(requests) { requests.filter { it.path == path }.map { it.body } }
    }

    private lateinit var app: Application
    private lateinit var executor: ScheduledExecutorService
    private lateinit var transport: FakeTransport
    private lateinit var storage: Storage
    private lateinit var images: MockWebServer
    private val background = Executors.newSingleThreadExecutor()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        executor = Executors.newSingleThreadScheduledExecutor()
        transport = FakeTransport()
        storage = Storage(app)
        // The notification opens the launcher activity of the app.
        val launcher = ComponentName(app, Activity::class.java)
        shadowOf(app.packageManager).addActivityIfNotPresent(launcher)
        shadowOf(app.packageManager).addIntentFilterForActivity(launcher, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        images = MockWebServer()
        images.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/missing.png" -> MockResponse().setResponseCode(404)
                else -> MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png()))
            }
        }
        images.start()
    }

    @After
    fun tearDown() {
        OneTrace.shutdownForTests()
        executor.shutdownNow()
        background.shutdownNow()
        images.shutdown()
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun client(richPush: Boolean = true, locationPermission: () -> String = { "none" }) = Client(
        app,
        OneTraceConfig("cdp_wk_test", "https://crm.example.com", flushAt = 1, flushIntervalSeconds = 3600, trackLifecycle = false, openPushLinks = false, richPush = richPush),
        storage,
        EventQueue(File(app.filesDir, "queue-push.jsonl").apply { delete() }, 50),
        transport,
        executor,
        DeviceContext(app, storage.installationId),
        notificationsEnabled = { true },
        locationPermission = locationPermission,
    ).also { OneTrace.setClientForTests(it) }

    private fun drain() = executor.submit {}.get()

    /** The push service calls the SDK on a background thread. */
    private fun receive(data: Map<String, String>): Boolean = background.submit<Boolean> { OneTrace.handlePushData(app, data) }.get().also { drain() }

    private fun notifications(): List<Notification> = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications

    private fun deliver(pending: PendingIntent) {
        PushActionReceiver().onReceive(app, shadowOf(pending).savedIntent)
        drain()
        ShadowLooper.idleMainLooper()
    }

    private fun image(name: String) = images.url("/$name").toString()

    @Test
    fun registersTheInstallationWithItsPushCapabilitiesAndLocationPermission() {
        var permission = "none"
        val client = client(richPush = true) { permission }
        client.start()
        drain()

        val first = transport.sent("/devices").last()
        assertEquals(listOf("rich", "live_updates", "silent"), first.getJSONObject("push").getJSONArray("capabilities").let { list -> (0 until list.length()).map { list.getString(it) } })
        assertEquals("none", first.getJSONObject("location").getString("permission"))

        permission = "always"
        client.refreshRegistration()
        drain()
        assertEquals("always", transport.sent("/devices").last().getJSONObject("location").getString("permission"))

        OneTrace.shutdownForTests()
        transport.requests.clear()
        val plain = client(richPush = false)
        plain.start()
        drain()
        assertEquals(0, transport.sent("/devices").last().getJSONObject("push").getJSONArray("capabilities").length())
    }

    @Test
    fun reportsTheLocationPermissionFromTheGrantedPermissions() {
        val client = Client(
            app,
            OneTraceConfig("cdp_wk_test", "https://crm.example.com", flushIntervalSeconds = 3600, trackLifecycle = false),
            storage,
            EventQueue(File(app.filesDir, "queue-push.jsonl").apply { delete() }, 50),
            transport,
            executor,
            DeviceContext(app, storage.installationId),
        )
        client.start()
        drain()
        assertEquals("none", transport.sent("/devices").last().getJSONObject("location").getString("permission"))

        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        client.refreshRegistration()
        drain()
        assertEquals("foreground", transport.sent("/devices").last().getJSONObject("location").getString("permission"))

        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        client.refreshRegistration()
        drain()
        assertEquals("always", transport.sent("/devices").last().getJSONObject("location").getString("permission"))
    }

    @Test
    fun aSilentCheckShowsNothingAndReportsNothing() {
        client()

        assertTrue(receive(mapOf("cdp_kind" to "ping")))
        assertTrue(notifications().isEmpty())
        assertTrue(transport.requests.isEmpty())
        assertFalse(receive(mapOf("google.message_id" to "1")))
    }

    @Test
    fun showsACarouselOfTheCardsWhoseImagesLoadedAndPagesThroughThem() {
        client()
        val carousel = JSONArray()
            .put(JSONObject().put("image", image("a.png")).put("title", "Sneakers").put("body", "4 990 ₽").put("url", "shopapp://product/1"))
            .put(JSONObject().put("image", image("missing.png")).put("title", "Boots").put("body", "7 990 ₽").put("url", "shopapp://product/2"))
            .put(JSONObject().put("image", image("c.png")).put("title", "Sandals").put("body", "2 990 ₽").put("url", "shopapp://product/3"))

        assertTrue(receive(mapOf("cdp_token" to "t-carousel", "cdp_title" to "New arrivals", "cdp_body" to "Picked for you", "cdp_carousel" to carousel.toString())))

        val shown = notifications().single()
        assertEquals("New arrivals", shown.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(2, shown.extras.getInt("pro.onetrace.carousel_size"))
        assertEquals(0, shown.extras.getInt("pro.onetrace.carousel_index"))
        assertEquals("received", transport.sent("/push/events").single().getString("event"))

        val view = shown.bigContentView.apply(app, FrameLayout(app))
        assertEquals("Sneakers", view.findViewById<android.widget.TextView>(R.id.onetrace_carousel_title).text.toString())
        assertEquals("1 / 2", view.findViewById<android.widget.TextView>(R.id.onetrace_carousel_counter).text.toString())

        // «›» shows the next card that has an image: the third one, the second is skipped.
        view.findViewById<android.view.View>(R.id.onetrace_carousel_next).performClick()
        val next = shadowOf(app).broadcastIntents.last()
        PushActionReceiver().onReceive(app, next)
        ShadowLooper.idleMainLooper()

        val paged = notifications().single()
        assertEquals(1, paged.extras.getInt("pro.onetrace.carousel_index"))
        val pagedView = paged.bigContentView.apply(app, FrameLayout(app))
        assertEquals("Sandals", pagedView.findViewById<android.widget.TextView>(R.id.onetrace_carousel_title).text.toString())

        // A tap on the card opens the app with the card link: the open is reported with it.
        pagedView.findViewById<android.view.View>(R.id.onetrace_carousel_image).performClick()
        val opened = shadowOf(app).nextStartedActivity
        assertEquals("shopapp://product/3", opened.getStringExtra("cdp_url"))
        OneTrace.handleIntent(app, opened)
        drain()
        val open = transport.sent("/push/events").last()
        assertEquals("opened", open.getString("event"))
        assertEquals("shopapp://product/3", open.getString("url"))
    }

    @Test
    fun aCarouselWithOneLoadedImageBecomesAPlainNotificationWithThatImage() {
        client()
        val carousel = JSONArray()
            .put(JSONObject().put("image", image("missing.png")).put("title", "Boots").put("url", "shopapp://product/2"))
            .put(JSONObject().put("image", image("c.png")).put("title", "Sandals").put("url", "shopapp://product/3"))

        receive(mapOf("cdp_token" to "t-one", "cdp_title" to "New arrivals", "cdp_body" to "Picked for you", "cdp_carousel" to carousel.toString()))

        val shown = notifications().single()
        assertFalse(shown.extras.containsKey("pro.onetrace.carousel_size"))
        assertEquals("android.app.Notification\$BigPictureStyle", shown.extras.getString(Notification.EXTRA_TEMPLATE))
        assertNotNull(shown.extras.get(Notification.EXTRA_PICTURE))
    }

    @Test
    fun showsTheCountdownOfATimerAndRemovesTheNotificationAtTheEnd() {
        client()
        val end = System.currentTimeMillis() + 2 * 3600_000L
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(end))
        val endSeconds = end / 1000 * 1000

        receive(mapOf("cdp_token" to "t-timer", "cdp_title" to "Sale ends soon", "cdp_body" to "-30%", "cdp_timer_end" to iso, "cdp_timer_remove" to "1"))

        val shown = notifications().single()
        assertEquals(endSeconds, shown.`when`)
        assertTrue(shown.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(shown.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        val timeout = shown.timeoutAfter
        assertTrue("timeoutAfter $timeout", timeout in (endSeconds - System.currentTimeMillis() - 60_000)..(endSeconds - System.currentTimeMillis() + 1000))

        receive(mapOf("cdp_token" to "t-timer-2", "cdp_title" to "Sale ends soon", "cdp_timer_end" to iso, "cdp_timer_remove" to "0"))
        assertEquals(0L, notifications().last().timeoutAfter)
    }

    @Test
    fun buttonsBecomeNotificationActionsAndATapIsReportedOnceWithTheButton() {
        client()
        val buttons = JSONArray()
            .put(JSONObject().put("id", "b1").put("title", "Buy").put("action", "open_url").put("url", "shopapp://cart"))
            .put(JSONObject().put("id", "b2").put("title", "Open").put("action", "open_app"))
            .put(JSONObject().put("id", "b3").put("title", "Close").put("action", "dismiss"))

        receive(mapOf("cdp_token" to "t-buttons", "cdp_title" to "Your cart", "cdp_body" to "2 items", "cdp_url" to "shopapp://home", "cdp_buttons" to buttons.toString()))

        val shown = notifications().single()
        assertEquals(listOf("Buy", "Open", "Close"), shown.actions.map { it.title.toString() })

        val buy = shadowOf(shown.actions[0].actionIntent).savedIntent
        assertTrue(OneTrace.handleIntent(app, buy))
        assertTrue(OneTrace.notificationOpened(app, mapOf("cdp_token" to "t-buttons", "cdp_buttons" to buttons.toString()), "b1"))
        drain()
        val opened = transport.sent("/push/events").filter { it.getString("event") == "opened" }
        assertEquals(1, opened.size)
        assertEquals("b1", opened[0].getString("action"))
        assertEquals("shopapp://cart", opened[0].getString("url"))
        // The action button does not close the notification by itself: the SDK does.
        assertTrue(notifications().isEmpty())

        val openApp = shadowOf(shown.actions[1].actionIntent).savedIntent
        OneTrace.handleIntent(app, openApp)
        drain()
        val second = transport.sent("/push/events").last()
        assertEquals("b2", second.getString("action"))
        assertFalse(second.has("url"))

        // «Close» removes the notification and reports nothing.
        receive(mapOf("cdp_token" to "t-buttons-2", "cdp_title" to "Your cart", "cdp_buttons" to buttons.toString()))
        val before = transport.requests.size
        deliver(notifications().single().actions[2].actionIntent)
        assertTrue(notifications().isEmpty())
        assertEquals(before, transport.requests.size)
    }

    @Test
    fun snoozeShowsTheSameNotificationAgainAtTheChosenTimeWithoutThePlatform() {
        client()
        val buttons = JSONArray()
            .put(JSONObject().put("id", "b1").put("title", "In an hour").put("action", "snooze").put("snooze_minutes", "60"))
            .put(JSONObject().put("id", "b2").put("title", "Tomorrow").put("action", "snooze").put("snooze_minutes", "tomorrow_9"))
        val data = mapOf("cdp_token" to "t-snooze", "cdp_title" to "Your cart", "cdp_body" to "2 items", "cdp_buttons" to buttons.toString())
        receive(data)

        val before = System.currentTimeMillis()
        deliver(notifications().single().actions[0].actionIntent)
        assertTrue(notifications().isEmpty())

        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        val alarm = alarms.scheduledAlarms.single()
        assertTrue(alarm.triggerAtMs in (before + 3600_000 - 1000)..(System.currentTimeMillis() + 3600_000))
        val snoozed = transport.sent("/push/events").filter { it.getString("event") == "opened" }.single()
        assertEquals("b1", snoozed.getString("action"))

        // The alarm shows the same notification, the platform sends nothing again.
        val requests = transport.requests.size
        deliver(alarm.operation!!)
        val again = notifications().single()
        assertEquals("Your cart", again.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("2 items", again.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(requests, transport.requests.size)

        // «Tomorrow at 9:00» by the phone clock.
        deliver(again.actions[1].actionIntent)
        val tomorrow = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(tomorrow, alarms.scheduledAlarms.last().triggerAtMs)
    }

    private fun live(event: String, status: String, step: Int, extra: (JSONObject) -> Unit = {}): Map<String, String> {
        val live = JSONObject().put("type", "order").put("key", "1001").put("event", event)
            .put("state", JSONObject().put("title", "Order 1001").put("status", status).put("step", step)
                .put("steps", JSONArray(listOf("Placed", "Packed", "On the way", "Delivered"))).put("data", JSONObject()))
        extra(live)
        return mapOf("cdp_token" to "t-live-$event-$step", "cdp_kind" to "live", "cdp_live" to live.toString())
    }

    @Test
    fun keepsOneLiveUpdatePerKeyAndRemovesItAfterTheEnd() {
        client()
        receive(live("start", "Placed", 1))
        receive(live("update", "Packed", 2))
        receive(live("update", "On the way", 3))

        val shown = notifications().single()
        assertEquals("Order 1001", shown.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("On the way", shown.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(75, shown.extras.getInt(Notification.EXTRA_PROGRESS))
        assertTrue(shown.flags and Notification.FLAG_ONGOING_EVENT != 0)

        val started = transport.sent("/live-activities")
        assertEquals(1, started.size)
        assertEquals("started", started[0].getString("event"))
        assertEquals("order", started[0].getString("type"))
        assertEquals("1001", started[0].getString("key"))
        assertEquals(storage.installationId, started[0].getString("installationId"))
        assertFalse(started[0].has("token"))

        // The end: the final state, removed by the system after «dismiss after».
        receive(live("end", "Delivered", 4) { it.put("dismissAt", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(System.currentTimeMillis() + 300_000))) })
        val ended = notifications().single()
        assertEquals("Delivered", ended.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(0, ended.flags and Notification.FLAG_ONGOING_EVENT)
        assertTrue(ended.timeoutAfter in 240_000..300_000)

        receive(live("end", "Delivered", 4) { it.put("dismissAt", "2020-01-01T00:00:00Z") })
        assertTrue(notifications().isEmpty())
    }

    @Test
    fun anUpdateWithAlertSoundsAgainWithABannerAndOtherUpdatesStayQuiet() {
        client()
        receive(live("start", "Placed", 1))
        receive(live("update", "Packed", 2))
        assertTrue(notifications().single().flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)

        receive(live("update", "On the way", 3) { it.put("alert", true) })
        val alerted = notifications().single()
        assertEquals("On the way", alerted.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(0, alerted.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        // A banner and a sound: a channel of high importance.
        assertEquals(NotificationManager.IMPORTANCE_HIGH, app.getSystemService(NotificationManager::class.java).getNotificationChannel(alerted.channelId).importance)

        receive(live("update", "Nearby", 3))
        assertTrue(notifications().single().flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test
    fun aLiveUpdateIsMarkedOutOfDateFromItsStaleTimeUntilAFreshUpdate() {
        client()
        val subText = { notifications().single().extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty() }
        val staleAt = System.currentTimeMillis() / 1000 + 600
        receive(live("start", "Packed", 2) { it.put("staleAt", staleAt) })
        assertFalse(subText().contains("out of date"))

        // The SDK marks it at that time by itself.
        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        val stale = alarms.scheduledAlarms.single { shadowOf(it.operation).savedIntent.action == PushActionReceiver.LIVE_STALE }
        assertEquals(staleAt * 1000, stale.triggerAtMs)

        // An update that is already late is marked at once.
        receive(live("update", "On the way", 3) { it.put("staleAt", System.currentTimeMillis() / 1000 - 5) })
        assertEquals("On the way", notifications().single().extras.getString(Notification.EXTRA_TEXT))
        assertTrue(subText(), subText().contains("out of date"))

        receive(live("update", "Nearby", 3))
        assertFalse(subText().contains("out of date"))
    }

    @Test
    fun aStaleTimeAfterTheEndOfTheCountdownKeepsItCompleted() {
        client()
        val now = System.currentTimeMillis()
        receive(live("start", "On the way", 3) {
            it.getJSONObject("state").put("endsAt", (now + 500) / 1000.0)
            it.put("staleAt", now / 1000 + 600)
        })

        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        val alarm = { action: String -> alarms.scheduledAlarms.single { shadowOf(it.operation).savedIntent.action == action } }
        val stale = alarm(PushActionReceiver.LIVE_STALE)
        deliver(alarm(PushActionReceiver.LIVE_COMPLETED).operation!!)
        assertEquals("Completed", notifications().single().extras.getString(Notification.EXTRA_TEXT))

        deliver(stale.operation!!)
        val shown = notifications().single()
        assertEquals("Completed", shown.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(0, shown.flags and Notification.FLAG_ONGOING_EVENT)
    }

    @Test
    fun aSwipedLiveUpdateIsReportedAndOnlyStartBringsItBack() {
        client()
        receive(live("start", "Placed", 1))
        deliver(notifications().single().deleteIntent)

        assertEquals("dismissed", transport.sent("/live-activities").last().getString("event"))
        app.getSystemService(NotificationManager::class.java).cancelAll()

        receive(live("update", "Packed", 2))
        assertTrue(notifications().isEmpty())

        receive(live("start", "Packed", 2))
        assertEquals("Packed", notifications().single().extras.getString(Notification.EXTRA_TEXT))
        assertEquals(listOf("started", "dismissed", "started"), transport.sent("/live-activities").map { it.getString("event") })
    }

    @Test
    fun anOldStyleNotificationWithoutRichDataStillShows() {
        client()
        receive(mapOf("cdp_token" to "t-plain", "cdp_title" to "Hello", "cdp_body" to "World", "cdp_image" to image("a.png")))

        val shown = notifications().single()
        assertEquals("Hello", shown.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("android.app.Notification\$BigPictureStyle", shown.extras.getString(Notification.EXTRA_TEMPLATE))
        assertNull(shown.extras.get("pro.onetrace.carousel_size"))
    }
}
