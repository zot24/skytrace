package com.zot24.skytrace

import org.json.JSONArray
import kotlin.math.floor

data class Airport(
    val icao: String,
    val iata: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val kind: String,
    val city: String,
)

fun parseAirports(json: String): List<Airport> {
    val rows = JSONArray(json)
    val out = ArrayList<Airport>(rows.length())
    for (i in 0 until rows.length()) {
        val row = rows.getJSONArray(i)
        out.add(
            Airport(
                icao = row.getString(0),
                iata = row.optString(1),
                name = row.getString(2),
                lat = row.getDouble(3),
                lon = row.getDouble(4),
                kind = row.getString(5),
                city = row.optString(6),
            ),
        )
    }
    return out
}

/** Airports bucketed by whole degree, so a proximity check reads a few cells instead of every airport. */
class AirportGrid(airports: List<Airport>) {
    private val cells = airports.groupBy { cell(it.lat, it.lon) }

    fun near(lat: Double, lon: Double, nm: Double): Boolean {
        val lat0 = floor(lat).toInt()
        val lon0 = floor(lon).toInt()
        for (dLat in -1..1) for (dLon in -1..1) {
            val bucket = cells[key(lat0 + dLat, wrapDegree(lon0 + dLon))] ?: continue
            if (bucket.any { distanceNm(lat, lon, it.lat, it.lon) <= nm }) return true
        }
        return false
    }

    private fun cell(lat: Double, lon: Double) = key(floor(lat).toInt(), floor(lon).toInt())

    private fun key(lat: Int, lon: Int) = lat * 1000 + lon

    private fun wrapDegree(lon: Int) = if (lon >= 180) lon - 360 else if (lon < -180) lon + 360 else lon
}

fun searchAirports(airports: List<Airport>, query: String): List<Airport> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return emptyList()
    val scored = ArrayList<Pair<Airport, Int>>()
    for (airport in airports) {
        val iata = airport.iata.lowercase()
        val icao = airport.icao.lowercase()
        val name = airport.name.lowercase()
        val city = airport.city.lowercase()
        val score = when {
            iata.isNotEmpty() && iata == needle -> 100
            icao == needle -> 92
            iata.startsWith(needle) -> 80
            icao.startsWith(needle) -> 70
            name.startsWith(needle) -> 60
            city.startsWith(needle) -> 50
            name.contains(needle) -> 40
            city.contains(needle) -> 30
            else -> 0
        }
        if (score > 0) scored.add(airport to score + if (airport.kind == "L") 2 else 0)
    }
    scored.sortWith(compareByDescending<Pair<Airport, Int>> { it.second }.thenBy { it.first.name })
    return scored.take(8).map { it.first }
}
