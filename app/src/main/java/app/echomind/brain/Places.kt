package app.echomind.brain

import android.util.Log
import app.echomind.core.Cloud
import app.echomind.core.SettingsStore
import app.echomind.data.Db
import app.echomind.data.Kv
import app.echomind.data.LocationRow
import app.echomind.util.Js
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.ZoneId

/**
 * Your timeline of places: a low-power location reading every 15–60 minutes
 * (only if you turned it on), named visits, the weather — and what happened
 * where. Place names and weather come from OpenStreetMap and Open-Meteo only if
 * you allowed it, with the location rounded to about 100 m, and every request
 * is in the data ledger.
 */
class Places(
    private val db: Db,
    private val settings: SettingsStore,
    private val cloud: Cloud,
    private val travel: () -> Travel,
    private val web: () -> Web,
) {
    private val zone get() = ZoneId.systemDefault()
    private fun bounds(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli() to d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Whether readings are wanted right now: history is on (and not resting on a trip), or a trip that tracks places. */
    fun recording(): Boolean {
        val s = settings.value
        return if (s.travel) s.travelProfile == "places" || s.travelProfile == "record" else s.locationHistory
    }

    /** One reading if it's time (called from the 15-minute watchdog). */
    suspend fun maybeRecord() {
        if (!recording() || !travel().hasPermission()) return
        val s = settings.value
        val last = db.locations().latest()
        val every = (if (s.travel) maxOf(30, s.locationEveryMin) else s.locationEveryMin).coerceIn(15, 120)
        if (last != null && System.currentTimeMillis() - last.at < (every - 2) * 60_000L) return
        val f = travel().locate() ?: return
        if (last != null && last.at == f.at) return
        db.locations().insert(LocationRow(at = f.at, lat = f.lat, lon = f.lon, accuracyM = f.accuracyM))
        // Old readings fade out.
        if (System.currentTimeMillis() % 20 == 0L) db.locations().deleteBefore(System.currentTimeMillis() - s.locationKeepDays * 86_400_000L)
        if (s.placesOnline && !s.travel) runCatching { weather(LocalDate.now(), refresh = true) }
    }

    /** A reading right now (from the map's "I'm here" or after turning history on). */
    suspend fun recordNow(): GeoFix? {
        val f = travel().locate() ?: return null
        db.locations().insert(LocationRow(at = System.currentTimeMillis(), lat = f.lat, lon = f.lon, accuracyM = f.accuracyM, source = "manual"))
        return f
    }

    suspend fun fixes(from: Long, to: Long): List<GeoFix> = db.locations().range(from, to).map { GeoFix(it.lat, it.lon, it.at, it.accuracyM) }

    suspend fun fixes(d: LocalDate): List<GeoFix> = bounds(d).let { (a, b) -> fixes(a, b) }

    /** Visits of a day, named after your places, else (if allowed) the street or area from OpenStreetMap. */
    suspend fun visits(d: LocalDate, lookUp: Boolean = true): List<Visit> = visits(fixes(d), lookUp)

    suspend fun visits(fixes: List<GeoFix>, lookUp: Boolean = true): List<Visit> {
        val s = settings.value
        return Whereabouts.visits(fixes).map { v ->
            val labelled = Whereabouts.label(v, s.places)
            if (labelled.name.isNotBlank()) labelled
            else labelled.copy(name = (if (lookUp) name(v.lat, v.lon) else cachedName(v.lat, v.lon)) ?: near(v.lat, v.lon, s.places))
        }
    }

    private fun near(lat: Double, lon: Double, places: List<Place>): String =
        places.minByOrNull { Geo.meters(lat, lon, it.lat, it.lon) }?.let { "${Geo.km(Geo.meters(lat, lon, it.lat, it.lon))} ${Geo.direction(it.lat, it.lon, lat, lon)} of ${it.name}" } ?: "Somewhere new"

    private fun key(lat: Double, lon: Double) = "geo:%.3f,%.3f".format(lat, lon)

    private suspend fun cachedName(lat: Double, lon: Double): String? = db.memory().kv(key(lat, lon))

    /** Reverse geocoding by OpenStreetMap (Nominatim), rounded to ~100 m and cached forever. */
    suspend fun name(lat: Double, lon: Double): String? {
        cachedName(lat, lon)?.let { return it }
        if (!settings.value.placesOnline) return null
        val la = "%.3f".format(lat); val lo = "%.3f".format(lon)
        val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=17&lat=$la&lon=$lo"
        val body = runCatching {
            cloud.sendPublic("OpenStreetMap", url.length.toLong(), "Place name near $la, $lo", allowed = true, feature = "Place names") { withContext(Dispatchers.IO) { web().fetch(url, appAgent = true) } }
        }.onFailure { Log.w(TAG, "place name failed: ${it.message}") }.getOrNull() ?: return null
        val o = runCatching { Js.json.parseToJsonElement(body) as JsonObject }.getOrNull() ?: return null
        val addr = o["address"] as? JsonObject
        fun a(k: String) = (addr?.get(k) as? JsonPrimitive)?.content
        val main = (o["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: a("amenity") ?: a("shop") ?: a("road")
        val area = a("suburb") ?: a("neighbourhood") ?: a("city_district") ?: a("village") ?: a("town") ?: a("city")
        val n = listOfNotNull(main, area?.takeIf { it != main }).joinToString(", ").ifBlank { return null }.take(60)
        db.memory().putKv(Kv(key(lat, lon), n, System.currentTimeMillis()))
        return n
    }

    // ── Weather ──

    suspend fun cachedWeather(d: LocalDate): WeatherDay? = db.memory().kv("weather:$d")?.let { runCatching { Js.json.decodeFromString(WeatherDay.serializer(), it) }.getOrNull() }

    /**
     * Hourly weather for [d] at where you were (or home): from Open-Meteo, no account,
     * location rounded to ~1 km. Cached; refreshed at most every 3 hours for today.
     */
    suspend fun weather(d: LocalDate, refresh: Boolean = false): WeatherDay? {
        val cached = cachedWeather(d)
        val s = settings.value
        val fresh = cached != null && (d.isBefore(LocalDate.now()) && cached.fetchedAt > bounds(d).second || System.currentTimeMillis() - cached.fetchedAt < 3 * 3_600_000L)
        if (cached != null && (fresh || !refresh)) return cached
        if (!s.placesOnline || s.travel && s.travelProfile == "rest") return cached
        if (d.isBefore(LocalDate.now().minusDays(5)) || d.isAfter(LocalDate.now().plusDays(1))) return cached
        val spot = fixes(d).lastOrNull()?.let { it.lat to it.lon } ?: db.locations().latest()?.let { it.lat to it.lon }
            ?: s.places.firstOrNull { it.kind == "home" }?.let { it.lat to it.lon } ?: return cached
        val la = "%.2f".format(spot.first); val lo = "%.2f".format(spot.second)
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$la&longitude=$lo&hourly=temperature_2m,precipitation,weather_code&timezone=auto&start_date=$d&end_date=$d"
        val body = runCatching {
            cloud.sendPublic("Open-Meteo", url.length.toLong(), "Weather for $d near $la, $lo", allowed = true, feature = "Weather") { withContext(Dispatchers.IO) { web().fetch(url, appAgent = true) } }
        }.onFailure { Log.w(TAG, "weather failed: ${it.message}") }.getOrNull() ?: return cached
        val h = runCatching { (Js.json.parseToJsonElement(body) as JsonObject)["hourly"] as JsonObject }.getOrNull() ?: return cached
        fun arr(k: String) = (h[k] as? JsonArray).orEmpty()
        val temps = arr("temperature_2m"); val rain = arr("precipitation"); val codes = arr("weather_code")
        val hours = temps.indices.mapNotNull { i ->
            val t = (temps[i] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            WeatherHour(i, t.toFloat(), ((rain.getOrNull(i) as? JsonPrimitive)?.doubleOrNull ?: 0.0).toFloat(), (codes.getOrNull(i) as? JsonPrimitive)?.intOrNull ?: 0)
        }.take(24)
        if (hours.isEmpty()) return cached
        val w = WeatherDay(d.toString(), spot.first, spot.second, hours, System.currentTimeMillis())
        db.memory().putKv(Kv("weather:$d", Js.json.encodeToString(WeatherDay.serializer(), w), System.currentTimeMillis()))
        return w
    }

    // ── Words for the diary, the review and the brief ──

    /** "Places: Home until 9:40 AM → Office … · Weather: 22–29°C, rain around 4–6 PM" (null when nothing is known). */
    suspend fun dayNotes(d: LocalDate): String? {
        val f = fixes(d)
        val story = Whereabouts.story(visits(f), f, zone)
        val w = weather(d)?.let { Whereabouts.weatherLine(it) }
        if (story == null && w == null) return null
        return listOfNotNull(story?.let { "Places: $it" }, w?.let { "Weather: $it" }).joinToString("\n")
    }

    /** For the morning brief: today's weather from now on. */
    suspend fun morningWeather(): String? {
        val w = weather(LocalDate.now(), refresh = true) ?: return null
        return Whereabouts.weatherLine(w, fromHour = java.time.LocalTime.now().hour, toHour = 22)
    }

    /** Where a moment happened, if known: "Office" or "Toit, Indiranagar". */
    suspend fun placeAt(t: Long): Visit? {
        val d = java.time.Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
        return Whereabouts.at(visits(d, lookUp = false), t)
    }

    suspend fun forgetAll() = db.locations().clear()

    companion object { private const val TAG = "EchoPlaces" }
}
