package app.echomind

import app.echomind.brain.Languages
import app.echomind.brain.Languages.Lang
import app.echomind.brain.Languages.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class LanguagesTest {
    @Test fun linesFromTheRealTranscripts() {
        assertEquals(Lang.HINDI, Languages.of("अरे भाई तुमकॊ बड़ा चास्का है यार।"))
        assertEquals(Lang.HINDI, Languages.of("Theek hai ye main pic kar Lunga."))
        assertEquals(Lang.ENGLISH, Languages.of("That it will, mark those things turn is just like an assistant thing,"))
        assertEquals(Lang.HINGLISH, Languages.of("Yaar the meeting is kal at office, tum aa jaana"))
        assertNull(Languages.of("12 34"))
    }

    @Test fun dayTotalsSwitchesAndPeople() {
        val z = ZoneId.of("Asia/Kolkata")
        fun t(h: Int, m: Int) = LocalDate.of(2026, 10, 25).atTime(h, m).atZone(z).toInstant().toEpochMilli()
        val lines = listOf(
            Line("me", "Theek hai yaar kal milte hain", t(10, 0), t(10, 0) + 10_000, 1),
            Line("Rahul", "Sure, I will send the deck tomorrow", t(10, 1), t(10, 1) + 8_000, 1),
            Line("me", "Please share the final numbers before lunch", t(10, 2), t(10, 2) + 6_000, 1),
            Line("me", "मैं शाम को कॉल करूंगा", t(18, 0), t(18, 0) + 4_000, 2),
        )
        val d = Languages.day(lines, z)
        assertEquals(14, d.mine.hindi)
        assertEquals(6, d.mine.english)
        assertEquals(8, d.others.english)
        assertEquals(1, d.switches)
        assertTrue(d.byPerson.containsKey("Rahul"))
        assertEquals(10, d.byHour[10].hindi)
        assertEquals(70, d.mine.pct(Lang.HINDI))
        assertTrue(d.topWords.any { it.first == "numbers" })
    }
}
