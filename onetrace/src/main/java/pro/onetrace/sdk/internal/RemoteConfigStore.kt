package pro.onetrace.sdk.internal

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONObject

/**
 * Values of the app settings (Remote Config) for this installation: `GET /app/config` with the ETag of the stored
 * values, at most once per [minIntervalMillis] unless forced (after `identify` and `reset`). The last values are kept
 * on disk, so the app reads them on the next launch before the first answer and without network.
 */
internal class RemoteConfigStore(
    context: Context,
    private val transport: Transport,
    private val minIntervalMillis: Long,
    private val clock: () -> Long,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("pro.onetrace.sdk.config", Context.MODE_PRIVATE)

    @Volatile
    var values: JSONObject = runCatching { JSONObject(prefs.getString(VALUES, null) ?: "{}") }.getOrDefault(JSONObject())
        private set

    /** Keys whose values changed by the fetch, or null when the values could not be fetched. */
    class Fetched(val changed: Set<String>?)

    /** SDK thread only. Skips the request within the interval unless [force]; that counts as a success. */
    fun fetch(force: Boolean, anonymousId: String, appId: String, appVersion: String): Fetched {
        val last = prefs.getLong(FETCHED_AT, 0)

        if (!force && last > 0 && clock() - last < minIntervalMillis) {
            return Fetched(emptySet())
        }

        val query = "anonymousId=${Uri.encode(anonymousId)}&appId=${Uri.encode(appId)}&platform=android&appVersion=${Uri.encode(appVersion)}"
        // The values depend on the profile: another anonymous id must not get «not modified» for the previous one.
        val etag = prefs.getString(ETAG, null)?.takeIf { prefs.getString(ETAG_FOR, null) == anonymousId }
        val result = transport.get("/app/config?$query", etag?.let { mapOf("If-None-Match" to it) } ?: emptyMap())

        // 304, and a refusal that the next request would get too (402 without the plan's feature, other 4xx but 429):
        // the stored values stay and the next request waits for the interval instead of every launch.
        if (result.status == 304 || (result.status in 400..499 && result.status != 429)) {
            prefs.edit().putLong(FETCHED_AT, clock()).apply()
            return Fetched(emptySet())
        }

        if (!result.successful) {
            return Fetched(null)
        }

        val fresh = runCatching { JSONObject(result.body).optJSONObject("values") }.getOrNull() ?: return Fetched(null)
        val previous = values
        val changed = (keys(previous) + keys(fresh)).filter { key -> previous.opt(key)?.toString() != fresh.opt(key)?.toString() }.toSet()
        values = fresh
        prefs.edit()
            .putString(VALUES, fresh.toString())
            .putString(ETAG, result.header("ETag"))
            .putString(ETAG_FOR, anonymousId)
            .putLong(FETCHED_AT, clock())
            .apply()

        return Fetched(changed)
    }

    private fun keys(json: JSONObject): Set<String> = json.keys().asSequence().toSet()

    private companion object {
        const val VALUES = "values"
        const val ETAG = "etag"
        const val ETAG_FOR = "etag_anonymous_id"
        const val FETCHED_AT = "fetched_at"
    }
}
