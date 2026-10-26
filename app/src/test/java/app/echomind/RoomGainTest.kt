package app.echomind

import app.echomind.capture.RoomGain
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class RoomGainTest {
    private val rnd = Random(3)

    /** 80 ms frames: [speechDb] tone bursts (2 s on, 1 s off) over [noiseDb] noise. */
    private fun frames(seconds: Int, speechDb: Double, noiseDb: Double): List<ShortArray> {
        val sa = 10.0.pow(speechDb / 20) * 32768 * sqrt(2.0); val na = 10.0.pow(noiseDb / 20) * 32768
        var t = 0
        return List(seconds * 1000 / 80) { f ->
            val on = (f * 80 / 1000) % 3 != 2
            ShortArray(1280) { (if (on) sa * sin(2 * PI * 220 * (t++) / 16000.0) else { t++; 0.0 } + rnd.nextGaussian() * na).toInt().coerceIn(-32768, 32767).toShort() }
        }
    }
    private fun Double.pow(p: Double) = Math.pow(this, p)
    private fun db(f: ShortArray) = 20 * log10(max(sqrt(f.sumOf { it.toDouble() * it } / f.size) / 32768, 1e-5))

    @Test fun liftsDistantSpeechTowardsTheTarget() {
        val g = RoomGain()
        val out = frames(20, -46.0, -66.0).map { it.copyOf().also(g::process) }
        val loud = out.takeLast(60).map(::db).filter { it > -50 }
        val avg = loud.average()
        assertTrue("speech now at $avg dB", avg in -34.0..-20.0)
    }

    @Test fun neverClipsALoudVoice() {
        val g = RoomGain(maxGainDb = 30f)
        frames(10, -50.0, -70.0).forEach { g.process(it.copyOf()) } // build up gain on quiet speech
        val loud = frames(3, -6.0, -70.0)
        loud.forEach { f -> val c = f.copyOf(); g.process(c); assertTrue(c.all { it.toInt() in -32767..32766 }) }
        // The output stays below full scale even though gain was high when the loud voice came.
        val peakDb = loud.map { f -> f.copyOf().also(g::process) }.maxOf(::db)
        assertTrue("peak $peakDb", peakDb < -1.0)
    }

    @Test fun keepsAQuietRoomQuiet() {
        val g = RoomGain()
        val out = frames(10, -120.0, -58.0).map { it.copyOf().also(g::process) }
        assertTrue("noise now ${out.takeLast(20).map(::db).average()}", out.takeLast(20).map(::db).average() < -38)
    }
}

class MicTestTest {
    private fun r(m: String, loud: Float, floor: Float) = app.echomind.capture.MicTest.Result(m, loud, floor)

    @Test fun picksByClarityOnlyWhenVoicesWereHeard() {
        // What the test measured on the phone in a quiet room: nothing to judge by voice.
        val quiet = listOf(r("recognition", -67f, -71f), r("room", -48f, -51f), r("standard", -50f, -53f))
        org.junit.Assert.assertNull(app.echomind.capture.MicTest.best(quiet))
        val (l, gap) = app.echomind.capture.MicTest.louder(quiet, "recognition")!!
        org.junit.Assert.assertEquals("room", l.mode); assertTrue(gap in 19f..21f)
        // Someone talking: the clearest wins.
        val talk = listOf(r("recognition", -45f, -71f), r("room", -30f, -51f), r("standard", -33f, -53f))
        org.junit.Assert.assertEquals("recognition", app.echomind.capture.MicTest.best(talk)?.mode)
    }
}
