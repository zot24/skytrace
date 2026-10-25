package com.zot24.skytrace

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.exp
import kotlin.math.roundToInt

data class Weather(
    val conditions: String,
    val temperatureC: Int?,
    val dewPointC: Int?,
    val humidityPct: Int?,
    val wind: String,
    val pressure: String?,
    val ageMin: Int?,
)

private val wxWords = linkedMapOf(
    "TS" to "Thunderstorm", "FZRA" to "Freezing rain", "SN" to "Snow", "RA" to "Rain", "DZ" to "Drizzle",
    "SH" to "Showers", "FG" to "Fog", "BR" to "Mist", "HZ" to "Haze", "GR" to "Hail", "FU" to "Smoke",
)
private val coverWords = mapOf(
    "SKC" to "Clear", "CLR" to "Clear", "NSC" to "Clear", "CAVOK" to "Clear", "FEW" to "Few clouds",
    "SCT" to "Scattered clouds", "BKN" to "Broken clouds", "OVC" to "Overcast", "VV" to "Sky obscured",
)

/** Decodes the parts of a METAR a pilot glances at. Anything it cannot read stays null and the raw text is still shown. */
fun decodeMetar(raw: String, now: Long = System.currentTimeMillis()): Weather? {
    val tokens = raw.trim().split(Regex("\\s+")).takeWhile { it != "RMK" }
    if (tokens.size < 3) return null
    var ageMin: Int? = null
    var wind = "—"
    var temp: Int? = null
    var dew: Int? = null
    var pressure: String? = null
    var weather: String? = null
    var cover: String? = null
    for (token in tokens) {
        Regex("^(\\d{2})(\\d{2})(\\d{2})Z$").find(token)?.let { m ->
            val (d, h, mi) = m.destructured
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = now }
            val nowDay = cal.get(Calendar.DAY_OF_MONTH)
            if (d.toInt() > nowDay) cal.add(Calendar.MONTH, -1)
            cal.set(Calendar.DAY_OF_MONTH, d.toInt())
            cal.set(Calendar.HOUR_OF_DAY, h.toInt())
            cal.set(Calendar.MINUTE, mi.toInt())
            cal.set(Calendar.SECOND, 0)
            ageMin = ((now - cal.timeInMillis) / 60_000L).toInt().coerceAtLeast(0)
        }
        Regex("^(\\d{3}|VRB)(\\d{2,3})(G(\\d{2,3}))?(KT|MPS)$").find(token)?.let { m ->
            val dir = m.groupValues[1]
            val speed = m.groupValues[2].toInt()
            val gust = m.groupValues[4]
            val unit = if (m.groupValues[5] == "MPS") "m/s" else "kt"
            wind = when {
                speed == 0 -> "Calm"
                dir == "VRB" -> "Variable $speed $unit"
                else -> "${dir}° $speed $unit"
            } + if (gust.isNotEmpty()) ", gusts ${gust.toInt()}" else ""
        }
        Regex("^(M?\\d{2})/(M?\\d{2})?$").find(token)?.let { m ->
            temp = m.groupValues[1].replace('M', '-').toIntOrNull()
            dew = m.groupValues[2].takeIf { it.isNotEmpty() }?.replace('M', '-')?.toIntOrNull()
        }
        Regex("^A(\\d{4})$").find(token)?.let { pressure = "%.2f inHg".format(it.groupValues[1].toInt() / 100.0) }
        Regex("^Q(\\d{4})$").find(token)?.let { pressure = "${it.groupValues[1].toInt()} hPa" }
        if (weather == null) {
            val code = token.trimStart('+', '-').removePrefix("VC")
            weather = wxWords.entries.firstOrNull { code.contains(it.key) && code.length <= 8 && !code.contains('/') && code.all { c -> c.isLetter() } }?.let { entry ->
                (if (token.startsWith("+")) "Heavy " else if (token.startsWith("-")) "Light " else "") + entry.value.lowercase().replaceFirstChar { it.uppercase() }
            }
        }
        val coverKey = coverWords.keys.firstOrNull { token.startsWith(it) }
        if (coverKey != null && coverRank(coverKey) >= coverRankOf(cover)) cover = coverWords[coverKey]
    }
    val humidity = if (temp != null && dew != null) relativeHumidity(temp!!.toDouble(), dew!!.toDouble()) else null
    return Weather(weather ?: cover ?: "—", temp, dew, humidity, wind, pressure, ageMin)
}

private fun relativeHumidity(t: Double, td: Double): Int {
    fun e(x: Double) = exp(17.625 * x / (243.04 + x))
    return (100.0 * e(td) / e(t)).roundToInt().coerceIn(0, 100)
}

fun ageText(min: Int?): String = when {
    min == null -> ""
    min < 1 -> "just now"
    min < 90 -> "$min min ago"
    else -> "${min / 60} h ago"
}

private fun coverRank(key: String): Int = when (key) {
    "VV" -> 5
    "OVC" -> 4
    "BKN" -> 3
    "SCT" -> 2
    "FEW" -> 1
    else -> 0
}

private fun coverRankOf(word: String?): Int = if (word == null) -1 else coverRank(coverWords.entries.firstOrNull { it.value == word }?.key ?: "")
