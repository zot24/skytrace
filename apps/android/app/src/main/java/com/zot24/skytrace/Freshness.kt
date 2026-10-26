package com.zot24.skytrace

/** Full opacity up to here. adsb.lol drops a position after 60 s; our poll adds up to ~6 s. */
const val FRESH_MS = 30_000L
const val DROP_MS = 60_000L
/** Two polls: within this the fix is the latest the feed has; past it the drawn position is dead-reckoned. */
const val LIVE_MS = 12_000L
const val STALE_OPACITY = 0.3f

data class Freshness(val ageMs: Long, val opacity: Float, val live: Boolean, val fresh: Boolean)

/** Null once the fix is too old to draw. */
fun freshness(ageMs: Long): Freshness? {
    val age = ageMs.coerceAtLeast(0L)
    if (age >= DROP_MS) return null
    val opacity = if (age <= FRESH_MS) 1f else 1f - (1f - STALE_OPACITY) * (age - FRESH_MS) / (DROP_MS - FRESH_MS).toFloat()
    return Freshness(age, opacity, live = age <= LIVE_MS, fresh = age <= FRESH_MS)
}

fun seenLabel(f: Freshness): String = "seen ${f.ageMs / 1000}s ago · " + if (f.live) "live" else "estimated"
