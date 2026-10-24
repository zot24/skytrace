package com.zot24.skytrace

import org.json.JSONObject

data class FeedBody(
    val src: String,
    val stale: Boolean,
    val retryAfterMs: Long,
    val rows: List<PlaneRow>,
)

fun parseFeed(json: String): FeedBody? {
    val body = try {
        JSONObject(json)
    } catch (_: Exception) {
        return null
    }
    val rowsJson = body.optJSONArray("ac")
    val rows = ArrayList<PlaneRow>(rowsJson?.length() ?: 0)
    if (rowsJson != null) {
        for (i in 0 until rowsJson.length()) {
            val row = rowsJson.optJSONArray(i) ?: continue
            if (row.length() < 9) continue
            val hex = row.optString(0).trim().lowercase()
            if (hex.isEmpty()) continue
            rows.add(
                PlaneRow(
                    hex = hex,
                    callsign = row.optString(1).trim(),
                    lat = row.optDouble(2, Double.NaN),
                    lon = row.optDouble(3, Double.NaN),
                    altFt = row.optDouble(4, 0.0),
                    gsKt = row.optDouble(5, 0.0),
                    trackDeg = row.optDouble(6, 0.0),
                    typeCode = row.optString(7).trim(),
                    reg = row.optString(8).trim(),
                    category = if (row.length() > 9) row.optString(9).trim() else "",
                ),
            )
        }
    }
    return FeedBody(
        src = body.optString("src", "none"),
        stale = body.optBoolean("stale", false),
        retryAfterMs = body.optLong("retryAfterMs", 0L),
        rows = rows.filter { it.lat.isFinite() && it.lon.isFinite() },
    )
}

fun planesUrl(base: String, south: Double, north: Double, west: Double, east: Double): String {
    val root = base.trimEnd('/')
    val latSpan = north - south
    val lonSpan = kotlin.math.abs(east - west)
    if (latSpan > 12.0 || lonSpan > 18.0) return "$root/api/planes"
    fun q(n: Double) = ((kotlin.math.round(n * 2.0) / 2.0)).let { "%.1f".format(java.util.Locale.US, it) }
    return "$root/api/planes?lamin=${q(south)}&lamax=${q(north)}&lomin=${q(west)}&lomax=${q(east)}"
}

fun nextPollDelay(fresh: Boolean, attempt: Int, retryAfterMs: Long): Long {
    if (fresh) return 5_000L + kotlin.random.Random.nextLong(0L, 801L)
    if (retryAfterMs > 0L) return retryAfterMs
    val exp = kotlin.math.min(60_000.0, 2_000.0 * Math.pow(2.0, kotlin.math.min(attempt - 1, 5).toDouble()))
    return kotlin.math.round(exp * (0.5 + Math.random() * 0.5)).toLong().coerceAtLeast(1L)
}
