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

private val Application.store by preferencesDataStore("skytrace")
private val keyUrl = stringPreferencesKey("base_url")
private val keyCamera = stringPreferencesKey("camera")
private val keyAircraft = stringSetPreferencesKey("fav_aircraft")
private val keyAirports = stringSetPreferencesKey("fav_airports")
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
    val speed: String,
    val heading: String,
)

data class FramePicture(
    val planes: List<VisiblePlane>,
    val trails: List<List<Pair<Double, Double>>>,
    val total: Int,
)

class SkyViewModel(app: Application) : AndroidViewModel(app) {
    val fleet = Fleet()
    var source by mutableStateOf("connecting")
    var count by mutableStateOf(0)
    var selected by mutableStateOf<String?>(null)
    var detail by mutableStateOf<PlaneDetail?>(null)
    var baseUrl by mutableStateOf(DEFAULT_BASE)
    var planeSymbols: PlaneSymbols? = null
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
            val prefs = getApplication<Application>().store.data.first()
            applyPrefs(prefs[keyUrl], prefs[keyAircraft], prefs[keyAirports])
            val saved = parseCamera(prefs[keyCamera])
            val cam = saved ?: BUSY_OPENING
            lastSavedCamera = if (saved != null) formatCamera(cam) else null
            cameraWritable = true
            opening.value = cam
            launch {
                getApplication<Application>().store.data.collect { next ->
                    applyPrefs(next[keyUrl], next[keyAircraft], next[keyAirports])
                }
            }
            pollLoop()
        }
        viewModelScope.launch(Dispatchers.IO) {
            val text = getApplication<Application>().assets.open("airports.json").bufferedReader().use { it.readText() }
            val parsed = parseAirports(text)
            withContext(Dispatchers.Main) { airports = parsed }
        }
    }

    fun saveBaseUrl(raw: String) {
        val trimmed = raw.trim().ifBlank { DEFAULT_BASE }
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
        viewModelScope.launch {
            getApplication<Application>().store.edit { it[keyUrl] = withScheme.trimEnd('/') }
        }
    }

    fun toggleAircraft(hex: String) = toggle(keyAircraft, hex)

    fun toggleAirport(icao: String) = toggle(keyAirports, icao)

    fun focusAirport(airport: Airport) {
        camera.tryEmit(CameraJump(airport.lat, airport.lon, 11.0))
    }

    fun focusAircraft(hex: String) {
        val plane = fleet.one(hex, System.currentTimeMillis()) ?: return
        selected = hex
        detail = toDetail(plane)
        camera.tryEmit(CameraJump(plane.lat, plane.lon, 7.0))
    }

    fun onPlaneHit(hex: String?, now: Long) {
        if (hex.isNullOrEmpty()) return
        val plane = fleet.one(hex, now) ?: return
        selected = hex
        detail = toDetail(plane)
    }

    fun rememberView(lat: Double, lon: Double, zoom: Double, pinned: Boolean) {
        if (!cameraWritable) return
        if (!pinned && !nearOpening(lat, lon, zoom)) return
        val encoded = formatCamera(CameraJump(lat, lon, zoom))
        if (encoded == lastSavedCamera) return
        lastSavedCamera = encoded
        viewModelScope.launch {
            getApplication<Application>().store.edit { it[keyCamera] = encoded }
        }
    }

    fun closeDetail() {
        selected = null
        detail = null
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
        val inView = fleet.visible(now, padded.south, padded.north, padded.west, padded.east, hex)
        val planes = if (zoom < PLANE_CULL_ZOOM) thin(inView, padded, hex) else inView
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
        return FramePicture(planes, trails, fleet.tracked(now))
    }

    private fun toggle(key: androidx.datastore.preferences.core.Preferences.Key<Set<String>>, id: String) {
        viewModelScope.launch {
            getApplication<Application>().store.edit { prefs ->
                val cur = prefs[key]?.toMutableSet() ?: mutableSetOf()
                if (!cur.add(id)) cur.remove(id)
                prefs[key] = cur
            }
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
            fleet.accept(body.rows, now, fresh = true)
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

    private fun toDetail(plane: VisiblePlane): PlaneDetail {
        val heading = ((plane.trackDeg % 360.0) + 360.0) % 360.0
        return PlaneDetail(
            hex = plane.hex,
            title = plane.callsign.ifBlank { plane.hex.uppercase(Locale.US) },
            type = plane.typeCode.ifBlank { "—" },
            reg = plane.reg.ifBlank { "—" },
            altitude = "${numbers.format(plane.altFt.roundToInt())} ft",
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

private fun thin(planes: List<VisiblePlane>, view: ViewBox, alwaysHex: String?): List<VisiblePlane> {
    val latSpan = (view.north - view.south).coerceAtLeast(0.5)
    val crosses = view.west > view.east
    val lonSpan = (if (crosses) 360.0 - view.west + view.east else view.east - view.west).coerceAtLeast(0.5)
    val latCell = latSpan / 12.0
    val lonCell = lonSpan / 24.0
    val cells = HashMap<Long, VisiblePlane>(288)
    var always: VisiblePlane? = null
    for (plane in planes) {
        if (plane.hex == alwaysHex) always = plane
        val row = floor((plane.lat - view.south) / latCell).toLong()
        val lon0 = if (crosses && plane.lon < view.west) plane.lon + 360.0 else plane.lon
        val col = floor((lon0 - view.west) / lonCell).toLong()
        val key = (row shl 32) xor (col and 0xffffffffL)
        val prev = cells[key]
        if (prev == null || plane.hex < prev.hex) cells[key] = plane
    }
    if (always == null) return cells.values.toList()
    if (cells.values.any { it.hex == always.hex }) return cells.values.toList()
    val out = ArrayList<VisiblePlane>(cells.size + 1)
    out.addAll(cells.values)
    out.add(always)
    return out
}
