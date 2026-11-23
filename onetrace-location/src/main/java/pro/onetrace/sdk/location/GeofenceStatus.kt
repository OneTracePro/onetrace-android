package pro.onetrace.sdk.location

/** The result of [OneTraceLocation.enableGeofences]: anything but [ENABLED] is the reason the zones are not monitored. */
public enum class GeofenceStatus {
    /** The zones are monitored; the list loads in the background. */
    ENABLED,

    /** No precise location permission (`ACCESS_FINE_LOCATION`). */
    LOCATION_PERMISSION_MISSING,

    /** Android 10+ without «Allow all the time» (`ACCESS_BACKGROUND_LOCATION`). */
    BACKGROUND_PERMISSION_MISSING,

    /** Google Play services are missing or outdated on the device. */
    PLAY_SERVICES_UNAVAILABLE,

    /** `OneTrace.init()` was not called yet. */
    NOT_INITIALIZED,
}
