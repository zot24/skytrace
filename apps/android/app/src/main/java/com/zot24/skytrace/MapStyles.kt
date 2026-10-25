package com.zot24.skytrace

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.HillshadeLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.RasterDemSource
import org.maplibre.android.style.sources.TileSet

/**
 * Only free sources whose terms allow use in an MIT app. EOX Sentinel-2 cloudless (CC BY-NC-SA for 2018+)
 * is left out: its non-commercial clause conflicts with MIT's grant.
 */
enum class MapStyle(val label: String, val credit: String) {
    DARK("Dark", "© OpenFreeMap · © OpenStreetMap contributors"),
    SATELLITE("Satellite", EOX_ATTRIBUTION),
    TERRAIN(
        "Terrain",
        "© OpenFreeMap · © OpenStreetMap contributors · Elevation: Mapzen Terrain Tiles on AWS Open Data (SRTM, GMTED2010, ETOPO1, NED and others)",
    ),
}

fun styleBuilder(style: MapStyle): Style.Builder = when (style) {
    MapStyle.DARK -> Style.Builder().fromUri("https://tiles.openfreemap.org/styles/dark")
    MapStyle.TERRAIN -> Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")
    MapStyle.SATELLITE -> Style.Builder().fromUri(SATELLITE_STYLE_URL)
}

fun addTerrain(style: Style) {
    val tiles = TileSet("2.2.0", "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png")
    tiles.encoding = "terrarium"
    tiles.maxZoom = 15f
    style.addSource(RasterDemSource("dem", tiles, 256))
    val shade = HillshadeLayer("hillshade", "dem").withProperties(
        PropertyFactory.hillshadeExaggeration(0.55f),
        PropertyFactory.hillshadeShadowColor("#473b2a"),
        PropertyFactory.hillshadeHighlightColor("#ffffff"),
    )
    val below = style.layers.firstOrNull { it.id == "water" }?.id
        ?: style.layers.firstOrNull { it is SymbolLayer }?.id
    if (below != null) style.addLayerBelow(shade, below) else style.addLayer(shade)
}

/** Required attribution for the 2025 layer, verbatim from cloudless.eox.at/license-non-commercial. Licence: CC BY-NC-SA 4.0. */
const val EOX_ATTRIBUTION = "EOxCloudless https://cloudless.eox.at by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2025)"

// EOxCloudless (Sentinel-2 cloudless) 2025, WMTS layer s2cloudless-2025_3857, matrix set GoogleMapsCompatible.
// Native pyramid is z0–14 in EPSG:3857 (cloudless.eox.at product list), so maxzoom 14: MapLibre over-zooms past it.
// CC BY-NC-SA 4.0: non-commercial use only.
/** Style lives in data/satellite.json (copied into assets) so the offline prefetcher can address it by URL. */
const val SATELLITE_STYLE_URL = "asset://satellite.json"
