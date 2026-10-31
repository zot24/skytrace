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
internal const val FAVOURITE_SIGHTING_EVERY_MS = 30_000L
internal const val FAVOURITE_POLL_MS = 2_000L

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
fun shouldRecord(prev: Sighting?, next: Sighting, everyMs: Long = SIGHTING_EVERY_MS): Boolean =
    prev == null || prev.onGround != next.onGround || next.atMs - prev.atMs >= everyMs

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

fun hexesUrl(base: String, hexes: Collection<String>): String =
    "%s/v2/hex/%s".format(Locale.US, base.trimEnd('/'), hexes.joinToString(",") { it.lowercase(Locale.US) })

/** A kind of aircraft a person can type: a class, a family name, or an ICAO type-code prefix. */
data class TypeMatch(val label: String, val kinds: Set<Silhouette> = emptySet(), val codes: Set<String> = emptySet())

private val HELICOPTERS = setOf(Silhouette.HELI_LIGHT, Silhouette.HELI_MEDIUM, Silhouette.HELI_HEAVY)
private val AIRLINERS = setOf(Silhouette.WIDE_BODY, Silhouette.NARROW_BODY, Silhouette.REGIONAL_JET)

private val TYPE_ALIASES: Map<String, TypeMatch> = mapOf(
    "helicopter" to TypeMatch("Helicopters", HELICOPTERS),
    "helicopters" to TypeMatch("Helicopters", HELICOPTERS),
    "heli" to TypeMatch("Helicopters", HELICOPTERS),
    "chopper" to TypeMatch("Helicopters", HELICOPTERS),
    "choppers" to TypeMatch("Helicopters", HELICOPTERS),
    "rotorcraft" to TypeMatch("Helicopters", HELICOPTERS),
    "robinson" to TypeMatch("Robinson", Silhouette.HELI_LIGHT.let { setOf(it) }, setOf("R22", "R44", "R66")),
    "r22" to TypeMatch("R22", codes = setOf("R22")),
    "r44" to TypeMatch("R44", codes = setOf("R44")),
    "r66" to TypeMatch("R66", codes = setOf("R66")),
    "blackhawk" to TypeMatch("Black Hawk", codes = setOf("H60")),
    "blackhawks" to TypeMatch("Black Hawk", codes = setOf("H60")),
    "uh60" to TypeMatch("Black Hawk", codes = setOf("H60")),
    "h60" to TypeMatch("Black Hawk", codes = setOf("H60")),
    "chinook" to TypeMatch("Chinook", codes = setOf("H47", "CH47")),
    "ch47" to TypeMatch("Chinook", codes = setOf("H47", "CH47")),
    "h47" to TypeMatch("Chinook", codes = setOf("H47", "CH47")),
    "apache" to TypeMatch("Apache", codes = setOf("H64")),
    "h64" to TypeMatch("Apache", codes = setOf("H64")),
    "bizjet" to TypeMatch("Bizjets", setOf(Silhouette.BIZJET)),
    "bizjets" to TypeMatch("Bizjets", setOf(Silhouette.BIZJET)),
    "businessjet" to TypeMatch("Bizjets", setOf(Silhouette.BIZJET)),
    "businessjets" to TypeMatch("Bizjets", setOf(Silhouette.BIZJET)),
    "gulfstream" to TypeMatch("Gulfstream", codes = setOf("GLF", "GLEX", "GL5T", "GL7T", "G280")),
    "citation" to TypeMatch("Citation", codes = setOf("C25", "C510", "C525", "C550", "C560", "C56", "C680", "C68", "C750")),
    "challenger" to TypeMatch("Challenger", codes = setOf("CL30", "CL35", "CL60")),
    "falcon" to TypeMatch("Falcon", codes = setOf("FA7", "FA8", "FA50", "F2TH", "F900")),
    "lear" to TypeMatch("Learjet", codes = setOf("LJ")),
    "learjet" to TypeMatch("Learjet", codes = setOf("LJ")),
    "turboprop" to TypeMatch("Turboprops", setOf(Silhouette.TURBOPROP)),
    "turboprops" to TypeMatch("Turboprops", setOf(Silhouette.TURBOPROP)),
    "prop" to TypeMatch("Turboprops", setOf(Silhouette.TURBOPROP)),
    "props" to TypeMatch("Turboprops", setOf(Silhouette.TURBOPROP)),
    "atr" to TypeMatch("ATR", codes = setOf("AT4", "AT7")),
    "dash8" to TypeMatch("Dash 8", codes = setOf("DH8")),
    "q400" to TypeMatch("Dash 8", codes = setOf("DH8")),
    "kingair" to TypeMatch("King Air", codes = setOf("BE20", "B350")),
    "pilatus" to TypeMatch("PC-12", codes = setOf("PC12")),
    "pc12" to TypeMatch("PC-12", codes = setOf("PC12")),
    "tbm" to TypeMatch("TBM", codes = setOf("TBM")),
    "caravan" to TypeMatch("Caravan", codes = setOf("C208")),
    "piston" to TypeMatch("Light piston", setOf(Silhouette.LIGHT_PISTON)),
    "pistons" to TypeMatch("Light piston", setOf(Silhouette.LIGHT_PISTON)),
    "cessna" to TypeMatch("Cessna", codes = setOf("C152", "C150", "C172", "C177", "C182", "C206", "C210", "C208")),
    "skyhawk" to TypeMatch("Cessna 172", codes = setOf("C172")),
    "c172" to TypeMatch("Cessna 172", codes = setOf("C172")),
    "cirrus" to TypeMatch("Cirrus", codes = setOf("SR2")),
    "sr22" to TypeMatch("Cirrus", codes = setOf("SR22", "SR20")),
    "piper" to TypeMatch("Piper", codes = setOf("P28", "PA18", "PA24", "PA32", "PA34", "PA38")),
    "diamond" to TypeMatch("Diamond", codes = setOf("DA40", "DA42", "DA20")),
    "airliner" to TypeMatch("Airliners", AIRLINERS),
    "airliners" to TypeMatch("Airliners", AIRLINERS),
    "narrowbody" to TypeMatch("Narrow-body", setOf(Silhouette.NARROW_BODY)),
    "widebody" to TypeMatch("Wide-body", setOf(Silhouette.WIDE_BODY)),
    "wide" to TypeMatch("Wide-body", setOf(Silhouette.WIDE_BODY)),
    "regional" to TypeMatch("Regional jets", setOf(Silhouette.REGIONAL_JET)),
    "regionaljet" to TypeMatch("Regional jets", setOf(Silhouette.REGIONAL_JET)),
    "737" to TypeMatch("737", codes = setOf("B73", "B37", "B38", "B39", "B3X")),
    "b737" to TypeMatch("737", codes = setOf("B73", "B37", "B38", "B39", "B3X")),
    "747" to TypeMatch("747", codes = setOf("B74")),
    "757" to TypeMatch("757", codes = setOf("B75")),
    "767" to TypeMatch("767", codes = setOf("B76")),
    "777" to TypeMatch("777", codes = setOf("B77")),
    "787" to TypeMatch("787", codes = setOf("B78")),
    "dreamliner" to TypeMatch("787", codes = setOf("B78")),
    "a220" to TypeMatch("A220", codes = setOf("BCS")),
    "a320" to TypeMatch("A320 family", codes = setOf("A31", "A32", "A20N", "A21N")),
    "a330" to TypeMatch("A330", codes = setOf("A33")),
    "a340" to TypeMatch("A340", codes = setOf("A34")),
    "a350" to TypeMatch("A350", codes = setOf("A35")),
    "a380" to TypeMatch("A380", codes = setOf("A38")),
    "e175" to TypeMatch("E175", codes = setOf("E75", "E170")),
    "e190" to TypeMatch("E190", codes = setOf("E190", "E19")),
    "embraer" to TypeMatch("Embraer", codes = setOf("E17", "E19", "E75", "E29")),
    "crj" to TypeMatch("CRJ", codes = setOf("CRJ")),
    "737max" to TypeMatch("737", codes = setOf("B73", "B37", "B38", "B39", "B3X")),
    "a319" to TypeMatch("A319", codes = setOf("A319")),
    "a321" to TypeMatch("A321", codes = setOf("A321", "A21N")),
    "boeing" to TypeMatch("Boeing", codes = setOf("B73", "B74", "B75", "B76", "B77", "B78", "B37", "B38", "B39", "B3X", "B712")),
    "airbus" to TypeMatch("Airbus", codes = setOf("A30", "A31", "A32", "A33", "A34", "A35", "A38", "A20", "A21", "BCS")),
    "bell" to TypeMatch("Bell", codes = setOf("B06", "B407", "B412", "B429", "B47")),
    "sikorsky" to TypeMatch("Sikorsky", codes = setOf("H60", "S76", "S92", "H53")),
    "hercules" to TypeMatch("C-130", codes = setOf("C130", "C30J")),
    "c130" to TypeMatch("C-130", codes = setOf("C130", "C30J")),
    "md80" to TypeMatch("MD-80", codes = setOf("MD8")),
    "md11" to TypeMatch("MD-11", codes = setOf("MD11")),
    "aw139" to TypeMatch("AW139", codes = setOf("A139")),
)

private val TYPE_NOISE = setOf("max", "neo", "family", "jet", "jets")

private fun typeKey(query: String): String {
    val compact = query.trim().lowercase(Locale.US).replace(Regex("[\\s_-]+"), "")
    if (compact == "boeing" || compact == "airbus") return compact
    return compact.removePrefix("boeing").removePrefix("airbus")
}

/**
 * Null when the query is a flight, registration, or hex rather than a kind of aircraft.
 * A bare code such as B738 counts only when it is a type this app already knows, so UAL1 stays a callsign.
 */
fun typeMatch(query: String): TypeMatch? {
    val kind = lookupKind(query)
    if (kind == LookupKind.HEX || kind == LookupKind.REG) return null
    val key = typeKey(query)
    if (key.length < 2) return null
    TYPE_ALIASES[key]?.let { return it }
    val tokens = query.trim().lowercase(Locale.US).split(Regex("[\\s_-]+")).filter { it.length >= 2 }
    if (tokens.size > 1) {
        val aliased = ArrayList<TypeMatch>()
        var pure = true
        for (token in tokens) {
            val stripped = if (token == "boeing" || token == "airbus") token else token.removePrefix("boeing").removePrefix("airbus")
            val alias = TYPE_ALIASES[stripped]
            if (alias != null) {
                aliased.add(alias)
            } else if (token.all { it.isDigit() } || token in TYPE_NOISE) {
                continue
            } else {
                pure = false
                break
            }
        }
        if (pure && aliased.isNotEmpty()) {
            return aliased.maxWith(
                compareBy<TypeMatch> { it.codes.maxOfOrNull { code -> code.length } ?: 0 }.thenBy { it.label },
            )
        }
    }
    val code = key.uppercase(Locale.US)
    if (code.length !in 3..4 || !code.any { it.isLetter() } || !code.any { it.isDigit() }) return null
    if (!looksLikeTypeCode(code)) return null
    return TypeMatch(code, codes = setOf(code))
}

fun typeQuery(query: String): Boolean = typeMatch(query) != null

fun planeMatchesType(plane: VisiblePlane, match: TypeMatch): Boolean {
    if (plane.silhouette in match.kinds) return true
    val type = plane.typeCode.trim().uppercase(Locale.US)
    if (type.isEmpty()) return false
    return match.codes.any { type.startsWith(it) }
}

/**
 * Callsign, registration, hex, ICAO type, or a family name such as 737, Cessna, or helicopter.
 * Several words must each match a field, so "DAL B738" finds that flight and "N525 heli" finds that helicopter.
 */
fun searchPlanes(planes: List<VisiblePlane>, query: String, limit: Int = 8): List<VisiblePlane> {
    val needle = query.trim().lowercase(Locale.US)
    if (needle.length < 2) return emptyList()
    val match = typeMatch(query)
    val tokens = needle.split(Regex("\\s+")).filter { it.length >= 2 }
    val scored = ArrayList<Pair<VisiblePlane, Int>>()
    for (plane in planes) {
        var score = aircraftScore(plane, needle)
        if (match != null && planeMatchesType(plane, match)) score = maxOf(score, 88)
        if (tokens.size > 1 && score == 0) {
            var weakest = Int.MAX_VALUE
            var all = true
            for (token in tokens) {
                val part = tokenScore(plane, token)
                if (part == 0) {
                    all = false
                    break
                }
                if (part < weakest) weakest = part
            }
            if (all) score = weakest
        }
        if (score > 0) scored.add(plane to score)
    }
    scored.sortWith(compareByDescending<Pair<VisiblePlane, Int>> { it.second }.thenBy { it.first.callsign })
    return scored.take(limit).map { it.first }
}

fun sightingScore(s: Sighting, query: String): Int {
    val needle = query.trim().lowercase(Locale.US)
    if (needle.length < 2) return 0
    val hex = s.hex.lowercase(Locale.US)
    val callsign = s.callsign.trim().lowercase(Locale.US)
    return when {
        hex == needle || callsign == needle -> 90
        callsign.startsWith(needle) || hex.startsWith(needle) -> 70
        needle.length >= 3 && (callsign.contains(needle) || hex.contains(needle)) -> 50
        else -> 0
    }
}

private fun aircraftScore(plane: VisiblePlane, needle: String): Int {
    val hex = plane.hex.lowercase(Locale.US)
    val callsign = plane.callsign.trim().lowercase(Locale.US)
    val reg = plane.reg.trim().lowercase(Locale.US)
    val type = plane.typeCode.trim().lowercase(Locale.US)
    return when {
        hex == needle -> 100
        callsign == needle -> 96
        reg == needle -> 94
        type.isNotEmpty() && type == needle -> 92
        callsign.startsWith(needle) -> 80
        reg.startsWith(needle) -> 78
        hex.startsWith(needle) -> 76
        type.startsWith(needle) -> 74
        needle.length >= 3 && callsign.contains(needle) -> 60
        needle.length >= 3 && reg.contains(needle) -> 55
        needle.length >= 3 && hex.contains(needle) -> 50
        needle.length >= 3 && type.contains(needle) -> 48
        else -> 0
    }
}

private fun tokenScore(plane: VisiblePlane, token: String): Int {
    val typed = typeMatch(token)?.let { match -> if (planeMatchesType(plane, match)) 88 else 0 } ?: 0
    return maxOf(aircraftScore(plane, token), typed)
}

sealed interface AircraftHit {
    data class Live(val hex: String, val title: String, val line: String = "live now") : AircraftHit
    data class LastSeen(val sighting: Sighting) : AircraftHit
    data class Unknown(val query: String) : AircraftHit
}
