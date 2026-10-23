package app.echomind

import app.echomind.brain.SpeechStats
import app.echomind.brain.SpeechStats.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechStatsTest {
    private fun l(id: Long, who: String, text: String, startS: Int, endS: Int) = Line(id, who, text, startS * 1000L, endS * 1000L)

    @Test fun countsFillersInEnglishHindiAndPhrases() {
        val f = SpeechStats.fillersIn("Um, basically you know matlab मतलब it's fine")
        assertEquals(1, f["um"]); assertEquals(1, f["basically"]); assertEquals(1, f["you know"]); assertEquals(1, f["matlab"]); assertEquals(1, f["मतलब"])
    }

    @Test fun restartsAndStutters() {
        assertTrue(SpeechStats.isRestart("मैं 40 डे दूंगा तुम सो डे दो", "क्या हमें 40 डे दूंगा तुम सो डे दो"))
        assertTrue(SpeechStats.isRestart("I will send the file", "I will send it tomorrow"))
        assertFalse(SpeechStats.isRestart("Book the cab", "What time is it"))
        assertEquals(1, SpeechStats.stuttersIn("I I think so"))
    }

    @Test fun analyzeOwnerAndSpeakers() {
        val lines = listOf(
            l(1, "me", "Um I I think we should go basically", 0, 4),
            l(2, "me", "I think we should go by auto", 5, 8),
            l(3, "S1", "Okay, let's book it", 9, 11),
            l(4, "me", "Is it cheaper?", 12, 13),
            l(5, "Song", "la la la la", 14, 20),
        )
        val r = SpeechStats.analyze(lines)
        assertEquals(18, r.words)
        assertEquals(1, r.restarts)
        assertEquals(1, r.stutters)
        assertEquals(1, r.questions)
        assertEquals(2, r.turns)
        assertTrue(1L in r.hesitant && 2L in r.hesitant)
        assertEquals(listOf("me", "S1"), r.bySpeaker.map { it.speaker })
    }
}
