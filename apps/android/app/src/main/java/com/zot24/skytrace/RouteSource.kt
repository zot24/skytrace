package com.zot24.skytrace

data class FlightRoute(val label: String, val progress: String, val eta: String)

/**
 * Routes are shown only from a source whose licence has been checked. None is wired in yet:
 * the adsb.lol route set and adsbdb have no clear licence for redistribution, and OpenSky's
 * route data is non-commercial. Until one is approved this returns null and the sheet says "Route unknown".
 */
object RouteSource {
    fun routeFor(callsign: String): FlightRoute? = null
}
