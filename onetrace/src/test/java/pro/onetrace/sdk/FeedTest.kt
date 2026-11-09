package pro.onetrace.sdk

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import pro.onetrace.sdk.internal.Client
import pro.onetrace.sdk.internal.DeviceContext
import pro.onetrace.sdk.internal.EventQueue
import pro.onetrace.sdk.internal.Feed
import pro.onetrace.sdk.internal.HttpResult
import pro.onetrace.sdk.internal.Storage
import pro.onetrace.sdk.internal.Transport
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

@RunWith(RobolectricTestRunner::class)
class FeedTest {
    private class FakeTransport : Transport {
        val gets = mutableListOf<String>()
        val events = mutableListOf<JSONObject>()
        var cards = JSONArray()
        var status = 200

        override fun get(path: String): HttpResult {
            gets.add(path)
            return if (path.startsWith("/feed?")) HttpResult(status, JSONObject().put("data", cards).put("unread", 0).toString()) else HttpResult(200, "{\"data\": []}")
        }

        override fun post(path: String, body: JSONObject): HttpResult {
            if (path == "/feed/events") {
                events.add(JSONObject(body.toString()))
            }

            return HttpResult(if (path == "/batch") 202 else 200, "{\"recorded\": true}")
        }

        fun reported() = events.map { "${it.getString("token")} ${it.getString("event")}" + (if (it.has("url")) " ${it.getString("url")}" else "") }
    }

    private lateinit var app: Application
    private lateinit var executor: ScheduledExecutorService
    private lateinit var transport: FakeTransport
    private val now = 1_800_000_000_000L // 2027-01-15T08:00:00Z

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        executor = Executors.newSingleThreadScheduledExecutor()
        transport = FakeTransport()
    }

    @After
    fun tearDown() {
        OneTrace.shutdownForTests()
        executor.shutdownNow()
    }

    private fun feed(openLinks: Boolean = true) = Feed(transport, executor, { "anon-1" }, openLinks, {}, clock = { now })

    private fun card(token: String, read: Boolean = false, expires: String = "2027-02-01T00:00:00+00:00") = JSONObject()
        .put("token", token).put("layout", "classic").put("title", "Card $token").put("pinned", false).put("read", read)
        .put("created_at", "2027-01-10T12:00:00+00:00").put("expires_at", expires)

    /** Waits for the SDK thread, then runs what it posted to the main thread. */
    private fun drain() {
        executor.submit {}.get()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun parsesTheThreeLayoutsAndOptionalFields() {
        val classic = FeedCard.parse(JSONObject().put("token", "t1").put("layout", "classic").put("title", "Sale").put("body", "-20% on shoes")
            .put("image_url", "https://cdn.example.com/a.jpg").put("button_text", "Shop").put("button_url", "shopapp://sale").put("pinned", true).put("read", true)
            .put("created_at", "2026-11-09T10:00:00+03:00").put("expires_at", "2026-11-23T07:00:00.250Z"))!!
        assertEquals(FeedCard.Layout.CLASSIC, classic.layout)
        assertEquals("-20% on shoes", classic.body)
        assertEquals("https://cdn.example.com/a.jpg", classic.imageUrl)
        assertEquals("Shop", classic.buttonText)
        assertTrue(classic.pinned)
        assertTrue(classic.read)
        assertFalse(classic.opensOnTap)
        assertEquals(1_794_207_600_000L, classic.createdAt!!.time)
        assertEquals(1_795_417_200_250L, classic.expiresAt!!.time)

        val captioned = FeedCard.parse(JSONObject().put("token", "t2").put("layout", "captioned").put("title", "News").put("body", JSONObject.NULL)
            .put("button_text", "").put("button_url", "https://shop.example.com/news"))!!
        assertEquals(FeedCard.Layout.CAPTIONED, captioned.layout)
        assertNull(captioned.body)
        assertNull(captioned.buttonText)
        assertTrue(captioned.opensOnTap)
        assertFalse(captioned.pinned)
        assertFalse(captioned.read)
        assertNull(captioned.createdAt)

        val banner = FeedCard.parse(JSONObject().put("token", "t3").put("layout", "banner").put("image_url", "javascript:alert(1)").put("button_text", "Go").put("button_url", "javascript:alert(1)"))!!
        assertEquals(FeedCard.Layout.BANNER, banner.layout)
        assertEquals("", banner.title)
        assertNull(banner.imageUrl)
        assertFalse(banner.opensOnTap)

        assertEquals(FeedCard.Layout.CLASSIC, FeedCard.parse(JSONObject().put("token", "t4").put("layout", "carousel"))!!.layout)
        assertNull(FeedCard.parse(JSONObject().put("title", "No token")))
    }

    @Test
    fun loadsTheFeedOfTheAnonymousIdWithoutExpiredCards() {
        transport.cards = JSONArray().put(card("t1").put("pinned", true)).put(card("t2", read = true)).put(card("t3", expires = "2027-01-15T07:59:59+00:00"))
        val feed = feed()
        var result: Pair<List<String>, Boolean>? = null

        feed.fetch { cards, success -> result = cards.map { it.token } to success }
        drain()

        assertEquals(listOf("/feed?anonymousId=anon-1"), transport.gets)
        assertEquals(listOf("t1", "t2") to true, result)
        assertEquals(1, feed.unread)

        transport.status = 0
        feed.fetch { cards, success -> result = cards.map { it.token } to success }
        drain()
        assertEquals(listOf("t1", "t2") to false, result)
    }

    @Test
    fun reportsAShowOnceAndKeepsItReadWhenARefreshComesBeforeThePlatformHasIt() {
        transport.cards = JSONArray().put(card("t1")).put(card("t2")).put(card("t3", read = true))
        val feed = feed()
        feed.fetch(null)
        drain()
        assertEquals(2, feed.unread)

        feed.shown("t1")
        feed.shown("t1")
        feed.shown("t3")
        drain()
        assertEquals(listOf("t1 shown"), transport.reported())
        assertEquals(1, feed.unread)

        // The platform still has t1 unread: the local show wins.
        feed.fetch(null)
        drain()
        assertTrue(feed.current.single { it.token == "t1" }.read)
        assertEquals(1, feed.unread)
    }

    @Test
    fun aDismissedCardLeavesTheFeedAndDoesNotComeBack() {
        transport.cards = JSONArray().put(card("t1")).put(card("t2"))
        val feed = feed()
        feed.fetch(null)
        drain()

        feed.dismissed("t1")
        drain()
        assertEquals(listOf("t1 dismissed"), transport.reported())
        assertEquals(listOf("t2"), feed.current.map { it.token })
        assertEquals(1, feed.unread)

        feed.fetch(null)
        drain()
        assertEquals(listOf("t2"), feed.current.map { it.token })
    }

    @Test
    fun aClickReportsTheLinkMarksTheCardReadAndOpensTheLink() {
        transport.cards = JSONArray().put(card("t1").put("button_text", "Shop").put("button_url", "shopapp://sale")).put(card("t2").put("button_url", "javascript:alert(1)"))
        val feed = feed()
        feed.fetch(null)
        drain()

        assertTrue(feed.clicked(app, feed.current[0]))
        assertFalse(feed.clicked(app, feed.current[1]))
        drain()

        assertEquals(listOf("t1 clicked shopapp://sale"), transport.reported())
        assertTrue(feed.current[0].read)
        assertEquals("shopapp://sale", shadowOf(app).nextStartedActivity.data.toString())
        assertNull(shadowOf(app).nextStartedActivity)

        // The app opens links itself.
        val opened = mutableListOf<String>()
        feed.linkHandler = FeedLinkHandler { card, url -> opened.add("${card.token} $url") }
        feed.clicked(app, feed.current[0])
        drain()
        assertEquals(listOf("t1 shopapp://sale"), opened)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun theUnreadListenerHearsTheCountWhenItChanges() {
        transport.cards = JSONArray().put(card("t1")).put(card("t2"))
        val feed = feed()
        val counts = mutableListOf<Int>()

        // Added before the first load: hears the current count and the feed is loaded.
        feed.addUnreadListener { counts.add(it) }
        drain()
        assertEquals(listOf(0, 2), counts)

        feed.fetch(null)
        feed.shown("t1")
        drain()
        assertEquals(listOf(0, 2, 1), counts)
    }

    @Test
    fun theClientRefreshesAWatchedFeedOnOpenAndLoadsTheNewVisitorsAfterReset() {
        val storage = Storage(app)
        val client = Client(app, OneTraceConfig("cdp_wk_test", "https://crm.example.com", flushIntervalSeconds = 3600, trackLifecycle = false), storage,
            EventQueue(File(app.filesDir, "feed-queue-test.jsonl").apply { delete() }, 50), transport, executor, DeviceContext(app, storage.installationId), clock = { now }, notificationsEnabled = { true })
        transport.cards = JSONArray().put(card("t1"))

        client.appForeground(fromBackground = false)
        drain()
        assertTrue(transport.gets.none { it.startsWith("/feed?") })

        val counts = mutableListOf<Int>()
        client.feed.addUnreadListener { counts.add(it) }
        drain()
        assertEquals(listOf(0, 1), counts)

        val previous = storage.anonymousId
        transport.cards = JSONArray()
        client.reset()
        drain()

        val feeds = transport.gets.filter { it.startsWith("/feed?") }
        assertEquals(listOf("/feed?anonymousId=$previous", "/feed?anonymousId=${storage.anonymousId}"), feeds)
        assertEquals(listOf(0, 1, 0), counts)
    }

    @Test
    fun theFeedViewShowsTheCardsAndReportsShowsClicksAndRemovals() {
        val storage = Storage(app)
        val client = Client(app, OneTraceConfig("cdp_wk_test", "https://crm.example.com", flushIntervalSeconds = 3600, trackLifecycle = false), storage,
            EventQueue(File(app.filesDir, "feed-queue-test.jsonl").apply { delete() }, 50), transport, executor, DeviceContext(app, storage.installationId), clock = { now }, notificationsEnabled = { true })
        OneTrace.setClientForTests(client)
        transport.cards = JSONArray()
            .put(card("t1").put("pinned", true).put("body", "-20% on shoes").put("button_text", "Shop").put("button_url", "shopapp://sale"))
            .put(card("t2").put("layout", "captioned").put("button_url", "https://shop.example.com/news"))
            .put(card("t3", read = true).put("layout", "banner"))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = OneTraceFeedView(activity).apply { onScreen = { true } }
        activity.setContentView(view)
        drain()
        drain()

        assertEquals(listOf("t1", "t2", "t3"), view.cardViews.map { it.first.token })
        assertNotNull(findText(view, "Pinned"))
        assertNotNull(findText(view, "-20% on shoes"))
        // Visible cards were reported shown; the one already read was not.
        assertEquals(listOf("t1 shown", "t2 shown"), transport.reported())

        (findText(view, "Shop") as Button).performClick()
        view.cardViews[1].second.performClick()
        drain()
        assertEquals(listOf("t1 shown", "t2 shown", "t1 clicked shopapp://sale", "t2 clicked https://shop.example.com/news"), transport.reported())
        assertEquals(setOf("shopapp://sale", "https://shop.example.com/news"), setOf(shadowOf(activity).nextStartedActivity.data.toString(), shadowOf(activity).nextStartedActivity.data.toString()))

        val dismiss = (view.cardViews[0].second as ViewGroup).let { card -> card.getChildAt(card.childCount - 1) }
        assertEquals("Remove", dismiss.contentDescription)
        dismiss.performClick()
        drain()
        assertEquals("t1 dismissed", transport.reported().last())
        assertEquals(listOf("t2", "t3"), view.cardViews.map { it.first.token })
    }

    @Test
    @Config(qualifiers = "ru")
    fun theFeedScreenIsLocalizedAndShowsTheEmptyState() {
        val storage = Storage(app)
        val client = Client(app, OneTraceConfig("cdp_wk_test", "https://crm.example.com", flushIntervalSeconds = 3600, trackLifecycle = false), storage,
            EventQueue(File(app.filesDir, "feed-queue-test.jsonl").apply { delete() }, 50), transport, executor, DeviceContext(app, storage.installationId), clock = { now }, notificationsEnabled = { true })
        OneTrace.setClientForTests(client)

        OneTrace.openFeed(app)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(OneTraceFeedActivity::class.java.name, intent.component!!.className)

        val activity = Robolectric.buildActivity(OneTraceFeedActivity::class.java, intent).setup().get()
        drain()
        drain()

        assertEquals("Новости", activity.title)
        assertNotNull(findText(activity.window.decorView, "Новости"))
        assertNotNull(findText(activity.window.decorView, "Здесь появятся предложения и новости магазина."))
    }

    private fun findText(view: View, text: String): View? = when (view) {
        is TextView -> view.takeIf { it.text.toString() == text }
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findText(view.getChildAt(it), text) }
        else -> null
    }
}
