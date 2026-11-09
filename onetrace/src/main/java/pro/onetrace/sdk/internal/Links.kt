package pro.onetrace.sdk.internal

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Links of push notifications, in-app message buttons and feed cards: a web page or a deep link of the app, opened by
 * whichever app handles it.
 */
internal object Links {
    private val unsafe = Regex("^\\s*(javascript|data|vbscript|file):", RegexOption.IGNORE_CASE)

    /** The link, or null when it is empty or a scheme that must not be opened from a message. */
    fun safe(url: String?): String? = url?.trim()?.takeIf { it.isNotEmpty() && it != "null" && !unsafe.containsMatchIn(it) }

    /** Image addresses: only web ones are downloaded. */
    fun image(url: String?): String? = url?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }

    /** Opens the link; false when no app on the device can. */
    fun open(launcher: Context, url: String): Boolean {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))

        if (launcher !is Activity) {
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            launcher.startActivity(view)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
