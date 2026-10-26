package com.zot24.skytrace

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Debug-only fixed fleet for perf runs. 80% around New York, 20% scattered worldwide. */
object SyntheticTraffic {
    /** Plane count, set from the `synthetic` intent extra in debug builds. 0 means real data. */
    @Volatile var planes = 0

    /** Opening camera "lat,lon,zoom" from the `camera` extra, for repeatable perf runs. */
    @Volatile var camera: String? = null

    /** `--ez overlay false` hides the debug fps line, for demo recordings. */
    @Volatile var overlay = true

    private const val LOOP_MS = 30 * 60_000L
    private val types = listOf("B738", "A320", "A321", "B77W", "A359", "E75L", "CRJ9", "B789", "C680", "CL35")
    private val slowTypes = listOf("C172", "DH8D", "R44", "EC35")
    private const val JFK_LAT = 40.6413
    private const val JFK_LON = -73.7781
    private val epoch = System.currentTimeMillis()

    fun rows(now: Long): List<PlaneRow> {
        val n = planes
        val t = ((now - epoch) % LOOP_MS) / 1000.0
        return List(n) { i ->
            val r = Random(i)
            val region = i % 5 != 0
            val lat0 = if (region) 36.0 + r.nextDouble() * 10 else -55.0 + r.nextDouble() * 120
            val lon0 = if (region) -82.0 + r.nextDouble() * 16 else -180.0 + r.nextDouble() * 360
            val track = r.nextDouble() * 360
            val slow = r.nextInt(10) == 8
            val gs = if (slow) 90.0 + r.nextDouble() * 40 else 250.0 + r.nextDouble() * 230
            val alt = if (slow) 2_000.0 + r.nextDouble() * 6_000 else 8_000.0 + r.nextDouble() * 31_000
            val code = if (slow) slowTypes[r.nextInt(slowTypes.size)] else types[r.nextInt(types.size)]
            val nm = gs * t / 3600.0
            val rad = Math.toRadians(track)
            val lat = (lat0 + nm * cos(rad) / 60.0).coerceIn(-85.0, 85.0)
            val lon = lon0 + nm * sin(rad) / (60.0 * cos(Math.toRadians(lat0)))
            PlaneRow(
                hex = "f5%04x".format(i), callsign = "SYN%03d".format(i), lat = lat,
                lon = ((lon + 540.0) % 360.0) - 180.0, altFt = alt, gsKt = gs, trackDeg = track,
                typeCode = code, reg = "N%03dSY".format(i), category = "",
            )
        } + airportTraffic(now)
    }

    /** Ground, departing and arriving traffic at JFK, on a ten-minute loop, for the airport sheet. */
    private fun airportTraffic(now: Long): List<PlaneRow> {
        if (planes <= 0) return emptyList()
        return List(24) { i ->
            // Each plane runs the loop from its own offset, so departures and arrivals spread out instead of moving in lockstep.
            val t = ((now - epoch + i * 75_000L) % 600_000L) / 1000.0
            val r = Random(10_000 + i)
            val phase = i % 3
            // Airborne JFK traffic uses real airline callsigns found in the bundled VRS routes, pointed the right way,
            // so the flight sheet can show a scheduled route. Ground traffic keeps JFKnn, which has no route.
            val far = SYNTH_ROUTES.getOrNull(i / 3)
            val farEnd = far?.let { if (phase == 1) it.second else it.third }
            val track = when {
                phase == 0 || farEnd == null -> r.nextDouble() * 360
                else -> bearingDeg(JFK_LAT, JFK_LON, farEnd.first, farEnd.second)
            }
            val rad = Math.toRadians(track)
            val (startNm, gs, alt, heading) = when (phase) {
                0 -> listOf(0.3 + r.nextDouble() * 1.2, 12.0, 0.0, track)
                1 -> listOf(1.0 + t * 200.0 / 3600.0, 200.0, 1_500.0 + t * 12.0, track)
                else -> listOf((30.0 - t * 160.0 / 3600.0).coerceAtLeast(2.0), 160.0, (9_000.0 - t * 14.0).coerceAtLeast(800.0), (track + 180.0) % 360.0)
            }
            val lat = JFK_LAT + startNm * kotlin.math.cos(rad) / 60.0
            val lon = JFK_LON + startNm * kotlin.math.sin(rad) / (60.0 * cos(Math.toRadians(JFK_LAT)))
            PlaneRow(
                hex = "f6%04x".format(i),
                callsign = if (phase == 0 || far == null) "JFK%02d".format(i) else if (phase == 1) far.first.substringBefore('/') else far.first.substringAfter('/'), lat = lat, lon = lon,
                altFt = alt, gsKt = gs, trackDeg = heading,
                typeCode = types[i % 8], reg = "N%02dJF".format(i), category = "",
            )
        }
    }
}

/** Departure callsign / arrival callsign, destination, origin. All pairs exist in VRS routes; ZZT123 is not in VRS, so it shows "Route unknown" while airborne. */
private val SYNTH_ROUTES = listOf(
    Triple("AAL100/AAL101", 51.4706 to -0.4619, 51.4706 to -0.4619),
    Triple("AAL117/AAL10", 33.9425 to -118.408, 33.9425 to -118.408),
    Triple("AAL1174/AAL1357", 25.7959 to -80.287, 25.7959 to -80.287),
    Triple("JBU1/ZZT123", 26.0726 to -80.1527, 42.3656 to -71.0096),
    Triple("AAL1229/AAL1221", 35.214 to -80.9431, 35.214 to -80.9431),
    Triple("AAL1473/AAL1175", 36.084 to -115.1537, 36.084 to -115.1537),
    Triple("AAL1186/AAL1040", 33.4342 to -112.0116, 32.8968 to -97.038),
    Triple("AAL1444/AAL1315", 35.214 to -80.9431, 41.9786 to -87.9048),
)

fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dl = Math.toRadians(lon2 - lon1)
    val y = kotlin.math.sin(dl) * kotlin.math.cos(p2)
    val x = kotlin.math.cos(p1) * kotlin.math.sin(p2) - kotlin.math.sin(p1) * kotlin.math.cos(p2) * kotlin.math.cos(dl)
    return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
}
