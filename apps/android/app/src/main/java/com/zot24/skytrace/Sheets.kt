package com.zot24.skytrace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

internal val SheetInk = Color(0xFF07090C)
internal val SheetPaper = Color(0xFFE8EEF6)
internal val SheetMuted = Color(0xFF93A1B3)
internal val SheetGold = Color(0xFFE6B15A)
internal val SheetPanel = Color(0xEB080B10)
internal val SheetLine = Color(0x1FFFFFFF)

/** A bottom card that closes on a downward swipe. Back is handled by the screen. */
@Composable
fun SwipeSheet(key: Any, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SheetPanel)
            .border(1.dp, SheetLine, RoundedCornerShape(16.dp))
            .pointerInput(key) {
                var pulled = 0f
                detectVerticalDragGestures(
                    onDragStart = { pulled = 0f },
                    onDragEnd = { if (pulled > 48.dp.toPx()) onDismiss() },
                ) { change, dy ->
                    change.consume()
                    pulled += dy
                }
            }
            .padding(16.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(SheetLine))
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun SheetTitle(title: String, star: Boolean?, onStar: () -> Unit, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = SheetPaper, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (star != null) {
            Text(if (star) "★" else "☆", color = SheetGold, fontSize = 22.sp, modifier = Modifier.clickable { onStar() })
            Spacer(Modifier.width(12.dp))
        }
        Text("×", color = SheetMuted, fontSize = 26.sp, modifier = Modifier.clickable { onClose() })
    }
}

@Composable
fun SheetFact(label: String, value: String, valueColor: Color = SheetPaper) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = SheetMuted, fontSize = 13.sp)
        Text(value, color = valueColor, fontSize = 13.sp)
    }
}

@Composable
fun FlightSheet(vm: SkyViewModel, detail: PlaneDetail, favourite: Boolean) {
    val following = vm.followHex == detail.hex
    SwipeSheet(detail.hex, onDismiss = { vm.closeDetail() }) {
        SheetTitle(detail.title, favourite, { vm.toggleAircraft(detail.hex) }, { vm.closeDetail() })
        Spacer(Modifier.height(6.dp))
        val route = vm.route
        if (route == null) {
            Text(if (vm.routeLookedUp) "Route unknown" else "Looking up route…", color = SheetMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
        } else {
            SheetFact("Scheduled route", route.label)
            route.progress?.let { SheetFact("Progress", it) }
            route.eta?.let { SheetFact("ETA", it) }
            route.note?.let { Text(it, color = SheetMuted, fontSize = 11.sp) }
            Text(route.credit.line, color = SheetMuted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
        SheetFact("Status", if (detail.onGround) "On ground" else "Flying")
        if (detail.seen.isNotEmpty()) SheetFact("Position", detail.seen)
        SheetFact("Type", detail.type)
        SheetFact("Registration", detail.reg)
        if (!detail.onGround) SheetFact("Altitude", detail.altitude)
        SheetFact("Ground speed", detail.speed)
        SheetFact("Heading", detail.heading)
        Spacer(Modifier.height(10.dp))
        Text(
            if (following) "Following · tap to stop" else "Follow",
            color = if (following) SheetInk else SheetGold,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(if (following) SheetGold else Color.Transparent)
                .border(1.dp, SheetGold, RoundedCornerShape(999.dp))
                .clickable { vm.toggleFollow() }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
fun PickerSheet(vm: SkyViewModel) {
    SwipeSheet("picker", onDismiss = { vm.closePicker() }) {
        SheetTitle("${vm.picker.size} aircraft here", null, {}, { vm.closePicker() })
        Spacer(Modifier.height(6.dp))
        for (row in vm.picker) {
            Row(
                Modifier.fillMaxWidth().clickable { vm.pick(row.hex) }.padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(row.callsign, color = SheetGold, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(row.type, color = SheetPaper, fontSize = 13.sp, modifier = Modifier.width(64.dp))
                Text(row.altitude, color = SheetMuted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun AirportSheet(vm: SkyViewModel, airport: Airport, favourite: Boolean) {
    var tab by remember(airport.icao) { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(airport.icao) {
        while (true) {
            delay(2_000L)
            tick += 1
        }
    }
    val traffic = remember(airport.icao, tick) { vm.airportTraffic(airport, System.currentTimeMillis()) }
    SwipeSheet(airport.icao, onDismiss = { vm.closeAirport() }) {
        SheetTitle(
            listOf(airport.iata, airport.icao).filter { it.isNotBlank() }.joinToString(" · "),
            favourite,
            { vm.toggleAirport(airport.icao) },
            { vm.closeAirport() },
        )
        Text(if (airport.city.isBlank()) airport.name else "${airport.name} · ${airport.city}", color = SheetPaper, fontSize = 13.sp)
        Text(
            if (airport.kind == "L") "Large airport" else "Medium airport",
            color = SheetGold,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        WeatherBlock(vm.metar)
        Spacer(Modifier.height(8.dp))
        val tabs = listOf("Departures ${traffic.departures.size}", "Arrivals ${traffic.arrivals.size}", "Ground ${traffic.ground.size}")
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            tabs.forEachIndexed { index, label ->
                Text(
                    label,
                    color = if (tab == index) SheetInk else SheetPaper,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (tab == index) SheetGold else Color(0x22FFFFFF))
                        .clickable { tab = index }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        val rows = when (tab) {
            0 -> traffic.departures
            1 -> traffic.arrivals
            else -> traffic.ground
        }
        Spacer(Modifier.height(4.dp))
        if (rows.isEmpty()) Text("Nothing right now", color = SheetMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
        for (row in rows.take(5)) {
            Row(Modifier.fillMaxWidth().clickable { vm.pick(row.hex) }.padding(vertical = 5.dp)) {
                Text(row.callsign, color = SheetGold, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(row.type, color = SheetPaper, fontSize = 13.sp, modifier = Modifier.width(56.dp))
                Text(row.altitude, color = SheetMuted, fontSize = 13.sp, modifier = Modifier.width(80.dp))
                Text("${row.distanceNm} nm", color = SheetMuted, fontSize = 13.sp)
            }
        }
        Text("Inferred from live positions near the field. No schedule data.", color = SheetMuted, fontSize = 10.sp)
    }
}

@Composable
fun TypeChips(vm: SkyViewModel, active: Set<TypeChip>) {
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        for (chip in TypeChip.entries) {
            val on = chip in active
            Text(
                chip.label,
                color = if (on) SheetInk else SheetPaper,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (on) SheetGold else SheetPanel)
                    .border(1.dp, if (on) SheetGold else SheetLine, RoundedCornerShape(999.dp))
                    .clickable { vm.toggleChip(chip) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun StyleSwitch(vm: SkyViewModel, current: MapStyle) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(SheetPanel).border(1.dp, SheetLine, RoundedCornerShape(999.dp)),
    ) {
        for (style in MapStyle.entries) {
            val on = style == current
            Text(
                style.label,
                color = if (on) SheetInk else SheetPaper,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (on) SheetGold else Color.Transparent)
                    .clickable { vm.setStyle(style) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** One short credit line. The (i) opens every credit. */
@Composable
fun Credits(style: MapStyle, feed: String?, onAbout: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    BackHandler(enabled = open) { open = false }
    if (open) {
        SwipeSheet("credits", onDismiss = { open = false }) {
            SheetTitle("Credits", null, {}, { open = false })
            Spacer(Modifier.height(4.dp))
            val lines = listOf(
                "Map: ${style.credit}" + if (style == MapStyle.SATELLITE) " · CC BY-NC-SA 4.0, non-commercial" else "",
                "ADS-B: © adsb.lol contributors (ODbL)",
                "Routes: VRS standing data (CC0)" + RouteSource.snapshotLabel.let { if (it.isBlank()) "" else ", snapshot $it" },
                "Airports: OurAirports (public domain)",
                "METAR: NOAA Aviation Weather Center (public domain)",
                "Aircraft icons based on Pinhead by Quincy Morgan (CC0 1.0)",
            )
            for (line in lines) Text(line, color = SheetPaper, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(vertical = 3.dp))
            Spacer(Modifier.height(6.dp))
            Text("About and feed URL", color = SheetGold, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { open = false; onAbout() }.padding(vertical = 6.dp))
        }
    } else {
        val satellite = style == MapStyle.SATELLITE
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = if (satellite) 6.dp else 0.dp)
                .clip(RoundedCornerShape(if (satellite) 14.dp else 999.dp))
                .background(SheetPanel)
                .clickable { open = true }
                .padding(start = 12.dp, top = if (satellite) 6.dp else 0.dp, bottom = if (satellite) 6.dp else 0.dp),
        ) {
            Text(
                style.credit.substringBefore(" · ") + (feed?.let { " · $it" } ?: ""),
                color = Color(0xC7E8EEF6),
                fontSize = if (style == MapStyle.SATELLITE) 10.sp else 11.sp,
                lineHeight = 13.sp,
                // EOX requires its full attribution to be visible whenever the imagery is shown.
                maxLines = if (style == MapStyle.SATELLITE) 3 else 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                "ⓘ",
                color = SheetPaper,
                fontSize = 18.sp,
                modifier = Modifier.clickable { open = true }.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun WeatherBlock(raw: String?) {
    if (raw == null) return
    val weather = decodeMetar(raw)
    if (weather == null) {
        Text(raw, color = SheetMuted, fontSize = 12.sp)
        return
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(weather.temperatureC?.let { "$it°C" } ?: "—", color = SheetPaper, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(weather.conditions, color = SheetPaper, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("Report ${ageText(weather.ageMin)}", color = SheetMuted, fontSize = 11.sp)
        }
    }
    Spacer(Modifier.height(4.dp))
    SheetFact("Wind", weather.wind)
    weather.pressure?.let { SheetFact("Pressure", it) }
    if (weather.dewPointC != null) {
        SheetFact("Dew point", "${weather.dewPointC}°C" + (weather.humidityPct?.let { " · $it% humidity" } ?: ""))
    }
    Spacer(Modifier.height(4.dp))
    Text(raw, color = SheetMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, lineHeight = 13.sp)
}
