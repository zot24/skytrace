package com.zot24.skytrace

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

const val COAST_MS = 60_000L
const val FADE_MS = 10_000L
const val EASE_MS = 1_000L

data class PlaneRow(
    val hex: String,
    val callsign: String,
    val lat: Double,
    val lon: Double,
    val altFt: Double,
    val gsKt: Double,
    val trackDeg: Double,
    val typeCode: String,
    val reg: String,
    val category: String,
)

data class VisiblePlane(
    val hex: String,
    val callsign: String,
    val typeCode: String,
    val reg: String,
    val altFt: Double,
    val gsKt: Double,
    val trackDeg: Double,
    val lat: Double,
    val lon: Double,
    val opacity: Float,
    val silhouette: Silhouette,
)

fun project(lat0: Double, lon0: Double, gsKt: Double, trackDeg: Double, dtSec: Double): Pair<Double, Double> {
    if (dtSec == 0.0 || gsKt <= 0.0) return lat0 to lon0
    val distNm = gsKt * (dtSec / 3600.0)
    val rad = Math.toRadians(trackDeg)
    val dLat = distNm * cos(rad) / 60.0
    val cosLat = cos(Math.toRadians(lat0))
    val dLon = distNm * sin(rad) / (60.0 * max(0.15, abs(cosLat)))
    var lat = lat0 + dLat
    var lon = lon0 + dLon
    if (lat > 85.0) lat = 85.0
    if (lat < -85.0) lat = -85.0
    while (lon > 180.0) lon -= 360.0
    while (lon < -180.0) lon += 360.0
    return lat to lon
}

private data class Craft(
    val row: PlaneRow,
    val fixAtMs: Long,
    val anchorLat: Double,
    val anchorLon: Double,
    val anchorTrack: Double,
    val anchorAtMs: Long,
)

class Fleet {
    private val craft = LinkedHashMap<String, Craft>()
    private val trails = HashMap<String, ArrayDeque<Triple<Double, Double, Long>>>()
    private val deadScratch = ArrayList<String>(8)

    fun accept(rows: List<PlaneRow>, now: Long, fresh: Boolean) {
        if (!fresh) {
            if (craft.isEmpty()) {
                for (row in rows) putNew(row, now)
            }
            return
        }
        val seen = HashSet<String>(rows.size)
        for (row in rows) {
            if (row.hex.isEmpty()) continue
            seen.add(row.hex)
            val existing = craft[row.hex]
            if (existing == null) {
                putNew(row, now)
            } else {
                val pose = pose(existing, now)
                val merged = existing.row.copy(
                    callsign = row.callsign.ifBlank { existing.row.callsign },
                    lat = row.lat,
                    lon = row.lon,
                    altFt = row.altFt,
                    gsKt = row.gsKt,
                    trackDeg = row.trackDeg,
                    typeCode = row.typeCode.ifBlank { existing.row.typeCode },
                    reg = row.reg.ifBlank { existing.row.reg },
                    category = row.category.ifBlank { existing.row.category },
                )
                craft[row.hex] = if (pose == null) {
                    Craft(merged, now, row.lat, row.lon, row.trackDeg, Long.MIN_VALUE)
                } else {
                    Craft(merged, now, pose.lat, pose.lon, pose.trackDeg, now)
                }
            }
        }
        val gone = craft.keys.filter { it !in seen }
        for (hex in gone) {
            craft.remove(hex)
            trails.remove(hex)
        }
    }

    fun tracked(now: Long): Int {
        prune(now)
        return craft.size
    }

    fun visible(
        now: Long,
        south: Double = -90.0,
        north: Double = 90.0,
        west: Double = -180.0,
        east: Double = 180.0,
        alwaysHex: String? = null,
    ): List<VisiblePlane> {
        prune(now)
        val out = ArrayList<VisiblePlane>(craft.size)
        for (item in craft.values) {
            val pose = pose(item, now) ?: continue
            val keep = item.row.hex == alwaysHex || inside(pose.lon, pose.lat, south, north, west, east)
            if (keep) out.add(pose)
        }
        return out
    }

    fun snapshotFor(hexes: Set<String>, now: Long): List<VisiblePlane> = hexes.mapNotNull { one(it, now) }

    fun one(hex: String, now: Long): VisiblePlane? {
        prune(now)
        val item = craft[hex] ?: return null
        return pose(item, now)
    }

    private fun prune(now: Long) {
        deadScratch.clear()
        for ((hex, item) in craft) {
            if (now - item.fixAtMs >= COAST_MS + FADE_MS) deadScratch.add(hex)
        }
        for (index in deadScratch.indices) {
            val hex = deadScratch[index]
            craft.remove(hex)
            trails.remove(hex)
        }
    }

    fun noteTrail(hex: String, lon: Double, lat: Double, now: Long) {
        val path = trails.getOrPut(hex) { ArrayDeque() }
        val prev = path.lastOrNull()
        if (prev != null) {
            var dLon = abs(prev.first - lon)
            if (dLon > 180.0) dLon = 360.0 - dLon
            if (dLon + abs(prev.second - lat) < 0.0015) return
        }
        path.addLast(Triple(lon, lat, now))
        while (path.size > 180) path.removeFirst()
    }

    fun trailSegments(hex: String, now: Long): List<List<Pair<Double, Double>>> {
        val item = craft[hex] ?: return emptyList()
        val pose = pose(item, now) ?: return emptyList()
        val stored = trails[hex].orEmpty()
        val real = ArrayList<Pair<Double, Double>>(stored.size + 1)
        for (point in stored) real.add(point.first to point.second)
        val tip = pose.lon to pose.lat
        if (real.isEmpty() || !same(real.last(), tip)) real.add(tip)
        val features = ArrayList<List<Pair<Double, Double>>>()
        val originT = stored.firstOrNull()?.third ?: item.fixAtMs
        val span = if (stored.isEmpty()) 0L else now - originT
        if (span < 150_000L && item.row.gsKt >= 40.0 && real.isNotEmpty()) {
            val origin = real.first()
            val back = project(origin.second, origin.first, item.row.gsKt, item.row.trackDeg, -150.0)
            features.add(listOf(back.second to back.first, origin))
        }
        features.addAll(splitAntimeridian(real))
        return features
    }

    private fun putNew(row: PlaneRow, now: Long) {
        if (row.hex.isEmpty()) return
        craft[row.hex] = Craft(row, now, row.lat, row.lon, row.trackDeg, Long.MIN_VALUE)
    }
}

private fun pose(item: Craft, now: Long): VisiblePlane? {
    val age = now - item.fixAtMs
    if (age >= COAST_MS + FADE_MS) return null
    val opacity = if (age <= COAST_MS) {
        1f
    } else {
        (1f - (age - COAST_MS).toFloat() / FADE_MS.toFloat()).coerceIn(0f, 1f)
    }
    val dt = (now - item.fixAtMs) / 1000.0
    val projected = project(item.row.lat, item.row.lon, item.row.gsKt, item.row.trackDeg, dt)
    val easing = item.anchorAtMs >= 0 && now < item.anchorAtMs + EASE_MS
    val lat: Double
    val lon: Double
    val track: Double
    if (easing) {
        val u = (now - item.anchorAtMs).toDouble() / EASE_MS.toDouble()
        val s = smoothstep(u)
        lat = item.anchorLat + (projected.first - item.anchorLat) * s
        lon = item.anchorLon + (projected.second - item.anchorLon) * s
        track = lerpAngle(item.anchorTrack, item.row.trackDeg, s)
    } else {
        lat = projected.first
        lon = projected.second
        track = item.row.trackDeg
    }
    return VisiblePlane(
        hex = item.row.hex,
        callsign = item.row.callsign,
        typeCode = item.row.typeCode,
        reg = item.row.reg,
        altFt = item.row.altFt,
        gsKt = item.row.gsKt,
        trackDeg = track,
        lat = lat,
        lon = lon,
        opacity = opacity,
        silhouette = silhouetteFor(item.row.typeCode, item.row.category),
    )
}

private fun smoothstep(u: Double): Double {
    val x = u.coerceIn(0.0, 1.0)
    return x * x * (3.0 - 2.0 * x)
}

private fun lerpAngle(from: Double, to: Double, t: Double): Double {
    var delta = (to - from) % 360.0
    if (delta > 180.0) delta -= 360.0
    if (delta < -180.0) delta += 360.0
    var value = from + delta * t
    value %= 360.0
    if (value < 0.0) value += 360.0
    return value
}

private fun inside(lon: Double, lat: Double, south: Double, north: Double, west: Double, east: Double): Boolean {
    if (lat < south || lat > north) return false
    return if (west <= east) lon in west..east else lon >= west || lon <= east
}

private fun same(a: Pair<Double, Double>, b: Pair<Double, Double>): Boolean {
    return abs(a.first - b.first) < 1e-6 && abs(a.second - b.second) < 1e-6
}

private fun splitAntimeridian(points: List<Pair<Double, Double>>): List<List<Pair<Double, Double>>> {
    val lines = ArrayList<List<Pair<Double, Double>>>()
    var current = ArrayList<Pair<Double, Double>>()
    for (point in points) {
        val prev = current.lastOrNull()
        if (prev != null && abs(prev.first - point.first) > 180.0) {
            if (current.size >= 2) lines.add(current)
            current = ArrayList()
        }
        current.add(point)
    }
    if (current.size >= 2) lines.add(current)
    return lines
}
