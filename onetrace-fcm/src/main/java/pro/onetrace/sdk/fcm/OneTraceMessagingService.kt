package pro.onetrace.sdk.fcm

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * The FCM service of the SDK: token updates and OneTrace notifications. Messages from other senders are left to
 * [onOtherMessage] — override it to keep handling them.
 */
public open class OneTraceMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        OneTraceFcm.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!OneTraceFcm.onMessageReceived(this, message)) {
            onOtherMessage(message)
        }
    }

    /** A message that is not from OneTrace. */
    public open fun onOtherMessage(message: RemoteMessage): Unit = Unit
}
