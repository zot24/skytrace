package com.zot24.skytrace

import android.app.Application
import android.graphics.PointF
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
import kotlin.math.roundToInt

const val DEFAULT_BASE = "http://10.0.2.2:8787"

private val Application.store by preferencesDataStore("skytrace")
private val keyUrl = stringPreferencesKey("base_url")
private val keyAircraft = stringSetPreferencesKey("fav_aircraft")
private val keyAirports = stringSetPreferencesKey("fav_airports")

data class ViewBox(val south: Double, val north: Double, val west: Double, val east: Double)

data class CameraJump(val lat: Double, val lon: Double, val zoom: Double)

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
)

class SkyViewModel(app: Application) : AndroidViewModel(app) {
    val fleet = Fleet()
    var source by mutableStateOf("connecting")
    var count by mutableStateOf(0)
    var selected by mutableStateOf<String?>(null)
    var detail by mutableStateOf<PlaneDetail?>(null)
    var baseUrl by mutableStateOf(DEFAULT_BASE)
    var airports by mutableStateOf<List<Airport>>(emptyList())
    val favouriteAircraft = MutableStateFlow<Set<String>>(emptySet())
    val favouriteAirports = MutableStateFlow<Set<String>>(emptySet())
    val camera = MutableSharedFlow<CameraJump>(extraBufferCapacity = 1)

    private var box: ViewBox? = null
    private var attempt = 0
    private var lastUi = 0L
    private var lastTrail = 0L
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    init {
        viewModelScope.launch {
            val prefs = getApplication<Application>().store.data.first()
            applyPrefs(prefs[keyUrl], prefs[keyAircraft], prefs[keyAirports])
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

    fun onTap(x: Float, y: Float, now: Long, project: (Double, Double) -> PointF) {
        var best: String? = null
        var bestD = 36f * 36f
        for (plane in fleet.visible(now)) {
            val point = project(plane.lon, plane.lat)
            val dx = point.x - x
            val dy = point.y - y
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = plane.hex
            }
        }
        selected = best
        detail = best?.let { fleet.one(it, now) }?.let { toDetail(it) }
    }

    fun closeDetail() {
        selected = null
        detail = null
    }

    fun prepareFrame(now: Long, view: ViewBox): FramePicture {
        box = view
        val hex = selected
        if (now - lastUi >= 250L) {
            lastUi = now
            count = fleet.tracked(now)
            val plane = hex?.let { fleet.one(it, now) }
            if (hex != null && plane == null) {
                selected = null
                detail = null
            } else if (plane != null) {
                detail = toDetail(plane)
            }
        }
        val planes = fleet.visible(now, view.south - 2.0, view.north + 2.0, view.west - 2.0, view.east + 2.0, hex)
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
        return FramePicture(planes, trails)
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
            return nextPollDelay(false, attempt, 0L)
        }
        val (code, body) = fetched
        if (body == null) {
            attempt += 1
            markStale(source, now)
            return nextPollDelay(false, attempt, 0L)
        }
        val fresh = code in 200..299 && !body.stale && body.src != "none" && body.rows.isNotEmpty()
        if (fresh) {
            fleet.accept(body.rows, now, fresh = true)
            attempt = 0
            source = body.src.ifBlank { "adsb.lol" }
            count = fleet.tracked(now)
            refreshDetail(now)
            return nextPollDelay(true, 0, 0L)
        }
        fleet.accept(body.rows, now, fresh = false)
        attempt += 1
        val label = body.src.takeIf { it.isNotBlank() && it != "none" } ?: source
        markStale(label, now)
        return nextPollDelay(false, attempt, body.retryAfterMs)
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
