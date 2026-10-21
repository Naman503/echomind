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
}
