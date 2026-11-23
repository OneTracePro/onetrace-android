package pro.onetrace.sdk.internal

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import pro.onetrace.sdk.R
import kotlin.math.roundToInt

/**
 * Order and delivery status in one notification per type and key (`cdp_kind=live`, `cdp_live`), updated in place: a
 * Live Update on Android 16 (`ProgressStyle`, promoted ongoing), an ongoing notification with progress and a countdown
 * before it. `end` shows the final state and lets the system remove it at `dismissAt`; at the end of the countdown the
 * notification becomes «completed», and from `staleAt` it is marked «out of date» until a fresh update. `alert: true`
 * («Notify» in the template) makes an update sound with a banner; other updates change it silently. A swiped notification
 * stays away for later updates; only `start` brings it back.
 */
internal object LiveUpdates {
    const val EXTRA_TYPE = "pro.onetrace.live_type"
    const val EXTRA_KEY = "pro.onetrace.live_key"
    private const val ID = 0x4F54

    private class Live(val type: String, val key: String, val event: String, val state: JSONObject, val dismissAt: Long?, val staleAt: Long?, val alert: Boolean) {
        val tag: String get() = "onetrace_live:$type:$key"
    }

    fun handle(context: Context, data: Map<String, String>, client: Client?) {
        val live = parse(data[Client.PUSH_LIVE]) ?: return
        val prefs = prefs(context)
        val dismissed = prefs.getBoolean("dismissed:${live.tag}", false)
        val active = prefs.getBoolean("active:${live.tag}", false)

        when (live.event) {
            "start" -> prefs.edit().remove("dismissed:${live.tag}").apply()
            // The user swiped it away: updates do not bring it back.
            else -> if (dismissed) {
                if (live.event == "end") {
                    forget(prefs, live)
                }
                return
            }
        }

        if (live.event == "end") {
            forget(prefs, live)
            val now = System.currentTimeMillis()

            if (live.dismissAt != null && live.dismissAt <= now) {
                NotificationManagerCompat.from(context).cancel(live.tag, ID)
                return
            }

            post(context, data, live, ongoing = false, completed = false, timeout = live.dismissAt?.let { it - now }, alert = live.alert)
            return
        }

        post(context, data, live, ongoing = true, completed = false, timeout = null, alert = live.alert)
        prefs.edit().putBoolean("active:${live.tag}", true).putString("data:${live.tag}", JSONObject(data).toString()).apply()
        scheduleCompletion(context, live)
        scheduleStale(context, live)

        if (!active) {
            client?.liveUpdateEvent(live.type, live.key, "started")
        }
    }

    /** The user swiped the notification away. */
    fun dismissed(context: Context, type: String, key: String, client: Client?) {
        val tag = "onetrace_live:$type:$key"
        prefs(context).edit().putBoolean("dismissed:$tag", true).remove("active:$tag").remove("data:$tag").apply()
        client?.liveUpdateEvent(type, key, "dismissed")
    }

    /** The countdown is over: the same notification as «completed», if it is still the current state. */
    fun completed(context: Context, type: String, key: String) {
        val tag = "onetrace_live:$type:$key"
        val data = RichNotifications.data(prefs(context).getString("data:$tag", null))
        val live = parse(data[Client.PUSH_LIVE]) ?: return
        val endsAt = endsAt(live.state) ?: return

        if (endsAt <= System.currentTimeMillis() + 1000) {
            post(context, data, live, ongoing = false, completed = true, timeout = null, alert = false)
        }
    }

    /**
     * The stale time of the current state came: the same notification, marked «out of date», without a sound. After
     * the end of the countdown («Completed») and after `end` (no current state) nothing changes.
     */
    fun stale(context: Context, type: String, key: String) {
        val tag = "onetrace_live:$type:$key"
        val data = RichNotifications.data(prefs(context).getString("data:$tag", null))
        val live = parse(data[Client.PUSH_LIVE]) ?: return
        val countdownOver = endsAt(live.state)?.let { it <= System.currentTimeMillis() + 1000 } ?: false

        if (live.staleAt != null && !countdownOver) {
            post(context, data, live, ongoing = true, completed = false, timeout = null, alert = false)
        }
    }

    private fun forget(prefs: SharedPreferences, live: Live) {
        prefs.edit().remove("active:${live.tag}").remove("data:${live.tag}").remove("dismissed:${live.tag}").apply()
    }

    private fun parse(json: String?): Live? {
        val live = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val type = live.optString("type").takeIf { it.isNotEmpty() } ?: return null
        val key = live.optString("key").takeIf { it.isNotEmpty() && it != "null" } ?: ""
        val event = live.optString("event").takeIf { it in listOf("start", "update", "end") } ?: "update"

        val time = { name: String -> Iso.parse(live.opt(name)?.takeIf { it != JSONObject.NULL }?.toString()) }

        return Live(type, key, event, live.optJSONObject("state") ?: JSONObject(), time("dismissAt"), time("staleAt"), live.optBoolean("alert", false))
    }

    /** `endsAt` of the state: seconds since 1970 (as on iOS) or ISO 8601. */
    private fun endsAt(state: JSONObject): Long? = state.opt("endsAt")?.takeIf { it != JSONObject.NULL }?.let { value ->
        if (value is Number) (value.toDouble() * 1000).toLong() else Iso.parse(value.toString())
    }

    private fun steps(state: JSONObject): List<String> = state.optJSONArray("steps")?.let { list -> (0 until list.length()).map { list.optString(it) } } ?: emptyList()

    /** 0–100: the progress of the state, or the step of the steps. */
    private fun percent(state: JSONObject): Int? {
        if (state.has("progress") && !state.isNull("progress")) {
            return (state.optDouble("progress", 0.0).coerceIn(0.0, 1.0) * 100).roundToInt()
        }

        val steps = steps(state)
        val step = state.optInt("step", 0)

        return if (steps.isNotEmpty() && step > 0) (step.coerceAtMost(steps.size) * 100.0 / steps.size).roundToInt() else null
    }

    private fun post(context: Context, data: Map<String, String>, live: Live, ongoing: Boolean, completed: Boolean, timeout: Long?, alert: Boolean) {
        val manager = NotificationManagerCompat.from(context)

        if (!manager.areNotificationsEnabled()) {
            return
        }

        val state = live.state
        val steps = steps(state)
        val step = state.optInt("step", 0)
        val title = state.optString("title")
        val status = if (completed) context.getString(R.string.onetrace_live_completed) else state.optString("status").takeIf { it.isNotEmpty() && it != "null" }
            ?: steps.getOrNull(step - 1).orEmpty()
        val stale = !completed && live.staleAt != null && live.staleAt <= System.currentTimeMillis() + 1000
        val stepText = listOfNotNull(
            if (steps.isNotEmpty() && step > 0) context.getString(R.string.onetrace_live_step, step.coerceAtMost(steps.size), steps.size) else null,
            if (stale) context.getString(R.string.onetrace_live_stale) else null,
        ).joinToString(" · ").takeIf { it.isNotEmpty() }
        // «Notify»: the messages channel of high importance gives the banner and the sound of an ordinary push.
        val channel = if (alert) PushChannels.messages(context) else PushChannels.live(context)
        val endsAt = endsAt(state)?.takeIf { !completed && it > System.currentTimeMillis() }
        val percent = if (completed) 100 else percent(state)
        val content = RichNotifications.launch(context, data, live.tag.hashCode(), 0, emptyMap())
        val delete = broadcast(context, live, PushActionReceiver.LIVE_DISMISSED)

        val notification = if (Build.VERSION.SDK_INT >= 36) {
            liveUpdate(context, channel, title, status, stepText, steps.size, percent, endsAt, ongoing, timeout, alert, content, delete)
        } else {
            NotificationCompat.Builder(context, channel)
                .setSmallIcon(PushChannels.icon(context))
                .setContentTitle(title)
                .setContentText(status)
                .setSubText(stepText)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .setOnlyAlertOnce(!alert)
                .setDeleteIntent(delete)
                .apply {
                    if (alert) {
                        setPriority(NotificationCompat.PRIORITY_HIGH).setDefaults(NotificationCompat.DEFAULT_ALL)
                    }
                    content?.let(::setContentIntent)
                    percent?.let { setProgress(100, it, false) }
                    endsAt?.let { setWhen(it).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true) }
                    timeout?.let { setTimeoutAfter(it) }
                }
                .build()
        }

        try {
            manager.notify(live.tag, ID, notification)
        } catch (e: SecurityException) {
            // The notification permission was revoked between the check and the call.
        }
    }

    /** Android 16: the Live Update with the steps as progress segments, promoted to the status bar while ongoing. */
    @RequiresApi(36)
    @SuppressLint("MissingPermission")
    private fun liveUpdate(context: Context, channel: String, title: String, status: String, stepText: String?, stepCount: Int, percent: Int?, endsAt: Long?, ongoing: Boolean, timeout: Long?, alert: Boolean, content: PendingIntent?, delete: PendingIntent): Notification {
        val style = Notification.ProgressStyle().setStyledByProgress(true)

        if (stepCount > 1) {
            val length = 100 / stepCount
            style.setProgressSegments((1..stepCount).map { index -> Notification.ProgressStyle.Segment(if (index == stepCount) 100 - length * (stepCount - 1) else length) })
            style.setProgressPoints((1 until stepCount).map { Notification.ProgressStyle.Point(it * length) })
        }

        percent?.let { style.setProgress(it) }

        return Notification.Builder(context, channel)
            .setSmallIcon(PushChannels.icon(context))
            .setContentTitle(title)
            .setContentText(status)
            .setSubText(stepText)
            .setCategory(Notification.CATEGORY_STATUS)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(!alert)
            // Notification.EXTRA_REQUEST_PROMOTED_ONGOING: public from API 36.1, read by Android 16.
            .addExtras(android.os.Bundle().apply { putBoolean("android.requestPromotedOngoing", ongoing) })
            .setShortCriticalText(status.take(12).takeIf { it.isNotEmpty() })
            .setStyle(style)
            .setDeleteIntent(delete)
            .apply {
                content?.let(::setContentIntent)
                endsAt?.let { setWhen(it).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true) }
                timeout?.let { setTimeoutAfter(it) }
            }
            .build()
    }

    private fun scheduleCompletion(context: Context, live: Live) {
        endsAt(live.state)?.let { alarm(context, it, broadcast(context, live, PushActionReceiver.LIVE_COMPLETED)) }
    }

    private fun scheduleStale(context: Context, live: Live) {
        val pending = broadcast(context, live, PushActionReceiver.LIVE_STALE)

        if (live.staleAt == null) {
            // A fresh state without a stale time: the earlier one no longer applies.
            context.getSystemService(AlarmManager::class.java).cancel(pending)
            return
        }

        alarm(context, live.staleAt, pending)
    }

    private fun alarm(context: Context, at: Long, pending: PendingIntent) {
        if (at <= System.currentTimeMillis()) {
            return
        }

        val alarms = context.getSystemService(AlarmManager::class.java)

        if (Build.VERSION.SDK_INT >= 23) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC, at, pending)
        } else {
            alarms.set(AlarmManager.RTC, at, pending)
        }
    }

    private fun broadcast(context: Context, live: Live, action: String): PendingIntent {
        val intent = Intent(context, PushActionReceiver::class.java).setAction(action)
            .setData(Uri.parse("onetrace://live/${Uri.encode(live.type)}/${Uri.encode(live.key)}/$action"))
            .putExtra(EXTRA_TYPE, live.type)
            .putExtra(EXTRA_KEY, live.key)

        return PendingIntent.getBroadcast(context, live.tag.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("pro.onetrace.sdk.live", Context.MODE_PRIVATE)
}
