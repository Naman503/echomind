package app.echomind

import app.echomind.brain.NotePatch
import app.echomind.brain.TextNorm
import app.echomind.brain.Vectors
import app.echomind.brain.When
import app.echomind.calls.CallImporter
import app.echomind.stt.VoiceId
import app.echomind.stt.Word
import app.echomind.stt.groupWords
import app.echomind.util.Js
import app.echomind.util.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TextNormTest {
    @Test fun `hindi and latin spellings of a name meet`() {
        val a = TextNorm.searchable("राहुल")
        val b = TextNorm.searchable("Raahul")
        val c = TextNorm.searchable("Rahul")
        assertEquals(c, b)
        assertEquals(c, a)
    }

    @Test fun `inherent vowel is kept inside words`() {
        assertEquals("kal", TextNorm.transliterate("कल"))
        assertEquals("namaste", TextNorm.transliterate("नमस्ते"))
    }

    @Test fun `fts query drops stop words and uses prefixes`() {
        val q = TextNorm.ftsQuery("What did Rahul say about the invoice?")!!
        assertTrue(q.contains("searchText:rahul*"))
        assertTrue(q.contains("searchText:invoice*") || q.contains("searchText:invoice"))
        assertTrue(!q.contains("about"))
    }

    @Test fun `devanagari words are counted whole`() = assertEquals(3, TextNorm.words("मुझे कल याद"))

    @Test fun `empty query gives no fts`() = assertNull(TextNorm.ftsQuery("what did i say"))
}

class WhenTest {
    private val zone = ZoneId.systemDefault()
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()
    private val wednesday3pm = at(2026, 10, 21, 15)

    @Test fun `kal shaam 5 baje is tomorrow 17h`() {
        val r = When.parse("kal shaam 5 baje", wednesday3pm)!!
        assertEquals(at(2026, 10, 22, 17), r.at)
        assertTrue(r.hasTime)
    }

    @Test fun `friday morning`() {
        val r = When.parse("Friday morning", wednesday3pm)!!
        assertEquals(at(2026, 10, 23, 9), r.at)
    }

    @Test fun `bare 4 means afternoon`() {
        val r = When.parse("at 4", wednesday3pm)
        // "at 4" has no am/pm and no clock marker, so it is not treated as a time.
        assertNull(r)
        assertEquals(at(2026, 10, 21, 16), When.parse("4 baje", wednesday3pm)!!.at)
    }

    @Test fun `phrase wins over a wrong model weekday`() {
        // Model said Thursday; the phrase says Friday.
        val r = When.resolve("2026-10-22T10:00", "Friday 10am", wednesday3pm)!!
        assertEquals(at(2026, 10, 23, 10), r.at)
    }

    @Test fun `model time is used when the phrase has only a day`() {
        val r = When.resolve("2026-10-22T11:30", "tomorrow", wednesday3pm)!!
        assertEquals(at(2026, 10, 22, 11, 30), r.at)
    }

    @Test fun `devanagari phrase`() {
        val r = When.parse("कल शाम 5 बजे", wednesday3pm)!!
        assertEquals(at(2026, 10, 22, 17), r.at)
    }

    @Test fun `punctuation does not hide words`() {
        assertEquals(at(2026, 10, 22, 9), When.parse("tomorrow, morning!", wednesday3pm)!!.at)
    }

    @Test fun `implausible model dates are ignored`() = assertNull(When.resolve("2019-01-01T10:00", "", wednesday3pm))
}

class NotePatchTest {
    private val note = """
        ## Plan
        - Launch on Thursday
        - Budget 2 lakh
        ## People
        - Priya handles design
    """.trimIndent()

    @Test fun `update replaces the matching bullet`() {
        val out = NotePatch.apply(note, listOf(NotePatch.Edit("update", "Plan", "launch thursday", "Launch moved to Friday")), emptySet())
        assertTrue(out.contains("- Launch moved to Friday"))
        assertTrue(!out.contains("Launch on Thursday"))
    }

    @Test fun `add goes under its section`() {
        val out = NotePatch.apply(note, listOf(NotePatch.Edit("add", "People", "", "Rahul reviews the code")), emptySet())
        val lines = out.lines()
        assertTrue(lines.indexOf("- Rahul reviews the code") > lines.indexOf("## People"))
    }

    @Test fun `new section is created`() {
        val out = NotePatch.apply(note, listOf(NotePatch.Edit("add", "Risks", "", "Vendor may be late")), emptySet())
        assertTrue(out.contains("## Risks\n- Vendor may be late"))
    }

    @Test fun `user lines are never removed`() {
        val out = NotePatch.apply(note, listOf(NotePatch.Edit("remove", "", "budget 2 lakh", "")), setOf("- Budget 2 lakh"))
        assertTrue(out.contains("- Budget 2 lakh"))
    }

    @Test fun `duplicate adds are ignored`() {
        val out = NotePatch.apply(note, listOf(NotePatch.Edit("add", "Plan", "", "Budget 2 lakh")), emptySet())
        assertEquals(1, out.lines().count { it.contains("Budget 2 lakh") })
    }
}

class JsonRepairTest {
    @Test fun `fenced json parses`() = assertEquals("x", Js.parseLenient("```json\n{\"a\":\"x\"}\n```")!!.str("a"))

    @Test fun `truncated json is closed`() {
        val o = Js.parseLenient("{\"title\":\"Budget call\",\"tasks\":[{\"title\":\"Send the dr")
        assertNotNull(o)
        assertEquals("Budget call", o!!.str("title"))
    }

    @Test fun `prose around json is ignored`() = assertEquals("1", Js.parseLenient("Sure! Here it is: {\"a\":\"1\"} hope it helps")!!.str("a"))
}

class CallNameTest {
    @Test fun `vivo name with spam prefix`() {
        val p = CallImporter.parse("(⛔ Spam Alert From Jio) Anil Bhuriya  2026-01-09 13-04-34.m4a")
        assertEquals("Anil Bhuriya", p.contact)
        assertTrue(p.spam)
        assertNotNull(p.at)
    }

    @Test fun `plain vivo name`() {
        val p = CallImporter.parse("Dipika Pathak 2026-10-20 22-10-16.m4a")
        assertEquals("Dipika Pathak", p.contact)
        assertEquals(LocalDateTime.of(2026, 10, 20, 22, 10, 16).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), p.at)
    }

    @Test fun `compact timestamp`() {
        val p = CallImporter.parse("Rahul_20261020221016.m4a")
        assertEquals("Rahul", p.contact)
        assertNotNull(p.at)
    }
}

class SpeakerTest {
    @Test fun `words group into lines by speaker and pause`() {
        val lines = groupWords(
            listOf(
                Word("Hello", 0.0, 0.4, "speaker_0"), Word("there", 0.45, 0.8, "speaker_0"),
                Word("Hi", 1.0, 1.2, "speaker_1"),
                Word("so", 3.0, 3.1, "speaker_1"),
            ),
            offsetMs = 10_000, lang = "en",
        )
        assertEquals(3, lines.size)
        assertEquals("Hello there", lines[0].text)
        assertEquals("S1", lines[0].speaker)
        assertEquals("S2", lines[1].speaker)
        assertEquals(10_000L, lines[0].startMs)
    }

    @Test fun `voice prints cluster`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val a2 = floatArrayOf(0.95f, 0.1f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        val k = VoiceId.cluster(listOf(a, b, a2, null))
        assertEquals(k[0], k[2])
        assertTrue(k[0] != k[1])
        assertNull(k[3])
    }

    @Test fun `int8 vectors keep cosine ranking`() {
        val q = floatArrayOf(0.2f, 0.9f, -0.1f)
        val near = Vectors.quantize(floatArrayOf(0.25f, 0.85f, -0.05f)).first
        val far = Vectors.quantize(floatArrayOf(-0.9f, 0.1f, 0.3f)).first
        assertTrue(Vectors.cosine(q, near) > Vectors.cosine(q, far))
    }
}
