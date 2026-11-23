package pro.onetrace.sdk

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import pro.onetrace.sdk.internal.RemoteConfigStore
import java.util.concurrent.CopyOnWriteArraySet

/** Called on the main thread after [OneTraceRemoteConfig.fetch]: false when the values could not be fetched. */
public fun interface RemoteConfigFetchCallback {
    public fun onFetched(success: Boolean)
}

/** Called on the main thread when fetched values differ from the previous ones. */
public fun interface RemoteConfigListener {
    public fun onRemoteConfigChanged(changedKeys: Set<String>)
}

/**
 * App settings from OneTrace (**Apps → App settings**): values by segment, platform and app version, without a new app
 * release. The SDK fetches them when the app starts and opens, at most once per `remoteConfigMinFetchIntervalSeconds`,
 * and right after `identify` and `reset`. Until the first answer and offline the getters return the values stored on
 * the last launch, and without them the defaults you pass.
 *
 * The values are visible to any app with the write key: never keep secrets there.
 */
public class OneTraceRemoteConfig internal constructor() {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<RemoteConfigListener>()

    @Volatile
    internal var store: RemoteConfigStore? = null

    /** Fetches the values; set by the initialized SDK. */
    @Volatile
    internal var fetcher: ((force: Boolean, done: (Boolean) -> Unit) -> Unit)? = null

    /** A text value; numbers and booleans as text. */
    public fun getString(key: String, default: String?): String? = when (val value = raw(key)) {
        is String -> value
        is Number, is Boolean -> value.toString()
        else -> default
    }

    public fun getBoolean(key: String, default: Boolean): Boolean = when (val value = raw(key)) {
        is Boolean -> value
        "true" -> true
        "false" -> false
        else -> default
    }

    public fun getLong(key: String, default: Long): Long = when (val value = raw(key)) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: default
        else -> default
    }

    public fun getDouble(key: String, default: Double): Double = when (val value = raw(key)) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull() ?: default
        else -> default
    }

    /** A JSON value (an object or a list) as JSON text, for example for `JSONObject(text)` or a JSON library. */
    public fun getJson(key: String, default: String?): String? = when (val value = raw(key)) {
        is JSONObject, is JSONArray -> value.toString()
        else -> default
    }

    /** Fetches the values now unless they were fetched within the minimum interval; before `init` calls back false. */
    public fun fetch(callback: RemoteConfigFetchCallback) {
        val fetch = fetcher

        if (fetch == null) {
            main.post { callback.onFetched(false) }
            return
        }

        fetch(false) { success -> main.post { callback.onFetched(success) } }
    }

    public fun addListener(listener: RemoteConfigListener) {
        listeners.add(listener)
    }

    public fun removeListener(listener: RemoteConfigListener) {
        listeners.remove(listener)
    }

    internal fun changed(keys: Set<String>) {
        if (keys.isEmpty()) {
            return
        }

        main.post { listeners.forEach { it.onRemoteConfigChanged(keys) } }
    }

    private fun raw(key: String): Any? = store?.values?.opt(key)?.takeIf { it != JSONObject.NULL }
}
