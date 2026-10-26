package com.zot24.skytrace

import org.junit.Assert.assertEquals
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
}
