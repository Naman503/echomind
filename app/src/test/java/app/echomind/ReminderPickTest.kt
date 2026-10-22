package app.echomind

import app.echomind.brain.Tasks
import app.echomind.brain.When
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderPickTest {
    private val zone = ZoneId.systemDefault()
    private fun ms(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()
    private val today = LocalDate.of(2026, 10, 22)
    private val now = ms(today.atTime(10, 0))
    private fun timed(d: LocalDate, h: Int, m: Int = 0) = When.Resolved(ms(d.atTime(h, m)), true)
    private fun dated(d: LocalDate) = When.Resolved(ms(d.atTime(When.DEFAULT_HOUR, 0)), false)

    @Test fun saidTimeWins() {
        val due = timed(today, 17)
        assertEquals(due.at, Tasks.pickReminder(due, timed(today, 15), askedToRemind = true, mine = true, smart = true, now = now))
    }

    @Test fun aiPickBeforeATimedTask() {
        // "Call the bank at 5" (not an explicit reminder): the AI's "an hour before" is used.
        val pick = timed(today, 16)
        assertEquals(pick.at, Tasks.pickReminder(timed(today, 17), pick, askedToRemind = false, mine = true, smart = true, now = now))
    }

    @Test fun aiPickAfterTheDueTimeIsIgnored() {
        val due = timed(today, 17)
        assertEquals(due.at, Tasks.pickReminder(due, timed(today, 19), askedToRemind = false, mine = true, smart = true, now = now))
    }

    @Test fun eveningBeforeForADatedTask() {
        val pick = timed(today, 20)
        assertEquals(pick.at, Tasks.pickReminder(dated(today.plusDays(1)), pick, askedToRemind = false, mine = true, smart = true, now = now))
    }

    @Test fun datedTaskWithoutAiPickGetsTheNextSlotOnItsDay() {
        assertEquals(ms(today.plusDays(2).atTime(9, 0)), Tasks.pickReminder(dated(today.plusDays(2)), null, false, true, true, now))
        // Today, after 9 AM: the 1 PM slot.
        assertEquals(ms(today.atTime(13, 0)), Tasks.pickReminder(dated(today), null, false, true, true, now))
    }

    @Test fun undatedTaskUsesOnlyTheAiPick() {
        val pick = timed(today, 18)
        assertEquals(pick.at, Tasks.pickReminder(null, pick, false, true, true, now))
        assertNull(Tasks.pickReminder(null, null, false, true, true, now))
    }

    @Test fun pastPicksAndOthersTasksGetNoReminder() {
        assertNull(Tasks.pickReminder(null, timed(today, 8), false, true, true, now))
        assertNull(Tasks.pickReminder(dated(today.plusDays(1)), timed(today, 20), askedToRemind = false, mine = false, smart = true, now = now))
    }

    @Test fun smartOffKeepsOnlySaidTimes() {
        assertNull(Tasks.pickReminder(dated(today.plusDays(1)), timed(today, 20), false, true, smart = false, now = now))
        assertEquals(ms(today.atTime(17, 0)), Tasks.pickReminder(timed(today, 17), null, false, true, smart = false, now = now))
    }
}
