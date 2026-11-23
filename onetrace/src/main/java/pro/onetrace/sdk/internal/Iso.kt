package pro.onetrace.sdk.internal

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** ISO 8601 times of push data (`2026-11-23T18:00:00Z`, with milliseconds or an offset) on every Android version. */
internal object Iso {
    private val offset = Regex("([+-]\\d{2}):?(\\d{2})$")

    fun parse(value: String?): Long? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // Unix seconds are accepted too.
        text.toLongOrNull()?.let { return it * 1000 }
        // «Z» and «+03:00» as the RFC 822 zone that SimpleDateFormat parses before Android 7.
        val zoned = if (text.endsWith("Z") || text.endsWith("z")) text.dropLast(1) + "+0000" else offset.replace(text) { "${it.groupValues[1]}${it.groupValues[2]}" }

        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd'T'HH:mmZ")) {
            try {
                val format = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
                // More than three fraction digits («.123456») are cut to milliseconds.
                return format.parse(zoned.replace(Regex("(\\.\\d{3})\\d+"), "$1"))?.time
            } catch (e: ParseException) {
                continue
            }
        }

        return null
    }
}
