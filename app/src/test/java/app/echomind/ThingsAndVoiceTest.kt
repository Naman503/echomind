package app.echomind

import app.echomind.brain.Things
import app.echomind.brain.VoiceCheck
import app.echomind.data.EntityRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ThingsAndVoiceTest {
    private fun e(id: Long, kind: String, name: String, aliases: String = "[]") = EntityRow(id = id, kind = kind, name = name, aliases = aliases, firstSeenAt = 0, lastSeenAt = 0)

    @Test fun namesMatchAcrossSpellingAndScript() {
        val known = listOf(e(1, "show", "Panchayat"), e(2, "tech", "Kotlin"), e(3, "place", "Koramangala"), e(4, "movie", "Dune"))
        assertEquals(1L, Things.match("पंचायत", "show", known)?.id)
        assertEquals(1L, Things.match("Panchayath", "movie", known)?.id) // movie/show confusion
        assertEquals(3L, Things.match("Kormangala", "place", known)?.id)
        assertEquals(2L, Things.match("kotlin", "product", known)?.id)
        assertNull(Things.match("Kotlin", "place", known)) // different kind group
        assertNull(Things.match("Dunk", "movie", known)) // short names must match exactly
    }

    @Test fun kindsAndStatus() {
        assertEquals("org", Things.kindOf("Company"))
        assertEquals("other", Things.kindOf("weird"))
        assertEquals("want", Things.status("done", "want"))
        assertEquals("done", Things.status("done", "none"))
        assertEquals("Want to watch", Things.statusLabel("show", "want"))
    }

    @Test fun picksAResultOfTheRightType() {
        val r = listOf("Q1" to "village in Uttar Pradesh", "Q2" to "2020 Indian web series")
        assertEquals("Q2", Things.pick("show", r)?.first)
        assertEquals("Q1", Things.pick("place", r)?.first)
        assertNull(Things.pick("book", r))
    }

    @Test fun thresholdSitsBetweenYouAndOthers() {
        val rnd = Random(7)
        val others = List(150) { (0.22 + rnd.nextGaussian() * 0.08).toFloat() }
        val me = List(90) { (0.74 + rnd.nextGaussian() * 0.07).toFloat() }
        val t = VoiceCheck.Calib.threshold(others + me, 0.55f)
        assertNotNull(t)
        assertTrue("$t", t!! in 0.47f..0.63f)
        // Only one group (e.g. only your voice): no change.
        assertNull(VoiceCheck.Calib.threshold(me + me, 0.55f))
        assertNull(VoiceCheck.Calib.threshold(me.take(10), 0.55f))
    }

}
