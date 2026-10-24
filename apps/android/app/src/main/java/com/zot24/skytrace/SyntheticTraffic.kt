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
        val t = ((now - epoch) % 600_000L) / 1000.0
        return List(24) { i ->
            val r = Random(10_000 + i)
            val phase = i % 3
            val track = r.nextDouble() * 360
            val rad = Math.toRadians(track)
            val (startNm, gs, alt, heading) = when (phase) {
                0 -> listOf(0.3 + r.nextDouble() * 1.2, 12.0, 0.0, track)
                1 -> listOf(1.0 + t * 200.0 / 3600.0, 200.0, 1_500.0 + t * 12.0, track)
                else -> listOf((30.0 - t * 160.0 / 3600.0).coerceAtLeast(2.0), 160.0, (9_000.0 - t * 14.0).coerceAtLeast(800.0), (track + 180.0) % 360.0)
            }
            val lat = JFK_LAT + startNm * kotlin.math.cos(rad) / 60.0
            val lon = JFK_LON + startNm * kotlin.math.sin(rad) / (60.0 * cos(Math.toRadians(JFK_LAT)))
            PlaneRow(
                hex = "f6%04x".format(i), callsign = "JFK%02d".format(i), lat = lat, lon = lon,
                altFt = alt, gsKt = gs, trackDeg = heading,
                typeCode = types[i % 8], reg = "N%02dJF".format(i), category = "",
            )
        }
    }
}
