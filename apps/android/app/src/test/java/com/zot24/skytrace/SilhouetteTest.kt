package com.zot24.skytrace

import org.junit.Assert.assertEquals
import org.junit.Test

class SilhouetteTest {
    @Test
    fun typeCodePicksTheSilhouette() {
        assertEquals(Silhouette.WIDE_BODY, silhouetteFor("B744", null))
        assertEquals(Silhouette.WIDE_BODY, silhouetteFor("A388", null))
        assertEquals(Silhouette.WIDE_BODY, silhouetteFor("A359", "A1"))
        assertEquals(Silhouette.WIDE_BODY, silhouetteFor("A310", null))
        assertEquals(Silhouette.NARROW_BODY, silhouetteFor("B738", null))
        assertEquals(Silhouette.NARROW_BODY, silhouetteFor("a320", "A7"))
        assertEquals(Silhouette.NARROW_BODY, silhouetteFor("A319", null))
        assertEquals(Silhouette.REGIONAL_JET, silhouetteFor("E75L", null))
        assertEquals(Silhouette.REGIONAL_JET, silhouetteFor("CRJ9", null))
        assertEquals(Silhouette.BIZJET, silhouetteFor("C56X", null))
        assertEquals(Silhouette.BIZJET, silhouetteFor("GLEX", null))
        assertEquals(Silhouette.TURBOPROP, silhouetteFor("AT76", null))
        assertEquals(Silhouette.TURBOPROP, silhouetteFor("DH8D", null))
        assertEquals(Silhouette.LIGHT_PISTON, silhouetteFor("C172", "A5"))
        assertEquals(Silhouette.HELI_LIGHT, silhouetteFor("R44", "A5"))
        assertEquals(Silhouette.HELI_MEDIUM, silhouetteFor("H60", null))
        assertEquals(Silhouette.HELI_HEAVY, silhouetteFor("H47", null))
    }

    @Test
    fun unknownTypeUsesEmitterCategory() {
        assertEquals(Silhouette.WIDE_BODY, silhouetteFor("", "A5"))
        assertEquals(Silhouette.WIDE_BODY, silhouetteFor("ZZZZ", "A4"))
        assertEquals(Silhouette.NARROW_BODY, silhouetteFor(null, "A3"))
        assertEquals(Silhouette.REGIONAL_JET, silhouetteFor(null, "a2"))
        assertEquals(Silhouette.BIZJET, silhouetteFor(null, "A6"))
        assertEquals(Silhouette.LIGHT_PISTON, silhouetteFor(null, "A1"))
        assertEquals(Silhouette.HELI_MEDIUM, silhouetteFor(null, "A7"))
        assertEquals(Silhouette.NARROW_BODY, silhouetteFor(null, null))
        assertEquals(Silhouette.NARROW_BODY, silhouetteFor("  ", "nope"))
    }
}
