package pro.onetrace.sdk.location

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import pro.onetrace.sdk.location.internal.Geofences

/**
 * Geofences of OneTrace (**Apps → Geofences**): the SDK monitors the zones nearest to the device and sends
 * `geofence_entered`, `geofence_dwelled` and `geofence_exited` with the zone id, external id, name and the time spent
 * in it (`dwell_seconds`) — for journeys and segments. The device position never leaves the device.
 *
 * The SDK does not ask for the location permission itself: ask when it suits your app, for example with
 * [requestLocationPermission], and call [enableGeofences]; once the permission is granted the zones turn on without a
 * restart. A re-entry into the same zone within 30 minutes after the exit is not reported.
 */
public object OneTraceLocation {
    /** The request code of [requestLocationPermission] in `onRequestPermissionsResult`. */
    public const val REQUEST_CODE: Int = 0x4F54

    /**
     * How many of the nearest zones are monitored (Android allows 100 per app, one is the update region); set before
     * [enableGeofences].
     */
    @JvmStatic
    public var maxMonitored: Int = 90
        set(value) {
            field = value.coerceIn(1, 99)
        }

    /**
     * Turns geofences on: the list is fetched (at launch and every 12 hours) and the nearest zones registered. Without
     * the precise and, on Android 10+, the background location permission nothing is registered and the reason is
     * returned; the zones turn on by themselves once the permission is granted.
     */
    @JvmStatic
    public fun enableGeofences(context: Context): GeofenceStatus = Geofences.enable(context)

    /** Stops monitoring the zones; nothing is sent until [enableGeofences] is called again. */
    @JvmStatic
    public fun disableGeofences(context: Context) {
        Geofences.disable(context)
    }

    /**
     * Asks for the next missing location permission: the precise one first, then — as a separate step, call it again
     * after the answer — «Allow all the time» on Android 10+ (Android 11+ opens the settings for it).
     *
     * @return false when everything is granted and nothing was asked.
     */
    @JvmStatic
    @JvmOverloads
    public fun requestLocationPermission(activity: Activity, requestCode: Int = REQUEST_CODE): Boolean {
        val granted = { permission: String -> ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED }

        return when {
            !granted(Manifest.permission.ACCESS_FINE_LOCATION) -> {
                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), requestCode)
                true
            }
            Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> {
                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), requestCode)
                true
            }
            else -> false
        }
    }
}
