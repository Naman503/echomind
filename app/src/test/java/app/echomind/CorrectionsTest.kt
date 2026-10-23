package app.echomind

import app.echomind.brain.Corrections
import org.junit.Assert.assertEquals
import org.junit.Test

class CorrectionsTest {
    private val rules = Corrections.parse(listOf("not taking => note-taking", "Eazy box → Eazeebox", "bad line", "x => x"))

    @Test fun parsesBothArrowsAndSkipsJunk() { assertEquals(2, rules.size) }

    @Test fun replacesWholeWordsAnyCase() {
        assertEquals("the note-taking application", Corrections.apply("the Not Taking application", rules))
        assertEquals("I work at Eazeebox", Corrections.apply("I work at eazy box", rules))
        assertEquals("nothing here", Corrections.apply("nothing here", rules))
        assertEquals("knot takings", Corrections.apply("knot takings", rules))
    }
}
