package com.zot24.skytrace

import android.content.Context
import android.content.Intent
import android.net.Uri
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

private data class WidgetLine(val kind: String, val id: String, val title: String, val detail: String)

object WidgetSync {
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("skytrace-widget", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** [planes] comes from the running app. Null means a background refresh that fetches favourites itself. */
    suspend fun refresh(context: Context, planes: List<VisiblePlane>?) = withContext(Dispatchers.IO) {
        val prefs = context.skyStore.data.first()
        val favAircraft = prefs[keyAircraft].orEmpty()
        val favAirports = prefs[keyAirports].orEmpty()
        val lines = ArrayList<String>()
        if (favAirports.isNotEmpty()) {
            val airports = parseAirports(context.assets.open("airports.json").bufferedReader().use { it.readText() })
            for (airport in airports.filter { it.icao in favAirports }.take(4)) {
                lines += listOf("A", airport.icao, airport.iata.ifBlank { airport.icao }, airport.name).joinToString("\t")
            }
        }
        val rows: List<Triple<String, String, Double>> = when {
            favAircraft.isEmpty() -> emptyList()
            planes != null -> planes.map { Triple(it.hex, it.callsign, it.altFt) }
            else -> backgroundRows(context, prefs[keyUrl], prefs[keyDebugSynthetic] ?: 0, favAircraft)
        }
        val seen = rows.associateBy { it.first }
        for (hex in favAircraft.sorted().take(4)) {
            val row = seen[hex]
            val title = row?.second?.ifBlank { null } ?: hex.uppercase(Locale.US)
            val detail = if (row == null) "not in range" else "${"%,d".format(Locale.US, row.third.roundToInt())} ft"
            lines += listOf("P", hex, title, detail).joinToString("\t")
        }
        context.skyStore.edit {
            it[keyWidgetSnapshot] = lines.joinToString("\n")
            it[keyWidgetUpdated] = System.currentTimeMillis()
        }
        FavouritesWidget().updateAll(context)
    }

    private fun backgroundRows(context: Context, base: String?, synthetic: Int, favs: Set<String>): List<Triple<String, String, Double>> {
        if (BuildConfig.DEBUG && synthetic > 0) {
            SyntheticTraffic.planes = synthetic
            return SyntheticTraffic.rows(System.currentTimeMillis()).filter { it.hex in favs }.map { Triple(it.hex, it.callsign, it.altFt) }
        }
        return try {
            val url = (base?.ifBlank { null } ?: DEFAULT_BASE).trimEnd('/') + "/api/planes"
            val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = parseFeed(response.body?.string().orEmpty()) ?: return emptyList()
                body.rows.filter { it.hex in favs }.map { Triple(it.hex, it.callsign, it.altFt) }
            }
        } catch (_: Exception) {
            emptyList()
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
        val prefs = context.skyStore.data.first()
        val lines = prefs[keyWidgetSnapshot].orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { raw ->
            val f = raw.split('\t')
            if (f.size == 4) WidgetLine(f[0], f[1], f[2], f[3]) else null
        }
        val updated = prefs[keyWidgetUpdated] ?: 0L
        val ago = if (updated == 0L) "not updated yet" else {
            val minutes = ((System.currentTimeMillis() - updated) / 60_000L).toInt()
            if (minutes < 1) "updated just now" else "updated $minutes min ago"
        }
        provideContent {
            Column(
                GlanceModifier.fillMaxSize().background(Color(0xF0080B10)).cornerRadius(16.dp).padding(12.dp),
            ) {
                Row(GlanceModifier.fillMaxWidth()) {
                    Text("skytrace favourites", style = TextStyle(color = ColorProvider(Color(0xFFE8EEF6)), fontSize = 13.sp, fontWeight = FontWeight.Bold))
                }
                Text(ago, style = TextStyle(color = ColorProvider(Color(0xFF93A1B3)), fontSize = 11.sp))
                Spacer(GlanceModifier.height(6.dp))
                if (lines.isEmpty()) {
                    Text("Star an airport or aircraft in the app", style = TextStyle(color = ColorProvider(Color(0xFF93A1B3)), fontSize = 12.sp))
                }
                for (line in lines) {
                    val path = if (line.kind == "A") "airport" else "aircraft"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("skytrace://$path/${line.id}"), context, MainActivity::class.java)
                    Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp).clickable(actionStartActivity(intent))) {
                        Text(
                            (if (line.kind == "A") "✈︎ " else "★ ") + line.title,
                            style = TextStyle(color = ColorProvider(Color(0xFFE6B15A)), fontSize = 13.sp, fontWeight = FontWeight.Bold),
                        )
                        Text("  " + line.detail, style = TextStyle(color = ColorProvider(Color(0xFFE8EEF6)), fontSize = 12.sp), maxLines = 1)
                    }
                }
            }
        }
    }
}
