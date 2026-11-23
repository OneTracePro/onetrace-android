package pro.onetrace.sdk.location.internal

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import pro.onetrace.sdk.OneTrace
import pro.onetrace.sdk.OneTraceModules
import pro.onetrace.sdk.location.GeofenceStatus
import pro.onetrace.sdk.location.OneTraceLocation
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** The device position for selecting the nearest zones; not called back when it is unknown. */
internal fun interface LocationSource {
    fun current(done: (latitude: Double, longitude: Double) -> Unit)
}

/** Registers the monitored zones with the system (Google Play services), replacing the previous ones. */
internal interface GeofenceRegistrar {
    fun register(context: Context, zones: List<GeofenceZone>, region: Region?)

    fun clear(context: Context)
}

/**
 * The geofence engine: the list from `GET /geofences` (ETag, at launch and every `refreshHours`), the nearest zones
 * registered with the system, the transitions turned into `geofence_*` events through the SDK event queue. The device
 * position is used on the device only, to select the zones; it never goes to the platform.
 */
internal object Geofences {
    const val ENTER = 1
    const val EXIT = 2
    const val DWELL = 4
    const val ZONE_PREFIX = "onetrace:"
    const val REGION_ID = "onetrace:region"
    private const val HOUR = 3600_000L

    private val defaultExecutor by lazy { Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "onetrace-geofences").apply { isDaemon = true } } }
    private val defaultTrack: (String, Map<String, Any?>) -> Unit = { event, properties ->
        OneTrace.track(event, properties)
        OneTrace.flush()
    }

    // Seams for the tests; the defaults are Play services, the SDK event queue and the platform API.
    var executor: Executor = Executor { defaultExecutor.execute(it) }
    var registrar: (Context) -> GeofenceRegistrar = { PlayGeofenceRegistrar }
    var locations: (Context) -> LocationSource = { context -> PlayLocationSource(context) }
    var playServices: (Context) -> Boolean = PlayGeofenceRegistrar::available
    var initialized: () -> Boolean = { OneTraceModules.isInitialized }
    var clock: () -> Long = System::currentTimeMillis
    var track: (String, Map<String, Any?>) -> Unit = defaultTrack
    var api: (String, String?) -> OneTraceModules.Response? = OneTraceModules::get

    private val resumeListener = OneTraceModules.ResumeListener { activity -> resumed(activity.applicationContext) }

    fun enable(context: Context): GeofenceStatus {
        val app = context.applicationContext ?: context
        val prefs = prefs(app)
        prefs.edit().putBoolean(WANTED, true).apply()
        // A permission granted later (a prompt, the settings) turns the zones on when the app comes back.
        OneTraceModules.addResumeListener(resumeListener)
        OneTraceModules.locationPermissionChanged()
        val status = status(app)

        if (status == GeofenceStatus.ENABLED) {
            executor.execute { refresh(app, register = !prefs.getBoolean(ACTIVE, false)) }
        }

        return status
    }

    fun disable(context: Context) {
        val app = context.applicationContext ?: context
        prefs(app).edit().putBoolean(WANTED, false).putBoolean(ACTIVE, false).apply()
        OneTraceModules.removeResumeListener(resumeListener)
        executor.execute {
            registrar(app).clear(app)
            tracker(app).clear()
        }
    }

    /** An activity resumed: a permission granted meanwhile turns the zones on; an old list is fetched again. */
    fun resumed(context: Context) {
        val prefs = prefs(context)

        if (!prefs.getBoolean(WANTED, false)) {
            return
        }

        if (status(context) == GeofenceStatus.ENABLED) {
            executor.execute { refresh(context, register = !prefs.getBoolean(ACTIVE, false)) }
        } else {
            prefs.edit().putBoolean(ACTIVE, false).apply()
        }
    }

    /** After a restart of the phone or an app update the system has forgotten the zones: from the stored list. */
    fun reregister(context: Context) {
        val prefs = prefs(context)

        if (!prefs.getBoolean(WANTED, false) || status(context) != GeofenceStatus.ENABLED) {
            return
        }

        executor.execute { refresh(context, register = true) }
    }

    /** A system transition of the zones with these request ids; the position only selects zones again. */
    fun transition(context: Context, transition: Int, requestIds: List<String>, latitude: Double?, longitude: Double?) {
        executor.execute {
            val zones = zones(context)
            val byId = zones.associateBy { it.id }
            val tracker = tracker(context)
            val now = clock()

            requestIds.forEach { requestId ->
                if (requestId == REGION_ID) {
                    // Out of the update region: other zones may be the nearest now.
                    if (transition == EXIT && latitude != null && longitude != null) {
                        select(context, zones, latitude, longitude)
                    }
                    return@forEach
                }

                val zone = requestId.removePrefix(ZONE_PREFIX).toIntOrNull()?.let(byId::get) ?: return@forEach
                val event = when (transition) {
                    ENTER -> tracker.entered(zone, now)
                    DWELL -> tracker.dwelled(zone, now)
                    EXIT -> tracker.exited(zone, now)
                    else -> null
                }
                event?.let { track(it.name, it.properties) }
            }

            refresh(context, register = false)
        }
    }

    fun status(context: Context): GeofenceStatus {
        val granted = { permission: String -> ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED }

        return when {
            !granted(Manifest.permission.ACCESS_FINE_LOCATION) -> GeofenceStatus.LOCATION_PERMISSION_MISSING
            Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> GeofenceStatus.BACKGROUND_PERMISSION_MISSING
            !playServices(context) -> GeofenceStatus.PLAY_SERVICES_UNAVAILABLE
            !initialized() -> GeofenceStatus.NOT_INITIALIZED
            else -> GeofenceStatus.ENABLED
        }
    }

    /** Fetches the list when it is older than its refresh interval; registers the zones when it changed or [register]. */
    private fun refresh(context: Context, register: Boolean) {
        val prefs = prefs(context)
        val refreshHours = prefs.getInt(REFRESH_HOURS, 12).coerceAtLeast(1)
        var changed = false

        if (initialized() && clock() - prefs.getLong(FETCHED_AT, 0) >= refreshHours * HOUR) {
            changed = fetch(context, prefs)
        }

        if (changed || register) {
            val zones = zones(context)
            // A removed or switched off zone is no longer monitored, and its visit is forgotten.
            tracker(context).forget(zones.map { it.id }.toSet())
            prefs.edit().putBoolean(ACTIVE, true).apply()
            locate(context, zones)
        }
    }

    /** True when the list changed. */
    private fun fetch(context: Context, prefs: SharedPreferences): Boolean {
        val appId = OneTraceModules.appId ?: context.packageName
        val response = api("/geofences?appId=${Uri.encode(appId)}&platform=android", prefs.getString(VERSION, null)) ?: return false

        if (response.status == 304) {
            prefs.edit().putLong(FETCHED_AT, clock()).apply()
            return false
        }

        if (!response.successful) {
            return false
        }

        val body = runCatching { JSONObject(response.body) }.getOrNull() ?: return false
        val zones = GeofenceZone.list(body.optJSONArray("geofences"))
        prefs.edit()
            .putString(ZONES, JSONArray(zones.map { it.toJson() }).toString())
            .putString(VERSION, response.etag ?: body.optString("version"))
            .putInt(REFRESH_HOURS, body.optInt("refreshHours", 12))
            .putLong(FETCHED_AT, clock())
            .apply()

        return true
    }

    private fun locate(context: Context, zones: List<GeofenceZone>) {
        val prefs = prefs(context)

        if (zones.size <= OneTraceLocation.maxMonitored) {
            select(context, zones, 0.0, 0.0)
            return
        }

        var located = false
        locations(context).current { latitude, longitude ->
            located = true
            executor.execute { select(context, zones, latitude, longitude) }
        }

        // No position yet (a restart before the first fix): the place of the last selection.
        if (!located && prefs.contains(CENTER_LATITUDE)) {
            select(context, zones, prefs.getFloat(CENTER_LATITUDE, 0f).toDouble(), prefs.getFloat(CENTER_LONGITUDE, 0f).toDouble())
        }
    }

    private fun select(context: Context, zones: List<GeofenceZone>, latitude: Double, longitude: Double) {
        val selection = GeofenceSelector(OneTraceLocation.maxMonitored.coerceIn(1, 99)).select(zones, latitude, longitude)
        selection.region?.let { prefs(context).edit().putFloat(CENTER_LATITUDE, it.latitude.toFloat()).putFloat(CENTER_LONGITUDE, it.longitude.toFloat()).apply() }
        registrar(context).register(context, selection.zones, selection.region)
    }

    private fun zones(context: Context): List<GeofenceZone> =
        GeofenceZone.list(runCatching { JSONArray(prefs(context).getString(ZONES, "[]")) }.getOrNull())

    private fun tracker(context: Context): DwellTracker {
        val prefs = prefs(context)
        return DwellTracker(read = { prefs.getString(VISITS, null) }, write = { prefs.edit().putString(VISITS, it).apply() })
    }

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("pro.onetrace.sdk.geofences", Context.MODE_PRIVATE)

    fun resetForTests() {
        executor = Executor { defaultExecutor.execute(it) }
        registrar = { PlayGeofenceRegistrar }
        locations = { context -> PlayLocationSource(context) }
        playServices = PlayGeofenceRegistrar::available
        initialized = { OneTraceModules.isInitialized }
        clock = System::currentTimeMillis
        track = defaultTrack
        api = OneTraceModules::get
        OneTraceLocation.maxMonitored = 90
    }

    private const val WANTED = "wanted"
    private const val ACTIVE = "active"
    private const val ZONES = "zones"
    private const val VERSION = "version"
    private const val REFRESH_HOURS = "refresh_hours"
    private const val FETCHED_AT = "fetched_at"
    private const val CENTER_LATITUDE = "center_latitude"
    private const val CENTER_LONGITUDE = "center_longitude"
    private const val VISITS = "visits"
}
