package app.echomind.brain

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/** Somewhere you stayed: consecutive readings close together for long enough. */
data class Visit(val start: Long, val end: Long, val lat: Double, val lon: Double, val fixes: Int, val name: String = "", val place: Place? = null) {
    val minutes get() = ((end - start) / 60_000).toInt()
}

/** The weather for one hour at one place. */
@Serializable
data class WeatherHour(val hour: Int, val tempC: Float, val rainMm: Float, val code: Int)

@Serializable
data class WeatherDay(val day: String, val lat: Double, val lon: Double, val hours: List<WeatherHour>, val fetchedAt: Long)

/**
 * Where your day happened, from location readings: the stays (visits), the
 * moves between them, which place a conversation was at, the weather — and the
 * same in a few sentences for the diary and the morning brief. Pure functions;
 * fetching lives in [Places].
 */
object Whereabouts {
    /**
     * Stays: runs of readings that remain within [radiusM] of their running centre for at
     * least [minStayMin] minutes. A stay is closed when a reading moves away; a lone reading
     * elsewhere doesn't split a stay if the next one is back (noise).
     */
    fun visits(fixes: List<GeoFix>, minStayMin: Int = 15, radiusM: Double = 250.0): List<Visit> {
        val sorted = fixes.sortedBy { it.at }
        val out = ArrayList<Visit>()
        var i = 0
        // Better readings count more: weight 1/accuracy (a ±2 km reading barely moves a ±30 m one).
        fun w(f: GeoFix) = 1.0 / f.accuracyM.coerceIn(15f, 3_000f)
        while (i < sorted.size) {
            var lat = sorted[i].lat; var lon = sorted[i].lon; var n = 1; var wsum = w(sorted[i])
            var j = i + 1
            var lastIn = i
            while (j < sorted.size) {
                val f = sorted[j]
                if (Geo.meters(lat, lon, f.lat, f.lon) <= radiusM + f.accuracyM.coerceAtMost(500f)) {
                    val wf = w(f)
                    lat = (lat * wsum + f.lat * wf) / (wsum + wf); lon = (lon * wsum + f.lon * wf) / (wsum + wf); wsum += wf; n++; lastIn = j; j++
                } else if (j + 1 < sorted.size && Geo.meters(lat, lon, sorted[j + 1].lat, sorted[j + 1].lon) <= radiusM) {
                    j++ // one stray reading
                } else break
            }
            val start = sorted[i].at; val end = sorted[lastIn].at
            if ((end - start) / 60_000 >= minStayMin) out.add(Visit(start, end, lat, lon, n))
            i = lastIn + 1
        }
        return out
    }

    /** Names a visit after the saved place it falls in, if any. */
    fun label(v: Visit, places: List<Place>): Visit {
        val p = places.firstOrNull { Geo.meters(v.lat, v.lon, it.lat, it.lon) <= it.radiusM }
        return if (p != null) v.copy(name = p.name, place = p) else v
    }

    /** The visit that covers [t] (with a little slack at the edges). */
    fun at(visits: List<Visit>, t: Long, slackMs: Long = 10 * 60_000L): Visit? = visits.firstOrNull { t >= it.start - slackMs && t <= it.end + slackMs }

    /** Total metres moved over the readings (straight lines between them). */
    fun distance(fixes: List<GeoFix>): Double = fixes.sortedBy { it.at }.zipWithNext().sumOf { (a, b) -> Geo.meters(a.lat, a.lon, b.lat, b.lon) }

    // ── Weather ──

    /** WMO weather codes, as words. */
    fun weatherWord(code: Int): String = when (code) {
        0 -> "clear"; 1 -> "mostly clear"; 2 -> "partly cloudy"; 3 -> "overcast"
        45, 48 -> "foggy"; 51, 53, 55 -> "drizzle"; 56, 57 -> "freezing drizzle"
        61 -> "light rain"; 63 -> "rain"; 65 -> "heavy rain"; 66, 67 -> "freezing rain"
        71, 73, 75, 77 -> "snow"; 80 -> "light showers"; 81 -> "showers"; 82 -> "heavy showers"
        85, 86 -> "snow showers"; 95 -> "thunderstorm"; 96, 99 -> "thunderstorm with hail"
        else -> "mixed weather"
    }

    fun isRain(code: Int) = code in 51..67 || code in 80..82 || code >= 95

    /** "22–29°C, mostly clear; rain around 4–6 PM" for the hours [fromHour]..[toHour]. */
    fun weatherLine(w: WeatherDay, fromHour: Int = 0, toHour: Int = 23): String? {
        val hs = w.hours.filter { it.hour in fromHour..toHour }
        if (hs.isEmpty()) return null
        val lo = hs.minOf { it.tempC }.toInt(); val hi = hs.maxOf { it.tempC }.toInt()
        val main = hs.groupingBy { weatherWord(it.code) }.eachCount().maxBy { it.value }.key
        val rain = hs.filter { isRain(it.code) || it.rainMm >= 0.3f }
        val rainPart = if (rain.isEmpty()) "" else {
            val a = rain.first().hour; val b = rain.last().hour + 1
            "; ${weatherWord(rain.maxBy { it.rainMm }.code).let { if (it.contains("rain") || it.contains("shower") || it.contains("thunder") || it.contains("drizzle")) it else "rain" }} around ${hourName(a)}–${hourName(b)} (${"%.1f".format(rain.sumOf { it.rainMm.toDouble() })} mm)"
        }
        return "${if (lo == hi) "$lo" else "$lo–$hi"}°C, $main$rainPart"
    }

    fun hourName(h: Int): String { val x = ((h % 24) + 24) % 24; return "${if (x % 12 == 0) 12 else x % 12} ${if (x < 12) "AM" else "PM"}" }

    // ── Words ──

    private fun time(t: Long, zone: ZoneId) = Instant.ofEpochMilli(t).atZone(zone).toLocalTime().let { "${if (it.hour % 12 == 0) 12 else it.hour % 12}:${"%02d".format(it.minute)} ${if (it.hour < 12) "AM" else "PM"}" }

    /** "Home until 9:40 AM → Office 10:15 AM–6:30 PM → Toit 8:05–10:20 PM · 34 km travelled". */
    fun story(visits: List<Visit>, fixes: List<GeoFix>, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (visits.isEmpty()) return null
        val parts = visits.mapIndexed { i, v ->
            val n = v.name.ifBlank { "somewhere new" }
            when {
                i == 0 && visits.size > 1 -> "$n until ${time(v.end, zone)}"
                i == visits.lastIndex && visits.size > 1 -> "$n from ${time(v.start, zone)}"
                else -> "$n ${time(v.start, zone)}–${time(v.end, zone)}"
            }
        }
        val km = distance(fixes) / 1000
        return parts.joinToString(" → ") + if (km >= 1) " · about ${km.toInt()} km travelled" else ""
    }
}
