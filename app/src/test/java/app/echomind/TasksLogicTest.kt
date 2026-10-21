package app.echomind

import app.echomind.brain.QuickAdd
import app.echomind.brain.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class QuickAddTest {
    private val zone = ZoneId.systemDefault()
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()
    private val wed3pm = at(2026, 10, 21, 15)

    @Test fun `full syntax`() {
        val p = QuickAdd.parse("Call mom tomorrow 6pm #family !1 @phone every week", wed3pm)
        assertEquals("Call mom", p.title)
        assertEquals(at(2026, 10, 22, 18), p.due!!.at)
        assertEquals("family", p.list)
        assertEquals(1, p.priority)
        assertEquals(listOf("phone"), p.labels)
        assertEquals("weekly", p.recurrence)
        assertTrue(p.remind)
    }

    @Test fun `remind me phrasing`() {
        val p = QuickAdd.parse("remind me to pay the electricity bill friday 5pm", wed3pm)
        assertEquals("Pay the electricity bill", p.title)
        assertEquals(at(2026, 10, 23, 17), p.due!!.at)
        assertTrue(p.remind)
    }

    @Test fun `hinglish`() {
        val p = QuickAdd.parse("doctor ko call karna kal shaam 5 baje", wed3pm)
        assertEquals(at(2026, 10, 22, 17), p.due!!.at)
        assertTrue(p.title.startsWith("Doctor ko call karna"))
    }

    @Test fun `plain task has no date`() {
        val p = QuickAdd.parse("Buy 2 kg apples", wed3pm)
        assertEquals("Buy 2 kg apples", p.title)
        assertNull(p.due)
        assertEquals(4, p.priority)
    }

    @Test fun `repeat without date starts today`() {
        val p = QuickAdd.parse("Take vitamins every day", wed3pm)
        assertEquals("Take vitamins", p.title)
        assertEquals("daily", p.recurrence)
        assertNotNull(p.due)
    }
}

class RecurrenceTest {
    private val zone = ZoneId.systemDefault()
    private fun at(y: Int, m: Int, d: Int, h: Int) = LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun `weekdays skip the weekend`() {
        val fri = at(2026, 10, 23, 9)
        assertEquals(at(2026, 10, 26, 9), Recurrence.next("weekdays", fri, now = fri))
    }

    @Test fun `weekly on chosen days`() {
        val mon = at(2026, 10, 19, 8)
        assertEquals(at(2026, 10, 22, 8), Recurrence.next("weekly:MON,THU", mon, now = mon))
    }

    @Test fun `monthly clamps to month end`() {
        val jan31 = at(2027, 1, 31, 10)
        assertEquals(at(2027, 2, 28, 10), Recurrence.next("monthly:31", jan31, now = jan31))
    }

    @Test fun `late completion jumps to the next future slot`() {
        val oldDue = at(2026, 10, 1, 9)
        val now = at(2026, 10, 21, 15)
        assertEquals(at(2026, 10, 22, 9), Recurrence.next("daily", oldDue, now))
    }

    @Test fun `parse phrases`() {
        assertEquals("weekly:MON", Recurrence.parse("standup every monday")!!.first)
        assertEquals("every:3:days", Recurrence.parse("water plants every 3 days")!!.first)
        assertEquals("daily", Recurrence.parse("roz walk")!!.first)
    }
}
