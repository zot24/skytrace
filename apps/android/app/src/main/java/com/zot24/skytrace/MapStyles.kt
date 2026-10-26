package com.zot24.skytrace

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.HillshadeLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.RasterDemSource
import org.maplibre.android.style.sources.TileSet

/** Satellite is EOxCloudless (CC BY-NC-SA 4.0, non-commercial); see the README licence note. */
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

/** Verbatim from cloudless.eox.at/license-non-commercial; it must stay fully visible while the imagery shows. */
const val EOX_ATTRIBUTION = "EOxCloudless https://cloudless.eox.at by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2025)"

/** data/satellite.json, copied into assets so the offline prefetcher can address it by URL. Native z0–14; MapLibre over-zooms. */
const val SATELLITE_STYLE_URL = "asset://satellite.json"
