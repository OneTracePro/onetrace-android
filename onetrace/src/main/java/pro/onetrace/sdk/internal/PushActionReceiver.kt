package pro.onetrace.sdk.internal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import pro.onetrace.sdk.OneTrace

/**
 * Taps inside OneTrace notifications that do not open the app: carousel paging, «Close», «Remind me later» and the
 * snoozed notification coming back, a swiped Live Update and the end of its countdown.
 */
internal class PushActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val data = RichNotifications.data(intent.getStringExtra(RichNotifications.EXTRA_DATA))
        val id = intent.getIntExtra(RichNotifications.EXTRA_NOTIFICATION_ID, RichNotifications.notificationId(data))
        val client = OneTrace.currentClient

        when (intent.action) {
            PAGE -> RichNotifications.show(context, data, intent.getIntExtra(RichNotifications.EXTRA_INDEX, 0), cacheOnly = true)
            DISMISS -> NotificationManagerCompat.from(context).cancel(id)
            SNOOZE -> {
                NotificationManagerCompat.from(context).cancel(id)
                val button = PushButton.list(data[RichNotifications.BUTTONS]).firstOrNull { it.id == intent.getStringExtra(RichNotifications.EXTRA_BUTTON) } ?: return
                RichNotifications.snooze(context, data, button)
                data[Client.PUSH_TOKEN]?.let { token -> client?.actionTapped(token, button.id) }
            }
            // The images are in the cache unless it was cleared: then it comes back without them.
            SNOOZED -> RichNotifications.show(context, data, cacheOnly = true)
            LIVE_DISMISSED -> LiveUpdates.dismissed(context, intent.getStringExtra(LiveUpdates.EXTRA_TYPE) ?: return, intent.getStringExtra(LiveUpdates.EXTRA_KEY).orEmpty(), client)
            LIVE_COMPLETED -> LiveUpdates.completed(context, intent.getStringExtra(LiveUpdates.EXTRA_TYPE) ?: return, intent.getStringExtra(LiveUpdates.EXTRA_KEY).orEmpty())
        }
    }

    companion object {
        const val PAGE = "pro.onetrace.sdk.push.PAGE"
        const val DISMISS = "pro.onetrace.sdk.push.DISMISS"
        const val SNOOZE = "pro.onetrace.sdk.push.SNOOZE"
        const val SNOOZED = "pro.onetrace.sdk.push.SNOOZED"
        const val LIVE_DISMISSED = "pro.onetrace.sdk.push.LIVE_DISMISSED"
        const val LIVE_COMPLETED = "pro.onetrace.sdk.push.LIVE_COMPLETED"
    }
}
