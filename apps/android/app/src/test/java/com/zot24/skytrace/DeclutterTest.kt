package com.zot24.skytrace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeclutterTest {
    private val canaries = ViewBox(24.0, 32.0, -19.0, -13.0)
    private val tfs = Airport("GCTS", "TFS", "Tenerife Sur", 28.0445, -16.5725, "L", "Tenerife")
    private val airports = AirportGrid(listOf(tfs))

    private fun plane(hex: String, lat: Double, lon: Double, altFt: Double = 35_000.0, gsKt: Double = 450.0) =
        VisiblePlane(hex, "", "", "", altFt, gsKt, 0.0, lat, lon, 1f, Silhouette.NARROW_BODY)

    private fun cluster(count: Int) = (0 until count).map { plane("c%04d".format(it), 28.0 + it * 1e-4, -16.0) }

    private fun drawn(planes: List<VisiblePlane>, zoom: Double) = declutter(planes, canaries, zoom, emptySet(), airports).map { it.hex }.toSet()

    @Test
    fun aSparseViewDrawsEveryPlaneEvenWhenTheyShareAGridCell() {
        assertEquals(THIN_FROM_PLANES - 1, drawn(cluster(THIN_FROM_PLANES - 1), 6.0).size)
    }

    @Test
    fun aCrowdedViewStillKeepsOnePlanePerGridCell() {
        assertEquals(1, drawn(cluster(THIN_FROM_PLANES), 6.0).size)
    }

    @Test
    fun lowSlowTrafficShowsNearAnAirportAndHidesElsewhereBelowGroundZoom() {
        val approach = plane("near", 28.10, -16.60, altFt = 900.0, gsKt = 140.0)
        val parked = plane("apron", 28.045, -16.572, altFt = 0.0, gsKt = 0.0)
        val faraway = plane("far", 29.5, -18.0, altFt = 900.0, gsKt = 140.0)
        assertEquals(setOf("near", "apron"), drawn(listOf(approach, parked, faraway), 9.0))
        assertEquals(setOf("near", "apron", "far"), drawn(listOf(approach, parked, faraway), GROUND_ZOOM))
    }

    @Test
    fun parkedPlanesOnOneStandAreSpreadApartAndAirborneOnesStayPut() {
        val a = plane("ana109", 40.6413, -73.7781, altFt = 0.0, gsKt = 0.0)
        val b = plane("rpa5822", 40.6413, -73.7781, altFt = 0.0, gsKt = 3.0)
        val c = plane("c", 40.64131, -73.77811, altFt = 0.0, gsKt = 0.0)
        val overhead = plane("high", 40.6413, -73.7781)
        val zoom = 14.0
        val out = spreadGround(listOf(a, b, c, overhead), zoom).associateBy { it.hex }
        val gapM = GROUND_SPREAD_DP * 40_075_016.686 * Math.cos(Math.toRadians(40.6413)) / (512.0 * Math.pow(2.0, zoom))
        val parked = listOf("ana109", "rpa5822", "c").map { out.getValue(it) }
        for (i in parked.indices) for (j in i + 1 until parked.size) {
            val metres = distanceNm(parked[i].lat, parked[i].lon, parked[j].lat, parked[j].lon) * 1852.0
            assertTrue("$i-$j only $metres m apart", metres >= gapM * 0.99)
        }
        assertEquals(overhead, out.getValue("high"))
    }

    @Test
    fun parkedPlanesAlreadyApartAreLeftAlone() {
        val planes = listOf(plane("a", 28.0445, -16.5725, 0.0, 0.0), plane("b", 28.0500, -16.5600, 0.0, 0.0))
        assertEquals(planes, spreadGround(planes, 14.0))
    }
}
