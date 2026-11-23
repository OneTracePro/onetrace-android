package pro.onetrace.sdk.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.onetrace.sdk.location.internal.GeofenceSelector
import pro.onetrace.sdk.location.internal.GeofenceZone
import kotlin.random.Random

class GeofenceSelectorTest {
    /** 1000 stores on a meridian, store i at i × 0.001° (≈ 111.2 m) north of the first one, in random order. */
    private val stores = (0 until 1000).map { GeofenceZone(it, "store_$it", "Store $it", 55.0 + it * 0.001, 37.0, 150, 300) }.shuffled(Random(7))

    @Test
    fun keepsTheNinetyNearestZonesAndAnUpdateRegionAroundThem() {
        val selection = GeofenceSelector(maxMonitored = 90).select(stores, 55.0, 37.0)

        assertEquals((0 until 90).toSet(), selection.zones.map { it.id }.toSet())
        val region = selection.region!!
        // The region reaches the farthest monitored store: 89 × 111.19 m.
        assertEquals(89 * 111.19, region.radius, 50.0)
        assertTrue(region.contains(55.005, 37.0))
        assertFalse(region.contains(55.5, 37.0))
    }

    @Test
    fun leavingTheRegionSelectsTheStoresAroundTheNewPlace() {
        val selector = GeofenceSelector(maxMonitored = 90)
        val first = selector.select(stores, 55.0, 37.0)
        val latitude = 55.50025
        assertFalse(first.region!!.contains(latitude, 37.0))

        val next = selector.select(stores, latitude, 37.0)

        // 500.25 is the middle: stores 456–545 are within 44.75 steps, 455 and 546 farther.
        assertEquals((456..545).toSet(), next.zones.map { it.id }.toSet())
        assertTrue(next.region!!.contains(latitude, 37.0))
    }

    @Test
    fun monitorsEveryZoneWithoutARegionWhenTheyAllFit() {
        val selection = GeofenceSelector(maxMonitored = 90).select(stores.take(12), 10.0, 10.0)

        assertEquals(12, selection.zones.size)
        assertNull(selection.region)
    }
}
