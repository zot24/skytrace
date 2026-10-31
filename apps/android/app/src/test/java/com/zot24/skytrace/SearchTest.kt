package com.zot24.skytrace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private fun plane(hex: String, callsign: String, type: String, reg: String, kind: Silhouette) =
        VisiblePlane(hex, callsign, type, reg, 1_000.0, 100.0, 0.0, 1.0, 2.0, 1f, kind)

    @Test
    fun searchMatchesCallsignRegistrationHexTypeAndHelicopter() {
        val heli = plane("a1b2c3", "N525TM", "R44", "N525TM", Silhouette.HELI_LIGHT)
        val jet = plane("abcdef", "DAL42", "B738", "N12345", Silhouette.NARROW_BODY)
        val planes = listOf(jet, heli)
        assertEquals(listOf("a1b2c3"), searchPlanes(planes, "helicopter").map { it.hex })
        assertEquals(listOf("a1b2c3"), searchPlanes(planes, "R44").map { it.hex })
        assertEquals(listOf("abcdef"), searchPlanes(planes, "B738").map { it.hex })
        assertEquals(listOf("abcdef"), searchPlanes(planes, "DAL").map { it.hex })
        assertEquals(listOf("abcdef"), searchPlanes(planes, "N123").map { it.hex })
        assertEquals(listOf("a1b2c3"), searchPlanes(planes, "a1b2").map { it.hex })
        assertEquals(true, typeQuery("helicopters"))
        assertEquals(90, sightingScore(Sighting("abcdef", "DAL42", 0.0, 0.0, 0.0, false, 0L, null), "DAL42"))
    }

    @Test
    fun searchMatchesFamiliesAndKeepsAFlightFromLookingLikeAType() {
        val heli = plane("a1b2c3", "N525TM", "R44", "N525TM", Silhouette.HELI_LIGHT)
        val jet = plane("abcdef", "DAL42", "B738", "N12345", Silhouette.NARROW_BODY)
        val cessna = plane("c17201", "N172SP", "C172", "N172SP", Silhouette.LIGHT_PISTON)
        val hawk = plane("h60001", "R12345", "H60", "N60AR", Silhouette.HELI_MEDIUM)
        val airbus = plane("a32001", "AAL100", "A320", "N320AA", Silhouette.NARROW_BODY)
        val bareHeli = plane("bare01", "HELI2", "", "", Silhouette.HELI_MEDIUM)
        val planes = listOf(jet, heli, cessna, hawk, airbus, bareHeli)

        assertEquals(listOf("abcdef"), searchPlanes(planes, "737").map { it.hex })
        assertEquals(listOf("abcdef"), searchPlanes(planes, "boeing 737").map { it.hex })
        assertEquals(listOf("abcdef"), searchPlanes(planes, "boeing").map { it.hex })
        assertEquals(listOf("a32001"), searchPlanes(planes, "a320").map { it.hex })
        assertEquals(listOf("c17201"), searchPlanes(planes, "cessna").map { it.hex })
        assertEquals(listOf("c17201"), searchPlanes(planes, "cessna 172").map { it.hex })
        assertEquals(listOf("h60001"), searchPlanes(planes, "black hawk").map { it.hex })
        assertEquals(setOf("a1b2c3", "h60001", "bare01"), searchPlanes(planes, "helicopter", limit = 20).map { it.hex }.toSet())
        assertEquals(listOf("abcdef"), searchPlanes(planes, "DAL B738").map { it.hex })
        assertEquals(listOf("a1b2c3"), searchPlanes(planes, "N525 heli").map { it.hex })
        assertTrue(planeMatchesType(bareHeli, typeMatch("helicopter")!!))
        assertTrue(planeMatchesType(jet, typeMatch("737")!!))
        assertEquals("737", typeMatch("boeing 737")?.label)
        assertEquals("Black Hawk", typeMatch("black hawk")?.label)
        assertEquals("Cessna", typeMatch("cessna 172")?.label)
        assertNull(typeMatch("DAL42"))
        assertNull(typeMatch("DL42"))
        assertNull(typeMatch("UAL1"))
        assertNull(typeMatch("N123"))
        assertNull(typeMatch("N12345"))

        val focus = typeMatch("737")!!
        val shown = planesForFrame(planes, emptySet(), focus, pins = setOf("a1b2c3"), favourites = emptySet())
        assertEquals(setOf("abcdef", "a1b2c3"), shown.map { it.hex }.toSet())
        val helis = planesForFrame(planes, setOf(TypeChip.HELIS), null, emptySet(), emptySet())
        assertEquals(setOf("a1b2c3", "h60001", "bare01"), helis.map { it.hex }.toSet())
    }
}
