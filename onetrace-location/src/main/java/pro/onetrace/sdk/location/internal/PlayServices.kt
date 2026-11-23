package pro.onetrace.sdk.location.internal

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/** Geofences of Google Play services (`GeofencingClient`): the transitions come to [GeofenceTransitionReceiver]. */
internal object PlayGeofenceRegistrar : GeofenceRegistrar {
    fun available(context: Context): Boolean = try {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    } catch (e: Exception) {
        false
    }

    /** Blocks the geofence thread until the system answers: the next set never overlaps this one. */
    @SuppressLint("MissingPermission")
    override fun register(context: Context, zones: List<GeofenceZone>, region: Region?): Boolean {
        val client = LocationServices.getGeofencingClient(context)
        val pending = pendingIntent(context)
        val fences = zones.map { zone ->
            Geofence.Builder()
                .setRequestId(Geofences.ZONE_PREFIX + zone.id)
                .setCircularRegion(zone.latitude, zone.longitude, zone.radius.toFloat())
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT or Geofence.GEOFENCE_TRANSITION_DWELL)
                // The system DWELL transition comes after the zone's «dwelled» threshold inside it.
                .setLoiteringDelay((zone.dwellSeconds * 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                .build()
        } + listOfNotNull(region?.let {
            Geofence.Builder()
                .setRequestId(Geofences.REGION_ID)
                .setCircularRegion(it.latitude, it.longitude, it.radius.toFloat())
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                .build()
        })

        return try {
            Tasks.await(client.removeGeofences(pending), TIMEOUT_SECONDS, TimeUnit.SECONDS)

            if (fences.isNotEmpty()) {
                val request = GeofencingRequest.Builder().setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER).addGeofences(fences).build()
                Tasks.await(client.addGeofences(request, pending), TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }

            true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (e: Exception) {
            // Location turned off (GEOFENCE_NOT_AVAILABLE), the permission revoked meanwhile, no answer: tried again on
            // the next resume of the app.
            false
        }
    }

    override fun clear(context: Context) {
        LocationServices.getGeofencingClient(context).removeGeofences(pendingIntent(context))
    }

    private const val TIMEOUT_SECONDS = 10L

    private fun pendingIntent(context: Context): PendingIntent {
        // Play services fill in the transition: the intent must be mutable on Android 12+.
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, 0, Intent(context, GeofenceTransitionReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or mutable)
    }
}

/** The last known position, or one fix at balanced accuracy when there is none; used on the device only. */
internal class PlayLocationSource(private val context: Context) : LocationSource {
    @SuppressLint("MissingPermission")
    override fun current(done: (latitude: Double, longitude: Double) -> Unit) {
        val client = LocationServices.getFusedLocationProviderClient(context)

        try {
            client.lastLocation.addOnSuccessListener { last ->
                if (last != null) {
                    done(last.latitude, last.longitude)
                } else {
                    client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).addOnSuccessListener { fix -> fix?.let { done(it.latitude, it.longitude) } }
                }
            }
        } catch (e: SecurityException) {
            // No permission: no selection until it is granted.
        }
    }
}
