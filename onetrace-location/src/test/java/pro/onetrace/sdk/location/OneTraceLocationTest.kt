package pro.onetrace.sdk.location

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import pro.onetrace.sdk.OneTraceModules
import pro.onetrace.sdk.location.internal.GeofenceBootReceiver
import pro.onetrace.sdk.location.internal.GeofenceRegistrar
import pro.onetrace.sdk.location.internal.GeofenceZone
import pro.onetrace.sdk.location.internal.Geofences
import pro.onetrace.sdk.location.internal.LocationSource
import pro.onetrace.sdk.location.internal.Region

@RunWith(RobolectricTestRunner::class)
class OneTraceLocationTest {
    private class FakeRegistrar : GeofenceRegistrar {
        val registered = mutableListOf<List<Int>>()
        var regions = mutableListOf<Region?>()
        var cleared = 0

        override fun register(context: Context, zones: List<GeofenceZone>, region: Region?) {
            registered.add(zones.map { it.id })
            regions.add(region)
        }

        override fun clear(context: Context) {
            cleared++
        }
    }

    private lateinit var app: Application
    private val registrar = FakeRegistrar()
    private val fetches = mutableListOf<Pair<String, String?>>()
    private val tracked = mutableListOf<Pair<String, Map<String, Any?>>>()
    private var zones = JSONArray()
    private var version = "v1"
    private var online = true
    private var now = 1_800_000_000_000L

    private fun zone(id: Int, latitude: Double) = JSONObject().put("id", id).put("externalId", "store_$id").put("name", "Store $id")
        .put("latitude", latitude).put("longitude", 37.0).put("radius", 150).put("dwellSeconds", 300)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        zones = JSONArray().put(zone(1, 55.0)).put(zone(2, 55.01)).put(zone(3, 55.02))
        Geofences.executor = java.util.concurrent.Executor { it.run() }
        Geofences.registrar = { registrar }
        Geofences.locations = { LocationSource { done -> done(55.0, 37.0) } }
        Geofences.playServices = { true }
        Geofences.initialized = { true }
        Geofences.clock = { now }
        Geofences.track = { event, properties -> tracked.add(event to properties) }
        Geofences.api = { path, etag ->
            fetches.add(path to etag)
            when {
                !online -> OneTraceModules.Response(0, "", null)
                etag == version -> OneTraceModules.Response(304, "", version)
                else -> OneTraceModules.Response(200, JSONObject().put("version", version).put("refreshHours", 12).put("geofences", zones).toString(), version)
            }
        }
    }

    @After
    fun tearDown() {
        Geofences.resetForTests()
    }

    private fun grant(vararg permissions: String) = shadowOf(app).grantPermissions(*permissions)

    @Test
    fun withoutTheBackgroundPermissionNothingIsRegisteredAndTheReasonIsReturned() {
        assertEquals(GeofenceStatus.LOCATION_PERMISSION_MISSING, OneTraceLocation.enableGeofences(app))

        grant(Manifest.permission.ACCESS_FINE_LOCATION)
        assertEquals(GeofenceStatus.BACKGROUND_PERMISSION_MISSING, OneTraceLocation.enableGeofences(app))

        assertTrue(registrar.registered.isEmpty())
        assertTrue(fetches.isEmpty())
    }

    @Test
    fun theZonesAreEnabledOnceThePermissionIsGrantedWithoutARestart() {
        grant(Manifest.permission.ACCESS_FINE_LOCATION)
        OneTraceLocation.enableGeofences(app)

        grant(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        Geofences.resumed(app)

        assertEquals(listOf(listOf(1, 2, 3)), registrar.registered)
        assertTrue(fetches.single().first.startsWith("/geofences?appId=") && fetches.single().first.endsWith("&platform=android"))
    }

    @Test
    fun theListIsFetchedEveryTwelveHoursWithItsEtagAndARemovedZoneStopsBeingMonitored() {
        grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        assertEquals(GeofenceStatus.ENABLED, OneTraceLocation.enableGeofences(app))
        assertEquals(1, fetches.size)

        now += 3 * 3600_000L
        Geofences.resumed(app)
        assertEquals(1, fetches.size)

        // Zone 2 was removed in the platform.
        zones = JSONArray().put(zone(1, 55.0)).put(zone(3, 55.02))
        version = "v2"
        now += 10 * 3600_000L
        Geofences.resumed(app)

        assertEquals("v1", fetches.last().second)
        assertEquals(listOf(1, 3), registrar.registered.last())
    }

    @Test
    fun theZonesAreRegisteredAgainAfterARestartOfThePhone() {
        grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        OneTraceLocation.enableGeofences(app)
        registrar.registered.clear()
        online = false

        GeofenceBootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals(listOf(listOf(1, 2, 3)), registrar.registered)
    }

    @Test
    fun nothingIsRegisteredAfterARestartWhenTheZonesAreOff() {
        grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        OneTraceLocation.enableGeofences(app)
        OneTraceLocation.disableGeofences(app)
        registrar.registered.clear()

        GeofenceBootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertTrue(registrar.registered.isEmpty())
        assertEquals(1, registrar.cleared)
    }

    @Test
    fun transitionsBecomeZoneEventsWithoutCoordinates() {
        grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        OneTraceLocation.enableGeofences(app)

        Geofences.transition(app, Geofences.ENTER, listOf("onetrace:2"), 55.01, 37.0)
        now += 600_000
        Geofences.transition(app, Geofences.EXIT, listOf("onetrace:2"), 55.03, 37.0)

        assertEquals(listOf("geofence_entered", "geofence_exited"), tracked.map { it.first })
        assertEquals(mapOf("geofence_id" to 2, "geofence_external_id" to "store_2", "geofence_name" to "Store 2", "dwell_seconds" to 600L), tracked[1].second)
        assertFalse(tracked.any { (_, properties) -> properties.keys.any { it.contains("lat") || it.contains("lon") } })
    }

    @Test
    fun leavingTheUpdateRegionSelectsTheNearestZonesAgain() {
        zones = JSONArray().apply { (1..200).forEach { put(zone(it, 55.0 + it * 0.001)) } }
        grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        OneTraceLocation.maxMonitored = 20
        OneTraceLocation.enableGeofences(app)
        assertEquals((1..20).toList(), registrar.registered.last().sorted())

        Geofences.transition(app, Geofences.EXIT, listOf(Geofences.REGION_ID), 55.1505, 37.0)

        assertEquals((141..160).toList(), registrar.registered.last().sorted())
        assertTrue(tracked.isEmpty())
    }
}
