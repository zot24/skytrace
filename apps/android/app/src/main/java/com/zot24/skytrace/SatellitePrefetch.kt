package com.zot24.skytrace

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Satellite speed-ups, kept modest for EOX's free endpoint ("cache tiles on your own CDN or device, and spread
 * out your requests rather than sending large batches all at once" — cloudless.eox.at FAQ).
 *
 * - The ambient (disk) cache is raised to [CACHE_BYTES] so a second visit is served from disk.
 * - When the camera settles on satellite, the three zoom levels just below the camera (z-3..z-1, floor 0,
 *   capped at the native max 14) are prefetched over the view grown by one view-width each side.
 *   At most [MAX_TILES] tiles per prefetch, one prefetch at a time, and a view already covered is skipped.
 */
object SatellitePrefetch {
    private const val TAG = "skytrace-prefetch"
    const val CACHE_BYTES = 256L * 1024 * 1024
    private const val MAX_TILES = 200
    private const val LEVELS = 3
    private const val KEEP_REGIONS = 8
    private var cacheSet = false
    private var busy = false
    private val done = ArrayList<Pair<LatLngBounds, Int>>()

    fun setUpCache(context: Context) {
        if (cacheSet) return
        cacheSet = true
        OfflineManager.getInstance(context).setMaximumAmbientCacheSize(CACHE_BYTES, object : OfflineManager.FileSourceCallback {
            override fun onSuccess() { Log.i(TAG, "ambient cache max=${CACHE_BYTES / (1024 * 1024)}MB") }
            override fun onError(message: String) { Log.w(TAG, "ambient cache size failed: $message") }
        })
    }

    fun maybePrefetch(context: Context, map: MapLibreMap) {
        setUpCache(context)
        if (busy) return
        val zoom = map.cameraPosition.zoom
        val top = (floor(zoom).toInt() - 1).coerceAtMost(14)
        if (top < 0) return
        val visible = map.projection.visibleRegion.latLngBounds
        val latSpan = visible.latitudeSpan
        val lonSpan = visible.longitudeSpan
        val south = (visible.latitudeSouth - latSpan).coerceAtLeast(-85.0)
        val north = (visible.latitudeNorth + latSpan).coerceAtMost(85.0)
        val west = (visible.longitudeWest - lonSpan).coerceAtLeast(-180.0)
        val east = (visible.longitudeEast + lonSpan).coerceAtMost(180.0)
        if (done.any { (b, z) -> z == top && b.contains(visible.center) && b.latitudeSpan <= (north - south) * 1.5 }) return
        var low = (top - LEVELS + 1).coerceAtLeast(0)
        var count = tiles(south, north, west, east, low, top)
        var high = top
        while (count > MAX_TILES && high > low) {
            high--
            count = tiles(south, north, west, east, low, high)
        }
        if (count > MAX_TILES) return
        val bounds = LatLngBounds.from(north, east, south, west)
        val ratio = context.resources.displayMetrics.density
        val definition = OfflineTilePyramidRegionDefinition(SATELLITE_STYLE_URL, bounds, low.toDouble(), high.toDouble(), ratio)
        busy = true
        val started = SystemClock.elapsedRealtime()
        val manager = OfflineManager.getInstance(context)
        manager.createOfflineRegion(definition, ByteArray(0), object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                Log.i(TAG, "start z$low-z$high tiles~$count")
                offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        if (!status.isComplete) return
                        offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        Log.i(TAG, "done z$low-z$high resources=${status.completedResourceCount} tiles=${status.completedTileCount} bytes=${status.completedResourceSize} ms=${SystemClock.elapsedRealtime() - started}")
                        done.add(bounds to top)
                        busy = false
                        trim(manager)
                    }
                    override fun onError(error: OfflineRegionError) {
                        Log.w(TAG, "error ${error.reason} ${error.message}")
                    }
                    override fun mapboxTileCountLimitExceeded(limit: Long) {
                        Log.w(TAG, "tile limit $limit")
                        offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        busy = false
                    }
                })
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }
            override fun onError(error: String) {
                Log.w(TAG, "create failed $error")
                busy = false
            }
        })
    }

    /** Keeps only the newest regions; their tiles stay in the ambient cache until it fills. */
    private fun trim(manager: OfflineManager) {
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val regions = offlineRegions ?: return
                if (regions.size <= KEEP_REGIONS) return
                regions.sortedBy { it.id }.take(regions.size - KEEP_REGIONS).forEach { region ->
                    region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                        override fun onDelete() {}
                        override fun onError(error: String) {}
                    })
                }
            }
            override fun onError(error: String) {}
        })
    }

    /** Web-mercator tile count for a box over zoom levels [from]..[to]. */
    fun tiles(south: Double, north: Double, west: Double, east: Double, from: Int, to: Int): Int {
        var total = 0
        for (z in from..to) {
            val n = 1 shl z
            fun x(lon: Double) = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
            fun y(lat: Double): Int {
                val r = Math.toRadians(lat)
                return floor((1.0 - ln(tan(r) + 1.0 / kotlin.math.cos(r)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
            }
            total += (x(east) - x(west) + 1) * (y(south) - y(north) + 1)
        }
        return total
    }
}
