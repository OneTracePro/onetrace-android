package pro.onetrace.sdk.internal

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal class HttpResult(val status: Int, val body: String) {
    val successful: Boolean get() = status in 200..299

    /** The request may succeed later: no network, rate limit or a server error. */
    val retryable: Boolean get() = status == 0 || status == 429 || status >= 500
}

internal fun interface Transport {
    fun post(path: String, body: JSONObject): HttpResult
}

/**
 * JSON over HTTPS with the write key; no third-party HTTP library, so the SDK adds nothing to the app.
 */
internal class HttpTransport(private val apiUrl: String, private val writeKey: String, private val userAgent: String) : Transport {
    override fun post(path: String, body: JSONObject): HttpResult {
        val connection = try {
            URL(apiUrl + path).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return HttpResult(0, e.message.orEmpty())
        }

        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $writeKey")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", userAgent)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            HttpResult(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } catch (e: IOException) {
            HttpResult(0, e.message.orEmpty())
        } finally {
            connection.disconnect()
        }
    }
}
