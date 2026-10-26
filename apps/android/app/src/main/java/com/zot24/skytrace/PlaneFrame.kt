package com.zot24.skytrace

const val PLANE_CULL_ZOOM = 8.0
const val LOW_ZOOM_PUSH_MS = 200L
const val HIGH_ZOOM_PUSH_MS = 50L
const val PLANE_LAYER_ID = "plane-symbols"
const val HIT_HALF_DP = 32f

data class PlaneHit(val hex: String, val x: Float, val y: Float)

class DrawnIndex {
    private val x = HashMap<String, Float>(512)
    private val y = HashMap<String, Float>(512)
    private val lat = HashMap<String, Double>(512)
    private val lon = HashMap<String, Double>(512)
    private val hdg = HashMap<String, Float>(512)
    private val op = HashMap<String, Float>(512)
    private var selected: String? = null
    private var trailSig: Int = 0

    fun unchanged(
        planes: List<VisiblePlane>,
        sx: FloatArray,
        sy: FloatArray,
        selected: String?,
        trailSig: Int,
    ): Boolean {
        if (selected != this.selected || trailSig != this.trailSig || planes.size != x.size) return false
        for (i in planes.indices) {
            val plane = planes[i]
            val ox = x[plane.hex] ?: return false
            val oy = y[plane.hex] ?: return false
            val dx = sx[i] - ox
            val dy = sy[i] - oy
            if (dx * dx + dy * dy > 1f) return false
            val previousHeading = hdg[plane.hex] ?: return false
            var turn = plane.trackDeg.toFloat() - previousHeading
            if (turn > 180f) turn -= 360f
            if (turn < -180f) turn += 360f
            if (turn > 1f || turn < -1f) return false
            val previousOpacity = op[plane.hex] ?: return false
            if (plane.opacity - previousOpacity > 0.04f || previousOpacity - plane.opacity > 0.04f) return false
        }
        return true
    }

    fun commit(
        planes: List<VisiblePlane>,
        sx: FloatArray,
        sy: FloatArray,
        selected: String?,
        trailSig: Int,
    ) {
        var same = planes.size == x.size
        if (same) {
            for (plane in planes) {
                if (plane.hex !in x) {
                    same = false
                    break
                }
            }
        }
        if (!same) {
            x.clear()
            y.clear()
            lat.clear()
            lon.clear()
            hdg.clear()
            op.clear()
        }
        for (i in planes.indices) {
            val plane = planes[i]
            x[plane.hex] = sx[i]
            y[plane.hex] = sy[i]
            lat[plane.hex] = plane.lat
            lon[plane.hex] = plane.lon
            hdg[plane.hex] = plane.trackDeg.toFloat()
            op[plane.hex] = plane.opacity
        }
        this.selected = selected
        this.trailSig = trailSig
    }

    fun reset() {
        x.clear()
        selected = "\u0000"
    }

    fun forEachGeo(block: (hex: String, lat: Double, lon: Double) -> Unit) {
        for ((hex, latitude) in lat) {
            val longitude = lon[hex] ?: continue
            block(hex, latitude, longitude)
        }
    }
}

fun planesInHitBox(
    tapX: Float,
    tapY: Float,
    halfPx: Float,
    rendered: List<PlaneHit>,
    queried: List<PlaneHit>,
): List<PlaneHit> {
    val hits = ArrayList<PlaneHit>(8)
    val seen = HashSet<String>(8)
    for (hit in rendered) {
        val dx = hit.x - tapX
        val dy = hit.y - tapY
        if (dx <= halfPx && dx >= -halfPx && dy <= halfPx && dy >= -halfPx) {
            hits.add(hit)
            seen.add(hit.hex)
        }
    }
    for (hit in queried) {
        if (hit.hex.isEmpty() || hit.hex in seen) continue
        hits.add(hit)
        seen.add(hit.hex)
    }
    hits.sortBy { hit ->
        val dx = hit.x - tapX
        val dy = hit.y - tapY
        dx * dx + dy * dy
    }
    return hits
}

fun trailSignature(trails: List<List<TrailPoint>>): Int {
    var signature = trails.size
    for (segment in trails) {
        signature = signature * 31 + segment.size
        val tip = segment.lastOrNull() ?: continue
        signature = signature * 31 + tip.lon.hashCode()
        signature = signature * 31 + tip.lat.hashCode()
    }
    return signature
}

fun appendPlaneJson(out: StringBuilder, planes: List<VisiblePlane>, selected: String?, pins: Set<String>, favourites: Set<String>) {
    out.setLength(0)
    out.append("{\"type\":\"FeatureCollection\",\"features\":[")
    for (i in planes.indices) {
        if (i > 0) out.append(',')
        val plane = planes[i]
        val icon = plane.silhouette.name
        out.append("{\"type\":\"Feature\",\"properties\":{\"hex\":\"")
        appendJsonString(out, plane.hex)
        out.append("\",\"icon\":\"")
        appendJsonString(out, icon)
        out.append("\",\"type\":\"")
        appendJsonString(out, plane.silhouette.name)
        out.append("\",\"hdg\":")
        out.append(plane.trackDeg)
        out.append(",\"op\":")
        out.append(plane.opacity.toDouble())
        out.append(",\"alt\":")
        out.append(plane.altFt.toInt())
        out.append(",\"sel\":")
        out.append(plane.hex == selected)
        out.append(",\"pin\":")
        out.append(plane.hex in pins)
        out.append(",\"fav\":")
        out.append(plane.hex in favourites)
        out.append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
        out.append(plane.lon)
        out.append(',')
        out.append(plane.lat)
        out.append("]}}")
    }
    out.append("]}")
}

/** One two-point line per step, each carrying its mean altitude, so the layer colours the trail by height. */
fun appendTrailJson(out: StringBuilder, trails: List<List<TrailPoint>>) {
    out.setLength(0)
    out.append("{\"type\":\"FeatureCollection\",\"features\":[")
    var written = 0
    for (segment in trails) {
        for (i in 1 until segment.size) {
            val a = segment[i - 1]
            val b = segment[i]
            if (written > 0) out.append(',')
            written += 1
            out.append("{\"type\":\"Feature\",\"properties\":{\"alt\":")
            out.append(((a.altFt + b.altFt) / 2).toInt())
            out.append("},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[")
            out.append(a.lon).append(',').append(a.lat).append("],[")
            out.append(b.lon).append(',').append(b.lat).append("]]}}")
        }
    }
    out.append("]}")
}

private fun appendJsonString(out: StringBuilder, value: String) {
    for (ch in value) {
        if (ch == '\\' || ch == '"') out.append('\\')
        out.append(ch)
    }
}
