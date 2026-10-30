package pro.onetrace.sdk.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage
import pro.onetrace.sdk.OneTrace
import java.net.HttpURLConnection
import java.net.URL

/**
 * Firebase Cloud Messaging for OneTrace: the FCM token goes to the platform, OneTrace notifications are reported as
 * received and shown while the app is in the foreground (in the background Android shows them itself).
 *
 * The bundled [OneTraceMessagingService] does all of it. If your app has its own `FirebaseMessagingService`, remove ours
 * from the manifest and call [onNewToken] and [onMessageReceived] from yours.
 */
public object OneTraceFcm {
    /** Notification channel of OneTrace notifications on Android 8+; override with a meta-data entry of this name. */
    public const val CHANNEL_META: String = "pro.onetrace.notification_channel"

    private const val DEFAULT_CHANNEL = "onetrace"

    /** Sends the current FCM token to OneTrace; call once after `OneTrace.init()`. */
    @JvmStatic
    public fun register() {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token -> OneTrace.setPushToken(token, OneTrace.PushProvider.FCM) }
    }

    @JvmStatic
    public fun onNewToken(token: String) {
        OneTrace.setPushToken(token, OneTrace.PushProvider.FCM)
    }

    /**
     * Handles a OneTrace notification: reports it as received and shows it if the app is in the foreground.
     *
     * @return false when the message is not from OneTrace and is yours to handle.
     */
    @JvmStatic
    public fun onMessageReceived(context: Context, message: RemoteMessage): Boolean {
        if (!OneTrace.isOneTraceNotification(message.data)) {
            return false
        }

        OneTrace.notificationReceived(message.data)
        message.notification?.let { show(context, it.title.orEmpty(), it.body.orEmpty(), message.data) }

        return true
    }

    /**
     * Shows a notification that opens the app with the OneTrace data: the tap is reported and the link opened by the SDK.
     */
    @JvmStatic
    public fun show(context: Context, title: String, body: String, data: Map<String, String>) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return
        }

        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        data.forEach { (key, value) -> launch.putExtra(key, value) }

        val intent = PendingIntent.getActivity(context, data.hashCode(), launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, channel(context))
            .setSmallIcon(icon(context))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(intent)

        data["cdp_image"]?.let(::download)?.let { image ->
            builder.setLargeIcon(image).setStyle(NotificationCompat.BigPictureStyle().bigPicture(image).setSummaryText(body))
        }

        try {
            NotificationManagerCompat.from(context).notify(data["cdp_token"].hashCode(), builder.build())
        } catch (e: SecurityException) {
            // The notification permission was revoked between the check and the call.
        }
    }

    private fun channel(context: Context): String {
        val configured = metaData(context)?.getString(CHANNEL_META)

        if (configured != null) {
            return configured
        }

        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)

            if (manager.getNotificationChannel(DEFAULT_CHANNEL) == null) {
                manager.createNotificationChannel(NotificationChannel(DEFAULT_CHANNEL, context.getString(R.string.onetrace_channel_name), NotificationManager.IMPORTANCE_HIGH))
            }
        }

        return DEFAULT_CHANNEL
    }

    private fun icon(context: Context): Int =
        metaData(context)?.getInt("com.google.firebase.messaging.default_notification_icon", 0)?.takeIf { it != 0 } ?: context.applicationInfo.icon

    @Suppress("DEPRECATION")
    private fun metaData(context: Context) = try {
        context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun download(url: String): Bitmap? = try {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 10_000
            readTimeout = 10_000
            inputStream.use(BitmapFactory::decodeStream).also { disconnect() }
        }
    } catch (e: Exception) {
        null
    }
}
