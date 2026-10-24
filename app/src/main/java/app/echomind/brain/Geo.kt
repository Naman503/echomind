package app.echomind.brain

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A place that is "yours": being there (or on the way between two of them) is not travelling. */
@Serializable
data class Place(
    val id: String,
    val name: String,
    /** home | work | other */
    val kind: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Int = 1_500,
)

/** A planned trip: travel mode is on for these dates, no location needed. */
@Serializable
data class Trip(val id: String, val label: String, val from: String, val to: String)

@Serializable
data class GeoFix(val lat: Double, val lon: Double, val at: Long, val accuracyM: Float = 0f)

/**
 * Where you are, in plain geometry — no maps, no network. Used to notice that
 * you're travelling: far from every place of yours and not on the way between
 * them.
 */
object Geo {
    private const val R = 6_371_000.0

    fun meters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = Math.toRadians(bLat - aLat); val dLon = Math.toRadians(bLon - aLon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2)
        return 2 * R * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun meters(f: GeoFix, p: Place) = meters(f.lat, f.lon, p.lat, p.lon)

    /** Shortest distance from a point to the straight segment a–b (local flat projection; fine below a few hundred km). */
    fun metersToSegment(lat: Double, lon: Double, a: Place, b: Place): Double {
        val k = cos(Math.toRadians((a.lat + b.lat) / 2))
        fun x(lo: Double) = Math.toRadians(lo) * R * k
        fun y(la: Double) = Math.toRadians(la) * R
        val px = x(lon); val py = y(lat); val ax = x(a.lon); val ay = y(a.lat); val bx = x(b.lon); val by = y(b.lat)
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        return sqrt((px - cx).pow(2) + (py - cy).pow(2))
    }

    sealed interface Where {
        data class At(val place: Place) : Where
        /** On the way between two of your places (within the corridor). */
        data class Commuting(val from: Place, val to: Place) : Where
        /** Out, but close to a place of yours (the gym, a friend's house). */
        data class Nearby(val place: Place, val meters: Double) : Where
        /** Far from everything that is yours. */
        data class Away(val nearest: Place?, val meters: Double) : Where
    }

    /**
     * Where a fix is. A poor fix (big accuracy circle) is given the benefit of the doubt:
     * its accuracy is subtracted before deciding you're away.
     */
    fun classify(f: GeoFix, places: List<Place>, awayKm: Double, corridorKm: Double): Where {
        if (places.isEmpty()) return Where.Away(null, Double.MAX_VALUE)
        val slack = f.accuracyM.toDouble().coerceIn(0.0, 5_000.0)
        val dist = places.map { it to meters(f, it) }
        dist.firstOrNull { (p, d) -> d - slack <= p.radiusM }?.let { return Where.At(it.first) }
        // The daily way between a home and a work place (a commute: up to 60 km apart). A drive
        // between two homes far apart — to your parents' — is a real trip, so it isn't a corridor.
        for (a in places) for (b in places) {
            if (a.kind != "home" || b.kind != "work" || meters(a.lat, a.lon, b.lat, b.lon) > 60_000) continue
            if (metersToSegment(f.lat, f.lon, a, b) - slack <= corridorKm * 1_000) return Where.Commuting(a, b)
        }
        val (near, d) = dist.minBy { it.second }
        return if (d - slack < awayKm * 1_000) Where.Nearby(near, d) else Where.Away(near, d)
    }

    /**
     * Travelling when two readings at least [minGapMs] apart both say Away (one odd
     * fix never switches anything), or one says Away while moving fast between them
     * (on a train, a highway).
     */
    fun confirmsTravel(recent: List<Pair<GeoFix, Where>>, minGapMs: Long = 20 * 60_000L): Boolean {
        val away = recent.filter { it.second is Where.Away }
        if (away.size >= 2 && away.last().first.at - away.first().first.at >= minGapMs) return true
        if (recent.size >= 2 && recent.last().second is Where.Away) {
            val (a, b) = recent[recent.size - 2].first to recent.last().first
            val hours = (b.at - a.at) / 3_600_000.0
            if (hours > 0.05 && meters(a.lat, a.lon, b.lat, b.lon) / 1_000 / hours >= 60) return true
        }
        return false
    }

    data class Suggestion(val kind: String, val lat: Double, val lon: Double, val samples: Int)

    /**
     * Places worth saving, learned from past fixes: where the phone sleeps (most fixes
     * between 11 PM and 6 AM) is likely home; where it spends weekday working hours,
     * likely work. Clusters within [clusterM] count as one place.
     */
    fun suggest(fixes: List<GeoFix>, known: List<Place>, zone: ZoneId = ZoneId.systemDefault(), clusterM: Double = 400.0): List<Suggestion> {
        data class C(var lat: Double, var lon: Double, var n: Int, var night: Int, var work: Int)
        val clusters = ArrayList<C>()
        for (f in fixes) {
            if (f.accuracyM > 1_500) continue
            val t = Instant.ofEpochMilli(f.at).atZone(zone)
            val night = t.hour >= 23 || t.hour < 6
            val work = t.dayOfWeek.value <= 5 && t.hour in 10..16
            val c = clusters.firstOrNull { meters(it.lat, it.lon, f.lat, f.lon) <= clusterM }
            if (c == null) clusters.add(C(f.lat, f.lon, 1, if (night) 1 else 0, if (work) 1 else 0))
            else { c.lat = (c.lat * c.n + f.lat) / (c.n + 1); c.lon = (c.lon * c.n + f.lon) / (c.n + 1); c.n++; if (night) c.night++; if (work) c.work++ }
        }
        val fresh = clusters.filter { c -> known.none { meters(c.lat, c.lon, it.lat, it.lon) <= it.radiusM + clusterM } }
        val out = ArrayList<Suggestion>()
        fresh.filter { it.night >= 3 }.maxByOrNull { it.night }?.let { out.add(Suggestion("home", it.lat, it.lon, it.night)) }
        fresh.filter { it.work >= 3 && out.none { s -> meters(s.lat, s.lon, it.lat, it.lon) <= clusterM } }.maxByOrNull { it.work }?.let { out.add(Suggestion("work", it.lat, it.lon, it.work)) }
        return out
    }

    fun km(m: Double) = if (m < 10_000) "%.1f km".format(m / 1_000) else "${(m / 1_000).toInt()} km"

    /** Rough bearing word for "85 km north of Home". */
    fun direction(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): String {
        val b = (Math.toDegrees(atan2(
            sin(Math.toRadians(toLon - fromLon)) * cos(Math.toRadians(toLat)),
            cos(Math.toRadians(fromLat)) * sin(Math.toRadians(toLat)) - sin(Math.toRadians(fromLat)) * cos(Math.toRadians(toLat)) * cos(Math.toRadians(toLon - fromLon)),
        )) + 360) % 360
        return listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")[((b + 22.5) / 45).toInt() % 8]
    }
}
