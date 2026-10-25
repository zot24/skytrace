package com.zot24.skytrace

import android.content.Context
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.geojson.Point
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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

private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
private const val FPS_TAG = "skytrace-fps"
private const val STYLE_TAG = "skytrace-style"

@Composable
fun SkyMap(
    vm: SkyViewModel,
    modifier: Modifier = Modifier,
    onFpsLine: ((String) -> Unit)? = null,
    onUserGesture: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context).also { MapTouchRouter.view = it } }
    val holder = remember { MapHolder() }
    val onUserGestureState = rememberUpdatedState(onUserGesture)
    val geoExecutor = remember {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "skytrace-geo").apply { isDaemon = true }
        }
    }

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
            holder.alive = false
            holder.frameListener?.let { mapView.removeOnDidFinishRenderingFrameListener(it) }
            holder.callback?.let { Choreographer.getInstance().removeFrameCallback(it) }
            geoExecutor.shutdownNow()
            vm.planeSymbols = null
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            if (MapTouchRouter.view === mapView) MapTouchRouter.view = null
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        vm.opening.collect { jump ->
            holder.desired = jump
            holder.map?.let { applyOpening(it, holder) }
        }
    }

    LaunchedEffect(mapView) {
        vm.mapStyle.collect { wanted ->
            holder.wantedStyle = wanted
            holder.map?.let { loadStyle(it, holder, vm, mapView) }
        }
    }

    LaunchedEffect(mapView) {
        vm.camera.collect { jump ->
            holder.followJump = true
            holder.map?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(jump.lat, jump.lon), jump.zoom),
                1400,
            )
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            mapView.isFocusableInTouchMode = true
            mapView.setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) MapTouchRouter.mapSawDown = true
                val action = event.actionMasked
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
                    holder.touchX = event.x
                    holder.touchY = event.y
                    holder.touchAtMs = SystemClock.elapsedRealtime()
                }
                false
            }
            mapView.getMapAsync { map ->
                SatellitePrefetch.setUpCache(mapView.context)
                holder.map = map
                map.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                        holder.userMoved = true
                        vm.stopFollow()
                        onUserGestureState.value.invoke()
                    }
                }
                map.addOnCameraIdleListener {
                    if (BuildConfig.DEBUG) {
                        val c = map.cameraPosition
                        Log.i("skytrace-cam", "idle zoom=%.2f bearing=%.1f tilt=%.1f".format(java.util.Locale.US, c.zoom, c.bearing, c.tilt))
                    }
                    if (!holder.appliedOpening) return@addOnCameraIdleListener
                    if (holder.loadedStyle == MapStyle.SATELLITE) SatellitePrefetch.maybePrefetch(mapView.context, map)
                    val target = map.cameraPosition.target ?: return@addOnCameraIdleListener
                    vm.rememberView(
                        target.latitude,
                        target.longitude,
                        map.cameraPosition.zoom,
                        holder.userMoved || holder.followJump,
                    )
                }
                // The map-level "finished rendering" event only fires when the map goes idle. The plane layer is
                // re-pushed every frame, so the map rarely goes idle and the cover used to wait for its timeout.
                // Each rendered frame reports whether every needed tile was drawn, so clear the cover on the first
                // fully drawn frame of the new style instead.
                mapView.addOnDidFinishRenderingFrameListener { fully, _, _ ->
                    val pending = holder.coverStyle ?: return@addOnDidFinishRenderingFrameListener
                    if (!fully || holder.style == null || holder.loadedStyle != pending) return@addOnDidFinishRenderingFrameListener
                    Log.i(STYLE_TAG, "style=$pending fullyRenderedMs=${SystemClock.elapsedRealtime() - holder.styleStartedMs}")
                    holder.coverStyle = null
                    vm.styleCover = null
                    vm.styleLoading = null
                }
                if (BuildConfig.DEBUG) {
                    val frameListener = MapView.OnDidFinishRenderingFrameListener { _, _, _ ->
                        noteRenderedFrame(mapView, holder, onFpsLine)
                    }
                    holder.frameListener = frameListener
                    mapView.addOnDidFinishRenderingFrameListener(frameListener)
                }
                map.uiSettings.isLogoEnabled = false
                map.uiSettings.isAttributionEnabled = false
                val planeJson = StringBuilder(64 * 1024)
                val trailJson = StringBuilder(4 * 1024)
                holder.onStyleReady = {
                    if (holder.loadedStyle == MapStyle.SATELLITE) SatellitePrefetch.maybePrefetch(mapView.context, map)
                    if (holder.callback == null) {
                    val callback = object : Choreographer.FrameCallback {
                        override fun doFrame(frameTimeNanos: Long) {
                            if (!holder.alive) return
                            val clock = SystemClock.elapsedRealtime()
                            val zoom = map.cameraPosition.zoom
                            val interval = if (zoom < PLANE_CULL_ZOOM) LOW_ZOOM_PUSH_MS else HIGH_ZOOM_PUSH_MS
                            // While the cover hides a style switch, stop re-pushing planes: each setGeoJson re-tiles the
                            // planes source, and a frame only counts as fully drawn when no source is mid-update. Pushing
                            // every frame kept "fully" false for seconds (Terrain timed out at 15 s under load).
                            if (holder.pushBusy || holder.coverStyle != null || clock - holder.lastPushMs < interval) {
                                Choreographer.getInstance().postFrameCallback(this)
                                return
                            }
                            holder.lastPushMs = clock
                            val bounds = map.projection.visibleRegion.latLngBounds
                            val view = ViewBox(
                                bounds.latitudeSouth,
                                bounds.latitudeNorth,
                                bounds.longitudeWest,
                                bounds.longitudeEast,
                            )
                            val style = holder.style
                            if (style == null) {
                                Choreographer.getInstance().postFrameCallback(this)
                                return
                            }
                            if (!holder.airportsSent && vm.airports.isNotEmpty()) {
                                style.getSourceAs<GeoJsonSource>("airports")?.setGeoJson(airportsJson(vm.airports))
                                holder.airportsSent = true
                            }
                            val follow = vm.followHex
                            if (follow != null && clock - holder.lastFollowMs >= 1_000L) {
                                holder.lastFollowMs = clock
                                vm.fleet.one(follow, System.currentTimeMillis())?.let { plane ->
                                    map.easeCamera(CameraUpdateFactory.newLatLng(LatLng(plane.lat, plane.lon)), 900)
                                }
                            }
                            val picture = vm.prepareFrame(System.currentTimeMillis(), view, zoom)
                            val selected = vm.selected
                            val favourites = vm.favouriteAircraft.value
                            val pins = HashSet<String>(favourites)
                            selected?.let { pins += it }
                            vm.followHex?.let { pins += it }
                            val signature = trailSignature(picture.trails) * 31 + pins.hashCode()
                            projectPlanes(map, picture.planes, holder)
                            if (holder.drawn.unchanged(picture.planes, holder.sx, holder.sy, selected, signature)) {
                                val maintained = SystemClock.elapsedRealtime()
                                if (maintained - holder.lastMaintainMs >= 1_000L) {
                                    holder.lastMaintainMs = maintained
                                    vm.maintainSelection(System.currentTimeMillis())
                                }
                                Choreographer.getInstance().postFrameCallback(this)
                                return
                            }
                            val screenX = holder.sx
                            val screenY = holder.sy
                            holder.pushBusy = true
                            try {
                                geoExecutor.execute {
                                    val built = try {
                                        appendPlaneJson(planeJson, picture.planes, selected, pins, favourites)
                                        val planeBody = planeJson.toString()
                                        appendTrailJson(trailJson, picture.trails)
                                        planeBody to trailJson.toString()
                                    } catch (error: Exception) {
                                        Log.e("skytrace-geo", "plane frame failed", error)
                                        null
                                    }
                                    val publish = Runnable {
                                        try {
                                            if (holder.alive && built != null) {
                                                // GeoJsonSource.setGeoJson requires the UI thread. The string was built on skytrace-geo.
                                                holder.planesSource?.setGeoJson(built.first)
                                                if (picture.trails.isNotEmpty() || !holder.trailClear) {
                                                    holder.trailSource?.setGeoJson(built.second)
                                                    holder.trailClear = picture.trails.isEmpty()
                                                }
                                                holder.drawn.commit(picture.planes, screenX, screenY, selected, signature)
                                                holder.drawnPlanes = picture.planes.size
                                                holder.totalPlanes = picture.total
                                                holder.inViewPlanes = picture.inView
                                                holder.pinsInView = picture.pinsInView
                                                holder.pinsDrawn = picture.pinsDrawn
                                                val maintained = SystemClock.elapsedRealtime()
                                                if (maintained - holder.lastMaintainMs >= 1_000L) {
                                                    holder.lastMaintainMs = maintained
                                                    vm.maintainSelection(System.currentTimeMillis())
                                                }
                                            }
                                        } finally {
                                            holder.pushBusy = false
                                        }
                                    }
                                    if (!mapView.post(publish)) holder.pushBusy = false
                                }
                            } catch (_: RejectedExecutionException) {
                                holder.pushBusy = false
                            }
                            Choreographer.getInstance().postFrameCallback(this)
                        }
                    }
                    holder.callback = callback
                    Choreographer.getInstance().postFrameCallback(callback)
                    }
                }
                loadStyle(map, holder, vm, mapView)
                map.addOnMapClickListener { latLng ->
                    val half = HIT_HALF_DP * mapView.resources.displayMetrics.density
                    val age = SystemClock.elapsedRealtime() - holder.touchAtMs
                    val tapX: Float
                    val tapY: Float
                    if (holder.touchAtMs != 0L && age in 0L..500L) {
                        tapX = holder.touchX
                        tapY = holder.touchY
                    } else {
                        val screen = map.projection.toScreenLocation(latLng)
                        tapX = screen.x
                        tapY = screen.y
                    }
                    // queryRenderedFeatures divides by the map pixel ratio. The rect stays in view pixels.
                    val box = RectF(tapX - half, tapY - half, tapX + half, tapY + half)
                    val queried = ArrayList<PlaneHit>(4)
                    for (feature in map.queryRenderedFeatures(box, PLANE_LAYER_ID)) {
                        val hex = feature.getStringProperty("hex") ?: continue
                        if (hex.isEmpty()) continue
                        val point = feature.geometry() as? Point
                        val screen = if (point == null) {
                            null
                        } else {
                            map.projection.toScreenLocation(LatLng(point.latitude(), point.longitude()))
                        }
                        queried.add(PlaneHit(hex, screen?.x ?: tapX, screen?.y ?: tapY))
                    }
                    val rendered = ArrayList<PlaneHit>(holder.drawnPlanes)
                    holder.drawn.forEachGeo { hex, latitude, longitude ->
                        val screen = map.projection.toScreenLocation(LatLng(latitude, longitude))
                        rendered.add(PlaneHit(hex, screen.x, screen.y))
                    }
                    val hits = planesInHitBox(tapX, tapY, half, rendered, queried)
                    if (hits.isNotEmpty()) {
                        vm.onPlaneHit(hits.map { it.hex }, System.currentTimeMillis())
                    } else {
                        val airport = map.queryRenderedFeatures(box, "airports-large", "airports-medium")
                            .firstNotNullOfOrNull { it.getStringProperty("icao")?.takeIf { icao -> icao.isNotEmpty() } }
                        if (airport != null) vm.openAirport(airport)
                    }
                    onUserGestureState.value.invoke()
                    true
                }
            }
            mapView
        },
    )
}

private class MapHolder {
    var map: MapLibreMap? = null
    var style: Style? = null
    var planesSource: GeoJsonSource? = null
    var trailSource: GeoJsonSource? = null
    var loadedStyle: MapStyle? = null
    var wantedStyle: MapStyle = MapStyle.DARK
    var onStyleReady: (() -> Unit)? = null
    var lastFollowMs: Long = 0L
    var styleStartedMs: Long = 0L
    var coverStyle: MapStyle? = null
    var inViewPlanes: Int = 0
    var pinsInView: Int = 0
    var pinsDrawn: Int = 0
    var callback: Choreographer.FrameCallback? = null
    var frameListener: MapView.OnDidFinishRenderingFrameListener? = null
    var created: Boolean = false
    var airportsSent: Boolean = false
    var drawnPlanes: Int = 0
    var totalPlanes: Int = 0
    var lastPushMs: Long = 0L
    var lastMaintainMs: Long = 0L
    var pushBusy: Boolean = false
    var alive: Boolean = true
    var appliedOpening: Boolean = false
    var userMoved: Boolean = false
    var followJump: Boolean = false
    var trailClear: Boolean = true
    var desired: CameraJump = BUSY_OPENING
    var touchX: Float = 0f
    var touchY: Float = 0f
    var touchAtMs: Long = 0L
    val drawn = DrawnIndex()
    var latLon = DoubleArray(0)
    var xy = DoubleArray(0)
    var sx = FloatArray(0)
    var sy = FloatArray(0)
    val renderedFrames = AtomicInteger(0)
    val frameWindowStartMs = AtomicLong(0L)
}

private fun loadStyle(map: MapLibreMap, holder: MapHolder, vm: SkyViewModel, mapView: MapView) {
    val wanted = holder.wantedStyle
    if (holder.loadedStyle == wanted) return
    val first = holder.loadedStyle == null
    holder.loadedStyle = wanted
    if (first) {
        applyStyle(map, holder, vm, mapView, wanted)
        return
    }
    // Keep a still of the old style on screen until the new one has drawn every tile.
    val t0 = SystemClock.elapsedRealtime()
    map.snapshot { bitmap ->
        if (!holder.alive || holder.loadedStyle != wanted) return@snapshot
        vm.styleCover = bitmap.asImageBitmap()
        vm.styleLoading = wanted
        Log.i(STYLE_TAG, "style=$wanted snapshotMs=${SystemClock.elapsedRealtime() - t0}")
        applyStyle(map, holder, vm, mapView, wanted)
    }
}

private fun applyStyle(map: MapLibreMap, holder: MapHolder, vm: SkyViewModel, mapView: MapView, wanted: MapStyle) {
    holder.style = null
    val started = SystemClock.elapsedRealtime()
    holder.styleStartedMs = started
    holder.coverStyle = wanted
    mapView.postDelayed({
        if (holder.coverStyle == wanted && vm.styleCover != null) {
            Log.i(STYLE_TAG, "style=$wanted cover timeout after ${SystemClock.elapsedRealtime() - started}ms")
            holder.coverStyle = null
            vm.styleCover = null
            vm.styleLoading = null
        }
    }, 15_000L)
    map.setStyle(styleBuilder(wanted)) { style ->
        Log.i(STYLE_TAG, "style=$wanted styleLoadedMs=${SystemClock.elapsedRealtime() - started}")
        if (holder.loadedStyle != wanted || !holder.alive) return@setStyle
        if (wanted == MapStyle.TERRAIN) addTerrain(style)
        vm.planeSymbols = install(style, vm.airports, mapView.context)
        holder.airportsSent = vm.airports.isNotEmpty()
        holder.planesSource = style.getSourceAs("planes")
        holder.trailSource = style.getSourceAs("trail")
        holder.trailClear = false
        holder.drawn.reset()
        holder.style = style
        if (!holder.appliedOpening) applyOpening(map, holder)
        holder.onStyleReady?.invoke()
    }
}

private fun applyOpening(map: MapLibreMap, holder: MapHolder) {
    if (holder.userMoved) return
    val jump = holder.desired
    map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(jump.lat, jump.lon), jump.zoom))
    holder.appliedOpening = true
}

private fun noteRenderedFrame(
    mapView: MapView,
    holder: MapHolder,
    onFpsLine: ((String) -> Unit)?,
) {
    val now = SystemClock.elapsedRealtime()
    val start = holder.frameWindowStartMs.get()
    if (start == 0L) {
        holder.frameWindowStartMs.compareAndSet(0L, now)
        holder.renderedFrames.incrementAndGet()
        return
    }
    holder.renderedFrames.incrementAndGet()
    val elapsed = now - start
    if (elapsed < 1_000L) return
    if (!holder.frameWindowStartMs.compareAndSet(start, now)) return
    val counted = holder.renderedFrames.getAndSet(0)
    val fps = counted * 1000.0 / elapsed.toDouble()
    val drawn = holder.drawnPlanes
    val total = holder.totalPlanes
    val inView = holder.inViewPlanes
    val pins = "${holder.pinsDrawn}/${holder.pinsInView}"
    val zoom = holder.map?.cameraPosition?.zoom ?: 0.0
    mapView.post {
        if (!holder.alive) return@post
        val line = formatFpsLine(fps, drawn, total) + String.format(Locale.US, " inview=%d pins=%s z=%.1f", inView, pins, zoom)
        Log.i(FPS_TAG, line)
        onFpsLine?.invoke(line)
    }
}

private fun formatFpsLine(fps: Double, drawn: Int, total: Int): String {
    return String.format(Locale.US, "fps=%.1f drawn=%d total=%d", fps, drawn, total)
}

private fun projectPlanes(map: MapLibreMap, planes: List<VisiblePlane>, holder: MapHolder) {
    val count = planes.size
    val coords = count * 2
    if (holder.latLon.size != coords) {
        holder.latLon = DoubleArray(coords)
        holder.xy = DoubleArray(coords)
    }
    if (holder.sx.size != count) {
        holder.sx = FloatArray(count)
        holder.sy = FloatArray(count)
    }
    if (count == 0) return
    var cursor = 0
    for (plane in planes) {
        holder.latLon[cursor] = plane.lat
        holder.latLon[cursor + 1] = plane.lon
        cursor += 2
    }
    map.projection.toScreenLocations(holder.latLon, holder.xy)
    for (index in 0 until count) {
        holder.sx[index] = holder.xy[index * 2].toFloat()
        holder.sy[index] = holder.xy[index * 2 + 1].toFloat()
    }
}

private fun install(style: Style, airports: List<Airport>, context: Context): PlaneSymbols {
    for (kind in Silhouette.entries) {
        style.addImage(kind.name, iconCache.getOrPut(kind) { aircraftIcon(context, kind) }, true)
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

    val planes = SymbolLayer(PLANE_LAYER_ID, "planes").withProperties(
        PropertyFactory.iconImage(Expression.get("icon")),
        PropertyFactory.iconRotate(Expression.get("hdg")),
        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
        PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
        PropertyFactory.iconOpacity(Expression.get("op")),
        PropertyFactory.iconColor(
            Expression.switchCase(
                Expression.eq(Expression.get("sel"), true),
                Expression.color(SELECTED_AMBER),
                Expression.interpolate(
                    Expression.linear(),
                    Expression.get("alt"),
                    Expression.stop(0, Expression.color(0xFF7CF29A.toInt())),
                    Expression.stop(10_000, Expression.color(0xFF35D6F0.toInt())),
                    Expression.stop(25_000, Expression.color(0xFF6FA8FF.toInt())),
                    Expression.stop(40_000, Expression.color(0xFFD08CFF.toInt())),
                ),
            ),
        ),
        PropertyFactory.iconHaloColor("rgba(4,6,10,0.9)"),
        PropertyFactory.iconHaloWidth(1.6f),
        PropertyFactory.iconSize(
            Expression.interpolate(
                Expression.linear(),
                Expression.zoom(),
                scaledStop(1.0, 0.22),
                scaledStop(2.0, 0.36),
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
    return PlaneSymbols(planes)
}

class PlaneSymbols(private val layer: SymbolLayer) {
    fun setPlaneFilter(filter: Expression?) {
        layer.setFilter(filter ?: Expression.literal(true))
    }
}

private fun iconScaleExpression(): Expression {
    val stops = mutableListOf<Expression.Stop>()
    for (kind in Silhouette.entries) {
        val scale = iconScale(kind)
        if (scale != 1.0) {
            stops += Expression.stop(kind.name, scale)
        }
    }
    return Expression.match(Expression.get("icon"), Expression.literal(1.0), *stops.toTypedArray())
}

private const val SELECTED_AMBER = 0xFFFFB020.toInt()
private val iconCache = HashMap<Silhouette, android.graphics.Bitmap>()

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

private fun jsonEscape(value: String): String {
    return value.replace("\\", "\\\\").replace("\"", "\\\"")
}
