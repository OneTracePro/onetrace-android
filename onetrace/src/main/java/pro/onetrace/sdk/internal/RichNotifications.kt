package pro.onetrace.sdk.internal

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import pro.onetrace.sdk.R
import java.util.Calendar

/**
 * OneTrace notifications drawn by the SDK from the push data (`cdp_*` keys): a carousel paged with «‹ ›», a countdown
 * to the end of an offer, up to three buttons, «Remind me later» as a local notification. No Firebase here: the
 * `onetrace-fcm` module and the wrappers pass the data on.
 */
internal object RichNotifications {
    const val TITLE = "cdp_title"
    const val BODY = "cdp_body"
    const val BUTTONS = "cdp_buttons"
    const val CAROUSEL = "cdp_carousel"
    const val TIMER_END = "cdp_timer_end"
    const val TIMER_REMOVE = "cdp_timer_remove"

    /** Extras of the notification and of the SDK intents. */
    const val EXTRA_NOTIFICATION_ID = "pro.onetrace.notification_id"
    const val EXTRA_DATA = "pro.onetrace.data"
    const val EXTRA_INDEX = "pro.onetrace.carousel_index"
    const val EXTRA_SIZE = "pro.onetrace.carousel_size"
    const val EXTRA_BUTTON = "pro.onetrace.button"

    /** True when the data came from OneTrace: a message, a Live Update or a silent check. */
    fun isOneTrace(data: Map<String, String>): Boolean = data.containsKey(Client.PUSH_TOKEN) || data.containsKey(Client.PUSH_KIND)

    /**
     * Shows a message, updates a Live Update or ignores a silent check; reports the message as received. Blocks while
     * the images download (up to 10 seconds), so not on the main thread.
     */
    fun handle(context: Context, data: Map<String, String>, client: Client?): Boolean {
        if (!isOneTrace(data)) {
            return false
        }

        when (data[Client.PUSH_KIND]) {
            // A check that the app is still installed: nothing to show or report.
            "ping" -> Unit
            "live" -> {
                client?.pushReceived(data)
                LiveUpdates.handle(context, data, client)
            }
            else -> {
                client?.pushReceived(data)

                if (!data[TITLE].isNullOrBlank() || !data[BODY].isNullOrBlank()) {
                    show(context, data)
                }
            }
        }

        return true
    }

    fun notificationId(data: Map<String, String>): Int = (data[Client.PUSH_TOKEN] ?: data.toString()).hashCode()

    /** Draws the notification; [cacheOnly] pages a carousel with the images downloaded when it arrived. */
    fun show(context: Context, data: Map<String, String>, index: Int = 0, cacheOnly: Boolean = false) {
        val manager = NotificationManagerCompat.from(context)

        if (!manager.areNotificationsEnabled()) {
            return
        }

        val id = notificationId(data)
        val title = data[TITLE].orEmpty()
        val body = data[BODY].orEmpty()
        val now = System.currentTimeMillis()
        val timerEnd = Iso.parse(data[TIMER_END])
        val removeAtEnd = data[TIMER_REMOVE] != "0"

        // The offer is over: nothing left to count down to.
        if (timerEnd != null && timerEnd <= now && removeAtEnd) {
            return
        }

        val builder = NotificationCompat.Builder(context, PushChannels.messages(context))
            .setSmallIcon(PushChannels.icon(context))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        launch(context, data, id, 0, emptyMap())?.let(builder::setContentIntent)

        val cards = cards(data[CAROUSEL])
        val images = PushImages.load(context, id.toString(), cards.map { it.optString("image") } + data[Client.PUSH_IMAGE], cacheOnly)
        val loaded = cards.indices.filter { images[it] != null }

        if (loaded.size >= 2) {
            val position = Math.floorMod(index, loaded.size)
            val card = cards[loaded[position]]
            builder.setCustomBigContentView(carouselView(context, data, id, card, images[loaded[position]]!!, position, loaded.size))
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .addExtras(android.os.Bundle().apply {
                    putInt(EXTRA_SIZE, loaded.size)
                    putInt(EXTRA_INDEX, position)
                })
        } else {
            // Fewer than two cards with images: a plain notification with the first image there is.
            (loaded.firstOrNull()?.let { images[it] } ?: images.last())?.let { image ->
                builder.setLargeIcon(image).setStyle(NotificationCompat.BigPictureStyle().bigPicture(image).bigLargeIcon(null as Bitmap?).setSummaryText(body))
            }
        }

        if (timerEnd != null && timerEnd > now) {
            builder.setWhen(timerEnd).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)

            if (removeAtEnd) {
                builder.setTimeoutAfter(timerEnd - now)
            }
        }

        PushButton.list(data[BUTTONS]).forEachIndexed { slot, button -> builder.addAction(0, button.title, buttonIntent(context, data, id, slot + 1, button)) }

        try {
            manager.notify(id, builder.build())
        } catch (e: SecurityException) {
            // The notification permission was revoked between the check and the call.
        }
    }

    /** «Remind me later»: the same notification again at the chosen time, by the phone alone. */
    fun snooze(context: Context, data: Map<String, String>, button: PushButton) {
        val at = snoozeTime(button.snooze, System.currentTimeMillis()) ?: return
        val intent = Intent(context, PushActionReceiver::class.java).setAction(PushActionReceiver.SNOOZED)
            .setData(Uri.parse("onetrace://push/${notificationId(data)}/snoozed"))
            .putExtra(EXTRA_DATA, JSONObject(data).toString())
        val pending = PendingIntent.getBroadcast(context, notificationId(data), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarms = context.getSystemService(AlarmManager::class.java)

        if (Build.VERSION.SDK_INT >= 23) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            alarms.set(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    /** Minutes (`60`, `180`) or `tomorrow_9`: tomorrow at 9:00 by the phone clock. */
    fun snoozeTime(value: String?, now: Long): Long? {
        val text = value?.trim()?.lowercase() ?: return null
        text.toLongOrNull()?.takeIf { it > 0 }?.let { return now + it * 60_000 }

        if (!text.startsWith("tomorrow")) {
            return null
        }

        return Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun data(json: String?): Map<String, String> {
        val obj = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
        return obj.keys().asSequence().associateWith { obj.optString(it) }
    }

    private fun cards(json: String?): List<JSONObject> {
        val array = json?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.take(10)
    }

    private fun carouselView(context: Context, data: Map<String, String>, id: Int, card: JSONObject, image: Bitmap, position: Int, size: Int): RemoteViews =
        RemoteViews(context.packageName, R.layout.onetrace_carousel).apply {
            setImageViewBitmap(R.id.onetrace_carousel_image, image)
            setTextViewText(R.id.onetrace_carousel_title, card.optString("title"))
            setTextViewText(R.id.onetrace_carousel_body, card.optString("body"))
            setTextViewText(R.id.onetrace_carousel_counter, "${position + 1} / $size")
            setOnClickPendingIntent(R.id.onetrace_carousel_prev, pageIntent(context, data, id, position - 1))
            setOnClickPendingIntent(R.id.onetrace_carousel_next, pageIntent(context, data, id, position + 1))
            // A tap on the card opens its link (or the message link) and is reported with it.
            val link = Links.safe(card.optString("url").takeIf { card.has("url") }) ?: data[Client.PUSH_URL]
            launch(context, data, id, 10 + position, mapOf(Client.PUSH_URL to link, EXTRA_NOTIFICATION_ID to id.toString()))?.let { open ->
                setOnClickPendingIntent(R.id.onetrace_carousel_image, open)
                setOnClickPendingIntent(R.id.onetrace_carousel_title, open)
            }
        }

    private fun pageIntent(context: Context, data: Map<String, String>, id: Int, index: Int): PendingIntent =
        broadcast(context, data, id, PushActionReceiver.PAGE, "page/$index") { it.putExtra(EXTRA_INDEX, index) }

    private fun buttonIntent(context: Context, data: Map<String, String>, id: Int, slot: Int, button: PushButton): PendingIntent? = when (button.action) {
        PushButton.DISMISS -> broadcast(context, data, id, PushActionReceiver.DISMISS, "button/$slot") { it.putExtra(EXTRA_BUTTON, button.id) }
        PushButton.SNOOZE -> broadcast(context, data, id, PushActionReceiver.SNOOZE, "button/$slot") { it.putExtra(EXTRA_BUTTON, button.id) }
        // Opening the app or a link from a notification action needs an activity intent (Android 12 forbids trampolines).
        PushButton.OPEN_APP -> launch(context, data, id, slot, mapOf(Client.PUSH_ACTION to button.id, Client.PUSH_URL to null, EXTRA_NOTIFICATION_ID to id.toString()))
        else -> launch(context, data, id, slot, mapOf(Client.PUSH_ACTION to button.id, Client.PUSH_URL to button.url, EXTRA_NOTIFICATION_ID to id.toString()))
    }

    private fun broadcast(context: Context, data: Map<String, String>, id: Int, action: String, path: String, extras: (Intent) -> Unit): PendingIntent {
        val intent = Intent(context, PushActionReceiver::class.java).setAction(action)
            .setData(Uri.parse("onetrace://push/$id/$path"))
            .putExtra(EXTRA_DATA, JSONObject(data).toString())
            .putExtra(EXTRA_NOTIFICATION_ID, id)
        extras(intent)

        return PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Opens the launcher activity with the push data; the SDK reports the tap from the activity intent. */
    fun launch(context: Context, data: Map<String, String>, id: Int, slot: Int, overrides: Map<String, String?>): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        data.forEach { (key, value) -> launch.putExtra(key, value) }
        overrides.forEach { (key, value) ->
            when {
                value == null -> launch.removeExtra(key)
                key == EXTRA_NOTIFICATION_ID -> launch.putExtra(key, value.toInt())
                else -> launch.putExtra(key, value)
            }
        }

        return PendingIntent.getActivity(context, 31 * id + slot, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
