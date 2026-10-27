package com.zot24.skytrace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastSeenTest {
    private val jfk = Airport("KJFK", "JFK", "John F Kennedy Intl", 40.6398, -73.7789, "L", "New York")
    private val lga = Airport("KLGA", "LGA", "LaGuardia", 40.7772, -73.8726, "L", "New York")
    private val lax = Airport("KLAX", "LAX", "Los Angeles Intl", 33.9425, -118.4081, "L", "Los Angeles")
    private val strip = Airport("NY01", "", "Private strip", 40.64, -73.78, "S", "")
    private val now = 1_800_000_000_000L
    private fun seen(lat: Double, lon: Double, agoMs: Long, ground: Boolean, dest: String?) =
        Sighting("a1b2c3", "DAL42", lat, lon, if (ground) 0.0 else 9000.0, ground, now - agoMs, dest)

    @Test
    fun nearestAirportSkipsCodelessFieldsAndFarOnes() {
        val list = listOf(strip, lga, jfk, lax)
        assertEquals("JFK", nearestAirport(list, 40.645, -73.785)?.iata)
        assertNull(nearestAirport(list, 45.0, -60.0))
    }

    @Test
    fun lineSaysAgeNearAndDestination() {
        assertEquals("last seen 2h ago near JFK, to LAX", lastSeenLine(seen(40.66, -73.8, 2 * 3_600_000L, false, "KLAX"), jfk, lax, now))
        assertEquals("last seen 15m ago near LAX, on the ground", lastSeenLine(seen(33.94, -118.4, 15 * 60_000L, true, "KLAX"), lax, lax, now))
        assertEquals("last seen 3d ago", lastSeenLine(seen(45.0, -60.0, 3 * 86_400_000L, false, null), null, null, now))
    }

    @Test
    fun writesAreThrottledUnlessTheGroundStateFlips() {
        val first = seen(40.6, -73.7, 0, false, null)
        assertEquals(false, shouldRecord(first, first.copy(atMs = first.atMs + 60_000L)))
        assertEquals(true, shouldRecord(first, first.copy(atMs = first.atMs + 60_000L, onGround = true)))
        assertEquals(true, shouldRecord(first, first.copy(atMs = first.atMs + SIGHTING_EVERY_MS)))
        assertEquals(first, decodeSighting(first.hex, encodeSighting(first)))
    }

    @Test
    fun queryPicksOneLookup() {
        assertEquals(LookupKind.HEX, lookupKind("a1b2c3"))
        assertEquals(LookupKind.CALLSIGN, lookupKind("ACA123"))
        assertEquals(LookupKind.REG, lookupKind("N123AB"))
        assertEquals(LookupKind.REG, lookupKind("G-EUPT"))
        assertNull(lookupKind("new york"))
    }
}
