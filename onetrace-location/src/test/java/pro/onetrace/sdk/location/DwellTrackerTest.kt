package pro.onetrace.sdk.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pro.onetrace.sdk.location.internal.DwellTracker
import pro.onetrace.sdk.location.internal.GeofenceZone

/** Robolectric for `org.json` only: the tracker is plain Kotlin. */
@RunWith(RobolectricTestRunner::class)
class DwellTrackerTest {
    private var saved: String? = null
    private val tracker = DwellTracker(read = { saved }, write = { saved = it })
    private val store = GeofenceZone(15, "store_15", "Store on Lenina", 55.75, 37.61, 150, 300)
    private val minute = 60_000L
    private val start = 1_800_000_000_000L

    @Test
    fun theExitCarriesTheTimeSinceTheEntry() {
        val entered = tracker.entered(store, start)!!
        assertEquals("geofence_entered", entered.name)
        assertEquals(mapOf("geofence_id" to 15, "geofence_external_id" to "store_15", "geofence_name" to "Store on Lenina"), entered.properties)

        val dwelled = tracker.dwelled(store, start + 5 * minute)!!
        assertEquals("geofence_dwelled", dwelled.name)
        assertEquals(300L, dwelled.properties["dwell_seconds"])

        val exited = tracker.exited(store, start + 12 * minute)!!
        assertEquals("geofence_exited", exited.name)
        assertEquals(720L, exited.properties["dwell_seconds"])
        // Only the zone ids: no coordinates of the device or the zone.
        assertEquals(setOf("geofence_id", "geofence_external_id", "geofence_name", "dwell_seconds"), exited.properties.keys)
    }

    @Test
    fun anExitWithoutARecordedEntryHasNoDwellTime() {
        val exited = tracker.exited(store, start)!!

        assertEquals("geofence_exited", exited.name)
        assertFalse(exited.properties.containsKey("dwell_seconds"))
    }

    @Test
    fun anEntryOlderThanADayWithoutAnExitIsDropped() {
        tracker.entered(store, start)
        val exited = tracker.exited(store, start + 25 * 60 * minute)!!

        assertFalse(exited.properties.containsKey("dwell_seconds"))
    }

    @Test
    fun aReEntryWithinThirtyMinutesOfTheExitIsNotWritten() {
        tracker.entered(store, start)
        tracker.exited(store, start + 10 * minute)

        assertNull(tracker.entered(store, start + 20 * minute))
        // The jitter visit stays silent to its end.
        assertNull(tracker.exited(store, start + 22 * minute))
        assertNull(tracker.entered(store, start + 40 * minute))
        assertNull(tracker.exited(store, start + 45 * minute))

        // More than 30 minutes after the last exit: a new visit.
        val later = tracker.entered(store, start + 80 * minute)!!
        assertEquals("geofence_entered", later.name)
    }

    @Test
    fun aRepeatedEntryWhileInsideIsNotWrittenTwice() {
        tracker.entered(store, start)

        assertNull(tracker.entered(store, start + minute))
        assertEquals(120L, tracker.exited(store, start + 2 * minute)!!.properties["dwell_seconds"])
    }

    @Test
    fun theStateSurvivesARestart() {
        tracker.entered(store, start)
        val restarted = DwellTracker(read = { saved }, write = { saved = it })

        assertEquals(60L, restarted.exited(store, start + minute)!!.properties["dwell_seconds"])
    }
}
