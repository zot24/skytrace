package com.zot24.skytrace

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

const val DEFAULT_BASE = "http://10.0.2.2:8787"

private const val COUNT_TAG = "skytrace-count"

data class ViewBox(val south: Double, val north: Double, val west: Double, val east: Double)

data class CameraJump(val lat: Double, val lon: Double, val zoom: Double)

val BUSY_OPENING = CameraJump(40.72, -73.98, 6.0)

data class PlaneDetail(
    val hex: String,
    val title: String,
    val type: String,
    val reg: String,
    val altitude: String,
    val onGround: Boolean,
    val speed: String,
    val heading: String,
)

data class FramePicture(
    val planes: List<VisiblePlane>,
    val trails: List<List<Pair<Double, Double>>>,
    val total: Int,
    val inView: Int = planes.size,
    val pinsInView: Int = 0,
    val pinsDrawn: Int = 0,
)

data class PickerRow(val hex: String, val callsign: String, val type: String, val altitude: String)

data class TrafficRow(val hex: String, val callsign: String, val type: String, val altitude: String, val distanceNm: Int)

data class AirportTraffic(val departures: List<TrafficRow>, val arrivals: List<TrafficRow>, val ground: List<TrafficRow>)

class SkyViewModel(app: Application) : AndroidViewModel(app) {
    val fleet = Fleet()
    var source by mutableStateOf("connecting")
    var count by mutableStateOf(0)
    var selected by mutableStateOf<String?>(null)
    var detail by mutableStateOf<PlaneDetail?>(null)
    /** Null while looking up; [routeLookedUp] tells "unknown" apart from "not asked yet". */
    var route by mutableStateOf<FlightRoute?>(null)
    var routeLookedUp by mutableStateOf(false)
    var extraRoutes by mutableStateOf(false)
    private var routeCallsign: String? = null
    private var routeResult: RouteResult? = null
    private val airportsByIcao by lazy { airports.associateBy { it.icao } }
    var baseUrl by mutableStateOf(DEFAULT_BASE)
    var planeSymbols: PlaneSymbols? = null
        set(value) {
            field = value
            value?.setPlaneFilter(planeFilterExpression(typeChips.value))
        }
    var followHex by mutableStateOf<String?>(null)
    var styleCover by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    var styleLoading by mutableStateOf<MapStyle?>(null)
    var picker by mutableStateOf<List<PickerRow>>(emptyList())
    var airportSheet by mutableStateOf<Airport?>(null)
    var metar by mutableStateOf<String?>(null)
    val mapStyle = MutableStateFlow(MapStyle.DARK)
    val typeChips = MutableStateFlow<Set<TypeChip>>(emptySet())
    private var pendingLink: String? = null
    private val metarCache = HashMap<String, Pair<Long, String>>()
    var airports by mutableStateOf<List<Airport>>(emptyList())
    val favouriteAircraft = MutableStateFlow<Set<String>>(emptySet())
    val favouriteAirports = MutableStateFlow<Set<String>>(emptySet())
    val camera = MutableSharedFlow<CameraJump>(extraBufferCapacity = 1)
    val opening = MutableStateFlow(BUSY_OPENING)

    private var box: ViewBox? = null
    private var attempt = 0
    private var lastTrail = 0L
    private var lastSavedCamera: String? = null
    private var cameraWritable = false
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    init {
        viewModelScope.launch {
            val prefs = getApplication<Application>().skyStore.data.first()
            applyPrefs(prefs[keyUrl], prefs[keyAircraft], prefs[keyAirports])
            mapStyle.value = prefs[keyStyle]?.let { name -> MapStyle.entries.firstOrNull { it.name == name } } ?: MapStyle.DARK
            setChips(prefs[keyChips].orEmpty().mapNotNull { name -> TypeChip.entries.firstOrNull { it.name == name } }.toSet(), save = false)
            if (BuildConfig.DEBUG) {
                getApplication<Application>().skyStore.edit { it[keyDebugSynthetic] = SyntheticTraffic.planes }
            }
            WidgetSync.schedule(getApplication())
            launch {
                while (isActive) {
                    delay(5_000L)
                    WidgetSync.refresh(getApplication(), fleet.snapshotFor(fleet.snapshot().keys, System.currentTimeMillis()))
                    delay(115_000L)
                }
            }
            val saved = parseCamera(SyntheticTraffic.camera.takeIf { BuildConfig.DEBUG } ?: prefs[keyCamera])
            val cam = saved ?: BUSY_OPENING
            lastSavedCamera = if (saved != null) formatCamera(cam) else null
            cameraWritable = true
            opening.value = cam
            launch {
                getApplication<Application>().skyStore.data.collect { next ->
                    applyPrefs(next[keyUrl], next[keyAircraft], next[keyAirports])
                    extraRoutes = next[keyExtraRoutes] ?: false
                    if (RouteSource.extraEnabled != extraRoutes) {
                        RouteSource.extraEnabled = extraRoutes
                        routeCallsign = null
                    }
                }
            }
            pollLoop()
        }
        viewModelScope.launch(Dispatchers.IO) {
            val text = getApplication<Application>().assets.open("airports.json").bufferedReader().use { it.readText() }
            val parsed = parseAirports(text)
            RouteSource.init(getApplication())
            withContext(Dispatchers.Main) {
                airports = parsed
                applyPendingLink()
            }
        }
    }

    fun saveBaseUrl(raw: String) {
        val trimmed = raw.trim().ifBlank { DEFAULT_BASE }
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
        viewModelScope.launch {
            getApplication<Application>().skyStore.edit { it[keyUrl] = withScheme.trimEnd('/') }
        }
    }

    fun toggleAircraft(hex: String) = toggle(keyAircraft, hex)

    fun toggleAirport(icao: String) = toggle(keyAirports, icao)

    fun focusAirport(airport: Airport) {
        camera.tryEmit(CameraJump(airport.lat, airport.lon, 11.0))
        openAirport(airport.icao)
    }

    fun focusAircraft(hex: String) {
        val plane = fleet.one(hex, System.currentTimeMillis()) ?: return
        selected = hex
        detail = toDetail(plane)
        camera.tryEmit(CameraJump(plane.lat, plane.lon, 7.0))
    }

    fun onPlaneHit(hexes: List<String>, now: Long) {
        val planes = hexes.mapNotNull { fleet.one(it, now) }
        if (planes.isEmpty()) return
        airportSheet = null
        if (planes.size == 1) {
            picker = emptyList()
            pick(planes[0].hex)
            return
        }
        picker = planes.take(8).map { plane ->
            PickerRow(
                plane.hex,
                plane.callsign.ifBlank { plane.hex.uppercase(Locale.US) },
                plane.typeCode.ifBlank { "—" },
                if (isOnGround(plane.altFt, plane.gsKt)) "On ground" else "${numbers.format(plane.altFt.roundToInt())} ft",
            )
        }
    }

    fun pick(hex: String) {
        val plane = fleet.one(hex, System.currentTimeMillis()) ?: return
        picker = emptyList()
        airportSheet = null
        if (followHex != null && followHex != hex) followHex = null
        selected = hex
        detail = toDetail(plane)
    }

    fun closePicker() {
        picker = emptyList()
    }

    fun toggleFollow() {
        val hex = selected ?: return
        followHex = if (followHex == hex) null else hex
    }

    fun stopFollow() {
        followHex = null
    }

    fun openAirport(icao: String) {
        val airport = airports.firstOrNull { it.icao == icao } ?: return
        picker = emptyList()
        selected = null
        detail = null
        followHex = null
        airportSheet = airport
        loadMetar(airport.icao)
    }

    fun closeAirport() {
        airportSheet = null
    }

    fun setStyle(style: MapStyle) {
        mapStyle.value = style
        viewModelScope.launch { getApplication<Application>().skyStore.edit { it[keyStyle] = style.name } }
    }

    fun toggleChip(chip: TypeChip) {
        val next = typeChips.value.toMutableSet()
        if (!next.add(chip)) next.remove(chip)
        setChips(next, save = true)
    }

    private fun setChips(chips: Set<TypeChip>, save: Boolean) {
        typeChips.value = chips
        planeSymbols?.setPlaneFilter(planeFilterExpression(chips))
        if (save) {
            viewModelScope.launch { getApplication<Application>().skyStore.edit { it[keyChips] = chips.map { c -> c.name }.toSet() } }
        }
    }

    /** skytrace://airport/ICAO or skytrace://aircraft/hex, from the widget. */
    fun openLink(uri: String?) {
        if (uri.isNullOrBlank()) return
        pendingLink = uri
        applyPendingLink()
    }

    private fun applyPendingLink() {
        val uri = pendingLink ?: return
        val parts = uri.removePrefix("skytrace://").split('/')
        if (parts.size != 2) {
            pendingLink = null
            return
        }
        when (parts[0]) {
            "airport" -> {
                val airport = airports.firstOrNull { it.icao == parts[1] } ?: return
                pendingLink = null
                focusAirport(airport)
            }
            "aircraft" -> {
                if (fleet.one(parts[1], System.currentTimeMillis()) == null) return
                pendingLink = null
                focusAircraft(parts[1])
            }
            else -> pendingLink = null
        }
    }

    private fun loadMetar(icao: String) {
        val now = System.currentTimeMillis()
        val cached = metarCache[icao]
        if (cached != null && now - cached.first < 10 * 60_000L) {
            metar = cached.second
            return
        }
        metar = null
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    val request = Request.Builder().url("https://aviationweather.gov/api/data/metar?ids=$icao&format=raw").build()
                    http.newCall(request).execute().use { response ->
                        if (response.isSuccessful) response.body?.string()?.trim().orEmpty() else ""
                    }
                } catch (_: Exception) {
                    ""
                }
            }
            val shown = text.ifBlank { "No METAR available" }
            if (text.isNotBlank()) metarCache[icao] = now to shown
            if (airportSheet?.icao == icao) metar = shown
        }
    }

    fun airportTraffic(airport: Airport, now: Long): AirportTraffic =
        classifyTraffic(airport, fleet.visible(now, airport.lat - 0.8, airport.lat + 0.8, airport.lon - 1.1, airport.lon + 1.1))

    fun rememberView(lat: Double, lon: Double, zoom: Double, pinned: Boolean) {
        if (!cameraWritable) return
        if (!pinned && !nearOpening(lat, lon, zoom)) return
        val encoded = formatCamera(CameraJump(lat, lon, zoom))
        if (encoded == lastSavedCamera) return
        lastSavedCamera = encoded
        viewModelScope.launch {
            getApplication<Application>().skyStore.edit { it[keyCamera] = encoded }
        }
    }

    fun closeDetail() {
        selected = null
        detail = null
        followHex = null
    }

    fun maintainSelection(now: Long) {
        count = fleet.tracked(now)
        val hex = selected ?: return
        val plane = fleet.one(hex, now)
        if (plane == null) {
            selected = null
            detail = null
        } else {
            detail = toDetail(plane)
        }
    }

    fun prepareFrame(now: Long, view: ViewBox, zoom: Double): FramePicture {
        box = view
        val hex = selected
        val padded = padView(view)
        val favourites = favouriteAircraft.value
        val pins = HashSet<String>(favourites.size + 2)
        hex?.let { pins += it }
        followHex?.let { pins += it }
        pins.addAll(favourites)
        val inView = fleet.visible(now, padded.south, padded.north, padded.west, padded.east, hex ?: followHex)
        val chips = typeChips.value
        val kept = if (chips.isEmpty()) inView else inView.filter { it.hex in pins || keepsPlane(chips, it.silhouette, it.hex in favourites) }
        // Thinning only below zoom 8, and never over a pinned plane, so it cannot hide what the user is looking for.
        val planes = if (zoom < PLANE_CULL_ZOOM) thin(kept, padded, pins) else kept
        val trails = if (hex == null) {
            emptyList()
        } else {
            val plane = fleet.one(hex, now)
            if (plane != null && now - lastTrail >= 1_000L) {
                fleet.noteTrail(hex, plane.lon, plane.lat, now)
                lastTrail = now
            }
            fleet.trailSegments(hex, now)
        }
        return FramePicture(
            planes,
            trails,
            fleet.tracked(now),
            inView = inView.size,
            pinsInView = inView.count { it.hex in pins },
            pinsDrawn = planes.count { it.hex in pins },
        )
    }

    private fun toggle(key: androidx.datastore.preferences.core.Preferences.Key<Set<String>>, id: String) {
        viewModelScope.launch {
            getApplication<Application>().skyStore.edit { prefs ->
                val cur = prefs[key]?.toMutableSet() ?: mutableSetOf()
                if (!cur.add(id)) cur.remove(id)
                prefs[key] = cur
            }
            WidgetSync.refresh(getApplication(), fleet.snapshotFor(fleet.snapshot().keys, System.currentTimeMillis()))
        }
    }

    private fun applyPrefs(url: String?, aircraft: Set<String>?, airports: Set<String>?) {
        baseUrl = url?.ifBlank { DEFAULT_BASE } ?: DEFAULT_BASE
        favouriteAircraft.value = aircraft ?: emptySet()
        favouriteAirports.value = airports ?: emptySet()
    }

    private suspend fun pollLoop() {
        while (viewModelScope.isActive) {
            val wait = fetchOnce()
            delay(wait)
        }
    }

    private suspend fun fetchOnce(): Long {
        if (BuildConfig.DEBUG && SyntheticTraffic.planes > 0) {
            val now = System.currentTimeMillis()
            val rows = withContext(Dispatchers.Default) { SyntheticTraffic.rows(now) }
            acceptFresh(rows, now)
            applyPendingLink()
            source = "synthetic"
            count = fleet.tracked(now)
            refreshDetail(now)
            logCount(rows.size, false)
            return 5_000L
        }
        val base = baseUrl
        val view = box
        val url = if (view == null) {
            base.trimEnd('/') + "/api/planes"
        } else {
            planesUrl(base, view.south, view.north, view.west, view.east)
        }
        val fetched = withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).header("Accept", "application/json").build()
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    response.code to parseFeed(text)
                }
            } catch (_: Exception) {
                null
            }
        }
        val now = System.currentTimeMillis()
        if (fetched == null) {
            attempt += 1
            markStale(source, now)
            logCount(0, true)
            return nextPollDelay(false, attempt, 0L)
        }
        val (code, body) = fetched
        if (body == null) {
            attempt += 1
            markStale(source, now)
            logCount(0, true)
            return nextPollDelay(false, attempt, 0L)
        }
        val fresh = code in 200..299 && !body.stale && body.src != "none" && body.rows.isNotEmpty()
        if (fresh) {
            acceptFresh(body.rows, now)
            applyPendingLink()
            attempt = 0
            source = body.src.ifBlank { "adsb.lol" }
            count = fleet.tracked(now)
            refreshDetail(now)
            logCount(body.rows.size, false)
            return nextPollDelay(true, 0, 0L)
        }
        fleet.accept(body.rows, now, fresh = false)
        attempt += 1
        val label = body.src.takeIf { it.isNotBlank() && it != "none" } ?: source
        markStale(label, now)
        logCount(body.rows.size, true)
        return nextPollDelay(false, attempt, body.retryAfterMs)
    }

    /** The merge runs on Default. The main thread only copies the map and swaps the result in. */
    private suspend fun acceptFresh(rows: List<PlaneRow>, now: Long) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val base = fleet.snapshot()
        val t1 = android.os.SystemClock.elapsedRealtime()
        val next = withContext(Dispatchers.Default) { merge(base, rows, now) }
        val t2 = android.os.SystemClock.elapsedRealtime()
        fleet.install(next)
        val t3 = android.os.SystemClock.elapsedRealtime()
        Log.i("skytrace-perf", "accept rows=${rows.size} mainMs=${(t1 - t0) + (t3 - t2)} bgMs=${t2 - t1}")
    }

    private fun logCount(rows: Int, stale: Boolean) {
        Log.i(COUNT_TAG, "total=$count rows=$rows stale=$stale")
    }

    private fun nearOpening(lat: Double, lon: Double, zoom: Double): Boolean {
        val target = opening.value
        return abs(target.lat - lat) < 1e-3 && abs(target.lon - lon) < 1e-3 && abs(target.zoom - zoom) < 0.02
    }

    private fun markStale(label: String, now: Long) {
        val base = label.removeSuffix(" · stale")
        source = if (fleet.tracked(now) > 0 && base.isNotBlank() && base != "connecting" && base != "feed unavailable") {
            "$base · stale"
        } else if (fleet.tracked(now) > 0) {
            "feed unavailable"
        } else {
            "feed unavailable"
        }
        count = fleet.tracked(now)
        refreshDetail(now)
    }

    private fun refreshDetail(now: Long) {
        val hex = selected ?: return
        val plane = fleet.one(hex, now)
        if (plane == null) {
            selected = null
            detail = null
        } else {
            detail = toDetail(plane)
        }
    }

    fun setExtraRoutes(on: Boolean) {
        viewModelScope.launch { getApplication<Application>().skyStore.edit { it[keyExtraRoutes] = on } }
    }

    private fun trackRoute(plane: VisiblePlane, onGround: Boolean) {
        val callsign = plane.callsign.trim()
        if (callsign != routeCallsign) {
            routeCallsign = callsign
            routeResult = null
            route = null
            routeLookedUp = false
            if (callsign.isEmpty()) { routeLookedUp = true; return }
            viewModelScope.launch {
                val found = withContext(Dispatchers.IO) { RouteSource.lookup(callsign, plane.lat, plane.lon) }
                if (routeCallsign != callsign) return@launch
                routeResult = found
                routeLookedUp = true
                route = found?.takeIf { airports.isNotEmpty() }?.let { describeRoute(it, airportsByIcao, plane, onGround, System.currentTimeMillis()) }
            }
            return
        }
        routeResult?.let { route = describeRoute(it, airportsByIcao, plane, onGround, System.currentTimeMillis()) }
    }

    private fun toDetail(plane: VisiblePlane): PlaneDetail {
        trackRoute(plane, isOnGround(plane.altFt, plane.gsKt))
        val heading = ((plane.trackDeg % 360.0) + 360.0) % 360.0
        return PlaneDetail(
            hex = plane.hex,
            title = plane.callsign.ifBlank { plane.hex.uppercase(Locale.US) },
            type = plane.typeCode.ifBlank { "—" },
            reg = plane.reg.ifBlank { "—" },
            altitude = "${numbers.format(plane.altFt.roundToInt())} ft",
            onGround = isOnGround(plane.altFt, plane.gsKt),
            speed = "${numbers.format(plane.gsKt.roundToInt())} kt",
            heading = "%03d°".format(Locale.US, heading.roundToInt() % 360),
        )
    }

    private val numbers = java.text.NumberFormat.getIntegerInstance(Locale.US)
}

private fun formatCamera(jump: CameraJump): String {
    return String.format(Locale.US, "%.5f,%.5f,%.2f", jump.lat, jump.lon, jump.zoom)
}

private fun parseCamera(raw: String?): CameraJump? {
    val parts = raw?.split(',') ?: return null
    if (parts.size != 3) return null
    val lat = parts[0].toDoubleOrNull() ?: return null
    val lon = parts[1].toDoubleOrNull() ?: return null
    val zoom = parts[2].toDoubleOrNull() ?: return null
    if (lat !in -85.0..85.0 || lon !in -180.0..180.0 || zoom !in 0.5..18.0) return null
    return CameraJump(lat, lon, zoom)
}

private fun padView(view: ViewBox): ViewBox {
    val latSpan = (view.north - view.south).coerceAtLeast(0.01)
    val crosses = view.west > view.east
    val lonSpan = if (crosses) 360.0 - view.west + view.east else view.east - view.west
    val latM = max(0.3, latSpan * 0.15)
    val lonM = max(0.3, lonSpan.coerceAtLeast(0.01) * 0.15)
    val south = (view.south - latM).coerceAtLeast(-85.0)
    val north = (view.north + latM).coerceAtMost(85.0)
    if (lonSpan + lonM * 2 >= 360.0) return ViewBox(south, north, -180.0, 180.0)
    return ViewBox(south, north, wrapLon(view.west - lonM), wrapLon(view.east + lonM))
}

private fun wrapLon(lon: Double): Double {
    var value = lon
    while (value > 180.0) value -= 360.0
    while (value < -180.0) value += 360.0
    return value
}

private fun thin(planes: List<VisiblePlane>, view: ViewBox, pins: Set<String>): List<VisiblePlane> {
    val latSpan = (view.north - view.south).coerceAtLeast(0.5)
    val crosses = view.west > view.east
    val lonSpan = (if (crosses) 360.0 - view.west + view.east else view.east - view.west).coerceAtLeast(0.5)
    val latCell = latSpan / 12.0
    val lonCell = lonSpan / 24.0
    val cells = HashMap<Long, VisiblePlane>(288)
    val pinned = ArrayList<VisiblePlane>(4)
    for (plane in planes) {
        if (plane.hex in pins) {
            pinned.add(plane)
            continue
        }
        val row = floor((plane.lat - view.south) / latCell).toLong()
        val lon0 = if (crosses && plane.lon < view.west) plane.lon + 360.0 else plane.lon
        val col = floor((lon0 - view.west) / lonCell).toLong()
        val key = (row shl 32) xor (col and 0xffffffffL)
        val prev = cells[key]
        if (prev == null || plane.hex < prev.hex) cells[key] = plane
    }
    val out = ArrayList<VisiblePlane>(cells.size + pinned.size)
    out.addAll(cells.values)
    out.addAll(pinned)
    return out
}

internal fun distanceNm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = p2 - p1
    val dl = Math.toRadians(lon2 - lon1)
    val a = kotlin.math.sin(dp / 2) * kotlin.math.sin(dp / 2) + kotlin.math.cos(p1) * kotlin.math.cos(p2) * kotlin.math.sin(dl / 2) * kotlin.math.sin(dl / 2)
    return 2 * 3440.065 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
}

private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dl = Math.toRadians(lon2 - lon1)
    val y = kotlin.math.sin(dl) * kotlin.math.cos(p2)
    val x = kotlin.math.cos(p1) * kotlin.math.sin(p2) - kotlin.math.sin(p1) * kotlin.math.cos(p2) * kotlin.math.cos(dl)
    return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
}

private fun angleGap(a: Double, b: Double): Double {
    val d = abs(((a - b) % 360.0 + 540.0) % 360.0 - 180.0)
    return d
}

fun classifyTraffic(airport: Airport, near: List<VisiblePlane>): AirportTraffic {
    val numbers = java.text.NumberFormat.getIntegerInstance(Locale.US)
    val departures = ArrayList<TrafficRow>()
    val arrivals = ArrayList<TrafficRow>()
    val ground = ArrayList<TrafficRow>()
    for (plane in near) {
        val nm = distanceNm(airport.lat, airport.lon, plane.lat, plane.lon)
        val row = TrafficRow(
            plane.hex,
            plane.callsign.ifBlank { plane.hex.uppercase(Locale.US) },
            plane.typeCode.ifBlank { "—" },
            if (isOnGround(plane.altFt, plane.gsKt)) "On ground" else "${numbers.format(plane.altFt.roundToInt())} ft",
            nm.roundToInt(),
        )
        if (nm < 4.0 && (plane.altFt < 300 || plane.gsKt < 60)) {
            ground += row
            continue
        }
        if (nm > 40.0 || plane.altFt > 14_000) continue
        val toField = bearing(plane.lat, plane.lon, airport.lat, airport.lon)
        if (angleGap(plane.trackDeg, toField) < 50.0) arrivals += row
        else if (angleGap(plane.trackDeg, (toField + 180.0) % 360.0) < 50.0) departures += row
    }
    return AirportTraffic(
        departures.sortedBy { it.distanceNm },
        arrivals.sortedBy { it.distanceNm },
        ground.sortedBy { it.distanceNm },
    )
}


fun isOnGround(altFt: Double, gsKt: Double): Boolean = altFt < 50 || (altFt < 300 && gsKt < 50)
