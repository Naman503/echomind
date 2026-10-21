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

    /** Singing-family sums per 10 s slice, measured on the owner's real recordings of "O Rangrez" (22 Oct 2026). */
    private fun run(vararg sums: Float) = sums.mapIndexed { i, v ->
        Sounds.Slice(i * 10_000L, (i + 1) * 10_000L, if (v > 0) listOf("Male singing" to v) else listOf("Speech" to 0.7f, "Silence" to 0.2f))
    }

    @Test fun `real singing becomes one song each time`() {
        // 10:23 PM — loud singing for ~90 s, then talk.
        val a = Sounds.songSpans(run(.97f, .69f, .82f, .68f, .57f, .59f, .49f, .35f, .62f, .18f, 0f, 0f))
        assertEquals(1, a.size); assertEquals(0L, a[0].first); assertTrue(a[0].second >= 90_000)
        // 10:35 PM — a short line sung between silence.
        val b = Sounds.songSpans(run(0f, 0f, 0f, 0f, .51f, .62f, 0f, 0f))
        assertEquals(listOf(40_000L to 60_000L), b.map { it.first to it.second })
        // 12:45 AM — sung softly: one strong slice carries the quieter ones before it.
        val c = Sounds.songSpans(run(.22f, .31f, .60f, .04f, 0f, 0f, 0f))
        assertEquals(listOf(0L to 30_000L), c.map { it.first to it.second })
    }

    @Test fun `talk with a hum is not a song`() {
        assertTrue(Sounds.songSpans(run(0f, 0f, 0f)).isEmpty())
        assertTrue(Sounds.songSpans(run(.26f, .2f, 0f)).isEmpty())      // speech + humming never starts a song
        assertTrue(Sounds.songSpans(run(0f, .40f, 0f)).isEmpty())       // one weak 10 s blip is too short
    }

    @Test fun `heard Hindi lyrics match the song's romanised lyrics`() {
        // What the phone transcribed on 21 Oct, and the start of the real lyrics (LRCLIB).
        val heard = "ओ रंग रे तेरे। रंग दरिया में ओ रंगरेज तेरे रंग दरिया! अपने ही रंग में हो अपने ही अंग में खुद को रंग"
        val orangrez = "Oh, rangrez, tere rang dariya mein. Doobna hai bas tera ban ke. Apne hi rang mein mujhko rang de"
        val other = "Tujhe kitna chahne lage hum, itna kisi ko chaha na ho"
        assertTrue(Sounds.lyricOverlap(heard, orangrez) > 0.4f)
        assertTrue(Sounds.lyricOverlap(heard, other) < 0.15f)
        // A song that shares only filler words ("mein", "tere", "rang") must not win.
        val filler = "Rang mein tere, tere rang mein, kinna sona tera rang ve, sohna sohna mukhda"
        assertTrue(Sounds.lyricOverlap(heard, filler) < 0.3f)
        assertTrue(Sounds.lyricOverlap(heard, orangrez) > Sounds.lyricOverlap(heard, filler) + 0.3f)
        assertTrue(Sounds.titleInLyrics("O Rangrez", heard) >= 0.5f)
        assertTrue(Sounds.titlePhrases("apne hi rang mein apne hi rang mein khud ko rang de").first().contains("rang"))
    }

    /** Overlaps measured against real LRCLIB lyrics for the owner's two recordings. */
    @Test fun `the closest lyrics win only when clearly closest`() {
        fun m(t: String, o: Float) = Sounds.SongMatch(t, "", "", null, null, null, null, overlap = o)
        assertEquals("O Rangrez", Sounds.pickSong(listOf(m("Rangrez", 0.08f), m("O Rangrez", 0.29f), m("Rangrez (Krsna)", 0.07f), m("Kinna Sona", 0.02f)))?.title)
        assertNull(Sounds.pickSong(listOf(m("Kinna Sona", 0.30f), m("Deewana Kar Raha Hai", 0.24f), m("O Rangrez", 0.19f))))
    }
}
