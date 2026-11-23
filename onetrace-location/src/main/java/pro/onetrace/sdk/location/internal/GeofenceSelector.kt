package pro.onetrace.sdk.location.internal

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Android monitors at most 100 geofences per app: the SDK keeps the [maxMonitored] zones nearest to the device and an
 * «update region» around it that reaches the farthest of them. Leaving the region (or a new list) selects again.
 */
internal class GeofenceSelector(private val maxMonitored: Int) {
    fun select(zones: List<GeofenceZone>, latitude: Double, longitude: Double): Selection {
        if (zones.size <= maxMonitored) {
            return Selection(zones, null)
        }

        val nearest = zones.map { it to distance(latitude, longitude, it.latitude, it.longitude) }
            .sortedWith(compareBy<Pair<GeofenceZone, Double>> { it.second }.thenBy { it.first.id })
            .take(maxMonitored)
        val radius = (nearest.last().second).coerceAtLeast(MIN_REGION_RADIUS)

        return Selection(nearest.map { it.first }, Region(latitude, longitude, radius))
    }

    private companion object {
        /** The smallest radius the platform monitors reliably. */
        const val MIN_REGION_RADIUS = 200.0
    }
}

/** The zones to monitor and the region whose exit selects them again (null when every zone is monitored). */
internal class Selection(val zones: List<GeofenceZone>, val region: Region?)

internal class Region(val latitude: Double, val longitude: Double, val radius: Double) {
    fun contains(latitude: Double, longitude: Double): Boolean = distance(this.latitude, this.longitude, latitude, longitude) <= radius
}

/** The great-circle distance in metres. */
internal fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dPhi = Math.toRadians(lat2 - lat1)
    val dLambda = Math.toRadians(lon2 - lon1)
    val a = sin(dPhi / 2) * sin(dPhi / 2) + cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)

    return 2 * EARTH_RADIUS * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

private const val EARTH_RADIUS = 6_371_008.8
