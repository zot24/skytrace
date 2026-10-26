package com.zot24.skytrace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.NumberFormat
import java.util.Locale

private val Ink = Color(0xFF07090C)
private val Paper = Color(0xFFE8EEF6)
private val Muted = Color(0xFF93A1B3)
private val Gold = Color(0xFFE6B15A)
private val Panel = Color(0xEB080B10)
private val Line = Color(0x1FFFFFFF)
private val Pill = RoundedCornerShape(14.dp)

@Composable
fun SkyScreen(vm: SkyViewModel = viewModel()) {
    val favAircraft by vm.favouriteAircraft.collectAsState()
    val favAirports by vm.favouriteAirports.collectAsState()
    var query by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var fpsLine by remember { mutableStateOf<String?>(null) }
    val hits = if (query.isBlank()) emptyList() else searchAirports(vm.airports, query)
    val showFavs = focused && query.isBlank()
    val searchOpen = focused || query.isNotBlank()
    val countText = NumberFormat.getIntegerInstance(Locale.US).format(vm.count)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dismissSearch = {
        keyboard?.hide()
        focusManager.clearFocus(force = true)
        if (query.isNotEmpty()) query = ""
    }
    // Any path that opens a sheet (search pick, favourite, widget link) closes the search dropdown first.
    LaunchedEffect(vm.airportSheet?.icao, vm.detail?.hex) {
        if (vm.airportSheet != null || vm.detail != null) dismissSearch()
    }
    val chips by vm.typeChips.collectAsState()
    val mapStyle by vm.mapStyle.collectAsState()
    val showOverlay = BuildConfig.DEBUG && SyntheticTraffic.overlay
    BackHandler(enabled = about || searchOpen || vm.detail != null || vm.picker.isNotEmpty() || vm.airportSheet != null) {
        when {
            about -> about = false
            searchOpen -> dismissSearch()
            vm.picker.isNotEmpty() -> vm.closePicker()
            vm.airportSheet != null -> vm.closeAirport()
            else -> vm.closeDetail()
        }
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        SkyMap(
            vm,
            Modifier.fillMaxSize(),
            onFpsLine = if (showOverlay) ({ fpsLine = it }) else null,
            onUserGesture = { if (searchOpen) dismissSearch() },
        )
        vm.styleCover?.let { cover ->
            androidx.compose.foundation.Image(
                cover,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Column(
                Modifier
                    .clip(Pill)
                    .background(Panel)
                    .border(1.dp, Line, Pill)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    "skytrace",
                    color = Paper,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp,
                )
                Text(
                    "$countText aircraft · ${vm.source}",
                    color = Muted,
                    fontSize = 12.sp,
                )
                if (showOverlay) {
                    fpsLine?.let { line ->
                        Text(line, color = Muted, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StyleSwitch(vm, mapStyle)
                vm.styleLoading?.let { loading ->
                    Text(
                        "Loading ${loading.label.lowercase()}…",
                        color = Muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 10.dp).clip(Pill).background(Panel).padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(999.dp))
                    .background(Panel)
                    .border(1.dp, Line, RoundedCornerShape(999.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    textStyle = TextStyle(color = Paper, fontSize = 14.sp),
                    cursorBrush = SolidColor(Gold),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focused = it.isFocused }
                        .padding(vertical = 4.dp),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text("Search airport — name, IATA, ICAO", color = Muted, fontSize = 14.sp)
                        }
                        inner()
                    },
                )
                if (searchOpen) {
                    Text(
                        "×",
                        color = Muted,
                        fontSize = 20.sp,
                        modifier = Modifier.clickable { dismissSearch() }.padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            TypeChips(vm, chips)
            if (hits.isNotEmpty() || showFavs) {
                Column(
                    Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .clip(Pill)
                        .background(Color(0xF0080B10))
                        .border(1.dp, Line, Pill),
                ) {
                    if (showFavs) {
                        FavouriteBlock(vm, favAirports, favAircraft)
                    }
                    hits.forEach { airport ->
                        AirportRow(airport, favAirports.contains(airport.icao), { vm.focusAirport(airport); dismissSearch() }) {
                            vm.toggleAirport(airport.icao)
                        }
                    }
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(12.dp),
        ) {
            val detail = vm.detail
            val airport = vm.airportSheet
            when {
                vm.picker.isNotEmpty() -> PickerSheet(vm)
                airport != null -> AirportSheet(vm, airport, favAirports.contains(airport.icao))
                detail != null -> FlightSheet(vm, detail, favAircraft.contains(detail.hex))
            }
            Spacer(Modifier.height(8.dp))
            Credits(mapStyle, feedCredit(vm.source)) { about = true }
        }

        if (about) {
            AboutSheet(vm.baseUrl, onSave = { vm.saveBaseUrl(it); about = false }, onClose = { about = false })
        }
    }
}

@Composable
private fun FavouriteBlock(vm: SkyViewModel, airports: Set<String>, aircraft: Set<String>) {
    val airportHits = vm.airports.filter { airports.contains(it.icao) }.take(8)
    val now = System.currentTimeMillis()
    val craftHits = aircraft.mapNotNull { hex -> vm.fleet.one(hex, now) }.take(8)
    if (airportHits.isEmpty() && craftHits.isEmpty()) {
        Text("No favourites yet", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
        return
    }
    craftHits.forEach { plane ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { vm.focusAircraft(plane.hex) }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(plane.callsign.ifBlank { plane.hex.uppercase(Locale.US) }, color = Paper, fontSize = 13.sp)
            Text("★", color = Gold, fontSize = 16.sp)
        }
    }
    airportHits.forEach { airport ->
        AirportRow(airport, favourite = true, onPick = { vm.focusAirport(airport) }) { vm.toggleAirport(airport.icao) }
    }
}

@Composable
private fun AirportRow(airport: Airport, favourite: Boolean, onPick: () -> Unit, onStar: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                listOf(airport.iata, airport.icao).filter { it.isNotBlank() }.joinToString(" · "),
                color = Gold,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (airport.city.isBlank()) airport.name else "${airport.name} · ${airport.city}",
                color = Paper,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            if (favourite) "★" else "☆",
            color = Gold,
            fontSize = 18.sp,
            modifier = Modifier.clickable(onClick = onStar).padding(8.dp),
        )
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 13.sp)
        Text(value, color = Paper, fontSize = 13.sp)
    }
}

@Composable
private fun AboutSheet(baseUrl: String, onSave: (String) -> Unit, onClose: () -> Unit) {
    var draft by remember(baseUrl) { mutableStateOf(baseUrl) }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable { onClose() }) {
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(24.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF10141B))
                .border(1.dp, Line, RoundedCornerShape(16.dp))
                .clickable { }
                .padding(18.dp),
        ) {
            Text("About", color = Paper, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Aircraft icons based on Pinhead by Quincy Morgan (CC0 1.0), https://github.com/waysidemapping/pinhead",
                color = Paper,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Wide-body, narrow-body, regional jet, bizjet, turboprop, light piston, and light, medium, and heavy helicopter silhouettes come from the ICAO type code. If the type is missing or unknown, the ADS-B emitter category is used. A7 is drawn as a medium helicopter because the category does not carry a weight class.",
                color = Muted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text("Feed URL", color = Muted, fontSize = 12.sp)
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = TextStyle(color = Paper, fontSize = 14.sp),
                singleLine = true,
                cursorBrush = SolidColor(Gold),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF07090C))
                    .padding(10.dp),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                Text("Close", color = Muted, modifier = Modifier.clickable { onClose() }.padding(8.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save", color = Gold, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onSave(draft) }.padding(8.dp))
            }
        }
    }
}
