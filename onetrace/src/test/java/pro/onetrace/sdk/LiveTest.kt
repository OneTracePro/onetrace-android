package pro.onetrace.sdk

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pro.onetrace.sdk.internal.DeviceContext
import pro.onetrace.sdk.internal.HttpTransport
import pro.onetrace.sdk.internal.Json
import java.util.UUID

/**
 * Against a real project: ./gradlew :onetrace:testDebugUnitTest --tests '*LiveTest*' -Ponetrace.live.url=… -Ponetrace.live.key=…
 * Skipped without these properties.
 */
@RunWith(RobolectricTestRunner::class)
class LiveTest {
    @Test
    fun thePlatformAcceptsTheSdkRequests() {
        val url = System.getProperty("onetrace.live.url")
        val key = System.getProperty("onetrace.live.key")
        assumeTrue(!url.isNullOrBlank() && !key.isNullOrBlank())

        val app: Application = ApplicationProvider.getApplicationContext()
        val installation = "live-" + UUID.randomUUID().toString().take(8)
        val anonymous = "live-anon-" + UUID.randomUUID().toString().take(8)
        val context = DeviceContext(app, installation).build()
        val transport = HttpTransport(OneTraceConfig(key!!, url!!).apiUrl, key, "onetrace-android-live-test")
        val event = { type: String, fields: JSONObject -> fields.put("type", type).put("anonymousId", anonymous).put("messageId", UUID.randomUUID().toString()).put("timestamp", Json.iso(System.currentTimeMillis())).put("context", context) }

        val batch = transport.post("/batch", JSONObject().put("batch", JSONArray()
            .put(event("track", JSONObject().put("event", "app_opened").put("properties", JSONObject().put("from_background", false))))
            .put(event("screen", JSONObject().put("name", "Catalog")))))
        assertEquals(batch.body, 202, batch.status)

        val device = transport.post("/devices", JSONObject()
            .put("installationId", installation).put("anonymousId", anonymous)
            .put("app", JSONObject().put("id", "pro.onetrace.live").put("version", "1.0.0"))
            .put("os", JSONObject().put("name", "android").put("version", "15"))
            .put("push", JSONObject().put("provider", "fcm").put("token", "live-token-$installation").put("enabled", true)))
        assertEquals(device.body, 200, device.status)
        assertEquals(true, JSONObject(device.body).getBoolean("push"))
    }
}
