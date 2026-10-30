package pro.onetrace.sdk.internal

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Event properties and traits from Kotlin and Java values: maps, lists, numbers, booleans, dates (ISO 8601 UTC).
 */
internal object Json {
    fun obj(map: Map<String, Any?>): JSONObject = JSONObject().apply {
        map.forEach { (key, value) -> put(key, value(value)) }
    }

    fun value(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is JSONObject, is JSONArray, is String, is Boolean, is Int, is Long, is Double -> value
        is Float -> value.toDouble()
        is Number -> value.toDouble()
        is Date -> iso(value.time)
        is Map<*, *> -> JSONObject().apply { value.forEach { (k, v) -> put(k.toString(), value(v)) } }
        is Iterable<*> -> JSONArray().apply { value.forEach { put(value(it)) } }
        is Array<*> -> JSONArray().apply { value.forEach { put(value(it)) } }
        else -> value.toString()
    }

    fun iso(millis: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(millis))
}
