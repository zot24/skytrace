package com.zot24.skytrace

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Debug-only fixed fleet for perf runs. 80% around New York, 20% scattered worldwide. */
object SyntheticTraffic {
    /** Plane count, set from the `synthetic` intent extra in debug builds. 0 means real data. */
    @Volatile var planes = 0

    private const val LOOP_MS = 30 * 60_000L
    private val types = listOf("B738", "A320", "A321", "B77W", "A359", "E75L", "CRJ9", "B789", "C172", "DH8D")
    private val categories = listOf("A3", "A3", "A3", "A5", "A5", "A3", "A3", "A5", "A1", "A2")
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
            val type = r.nextInt(types.size).let { if (slow) 8 else it }
            val nm = gs * t / 3600.0
            val rad = Math.toRadians(track)
            val lat = (lat0 + nm * cos(rad) / 60.0).coerceIn(-85.0, 85.0)
            val lon = lon0 + nm * sin(rad) / (60.0 * cos(Math.toRadians(lat0)))
            PlaneRow(
                hex = "f5%04x".format(i), callsign = "SYN%03d".format(i), lat = lat,
                lon = ((lon + 540.0) % 360.0) - 180.0, altFt = alt, gsKt = gs, trackDeg = track,
                typeCode = types[type], reg = "N%03dSY".format(i), category = categories[type],
            )
        }
    }
}
