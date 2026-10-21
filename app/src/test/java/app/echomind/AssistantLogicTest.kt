package app.echomind

import app.echomind.tts.Speakable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakableTest {
    @Test fun `markdown becomes speech`() {
        val s = Speakable.clean("## Plan\n- [ ] Call **Priya** [2]\n- [x] Send deck\n---\nSee https://x.y/z")
        assertTrue(s.contains("Plan"))
        assertTrue(s.contains("To do: Call Priya"))
        assertTrue(s.contains("Done: Send deck"))
        assertTrue(s.contains("a link"))
        assertTrue(!s.contains("**") && !s.contains("[2]"))
    }

    @Test fun `language by script`() {
        assertEquals("hi", Speakable.language("आज तीन काम बाकी हैं"))
        assertEquals("en", Speakable.language("You have three tasks"))
        assertEquals("en", Speakable.language("kal meeting hai"))
    }

    @Test fun `long text splits into sentences`() {
        val parts = Speakable.sentences("First one. Second one! " + "word ".repeat(80))
        assertEquals("First one.", parts[0])
        assertTrue(parts.all { it.length <= 221 })
    }
}
