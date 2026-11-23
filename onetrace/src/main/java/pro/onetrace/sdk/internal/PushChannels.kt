package pro.onetrace.sdk.internal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import pro.onetrace.sdk.R

/**
 * Channels and the icon of OneTrace notifications: `onetrace` for messages, `onetrace_live` for Live Updates; the app
 * sets its own with meta-data entries.
 */
internal object PushChannels {
    const val CHANNEL_META = "pro.onetrace.notification_channel"
    const val LIVE_CHANNEL_META = "pro.onetrace.live_channel"
    private const val DEFAULT = "onetrace"
    private const val LIVE = "onetrace_live"

    fun messages(context: Context): String = channel(context, CHANNEL_META, DEFAULT, R.string.onetrace_channel_name, NotificationManager.IMPORTANCE_HIGH)

    fun live(context: Context): String = channel(context, LIVE_CHANNEL_META, LIVE, R.string.onetrace_live_channel_name, NotificationManager.IMPORTANCE_DEFAULT)

    fun icon(context: Context): Int =
        metaData(context)?.getInt("com.google.firebase.messaging.default_notification_icon", 0)?.takeIf { it != 0 } ?: context.applicationInfo.icon

    private fun channel(context: Context, meta: String, id: String, name: Int, importance: Int): String {
        metaData(context)?.getString(meta)?.let { return it }

        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)

            if (manager.getNotificationChannel(id) == null) {
                manager.createNotificationChannel(NotificationChannel(id, context.getString(name), importance))
            }
        }

        return id
    }

    @Suppress("DEPRECATION")
    private fun metaData(context: Context): Bundle? = try {
        context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
