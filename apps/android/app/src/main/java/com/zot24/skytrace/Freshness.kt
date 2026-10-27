package com.zot24.skytrace

/** Full opacity up to here. adsb.lol stops sending a position at 60 s. */
const val FRESH_MS = 30_000L
const val DROP_MS = 60_000L
/** About two polls: within this the fix is the latest the feed has; past it the drawn position is dead-reckoned. */
const val LIVE_MS = 12_000L
/** Past [FRESH_MS] a plane steps down to [FADED_FROM], so it reads as old at once, then sinks to [FADED_TO]. */
const val FADED_FROM = 0.45f
const val FADED_TO = 0.2f

data class Freshness(val ageMs: Long, val opacity: Float, val live: Boolean, val fresh: Boolean)

/** Null once the fix is too old to draw. */
fun freshness(ageMs: Long): Freshness? {
    val age = ageMs.coerceAtLeast(0L)
    if (age >= DROP_MS) return null
    val opacity = if (age <= FRESH_MS) 1f else FADED_FROM - (FADED_FROM - FADED_TO) * (age - FRESH_MS) / (DROP_MS - FRESH_MS).toFloat()
    return Freshness(age, opacity, live = age <= LIVE_MS, fresh = age <= FRESH_MS)
}

fun seenLabel(f: Freshness): String = "seen ${f.ageMs / 1000}s ago · " + if (f.live) "live" else "estimated"
