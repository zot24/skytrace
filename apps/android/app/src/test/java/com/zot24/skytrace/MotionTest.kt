package com.zot24.skytrace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {
    @Test
    fun deadReckonEastAndNorthFromSpeedAndHeading() {
        val fleet = Fleet()
        fleet.accept(listOf(row(lat = 0.0, lon = 0.0, gs = 360.0, track = 90.0)), 0L, fresh = true)
        val east = fleet.one("abc123", 10_000L)!!
        assertEquals(0.0, east.lat, 1e-12)
        assertEquals(0.016666666666666666, east.lon, 1e-12)
        assertEquals(1f, east.opacity, 0f)

        val northbound = Fleet()
        northbound.accept(listOf(row(lat = 0.0, lon = 0.0, gs = 360.0, track = 0.0)), 0L, fresh = true)
        val north = northbound.one("abc123", 10_000L)!!
        assertEquals(0.016666666666666666, north.lat, 1e-12)
        assertEquals(0.0, north.lon, 1e-12)
    }

    @Test
    fun staleSnapshotKeepsCoastingThenFadesAndStillMoves() {
        val fleet = Fleet()
        fleet.accept(listOf(row(lat = 0.0, lon = 0.0, gs = 360.0, track = 90.0)), 0L, fresh = true)
        fleet.accept(
            listOf(row(lat = 40.0, lon = 10.0, gs = 0.0, track = 0.0)),
            50_000L,
            fresh = false,
        )
        val at60 = fleet.one("abc123", 60_000L)!!
        assertEquals(1f, at60.opacity, 0f)
        assertEquals(0.0, at60.lat, 1e-9)
        assertEquals(0.1, at60.lon, 1e-9)

        val at65 = fleet.one("abc123", 65_000L)!!
        assertEquals(0.5f, at65.opacity, 0.0001f)
        assertEquals(0.0, at65.lat, 1e-9)
        assertEquals(0.10833333333333334, at65.lon, 1e-9)
        assertTrue(at65.lon > at60.lon)

        assertNull(fleet.one("abc123", 70_000L))
        assertEquals(0, fleet.tracked(70_000L))
    }

    @Test
    fun freshFixDoesNotJumpAndMeetsTheNewTrackAfterTheEase() {
        val fleet = Fleet()
        fleet.accept(listOf(row(lat = 0.0, lon = 0.0, gs = 360.0, track = 90.0)), 0L, fresh = true)
        fleet.accept(listOf(row(lat = 0.0, lon = 0.0, gs = 360.0, track = 90.0)), 10_000L, fresh = true)

        val sameInstant = fleet.one("abc123", 10_000L)!!
        assertEquals(0.0, sameInstant.lat, 1e-12)
        assertEquals(0.016666666666666666, sameInstant.lon, 1e-12)

        val halfway = fleet.one("abc123", 10_500L)!!
        assertEquals(0.0, halfway.lat, 1e-12)
        assertEquals(0.00875, halfway.lon, 1e-12)

        val settled = fleet.one("abc123", 11_000L)!!
        assertEquals(0.0, settled.lat, 1e-12)
        assertEquals(0.0016666666666666668, settled.lon, 1e-12)
    }

    @Test
    fun freshSnapshotDropsAircraftTheFeedLeftBehind() {
        val fleet = Fleet()
        fleet.accept(
            listOf(
                row(hex = "aaa111"),
                row(hex = "bbb222", lon = 5.0),
            ),
            0L,
            fresh = true,
        )
        fleet.accept(listOf(row(hex = "aaa111")), 1_000L, fresh = true)
        assertEquals("aaa111", fleet.one("aaa111", 1_000L)!!.hex)
        assertNull(fleet.one("bbb222", 1_000L))
    }

    private fun row(
        hex: String = "abc123",
        lat: Double = 0.0,
        lon: Double = 0.0,
        gs: Double = 360.0,
        track: Double = 90.0,
    ) = PlaneRow(
        hex = hex,
        callsign = "TST1",
        lat = lat,
        lon = lon,
        altFt = 35000.0,
        gsKt = gs,
        trackDeg = track,
        typeCode = "B738",
        reg = "N1",
        category = "",
    )
}
