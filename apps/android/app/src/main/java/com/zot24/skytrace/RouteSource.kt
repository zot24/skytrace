package com.zot24.skytrace

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** Where a route came from, with the licence line the sheet shows under it. */
enum class RouteCredit(val line: String) {
    VRS("Route: VRS standing data (CC0)"),
    ADSB_LOL("Route data © adsb.lol contributors, ODbL"),
}

data class RouteResult(val airports: List<String>, val credit: RouteCredit, val plausible: Boolean? = null)

/** What the flight sheet shows. Progress and ETA are null unless they can be computed from the position. */
data class FlightRoute(val label: String, val progress: String?, val eta: String?, val credit: RouteCredit, val note: String?)

/**
 * Callsign to scheduled route.
 *
 * Default: VRS standing data (CC0). A snapshot of the routes table ships in the APK
 * (data/vrs-routes.txt.gz, built by tools/update-vrs-routes.sh). The airline block a lookup needs
 * is refreshed from raw.githubusercontent.com/vradarserver/standing-data at most once a week.
 *
 * Opt-in ("Extra route sources (non-commercial)"): the adsb.lol API routeset endpoint (ODbL), asked
 * only for callsigns VRS does not have. adsbdb and OpenSky are not used: both need permission.
 */
object RouteSource {
    private const val TAG = "skytrace-route"
    private const val VRS_RAW = "https://raw.githubusercontent.com/vradarserver/standing-data/main/routes/schema-01"
    private const val VRS_LIST = "https://api.github.com/repos/vradarserver/standing-data/contents/routes/schema-01"
    const val ADSB_LOL_BASE = "https://api.adsb.lol"
    private const val WEEK_MS = 7L * 24 * 3600_000L

    /** Debug builds may point the adsb.lol lookup at a local mock; release always uses [ADSB_LOL_BASE]. */
    @Volatile var adsbLolBase: String = ADSB_LOL_BASE
    @Volatile var extraEnabled: Boolean = false
    @Volatile var snapshotLabel: String = ""
    private var snapshotMs = 0L

    private val client by lazy { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build() }
    private var dir: File? = null
    private var bundle: File? = null
    private val index = HashMap<String, LongArray>()
    private val blocks = object : LinkedHashMap<String, Map<String, String>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, String>>?) = size > 48
    }
    private val tried = HashMap<String, Long>()
    private val extraCache = HashMap<String, Pair<Long, RouteResult?>>()

    /** Unpacks the bundled snapshot once and indexes it by callsign prefix. Call off the main thread. */
    @Synchronized
    fun init(context: Context) {
        if (bundle != null) return
        val started = System.nanoTime()
        val root = File(context.filesDir, "routes").apply { mkdirs() }
        val unpacked = File(root, "vrs-routes.txt")
        val stamp = File(root, "vrs-routes.version")
        val apkVersion = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (!unpacked.exists() || stamp.takeIf { it.exists() }?.readText() != apkVersion) {
            GZIPInputStream(context.assets.open("vrs-routes.txt.gz")).use { input -> unpacked.outputStream().use { input.copyTo(it) } }
            stamp.writeText(apkVersion)
        }
        RandomAccessFile(unpacked, "r").use { file ->
            val bytes = ByteArray(file.length().toInt())
            file.readFully(bytes)
            var lineStart = 0
            var current: String? = null
            var blockStart = 0L
            for (i in bytes.indices) {
                if (bytes[i] != '\n'.code.toByte()) continue
                if (bytes[lineStart] == '='.code.toByte()) {
                    current?.let { index[it] = longArrayOf(blockStart, lineStart - blockStart) }
                    current = String(bytes, lineStart + 1, i - lineStart - 1)
                    blockStart = (i + 1).toLong()
                } else if (bytes[lineStart] == '#'.code.toByte()) {
                    val parts = String(bytes, lineStart, i - lineStart).split(' ')
                    snapshotLabel = parts.getOrNull(2)?.take(10).orEmpty()
                    snapshotMs = runCatching { java.time.OffsetDateTime.parse(parts[2]).toInstant().toEpochMilli() }.getOrDefault(0L)
                }
                lineStart = i + 1
            }
            current?.let { index[it] = longArrayOf(blockStart, bytes.size - blockStart) }
        }
        dir = root
        bundle = unpacked
        Log.i(TAG, "vrs snapshot=$snapshotLabel prefixes=${index.size} initMs=${(System.nanoTime() - started) / 1_000_000}")
    }

    /** Blocking lookup, call off the main thread. [lat]/[lon] are only sent to the opt-in source. */
    fun lookup(callsign: String, lat: Double, lon: Double): RouteResult? {
        val cs = callsign.trim().uppercase()
        if (cs.length < 4 || bundle == null) return null
        val prefix = cs.take(3)
        refreshIfStale(prefix)
        val codes = block(prefix)[cs.drop(3)]
        if (codes != null) return RouteResult(codes.split('-'), RouteCredit.VRS)
        if (extraEnabled) return adsbLol(cs, lat, lon)
        return null
    }

    @Synchronized
    private fun block(prefix: String): Map<String, String> {
        blocks[prefix]?.let { return it }
        val live = File(dir, "live-$prefix.txt")
        val text = if (live.exists()) live.readText() else {
            val at = index[prefix] ?: return emptyMap<String, String>().also { blocks[prefix] = it }
            RandomAccessFile(bundle, "r").use { f -> ByteArray(at[1].toInt()).also { f.seek(at[0]); f.readFully(it) } }.toString(Charsets.UTF_8)
        }
        val map = HashMap<String, String>()
        for (line in text.lineSequence()) {
            val comma = line.indexOf(',')
            if (comma > 0) map[line.substring(0, comma)] = line.substring(comma + 1)
        }
        blocks[prefix] = map
        return map
    }

    /** Re-fetches one airline's VRS files from GitHub when the local copy is over a week old. */
    private fun refreshIfStale(prefix: String) {
        val live = File(dir, "live-$prefix.txt")
        val now = System.currentTimeMillis()
        val bundledFresh = index.containsKey(prefix) && snapshotMs > now - WEEK_MS
        if (bundledFresh || (live.exists() && live.lastModified() > now - WEEK_MS)) return
        synchronized(tried) {
            if ((tried[prefix] ?: 0L) > now - 3600_000L) return
            tried[prefix] = now
        }
        try {
            val listing = client.newCall(Request.Builder().url("$VRS_LIST/${prefix[0]}").header("Accept", "application/vnd.github+json").build()).execute().use {
                if (!it.isSuccessful) return
                JSONArray(it.body?.string().orEmpty())
            }
            val out = StringBuilder()
            for (i in 0 until listing.length()) {
                val name = listing.getJSONObject(i).optString("name")
                if (!name.startsWith("$prefix-") || !name.endsWith(".csv")) continue
                client.newCall(Request.Builder().url("$VRS_RAW/${prefix[0]}/$name").build()).execute().use { response ->
                    if (!response.isSuccessful) return
                    response.body?.string().orEmpty().lineSequence().drop(1).forEach { row ->
                        val f = row.split(',')
                        if (f.size >= 5 && f[0].length > 3 && f[0].startsWith(prefix)) out.append(f[0].drop(3)).append(',').append(f[4]).append('\n')
                    }
                }
            }
            if (out.isEmpty()) return
            live.writeText(out.toString())
            synchronized(this) { blocks.remove(prefix) }
            Log.i(TAG, "vrs refreshed prefix=$prefix bytes=${out.length}")
        } catch (e: Exception) {
            Log.w(TAG, "vrs refresh failed prefix=$prefix ${e.message}")
        }
    }

    /** POST /api/0/routeset is the current adsb.lol route endpoint; GET /api/0/route/{callsign} is deprecated upstream. */
    private fun adsbLol(cs: String, lat: Double, lon: Double): RouteResult? {
        val now = System.currentTimeMillis()
        synchronized(extraCache) { extraCache[cs]?.let { if (it.first > now - 20 * 60_000L) return it.second } }
        val result = try {
            val body = JSONObject().put("planes", JSONArray().put(JSONObject().put("callsign", cs).put("lat", lat).put("lng", lon))).toString()
            val request = Request.Builder().url("${adsbLolBase.trimEnd('/')}/api/0/routeset")
                .post(body.toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else {
                    val first = JSONArray(response.body?.string().orEmpty()).optJSONObject(0)
                    val codes = first?.optString("airport_codes").orEmpty()
                    if (codes.isBlank() || codes == "unknown") null
                    else RouteResult(codes.split('-'), RouteCredit.ADSB_LOL, if (first!!.has("plausible")) first.optBoolean("plausible") else null)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "adsb.lol route failed ${e.message}")
            null
        }
        Log.i(TAG, "extra source=adsb.lol callsign=$cs found=${result != null}")
        synchronized(extraCache) { extraCache[cs] = now to result }
        return result
    }
}

/**
 * Turns a route into sheet text. Progress and ETA only appear for a single leg whose ends are known
 * airports, with the plane airborne, moving and roughly on the line between them.
 */
fun describeRoute(route: RouteResult, airports: Map<String, Airport>, plane: VisiblePlane, onGround: Boolean, now: Long): FlightRoute {
    val label = route.airports.joinToString(" → ") { code -> airports[code]?.iata?.ifBlank { null } ?: code }
    val note = if (route.plausible == false) "Not plausible for this position" else null
    val from = airports[route.airports.first()]
    val to = airports[route.airports.last()]
    if (route.airports.size != 2 || from == null || to == null || onGround || plane.gsKt < 80 || route.plausible == false) {
        return FlightRoute(label, null, null, route.credit, note)
    }
    val total = distanceNm(from.lat, from.lon, to.lat, to.lon)
    val done = distanceNm(from.lat, from.lon, plane.lat, plane.lon)
    val left = distanceNm(plane.lat, plane.lon, to.lat, to.lon)
    if (total < 20 || done + left > total * 1.15 + 30) return FlightRoute(label, null, null, route.credit, note)
    val pct = (100 * done / (done + left)).toInt().coerceIn(0, 100)
    val minutes = (left / plane.gsKt * 60).toInt()
    val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(now + minutes * 60_000L))
    val eta = "~$clock (${if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"} at current speed)"
    return FlightRoute(label, "$pct% · ${left.toInt()} nm to go", eta, route.credit, note)
}
