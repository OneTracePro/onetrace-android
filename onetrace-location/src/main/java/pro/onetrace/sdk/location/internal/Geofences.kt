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
import pro.onetrace.sdk.OneTraceModules
import pro.onetrace.sdk.location.GeofenceStatus
import pro.onetrace.sdk.location.OneTraceLocation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The device position for selecting the nearest zones; not called back when it is unknown (any thread). */
internal fun interface LocationSource {
    fun current(done: (latitude: Double, longitude: Double) -> Unit)
}

/** Registers the monitored zones with the system (Google Play services), replacing the previous ones. */
internal interface GeofenceRegistrar {
    /** Blocks until the system answers; false when it refused the zones (location turned off, no permission). */
    fun register(context: Context, zones: List<GeofenceZone>, region: Region?): Boolean

    fun clear(context: Context)
}

/**
 * The geofence engine: the list from `GET /geofences` (ETag, at launch and every `refreshHours`), the nearest zones
 * registered with the system, the transitions turned into `geofence_*` events through the SDK event queue. The device
 * position is used on the device only, to select the zones; it never goes to the platform.
 *
 * Every change of the registered zones runs on [executor] one after another, one set per selection: Android allows 100
 * zones per app, so two sets in flight would exceed it. A transition before `OneTrace.init` (a background start of a
 * Flutter or React Native app) is kept with its time and sent after init.
 */
internal object Geofences {
    const val ENTER = 1
    const val EXIT = 2
    const val DWELL = 4
    const val ZONE_PREFIX = "onetrace:"
    const val REGION_ID = "onetrace:region"
    private const val HOUR = 3600_000L

    private val defaultExecutor by lazy { Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "onetrace-geofences").apply { isDaemon = true } } }
    private val defaultTrack: (String, Map<String, Any?>, Long) -> Unit = OneTraceModules::track
    private const val MAX_PENDING = 100

    // Seams for the tests; the defaults are Play services, the SDK event queue and the platform API.
    var executor: Executor = Executor { defaultExecutor.execute(it) }
    var registrar: (Context) -> GeofenceRegistrar = { PlayGeofenceRegistrar }
    var locations: (Context) -> LocationSource = { context -> PlayLocationSource(context) }
    var playServices: (Context) -> Boolean = PlayGeofenceRegistrar::available
    var initialized: () -> Boolean = { OneTraceModules.isInitialized }
    var clock: () -> Long = System::currentTimeMillis
    var track: (String, Map<String, Any?>, Long) -> Unit = defaultTrack
    /** How long a selection waits for the position before it takes the place of the last one. */
    var locationTimeoutMillis = 20_000L
    var api: (String, String?) -> OneTraceModules.Response? = OneTraceModules::get

    private val resumeListener = OneTraceModules.ResumeListener { activity -> resumed(activity.applicationContext) }

    @Volatile
    private var pendingContext: Context? = null
    private val initListener = Runnable { pendingContext?.let { context -> executor.execute { sendPending(context) } } }

    fun enable(context: Context): GeofenceStatus {
        val app = context.applicationContext ?: context
        val prefs = prefs(app)
        prefs.edit().putBoolean(WANTED, true).apply()
        // A permission granted later (a prompt, the settings) turns the zones on when the app comes back.
        OneTraceModules.addResumeListener(resumeListener)
        OneTraceModules.locationPermissionChanged()
        val status = status(app)

        if (status == GeofenceStatus.ENABLED) {
            // Every call registers the zones: after a reinstall Auto Backup restores these settings, but the system
            // knows no zones of the new installation.
            executor.execute {
                sendPending(app)
                refresh(app, register = true)
            }
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
            executor.execute {
                sendPending(context)
                refresh(context, register = !prefs.getBoolean(ACTIVE, false))
            }
        } else {
            prefs.edit().putBoolean(ACTIVE, false).apply()
        }
    }

    /**
     * After a restart of the phone or an app update the system has forgotten the zones: from the stored list, without
     * `OneTrace.init` (the list is fetched again only after it).
     */
    fun reregister(context: Context) {
        val prefs = prefs(context)

        if (!prefs.getBoolean(WANTED, false) || permissions(context) != null) {
            return
        }

        executor.execute { refresh(context, register = true) }
    }

    /** `GEOFENCE_NOT_AVAILABLE`: location was turned off and the system dropped every zone; registered again on resume. */
    fun unavailable(context: Context) {
        prefs(context).edit().putBoolean(ACTIVE, false).apply()
    }

    /** A system transition of the zones with these request ids; the position only selects zones again. */
    fun transition(context: Context, transition: Int, requestIds: List<String>, latitude: Double?, longitude: Double?) {
        executor.execute {
            val zones = zones(context)
            val byId = zones.associateBy { it.id }
            val tracker = tracker(context)
            val now = clock()

            var leftRegion = false

            requestIds.forEach { requestId ->
                if (requestId == REGION_ID) {
                    leftRegion = transition == EXIT
                    return@forEach
                }

                val zone = requestId.removePrefix(ZONE_PREFIX).toIntOrNull()?.let(byId::get) ?: return@forEach
                val event = when (transition) {
                    ENTER -> tracker.entered(zone, now)
                    DWELL -> tracker.dwelled(zone, now)
                    EXIT -> tracker.exited(zone, now)
                    else -> null
                }
                event?.let { record(context, it.name, it.properties, now) }
            }

            // Out of the update region: other zones may be the nearest now, selected at the position of the transition.
            if (leftRegion && latitude != null && longitude != null) {
                prefs(context).edit().putBoolean(ACTIVE, select(context, zones, latitude to longitude)).apply()
            }

            refresh(context, register = false)
        }
    }

    fun status(context: Context): GeofenceStatus = permissions(context) ?: if (initialized()) GeofenceStatus.ENABLED else GeofenceStatus.NOT_INITIALIZED

    /** Why the device cannot monitor the zones, or null when it can. */
    private fun permissions(context: Context): GeofenceStatus? {
        val granted = { permission: String -> ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED }

        return when {
            !granted(Manifest.permission.ACCESS_FINE_LOCATION) -> GeofenceStatus.LOCATION_PERMISSION_MISSING
            Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> GeofenceStatus.BACKGROUND_PERMISSION_MISSING
            !playServices(context) -> GeofenceStatus.PLAY_SERVICES_UNAVAILABLE
            else -> null
        }
    }

    /** A zone event: sent now, or kept until `OneTrace.init` with the time it happened. */
    private fun record(context: Context, event: String, properties: Map<String, Any?>, at: Long) {
        if (initialized()) {
            sendPending(context)
            track(event, properties, at)
            return
        }

        val prefs = prefs(context)
        val pending = runCatching { JSONArray(prefs.getString(PENDING, "[]")) }.getOrDefault(JSONArray())
        pending.put(JSONObject().put("event", event).put("properties", JSONObject(properties)).put("at", at))
        val kept = JSONArray((maxOf(0, pending.length() - MAX_PENDING) until pending.length()).map { pending.get(it) })
        prefs.edit().putString(PENDING, kept.toString()).apply()
        pendingContext = context.applicationContext ?: context
        OneTraceModules.addInitListener(initListener)
    }

    private fun sendPending(context: Context) {
        val prefs = prefs(context)
        val stored = prefs.getString(PENDING, null) ?: return

        if (!initialized()) {
            return
        }

        prefs.edit().remove(PENDING).apply()
        val pending = runCatching { JSONArray(stored) }.getOrDefault(JSONArray())

        (0 until pending.length()).mapNotNull { pending.optJSONObject(it) }.forEach { item ->
            val properties = item.optJSONObject("properties") ?: JSONObject()
            track(item.optString("event"), properties.keys().asSequence().associateWith { properties.opt(it) }, item.optLong("at"))
        }
    }

    /**
     * Fetches the list when it is older than its refresh interval (after `OneTrace.init` only); registers the zones when
     * it changed or [register].
     */
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
            // Not active when the system refused them: the next resume tries again.
            prefs.edit().putBoolean(ACTIVE, locate(context, zones)).apply()
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

        // A refusal that the next request would get too (402 without the plan's feature, other 4xx but 429): asked again
        // after the refresh interval, not on every launch.
        if (response.status in 400..499 && response.status != 429) {
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

    /** Registers one selection: at the current position, or the place of the last one without it. True when registered. */
    private fun locate(context: Context, zones: List<GeofenceZone>): Boolean {
        val prefs = prefs(context)

        if (zones.size <= OneTraceLocation.maxMonitored) {
            return select(context, zones, null)
        }

        // No position yet (a restart before the first fix): the place of the last selection.
        val position = position(context)
            ?: prefs.takeIf { it.contains(CENTER_LATITUDE) }?.let { it.getFloat(CENTER_LATITUDE, 0f).toDouble() to it.getFloat(CENTER_LONGITUDE, 0f).toDouble() }
            ?: return false

        return select(context, zones, position)
    }

    /** Waits for the position on the geofence thread, at most [locationTimeoutMillis]; a later answer is ignored. */
    private fun position(context: Context): Pair<Double, Double>? {
        val result = AtomicReference<Pair<Double, Double>?>()
        val latch = CountDownLatch(1)
        locations(context).current { latitude, longitude ->
            if (result.compareAndSet(null, latitude to longitude)) {
                latch.countDown()
            }
        }

        return try {
            if (latch.await(locationTimeoutMillis, TimeUnit.MILLISECONDS)) result.get() else null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun select(context: Context, zones: List<GeofenceZone>, position: Pair<Double, Double>?): Boolean {
        val selection = GeofenceSelector(OneTraceLocation.maxMonitored.coerceIn(1, 99)).select(zones, position?.first ?: 0.0, position?.second ?: 0.0)
        selection.region?.let { prefs(context).edit().putFloat(CENTER_LATITUDE, it.latitude.toFloat()).putFloat(CENTER_LONGITUDE, it.longitude.toFloat()).apply() }
        return registrar(context).register(context, selection.zones, selection.region)
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
        locationTimeoutMillis = 20_000L
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
    private const val PENDING = "pending_events"
}
