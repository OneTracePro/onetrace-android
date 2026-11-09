package pro.onetrace.sdk

import org.json.JSONObject
import pro.onetrace.sdk.internal.Links
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * A card of the message feed: an offer, news, a reminder or a promo code that stays in the app until it expires or the
 * user removes it. Get them with [OneTrace.fetchFeed]; report what the user does with [OneTrace.feedCardShown],
 * [OneTrace.feedCardClicked] and [OneTrace.feedCardDismissed], or show [OneTraceFeedView] and the SDK does all of it.
 *
 * @property token identifies the card in the reports.
 * @property title shown in bold; for the [Layout.BANNER] layout the description of the image.
 * @property imageUrl a web address of the image.
 * @property buttonText the button label; null when the whole card is the link.
 * @property buttonUrl a web page or a deep link of the app.
 * @property pinned shown at the top of the feed.
 * @property read already shown to the user, in this or an earlier session.
 */
public data class FeedCard(
    public val token: String,
    public val layout: Layout,
    public val title: String,
    public val body: String?,
    public val imageUrl: String?,
    public val buttonText: String?,
    public val buttonUrl: String?,
    public val pinned: Boolean,
    public val read: Boolean,
    public val createdAt: Date?,
    public val expiresAt: Date?,
) {
    /** How the card is drawn. */
    public enum class Layout(internal val key: String) {
        /** The image on the left of the text. */
        CLASSIC("classic"),

        /** The image above the text. */
        CAPTIONED("captioned"),

        /** Only the image. */
        BANNER("banner"),
    }

    /** True when a tap anywhere on the card opens [buttonUrl]: there is no button, or the card is only an image. */
    public val opensOnTap: Boolean get() = link != null && (buttonText == null || layout == Layout.BANNER)

    /** [buttonUrl] when it may be opened. */
    internal val link: String? get() = Links.safe(buttonUrl)

    internal companion object {
        private val iso = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})(\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?$")

        /** A card of `GET /feed`; null without a token. */
        fun parse(json: JSONObject): FeedCard? {
            val token = text(json, "token") ?: return null

            return FeedCard(
                token = token,
                layout = Layout.entries.firstOrNull { it.key == json.optString("layout") } ?: Layout.CLASSIC,
                title = text(json, "title").orEmpty(),
                body = text(json, "body"),
                imageUrl = Links.image(text(json, "image_url")),
                buttonText = text(json, "button_text"),
                buttonUrl = text(json, "button_url"),
                pinned = json.optBoolean("pinned", false),
                read = json.optBoolean("read", false),
                createdAt = date(text(json, "created_at")),
                expiresAt = date(text(json, "expires_at")),
            )
        }

        private fun text(json: JSONObject, key: String): String? = if (json.isNull(key)) null else json.optString(key).takeIf { it.isNotBlank() }

        /** ISO 8601 with an offset (`2026-11-09T10:00:00+00:00`); java.time and the `X` pattern need API 26 and 24. */
        fun date(value: String?): Date? {
            val match = iso.matchEntire(value?.trim() ?: return null) ?: return null
            val parts = match.groupValues
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(parts[1].toInt(), parts[2].toInt() - 1, parts[3].toInt(), parts[4].toInt(), parts[5].toInt(), parts[6].toInt())
                set(Calendar.MILLISECOND, parts[7].drop(1).padEnd(3, '0').take(3).ifEmpty { "0" }.toInt())
            }
            val zone = parts[8].replace(":", "")
            val offset = if (zone.isEmpty() || zone == "Z") 0 else (if (zone[0] == '-') -1 else 1) * (zone.substring(1, 3).toInt() * 60 + zone.substring(3, 5).toInt())

            return Date(calendar.timeInMillis - offset * 60_000L)
        }
    }
}
