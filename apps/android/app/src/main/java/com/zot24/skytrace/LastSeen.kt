package com.zot24.skytrace

import java.util.Locale

/** The latest place this phone saw a favourite; one per favourite, overwritten on each sighting. */
data class Sighting(
    val hex: String,
    val callsign: String,
    val lat: Double,
    val lon: Double,
    val altFt: Double,
    val onGround: Boolean,
    val atMs: Long,
    /** ICAO of the VRS destination, when the route was known. */
    val dest: String?,
)

internal const val SIGHTING_PREFIX = "seen_"
internal const val SIGHTING_EVERY_MS = 5 * 60_000L

fun encodeSighting(s: Sighting): String =
    listOf(s.callsign, s.lat, s.lon, s.altFt, s.onGround, s.atMs, s.dest.orEmpty()).joinToString("|")

fun decodeSighting(hex: String, raw: String): Sighting? {
    val p = raw.split('|')
    if (p.size != 7) return null
    return Sighting(
        hex, p[0],
        p[1].toDoubleOrNull() ?: return null,
        p[2].toDoubleOrNull() ?: return null,
        p[3].toDoubleOrNull() ?: 0.0,
        p[4] == "true",
        p[5].toLongOrNull() ?: return null,
        p[6].ifBlank { null },
    )
}

/** A sighting is worth writing when it is the first, the plane landed or took off, or the last write is old. */
fun shouldRecord(prev: Sighting?, next: Sighting): Boolean =
    prev == null || prev.onGround != next.onGround || next.atMs - prev.atMs >= SIGHTING_EVERY_MS

/** Nearest airport with a passenger code, within 40 nm; beyond that "near X" would mislead. */
fun nearestAirport(airports: List<Airport>, lat: Double, lon: Double): Airport? =
    airports.asSequence()
        .filter { it.iata.isNotBlank() }
        .map { it to distanceNm(lat, lon, it.lat, it.lon) }
        .filter { it.second <= 40.0 }
        .minByOrNull { it.second }?.first

fun agoLabel(ms: Long): String {
    val min = ms / 60_000L
    return when {
        min < 1 -> "just now"
        min < 60 -> "${min}m ago"
        min < 48 * 60 -> "${min / 60}h ago"
        else -> "${min / (24 * 60)}d ago"
    }
}

fun lastSeenLine(s: Sighting, near: Airport?, dest: Airport?, now: Long): String = buildString {
    append("last seen ").append(agoLabel(now - s.atMs))
    if (near != null) append(" near ").append(near.iata)
    if (s.onGround) append(", on the ground")
    val to = dest?.iata?.ifBlank { null } ?: s.dest
    if (to != null && to != near?.iata && dest?.icao != near?.icao) append(", to ").append(to)
}

enum class LookupKind(val path: String) { HEX("hex"), REG("reg"), CALLSIGN("callsign") }

/** Which single adsb.lol per-aircraft lookup a typed query is, or null if it does not look like an aircraft. */
fun lookupKind(query: String): LookupKind? {
    val q = query.trim()
    if (q.length !in 3..8 || !q.all { it.isLetterOrDigit() || it == '-' }) return null
    return when {
        q.length == 6 && !q.take(3).all { it.isLetter() } && q.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' } && q.any { it.isDigit() } -> LookupKind.HEX
        '-' in q || Regex("^[Nn][0-9][0-9A-Za-z]*$").matches(q) -> LookupKind.REG
        else -> LookupKind.CALLSIGN
    }
}

fun lookupUrl(base: String, kind: LookupKind, query: String): String =
    "%s/v2/%s/%s".format(Locale.US, base.trimEnd('/'), kind.path, query.trim().uppercase(Locale.US).let { if (kind == LookupKind.HEX) it.lowercase(Locale.US) else it })

sealed interface AircraftHit {
    data class Live(val hex: String, val title: String) : AircraftHit
    data class LastSeen(val sighting: Sighting) : AircraftHit
    data class Unknown(val query: String) : AircraftHit
}
