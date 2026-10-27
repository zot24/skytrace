package com.zot24.skytrace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshnessTest {
    @Test
    fun liveThenEstimatedThenFadedThenDropped() {
        val live = freshness(5_000L)!!
        assertTrue(live.live && live.fresh)
        assertEquals(1f, live.opacity, 0f)
        assertEquals("seen 5s ago · live", seenLabel(live))

        val estimated = freshness(20_000L)!!
        assertFalse(estimated.live)
        assertTrue(estimated.fresh)
        assertEquals("seen 20s ago · estimated", seenLabel(estimated))

        val stale = freshness(45_000L)!!
        assertFalse(stale.fresh)
        assertEquals(0.325f, stale.opacity, 0.0001f)

        assertEquals(FADED_FROM, freshness(FRESH_MS + 1)!!.opacity, 0.001f)
        assertEquals(FADED_TO, freshness(DROP_MS - 1)!!.opacity, 0.001f)
        assertNull(freshness(DROP_MS))
    }

    @Test
    fun feedFixTimeAgesThePlaneAndCountsOnlyFresh() {
        val now = 100_000L
        fun row(hex: String, lon: Double, fixAt: Long) =
            PlaneRow(hex, hex, 0.0, lon, 30_000.0, 400.0, 90.0, "B738", "N1", "", fixAtMs = fixAt)
        val fleet = Fleet()
        fleet.accept(listOf(row("aaa111", 0.0, now - 2_000L), row("bbb222", 1.0, now - 40_000L)), now, fresh = true)
        assertEquals(2, fleet.tracked(now))
        assertEquals(1, fleet.freshCount(now))
        assertEquals(1f, fleet.one("aaa111", now)!!.opacity, 0f)
        assertTrue(fleet.one("bbb222", now)!!.opacity < 1f)
        assertNull(fleet.one("bbb222", now + 20_000L))
    }
}
