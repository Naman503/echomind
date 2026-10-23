package app.echomind

import app.echomind.brain.Analytics
import app.echomind.brain.Analytics.DayPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AnalyticsTest {
    private val start = LocalDate.of(2026, 10, 1)
    private fun day(i: Int, talk: Int = 30, mood: Float? = 0f, people: Set<String> = emptySet(), late: Int = 0, prod: Int? = 50) =
        DayPoint(start.plusDays(i.toLong()), talkMin = talk, conversations = 2, people = people, mood = mood, productivity = prod, lateMin = late)

    @Test fun deltas() {
        assertEquals(50, Analytics.deltaPct(150.0, 100.0))
        assertEquals(-25, Analytics.deltaPct(75.0, 100.0))
        assertNull(Analytics.deltaPct(10.0, 0.0))
    }

    @Test fun influenceNeedsEnoughDaysAndLabelsConfidence() {
        // Rahul on 12 days with mood 0.6, without on 12 days with mood -0.2.
        val pts = (0 until 24).map { i -> if (i % 2 == 0) day(i, mood = 0.6f, people = setOf("Rahul")) else day(i, mood = -0.2f) }
        val inf = Analytics.influence(pts, "talking with Rahul", "mood", { "Rahul" in it.people }, { it.mood?.toDouble() })
        assertNotNull(inf)
        assertEquals(Analytics.Confidence.HIGH, inf!!.confidence)
        assertEquals(0.8, inf.diff, 1e-6)
        assertTrue(Analytics.sentence(inf).startsWith("You were happier on days of talking with Rahul"))
        // Two days with is not enough.
        assertNull(Analytics.influence(pts.take(4), "x", "mood", { "Rahul" in it.people }, { it.mood?.toDouble() }))
    }

    @Test fun nextDayLag() {
        // Late nights are followed by low-mood days.
        val pts = (0 until 20).map { i -> day(i, late = if (i % 2 == 0) 40 else 0, mood = if (i % 2 == 1) -0.5f else 0.5f) }
        val inf = Analytics.influence(pts, "late", "mood", { it.lateMin >= 15 }, { it.mood?.toDouble() }, lag = 1)!!
        assertTrue(inf.diff < 0)
        assertTrue(Analytics.sentence(inf).contains("the day after"))
    }

    @Test fun streaks() {
        val today = LocalDate.of(2026, 10, 20)
        val dates = setOf(today.minusDays(1), today.minusDays(2), today.minusDays(3), today.minusDays(6))
        assertEquals(3, Analytics.streak(dates, today))
        assertEquals(3, Analytics.longestStreak(dates))
    }

    @Test fun heatmapBucketsByWeekdayAndHour() {
        val z = ZoneId.of("Asia/Kolkata")
        val t = LocalDate.of(2026, 10, 19).atTime(10, 15).atZone(z).toInstant().toEpochMilli() // a Monday
        val g = Analytics.heatmap(listOf(t to t + 90_000, t + 100_000 to t + 190_000), z)
        assertEquals(3, g[0][10])
    }

    @Test fun headlineAndNoticed() {
        val prev = (0 until 7).map { day(it, talk = 20) }
        val cur = (7 until 14).map { day(it, talk = 40, prod = 80) }
        assertEquals("A talkative, productive stretch", Analytics.headline(cur, prev))
        val spike = (0 until 10).map { day(it, talk = 30) } + day(10, talk = 200)
        assertTrue(Analytics.noticed(spike)!!.text.contains("200 min"))
    }
}
