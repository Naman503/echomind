package app.echomind

import app.echomind.brain.Actions
import app.echomind.brain.Sounds
import app.echomind.brain.Web
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacySoundsTest {
    @Test fun `web queries lose names numbers and emails`() {
        val names = setOf("priya", "rahul")
        assertEquals("birthday gift ideas for", Web.clean("birthday gift ideas for Priya", names))
        assertEquals("call about the invoice", Web.clean("call rahul@acme.com about the invoice +91 98765 43210", names))
        assertEquals("pnr status check", Web.clean("pnr 4521983345 status check", names))
        assertNull(Web.clean("Rahul Priya", names))
    }

    @Test fun `page text keeps paragraphs and drops scripts`() {
        val html = "<html><script>var x=1</script><nav>menu</nav><p>The quick brown fox jumps over the lazy dog, again and again.</p><p>Second paragraph is long enough to count as text here.</p><p>Third paragraph is also long enough to be kept by the reader.</p></html>"
        val text = Web.pageText(html)
        assertTrue(text.contains("quick brown fox"))
        assertFalse(text.contains("var x"))
        assertFalse(text.contains("menu"))
    }

    @Test fun `surroundings win over talk when clearly there`() {
        fun s(vararg e: Pair<String, Float>) = Sounds.Slice(0, 10_000, e.toList())
        assertEquals("On the road", Sounds.activity(s("Speech" to 0.8f, "Vehicle horn, car horn, honking" to 0.4f)))
        assertEquals("Talking", Sounds.activity(s("Speech" to 0.8f, "Vehicle" to 0.1f)))
        assertEquals("Kitchen", Sounds.activity(s("Dishes, pots, and pans" to 0.5f)))
        assertEquals("Music", Sounds.activity(s("Music of Bollywood" to 0.6f)))
        assertNull(Sounds.activity(s("Speech" to 0.1f)))
    }

    @Test fun `settings changes count as instructions`() {
        assertTrue(Actions.looksLikeInstruction("turn on surroundings"))
        assertTrue(Actions.looksLikeInstruction("keep recordings for 30 days"))
        assertTrue(Actions.looksLikeInstruction("read faster please"))
        assertTrue(Actions.SETTINGS.containsKey("speaker_policy"))
    }

    /** Scores the tagger gave real recordings on the Mac (sherpa-onnx 1.13.8, CED tiny): the song rule must hold for them. */
    @Test fun `songs are told apart from talk and alarms`() {
        fun s(vararg e: Pair<String, Float>) = Sounds.Slice(0, 10_000, e.toList())
        assertTrue(s("Male singing" to 0.49f, "Mantra" to 0.38f, "Lullaby" to 0.14f).songish())       // solo folk singer
        assertTrue(s("Choir" to 0.58f, "Music" to 0.41f, "A capella" to 0.37f).songish())           // choir
        assertTrue(s("Mantra" to 0.27f, "Male singing" to 0.13f, "Lullaby" to 0.13f).songish())     // quiet singing
        assertTrue(s("A capella" to 0.72f, "Singing" to 0.53f, "Vocal music" to 0.49f).songish())
        assertFalse(s("Speech synthesizer" to 0.87f, "Speech" to 0.53f).songish())                  // talk
        assertFalse(s("Speech" to 0.36f, "Female speech, woman speaking" to 0.09f).songish())        // quiet talk
        assertFalse(s("Smoke detector, smoke alarm" to 0.78f, "Music" to 0.42f).songish())          // alarm
        assertFalse(s("Whistling" to 0.80f, "Music" to 0.35f, "Piano" to 0.17f).songish())
        assertTrue(s("Music of Bollywood" to 0.7f, "Music" to 0.6f).songish())                      // a song playing
    }
}
