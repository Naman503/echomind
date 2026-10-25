package app.echomind

import app.echomind.brain.GeoFix
import app.echomind.brain.Place
import app.echomind.brain.WeatherDay
import app.echomind.brain.WeatherHour
import app.echomind.brain.Whereabouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WhereaboutsTest {
    private val z = ZoneId.of("Asia/Kolkata")
    private fun t(h: Int, m: Int = 0) = LocalDate.of(2026, 10, 24).atTime(h, m).atZone(z).toInstant().toEpochMilli()
    private val home = Place("h", "Home", "home", 12.9784, 77.6408)
    private val office = Place("w", "Office", "work", 12.9698, 77.7500)

    // Home 7:00–9:30 (every 15 min), drive, office 10:15–18:30, one stray reading at 13:00.
    private val fixes = buildList {
        for (m in 0..150 step 15) add(GeoFix(12.9785, 77.6409, t(7) + m * 60_000L, 40f))
        add(GeoFix(12.975, 77.70, t(9, 50), 100f))
        for (m in 0..495 step 15) add(GeoFix(12.9699, 77.7501, t(10, 15) + m * 60_000L, 40f))
    }.toMutableList().also { it.add(GeoFix(12.9900, 77.7300, t(13, 0) + 30_000, 60f)) }

    @Test fun findsStaysAndIgnoresAStrayReading() {
        val v = Whereabouts.visits(fixes)
        assertEquals(2, v.size)
        assertTrue(v[1].minutes >= 480)
    }

    @Test fun labelsAndStory() {
        val v = Whereabouts.visits(fixes).map { Whereabouts.label(it, listOf(home, office)) }
        assertEquals(listOf("Home", "Office"), v.map { it.name })
        val s = Whereabouts.story(v, fixes, z)!!
        assertTrue(s, s.startsWith("Home until 9:30 AM → Office from 10:15 AM"))
        assertTrue(s, s.contains("km travelled"))
    }

    @Test fun whichPlaceAConversationWasAt() {
        val v = Whereabouts.visits(fixes).map { Whereabouts.label(it, listOf(home, office)) }
        assertEquals("Office", Whereabouts.at(v, t(15))!!.name)
        assertNull(Whereabouts.at(v, t(22)))
    }

    @Test fun weatherInWords() {
        val hours = (0..23).map { h -> WeatherHour(h, 22f + (if (h in 12..16) 6 else 0), if (h in 16..17) 2f else 0f, if (h in 16..17) 63 else 1) }
        val line = Whereabouts.weatherLine(WeatherDay("2026-10-24", 0.0, 0.0, hours, 0))
        assertNotNull(line)
        assertTrue(line, line!!.startsWith("22–28°C, mostly clear; rain around 4 PM–6 PM (4.0 mm)"))
    }
}
