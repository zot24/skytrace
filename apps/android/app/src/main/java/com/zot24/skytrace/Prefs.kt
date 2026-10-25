package com.zot24.skytrace

import android.content.Context
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

internal val Context.skyStore by preferencesDataStore("skytrace")
internal val keyUrl = stringPreferencesKey("base_url")
internal val keyCamera = stringPreferencesKey("camera")
internal val keyAircraft = stringSetPreferencesKey("fav_aircraft")
internal val keyAirports = stringSetPreferencesKey("fav_airports")
internal val keyStyle = stringPreferencesKey("map_style")
internal val keyChips = stringSetPreferencesKey("type_chips")
internal val keyWidgetSnapshot = stringPreferencesKey("widget_snapshot")
internal val keyWidgetUpdated = longPreferencesKey("widget_updated")
internal val keyDebugSynthetic = intPreferencesKey("debug_synthetic")
internal val keyExtraRoutes = androidx.datastore.preferences.core.booleanPreferencesKey("extra_route_sources")
