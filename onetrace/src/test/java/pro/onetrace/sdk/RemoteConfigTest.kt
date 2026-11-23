package pro.onetrace.sdk

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
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
class RemoteConfigTest {
    private class ConfigTransport : Transport {
        val configRequests = mutableListOf<Pair<String, Map<String, String>>>()
        var status = 200
        var values = JSONObject()
        var etag = "\"v1\""

        override fun get(path: String): HttpResult = get(path, emptyMap())

        override fun get(path: String, headers: Map<String, String>): HttpResult {
            if (!path.startsWith("/app/config?")) {
                return HttpResult(200, "{\"data\": []}")
            }

            configRequests.add(path to headers)

            return when {
                status != 200 -> HttpResult(status, "")
                headers["If-None-Match"] == etag -> HttpResult(304, "")
                else -> HttpResult(200, JSONObject().put("values", values).toString(), mapOf("etag" to etag))
            }
        }

        override fun post(path: String, body: JSONObject): HttpResult = HttpResult(if (path == "/batch") 202 else 200, "{}")
    }

    private lateinit var app: Application
    private lateinit var executor: ScheduledExecutorService
    private lateinit var transport: ConfigTransport
    private var now = 1_800_000_000_000L

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        executor = Executors.newSingleThreadScheduledExecutor()
        transport = ConfigTransport()
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    private fun client(config: OneTraceRemoteConfig = OneTraceRemoteConfig()): Client {
        val storage = Storage(app)

        return Client(
            app,
            OneTraceConfig("cdp_wk_test", "https://crm.example.com", flushIntervalSeconds = 3600, trackLifecycle = false, remoteConfigMinFetchIntervalSeconds = 3600),
            storage,
            EventQueue(File(app.filesDir, "queue-config.jsonl").apply { delete() }, 50),
            transport,
            executor,
            DeviceContext(app, storage.installationId),
            clock = { now },
            remoteConfig = config,
        )
    }

    private fun drain() = executor.submit {}.get()

    @Test
    fun returnsTheStoredValuesOfflineAndTheDefaultsWithoutThem() {
        transport.status = 0
        val offline = OneTraceRemoteConfig()
        client(offline).start()
        drain()

        assertEquals("default", offline.getString("home_banner", "default"))
        assertEquals(10L, offline.getLong("free_delivery_from", 10))
        assertFalse(offline.getBoolean("new_checkout", false))

        transport.status = 200
        transport.values = JSONObject().put("home_banner", "summer").put("free_delivery_from", 3000).put("new_checkout", true)
            .put("ratio", 0.25).put("menu", JSONObject().put("items", 3))
        client(OneTraceRemoteConfig()).start()
        drain()

        // The next launch without network: the values of the last answer.
        transport.status = 0
        val relaunched = OneTraceRemoteConfig()
        client(relaunched).start()
        drain()

        assertEquals("summer", relaunched.getString("home_banner", "default"))
        assertEquals(3000L, relaunched.getLong("free_delivery_from", 10))
        assertTrue(relaunched.getBoolean("new_checkout", false))
        assertEquals(0.25, relaunched.getDouble("ratio", 1.0), 0.0)
        assertEquals(3, JSONObject(relaunched.getJson("menu", null)!!).getInt("items"))
        assertNull(relaunched.getJson("home_banner", null))
        assertEquals("fallback", relaunched.getString("missing", "fallback"))
    }

    @Test
    fun fetchesAtMostOnceAnHourButRightAfterIdentifyAndReset() {
        val client = client()
        client.start()
        drain()
        assertEquals(1, transport.configRequests.size)
        val first = transport.configRequests.first().first
        assertTrue(first, first.contains("anonymousId=") && first.contains("appId=${app.packageName}") && first.contains("platform=android") && first.contains("appVersion="))

        now += 20 * 60_000
        client.appForeground(fromBackground = true)
        drain()
        assertEquals(1, transport.configRequests.size)

        client.identify("u-42", emptyMap(), null)
        drain()
        assertEquals(2, transport.configRequests.size)
        // Nothing changed since: the stored ETag, a 304.
        assertEquals("\"v1\"", transport.configRequests[1].second["If-None-Match"])

        client.reset()
        drain()
        assertEquals(3, transport.configRequests.size)

        now += 61 * 60_000
        client.appForeground(fromBackground = true)
        drain()
        assertEquals(4, transport.configRequests.size)
    }

    @Test
    fun tellsListenersWhichValuesChanged() {
        val config = OneTraceRemoteConfig()
        val changes = mutableListOf<Set<String>>()
        config.addListener { changes.add(it) }
        transport.values = JSONObject().put("home_banner", "summer").put("theme", "light")
        val client = client(config)
        client.start()
        drain()

        transport.values = JSONObject().put("home_banner", "autumn").put("theme", "light")
        transport.etag = "\"v2\""
        client.reset()
        drain()
        ShadowLooper.idleMainLooper()

        assertEquals(listOf(setOf("home_banner", "theme"), setOf("home_banner")), changes)
        assertEquals("autumn", config.getString("home_banner", null))
    }

    @Test
    fun aPlanWithoutTheFeatureIsAskedAgainOnlyAfterTheIntervalButALimitedRequestIsRetried() {
        // 402: the plan has no mobile engagement; the defaults stay and the next launch does not ask again.
        transport.status = 402
        val client = client()
        client.start()
        drain()
        assertEquals(1, transport.configRequests.size)

        now += 20 * 60_000
        client(OneTraceRemoteConfig()).start()
        drain()
        assertEquals(1, transport.configRequests.size)

        now += 41 * 60_000
        transport.status = 429
        client.appForeground(fromBackground = true)
        drain()
        assertEquals(2, transport.configRequests.size)

        // Too many requests is not an answer: asked again on the next start.
        client.appForeground(fromBackground = true)
        drain()
        assertEquals(3, transport.configRequests.size)
    }
}
