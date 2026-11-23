package pro.onetrace.sdk.location.internal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.GeofencingEvent

/** Transitions of the registered zones from Google Play services. */
internal class GeofenceTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return

        if (event.hasError()) {
            return
        }

        val ids = event.triggeringGeofences?.map { it.requestId } ?: return
        val location = event.triggeringLocation
        // Keeps the process alive until the events are queued.
        val pending = runCatching { goAsync() }.getOrNull()
        Geofences.transition(context.applicationContext ?: context, event.geofenceTransition, ids, location?.latitude, location?.longitude)
        Geofences.executor.execute { pending?.finish() }
    }
}
