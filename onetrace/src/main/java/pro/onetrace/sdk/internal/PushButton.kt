package pro.onetrace.sdk.internal

import org.json.JSONArray

/**
 * A button of a OneTrace notification (`cdp_buttons`): up to three, each with its id (`b1`…`b3`), text and action.
 * «Remind me later» carries `snooze_minutes`: minutes, or `tomorrow_9` for tomorrow at 9:00 by the phone clock.
 */
internal class PushButton(val id: String, val title: String, val action: String, val url: String?, val snooze: String?) {
    companion object {
        const val OPEN_URL = "open_url"
        const val OPEN_APP = "open_app"
        const val DISMISS = "dismiss"
        const val SNOOZE = "snooze"
        const val MAX = 3

        private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")

        /** The buttons that can be shown; a broken list or button is skipped, not the notification. */
        fun list(json: String?): List<PushButton> {
            val array = json?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()

            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id")
                val title = item.optString("title").trim()
                // The editor calls the link action `url`.
                val action = item.optString("action").let { if (it == "url") OPEN_URL else it }
                val url = Links.safe(item.optString("url").takeIf { item.has("url") })

                when {
                    !ID.matches(id) || title.isEmpty() -> null
                    action !in listOf(OPEN_URL, OPEN_APP, DISMISS, SNOOZE) -> null
                    action == OPEN_URL && url == null -> null
                    else -> PushButton(id, title, action, url, item.opt("snooze_minutes")?.toString()?.takeIf { action == SNOOZE })
                }
            }.take(MAX)
        }
    }
}
