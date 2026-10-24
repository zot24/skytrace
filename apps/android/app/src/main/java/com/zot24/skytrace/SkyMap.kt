package com.zot24.skytrace

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import java.util.Locale

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/dark"
private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
private const val FPS_TAG = "skytrace-fps"

@Composable
fun SkyMap(
    vm: SkyViewModel,
    modifier: Modifier = Modifier,
    onFpsLine: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context) }
    val holder = remember { MapHolder() }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> if (!holder.created) {
                    mapView.onCreate(null)
                    holder.created = true
                }
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            holder.map?.setOnFpsChangedListener(null)
            holder.callback?.let { Choreographer.getInstance().removeFrameCallback(it) }
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        vm.camera.collect { jump ->
            holder.map?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(jump.lat, jump.lon), jump.zoom),
                1400,
            )
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            mapView.getMapAsync { map ->
                holder.map = map
                if (BuildConfig.DEBUG) {
                    // MapLibre invokes this on the GL thread. The header state is main-thread only.
                    map.setOnFpsChangedListener { fps ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - holder.lastFpsLogMs < 1_000L) return@setOnFpsChangedListener
                        holder.lastFpsLogMs = now
                        mapView.post {
                            val line = formatFpsLine(fps, holder.drawnPlanes)
                            Log.i(FPS_TAG, line)
                            onFpsLine?.invoke(line)
                        }
                    }
                }
                map.uiSettings.isLogoEnabled = false
                map.uiSettings.isAttributionEnabled = false
                map.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                    install(style, vm.airports, mapView.context)
                    holder.airportsSent = vm.airports.isNotEmpty()
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(24.0, 12.0), 1.8))
                    val planes = style.getSourceAs<GeoJsonSource>("planes")
                    val trail = style.getSourceAs<GeoJsonSource>("trail")
                    val callback = object : Choreographer.FrameCallback {
                        override fun doFrame(frameTimeNanos: Long) {
                            val now = System.currentTimeMillis()
                            val bounds = map.projection.visibleRegion.latLngBounds
                            val picture = vm.prepareFrame(
                                now,
                                ViewBox(
                                    bounds.latitudeSouth,
                                    bounds.latitudeNorth,
                                    bounds.longitudeWest,
                                    bounds.longitudeEast,
                                ),
                            )
                            if (!holder.airportsSent && vm.airports.isNotEmpty()) {
                                style.getSourceAs<GeoJsonSource>("airports")?.setGeoJson(airportsJson(vm.airports))
                                holder.airportsSent = true
                            }
                            planes?.setGeoJson(planesJson(picture.planes, vm.selected))
                            trail?.setGeoJson(trailJson(picture.trails))
                            holder.drawnPlanes = picture.planes.size
                            Choreographer.getInstance().postFrameCallback(this)
                        }
                    }
                    holder.callback = callback
                    Choreographer.getInstance().postFrameCallback(callback)
                }
                map.addOnMapClickListener { latLng ->
                    val screen = map.projection.toScreenLocation(latLng)
                    vm.onTap(screen.x, screen.y, System.currentTimeMillis()) { lon, lat ->
                        map.projection.toScreenLocation(LatLng(lat, lon))
                    }
                    true
                }
            }
            mapView
        },
    )
}

private class MapHolder {
    var map: MapLibreMap? = null
    var callback: Choreographer.FrameCallback? = null
    var created: Boolean = false
    var airportsSent: Boolean = false
    var drawnPlanes: Int = 0
    var lastFpsLogMs: Long = 0L
}

private fun formatFpsLine(fps: Double, planes: Int): String {
    return String.format(Locale.US, "fps=%.1f planes=%d", fps, planes)
}

private fun install(style: Style, airports: List<Airport>, context: Context) {
    for (kind in Silhouette.entries) {
        style.addImage(kind.name, aircraftIcon(context, kind, selected = false))
        style.addImage("${kind.name}-sel", aircraftIcon(context, kind, selected = true))
    }
    style.addSource(GeoJsonSource("airports", airportsJson(airports)))
    style.addSource(GeoJsonSource("trail", EMPTY))
    style.addSource(GeoJsonSource("planes", EMPTY))

    val large = CircleLayer("airports-large", "airports").withFilter(
        Expression.eq(Expression.get("k"), Expression.literal("L")),
    ).withProperties(
        PropertyFactory.circleColor("#e4b15a"),
        PropertyFactory.circleOpacity(0.92f),
        PropertyFactory.circleStrokeWidth(1f),
        PropertyFactory.circleStrokeColor("rgba(0,0,0,0.5)"),
        PropertyFactory.circleRadius(
            Expression.interpolate(
                Expression.linear(),
                Expression.zoom(),
                Expression.stop(1.5, 1.7),
                Expression.stop(4, 3.2),
                Expression.stop(8, 5.5),
            ),
        ),
    )
    val medium = CircleLayer("airports-medium", "airports").withFilter(
        Expression.eq(Expression.get("k"), Expression.literal("M")),
    ).withProperties(
        PropertyFactory.circleColor("#8ea0b5"),
        PropertyFactory.circleOpacity(0.8f),
        PropertyFactory.circleStrokeWidth(0.6f),
        PropertyFactory.circleStrokeColor("rgba(0,0,0,0.45)"),
        PropertyFactory.circleRadius(
            Expression.interpolate(
                Expression.linear(),
                Expression.zoom(),
                Expression.stop(4, 1.6),
                Expression.stop(7, 3),
                Expression.stop(11, 4.5),
            ),
        ),
    )
    medium.minZoom = 4f
    val labels = SymbolLayer("airport-labels", "airports").withFilter(
        Expression.eq(Expression.get("k"), Expression.literal("L")),
    ).withProperties(
        PropertyFactory.textField(
            Expression.switchCase(
                Expression.neq(Expression.get("iata"), Expression.literal("")),
                Expression.get("iata"),
                Expression.get("icao"),
            ),
        ),
        PropertyFactory.textSize(11f),
        PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
        PropertyFactory.textOffset(arrayOf(0f, 0.7f)),
        PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
        PropertyFactory.textColor("#e7eef6"),
        PropertyFactory.textHaloColor("#0c1016"),
        PropertyFactory.textHaloWidth(1.2f),
        PropertyFactory.textAllowOverlap(false),
    )
    labels.minZoom = 6f

    fun line(id: String, color: String, opacity: Float, blur: Float, vararg stops: Expression.Stop): LineLayer {
        return LineLayer(id, "trail").withProperties(
            PropertyFactory.lineColor(color),
            PropertyFactory.lineOpacity(opacity),
            PropertyFactory.lineBlur(blur),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineWidth(
                Expression.interpolate(Expression.linear(), Expression.zoom(), *stops),
            ),
        )
    }

    val planes = SymbolLayer("plane-symbols", "planes").withProperties(
        PropertyFactory.iconImage(Expression.get("icon")),
        PropertyFactory.iconRotate(Expression.get("hdg")),
        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
        PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
        PropertyFactory.iconOpacity(Expression.get("op")),
        PropertyFactory.iconSize(
            Expression.interpolate(
                Expression.linear(),
                Expression.zoom(),
                scaledStop(2.0, 0.42),
                scaledStop(6.0, 0.58),
                scaledStop(11.0, 0.78),
                scaledStop(14.0, 1.0),
            ),
        ),
    )
    style.addLayer(large)
    style.addLayer(medium)
    style.addLayer(labels)
    style.addLayer(line("trail-glow", "#ffb020", 0.35f, 1.5f, Expression.stop(1, 10), Expression.stop(6, 14), Expression.stop(10, 18), Expression.stop(14, 24)))
    style.addLayer(line("trail-casing", "#1a1004", 0.95f, 0f, Expression.stop(1, 6), Expression.stop(6, 8), Expression.stop(10, 11), Expression.stop(14, 15)))
    style.addLayer(line("trail-core", "#ffc14a", 1f, 0f, Expression.stop(1, 3), Expression.stop(6, 4), Expression.stop(10, 5.5), Expression.stop(14, 7.5)))
    style.addLayer(planes)
}

private fun iconScaleExpression(): Expression {
    val stops = mutableListOf<Expression.Stop>()
    for (kind in Silhouette.entries) {
        val scale = iconScale(kind)
        if (scale != 1.0) {
            stops += Expression.stop(kind.name, scale)
            stops += Expression.stop("${kind.name}-sel", scale)
        }
    }
    return Expression.match(Expression.get("icon"), Expression.literal(1.0), *stops.toTypedArray())
}

private fun scaledStop(zoom: Double, size: Double): Expression.Stop {
    return Expression.stop(zoom, Expression.product(Expression.literal(size), iconScaleExpression()))
}

private fun airportsJson(airports: List<Airport>): String {
    val sb = StringBuilder(airports.size * 96)
    sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
    airports.forEachIndexed { index, airport ->
        if (index > 0) sb.append(',')
        sb.append("{\"type\":\"Feature\",\"properties\":{\"k\":\"")
        sb.append(airport.kind)
        sb.append("\",\"iata\":\"")
        sb.append(jsonEscape(airport.iata))
        sb.append("\",\"icao\":\"")
        sb.append(jsonEscape(airport.icao))
        sb.append("\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
        sb.append(airport.lon)
        sb.append(',')
        sb.append(airport.lat)
        sb.append("]}}")
    }
    sb.append("]}")
    return sb.toString()
}

private fun planesJson(planes: List<VisiblePlane>, selected: String?): String {
    val sb = StringBuilder(planes.size * 140)
    sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
    planes.forEachIndexed { index, plane ->
        if (index > 0) sb.append(',')
        val icon = if (plane.hex == selected) "${plane.silhouette.name}-sel" else plane.silhouette.name
        sb.append("{\"type\":\"Feature\",\"properties\":{\"icon\":\"")
        sb.append(icon)
        sb.append("\",\"hdg\":")
        sb.append(plane.trackDeg)
        sb.append(",\"op\":")
        sb.append(plane.opacity)
        sb.append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
        sb.append(plane.lon)
        sb.append(',')
        sb.append(plane.lat)
        sb.append("]}}")
    }
    sb.append("]}")
    return sb.toString()
}

private fun trailJson(segments: List<List<Pair<Double, Double>>>): String {
    val sb = StringBuilder()
    sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
    var written = 0
    for (segment in segments) {
        if (segment.size < 2) continue
        if (written > 0) sb.append(',')
        written += 1
        sb.append("{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
        segment.forEachIndexed { index, point ->
            if (index > 0) sb.append(',')
            sb.append('[')
            sb.append(point.first)
            sb.append(',')
            sb.append(point.second)
            sb.append(']')
        }
        sb.append("]}}")
    }
    sb.append("]}")
    return sb.toString()
}

private fun jsonEscape(value: String): String {
    return value.replace("\\", "\\\\").replace("\"", "\\\"")
}
