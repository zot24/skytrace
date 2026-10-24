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
    SATELLITE("Satellite", "Imagery: NASA GIBS Blue Marble · USGS The National Map (public domain)"),
    TERRAIN(
        "Terrain",
        "© OpenFreeMap · © OpenStreetMap contributors · Elevation: Mapzen Terrain Tiles on AWS Open Data (SRTM, GMTED2010, ETOPO1, NED and others)",
    ),
}

fun styleBuilder(style: MapStyle): Style.Builder = when (style) {
    MapStyle.DARK -> Style.Builder().fromUri("https://tiles.openfreemap.org/styles/dark")
    MapStyle.TERRAIN -> Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")
    MapStyle.SATELLITE -> Style.Builder().fromJson(SATELLITE_JSON)
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

private const val SATELLITE_JSON = """{"version":8,
"glyphs":"https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
"sources":{
"gibs":{"type":"raster","tileSize":256,"maxzoom":8,"tiles":["https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/BlueMarble_ShadedRelief_Bathymetry/default/GoogleMapsCompatible_Level8/{z}/{y}/{x}.jpeg"]},
"usgs":{"type":"raster","tileSize":256,"minzoom":7,"maxzoom":16,"bounds":[-125,24,-66,50],"tiles":["https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer/tile/{z}/{y}/{x}"]}},
"layers":[
{"id":"bg","type":"background","paint":{"background-color":"#05070a"}},
{"id":"gibs","type":"raster","source":"gibs"},
{"id":"usgs","type":"raster","source":"usgs","minzoom":7}]}"""
