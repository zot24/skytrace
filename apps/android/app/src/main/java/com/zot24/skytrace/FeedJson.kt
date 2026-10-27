package com.zot24.skytrace

import org.json.JSONObject

data class FeedBody(
    val src: String,
    val stale: Boolean,
    val retryAfterMs: Long,
    val rows: List<PlaneRow>,
)

/**
 * An adsb.lol v2 response. A plane's age is its `seen_pos` plus however long the response sat in caches
 * since adsb.lol's `now`, capped so a fast phone clock cannot age everything out.
 */
fun parseFeed(json: String, receivedAt: Long = System.currentTimeMillis()): FeedBody? {
    val body = try {
        JSONObject(json)
    } catch (_: Exception) {
        return null
    }
    val list = body.optJSONArray("ac") ?: return null
    val transitMs = body.optLong("now", 0L).let { if (it > 0L) (receivedAt - it).coerceIn(0L, MAX_TRANSIT_MS) else 0L }
    val rows = ArrayList<PlaneRow>(list.length())
    for (i in 0 until list.length()) {
        val ac = list.optJSONObject(i) ?: continue
        val hex = ac.optString("hex").trim().lowercase()
        if (hex.isEmpty()) continue
        val last = ac.optJSONObject("lastPosition")
        val hasPos = ac.has("lat") && ac.has("lon")
        val lat = if (hasPos) ac.optDouble("lat") else last?.optDouble("lat") ?: Double.NaN
        val lon = if (hasPos) ac.optDouble("lon") else last?.optDouble("lon") ?: Double.NaN
        if (!lat.isFinite() || !lon.isFinite()) continue
        val seenPos = (if (hasPos) ac.optDouble("seen_pos", 0.0) else last?.optDouble("seen_pos", 0.0) ?: 0.0)
        val baro = ac.opt("alt_baro")
        rows.add(
            PlaneRow(
                hex = hex,
                callsign = ac.optString("flight").trim(),
                lat = lat,
                lon = lon,
                altFt = if (baro == "ground") 0.0 else (baro as? Number)?.toDouble() ?: ac.optDouble("alt_geom", 0.0),
                gsKt = ac.optDouble("gs", 0.0),
                trackDeg = ac.optDouble("track", ac.optDouble("true_heading", 0.0)),
                typeCode = ac.optString("t").trim(),
                reg = ac.optString("r").trim(),
                category = ac.optString("category").trim(),
                fixAtMs = receivedAt - transitMs - (seenPos * 1000).toLong(),
            ),
        )
    }
    return FeedBody(src = "adsb.lol", stale = false, retryAfterMs = 0L, rows = rows)
}

private const val MAX_TRANSIT_MS = 10_000L

/** adsb.lol's point query around the view's centre, wide enough for its corners; 250 nm is their maximum. */
fun planesUrl(base: String, south: Double, north: Double, west: Double, east: Double): String {
    val lat = (south + north) / 2.0
    val lon = (west + east) / 2.0
    val dLat = (north - south) / 2.0 * 60.0
    val dLon = kotlin.math.abs(east - west) / 2.0 * 60.0 * kotlin.math.cos(Math.toRadians(lat))
    val radius = kotlin.math.sqrt(dLat * dLat + dLon * dLon).coerceIn(5.0, 250.0)
    return "%s/v2/point/%.3f/%.3f/%.0f".format(java.util.Locale.US, base.trimEnd('/'), lat, lon, kotlin.math.ceil(radius))
}

fun nextPollDelay(fresh: Boolean, attempt: Int, retryAfterMs: Long): Long {
    if (fresh) return 5_000L + kotlin.random.Random.nextLong(0L, 801L)
    if (retryAfterMs > 0L) return retryAfterMs
    val exp = kotlin.math.min(60_000.0, 2_000.0 * Math.pow(2.0, kotlin.math.min(attempt - 1, 5).toDouble()))
    return kotlin.math.round(exp * (0.5 + Math.random() * 0.5)).toLong().coerceAtLeast(1L)
}
