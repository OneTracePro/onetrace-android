package pro.onetrace.sdk.location.internal

import org.json.JSONObject

/** A zone event for `OneTrace.track`: only the zone ids and the time in it, never coordinates. */
internal class GeofenceEvent(val name: String, val properties: Map<String, Any?>)

/**
 * Entries, «dwelled» and exits of the zones, with the time spent inside, kept on disk between launches:
 * - an exit carries `dwell_seconds` since the recorded entry; without one (the device was off) it has none;
 * - an entry older than 24 hours without an exit is dropped;
 * - an entry within 30 minutes after the exit of the same zone (GPS jitter at the border) is not written, nor is the
 *   rest of that visit;
 * - a repeated entry while inside (geofences registered again) is not written twice.
 */
internal class DwellTracker(private val read: () -> String?, private val write: (String) -> Unit) {
    @Synchronized
    fun entered(zone: GeofenceZone, now: Long): GeofenceEvent? = update(now) { inside, exits ->
        val key = zone.id.toString()

        when {
            inside.has(key) -> null
            exits.optLong(key, 0) > now - REENTRY_MS -> {
                inside.put(key, JSONObject().put("at", now).put("silent", true))
                null
            }
            else -> {
                inside.put(key, JSONObject().put("at", now))
                GeofenceEvent(ENTERED, ids(zone))
            }
        }
    }

    /** The system DWELL transition: the zone's threshold has passed inside it. Once per visit. */
    @Synchronized
    fun dwelled(zone: GeofenceZone, now: Long): GeofenceEvent? = update(now) { inside, _ ->
        val visit = inside.optJSONObject(zone.id.toString())

        when {
            visit == null -> GeofenceEvent(DWELLED, ids(zone) + ("dwell_seconds" to zone.dwellSeconds.toLong()))
            visit.optBoolean("silent") || visit.optBoolean("dwelled") -> null
            else -> {
                visit.put("dwelled", true)
                GeofenceEvent(DWELLED, ids(zone) + ("dwell_seconds" to (now - visit.getLong("at")) / 1000))
            }
        }
    }

    @Synchronized
    fun exited(zone: GeofenceZone, now: Long): GeofenceEvent? = update(now) { inside, exits ->
        val key = zone.id.toString()
        val visit = inside.optJSONObject(key)
        inside.remove(key)
        exits.put(key, now)

        when {
            visit == null -> GeofenceEvent(EXITED, ids(zone))
            visit.optBoolean("silent") -> null
            else -> GeofenceEvent(EXITED, ids(zone) + ("dwell_seconds" to (now - visit.getLong("at")) / 1000))
        }
    }

    /** Zones that left the list: their visits are forgotten. */
    @Synchronized
    fun forget(keep: Set<Int>) {
        update(0) { inside, exits ->
            listOf(inside, exits).forEach { json -> json.keys().asSequence().toList().filter { it.toIntOrNull() !in keep }.forEach(json::remove) }
            null
        }
    }

    @Synchronized
    fun clear() = write("{}")

    private fun update(now: Long, block: (inside: JSONObject, exits: JSONObject) -> GeofenceEvent?): GeofenceEvent? {
        val state = runCatching { JSONObject(read() ?: "{}") }.getOrDefault(JSONObject())
        val inside = state.optJSONObject("inside") ?: JSONObject()
        val exits = state.optJSONObject("exits") ?: JSONObject()

        if (now > 0) {
            inside.keys().asSequence().toList().filter { (inside.optJSONObject(it)?.optLong("at") ?: 0) < now - STALE_ENTRY_MS }.forEach(inside::remove)
            exits.keys().asSequence().toList().filter { exits.optLong(it) < now - REENTRY_MS }.forEach(exits::remove)
        }

        val event = block(inside, exits)
        write(JSONObject().put("inside", inside).put("exits", exits).toString())

        return event
    }

    private fun ids(zone: GeofenceZone): Map<String, Any?> = mapOf("geofence_id" to zone.id, "geofence_external_id" to zone.externalId, "geofence_name" to zone.name)

    companion object {
        const val ENTERED = "geofence_entered"
        const val DWELLED = "geofence_dwelled"
        const val EXITED = "geofence_exited"
        private const val REENTRY_MS = 30 * 60_000L
        private const val STALE_ENTRY_MS = 24 * 3600_000L
    }
}
