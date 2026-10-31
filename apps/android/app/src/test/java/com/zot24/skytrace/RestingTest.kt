package com.zot24.skytrace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestingTest {
    @Test
    fun aGroundAircraftThatLeavesTheFeedStaysWhereItShutDown() {
        val fleet = Fleet()
        val landed = PlaneRow("aaa111", "TST1", 28.0, -16.5, 0.0, 0.0, 90.0, "A320", "N1", "")
        val flying = PlaneRow("bbb222", "TST2", 28.1, -16.4, 35_000.0, 400.0, 90.0, "B738", "N2", "")
        fleet.accept(listOf(landed, flying), 0L, fresh = true)
        fleet.accept(listOf(flying), 1_000L, fresh = true)
        val rest = fleet.one("aaa111", 1_000L)!!
        assertTrue(rest.resting)
        assertEquals(28.0, rest.lat, 1e-9)
        assertEquals(-16.5, rest.lon, 1e-9)
        assertEquals(28.0, fleet.one("aaa111", 30_000L)!!.lat, 1e-9)
        assertEquals(-16.5, fleet.one("aaa111", 30_000L)!!.lon, 1e-9)
        assertFalse(fleet.one("bbb222", 1_000L)!!.resting)
    }

    @Test
    fun anAreaUpdateKeepsAFavouriteUntilItsOwnPollSaysItWentQuiet() {
        val fleet = Fleet()
        fleet.keep(setOf("abc123"))
        fleet.accept(listOf(PlaneRow("abc123", "HELI1", 28.2, -16.3, 800.0, 90.0, 20.0, "R44", "N44", "A7")), 0L, fresh = true)
        fleet.accept(emptyList(), 1_000L, fresh = true)
        val still = fleet.one("abc123", 1_000L)!!
        assertFalse(still.resting)
        val parked = fleet.upsert(emptyList(), 2_000L, setOf("abc123"))
        assertEquals(setOf("abc123"), parked)
        val rest = fleet.one("abc123", 2_000L)!!
        assertTrue(rest.resting)
        assertEquals(28.2, rest.lat, 1e-9)
        assertEquals(-16.3, rest.lon, 1e-9)
        val later = fleet.one("abc123", 30_000L)!!
        assertEquals(28.2, later.lat, 1e-9)
        assertEquals(-16.3, later.lon, 1e-9)
        assertNull(fleet.one("missing", 30_000L))
    }
}
