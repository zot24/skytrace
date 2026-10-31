package com.zot24.skytrace

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

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
    /** When the feed fixed this position, epoch ms; 0 means "as of this poll". */
    val fixAtMs: Long = 0L,
)

data class TrailPoint(val lon: Double, val lat: Double, val altFt: Double)

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
    /** Last fix, held in place. The aircraft stopped emitting. */
    val resting: Boolean = false,
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

internal data class Craft(
    val row: PlaneRow,
    val fixAtMs: Long,
    val anchorLat: Double,
    val anchorLon: Double,
    val anchorTrack: Double,
    val anchorAtMs: Long,
)

internal const val REST_OPACITY = 0.55f
internal const val REST_KEEP_MS = 18L * 60L * 60L * 1000L
internal const val REST_CAP = 400

class Fleet {
    private var craft = LinkedHashMap<String, Craft>()
    private var resting = LinkedHashMap<String, Craft>()
    private var kept = emptySet<String>()
    private val trails = HashMap<String, ArrayDeque<Pair<TrailPoint, Long>>>()
    private val deadScratch = ArrayList<String>(8)

    /** Favourites stay on the map when the area feed no longer lists them, until their own poll says they went quiet. */
    fun keep(ids: Set<String>) {
        kept = ids
    }

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
            resting.remove(row.hex)
            craft[row.hex] = absorb(craft[row.hex], row, now)
        }
        val gone = craft.keys.filter { it !in seen }
        for (hex in gone) {
            val item = craft[hex] ?: continue
            if (hex in kept) continue
            craft.remove(hex)
            trails.remove(hex)
            if (isOnGround(item.row.altFt, item.row.gsKt)) resting[hex] = freeze(item)
        }
        trimResting(now)
    }

    /**
     * Updates only [rows], and parks [missing] at their last fix.
     * An area poll must not use this: it would leave the rest of the sky in place, which is the point for favourites.
     */
    fun upsert(rows: List<PlaneRow>, now: Long, missing: Set<String>): Set<String> {
        val parked = LinkedHashSet<String>()
        for (row in rows) {
            if (row.hex.isEmpty()) continue
            val prior = craft[row.hex] ?: resting.remove(row.hex)
            resting.remove(row.hex)
            craft[row.hex] = absorb(prior, row, now)
        }
        for (hex in missing) {
            val item = craft.remove(hex) ?: continue
            trails.remove(hex)
            resting[hex] = freeze(item)
            parked.add(hex)
        }
        trimResting(now)
        return parked
    }

    /** Main thread. A shallow copy, so a background merge never reads a map the frame loop is pruning. */
    internal fun snapshot(): Map<String, Craft> = HashMap(craft)

    internal fun restingSnapshot(): Map<String, Craft> = HashMap(resting)

    /** Main thread. Swaps in a map built by [merge]. */
    internal fun install(next: LinkedHashMap<String, Craft>, restingNext: LinkedHashMap<String, Craft>? = null) {
        craft = next
        if (restingNext != null) resting = restingNext
        trails.keys.retainAll(craft.keys)
    }

    fun tracked(now: Long): Int {
        prune(now)
        return craft.size
    }

    fun freshness(hex: String, now: Long): Freshness? = craft[hex]?.let { freshness(now - it.fixAtMs) }

    fun fixAt(hex: String): Long? = craft[hex]?.fixAtMs ?: resting[hex]?.fixAtMs

    fun freshCount(now: Long): Int {
        prune(now)
        return craft.values.count { now - it.fixAtMs <= FRESH_MS }
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
        val out = ArrayList<VisiblePlane>(craft.size + resting.size)
        for (item in craft.values) {
            val pose = pose(item, now) ?: continue
            val keep = item.row.hex == alwaysHex || inside(pose.lon, pose.lat, south, north, west, east)
            if (keep) out.add(pose)
        }
        for ((hex, item) in resting) {
            if (hex in craft) continue
            val pose = pose(item, now, resting = true) ?: continue
            val keep = hex == alwaysHex || inside(pose.lon, pose.lat, south, north, west, east)
            if (keep) out.add(pose)
        }
        return out
    }

    /** Prunes once, then poses each hex. Calling one() per hex pruned the whole fleet every time. */
    fun snapshotFor(hexes: Set<String>, now: Long): List<VisiblePlane> {
        prune(now)
        return hexes.mapNotNull { hex ->
            craft[hex]?.let { pose(it, now) } ?: resting[hex]?.let { pose(it, now, resting = true) }
        }
    }

    fun one(hex: String, now: Long): VisiblePlane? {
        prune(now)
        val live = craft[hex]
        if (live != null) return pose(live, now)
        val parked = resting[hex] ?: return null
        return pose(parked, now, resting = true)
    }

    private fun prune(now: Long) {
        deadScratch.clear()
        for ((hex, item) in craft) {
            if (freshness(now - item.fixAtMs) == null) deadScratch.add(hex)
        }
        for (index in deadScratch.indices) {
            val hex = deadScratch[index]
            val item = craft.remove(hex) ?: continue
            trails.remove(hex)
            if (hex in kept || isOnGround(item.row.altFt, item.row.gsKt)) resting[hex] = freeze(item)
        }
        trimResting(now)
    }

    private fun trimResting(now: Long) {
        val it = resting.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.key in kept) continue
            if (now - entry.value.fixAtMs >= REST_KEEP_MS) it.remove()
        }
        while (resting.size > REST_CAP) {
            val oldest = resting.entries.firstOrNull { it.key !in kept } ?: break
            resting.remove(oldest.key)
        }
    }

    fun noteTrail(hex: String, lon: Double, lat: Double, altFt: Double, now: Long) {
        val path = trails.getOrPut(hex) { ArrayDeque() }
        val prev = path.lastOrNull()?.first
        if (prev != null) {
            var dLon = abs(prev.lon - lon)
            if (dLon > 180.0) dLon = 360.0 - dLon
            if (dLon + abs(prev.lat - lat) < 0.0015) return
        }
        path.addLast(TrailPoint(lon, lat, altFt) to now)
        while (path.size > 180) path.removeFirst()
    }

    fun trailSegments(hex: String, now: Long): List<List<TrailPoint>> {
        val item = craft[hex] ?: return emptyList()
        val pose = pose(item, now) ?: return emptyList()
        val stored = trails[hex].orEmpty()
        val real = ArrayList<TrailPoint>(stored.size + 1)
        for (point in stored) real.add(point.first)
        val tip = TrailPoint(pose.lon, pose.lat, pose.altFt)
        if (real.isEmpty() || !same(real.last(), tip)) real.add(tip)
        val features = ArrayList<List<TrailPoint>>()
        val originT = stored.firstOrNull()?.second ?: item.fixAtMs
        val span = if (stored.isEmpty()) 0L else now - originT
        if (span < 150_000L && item.row.gsKt >= 40.0 && real.isNotEmpty()) {
            val origin = real.first()
            val back = project(origin.lat, origin.lon, item.row.gsKt, item.row.trackDeg, -150.0)
            features.add(listOf(TrailPoint(back.second, back.first, origin.altFt), origin))
        }
        features.addAll(splitAntimeridian(real))
        return features
    }

    private fun putNew(row: PlaneRow, now: Long) {
        if (row.hex.isEmpty()) return
        craft[row.hex] = Craft(row, fixOf(row, now), row.lat, row.lon, row.trackDeg, Long.MIN_VALUE)
    }
}

private fun pose(item: Craft, now: Long, resting: Boolean = false): VisiblePlane? {
    if (resting) {
        return VisiblePlane(
            hex = item.row.hex,
            callsign = item.row.callsign,
            typeCode = item.row.typeCode,
            reg = item.row.reg,
            altFt = item.row.altFt,
            gsKt = 0.0,
            trackDeg = item.row.trackDeg,
            lat = item.row.lat,
            lon = item.row.lon,
            opacity = REST_OPACITY,
            silhouette = silhouetteFor(item.row.typeCode, item.row.category),
            resting = true,
        )
    }
    val opacity = freshness(now - item.fixAtMs)?.opacity ?: return null
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

private fun same(a: TrailPoint, b: TrailPoint): Boolean {
    return abs(a.lon - b.lon) < 1e-6 && abs(a.lat - b.lat) < 1e-6
}

private fun splitAntimeridian(points: List<TrailPoint>): List<List<TrailPoint>> {
    val lines = ArrayList<List<TrailPoint>>()
    var current = ArrayList<TrailPoint>()
    for (point in points) {
        val prev = current.lastOrNull()
        if (prev != null && abs(prev.lon - point.lon) > 180.0) {
            if (current.size >= 2) lines.add(current)
            current = ArrayList()
        }
        current.add(point)
    }
    if (current.size >= 2) lines.add(current)
    return lines
}

internal data class Merged(val live: LinkedHashMap<String, Craft>, val resting: LinkedHashMap<String, Craft>)

/** Any thread. The fresh-poll half of [Fleet.accept], built from a [Fleet.snapshot]. */
internal fun merge(
    base: Map<String, Craft>,
    rows: List<PlaneRow>,
    now: Long,
    keep: Set<String> = emptySet(),
    resting: Map<String, Craft> = emptyMap(),
): Merged {
    val live = LinkedHashMap<String, Craft>(rows.size * 2)
    val rest = LinkedHashMap(resting)
    for (row in rows) {
        if (row.hex.isEmpty()) continue
        val existing = base[row.hex] ?: rest.remove(row.hex)
        live[row.hex] = absorb(existing, row, now)
    }
    for ((hex, item) in base) {
        if (hex in live) continue
        if (hex in keep) {
            live[hex] = item
            continue
        }
        if (isOnGround(item.row.altFt, item.row.gsKt)) rest[hex] = freeze(item)
    }
    trimRestingMap(rest, now, keep)
    return Merged(live, rest)
}

private fun absorb(existing: Craft?, row: PlaneRow, now: Long): Craft {
    if (existing == null) return Craft(row, fixOf(row, now), row.lat, row.lon, row.trackDeg, Long.MIN_VALUE)
    val posed = pose(existing, now)
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
    return if (posed == null) {
        Craft(merged, fixOf(row, now), row.lat, row.lon, row.trackDeg, Long.MIN_VALUE)
    } else {
        Craft(merged, fixOf(row, now), posed.lat, posed.lon, posed.trackDeg, now)
    }
}

private fun freeze(item: Craft): Craft = item.copy(row = item.row.copy(gsKt = 0.0))

private fun trimRestingMap(rest: LinkedHashMap<String, Craft>, now: Long, keep: Set<String>) {
    val it = rest.entries.iterator()
    while (it.hasNext()) {
        val entry = it.next()
        if (entry.key in keep) continue
        if (now - entry.value.fixAtMs >= REST_KEEP_MS) it.remove()
    }
    while (rest.size > REST_CAP) {
        val oldest = rest.entries.firstOrNull { it.key !in keep } ?: break
        rest.remove(oldest.key)
    }
}

/** The feed's own fix time when it gave one, never later than now. */
private fun fixOf(row: PlaneRow, now: Long): Long = if (row.fixAtMs in 1..now) row.fixAtMs else now
