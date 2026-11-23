package pro.onetrace.sdk.location.internal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The system forgets geofences on a restart of the phone and on an app update: the SDK registers them again. */
internal class GeofenceBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Geofences.reregister(context.applicationContext ?: context)
        }
    }
}
