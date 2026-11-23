package pro.onetrace.sdk.fcm

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage
import pro.onetrace.sdk.OneTrace
import pro.onetrace.sdk.OneTraceModules

/**
 * Firebase Cloud Messaging for OneTrace: the FCM token goes to the platform, OneTrace messages go to
 * [OneTrace.handlePushData], which draws carousels, countdowns, buttons and Live Updates and reports the message as
 * received. A message with a `notification` block in the background is shown by Android itself, as before.
 *
 * The bundled [OneTraceMessagingService] does all of it. If your app has its own `FirebaseMessagingService`, remove ours
 * from the manifest and call [onNewToken] and [onMessageReceived] from yours.
 */
public object OneTraceFcm {
    /** Notification channel of OneTrace notifications on Android 8+; override with a meta-data entry of this name. */
    public const val CHANNEL_META: String = "pro.onetrace.notification_channel"

    /** Sends the current FCM token to OneTrace; call once after `OneTrace.init()`. */
    @JvmStatic
    public fun register() {
        OneTraceModules.richPushHandled()
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token -> OneTrace.setPushToken(token, OneTrace.PushProvider.FCM) }
    }

    @JvmStatic
    public fun onNewToken(token: String) {
        OneTraceModules.richPushHandled()
        OneTrace.setPushToken(token, OneTrace.PushProvider.FCM)
    }

    /**
     * Handles a OneTrace message: draws it (in the foreground also one with a `notification` block), updates a Live
     * Update or ignores a silent check.
     *
     * @return false when the message is not from OneTrace and is yours to handle.
     */
    @JvmStatic
    public fun onMessageReceived(context: Context, message: RemoteMessage): Boolean {
        if (!OneTrace.isOneTraceNotification(message.data)) {
            return false
        }

        OneTraceModules.richPushHandled()

        return OneTrace.handlePushData(context, withNotification(message))
    }

    /**
     * Shows a notification that opens the app with the OneTrace data: the tap is reported and the link opened by the SDK.
     */
    @JvmStatic
    public fun show(context: Context, title: String, body: String, data: Map<String, String>) {
        OneTraceModules.showNotification(context, data + mapOf("cdp_title" to title, "cdp_body" to body))
    }

    /** The title and text of a `notification` block (an installation without rich push in the foreground). */
    private fun withNotification(message: RemoteMessage): Map<String, String> {
        val notification = message.notification ?: return message.data
        val texts = mutableMapOf<String, String>()
        notification.title?.let { texts["cdp_title"] = it }
        notification.body?.let { texts["cdp_body"] = it }
        notification.imageUrl?.let { texts["cdp_image"] = it.toString() }

        return texts + message.data
    }
}
