package pro.onetrace.sdk.location.internal

import org.json.JSONArray
import org.json.JSONObject

/** A zone from `GET /geofences`: a circle with its platform id, external id, name and the «dwelled» threshold. */
internal data class GeofenceZone(
    val id: Int,
    val externalId: String?,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Int,
    val dwellSeconds: Int,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("externalId", externalId ?: JSONObject.NULL).put("name", name)
        .put("latitude", latitude).put("longitude", longitude).put("radius", radius).put("dwellSeconds", dwellSeconds)

    companion object {
        fun list(array: JSONArray?): List<GeofenceZone> = (0 until (array?.length() ?: 0)).mapNotNull { index ->
            val item = array!!.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optInt("id", -1).takeIf { it > 0 } ?: return@mapNotNull null
            val latitude = item.optDouble("latitude", Double.NaN)
            val longitude = item.optDouble("longitude", Double.NaN)
            val radius = item.optInt("radius", 0)

            if (latitude.isNaN() || longitude.isNaN() || radius <= 0) {
                return@mapNotNull null
            }

            GeofenceZone(id, item.optString("externalId").takeIf { item.has("externalId") && !item.isNull("externalId") }, item.optString("name"),
                latitude, longitude, radius, item.optInt("dwellSeconds", 300).coerceAtLeast(1))
        }
    }
}
