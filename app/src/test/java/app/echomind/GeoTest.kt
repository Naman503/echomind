package app.echomind

import app.echomind.brain.GeoFix
import app.echomind.brain.Geo
import app.echomind.brain.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class GeoTest {
    // Bengaluru: home in Indiranagar, office in Whitefield (~12 km apart), parents' home in Mysuru (~140 km).
    private val home = Place("h", "Home", "home", 12.9784, 77.6408)
    private val work = Place("w", "Office", "work", 12.9698, 77.7500)
    private val parents = Place("p", "Parents", "home", 12.2958, 76.6394, radiusM = 2_000)
    private val places = listOf(home, work, parents)
    private fun fix(lat: Double, lon: Double, minutes: Long = 0, acc: Float = 50f) = GeoFix(lat, lon, minutes * 60_000L, acc)

    @Test fun distances() {
        val d = Geo.meters(home.lat, home.lon, work.lat, work.lon)
        assertTrue(d in 11_000.0..13_000.0)
    }

    @Test fun atHomeAtWorkAndAtSecondHome() {
        assertTrue(Geo.classify(fix(12.9790, 77.6410), places, 30.0, 2.0) is Geo.Where.At)
        assertTrue(Geo.classify(fix(12.2960, 76.6400), places, 30.0, 2.0) is Geo.Where.At)
    }

    @Test fun onTheWayToWorkIsNotTravel() {
        // Halfway between home and office, a few hundred metres off the straight line.
        assertTrue(Geo.classify(fix(12.9770, 77.6950), places, 30.0, 2.0) is Geo.Where.Commuting)
    }

    @Test fun theDriveToParentsIsATrip() {
        // Halfway between Bengaluru and Mysuru: on the line between two homes, but far from both.
        assertTrue(Geo.classify(fix(12.64, 77.14), places, 30.0, 2.0) is Geo.Where.Away)
    }

    @Test fun theGymIsNearbyNotAway() {
        assertTrue(Geo.classify(fix(13.0350, 77.5970), places, 30.0, 2.0) is Geo.Where.Nearby)
    }

    @Test fun goaIsAway() {
        val w = Geo.classify(fix(15.4909, 73.8278), places, 30.0, 2.0)
        assertTrue(w is Geo.Where.Away)
        assertTrue((w as Geo.Where.Away).meters > 400_000)
    }

    @Test fun poorFixGetsBenefitOfDoubt() {
        // 3 km from home but the fix is only good to 2.5 km: counts as at home.
        assertTrue(Geo.classify(fix(13.0050, 77.6408, acc = 2_500f), places, 30.0, 2.0) is Geo.Where.At)
    }

    @Test fun travelNeedsTwoAwayReadingsOrSpeed() {
        val away1 = fix(15.49, 73.83, 0); val away2 = fix(15.50, 73.84, 30)
        val w = { f: GeoFix -> Geo.classify(f, places, 30.0, 2.0) }
        assertFalse(Geo.confirmsTravel(listOf(away1 to w(away1))))
        assertTrue(Geo.confirmsTravel(listOf(away1 to w(away1), away2 to w(away2))))
        // Moving fast: Bengaluru outskirts to 100 km away in an hour.
        val a = fix(13.10, 77.60, 0); val b = fix(13.95, 77.60, 60)
        assertTrue(Geo.confirmsTravel(listOf(a to w(a), b to w(b))))
    }

    @Test fun learnsHomeFromNights() {
        val z = ZoneId.of("Asia/Kolkata")
        fun at(day: Int, hour: Int, lat: Double, lon: Double) = GeoFix(lat, lon, LocalDateTime.of(2026, 10, day, hour, 0).atZone(z).toInstant().toEpochMilli(), 100f)
        val fixes = (19..23).flatMap { d -> listOf(at(d, 1, 12.93, 77.62), at(d, 2, 12.9302, 77.6201), at(d, 11, 12.99, 77.70), at(d, 14, 12.9901, 77.7002)) }
        val s = Geo.suggest(fixes, emptyList(), z)
        assertEquals(listOf("home", "work"), s.map { it.kind })
    }

    @Test fun directionWords() {
        assertEquals("north-west", Geo.direction(home.lat, home.lon, 15.49, 73.83))
    }
}
