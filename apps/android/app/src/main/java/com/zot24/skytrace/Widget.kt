package com.zot24.skytrace

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.wrapContentHeight
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private data class WidgetLine(val kind: String, val id: String, val title: String, val first: String, val second: String)

object WidgetSync {
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("skytrace-widget", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private val metarCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
    private val numbers = java.text.NumberFormat.getIntegerInstance(Locale.US)

    /** [planes] is the app's live fleet. Null means a background refresh that fetches the feed itself. */
    suspend fun refresh(context: Context, planes: List<VisiblePlane>?) = withContext(Dispatchers.IO) {
        val prefs = context.skyStore.data.first()
        val favAircraft = prefs[keyAircraft].orEmpty()
        val favAirports = prefs[keyAirports].orEmpty()
        val fleet: List<VisiblePlane> = when {
            favAircraft.isEmpty() && favAirports.isEmpty() -> emptyList()
            planes != null -> planes
            else -> backgroundRows(prefs[keyUrl], prefs[keyDebugSynthetic] ?: 0)
        }
        val lines = ArrayList<String>()
        if (favAirports.isNotEmpty()) {
            val airports = parseAirports(context.assets.open("airports.json").bufferedReader().use { it.readText() })
            for (airport in airports.filter { it.icao in favAirports }.take(3)) {
                val weather = metar(airport.icao)?.let { decodeMetar(it) }
                val first = if (weather == null) "Weather unavailable" else {
                    listOfNotNull(weather.temperatureC?.let { "$it°C" }, "wind ${weather.wind}").joinToString(" · ")
                }
                val near = fleet.filter { kotlin.math.abs(it.lat - airport.lat) < 0.8 && kotlin.math.abs(it.lon - airport.lon) < 1.1 }
                val t = classifyTraffic(airport, near)
                val second = "${t.departures.size} dep · ${t.arrivals.size} arr · ${t.ground.size} on ground"
                lines += listOf("A", airport.icao, airport.iata.ifBlank { airport.icao }, first, second).joinToString("\t")
            }
        }
        val byHex = fleet.associateBy { it.hex }
        for (hex in favAircraft.sorted().take(3)) {
            val plane = byHex[hex]
            val title = plane?.callsign?.ifBlank { null } ?: hex.uppercase(Locale.US)
            val first: String
            val second: String
            when {
                plane == null -> { first = "Not in range"; second = "" }
                isOnGround(plane.altFt, plane.gsKt) -> { first = "On ground"; second = plane.typeCode }
                else -> {
                    first = "Flying · ${numbers.format(plane.altFt.roundToInt())} ft · ${plane.gsKt.roundToInt()} kt"
                    second = plane.typeCode
                }
            }
            lines += listOf("P", hex, title, first, second).joinToString("\t")
        }
        context.skyStore.edit {
            it[keyWidgetSnapshot] = lines.joinToString("\n")
            it[keyWidgetUpdated] = System.currentTimeMillis()
        }
        FavouritesWidget().updateAll(context)
    }

    private fun metar(icao: String): String? {
        val now = System.currentTimeMillis()
        metarCache[icao]?.let { if (now - it.first < 10 * 60_000L) return it.second }
        return try {
            val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
            client.newCall(Request.Builder().url("https://aviationweather.gov/api/data/metar?ids=$icao&format=raw").build()).execute().use { response ->
                val text = response.body?.string()?.trim().orEmpty()
                if (response.isSuccessful && text.isNotBlank()) text.also { metarCache[icao] = now to it } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun backgroundRows(base: String?, synthetic: Int): List<VisiblePlane> {
        val rows: List<PlaneRow> = if (BuildConfig.DEBUG && synthetic > 0) {
            SyntheticTraffic.planes = synthetic
            SyntheticTraffic.rows(System.currentTimeMillis())
        } else {
            try {
                val url = (base?.ifBlank { null } ?: DEFAULT_BASE).trimEnd('/') + "/api/planes"
                val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    parseFeed(response.body?.string().orEmpty())?.rows.orEmpty()
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        return rows.map {
            VisiblePlane(it.hex, it.callsign, it.typeCode, it.reg, it.altFt, it.gsKt, it.trackDeg, it.lat, it.lon, 1f, silhouetteFor(it.typeCode, it.category))
        }
    }
}

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WidgetSync.refresh(applicationContext, null)
        return Result.success()
    }
}

class FavouritesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FavouritesWidget()
}

class FavouritesWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = context.skyStore.data.first()
        provideContent {
            // Read inside the composition: provideGlance runs once per session, so values captured above it went
            // stale while the session lived and updateAll() redrew the old snapshot.
            val prefs = context.skyStore.data.collectAsState(initial).value
            val lines = prefs[keyWidgetSnapshot].orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { raw ->
                val f = raw.split('\t')
                if (f.size == 5) WidgetLine(f[0], f[1], f[2], f[3], f[4]) else null
            }
            val updated = prefs[keyWidgetUpdated] ?: 0L
            val ago = if (updated == 0L) "not updated yet" else {
                val minutes = ((System.currentTimeMillis() - updated) / 60_000L).toInt()
                if (minutes < 1) "updated just now" else "updated $minutes min ago"
            }
            Column(
                GlanceModifier.fillMaxWidth().wrapContentHeight().background(Color(0xF0080B10)).cornerRadius(16.dp).padding(12.dp),
            ) {
                Row(GlanceModifier.fillMaxWidth()) {
                    Text("skytrace", style = TextStyle(color = ColorProvider(Color(0xFFE8EEF6)), fontSize = 13.sp, fontWeight = FontWeight.Bold))
                    Text("  $ago", style = TextStyle(color = ColorProvider(Color(0xFF93A1B3)), fontSize = 11.sp))
                }
                if (lines.isEmpty()) {
                    Text("Star an airport or aircraft in the app", style = TextStyle(color = ColorProvider(Color(0xFF93A1B3)), fontSize = 12.sp))
                }
                for (line in lines) {
                    val path = if (line.kind == "A") "airport" else "aircraft"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("skytrace://$path/${line.id}"), context, MainActivity::class.java)
                    Column(GlanceModifier.fillMaxWidth().padding(top = 6.dp).clickable(actionStartActivity(intent))) {
                        Row {
                            Text(
                                (if (line.kind == "A") "✈︎ " else "★ ") + line.title,
                                style = TextStyle(color = ColorProvider(Color(0xFFE6B15A)), fontSize = 13.sp, fontWeight = FontWeight.Bold),
                            )
                            Text("  " + line.first, style = TextStyle(color = ColorProvider(Color(0xFFE8EEF6)), fontSize = 12.sp), maxLines = 1)
                        }
                        if (line.second.isNotBlank()) {
                            Text(line.second, style = TextStyle(color = ColorProvider(Color(0xFF93A1B3)), fontSize = 11.sp), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
